package com.aicompany.core.database;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Migraciones versionadas como Flyway, sin Flyway (spec 2026-10-02 §3). Puro: nunca toca la red. */
public final class MigrationSet {

    private static final Pattern PATH = Pattern.compile("db/postgres/migrations/V([1-9][0-9]*)__([a-z0-9_]+)\\.sql");

    public record Migration(int version, String description, String sql, String checksum) {
    }

    public record AppliedMigration(int version, String checksum) {
    }

    public record Plan(List<Migration> toApply, String error) {
    }

    private MigrationSet() {
    }

    public static List<Migration> parse(Map<String, String> contentsByPath) {
        var result = new ArrayList<Migration>();
        contentsByPath.forEach((path, sql) -> {
            var m = PATH.matcher(path);
            if (m.matches()) {
                result.add(new Migration(Integer.parseInt(m.group(1)), m.group(2), sql, sha256(sql)));
            }
        });
        result.sort(Comparator.comparingInt(Migration::version));
        return result;
    }

    public static Plan pending(List<Migration> all, List<AppliedMigration> history) {
        var byVersion = new java.util.HashMap<Integer, Migration>();
        all.forEach(m -> byVersion.put(m.version(), m));
        for (var applied : history) {
            var current = byVersion.get(applied.version());
            if (current == null) {
                return new Plan(List.of(), "V" + applied.version() + " está aplicada en la base pero ya no está en el "
                        + "repositorio: no se aplica nada.");
            }
            if (!current.checksum().equals(applied.checksum())) {
                return new Plan(List.of(), "V" + applied.version() + " se editó después de aplicarse: el cambio va en "
                        + "una migración nueva. No se aplicó nada.");
            }
        }
        var appliedVersions = history.stream().map(AppliedMigration::version).toList();
        return new Plan(all.stream().filter(m -> !appliedVersions.contains(m.version())).toList(), null);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
