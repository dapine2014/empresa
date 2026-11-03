package com.aicompany.core.agent.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aicompany.core.agent.model.VisualSystem;
import com.aicompany.core.agent.model.VisualSystem.ColorToken;
import com.aicompany.core.model.Box;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DesignComparisonTest {

    private static final VisualSystem VS = new VisualSystem(
            List.of(new ColorToken("primary", "#1B5E20"), new ColorToken("surface", "#FFFFFF")),
            null, Map.of(), Map.of(), List.of());

    private static BufferedImage blank() {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 32; x++) for (int y = 0; y < 32; y++) img.setRGB(x, y, Color.WHITE.getRGB());
        return img;
    }

    private static DesignComparison.ScreenResult run(List<Box> mockup, List<Box> app) {
        return DesignComparison.compare("login", "360x640", blank(), mockup, blank(), app, VS, 0.9);
    }

    @Test
    void flagsButtonMovedAndOffPalette() {
        var r = run(List.of(new Box("btn-ingresar", 20, 520, 320, 48, "#1B5E20")),
                List.of(new Box("btn-ingresar", 20, 580, 320, 40, "#6750A4")));
        assertEquals(1, r.diffs().size());
        String t = DesignComparison.correction(List.of(r));
        assertTrue(t.startsWith("CORRECCIÓN DE DISEÑO — "), t);
        assertTrue(t.contains("btn-ingresar") && t.contains("y=520") && t.contains("y=580"), t);
        assertTrue(t.contains("no está en la paleta"), t);
        assertTrue(t.contains("AppTheme."), t);
        assertTrue(t.matches("(?s).*similitud \\d,\\d\\d.*"), t);
        assertTrue(t.contains("Lo demás coincide: no lo cambies."), t);
    }

    @Test
    void flagsMissingElement() {
        var r = run(List.of(new Box("link-olvide", 20, 600, 100, 20, null)), List.of());
        assertEquals(List.of("link-olvide"), r.missing());
        assertFalse(r.passed());
        assertTrue(DesignComparison.correction(List.of(r)).contains("link-olvide"));
        // Revisión final (C2): la corrección pide ForjaiId, no una Key (que no llega a la captura).
        assertTrue(DesignComparison.correction(List.of(r)).contains("ForjaiId('link-olvide', child: …)"),
                DesignComparison.correction(List.of(r)));
    }

    @Test
    void withinToleranceNoDiff() {
        var r = run(List.of(new Box("btn", 20, 520, 320, 48, "#1B5E20")),
                List.of(new Box("btn", 26, 526, 314, 54, "#1b5e20")));
        assertTrue(r.diffs().isEmpty());
        assertTrue(r.passed());
    }
}
