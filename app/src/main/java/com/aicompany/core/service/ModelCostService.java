package com.aicompany.core.service;

import com.aicompany.core.model.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Costo real por misión (2026-10-04): registra los tokens de cada llamada remota con su costo (precio por proveedor en
 * {@code remote-models.providers.<p>.price.input|cached|output}, US$ por millón; sin precio = gratis) y lo resume por
 * misión y por mes. Registrar nunca rompe la llamada al modelo.
 */
@Service
public class ModelCostService {

    private static final Logger log = LoggerFactory.getLogger(ModelCostService.class);

    public record Summary(List<ModelUsageMemoryService.Row> rows) {
        public double totalUsd() {
            return rows.stream().mapToDouble(ModelUsageMemoryService.Row::costUsd).sum();
        }

        public long calls() {
            return rows.stream().mapToLong(ModelUsageMemoryService.Row::calls).sum();
        }
    }

    private final ModelUsageMemoryService memory;
    private final Environment env;

    public ModelCostService(ModelUsageMemoryService memory, Environment env) {
        this.memory = memory;
        this.env = env;
    }

    public TokenUsage.Price priceOf(String provider) {
        var prefix = "remote-models.providers." + provider + ".price.";
        return new TokenUsage.Price(env.getProperty(prefix + "input", Double.class, 0.0),
                env.getProperty(prefix + "cached", Double.class, 0.0), env.getProperty(prefix + "output", Double.class, 0.0));
    }

    public void record(String provider, String model, String actor, String operation, TokenUsage usage) {
        try {
            var context = ModelCallContext.current();
            var cost = usage.costUsd(priceOf(provider));
            memory.save(context == null ? null : context.missionId(), context == null ? null : context.taskId(), actor,
                    operation, provider, model, usage, cost);
        } catch (Exception ex) {
            log.warn("MODEL_USAGE_NOT_RECORDED provider={} model={} error={}", provider, model, ex.getMessage());
        }
    }

    public Summary missionCost(String missionId) {
        return new Summary(memory.byMission(missionId));
    }

    public Summary monthCost() {
        return new Summary(memory.thisMonth());
    }

    public static String describe(Summary summary) {
        if (summary.rows().isEmpty()) {
            return "sin llamadas remotas registradas";
        }
        return String.format(Locale.ROOT, "US$ %.2f en %d llamadas (", summary.totalUsd(), summary.calls())
                + summary.rows().stream().map(r -> String.format(Locale.ROOT,
                        "%s:%s: %d llamadas, %,d tokens de entrada (%,d de caché) y %,d de salida, US$ %.2f",
                        r.provider(), r.model(), r.calls(), r.input(), r.cached(), r.output(), r.costUsd()))
                .collect(Collectors.joining("; ")) + ")";
    }
}
