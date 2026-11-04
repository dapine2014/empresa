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
 * lib/&lt;contexto&gt;/presentation/previews/pant_NN_preview.dart (class PantNNPreview) y lib/main.dart arranca con
 * forjaiPreviewOr(. Con reintento y el motivo.
 *
 * <p>Prueba en vivo 3: la vista previa es la pantalla real con datos de ejemplo, no una copia. La pantalla real es un
 * .dart de presentation/ fuera de previews/ con Key('PANT-NN') en su raíz; la vista previa instancia uno de sus widgets
 * y no arma su propio Scaffold. Los data-id de la maqueta se buscan en la pantalla real, no en la vista previa: si no,
 * el front corrige la copia y la app que usa el cliente sigue distinta.
 */
public final class PreviewGate {

    private static final Pattern PREVIEW_FILE = Pattern.compile("(?:.*/)?lib/.+/presentation/previews/pant_(\\d+)_preview\\.dart");
    private static final Pattern SCREEN = Pattern.compile("PANT-(\\d+)");
    private static final Pattern WIDGET_CLASS = Pattern.compile("\\bclass\\s+([A-Z]\\w*)\\s+extends\\s+(?:StatelessWidget|StatefulWidget)\\b");
    private static final Pattern SCAFFOLD = Pattern.compile("\\bScaffold\\s*\\(");

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
            checkRealScreen(errors, path.get(), merged, screen);
            var ids = idsByScreen == null ? Set.<String>of() : idsByScreen.getOrDefault(screen, Set.of());
            var missing = ids.stream().filter(id -> !id.equals(screen)).filter(id -> merged.entrySet().stream()
                    .noneMatch(e -> e.getKey().endsWith(".dart") && !PREVIEW_FILE.matcher(e.getKey()).matches()
                            && wrapsId(CSharpComments.strip(e.getValue()), id))).toList();
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

    private static final Pattern INDEX = Pattern.compile("-(\\d+)(?=-|$)");
    private static final Pattern SCREEN_PREFIX = Pattern.compile("^PANT-\\d+");

    /** Elemento repetido en una lista: lo que va antes y después del índice (task-item-1, PANT-02-item-1-check). */
    record Repeated(String prefix, String suffix) {
        String template() {
            return prefix + "$i" + suffix;
        }
    }

    /**
     * El último segmento numérico del id es el índice de la lista (prueba en vivo 5: también en el medio, como
     * PANT-02-item-1-check); el número de la pantalla (PANT-NN) nunca lo es. Null si no es un elemento repetido.
     */
    static Repeated repeated(String id) {
        var screen = SCREEN_PREFIX.matcher(id);
        var from = screen.find() ? screen.end() : 0;
        var m = INDEX.matcher(id);
        Repeated last = null;
        while (m.find()) {
            if (m.start() >= from) {
                last = new Repeated(id.substring(0, m.start() + 1), id.substring(m.end()));
            }
        }
        return last;
    }

    /** Si el id es un elemento repetido de una lista (con índice -N). */
    public static boolean isRepeated(String id) {
        return repeated(id) != null;
    }

    /**
     * El código (sin comentarios) envuelve el data-id: ForjaiId literal o, si es un elemento repetido de una lista,
     * un id interpolado con el mismo prefijo y sufijo ('base$i', 'base${i}-check', "base$i", 'base' + i + '-check').
     */
    static boolean wrapsId(String code, String id) {
        if (Pattern.compile("Forjai(?:Bar)?Id\\(\\s*['\"]" + Pattern.quote(id) + "['\"]").matcher(code).find()) {
            return true;
        }
        var r = repeated(id);
        if (r == null) {
            return false;
        }
        var p = Pattern.quote(r.prefix());
        var sfx = Pattern.quote(r.suffix());
        if (Pattern.compile("Forjai(?:Bar)?Id\\(\\s*['\"]" + p + "(?:\\$\\w+|\\$\\{[^}]+\\})" + sfx + "['\"]").matcher(code).find()) {
            return true;
        }
        var concat = r.suffix().isEmpty() ? "['\"]" + p + "['\"]\\s*\\+"
                : "['\"]" + p + "['\"]\\s*\\+[^,;]*?\\+\\s*['\"]" + sfx + "['\"]";
        return Pattern.compile(concat).matcher(code).find();
    }

    /** Cómo pedir cada id: literal, o agrupado por plantilla para los elementos repetidos en listas. */
    public static List<String> wrapHints(java.util.Collection<String> ids) {
        var out = new ArrayList<String>();
        var groups = new LinkedHashMap<String, List<String>>();
        for (var id : ids) {
            var r = repeated(id);
            if (r == null) {
                out.add("ForjaiId('" + id + "', child: …)");
            } else {
                groups.computeIfAbsent(r.template(), k -> new ArrayList<>()).add(id);
            }
        }
        groups.forEach((template, list) -> out.add("ForjaiId('" + template + "', child: …) dentro del bucle de la lista (cubre "
                + String.join(", ", list) + ")"));
        return out;
    }

    private static void validate(List<String> errors, String path, String content, int number, String screen) {
        var cls = String.format("Pant%02dPreview", number);
        var code = CSharpComments.strip(content == null ? "" : content);
        if (!Pattern.compile("\\bclass\\s+" + cls + "\\b").matcher(code).find()) {
            errors.add(path + " debe declarar class " + cls + ".");
        }
        if (SCAFFOLD.matcher(code).find()) {
            errors.add(path + " no puede armar su propio Scaffold: la vista previa muestra la pantalla real de " + screen
                    + " con datos de ejemplo, no una copia.");
        }
    }

    private static boolean hasRootKey(String code, String screen) {
        return Pattern.compile("Key\\(\\s*['\"]" + Pattern.quote(screen) + "['\"]\\s*\\)").matcher(code).find()
                || Pattern.compile("Forjai(?:Bar)?Id\\(\\s*['\"]" + Pattern.quote(screen) + "['\"]").matcher(code).find();
    }

    /** La pantalla real (presentation/ fuera de previews/, con Key('PANT-NN')) existe y la vista previa la instancia. */
    private static void checkRealScreen(List<String> errors, String previewPath, Map<String, String> merged, String screen) {
        var classes = new java.util.LinkedHashSet<String>();
        merged.forEach((p, c) -> {
            if (!p.endsWith(".dart") || !p.contains("/presentation/") || PREVIEW_FILE.matcher(p).matches()) {
                return;
            }
            var code = CSharpComments.strip(c);
            if (hasRootKey(code, screen)) {
                var m = WIDGET_CLASS.matcher(code);
                while (m.find()) {
                    classes.add(m.group(1));
                }
            }
        });
        if (classes.isEmpty()) {
            errors.add("La pantalla real de " + screen + " (un .dart de lib/<contexto>/presentation/, fuera de previews/) debe "
                    + "declarar su widget (extends StatelessWidget o StatefulWidget) con Key('" + screen + "') en su raíz.");
            return;
        }
        var preview = CSharpComments.strip(merged.getOrDefault(previewPath, ""));
        if (classes.stream().noneMatch(c -> Pattern.compile("\\b" + c + "\\s*\\(").matcher(preview).find())) {
            errors.add(previewPath + " debe mostrar la pantalla real de " + screen + ": instancia " + String.join(" o ", classes)
                    + "(…) con datos de ejemplo (si necesita casos de uso, pásale implementaciones de ejemplo de las interfaces "
                    + "de application definidas en la misma vista previa). No copies el layout.");
        }
    }
}
