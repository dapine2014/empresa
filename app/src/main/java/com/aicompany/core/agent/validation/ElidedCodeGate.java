package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Código omitido (spec 2026-10-01 §5): el modelo resume en vez de escribir ("...", "// resto del código",
 * "TODO: implementar", NotImplementedException). Verificado en vivo (MISSION-1790796941181): 72 errores de
 * compilación por esto. Solo mira archivos de código y solo líneas que son SOLO la elisión o un comentario,
 * para no confundir el spread de Dart, rangos de C# o textos con "...". Puro: nunca llama a un modelo.
 */
public final class ElidedCodeGate {

    private static final Set<String> CODE_EXTENSIONS =
            Set.of(".cs", ".dart", ".js", ".ts", ".tsx", ".jsx", ".py", ".java", ".gd", ".html", ".css");

    private static final Pattern ONLY_ELLIPSIS = Pattern.compile("^(\\.{3}|…)$");
    // Revisión final (m-6, m-7): un comentario que EMPIEZA con la elisión ("// ... otros métodos") es código
    // omitido; "#" solo es comentario en Python y GDScript (en C# es #region, en CSS un selector).
    private static final Pattern COMMENT_PREFIX = Pattern.compile("^(//+|/\\*+|\\*|<!--)\\s*(.*)$");
    private static final Pattern HASH_COMMENT_PREFIX = Pattern.compile("^(#)\\s*(.*)$");
    private static final List<String> SKIP_PHRASES = List.of(
            "resto del código", "resto del codigo", "el resto igual", "rest of the code", "rest of code",
            "todo: implement");
    private static final Pattern NOT_IMPLEMENTED = Pattern.compile("NotImplementedException\\s*\\(|UnimplementedError\\s*\\(");

    private ElidedCodeGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> files) {
        var errors = new ArrayList<String>();
        for (var file : files.stream().filter(Objects::nonNull).toList()) {
            var path = file.path() == null ? "" : file.path();
            if (!isCode(path) || file.content() == null) {
                continue;
            }
            var lines = file.content().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                var line = lines[i].strip();
                if (isElided(line, path) || (!isTest(path) && NOT_IMPLEMENTED.matcher(line).find())) {
                    errors.add("Código omitido en " + path + " (línea " + (i + 1) + ": \"" + abbreviate(line) + "\"). "
                            + "Escribe el archivo COMPLETO, sin \"...\", sin \"resto del código\" y sin métodos sin "
                            + "implementar: el sandbox compila exactamente lo que entregas.");
                    break;
                }
            }
        }
        return errors;
    }

    private static boolean isElided(String line, String path) {
        if (ONLY_ELLIPSIS.matcher(line).matches()) {
            return true;
        }
        var lower = path.toLowerCase(Locale.ROOT);
        var comment = COMMENT_PREFIX.matcher(line);
        var hash = HASH_COMMENT_PREFIX.matcher(line);
        String text;
        if (comment.matches()) {
            text = comment.group(2);
        } else if ((lower.endsWith(".py") || lower.endsWith(".gd")) && hash.matches()) {
            text = hash.group(2);
        } else {
            return false;
        }
        if (text.startsWith("...") || text.startsWith("…")) {
            return true;
        }
        var lowerText = text.toLowerCase(Locale.ROOT);
        return SKIP_PHRASES.stream().anyMatch(lowerText::contains);
    }

    private static boolean isCode(String path) {
        var lower = path.toLowerCase(Locale.ROOT);
        return CODE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static boolean isTest(String path) {
        return path.startsWith("test/") || path.startsWith("tests/") || path.contains(".Tests/")
                || path.endsWith("_test.dart") || path.endsWith("Tests.cs");
    }

    private static String abbreviate(String line) {
        return line.length() <= 80 ? line : line.substring(0, 80) + "…";
    }
}
