package com.forjai.sandbox;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Parte de EJECUCIÓN de cada perfil (spec 2026-09-26 §2): imagen y pasos fijos.
 * La parte estructural vive en company-core (StackProfile); lo único compartido es el id.
 * Los comandos de cada paso están dentro de la imagen (/forjai/run.sh <paso>).
 */
public enum ExecutionProfile {

    DOTNET_APP("localhost/forjai-sandbox/dotnet-app:1"),
    GODOT_DOTNET_GAME("localhost/forjai-sandbox/godot-dotnet-game:1"),
    FLUTTER_WEB_APP("localhost/forjai-sandbox/flutter-web-app:1");

    public record Step(String name, int timeoutSeconds) {
    }

    private static final List<Step> STEPS = List.of(
            new Step("restore", 300), new Step("build", 600), new Step("test", 600), new Step("smoke", 180));

    private final String image;

    ExecutionProfile(String image) {
        this.image = image;
    }

    public String image() {
        return image;
    }

    public List<Step> steps() {
        return STEPS;
    }

    public static Optional<ExecutionProfile> parse(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(p -> p.name().equals(id)).findFirst();
    }
}
