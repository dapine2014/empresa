package com.aicompany.core.prospecting;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verificado en vivo (2026-09-30, primera búsqueda real): Sofía solo recibía título y fragmento de cada página, sin
 * emails, y devolvía una lista vacía. Java le entrega el texto de la página y los contactos que encuentra en ella.
 */
class PageDigestTest {

    private static final String HTML = """
            <html><head><title>Top agencies</title><style>.x{color:red}</style><script>var a = "no@script.com";</script></head>
            <body>
              <h1>Best Content Agencies 2026</h1>
              <p>Acme Studio &amp; Co helps B2B founders. Write to <a href="mailto:Hello@Acme.com">us</a>.</p>
              <p>Beta Media: contact team@beta-media.io</p>
              <a href="/contact">Contact</a> <a href="https://other.com/about-us">About</a> <a href="/pricing">Pricing</a>
            </body></html>
            """;

    @Test
    void textWithoutTagsScriptsOrStyles() {
        var digest = PageDigest.of(HTML, "https://list.com/top");

        assertTrue(digest.excerpt().contains("Best Content Agencies 2026"), digest.excerpt());
        assertTrue(digest.excerpt().contains("Acme Studio & Co helps B2B founders"), digest.excerpt());
        assertFalse(digest.excerpt().contains("<p>"), digest.excerpt());
        assertFalse(digest.excerpt().contains("color:red"), digest.excerpt());
        assertFalse(digest.excerpt().contains("no@script.com"), digest.excerpt());
    }

    @Test
    void emailsFromTextAndMailtoLowercasedAndUnique() {
        var digest = PageDigest.of(HTML + "<p>hello@acme.com</p>", "https://list.com/top");

        assertEquals(List.of("hello@acme.com", "team@beta-media.io"), digest.emails());
    }

    @Test
    void contactLinksAreAbsolute() {
        var digest = PageDigest.of(HTML, "https://list.com/top");

        assertEquals(List.of("https://list.com/contact", "https://other.com/about-us"), digest.contactLinks());
    }

    @Test
    void theExcerptIsCapped() {
        var digest = PageDigest.of("<p>" + "palabra ".repeat(2000) + "</p>", "https://x.com");

        assertTrue(digest.excerpt().length() <= PageDigest.MAX_EXCERPT);
    }
}
