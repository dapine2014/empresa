package com.aicompany.core.service;

import com.aicompany.core.agent.model.ClientContract;
import com.aicompany.core.agent.model.ClientContract.Item;
import com.aicompany.core.event.CompanyEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Contrato (spec 2026-10-16 contrato §1): Aria levanta el contrato; Java valida y ella corrige hasta 3 veces. */
class ContractWriterTest {

    private final CeoService ceo = mock(CeoService.class);
    private final MissionMemoryService missions = mock(MissionMemoryService.class);
    private final ContractMemoryService contracts = mock(ContractMemoryService.class);
    private final CompanyMemoryService company = mock(CompanyMemoryService.class);
    private final PromptMemoryService prompts = mock(PromptMemoryService.class);
    private final ContractWriter writer = new ContractWriter(ceo, missions, contracts, company, prompts,
            mock(CompanyEventPublisher.class), JsonMapper.builder().build(), "qwen3:8b");

    private static final ClientContract VALID = new ClientContract("Un Pong.", "GAME",
            List.of(new Item("MOD-01", "LEVEL", "Nivel 1", "pelota normal")), List.of(), false, "un solo nivel");
    private static final ClientContract INVALID = new ClientContract("Un Pong.", "GAME",
            List.of(new Item("MOD-01", "SCENE", "Menú", "x")), List.of(), false, "uno");

    {
        when(company.agentModel(eq("product-owner"), anyString())).thenReturn("nvidia:x/y");
        when(prompts.activePrompt("product-owner")).thenReturn("");
        when(contracts.saveDraft(anyString(), anyString(), any())).thenReturn(1);
    }

    @Test void aValidContractIsSavedAsADraft() {
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceo.writeContract(eq("product-owner"), prompt.capture(), any(), any())).thenReturn(VALID);

        var written = writer.write("M-1", "un pong", Optional.empty(), null);

        assertEquals(1, written.version());
        verify(contracts).saveDraft(eq("M-1"), argThat(json -> json.contains("\"MOD-01\"")), isNull());
        verify(missions).createTask("M-1-CONTRACT", "M-1", "product-owner", "CLIENT_CONTRACT", "REQUIREMENTS");
        assertTrue(prompt.getValue().contains("PEDIDO DEL CLIENTE"), prompt.getValue());
        assertTrue(prompt.getValue().contains("un pong"), prompt.getValue());
        assertTrue(prompt.getValue().contains("useEpics"), prompt.getValue());
    }

    @Test void anInvalidContractIsRetriedWithTheExactReason() {
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceo.writeContract(eq("product-owner"), prompt.capture(), any(), any())).thenReturn(INVALID, VALID);

        writer.write("M-1", "un pong", Optional.empty(), null);

        assertTrue(prompt.getAllValues().get(1).contains("CORRECCIÓN DEL INTENTO ANTERIOR"));
        assertTrue(prompt.getAllValues().get(1).contains("al menos un ítem LEVEL"));
    }

    @Test void threeInvalidAttemptsFailTheTask() {
        when(ceo.writeContract(any(), any(), any(), any())).thenReturn(INVALID);

        var ex = assertThrows(IllegalStateException.class, () -> writer.write("M-1", "un pong", Optional.empty(), null));

        assertTrue(ex.getMessage().contains("3 intentos"));
        verify(missions).updateTask(eq("M-1-CONTRACT"), eq("FAILED"), anyString());
        verify(contracts, never()).saveDraft(any(), any(), any());
    }

    // Review Focus 1: cada ajuste del fundador parte de la versión anterior y deja una versión nueva.
    @Test void threeInvalidAttemptsGetOneMoreWithThePaidFallback() {
        when(company.agentPaidFallbackModel("product-owner")).thenReturn("bedrock:kimi");
        when(ceo.writeContract(anyString(), anyString(), any(), any())).thenReturn(INVALID, INVALID, INVALID, VALID);
        var written = writer.write("M-1", "un pong", Optional.empty(), null);
        assertEquals(1, written.version());
        verify(ceo).writeContract(anyString(), contains("CORRECCIÓN DEL INTENTO ANTERIOR"), any(), eq("bedrock:kimi"));
        verify(missions).setTaskModelUsed("M-1-CONTRACT", "bedrock:kimi");
    }

    @Test void withoutPaidFallbackItIsExactlyThreeCalls() {
        when(ceo.writeContract(anyString(), anyString(), any(), any())).thenReturn(INVALID);
        assertThrows(IllegalStateException.class, () -> writer.write("M-1", "un pong", Optional.empty(), null));
        verify(ceo, times(3)).writeContract(anyString(), anyString(), any(), any());
    }

    @Test void anAdjustmentStartsFromThePreviousVersion() {
        when(contracts.latest("M-1")).thenReturn(Optional.of(new ContractMemoryService.Version(1, "{}", "DRAFT", "", null)));
        when(contracts.saveDraft(anyString(), anyString(), any())).thenReturn(2);
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceo.writeContract(eq("product-owner"), prompt.capture(), any(), any())).thenReturn(VALID);

        var written = writer.write("M-1", "un pong", Optional.of(VALID), "el nivel 3 tiene dos pelotas");

        assertEquals(2, written.version());
        verify(missions).createTask("M-1-CONTRACT-V2", "M-1", "product-owner", "CLIENT_CONTRACT", "REQUIREMENTS");
        verify(contracts).saveDraft(eq("M-1"), anyString(), eq("el nivel 3 tiene dos pelotas"));
        assertTrue(prompt.getValue().contains("VERSIÓN ANTERIOR"), prompt.getValue());
        assertTrue(prompt.getValue().contains("AJUSTE DEL FUNDADOR:\nel nivel 3 tiene dos pelotas"), prompt.getValue());
    }
}
