package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.VisualSystem;
import com.aicompany.core.agent.model.VisualSystem.ColorToken;
import com.aicompany.core.agent.model.VisualSystem.ComponentStyle;
import com.aicompany.core.agent.model.VisualSystem.Typography;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VisualSystemValidatorTest {

    private static List<ColorToken> colors() {
        return new ArrayList<>(List.of(new ColorToken("primary", "#0D47A1"), new ColorToken("onPrimary", "#FFFFFF"),
                new ColorToken("surface", "#FFFFFF"), new ColorToken("onSurface", "#212121"),
                new ColorToken("error", "#B00020"), new ColorToken("onError", "#FFFFFF")));
    }

    private static VisualSystem system(List<ColorToken> colors, String font, List<ComponentStyle> components) {
        return new VisualSystem(colors, new Typography(font, Map.of("display", 32.0, "title", 20.0, "body", 14.0, "label", 12.0)),
                Map.of("sm", 8.0, "md", 16.0), Map.of("sm", 4.0, "md", 8.0), components);
    }

    private static VisualSystem valid() {
        return system(colors(), "Roboto", List.of(new ComponentStyle("button", Map.of("background", "primary", "text", "onPrimary"))));
    }

    @Test
    void acceptsCompleteSystem() {
        assertEquals(List.of(), VisualSystemValidator.validate(valid()));
    }

    @Test
    void rejectsMissingRequiredColor() {
        var c = colors();
        c.removeIf(t -> t.name().equals("onError"));
        var errors = VisualSystemValidator.validate(system(c, "Roboto", List.of()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("onError")), errors.toString());
    }

    @Test
    void rejectsLowContrast() {
        var c = colors();
        c.set(0, new ColorToken("primary", "#777777"));
        c.set(1, new ColorToken("onPrimary", "#888888"));
        var errors = VisualSystemValidator.validate(system(c, "Roboto", List.of()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("contraste") && e.contains("4,5")), errors.toString());
    }

    @Test
    void rejectsFontOutsideCatalog() {
        var errors = VisualSystemValidator.validate(system(colors(), "Comic Sans", List.of()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("Roboto") && e.contains("Noto Sans") && e.contains("Noto Serif")), errors.toString());
    }

    @Test
    void rejectsComponentTokenThatDoesNotExist() {
        var errors = VisualSystemValidator.validate(system(colors(), "Roboto",
                List.of(new ComponentStyle("button", Map.of("background", "brand")))));
        assertTrue(errors.stream().anyMatch(e -> e.contains("brand")), errors.toString());
    }

    @Test
    void rejectsInvalidHex() {
        var c = colors();
        c.set(2, new ColorToken("surface", "#12345"));
        var errors = VisualSystemValidator.validate(system(c, "Roboto", List.of()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("#12345")), errors.toString());
    }
}
