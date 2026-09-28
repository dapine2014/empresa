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

    public boolean isDown(String model) {
        return states.getOrDefault(model, State.UP).down();
    }

    public Instant downSince(String model) {
        return states.getOrDefault(model, State.UP).since();
    }

    public String fallbackFor(String agentId) {
        return companyMemory.agentFallbackModel(agentId);
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
                "El modelo " + model + " dejó de responder (" + error + ").\n\nMientras tanto, estos agentes trabajan con "
                        + "su suplente local:\n" + substitutes + "\n\nForjai lo prueba cada 30 segundos y te avisa cuando vuelva.",
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
