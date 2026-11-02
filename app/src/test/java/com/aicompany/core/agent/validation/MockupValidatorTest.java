package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign.AppDesign;
import com.aicompany.core.agent.model.InteractionDesign.Screen;
import com.aicompany.core.agent.model.Mockups;
import com.aicompany.core.agent.model.Mockups.Mockup;
import com.aicompany.core.agent.model.Mockups.SvgAsset;
import com.aicompany.core.agent.model.VisualSystem;
import com.aicompany.core.agent.model.VisualSystem.ColorToken;
import com.aicompany.core.agent.model.VisualSystem.Typography;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockupValidatorTest {

    private static final VisualSystem VS = new VisualSystem(
            List.of(new ColorToken("primary", "#2F6FED"), new ColorToken("surface", "#FFFFFF")),
            new Typography("Roboto", Map.of("body", 16.0)), Map.of("md", 16.0), Map.of("md", 8.0), List.of());
    private static final AppDesign APP = new AppDesign(
            List.of(new Screen("PANT-01", "Inicio", "p", "r", List.of())), List.of(), List.of(), List.of());

    private static List<String> validate(String html, List<SvgAsset> svgs) {
        return MockupValidator.validate(new Mockups(List.of(new Mockup("PANT-01", html)), svgs), APP, Set.of("PANT-01"), VS);
    }

    private static boolean mentions(List<String> errors, String text) {
        return errors.stream().anyMatch(e -> e.contains(text));
    }

    @Test
    void acceptsCleanMockup() {
        var html = "<html><head><style>.a{color:var(--color-primary)}</style></head>"
                + "<body><div data-id=\"btn_ok\" style=\"background:var(--color-surface)\"></div></body></html>";
        assertTrue(validate(html, List.of()).isEmpty());
    }

    @Test
    void requiresMockupForEachIncrementScreen() {
        var errors = MockupValidator.validate(new Mockups(List.of(), List.of()), APP, Set.of("PANT-01"), VS);
        assertTrue(mentions(errors, "PANT-01"));
    }

    @Test
    void rejectsLiteralColorInStyleTag() {
        var errors = validate("<style>.a{color:#FF0000}</style><div data-id=\"a\"></div>", List.of());
        assertFalse(errors.isEmpty());
        assertTrue(mentions(errors, "color"));
    }

    @Test
    void rejectsLiteralColorInInlineStyleAndSvgFill() {
        assertFalse(validate("<div data-id=\"a\" style=\"background:rgb(1,2,3)\"></div>", List.of()).isEmpty());
        assertFalse(validate("<div data-id=\"a\"><svg><rect fill=\"#abc\"/></svg></div>", List.of()).isEmpty());
        assertFalse(validate("<div data-id=\"a\"><svg><rect stroke='hsl(1,2%,3%)'/></svg></div>", List.of()).isEmpty());
        assertFalse(validate("<div data-id=\"a\"></div>",
                List.of(new SvgAsset("logo", "<svg><rect fill=\"#fff\"/></svg>"))).isEmpty());
    }

    @Test
    void rejectsUnknownTokenVar() {
        var errors = validate("<div data-id=\"a\" style=\"color:var(--color-inventado)\"></div>", List.of());
        assertTrue(mentions(errors, "inventado"));
    }

    @Test
    void rejectsScriptAndExternalUrl() {
        assertTrue(mentions(validate("<div data-id=\"a\"></div><script>alert(1)</script>", List.of()), "script"));
        assertFalse(validate("<img data-id=\"a\" src=\"https://x.com/a.png\">", List.of()).isEmpty());
        assertFalse(validate("<div data-id=\"a\" style=\"background:url(foo.png)\"></div>", List.of()).isEmpty());
        assertFalse(validate("<div data-id=\"a\"></div>",
                List.of(new SvgAsset("x", "<svg><script>1</script></svg>"))).isEmpty());
        assertFalse(validate("<div data-id=\"a\"></div>",
                List.of(new SvgAsset("x", "<svg><image href=\"http://e.com/a.png\"/></svg>"))).isEmpty());
    }

    @Test
    void rejectsDuplicateDataId() {
        var errors = validate("<div data-id=\"a\"></div><div data-id=\"a\"></div>", List.of());
        assertTrue(mentions(errors, "data-id"));
        assertTrue(mentions(validate("<div></div>", List.of()), "data-id"));
    }
}
