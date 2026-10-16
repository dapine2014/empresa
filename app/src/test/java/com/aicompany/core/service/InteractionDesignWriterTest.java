package com.aicompany.core.service;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.InteractionDesign.*;
import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.event.CompanyEventPublisher;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InteractionDesignWriterTest {

    private final CeoService ceo = mock(CeoService.class);
    private final MissionMemoryService missions = mock(MissionMemoryService.class);
    private final InteractionDesignMemoryService designs = mock(InteractionDesignMemoryService.class);
    private final CompanyMemoryService company = mock(CompanyMemoryService.class);
    private final PromptMemoryService prompts = mock(PromptMemoryService.class);
    private final AgentAvailability availability = mock(AgentAvailability.class);
    private final InteractionDesignWriter writer = new InteractionDesignWriter(ceo, missions, designs, company, prompts,
            availability, mock(CompanyEventPublisher.class), JsonMapper.builder().build(), "qwen3:8b");

    static final RequirementsSpec SPEC = new RequirementsSpec("CRM", List.of(), List.of(),
            List.of(new RequirementsSpec.Role("vendedor", "d")),
            List.of(new RequirementsSpec.UserStory("HU-01", "vendedor", "q", "p", List.of("c"), List.of(),
                    List.of(new RequirementsSpec.Scenario("ESC-01", "a", List.of("d"), List.of("c"), List.of("e"))))),
            List.of(new RequirementsSpec.Increment(1, "MVP", List.of("HU-01"))), "APP");

    static final AppDesign VALID = new AppDesign(List.of(new Screen("PANT-01", "Lista", "ver", "vendedor", List.of())),
            List.of(new Flow("HU-01", List.of("PANT-01"), List.of())), List.of(new ScenarioScreen("ESC-01", "PANT-01")),
            List.of("r"));
    static final AppDesign INVALID = new AppDesign(List.of(), List.of(), List.of(), List.of());

    {
        when(availability.isEnabled("interaction-design")).thenReturn(true);
        when(company.agentModel(eq("interaction-design"), anyString())).thenReturn("nvidia:x/y");
        when(prompts.activePrompt("interaction-design")).thenReturn("");
        when(designs.saveDraft(anyString(), anyString(), anyInt(), any())).thenReturn(1);
    }

    @Test void anInvalidDesignIsRetriedWithTheCorrection() {
        when(ceo.writeAppDesign(anyString(), anyString(), anyString(), anyString())).thenReturn(INVALID, VALID);
        var written = writer.write("M-1", SPEC, 3, Optional.empty(), null);
        assertEquals(1, written.version());
        verify(ceo).writeAppDesign(eq("interaction-design"), contains("CORRECCIÓN DEL INTENTO ANTERIOR"), anyString(), anyString());
        verify(designs).saveDraft(eq("M-1"), anyString(), eq(3), isNull());
        verify(missions).createTask("M-1-INTERACTION", "M-1", "interaction-design", "INTERACTION_DESIGN", "DESIGN");
    }

    @Test void threeInvalidDesignsFail() {
        when(ceo.writeAppDesign(anyString(), anyString(), anyString(), anyString())).thenReturn(INVALID);
        assertThrows(IllegalStateException.class, () -> writer.write("M-1", SPEC, 1, Optional.empty(), null));
        verify(designs, never()).saveDraft(anyString(), anyString(), anyInt(), any());
    }

    @Test void threeInvalidDesignsWithTheFreeModelGetOneExtraAttemptWithThePaidFallback() {
        var health = mock(ModelHealthService.class);
        writer.setModelHealth(health);
        when(company.agentPaidFallbackModel("interaction-design")).thenReturn("bedrock:kimi");
        when(ceo.writeAppDesign(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(INVALID, INVALID, INVALID, VALID);
        var written = writer.write("M-1", SPEC, 1, Optional.empty(), null);
        assertEquals(1, written.version());
        verify(ceo, times(3)).writeAppDesign(anyString(), anyString(), anyString(), eq("nvidia:x/y"));
        verify(ceo).writeAppDesign(eq("interaction-design"), contains("CORRECCIÓN DEL INTENTO ANTERIOR"), anyString(),
                eq("bedrock:kimi"));
        verify(missions).setTaskModelUsed("M-1-INTERACTION", "bedrock:kimi");
    }

    @Test void withoutPaidFallbackThreeInvalidDesignsAreExactlyThreeCalls() {
        when(ceo.writeAppDesign(anyString(), anyString(), anyString(), anyString())).thenReturn(INVALID);
        var ex = assertThrows(IllegalStateException.class, () -> writer.write("M-1", SPEC, 1, Optional.empty(), null));
        verify(ceo, times(3)).writeAppDesign(anyString(), anyString(), anyString(), anyString());
        assertFalse(ex.getMessage().contains("respaldo pago"));
    }

    @Test void kaelOffFailsWithoutCallingTheModel() {
        when(availability.isEnabled("interaction-design")).thenReturn(false);
        var ex = assertThrows(IllegalStateException.class, () -> writer.write("M-1", SPEC, 1, Optional.empty(), null));
        assertTrue(ex.getMessage().contains("Kael está apagado"));
        verifyNoInteractions(ceo);
    }

    @Test void anAdjustmentSendsThePreviousDesignAndUsesAVersionedTask() {
        when(designs.latest("M-1")).thenReturn(Optional.of(new InteractionDesignMemoryService.Version(1, "{}", "DRAFT", 1, "")));
        when(ceo.writeAppDesign(anyString(), anyString(), anyString(), anyString())).thenReturn(VALID);
        writer.write("M-1", SPEC, 1, Optional.of(new InteractionDesign("APP", VALID, null)), "lista con buscador");
        verify(ceo).writeAppDesign(anyString(), argThat(p -> p.contains("lista con buscador") && p.contains("PANT-01")),
                anyString(), anyString());
        verify(missions).createTask("M-1-INTERACTION-V2", "M-1", "interaction-design", "INTERACTION_DESIGN", "DESIGN");
    }

    @Test void theCorrectionAndThePreviousDesignShowTheFlatDesignNotTheWrapper() {
        when(designs.latest("M-1")).thenReturn(Optional.of(new InteractionDesignMemoryService.Version(1, "{}", "DRAFT", 1, "")));
        when(ceo.writeAppDesign(anyString(), anyString(), anyString(), anyString())).thenReturn(INVALID, VALID);
        writer.write("M-1", SPEC, 1, Optional.of(new InteractionDesign("APP", VALID, null)), "lista con buscador");
        var prompts = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(ceo, times(2)).writeAppDesign(anyString(), prompts.capture(), anyString(), anyString());
        var first = prompts.getAllValues().get(0);
        var second = prompts.getAllValues().get(1);
        assertTrue(first.contains("DISEÑO ANTERIOR") && first.contains("\"screens\""), first);
        assertTrue(second.contains("Así entendió Java"), second);
        for (var p : List.of(first, second)) {
            assertFalse(p.contains("\"experience\"") || p.contains("\"app\":"), p);
        }
    }

    @Test void aPreviousDesignOfAnotherExperienceIsIgnored() {
        when(designs.latest("M-1")).thenReturn(Optional.of(new InteractionDesignMemoryService.Version(1, "{}", "DRAFT", 1, "")));
        when(ceo.writeAppDesign(anyString(), anyString(), anyString(), anyString())).thenReturn(VALID);
        var game = new GameDesign(List.of("PLAYING"), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
        writer.write("M-1", SPEC, 2, Optional.of(new InteractionDesign("GAME", null, game)), "más simple");
        verify(ceo).writeAppDesign(anyString(), argThat(p -> !p.contains("DISEÑO ANTERIOR") && !p.contains("PLAYING")
                && p.contains("más simple")), anyString(), anyString());
    }

    @Test void theGamePromptListsTheLevelsAndScenesOfTheContract() {
        var spec = new RequirementsSpec("Pong", List.of(), List.of(), List.of(new RequirementsSpec.Role("jugador", "d")),
                List.of(new RequirementsSpec.UserStory("HU-01", "jugador", "q", "p", List.of("c"), List.of(), List.of(), "MOD-01"),
                        new RequirementsSpec.UserStory("HU-02", "jugador", "q", "p", List.of("c"), List.of(), List.of(), "MOD-02")),
                List.of(new RequirementsSpec.Increment(1, "N1", List.of("HU-01")),
                        new RequirementsSpec.Increment(2, "Fin", List.of("HU-02"))), "GAME",
                List.of(new com.aicompany.core.agent.model.ClientContract.Item("MOD-01", "LEVEL", "Nivel 1", "x"),
                        new com.aicompany.core.agent.model.ClientContract.Item("MOD-02", "SCENE", "Game over", "x")), true);
        when(ceo.writeGameDesign(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("x"));
        assertThrows(IllegalStateException.class, () -> writer.write("M-1", spec, 1, Optional.empty(), null));
        verify(ceo, atLeastOnce()).writeGameDesign(anyString(), argThat(p -> p.contains("- MOD-01 [LEVEL] Nivel 1: x")
                && p.contains("- MOD-02 [SCENE] Game over: x")), anyString(), anyString());
    }
}
