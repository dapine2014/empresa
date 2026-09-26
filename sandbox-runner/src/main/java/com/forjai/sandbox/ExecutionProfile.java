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

    DOTNET_APP("localhost/forjai-sandbox/dotnet-app:1", true),
    GODOT_DOTNET_GAME("localhost/forjai-sandbox/godot-dotnet-game:1", true),
    // Verificado en vivo: el SDK de Flutter crea bin/cache/lockfile en cada comando (falla con raíz de solo lectura).
    FLUTTER_WEB_APP("localhost/forjai-sandbox/flutter-web-app:1", false);

    public record Step(String name, int timeoutSeconds) {
    }

    private static final List<Step> STEPS = List.of(
            new Step("restore", 300), new Step("build", 600), new Step("test", 600), new Step("smoke", 180));

    private final String image;
    private final boolean readOnlyRoot;

    ExecutionProfile(String image, boolean readOnlyRoot) {
        this.image = image;
        this.readOnlyRoot = readOnlyRoot;
    }

    /** Raíz del contenedor de solo lectura. Si es false, igual se descarta al terminar (--rm). */
    public boolean readOnlyRoot() {
        return readOnlyRoot;
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
