package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AgentResultValidator {

    private static final Logger log =
            LoggerFactory.getLogger(AgentResultValidator.class);

    public ValidationResult validate(AgentResult result) {

        var errors = new ArrayList<String>();

        if (result == null) {
            errors.add("AgentResult es null");
            return invalid(errors);
        }

        validateRequiredFields(result, errors);
        validateConfidence(result, errors);
        validateCalculations(result, errors);
        validateEvidence(result, errors);
        validateCustomerCandidates(result, errors);
        validateVerificationStatus(result, errors);

        if (!errors.isEmpty()) {

            log.warn(
                    "AGENT_RESULT_INVALID agent={} action={} errors={}",
                    result.agent(),
                    result.action(),
                    errors
            );

            return invalid(errors);
        }

        return new ValidationResult(
                true,
                List.of()
        );
    }

    private void validateRequiredFields(
            AgentResult result,
            List<String> errors) {

        if (isBlank(result.agent())) {
            errors.add("agent es obligatorio");
        }

        if (isBlank(result.action())) {
            errors.add("action es obligatorio");
        }

        if (isBlank(result.verificationStatus())) {
            errors.add("verificationStatus es obligatorio");
        }

        if (isBlank(result.recommendation())) {
            errors.add("recommendation es obligatorio");
        }

        if (result.facts() == null) {
            errors.add("facts no puede ser null");
        }

        if (result.hypotheses() == null) {
            errors.add("hypotheses no puede ser null");
        }

        if (result.estimates() == null) {
            errors.add("estimates no puede ser null");
        }

        if (result.evidence() == null) {
            errors.add("evidence no puede ser null");
        }

        if (result.evidenceRequired() == null) {
            errors.add("evidenceRequired no puede ser null");
        }

        if (result.calculations() == null) {
            errors.add("calculations no puede ser null");
        }

        if (result.risks() == null) {
            errors.add("risks no puede ser null");
        }
    }

    private void validateConfidence(
            AgentResult result,
            List<String> errors) {

        if (result.confidence() < 0.0
                || result.confidence() > 1.0) {

            errors.add(
                    "confidence debe estar entre 0 y 1"
            );
        }
    }

    private void validateCalculations(
            AgentResult result,
            List<String> errors) {

        if (result.calculations() == null) {
            return;
        }

        for (var calculation : result.calculations()) {

            if (calculation == null) {
                errors.add("Existe una calculation null");
                continue;
            }

            if (isBlank(calculation.name())) {
                errors.add(
                        "Calculation sin nombre"
                );
            }

            if (isBlank(calculation.operation())) {
                errors.add(
                        "Calculation sin operation"
                );
                continue;
            }

            double expected;

            switch (calculation.operation().toUpperCase()) {

                case "ADD":
                    expected =
                            calculation.inputA()
                                    + calculation.inputB();
                    break;

                case "SUBTRACT":
                    expected =
                            calculation.inputA()
                                    - calculation.inputB();
                    break;

                default:
                    errors.add(
                            "Operación matemática inválida: "
                                    + calculation.operation()
                    );
                    continue;
            }

            if (!approximatelyEqual(
                    expected,
                    calculation.result())) {

                errors.add(
                        "Cálculo inconsistente: "
                                + calculation.name()
                                + " expected="
                                + expected
                                + " actual="
                                + calculation.result()
                );
            }
        }
    }

    private void validateEvidence(
            AgentResult result,
            List<String> errors) {

        if (result.evidence() == null) {
            return;
        }

        for (var evidence : result.evidence()) {

            if (evidence == null) {
                errors.add("Existe evidencia null");
                continue;
            }

            if (isBlank(evidence.description())) {
                errors.add(
                        "Evidencia sin descripción"
                );
            }

            if (isBlank(evidence.sourceType())) {
                errors.add(
                        "Evidencia sin sourceType"
                );
            }

            if (evidence.verified()
                    && isBlank(evidence.source())) {

                errors.add(
                        "Una evidencia verificada debe tener source"
                );
            }
        }
    }

    /**
     * {@code AgentResultSchema.CUSTOMER_CANDIDATE_ITEM_SCHEMA} ya declara
     * {@code confidence} con {@code minimum}/{@code maximum}, pero eso
     * solo restringe la gramática de generación de Ollama -- no es una
     * garantía dura (mismo criterio que ya motivó {@link #validateConfidence}
     * para el {@code confidence} de nivel superior, ver el incidente real
     * documentado en {@code CLAUDE.md}: "confidence=75" fuera de [0,1]
     * colado por el modelo pese a la restricción del schema).
     */
    private void validateCustomerCandidates(
            AgentResult result,
            List<String> errors) {

        if (result.customerCandidates() == null) {
            return;
        }

        for (var candidate : result.customerCandidates()) {

            if (candidate == null) {
                errors.add("Existe un customerCandidate null");
                continue;
            }

            if (candidate.confidence() < 0.0
                    || candidate.confidence() > 1.0) {

                errors.add(
                        "customerCandidate confidence debe estar entre 0 y 1: "
                                + candidate.name()
                );
            }
        }
    }

    private void validateVerificationStatus(
            AgentResult result,
            List<String> errors) {

        var status = result.verificationStatus();

        if (!List.of(
                "NOT_VALIDATED",
                "PARTIALLY_VALIDATED",
                "VALIDATED"
        ).contains(status)) {

            errors.add(
                    "verificationStatus inválido: "
                            + status
            );

            return;
        }

        if ("VALIDATED".equals(status)) {

            boolean hasVerifiedEvidence =
                    result.evidence() != null
                            && result.evidence()
                            .stream()
                            .anyMatch(
                                    evidence ->
                                            evidence != null
                                                    && evidence.verified()
                                                    && !isBlank(
                                                    evidence.source()
                                            )
                            );

            if (!hasVerifiedEvidence) {

                errors.add(
                        "VALIDATED requiere al menos " +
                        "una evidencia verificada con source"
                );
            }
        }
    }

    private boolean approximatelyEqual(
            double expected,
            double actual) {

        return Math.abs(expected - actual) < 0.000001;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ValidationResult invalid(
            List<String> errors) {

        return new ValidationResult(
                false,
                List.copyOf(errors)
        );
    }

    public record ValidationResult(
            boolean valid,
            List<String> errors
    ) {
    }
}
