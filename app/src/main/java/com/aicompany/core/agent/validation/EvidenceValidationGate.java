package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Validación *semántica* de evidencia, separada deliberadamente de
 * {@link AgentResultValidator} (que es puramente sintáctica: campos no
 * nulos, rangos, cálculos consistentes). Este gate no verifica que la
 * evidencia sea sintácticamente completa — verifica que lo que el agente
 * declara como "verificado" sea, al menos superficialmente, creíble.
 *
 * "El agente afirma X" no es lo mismo que "la empresa puede demostrar X":
 * un {@code verified=true} solo tiene sentido si viene acompañado de un
 * {@code sourceType} real y, para evidencia WEB, de algo que al menos
 * tenga forma de URL.
 */
@Component
public class EvidenceValidationGate {

    private static final Logger log =
            LoggerFactory.getLogger(EvidenceValidationGate.class);

    private static final Set<String> VALID_SOURCE_TYPES = Set.of(
            "WEB", "CUSTOMER", "TRANSACTION", "INTERNAL", "NONE"
    );

    public ValidationResult validate(AgentResult result) {

        if (result == null) {
            return new ValidationResult(true, List.of());
        }

        var validation = validate(result.evidence());

        if (!validation.valid()) {

            log.warn(
                    "EVIDENCE_SEMANTIC_INVALID agent={} action={} errors={}",
                    result.agent(),
                    result.action(),
                    validation.errors()
            );
        }

        return validation;
    }

    /**
     * Misma validación semántica, pero sobre evidencia que no viene de un
     * {@link AgentResult} — p. ej. evidencia real que un humano registra
     * directamente al validar un cliente o una venta (ver
     * {@code CustomerService}). El origen de la evidencia no cambia el
     * criterio: "verified=true" exige la misma credibilidad mínima venga
     * de un agente o de una persona.
     */
    public ValidationResult validate(
            List<AgentResult.Evidence> evidenceList) {

        var errors = new ArrayList<String>();

        if (evidenceList == null) {
            return new ValidationResult(true, List.of());
        }

        for (var evidence : evidenceList) {

            if (evidence == null) {
                continue;
            }

            validateSourceType(evidence, errors);
            validateNoneCannotBeVerified(evidence, errors);
            validateWebSourceLooksLikeUrl(evidence, errors);
        }

        if (!errors.isEmpty()) {
            return new ValidationResult(false, List.copyOf(errors));
        }

        return new ValidationResult(true, List.of());
    }

    private void validateSourceType(
            AgentResult.Evidence evidence,
            List<String> errors) {

        var sourceType = evidence.sourceType();

        if (sourceType == null
                || !VALID_SOURCE_TYPES.contains(sourceType.toUpperCase(Locale.ROOT))) {

            errors.add(
                    "sourceType desconocido: '"
                            + sourceType
                            + "' (debe ser WEB, CUSTOMER, TRANSACTION, "
                            + "INTERNAL o NONE) en evidencia \""
                            + evidence.description()
                            + "\""
            );
        }
    }

    private void validateNoneCannotBeVerified(
            AgentResult.Evidence evidence,
            List<String> errors) {

        if (evidence.verified()
                && "NONE".equalsIgnoreCase(evidence.sourceType())) {

            errors.add(
                    "Evidencia marcada verified=true con sourceType=NONE "
                            + "— no puede estar verificada sin una fuente "
                            + "real: \""
                            + evidence.description()
                            + "\""
            );
        }
    }

    private void validateWebSourceLooksLikeUrl(
            AgentResult.Evidence evidence,
            List<String> errors) {

        if (!evidence.verified()
                || !"WEB".equalsIgnoreCase(evidence.sourceType())) {
            return;
        }

        var source = evidence.source();

        var looksLikeUrl =
                source != null
                        && (source.startsWith("http://")
                        || source.startsWith("https://"));

        if (!looksLikeUrl) {

            errors.add(
                    "Evidencia WEB marcada verified=true pero source no "
                            + "parece una URL real: \""
                            + source
                            + "\""
            );
        }
    }

    public record ValidationResult(
            boolean valid,
            List<String> errors
    ) {
    }
}
