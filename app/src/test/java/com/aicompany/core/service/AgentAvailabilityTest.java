package com.aicompany.core.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Decisión del fundador (2026-10-01): solo Alex y el Development Group trabajan mientras Forjai se entrena. */
class AgentAvailabilityTest {

    private final CompanyMemoryService memory = mock(CompanyMemoryService.class);
    private final AgentAvailability availability = new AgentAvailability(memory);

    {
        when(memory.enabledAgentIds()).thenReturn(Set.of("ceo", "engineering", "frontend-ui", "qa"));
        when(memory.agentName(anyString())).thenAnswer(inv -> Optional.of(switch ((String) inv.getArgument(0)) {
            case "sales" -> "Sofia";
            case "finance" -> "Max";
            default -> "X";
        }));
    }

    @Test
    void onlyTheCeoAndTheDevelopmentGroupStartEnabled() {
        assertTrue(AgentAvailability.defaultEnabled("ceo"));
        for (var id : List.of("product-owner", "engineering", "backend", "devops", "frontend-ui",
                "interaction-design", "specialist-3d", "product", "qa", "delivery")) {
            assertTrue(AgentAvailability.defaultEnabled(id), id);
        }
        for (var id : List.of("sales", "finance", "visual-design", "telemetry", "growth-content", "community")) {
            assertFalse(AgentAvailability.defaultEnabled(id), id);
        }
    }

    @Test
    void requireEnabledNamesEveryAgentThatIsOff() {
        var ex = assertThrows(IllegalStateException.class,
                () -> availability.requireEnabled(List.of("ceo", "sales", "finance", "qa"), "iniciar una discovery"));

        assertTrue(ex.getMessage().contains("iniciar una discovery"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Sofia (sales)"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Max (finance)"), ex.getMessage());
        assertFalse(ex.getMessage().contains("(qa)"), ex.getMessage());
    }

    @Test
    void requireEnabledPassesWhenAllAreOn() {
        assertDoesNotThrow(() -> availability.requireEnabled(List.of("ceo", "engineering"), "x"));
    }

    @Test
    void theCeoCannotBeTurnedOff() {
        var ex = assertThrows(IllegalArgumentException.class, () -> availability.setEnabled("ceo", false));
        assertTrue(ex.getMessage().contains("Alex"), ex.getMessage());
        verify(memory, never()).setAgentEnabled(anyString(), anyBooleanArg());
    }

    @Test
    void otherAgentsCanBeTurnedOnAndOff() {
        when(memory.agentName("sales")).thenReturn(Optional.of("Sofia"));
        availability.setEnabled("sales", true);
        verify(memory).setAgentEnabled("sales", true);
    }

    @Test
    void anUnknownAgentIsRejected() {
        when(memory.agentName("nadie")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> availability.setEnabled("nadie", true));
    }

    private static boolean anyBooleanArg() {
        return org.mockito.ArgumentMatchers.anyBoolean();
    }
}
