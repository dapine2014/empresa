package com.aicompany.core.agent.validation;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** QA casos de uso (2026-10-05): ids de escenario en un nombre o en código (ESC-03 o ESC_03 → ESC-03). */
public final class ScenarioIds {

    private static final Pattern ID = Pattern.compile("\\bESC[-_](\\d{2})(?!\\d)");

    private ScenarioIds() {
    }

    public static Set<String> in(String text) {
        var ids = new LinkedHashSet<String>();
        if (text == null) {
            return ids;
        }
        var m = ID.matcher(text.replace("_y_", " ").replace("_and_", " "));
        while (m.find()) {
            ids.add("ESC-" + m.group(1));
        }
        return ids;
    }
}
