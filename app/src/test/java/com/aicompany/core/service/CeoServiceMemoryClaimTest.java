package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Verificado en vivo (2026-09-27, nemotron-3-ultra): Alex escribió "Según Company Memory (query_company_memory
 * COMPANY_STATUS)" sin haber llamado la herramienta. Java lo marca; nunca se le pide al modelo que se revise.
 */
class CeoServiceMemoryClaimTest {

    private final OpenAiCompatibleClient ceo = mock(OpenAiCompatibleClient.class);
    private final CeoService ceoService = new CeoService(mock(RestClient.class), JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class), mock(CompanyEventPublisher.class), new SimpleMeterRegistry(),
            Map.of("nvidia-ceo", ceo));

    private String chat(String reply) {
        when(ceo.complete(anyString(), anyList(), any(), eq(false), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply(reply, List.of()));
        return ceoService.chat("Alex", "Alex (CEO)", List.of(), "¿cuánto capital tenemos?", topic -> "datos",
                null, "nvidia-ceo:nvidia/nemotron-3-ultra-550b-a55b");
    }

    @Test
    void anAnswerClaimingTheMemoryWithoutCallingItIsFlaggedByJava() {
        var answer = chat("Según Company Memory (query_company_memory COMPANY_STATUS): **US$50.00** disponibles.");

        assertTrue(answer.startsWith("Según Company Memory"), answer);
        assertTrue(answer.contains(CeoService.UNBACKED_MEMORY_CLAIM_NOTE), answer);
    }

    @Test
    void spanishClaimsWithAccentsAreFlaggedToo() {
        assertTrue(chat("Consulté la memoria de la empresa: hay 2 misiones.").contains(CeoService.UNBACKED_MEMORY_CLAIM_NOTE));
        assertTrue(chat("Según la memoria, tenemos US$50.").contains(CeoService.UNBACKED_MEMORY_CLAIM_NOTE));
    }

    @Test
    void anAnswerWithoutAMemoryClaimIsLeftUntouched() {
        assertEquals("No tengo ese dato registrado.", chat("No tengo ese dato registrado."));
    }

    @SuppressWarnings("unchecked")
    @Test
    void anAnswerBackedByARealToolCallIsNotFlagged() {
        when(ceo.complete(anyString(), anyList(), any(), eq(false), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("", List.of(Map.of("function",
                        Map.of("name", "query_company_memory", "arguments", Map.of("topic", "COMPANY_STATUS"))))))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("Según Company Memory tenemos US$50.", List.of()));

        var answer = ceoService.chat("Alex", "Alex (CEO)", List.of(), "¿cuánto capital tenemos?", topic -> "capital 50",
                null, "nvidia-ceo:nvidia/nemotron-3-ultra-550b-a55b");

        assertEquals("Según Company Memory tenemos US$50.", answer);
    }
}
