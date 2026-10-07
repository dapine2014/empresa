package com.aicompany.core.database;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.validation.MigrationFilesGate;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.DevelopmentWorkspaceService;
import com.aicompany.core.service.MissionMemoryService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Crea la base de una misión en el servidor del fundador (spec 2026-10-02 §3): solo con el código VERIFIED, con las
 * migraciones del commit verificado. Registra tarea, evidencia y eventos; el texto que devuelve va al estado
 * verificable y nunca incluye host, usuario ni clave.
 */
@Service
public class DatabaseApplyService {

    public record MigrationLine(int version, String description, String state) {
    }

    /** Estado de una conexión de la cascada: APPLIED, FAILED, SKIPPED o PENDING (Spec Diego ambientes §1). */
    public record EnvironmentStatus(String connection, String environment, List<Integer> applied,
                                    Integer failedVersion, String state, String detail) {
    }

    public record MissionDatabaseStatus(List<EnvironmentStatus> environments, List<MigrationLine> migrations,
                                        String lastResult) {
    }

    public record EnvironmentResult(String connection, String environment, List<Integer> applied,
                                    Integer failedVersion, String error, boolean skipped) {
    }

    public record CascadeResult(List<EnvironmentResult> environments, boolean allApplied, String text) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String DBA = "devops";
    private static final java.util.Set<com.aicompany.core.model.MissionStatus> STOPPED = java.util.Set.of(
            com.aicompany.core.model.MissionStatus.AWAITING_INVESTOR, com.aicompany.core.model.MissionStatus.COMPLETED,
            com.aicompany.core.model.MissionStatus.FAILED, com.aicompany.core.model.MissionStatus.BLOCKED);

    private final DatabaseConnectionService connections;
    private final DatabaseSchemaApplier applier;
    private final DevelopmentWorkspaceService workspace;
    private final MissionMemoryService memory;
    private final CompanyEventPublisher events;

    public DatabaseApplyService(DatabaseConnectionService connections, DatabaseSchemaApplier applier,
                                DevelopmentWorkspaceService workspace, MissionMemoryService memory,
                                CompanyEventPublisher events) {
        this.connections = connections;
        this.applier = applier;
        this.workspace = workspace;
        this.memory = memory;
        this.events = events;
    }

    /**
     * Cascada por ambiente (Spec Diego ambientes §1): TEST antes que PRODUCTION; el primer ambiente que falla
     * detiene a los siguientes, que quedan {@code skipped}. Cada conexión se registra, también la que no responde.
     */
    public CascadeResult apply(String missionId, String commitSha, TeamPlan plan) {
        var db = plan == null ? null : plan.databaseOrNull();
        if (db == null) {
            return new CascadeResult(List.of(), true, "");
        }
        var ordered = EnvironmentCascade.order(connections.connectionsOf(missionId), db.engine(), db.connectionName());
        if (ordered.isEmpty()) {
            return new CascadeResult(List.of(), false, "Falta la conexión PostgreSQL para crear la base: cárgala en "
                    + "Bases de datos y escribe 'aplica el esquema de " + missionId + "'.");
        }
        var taskId = missionId + "-DATABASE";
        // Revisión final (I-3): createTask exige un Agent existente; la base es trabajo de Diego (devops), el DBA.
        memory.createTask(taskId, missionId, DBA, "DATABASE_APPLY");
        memory.updateTask(taskId, "RUNNING", "Aplicando migraciones en " + ordered.size() + " ambiente(s).");

        var results = new ArrayList<EnvironmentResult>();
        var texts = new ArrayList<String>();
        EnvironmentResult failedEnv = null;
        for (var c : ordered) {
            if (failedEnv != null) {
                var text = "Base de datos " + c.name() + " (" + c.environment() + "): sin aplicar, falló "
                        + failedEnv.environment() + " (" + failedEnv.connection() + ")"
                        + (failedEnv.failedVersion() == null ? "" : " en V" + failedEnv.failedVersion()) + ".";
                results.add(new EnvironmentResult(c.name(), c.environment(), List.of(), null, null, true));
                texts.add(text);
                memory.recordDatabaseApply(missionId, c.name(), c.environment(), List.of(), null, text, "SKIPPED");
                continue;
            }
            var r = applyOne(missionId, commitSha, taskId, c);
            results.add(r);
            if (r.error() != null) {
                failedEnv = r;
            }
            texts.add(describe(c, r));
        }
        var allApplied = failedEnv == null;
        var text = String.join(" · ", texts);
        memory.updateTask(taskId, allApplied ? "COMPLETED" : "FAILED", text);
        return new CascadeResult(List.copyOf(results), allApplied, text);
    }

    private EnvironmentResult applyOne(String missionId, String commitSha, String taskId, DatabaseConnection c) {
        String password = null;
        DatabaseSchemaApplier.ApplyResult result;
        try {
            password = connections.password(c);
            result = applier.apply(c, password, migrations(missionId, commitSha), missionId, commitSha);
        } catch (Exception ex) {
            // Review Focus 2: una conexión que no responde es la falla de ese ambiente, con el error redactado.
            result = new DatabaseSchemaApplier.ApplyResult(List.of(), null, DatabaseConnectionService.redact(
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), c, password), false);
        }
        var r = new EnvironmentResult(c.name(), c.environment(), result.applied(), result.failedVersion(),
                result.error(), false);
        var ok = r.error() == null;
        memory.recordDatabaseApply(missionId, c.name(), c.environment(), r.applied(), r.failedVersion(),
                describe(c, r), ok ? "APPLIED" : "FAILED");
        if (!r.applied().isEmpty()) {
            memory.recordEvidence(taskId, missionId, DBA, r.applied().stream()
                    .map(v -> new AgentResult.Evidence("Migración V" + v + " aplicada en " + c.name(),
                            "database:" + c.name() + "@" + commitSha + "/V" + v, "INTERNAL", true))
                    .toList());
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("connectionName", c.name());
        data.put("environment", c.environment());
        data.put("applied", r.applied());
        if (r.failedVersion() != null) {
            data.put("failedVersion", r.failedVersion());
        }
        if (!ok) {
            data.put("error", r.error());
        }
        events.publish(ok ? "EMPRESA_DATABASE_SCHEMA_APPLIED" : "EMPRESA_DATABASE_SCHEMA_FAILED", missionId, taskId,
                "forjai", data);
        return r;
    }

    private static String describe(DatabaseConnection c, EnvironmentResult r) {
        if (r.error() != null) {
            return "Esquema no aplicado en " + c.name() + " (" + c.environment() + "): " + r.error();
        }
        return "Base de datos " + c.name() + " (" + c.environment() + "): "
                + (r.applied().isEmpty() ? "sin migraciones nuevas"
                : "aplicadas " + r.applied().stream().map(v -> "V" + v).collect(Collectors.joining(", "))) + ".";
    }

    /** A pedido del fundador (chat o botón): exige el código VERIFIED y usa el último plan y el HEAD del workspace. */
    public String applyLatest(String missionId) {
        var status = memory.lastValidationStatus(missionId).orElse(null);
        if (!"VERIFIED".equals(status)) {
            throw new IllegalStateException("El código de " + missionId + " no está VERIFIED (" + status
                    + "): no se aplica el esquema.");
        }
        // Revisión final (I-4): durante una ronda el HEAD tiene código sin verificar; solo con la misión detenida.
        var mission = memory.find(missionId).orElseThrow(() -> new IllegalStateException("No existe " + missionId + "."));
        if (!STOPPED.contains(mission.status())) {
            throw new IllegalStateException(missionId + " está en curso (" + mission.status() + "): espera a que termine "
                    + "para aplicar el esquema.");
        }
        var plan = plan(missionId).orElseThrow(() -> new IllegalStateException(missionId + " no tiene plan del equipo."));
        if (plan.databaseOrNull() == null) {
            throw new IllegalStateException("El plan de " + missionId + " no declara base de datos.");
        }
        var commit = memory.verifiedCommit(missionId).orElseThrow(() -> new IllegalStateException(
                missionId + " no tiene un commit verificado registrado."));
        return apply(missionId, commit, plan).text();
    }

    public Optional<MissionDatabaseStatus> status(String missionId) {
        var plan = plan(missionId).orElse(null);
        if (plan == null || plan.databaseOrNull() == null) {
            return Optional.empty();
        }
        var db = plan.databaseOrNull();
        var applies = memory.databaseApplies(missionId);
        var ordered = EnvironmentCascade.order(connections.connectionsOf(missionId), db.engine(), db.connectionName());
        var environments = new ArrayList<EnvironmentStatus>();
        var texts = new ArrayList<String>();
        for (var c : ordered) {
            var a = applies.get(c.name());
            environments.add(a == null
                    ? new EnvironmentStatus(c.name(), c.environment(), List.of(), null, "PENDING", null)
                    : new EnvironmentStatus(c.name(), c.environment(), a.applied(), a.failedVersion(),
                    a.state() == null ? "PENDING" : a.state(), a.text()));
            if (a != null && a.text() != null) {
                texts.add(a.text());
            }
        }
        // Misión anterior a la cascada cuya conexión ya no está asociada: no se pierde lo registrado.
        for (var a : applies.values()) {
            if (ordered.stream().noneMatch(c -> c.name().equals(a.connection()))) {
                environments.add(new EnvironmentStatus(a.connection(), a.environment(), a.applied(),
                        a.failedVersion(), a.state() == null ? "PENDING" : a.state(), a.text()));
                if (a.text() != null) {
                    texts.add(a.text());
                }
            }
        }
        var appliedAnywhere = memory.appliedAnywhere(missionId);
        var failed = applies.values().stream().map(MissionMemoryService.DatabaseApply::failedVersion)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        List<MigrationSet.Migration> all;
        try {
            all = migrations(missionId, workspace.headSha(missionId));
        } catch (Exception ex) {
            all = List.of();
        }
        var lines = all.stream().map(m -> new MigrationLine(m.version(), m.description(),
                appliedAnywhere.contains(m.version()) ? "APPLIED" : m.version() == (failed == null ? -1 : failed)
                        ? "FAILED" : "PENDING")).toList();
        return Optional.of(new MissionDatabaseStatus(List.copyOf(environments), lines,
                texts.isEmpty() ? null : String.join(" · ", texts)));
    }

    private List<MigrationSet.Migration> migrations(String missionId, String commitSha) {
        try {
            var contents = new LinkedHashMap<String, String>();
            for (var path : workspace.filesAtCommit(missionId, commitSha)) {
                if (path.startsWith(MigrationFilesGate.ROOT)) {
                    contents.put(path, workspace.readFileAtCommit(missionId, commitSha, path));
                }
            }
            return MigrationSet.parse(contents);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("No se pudieron leer las migraciones del commit " + commitSha + ".", ex);
        }
    }

    private Optional<TeamPlan> plan(String missionId) {
        return memory.lastTeamPlanJson(missionId).map(json -> {
            try {
                return JSON.readValue(json, TeamPlan.class);
            } catch (Exception ex) {
                return null;
            }
        });
    }
}
