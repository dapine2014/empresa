package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeLiteralGateTest {

    private static GeneratedFile f(String path, String content) {
        return new GeneratedFile(path, content);
    }

    @Test
    void rejectsColorLiteralInPresentation() {
        var errors = ThemeLiteralGate.check(List.of(
                f("lib/pedidos/presentation/pant_01.dart", "final c = Color(0xFF112233); final d = Colors.red; final e = Colors.transparent;")));
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("lib/pedidos/presentation/pant_01.dart"), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("Colors.red")), errors.toString());
    }

    @Test
    void allowsColorInDomainFile() {
        assertEquals(List.of(), ThemeLiteralGate.check(List.of(
                f("lib/pedidos/domain/etiqueta.dart", "final c = Color(0xFF112233); const t = TextStyle(fontSize: 3);"))));
    }

    @Test
    void ignoresCommentedLiteral() {
        assertEquals(List.of(), ThemeLiteralGate.check(List.of(
                f("lib/pedidos/presentation/a.dart", "// Color(0xFF000000)\n/* Colors.red fontFamily: 'x' */\nfinal x = 1;"))));
    }

    @Test
    void rejectsTextStyleWithLiteralFontSize() {
        var errors = ThemeLiteralGate.check(List.of(
                f("app/lib/pedidos/presentation/a.dart", "final s = TextStyle(fontSize: 14); final t = TextStyle(color: Color.fromARGB(255, 1, 2, 3)); final u = TextStyle(fontFamily: 'Roboto');")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("fontSize")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("Color.fromARGB")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("fontFamily")), errors.toString());
    }

    @Test
    void allowsThemeTokens() {
        assertEquals(List.of(), ThemeLiteralGate.check(List.of(
                f("lib/pedidos/presentation/a.dart", "final s = AppTheme.body.copyWith(color: AppTheme.primary); final c = Colors.transparent;"))));
    }
}
