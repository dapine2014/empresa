package com.aicompany.core.model;

import java.util.List;

/**
 * Spec 2026-11-01 diseño visual §14-15: la revisión de diseño de una misión. {@code blockReason}: la primera línea del
 * motivo si la misión está frenada por diseño ({@code DESIGN_MISMATCH: …} o {@code DESIGN_CAPTURE_FAILED: …}), si no
 * null. {@code decider}: {@code FOUNDER} o {@code PRODUCT_OWNER}, resuelto por Java desde la política.
 */
public record DesignReviewView(String missionId, String missionStatus, String blockReason, String decider,
                               List<DesignScreenReview> screens) {
}
