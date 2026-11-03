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

    DOTNET_APP("localhost/forjai-sandbox/dotnet-app:2", true),
    GODOT_DOTNET_GAME("localhost/forjai-sandbox/godot-dotnet-game:3", true),
    // Verificado en vivo: el SDK de Flutter crea bin/cache/lockfile en cada comando (falla con raíz de solo lectura).
    // :3 (diseño visual, spec 2026-11-01): fuentes del catálogo en /forjai/fonts y pasos capture-mockups/capture-app.
    FLUTTER_WEB_APP("localhost/forjai-sandbox/flutter-web-app:4", false);

    public record Step(String name, int timeoutSeconds) {
    }

    /**
     * Entrega del producto: empaqueta los targets pedidos (FORJAI_TARGETS) en /work/.forjai/package. 1800 s: hasta 5
     * targets de 300 s más el build Release; run.sh corta los que no entran antes de ~1700 s (revisión final, 5).
     */
    public static final Step PACKAGE_STEP = new Step("package", 1800);

    /**
     * Diseño visual (spec 2026-11-01 §4 y §9): qué renderiza el job DESIGN_CAPTURE. MOCKUPS abre las maquetas
     * aprobadas ({@code .forjai/mockups/<PANT-NN>.html}); APP compila la web y abre cada {@code ?preview=<PANT-NN>}.
     * 600 s: el build web de siempre más las capturas en teléfono y escritorio.
     */
    public enum CaptureMode {
        MOCKUPS(new Step("capture-mockups", 600)),
        APP(new Step("capture-app", 600));

        private final Step step;

        CaptureMode(Step step) {
            this.step = step;
        }

        public Step step() {
            return step;
        }

        public static Optional<CaptureMode> parse(String id) {
            return id == null ? Optional.empty() : Arrays.stream(values()).filter(m -> m.name().equals(id)).findFirst();
        }
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
