package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Verificado en vivo (MISSION-1790796941181): 72 errores de compilación por archivos con "..." en lugar de código. */
class ElidedCodeGateTest {

    private static List<String> check(String path, String content) {
        return ElidedCodeGate.check(List.of(new GeneratedFile(path, content)));
    }

    @Test
    void aLineWithOnlyAnEllipsisIsElidedCode() {
        var errors = check("src/Citas.Application/Reservar.cs", "public class Reservar {\n    ...\n}");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("src/Citas.Application/Reservar.cs") && errors.get(0).contains("línea 2"),
                errors.toString());
    }

    @Test
    void commentsThatSkipCodeAreElidedCode() {
        assertFalse(check("lib/a.dart", "void main() {\n  // ...\n}").isEmpty());
        assertFalse(check("lib/a.dart", "void main() {\n  // resto del código igual\n}").isEmpty());
        assertFalse(check("src/A.cs", "class A {\n  /* rest of the code */\n}").isEmpty());
        assertFalse(check("src/A.cs", "class A {\n  // TODO: implementar\n}").isEmpty());
    }

    @Test
    void notImplementedOutsideTestsIsElidedCode() {
        assertFalse(check("src/A.cs", "class A { void B() => throw new NotImplementedException(); }").isEmpty());
        assertFalse(check("lib/a.dart", "void b() => throw UnimplementedError();").isEmpty());
    }

    @Test
    void notImplementedInsideTestsIsAllowed() {
        assertTrue(check("tests/Citas.Tests/ReservarTests.cs",
                "Assert.Throws<NotImplementedException>(() => throw new NotImplementedException());").isEmpty());
        assertTrue(check("test/saludo/saludo_test.dart", "expect(() => throw UnimplementedError(), throwsA(anything));")
                .isEmpty());
    }

    @Test
    void legitimateEllipsisIsNotElidedCode() {
        assertTrue(check("lib/a.dart", "final all = [...items, ...more];\nfinal s = 'Cargando...';").isEmpty());
        assertTrue(check("src/A.cs", "var r = list[1..^1];\nvar t = \"Espere...\";").isEmpty());
    }

    @Test
    void nonCodeFilesAreNotChecked() {
        assertTrue(check("README.md", "Instalar...\n...\n").isEmpty());
        assertTrue(check("pubspec.yaml", "# ...\nname: app").isEmpty());
    }
}
