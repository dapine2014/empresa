package com.aicompany.core.evidence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceDedupKeyTest {

    @Test
    void sameSourceAndDescriptionProduceTheSameId() {
        var id1 = EvidenceDedupKey.stableId(
                "https://example.com/a", "El precio promedio es US$100");
        var id2 = EvidenceDedupKey.stableId(
                "https://example.com/a", "El precio promedio es US$100");

        assertEquals(id1, id2);
    }

    @Test
    void differentSourceProducesDifferentId() {
        var id1 = EvidenceDedupKey.stableId(
                "https://example.com/a", "El precio promedio es US$100");
        var id2 = EvidenceDedupKey.stableId(
                "https://example.com/b", "El precio promedio es US$100");

        assertNotEquals(id1, id2);
    }

    @Test
    void differentDescriptionProducesDifferentId() {
        var id1 = EvidenceDedupKey.stableId(
                "https://example.com/a", "El precio promedio es US$100");
        var id2 = EvidenceDedupKey.stableId(
                "https://example.com/a", "El precio promedio es US$200");

        assertNotEquals(id1, id2);
    }

    @Test
    void isCaseAndTrailingSlashInsensitive() {
        var id1 = EvidenceDedupKey.stableId(
                "https://Example.com/a/", "  Precio Promedio  ");
        var id2 = EvidenceDedupKey.stableId(
                "https://example.com/a", "precio promedio");

        assertEquals(id1, id2);
    }

    @Test
    void collapsesInternalWhitespaceDifferences() {
        var id1 = EvidenceDedupKey.stableId(
                "https://example.com/a", "precio   promedio\tdel servicio");
        var id2 = EvidenceDedupKey.stableId(
                "https://example.com/a", "precio promedio del servicio");

        assertEquals(id1, id2);
    }

    @Test
    void handlesNullFieldsWithoutThrowing() {
        assertDoesNotThrow(() -> EvidenceDedupKey.stableId(null, null));

        assertEquals(
                EvidenceDedupKey.stableId(null, "x"),
                EvidenceDedupKey.stableId("", "x")
        );
    }

    @Test
    void idHasAStablePrefixAndLooksLikeHex() {
        var id = EvidenceDedupKey.stableId("https://example.com/a", "x");

        assertTrue(id.startsWith("EVIDENCE-"));
        assertTrue(id.substring("EVIDENCE-".length()).matches("[0-9a-f]{64}"));
    }
}
