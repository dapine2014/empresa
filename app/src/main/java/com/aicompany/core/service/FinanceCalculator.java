package com.aicompany.core.service;

import com.aicompany.core.model.FinanceEntry;
import com.aicompany.core.model.FinanceMovement;
import com.aicompany.core.model.FinanceSummary;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Spec finanzas §2: libro y totales 100% en Java; solo PRODUCTION suma; montos redondeados a centavos. */
public final class FinanceCalculator {

    private FinanceCalculator() {
    }

    public static FinanceSummary summarize(List<FinanceMovement> movements, double seedCapitalUsd, String missionIdOrNull) {
        var selected = movements.stream()
                .filter(m -> missionIdOrNull == null || missionIdOrNull.equals(m.missionId()))
                .sorted(Comparator.comparing(FinanceMovement::recordedAt))
                .toList();

        var entries = new ArrayList<FinanceEntry>();
        var revenue = BigDecimal.ZERO;
        var costs = BigDecimal.ZERO;
        var running = cents(seedCapitalUsd);

        for (var m : selected) {
            var counts = "PRODUCTION".equals(m.environment());
            var lines = switch (m.kind()) {
                case "SALE" -> m.costUsd() > 0
                        ? List.of(line(m, "SALE_REVENUE", m.revenueUsd()), line(m, "SALE_COST", -m.costUsd()))
                        : List.of(line(m, "SALE_REVENUE", m.revenueUsd()));
                case "EXPENSE" -> List.of(line(m, "EXPENSE", -m.costUsd()));
                case "CORRECTION" -> List.of(line(m, "CORRECTION", m.revenueUsd() - m.costUsd()));
                default -> List.<FinanceEntry>of();
            };
            if (counts) {
                revenue = revenue.add(cents(m.revenueUsd()));
                costs = costs.add(cents(m.costUsd()));
            }
            for (var entry : lines) {
                if (counts) {
                    running = running.add(cents(entry.amountUsd()));
                }
                entries.add(new FinanceEntry(entry.movementId(), entry.type(), entry.description(), entry.amountUsd(),
                        entry.missionId(), entry.environment(), entry.occurredAt(),
                        counts ? running.doubleValue() : null, entry.targetId()));
            }
        }

        var profit = revenue.subtract(costs);
        return new FinanceSummary(cents(seedCapitalUsd).doubleValue(), revenue.doubleValue(), costs.doubleValue(),
                profit.doubleValue(), cents(seedCapitalUsd).add(profit).doubleValue(), entries);
    }

    private static FinanceEntry line(FinanceMovement m, String type, double amount) {
        return new FinanceEntry(m.id(), type, m.description(), cents(amount).doubleValue(), m.missionId(),
                m.environment(), m.recordedAt(), null, m.targetId());
    }

    private static BigDecimal cents(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
