package com.aicompany.core.agent.validation;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * Licencias permitidas (spec §3): MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause, ISC, Zlib. NuGet: expresión del
 * .nuspec (OR → alguna permitida; AND → todas). Pub: firma del texto de LICENSE; lo no reconocido no se permite.
 */
public final class LicenseClassifier {

    static final Set<String> ALLOWED = Set.of("MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "ISC", "Zlib");

    private LicenseClassifier() {
    }

    public static Optional<String> nuget(String expression) {
        if (expression == null || expression.isBlank()) {
            return Optional.empty();
        }
        var e = expression.replace("(", "").replace(")", "").strip();
        if (e.contains(" AND ")) {
            return Arrays.stream(e.split(" AND ")).map(String::strip).allMatch(ALLOWED::contains)
                    ? Optional.of(expression) : Optional.empty();
        }
        return Arrays.stream(e.split(" OR ")).map(String::strip).anyMatch(ALLOWED::contains)
                ? Optional.of(expression) : Optional.empty();
    }

    public static Optional<String> text(String license) {
        if (license == null) {
            return Optional.empty();
        }
        var t = license.replaceAll("\\s+", " ");
        if (t.contains("Permission is hereby granted, free of charge")) {
            return Optional.of("MIT");
        }
        if (t.contains("Apache License") && t.contains("Version 2.0")) {
            return Optional.of("Apache-2.0");
        }
        if (t.contains("Redistribution and use in source and binary forms")) {
            return Optional.of(t.contains("Neither the name") ? "BSD-3-Clause" : "BSD-2-Clause");
        }
        if (t.contains("Permission to use, copy, modify, and/or distribute")) {
            return Optional.of("ISC");
        }
        if (t.contains("This software is provided 'as-is'") && t.contains("Altered source versions")) {
            return Optional.of("Zlib");
        }
        return Optional.empty();
    }
}
