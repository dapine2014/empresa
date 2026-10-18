package com.aicompany.core.delivery;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.SandboxResult;
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

/**
 * Entrega del producto (spec 2026-10-17): al quedar un incremento VERIFIED pide los compilados al sandbox-runner, arma
 * ENTREGA.md y el zip y lo registra. Todo lo decide Java; nunca lanza: una falla queda como entrega FAILED con su motivo.
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    private static final Set<String> APPS = Set.of("FLUTTER_WEB_APP", "DOTNET_APP");
    private static final int REASON_MAX = 300;

    private final DeliveryMemoryService deliveries;
    private final SandboxRunnerClient runner;
    private final DeliveryPackager packager;
    private final AndroidSigningKeyService keys;
    private final DeliveryFactsBuilder factsBuilder;
    private final CompanyEventPublisher events;
    private final MissionMemoryService missions;
    private final JsonMapper json;

    public DeliveryService(DeliveryMemoryService deliveries, SandboxRunnerClient runner, DeliveryPackager packager,
                           AndroidSigningKeyService keys, DeliveryFactsBuilder factsBuilder,
                           CompanyEventPublisher events, MissionMemoryService missions, JsonMapper json) {
        this.deliveries = deliveries;
        this.runner = runner;
        this.packager = packager;
        this.keys = keys;
        this.factsBuilder = factsBuilder;
        this.events = events;
        this.missions = missions;
        this.json = json;
    }

    public DeliveryRecord deliver(String missionId, int increment, String commitSha, String stackProfile,
                                  int correctionRounds, SandboxResult verifiedRun) {
        try {
            deliveries.markPackaging(missionId, increment);
            var spec = factsBuilder.spec(missionId);
            var last = spec.map(s -> s.incrementNumbers().stream().mapToInt(Integer::intValue).max().orElse(increment))
                    .map(max -> increment >= max).orElse(true);
            var contract = factsBuilder.contract(missionId);
            var plan = DeliveryTargets.plan(stackProfile, contract == null ? null : contract.deliveryOrNull(), last,
                    keys.status(missionId).configured());
            var release = last && plan.targets().contains("android-aab") ? keys.plain(missionId).orElse(null) : null;

            var run = runner.packageJob(missionId, commitSha, stackProfile, increment, plan.targets(), release)
                    .orElseThrow(() -> new IllegalStateException(runner.lastError() == null
                            ? "El sandbox-runner no devolvió resultado." : runner.lastError()));
            var results = targetResults(plan.targets(), run);
            var passed = results.stream().filter(t -> "PASS".equals(t.status())).map(DeliveryFacts.TargetResult::name).toList();

            var migrations = factsBuilder.migrations(missionId, commitSha);
            var zipPlanned = str(packager.zipPath(missionId, increment));
            var facts = factsBuilder.build(missionId, increment, commitSha, stackProfile, correctionRounds, verifiedRun,
                    results, plan.limitations(), zipPlanned, spec.orElse(null), contract, List.copyOf(migrations.keySet()));
            var md = DeliveryDocument.render(facts);

            var extras = extraFiles(stackProfile, facts, !migrations.isEmpty());
            var zip = packager.assemble(new DeliveryPackager.Assembly(missionId, increment, commitSha, stackProfile,
                    packager.stagingDir(missionId, increment), md, extras, migrations));
            var path = zip == null ? zipPlanned : zip.toString();

            var status = passed.isEmpty() ? "FAILED" : passed.size() == results.size() ? "READY" : "PARTIAL";
            var error = "FAILED".equals(status) ? "no se generó ningún compilado" : "";
            var saved = new DeliveryRecord(missionId, increment, status, path, sizeOf(zip), results, error,
                    Instant.now().toString());
            deliveries.save(saved);
            events.publish("EMPRESA_DELIVERY_PACKAGED", missionId, null, "delivery", Map.of("increment", increment,
                    "status", status, "targets", passed, "path", path));
            return saved;
        } catch (Exception ex) {
            return failed(missionId, increment, ex);
        }
    }

    /** Para el botón y la recuperación: el último commit VERIFIED con el incremento en curso. */
    public DeliveryRecord deliverVerified(String missionId) {
        return redo(missionId, Math.max(1, requirementsIncrement(missionId)));
    }

    public List<DeliveryRecord> list(String missionId) {
        return deliveries.list(missionId);
    }

    public List<DeliveryRecord> recent(int limit) {
        return deliveries.recent(limit);
    }

    /** Rehace las entregas que un reinicio dejó en PACKAGING. Nunca lanza. */
    public void resumeStuck() {
        List<DeliveryRecord> stuck;
        try {
            stuck = deliveries.packaging();
        } catch (Exception ex) {
            log.warn("DELIVERY_RESUME could not list stuck deliveries: {}", ex.getMessage());
            return;
        }
        for (var r : stuck) {
            try {
                redo(r.missionId(), r.increment());
            } catch (Exception ex) {
                log.warn("DELIVERY_RESUME mission={} increment={} reason={}", r.missionId(), r.increment(), ex.getMessage());
            }
        }
    }

    private DeliveryRecord redo(String missionId, int increment) {
        var sha = missions.verifiedCommit(missionId).orElseThrow(() -> new IllegalStateException(
                missionId + " no tiene un incremento VERIFIED para entregar."));
        var verifiedRun = missions.lastVerifiedSandboxJson(missionId).map(this::sandboxOrNull).orElse(null);
        return deliver(missionId, increment, sha, stackProfileOf(missionId), 0, verifiedRun);
    }

    private int requirementsIncrement(String missionId) {
        return factsBuilder.currentIncrement(missionId);
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

    private DeliveryRecord failed(String missionId, int increment, Exception ex) {
        var reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
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
