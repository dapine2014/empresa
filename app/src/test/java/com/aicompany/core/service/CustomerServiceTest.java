package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.validation.EvidenceValidationGate;
import com.aicompany.core.config.AppProperties;
import com.aicompany.core.model.CustomerCommand;
import com.aicompany.core.model.TransactionCommand;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomerServiceTest {

    private final EvidenceValidationGate evidenceGate = new EvidenceValidationGate();
    private final AppProperties appProperties = new AppProperties("AI Company", 50.0, 60);

    @Test
    void registersCustomerWhenMissionExistsAndEvidenceIsValid() {
        var memory = mock(CustomerMemoryService.class);
        when(memory.missionExists("MISSION-001")).thenReturn(true);

        var service = new CustomerService(memory, evidenceGate, appProperties);

        var command = new CustomerCommand(
                "CUST-1", "Panadería El Sol", "panaderia@example.com",
                "Pedido confirmado por WhatsApp", "chat con el dueño", "CUSTOMER", true
        );

        var response = service.registerCustomer("MISSION-001", command);

        assertEquals("CUST-1", response.customerId());
        assertEquals("MISSION-001", response.missionId());
        verify(memory).registerCustomer(
                eq("MISSION-001"), eq("CUST-1"), eq("Panadería El Sol"),
                eq("panaderia@example.com"), any(AgentResult.Evidence.class)
        );
    }

    @Test
    void rejectsCustomerWhenMissionDoesNotExist() {
        var memory = mock(CustomerMemoryService.class);
        when(memory.missionExists("MISSION-404")).thenReturn(false);

        var service = new CustomerService(memory, evidenceGate, appProperties);

        var command = new CustomerCommand(
                "CUST-1", "Panadería El Sol", null,
                "Pedido confirmado", null, "INTERNAL", false
        );

        assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomer("MISSION-404", command));

        verify(memory, never()).registerCustomer(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsCustomerWithSemanticallyInvalidEvidence() {
        var memory = mock(CustomerMemoryService.class);
        when(memory.missionExists("MISSION-001")).thenReturn(true);

        var service = new CustomerService(memory, evidenceGate, appProperties);

        // verified=true con sourceType=NONE: contradicción semántica.
        var command = new CustomerCommand(
                "CUST-1", "Panadería El Sol", null,
                "El negocio existe", null, "NONE", true
        );

        assertThrows(IllegalStateException.class,
                () -> service.registerCustomer("MISSION-001", command));

        verify(memory, never()).registerCustomer(any(), any(), any(), any(), any());
    }

    @Test
    void registersTransactionAndComputesNetProfit() {
        var memory = mock(CustomerMemoryService.class);
        when(memory.registerTransaction(
                eq("MISSION-001"), eq("CUST-1"), eq("TX-1"),
                eq("Venta de 10 camisetas"), eq(100.0), eq(40.0),
                any(AgentResult.Evidence.class)
        )).thenReturn(Optional.of("2026-09-13T00:00:00Z"));

        var service = new CustomerService(memory, evidenceGate, appProperties);

        var command = new TransactionCommand(
                "TX-1", "CUST-1", "Venta de 10 camisetas", 100.0, 40.0,
                "Comprobante de pago", "https://pagos.example.com/tx/1", "TRANSACTION", true
        );

        var response = service.registerTransaction("MISSION-001", command);

        assertTrue(response.isPresent());
        assertEquals(60.0, response.get().netProfitUsd(), 0.0001);
    }

    @Test
    void returnsEmptyWhenCustomerForTransactionDoesNotExist() {
        var memory = mock(CustomerMemoryService.class);
        when(memory.registerTransaction(
                any(), any(), any(), any(), anyDouble(), anyDouble(), any()
        )).thenReturn(Optional.empty());

        var service = new CustomerService(memory, evidenceGate, appProperties);

        var command = new TransactionCommand(
                "TX-1", "CUST-DESCONOCIDO", "Venta", 100.0, 40.0,
                "Comprobante", null, "INTERNAL", false
        );

        var response = service.registerTransaction("MISSION-001", command);

        assertTrue(response.isEmpty());
    }

    @Test
    void aggregatesNetProfitAcrossTransactions() {
        var memory = mock(CustomerMemoryService.class);
        when(memory.totalRevenueAndCost("MISSION-001"))
                .thenReturn(new double[]{200.0, 50.0});

        var service = new CustomerService(memory, evidenceGate, appProperties);

        var profit = service.netProfit("MISSION-001");

        assertEquals(200.0, profit.totalRevenueUsd());
        assertEquals(50.0, profit.totalCostUsd());
        assertEquals(150.0, profit.netProfitUsd());
        assertTrue(profit.successCriterionMet());
        assertEquals("MUY_BUENO", profit.successLevel());
    }

    @Test
    void successCriterionNotMetWhenNetProfitBelowSeedCapital() {
        var memory = mock(CustomerMemoryService.class);
        when(memory.totalRevenueAndCost("MISSION-001"))
                .thenReturn(new double[]{30.0, 10.0});

        var service = new CustomerService(memory, evidenceGate, appProperties);

        var profit = service.netProfit("MISSION-001");

        assertEquals(20.0, profit.netProfitUsd());
        assertFalse(profit.successCriterionMet());
        assertEquals("NINGUNO", profit.successLevel());
    }
}
