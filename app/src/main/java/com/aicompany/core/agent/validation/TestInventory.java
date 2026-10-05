package com.aicompany.core.agent.validation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** QA casos de uso (2026-10-05): los tests declarados en el código (nombre → saltado), en C# (xUnit) y Dart. */
public final class TestInventory {

    private static final Pattern CSHARP = Pattern.compile(
            "\\[(Fact|Theory)(\\([^\\]]*\\))?\\][^{;]*?\\b(?:void|Task)\\s+(\\w+)\\s*\\(");
    private static final Pattern DART = Pattern.compile("\\b(?:test|testWidgets)\\s*\\(\\s*(['\"])(.*?)\\1");
    private static final Pattern DART_SKIP = Pattern.compile("\\bskip\\s*:\\s*(true|['\"])");

    private TestInventory() {
    }

    public static boolean isTestFile(String path) {
        return TestTriageValidator.isTestFile(path) && (path.endsWith(".cs") || path.endsWith(".dart"));
    }

    public static Map<String, Boolean> of(Map<String, String> files) {
        var tests = new LinkedHashMap<String, Boolean>();
        files.forEach((path, content) -> {
            if (content == null || !isTestFile(path)) {
                return;
            }
            if (path.endsWith(".cs")) {
                var m = CSHARP.matcher(content);
                while (m.find()) {
                    tests.put(m.group(3), m.group(2) != null && m.group(2).contains("Skip"));
                }
            } else {
                var m = DART.matcher(content);
                while (m.find()) {
                    var end = content.indexOf(");", m.end());
                    var call = content.substring(m.start(), end < 0 ? content.length() : end);
                    tests.put(m.group(2), DART_SKIP.matcher(call).find());
                }
            }
        });
        return tests;
    }
}
