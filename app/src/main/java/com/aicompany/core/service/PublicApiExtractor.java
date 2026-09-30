package com.aicompany.core.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Contrato de API de las capas ya commiteadas (verificado en vivo, MISSION-ORQ-1790736885126, 2026-09-30): el código
 * previo llegaba recortado y las capas siguientes inventaban miembros que no existían ({@code Exito()} en vez de
 * {@code Exitoso()}, {@code SembrarAsync()}, {@code AddInfrastructureServices()} como extensión). Java extrae, con reglas
 * deterministas y sin cuerpos, los namespaces, tipos y firmas públicas de C# y Dart; el bloque nunca se recorta.
 */
final class PublicApiExtractor {

    private static final Pattern CS_TYPE = Pattern.compile(
            "^((public|internal)\\s+)?((static|sealed|abstract|partial|readonly)\\s+)*(class|record|interface|enum|struct)\\s+\\w+.*");
    private static final Pattern DART_TYPE = Pattern.compile("^(abstract\\s+)?(class|mixin|enum|extension)\\s+\\w+.*");
    private static final Pattern DART_CONSTRUCTOR = Pattern.compile("^\\s+(const\\s+|factory\\s+)?[A-Z]\\w*(\\.\\w+)?\\(.*");
    private static final Pattern DART_METHOD = Pattern.compile(
            "^\\s+(static\\s+)?([\\w<>?,\\[\\] ]+?)\\s+([a-z]\\w*)\\s*\\(.*");
    private static final Set<String> DART_STATEMENTS = Set.of("return", "await", "if", "for", "while", "switch", "throw",
            "print", "var", "final", "yield", "else", "case", "assert");

    private PublicApiExtractor() {
    }

    static String extract(Map<String, String> contentsByPath) {
        var out = new StringBuilder();
        for (var entry : contentsByPath.entrySet()) {
            var content = entry.getValue() == null ? "" : entry.getValue();
            var lines = entry.getKey().endsWith(".cs") ? csharp(content)
                    : entry.getKey().endsWith(".dart") ? dart(content) : List.<String>of();
            if (lines.isEmpty()) {
                continue;
            }
            out.append("### ").append(entry.getKey()).append("\n");
            lines.forEach(line -> out.append(line).append("\n"));
        }
        return out.toString();
    }

    private static List<String> csharp(String content) {
        var out = new ArrayList<String>();
        var insideInterface = false;
        var lines = content.split("\n");
        for (int i = 0; i < lines.length; i++) {
            var t = lines[i].strip();
            // Firma en varias líneas (records con parámetros, métodos largos): se une hasta cerrar el paréntesis.
            if ((t.startsWith("public ") || CS_TYPE.matcher(t).matches()) && unclosed(t)) {
                var joined = new StringBuilder(t);
                while (unclosed(joined) && i + 1 < lines.length) {
                    joined.append(' ').append(lines[++i].strip());
                }
                t = joined.toString();
            }
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("using ")
                    || t.startsWith("[")) {
                continue;
            }
            if (t.startsWith("namespace ")) {
                out.add(t.replaceAll("[{;]\\s*$", "").strip());
                continue;
            }
            if (CS_TYPE.matcher(t).matches()) {
                out.add(csBody(t));
                insideInterface = t.matches(".*\\binterface\\b.*");
                continue;
            }
            if (t.startsWith("public ")) {
                out.add("    " + csBody(t));
            } else if (insideInterface && t.contains("(") && t.endsWith(";") && !t.startsWith("private")) {
                out.add("    " + t);
            }
        }
        return out;
    }

    private static boolean unclosed(CharSequence s) {
        var open = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '(') {
                open++;
            } else if (s.charAt(i) == ')') {
                open--;
            }
        }
        return open > 0;
    }

    /** Firma sin cuerpo: propiedades hasta el cierre de sus accesores; métodos hasta antes de "{" o "=>". */
    private static String csBody(String t) {
        var accessor = t.indexOf("{ get");
        if (accessor < 0) {
            accessor = t.indexOf("{get");
        }
        if (accessor >= 0) {
            var close = t.indexOf('}', accessor);
            return close > 0 ? t.substring(0, close + 1) : t;
        }
        var cut = t.length();
        var arrow = t.indexOf(" =>");
        if (arrow >= 0) {
            cut = Math.min(cut, arrow);
        }
        var brace = t.indexOf('{');
        if (brace >= 0) {
            cut = Math.min(cut, brace);
        }
        return t.substring(0, cut).strip();
    }

    private static List<String> dart(String content) {
        var out = new ArrayList<String>();
        for (var raw : content.split("\n")) {
            var t = raw.strip();
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("import ") || t.startsWith("export ")
                    || t.startsWith("part ") || t.startsWith("@")) {
                continue;
            }
            if (!Character.isWhitespace(raw.isEmpty() ? 'x' : raw.charAt(0)) && DART_TYPE.matcher(t).matches()) {
                out.add(dartBody(t));
                continue;
            }
            if (DART_CONSTRUCTOR.matcher(raw).matches()) {
                out.add("  " + dartBody(t));
                continue;
            }
            var method = DART_METHOD.matcher(raw);
            if (method.matches() && !DART_STATEMENTS.contains(method.group(2).strip().split("\\s+")[0])) {
                out.add("  " + dartBody(t));
            }
        }
        return out;
    }

    /** El cuerpo empieza después del ")" que cierra la firma: los parámetros con nombre ({String? x}) no son cuerpo. */
    private static final Pattern DART_SIGNATURE_END = Pattern.compile("\\)\\s*(async\\*?|sync\\*|\\{|=>|:)");

    private static String dartBody(String t) {
        var end = DART_SIGNATURE_END.matcher(t);
        if (end.find()) {
            return t.substring(0, end.start() + 1).strip();
        }
        var brace = t.indexOf(" {");
        return (brace >= 0 ? t.substring(0, brace) : t).strip();
    }
}
