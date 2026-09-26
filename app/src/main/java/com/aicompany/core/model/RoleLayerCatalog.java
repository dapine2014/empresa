package com.aicompany.core.model;

import com.aicompany.core.model.StackProfile.Layer;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Qué capas DDD trabaja cada rol de Engineering (spec 2026-09-26 §1,
 * revisión 2). Catálogo fijo en código: verificado en vivo que qwen3:8b no
 * convergía repartiendo capas él mismo. De cada lista se usan solo las capas
 * que tenga el perfil elegido, en todos los bounded contexts. El líder no
 * trabaja capas: recibe los archivos de entrada del perfil.
 */
public final class RoleLayerCatalog {

    private RoleLayerCatalog() {
    }

    private static final Map<String, List<Layer>> LAYERS_BY_ROLE = Map.of(
            "DEV_BACKEND_INTEGRATIONS", List.of(Layer.DOMAIN, Layer.APPLICATION),
            "FRONTEND_GAME_UI_SPECIALIST", List.of(Layer.GAME, Layer.PRESENTATION, Layer.API),
            "CLOUD_DB_SRE_DEVOPS", List.of(Layer.INFRASTRUCTURE, Layer.TESTS),
            "CLOUD_ARCHITECT_LEAD_BACKEND", List.of()
    );

    public static Optional<List<Layer>> layersFor(String roleCode) {
        return Optional.ofNullable(roleCode == null ? null : LAYERS_BY_ROLE.get(roleCode));
    }

    public static String describe() {
        return "Iris/DEV_BACKEND_INTEGRATIONS → DOMAIN y APPLICATION; Mila/FRONTEND_GAME_UI_SPECIALIST → GAME, "
                + "PRESENTATION o API; Diego/CLOUD_DB_SRE_DEVOPS → INFRASTRUCTURE y TESTS; Neo (líder) → archivos "
                + "de entrada del proyecto; Vera (QA) → validación";
    }
}
