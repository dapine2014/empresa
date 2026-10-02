package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Migraciones de Diego (spec 2026-10-02 §2.4): nombre, secuencia sin huecos, contenido y sin metacomandos de psql. */
public final class MigrationFilesGate {

    public static final String ROOT = "db/postgres/migrations/";
    private static final Pattern NAME = Pattern.compile("V([1-9][0-9]*)__[a-z0-9_]+\\.sql");

    private MigrationFilesGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> files, List<String> alreadyInRepo) {
        var errors = new ArrayList<String>();
        var versions = new TreeSet<Integer>();
        for (var path : alreadyInRepo) {
            version(path).ifPresent(versions::add);
        }
        for (var file : files.stream().filter(Objects::nonNull).toList()) {
            if (file.path() == null || !file.path().startsWith(ROOT)) {
                continue;
            }
            var name = file.path().substring(ROOT.length());
            var matcher = NAME.matcher(name);
            if (!matcher.matches()) {
                errors.add("Migración \"" + file.path() + "\": el nombre debe ser V<n>__<descripcion>.sql (minúsculas y _).");
                continue;
            }
            versions.add(Integer.parseInt(matcher.group(1)));
            if (file.content() == null || file.content().isBlank()) {
                errors.add("Migración \"" + file.path() + "\" vacía.");
            } else if (file.content().lines().anyMatch(l -> l.strip().startsWith("\\"))) {
                errors.add("Migración \"" + file.path() + "\": sin metacomandos de psql (\\connect, \\c…); Forjai la aplica con JDBC.");
            }
        }
        var expected = 1;
        for (var v : versions) {
            if (v != expected) {
                errors.add("Las migraciones deben ir de V1 en adelante sin huecos: falta V" + expected + ".");
                break;
            }
            expected++;
        }
        return errors;
    }

    static java.util.Optional<Integer> version(String path) {
        if (path == null || !path.startsWith(ROOT)) {
            return java.util.Optional.empty();
        }
        var m = NAME.matcher(path.substring(ROOT.length()));
        return m.matches() ? java.util.Optional.of(Integer.parseInt(m.group(1))) : java.util.Optional.empty();
    }
}
