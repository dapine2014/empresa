package com.aicompany.core.database;

import java.util.regex.Pattern;

/**
 * Seguimiento Diego ambientes (privacidad): el mensaje principal de PostgreSQL puede traer valores de las filas del
 * fundador ({@code invalid input syntax for type integer: "x@y.com"}, {@code Key (email)=(x@y.com)}). Antes de que un
 * error salga del aplicador (tarea, misión, correcciones, búsquedas, eventos, chat, API) el contenido entre comillas
 * se reemplaza por {@code <valor>}, salvo los identificadores simples en minúscula ({@code [a-z_][a-z0-9_]*}), que
 * siguen legibles ({@code relation "clientes" does not exist}).
 */
final class DatabaseErrorScrubber {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final Pattern KEY_VALUES = Pattern.compile("(Key \\([^)]*\\)=)\\(.*\\)");
    private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"([^\"\\n]*)\"");
    private static final Pattern SINGLE_QUOTED = Pattern.compile("'([^'\\n]*)'");

    private DatabaseErrorScrubber() {
    }

    static String scrub(String message) {
        if (message == null) {
            return null;
        }
        var text = KEY_VALUES.matcher(message).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(1) + "(<valor>)"));
        text = mask(DOUBLE_QUOTED, text, '"');
        return mask(SINGLE_QUOTED, text, '\'');
    }

    private static String mask(Pattern pattern, String text, char quote) {
        return pattern.matcher(text).replaceAll(m -> {
            var inner = m.group(1);
            var keep = IDENTIFIER.matcher(inner).matches() || "<valor>".equals(inner) || "<host>".equals(inner)
                    || "<usuario>".equals(inner);
            return java.util.regex.Matcher.quoteReplacement(keep ? m.group() : quote + "<valor>" + quote);
        });
    }
}
