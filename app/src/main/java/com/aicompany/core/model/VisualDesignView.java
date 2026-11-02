package com.aicompany.core.model;

import com.aicompany.core.agent.model.VisualSystem;

import java.util.List;
import java.util.Map;

/**
 * Spec 2026-11-01 front con diseño visual: el sistema visual vigente de una misión, sus pantallas con maqueta y las URL
 * de las imágenes capturadas ({@code "PANT-NN-<phone|desktop>"} → URL). Una pantalla × tamaño sin URL = «sin imagen».
 */
public record VisualDesignView(String missionId, Integer version, String status, Integer interactionDesignVersion,
                               Integer approvedVersion, VisualSystem system, List<String> screens,
                               Map<String, String> imageUrls) {
}
