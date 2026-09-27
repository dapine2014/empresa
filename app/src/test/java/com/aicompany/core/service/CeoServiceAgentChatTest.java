package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec 2026-09-27 §5: cualquier agente responde en el chat con su identidad, solo lectura. */
class CeoServiceAgentChatTest {

    private final OpenAiCompatibleClient creative = mock(OpenAiCompatibleClient.class);
    private final CeoService ceoService = new CeoService(mock(RestClient.class), JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class), mock(CompanyEventPublisher.class), new SimpleMeterRegistry(),
            Map.of("nvidia-creative", creative));

    @SuppressWarnings("unchecked")
    @Test
    void anAgentAnswersWithItsOwnIdentityAndOnlyTheReadOnlyMemoryTool() {
        var messages = ArgumentCaptor.forClass(List.class);
        var tools = ArgumentCaptor.forClass(List.class);
        when(creative.complete(eq("moonshotai/kimi-k3"), messages.capture(), tools.capture(), eq(false), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("Propongo tres piezas de contenido.", List.of()));

        var answer = ceoService.agentChat(
                new CeoService.ChatSpeaker("growth-content", "Kira", "Growth, Content & Community", "Curiosa y directa"),
                "Alex (CEO), Kira (Growth)", List.of(), "¿ideas para el lanzamiento?",
                topic -> "datos", "Piensa en canales orgánicos.", "nvidia-creative:moonshotai/kimi-k3");

        assertEquals("Propongo tres piezas de contenido.", answer);
        var system = String.valueOf(((Map<String, Object>) messages.getValue().get(0)).get("content"));
        assertTrue(system.contains("Tu nombre es Kira"), system);
        assertTrue(system.contains("Growth, Content & Community"), system);
        assertTrue(system.contains("Curiosa y directa"), system);
        assertTrue(system.contains("No puedes lanzar misiones, aprobar, rechazar ni contactar"), system);
        assertTrue(system.contains("Piensa en canales orgánicos."), system);
        assertFalse(system.contains("Eres el CEO de Forjai"), system);
        var toolNames = ((List<Map<String, Object>>) tools.getValue()).stream()
                .map(t -> ((Map<String, Object>) t.get("function")).get("name")).toList();
        assertEquals(List.of("query_company_memory"), toolNames);
    }
}
