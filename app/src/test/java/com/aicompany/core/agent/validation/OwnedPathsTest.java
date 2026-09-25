package com.aicompany.core.agent.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OwnedPathsTest {

    @Test
    void normalizesLeadingDotSlashAndTrailingSlash() {
        assertEquals("web/ui", OwnedPaths.normalize("./web/ui/"));
    }

    @Test
    void coversTheExactFileAndEverythingUnderAFolder() {
        assertTrue(OwnedPaths.covers("web/ui", "web/ui/hud.js"));
        assertTrue(OwnedPaths.covers("web/index.html", "web/index.html"));
        assertFalse(OwnedPaths.covers("web/ui", "web/uikit/x.js"));
        assertFalse(OwnedPaths.covers("web/ui", "web/game/x.js"));
    }

    @Test
    void detectsOverlapInBothDirections() {
        assertTrue(OwnedPaths.overlap("web", "web/ui"));
        assertTrue(OwnedPaths.overlap("web/ui", "web"));
        assertFalse(OwnedPaths.overlap("web/ui", "web/game"));
    }

    @Test
    void unsafePathsAreRejected() {
        assertFalse(OwnedPaths.isSafe("/etc"));
        assertFalse(OwnedPaths.isSafe("C:\\x"));
        assertFalse(OwnedPaths.isSafe("../x"));
        assertFalse(OwnedPaths.isSafe("web\\ui"));
        assertFalse(OwnedPaths.isSafe(".git/config"));
        assertFalse(OwnedPaths.isSafe("   "));
        assertTrue(OwnedPaths.isSafe("web/ui"));
    }

    @Test
    void coveredByAnyHandlesNullOwnedList() {
        assertFalse(OwnedPaths.coveredByAny(null, "web/x.js"));
        assertTrue(OwnedPaths.coveredByAny(List.of("docs", "web"), "web/x.js"));
    }
}
