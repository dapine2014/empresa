package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import com.aicompany.core.llm.LlmProvider;
import com.aicompany.core.llm.OllamaLlmProvider;
import com.aicompany.core.model.ConversationTurn;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * El chat general con el CEO no tenía memoria real de la charla — cada
 * llamada a {@code CeoService.chat} solo mandaba el mensaje actual,
 * nunca los turnos anteriores (reportado por el usuario: "no está
 * recordando las charlas que tengo con el CEO", reproducido en vivo
 * preguntando el color favorito declarado un turno antes). Distinto del
 * "foco" de {@code LastMentioned} (que resuelve "esas"/"las dos" contra
 * misiones reales, no continuidad de charla).
 */
class CeoServiceChatHistoryTest {

    private final CeoService ceoService = new CeoService(
            mock(RestClient.class),
            "qwen3:8b",
            JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class),
            mock(CompanyEventPublisher.class),
            new SimpleMeterRegistry(),
            mock(LlmProvider.class),
            mock(OllamaLlmProvider.class),
            mock(AiBudgetService.class)
    );

    @Test
    void mapsPersistedRolesToOllamaChatRoles() {

        var history = List.of(
                new ConversationTurn("user", "Recordá que mi color favorito es el verde."),
                new ConversationTurn("ceo", "Entendido, lo tendré en cuenta.")
        );

        var messages = ceoService.buildHistoryMessages(history);

        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).get("role"));
        assertEquals("Recordá que mi color favorito es el verde.", messages.get(0).get("content"));
        assertEquals("assistant", messages.get(1).get("role"));
        assertEquals("Entendido, lo tendré en cuenta.", messages.get(1).get("content"));
    }

    @Test
    void preservesChronologicalOrder() {

        var history = List.of(
                new ConversationTurn("user", "primero"),
                new ConversationTurn("ceo", "segundo"),
                new ConversationTurn("user", "tercero")
        );

        var messages = ceoService.buildHistoryMessages(history);

        assertEquals("primero", messages.get(0).get("content"));
        assertEquals("segundo", messages.get(1).get("content"));
        assertEquals("tercero", messages.get(2).get("content"));
    }

    @Test
    void returnsEmptyListForEmptyHistory() {
        assertTrue(ceoService.buildHistoryMessages(List.of()).isEmpty());
    }
}
