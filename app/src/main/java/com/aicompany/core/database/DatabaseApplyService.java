package com.aicompany.core.database;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.validation.MigrationFilesGate;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.DevelopmentWorkspaceService;
import com.aicompany.core.service.MissionMemoryService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

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

    public record MissionDatabaseStatus(String connectionName, List<MigrationLine> migrations, String lastResult) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    public String apply(String missionId, String commitSha, TeamPlan plan) {
        var db = plan == null ? null : plan.databaseOrNull();
        if (db == null) {
            return "";
        }
        var connection = connection(missionId, db);
        if (connection.isEmpty()) {
            return "Falta la conexión PostgreSQL para crear la base: cárgala en Bases de datos y escribe "
                    + "'aplica el esquema de " + missionId + "'.";
        }
        var c = connection.get();
        var taskId = missionId + "-DATABASE";
        memory.createTask(taskId, missionId, "forjai", "DATABASE_APPLY");
        memory.updateTask(taskId, "RUNNING", "Aplicando migraciones en " + c.name() + ".");

        DatabaseSchemaApplier.ApplyResult result;
        try {
            result = applier.apply(c, connections.password(c), migrations(missionId, commitSha), missionId, commitSha);
        } catch (Exception ex) {
            result = new DatabaseSchemaApplier.ApplyResult(List.of(), null,
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), false);
        }

        var ok = result.error() == null;
        var text = ok
                ? "Base de datos " + c.name() + " (" + c.environment() + "): "
                        + (result.applied().isEmpty() ? "sin migraciones nuevas"
                        : "aplicadas " + result.applied().stream().map(v -> "V" + v).collect(Collectors.joining(", ")))
                        + (result.createdDatabase() ? " — base creada" : "") + "."
                : "Esquema no aplicado en " + c.name() + ": " + result.error();

        memory.updateTask(taskId, ok ? "COMPLETED" : "FAILED", text);
        memory.recordDatabaseApply(missionId, c.name(), result.applied(), result.failedVersion(), text);
        if (!result.applied().isEmpty()) {
            memory.recordEvidence(taskId, missionId, "forjai", result.applied().stream()
                    .map(v -> new AgentResult.Evidence("Migración V" + v + " aplicada en " + c.name(),
                            "database:" + c.name() + "@" + commitSha + "/V" + v, "INTERNAL", true))
                    .toList());
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("connectionName", c.name());
        data.put("applied", result.applied());
        if (result.failedVersion() != null) {
            data.put("failedVersion", result.failedVersion());
        }
        if (!ok) {
            data.put("error", result.error());
        }
        events.publish(ok ? "EMPRESA_DATABASE_SCHEMA_APPLIED" : "EMPRESA_DATABASE_SCHEMA_FAILED", missionId, taskId,
                "forjai", data);
        return text;
    }

    /** A pedido del fundador (chat o botón): exige el código VERIFIED y usa el último plan y el HEAD del workspace. */
    public String applyLatest(String missionId) {
        var status = memory.lastValidationStatus(missionId).orElse(null);
        if (!"VERIFIED".equals(status)) {
            throw new IllegalStateException("El código de " + missionId + " no está VERIFIED (" + status
                    + "): no se aplica el esquema.");
        }
        var plan = plan(missionId).orElseThrow(() -> new IllegalStateException(missionId + " no tiene plan del equipo."));
        if (plan.databaseOrNull() == null) {
            throw new IllegalStateException("El plan de " + missionId + " no declara base de datos.");
        }
        try {
            return apply(missionId, workspace.headSha(missionId), plan);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("No se pudo leer el repositorio de " + missionId + ".", ex);
        }
    }

    public Optional<MissionDatabaseStatus> status(String missionId) {
        var plan = plan(missionId).orElse(null);
        if (plan == null || plan.databaseOrNull() == null) {
            return Optional.empty();
        }
        var applied = memory.databaseApplied(missionId);
        var failed = memory.databaseFailedVersion(missionId).orElse(null);
        List<MigrationSet.Migration> all;
        try {
            all = migrations(missionId, workspace.headSha(missionId));
        } catch (Exception ex) {
            all = List.of();
        }
        var lines = all.stream().map(m -> new MigrationLine(m.version(), m.description(),
                applied.contains(m.version()) ? "APPLIED" : m.version() == (failed == null ? -1 : failed)
                        ? "FAILED" : "PENDING")).toList();
        var name = connection(missionId, plan.databaseOrNull()).map(DatabaseConnection::name)
                .orElse(plan.databaseOrNull().connectionName());
        return Optional.of(new MissionDatabaseStatus(name, lines, memory.databaseLastResult(missionId).orElse(null)));
    }

    private Optional<DatabaseConnection> connection(String missionId, TeamPlan.DatabaseNeed db) {
        var name = db.connectionName() == null ? "" : db.connectionName();
        var ofMission = connections.connectionsOf(missionId);
        if (!name.isBlank()) {
            return ofMission.stream().filter(c -> c.name().equals(name)).findFirst();
        }
        // Conexión cargada después del plan (spec §3, "si falta"): la única de ese motor asociada a la misión.
        var sameEngine = ofMission.stream().filter(c -> c.engine().equals(db.engine())).toList();
        return sameEngine.size() == 1 ? Optional.of(sameEngine.get(0)) : Optional.empty();
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
