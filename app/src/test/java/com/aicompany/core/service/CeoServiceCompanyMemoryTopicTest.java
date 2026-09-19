package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import com.aicompany.core.llm.LlmProvider;
import com.aicompany.core.llm.OllamaLlmProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class CeoServiceCompanyMemoryTopicTest {

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
    void parseCompanyMemoryTopicExtractsTopicAndId() {
        var rawToolCall = Map.<String, Object>of(
                "function", Map.of(
                        "name", "query_company_memory",
                        "arguments", Map.of("topic", "MISSION_DETAILS", "id", "MISSION-1")
                )
        );

        var query = ceoService.parseCompanyMemoryTopic(rawToolCall);

        assertEquals("MISSION_DETAILS", query.topic());
        assertEquals("MISSION-1", query.id());
    }

    @Test
    void parseCompanyMemoryTopicReturnsNullIdWhenAbsent() {
        var rawToolCall = Map.<String, Object>of(
                "function", Map.of(
                        "name", "query_company_memory",
                        "arguments", Map.of("topic", "COMPANY_STATUS")
                )
        );

        var query = ceoService.parseCompanyMemoryTopic(rawToolCall);

        assertEquals("COMPANY_STATUS", query.topic());
        assertNull(query.id());
    }

    @Test
    void parseCompanyMemoryTopicReturnsNullForOtherTools() {
        var rawToolCall = Map.<String, Object>of(
                "function", Map.of(
                        "name", "search_web_evidence",
                        "arguments", Map.of("query", "algo")
                )
        );

        var query = ceoService.parseCompanyMemoryTopic(rawToolCall);

        assertNull(query);
    }

    @Test
    void detectInlineCompanyMemoryTopicExtractsTopicAndId() {
        var content = "{\"name\": \"query_company_memory\", "
                + "\"arguments\": {\"topic\": \"OPPORTUNITY_DETAILS\", \"id\": \"MISSION-7\"}}";

        var query = ceoService.detectInlineCompanyMemoryTopic(content);

        assertEquals("OPPORTUNITY_DETAILS", query.topic());
        assertEquals("MISSION-7", query.id());
    }

    @Test
    void detectInlineCompanyMemoryTopicReturnsNullIdWhenAbsent() {
        var content = "{\"name\": \"query_company_memory\", "
                + "\"arguments\": {\"topic\": \"AGENT_STATUS\"}}";

        var query = ceoService.detectInlineCompanyMemoryTopic(content);

        assertEquals("AGENT_STATUS", query.topic());
        assertNull(query.id());
    }
}
