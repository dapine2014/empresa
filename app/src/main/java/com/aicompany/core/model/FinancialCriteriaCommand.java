package com.aicompany.core.model;

import java.time.LocalDate;
import java.util.Locale;

public record FinancialCriteriaCommand(
        FinancialMetric metric,
        double targetAmount,
        String currency,
        LocalDate deadline) {

    public String currencyOrDefault() {
        return currency == null || currency.isBlank()
                ? "USD"
                : currency.toUpperCase(Locale.ROOT);
    }
}
