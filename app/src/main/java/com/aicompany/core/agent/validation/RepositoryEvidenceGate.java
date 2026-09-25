package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.StaticReviewResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cada evidencia INTERNAL de la revisión estática debe citar un archivo
 * real de un commit real de ESTA misión (spec §7). Determinista, con
 * reintento en DevelopmentRuntime.
 */
@Component
public class RepositoryEvidenceGate {

    private static final Pattern CITATION = Pattern.compile("^workspace:([^@]+)@([0-9a-f]{40})/(.+)$");

    public static String citation(String missionId, String sha, String path) {
        return "workspace:" + missionId + "@" + sha + "/" + path;
    }

    public List<String> validate(StaticReviewResult review, String missionId, Map<String, Set<String>> filesBySha) {

        var errors = new ArrayList<String>();
        var internal = 0;

        var evidence = review == null || review.evidence() == null ? List.<com.aicompany.core.agent.model.AgentResult.Evidence>of() : review.evidence();

        for (var item : evidence.stream().filter(Objects::nonNull).toList()) {

            if (!"INTERNAL".equals(item.sourceType())) {
                continue;
            }

            internal++;

            var source = item.source() == null ? "" : item.source().strip();
            var matcher = CITATION.matcher(source);

            if (!matcher.matches()) {
                errors.add("La evidencia \"" + item.description() + "\" debe citar source=\"workspace:" + missionId
                        + "@<sha de 40 caracteres>/<ruta>\" (recibido: \"" + source + "\").");
                continue;
            }

            var citedMission = matcher.group(1);
            var sha = matcher.group(2);
            var path = matcher.group(3);

            if (!missionId.equals(citedMission)) {
                errors.add("La evidencia cita el workspace de " + citedMission + ", no el de " + missionId + ".");
            } else if (!filesBySha.containsKey(sha)) {
                errors.add("El sha " + sha + " no es un commit de esta misión. Commits válidos: " + filesBySha.keySet());
            } else if (!filesBySha.get(sha).contains(path)) {
                errors.add("El archivo \"" + path + "\" no existe en el commit " + sha + ".");
            }
        }

        if (internal == 0) {
            errors.add("La revisión debe citar al menos una evidencia sourceType=INTERNAL del repositorio de la misión.");
        }

        return errors;
    }
}
