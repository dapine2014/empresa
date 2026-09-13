package com.aicompany.core.model;

public record MissionProfitResponse(
        String missionId,
        double totalRevenueUsd,
        double totalCostUsd,
        double netProfitUsd,
        double seedCapitalUsd,
        boolean successCriterionMet,
        String successLevel
) {
}
