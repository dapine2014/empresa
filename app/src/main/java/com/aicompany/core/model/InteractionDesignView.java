package com.aicompany.core.model;

import com.aicompany.core.agent.model.InteractionDesign;

/** Spec 2026-10-14 Kael §3: diseño de interacción de una misión (versión vigente, aprobada y su texto). */
public record InteractionDesignView(String missionId, Integer version, String status, Integer specVersion,
                                    Integer approvedVersion, InteractionDesign design, String summary) {
}
