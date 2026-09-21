package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Gate de seguridad obligatorio antes de escribir cualquier archivo a
 * disco. Este gate en sí no reintenta nada — es una función pura de
 * validación, sin estado ni conocimiento de intentos anteriores; es
 * {@code DevelopmentRuntime} quien decide reintentar (hasta
 * {@code MAX_RESULT_RETRIES + 1} veces) con el feedback determinista de
 * {@link #validate}. A diferencia de {@link EvidenceValidationGate} (cuyo
 * llamador, {@code AgentRuntime}, no reintenta tras su rechazo), una ruta
 * de archivo inválida SÍ es corregible con feedback: una evidencia
 * inventada no lo es (no hay forma de que un reintento "invente mejor"),
 * pero una ruta absoluta o con path traversal sí — el modelo puede
 * corregirla con la instrucción exacta de qué estuvo mal. Ver
 * docs/superpowers/specs/2026-09-21-development-generation-design.md,
 * decisión 5, y docs/HISTORY.md para la discusión completa de esta
 * asimetría.
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

            if (segment.equals(".git")) {
                // git ignora cualquier entrada bajo un segmento ".git" al
                // recorrer el árbol de trabajo — un archivo generado ahí
                // se escribiría a disco pero nunca se commitearía,
                // perdiéndose en silencio.
                errors.add("Ruta con segmento \".git\" no permitida: \"" + path + "\"");
                return;
            }
        }
    }

    public record ValidationResult(boolean valid, List<String> errors) {
    }
}
