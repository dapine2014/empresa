package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Spec 2026-11-01 front con diseño visual: con un diseño aprobado, la capa de presentación Flutter solo usa colores,
 * tipografías y tamaños del tema (AppTheme / Theme.of(context)). Rechaza los literales, con reintento y el motivo.
 * Los comentarios Dart no cuentan.
 */
public final class ThemeLiteralGate {

    private static final Pattern PRESENTATION = Pattern.compile("(?:.*/)?lib/.+/presentation/.+\\.dart");

    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();

    static {
        RULES.put("un color literal", Pattern.compile("\\bColor\\(\\s*0x[0-9a-fA-F]+\\s*\\)|\\bColor\\.from(?:ARGB|RGBO)\\([^)]*\\)"));
        RULES.put("un color de Colors", Pattern.compile("\\bColors\\.(?!transparent\\b)\\w+"));
        RULES.put("una tipografía literal", Pattern.compile("\\bfontFamily\\s*:"));
        RULES.put("un tamaño de letra literal en TextStyle", Pattern.compile("\\bfontSize\\s*:\\s*\\d[\\d.]*"));
    }

    private ThemeLiteralGate() {
    }

    public static List<String> check(List<GeneratedFile> files) {
        var errors = new ArrayList<String>();
        if (files == null) {
            return errors;
        }
        for (var file : files) {
            if (file == null || file.path() == null || file.content() == null) {
                continue;
            }
            var path = OwnedPaths.normalize(file.path());
            if (!PRESENTATION.matcher(path).matches()) {
                continue;
            }
            var code = CSharpComments.strip(file.content());
            for (var rule : RULES.entrySet()) {
                var m = rule.getValue().matcher(code);
                if (m.find()) {
                    errors.add(path + " usa " + rule.getKey() + " (" + m.group().strip() + "): con el diseño visual aprobado "
                            + "los colores, fuentes y tamaños salen solo de AppTheme o Theme.of(context); no escribas Color(0x…), "
                            + "Colors.* (salvo Colors.transparent), fontFamily: ni fontSize:/color: literales.");
                }
            }
        }
        return errors;
    }
}
