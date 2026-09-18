package com.aicompany.core.controller;

import com.aicompany.core.model.DiscardLeadCommand;
import com.aicompany.core.model.LeadResponse;
import com.aicompany.core.service.OpportunityMemoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Puente LEAD → cliente real (`empresa.md` §5): listar y descartar son
 * nivel 🟢 (visibilidad sobre datos ya generados por agentes); convertir
 * un LEAD en cliente real sigue siendo, a propósito, vía
 * {@code CustomerController.registerCustomer} (nivel 🔴, contacto/cierre
 * de venta reales) — este controller nunca crea un {@code Customer} real.
 */
@RestController
@RequestMapping("/api/company/leads")
public class LeadController {

    private final OpportunityMemoryService opportunityMemory;

    public LeadController(OpportunityMemoryService opportunityMemory) {
        this.opportunityMemory = opportunityMemory;
    }

    @GetMapping
    public List<LeadResponse> list() {
        return opportunityMemory.listLeads();
    }

    @PostMapping("/{leadId}/discard")
    public LeadResponse discard(
            @PathVariable String leadId,
            @Valid @RequestBody DiscardLeadCommand command) {

        return opportunityMemory.discardLead(leadId, command.reason())
                .orElseThrow(() -> new IllegalStateException(
                        "El lead " + leadId
                                + " no existe o ya no está en estado LEAD"
                ));
    }
}
