package com.aicompany.core.service;

import com.aicompany.core.model.FinanceMovement;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FinanceCalculatorTest {

    private static FinanceMovement sale(String id, double revenue, double cost, String mission, String env, int minute) {
        return new FinanceMovement(id, "SALE", "venta " + id, revenue, cost, mission, env,
                Instant.parse("2026-09-27T10:00:00Z").plusSeconds(minute * 60L), null, "Cliente A");
    }

    private static FinanceMovement expense(String id, double amount, String mission, String env, int minute) {
        return new FinanceMovement(id, "EXPENSE", "gasto " + id, 0, amount, mission, env,
                Instant.parse("2026-09-27T10:00:00Z").plusSeconds(minute * 60L), null, null);
    }

    private static FinanceMovement correction(String id, String target, double revenueAdj, double costAdj, String mission,
                                              String env, int minute) {
        return new FinanceMovement(id, "CORRECTION", "Duplicada", revenueAdj, costAdj, mission, env,
                Instant.parse("2026-09-27T10:00:00Z").plusSeconds(minute * 60L), target, null);
    }

    @Test
    void costsAndProfitIncludeSalesExpensesAndCorrections() {
        var summary = FinanceCalculator.summarize(List.of(
                sale("S1", 100, 20, "MISSION-1", "PRODUCTION", 1),
                expense("E1", 12, null, "PRODUCTION", 2),
                sale("S2", 100, 20, "MISSION-1", "PRODUCTION", 3),
                correction("C1", "S2", -100, -20, "MISSION-1", "PRODUCTION", 4)), 50, null);

        assertEquals(100.0, summary.revenueUsd(), 1e-9);
        assertEquals(32.0, summary.costsUsd(), 1e-9);
        assertEquals(68.0, summary.profitUsd(), 1e-9);
        assertEquals(118.0, summary.balanceUsd(), 1e-9);
    }

    @Test
    void theLedgerIsChronologicalWithSignedAmountsAndARunningBalance() {
        var summary = FinanceCalculator.summarize(List.of(
                expense("E1", 12, null, "PRODUCTION", 2),
                sale("S1", 100, 20, null, "PRODUCTION", 1)), 50, null);

        var types = summary.entries().stream().map(e -> e.type()).toList();
        assertEquals(List.of("SALE_REVENUE", "SALE_COST", "EXPENSE"), types);
        assertEquals(List.of(100.0, -20.0, -12.0), summary.entries().stream().map(e -> e.amountUsd()).toList());
        assertEquals(List.of(150.0, 130.0, 118.0), summary.entries().stream().map(e -> e.runningBalanceUsd()).toList());
    }

    @Test
    void testMovementsAreListedButNeverCounted() {
        var summary = FinanceCalculator.summarize(List.of(
                sale("S1", 100, 0, null, "TEST", 1),
                expense("E1", 12, null, "PRODUCTION", 2)), 50, null);

        assertEquals(0.0, summary.revenueUsd(), 1e-9);
        assertEquals(12.0, summary.costsUsd(), 1e-9);
        assertEquals(2, summary.entries().size());
        assertNull(summary.entries().get(0).runningBalanceUsd());
    }

    @Test
    void aSaleWithoutCostHasOnlyARevenueLine() {
        var summary = FinanceCalculator.summarize(List.of(sale("S1", 30, 0, null, "PRODUCTION", 1)), 50, null);

        assertEquals(1, summary.entries().size());
    }

    @Test
    void perMissionOnlyCountsThatMissionsMovementsAndCorrections() {
        var summary = FinanceCalculator.summarize(List.of(
                sale("S1", 100, 20, "MISSION-1", "PRODUCTION", 1),
                expense("E1", 12, null, "PRODUCTION", 2),
                expense("E2", 5, "MISSION-1", "PRODUCTION", 3),
                correction("C1", "E2", 0, -5, "MISSION-1", "PRODUCTION", 4),
                sale("S9", 999, 0, "MISSION-9", "PRODUCTION", 5)), 50, "MISSION-1");

        assertEquals(100.0, summary.revenueUsd(), 1e-9);
        assertEquals(20.0, summary.costsUsd(), 1e-9);
        assertEquals(4, summary.entries().size());
    }

    // Spec catálogo (2026-09-28): ingresos y ganancias por producto (un producto se vende muchas veces).
    @Test
    void perProductOnlyCountsThatProductsSalesAndTheirCorrections() {
        var t0 = Instant.parse("2026-09-28T10:00:00Z");
        var summary = FinanceCalculator.summarize(List.of(
                new FinanceMovement("S1", "SALE", "a", 100, 10, null, "PRODUCTION", t0, null, "A", "P1"),
                new FinanceMovement("S2", "SALE", "b", 300, 0, null, "PRODUCTION", t0.plusSeconds(60), null, "B", "P2"),
                new FinanceMovement("C1", "CORRECTION", "rebaja", -20, 0, null, "PRODUCTION", t0.plusSeconds(120), "S1", null, "P1"),
                new FinanceMovement("E1", "EXPENSE", "dominio", 0, 12, null, "PRODUCTION", t0.plusSeconds(180), null, null)),
                50, null, "P1");

        assertEquals(80.0, summary.revenueUsd(), 1e-9);
        assertEquals(10.0, summary.costsUsd(), 1e-9);
        assertEquals(3, summary.entries().size());
    }

    @Test
    void centsAddUpExactly() {
        var summary = FinanceCalculator.summarize(List.of(
                sale("S1", 19.99, 0, null, "PRODUCTION", 1),
                sale("S2", 0.01, 0, null, "PRODUCTION", 2),
                expense("E1", 0.1, null, "PRODUCTION", 3),
                expense("E2", 0.2, null, "PRODUCTION", 4)), 50, null);

        assertEquals(20.00, summary.revenueUsd());
        assertEquals(0.30, summary.costsUsd());
        assertEquals(19.70, summary.profitUsd());
    }
}
