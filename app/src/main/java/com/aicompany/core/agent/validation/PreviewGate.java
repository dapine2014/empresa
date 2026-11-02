package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Spec 2026-11-01 front con diseño visual: cada pantalla del incremento entrega su vista previa
 * lib/&lt;contexto&gt;/presentation/previews/pant_NN_preview.dart (class PantNNPreview, Key('PANT-NN') raíz) y
 * lib/main.dart arranca con forjaiPreviewOr(. Con reintento y el motivo.
 */
public final class PreviewGate {

    private static final Pattern PREVIEW_FILE = Pattern.compile("(?:.*/)?lib/.+/presentation/previews/pant_(\\d+)_preview\\.dart");
    private static final Pattern SCREEN = Pattern.compile("PANT-(\\d+)");

    private PreviewGate() {
    }

    /**
     * @param files lo que devolvió el agente.
     * @param existing archivos .dart que ya están en el repositorio (ruta a contenido); lo devuelto los pisa.
     */
    public static List<String> check(List<GeneratedFile> files, Set<String> screensInIncrement, Map<String, String> existing) {
        var merged = new LinkedHashMap<String, String>();
        if (existing != null) {
            existing.forEach((p, c) -> merged.put(OwnedPaths.normalize(p), c == null ? "" : c));
        }
        if (files != null) {
            files.stream().filter(f -> f != null && f.path() != null)
                    .forEach(f -> merged.put(OwnedPaths.normalize(f.path()), f.content() == null ? "" : f.content()));
        }
        var errors = new ArrayList<String>();
        for (var screen : screensInIncrement == null ? Set.<String>of() : screensInIncrement) {
            var m = SCREEN.matcher(screen);
            if (!m.matches()) {
                continue;
            }
            var number = Integer.parseInt(m.group(1));
            var nn = String.format("%02d", number);
            var cls = "Pant" + nn + "Preview";
            var path = merged.keySet().stream().filter(p -> {
                var pm = PREVIEW_FILE.matcher(p);
                return pm.matches() && Integer.parseInt(pm.group(1)) == number;
            }).findFirst();
            if (path.isEmpty()) {
                errors.add("Falta la vista previa de " + screen + ": escribe lib/<contexto>/presentation/previews/pant_" + nn
                        + "_preview.dart con class " + cls + " (constructor const sin argumentos) y Key('" + screen
                        + "') en su widget raíz.");
                continue;
            }
            var code = CSharpComments.strip(merged.get(path.get()));
            if (!Pattern.compile("\\bclass\\s+" + cls + "\\b").matcher(code).find()) {
                errors.add(path.get() + " debe declarar class " + cls + ".");
            }
            if (!Pattern.compile("Key\\(\\s*['\"]" + Pattern.quote(screen) + "['\"]\\s*\\)").matcher(code).find()) {
                errors.add(path.get() + " debe llevar Key('" + screen + "') en su widget raíz.");
            }
        }
        merged.forEach((p, c) -> {
            if ((p.equals("lib/main.dart") || p.endsWith("/lib/main.dart")) && !CSharpComments.strip(c).contains("forjaiPreviewOr(")) {
                errors.add(p + " debe arrancar con runApp(forjaiPreviewOr(const MiApp())): así Forjai muestra las vistas previas.");
            }
        });
        return errors;
    }
}
