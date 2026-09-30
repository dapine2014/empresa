package com.aicompany.core.prospecting;

import java.time.Instant;

/** Estrategia propuesta por Marketing: status PENDING_APPROVAL | APPROVED | REJECTED. */
public record StoredStrategy(String id, String name, String description, String searchHints, String status,
                             String proposedBy, Instant proposedAt, Instant decidedAt) {
}
