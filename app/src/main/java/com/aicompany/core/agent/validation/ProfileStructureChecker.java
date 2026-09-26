package com.aicompany.core.agent.validation;

import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.StaticCheck;

import java.util.List;

/** Estructura del perfil (spec 2026-09-26 §1): archivos de entrada y ningún archivo fuera de la estructura DDD. */
public final class ProfileStructureChecker {

    private ProfileStructureChecker() {
    }

    public static List<StaticCheck> check(StackProfile profile, List<String> contexts, List<String> files) {

        var missing = profile.missingEntryFiles(files);
        var entry = missing.isEmpty()
                ? StaticCheck.pass("ENTRY_FILES", "Archivos de entrada de " + profile.name() + " presentes", null, List.of())
                : StaticCheck.fail("ENTRY_FILES", "Faltan archivos de entrada de " + profile.name() + ": " + missing,
                        null, missing);

        var outside = files.stream().filter(f -> !profile.isWithinStructure(f, contexts)).toList();
        var structure = outside.isEmpty()
                ? StaticCheck.pass("PROFILE_STRUCTURE", files.size() + " archivo(s) dentro de la estructura de "
                        + profile.name() + " para " + contexts, null, List.of())
                : StaticCheck.fail("PROFILE_STRUCTURE", "Archivos fuera de la estructura de " + profile.name()
                        + " (contextos " + contexts + "): " + outside, null, outside);

        return List.of(entry, structure);
    }
}
