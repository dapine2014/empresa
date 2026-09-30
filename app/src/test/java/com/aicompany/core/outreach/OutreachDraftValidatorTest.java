package com.aicompany.core.outreach;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Spec contacto con prospectos §1: Java verifica el borrador de Sofía. */
class OutreachDraftValidatorTest {

    private static OutreachDraftValidator.Result check(String subject, String body, Double price, boolean onRequest) {
        return OutreachDraftValidator.validate(new OutreachDraft(subject, body), "Email Signature Generator", price,
                onRequest, "Forjai — forjai.com", Set.of("https://forjai.com"), "founder@forjai.com");
    }

    @Test
    void aGoodDraftGetsTheOptOutAndSignatureAppended() {
        var r = check("Firmas para su despacho", "Hola equipo de Acme: Email Signature Generator crea firmas por US$39.",
                39.0, false);

        assertTrue(r.problems().isEmpty(), r.problems().toString());
        assertTrue(r.draft().body().contains(OutreachDraftValidator.OPT_OUT_LINE));
        assertTrue(r.draft().body().endsWith("Forjai — forjai.com"));
    }

    @Test
    void theProductNameIsRequired() {
        var r = check("Hola", "Tenemos una herramienta de firmas por US$39.", 39.0, false);

        assertTrue(r.problems().stream().anyMatch(p -> p.contains("nombre")), r.problems().toString());
    }

    @Test
    void priceFormatsAreAcceptedButOtherAmountsAreNot() {
        assertTrue(check("A", "Email Signature Generator cuesta 39.00 USD.", 39.0, false).problems().isEmpty());
        assertTrue(check("A", "Email Signature Generator cuesta US$ 39.", 39.0, false).problems().isEmpty());
        var wrong = check("A", "Email Signature Generator cuesta US$49.", 39.0, false);
        assertTrue(wrong.problems().stream().anyMatch(p -> p.contains("precio")), wrong.problems().toString());
        var missing = check("A", "Email Signature Generator es genial.", 39.0, false);
        assertTrue(missing.problems().stream().anyMatch(p -> p.contains("precio")), missing.problems().toString());
    }

    @Test
    void aPriceOnRequestProductMustNotQuoteAmounts() {
        assertTrue(check("A", "Email Signature Generator: le cotizamos a medida.", null, true).problems().isEmpty());
        assertFalse(check("A", "Email Signature Generator por $20.", null, true).problems().isEmpty());
    }

    @Test
    void foreignUrlsAndEmailsAreRejected() {
        var url = check("A", "Email Signature Generator por US$39. Mira https://otro.com/x", 39.0, false);
        var email = check("A", "Email Signature Generator por US$39. Escribe a ventas@otro.com", 39.0, false);

        assertTrue(url.problems().stream().anyMatch(p -> p.contains("URL")), url.problems().toString());
        assertTrue(email.problems().stream().anyMatch(p -> p.contains("email")), email.problems().toString());
        assertTrue(check("A", "Email Signature Generator por US$39. Más en https://forjai.com y founder@forjai.com",
                39.0, false).problems().isEmpty());
    }

    @Test
    void lengthLimits() {
        assertFalse(check("", "Email Signature Generator por US$39.", 39.0, false).problems().isEmpty());
        assertFalse(check("A", "Email Signature Generator por US$39. " + "x".repeat(1200), 39.0, false).problems().isEmpty());
    }

    // Revisión final: un asunto con saltos de línea podría inyectar cabeceras (Bcc:).
    @Test
    void aSubjectWithLineBreaksIsRejected() {
        var r = check("Hola\r\nBcc: x@evil.com", "Email Signature Generator por US$39.", 39.0, false);

        assertFalse(r.problems().isEmpty());
    }
}
