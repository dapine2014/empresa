package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Spec 2026-10-14 Kael §2.2: project.godot declara la escena principal y esa escena existe. */
public final class GameMainSceneGate {

    private static final Pattern MAIN = Pattern.compile("(?m)^run/main_scene=\"res://([^\"]+)\"");
    private static final String GODOT = "game/project.godot";

    private GameMainSceneGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> returned, Map<String, String> existing) {
        var godot = returned.stream().filter(Objects::nonNull)
                .filter(f -> f.path() != null && (GODOT.equals(f.path()) || f.path().endsWith("/" + GODOT)))
                .findFirst();
        if (godot.isEmpty()) {
            return List.of();
        }
        var godotPath = godot.get().path();
        var m = MAIN.matcher(godot.get().content() == null ? "" : godot.get().content());
        if (!m.find()) {
            return List.of(godotPath + " debe declarar run/main_scene=\"res://<Escena>.tscn\" en [application].");
        }
        // res:// es la carpeta de project.godot (con o sin prefijo de componente).
        var scene = godotPath.substring(0, godotPath.length() - "project.godot".length()) + m.group(1);
        var exists = existing.containsKey(scene) || returned.stream().filter(Objects::nonNull)
                .anyMatch(f -> scene.equals(f.path()));
        return exists ? List.of() : List.of("run/main_scene apunta a " + scene + ", que no existe: entrégala.");
    }
}
