package com.forjai.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DependencyPromoterTest {

    @TempDir
    Path deps;

    @Test
    void promotesNugetFromStagingToTheApprovedCache() throws Exception {
        var staged = Files.createDirectories(deps.resolve("staging/fetch-1/packages/newtonsoft.json/13.0.3"));
        Files.writeString(staged.resolve("newtonsoft.json.13.0.3.nupkg"), "x");

        var promoted = new DependencyPromoter(deps).promote("NUGET", "fetch-1",
                List.of(new DependencyRequest.Package("Newtonsoft.Json", "13.0.3")));

        assertEquals(List.of("Newtonsoft.Json@13.0.3"), promoted);
        assertTrue(Files.isRegularFile(deps.resolve("nuget/newtonsoft.json/13.0.3/newtonsoft.json.13.0.3.nupkg")));
    }

    @Test
    void promotesPubPackageAndItsHash() throws Exception {
        Files.createDirectories(deps.resolve("staging/fetch-2/packages/hosted/pub.dev/equatable-2.0.5/lib"));
        Files.createDirectories(deps.resolve("staging/fetch-2/packages/hosted-hashes/pub.dev"));
        Files.writeString(deps.resolve("staging/fetch-2/packages/hosted-hashes/pub.dev/equatable-2.0.5.sha256"), "h");

        new DependencyPromoter(deps).promote("PUB", "fetch-2", List.of(new DependencyRequest.Package("equatable", "2.0.5")));

        assertTrue(Files.isDirectory(deps.resolve("pub/hosted/pub.dev/equatable-2.0.5/lib")));
        assertTrue(Files.isRegularFile(deps.resolve("pub/hosted-hashes/pub.dev/equatable-2.0.5.sha256")));
    }

    // Review Focus: staging inexistente → error explícito, nada se promueve.
    @Test
    void aMissingStagingFails() {
        assertThrows(IllegalStateException.class, () -> new DependencyPromoter(deps).promote("NUGET", "fetch-x",
                List.of(new DependencyRequest.Package("A", "1.0.0"))));
    }

    @Test
    void aJobIdWithPathTraversalIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DependencyPromoter(deps).promote("NUGET", "../nuget",
                List.of(new DependencyRequest.Package("A", "1.0.0"))));
    }

    // Revisión final: ".." cumplía el patrón de nombre y el promotor armaba rutas con él.
    @Test
    void aPackageNameThatIsARelativePathIsRejected() throws Exception {
        Files.createDirectories(deps.resolve("staging/fetch-9/packages"));
        assertThrows(IllegalArgumentException.class, () -> new DependencyPromoter(deps).promote("NUGET", "fetch-9",
                List.of(new DependencyRequest.Package("..", "1.0.0"))));
    }
}
