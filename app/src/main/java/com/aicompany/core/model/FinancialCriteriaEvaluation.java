package com.aicompany.core.model;

import java.time.LocalDate;

public record FinancialCriteriaEvaluation(
        FinancialMetric metric,
        double targetAmount,
        String currency,
        LocalDate deadline,
        boolean criterionMet,
        double progressPct,
        Boolean deadlinePassed) {
}
