package com.aicompany.core.controller;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.AgentAvailability;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentEnabledControllerTest {

    private final AgentAvailability availability = mock(AgentAvailability.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final AgentEnabledController controller = new AgentEnabledController(availability, events);

    @Test
    void turningAnAgentOnOrOffIsSavedAndPublished() {
        var response = controller.update("sales", new AgentEnabledController.EnabledCommand(true));

        verify(availability).setEnabled("sales", true);
        verify(events).publish(eq("EMPRESA_AGENT_ENABLED_CHANGED"), eq(null), eq(null), eq("human"),
                eq(Map.of("agentId", "sales", "enabled", true)));
        assertEquals(new AgentEnabledController.EnabledResponse("sales", true), response);
    }

    @Test
    void aMissingValueIsRejectedWithoutChangingAnything() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.update("sales", new AgentEnabledController.EnabledCommand(null)));
        verifyNoInteractions(availability, events);
    }
}
