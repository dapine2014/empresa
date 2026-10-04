package com.aicompany.core.service;

import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.agent.model.RequirementsSpec.*;
import com.aicompany.core.event.CompanyEventPublisher;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec 2026-10-03 Aria §1-§2: Aria escribe el spec; Java valida y ella corrige hasta 3 veces. */
class RequirementsWriterTest {

    private final CeoService ceo = mock(CeoService.class);
    private final MissionMemoryService missions = mock(MissionMemoryService.class);
    private final RequirementsMemoryService requirements = mock(RequirementsMemoryService.class);
    private final CompanyMemoryService company = mock(CompanyMemoryService.class);
    private final PromptMemoryService prompts = mock(PromptMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final RequirementsWriter writer = new RequirementsWriter(ceo, missions, requirements, company, prompts,
            events, JsonMapper.builder().build(), "qwen3:8b");

    {
        when(company.agentModel(eq("product-owner"), anyString())).thenReturn("nvidia:x/y");
        when(prompts.activePrompt("product-owner")).thenReturn("");
        when(requirements.saveDraft(anyString(), anyString(), any())).thenReturn(1);
    }

    private static final RequirementsSpec VALID = new RequirementsSpec("CRM", List.of(), List.of(),
            List.of(new Role("vendedor", "x")),
            List.of(new UserStory("HU-01", "vendedor", "q", "p", List.of("c"), List.of(),
                    List.of(new Scenario("ESC-01", "t", List.of("d"), List.of("c"), List.of("e"))))),
            List.of(new Increment(1, "MVP", List.of("HU-01"))));

    private static final RequirementsSpec INVALID = new RequirementsSpec("CRM", List.of(), List.of(), List.of(),
            List.of(), List.of());

    @Test
    void anInvalidSpecIsRetriedWithTheExactCorrection() {
        when(ceo.writeRequirements(eq("product-owner"), anyString(), anyString(), anyString())).thenReturn(INVALID, VALID);

        var written = writer.write("M-1", "Un CRM simple", Optional.empty(), null);

        assertEquals(1, written.version());
        verify(ceo).writeRequirements(eq("product-owner"), contains("CORRECCIÓN DEL INTENTO ANTERIOR"), anyString(), anyString());
        verify(missions).createTask("M-1-REQUIREMENTS", "M-1", "product-owner", "REQUIREMENTS_SPEC", "REQUIREMENTS");
        verify(missions).updateTask(eq("M-1-REQUIREMENTS"), eq("COMPLETED"), anyString());
    }

    @Test
    void threeInvalidSpecsFailWithTheReasons() {
        when(ceo.writeRequirements(anyString(), anyString(), anyString(), anyString())).thenReturn(INVALID);
        var ex = assertThrows(IllegalStateException.class, () -> writer.write("M-1", "x", Optional.empty(), null));
        assertTrue(ex.getMessage().contains("roles"), ex.getMessage());
        verify(missions).updateTask(eq("M-1-REQUIREMENTS"), eq("FAILED"), anyString());
        verify(requirements, never()).saveDraft(anyString(), anyString(), any());
    }

    @Test
    void anAdjustmentSendsThePreviousSpecAndTheInstructionAndUsesAVersionedTask() {
        when(requirements.latest("M-1")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "DRAFT", "", null)));
        when(requirements.saveDraft(anyString(), anyString(), any())).thenReturn(2);
        when(ceo.writeRequirements(anyString(), anyString(), anyString(), anyString())).thenReturn(VALID);

        writer.write("M-1", "Un CRM", Optional.of(VALID), "el MVP sin reportes");

        verify(ceo).writeRequirements(eq("product-owner"), argThat(p -> p.contains("el MVP sin reportes")
                && p.contains("HU-01")), anyString(), anyString());
        verify(missions).createTask("M-1-REQUIREMENTS-V2", "M-1", "product-owner", "REQUIREMENTS_SPEC", "REQUIREMENTS");
    }
}
