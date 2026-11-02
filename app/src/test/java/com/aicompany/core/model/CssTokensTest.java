package com.aicompany.core.model;

import com.aicompany.core.agent.model.Mockups.Mockup;
import com.aicompany.core.agent.model.VisualSystem;
import com.aicompany.core.agent.model.VisualSystem.ColorToken;
import com.aicompany.core.agent.model.VisualSystem.Typography;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CssTokensTest {

    private static final VisualSystem VS = new VisualSystem(
            List.of(new ColorToken("primary", "#2F6FED"), new ColorToken("surface", "#FFFFFF")),
            new Typography("Roboto", Map.of("body", 16.0)), Map.of("md", 16.0), Map.of("md", 8.0), List.of());

    @Test
    void rootHasEveryColorAndFontFace() {
        var css = CssTokens.root(VS);
        assertTrue(css.contains("--color-primary:#2F6FED"));
        assertTrue(css.contains("--color-surface:#FFFFFF"));
        assertTrue(css.contains("@font-face{font-family:\"Roboto\""));
        assertTrue(css.contains("fonts/Roboto-Bold.ttf"));
        assertTrue(css.contains("--size-body:16"));
        assertTrue(css.contains("--space-md:16"));
        assertTrue(css.contains("--radius-md:8"));
        assertTrue(css.contains("--font-family:\"Roboto\""));
    }

    @Test
    void pageInjectsStyleAtStartOfHead() {
        var page = CssTokens.page(new Mockup("PANT-01", "<html><head><title>x</title></head><body></body></html>"), VS);
        assertTrue(page.contains("<head><style>:root{"));
        assertTrue(page.contains("<title>x</title>"));
        var bare = CssTokens.page(new Mockup("PANT-01", "<div data-id=\"a\"></div>"), VS);
        assertTrue(bare.contains("<style>:root{") && bare.contains("<div data-id"));
    }

    @Test
    void fragmentWithHeaderTagGetsRealHeadAndCharset() {
        var page = CssTokens.page(new Mockup("PANT-01", "<header data-id=\"bar\">Inicio</header><main></main>"), VS);
        assertTrue(page.contains("<head><meta charset=\"utf-8\"><style>:root{"));
        assertTrue(page.contains("<header data-id=\"bar\">Inicio</header>"));
        assertTrue(page.indexOf("<style>") < page.indexOf("<header"));
    }

    @Test
    void fullDocumentKeepsItsHead() {
        var page = CssTokens.page(new Mockup("PANT-01",
                "<!DOCTYPE html><html><head lang=\"es\"><meta charset=\"utf-8\"></head><body><header></header></body></html>"), VS);
        assertTrue(page.contains("<head lang=\"es\"><style>:root{"));
        assertTrue(page.indexOf("<style>") < page.indexOf("<header>"));
    }
}
