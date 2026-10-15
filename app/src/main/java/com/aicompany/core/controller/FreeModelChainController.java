package com.aicompany.core.controller;

import com.aicompany.core.model.ModelHealth;
import com.aicompany.core.service.FreeModelChainService;
import com.aicompany.core.service.ModelHealthService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Cadena de modelos gratis (2026-10-15): el orden en que se prueban los gratis antes del respaldo pago, con el estado
 * de cada uno; se edita desde Settings.
 */
@RestController
@RequestMapping("/api/company/models/free-chain")
public class FreeModelChainController {

    public record FreeModelChainView(List<ModelHealth> models, String updatedAt, String updatedBy) {
    }

    private final FreeModelChainService chain;
    private final ModelHealthService health;

    public FreeModelChainController(FreeModelChainService chain, ModelHealthService health) {
        this.chain = chain;
        this.health = health;
    }

    @GetMapping
    public FreeModelChainView get() {
        var current = chain.chain();
        return new FreeModelChainView(health.freeChainSnapshot(), current.updatedAt(), current.updatedBy());
    }

    @PutMapping
    public FreeModelChainView update(@RequestBody Map<String, List<String>> body) {
        chain.update(body.get("models"), "human");
        return get();
    }
}
