package com.aicompany.core.agent.validation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Diagnóstico 360 (decisión del fundador 2026-10-03): los tests que fallan, leídos de la salida real del sandbox (nunca
 * de un modelo). Formato de dotnet test; el sandbox de Flutter imprime su resumen en el mismo formato (run.sh).
 * Las rutas del stack trace vuelven con la carpeta del componente.
 */
public final class FailingTestParser {

    public record FailingTest(String name, String message, List<String> locations) {

        /** El nombre del método o caso (lo que debe seguir existiendo si se corrige el test). */
        public String method() {
            var dot = name.lastIndexOf('.');
            return dot < 0 || name.contains(" ") ? name : name.substring(dot + 1);
        }

        public List<String> files() {
            return locations.stream().map(l -> l.substring(0, l.lastIndexOf(':'))).distinct().toList();
        }
    }

    private static final Pattern FAILED = Pattern.compile("^\\s*Failed (.+?)(?: \\[[^\\]]*\\])?\\s*$");
    private static final Pattern LOCATION = Pattern.compile(" in /work/(\\S+?):line (\\d+)\\s*$");
    static final int MAX_MESSAGE_CHARS = 1_500;

    private FailingTestParser() {
    }

    public static List<FailingTest> parse(String output, String componentDir) {
        var tests = new ArrayList<FailingTest>();
        if (output == null) {
            return tests;
        }
        String name = null;
        var message = new StringBuilder();
        var locations = new LinkedHashSet<String>();
        var section = "";
        for (var line : output.split("\\R")) {
            var failed = FAILED.matcher(line);
            if (failed.matches()) {
                if (name != null) {
                    tests.add(build(name, message, locations));
                }
                name = failed.group(1).strip();
                message.setLength(0);
                locations.clear();
                section = "";
                continue;
            }
            if (name == null) {
                continue;
            }
            var trimmed = line.strip();
            if (trimmed.equals("Error Message:")) {
                section = "message";
            } else if (trimmed.equals("Stack Trace:")) {
                section = "stack";
            } else if (trimmed.startsWith("Results File:") || trimmed.startsWith("Failed!") || trimmed.startsWith("Passed!")) {
                section = "";
            } else if ("message".equals(section)) {
                if (message.length() < MAX_MESSAGE_CHARS) {
                    message.append(message.isEmpty() ? "" : "\n").append(trimmed);
                }
            } else if ("stack".equals(section)) {
                var location = LOCATION.matcher(line);
                if (location.find()) {
                    var path = componentDir == null || componentDir.isEmpty()
                            ? location.group(1) : componentDir + "/" + location.group(1);
                    locations.add(path + ":" + location.group(2));
                }
            }
        }
        if (name != null) {
            tests.add(build(name, message, locations));
        }
        return tests;
    }

    private static FailingTest build(String name, StringBuilder message, LinkedHashSet<String> locations) {
        return new FailingTest(name, message.toString(), List.copyOf(locations));
    }
}
