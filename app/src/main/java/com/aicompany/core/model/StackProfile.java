package com.aicompany.core.model;

import com.aicompany.core.agent.validation.OwnedPaths;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Catálogo fijo de perfiles de stack (spec 2026-09-26 §1): parte ESTRUCTURAL
 * de cada perfil — capas DDD por bounded context, archivos de entrada,
 * frameworks prohibidos en domain. La parte de ejecución (imagen, comandos)
 * vive en sandbox-runner. Agregar un perfil es un cambio de código.
 */
public enum StackProfile {

    DOTNET_APP(
            "APIs y servicios .NET (ASP.NET)",
            Ecosystem.NUGET, ".cs", "[A-Z][A-Za-z0-9]*", "PascalCase, p. ej. Pedidos",
            List.of(
                    new LayerRoot(Layer.DOMAIN, "src/{ctx}.Domain"),
                    new LayerRoot(Layer.APPLICATION, "src/{ctx}.Application"),
                    new LayerRoot(Layer.INFRASTRUCTURE, "src/{ctx}.Infrastructure"),
                    new LayerRoot(Layer.API, "src/{ctx}.Api"),
                    new LayerRoot(Layer.TESTS, "tests/{ctx}.Tests")),
            List.of(),
            List.of(new EntryFile("[A-Za-z0-9]+\\.sln", "<Producto>.sln")),
            List.of("README.md", ".gitignore", "docs", "Directory.Build.props"),
            List.of("Microsoft.AspNetCore", "Godot")),

    GODOT_DOTNET_GAME(
            "Juegos Godot con C#",
            Ecosystem.NUGET, ".cs", "[A-Z][A-Za-z0-9]*", "PascalCase, p. ej. Combate",
            List.of(
                    new LayerRoot(Layer.DOMAIN, "src/{ctx}.Domain"),
                    new LayerRoot(Layer.APPLICATION, "src/{ctx}.Application"),
                    new LayerRoot(Layer.TESTS, "tests/{ctx}.Tests")),
            List.of(new LayerRoot(Layer.GAME, "game")),
            List.of(new EntryFile("game/project\\.godot", "game/project.godot"),
                    new EntryFile("[A-Za-z0-9]+\\.sln", "<Producto>.sln")),
            List.of("README.md", ".gitignore", "docs", "Directory.Build.props"),
            List.of("Godot", "Microsoft.AspNetCore")),

    FLUTTER_WEB_APP(
            "Apps Flutter (web en esta ola)",
            Ecosystem.PUB, ".dart", "[a-z][a-z0-9_]*", "snake_case, p. ej. pedidos",
            List.of(
                    new LayerRoot(Layer.DOMAIN, "lib/{ctx}/domain"),
                    new LayerRoot(Layer.APPLICATION, "lib/{ctx}/application"),
                    new LayerRoot(Layer.INFRASTRUCTURE, "lib/{ctx}/infrastructure"),
                    new LayerRoot(Layer.PRESENTATION, "lib/{ctx}/presentation"),
                    new LayerRoot(Layer.TESTS, "test/{ctx}")),
            List.of(),
            List.of(new EntryFile("pubspec\\.yaml", "pubspec.yaml"),
                    new EntryFile("lib/main\\.dart", "lib/main.dart")),
            List.of("README.md", ".gitignore", "docs", "analysis_options.yaml", "web"),
            List.of("package:flutter/", "dart:ui", "dart:html"));

    public enum Ecosystem { NUGET, PUB }

    public enum Layer {
        DOMAIN, APPLICATION, INFRASTRUCTURE, API, PRESENTATION, GAME, TESTS;

        public boolean isOuter() {
            return this == INFRASTRUCTURE || this == API || this == PRESENTATION || this == GAME;
        }
    }

    /** {@code context == null} para capas compartidas sin contexto (p. ej. {@code game/}). */
    public record Location(String context, Layer layer) {
    }

    private record LayerRoot(Layer layer, String template) {
        String resolve(String context) {
            return template.replace("{ctx}", context);
        }
    }

    private record EntryFile(String regex, String display) {
        boolean matches(String path) {
            return Pattern.matches(regex, path);
        }
    }

    private final String usage;
    private final Ecosystem ecosystem;
    private final String sourceExtension;
    private final Pattern contextName;
    private final String contextNameRule;
    private final List<LayerRoot> contextRoots;
    private final List<LayerRoot> sharedRoots;
    private final List<EntryFile> entryFiles;
    private final List<String> extras;
    private final List<String> forbiddenDomainDependencies;

    StackProfile(String usage, Ecosystem ecosystem, String sourceExtension, String contextName,
                 String contextNameRule, List<LayerRoot> contextRoots, List<LayerRoot> sharedRoots,
                 List<EntryFile> entryFiles, List<String> extras, List<String> forbiddenDomainDependencies) {
        this.usage = usage;
        this.ecosystem = ecosystem;
        this.sourceExtension = sourceExtension;
        this.contextName = Pattern.compile(contextName);
        this.contextNameRule = contextNameRule;
        this.contextRoots = contextRoots;
        this.sharedRoots = sharedRoots;
        this.entryFiles = entryFiles;
        this.extras = extras;
        this.forbiddenDomainDependencies = forbiddenDomainDependencies;
    }

    public static Optional<StackProfile> parse(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(p -> p.name().equals(id.strip())).findFirst();
    }

    public String usage() {
        return usage;
    }

    public Ecosystem ecosystem() {
        return ecosystem;
    }

    public String sourceExtension() {
        return sourceExtension;
    }

    public List<String> forbiddenDomainDependencies() {
        return forbiddenDomainDependencies;
    }

    public boolean isValidContextName(String name) {
        return name != null && contextName.matcher(name).matches();
    }

    public String contextNameRule() {
        return contextNameRule;
    }

    public List<String> allowedRoots(List<String> contexts) {
        var roots = new ArrayList<String>();
        for (var context : contexts) {
            contextRoots.forEach(root -> roots.add(root.resolve(context)));
        }
        sharedRoots.forEach(root -> roots.add(root.template()));
        roots.addAll(extras);
        return roots;
    }

    public boolean isEntryFile(String path) {
        var normalized = OwnedPaths.normalize(path);
        return entryFiles.stream().anyMatch(e -> e.matches(normalized));
    }

    public List<String> missingEntryFiles(Collection<String> files) {
        var normalized = files.stream().map(OwnedPaths::normalize).toList();
        return entryFiles.stream()
                .filter(e -> normalized.stream().noneMatch(e::matches))
                .map(EntryFile::display)
                .toList();
    }

    public boolean isWithinStructure(String path, List<String> contexts) {
        var normalized = OwnedPaths.normalize(path);
        return isEntryFile(normalized)
                || allowedRoots(contexts).stream().anyMatch(root -> OwnedPaths.covers(root, normalized));
    }

    public Optional<Location> locate(String path, List<String> contexts) {
        var normalized = OwnedPaths.normalize(path);
        for (var context : contexts) {
            for (var root : contextRoots) {
                if (OwnedPaths.covers(root.resolve(context), normalized)) {
                    return Optional.of(new Location(context, root.layer()));
                }
            }
        }
        for (var root : sharedRoots) {
            if (OwnedPaths.covers(root.template(), normalized)) {
                return Optional.of(new Location(null, root.layer()));
            }
        }
        return Optional.empty();
    }

    /** Capas que ofrece el perfil (por contexto y compartidas). */
    public List<Layer> layers() {
        var layers = new ArrayList<Layer>();
        contextRoots.forEach(r -> layers.add(r.layer()));
        sharedRoots.forEach(r -> layers.add(r.layer()));
        return layers;
    }

    public boolean isSharedLayer(Layer layer) {
        return sharedRoots.stream().anyMatch(r -> r.layer() == layer);
    }

    /** Raíz de la capa para el contexto (ignorado en capas compartidas), o vacío si el perfil no la tiene. */
    public Optional<String> resolveRoot(String context, Layer layer) {
        for (var root : sharedRoots) {
            if (root.layer() == layer) {
                return Optional.of(root.template());
            }
        }
        for (var root : contextRoots) {
            if (root.layer() == layer && context != null) {
                return Optional.of(root.resolve(context));
            }
        }
        return Optional.empty();
    }

    /** Archivos de entrada y extras de arranque que recibe el líder (revisión 2026-09-26, opción B). */
    public List<String> leaderOwnedPaths() {
        return switch (this) {
            case DOTNET_APP -> List.of("Solution.sln");
            case GODOT_DOTNET_GAME -> List.of("Solution.sln", "game/project.godot");
            case FLUTTER_WEB_APP -> List.of("pubspec.yaml", "lib/main.dart", "web");
        };
    }

    public String describe() {
        var placeholder = ecosystem == Ecosystem.PUB ? "<ctx>" : "<Ctx>";
        var layers = contextRoots.stream()
                .map(r -> r.layer().name().toLowerCase() + "=" + r.template().replace("{ctx}", placeholder))
                .collect(Collectors.joining(", "));
        var shared = sharedRoots.isEmpty() ? "" : " | compartido: " + sharedRoots.stream()
                .map(r -> r.layer().name().toLowerCase() + "=" + r.template())
                .collect(Collectors.joining(", "));
        var entries = entryFiles.stream().map(EntryFile::display).collect(Collectors.joining(", "));
        return "- " + name() + " (" + usage + "): por contexto " + layers + shared
                + " | archivos de entrada: " + entries
                + " | nombres de contexto: " + contextNameRule
                + " | prohibido en domain: " + forbiddenDomainDependencies;
    }

    public static String describeAll() {
        return Arrays.stream(values()).map(StackProfile::describe).collect(Collectors.joining("\n"));
    }
}
