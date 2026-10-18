package com.aicompany.core.agent.validation;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** QA casos de uso (2026-10-05): ids de escenario en un nombre o en código (ESC-03 o ESC_03 → ESC-03). */
public final class ScenarioIds {

    // Revisión final (M1): también en medio de un identificador (Crear_ESC_03, ESC_03_ESC_04), nunca ESC_031.
    private static final Pattern ID = Pattern.compile("(?<![A-Za-z0-9])ESC[-_](\\d{2})(?!\\d)");

    private ScenarioIds() {
    }

    public static Set<String> in(String text) {
        var ids = new LinkedHashSet<String>();
        if (text == null) {
            return ids;
        }
        var m = ID.matcher(text);
        while (m.find()) {
            ids.add("ESC-" + m.group(1));
        }
        return ids;
    }

    /** Revisión final (M2): en el nombre de un test; los argumentos de un Theory ("(s: \"ESC-09\")") no cuentan. */
    public static Set<String> inTestName(String name) {
        if (name == null) {
            return new LinkedHashSet<>();
        }
        var paren = name.indexOf('(');
        return in(paren < 0 ? name : name.substring(0, paren));
    }
}
