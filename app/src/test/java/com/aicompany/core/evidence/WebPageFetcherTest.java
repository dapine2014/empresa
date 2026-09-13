package com.aicompany.core.evidence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebPageFetcherTest {

    private final WebPageFetcher fetcher = new WebPageFetcher();

    @Test
    void blocksLocalhostByName() {
        assertThrows(IllegalArgumentException.class,
                () -> fetcher.parseAndValidate("http://localhost/secret"));
    }

    @Test
    void blocksLoopbackIp() {
        assertThrows(IllegalArgumentException.class,
                () -> fetcher.parseAndValidate("http://127.0.0.1/secret"));
    }

    @Test
    void blocksCloudMetadataEndpoint() {
        assertThrows(IllegalArgumentException.class,
                () -> fetcher.parseAndValidate("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    void blocksPrivateRange10() {
        assertThrows(IllegalArgumentException.class,
                () -> fetcher.parseAndValidate("http://10.0.0.5/"));
    }

    @Test
    void blocksPrivateRange172() {
        assertThrows(IllegalArgumentException.class,
                () -> fetcher.parseAndValidate("http://172.16.0.5/"));
    }

    @Test
    void blocksPrivateRange192() {
        assertThrows(IllegalArgumentException.class,
                () -> fetcher.parseAndValidate("http://192.168.1.5/"));
    }

    @Test
    void blocksNonHttpScheme() {
        assertThrows(IllegalArgumentException.class,
                () -> fetcher.parseAndValidate("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> fetcher.parseAndValidate("ftp://example.com/file"));
    }

    @Test
    void rejectsBlankUrl() {
        assertThrows(IllegalArgumentException.class, () -> fetcher.fetch(""));
        assertThrows(IllegalArgumentException.class, () -> fetcher.fetch(null));
    }

    @Test
    void acceptsRealPublicHttpsUrl() {
        // Verificación en vivo (requiere internet real): confirma que la
        // validación no bloquea de más un host público legítimo.
        assertDoesNotThrow(() -> fetcher.parseAndValidate("https://example.com/"));
    }

    @Test
    void fetchesRealPublicContent() {
        // Verificación en vivo real, sin API key: example.com es un
        // dominio estable reservado por IANA para pruebas/documentación.
        var content = fetcher.fetch("https://example.com/");

        assertNotNull(content);
        assertTrue(content.toLowerCase().contains("example"));
    }
}
