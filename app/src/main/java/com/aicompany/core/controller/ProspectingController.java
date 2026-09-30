package com.aicompany.core.controller;

import com.aicompany.core.prospecting.Prospect;
import com.aicompany.core.prospecting.ProspectingMemoryService;
import com.aicompany.core.prospecting.ProspectingRun;
import com.aicompany.core.prospecting.ProspectingService;
import com.aicompany.core.prospecting.StoredStrategy;
import com.aicompany.core.prospecting.StrategyProposalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Spec búsqueda de prospectos §3: prospectos, corridas y estrategias; aprobar/rechazar es del fundador (🔴). */
@RestController
@RequestMapping("/api/company/prospecting")
public class ProspectingController {

    private final ProspectingService prospecting;
    private final StrategyProposalService strategies;
    private final ProspectingMemoryService memory;

    public ProspectingController(ProspectingService prospecting, StrategyProposalService strategies,
                                 ProspectingMemoryService memory) {
        this.prospecting = prospecting;
        this.strategies = strategies;
        this.memory = memory;
    }

    @GetMapping("/prospects")
    public List<Prospect> prospects() {
        return memory.prospects();
    }

    @GetMapping("/runs")
    public List<ProspectingRun> runs() {
        return memory.runs(20);
    }

    @PostMapping("/runs")
    public ProspectingRun runNow() {
        return prospecting.runNow();
    }

    @GetMapping("/strategies")
    public List<StrategyProposalService.StrategyView> strategies() {
        return strategies.views();
    }

    @PutMapping("/strategies/{id}/approve")
    public StoredStrategy approve(@PathVariable("id") String id) {
        return strategies.approve(id);
    }

    @PutMapping("/strategies/{id}/reject")
    public StoredStrategy reject(@PathVariable("id") String id) {
        return strategies.reject(id);
    }
}
