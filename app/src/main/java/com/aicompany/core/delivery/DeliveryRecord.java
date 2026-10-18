package com.aicompany.core.delivery;

import java.util.List;

/** Entrega de un incremento (spec 2026-10-17). {@code status}: PACKAGING|READY|PARTIAL|FAILED. */
public record DeliveryRecord(String missionId, int increment, String status, String path, long sizeBytes,
                             List<DeliveryFacts.TargetResult> targets, String error, String createdAt) {
}
