package com.aicompany.core.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Spec 2026-09-27 §5: menciones @Nombre o @agentId contra los agentes reales. */
class MentionResolverTest {

    private static final List<Map<String, Object>> AGENTS = List.of(
            Map.of("id", "ceo", "name", "Alex", "role", "CEO", "personality", "x"),
            Map.of("id", "growth-content", "name", "Kira", "role", "Growth", "personality", "x"),
            Map.of("id", "sales", "name", "Sofía", "role", "Sales", "personality", "x"));

    @Test
    void resolvesNamesAndIdsIgnoringCaseAndAccentsInOrderWithoutDuplicates() {
        assertEquals(List.of("growth-content"), MentionResolver.resolve("@Kira ideas", AGENTS).agentIds());
        assertEquals(List.of("growth-content", "sales"), MentionResolver.resolve("@kíra y @SOFIA, ¿qué opinan? @Kira", AGENTS).agentIds());
        assertEquals(List.of("growth-content"), MentionResolver.resolve("hola @growth-content", AGENTS).agentIds());
    }

    @Test
    void unknownMentionsAreReported() {
        var resolution = MentionResolver.resolve("@Pepe @Kira", AGENTS);
        assertEquals(List.of("Pepe"), resolution.unknown());
        assertEquals(List.of("growth-content"), resolution.agentIds());
    }

    @Test
    void noMentionOrAnEmailIsNotAMention() {
        assertEquals(List.of(), MentionResolver.resolve("dame un status", AGENTS).agentIds());
        var email = MentionResolver.resolve("escríbele a kira@empresa.com", AGENTS);
        assertEquals(List.of(), email.agentIds());
        assertEquals(List.of(), email.unknown());
    }
}
