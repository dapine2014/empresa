package com.aicompany.core.agent.validation;

import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.StackProfile.Layer;
import com.aicompany.core.model.StackProfile.Location;
import com.aicompany.core.model.StaticCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Chequeo determinista de capas DDD (spec 2026-09-26 §1), escrito en Java y
 * sin herramientas externas por lenguaje. Ubica cada archivo en (contexto,
 * capa) por su ruta y cada dependencia por su namespace (C#) o import (Dart).
 * Límite conocido: en C# no ve nombres totalmente calificados sin `using`.
 */
public final class DddLayerChecker {

    public record Violation(String file, String dependency, String reason) {
    }

    private record Dependency(String raw, Optional<Location> target, boolean forbidden) {
    }

    private static final Pattern CS_USING = Pattern.compile(
            "^\\s*(?:global\\s+)?using\\s+(?:static\\s+)?(?:[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*)?([A-Za-z_][A-Za-z0-9_.]*)\\s*;",
            Pattern.MULTILINE);

    private static final Map<String, Layer> CS_LAYER_NAMES = Map.of(
            "Domain", Layer.DOMAIN, "Application", Layer.APPLICATION, "Infrastructure", Layer.INFRASTRUCTURE,
            "Api", Layer.API, "Tests", Layer.TESTS);

    private DddLayerChecker() {
    }

    public static List<Violation> check(StackProfile profile, List<String> contexts, Map<String, String> contentsByPath) {

        var violations = new ArrayList<Violation>();

        for (var entry : contentsByPath.entrySet()) {

            var path = entry.getKey();

            if (!path.endsWith(profile.sourceExtension()) || profile.isEntryFile(path)) {
                continue;
            }

            var source = profile.locate(path, contexts);
            if (source.isEmpty() || source.get().layer() == Layer.TESTS) {
                continue;
            }

            for (var dependency : dependencies(profile, contexts, path, entry.getValue(), contentsByPath)) {
                var reason = violation(source.get(), dependency);
                if (reason != null) {
                    violations.add(new Violation(path, dependency.raw(), reason));
                }
            }
        }

        return violations;
    }

    public static StaticCheck toCheck(List<Violation> violations, int analyzedFiles) {

        if (violations.isEmpty()) {
            return StaticCheck.pass("DDD_LAYERS", "Sin violaciones de capas DDD (" + analyzedFiles + " archivo(s) analizados)",
                    null, List.of());
        }

        var detail = violations.stream().limit(10)
                .map(v -> v.file() + " → " + v.dependency() + ": " + v.reason())
                .collect(Collectors.joining("; "));
        var files = violations.stream().map(Violation::file).distinct().toList();

        return StaticCheck.fail("DDD_LAYERS", violations.size() + " violación(es) de capas DDD: " + detail, null, files);
    }

    private static String violation(Location source, Dependency dependency) {

        if (dependency.forbidden()) {
            return source.layer() == Layer.DOMAIN ? "domain no puede depender de frameworks" : null;
        }

        if (dependency.target().isEmpty()) {
            return null;
        }

        var target = dependency.target().get();
        var sameContext = Objects.equals(source.context(), target.context());

        return switch (source.layer()) {
            case DOMAIN -> target.layer() == Layer.DOMAIN && sameContext
                    ? null : "domain solo puede depender de su propio domain";
            case APPLICATION -> (target.layer() == Layer.DOMAIN && sameContext) || target.layer() == Layer.APPLICATION
                    ? null : "application solo puede depender de su domain y de application de otros contextos";
            case GAME -> target.layer() == Layer.DOMAIN || target.layer() == Layer.APPLICATION || target.layer() == Layer.GAME
                    ? null : "game solo puede depender de domain y application";
            case INFRASTRUCTURE, API, PRESENTATION -> {
                if (sameContext) {
                    yield target.layer() == Layer.DOMAIN || target.layer() == Layer.APPLICATION || target.layer() == source.layer()
                            ? null : "una capa externa solo puede depender de domain/application de su contexto";
                }
                yield target.layer() == Layer.APPLICATION
                        ? null : "entre contextos solo se permite usar la capa application";
            }
            case TESTS -> null;
        };
    }

    private static List<Dependency> dependencies(
            StackProfile profile, List<String> contexts, String path, String content, Map<String, String> all) {
        return profile.ecosystem() == StackProfile.Ecosystem.NUGET
                ? csharpDependencies(profile, contexts, content)
                : DartImports.dependencies(profile, contexts, path, content, all);
    }

    private static List<Dependency> csharpDependencies(StackProfile profile, List<String> contexts, String content) {

        var result = new ArrayList<Dependency>();
        var matcher = CS_USING.matcher(content == null ? "" : content);

        while (matcher.find()) {
            var namespace = matcher.group(1);
            var forbidden = profile.forbiddenDomainDependencies().stream()
                    .anyMatch(f -> namespace.equals(f) || namespace.startsWith(f + "."));
            result.add(new Dependency(namespace, csharpTarget(namespace, contexts), forbidden));
        }

        return result;
    }

    private static Optional<Location> csharpTarget(String namespace, List<String> contexts) {
        for (var context : contexts) {
            for (var layer : CS_LAYER_NAMES.entrySet()) {
                var prefix = context + "." + layer.getKey();
                if (namespace.equals(prefix) || namespace.startsWith(prefix + ".")) {
                    return Optional.of(new Location(context, layer.getValue()));
                }
            }
        }
        return Optional.empty();
    }

    /** Dart: `import`/`export`; `package:<app>/...` → `lib/...`; relativos resueltos contra el archivo. */
    static final class DartImports {

        private static final Pattern DART_IMPORT = Pattern.compile(
                "^\\s*(?:import|export)\\s+['\"]([^'\"]+)['\"]", Pattern.MULTILINE);

        private static final Pattern PUBSPEC_NAME = Pattern.compile("^name:\\s*([a-z0-9_]+)\\s*$", Pattern.MULTILINE);

        static List<Dependency> dependencies(
                StackProfile profile, List<String> contexts, String path, String content, Map<String, String> all) {

            var appPackage = appPackage(all);
            var result = new ArrayList<Dependency>();
            var matcher = DART_IMPORT.matcher(content == null ? "" : content);

            while (matcher.find()) {
                var uri = matcher.group(1);
                var forbidden = profile.forbiddenDomainDependencies().stream().anyMatch(uri::startsWith);
                var targetPath = resolve(uri, path, appPackage);
                var target = targetPath == null ? Optional.<Location>empty() : profile.locate(targetPath, contexts);
                result.add(new Dependency(uri, target, forbidden));
            }

            return result;
        }

        private static String appPackage(Map<String, String> all) {
            var pubspec = all.get("pubspec.yaml");
            if (pubspec == null) {
                return null;
            }
            var matcher = PUBSPEC_NAME.matcher(pubspec);
            return matcher.find() ? matcher.group(1) : null;
        }

        private static String resolve(String uri, String fromPath, String appPackage) {

            if (appPackage != null && uri.startsWith("package:" + appPackage + "/")) {
                return "lib/" + uri.substring(("package:" + appPackage + "/").length());
            }

            if (uri.contains(":")) {
                return null;
            }

            var base = fromPath.contains("/") ? fromPath.substring(0, fromPath.lastIndexOf('/')) : "";
            var segments = new ArrayList<String>(base.isEmpty() ? List.of() : List.of(base.split("/")));

            for (var segment : uri.split("/")) {
                if (segment.equals("..")) {
                    if (!segments.isEmpty()) {
                        segments.remove(segments.size() - 1);
                    }
                } else if (!segment.equals(".") && !segment.isEmpty()) {
                    segments.add(segment);
                }
            }

            return OwnedPaths.normalize(String.join("/", segments));
        }
    }
}
