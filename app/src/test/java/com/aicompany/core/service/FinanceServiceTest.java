package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.FinanceCorrectionCommand;
import com.aicompany.core.model.FinanceExpenseCommand;
import com.aicompany.core.model.FinanceSaleCommand;
import com.aicompany.core.model.PolicyKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec finanzas (2026-09-27): el fundador registra y corrige; Java valida. */
class FinanceServiceTest {

    private final FinanceMemoryService memory = mock(FinanceMemoryService.class);
    private final CompanyPolicyService policies = mock(CompanyPolicyService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final FinanceService service = new FinanceService(memory, policies, events);

    @Test
    void aSaleWithoutMissionIsProductionAndPublishesAnEvent() {
        when(memory.customerKind("C1")).thenReturn(Optional.of("CUSTOMER"));

        var id = service.registerSale(new FinanceSaleCommand("C1", "Landing", 120, 10, null, null, "Pago por Nequi", null));

        verify(memory).createSale(eq(id), any(), eq("PRODUCTION"));
        verify(events).publish(eq("EMPRESA_SALE_RECORDED"), isNull(), isNull(), eq("human"), anyMap());
    }

    @Test
    void aMovementOfAMissionTakesTheMissionsEnvironment() {
        when(memory.missionEnvironment("MISSION-1")).thenReturn(Optional.of("TEST"));

        var id = service.registerExpense(new FinanceExpenseCommand("Dominio", 12, "MISSION-1", null, "Recibo", null));

        verify(memory).createExpense(eq(id), any(), eq("TEST"));
    }

    @Test
    void aProspectIsNotACustomer() {
        when(memory.customerKind("LEAD-1")).thenReturn(Optional.of("LEAD"));

        var ex = assertThrows(IllegalArgumentException.class, () -> service.registerSale(
                new FinanceSaleCommand("LEAD-1", "x", 10, 0, null, null, "recibo", null)));
        assertTrue(ex.getMessage().contains("prospecto"), ex.getMessage());
    }

    @Test
    void invalidInputIsRejectedWithAClearMessage() {
        assertThrows(IllegalArgumentException.class, () -> service.registerExpense(
                new FinanceExpenseCommand("Dominio", 12, null, null, " ", null)));
        assertThrows(IllegalArgumentException.class, () -> service.registerExpense(
                new FinanceExpenseCommand("Dominio", 12, null, null, "Recibo", "ftp://x")));
        assertThrows(IllegalArgumentException.class, () -> service.registerExpense(
                new FinanceExpenseCommand("Dominio", 0, null, null, "Recibo", null)));
        assertThrows(IllegalArgumentException.class, () -> service.registerExpense(
                new FinanceExpenseCommand("Dominio", 12, "MISSION-404", null, "Recibo", null)));
        verify(memory, never()).createExpense(any(), any(), any());
    }

    @Test
    void aCorrectionMustPointToASaleOrAnExpense() {
        when(memory.movementKind("C1")).thenReturn(Optional.of("CORRECTION"));
        when(memory.movementKind("X")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.registerCorrection(
                new FinanceCorrectionCommand("C1", -10, 0, "error", "nota", null)));
        assertThrows(IllegalArgumentException.class, () -> service.registerCorrection(
                new FinanceCorrectionCommand("X", -10, 0, "error", "nota", null)));
        verify(memory, never()).createCorrection(any(), any());
    }

    @Test
    void anExpenseCorrectionCanOnlyAdjustCostsAndNeedsAReason() {
        when(memory.movementKind("E1")).thenReturn(Optional.of("EXPENSE"));

        assertThrows(IllegalArgumentException.class, () -> service.registerCorrection(
                new FinanceCorrectionCommand("E1", -5, 0, "error", "nota", null)));
        assertThrows(IllegalArgumentException.class, () -> service.registerCorrection(
                new FinanceCorrectionCommand("E1", 0, -5, " ", "nota", null)));

        var id = service.registerCorrection(new FinanceCorrectionCommand("E1", 0, -5, "Cobro duplicado", "Extracto", null));

        verify(memory).createCorrection(eq(id), any());
        verify(events).publish(eq("EMPRESA_CORRECTION_RECORDED"), isNull(), isNull(), eq("human"), anyMap());
    }

    @Test
    void theSummaryUsesTheSeedCapitalPolicy() {
        when(policies.activeValue(PolicyKey.SEED_CAPITAL_USD)).thenReturn(50.0);
        when(memory.movements()).thenReturn(List.of());

        var summary = service.summary(null);

        assertEquals(50.0, summary.balanceUsd());
        assertEquals(0.0, summary.costsUsd());
    }
}
