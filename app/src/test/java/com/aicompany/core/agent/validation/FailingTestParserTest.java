package com.aicompany.core.agent.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FailingTestParserTest {

    // Verificado en vivo (MISSION-B1-AGENDA-1790997634): la salida real de dotnet test con un test rojo.
    @Test
    void dotnetFailedTestWithMessageAndStackFiles() {
        var tests = FailingTestParser.parse("""
                [xUnit.net 00:00:00.20]     Reservas.Tests.CancelarReservaTests.CancelarReserva_ReservaActiva [FAIL]
                  Failed Reservas.Tests.CancelarReservaTests.CancelarReserva_ReservaActiva [10 ms]
                  Error Message:
                   Reservas.Domain.ExcepcionDominio : El turno no tiene una reserva activa para cancelar.
                  Stack Trace:
                     at Reservas.Domain.Turno.CancelarReservaActiva(DateTime f) in /work/src/Reservas.Domain/Turno.cs:line 82
                   at Reservas.Tests.CancelarReservaTests.CancelarReserva_ReservaActiva() in /work/tests/Reservas.Tests/CancelarReservaTests.cs:line 37
                --- End of stack trace from previous location ---
                Results File: /work/.forjai/results.trx
                """, "backend");
        assertEquals(1, tests.size());
        var test = tests.get(0);
        assertEquals("Reservas.Tests.CancelarReservaTests.CancelarReserva_ReservaActiva", test.name());
        assertEquals("CancelarReserva_ReservaActiva", test.method());
        assertTrue(test.message().contains("El turno no tiene una reserva activa"), test.message());
        assertEquals(List.of("backend/src/Reservas.Domain/Turno.cs:82",
                "backend/tests/Reservas.Tests/CancelarReservaTests.cs:37"), test.locations());
        assertEquals(List.of("backend/src/Reservas.Domain/Turno.cs", "backend/tests/Reservas.Tests/CancelarReservaTests.cs"),
                test.files());
    }

    @Test
    void severalFailuresAndARootComponent() {
        var tests = FailingTestParser.parse("""
                  Failed A.Tests.T.Uno [1 ms]
                  Error Message:
                   Assert.Equal() Failure
                  Stack Trace:
                     at A.Tests.T.Uno() in /work/tests/A.Tests/T.cs:line 10
                  Failed A.Tests.T.Dos [1 ms]
                  Error Message:
                   boom
                """, "");
        assertEquals(List.of("A.Tests.T.Uno", "A.Tests.T.Dos"), tests.stream().map(t -> t.name()).toList());
        assertEquals(List.of("tests/A.Tests/T.cs"), tests.get(0).files());
        assertEquals(List.of(), tests.get(1).files());
    }

    // Verificado en vivo (2026-10-03, imagen flutter-web-app con failing_tests.py): el resumen de Flutter se lee igual.
    @Test
    void flutterSummaryFromTheSandbox() {
        var tests = FailingTestParser.parse("""
                  Failed Counter increments smoke test
                  Error Message:
                   The following TestFailure was thrown running a test:
                   Expected: exactly 5 matching candidates
                   Actual: _TextWidgetFinder:<Found 1 widget with text "1": [
                  Stack Trace:
                     at test/widget_test.dart in /work/test/widget_test.dart:line 28
                """, "app");
        assertEquals(1, tests.size());
        assertEquals("Counter increments smoke test", tests.get(0).method());
        assertTrue(tests.get(0).message().contains("Expected: exactly 5"), tests.get(0).message());
        assertEquals(List.of("app/test/widget_test.dart"), tests.get(0).files());
    }
}
