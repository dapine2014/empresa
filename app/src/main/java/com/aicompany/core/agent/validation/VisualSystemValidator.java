package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.VisualSystem;
import com.aicompany.core.model.FontCatalog;

import java.util.*;
import java.util.regex.Pattern;

/** Spec 2026-11-01 front con diseño visual: reglas de Java sobre el sistema visual (lista vacía = válido). */
public final class VisualSystemValidator {

    public static final List<String> REQUIRED_COLORS = List.of("primary", "onPrimary", "surface", "onSurface", "error", "onError");
    /** Texto sobre fondo: WCAG 2.1 AA exige 4,5. */
    public static final double MIN_CONTRAST = 4.5;
    private static final List<String[]> PAIRS = List.of(new String[]{"onPrimary", "primary"},
            new String[]{"onSurface", "surface"}, new String[]{"onError", "error"});
    private static final Set<String> COMPONENTS = Set.of("button", "field", "card", "appBar");
    private static final List<String> SIZES = List.of("display", "title", "body", "label");
    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{6}");

    private VisualSystemValidator() {
    }

    public static List<String> validate(VisualSystem vs) {
        if (vs == null) {
            return List.of("El sistema visual está vacío.");
        }
        var errors = new ArrayList<String>();
        var hexes = new LinkedHashMap<String, String>();
        for (var c : vs.colorsOrEmpty()) {
            if (c == null || c.name() == null || c.name().isBlank()) {
                errors.add("Hay un color sin nombre.");
            } else if (c.hex() == null || !HEX.matcher(c.hex()).matches()) {
                errors.add("El color " + c.name() + " tiene el hex \"" + (c.hex() == null ? "" : c.hex()) + "\" inválido: usa #RRGGBB.");
            } else if (hexes.put(c.name(), c.hex()) != null) {
                errors.add("El color " + c.name() + " está repetido.");
            }
        }
        var names = new HashSet<>(vs.colorsOrEmpty().stream().filter(Objects::nonNull).map(VisualSystem.ColorToken::name).toList());
        for (var req : REQUIRED_COLORS) {
            if (!names.contains(req)) {
                errors.add("Falta el color obligatorio " + req + " (obligatorios: " + REQUIRED_COLORS + ").");
            }
        }
        for (var p : PAIRS) {
            var fg = hexes.get(p[0]);
            var bg = hexes.get(p[1]);
            if (fg != null && bg != null) {
                var ratio = contrast(fg, bg);
                if (ratio < MIN_CONTRAST) {
                    errors.add(String.format(Locale.ROOT, "El contraste de %s sobre %s es %.2f, menor al mínimo de 4,5 (WCAG AA).",
                            p[0], p[1], ratio).replace(".", ",").replaceFirst(",$", "."));
                }
            }
        }
        var t = vs.typography();
        if (t == null || t.fontFamily() == null || !FontCatalog.FAMILIES.contains(t.fontFamily())) {
            errors.add("La fuente \"" + (t == null ? "" : t.fontFamily()) + "\" no está en el catálogo; usa una de " + FontCatalog.FAMILIES + ".");
        }
        if (t != null) {
            for (var s : SIZES) {
                var v = t.sizes().get(s);
                if (v == null || v <= 0) {
                    errors.add("typography.sizes debe tener un tamaño positivo para " + s + ".");
                }
            }
        }
        for (var comp : vs.componentsOrEmpty()) {
            if (comp == null || comp.component() == null || !COMPONENTS.contains(comp.component())) {
                errors.add("El componente \"" + (comp == null ? null : comp.component()) + "\" no es button, field, card ni appBar.");
                continue;
            }
            comp.tokens().forEach((slot, token) -> {
                if (token == null || !names.contains(token)) {
                    errors.add(comp.component() + "." + slot + ": el token \"" + token + "\" no existe en los colores.");
                }
            });
        }
        return errors;
    }

    /** Razón de contraste WCAG 2.1 entre dos colores #RRGGBB. */
    static double contrast(String a, String b) {
        var la = luminance(a);
        var lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(String hex) {
        var rgb = Integer.parseInt(hex.substring(1), 16);
        return 0.2126 * lin((rgb >> 16) & 0xFF) + 0.7152 * lin((rgb >> 8) & 0xFF) + 0.0722 * lin(rgb & 0xFF);
    }

    private static double lin(int channel) {
        var c = channel / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
