package com.forjai.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Metadatos de lo restaurado en staging (estructuras verificadas en vivo el 2026-09-27). */
public final class PackageMetadataReader {

    private static final Pattern ID = Pattern.compile("<id>([^<]+)</id>");
    private static final Pattern VERSION = Pattern.compile("<version>([^<]+)</version>");
    private static final Pattern LICENSE = Pattern.compile("<license\\s+type=\"expression\"\\s*>([^<]+)</license>");
    private static final int LICENSE_TEXT_CHARS = 4000;

    private PackageMetadataReader() {
    }

    public static List<FetchedPackage> nuget(Path staging) {
        var result = new ArrayList<FetchedPackage>();
        try (var ids = list(staging)) {
            for (var idDir : ids.filter(Files::isDirectory).toList()) {
                try (var versions = list(idDir)) {
                    for (var versionDir : versions.filter(Files::isDirectory).toList()) {
                        result.add(nugetPackage(versionDir));
                    }
                }
            }
        }
        return result;
    }

    private static FetchedPackage nugetPackage(Path dir) {
        var nuspec = firstFile(dir, ".nuspec");
        var text = nuspec == null ? "" : read(nuspec, Integer.MAX_VALUE);
        var id = match(ID, text, dir.getParent().getFileName().toString());
        var version = match(VERSION, text, dir.getFileName().toString());
        var license = match(LICENSE, text, null);
        var buildFiles = new ArrayList<String>();
        for (var folder : List.of("build", "buildTransitive")) {
            buildFiles.addAll(relativeFiles(dir, dir.resolve(folder), f -> true));
        }
        buildFiles.addAll(relativeFiles(dir, dir.resolve("tools"), f -> f.toString().endsWith(".ps1")));
        return new FetchedPackage(id, version, license == null ? null : license.strip(), null,
                !buildFiles.isEmpty(), buildFiles);
    }

    public static List<FetchedPackage> pub(Path staging) {
        var result = new ArrayList<FetchedPackage>();
        try (var packages = list(staging.resolve("hosted/pub.dev"))) {
            for (var dir : packages.filter(Files::isDirectory).toList()) {
                var folder = dir.getFileName().toString();
                var dash = folder.lastIndexOf('-');
                if (dash <= 0) {
                    continue;
                }
                var license = Files.isRegularFile(dir.resolve("LICENSE")) ? read(dir.resolve("LICENSE"), LICENSE_TEXT_CHARS) : null;
                var hook = Files.isDirectory(dir.resolve("hook"));
                result.add(new FetchedPackage(folder.substring(0, dash), folder.substring(dash + 1), null, license,
                        hook, hook ? List.of("hook/") : List.of()));
            }
        }
        return result;
    }

    private static Stream<Path> list(Path dir) {
        try {
            return Files.isDirectory(dir) ? Files.list(dir) : Stream.empty();
        } catch (IOException ex) {
            return Stream.empty();
        }
    }

    private static Path firstFile(Path dir, String suffix) {
        try (var files = list(dir)) {
            return files.filter(f -> f.toString().endsWith(suffix)).findFirst().orElse(null);
        }
    }

    private static List<String> relativeFiles(Path root, Path dir, java.util.function.Predicate<Path> filter) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).filter(filter)
                    .map(f -> root.relativize(f).toString().replace('\\', '/')).sorted().toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    private static String read(Path file, int maxChars) {
        try {
            var text = Files.readString(file);
            return text.length() <= maxChars ? text : text.substring(0, maxChars);
        } catch (IOException | RuntimeException ex) {
            return "";
        }
    }

    private static String match(Pattern pattern, String text, String fallback) {
        var m = pattern.matcher(text);
        return m.find() ? m.group(1) : fallback;
    }
}
