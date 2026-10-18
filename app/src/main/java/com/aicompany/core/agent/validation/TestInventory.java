package com.aicompany.core.agent.validation;

import com.aicompany.core.model.StackProfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * QA casos de uso (2026-10-05): los tests declarados en el código (nombre → saltado), en C# (xUnit) y Dart. Revisión
 * final (I4): listas de atributos ([Fact, Trait(...)]), InlineData con ';' o '{', async ValueTask/Task&lt;T&gt;, y en Dart
 * un skip: después del cuerpo.
 */
public final class TestInventory {

    private static final Pattern CSHARP_METHOD = Pattern.compile(
            "(?:\\b(?:public|internal|private|protected|static|async|virtual|override)\\s+)*"
                    + "\\b(?:void|Task|ValueTask)(?:<[^>()]*>)?\\s+(\\w+)\\s*\\(");
    /** Atributos seguidos justo antes del método; tolera strings con ']', ';' o '{'. */
    private static final Pattern TRAILING_ATTRIBUTES = Pattern.compile(
            "((?:\\[(?:[^\\[\\]\"]|\"(?:[^\"\\\\]|\\\\.)*\")*\\]\\s*)+)$");
    private static final Pattern TEST_ATTRIBUTE = Pattern.compile("\\b(Fact|Theory)\\b");
    private static final Pattern DART = Pattern.compile("\\b(?:test|testWidgets)\\s*\\(\\s*(['\"])(.*?)\\1");
    private static final Pattern DART_SKIP = Pattern.compile("\\bskip\\s*:\\s*(true|['\"])");

    private static final Pattern PLAYTHROUGH = Pattern.compile(
            "\\bclass\\s+(\\w+)\\s*:\\s*(?:Forjai\\.Playthroughs\\.)?Playthrough\\b");

    private TestInventory() {
    }

    public static boolean isTestFile(String path) {
        return (TestTriageValidator.isTestFile(path) && (path.endsWith(".cs") || path.endsWith(".dart")))
                || isPlaythrough(path);
    }

    /** Spec 2026-10-14 Kael §2.3: las partidas simuladas de Vera cuentan como tests (el nombre de la clase lleva el ESC). */
    static boolean isPlaythrough(String path) {
        var p = OwnedPaths.normalize(path);
        return p.endsWith(".cs") && (p.startsWith(StackProfile.PLAYTHROUGHS_ROOT + "/")
                || p.contains("/" + StackProfile.PLAYTHROUGHS_ROOT + "/"));
    }

    public static Map<String, Boolean> of(Map<String, String> files) {
        var tests = new LinkedHashMap<String, Boolean>();
        files.forEach((path, content) -> {
            if (content == null || !isTestFile(path)) {
                return;
            }
            if (isPlaythrough(path)) {
                var m = PLAYTHROUGH.matcher(content);
                while (m.find()) {
                    tests.put(m.group(1), false);
                }
                return;
            }
            if (path.endsWith(".cs")) {
                csharp(content, tests);
            } else {
                dart(content, tests);
            }
        });
        return tests;
    }

    private static void csharp(String content, Map<String, Boolean> tests) {
        var m = CSHARP_METHOD.matcher(content);
        while (m.find()) {
            var prefix = content.substring(Math.max(0, m.start() - 2000), m.start());
            var attributes = TRAILING_ATTRIBUTES.matcher(prefix);
            if (attributes.find() && TEST_ATTRIBUTE.matcher(attributes.group(1)).find()) {
                tests.put(m.group(1), attributes.group(1).contains("Skip"));
            }
        }
    }

    private static void dart(String content, Map<String, Boolean> tests) {
        var m = DART.matcher(content);
        while (m.find()) {
            var open = content.indexOf('(', m.start());
            var close = closingParen(content, open);
            var call = content.substring(m.start(), close < 0 ? content.length() : close);
            tests.put(m.group(2), DART_SKIP.matcher(afterBody(call)).find());
        }
    }

    /** Índice del ')' que cierra el '(' en {@code open}, saltando strings; -1 si no cierra. */
    private static int closingParen(String text, int open) {
        var depth = 0;
        char quote = 0;
        for (int i = open; i < text.length(); i++) {
            var c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int lastBodyEnd(String call) {
        var end = call.lastIndexOf('}');
        return end < 0 ? 0 : end;
    }

    /** Lo que sigue al cuerpo del test (los argumentos con nombre como skip:). */
    private static String afterBody(String call) {
        return call.substring(lastBodyEnd(call));
    }
}
