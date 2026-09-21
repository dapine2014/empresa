package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Gate de seguridad obligatorio antes de escribir cualquier archivo a
 * disco — mismo espíritu que {@link EvidenceValidationGate}: rechaza de
 * inmediato, **sin reintento** (una ruta insegura no es un error de forma
 * que el modelo pueda corregir con feedback útil). Ver
 * docs/superpowers/specs/2026-09-21-development-generation-design.md,
 * decisión 5.
 */
@Component
public class DevelopmentPathValidationGate {

    private static final Logger log =
            LoggerFactory.getLogger(DevelopmentPathValidationGate.class);

    public ValidationResult validate(DevelopmentResult result) {

        if (result == null || result.files() == null) {
            return new ValidationResult(true, List.of());
        }

        var errors = new ArrayList<String>();

        for (var file : result.files()) {

            if (file == null) {
                continue;
            }

            validatePath(file.path(), errors);
        }

        if (!errors.isEmpty()) {

            log.warn(
                    "DEVELOPMENT_PATH_INVALID errors={}",
                    errors
            );

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
        }
    }

    public record ValidationResult(boolean valid, List<String> errors) {
    }
}
