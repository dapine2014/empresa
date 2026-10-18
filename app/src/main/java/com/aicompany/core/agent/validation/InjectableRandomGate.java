package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Spec 2026-10-14 Kael §2.2: azar con semilla inyectable en las reglas (la partida simulada debe ser determinista). */
public final class InjectableRandomGate {

    private static final Pattern RANDOM = Pattern.compile("new\\s+Random(NumberGenerator)?\\s*\\(|Random\\.Shared|GD\\.Rand(i|f|fn|Range)\\b");

    private InjectableRandomGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> files, List<String> rulesRoots) {
        var errors = new ArrayList<String>();
        for (var f : files.stream().filter(Objects::nonNull).toList()) {
            if (f.path() == null || rulesRoots.stream().noneMatch(r -> OwnedPaths.covers(r, f.path()))) {
                continue;
            }
            var m = RANDOM.matcher(CSharpComments.strip(f.content()));
            if (m.find()) {
                errors.add(f.path() + " usa \"" + m.group().strip() + "\": en domain/application el azar entra como "
                        + "dependencia (una interfaz con semilla, p. ej. IRandom), nunca se crea ahí.");
            }
        }
        return errors;
    }
}
