package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Gate de seguridad antes de escribir cualquier archivo a disco — mismo
 * espíritu que {@link EvidenceValidationGate}: rechaza de inmediato, SIN
 * reintento (spec §6). Las rutas fuera de los ownedPaths del agente NO son
 * responsabilidad de este gate (son corregibles y se reintentan en
 * {@code DevelopmentRuntime}).
 */
@Component
public class DevelopmentPathValidationGate {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentPathValidationGate.class);

    public ValidationResult validate(DevelopmentResult result) {

        if (result == null || result.files() == null) {
            return new ValidationResult(true, List.of());
        }

        var errors = new ArrayList<String>();

        for (var file : result.files()) {
            if (file != null) {
                validatePath(file.path(), errors);
            }
        }

        if (!errors.isEmpty()) {
            log.warn("DEVELOPMENT_PATH_INVALID errors={}", errors);
            return new ValidationResult(false, List.copyOf(errors));
        }

        return new ValidationResult(true, List.of());
    }

    private void validatePath(String path, List<String> errors) {

        if (path == null || path.isBlank()) {
            errors.add("Ruta de archivo vacía.");
            return;
        }

        if (path.startsWith("/") || path.matches("^[a-zA-Z]:.*")) {
            errors.add("Ruta absoluta no permitida: \"" + path + "\"");
            return;
        }

        for (var segment : path.split("[/\\\\]")) {

            if (segment.equals("..")) {
                errors.add("Ruta con path traversal no permitida: \"" + path + "\"");
                return;
            }

            if (segment.equals(".git")) {
                errors.add("Ruta dentro de .git no permitida: \"" + path + "\"");
                return;
            }
        }
    }

    public record ValidationResult(boolean valid, List<String> errors) {
    }
}
