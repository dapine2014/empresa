package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.agent.model.TestTriage;
import com.aicompany.core.agent.validation.FailingTestParser.FailingTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Diagnóstico 360 (decisión del fundador 2026-10-03): Neo arbitra; Java valida el veredicto y evita trampas. */
class TestTriageValidatorTest {

    private static final FailingTest CANCELAR = new FailingTest("Reservas.Tests.CancelarReservaTests.Cancelar",
            "El turno no tiene una reserva activa", List.of("backend/src/Reservas.Domain/Turno.cs:82",
            "backend/tests/Reservas.Tests/CancelarReservaTests.cs:37"));
    private static final Set<String> REPO = Set.of("backend/src/Reservas.Domain/Turno.cs",
            "backend/src/Reservas.Application/CancelarReserva.cs", "backend/tests/Reservas.Tests/CancelarReservaTests.cs",
            "backend/tests/Reservas.Tests/Fakes.cs");

    private static TestTriage.Verdict v(String test, String verdict, List<String> code, List<String> tests) {
        return new TestTriage.Verdict(test, verdict, "Cancelar una reserva activa la cancela y libera el turno.",
                "El repositorio reconstruye el turno sin sus reservas.", code, tests);
    }

    @Test
    void aValidVerdictForEveryFailingTestPasses() {
        var triage = new TestTriage(List.of(v(CANCELAR.name(), "CODE_WRONG",
                List.of("backend/src/Reservas.Domain/Turno.cs"), List.of())));
        assertEquals(List.of(), TestTriageValidator.validate(triage, List.of(CANCELAR), REPO));
    }

    @Test
    void missingVerdictsUnknownVerdictsAndInventedFilesAreRejected() {
        var errors = TestTriageValidator.validate(new TestTriage(List.of(
                v("otro test", "MAYBE", List.of(), List.of()),
                v(CANCELAR.name(), "TEST_WRONG", List.of(), List.of("backend/tests/Reservas.Tests/NoExiste.cs")))),
                List.of(CANCELAR), REPO);
        assertTrue(errors.stream().anyMatch(e -> e.contains("MAYBE")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("otro test") && e.contains("no es un test que falló")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("NoExiste.cs")), errors.toString());
    }

    @Test
    void codeWrongNeedsCodeFilesAndTestWrongNeedsTestFiles() {
        var errors = TestTriageValidator.validate(new TestTriage(List.of(v(CANCELAR.name(), "BOTH",
                List.of("backend/tests/Reservas.Tests/Fakes.cs"), List.of("backend/src/Reservas.Domain/Turno.cs")))),
                List.of(CANCELAR), REPO);
        assertTrue(errors.stream().anyMatch(e -> e.contains("codeFiles") && e.contains("Fakes.cs")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("testFiles") && e.contains("Turno.cs")), errors.toString());
    }

    // Guarda anti-trampa: corregir un test no es borrarlo ni bajar la cantidad de tests.
    @Test
    void aTestFixCannotDeleteOrRenameTheFailingTestNorDropTests() {
        var head = Map.of("backend/tests/Reservas.Tests/CancelarReservaTests.cs",
                "[Fact] public void Cancelar() {}\n[Fact] public void Otro() {}");
        var deleted = TestTriageValidator.preservesTests(List.of(new GeneratedFile(
                "backend/tests/Reservas.Tests/CancelarReservaTests.cs", "[Fact] public void Otro() {}")), head,
                List.of(CANCELAR));
        assertEquals(2, deleted.size(), deleted.toString());
        assertEquals(List.of(), TestTriageValidator.preservesTests(List.of(new GeneratedFile(
                "backend/tests/Reservas.Tests/CancelarReservaTests.cs",
                "[Fact] public void Cancelar() { /* corregido */ }\n[Fact] public void Otro() {}")), head, List.of(CANCELAR)));
    }
}
