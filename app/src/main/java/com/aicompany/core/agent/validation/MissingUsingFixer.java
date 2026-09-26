package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.validation.CompilerErrorParser.CompilerError;
import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.StackProfile.Layer;
import com.aicompany.core.model.StackProfile.Location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Corrección determinista de "using" faltantes (verificado en vivo con MISSION-SANDBOX-VERIFY-12: cada capa la
 * escribe un agente distinto y el error más común es CS0246 por un tipo que sí existe en otra capa). Solo agrega
 * {@code using N;} si el tipo está declarado en un único namespace del repositorio y la capa que lo declara es una
 * dependencia permitida por las reglas DDD. Lo demás queda para el agente.
 */
public final class MissingUsingFixer {

    private static final Pattern TYPE_NOT_FOUND = Pattern.compile("The type or namespace name '([A-Za-z_][A-Za-z0-9_]*)'");
    private static final Pattern DECLARATION = Pattern.compile(
            "\\b(?:class|interface|record|enum|struct)\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern NAMESPACE = Pattern.compile("(?m)^\\s*namespace\\s+([A-Za-z_][A-Za-z0-9_.]*)");
    private static final Pattern USING_LINE = Pattern.compile("(?m)^using\\s+[A-Za-z_][A-Za-z0-9_.]*\\s*;[ \\t]*\\R");

    private record Declaration(String namespace, String path) {
    }

    private MissingUsingFixer() {
    }

    /** Archivos corregidos (ruta → contenido nuevo); vacío si no hay nada seguro que corregir. */
    public static Map<String, String> fix(StackProfile profile, List<String> contexts, Map<String, String> files,
                                          List<CompilerError> errors) {

        if (profile.ecosystem() != StackProfile.Ecosystem.NUGET) {
            return Map.of();
        }

        var declarations = new HashMap<String, List<Declaration>>();
        for (var entry : files.entrySet()) {
            if (!entry.getKey().endsWith(".cs") || entry.getValue() == null) {
                continue;
            }
            var namespace = NAMESPACE.matcher(entry.getValue());
            if (!namespace.find()) {
                continue;
            }
            var types = DECLARATION.matcher(entry.getValue());
            while (types.find()) {
                declarations.computeIfAbsent(types.group(1), k -> new ArrayList<>())
                        .add(new Declaration(namespace.group(1), entry.getKey()));
            }
        }

        var usingsByFile = new LinkedHashMap<String, Set<String>>();

        for (var error : errors) {
            if (!"CS0246".equals(error.code())) {
                continue;
            }
            var type = TYPE_NOT_FOUND.matcher(error.message());
            var content = files.get(error.path());
            var source = profile.locate(error.path(), contexts);
            if (!type.find() || content == null || source.isEmpty()) {
                continue;
            }
            var candidates = declarations.getOrDefault(type.group(1), List.of());
            var namespaces = candidates.stream().map(Declaration::namespace).distinct().toList();
            if (namespaces.size() != 1) {
                continue;
            }
            var allowed = candidates.stream().allMatch(d -> profile.locate(d.path(), contexts)
                    .map(target -> allowed(source.get(), target, error.path()))
                    .orElse(false));
            if (allowed && !hasUsing(content, namespaces.get(0))) {
                usingsByFile.computeIfAbsent(error.path(), k -> new LinkedHashSet<>()).add(namespaces.get(0));
            }
        }

        var fixes = new LinkedHashMap<String, String>();
        usingsByFile.forEach((path, namespaces) -> fixes.put(path, addUsings(files.get(path), namespaces)));
        return fixes;
    }

    private static boolean allowed(Location source, Location target, String path) {
        if (source.layer() == Layer.TESTS || Objects.equals(source, target)) {
            return true;
        }
        if (source.layer() == Layer.API && path.endsWith("/Program.cs") && target.layer() == Layer.INFRASTRUCTURE
                && Objects.equals(source.context(), target.context())) {
            return true;
        }
        return DddLayerChecker.layerRule(source, target) == null;
    }

    private static boolean hasUsing(String content, String namespace) {
        return Pattern.compile("(?m)^\\s*using\\s+" + Pattern.quote(namespace) + "\\s*;").matcher(content).find();
    }

    /** Inserta los using después del último using del encabezado (o al principio). */
    private static String addUsings(String content, Set<String> namespaces) {
        var lines = new StringBuilder();
        namespaces.forEach(n -> lines.append("using ").append(n).append(";\n"));
        var matcher = USING_LINE.matcher(content);
        var insertAt = 0;
        while (matcher.find()) {
            insertAt = matcher.end();
        }
        return content.substring(0, insertAt) + lines + content.substring(insertAt);
    }
}
