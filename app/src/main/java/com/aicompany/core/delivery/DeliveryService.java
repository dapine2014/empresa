package com.aicompany.core.delivery;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.SandboxResult;
import com.aicompany.core.recovery.MissionRecoveryService;
import com.aicompany.core.service.MissionCancellations;
import com.aicompany.core.service.MissionMemoryService;
import com.aicompany.core.service.SandboxRunnerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entrega del producto (spec 2026-10-17): al quedar un incremento VERIFIED pide los compilados al sandbox-runner, arma
 * ENTREGA.md y el zip y lo registra. Todo lo decide Java; una falla queda como entrega FAILED con su motivo (solo la
 * cancelación de la misión se relanza).
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    private static final Set<String> APPS = Set.of("FLUTTER_WEB_APP", "DOTNET_APP");
    private static final int REASON_MAX = 300;
    /** Revisión final (11): sin dato de rondas de corrección (ENTREGA.md dice «no disponible»). */
    public static final int ROUNDS_UNKNOWN = -1;
    static final String NOTHING_TO_BUILD = "no se pidió ningún compilado posible";
    static final String INTERRUPTED = "La entrega se cortó con un reinicio y la validación retomada no quedó VERIFIED.";

    /** missionId#incremento con un empaquetado en curso en este proceso. */
    private final Set<String> inProgress = ConcurrentHashMap.newKeySet();

    private final DeliveryMemoryService deliveries;
    private final SandboxRunnerClient runner;
    private final DeliveryPackager packager;
    private final AndroidSigningKeyService keys;
    private final DeliveryFactsBuilder factsBuilder;
    private final CompanyEventPublisher events;
    private final MissionMemoryService missions;
    private final JsonMapper json;
    private final DeliveryPaths paths;

    public DeliveryService(DeliveryMemoryService deliveries, SandboxRunnerClient runner, DeliveryPackager packager,
                           AndroidSigningKeyService keys, DeliveryFactsBuilder factsBuilder,
                           CompanyEventPublisher events, MissionMemoryService missions, JsonMapper json,
                           DeliveryPaths paths) {
        this.deliveries = deliveries;
        this.runner = runner;
        this.packager = packager;
        this.keys = keys;
        this.factsBuilder = factsBuilder;
        this.events = events;
        this.missions = missions;
        this.json = json;
        this.paths = paths;
    }

    /**
     * Al quedar VERIFIED (lo llama la validación). Si ya hay un empaquetado de ese incremento en curso en este proceso,
     * lo salta (null). Nunca lanza, salvo la cancelación de la misión.
     */
    public DeliveryRecord deliver(String missionId, int increment, String commitSha, String stackProfile,
                                  int correctionRounds, SandboxResult verifiedRun) {
        return guarded(missionId, increment, commitSha, stackProfile, correctionRounds, verifiedRun, false);
    }

    /** Revisión final (3): un solo empaquetado por misión e incremento a la vez (botón, chat, validación, recuperación). */
    private DeliveryRecord guarded(String missionId, int increment, String commitSha, String stackProfile,
                                   int correctionRounds, SandboxResult verifiedRun, boolean failIfBusy) {
        var key = key(missionId, increment);
        if (!inProgress.add(key)) {
            if (failIfBusy) {
                throw new IllegalStateException("Ya se está empaquetando el incremento " + increment + " de " + missionId
                        + ": espera a que termine.");
            }
            log.info("DELIVERY mission={} increment={} skipped: ya se está empaquetando", missionId, increment);
            return null;
        }
        try {
            return pack(missionId, increment, commitSha, stackProfile, correctionRounds, verifiedRun);
        } finally {
            inProgress.remove(key);
        }
    }

    private DeliveryRecord pack(String missionId, int increment, String commitSha, String stackProfile,
                                int correctionRounds, SandboxResult verifiedRun) {
        Path staging = null;
        try {
            deliveries.markPackaging(missionId, increment);
            var spec = factsBuilder.spec(missionId);
            var last = spec.map(s -> s.incrementNumbers().stream().mapToInt(Integer::intValue).max().orElse(increment))
                    .map(max -> increment >= max).orElse(true);
            var contract = factsBuilder.contract(missionId);
            var plan = DeliveryTargets.plan(stackProfile, contract == null ? null : contract.deliveryOrNull(), last,
                    keys.status(missionId).configured());

            // Revisión final (9): sin ningún target posible no se llama al runner; el zip lleva código y documentos.
            var results = List.<DeliveryFacts.TargetResult>of();
            if (!plan.targets().isEmpty()) {
                var release = last && plan.targets().contains("android-aab") ? keys.plain(missionId).orElse(null) : null;
                var run = runner.packageJob(missionId, commitSha, stackProfile, increment, plan.targets(), release)
                        .orElseThrow(() -> new IllegalStateException(runner.lastError() == null
                                ? "El sandbox-runner no devolvió resultado." : runner.lastError()));
                staging = packager.stagingDir(missionId, increment, run.stagingDir());
                results = targetResults(plan.targets(), run);
            }
            var passed = results.stream().filter(t -> "PASS".equals(t.status())).map(DeliveryFacts.TargetResult::name).toList();

            var migrations = factsBuilder.migrations(missionId, commitSha);
            var zipPlanned = str(packager.zipPath(missionId, increment));
            var zipShown = paths.display(zipPlanned);
            var facts = factsBuilder.build(missionId, increment, commitSha, stackProfile, correctionRounds, verifiedRun,
                    results, plan.limitations(), zipShown, spec.orElse(null), contract, List.copyOf(migrations.keySet()));
            var md = DeliveryDocument.render(facts);

            var extras = extraFiles(stackProfile, facts, !migrations.isEmpty());
            var zip = packager.assemble(new DeliveryPackager.Assembly(missionId, increment, commitSha, stackProfile,
                    staging, md, extras, migrations));
            var path = zip == null ? zipPlanned : zip.toString();

            var status = passed.isEmpty() ? "FAILED" : passed.size() == results.size() ? "READY" : "PARTIAL";
            var error = !"FAILED".equals(status) ? "" : results.isEmpty() ? NOTHING_TO_BUILD : "no se generó ningún compilado";
            var saved = new DeliveryRecord(missionId, increment, status, path, sizeOf(zip), results, error,
                    Instant.now().toString());
            deliveries.save(saved);
            events.publish("EMPRESA_DELIVERY_PACKAGED", missionId, null, "delivery", Map.of("increment", increment,
                    "status", status, "targets", passed, "path", paths.display(path)));
            return saved;
        } catch (MissionCancellations.MissionCancelledException ex) {
            // Revisión final (12): la cancelación no es una falla de la entrega; la decide quien canceló.
            failed(missionId, increment, "la misión se canceló durante la entrega");
            throw ex;
        } catch (Exception ex) {
            return failed(missionId, increment, reasonOf(ex));
        } finally {
            packager.discardStaging(staging);
        }
    }

    /**
     * Para el botón y el chat: el último commit VERIFIED con el número del incremento que verificó (revisión final, 2).
     * Con la misión trabajando no: la entrega se genera sola al quedar VERIFIED.
     */
    public DeliveryRecord deliverVerified(String missionId) {
        var mission = missions.find(missionId);
        if (mission != null && mission.isPresent() && MissionRecoveryService.WORKING.contains(mission.get().status())) {
            throw new IllegalStateException(missionId + " está trabajando (" + mission.get().status() + "): la entrega se "
                    + "genera sola cuando el incremento quede VERIFIED. Vuelve a intentarlo cuando termine.");
        }
        var sha = verifiedCommit(missionId);
        return redo(missionId, verifiedIncrement(missionId), sha, true);
    }

    public List<DeliveryRecord> list(String missionId) {
        return deliveries.list(missionId);
    }

    public List<DeliveryRecord> recent(int limit) {
        return deliveries.recent(limit);
    }

    /**
     * Al arrancar: borra los temporales huérfanos y resuelve las entregas que un reinicio dejó en PACKAGING. Una misión
     * en trabajo la retoma la recuperación (si queda VERIFIED vuelve a empaquetar; si no, {@link #abandonInterrupted});
     * una detenida se rehace solo si el incremento sigue siendo el VERIFIED, y si no queda FAILED. Nunca lanza.
     */
    public void resumeStuck() {
        try {
            packager.cleanOrphans();
        } catch (Exception ex) {
            log.warn("DELIVERY_RESUME could not clean orphans: {}", ex.getMessage());
        }
        List<DeliveryRecord> stuck;
        try {
            stuck = deliveries.packaging();
        } catch (Exception ex) {
            log.warn("DELIVERY_RESUME could not list stuck deliveries: {}", ex.getMessage());
            return;
        }
        for (var r : stuck) {
            try {
                var mission = missions.find(r.missionId());
                if (mission != null && mission.isPresent() && MissionRecoveryService.WORKING.contains(mission.get().status())) {
                    log.info("DELIVERY_RESUME mission={} skipped: la misión está en {}", r.missionId(), mission.get().status());
                    continue;
                }
                var sha = missions.verifiedCommit(r.missionId());
                var verified = verifiedIncrement(r.missionId());
                if (sha.isEmpty() || verified != r.increment()) {
                    failed(r.missionId(), r.increment(), "La entrega se cortó con un reinicio y el incremento "
                            + r.increment() + " ya no es el VERIFIED de la misión.");
                    continue;
                }
                redo(r.missionId(), r.increment(), sha.get(), false);
            } catch (Exception ex) {
                log.warn("DELIVERY_RESUME mission={} increment={} reason={}", r.missionId(), r.increment(), ex.getMessage());
            }
        }
    }

    /**
     * Revisión final (4): la validación retomada tras un reinicio no quedó VERIFIED; la entrega de ese incremento que el
     * reinicio dejó en PACKAGING queda FAILED. Nunca lanza.
     */
    public void abandonInterrupted(String missionId, int increment) {
        if (inProgress.contains(key(missionId, increment))) {
            return;
        }
        try {
            var r = deliveries.find(missionId, increment);
            if (r != null && r.isPresent() && "PACKAGING".equals(r.get().status())) {
                failed(missionId, increment, INTERRUPTED);
            }
        } catch (Exception ex) {
            log.warn("DELIVERY mission={} increment={} could not abandon: {}", missionId, increment, ex.getMessage());
        }
    }

    private DeliveryRecord redo(String missionId, int increment, String sha, boolean failIfBusy) {
        var verifiedRun = missions.lastVerifiedSandboxJson(missionId).map(this::sandboxOrNull).orElse(null);
        // Revisión final (11): a pedido no se sabe cuántas rondas de corrección hubo.
        return guarded(missionId, increment, sha, stackProfileOf(missionId), ROUNDS_UNKNOWN, verifiedRun, failIfBusy);
    }

    private String verifiedCommit(String missionId) {
        return missions.verifiedCommit(missionId).orElseThrow(() -> new IllegalStateException(
                missionId + " no tiene un incremento VERIFIED para entregar."));
    }

    /** El incremento del commit VERIFIED; en misiones anteriores a guardarlo, el último construido (mínimo 1). */
    private int verifiedIncrement(String missionId) {
        var stored = missions.verifiedIncrement(missionId);
        return Math.max(1, stored != null && stored.isPresent() ? stored.get() : factsBuilder.builtIncrement(missionId));
    }

    private static String key(String missionId, int increment) {
        return missionId + "#" + increment;
    }

    private String stackProfileOf(String missionId) {
        try {
            return missions.lastTeamPlanJson(missionId).map(j -> json.readValue(j, TeamPlan.class).stackProfile()).orElse("");
        } catch (Exception ex) {
            log.warn("DELIVERY mission={} plan unreadable: {}", missionId, ex.getMessage());
            return "";
        }
    }

    private SandboxResult sandboxOrNull(String j) {
        try {
            return json.readValue(j, SandboxResult.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private static List<DeliveryFacts.TargetResult> targetResults(List<String> targets, SandboxResult run) {
        var firstFailure = run.stepsOrEmpty().stream().filter(s -> !"PASS".equals(s.status())).findFirst().orElse(null);
        var out = new ArrayList<DeliveryFacts.TargetResult>();
        for (var t : targets) {
            var step = run.stepsOrEmpty().stream().filter(s -> ("package:" + t).equals(s.name())).findFirst().orElse(null);
            if (step != null) {
                out.add(new DeliveryFacts.TargetResult(t, step.status(),
                        "PASS".equals(step.status()) ? "" : oneLine(step.outputTail())));
            } else {
                out.add(new DeliveryFacts.TargetResult(t, "FAIL", firstFailure == null ? "el runner no devolvió el paso"
                        : firstFailure.name() + ": " + oneLine(firstFailure.outputTail())));
            }
        }
        return out;
    }

    private static Map<String, String> extraFiles(String stackProfile, DeliveryFacts facts, boolean hasMigrations) {
        var extras = new LinkedHashMap<String, String>();
        var hasDb = hasMigrations || !facts.databases().isEmpty();
        var ownPostgres = hasMigrations && facts.databases().isEmpty();
        if (APPS.contains(stackProfile)) {
            extras.put("docker/Dockerfile", DockerTemplates.dockerfile(stackProfile));
            extras.put("docker/docker-compose.yml", DockerTemplates.compose(stackProfile, facts.databases(), hasMigrations));
            extras.put("docker/.env.example", DockerTemplates.envExample(facts.databases(), ownPostgres));
        } else if (hasDb) {
            extras.put("docker/.env.example", DockerTemplates.envExample(facts.databases(), ownPostgres));
        }
        if (hasDb) {
            extras.put("base-de-datos/LEEME.md", DockerTemplates.databaseReadme(facts.migrations(), facts.databases(), ownPostgres));
        }
        return extras;
    }

    private static String reasonOf(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private DeliveryRecord failed(String missionId, int increment, String reason) {
        log.warn("DELIVERY_FAILED mission={} reason={}", missionId, reason);
        var record = new DeliveryRecord(missionId, increment, "FAILED", "", 0, List.of(), reason, Instant.now().toString());
        try {
            deliveries.save(record);
        } catch (Exception saveEx) {
            log.warn("DELIVERY_FAILED mission={} could not save: {}", missionId, saveEx.getMessage());
        }
        try {
            events.publish("EMPRESA_DELIVERY_FAILED", missionId, null, "delivery",
                    Map.of("increment", increment, "error", reason));
        } catch (Exception pubEx) {
            log.warn("DELIVERY_FAILED mission={} could not publish: {}", missionId, pubEx.getMessage());
        }
        return record;
    }

    private static String oneLine(String s) {
        var t = s == null ? "" : s.replaceAll("\\s+", " ").strip();
        return t.length() <= REASON_MAX ? t : t.substring(0, REASON_MAX);
    }

    private static String str(Path p) {
        return p == null ? "" : p.toString();
    }

    private static long sizeOf(Path zip) {
        try {
            return zip == null ? 0 : Files.size(zip);
        } catch (Exception ex) {
            return 0;
        }
    }
}
