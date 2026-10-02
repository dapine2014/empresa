package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Ningún secreto en el código (spec 2026-10-02 §2.4): la app lee solo las variables del contrato. */
public final class SecretLiteralGate {

    // Password=<valor literal> como en una cadena de conexión: sin espacios alrededor del "=" (así no se confunde con
    // "var pwd = Environment.GetEnvironmentVariable(...)") y sin interpolación {…}.
    private static final Pattern PASSWORD_LITERAL = Pattern.compile("(?i)\\b(password|pwd)=(?![{\"$;])[^;\"\\s{}]+");
    private static final Pattern URI_WITH_CREDENTIALS = Pattern.compile("(?i)postgres(ql)?://[^:/@\\s]+:[^@\\s]+@");

    private SecretLiteralGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> files, List<String> forbiddenLiterals) {
        var errors = new ArrayList<String>();
        for (var file : files.stream().filter(Objects::nonNull).toList()) {
            var content = file.content() == null ? "" : file.content();
            var found = PASSWORD_LITERAL.matcher(content).find() || URI_WITH_CREDENTIALS.matcher(content).find()
                    || forbiddenLiterals.stream().anyMatch(l -> l != null && !l.isBlank() && content.contains(l));
            if (found) {
                errors.add("Secreto o dato de conexión real en " + file.path() + ": la app lee la conexión SOLO de las "
                        + "variables de entorno del contrato (DB_<NOMBRE>_*); no escribas claves, hosts ni cadenas de conexión.");
            }
        }
        return errors;
    }
}
