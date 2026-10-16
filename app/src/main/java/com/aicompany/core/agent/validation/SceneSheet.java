package com.aicompany.core.agent.validation;

import java.util.List;

/** Juegos por niveles (spec 2026-10-16 contrato §4): lee el código C# de las escenas sin modelo. */
public final class SceneSheet {

    private SceneSheet() {
    }

    /** El código sin comentarios ni literales de texto (quedan como ""), para que no confundan a los patrones. */
    public static String strip(String source) {
        var out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
                out.append(' ');
            } else if (c == '"' || c == '\'') {
                boolean verbatim = c == '"' && i > 0 && source.charAt(i - 1) == '@';
                int j = i + 1;
                while (j < source.length()) {
                    char d = source.charAt(j);
                    if (!verbatim && d == '\\') { j += 2; continue; }
                    if (d == c) {
                        if (verbatim && j + 1 < source.length() && source.charAt(j + 1) == '"') { j += 2; continue; }
                        break;
                    }
                    j++;
                }
                out.append(c).append(c);
                i = Math.min(source.length(), j + 1);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    public record Member(String kind, String type, String name, String parameters) {
    }

    private static final java.util.regex.Pattern MEMBER = java.util.regex.Pattern.compile(
            "\\bpublic\\s+(?:(?:static|readonly|override|virtual|new|sealed|const|async)\\s+)*"
                    + "([A-Za-z_][\\w.]*(?:<[^;{}()=]*?>)?\\??(?:\\[\\])?)\\s+([A-Za-z_]\\w*)\\s*(\\(|\\{|=>|=|;)");

    public static List<Member> extract(String className, java.util.Collection<String> sources) {
        var out = new java.util.ArrayList<Member>();
        var seen = new java.util.HashSet<String>();
        var header = java.util.regex.Pattern.compile("\\bclass\\s+" + java.util.regex.Pattern.quote(className) + "\\b[^{]*\\{");
        for (var source : sources) {
            if (source == null) {
                continue;
            }
            var code = strip(source);
            var m = header.matcher(code);
            while (m.find()) {
                var body = body(code, m.end());
                var member = MEMBER.matcher(topLevel(body));
                while (member.find()) {
                    var name = member.group(2);
                    if (name.startsWith("_") || name.equals(className) || !seen.add(name)) {
                        continue;
                    }
                    var after = member.group(3);
                    if (after.equals("(")) {
                        var close = body.indexOf(')', bodyIndex(body, member.end()));
                        out.add(new Member("METHOD", member.group(1), name, body.substring(bodyIndex(body, member.end()),
                                Math.max(bodyIndex(body, member.end()), close)).strip().replaceAll("\\s+", " ")));
                    } else {
                        out.add(new Member(after.equals("{") || after.equals("=>") ? "PROPERTY" : "FIELD",
                                member.group(1), name, ""));
                    }
                }
            }
        }
        return out;
    }

    public static String render(Member m) {
        return switch (m.kind()) {
            case "METHOD" -> m.type() + " " + m.name() + "(" + m.parameters() + ")";
            case "PROPERTY" -> m.type() + " " + m.name() + " { get; }";
            default -> m.type() + " " + m.name();
        };
    }

    /** El cuerpo de la clase desde la llave de apertura (start = después de '{') hasta su cierre. */
    private static String body(String code, int start) {
        int depth = 1;
        int i = start;
        while (i < code.length() && depth > 0) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            if (c == '}') depth--;
            i++;
        }
        return code.substring(start, Math.max(start, i - 1));
    }

    /**
     * El cuerpo con el contenido de las llaves anidadas (cuerpos de métodos y de propiedades) reemplazado por espacios,
     * del mismo largo: las posiciones coinciden con body y solo quedan las declaraciones de nivel de clase.
     */
    private static String topLevel(String body) {
        var out = new StringBuilder(body);
        int depth = 0;
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (c == '{') {
                depth++;
                if (depth > 1) out.setCharAt(i, ' ');
            } else if (c == '}') {
                if (depth > 1) out.setCharAt(i, ' ');
                depth--;
            } else if (depth > 0 && c != '\n') {
                out.setCharAt(i, ' ');
            }
        }
        return out.toString();
    }

    private static int bodyIndex(String body, int matchEnd) {
        return Math.min(matchEnd, body.length());
    }
}
