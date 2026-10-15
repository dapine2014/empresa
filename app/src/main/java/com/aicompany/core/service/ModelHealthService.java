package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.ModelHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Salud de los modelos remotos (spec 2026-09-28). Verificado en vivo: kimi-k3 dejó de responder en NVIDIA durante horas
 * y cada agente esperaba 10 minutos por intento. Con 2 fallos de disponibilidad seguidos el modelo queda DOWN, Forjai
 * deja de llamarlo (CeoService desvía al suplente local), se avisa al fundador y cada 30 s se prueba si volvió.
 * Estado en memoria: tras un reinicio arranca UP y se re-detecta con el primer fallo doble.
 */
@Service
public class ModelHealthService {

    private static final Logger log = LoggerFactory.getLogger(ModelHealthService.class);
    static final int FAILURES_TO_DOWN = 2;

    private record State(int failures, boolean down, Instant since, String lastError) {
        static final State UP = new State(0, false, null, null);
    }

    private final Map<String, OpenAiCompatibleClient> remotes;
    private final CompanyMemoryService companyMemory;
    private final CompanyEventPublisher events;
    private final AlertMailService mail;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public ModelHealthService(Map<String, OpenAiCompatibleClient> remotes, CompanyMemoryService companyMemory,
                              CompanyEventPublisher events, AlertMailService mail) {
        this.remotes = remotes;
        this.companyMemory = companyMemory;
        this.events = events;
        this.mail = mail;
    }

    // ---- Suplentes (decisión del fundador 2026-10-03; verificado en vivo: Ollama quedó caído 10 h tras un reinicio). ----

    private LocalModelProbe localModels;
    private final Map<String, State> fallbackStates = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLocalModelProbe(LocalModelProbe localModels) {
        this.localModels = localModels;
    }

    public boolean isFallbackDown(String model) {
        return fallbackStates.getOrDefault(model, State.UP).down();
    }

    /** Suplentes de los agentes con los agentes que los usan, ordenados. */
    private Map<String, List<String>> fallbacksInUse() {
        var byModel = new java.util.TreeMap<String, List<String>>();
        for (var agent : companyMemory.agents()) {
            var model = String.valueOf(agent.getOrDefault("fallbackModel", ""));
            if (!model.isBlank()) {
                byModel.computeIfAbsent(model, k -> new ArrayList<>()).add(String.valueOf(agent.get("id")));
            }
        }
        byModel.values().forEach(agents -> agents.sort(String::compareTo));
        return byModel;
    }

    /** Cada 60 s: un suplente que Ollama no sirve (caído o sin el modelo) 2 veces seguidas queda DOWN y se avisa. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void probeFallbacks() {
        if (localModels == null) {
            return;
        }
        var installed = localModels.installedModels();
        for (var entry : fallbacksInUse().entrySet()) {
            var model = entry.getKey();
            var available = installed.map(names -> names.contains(model)).orElse(false);
            if (available) {
                var cameBack = new boolean[1];
                var since = new Instant[1];
                fallbackStates.computeIfPresent(model, (k, s) -> {
                    cameBack[0] = s.down();
                    since[0] = s.since();
                    return State.UP;
                });
                if (cameBack[0]) {
                    announceFallbackUp(model, since[0]);
                }
                continue;
            }
            var error = installed.isEmpty() ? "Ollama no responde"
                    : "el modelo no está instalado en Ollama (ollama pull " + model + ")";
            var wentDown = new boolean[1];
            fallbackStates.compute(model, (k, s) -> {
                var current = s == null ? State.UP : s;
                if (current.down()) {
                    return new State(current.failures() + 1, true, current.since(), error);
                }
                var failures = current.failures() + 1;
                if (failures >= FAILURES_TO_DOWN) {
                    wentDown[0] = true;
                    return new State(failures, true, Instant.now(), error);
                }
                return new State(failures, false, null, error);
            });
            if (wentDown[0]) {
                announceFallbackDown(model, error, entry.getValue());
            }
        }
    }

    public List<ModelHealth> fallbackSnapshot() {
        return fallbacksInUse().entrySet().stream().map(e -> {
            var s = fallbackStates.getOrDefault(e.getKey(), State.UP);
            return new ModelHealth(e.getKey(), s.down() ? "DOWN" : "UP", s.since(), s.lastError(), e.getValue());
        }).toList();
    }

    private void announceFallbackDown(String model, String error, List<String> agents) {
        log.warn("FALLBACK_DOWN model={} error={} agents={}", model, error, agents);
        events.publish("EMPRESA_FALLBACK_DOWN", null, null, "system",
                Map.of("model", model, "error", error, "affectedAgents", agents));
        mail.send("⚠ Forjai: el suplente " + model + " no está disponible",
                "El suplente local " + model + " no está disponible: " + error + ".\n\nSi un modelo remoto se cae, estos "
                        + "agentes no tienen respaldo y sus tareas fallan:\n" + String.join(", ", agents)
                        + "\n\nRevisa el contenedor de Ollama (docker start ollama). Forjai lo prueba cada minuto y te avisa "
                        + "cuando vuelva.", true);
    }

    private void announceFallbackUp(String model, Instant since) {
        log.info("FALLBACK_UP model={} downSince={}", model, since);
        events.publish("EMPRESA_FALLBACK_UP", null, null, "system",
                Map.of("model", model, "downSince", since == null ? "" : since.toString()));
        mail.send("✅ Forjai: el suplente " + model + " volvió", "El suplente local " + model + " vuelve a estar "
                + "disponible (no lo estaba desde " + since + ").", false);
    }

    public boolean isDown(String model) {
        return states.getOrDefault(model, State.UP).down();
    }

    public Instant downSince(String model) {
        return states.getOrDefault(model, State.UP).since();
    }

    public String fallbackFor(String agentId) {
        return companyMemory.agentFallbackModel(agentId);
    }

    /** Decisión del fundador (2026-10-04): respaldo pago, antes del suplente local; "" = sin respaldo pago. */
    public String paidFallbackFor(String agentId) {
        return companyMemory.agentPaidFallbackModel(agentId);
    }

    // ---- Cadena de modelos gratis (decisión del fundador 2026-10-15). ----

    private FreeModelChainService freeChain;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setFreeModelChain(FreeModelChainService freeChain) {
        this.freeChain = freeChain;
    }

    /**
     * "Validar primero": cada 60 s un ping corto (20 s) a cada modelo de la cadena que no esté caído. Así un gratis
     * saturado queda DOWN (2 fallos) antes de que una misión lo espere minutos; la vuelta la detecta probeDownModels.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void probeFreeChain() {
        if (freeChain == null) {
            return;
        }
        for (var model : freeChain.models()) {
            if (isDown(model)) {
                continue;
            }
            var remote = CeoService.remoteModel(model).orElse(null);
            var client = remote == null ? null : remotes.get(remote.provider());
            if (client == null) {
                continue;
            }
            if (client.ping(remote.model())) {
                recordSuccess(model);
            } else {
                recordFailure(model, "no respondió el chequeo rápido (20 s)");
            }
        }
    }

    /** Los modelos de la cadena, en orden, con su estado y los agentes que los tienen como principal. */
    public List<ModelHealth> freeChainSnapshot() {
        if (freeChain == null) {
            return List.of();
        }
        return freeChain.models().stream().map(m -> {
            var s = states.getOrDefault(m, State.UP);
            return new ModelHealth(m, s.down() ? "DOWN" : "UP", s.since(), s.lastError(), companyMemory.agentIdsUsingModel(m));
        }).toList();
    }

    /**
     * El modelo que de verdad atiende al agente: el suyo; si cayó, el siguiente gratis disponible de la cadena; si no,
     * el respaldo pago disponible; si no, el suplente.
     */
    public String effectiveModel(String agentId, String model) {
        if (!isDown(model)) {
            return model;
        }
        if (freeChain != null) {
            var free = freeChain.alternativesFor(model).stream().filter(m -> !isDown(m)).findFirst();
            if (free.isPresent()) {
                return free.get();
            }
        }
        var paid = paidFallbackFor(agentId);
        return paid != null && !paid.isBlank() && !paid.equals(model) && !isDown(paid) ? paid : fallbackFor(agentId);
    }

    public void recordSuccess(String model) {
        states.computeIfPresent(model, (k, s) -> s.down() ? s : State.UP);
    }

    public void recordFailure(String model, String error) {
        var wentDown = new boolean[1];
        states.compute(model, (k, s) -> {
            var current = s == null ? State.UP : s;
            if (current.down()) {
                return new State(current.failures() + 1, true, current.since(), error);
            }
            var failures = current.failures() + 1;
            if (failures >= FAILURES_TO_DOWN) {
                wentDown[0] = true;
                return new State(failures, true, Instant.now(), error);
            }
            return new State(failures, false, null, error);
        });
        if (wentDown[0]) {
            announceDown(model, error);
        }
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void probeDownModels() {
        for (var entry : List.copyOf(states.entrySet())) {
            if (!entry.getValue().down()) {
                continue;
            }
            var model = entry.getKey();
            var remote = CeoService.remoteModel(model).orElse(null);
            var client = remote == null ? null : remotes.get(remote.provider());
            if (client == null || !client.ping(remote.model())) {
                continue;
            }
            var cameBack = new boolean[1];
            states.computeIfPresent(model, (k, s) -> {
                cameBack[0] = s.down();
                return State.UP;
            });
            if (cameBack[0]) {
                announceUp(model, entry.getValue().since());
            }
        }
    }

    public List<ModelHealth> snapshot() {
        var models = new ArrayList<>(companyMemory.remoteModelsInUse());
        states.keySet().stream().filter(m -> !models.contains(m)).forEach(models::add);
        return models.stream().map(m -> {
            var s = states.getOrDefault(m, State.UP);
            return new ModelHealth(m, s.down() ? "DOWN" : "UP", s.since(), s.lastError(), companyMemory.agentIdsUsingModel(m));
        }).toList();
    }

    private void announceDown(String model, String error) {
        var agents = companyMemory.agentIdsUsingModel(model);
        var substitutes = agents.stream().map(a -> a + " → " + describe(fallbackFor(a))).collect(Collectors.joining("\n"));
        log.warn("MODEL_DOWN model={} error={} agents={}", model, error, agents);
        events.publish("EMPRESA_MODEL_DOWN", null, null, "system",
                Map.of("model", model, "error", error == null ? "" : error, "affectedAgents", agents));
        mail.send("⚠ Forjai: " + shortName(model) + " no responde en NVIDIA",
                "El modelo " + model + " dejó de responder (" + error + ").\n\n" + (agents.isEmpty()
                        ? "Ningún agente lo tiene como principal; queda fuera de la cadena de modelos gratis hasta que vuelva."
                        : "Mientras tanto, estos agentes pasan al siguiente modelo gratis de la cadena (si lo tienen), a "
                        + "su respaldo pago o a su suplente local:\n" + substitutes)
                        + "\n\nForjai lo prueba cada 30 segundos y te avisa cuando vuelva.",
                true);
    }

    private void announceUp(String model, Instant since) {
        log.info("MODEL_UP model={} downSince={}", model, since);
        events.publish("EMPRESA_MODEL_UP", null, null, "system",
                Map.of("model", model, "downSince", since == null ? "" : since.toString()));
        mail.send("✅ Forjai: " + shortName(model) + " volvió", "El modelo " + model + " vuelve a responder (caído desde "
                + since + "). Los agentes vuelven a usarlo en su próxima llamada.", false);
    }

    private static String describe(String fallback) {
        return fallback == null || fallback.isBlank() ? "sin suplente (sus tareas fallan hasta que vuelva)" : fallback;
    }

    static String shortName(String model) {
        return model.substring(model.lastIndexOf('/') + 1);
    }
}
