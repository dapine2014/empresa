package com.aicompany.core.delivery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Arma ENTREGA.md (Markdown) solo con hechos de {@link DeliveryFacts}; sin modelo de por medio. */
public final class DeliveryDocument {

    private static final String NA = "no disponible";

    /** Target id → ruta dentro del zip. */
    private static final Map<String, String> ZIP_PATHS = new LinkedHashMap<>();
    static {
        ZIP_PATHS.put("windows", "juego/windows/Juego.exe");
        ZIP_PATHS.put("macos", "juego/macos/Juego-macos.zip");
        ZIP_PATHS.put("linux", "juego/linux/Juego.x86_64");
        ZIP_PATHS.put("android-apk", "juego/android/Juego-debug.apk");
        ZIP_PATHS.put("android-aab", "juego/android/Juego.aab");
        ZIP_PATHS.put("web", "app/");
        ZIP_PATHS.put("app", "app/");
    }

    private DeliveryDocument() { }

    public static String render(DeliveryFacts f) {
        var sb = new StringBuilder();
        var passed = f.targets() == null ? List.<DeliveryFacts.TargetResult>of()
                : f.targets().stream().filter(t -> "PASS".equals(t.status())).toList();
        var isApp = passed.stream().anyMatch(t -> "web".equals(t.name()) || "app".equals(t.name()));
        var hasDb = !nul(f.migrations()).isEmpty() || !nul(f.databases()).isEmpty();

        var title = blank(f.productName()) ? f.missionId() : f.productName();
        sb.append("# ").append(title).append(" — entrega del incremento ").append(f.increment());
        if (f.totalIncrements() > 0) sb.append(" de ").append(f.totalIncrements());
        sb.append("\n\n");

        sb.append("## 1. Qué es\n\n")
                .append("- Cliente: ").append(or(f.clientSummary())).append('\n')
                .append("- Tipo: ").append(or(f.experience())).append('\n')
                .append("- Stack: ").append(or(f.stackProfile())).append('\n')
                .append("- Verificado: ").append(or(f.verifiedAt())).append("\n\n");

        sb.append("## 2. Dónde está cada cosa\n\n")
                .append("- Zip: ").append(or(f.zipPath())).append('\n')
                .append("- Repositorio: ").append(or(f.repoPath())).append('\n')
                .append("- Commit: ").append(or(f.commitSha())).append("\n\n")
                .append("```\n");
        var seen = new java.util.LinkedHashSet<String>();
        for (var t : passed) {
            var p = ZIP_PATHS.get(t.name());
            if (p != null && seen.add(p)) sb.append(p).append('\n');
        }
        if (isApp) sb.append("docker/\n");
        if (hasDb) sb.append("base-de-datos/\n");
        sb.append("codigo-fuente/\ncodigo-fuente.bundle\n```\n\n")
                .append("Historial completo: git clone codigo-fuente.bundle proyecto\n\n");

        sb.append("## 3. Cómo correrlo\n\n");
        var notBuilt = f.targets() == null ? List.<DeliveryFacts.TargetResult>of()
                : f.targets().stream().filter(t -> !"PASS".equals(t.status())).toList();
        if (passed.isEmpty() && notBuilt.isEmpty()) sb.append(NA).append('\n');
        for (var t : passed) {
            switch (t.name()) {
                case "windows" -> sb.append("- Windows: doble clic en juego/windows/Juego.exe.\n");
                case "macos" -> sb.append("- macOS: descomprime juego/macos/Juego-macos.zip y abre la app con clic "
                        + "derecho → Abrir.\n");
                case "linux" -> sb.append("- Linux: `chmod +x Juego.x86_64 && ./Juego.x86_64`.\n");
                case "android-apk" -> sb.append("- Android: activa la instalación desde orígenes desconocidos y corre "
                        + "`adb install Juego-debug.apk`.\n");
                case "android-aab" -> sb.append("- Google Play: sube juego/android/Juego.aab a Google Play Console.\n");
                case "web", "app" -> sb.append("- App: `cd docker && cp .env.example .env && docker compose up -d` y "
                        + "abre http://localhost:8080\n");
                default -> { }
            }
        }
        for (var t : notBuilt) {
            sb.append("- ").append(t.name()).append(": no se generó (").append(or(t.reason())).append(")\n");
        }
        sb.append('\n');

        sb.append("## 4. Cómo se usa\n\n");
        var controls = nul(f.controls());
        var levels = nul(f.levels());
        var screens = nul(f.screens());
        if (controls.isEmpty() && levels.isEmpty() && screens.isEmpty()) sb.append(NA).append('\n');
        if (!controls.isEmpty()) {
            sb.append("| Acción | Tecla | Efecto |\n|---|---|---|\n");
            controls.forEach(c -> sb.append("| ").append(or(c.action())).append(" | ").append(or(c.key()))
                    .append(" | ").append(or(c.effect())).append(" |\n"));
            sb.append('\n');
        }
        if (!levels.isEmpty()) {
            sb.append("Niveles:\n\n");
            levels.forEach(l -> sb.append("- ").append(or(l.name())).append(" (").append(or(l.item())).append("): ")
                    .append(or(l.parameters())).append("; avanza con ").append(or(l.advanceRule())).append('\n'));
        }
        if (!screens.isEmpty()) {
            sb.append("Pantallas:\n\n");
            screens.forEach(s -> sb.append("- ").append(s).append('\n'));
        }
        sb.append('\n');

        sb.append("## 5. Qué se construyó\n\n");
        var items = nul(f.contractItems());
        var stories = nul(f.stories());
        var scenarios = nul(f.scenarios());
        if (items.isEmpty() && stories.isEmpty() && scenarios.isEmpty()) sb.append(NA).append('\n');
        if (!items.isEmpty()) {
            sb.append("Contrato:\n\n");
            items.forEach(i -> sb.append("- ").append(i.id()).append(" (").append(or(i.kind())).append(") ")
                    .append(or(i.name())).append(": ").append(or(i.description())).append('\n'));
            sb.append('\n');
        }
        if (!stories.isEmpty()) {
            sb.append("Historias de usuario:\n\n");
            stories.forEach(s -> sb.append("- ").append(s.id()).append(" (incremento ").append(s.increment())
                    .append("): ").append(or(s.text())).append('\n'));
            sb.append('\n');
        }
        if (!scenarios.isEmpty()) {
            sb.append("Escenarios:\n\n");
            scenarios.forEach(s -> sb.append("- ").append(s.id()).append(" (incremento ").append(s.increment())
                    .append("): ").append(or(s.title())).append(" — ").append(or(s.status())).append('\n'));
        }
        sb.append('\n');

        long verified = scenarios.stream().filter(s -> "VERIFICADO".equals(s.status())).count();
        sb.append("## 6. Cómo se verificó\n\n")
                .append("- ").append(f.testsPassed()).append(" de ").append(f.testsTotal()).append(" tests\n")
                .append("- Escenarios verificados: ").append(verified).append(" de ").append(scenarios.size())
                .append('\n')
                .append(f.correctionRounds() < 0 ? "- Rondas de corrección: " + NA + "\n"
                        : "- " + f.correctionRounds() + " rondas de corrección\n")
                .append("- Commit: ").append(or(f.commitSha())).append("\n\n");

        sb.append("## 7. Base de datos\n\n");
        if (!hasDb) {
            sb.append("Sin base de datos.\n");
        } else {
            if (!nul(f.migrations()).isEmpty()) {
                sb.append("Migraciones (en este orden):\n\n");
                f.migrations().forEach(m -> sb.append("- ").append(m).append('\n'));
                sb.append('\n');
            }
            nul(f.databases()).forEach(d -> sb.append("- ").append(d.name()).append(" (").append(d.environment())
                    .append("): ").append(d.host()).append(':').append(d.port()).append('/').append(d.database())
                    .append(", usuario ").append(d.username()).append('\n'));
            if (!nul(f.databases()).isEmpty()) sb.append("\nLa contraseña está en Forjai → Settings → Bases de datos.\n");
            sb.append("\nCómo aplicar las migraciones y apuntar a una base real: base-de-datos/LEEME.md.\n");
        }
        sb.append('\n');

        sb.append("## 8. Limitaciones\n\n");
        var limits = nul(f.limitations());
        if (limits.isEmpty()) sb.append("Ninguna.\n");
        else limits.forEach(l -> sb.append("- ").append(l).append('\n'));
        sb.append('\n');

        sb.append("## 9. Costo y equipo\n\n")
                .append("- Costo: ").append(String.format(Locale.ROOT, "US$ %.2f", f.costUsd())).append('\n')
                .append("- Equipo: ").append(nul(f.agents()).isEmpty() ? NA : String.join(", ", f.agents()))
                .append('\n');
        return sb.toString();
    }

    private static <T> List<T> nul(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String or(String s) {
        return blank(s) ? NA : s;
    }
}
