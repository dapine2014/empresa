package com.aicompany.core.model;

import java.util.List;
import java.util.Map;

/**
 * Spec 2026-11-01 diseño visual §14-15: la revisión de diseño de una misión. {@code blockReason}: la primera línea del
 * motivo si la misión está frenada por diseño ({@code DESIGN_MISMATCH: …} o {@code DESIGN_CAPTURE_FAILED: …}), si no
 * null. {@code decider}: {@code FOUNDER} o {@code PRODUCT_OWNER}, resuelto por Java desde la política.
 * {@code appImageUrls}: revisión R15, los PNG de la última captura de la app ({@code "PANT-NN-<phone|desktop>"} → URL
 * {@code /design-images/app/<nombre>}); los de las maquetas están en {@link VisualDesignView#imageUrls}.
 */
public record DesignReviewView(String missionId, String missionStatus, String blockReason, String decider,
                               List<DesignScreenReview> screens, Map<String, String> appImageUrls) {
}
