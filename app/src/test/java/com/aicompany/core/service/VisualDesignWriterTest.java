package com.aicompany.core.service;

import com.aicompany.core.agent.model.InteractionDesign.*;
import com.aicompany.core.agent.model.Mockups;
import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.agent.model.VisualDesign;
import com.aicompany.core.agent.model.VisualSystem;
import com.aicompany.core.event.CompanyEventPublisher;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VisualDesignWriterTest {

    private final CeoService ceo = mock(CeoService.class);
    private final MissionMemoryService missions = mock(MissionMemoryService.class);
    private final VisualDesignMemoryService designs = mock(VisualDesignMemoryService.class);
    private final RequirementsMemoryService requirements = mock(RequirementsMemoryService.class);
    private final InteractionDesignMemoryService interaction = mock(InteractionDesignMemoryService.class);
    private final CompanyMemoryService company = mock(CompanyMemoryService.class);
    private final PromptMemoryService prompts = mock(PromptMemoryService.class);
    private final AgentAvailability availability = mock(AgentAvailability.class);
    private final VisualDesignWriter writer = new VisualDesignWriter(ceo, missions, designs, requirements, interaction,
            company, prompts, availability, mock(CompanyEventPublisher.class), JsonMapper.builder().build(), "qwen3:8b");

    static final RequirementsSpec SPEC = new RequirementsSpec("CRM", List.of(), List.of(),
            List.of(new RequirementsSpec.Role("vendedor", "d")),
            List.of(new RequirementsSpec.UserStory("HU-01", "vendedor", "q", "p", List.of("c"), List.of(), List.of()),
                    new RequirementsSpec.UserStory("HU-02", "vendedor", "q", "p", List.of("c"), List.of(), List.of())),
            List.of(new RequirementsSpec.Increment(1, "MVP", List.of("HU-01")),
                    new RequirementsSpec.Increment(2, "Detalle", List.of("HU-02"))), "APP");

    static final AppDesign APP = new AppDesign(List.of(
            new Screen("PANT-01", "Lista", "ver", "vendedor", List.of()),
            new Screen("PANT-02", "Detalle", "ver uno", "vendedor", List.of())),
            List.of(new Flow("HU-01", List.of("PANT-01"), List.of()), new Flow("HU-02", List.of("PANT-02"), List.of())),
            List.of(), List.of());

    static VisualSystem system(String onPrimary) {
        return new VisualSystem(List.of(new VisualSystem.ColorToken("primary", "#1F4E79"),
                new VisualSystem.ColorToken("onPrimary", onPrimary), new VisualSystem.ColorToken("surface", "#FFFFFF"),
                new VisualSystem.ColorToken("onSurface", "#111111"), new VisualSystem.ColorToken("error", "#B00020"),
                new VisualSystem.ColorToken("onError", "#FFFFFF")),
                new VisualSystem.Typography("Roboto", Map.of("display", 32.0, "title", 20.0, "body", 14.0, "label", 12.0)),
                Map.of("md", 16.0), Map.of("md", 8.0),
                List.of(new VisualSystem.ComponentStyle("button", Map.of("background", "primary", "text", "onPrimary"))));
    }

    static VisualDesign design(String onPrimary, String screen) {
        return new VisualDesign(system(onPrimary), new Mockups(List.of(new Mockups.Mockup(screen,
                "<div data-id=\"root\" style=\"color:var(--color-onSurface)\">x</div>")), List.of()));
    }

    {
        when(availability.isEnabled("frontend-ui")).thenReturn(true);
        when(company.agentModel(eq("frontend-ui"), anyString())).thenReturn("nvidia:x/y");
        when(prompts.activePrompt("frontend-ui")).thenReturn("");
        when(designs.saveDraft(anyString(), anyString(), anyInt(), anyInt(), any())).thenReturn(1);
    }

    @Test void acceptsValidDesignFirstAttempt() {
        when(ceo.writeVisualDesign(anyString(), anyString(), anyString(), anyString())).thenReturn(design("#FFFFFF", "PANT-01"));
        var written = writer.write("M-1", "frontend-ui", SPEC, APP, 1, Optional.empty(), null);
        assertEquals(1, written.version());
        verify(designs).saveDraft(eq("M-1"), anyString(), anyInt(), anyInt(), isNull());
        verify(missions).createTask("M-1-VISUAL", "M-1", "frontend-ui", "VISUAL_DESIGN", "DESIGN");
        verify(missions).updateTask(eq("M-1-VISUAL"), eq("COMPLETED"), anyString());
    }

    @Test void retriesWithCorrectionWhenValidatorRejects() {
        when(ceo.writeVisualDesign(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(design("#1F4E78", "PANT-01"), design("#FFFFFF", "PANT-01"));
        writer.write("M-1", "frontend-ui", SPEC, APP, 1, Optional.empty(), null);
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ceo, times(2)).writeVisualDesign(eq("frontend-ui"), captor.capture(), anyString(), anyString());
        var second = captor.getAllValues().get(1);
        assertTrue(second.contains("CORRECCIÓN DEL INTENTO ANTERIOR"), second);
        assertTrue(second.contains("contraste"), second);
    }

    @Test void failsAfterMaxAttempts() {
        when(ceo.writeVisualDesign(anyString(), anyString(), anyString(), anyString())).thenReturn(design("#1F4E78", "PANT-01"));
        var ex = assertThrows(IllegalStateException.class,
                () -> writer.write("M-1", "frontend-ui", SPEC, APP, 1, Optional.empty(), null));
        assertTrue(ex.getMessage().contains("después de 3 intentos"), ex.getMessage());
        verify(designs, never()).saveDraft(anyString(), anyString(), anyInt(), anyInt(), any());
    }

    @Test void asksMockupsOnlyForIncrementScreens() {
        when(ceo.writeVisualDesign(anyString(), anyString(), anyString(), anyString())).thenReturn(design("#FFFFFF", "PANT-02"));
        writer.write("M-1", "frontend-ui", SPEC, APP, 2, Optional.of(design("#FFFFFF", "PANT-01")), null);
        verify(ceo).writeVisualDesign(eq("frontend-ui"), argThat(p -> p.contains("PANT-02") && !p.contains("PANT-01")),
                anyString(), anyString());
    }

    @Test void throwsWhenFrontAgentDisabled() {
        when(availability.isEnabled("frontend-ui")).thenReturn(false);
        assertThrows(IllegalStateException.class,
                () -> writer.write("M-1", "frontend-ui", SPEC, APP, 1, Optional.empty(), null));
        verifyNoInteractions(ceo);
    }
}
