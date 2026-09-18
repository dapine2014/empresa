package com.aicompany.core.controller;

import com.aicompany.core.model.DiscardLeadCommand;
import com.aicompany.core.model.LeadResponse;
import com.aicompany.core.service.OpportunityMemoryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LeadControllerTest {

    private final OpportunityMemoryService opportunityMemory = mock(OpportunityMemoryService.class);

    private final LeadController controller = new LeadController(opportunityMemory);

    @Test
    void listDelegatesToOpportunityMemoryService() {
        var leads = List.of(new LeadResponse(
                "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                "Identificada en estudio de mercado", "https://example.com", "WEB",
                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                "LEAD", null, null
        ));
        when(opportunityMemory.listLeads()).thenReturn(leads);

        assertEquals(leads, controller.list());
    }

    @Test
    void discardReturnsTheUpdatedLeadWhenSuccessful() {
        var updated = new LeadResponse(
                "MISSION-1-CANDIDATE-SALES-0", "Panadería El Sol",
                "Identificada en estudio de mercado", "https://example.com", "WEB",
                "MISSION-1", "MISSION-1-OPPORTUNITY", Instant.now(),
                "DESCARTADO", "No responde", Instant.now()
        );
        when(opportunityMemory.discardLead("MISSION-1-CANDIDATE-SALES-0", "No responde"))
                .thenReturn(Optional.of(updated));

        var response = controller.discard(
                "MISSION-1-CANDIDATE-SALES-0", new DiscardLeadCommand("No responde"));

        assertEquals(updated, response);
    }

    @Test
    void discardThrowsWhenLeadIsNotInLeadStatus() {
        when(opportunityMemory.discardLead("MISSION-404", "motivo"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> controller.discard("MISSION-404", new DiscardLeadCommand("motivo")));
    }
}
