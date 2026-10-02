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

    // Revisión final (m-7): la forma más común es un comentario que empieza con la elisión y sigue con texto.
    @Test
    void aCommentThatStartsWithAnEllipsisIsElidedCode() {
        assertFalse(check("src/A.cs", "class A {\n  // ... otros métodos\n}").isEmpty());
        assertFalse(check("lib/a.dart", "class A {\n  // ...existing methods\n}").isEmpty());
        assertFalse(check("src/A.cs", "class A {\n  /* ... igual que antes */\n}").isEmpty());
    }

    // Revisión final (m-6): comentarios legítimos y selectores CSS no son código omitido.
    @Test
    void legitimateCommentsAndCssSelectorsAreNotElidedCode() {
        assertTrue(check("src/A.cs", "// Mantiene compatibilidad con el código existente\nclass A {}").isEmpty());
        assertTrue(check("src/A.cs", "// Adapter over existing code\nclass A {}").isEmpty());
        assertTrue(check("web/app.css", "#existing-code-banner {\n  color: red;\n}").isEmpty());
        assertTrue(check("src/A.cs", "#region Existing code\nclass A {}\n#endregion").isEmpty());
    }

    // Pendiente de la revisión (m-8): en HTML una línea "..." es texto visible; un comentario <!-- ... --> sí es elisión.
    @Test
    void anEllipsisLineInHtmlIsVisibleTextButAnElidedCommentIsNot() {
        assertTrue(check("web/index.html", "<p>\n  Cargando\n  ...\n</p>").isEmpty());
        assertFalse(check("web/index.html", "<body>\n  <!-- ... resto de la página -->\n</body>").isEmpty());
    }

    // Pendiente de la revisión (m-8): integration_test/ de Flutter son tests.
    @Test
    void flutterIntegrationTestsMayUseUnimplementedError() {
        assertTrue(check("integration_test/helpers.dart", "Never pendiente() => throw UnimplementedError();")
                .isEmpty());
    }
}
