package com.aicompany.core.prospecting;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Spec búsqueda de prospectos §1 (función pura): primero las estrategias que el producto nunca usó, en orden de catálogo;
 * después la de mejor rendimiento (válidos por corrida) sin repetir la de la corrida anterior; a igualdad, la usada hace
 * más tiempo.
 */
public final class StrategySelector {

    private StrategySelector() {
    }

    public static StrategyOption choose(List<StrategyOption> options, String productId, List<RunStat> history) {
        if (options.isEmpty()) {
            throw new IllegalStateException("No hay estrategias de búsqueda disponibles.");
        }
        var own = history.stream().filter(r -> productId.equals(r.productId())).toList();
        for (var option : options) {
            if (own.stream().noneMatch(r -> option.id().equals(r.strategyId()))) {
                return option;
            }
        }
        var last = own.stream().max(Comparator.comparing(RunStat::startedAt)).map(RunStat::strategyId).orElse(null);
        var candidates = options.size() > 1
                ? options.stream().filter(o -> !o.id().equals(last)).toList()
                : options;
        return candidates.stream()
                .max(Comparator.<StrategyOption>comparingDouble(o -> validPerRun(own, o.id()))
                        .thenComparing(o -> lastUse(own, o.id()), Comparator.reverseOrder()))
                .orElseThrow();
    }

    private static double validPerRun(List<RunStat> own, String strategyId) {
        return own.stream().filter(r -> strategyId.equals(r.strategyId())).mapToInt(RunStat::valid).average().orElse(0);
    }

    private static Instant lastUse(List<RunStat> own, String strategyId) {
        return own.stream().filter(r -> strategyId.equals(r.strategyId())).map(RunStat::startedAt)
                .max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
    }
}
