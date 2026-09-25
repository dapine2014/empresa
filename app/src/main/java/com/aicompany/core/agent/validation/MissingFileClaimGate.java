package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.StaticReviewResult;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * La revisión estática no puede declarar faltante un archivo que SÍ está en
 * el repo de la misión (verificado en vivo con MISSION-TEAM-VERIFY-3: Vera
 * marcó 6 BLOCKER afirmando que src/backend/*.js "no existen" aunque
 * FILES_IN_COMMIT dio PASS). Determinista, con reintento en
 * DevelopmentRuntime: el repo real manda sobre la redacción del modelo.
 * La detección de "afirma inexistencia" en findings es léxica (mismo
 * espíritu que ForbiddenClaimsGuard) y puede tener falsos negativos.
 */
@Component
public class MissingFileClaimGate {

    private static final Pattern NON_EXISTENCE = Pattern.compile(
            "\\bno (existe|existen|esta presente|estan presentes|se encuentra|se encuentran|fue incluido|fueron incluidos)\\b"
                    + "|\\binexistente(s)?\\b|\\bfalta(n)? (el|los) archivo(s)?\\b|\\bausente(s)?\\b");

    public List<String> validate(StaticReviewResult review, Set<String> repositoryFiles) {

        var errors = new ArrayList<String>();

        if (review == null) {
            return errors;
        }

        var wronglyMissing = new LinkedHashSet<String>();

        if (review.missingFiles() != null) {
            review.missingFiles().stream()
                    .filter(Objects::nonNull)
                    .map(OwnedPaths::normalize)
                    .filter(repositoryFiles::contains)
                    .forEach(wronglyMissing::add);
        }

        for (var path : wronglyMissing) {
            errors.add("El archivo \"" + path + "\" sí existe en el repositorio de la misión: no lo declares en "
                    + "missingFiles. Los chequeos deterministas ya confirmaron qué archivos existen.");
        }

        for (var finding : review.findingsOrEmpty()) {

            if (finding == null || finding.description() == null) {
                continue;
            }

            if (!NON_EXISTENCE.matcher(normalize(finding.description())).find()) {
                continue;
            }

            var mentioned = new LinkedHashSet<String>();

            if (finding.path() != null && repositoryFiles.contains(OwnedPaths.normalize(finding.path()))) {
                mentioned.add(OwnedPaths.normalize(finding.path()));
            }

            repositoryFiles.stream()
                    .filter(file -> finding.description().contains(file))
                    .forEach(mentioned::add);

            if (!mentioned.isEmpty()) {
                errors.add("El finding \"" + finding.description() + "\" afirma que no existe(n) " + mentioned
                        + ", pero sí existe(n) en el repositorio de la misión. Revisa el contenido real de esos "
                        + "archivos en vez de declararlos inexistentes.");
            }
        }

        return errors;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
