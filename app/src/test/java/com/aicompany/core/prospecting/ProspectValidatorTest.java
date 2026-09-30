package com.aicompany.core.prospecting;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Spec búsqueda de prospectos §1: validez decidida en Java, nunca por el modelo. */
class ProspectValidatorTest {

    private final Map<String, String> pages = new HashMap<>();
    private final ProspectValidator validator = new ProspectValidator(url -> {
        var page = pages.get(url);
        if (page == null) {
            throw new IllegalStateException("La URL respondió con estado 404: " + url);
        }
        return page;
    });

    private static ProspectCandidate withEmail(String name, String url, String email, String source) {
        return new ProspectCandidate(name, url, email, null, source, "Publican contenido largo cada semana");
    }

    @Test
    void aProspectWithItsNameOnItsPageAndTheEmailOnTheSourceIsValid() {
        pages.put("https://acme.com", "Bienvenidos a Acmé Studio, agencia de contenido");
        pages.put("https://acme.com/contacto", "Escríbenos a hola@acme.com");

        var result = validator.validate(List.of(withEmail("Acme Studio", "https://acme.com", "hola@acme.com",
                "https://acme.com/contacto")), Set.of());

        assertEquals(1, result.valid().size());
        assertTrue(result.rejections().isEmpty());
    }

    @Test
    void emailMatchIsCaseInsensitive() {
        pages.put("https://acme.com", "Acme Studio");
        pages.put("https://acme.com/c", "Contacto: Info@Acme.com");

        var result = validator.validate(List.of(withEmail("Acme Studio", "https://acme.com", "info@acme.com",
                "https://acme.com/c")), Set.of());

        assertEquals(1, result.valid().size());
    }

    @Test
    void anEmailThatIsNotOnTheSourcePageIsRejected() {
        pages.put("https://acme.com", "Acme Studio");
        pages.put("https://acme.com/c", "Formulario de contacto sin email");

        var result = validator.validate(List.of(withEmail("Acme Studio", "https://acme.com", "ventas@acme.com",
                "https://acme.com/c")), Set.of());

        assertTrue(result.valid().isEmpty());
        assertTrue(result.rejections().get(0).contains("Acme Studio"), result.rejections().toString());
        assertTrue(result.rejections().get(0).contains("contacto"), result.rejections().toString());
    }

    @Test
    void aNameThatIsNotOnItsPageIsRejected() {
        pages.put("https://blog.com/tendencias", "Tendencias del sector 2026");
        pages.put("https://blog.com/c", "hola@blog.com");

        var result = validator.validate(List.of(withEmail("Studio PixelCraft", "https://blog.com/tendencias",
                "hola@blog.com", "https://blog.com/c")), Set.of());

        assertTrue(result.valid().isEmpty());
        assertTrue(result.rejections().get(0).contains("nombre"), result.rejections().toString());
    }

    @Test
    void aReachableContactFormIsEnoughWithoutEmail() {
        pages.put("https://acme.com", "Acme Studio");
        pages.put("https://acme.com/form", "<form>");

        var result = validator.validate(List.of(new ProspectCandidate("Acme Studio", "https://acme.com", null,
                "https://acme.com/form", null, "Necesitan repurposing")), Set.of());

        assertEquals(1, result.valid().size());
    }

    @Test
    void withoutAnyVerifiableContactItIsRejected() {
        pages.put("https://acme.com", "Acme Studio");

        var result = validator.validate(List.of(new ProspectCandidate("Acme Studio", "https://acme.com", null, null,
                null, "Necesitan repurposing")), Set.of());

        assertTrue(result.valid().isEmpty());
    }

    @Test
    void anUnreachablePageIsRejected() {
        var result = validator.validate(List.of(withEmail("Acme Studio", "https://acme.com", "a@acme.com",
                "https://acme.com/c")), Set.of());

        assertTrue(result.rejections().get(0).contains("no responde"), result.rejections().toString());
    }

    @Test
    void aNonHttpUrlIsRejected() {
        var result = validator.validate(List.of(withEmail("Acme", "ftp://acme.com", "a@acme.com", "ftp://acme.com")),
                Set.of());

        assertTrue(result.valid().isEmpty());
    }

    @Test
    void aKnownDomainIsRejectedAsRepeated() {
        pages.put("https://www.acme.com", "Acme Studio");
        pages.put("https://acme.com/c", "a@acme.com");

        var result = validator.validate(List.of(withEmail("Acme Studio", "https://www.acme.com", "a@acme.com",
                "https://acme.com/c")), Set.of("acme.com"));

        assertTrue(result.rejections().get(0).contains("repetido"), result.rejections().toString());
    }

    @Test
    void sameDomainTwiceInOneBatchKeepsTheFirst() {
        pages.put("https://acme.com", "Acme Studio");
        pages.put("https://acme.com/equipo", "Acme Studio equipo");
        pages.put("https://acme.com/c", "a@acme.com");

        var result = validator.validate(List.of(
                withEmail("Acme Studio", "https://acme.com", "a@acme.com", "https://acme.com/c"),
                withEmail("Acme Studio", "https://acme.com/equipo", "a@acme.com", "https://acme.com/c")), Set.of());

        assertEquals(1, result.valid().size());
        assertEquals(1, result.rejections().size());
    }

    @Test
    void wwwIsIgnoredForDuplicates() {
        assertEquals("acme.com", ProspectValidator.domain("https://www.ACME.com/x"));
        assertNull(ProspectValidator.domain("no es url"));
    }

    @Test
    void missingFitReasonIsRejected() {
        pages.put("https://acme.com", "Acme Studio");

        var result = validator.validate(List.of(new ProspectCandidate("Acme Studio", "https://acme.com", null,
                "https://acme.com", null, " ")), Set.of());

        assertTrue(result.valid().isEmpty());
    }
}
