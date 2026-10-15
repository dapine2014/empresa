package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.model.StackProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Spec 2026-10-14 Kael §2.1 (Review Focus 5): game/forjai es de Forjai; game/playthroughs es de Vera. */
public final class ReservedGamePathsGate {

    private ReservedGamePathsGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> files, boolean isQa) {
        var errors = new ArrayList<String>();
        for (var f : files.stream().filter(Objects::nonNull).toList()) {
            var path = f.path() == null ? "" : OwnedPaths.normalize(f.path());
            if (under(StackProfile.FORJAI_GAME_ROOT, path)) {
                errors.add(path + " es del runner de Forjai: no lo escribas.");
            } else if (!isQa && under(StackProfile.PLAYTHROUGHS_ROOT, path)) {
                errors.add(path + " es una partida simulada de Vera (QA): no la escribas.");
            }
        }
        return errors;
    }

    /** La raíz reservada, en la raíz del workspace o bajo el prefijo de un componente (game-client/game/...). */
    private static boolean under(String root, String path) {
        if (OwnedPaths.covers(root, path)) {
            return true;
        }
        var at = path.indexOf("/" + root + "/");
        return at >= 0 || path.endsWith("/" + root);
    }
}
