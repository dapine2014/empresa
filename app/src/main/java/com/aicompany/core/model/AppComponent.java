package com.aicompany.core.model;

import com.aicompany.core.agent.validation.OwnedPaths;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Spec 2026-10-02 B1 §1: una parte de la aplicación (perfil + carpeta). Las piezas existentes siguen trabajando con
 * rutas relativas a su perfil: Java quita {@code dir/} antes de llamarlas y lo vuelve a poner en los resultados.
 * Con un solo componente {@code dir} es "" y todo queda como antes. La carpeta la fija Java, nunca el modelo.
 */
public record AppComponent(StackProfile profile, String dir) {

    /** Orden fijo: el backend se genera antes que la app que lo consume. */
    private static final List<StackProfile> ORDER =
            List.of(StackProfile.DOTNET_APP, StackProfile.GODOT_DOTNET_GAME, StackProfile.FLUTTER_WEB_APP);

    private static final Map<StackProfile, String> DIRS = Map.of(
            StackProfile.DOTNET_APP, "backend",
            StackProfile.FLUTTER_WEB_APP, "app",
            StackProfile.GODOT_DOTNET_GAME, "game-client");

    /** Con ≥2 componentes, lo que vive en la raíz del repositorio (compartido por todos). */
    public static final List<String> COMMON_ROOT_PATHS =
            List.of("db/postgres/migrations", ".env.example", "README.md", ".gitignore", "docs");

    public static String dirOf(StackProfile profile) {
        return DIRS.get(profile);
    }

    public static List<AppComponent> of(List<StackProfile> profiles) {
        var distinct = new LinkedHashSet<>(profiles);
        var ordered = ORDER.stream().filter(distinct::contains).toList();
        if (ordered.size() <= 1) {
            return ordered.stream().map(p -> new AppComponent(p, "")).toList();
        }
        return ordered.stream().map(p -> new AppComponent(p, DIRS.get(p))).toList();
    }

    public boolean isRoot() {
        return dir.isEmpty();
    }

    public String prefix(String path) {
        return isRoot() ? path : dir + "/" + path;
    }

    /** La ruta relativa al componente, o vacío si la ruta no es de este componente. */
    public Optional<String> strip(String path) {
        var normalized = OwnedPaths.normalize(path);
        if (isRoot()) {
            return Optional.of(normalized);
        }
        return normalized.startsWith(dir + "/") ? Optional.of(normalized.substring(dir.length() + 1)) : Optional.empty();
    }

    public boolean owns(String path) {
        var normalized = OwnedPaths.normalize(path);
        return isRoot() || normalized.equals(dir) || normalized.startsWith(dir + "/");
    }

    /** Los nombres del plan en la convención de este perfil (PascalCase en C#, snake_case en Dart). */
    public List<String> contexts(List<String> names) {
        return names.stream().map(this::contextName).toList();
    }

    private String contextName(String name) {
        if (name == null || profile.isValidContextName(name)) {
            return name;
        }
        if (profile.ecosystem() == StackProfile.Ecosystem.PUB) {
            return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
        }
        return Arrays.stream(name.split("_")).filter(s -> !s.isEmpty())
                .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1))
                .collect(Collectors.joining());
    }
}
