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

    private static final Pattern TRANSACTION = Pattern.compile("(?i)^\\s*(BEGIN|COMMIT|ROLLBACK|START\\s+TRANSACTION)\\b.*");

    /**
     * {@code existing}: migraciones ya commiteadas (ruta → contenido). Revisión final (I-5): una que ya existe no se
     * edita, el cambio va en una nueva; (m-7) Forjai abre la transacción, la migración no la maneja.
     */
    public static List<String> check(List<DevelopmentResult.GeneratedFile> files, java.util.Map<String, String> existing) {
        var errors = new ArrayList<String>(checkNames(files, new ArrayList<>(existing.keySet())));
        var next = existing.keySet().stream().map(MigrationFilesGate::version).flatMap(java.util.Optional::stream)
                .max(Integer::compare).orElse(0) + 1;
        for (var file : files.stream().filter(Objects::nonNull).toList()) {
            if (file.path() == null || !file.path().startsWith(ROOT)) {
                continue;
            }
            var previous = existing.get(file.path());
            if (previous != null && !previous.equals(file.content())) {
                errors.add("La migración " + file.path() + " ya existe y no se edita (puede estar aplicada en la base del "
                        + "fundador): devuélvela sin cambios y pon el cambio en una nueva, V" + next + "__<descripcion>.sql.");
            }
            if (file.content() != null && file.content().lines().anyMatch(l -> TRANSACTION.matcher(l).matches())) {
                errors.add("Migración \"" + file.path() + "\": sin BEGIN/COMMIT/ROLLBACK; Forjai aplica cada migración en "
                        + "su propia transacción.");
            }
        }
        return errors;
    }

    private static List<String> checkNames(List<DevelopmentResult.GeneratedFile> files, List<String> alreadyInRepo) {
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
