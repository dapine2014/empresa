package com.aicompany.core.model;

import com.aicompany.core.model.StackProfile.Layer;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Qué capas DDD trabaja cada rol de Engineering (spec 2026-09-26 §1,
 * revisión 2). Catálogo fijo en código: verificado en vivo que qwen3:8b no
 * convergía repartiendo capas él mismo. De cada lista se usan solo las capas
 * que tenga el perfil elegido, en todos los bounded contexts. El líder además
 * recibe los archivos de entrada que no genera Forjai ni cubre otra capa.
 */
public final class RoleLayerCatalog {

    private RoleLayerCatalog() {
    }

    private static final Map<String, List<Layer>> LAYERS_BY_ROLE = Map.of(
            // Revisión 3 (decisión del fundador tras MISSION-SANDBOX-VERIFY-1..8): el líder escribía el dominio
            // en vez de sus archivos de entrada, y Diego (Infrastructure + Tests) entregaba placeholders.
            "CLOUD_ARCHITECT_LEAD_BACKEND", List.of(Layer.DOMAIN),
            "DEV_BACKEND_INTEGRATIONS", List.of(Layer.APPLICATION, Layer.INFRASTRUCTURE),
            "FRONTEND_GAME_UI_SPECIALIST", List.of(Layer.GAME, Layer.PRESENTATION, Layer.API),
            "CLOUD_DB_SRE_DEVOPS", List.of(Layer.TESTS)
    );

    public static Optional<List<Layer>> layersFor(String roleCode) {
        return Optional.ofNullable(roleCode == null ? null : LAYERS_BY_ROLE.get(roleCode));
    }

    public static String describe() {
        return "Neo/CLOUD_ARCHITECT_LEAD_BACKEND (líder) → DOMAIN (y los archivos de entrada que no genera Forjai); "
                + "Iris/DEV_BACKEND_INTEGRATIONS → APPLICATION e INFRASTRUCTURE; Mila/FRONTEND_GAME_UI_SPECIALIST → "
                + "GAME, PRESENTATION o API; Diego/CLOUD_DB_SRE_DEVOPS → TESTS (va último y ve todo el código); "
                + "Vera (QA) → validación";
    }
}
