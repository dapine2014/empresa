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
            "GAME_DEV", new RoleLayers(List.of(Layer.GAME), List.of(Layer.DOMAIN, Layer.APPLICATION), 1, true),
            "SPECIALIST_3D", new RoleLayers(List.of(), List.of(), 3, true),
            "CREATIVE", new RoleLayers(List.of(), List.of(), 3, true),
            "DEVOPS", new RoleLayers(List.of(), List.of(), 2, true)
    );

    private RoleLayerCatalog() {
    }

    public static Optional<RoleLayers> of(String roleCode) {
        return Optional.ofNullable(roleCode == null ? null : BY_ROLE.get(roleCode));
    }

    /**
     * Spec 2026-10-02 B1 §1: en qué ecosistemas escribe cada rol cuando la misión tiene varios componentes (con uno
     * solo se mantiene el reparto de siempre, incluido el respaldo de Mila en las capas internas).
     */
    private static final Map<String, List<StackProfile.Ecosystem>> ECOSYSTEMS = Map.of(
            "BACKEND", List.of(StackProfile.Ecosystem.NUGET),
            "DATA_ARCHITECT", List.of(StackProfile.Ecosystem.NUGET),
            "GAME_DEV", List.of(StackProfile.Ecosystem.NUGET),
            "UI_UX", List.of(StackProfile.Ecosystem.PUB),
            "QA", List.of(StackProfile.Ecosystem.NUGET, StackProfile.Ecosystem.PUB));

    public static boolean writesIn(String roleCode, StackProfile.Ecosystem ecosystem) {
        return ECOSYSTEMS.getOrDefault(roleCode, List.of()).contains(ecosystem);
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
     * Dos o más agentes elegidos con el mismo rol se reparten los contextos. Cada agente conserva los que Neo le
     * puso en sus assignments (un contexto tiene un solo dueño por rol: gana el primero del plan); los que Neo no
     * asignó se reparten en orden, por turnos, entre los agentes de ese rol. Los agentes con rol único no aparecen
     * en el resultado: tienen todos los contextos.
     */
    public static Map<String, List<String>> splitByContext(LinkedHashMap<String, String> roleCodeByAgent,
                                                           Map<String, List<String>> contextsByAgent,
                                                           List<String> contexts) {

        var result = new LinkedHashMap<String, List<String>>();
        var agentsByRole = new LinkedHashMap<String, List<String>>();
        roleCodeByAgent.forEach((agent, role) -> agentsByRole.computeIfAbsent(role, r -> new ArrayList<>()).add(agent));

        for (var agents : agentsByRole.values()) {
            if (agents.size() < 2) {
                continue;
            }
            agents.forEach(agent -> result.put(agent, new ArrayList<>()));
            var taken = new java.util.HashSet<String>();
            for (var agent : agents) {
                for (var wanted : contextsByAgent.getOrDefault(agent, List.of())) {
                    contexts.stream().filter(c -> c.equalsIgnoreCase(wanted)).findFirst()
                            .filter(taken::add).ifPresent(c -> result.get(agent).add(c));
                }
            }
            int turn = 0;
            for (var context : contexts) {
                if (taken.add(context)) {
                    result.get(agents.get(turn % agents.size())).add(context);
                    turn++;
                }
            }
            agents.forEach(agent -> result.get(agent).sort(
                    java.util.Comparator.comparingInt(contexts::indexOf)));
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
                + "Mila/UI_UX y Orion/UI_UX (fronts intercambiables) → PRESENTATION y los archivos de entrada (y DOMAIN/APPLICATION/INFRASTRUCTURE si no "
                + "participa Iris); Vera/QA → los tests de aceptación (después del código) y la revisión final; "
                + "Kael/GAME_DEV → GAME (todo game/, incluidos project.godot y el script de la escena principal; si Forjai "
                + "ya creó la .tscn de una escena, no la escribas; y DOMAIN/APPLICATION si no participa Iris), solo en GODOT_DOTNET_GAME; Vera/QA también escribe las partidas simuladas "
                + "en game/playthroughs; "
                + "Luna/CREATIVE y Andrea/DEVOPS todavía no están habilitados "
                + "(fase " + DevelopmentPhase.CURRENT + ")";
    }
}
