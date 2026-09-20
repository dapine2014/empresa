package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import com.aicompany.core.llm.LlmProvider;
import com.aicompany.core.llm.LlmResponse;
import com.aicompany.core.llm.NvidiaNimLlmProvider;
import com.aicompany.core.llm.OllamaLlmProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Fallback de CEO en NVIDIA -> Ollama (ver
 * `docs/superpowers/specs/2026-09-18-nvidia-ceo-provider-design.md`).
 * Usa `NvidiaNimLlmProvider` mockeado como `ceoProvider` a propósito:
 * distinguir "estamos usando NVIDIA" de "estamos usando Ollama" es un
 * chequeo `instanceof` dentro de `CeoService.callCeo`, así que el mock
 * necesita ser del tipo concreto NVIDIA para ejercitar esa rama.
 */
class CeoServiceProviderFallbackTest {

    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final OllamaLlmProvider ollamaFallback = mock(OllamaLlmProvider.class);
    private final AiBudgetService budget = mock(AiBudgetService.class);
    private final NvidiaNimLlmProvider nvidiaProvider = mock(NvidiaNimLlmProvider.class);

    private CeoService ceoService(LlmProvider ceoProvider) {
        return new CeoService(
                mock(RestClient.class),
                "qwen3:8b",
                JsonMapper.builder().build(),
                mock(EvidenceAcquisitionService.class),
                events,
                new SimpleMeterRegistry(),
                ceoProvider,
                ollamaFallback,
                budget
        );
    }

    @Test
    void usesNvidiaResponseDirectlyWhenHealthyAndBudgetAvailable() {

        when(budget.isExhausted()).thenReturn(false);
        when(nvidiaProvider.chat(anyString(), any(), any()))
                .thenReturn(new LlmResponse("respuesta real de NVIDIA", List.of()));

        var result = ceoService(nvidiaProvider).executeMission("instruccion", "resultados");

        assertEquals("respuesta real de NVIDIA", result);
        verify(budget).recordCall();
        verifyNoInteractions(ollamaFallback);
        verifyNoInteractions(events);
    }

    @Test
    void fallsBackToOllamaWhenNvidiaThrows() {

        when(budget.isExhausted()).thenReturn(false);
        when(nvidiaProvider.chat(anyString(), any(), any()))
                .thenThrow(new RuntimeException("timeout"));
        when(ollamaFallback.chat(anyString(), any(), any()))
                .thenReturn(new LlmResponse("respuesta real de Ollama", List.of()));

        var result = ceoService(nvidiaProvider).executeMission("instruccion", "resultados");

        assertEquals("respuesta real de Ollama", result);
        verify(budget, never()).recordCall();
        verify(events).publish(
                eq("EMPRESA_CEO_PROVIDER_FALLBACK"), any(), any(), eq("ceo"),
                eq(Map.of("operation", "MISSION_CONSOLIDATION", "reason", "RATE_LIMIT_OR_ERROR"))
        );
    }

    @Test
    void fallsBackToOllamaWhenNvidiaReturnsEmptyContentAndNoToolCalls() {

        when(budget.isExhausted()).thenReturn(false);
        when(nvidiaProvider.chat(anyString(), any(), any()))
                .thenReturn(new LlmResponse(null, List.of()));
        when(ollamaFallback.chat(anyString(), any(), any()))
                .thenReturn(new LlmResponse("respuesta real de Ollama", List.of()));

        var result = ceoService(nvidiaProvider).executeMission("instruccion", "resultados");

        assertEquals("respuesta real de Ollama", result);
        verify(events).publish(
                eq("EMPRESA_CEO_PROVIDER_FALLBACK"), any(), any(), eq("ceo"),
                eq(Map.of("operation", "MISSION_CONSOLIDATION", "reason", "RATE_LIMIT_OR_ERROR"))
        );
    }

    @Test
    void fallsBackToOllamaWhenBudgetAlreadyExhausted() {

        when(budget.isExhausted()).thenReturn(true);
        when(ollamaFallback.chat(anyString(), any(), any()))
                .thenReturn(new LlmResponse("respuesta real de Ollama", List.of()));

        var result = ceoService(nvidiaProvider).executeMission("instruccion", "resultados");

        assertEquals("respuesta real de Ollama", result);
        verifyNoInteractions(nvidiaProvider);
        verify(events).publish(
                eq("EMPRESA_CEO_PROVIDER_FALLBACK"), any(), any(), eq("ceo"),
                eq(Map.of("operation", "MISSION_CONSOLIDATION", "reason", "BUDGET_EXHAUSTED"))
        );
    }

    @Test
    void neverConsultsBudgetOrFallsBackWhenConfiguredProviderIsOllama() {

        var ollamaAsCeoProvider = mock(OllamaLlmProvider.class);
        when(ollamaAsCeoProvider.chat(anyString(), any(), any()))
                .thenReturn(new LlmResponse("respuesta real de Ollama", List.of()));

        var result = ceoService(ollamaAsCeoProvider).executeMission("instruccion", "resultados");

        assertEquals("respuesta real de Ollama", result);
        verifyNoInteractions(budget);
        verifyNoInteractions(ollamaFallback);
    }

    /**
     * Hallazgo 3 de la revisión final de rama: antes de este fix, una
     * respuesta vacía de Ollama (el proveedor por default, 100% del
     * tráfico real) se trataba igual que una respuesta vacía de NVIDIA —
     * lanzaba `IllegalStateException`, que en `executeMission` termina en
     * `MissionExecutor.safeFail` marcando TODA la misión `FAILED`,
     * descartando el trabajo real de los 5 agentes. El chequeo de
     * "respuesta vacía" solo debe aplicar cuando el proveedor es NVIDIA.
     */
    @Test
    void returnsEmptyOllamaResponseWithoutThrowingOrFallingBack() {

        var ollamaAsCeoProvider = mock(OllamaLlmProvider.class);
        when(ollamaAsCeoProvider.chat(anyString(), any(), any()))
                .thenReturn(new LlmResponse("", List.of()));

        var result = ceoService(ollamaAsCeoProvider).executeMission("instruccion", "resultados");

        assertEquals("", result);
        verifyNoInteractions(budget);
        verifyNoInteractions(ollamaFallback);
    }

    /**
     * Hallazgo 4: la forma real de cualquier turno de tool-calling de
     * NVIDIA es `content=null` + `tool_calls` no vacío — eso es éxito, no
     * el caso de "respuesta vacía" que dispara el fallback.
     */
    @Test
    void treatsNvidiaToolCallResponseAsSuccessEvenWithBlankContent() {

        when(budget.isExhausted()).thenReturn(false);

        var toolCall = Map.<String, Object>of(
                "id", "call_0",
                "type", "function",
                "function", Map.of(
                        "name", "query_company_memory",
                        "arguments", Map.of("topic", "COMPANY_STATUS")
                )
        );

        when(nvidiaProvider.chat(anyString(), any(), any()))
                .thenReturn(new LlmResponse(null, List.of(toolCall)));

        var result = ceoService(nvidiaProvider).executeMission("instruccion", "resultados");

        assertNull(result);
        verify(budget).recordCall();
        verifyNoInteractions(ollamaFallback);
        verifyNoInteractions(events);
    }
}
