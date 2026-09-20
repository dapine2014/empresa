package com.aicompany.core.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertEmailTemplateTest {

    @Test
    void declaresUtf8CharsetSoAccentsAndEnesRenderCorrectly() {
        var html = AlertEmailTemplate.html("Misión", "ñ á é í ó ú", false);

        assertTrue(html.contains("<meta charset=\"UTF-8\">"));
    }

    @Test
    void includesTheBrandNameAndTheSubjectAsHeading() {
        var html = AlertEmailTemplate.html("Misión MISSION-1 falló", "cuerpo", false);

        assertTrue(html.contains("FORJAI"));
        assertTrue(html.contains("Misión MISSION-1 falló"));
    }

    @Test
    void preservesLineBreaksInTheBody() {
        var html = AlertEmailTemplate.html("asunto", "primera línea\nsegunda línea", false);

        assertTrue(html.contains("primera línea<br>segunda línea"));
    }

    @Test
    void usesARedAccentForCriticalAlertsAndBlueOtherwise() {
        var critical = AlertEmailTemplate.html("asunto", "cuerpo", true);
        var informational = AlertEmailTemplate.html("asunto", "cuerpo", false);

        assertTrue(critical.contains("#dc2626"));
        assertFalse(critical.contains("#4f8dfd"));

        assertTrue(informational.contains("#4f8dfd"));
        assertFalse(informational.contains("#dc2626"));
    }

    @Test
    void escapesHtmlInSubjectAndBodySoUntrustedContentCannotBreakTheMarkup() {
        var html = AlertEmailTemplate.html(
                "<script>alert(1)</script>",
                "riesgo & oportunidad <b>real</b>",
                false);

        assertFalse(html.contains("<script>alert(1)</script>"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("riesgo &amp; oportunidad &lt;b&gt;real&lt;/b&gt;"));
    }

    @Test
    void acceptsACustomFooterForOutreachEmailsInsteadOfTheInternalAlertFooter() {
        var html = AlertEmailTemplate.html("asunto", "cuerpo", false, "Mensaje enviado por Forjai");

        assertTrue(html.contains("Mensaje enviado por Forjai"));
        assertFalse(html.contains("Alerta automática"));
    }

    @Test
    void threeArgOverloadStillUsesTheInternalAlertFooterForBackwardCompatibility() {
        var html = AlertEmailTemplate.html("asunto", "cuerpo", false);

        assertTrue(html.contains("Alerta automática"));
    }
}
