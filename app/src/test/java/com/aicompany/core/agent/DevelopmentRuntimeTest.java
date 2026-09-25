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
    void reviewDeclaringAnExistingFileAsMissingIsRetried() throws Exception {
        var wrong = new StaticReviewResult("ISSUES_FOUND", List.of(), List.of("web/game/main.js"), "Coherente.",
                List.of("No se puede verificar la ejecución del juego."),
                List.of(new AgentResult.Evidence("Revisé main.js", validSource, "INTERNAL", true)));
        when(ceoService.reviewStaticWorkspace(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(wrong)
                .thenReturn(review("Coherente.", validSource));

        runtime.review("T-QA", "MISSION-1", "qa", "prompt", filesBySha).get();

        verify(ceoService).reviewStaticWorkspace(anyString(),
                argThat(p -> p.contains("CORRECCIÓN DEL INTENTO ANTERIOR") && p.contains("sí existe")),
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
}
