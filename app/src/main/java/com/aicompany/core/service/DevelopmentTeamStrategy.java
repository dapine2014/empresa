package com.aicompany.core.service;

import com.aicompany.core.agent.DevelopmentRuntime;
import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.StaticReviewResult;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.agent.validation.CompilerErrorParser;
import com.aicompany.core.agent.validation.DependencyManifest;
import com.aicompany.core.agent.validation.MissingUsingFixer;
import com.aicompany.core.agent.validation.OwnedPaths;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.DependencyRef;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.ProjectScaffold;
import com.aicompany.core.model.SandboxResult;
import com.aicompany.core.model.StackProfile;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Engineering (spec §6-8): código real en paralelo → un commit por agente
 * (secuencial, en el orden del plan) → chequeos deterministas → revisión
 * + sandbox (build, tests y arranque reales, spec 2026-09-26 §4) → revisión
 * estática del validador → reporte para el CEO + "Estado verificable".
 * company-core nunca ejecuta el código: lo hace el sandbox-runner aislado.
 */
@Service
public class DevelopmentTeamStrategy implements TeamExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentTeamStrategy.class);

    // Debe entrar en CeoService.TEAM_CONTEXT_WINDOW_TOKENS (16k) junto con el prompt y la respuesta.
    static final int REVIEW_TOTAL_BUDGET_CHARS = 24_000;
    static final int REVIEW_FILE_BUDGET_CHARS = 6_000;
    static final int REMOTE_REVIEW_TOTAL_BUDGET_CHARS = 120_000;
    static final int REMOTE_REVIEW_FILE_BUDGET_CHARS = 30_000;
    static final String NO_EXECUTION_DISCLAIMER =
            "No se verificó la ejecución: no se puede afirmar que el producto compile, se ejecute o pase tests.";
    static final int FAILED_STEP_TAIL_CHARS = 1_500;

    private final MissionMemoryService memory;
    private final DevelopmentRuntime runtime;
    private final DevelopmentWorkspaceService workspace;
    private final StaticWorkspaceValidator staticValidator;
    private final CompanyEventPublisher events;
    private final JsonMapper jsonMapper;
    private final SandboxRunnerClient sandbox;
    private final DependencyService dependencies;

    public DevelopmentTeamStrategy(
            MissionMemoryService memory,
            DevelopmentRuntime runtime,
            DevelopmentWorkspaceService workspace,
            StaticWorkspaceValidator staticValidator,
            CompanyEventPublisher events,
            JsonMapper jsonMapper,
            SandboxRunnerClient sandbox,
            DependencyService dependencies) {

        this.memory = memory;
        this.runtime = runtime;
        this.workspace = workspace;
        this.staticValidator = staticValidator;
        this.events = events;
        this.jsonMapper = jsonMapper;
        this.sandbox = sandbox;
        this.dependencies = dependencies;
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

        var expectedProjects = plan.profile().map(p -> p.projectFiles(plan.contextNames())).orElse(List.of());
        CommittedWork scaffold;
        try {
            scaffold = commitProjectScaffold(context);
        } catch (IllegalStateException ex) {
            // Sin estructura de proyectos no hay nada que generar: ninguna tarea queda colgada en PENDING.
            for (var task : plan.tasksOrEmpty().stream().filter(java.util.Objects::nonNull)
                    .toList()) {
                var id = taskId(missionId, task.agentId());
                memory.updateTask(id, "FAILED", ex.getMessage());
                events.publishTask("EMPRESA_TASK_FAILED", id, missionId, task.agentId(), "FAILED", ex.getMessage());
            }
            throw ex;
        }

        // Decisión del fundador (revisión 3, tras MISSION-SANDBOX-VERIFY-1..8): en paralelo cada agente escribía
        // contra clases que nunca vio y el código no compilaba. Se genera por capas (domain → application →
        // infrastructure → api/game/presentation → tests) y cada agente ve el código ya commiteado.
        var ordered = orderByLayer(work, plan);
        var failures = new ArrayList<String>();
        var committed = new ArrayList<CommittedWork>();
        var generationHead = scaffold == null ? null : scaffold.commitSha();
        var packagesByTask = new LinkedHashMap<PlannedTask, List<DevelopmentResult.PackageRequest>>();

        for (int i = 0; i < ordered.size(); i++) {

            var task = ordered.get(i);
            var id = taskId(missionId, task.agentId());

            progress.advance(MissionStatus.WAITING_AGENT_RESULTS, 30 + (40 * i / ordered.size()), "Desarrollo por capas",
                    agentName(context, task.agentId()) + " (" + (i + 1) + "/" + ordered.size() + ") está generando "
                            + task.ownedPathsOrEmpty() + ".");

            DevelopmentResult result;
            try {
                result = runtime.generate(id, missionId, task.agentId(),
                        buildWorkPrompt(context, task) + existingCode(missionId, generationHead),
                        task.ownedPathsOrEmpty(), expectedProjects).join();
            } catch (Exception ex) {
                failures.add(task.agentId() + ": " + safeMessage(ex, "no generó código"));
                continue;
            }

            try {

                var record = workspace.commitAgentWork(missionId, id, task.agentId(),
                        agentName(context, task.agentId()), result);

                memory.recordTaskArtifact(id, workspace.missionWorkspace(missionId).toString(),
                        record.sha(), record.files());
                memory.updateTask(id, "COMPLETED", toJson(result));

                events.publish("EMPRESA_TASK_COMMITTED", missionId, id, task.agentId(),
                        Map.of("commitSha", record.sha(), "files", record.files()));
                events.publishTask("EMPRESA_TASK_COMPLETED", id, missionId, task.agentId(), "COMPLETED",
                        "Commit " + record.sha());

                committed.add(new CommittedWork(id, task.agentId(), record.sha(), record.files()));
                generationHead = record.sha();
                if (!result.packagesOrEmpty().isEmpty()) {
                    packagesByTask.put(task, result.packagesOrEmpty());
                }

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

        // Parte 3: dependencias gobernadas. .NET: los paquetes pedidos entran al .csproj de la capa del agente
        // (commit de Forjai); Flutter: salen del pubspec.yaml. Lo que no está aprobado se descarga aislado y pasa
        // la política; si queda algo pendiente, VERIFY no corre.
        var dependencyCommit = commitRequestedPackages(context, packagesByTask);
        if (dependencyCommit != null) {
            committed.add(dependencyCommit);
        }
        var profileForDeps = plan.profile().orElse(null);
        var pubspec = profileForDeps == StackProfile.FLUTTER_WEB_APP
                ? readOrNull(missionId, committed.get(committed.size() - 1).commitSha(), "pubspec.yaml") : null;
        var requestedRefs = profileForDeps == null ? List.<DependencyRef>of() : dependencyRefs(profileForDeps,
                packagesByTask.values().stream().flatMap(List::stream).toList(), pubspec);
        var dependencyOutcome = requestedRefs.isEmpty()
                ? new DependencyService.Outcome(List.of(), List.of(), null)
                : dependencies.resolve(missionId, "engineering", requestedRefs);

        progress.advance(MissionStatus.EVALUATING, 75, "Validación estática",
                "Chequeos deterministas y revisión estática de " + validation.agentId() + ".");

        // Verificado en vivo (MISSION-SANDBOX-VERIFY-9): los archivos del scaffold (Forjai) también son permitidos.
        var allowedPaths = java.util.stream.Stream.concat(
                work.stream().flatMap(t -> t.ownedPathsOrEmpty().stream()),
                scaffold == null ? java.util.stream.Stream.<String>empty() : scaffold.files().stream()).toList();
        var profile = plan.profile().orElse(null);
        var checks = staticValidator.validate(missionId, committed, profile, plan.contextNames(), allowedPaths);

        var headSha = committed.get(committed.size() - 1).commitSha();
        SandboxResult sandboxResult = null;
        String sandboxError = null;
        var repairRounds = 0;
        var autofixRounds = 0;

        // Ciclo de corrección mínimo (MISSION-SANDBOX-VERIFY-10): si no compila, los errores reales del compilador
        // vuelven al dueño de cada archivo, se commitea la corrección y se verifica de nuevo (hasta MAX_REPAIR_ROUNDS).
        for (int round = 0; ; round++) {

            // Review Focus: si los chequeos deterministas fallaron, no se gastan minutos compilando.
            if (!checks.stream().allMatch(StaticCheck::passed) || profile == null) {
                sandboxResult = null;
                sandboxError = "Sandbox omitido: los chequeos deterministas fallaron.";
                break;
            }

            if (!dependencyOutcome.pending().isEmpty() || dependencyOutcome.error() != null) {
                sandboxResult = null;
                sandboxError = "Dependencias pendientes de aprobación (🔴): "
                        + dependencyOutcome.pending().stream().map(DependencyRef::id).toList()
                        + (dependencyOutcome.error() == null ? "" : " — " + dependencyOutcome.error());
                break;
            }

            progress.advance(MissionStatus.EVALUATING, 80, "Sandbox", round == 0
                    ? "Compilando, corriendo tests y arrancando el código en el sandbox."
                    : "Ronda de corrección " + round + ": verificando de nuevo en el sandbox.");
            var verified = sandbox.verify(missionId, headSha, profile.name());
            sandboxResult = verified.orElse(null);
            sandboxError = verified.isEmpty() ? sandbox.lastError() : null;

            if (sandboxResult == null || sandboxResult.passed()) {
                break;
            }

            // Verificado en vivo (MISSION-SANDBOX-VERIFY-12): los "using" faltantes los corrige Forjai sin modelo.
            if (autofixRounds < MAX_AUTOFIX_ROUNDS) {
                var autofix = commitMissingUsings(missionId, headSha, profile, plan.contextNames(), sandboxResult);
                if (autofix != null) {
                    committed.add(autofix);
                    headSha = autofix.commitSha();
                    autofixRounds++;
                    checks = staticValidator.validate(missionId, committed, profile, plan.contextNames(), allowedPaths);
                    continue;
                }
            }

            if (repairRounds >= MAX_REPAIR_ROUNDS) {
                break;
            }

            var errorsByTask = compileErrorsByOwner(sandboxResult, ordered);
            if (errorsByTask.isEmpty()) {
                break;
            }

            var repaired = false;
            for (var entry : errorsByTask.entrySet()) {
                var task = entry.getKey();
                var id = taskId(missionId, task.agentId());
                try {
                    var current = currentContents(missionId, headSha, entry.getValue());
                    var required = task.ownedPathsOrEmpty().stream().filter(owned -> entry.getValue().stream()
                            .anyMatch(e -> OwnedPaths.coveredByAny(List.of(owned), e.path()))).toList();
                    var basePrompt = buildWorkPrompt(context, task) + repairBlock(entry.getValue(), current)
                            + repositoryCode(missionId, headSha, "CÓDIGO ACTUAL DEL REPOSITORIO (incluye el tuyo, "
                            + "commit " + headSha + "); devuelve tus archivos corregidos y completos:");
                    DevelopmentResult result = null;
                    var insist = "";
                    // Verificado en vivo (MISSION-SANDBOX-VERIFY-14): la corrección devolvía el archivo idéntico.
                    for (int attempt = 0; attempt < REPAIR_ATTEMPTS && result == null; attempt++) {
                        var candidate = runtime.generate(id, missionId, task.agentId(), basePrompt + insist,
                                task.ownedPathsOrEmpty(), expectedProjects, required).join();
                        // Verificado en vivo (MISSION-SANDBOX-VERIFY-16): se compara TODO lo devuelto contra HEAD.
                        var returned = candidate == null || candidate.files() == null ? List.<String>of()
                                : candidate.files().stream().filter(Objects::nonNull)
                                        .map(DevelopmentResult.GeneratedFile::path).toList();
                        if (unchanged(candidate, headContents(missionId, headSha, returned))) {
                            insist = "\n\nATENCIÓN: devolviste tus archivos SIN CAMBIOS y el compilador sigue fallando en "
                                    + "las líneas indicadas arriba. Cambia esas líneas.";
                        } else {
                            result = candidate;
                        }
                    }
                    if (result == null) {
                        throw new IllegalStateException("devolvió los archivos sin cambios");
                    }
                    var record = workspace.commitAgentWork(missionId, id, task.agentId(),
                            agentName(context, task.agentId()), result);
                    memory.recordTaskArtifact(id, workspace.missionWorkspace(missionId).toString(),
                            record.sha(), record.files());
                    memory.updateTask(id, "COMPLETED", toJson(result));
                    events.publish("EMPRESA_TASK_COMMITTED", missionId, id, task.agentId(),
                            Map.of("commitSha", record.sha(), "files", record.files(), "repairRound", round + 1));
                    committed.add(new CommittedWork(id, task.agentId(), record.sha(), record.files()));
                    headSha = record.sha();
                    repaired = true;
                } catch (Exception ex) {
                    // Verificado en vivo (MISSION-SANDBOX-VERIFY-11): el commit anterior sigue siendo válido.
                    var message = "Corrección fallida (" + safeMessage(ex, "sin detalle") + "); se conserva el commit "
                            + "anterior.";
                    memory.updateTask(id, "COMPLETED", message);
                    log.warn("MISSION {} - repair of {} failed: {}", missionId, task.agentId(), message);
                }
            }

            if (!repaired) {
                break;
            }

            repairRounds++;
            checks = staticValidator.validate(missionId, committed, profile, plan.contextNames(), allowedPaths);
        }

        if (sandboxResult != null) {
            memory.recordSandboxResult(validationTaskId, toJson(sandboxResult));
            memory.recordEvidence(validationTaskId, missionId, "sandbox", sandboxEvidence(missionId, headSha, sandboxResult));
            events.publish("EMPRESA_SANDBOX_VERIFICATION_COMPLETED", missionId, validationTaskId, "sandbox",
                    Map.of("overall", sandboxResult.overall(), "testsPassed", sandboxResult.testsPassed(),
                            "testsFailed", sandboxResult.testsFailed()));
        }

        StaticReviewResult review = null;
        String reviewError = null;
        var reviewStarted = false;

        try {

            var filesBySha = new LinkedHashMap<String, Set<String>>();

            for (var item : committed) {
                filesBySha.put(item.commitSha(), new HashSet<>(workspace.filesAtCommit(missionId, item.commitSha())));
            }

            var contents = new LinkedHashMap<String, String>();
            for (var path : workspace.filesAtCommit(missionId, headSha).stream().sorted().toList()) {
                contents.put(path, workspace.readFileAtCommit(missionId, headSha, path));
            }

            var repositoryContext = renderRepositoryContext(contents,
                    reviewBudget(validatorModel(context, validation.agentId())).total(),
                    reviewBudget(validatorModel(context, validation.agentId())).perFile());

            reviewStarted = true;
            review = runtime.review(validationTaskId, missionId, validation.agentId(),
                    buildReviewPrompt(context, validation, committed, checks, headSha, repositoryContext,
                            sandboxSummary(sandboxResult, sandboxError)),
                    filesBySha).join();

        } catch (Exception ex) {
            reviewError = safeMessage(ex, "La revisión estática no se completó.");
            log.warn("MISSION {} - static review did not complete: {}", missionId, reviewError);

            // Verificado en vivo: si falla antes de lanzar la revisión, DevelopmentRuntime nunca marca la tarea.
            if (!reviewStarted) {
                memory.updateTask(validationTaskId, "FAILED", "No se pudo preparar la revisión: " + reviewError);
                events.publishTask("EMPRESA_TASK_FAILED", validationTaskId, missionId, validation.agentId(), "FAILED",
                        reviewError);
            }
        }

        // Verificado en vivo (MISSION-SANDBOX-VERIFY-9): los archivos del scaffold no son trabajo de un agente.
        review = withoutFindingsOn(review, scaffold == null ? List.of() : scaffold.files());

        var status = StaticValidationStatus.compute(checks, review, sandboxResult);

        memory.recordStaticValidation(validationTaskId, status.name(), toJson(checks));

        if (review != null && review.evidence() != null && !review.evidence().isEmpty()) {
            memory.recordEvidence(validationTaskId, missionId, validation.agentId(), review.evidence());
        }

        var failedChecks = checks.stream().filter(c -> !c.passed()).count();

        events.publish("EMPRESA_STATIC_VALIDATION_COMPLETED", missionId, validationTaskId, validation.agentId(),
                Map.of("validationStatus", status.name(), "failedChecks", failedChecks));

        return new TeamExecutionResult.Development(
                resultsForCeo(context, committed, checks, review, reviewError, status, failures,
                        sandboxSummary(sandboxResult, sandboxError)),
                verifiableState(context, scaffold, committed, checks, status, failures, sandboxResult, sandboxError,
                        repairRounds, autofixRounds, dependencyOutcome));
    }

    private static StaticReviewResult withoutFindingsOn(StaticReviewResult review, List<String> generatedByForjai) {
        if (review == null || generatedByForjai.isEmpty()) {
            return review;
        }
        var kept = review.findingsOrEmpty().stream()
                .filter(f -> f == null || !generatedByForjai.contains(f.path()))
                .toList();
        return new StaticReviewResult(review.verdict(), kept, review.missingFiles(), review.architectureConsistency(),
                review.notValidatableWithoutExecution(), review.evidence());
    }

    /** Parte 3: paquetes pedidos (.NET, del resultado de cada agente) o del pubspec.yaml (Flutter). */
    static List<DependencyRef> dependencyRefs(StackProfile profile, List<DevelopmentResult.PackageRequest> requested,
                                              String pubspec) {
        if (profile.ecosystem() == StackProfile.Ecosystem.PUB) {
            return pubspec == null ? List.of() : DependencyManifest.pubspec(pubspec).deps();
        }
        return requested.stream().map(p -> new DependencyRef("NUGET", p.name(), p.version())).distinct().toList();
    }

    /** Regenera y commitea (autor Forjai) solo los .csproj de las capas que pidieron paquetes. */
    private CommittedWork commitRequestedPackages(TeamMissionContext context,
                                                  Map<PlannedTask, List<DevelopmentResult.PackageRequest>> packagesByTask) {
        var plan = context.plan();
        var profile = plan.profile().orElse(null);
        if (profile == null || profile.ecosystem() != StackProfile.Ecosystem.NUGET || packagesByTask.isEmpty()) {
            return null;
        }
        var byProject = new LinkedHashMap<String, List<DependencyRef>>();
        for (var entry : packagesByTask.entrySet()) {
            // Verificado en vivo (MISSION-DEPS-VERIFY-4): va a todos los proyectos de las capas del agente.
            for (var project : projectsOf(profile, plan.contextNames(), entry.getKey().ownedPathsOrEmpty())) {
                byProject.computeIfAbsent(project, k -> new ArrayList<>()).addAll(
                        entry.getValue().stream().map(p -> new DependencyRef("NUGET", p.name(), p.version())).toList());
            }
        }
        if (byProject.isEmpty()) {
            return null;
        }
        var files = ProjectScaffold.generate(profile, plan.contextNames(), byProject).stream()
                .filter(f -> byProject.containsKey(f.path()))
                .toList();
        try {
            var id = context.missionId() + "-DEPENDENCIES";
            var record = workspace.commitAgentWork(context.missionId(), id, "forjai", "Forjai",
                    new DevelopmentResult("Forjai agregó los paquetes pedidos a " + byProject.keySet(), files));
            return new CommittedWork(id, "forjai", record.sha(), record.files());
        } catch (Exception ex) {
            log.warn("MISSION {} - could not commit requested packages: {}", context.missionId(), ex.getMessage());
            return null;
        }
    }

    static List<String> projectsOf(StackProfile profile, List<String> contexts, List<String> ownedPaths) {
        return profile.projectFiles(contexts).stream()
                .filter(project -> OwnedPaths.coveredByAny(ownedPaths, project))
                .toList();
    }

    record ReviewBudget(int total, int perFile) {
    }

    /**
     * Verificado en vivo (MISSION-DEPS-VERIFY-4): el tope pensado para los 16K de contexto de qwen3:8b dejaba archivos
     * fuera de la revisión con modelos remotos de contexto grande.
     */
    static ReviewBudget reviewBudget(String validatorModel) {
        return validatorModel != null && validatorModel.startsWith("nvidia")
                ? new ReviewBudget(REMOTE_REVIEW_TOTAL_BUDGET_CHARS, REMOTE_REVIEW_FILE_BUDGET_CHARS)
                : new ReviewBudget(REVIEW_TOTAL_BUDGET_CHARS, REVIEW_FILE_BUDGET_CHARS);
    }

    private String readOrNull(String missionId, String sha, String path) {
        try {
            return workspace.readFileAtCommit(missionId, sha, path);
        } catch (Exception ex) {
            return null;
        }
    }

    static final int MAX_REPAIR_ROUNDS = 2;
    static final int MAX_AUTOFIX_ROUNDS = 3;

    private static List<CompilerErrorParser.CompilerError> compileErrors(SandboxResult result) {
        var failed = result.stepsOrEmpty().stream()
                .filter(step -> "FAIL".equals(step.status()) || "TIMEOUT".equals(step.status()))
                .findFirst();
        if (failed.isEmpty() || !List.of("restore", "build").contains(failed.get().name())) {
            return List.of();
        }
        return CompilerErrorParser.parse(failed.get().outputTail());
    }

    /** Commit "Forjai (auto-fix)" con los using faltantes, o null si no hay nada seguro que corregir. */
    private CommittedWork commitMissingUsings(String missionId, String headSha, StackProfile profile,
                                              List<String> contexts, SandboxResult result) {
        var errors = compileErrors(result);
        if (errors.isEmpty()) {
            return null;
        }
        try {
            var files = new LinkedHashMap<String, String>();
            for (var path : workspace.filesAtCommit(missionId, headSha)) {
                if (path.endsWith(".cs")) {
                    files.put(path, workspace.readFileAtCommit(missionId, headSha, path));
                }
            }
            var fixes = MissingUsingFixer.fix(profile, contexts, files, errors);
            if (fixes.isEmpty()) {
                return null;
            }
            var generated = fixes.entrySet().stream()
                    .map(e -> new DevelopmentResult.GeneratedFile(e.getKey(), e.getValue())).toList();
            var record = workspace.commitAgentWork(missionId, missionId + "-AUTOFIX", "forjai", "Forjai",
                    new DevelopmentResult("Forjai agregó using faltantes (CS0246) en " + fixes.keySet(), generated));
            return new CommittedWork(missionId + "-AUTOFIX", "forjai", record.sha(), record.files());
        } catch (Exception ex) {
            log.warn("MISSION {} - automatic using fix failed: {}", missionId, ex.getMessage());
            return null;
        }
    }

    /** Errores del compilador del primer paso fallido (restore/build), agrupados por dueño en orden de capas. */
    private static LinkedHashMap<PlannedTask, List<CompilerErrorParser.CompilerError>> compileErrorsByOwner(
            SandboxResult result, List<PlannedTask> ordered) {
        var byOwner = new LinkedHashMap<PlannedTask, List<CompilerErrorParser.CompilerError>>();
        var failed = result.stepsOrEmpty().stream()
                .filter(step -> "FAIL".equals(step.status()) || "TIMEOUT".equals(step.status()))
                .findFirst();
        if (failed.isEmpty() || !List.of("restore", "build").contains(failed.get().name())) {
            return byOwner;
        }
        var errors = compileErrors(result);
        for (var task : ordered) {
            var own = errors.stream().filter(e -> OwnedPaths.coveredByAny(task.ownedPathsOrEmpty(), e.path())).toList();
            if (!own.isEmpty()) {
                byOwner.put(task, own);
            }
        }
        return byOwner;
    }

    static final int REPAIR_ATTEMPTS = 2;

    private Map<String, String> currentContents(String missionId, String headSha,
                                                List<CompilerErrorParser.CompilerError> errors) {
        var contents = new LinkedHashMap<String, String>();
        for (var path : errors.stream().map(CompilerErrorParser.CompilerError::path).distinct().toList()) {
            try {
                contents.put(path, workspace.readFileAtCommit(missionId, headSha, path));
            } catch (Exception ex) {
                log.debug("MISSION {} - could not read {}: {}", missionId, path, ex.getMessage());
            }
        }
        return contents;
    }

    private Map<String, String> headContents(String missionId, String headSha, List<String> paths) {
        var contents = new LinkedHashMap<String, String>();
        for (var path : paths) {
            try {
                contents.put(path, workspace.readFileAtCommit(missionId, headSha, path));
            } catch (Exception ex) {
                log.debug("MISSION {} - {} is not at {}: {}", missionId, path, headSha, ex.getMessage());
            }
        }
        return contents;
    }

    /** true si todos los archivos devueltos que ya existían quedaron idénticos. */
    private static boolean unchanged(DevelopmentResult result, Map<String, String> current) {
        return result != null && result.files() != null && !result.files().isEmpty()
                && result.files().stream().allMatch(f -> f != null && current.containsKey(f.path())
                && Objects.equals(current.get(f.path()), f.content()));
    }

    private static final java.util.regex.Pattern DUPLICATE_DEFINITION =
            java.util.regex.Pattern.compile("already contains a definition for '([A-Za-z_][A-Za-z0-9_]*)'");

    private static String errorLine(CompilerErrorParser.CompilerError error, Map<String, String> current) {
        var content = current.get(error.path());
        if (content == null) {
            return "";
        }
        var lines = content.split("\\R", -1);
        var out = error.line() >= 1 && error.line() <= lines.length
                ? "\n    línea " + error.line() + ": " + lines[error.line() - 1].strip() : "";

        // Verificado en vivo (MISSION-SANDBOX-VERIFY-15): CS0102 señala una sola línea; el conflicto está en otra.
        var duplicate = DUPLICATE_DEFINITION.matcher(error.message());
        if (duplicate.find()) {
            var name = duplicate.group(1);
            var declaration = java.util.regex.Pattern.compile(
                    "\\b(class|interface|record|enum|struct)\\s+" + name + "\\b|\\b" + name + "\\s*[{;=(]");
            var others = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i + 1 != error.line() && declaration.matcher(lines[i]).find()) {
                    others.append("\n      línea ").append(i + 1).append(": ").append(lines[i].strip());
                }
            }
            if (!others.isEmpty()) {
                out += "\n    otras definiciones de '" + name + "' en el mismo archivo (renombra o mueve una):" + others;
            }
        }
        return out;
    }

    private static String repairBlock(List<CompilerErrorParser.CompilerError> errors, Map<String, String> current) {
        return """

                CORRECCIÓN DEL SANDBOX (compilación real, no una opinión): tu código no compila. Errores del compilador
                en tus archivos:
                %s
                Corrige exactamente estos errores (mira la línea y la columna) sin cambiar lo que ya está bien.
                Solo puedes cambiar tus archivos: si usas un tipo que no existe en el código de abajo, defínelo en tus
                propias rutas o usa los tipos que sí existen. Devuelve completos los archivos que corrijas; los que
                no devuelvas quedan como están.
                """.formatted(errors.stream().map(e -> "- " + e.display() + errorLine(e, current))
                        .collect(Collectors.joining("\n")));
    }

    static final int EXISTING_CODE_TOTAL_BUDGET_CHARS = 18_000;
    static final int EXISTING_CODE_FILE_BUDGET_CHARS = 5_000;

    /** Orden de dependencia de las capas: cada una solo depende de las anteriores (reglas DDD). */
    private static int layerRank(StackProfile.Layer layer) {
        return switch (layer) {
            case DOMAIN -> 0;
            case APPLICATION -> 1;
            case INFRASTRUCTURE -> 2;
            case API, GAME, PRESENTATION -> 3;
            case TESTS -> 4;
        };
    }

    /** Orden estable por la capa más baja de cada tarea; rutas que no son de una capa (entrada) cuentan como 3. */
    private static List<PlannedTask> orderByLayer(List<PlannedTask> work, TeamPlan plan) {
        var profile = plan.profile();
        if (profile.isEmpty()) {
            return work;
        }
        return work.stream()
                .sorted(java.util.Comparator.comparingInt(task -> task.ownedPathsOrEmpty().stream()
                        .map(path -> profile.get().locate(path, plan.contextNames())
                                .map(location -> layerRank(location.layer())).orElse(3))
                        .min(Integer::compare).orElse(3)))
                .toList();
    }

    /** Código real ya commiteado (sin .csproj/.sln, que son de Forjai), con tope explícito. */
    private String existingCode(String missionId, String headSha) {
        return repositoryCode(missionId, headSha, "CÓDIGO YA ESCRITO POR EL EQUIPO (capas anteriores, commit " + headSha
                + "). Úsalo tal cual: mismos namespaces, clases, métodos y firmas. No lo reescribas ni lo devuelvas; "
                + "escribe solo tus archivos.");
    }

    private String repositoryCode(String missionId, String headSha, String header) {
        if (headSha == null) {
            return "";
        }
        try {
            var contents = new LinkedHashMap<String, String>();
            for (var path : workspace.filesAtCommit(missionId, headSha).stream().sorted().toList()) {
                if (path.endsWith(".csproj") || path.endsWith(".sln")) {
                    continue;
                }
                contents.put(path, workspace.readFileAtCommit(missionId, headSha, path));
            }
            if (contents.isEmpty()) {
                return "";
            }
            return "\n\n" + header + "\n" + renderRepositoryContext(contents, EXISTING_CODE_TOTAL_BUDGET_CHARS,
                    EXISTING_CODE_FILE_BUDGET_CHARS);
        } catch (Exception ex) {
            log.warn("MISSION {} - could not read existing code at {}: {}", missionId, headSha, ex.getMessage());
            return "";
        }
    }

    /**
     * Verificado en vivo (MISSION-SANDBOX-VERIFY-2 a -4): qwen3:8b no escribía los .csproj, los rompía o deformaba
     * ".csproj". Forjai los genera (ProjectScaffold) en un commit propio antes del trabajo de los agentes.
     */
    private CommittedWork commitProjectScaffold(TeamMissionContext context) {
        var plan = context.plan();
        var files = plan.profile().map(p -> ProjectScaffold.generate(p, plan.contextNames())).orElse(List.of());
        if (files.isEmpty()) {
            return null;
        }
        try {
            var record = workspace.commitAgentWork(context.missionId(), context.missionId() + "-SCAFFOLD", "forjai",
                    "Forjai", new DevelopmentResult("Proyectos .csproj generados por Forjai (reglas DDD del perfil "
                            + plan.stackProfile() + ")", files));
            return new CommittedWork(context.missionId() + "-SCAFFOLD", "forjai", record.sha(), record.files());
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo generar la estructura de proyectos: "
                    + safeMessage(ex, "error de Git"), ex);
        }
    }

    private void createTask(String missionId, PlannedTask task, String kind) {
        var id = taskId(missionId, task.agentId());
        memory.createTask(id, missionId, task.agentId(), task.action(), kind);
        events.publishTask("EMPRESA_TASK_CREATED", id, missionId, task.agentId(), "PENDING", "Tarea creada.");
    }

    static String taskId(String missionId, String agentId) {
        return missionId + "-" + agentId.toUpperCase(Locale.ROOT);
    }

    private static String validatorModel(TeamMissionContext context, String agentId) {
        return context.team().members().stream()
                .filter(m -> m.agentId().equals(agentId))
                .map(TeamMemberInfo::model)
                .findFirst()
                .orElse(null);
    }

    private static String agentName(TeamMissionContext context, String agentId) {
        return context.team().members().stream()
                .filter(m -> m.agentId().equals(agentId))
                .map(TeamMemberInfo::name)
                .findFirst()
                .orElse(agentId);
    }

    private static String dddContext(TeamPlan plan) {
        var profile = plan.profile().map(StackProfile::describe).orElse("- (sin perfil)");
        var contexts = plan.boundedContextsOrEmpty().stream()
                .map(c -> "- " + c.name() + ": " + c.description())
                .collect(Collectors.joining("\n"));
        var glossary = plan.ubiquitousLanguageOrEmpty().stream()
                .map(t -> "- " + t.term() + ": " + t.definition())
                .collect(Collectors.joining("\n"));
        return """
                PERFIL DE STACK (metodología DDD obligatoria):
                %s
                BOUNDED CONTEXTS:
                %s
                LENGUAJE UBICUO (usa estos términos en el código):
                %s
                REGLAS DE CAPAS: domain no depende de nada fuera de su domain ni de frameworks; application solo de
                domain; infrastructure/api/presentation/game dependen de application y domain.
                """.formatted(profile, contexts, glossary);
    }

    private String buildWorkPrompt(TeamMissionContext context, PlannedTask task) {

        var plan = context.plan();

        var others = plan.tasksOrEmpty().stream()
                .filter(t -> t != null && !t.agentId().equals(task.agentId()))
                .map(t -> "- " + t.agentId() + " (" + t.kind() + "): " + t.objective() + " | ownedPaths=" + t.ownedPathsOrEmpty())
                .collect(Collectors.joining("\n"));

        // Verificado en vivo: la dueña de "game" no creó game/project.godot porque nadie le dijo que era obligatorio.
        var requiredFiles = plan.profile().map(StackProfile::leaderOwnedPaths).orElse(List.of()).stream()
                .filter(file -> OwnedPaths.coveredByAny(task.ownedPathsOrEmpty(), file))
                .toList();
        var required = requiredFiles.isEmpty() ? "" : "ARCHIVOS OBLIGATORIOS que te corresponden (el proyecto no "
                + "compila ni arranca sin ellos): " + requiredFiles + "\n";
        var projects = plan.profile().map(p -> p.projectFiles(plan.contextNames())).orElse(List.of());
        if (!projects.isEmpty()) {
            required += "PROYECTOS .csproj: ya existen y los genera Forjai con las referencias DDD correctas; no los "
                    + "escribas: " + projects + "\n";
        }

        return """
                MISIÓN DEL EQUIPO %s:
                %s

                PLAN DEL LÍDER:
                %s
                %s

                TU TAREA (%s, action=%s):
                %s
                Solo puedes escribir archivos dentro de estos ownedPaths: %s
                %s
                TAREAS DEL RESTO DEL EQUIPO (se generan por capas: domain → application → infrastructure →
                api/game/presentation → tests; si hay código de capas anteriores, está al final de este mensaje):
                %s

                REGLAS:
                - Escribe código fuente REAL y completo para tu parte, no pseudocódigo ni placeholders.
                - Rutas relativas con "/" como separador; nunca rutas absolutas, "..", "\\" ni ".git".
                - Forjai compila, corre los tests y arranca este código en un sandbox sin red: escribe código C#/Dart
                  válido y completo. En summary no afirmes que compila o funciona; eso lo dice el sandbox.
                - summary: qué archivos escribiste y qué hace cada uno.

                FORMATO: {"summary": "...", "files": [{"path": "...", "content": "..."}]}
                """.formatted(
                context.team().teamName(), context.instruction(),
                plan.summary(), dddContext(plan),
                task.agentId(), task.action(), task.objective(), task.ownedPathsOrEmpty(), required,
                others);
    }

    private String buildReviewPrompt(
            TeamMissionContext context, PlannedTask validation, List<CommittedWork> committed,
            List<StaticCheck> checks, String headSha, String repositoryContext, String sandboxSummary) {

        var commits = committed.stream()
                .map(c -> "- " + c.agentId() + ": sha=" + c.commitSha() + " archivos=" + c.files())
                .collect(Collectors.joining("\n"));

        var checkLines = checks.stream()
                .map(c -> "- " + c.check() + ": " + c.status() + " — " + c.detail())
                .collect(Collectors.joining("\n"));

        return """
                Eres el agente %s y validas ESTÁTICAMENTE el código generado por %s para la misión %s.
                Tú no ejecutas código: la única ejecución es la del sandbox, cuyos resultados reales están abajo.

                OBJETIVO DE TU TAREA: %s

                PLAN DEL LÍDER: %s
                %s

                COMMITS POR AGENTE:
                %s

                CHEQUEOS DETERMINISTAS (hechos por Java; no los contradigas):
                %s

                RESULTADOS REALES DEL SANDBOX (ejecución real; no los contradigas ni afirmes nada más allá de ellos):
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
                  exactamente "workspace:%s@%s/<ruta>" (siempre ese sha, el HEAD: contiene todos los archivos),
                  con una ruta que aparezca en CÓDIGO DEL REPOSITORIO.
                - Los .csproj y el .sln los genera Forjai con las reglas DDD del perfil: no son trabajo de los agentes,
                  no los revises ni reportes findings sobre ellos.
                - Revisión DDD: ¿el código usa el lenguaje ubicuo del glosario? ¿Hay entidades, value objects y
                  agregados con sentido? ¿El dominio es anémico (solo datos, sin reglas)? Repórtalo en findings.
                - NUNCA afirmes que el juego compila, se ejecuta, funciona o pasa tests.
                """.formatted(
                validation.agentId(), context.team().teamName(), context.missionId(),
                validation.objective(),
                context.plan().summary(), dddContext(context.plan()),
                commits, checkLines, sandboxSummary, headSha, repositoryContext, context.missionId(), headSha);
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
            StaticReviewResult review, String reviewError, StaticValidationStatus status, List<String> failures,
            String sandboxSummary) {

        var out = new StringBuilder();

        out.append("MISIÓN DE EQUIPO: ").append(context.team().teamName())
                .append(" (").append(context.team().teamId()).append(")\n");
        out.append("PLAN DEL LÍDER: ").append(context.plan().summary())
                .append(" | Perfil: ").append(context.plan().stackProfile())
                .append(" | Bounded contexts: ").append(context.plan().contextNames()).append("\n\n");

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

        out.append("\nSANDBOX (ejecución real): ").append(sandboxSummary).append("\n");

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

        out.append("\nREGLA: ").append(status == StaticValidationStatus.VERIFIED ? verifiedPhraseRule() : NO_EXECUTION_DISCLAIMER)
                .append(" No afirmes que el producto está terminado ni que funciona más allá de lo probado.\n");

        return out.toString();
    }

    private String verifiableState(
            TeamMissionContext context, CommittedWork scaffold, List<CommittedWork> committed, List<StaticCheck> checks,
            StaticValidationStatus status, List<String> failures, SandboxResult sandboxResult, String sandboxError,
            int repairRounds, int autofixRounds, DependencyService.Outcome dependencyOutcome) {

        var passed = checks.stream().filter(StaticCheck::passed).count();

        var out = new StringBuilder();
        out.append("ESTADO VERIFICABLE (generado por Forjai, no por un modelo)\n");
        out.append("Workspace: ").append(workspace.missionWorkspace(context.missionId())).append("\n");
        out.append("Perfil: ").append(context.plan().stackProfile())
                .append(" | Bounded contexts: ").append(context.plan().contextNames()).append("\n");

        if (scaffold != null) {
            out.append("- Forjai (scaffold): commit ").append(scaffold.commitSha()).append(" — ")
                    .append(scaffold.files().size()).append(" archivo(s) de proyecto (.csproj y .sln) generados por Java\n");
        }

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
        out.append("Sandbox: ").append(sandboxSummary(sandboxResult, sandboxError)).append("\n");
        if (!dependencyOutcome.approvedNow().isEmpty() || !dependencyOutcome.pending().isEmpty()) {
            out.append("Dependencias: ").append(dependencyOutcome.approvedNow().size())
                    .append(" aprobada(s) por política ").append(dependencyOutcome.approvedNow().stream().map(DependencyRef::id).toList())
                    .append(", ").append(dependencyOutcome.pending().size()).append(" pendiente(s) de aprobación (🔴)\n");
        }
        if (autofixRounds > 0) {
            out.append("Correcciones automáticas de Forjai: ").append(autofixRounds)
                    .append(" (using faltantes de tipos que existen en otra capa)\n");
        }
        if (repairRounds > 0) {
            out.append("Rondas de corrección: ").append(repairRounds)
                    .append(" (errores reales del compilador devueltos a los dueños de los archivos)\n");
        }

        if (sandboxResult != null) {
            sandboxResult.stepsOrEmpty().stream()
                    .filter(s -> "FAIL".equals(s.status()) || "TIMEOUT".equals(s.status()))
                    .findFirst()
                    .ifPresent(s -> {
                        var output = s.outputTail() == null ? "" : s.outputTail();
                        out.append("Paso fallido: ").append(s.name()).append("\n")
                                .append(output.substring(Math.max(0, output.length() - FAILED_STEP_TAIL_CHARS)))
                                .append("\n");
                    });
        }

        if (status == StaticValidationStatus.VERIFIED) {
            out.append("Compiló, pasaron ").append(sandboxResult.testsPassed())
                    .append(" tests y arrancó en el sandbox. No garantiza que el producto esté completo ni que no tenga "
                            + "errores fuera de lo probado.");
        } else {
            out.append(NO_EXECUTION_DISCLAIMER);
        }

        return out.toString();
    }

    private static String verifiedPhraseRule() {
        return "La validación es VERIFIED: compiló, pasaron sus tests y arrancó en el sandbox; nada más.";
    }

    static String sandboxSummary(SandboxResult result, String error) {
        if (result == null) {
            return "No corrió: " + error;
        }
        return result.stepsOrEmpty().stream()
                .map(s -> {
                    var label = switch (s.name()) {
                        case "build" -> "Build";
                        case "test" -> "Tests";
                        case "smoke" -> "Arranque";
                        default -> s.name();
                    };
                    var tests = "test".equals(s.name())
                            ? " " + s.testsPassed() + "/" + (s.testsPassed() + s.testsFailed()) : "";
                    return label + " " + s.status() + tests + " (" + (s.durationMs() / 1000) + " s)";
                })
                .collect(Collectors.joining(" · "));
    }

    private static List<AgentResult.Evidence> sandboxEvidence(String missionId, String sha, SandboxResult result) {
        return result.stepsOrEmpty().stream()
                .map(s -> new AgentResult.Evidence(s.name() + " " + s.status() + " en el sandbox",
                        "sandbox:" + missionId + "@" + sha + "/" + s.name(), "INTERNAL", true))
                .toList();
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
