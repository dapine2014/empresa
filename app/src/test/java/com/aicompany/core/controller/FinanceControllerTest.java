package com.aicompany.core.controller;

import com.aicompany.core.model.FinanceExpenseCommand;
import com.aicompany.core.model.FinanceSummary;
import com.aicompany.core.service.FinanceService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinanceControllerTest {

    private final FinanceService finance = mock(FinanceService.class);
    private final FinanceController controller = new FinanceController(finance);

    @Test
    void everyEndpointDelegatesToTheFinanceService() {
        var summary = new FinanceSummary(50, 0, 0, 0, 50, List.of());
        when(finance.summary("MISSION-1")).thenReturn(summary);
        when(finance.registerExpense(any())).thenReturn("EXPENSE-1");

        assertEquals(summary, controller.summary("MISSION-1", null));
        assertEquals(Map.of("id", "EXPENSE-1"),
                controller.expense(new FinanceExpenseCommand("Dominio", 12, null, null, "Recibo", null)));
    }
}
