package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.agent.model.TestTriage;
import com.aicompany.core.agent.validation.FailingTestParser.FailingTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Diagnóstico 360 (decisión del fundador 2026-10-03): Java valida el veredicto de Neo (un veredicto por test que falló,
 * archivos reales del repositorio, código y tests en su lugar) y que corregir un test no sea borrarlo.
 */
public final class TestTriageValidator {

    private static final Set<String> VERDICTS = Set.of(TestTriage.CODE_WRONG, TestTriage.TEST_WRONG, TestTriage.BOTH);
    private static final Pattern TEST_DECLARATION = Pattern.compile("\\[(?:Fact|Theory)\\b|\\b(?:test|testWidgets)\\s*\\(");

    private TestTriageValidator() {
    }

    public static boolean isTestFile(String path) {
        var p = OwnedPaths.normalize(path);
        return p.startsWith("tests/") || p.startsWith("test/") || p.contains("/tests/") || p.contains("/test/");
    }

    public static List<String> validate(TestTriage triage, List<FailingTest> failing, Set<String> repoFiles) {
        var errors = new ArrayList<String>();
        var verdicts = triage == null ? List.<TestTriage.Verdict>of() : triage.verdictsOrEmpty();
        var names = failing.stream().map(FailingTest::name).toList();
        for (var verdict : verdicts) {
            if (verdict == null) {
                continue;
            }
            if (!names.contains(verdict.test())) {
                errors.add("\"" + verdict.test() + "\" no es un test que falló. Tests que fallaron: " + names + ".");
            }
            if (!VERDICTS.contains(verdict.verdict())) {
                errors.add("verdict \"" + verdict.verdict() + "\" de " + verdict.test() + " no es válido: usa "
                        + TestTriage.CODE_WRONG + ", " + TestTriage.TEST_WRONG + " o " + TestTriage.BOTH + ".");
                continue;
            }
            if (verdict.rule() == null || verdict.rule().isBlank() || verdict.reason() == null || verdict.reason().isBlank()) {
                errors.add(verdict.test() + ": rule (la regla de negocio correcta según lo pedido) y reason no pueden "
                        + "estar vacíos.");
            }
            if (verdict.codeWrong()) {
                if (verdict.codeFilesOrEmpty().isEmpty()) {
                    errors.add(verdict.test() + ": con " + verdict.verdict() + " indica en codeFiles qué archivos de código corregir.");
                }
                for (var file : verdict.codeFilesOrEmpty()) {
                    if (!repoFiles.contains(file) || isTestFile(file)) {
                        errors.add(verdict.test() + ": codeFiles \"" + file + "\" no es un archivo de código del repositorio.");
                    }
                }
            }
            if (verdict.testWrong()) {
                if (verdict.testFilesOrEmpty().isEmpty()) {
                    errors.add(verdict.test() + ": con " + verdict.verdict() + " indica en testFiles qué tests corregir.");
                }
                for (var file : verdict.testFilesOrEmpty()) {
                    if (!repoFiles.contains(file) || !isTestFile(file)) {
                        errors.add(verdict.test() + ": testFiles \"" + file + "\" no es un archivo de tests del repositorio.");
                    }
                }
            }
        }
        for (var name : names) {
            var count = verdicts.stream().filter(Objects::nonNull).filter(v -> name.equals(v.test())).count();
            if (count != 1) {
                errors.add("El test \"" + name + "\" necesita exactamente un veredicto (tiene " + count + ").");
            }
        }
        return errors;
    }

    /** Un test corregido sigue existiendo con su nombre y el archivo no pierde tests. */
    public static List<String> preservesTests(List<GeneratedFile> returned, Map<String, String> headTests,
                                              List<FailingTest> failing) {
        var errors = new ArrayList<String>();
        for (var file : returned == null ? List.<GeneratedFile>of() : returned) {
            if (file == null || !headTests.containsKey(file.path())) {
                continue;
            }
            var before = TEST_DECLARATION.matcher(headTests.get(file.path())).results().count();
            var after = TEST_DECLARATION.matcher(Objects.requireNonNullElse(file.content(), "")).results().count();
            if (after < before) {
                errors.add(file.path() + ": tenía " + before + " test(s) y ahora " + after + ". Corregir un test no es "
                        + "borrarlo: conserva todos los tests.");
            }
            for (var test : failing) {
                if (test.files().contains(file.path()) && !file.content().contains(test.method())) {
                    errors.add(file.path() + ": el test \"" + test.method() + "\" ya no está. Corrígelo sin borrarlo ni "
                            + "cambiarle el nombre.");
                }
            }
        }
        return errors;
    }
}
