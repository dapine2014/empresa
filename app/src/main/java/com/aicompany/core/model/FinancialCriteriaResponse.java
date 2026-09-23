package com.aicompany.core.model;

import java.time.LocalDate;

public record FinancialCriteriaResponse(
        FinancialMetric metric,
        double targetAmount,
        String currency,
        LocalDate deadline) {
}
