package com.aicompany.core.agent;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.StaticReviewResult;
import com.aicompany.core.agent.validation.DevelopmentPathValidationGate;
import com.aicompany.core.agent.validation.EvidenceValidationGate;
import com.aicompany.core.agent.validation.ForbiddenClaimsGuard;
import com.aicompany.core.agent.validation.DependencyManifest;
import com.aicompany.core.agent.validation.ProjectFileGate;
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

    private com.aicompany.core.service.ModelHealthService modelHealth;

    /** Spec salud de modelos (2026-09-28). Setter opcional: los tests que no lo usan no cambian. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setModelHealth(com.aicompany.core.service.ModelHealthService modelHealth) {
        this.modelHealth = modelHealth;
    }

    /** Si el modelo del agente está caído, la tarea la hizo su suplente local: queda marcada. */
    private void markIfDoneByFallback(String taskId, String agentId) {
        if (modelHealth == null) {
            return;
        }
        var model = companyMemory.agentModel(agentId, defaultAgentModel);
        if (modelHealth.isDown(model)) {
            memory.setTaskModelUsed(taskId, modelHealth.fallbackFor(agentId));
        }
    }

    public CompletableFuture<DevelopmentResult> generate(
            String taskId, String missionId, String agentId, String prompt, List<String> ownedPaths) {
        return generate(taskId, missionId, agentId, prompt, ownedPaths, List.of());
    }

    /** expectedProjects: los .csproj del plan (StackProfile.projectFiles), para validar ProjectReference. */
    public CompletableFuture<DevelopmentResult> generate(
            String taskId, String missionId, String agentId, String prompt, List<String> ownedPaths,
            List<String> expectedProjects) {
        return generate(taskId, missionId, agentId, prompt, ownedPaths, expectedProjects, ownedPaths);
    }

    /**
     * requiredPaths: las rutas que deben recibir al menos un archivo. En una corrección son solo las que tienen
     * errores (verificado en vivo con MISSION-SANDBOX-VERIFY-11); lo que no vuelve sigue en el repositorio.
     */
    public CompletableFuture<DevelopmentResult> generate(
            String taskId, String missionId, String agentId, String prompt, List<String> ownedPaths,
            List<String> expectedProjects, List<String> requiredPaths) {

        return submit(taskId, missionId, agentId, () -> executeWithRetries(
                taskId, missionId, agentId, prompt,
                (attemptPrompt, model, agentPrompt) -> discardForeignFiles(
                        collectBatches(agentId, attemptPrompt, agentPrompt, model), ownedPaths,
                        !expectedProjects.isEmpty()),
                result -> verifyGenerated(result, ownedPaths, expectedProjects, requiredPaths),
                "GENERATED"));
    }

    public CompletableFuture<StaticReviewResult> review(
            String taskId, String missionId, String agentId, String prompt, Map<String, Set<String>> filesBySha) {

        return submit(taskId, missionId, agentId, () -> executeWithRetries(
                taskId, missionId, agentId, prompt,
                (attemptPrompt, model, agentPrompt) -> MissingFileClaimGate.withoutExistingMissingFiles(
                        ceoService.reviewStaticWorkspace(agentId, attemptPrompt, agentPrompt, model),
                        filesBySha.values().stream().flatMap(Set::stream).collect(java.util.stream.Collectors.toSet())),
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
                    markIfDoneByFallback(taskId, agentId);

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

    static final int MAX_BATCHES = 8;

    /**
     * Revisión final (I-3): el tamaño del lote depende de la salida del modelo que realmente responde (remoto
     * 16.384 tokens; local o suplente de un remoto caído, 6.144) y tras un corte queda reducido todo el intento.
     */
    static String batchRule(boolean local, boolean reduced) {
        var files = local ? (reduced ? 1 : 2) : (reduced ? 2 : 4);
        var chars = local ? (reduced ? "8.000" : "15.000") : (reduced ? "15.000" : "30.000");
        return "\n\nENTREGA POR LOTES (obligatorio): tu respuesta tiene un límite de salida. Entrega como máximo "
                + files + " archivos o unos " + chars + " caracteres por respuesta, cada archivo COMPLETO. Si te "
                + "faltan archivos, responde con \"complete\": false y \"remainingPaths\" con las rutas que todavía "
                + "vas a entregar; Forjai te pedirá el resto. Cuando ya entregaste todo, \"complete\": true y "
                + "\"remainingPaths\": [].\n";
    }

    private boolean runsLocally(String model) {
        return model == null || !model.startsWith("nvidia") || (modelHealth != null && modelHealth.isDown(model));
    }

    /**
     * Spec 2026-10-01 §5 (verificado en vivo: MISSION-1790905978528, el JSON de Mila se cortó en los 3 intentos).
     * Junta lotes hasta complete=true. Un lote cortado se pide de nuevo con menos archivos; dos cortes seguidos, un
     * lote sin rutas nuevas o más de MAX_BATCHES lotes terminan el intento (executeWithRetries reintenta).
     */
    DevelopmentResult collectBatches(String agentId, String prompt, String agentPrompt, String model) {
        var byPath = new java.util.LinkedHashMap<String, DevelopmentResult.GeneratedFile>();
        var packagesByName = new java.util.LinkedHashMap<String, DevelopmentResult.PackageRequest>();
        var local = runsLocally(model);
        String summary = null;
        var continuation = "";
        var lastRemaining = List.<String>of();
        var reduced = false;
        var cutInARow = 0;

        for (int batch = 1; batch <= MAX_BATCHES; batch++) {
            DevelopmentResult part;
            try {
                part = ceoService.generateDevelopmentArtifact(agentId,
                        prompt + batchRule(local, reduced) + continuation, agentPrompt, model);
                cutInARow = 0;
            } catch (com.aicompany.core.service.TruncatedResponseException ex) {
                cutInARow++;
                if (cutInARow >= 2) {
                    throw new IllegalStateException("La respuesta se cortó dos veces seguidas aun pidiendo menos "
                            + "archivos: entrega de a 1 o 2 archivos por lote.");
                }
                reduced = true;
                continuation = "\n\nTU RESPUESTA ANTERIOR SE CORTÓ por el límite de salida y se descartó: desde ahora "
                        + "entrega lotes más chicos." + received(byPath) + pending(lastRemaining);
                continue;
            }

            if (summary == null) {
                summary = part.summary();
            }
            var newPaths = 0;
            for (var file : part.files() == null ? List.<DevelopmentResult.GeneratedFile>of() : part.files()) {
                if (file == null || file.path() == null) {
                    continue;
                }
                if (!byPath.containsKey(file.path())) {
                    newPaths++;
                }
                byPath.put(file.path(), file);
            }
            // Revisión final (I-5): el modelo repite packages en cada lote; gana el último por nombre.
            part.packagesOrEmpty().stream().filter(java.util.Objects::nonNull)
                    .forEach(p -> packagesByName.put(p.name(), p));

            if (part.isComplete()) {
                return new DevelopmentResult(summary, new ArrayList<>(byPath.values()),
                        new ArrayList<>(packagesByName.values()), true, List.of());
            }
            if (newPaths == 0) {
                throw new IllegalStateException("El lote " + batch + " no trajo archivos nuevos y dijo complete=false: "
                        + "entrega las rutas que faltan o responde complete=true.");
            }
            lastRemaining = part.remainingPathsOrEmpty();
            continuation = "\n\nCONTINUACIÓN (lote " + (batch + 1) + "): no repitas lo ya entregado."
                    + received(byPath) + pending(lastRemaining);
        }

        throw new IllegalStateException("Superaste " + MAX_BATCHES + " lotes sin marcar complete=true.");
    }

    /**
     * Revisión final (I-1): cada lote es una llamada nueva sin memoria del anterior; va el contrato público (Java,
     * PublicApiExtractor) de lo ya entregado para que no se reinventen nombres ni firmas dentro de la misma capa.
     */
    private static String received(java.util.Map<String, DevelopmentResult.GeneratedFile> byPath) {
        if (byPath.isEmpty()) {
            return "";
        }
        var contents = new java.util.LinkedHashMap<String, String>();
        byPath.forEach((path, file) -> contents.put(path, file.content() == null ? "" : file.content()));
        var contract = com.aicompany.core.service.PublicApiExtractor.extract(contents);
        return "\nARCHIVOS YA RECIBIDOS: " + new ArrayList<>(byPath.keySet())
                + (contract == null || contract.isBlank() ? ""
                : "\nCONTRATO PÚBLICO DE LO QUE YA ENTREGASTE (úsalo tal cual, mismos nombres y firmas):\n" + contract);
    }

    private static String pending(List<String> remaining) {
        return remaining.isEmpty() ? "" : "\nTe faltan, según tu lote anterior: " + remaining;
    }


    static final String DISCARDED_NOTE = "[Forjai descartó archivos que no te corresponden (de otro agente, o "
            + ".csproj que genera Forjai): ";

    /**
     * Verificado en vivo (MISSION-SANDBOX-VERIFY-5): el líder, dueño solo de Solution.sln, escribía todo el
     * proyecto y agotaba los reintentos. Un archivo relativo bien formado fuera de los ownedPaths pertenece a
     * otro agente: se descarta y queda anotado en el summary. Rutas absolutas o con "\" siguen yendo a
     * verifyGenerated para corregirse con reintento.
     */
    static DevelopmentResult discardForeignFiles(DevelopmentResult result, List<String> ownedPaths,
                                                 boolean projectsByForjai) {
        if (result == null || result.files() == null) {
            return result;
        }
        var kept = new ArrayList<DevelopmentResult.GeneratedFile>();
        var discarded = new ArrayList<String>();
        for (var file : result.files()) {
            var path = file == null ? null : file.path();
            // Nunca se descarta una ruta insegura: ".." o ".git" tienen que llegar al gate que falla sin reintento.
            var unsafe = path == null || java.util.Arrays.stream(path.split("[/\\\\]"))
                    .anyMatch(segment -> segment.equals("..") || segment.equalsIgnoreCase(".git"));
            var wellFormed = !unsafe && !path.startsWith("/") && !path.matches("^[a-zA-Z]:.*") && !path.contains("\\");
            // Verificado en vivo (MISSION-SANDBOX-VERIFY-4 y -6): los .csproj son de Forjai (ProjectScaffold).
            var projectFile = projectsByForjai && path != null && path.endsWith(".csproj");
            if (wellFormed && (projectFile || !OwnedPaths.coveredByAny(ownedPaths, path))) {
                discarded.add(path);
            } else {
                kept.add(file);
            }
        }
        if (discarded.isEmpty()) {
            return result;
        }
        var summary = (result.summary() == null ? "" : result.summary()) + "\n" + DISCARDED_NOTE + discarded + "]";
        // Revisión final (m-11): descartar un archivo ajeno no puede borrar los paquetes pedidos.
        return new DevelopmentResult(summary, kept, result.packagesOrEmpty());
    }

    private Verdict verifyGenerated(DevelopmentResult result, List<String> ownedPaths, List<String> expectedProjects,
                                    List<String> requiredPaths) {

        var discardedNote = result == null || result.summary() == null || !result.summary().contains(DISCARDED_NOTE)
                ? "" : " " + result.summary().substring(result.summary().indexOf(DISCARDED_NOTE));

        if (result == null || result.files() == null || result.files().isEmpty()) {
            return new Verdict(List.of(), List.of("Debes devolver al menos un archivo en files dentro de tus rutas "
                    + ownedPaths + "." + discardedNote));
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

        // Parte 3: paquetes pedidos solo con versión exacta; pubspec.yaml con dependencias exactas de pub.dev.
        retryable.addAll(DependencyManifest.validateRequests(result.packagesOrEmpty()));
        result.files().stream()
                .filter(f -> f != null && "pubspec.yaml".equals(f.path()))
                .findFirst()
                .ifPresent(f -> retryable.addAll(DependencyManifest.pubspec(f.content()).errors()));

        // Los .csproj esperados los genera Forjai (ProjectScaffold): el agente no puede pisarlos.
        retryable.addAll(ProjectFileGate.check(result.files(), expectedProjects, expectedProjects));

        // Spec 2026-10-01 §5: código omitido ("...", "resto del código") → reintento con la línea exacta.
        retryable.addAll(com.aicompany.core.agent.validation.ElidedCodeGate.check(result.files()));

        // Verificado en vivo: dueños de dos capas entregaban solo una (o solo el .csproj).
        var paths = result.files().stream().filter(Objects::nonNull).map(f -> f.path()).toList();
        for (var owned : requiredPaths) {
            if (paths.stream().noneMatch(path -> OwnedPaths.coveredByAny(List.of(owned), path))) {
                retryable.add("No escribiste ningún archivo en \"" + owned + "\": también es tu responsabilidad; "
                        + "devuelve TODOS tus archivos (los de todas tus rutas) en files, no solo los corregidos."
                        + discardedNote);
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
                Corrige estos errores y devuelve el resultado COMPLETO (todos tus archivos o campos, no solo
                las partes corregidas; lo que no devuelvas se pierde):
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
