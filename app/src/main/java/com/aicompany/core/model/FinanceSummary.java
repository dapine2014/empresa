package com.aicompany.core.model;

import java.util.List;

/** Costos frente a ganancias (spec 2026-09-27): solo PRODUCTION suma; el libro lista todo. */
public record FinanceSummary(double seedCapitalUsd, double revenueUsd, double costsUsd, double profitUsd,
                             double balanceUsd, List<FinanceEntry> entries) {
}
