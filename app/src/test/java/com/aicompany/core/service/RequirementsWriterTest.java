package com.aicompany.core.service;

import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.agent.model.RequirementsSpec.*;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.agent.model.ClientContract;
import org.mockito.ArgumentCaptor;
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
            List.of(new Increment(1, "MVP", List.of("HU-01"))), "APP");

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
    void allInvalidIncludingThePaidFallbackFailsAndTheMessageSaysSo() {
        when(company.agentPaidFallbackModel("product-owner")).thenReturn("bedrock:kimi");
        when(ceo.writeRequirements(anyString(), anyString(), anyString(), anyString())).thenReturn(INVALID);
        var ex = assertThrows(IllegalStateException.class, () -> writer.write("M-1", "x", Optional.empty(), null));
        verify(ceo, times(4)).writeRequirements(anyString(), anyString(), anyString(), anyString());
        verify(ceo).writeRequirements(anyString(), contains("CORRECCIÓN DEL INTENTO ANTERIOR"), anyString(), eq("bedrock:kimi"));
        assertTrue(ex.getMessage().contains("después de 3 intentos (y uno con el respaldo pago bedrock:kimi)"),
                ex.getMessage());
    }

    @Test
    void withoutPaidFallbackItIsExactlyThreeCalls() {
        when(ceo.writeRequirements(anyString(), anyString(), anyString(), anyString())).thenReturn(INVALID);
        var ex = assertThrows(IllegalStateException.class, () -> writer.write("M-1", "x", Optional.empty(), null));
        verify(ceo, times(3)).writeRequirements(anyString(), anyString(), anyString(), anyString());
        assertFalse(ex.getMessage().contains("respaldo pago"));
    }

    @Test
    void aCancelledMissionNeverTriesThePaidFallback() {
        when(company.agentPaidFallbackModel("product-owner")).thenReturn("bedrock:kimi");
        when(ceo.writeRequirements(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new MissionCancellations.MissionCancelledException("M-1"));
        assertThrows(IllegalStateException.class, () -> writer.write("M-1", "x", Optional.empty(), null));
        verify(ceo, never()).writeRequirements(anyString(), anyString(), anyString(), eq("bedrock:kimi"));
    }

    @Test
    void aWrappedCancellationNeverTriesThePaidFallback() {
        when(company.agentPaidFallbackModel("product-owner")).thenReturn("bedrock:kimi");
        when(ceo.writeRequirements(anyString(), anyString(), anyString(), anyString())).thenThrow(
                new IllegalStateException("Aria no devolvió un JSON válido",
                        new MissionCancellations.MissionCancelledException("M-1")));
        var ex = assertThrows(IllegalStateException.class, () -> writer.write("M-1", "x", Optional.empty(), null));
        verify(ceo, never()).writeRequirements(anyString(), anyString(), anyString(), eq("bedrock:kimi"));
        assertFalse(ex.getMessage().contains("respaldo pago"), ex.getMessage());
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

    // Verificado en vivo (MISSION-ARIA-CRM-1791093366): los 3 intentos llegaron con incrementos vacíos (el modelo usó otro
    // nombre de campo, que se pierde). La corrección le muestra cómo entendió Java su respuesta.
    @Test
    void theCorrectionShowsWhatJavaUnderstood() {
        var empty = new RequirementsSpec("CRM", List.of(), List.of(), List.of(new Role("vendedor", "x")),
                VALID.userStories(), List.of(new Increment(1, "MVP", List.of())), "APP");
        when(ceo.writeRequirements(eq("product-owner"), anyString(), anyString(), anyString())).thenReturn(empty, VALID);

        writer.write("M-1", "Un CRM simple", Optional.empty(), null);

        verify(ceo).writeRequirements(eq("product-owner"), argThat(p -> p.contains("CORRECCIÓN DEL INTENTO ANTERIOR")
                && p.contains("userStoryIds") && p.contains("\"userStoryIds\":[]")), anyString(), anyString());
    }

    // Con NVIDIA el esquema no se fuerza (json_object): el primer prompt lleva la forma exacta del JSON.
    @Test
    void theFirstPromptCarriesTheExactJsonShape() {
        when(ceo.writeRequirements(anyString(), anyString(), anyString(), anyString())).thenReturn(VALID);

        writer.write("M-1", "Un CRM", Optional.empty(), null);

        verify(ceo).writeRequirements(eq("product-owner"), argThat(p -> !p.contains("CORRECCIÓN")
                && p.contains("\"userStoryIds\"") && p.contains("\"acceptanceCriteria\"") && p.contains("\"given\"")
                && p.contains("\"outOfScope\"")), anyString(), anyString());
    }

    private static final ClientContract CONTRACT = new ClientContract("Un CRM de ventas.", "APP",
            List.of(new ClientContract.Item("MOD-01", "MODULE", "Clientes", "alta y búsqueda")), List.of(), false,
            "pequeño");

    // Contrato (spec 2026-10-16 contrato §2): con contrato aprobado, Aria escribe desde él y Java copia sus ítems.
    @Test
    void withAnApprovedContractAriaWritesFromItAndJavaCopiesItsItems() {
        var contracts = mock(ContractMemoryService.class);
        when(contracts.approved("M-1")).thenReturn(Optional.of(new ContractMemoryService.Version(1,
                JsonMapper.builder().build().writeValueAsString(CONTRACT), "APPROVED", "", null)));
        writer.setContracts(contracts);
        var withItem = new RequirementsSpec("CRM", List.of(), List.of(), List.of(new Role("vendedor", "x")),
                List.of(new UserStory("HU-01", "vendedor", "q", "p", List.of("c"), List.of(),
                        List.of(new Scenario("ESC-01", "t", List.of("d"), List.of("c"), List.of("e"))), "MOD-01")),
                List.of(new Increment(1, "MVP", List.of("HU-01"))), "APP");
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceo.writeRequirements(eq("product-owner"), prompt.capture(), any(), any())).thenReturn(withItem);

        var written = writer.write("M-1", "un crm", Optional.empty(), null);

        assertEquals(List.of("MOD-01"), written.spec().contractItemsOrEmpty().stream().map(ClientContract.Item::id).toList());
        verify(requirements).saveDraft(eq("M-1"), argThat(json -> json.contains("\"contractItems\"")), any());
        assertTrue(prompt.getValue().contains("CONTRATO APROBADO POR EL CLIENTE"), prompt.getValue());
        assertTrue(prompt.getValue().contains("- MOD-01 [Módulo] Clientes: alta y búsqueda"), prompt.getValue());
        assertTrue(prompt.getValue().contains("contractItem"), prompt.getValue());
    }

    // Review Focus 3: sin contrato (misión vieja), el prompt y lo guardado no cambian.
    @Test
    void withoutAContractThePromptStaysAsBefore() {
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceo.writeRequirements(eq("product-owner"), prompt.capture(), any(), any())).thenReturn(VALID);

        var written = writer.write("M-1", "un crm", Optional.empty(), null);

        assertTrue(prompt.getValue().contains("PEDIDO DEL FUNDADOR"), prompt.getValue());
        assertFalse(prompt.getValue().contains("CONTRATO APROBADO"), prompt.getValue());
        assertEquals(List.of(), written.spec().contractItemsOrEmpty());
    }
}
