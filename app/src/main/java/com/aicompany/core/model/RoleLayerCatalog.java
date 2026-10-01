package com.aicompany.core.model;

import com.aicompany.core.model.StackProfile.Layer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Qué capas escribe cada rol del Development Group (spec 2026-10-01 §1, revisión 4). Catálogo fijo en código:
 * verificado en vivo que un modelo repartiendo capas no convergía (revisiones 1-3). Neo elige QUIÉN participa;
 * Java decide QUÉ capas le tocan: primero las primarias, y las de respaldo solo si ningún elegido las tiene como
 * primarias (p. ej. una app Flutter sin backend: Mila escribe también domain y application).
 */
public final class RoleLayerCatalog {

    public record RoleLayers(List<Layer> primary, List<Layer> fallback, int phase, boolean writesCode) {
    }

    private static final Map<String, RoleLayers> BY_ROLE = Map.of(
            "PRODUCT_OWNER", new RoleLayers(List.of(), List.of(), 1, false),
            "TECH_LEAD", new RoleLayers(List.of(), List.of(), 1, false),
            "BACKEND", new RoleLayers(List.of(Layer.DOMAIN, Layer.APPLICATION, Layer.API),
                    List.of(Layer.INFRASTRUCTURE), 1, true),
            "DATA_ARCHITECT", new RoleLayers(List.of(Layer.INFRASTRUCTURE), List.of(), 1, true),
            "UI_UX", new RoleLayers(List.of(Layer.PRESENTATION),
                    List.of(Layer.DOMAIN, Layer.APPLICATION, Layer.INFRASTRUCTURE), 1, true),
            "QA", new RoleLayers(List.of(Layer.TESTS), List.of(), 1, true),
            "GAME_DEV", new RoleLayers(List.of(Layer.GAME), List.of(), 3, true),
            "SPECIALIST_3D", new RoleLayers(List.of(), List.of(), 3, true),
            "CREATIVE", new RoleLayers(List.of(), List.of(), 3, true),
            "DEVOPS", new RoleLayers(List.of(), List.of(), 2, true)
    );

    private RoleLayerCatalog() {
    }

    public static Optional<RoleLayers> of(String roleCode) {
        return Optional.ofNullable(roleCode == null ? null : BY_ROLE.get(roleCode));
    }

    public static boolean enabledNow(String roleCode) {
        return of(roleCode).map(r -> r.phase() <= DevelopmentPhase.CURRENT).orElse(false);
    }

    /**
     * Reparte las capas del perfil entre los agentes elegidos, en el orden de las capas del perfil. Cada capa
     * tiene a lo sumo un dueño: el primer agente (en el orden recibido) que la tenga como primaria; si ninguno,
     * el primero que la tenga como respaldo. Agentes sin rol conocido, que no escriben código o no habilitados
     * quedan con lista vacía.
     */
    public static LinkedHashMap<String, List<Layer>> assign(
            LinkedHashMap<String, String> roleCodeByAgent, List<Layer> profileLayers) {

        var result = new LinkedHashMap<String, List<Layer>>();
        roleCodeByAgent.keySet().forEach(agent -> result.put(agent, new ArrayList<>()));

        for (var layer : profileLayers) {
            var owner = firstWith(roleCodeByAgent, layer, true);
            if (owner == null) {
                owner = firstWith(roleCodeByAgent, layer, false);
            }
            if (owner != null) {
                result.get(owner).add(layer);
            }
        }
        return result;
    }

    /**
     * Revisión final del bloque 1 (I-2): el dueño de una capa no puede depender del orden en que Neo listó las
     * tareas. Se elige por esta prioridad de rol fija (el especialista más cercano a la capa primero) y, solo
     * entre agentes del mismo rol, por orden del plan.
     */
    private static final List<String> ROLE_PRIORITY =
            List.of("BACKEND", "DATA_ARCHITECT", "UI_UX", "GAME_DEV", "QA", "SPECIALIST_3D", "CREATIVE", "DEVOPS");

    private static String firstWith(LinkedHashMap<String, String> roleCodeByAgent, Layer layer, boolean primary) {
        for (var roleCode : ROLE_PRIORITY) {
            for (var entry : roleCodeByAgent.entrySet()) {
                if (!roleCode.equals(entry.getValue())) {
                    continue;
                }
                var role = of(roleCode).orElseThrow();
                if (!role.writesCode() || role.phase() > DevelopmentPhase.CURRENT) {
                    continue;
                }
                var layers = primary ? role.primary() : role.fallback();
                if (layers.contains(layer)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    public static String describe() {
        return "Aria/PRODUCT_OWNER y Neo/TECH_LEAD no escriben código (no les asignes tareas); "
                + "Iris/BACKEND → DOMAIN, APPLICATION y API (e INFRASTRUCTURE si no participa Diego); "
                + "Diego/DATA_ARCHITECT → INFRASTRUCTURE; "
                + "Mila/UI_UX → PRESENTATION y los archivos de entrada (y DOMAIN/APPLICATION/INFRASTRUCTURE si no "
                + "participa Iris); Vera/QA → los tests de aceptación (después del código) y la revisión final; "
                + "Kael/GAME_DEV, Orion/SPECIALIST_3D, Luna/CREATIVE y Andrea/DEVOPS todavía no están habilitados "
                + "(fase " + DevelopmentPhase.CURRENT + ")";
    }
}
