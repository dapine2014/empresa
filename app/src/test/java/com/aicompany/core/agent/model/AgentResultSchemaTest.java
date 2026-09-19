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
        // minimum/maximum agregados: la restricción de gramática de Ollama
        // sola no es garantía suficiente (ver AgentResultValidator, que
        // valida el rango de nuevo de forma determinista) pero es barata y
        // complementaria.
        assertEquals(
                Map.of("type", "number", "minimum", 0, "maximum", 1),
                itemProperties.get("confidence")
        );
    }
}
