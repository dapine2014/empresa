package com.aicompany.core.prospecting;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Spec búsqueda de prospectos §1: Java elige la estrategia del día. */
class StrategySelectorTest {

    private static final StrategyOption A = new StrategyOption("A", "a", "d", "h");
    private static final StrategyOption B = new StrategyOption("B", "b", "d", "h");
    private static final StrategyOption C = new StrategyOption("C", "c", "d", "h");

    private static RunStat run(String product, String strategy, int valid, String at) {
        return new RunStat(product, strategy, valid, Instant.parse(at));
    }

    @Test
    void unusedStrategiesGoFirstInCatalogOrder() {
        var history = List.of(run("P1", "A", 5, "2026-09-01T08:00:00Z"));

        assertEquals("B", StrategySelector.choose(List.of(A, B, C), "P1", history).id());
    }

    @Test
    void otherProductsHistoryDoesNotCount() {
        var history = List.of(run("P2", "A", 5, "2026-09-01T08:00:00Z"));

        assertEquals("A", StrategySelector.choose(List.of(A, B), "P1", history).id());
    }

    @Test
    void whenAllWereUsedTheBestYieldWinsButNotYesterdays() {
        var history = List.of(
                run("P1", "A", 1, "2026-09-01T08:00:00Z"),
                run("P1", "B", 6, "2026-09-02T08:00:00Z"),
                run("P1", "C", 3, "2026-09-03T08:00:00Z"),
                run("P1", "B", 8, "2026-09-04T08:00:00Z"));

        // B tiene el mejor rendimiento pero fue la de ayer: gana C (3) sobre A (1).
        assertEquals("C", StrategySelector.choose(List.of(A, B, C), "P1", history).id());
    }

    @Test
    void aTieGoesToTheOneUsedLongestAgo() {
        var history = List.of(
                run("P1", "A", 2, "2026-09-01T08:00:00Z"),
                run("P1", "B", 2, "2026-09-02T08:00:00Z"),
                run("P1", "C", 9, "2026-09-03T08:00:00Z"));

        assertEquals("A", StrategySelector.choose(List.of(A, B, C), "P1", history).id());
    }

    @Test
    void aSingleOptionIsRepeated() {
        var history = List.of(run("P1", "A", 0, "2026-09-01T08:00:00Z"));

        assertEquals("A", StrategySelector.choose(List.of(A), "P1", history).id());
    }

    @Test
    void theBaseCatalogHasFourStrategiesWithStableIds() {
        var ids = BaseStrategy.options().stream().map(StrategyOption::id).toList();

        assertEquals(List.of("BASE-DIRECTORIES", "BASE-COMMUNITIES", "BASE-COMPETITOR_CUSTOMERS", "BASE-NICHE_LISTS"), ids);
    }
}
