package com.aicompany.core.model;

import java.time.Instant;

public record TransactionResponse(
        String transactionId,
        String missionId,
        String customerId,
        double revenueUsd,
        double costUsd,
        double netProfitUsd,
        Instant recordedAt
) {
}
