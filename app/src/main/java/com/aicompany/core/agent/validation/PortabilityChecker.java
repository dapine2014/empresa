package com.aicompany.core.agent.validation;

import com.aicompany.core.model.StaticCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Spec 2026-10-02 B1 §1: un componente Flutter sirve igual para web, Android e iOS solo si ninguna capa importa
 * librerías que existen únicamente en el navegador. Compilar en web no lo detecta: lo detecta Java.
 */
public final class PortabilityChecker {

    static final List<String> WEB_ONLY = List.of("dart:html", "dart:js", "dart:js_util", "dart:js_interop", "package:web/");

    private static final Pattern IMPORT = Pattern.compile("^\\s*(?:import|export)\\s+['\"]([^'\"]+)['\"]", Pattern.MULTILINE);

    private PortabilityChecker() {
    }

    public static StaticCheck check(Map<String, String> dartByPath) {
        var found = new ArrayList<String>();
        var files = new ArrayList<String>();
        dartByPath.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var matcher = IMPORT.matcher(entry.getValue() == null ? "" : entry.getValue());
            while (matcher.find()) {
                var uri = matcher.group(1);
                if (WEB_ONLY.stream().anyMatch(w -> uri.equals(w) || (w.endsWith("/") && uri.startsWith(w)))) {
                    found.add(entry.getKey() + " → " + uri);
                    if (!files.contains(entry.getKey())) {
                        files.add(entry.getKey());
                    }
                }
            }
        });
        return found.isEmpty()
                ? StaticCheck.pass("PORTABILITY", "Sin librerías solo-web (" + dartByPath.size() + " archivo(s) Dart)",
                        null, List.of())
                : StaticCheck.fail("PORTABILITY", "Librerías solo-web (rompen Android/iOS): " + String.join("; ", found),
                        null, files);
    }
}
