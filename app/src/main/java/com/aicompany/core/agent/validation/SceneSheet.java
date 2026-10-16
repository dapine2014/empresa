package com.aicompany.core.agent.validation;

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
}
