package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.PackageRequest;
import com.aicompany.core.model.DependencyRef;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Lectura determinista de lo que pide un agente (spec §3): solo versiones exactas. */
public final class DependencyManifest {

    public record Parsed(List<DependencyRef> deps, List<String> errors) {
    }

    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");
    static final Pattern VERSION = Pattern.compile("^\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?$");
    private static final Pattern SECTION = Pattern.compile("^(dependencies|dev_dependencies):\\s*$");
    private static final Pattern ENTRY = Pattern.compile("^  ([A-Za-z0-9_]+):\\s*(.*?)\\s*$");
    private static final Pattern NESTED = Pattern.compile("^    (sdk|path|git|hosted|version):\\s*(.*?)\\s*$");

    private DependencyManifest() {
    }

    public static List<String> validateRequests(List<PackageRequest> requests) {
        var errors = new ArrayList<String>();
        for (var r : requests == null ? List.<PackageRequest>of() : requests) {
            if (r == null || r.name() == null || !NAME.matcher(r.name()).matches()) {
                errors.add("packages: nombre de paquete inválido \"" + (r == null ? null : r.name()) + "\".");
            } else if (r.version() == null || !VERSION.matcher(r.version()).matches()) {
                errors.add("packages: " + r.name() + " necesita una versión exacta (p. ej. 13.0.3), no \"" + r.version() + "\".");
            }
        }
        return errors;
    }

    public static Parsed pubspec(String yaml) {
        var deps = new ArrayList<DependencyRef>();
        var errors = new ArrayList<String>();
        var inSection = false;
        String pending = null;
        for (var line : (yaml == null ? "" : yaml).split("\\R")) {
            if (line.isBlank() || line.strip().startsWith("#")) {
                continue;
            }
            if (!line.startsWith(" ")) {
                inSection = SECTION.matcher(line).matches();
                pending = null;
                continue;
            }
            if (!inSection) {
                continue;
            }
            var nested = NESTED.matcher(line);
            if (nested.matches() && pending != null) {
                if (!"sdk".equals(nested.group(1))) {
                    errors.add("pubspec.yaml: " + pending + " usa " + nested.group(1) + ": solo se permiten paquetes de pub.dev con versión exacta.");
                }
                pending = null;
                continue;
            }
            var entry = ENTRY.matcher(line);
            if (!entry.matches()) {
                continue;
            }
            var name = entry.group(1);
            var version = entry.group(2).replace("\"", "").replace("'", "");
            if (version.isEmpty()) {
                pending = name;
            } else if (VERSION.matcher(version).matches()) {
                deps.add(new DependencyRef("PUB", name, version));
            } else {
                errors.add("pubspec.yaml: " + name + " necesita una versión exacta (p. ej. 2.0.5), no \"" + version + "\".");
            }
        }
        return new Parsed(deps, errors);
    }
}
