package com.aicompany.core.agent;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.StaticReviewResult;
import com.aicompany.core.agent.validation.DevelopmentPathValidationGate;
import com.aicompany.core.agent.validation.EvidenceValidationGate;
import com.aicompany.core.agent.validation.ForbiddenClaimsGuard;
import com.aicompany.core.agent.validation.MissingFileClaimGate;
import com.aicompany.core.agent.validation.OwnedPaths;
import com.aicompany.core.agent.validation.RepositoryEvidenceGate;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import com.aicompany.core.service.PromptMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Espejo de AgentRuntime para los contratos de desarrollo (spec §6-7) —
 * AgentRuntime no se toca. Mismo reintento (MAX_RESULT_RETRIES + 1), mismo
 * WORKING/IDLE con finally, feedback determinista como CORRECCIÓN. Errores
 * "fatales" (ruta insegura) fallan sin reintento.
 */
@Service
public class DevelopmentRuntime {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentRuntime.class);

    private static final int MAX_RESULT_RETRIES = 2;
    private static final Set<String> VERDICTS = Set.of("NO_EVIDENT_ISSUES", "ISSUES_FOUND");
    private static final Set<String> SEVERITIES = Set.of("BLOCKER", "MAJOR", "MINOR");

    @FunctionalInterface
    interface Attempt<T> {
        T call(String prompt, String model, String agentPrompt);
    }

    record Verdict(List<String> fatal, List<String> retryable) {
        boolean isOk() {
            return fatal.isEmpty() && retryable.isEmpty();
        }
    }

    private final CeoService ceoService;
    private final MissionMemoryService memory;
    private final CompanyMemoryService companyMemory;
    private final PromptMemoryService promptMemory;
    private final String defaultAgentModel;
    private final Executor agentTaskExecutor;
    private final CompanyEventPublisher events;
    private final DevelopmentPathValidationGate pathGate;
    private final EvidenceValidationGate evidenceGate;
    private final RepositoryEvidenceGate repositoryEvidenceGate;
    private final ForbiddenClaimsGuard forbiddenClaimsGuard;
    private final MissingFileClaimGate missingFileClaimGate;
    private final JsonMapper jsonMapper;

    public DevelopmentRuntime(
            CeoService ceoService,
            MissionMemoryService memory,
            CompanyMemoryService companyMemory,
            PromptMemoryService promptMemory,
            @Value("${ollama.agent-model}") String defaultAgentModel,
            @Qualifier("agentTaskExecutor") Executor agentTaskExecutor,
            CompanyEventPublisher events,
            DevelopmentPathValidationGate pathGate,
            EvidenceValidationGate evidenceGate,
            RepositoryEvidenceGate repositoryEvidenceGate,
            ForbiddenClaimsGuard forbiddenClaimsGuard,
            MissingFileClaimGate missingFileClaimGate,
            JsonMapper jsonMapper) {

        this.ceoService = ceoService;
        this.memory = memory;
        this.companyMemory = companyMemory;
        this.promptMemory = promptMemory;
        this.defaultAgentModel = defaultAgentModel;
        this.agentTaskExecutor = agentTaskExecutor;
        this.events = events;
        this.pathGate = pathGate;
        this.evidenceGate = evidenceGate;
        this.repositoryEvidenceGate = repositoryEvidenceGate;
        this.forbiddenClaimsGuard = forbiddenClaimsGuard;
        this.missingFileClaimGate = missingFileClaimGate;
        this.jsonMapper = jsonMapper;
    }

    public CompletableFuture<DevelopmentResult> generate(
            String taskId, String missionId, String agentId, String prompt, List<String> ownedPaths) {

        return submit(taskId, missionId, agentId, () -> executeWithRetries(
                taskId, missionId, agentId, prompt,
                (attemptPrompt, model, agentPrompt) ->
                        ceoService.generateDevelopmentArtifact(agentId, attemptPrompt, agentPrompt, model),
                result -> verifyGenerated(result, ownedPaths),
                "GENERATED"));
    }

    public CompletableFuture<StaticReviewResult> review(
            String taskId, String missionId, String agentId, String prompt, Map<String, Set<String>> filesBySha) {

        return submit(taskId, missionId, agentId, () -> executeWithRetries(
                taskId, missionId, agentId, prompt,
                (attemptPrompt, model, agentPrompt) ->
                        ceoService.reviewStaticWorkspace(agentId, attemptPrompt, agentPrompt, model),
                review -> verifyReview(review, missionId, filesBySha),
                "COMPLETED"));
    }

    private <T> CompletableFuture<T> submit(String taskId, String missionId, String agentId, Supplier<T> work) {
        try {
            return CompletableFuture.supplyAsync(work, agentTaskExecutor);
        } catch (Exception ex) {
            var message = safeMessage(ex, "No se pudo iniciar la tarea.");
            memory.updateTask(taskId, "FAILED", message);
            events.publishTask("EMPRESA_TASK_FAILED", taskId, missionId, agentId, "FAILED", message);
            return CompletableFuture.failedFuture(ex);
        }
    }

    private <T> T executeWithRetries(
            String taskId, String missionId, String agentId, String prompt,
            Attempt<T> attempt, Function<T, Verdict> verify, String successStatus) {

        memory.updateTask(taskId, "RUNNING", "Agente iniciado.");

        var model = companyMemory.agentModel(agentId, defaultAgentModel);
        var agentPrompt = promptMemory.activePrompt(agentId);

        memory.setAgentStatus(agentId, "WORKING");
        events.publishTask("EMPRESA_TASK_STARTED", taskId, missionId, agentId, "RUNNING", "Agente iniciado.");

        try {

            String feedback = null;

            for (int i = 0; i <= MAX_RESULT_RETRIES; i++) {

                if (i > 0) {
                    events.publishTask("EMPRESA_TASK_RETRY", taskId, missionId, agentId, "RETRYING",
                            "Reintentando. Intento " + (i + 1) + " de " + (MAX_RESULT_RETRIES + 1));
                }

                var attemptPrompt = feedback == null ? prompt : prompt + correctionBlock(feedback);

                T result;

                try {
                    result = attempt.call(attemptPrompt, model, agentPrompt);
                } catch (Exception ex) {
                    feedback = "- " + safeMessage(ex, "El agente no devolvió una respuesta procesable.");
                    continue;
                }

                var verdict = verify.apply(result);

                if (!verdict.fatal().isEmpty()) {
                    throw new IllegalStateException("Resultado rechazado sin reintento: "
                            + String.join("; ", verdict.fatal()));
                }

                if (verdict.isOk()) {

                    var json = toJson(result);
                    memory.updateTask(taskId, successStatus, json);

                    if ("COMPLETED".equals(successStatus)) {
                        events.publishTask("EMPRESA_TASK_COMPLETED", taskId, missionId, agentId, "COMPLETED", json);
                    }

                    log.info("TASK {} - agent {} produced a valid {}", taskId, agentId, successStatus);

                    return result;
                }

                feedback = "- " + String.join("\n- ", verdict.retryable());
            }

            throw new IllegalStateException("El agente " + agentId + " no produjo un resultado válido después de "
                    + (MAX_RESULT_RETRIES + 1) + " intentos:\n" + feedback);

        } catch (RuntimeException ex) {

            var message = safeMessage(ex, "Error inesperado");
            memory.updateTask(taskId, "FAILED", message);
            events.publishTask("EMPRESA_TASK_FAILED", taskId, missionId, agentId, "FAILED", message);
            log.error("TASK {} - agent {} failed: {}", taskId, agentId, message);
            throw ex;

        } finally {
            memory.setAgentStatus(agentId, "IDLE");
        }
    }

    private Verdict verifyGenerated(DevelopmentResult result, List<String> ownedPaths) {

        if (result == null || result.files() == null || result.files().isEmpty()) {
            return new Verdict(List.of(), List.of("Debes devolver al menos un archivo en files."));
        }

        var gate = pathGate.validate(result);

        if (!gate.valid()) {
            return new Verdict(gate.errors(), List.of());
        }

        var retryable = new ArrayList<String>();

        for (var file : result.files().stream().filter(Objects::nonNull).toList()) {

            if (file.path().startsWith("/") || file.path().matches("^[a-zA-Z]:.*")) {
                retryable.add("Ruta absoluta \"" + file.path() + "\": usa una ruta relativa al proyecto, p. ej. \""
                        + file.path().replaceFirst("^([a-zA-Z]:)?[/\\\\]+", "") + "\".");
            } else if (file.path().contains("\\")) {
                retryable.add("Usa \"/\" como separador de rutas, no \"\\\": \"" + file.path() + "\".");
            } else if (!OwnedPaths.coveredByAny(ownedPaths, file.path())) {
                retryable.add("La ruta \"" + file.path() + "\" está fuera de tus ownedPaths " + ownedPaths + ".");
            }
        }

        return new Verdict(List.of(), retryable);
    }

    private Verdict verifyReview(StaticReviewResult review, String missionId, Map<String, Set<String>> filesBySha) {

        if (review == null) {
            return new Verdict(List.of(), List.of("La revisión está vacía."));
        }

        var errors = new ArrayList<String>();

        if (!VERDICTS.contains(review.verdict())) {
            errors.add("verdict debe ser NO_EVIDENT_ISSUES o ISSUES_FOUND.");
        }

        for (var finding : review.findingsOrEmpty()) {
            if (finding == null || !SEVERITIES.contains(finding.severity())) {
                errors.add("Cada finding debe tener severity BLOCKER, MAJOR o MINOR.");
            }
        }

        if (review.notValidatableWithoutExecution() == null || review.notValidatableWithoutExecution().isEmpty()) {
            errors.add("notValidatableWithoutExecution no puede estar vacío: esta fase no ejecuta código.");
        }

        var evidence = evidenceGate.validate(review.evidence());
        if (!evidence.valid()) {
            errors.addAll(evidence.errors());
        }

        errors.addAll(repositoryEvidenceGate.validate(review, missionId, filesBySha));

        var texts = new ArrayList<String>();
        if (review.architectureConsistency() != null) {
            texts.add(review.architectureConsistency());
        }
        review.findingsOrEmpty().stream()
                .filter(Objects::nonNull)
                .map(StaticReviewResult.Finding::description)
                .forEach(texts::add);

        errors.addAll(forbiddenClaimsGuard.violations(texts));

        // Los archivos del repo son la unión de los commits de la misión (el último contiene todo HEAD).
        var repositoryFiles = new java.util.HashSet<String>();
        filesBySha.values().forEach(repositoryFiles::addAll);
        errors.addAll(missingFileClaimGate.validate(review, repositoryFiles));

        return new Verdict(List.of(), errors);
    }

    private String correctionBlock(String feedback) {
        return """

                CORRECCIÓN DEL INTENTO ANTERIOR

                El resultado anterior fue rechazado por validaciones deterministas.
                Corrige únicamente estos errores:
                %s
                """.formatted(feedback);
    }

    private String toJson(Object value) {
        try {
            return jsonMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("No se pudo serializar el resultado.", ex);
        }
    }

    private String safeMessage(Exception ex, String defaultMessage) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? defaultMessage : ex.getMessage();
    }
}
