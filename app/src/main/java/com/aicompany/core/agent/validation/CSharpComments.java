package com.aicompany.core.agent.validation;

import java.util.regex.Pattern;

/** Quita comentarios de C# (// y /* *​/) para que un gate no marque texto que el compilador ignora. */
final class CSharpComments {

    // (?<!:) conserva las URLs y las rutas res:// de una cadena.
    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/|(?<!:)//[^\\n]*", Pattern.DOTALL);

    private CSharpComments() {
    }

    static String strip(String content) {
        return content == null ? "" : COMMENT.matcher(content).replaceAll(" ");
    }
}
