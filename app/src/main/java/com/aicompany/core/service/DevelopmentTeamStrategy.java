package com.aicompany.core.service;

import com.aicompany.core.agent.DevelopmentRuntime;
import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.StaticReviewResult;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.StaticCheck;
import com.aicompany.core.model.StaticValidationStatus;
import com.aicompany.core.model.TeamExecutionMode;
import com.aicompany.core.model.TeamExecutionResult;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamMissionContext;
import com.aicompany.core.service.StaticWorkspaceValidator.CommittedWork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Engineering (spec §6-8): código real en paralelo → un commit por agente
 * (secuencial, en el orden del plan) → chequeos deterministas → revisión
 * estática del validador → reporte para el CEO + "Estado verificable".
 * Nunca ejecuta el código generado.
 */
@Service
public class DevelopmentTeamStrategy implements TeamExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentTeamStrategy.class);

    // Debe entrar en CeoService.TEAM_CONTEXT_WINDOW_TOKENS (16k) junto con el prompt y la respuesta.
    static final int REVIEW_TOTAL_BUDGET_CHARS = 24_000;
    static final int REVIEW_FILE_BUDGET_CHARS = 6_000;
    static final String NO_EXECUTION_DISCLAIMER =
            "Esta fase no ejecuta código: no se puede afirmar que el juego compile, se ejecute o pase tests.";

    private final MissionMemoryService memory;
    private final DevelopmentRuntime runtime;
    private final DevelopmentWorkspaceService workspace;
    private final StaticWorkspaceValidator staticValidator;
    private final CompanyEventPublisher events;
    private final JsonMapper jsonMapper;

    public DevelopmentTeamStrategy(
            MissionMemoryService memory,
            DevelopmentRuntime runtime,
            DevelopmentWorkspaceService workspace,
            StaticWorkspaceValidator staticValidator,
            CompanyEventPublisher events,
            JsonMapper jsonMapper) {

        this.memory = memory;
        this.runtime = runtime;
        this.workspace = workspace;
        this.staticValidator = staticValidator;
        this.events = events;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public TeamExecutionMode mode() {
        return TeamExecutionMode.DEVELOPMENT;
    }

    @Override
    public TeamExecutionResult execute(TeamMissionContext context, MissionProgress progress) {

        var missionId = context.missionId();
        var plan = context.plan();
        var work = plan.workTasks();
        var validation = plan.validationTask()
                .orElseThrow(() -> new IllegalStateException("El plan no tiene tarea VALIDATION."));
        var validationTaskId = taskId(missionId, validation.agentId());

        for (var task : work) {
            createTask(missionId, task, TeamPlan.KIND_WORK);
        }
        createTask(missionId, validation, TeamPlan.KIND_VALIDATION);

        var futures = new LinkedHashMap<PlannedTask, CompletableFuture<DevelopmentResult>>();

        for (var task : work) {
            futures.put(task, runtime.generate(taskId(missionId, task.agentId()), missionId, task.agentId(),
                    buildWorkPrompt(context, task), task.ownedPathsOrEmpty()));
        }

        progress.advance(MissionStatus.WAITING_AGENT_RESULTS, 30, "Desarrollo en paralelo",
                "Los miembros de " + context.team().teamName() + " están generando código.");

        var generated = new LinkedHashMap<PlannedTask, DevelopmentResult>();
        var failures = new ArrayList<String>();

        for (var entry : futures.entrySet()) {
            try {
                generated.put(entry.getKey(), entry.getValue().join());
            } catch (Exception ex) {
                failures.add(entry.getKey().agentId() + ": " + safeMessage(ex, "no generó código"));
            }
        }

        progress.advance(MissionStatus.EVALUATING, 60, "Commits por agente",
                "Registrando un commit por agente en el repositorio de la misión.");

        var committed = new ArrayList<CommittedWork>();

        for (var entry : generated.entrySet()) {

            var task = entry.getKey();
            var id = taskId(missionId, task.agentId());

            try {

                var record = workspace.commitAgentWork(missionId, id, task.agentId(),
                        agentName(context, task.agentId()), entry.getValue());

                memory.recordTaskArtifact(id, workspace.missionWorkspace(missionId).toString(),
                        record.sha(), record.files());
                memory.updateTask(id, "COMPLETED", toJson(entry.getValue()));

                events.publish("EMPRESA_TASK_COMMITTED", missionId, id, task.agentId(),
                        Map.of("commitSha", record.sha(), "files", record.files()));
                events.publishTask("EMPRESA_TASK_COMPLETED", id, missionId, task.agentId(), "COMPLETED",
                        "Commit " + record.sha());

                committed.add(new CommittedWork(id, task.agentId(), record.sha(), record.files()));

            } catch (Exception ex) {

                var message = "Archivos generados pero sin commit: " + safeMessage(ex, "error de Git");
                memory.updateTask(id, "FAILED", message);
                events.publishTask("EMPRESA_TASK_FAILED", id, missionId, task.agentId(), "FAILED", message);
                failures.add(task.agentId() + ": " + message);
            }
        }

        if (committed.isEmpty()) {

            var message = "No hay código commiteado que validar.";
            memory.updateTask(validationTaskId, "FAILED", message);
            events.publishTask("EMPRESA_TASK_FAILED", validationTaskId, missionId, validation.agentId(), "FAILED", message);

            throw new IllegalStateException("Ninguna tarea de desarrollo produjo un commit: " + String.join("; ", failures));
        }

        progress.advance(MissionStatus.EVALUATING, 75, "Validación estática",
                "Chequeos deterministas y revisión estática de " + validation.agentId() + ".");

        var allowedPaths = work.stream().flatMap(t -> t.ownedPathsOrEmpty().stream()).toList();
        var checks = staticValidator.validate(missionId, committed, plan.entryPoint(), allowedPaths);

        StaticReviewResult review = null;
        String reviewError = null;

        try {

            var headSha = committed.get(committed.size() - 1).commitSha();
            var filesBySha = new LinkedHashMap<String, Set<String>>();

            for (var item : committed) {
                filesBySha.put(item.commitSha(), new HashSet<>(workspace.filesAtCommit(missionId, item.commitSha())));
            }

            var contents = new LinkedHashMap<String, String>();
            for (var path : workspace.filesAtCommit(missionId, headSha).stream().sorted().toList()) {
                contents.put(path, workspace.readFileAtCommit(missionId, headSha, path));
            }

            var repositoryContext = renderRepositoryContext(contents, REVIEW_TOTAL_BUDGET_CHARS, REVIEW_FILE_BUDGET_CHARS);

            review = runtime.review(validationTaskId, missionId, validation.agentId(),
                    buildReviewPrompt(context, validation, committed, checks, headSha, repositoryContext),
                    filesBySha).join();

        } catch (Exception ex) {
            reviewError = safeMessage(ex, "La revisión estática no se completó.");
            log.warn("MISSION {} - static review did not complete: {}", missionId, reviewError);
        }

        var status = StaticValidationStatus.compute(checks, review);

        memory.recordStaticValidation(validationTaskId, status.name(), toJson(checks));

        if (review != null && review.evidence() != null && !review.evidence().isEmpty()) {
            memory.recordEvidence(validationTaskId, missionId, validation.agentId(), review.evidence());
        }

        var failedChecks = checks.stream().filter(c -> !c.passed()).count();

        events.publish("EMPRESA_STATIC_VALIDATION_COMPLETED", missionId, validationTaskId, validation.agentId(),
                Map.of("validationStatus", status.name(), "failedChecks", failedChecks));

        return new TeamExecutionResult.Development(
                resultsForCeo(context, committed, checks, review, reviewError, status, failures),
                verifiableState(context, committed, checks, status, failures));
    }

    private void createTask(String missionId, PlannedTask task, String kind) {
        var id = taskId(missionId, task.agentId());
        memory.createTask(id, missionId, task.agentId(), task.action(), kind);
        events.publishTask("EMPRESA_TASK_CREATED", id, missionId, task.agentId(), "PENDING", "Tarea creada.");
    }

    static String taskId(String missionId, String agentId) {
        return missionId + "-" + agentId.toUpperCase(Locale.ROOT);
    }

    private static String agentName(TeamMissionContext context, String agentId) {
        return context.team().members().stream()
                .filter(m -> m.agentId().equals(agentId))
                .map(TeamMemberInfo::name)
                .findFirst()
                .orElse(agentId);
    }

    private String buildWorkPrompt(TeamMissionContext context, PlannedTask task) {

        var plan = context.plan();

        var others = plan.tasksOrEmpty().stream()
                .filter(t -> t != null && !t.agentId().equals(task.agentId()))
                .map(t -> "- " + t.agentId() + " (" + t.kind() + "): " + t.objective() + " | ownedPaths=" + t.ownedPathsOrEmpty())
                .collect(Collectors.joining("\n"));

        return """
                MISIÓN DEL EQUIPO %s:
                %s

                PLAN DEL LÍDER:
                %s
                Tecnología: %s
                Punto de entrada: %s

                TU TAREA (%s, action=%s):
                %s
                Solo puedes escribir archivos dentro de estos ownedPaths: %s

                TAREAS DEL RESTO DEL EQUIPO (corren en paralelo; no verás su código, respeta sus rutas e interfaces):
                %s

                REGLAS:
                - Escribe código fuente REAL y completo para tu parte, no pseudocódigo ni placeholders.
                - Rutas relativas con "/" como separador; nunca rutas absolutas, "..", "\\" ni ".git".
                - Nadie va a ejecutar este código en esta fase: no afirmes en summary que compila o funciona.
                - summary: qué archivos escribiste y qué hace cada uno.

                FORMATO: {"summary": "...", "files": [{"path": "...", "content": "..."}]}
                """.formatted(
                context.team().teamName(), context.instruction(),
                plan.summary(), plan.techStack(), plan.entryPoint(),
                task.agentId(), task.action(), task.objective(), task.ownedPathsOrEmpty(),
                others);
    }

    private String buildReviewPrompt(
            TeamMissionContext context, PlannedTask validation, List<CommittedWork> committed,
            List<StaticCheck> checks, String headSha, String repositoryContext) {

        var commits = committed.stream()
                .map(c -> "- " + c.agentId() + ": sha=" + c.commitSha() + " archivos=" + c.files())
                .collect(Collectors.joining("\n"));

        var checkLines = checks.stream()
                .map(c -> "- " + c.check() + ": " + c.status() + " — " + c.detail())
                .collect(Collectors.joining("\n"));

        return """
                Eres el agente %s y validas ESTÁTICAMENTE el código generado por %s para la misión %s.
                Nadie ejecutó este código.

                OBJETIVO DE TU TAREA: %s

                PLAN DEL LÍDER: %s
                Tecnología: %s | Punto de entrada: %s

                COMMITS POR AGENTE:
                %s

                CHEQUEOS DETERMINISTAS (hechos por Java; no los contradigas):
                %s

                CÓDIGO DEL REPOSITORIO (HEAD = %s):
                %s

                QUÉ DEBES HACER:
                - Revisa coherencia, errores evidentes, archivos faltantes (missingFiles), consistencia entre
                  arquitectura y código, y riesgos técnicos.
                - findings: cada uno con path, severity (BLOCKER si impide que el proyecto tenga sentido como MVP;
                  MAJOR; MINOR) y description.
                - verdict: NO_EVIDENT_ISSUES o ISSUES_FOUND.
                - notValidatableWithoutExecution: lo que NO puede validarse sin ejecutar (compilación, ejecución,
                  rendimiento, jugabilidad...). Nunca vacío.
                - evidence: cita los archivos reales que revisaste con sourceType "INTERNAL", verified true y source
                  exactamente "workspace:%s@<sha>/<ruta>", usando un sha de la lista de commits y una ruta que exista en ese commit.
                - NUNCA afirmes que el juego compila, se ejecuta, funciona o pasa tests.
                """.formatted(
                validation.agentId(), context.team().teamName(), context.missionId(),
                validation.objective(),
                context.plan().summary(), context.plan().techStack(), context.plan().entryPoint(),
                commits, checkLines, headSha, repositoryContext, context.missionId());
    }

    /** Contenido real del repo con tope explícito; lo truncado u omitido queda marcado (Review Focus). */
    static String renderRepositoryContext(Map<String, String> contentsByPath, int totalBudgetChars, int perFileBudgetChars) {

        var out = new StringBuilder();
        var remaining = totalBudgetChars;

        for (var entry : contentsByPath.entrySet()) {

            if (remaining <= 0) {
                out.append("### ").append(entry.getKey())
                        .append(" (NO INCLUIDO: se agotó el presupuesto de revisión — revisión parcial)\n");
                continue;
            }

            var content = entry.getValue() == null ? "" : entry.getValue();
            var limit = Math.min(perFileBudgetChars, remaining);

            out.append("### ").append(entry.getKey()).append("\n");

            if (content.length() > limit) {
                out.append(content, 0, limit)
                        .append("\n[TRUNCADO: archivo revisado parcialmente, ")
                        .append(content.length() - limit).append(" caracteres omitidos]\n");
                remaining -= limit;
            } else {
                out.append(content).append("\n");
                remaining -= content.length();
            }
        }

        return out.toString();
    }

    private String resultsForCeo(
            TeamMissionContext context, List<CommittedWork> committed, List<StaticCheck> checks,
            StaticReviewResult review, String reviewError, StaticValidationStatus status, List<String> failures) {

        var out = new StringBuilder();

        out.append("MISIÓN DE EQUIPO: ").append(context.team().teamName())
                .append(" (").append(context.team().teamId()).append(")\n");
        out.append("PLAN DEL LÍDER: ").append(context.plan().summary())
                .append(" | Tecnología: ").append(context.plan().techStack())
                .append(" | Punto de entrada: ").append(context.plan().entryPoint()).append("\n\n");

        out.append("COMMITS POR AGENTE:\n");
        for (var c : committed) {
            out.append("- ").append(agentName(context, c.agentId())).append(" (").append(c.agentId()).append("): ")
                    .append(c.commitSha()).append(" ").append(c.files()).append("\n");
        }

        out.append("\nCHEQUEOS DETERMINISTAS:\n");
        for (var check : checks) {
            out.append("- ").append(check.check()).append(": ").append(check.status())
                    .append(" — ").append(check.detail()).append("\n");
        }

        out.append("\nREVISIÓN ESTÁTICA: ");
        if (review == null) {
            out.append("no se completó (").append(reviewError).append(")\n");
        } else {
            out.append(review.verdict()).append("\n");
            for (var finding : review.findingsOrEmpty()) {
                if (finding != null) {
                    out.append("- [").append(finding.severity()).append("] ").append(finding.path())
                            .append(": ").append(finding.description()).append("\n");
                }
            }
            out.append("No validable sin ejecución: ").append(review.notValidatableWithoutExecution()).append("\n");
        }

        out.append("\nVALIDATION_STATUS: ").append(status.name()).append("\n");

        if (!failures.isEmpty()) {
            out.append("\nAGENTES_FALLIDOS (resultado PARCIAL — no lo ignores):\n- ")
                    .append(String.join("\n- ", failures)).append("\n");
        }

        out.append("\nREGLA: ").append(NO_EXECUTION_DISCLAIMER)
                .append(" No afirmes que el juego está terminado ni que funciona.\n");

        return out.toString();
    }

    private String verifiableState(
            TeamMissionContext context, List<CommittedWork> committed, List<StaticCheck> checks,
            StaticValidationStatus status, List<String> failures) {

        var passed = checks.stream().filter(StaticCheck::passed).count();

        var out = new StringBuilder();
        out.append("ESTADO VERIFICABLE (generado por Forjai, no por un modelo)\n");
        out.append("Workspace: ").append(workspace.missionWorkspace(context.missionId())).append("\n");

        for (var c : committed) {
            out.append("- ").append(agentName(context, c.agentId())).append(" (").append(c.agentId()).append("): commit ")
                    .append(c.commitSha()).append(" — ").append(c.files().size()).append(" archivo(s): ")
                    .append(String.join(", ", c.files())).append("\n");
        }

        for (var failure : failures) {
            out.append("- Sin commit: ").append(failure).append("\n");
        }

        out.append("Validación estática: ").append(status.name())
                .append(" (").append(passed).append("/").append(checks.size()).append(" chequeos deterministas en PASS)\n");
        out.append(NO_EXECUTION_DISCLAIMER);

        return out.toString();
    }

    private String toJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("No se pudo serializar.", ex);
        }
    }

    private static String safeMessage(Exception ex, String defaultMessage) {
        var cause = ex.getCause() != null && ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
        return cause.getMessage() == null || cause.getMessage().isBlank() ? defaultMessage : cause.getMessage();
    }
}
