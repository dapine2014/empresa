package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** El código solo usa el contrato de variables; hay migraciones solo si el plan declaró base (spec §2.4). */
public final class DatabaseContractGate {

    private static final Pattern DB_VARIABLE = Pattern.compile("\\bDB_[A-Z0-9_]+_(HOST|PORT|NAME|USER|PASSWORD|SSLMODE)\\b");

    private DatabaseContractGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> files, boolean planHasDatabase,
                                     List<String> allowedVariables) {
        var errors = new ArrayList<String>();
        for (var file : files.stream().filter(Objects::nonNull).toList()) {
            if (file.path() != null && file.path().startsWith(MigrationFilesGate.ROOT) && !planHasDatabase) {
                errors.add("Hay migraciones (" + file.path() + ") pero el plan no declaró base de datos.");
            }
            var m = DB_VARIABLE.matcher(file.content() == null ? "" : file.content());
            while (m.find()) {
                if (!allowedVariables.contains(m.group())) {
                    errors.add("La variable " + m.group() + " en " + file.path() + " no es del contrato: usa "
                            + allowedVariables + ".");
                }
            }
        }
        return errors;
    }
}
