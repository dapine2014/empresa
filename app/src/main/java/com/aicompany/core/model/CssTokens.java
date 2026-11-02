package com.aicompany.core.model;

import com.aicompany.core.agent.model.Mockups.Mockup;
import com.aicompany.core.agent.model.VisualSystem;

import java.util.LinkedHashSet;
import java.util.Set;

/** Spec 2026-11-01 front con diseño visual: Java genera el CSS de variables; el modelo solo las referencia con var(). */
public final class CssTokens {

    private CssTokens() {
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** Bloque :root con todas las variables del sistema visual y los @font-face (fuentes junto a la maqueta, en fonts/). */
    public static String root(VisualSystem vs) {
        var sb = new StringBuilder();
        sb.append(":root{");
        for (var c : vs.colorsOrEmpty()) sb.append("--color-").append(c.name()).append(':').append(c.hex()).append(';');
        if (vs.typography() != null) {
            sb.append("--font-family:\"").append(vs.typography().fontFamily()).append("\";");
            vs.typography().sizes().forEach((k, v) -> sb.append("--size-").append(k).append(':').append(num(v)).append("px;"));
        }
        vs.spacing().forEach((k, v) -> sb.append("--space-").append(k).append(':').append(num(v)).append("px;"));
        vs.radii().forEach((k, v) -> sb.append("--radius-").append(k).append(':').append(num(v)).append("px;"));
        sb.append("}\n");
        for (var file : FontCatalog.files(vs.typography() == null ? null : vs.typography().fontFamily())) {
            var base = file.substring(file.lastIndexOf('/') + 1);
            sb.append("@font-face{font-family:\"").append(vs.typography().fontFamily())
                    .append("\";src:url(\"fonts/").append(base).append("\") format(\"truetype\");font-weight:")
                    .append(base.endsWith("Bold.ttf") ? "700" : "400").append(";}\n");
        }
        return sb.toString();
    }

    /** Nombres de variable (sin "--") que una maqueta puede referenciar. */
    public static Set<String> variableNames(VisualSystem vs) {
        var names = new LinkedHashSet<String>();
        for (var c : vs.colorsOrEmpty()) names.add("color-" + c.name());
        names.add("font-family");
        if (vs.typography() != null) vs.typography().sizes().keySet().forEach(k -> names.add("size-" + k));
        vs.spacing().keySet().forEach(k -> names.add("space-" + k));
        vs.radii().keySet().forEach(k -> names.add("radius-" + k));
        return names;
    }

    /** HTML final: el style de Java va al principio del head (o al principio si la maqueta no trae head). */
    public static String page(Mockup m, VisualSystem vs) {
        var style = "<style>" + root(vs) + "</style>";
        var html = m.html() == null ? "" : m.html();
        var head = java.util.regex.Pattern.compile("(?i)<head(\\s[^>]*)?>").matcher(html);
        if (head.find()) return html.substring(0, head.end()) + style + html.substring(head.end());
        var root = java.util.regex.Pattern.compile("(?i)<html[^>]*>").matcher(html);
        if (root.find()) return html.substring(0, root.end()) + "<head><meta charset=\"utf-8\">" + style + "</head>" + html.substring(root.end());
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" + style + "</head><body>" + html + "</body></html>";
    }
}
