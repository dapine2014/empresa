package com.aicompany.core.agent;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.agent.model.StaticReviewResult;
import com.aicompany.core.agent.validation.DevelopmentPathValidationGate;
import com.aicompany.core.agent.validation.EvidenceValidationGate;
import com.aicompany.core.agent.validation.ForbiddenClaimsGuard;
import com.aicompany.core.agent.validation.MissingFileClaimGate;
import com.aicompany.core.agent.validation.RepositoryEvidenceGate;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import com.aicompany.core.service.PromptMemoryService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DevelopmentRuntimeTest {

    private static final String SHA = "c".repeat(40);

    private final MissionMemoryService memory = mock(MissionMemoryService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final PromptMemoryService promptMemory = mock(PromptMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final CeoService ceoService = mock(CeoService.class);

    private final DevelopmentRuntime runtime = new DevelopmentRuntime(
            ceoService, memory, companyMemory, promptMemory, "qwen3:8b", Runnable::run, events,
            new DevelopmentPathValidationGate(), new EvidenceValidationGate(), new RepositoryEvidenceGate(),
            new ForbiddenClaimsGuard(), new MissingFileClaimGate(), JsonMapper.builder().build());

    {
        when(companyMemory.agentModel(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(promptMemory.activePrompt(anyString())).thenReturn("");
    }

    private static DevelopmentResult dev(String path) {
        return new DevelopmentResult("resumen", List.of(new GeneratedFile(path, "contenido")));
    }

    private static StaticReviewResult review(String architecture, String source) {
        return new StaticReviewResult("NO_EVIDENT_ISSUES", List.of(), List.of(), architecture,
                List.of("No se puede verificar la ejecución del juego."),
                List.of(new AgentResult.Evidence("Revisé main.js", source, "INTERNAL", true)));
    }

    private final Map<String, Set<String>> filesBySha = Map.of(SHA, Set.of("web/game/main.js"));
    private final String validSource = RepositoryEvidenceGate.citation("MISSION-1", SHA, "web/game/main.js");

    @Test
    void generateLeavesTheTaskGeneratedNotCompleted() throws Exception {
        when(ceoService.generateDevelopmentArtifact(eq("backend"), anyString(), anyString(), eq("qwen3:8b")))
                .thenReturn(dev("web/game/main.js"));

        var result = runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game")).get();

        assertEquals("web/game/main.js", result.files().get(0).path());
        verify(memory).updateTask(eq("T-1"), eq("GENERATED"), anyString());
        verify(memory, never()).updateTask(eq("T-1"), eq("COMPLETED"), anyString());
        verify(events, never()).publishTask(eq("EMPRESA_TASK_COMPLETED"), any(), any(), any(), any(), any());
    }

    @Test
    void anUnsafePathFailsTheTaskWithoutRetrying() {
        when(ceoService.generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(dev("../etc/passwd"));

        var future = runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game"));

        assertThrows(ExecutionException.class, future::get);
        verify(ceoService, times(1)).generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString());
        verify(memory).updateTask(eq("T-1"), eq("FAILED"), anyString());
    }

    @Test
    void aPathOutsideOwnedPathsIsRetriedWithCorrection() throws Exception {
        when(ceoService.generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(dev("docs/notas.md"))
                .thenReturn(dev("web/game/main.js"));

        runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game")).get();

        verify(ceoService).generateDevelopmentArtifact(anyString(),
                argThat(p -> p.contains("CORRECCIÓN DEL INTENTO ANTERIOR") && p.contains("docs/notas.md")),
                anyString(), anyString());
    }

    // Verificado en vivo (MISSION-SANDBOX-VERIFY-5): Neo, dueño solo de Solution.sln, escribía todo el proyecto
    // y agotaba los 3 intentos. Lo ajeno pertenece a otro agente: se descarta (y se informa), no se reintenta.
    @Test
    void filesOutsideOwnedPathsAreDiscardedWhenTheOwnWorkIsPresent() throws Exception {
        when(ceoService.generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new DevelopmentResult("todo", List.of(
                        new GeneratedFile("Solution.sln", "sln"),
                        new GeneratedFile("src/Tareas.Domain/Tarea.cs", "ajeno"))));

        var result = runtime.generate("T-1", "MISSION-1", "engineering", "prompt", List.of("Solution.sln")).get();

        assertEquals(List.of("Solution.sln"), result.files().stream().map(GeneratedFile::path).toList());
        assertTrue(result.summary().contains("src/Tareas.Domain/Tarea.cs"), result.summary());
        verify(ceoService, times(1)).generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString());
    }

    // Verificado en vivo (MISSION-SANDBOX-VERIFY-11): en una corrección solo se exigen las rutas con errores;
    // lo que el agente no devuelve sigue en el repositorio.
    @Test
    void aRepairOnlyRequiresThePathsWithErrors() throws Exception {
        when(ceoService.generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(dev("src/Tareas.Application/Handler.cs"));

        var result = runtime.generate("T-1", "MISSION-1", "backend", "prompt",
                List.of("src/Tareas.Application", "src/Tareas.Infrastructure"), List.of(),
                List.of("src/Tareas.Application")).get();

        assertEquals(1, result.files().size());
        verify(ceoService, times(1)).generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString());
    }

    // Verificado en vivo (MISSION-SANDBOX-VERIFY-15): Vera agotaba los reintentos declarando faltantes archivos
    // que existen y la revisión se perdía. Esos nombres se quitan de missingFiles sin rechazar la revisión.
    @Test
    void existingFilesAreDroppedFromMissingFilesWithoutRetrying() throws Exception {
        var claimed = new StaticReviewResult("NO_EVIDENT_ISSUES", List.of(), List.of("web/game/main.js", "web/falta.js"),
                "ok", List.of("x"), List.of(new AgentResult.Evidence("main", validSource, "INTERNAL", true)));
        when(ceoService.reviewStaticWorkspace(anyString(), anyString(), anyString(), anyString())).thenReturn(claimed);

        var result = runtime.review("T-QA", "MISSION-1", "qa", "prompt", filesBySha).get();

        assertEquals(List.of("web/falta.js"), result.missingFiles());
        verify(ceoService, times(1)).reviewStaticWorkspace(anyString(), anyString(), anyString(), anyString());
    }

    // Review Focus: "\" como separador es corregible → reintento, nunca un archivo con "\" en el nombre.
    @Test
    void aBackslashSeparatorIsRetriedNotWritten() throws Exception {
        when(ceoService.generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(dev("web\\game\\main.js"))
                .thenReturn(dev("web/game/main.js"));

        var result = runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game")).get();

        assertEquals("web/game/main.js", result.files().get(0).path());
        verify(ceoService, times(2)).generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void exhaustingRetriesFailsAndReturnsTheAgentToIdle() {
        when(ceoService.generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(dev("docs/notas.md"));

        assertThrows(ExecutionException.class,
                () -> runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game")).get());

        verify(ceoService, times(3)).generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString());
        var inOrder = inOrder(memory);
        inOrder.verify(memory).setAgentStatus("backend", "WORKING");
        inOrder.verify(memory).setAgentStatus("backend", "IDLE");
    }

    @Test
    void usesTheAgentsOwnPersistedModel() throws Exception {
        when(companyMemory.agentModel("backend", "qwen3:8b")).thenReturn("llama3:8b");
        when(ceoService.generateDevelopmentArtifact(eq("backend"), anyString(), anyString(), eq("llama3:8b")))
                .thenReturn(dev("web/game/main.js"));

        runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game")).get();

        verify(ceoService).generateDevelopmentArtifact(eq("backend"), anyString(), anyString(), eq("llama3:8b"));
    }

    @Test
    void reviewCompletesTheValidationTask() throws Exception {
        when(ceoService.reviewStaticWorkspace(eq("qa"), anyString(), anyString(), anyString()))
                .thenReturn(review("La arquitectura es coherente con el plan.", validSource));

        var result = runtime.review("T-QA", "MISSION-1", "qa", "prompt", filesBySha).get();

        assertEquals("NO_EVIDENT_ISSUES", result.verdict());
        verify(memory).updateTask(eq("T-QA"), eq("COMPLETED"), anyString());
        verify(events).publishTask(eq("EMPRESA_TASK_COMPLETED"), eq("T-QA"), eq("MISSION-1"), eq("qa"), eq("COMPLETED"), anyString());
    }

    @Test
    void reviewClaimingTheGameWorksIsRetried() throws Exception {
        when(ceoService.reviewStaticWorkspace(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(review("El juego funciona correctamente.", validSource))
                .thenReturn(review("La arquitectura es coherente con el plan.", validSource));

        runtime.review("T-QA", "MISSION-1", "qa", "prompt", filesBySha).get();

        verify(ceoService, times(2)).reviewStaticWorkspace(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void reviewCitingAnInventedFileIsRetried() throws Exception {
        var invented = RepositoryEvidenceGate.citation("MISSION-1", SHA, "web/inventado.js");
        when(ceoService.reviewStaticWorkspace(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(review("Coherente.", invented))
                .thenReturn(review("Coherente.", validSource));

        runtime.review("T-QA", "MISSION-1", "qa", "prompt", filesBySha).get();

        verify(ceoService).reviewStaticWorkspace(anyString(),
                argThat(p -> p.contains("web/inventado.js")), anyString(), anyString());
    }

    @Test
    void reviewClaimingAnExistingFileDoesNotExistIsRetried() throws Exception {
        // missingFiles con archivos existentes ya no se reintenta (se limpia, MISSION-SANDBOX-VERIFY-15); un finding
        // que afirma inexistencia sí.
        var wrong = new StaticReviewResult("ISSUES_FOUND", List.of(new StaticReviewResult.Finding("web/game/main.js",
                "BLOCKER", "El archivo web/game/main.js no existe.")), List.of(), "Coherente.",
                List.of("No se puede verificar la ejecución del juego."),
                List.of(new AgentResult.Evidence("Revisé main.js", validSource, "INTERNAL", true)));
        when(ceoService.reviewStaticWorkspace(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(wrong)
                .thenReturn(review("Coherente.", validSource));

        runtime.review("T-QA", "MISSION-1", "qa", "prompt", filesBySha).get();

        verify(ceoService).reviewStaticWorkspace(anyString(),
                argThat(p -> p.contains("CORRECCIÓN DEL INTENTO ANTERIOR") && p.contains("afirma que no existe")),
                anyString(), anyString());
    }

    // Verificado en vivo (MISSION-SANDBOX-VERIFY-4 y -6): Diego escribía .csproj aun prohibido (incluso uno
    // inventado) y agotaba los reintentos. Los proyectos son de Forjai: se descartan, no se reintenta.
    @Test
    void projectFilesWrittenByTheAgentAreDiscardedWithoutRetrying() throws Exception {
        when(ceoService.generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new DevelopmentResult("r", List.of(
                        new GeneratedFile("src/Tareas.Domain/Tareas.Domain.csproj", "<Project />"),
                        new GeneratedFile("src/Tareas.Domain/Inventado.csproj", "roto"),
                        new GeneratedFile("src/Tareas.Domain/Tarea.cs", "namespace Tareas.Domain;"))));

        var result = runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("src/Tareas.Domain"),
                List.of("src/Tareas.Domain/Tareas.Domain.csproj")).get();

        assertEquals(List.of("src/Tareas.Domain/Tarea.cs"), result.files().stream().map(GeneratedFile::path).toList());
        assertTrue(result.summary().contains("Inventado.csproj"), result.summary());
        verify(ceoService, times(1)).generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString());
    }

    // Verificado en vivo (MISSION-SANDBOX-VERIFY-2 a -4): dueños de Infrastructure+Tests entregaban solo una
    // capa (o solo el .csproj). Cada ownedPath debe recibir al menos un archivo.
    @Test
    void anOwnedPathWithoutFilesIsRetried() throws Exception {
        when(ceoService.generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(dev("src/Tareas.Infrastructure/Repo.cs"))
                .thenReturn(new DevelopmentResult("r", List.of(
                        new GeneratedFile("src/Tareas.Infrastructure/Repo.cs", "x"),
                        new GeneratedFile("tests/Tareas.Tests/TareaTests.cs", "x"))));

        var result = runtime.generate("T-1", "MISSION-1", "devops", "prompt",
                List.of("src/Tareas.Infrastructure", "tests/Tareas.Tests")).get();

        assertEquals(2, result.files().size());
        verify(ceoService).generateDevelopmentArtifact(anyString(),
                argThat(p -> p.contains("CORRECCIÓN DEL INTENTO ANTERIOR") && p.contains("tests/Tareas.Tests")),
                anyString(), anyString());
    }

    @Test
    void anAbsolutePathIsRetriedAskingForARelativePath() throws Exception {
        when(ceoService.generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(dev("/web/game/main.js"))
                .thenReturn(dev("web/game/main.js"));

        var result = runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game")).get();

        assertEquals("web/game/main.js", result.files().get(0).path());
        verify(ceoService).generateDevelopmentArtifact(anyString(),
                argThat(p -> p.contains("CORRECCIÓN DEL INTENTO ANTERIOR") && p.contains("ruta relativa")),
                anyString(), anyString());
    }

    @Test
    void persistentAbsolutePathsStillFailAfterRetries() {
        when(ceoService.generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(dev("/etc/passwd"));

        assertThrows(ExecutionException.class,
                () -> runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game")).get());
        verify(ceoService, times(3)).generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aNonExactPackageVersionIsRetried() throws Exception {
        when(ceoService.generateDevelopmentArtifact(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new DevelopmentResult("r", List.of(new GeneratedFile("web/game/a.cs", "x")),
                        List.of(new DevelopmentResult.PackageRequest("Newtonsoft.Json", "13.*"))))
                .thenReturn(new DevelopmentResult("r", List.of(new GeneratedFile("web/game/a.cs", "x")),
                        List.of(new DevelopmentResult.PackageRequest("Newtonsoft.Json", "13.0.3"))));

        var result = runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game")).get();

        assertEquals("13.0.3", result.packages().get(0).version());
        verify(ceoService).generateDevelopmentArtifact(anyString(),
                argThat(p -> p.contains("CORRECCIÓN") && p.contains("versión exacta")), anyString(), anyString());
    }

    // Spec 2026-10-01 §5: el código omitido se reintenta con la línea exacta.
    @Test
    void elidedCodeIsRetriedWithTheExactLine() throws Exception {
        when(ceoService.generateDevelopmentArtifact(eq("backend"), anyString(), anyString(), anyString()))
                .thenReturn(new DevelopmentResult("r", List.of(new GeneratedFile("web/game/main.js", "function a() {\n  // ...\n}"))))
                .thenReturn(dev("web/game/main.js"));

        runtime.generate("T-1", "MISSION-1", "backend", "prompt", List.of("web/game")).get();

        var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ceoService, times(2)).generateDevelopmentArtifact(eq("backend"), prompts.capture(), anyString(), anyString());
        assertTrue(prompts.getAllValues().get(1).contains("Código omitido en web/game/main.js (línea 2"),
                prompts.getAllValues().get(1));
    }
}
