package com.aicompany.core.agent.model;

import java.util.List;
import java.util.Map;

/**
 * Diagnóstico 360 de los tests que fallan (decisión del fundador 2026-10-03): Neo, el líder técnico, revisa a la vez
 * el test, la lógica y lo pedido, y dice quién está mal. Vera no juzga sus propios tests. Java valida el veredicto
 * (TestTriageValidator) y reparte las correcciones.
 */
public record TestTriage(List<Verdict> verdicts) {

    public static final String CODE_WRONG = "CODE_WRONG";
    public static final String TEST_WRONG = "TEST_WRONG";
    public static final String BOTH = "BOTH";

    /** rule: la regla de negocio correcta según lo pedido; codeFiles/testFiles: qué hay que corregir. */
    public record Verdict(String test, String verdict, String rule, String reason, List<String> codeFiles,
                          List<String> testFiles) {

        public List<String> codeFilesOrEmpty() {
            return codeFiles == null ? List.of() : codeFiles;
        }

        public List<String> testFilesOrEmpty() {
            return testFiles == null ? List.of() : testFiles;
        }

        public boolean codeWrong() {
            return CODE_WRONG.equals(verdict) || BOTH.equals(verdict);
        }

        public boolean testWrong() {
            return TEST_WRONG.equals(verdict) || BOTH.equals(verdict);
        }
    }

    public List<Verdict> verdictsOrEmpty() {
        return verdicts == null ? List.of() : verdicts;
    }

    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("verdicts", Map.of("type", "array", "minItems", 1, "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "test", Map.of("type", "string"),
                            "verdict", Map.of("type", "string", "enum", List.of(CODE_WRONG, TEST_WRONG, BOTH)),
                            "rule", Map.of("type", "string"),
                            "reason", Map.of("type", "string"),
                            "codeFiles", Map.of("type", "array", "items", Map.of("type", "string")),
                            "testFiles", Map.of("type", "array", "items", Map.of("type", "string"))),
                    "required", List.of("test", "verdict", "rule", "reason", "codeFiles", "testFiles")))),
            "required", List.of("verdicts"));
}
