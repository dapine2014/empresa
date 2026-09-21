package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * `query_company_memory` reemplazó `ENGINEERING_TEAM` por un
 * `TEAM_DETAILS` genérico + un segundo argumento `teamId` (ver
 * docs/superpowers/specs/2026-09-21-creative-marketing-teams-design.md,
 * decisión 5) — estos tests confirman que ambos parsers de topic
 * (tool_calls real y el fallback de JSON inline, ver
 * CeoService.chat) componen el string interno
 * "TEAM_DETAILS:&lt;teamId&gt;" que espera
 * ChatIntentRouter.answerMemoryTopic, y que el resto de los topics
 * (sin teamId) siguen intactos.
 */
class CeoServiceCompanyMemoryTopicTest {

    private final CeoService ceoService = new CeoService(
            mock(RestClient.class),
            JsonMapper.builder().build(),
            mock(EvidenceAcquisitionService.class),
            mock(CompanyEventPublisher.class),
            new SimpleMeterRegistry()
    );

    @Test
    void buildsCompositeTopicForTeamDetailsWithTeamId() {

        var toolCall = Map.<String, Object>of(
                "function", Map.of(
                        "name", "query_company_memory",
                        "arguments", Map.of("topic", "TEAM_DETAILS", "teamId", "TEAM-ENGINEERING")
                )
        );

        assertEquals("TEAM_DETAILS:TEAM-ENGINEERING", ceoService.parseCompanyMemoryTopic(toolCall));
    }

    @Test
    void buildsCompositeTopicForTeamDetailsWithoutTeamId() {

        var toolCall = Map.<String, Object>of(
                "function", Map.of(
                        "name", "query_company_memory",
                        "arguments", Map.of("topic", "TEAM_DETAILS")
                )
        );

        assertEquals("TEAM_DETAILS:", ceoService.parseCompanyMemoryTopic(toolCall));
    }

    @Test
    void leavesOtherTopicsUnchanged() {

        var toolCall = Map.<String, Object>of(
                "function", Map.of(
                        "name", "query_company_memory",
                        "arguments", Map.of("topic", "AGENT_STATUS")
                )
        );

        assertEquals("AGENT_STATUS", ceoService.parseCompanyMemoryTopic(toolCall));
    }

    @Test
    void returnsNullForNonQueryCompanyMemoryToolCall() {

        var toolCall = Map.<String, Object>of(
                "function", Map.of(
                        "name", "search_web_evidence",
                        "arguments", Map.of("topic", "TEAM_DETAILS", "teamId", "TEAM-ENGINEERING")
                )
        );

        assertNull(ceoService.parseCompanyMemoryTopic(toolCall));
    }

    @Test
    void detectsInlineTeamDetailsWithTeamId() {

        var content = "{\"name\":\"query_company_memory\","
                + "\"arguments\":{\"topic\":\"TEAM_DETAILS\",\"teamId\":\"TEAM-MARKETING-GROWTH\"}}";

        assertEquals("TEAM_DETAILS:TEAM-MARKETING-GROWTH", ceoService.detectInlineCompanyMemoryTopic(content));
    }

    @Test
    void detectsInlineOtherTopicsUnchanged() {

        var content = "{\"name\":\"query_company_memory\",\"arguments\":{\"topic\":\"COMPANY_STATUS\"}}";

        assertEquals("COMPANY_STATUS", ceoService.detectInlineCompanyMemoryTopic(content));
    }
}
