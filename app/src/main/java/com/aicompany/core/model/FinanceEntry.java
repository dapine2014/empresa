package com.aicompany.core.model;

import java.time.Instant;

/** Línea del libro: type SALE_REVENUE|SALE_COST|EXPENSE|CORRECTION; runningBalanceUsd null en líneas TEST. */
public record FinanceEntry(String movementId, String type, String description, double amountUsd, String missionId,
                           String environment, Instant occurredAt, Double runningBalanceUsd, String targetId) {
}
