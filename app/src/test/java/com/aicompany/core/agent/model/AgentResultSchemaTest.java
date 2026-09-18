package com.aicompany.core.agent.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentResultSchemaTest {

    @SuppressWarnings("unchecked")
    @Test
    void customerCandidateItemRequiresConfidenceAsANumber() {
        var properties = (Map<String, Object>) AgentResultSchema.SCHEMA.get("properties");
        var customerCandidatesProperty = (Map<String, Object>) properties.get("customerCandidates");
        var customerCandidateItem = (Map<String, Object>) customerCandidatesProperty.get("items");

        var itemProperties = (Map<String, Object>) customerCandidateItem.get("properties");
        var required = (List<String>) customerCandidateItem.get("required");

        assertTrue(required.contains("confidence"));
        assertEquals(Map.of("type", "number"), itemProperties.get("confidence"));
    }
}
