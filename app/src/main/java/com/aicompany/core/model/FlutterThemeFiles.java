package com.aicompany.core.model;

import com.aicompany.core.agent.model.VisualDesign;
import com.aicompany.core.agent.model.VisualSystem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Spec 2026-11-01 front con diseño visual: lo que Java escribe en el proyecto Flutter una vez aprobado el diseño.
 * {@code lib/theme/} y {@code lib/forjai/} son de Forjai (el front solo puede usar estos tokens); las fuentes .ttf las copia
 * el paso {@code restore} del sandbox desde la imagen (Java solo las declara en el pubspec).
 */
public final class FlutterThemeFiles {

    public static final String THEME_PATH = "lib/theme/app_theme.dart";
    public static final String PREVIEW_PATH = "lib/forjai/preview.dart";
    public static final String PREVIEWS_PATH = "lib/forjai/previews.dart";
    /**
     * Revisión final (C2): la captura del sandbox lee {@code flt-semantics-identifier}, que solo emite
     * {@code Semantics(identifier:)}; una Key sola no se ve. El front envuelve cada elemento de la maqueta con ForjaiId.
     */
    public static final String FORJAI_ID_PATH = "lib/forjai/forjai_id.dart";
    public static final List<String> RESERVED_ROOTS = List.of("lib/theme", "lib/forjai");
    /** Los SVG del diseño ({@code assets/svg}); la carpeta {@code assets} del componente es de Forjai con diseño aprobado. */
    public static final String ASSETS_ROOT = "assets";
    /** Versión exacta que resuelve sin red (precargada en la caché de pub de la imagen flutter-web-app). */
    public static final String FLUTTER_SVG_VERSION = "2.2.0";

    private static final Pattern PREVIEW_FILE = Pattern.compile("(?:.*/)?lib/(.+)/presentation/previews/pant_(\\d+)_preview\\.dart");

    private FlutterThemeFiles() {
    }

    /** Ruta de la vista previa de una pantalla (relativa a la raíz del componente), si {@code path} lo es. */
    public static boolean isPreviewFile(String path) {
        return path != null && PREVIEW_FILE.matcher(path).matches();
    }

    /** De las rutas dadas, las que son vistas previas (lib/&lt;ctx&gt;/presentation/previews/pant_NN_preview.dart), ordenadas. */
    public static List<String> previewPaths(java.util.Collection<String> paths) {
        return paths.stream().filter(FlutterThemeFiles::isPreviewFile).distinct().sorted().toList();
    }

    /** ruta → contenido, relativas a la raíz del proyecto Flutter. {@code previewPaths}: vistas previas que ya existen. */
    public static Map<String, String> files(VisualDesign d, List<String> previewPaths) {
        var files = new LinkedHashMap<String, String>();
        files.put(THEME_PATH, theme(d.system()));
        files.put(PREVIEW_PATH, PREVIEW);
        files.put(PREVIEWS_PATH, previews(previewPaths));
        files.put(FORJAI_ID_PATH, FORJAI_ID);
        for (var svg : d.mockups().svgs()) {
            files.put("assets/svg/" + svg.name() + ".svg", svg.svg());
        }
        return files;
    }

    // ---- tema ----

    static String theme(VisualSystem vs) {
        var out = new StringBuilder("// GENERADO POR FORJAI desde el diseño visual aprobado: no lo edites.\n")
                .append("import 'package:flutter/material.dart';\n\nclass AppTheme {\n  AppTheme._();\n\n");
        for (var c : vs.colorsOrEmpty()) {
            out.append("  static const Color ").append(ident(c.name())).append(" = Color(0xFF")
                    .append(c.hex().replace("#", "").toUpperCase(Locale.ROOT)).append(");\n");
        }
        var family = vs.typography() == null ? null : vs.typography().fontFamily();
        out.append("\n  static const String fontFamily = '").append(family == null ? "" : family.replace("'", "")).append("';\n");
        vs.spacing().forEach((k, v) -> out.append("  static const double space").append(cap(ident(k))).append(" = ").append(num(v)).append(";\n"));
        vs.radii().forEach((k, v) -> out.append("  static const double radius").append(cap(ident(k))).append(" = ").append(num(v)).append(";\n"));
        out.append('\n');
        var sizes = vs.typography() == null ? Map.<String, Double>of() : vs.typography().sizes();
        sizes.forEach((k, v) -> out.append("  static const TextStyle ").append(ident(k)).append(" = TextStyle(fontFamily: fontFamily, fontSize: ")
                .append(num(v)).append(k.equals("display") || k.equals("title") ? ", fontWeight: FontWeight.w700" : "").append(");\n"));
        out.append("\n  static ThemeData get theme => ThemeData(\n")
                .append("        useMaterial3: true,\n        fontFamily: fontFamily,\n")
                .append("        colorScheme: const ColorScheme.light(\n")
                .append("          primary: primary,\n          onPrimary: onPrimary,\n          surface: surface,\n")
                .append("          onSurface: onSurface,\n          error: error,\n          onError: onError,\n        ),\n")
                .append("        scaffoldBackgroundColor: surface,\n      );\n}\n");
        return out.toString();
    }

    private static String ident(String name) {
        var parts = name == null ? new String[0] : name.split("[^A-Za-z0-9]+");
        var sb = new StringBuilder();
        for (var p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            sb.append(sb.length() == 0 ? p.substring(0, 1).toLowerCase(Locale.ROOT) + p.substring(1) : cap(p));
        }
        var s = sb.toString();
        return s.isEmpty() || Character.isDigit(s.charAt(0)) ? "t" + cap(s) : s;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    private static String num(double v) {
        return v == Math.rint(v) ? String.format(Locale.ROOT, "%.1f", v) : String.valueOf(v);
    }

    // ---- vistas previas ----

    private static final String PREVIEW = """
            // GENERADO POR FORJAI: abre una vista previa con ?preview=PANT-NN (la captura del sandbox); no lo edites.
            import 'package:flutter/material.dart';
            import 'package:flutter/semantics.dart';
            import '../theme/app_theme.dart';
            import 'previews.dart';

            /// Con ?preview=PANT-NN devuelve la vista previa registrada de esa pantalla; si no, la app.
            Widget forjaiPreviewOr(Widget app) {
              final id = Uri.base.queryParameters['preview'];
              final builder = id == null ? null : forjaiPreviews[id];
              if (id == null || builder == null) {
                return app;
              }
              WidgetsFlutterBinding.ensureInitialized();
              SemanticsBinding.instance.ensureSemantics();
              return MaterialApp(
                debugShowCheckedModeBanner: false,
                theme: AppTheme.theme,
                home: Semantics(identifier: id, container: true, child: builder()),
              );
            }
            """;

    private static final String FORJAI_ID = """
            // GENERADO POR FORJAI: envuelve cada elemento de la maqueta (su data-id); no lo edites.
            // La captura de diseño lee Semantics(identifier:) y la Key sirve a los tests de widgets.
            import 'package:flutter/widgets.dart';

            class ForjaiId extends StatelessWidget {
              const ForjaiId(this.id, {super.key, required this.child});

              final String id;
              final Widget child;

              @override
              Widget build(BuildContext context) =>
                  Semantics(identifier: id, container: true, child: KeyedSubtree(key: Key(id), child: child));
            }
            """;

    static String previews(List<String> previewPaths) {
        var imports = new StringBuilder();
        var entries = new StringBuilder();
        for (var path : previewPaths) {
            var m = PREVIEW_FILE.matcher(path);
            if (!m.matches()) {
                continue;
            }
            var lib = path.substring(path.indexOf("lib/") + 4);
            imports.append("import '../").append(lib).append("';\n");
            entries.append("  'PANT-").append(m.group(2)).append("': () => const Pant").append(m.group(2)).append("Preview(),\n");
        }
        return "// GENERADO POR FORJAI: pantalla → vista previa (lib/<ctx>/presentation/previews/pant_NN_preview.dart).\n"
                + "import 'package:flutter/widgets.dart';\n" + imports
                + "\nfinal Map<String, Widget Function()> forjaiPreviews = {\n" + entries + "};\n";
    }

    // ---- pubspec ----

    /** Mezcla (idempotente) flutter_svg, las fuentes del sistema visual y assets/svg/ en un pubspec escrito por el front. */
    public static String mergePubspec(String pubspec, VisualDesign d) {
        var lines = new ArrayList<>(List.of(pubspec.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        if (!pubspec.contains("\n  flutter_svg:")) {
            insertUnder(lines, "dependencies:", List.of("  flutter_svg: " + FLUTTER_SVG_VERSION));
        }
        var family = d.system().typography() == null ? null : d.system().typography().fontFamily();
        var fonts = family == null ? List.<String>of() : FontCatalog.files(family);
        var block = new ArrayList<String>();
        if (!fonts.isEmpty() && !pubspec.contains("assets/fonts/" + base(fonts.get(0)))) {
            block.add("    - family: " + family);
            block.add("      fonts:");
            for (var f : fonts) {
                block.add("        - asset: assets/fonts/" + base(f));
                if (f.endsWith("-Bold.ttf")) {
                    block.add("          weight: 700");
                }
            }
        }
        var assets = d.mockups().svgs().isEmpty() || pubspec.contains("assets/svg/")
                ? List.<String>of() : List.of("    - assets/svg/");
        if (!block.isEmpty() || !assets.isEmpty()) {
            if (lines.stream().noneMatch(l -> l.equals("flutter:"))) {
                lines.add("");
                lines.add("flutter:");
            }
            if (!block.isEmpty()) {
                insertUnder(lines, "  fonts:", block, "flutter:");
            }
            if (!assets.isEmpty()) {
                insertUnder(lines, "  assets:", assets, "flutter:");
            }
        }
        return String.join("\n", lines) + "\n";
    }

    private static String base(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static void insertUnder(List<String> lines, String key, List<String> add) {
        insertUnder(lines, key, add, null);
    }

    /** Inserta tras la línea {@code key}; si no existe, la crea al final de la sección {@code parent} (o del archivo). */
    private static void insertUnder(List<String> lines, String key, List<String> add, String parent) {
        var at = lines.indexOf(key);
        if (at >= 0) {
            lines.addAll(at + 1, add);
            return;
        }
        var end = lines.size();
        if (parent != null) {
            var start = lines.indexOf(parent);
            end = start + 1;
            while (end < lines.size() && (lines.get(end).isBlank() || lines.get(end).startsWith(" ") || lines.get(end).startsWith("#"))) {
                end++;
            }
            while (end > start + 1 && lines.get(end - 1).isBlank()) {
                end--;
            }
        } else {
            lines.add("");
            end = lines.size();
        }
        var section = new ArrayList<String>();
        section.add(key);
        section.addAll(add);
        lines.addAll(end, section);
    }

    // ---- rutas reservadas ----

    /** lib/theme y lib/forjai son de Forjai: motivo por cada archivo del agente que cae ahí (en la raíz o bajo un componente). */
    public static List<String> reservedErrors(List<com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile> files) {
        var errors = new ArrayList<String>();
        for (var f : files) {
            if (f == null || f.path() == null) {
                continue;
            }
            var path = com.aicompany.core.agent.validation.OwnedPaths.normalize(f.path());
            for (var root : RESERVED_ROOTS) {
                if (path.equals(root) || path.startsWith(root + "/") || path.contains("/" + root + "/")) {
                    errors.add(path + " es de Forjai (tema y vistas previas generados del diseño visual aprobado): no lo escribas; "
                            + "usa los tokens de AppTheme.");
                }
            }
        }
        return errors;
    }
}
