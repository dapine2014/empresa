package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.InteractionDesign;

import java.util.List;
import java.util.Objects;

/** Ajuste de Kael (2026-10-17): el project.godot entregado usa en cada acción la tecla del diseño (GodotKeys). */
public final class GameInputMapGate {

    private static final String GODOT = "game/project.godot";

    private GameInputMapGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> returned,
                                     List<InteractionDesign.Control> controls) {
        if (controls == null || controls.isEmpty()) {
            return List.of();
        }
        var godot = returned.stream().filter(Objects::nonNull)
                .filter(f -> f.path() != null && (GODOT.equals(f.path()) || f.path().endsWith("/" + GODOT)))
                .findFirst();
        if (godot.isEmpty()) {
            return List.of();
        }
        var problems = GodotKeys.mismatches(godot.get().content(), controls);
        if (problems.isEmpty()) {
            return List.of();
        }
        return List.of(godot.get().path() + ": " + String.join("; ", problems)
                + ". Copia el bloque [input] del diseño tal cual:\n" + GodotKeys.inputBlock(controls).strip());
    }
}
