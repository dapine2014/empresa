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
        return check(files, screensInIncrement, existing, true, true);
    }

    public static List<String> check(List<GeneratedFile> files, Set<String> screensInIncrement, Map<String, String> existing,
                                     boolean checkMain) {
        return check(files, screensInIncrement, existing, checkMain, true);
    }

    /** checkMain: solo la dueña de lib/main.dart responde por forjaiPreviewOr(; las demás frontales no pueden corregirlo. */
    /**
     * complete: la dueña de la última presentación responde por que cada pantalla del incremento tenga su vista previa;
     * las anteriores (R10) solo responden por las vistas previas que devuelven (ruta, clase y Key).
     */
    public static List<String> check(List<GeneratedFile> files, Set<String> screensInIncrement, Map<String, String> existing,
                                     boolean checkMain, boolean complete) {
        return check(files, screensInIncrement, existing, checkMain, complete, Map.of());
    }

    private static final Pattern DATA_ID = Pattern.compile("data-id\\s*=\\s*[\"']([^\"']+)[\"']");

    /** Los data-id de una maqueta HTML, en orden y sin repetir. */
    public static Set<String> dataIds(String html) {
        var ids = new java.util.LinkedHashSet<String>();
        if (html != null) {
            var m = DATA_ID.matcher(html);
            while (m.find()) {
                ids.add(m.group(1));
            }
        }
        return ids;
    }

    /**
     * Revisión final (C2): {@code idsByScreen} = data-id de la maqueta aprobada de cada pantalla. La dueña de la última
     * presentación ({@code complete}) responde por que cada uno (salvo el de la pantalla, que pone la vista previa)
     * aparezca en algún .dart como {@code ForjaiId('<data-id>'}: es lo único que la captura ve.
     */
    public static List<String> check(List<GeneratedFile> files, Set<String> screensInIncrement, Map<String, String> existing,
                                     boolean checkMain, boolean complete, Map<String, Set<String>> idsByScreen) {
        var merged = new LinkedHashMap<String, String>();
        if (existing != null) {
            existing.forEach((p, c) -> merged.put(OwnedPaths.normalize(p), c == null ? "" : c));
        }
        if (files != null) {
            files.stream().filter(f -> f != null && f.path() != null)
                    .forEach(f -> merged.put(OwnedPaths.normalize(f.path()), f.content() == null ? "" : f.content()));
        }
        var errors = new ArrayList<String>();
        if (!complete && files != null) {
            for (var f : files) {
                if (f == null || f.path() == null) {
                    continue;
                }
                var path = OwnedPaths.normalize(f.path());
                var pm = PREVIEW_FILE.matcher(path);
                if (pm.matches()) {
                    var number = Integer.parseInt(pm.group(1));
                    validate(errors, path, f.content(), number, String.format("PANT-%02d", number));
                }
            }
        }
        for (var screen : complete ? (screensInIncrement == null ? Set.<String>of() : screensInIncrement) : Set.<String>of()) {
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
                        + "') en su vista previa.");
                continue;
            }
            validate(errors, path.get(), merged.get(path.get()), number, screen);
            var ids = idsByScreen == null ? Set.<String>of() : idsByScreen.getOrDefault(screen, Set.of());
            var missing = ids.stream().filter(id -> !id.equals(screen)).filter(id -> merged.entrySet().stream()
                    .noneMatch(e -> e.getKey().endsWith(".dart") && wrapsId(CSharpComments.strip(e.getValue()), id))).toList();
            if (!missing.isEmpty()) {
                errors.add("Los elementos de la maqueta de " + screen + " deben llevar su data-id con ForjaiId (de "
                        + "lib/forjai/forjai_id.dart; una Key sola no se ve en la captura). Envuelve: "
                        + String.join(", ", wrapHints(missing)) + ". Para appBar: usa ForjaiBarId (es PreferredSizeWidget).");
            }
        }
        merged.forEach((p, c) -> {
            if (checkMain && (p.equals("lib/main.dart") || p.endsWith("/lib/main.dart")) && !CSharpComments.strip(c).contains("forjaiPreviewOr(")) {
                errors.add(p + " debe arrancar con runApp(forjaiPreviewOr(const MiApp())): así Forjai muestra las vistas previas.");
            }
        });
        return errors;
    }

    private static final Pattern REPEATED = Pattern.compile("(.*-)(\\d+)");

    /** Base de un elemento repetido en lista (task-item-1 -> task-item-), o null si el id no termina en -N. */
    static String repeatedBase(String id) {
        var m = REPEATED.matcher(id);
        return m.matches() ? m.group(1) : null;
    }

    /**
     * El código (sin comentarios) envuelve el data-id: ForjaiId literal o, si el id termina en -N (elemento repetido
     * en una lista), un id interpolado con la misma base ('base$i', 'base${i}', "base$i", 'base' + i).
     */
    static boolean wrapsId(String code, String id) {
        if (Pattern.compile("Forjai(?:Bar)?Id\\(\\s*['\"]" + Pattern.quote(id) + "['\"]").matcher(code).find()) {
            return true;
        }
        var base = repeatedBase(id);
        if (base == null) {
            return false;
        }
        var b = Pattern.quote(base);
        return Pattern.compile("Forjai(?:Bar)?Id\\(\\s*['\"]" + b + "\\$").matcher(code).find()
                || Pattern.compile("['\"]" + b + "['\"]\\s*\\+").matcher(code).find();
    }

    /** Cómo pedir cada id: literal, o agrupado por base para los elementos repetidos en listas. */
    public static List<String> wrapHints(java.util.Collection<String> ids) {
        var out = new ArrayList<String>();
        var groups = new LinkedHashMap<String, List<String>>();
        for (var id : ids) {
            var base = repeatedBase(id);
            if (base == null) {
                out.add("ForjaiId('" + id + "', child: …)");
            } else {
                groups.computeIfAbsent(base, k -> new ArrayList<>()).add(id);
            }
        }
        groups.forEach((base, list) -> out.add("ForjaiId('" + base + "$i', child: …) dentro del bucle de la lista (cubre "
                + String.join(", ", list) + ")"));
        return out;
    }

    private static void validate(List<String> errors, String path, String content, int number, String screen) {
        var cls = String.format("Pant%02dPreview", number);
        var code = CSharpComments.strip(content == null ? "" : content);
        if (!Pattern.compile("\\bclass\\s+" + cls + "\\b").matcher(code).find()) {
            errors.add(path + " debe declarar class " + cls + ".");
        }
        if (!Pattern.compile("Key\\(\\s*['\"]" + Pattern.quote(screen) + "['\"]\\s*\\)").matcher(code).find()
                && !Pattern.compile("Forjai(?:Bar)?Id\\(\\s*['\"]" + Pattern.quote(screen) + "['\"]").matcher(code).find()) {
            errors.add(path + " debe incluir Key('" + screen + "') en su vista previa.");
        }
    }
}
