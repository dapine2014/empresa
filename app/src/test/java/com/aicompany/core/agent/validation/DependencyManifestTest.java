package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.PackageRequest;
import com.aicompany.core.model.DependencyRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DependencyManifestTest {

    @Test
    void readsExactPubDependenciesAndIgnoresSdkEntries() {
        var parsed = DependencyManifest.pubspec("""
                name: app
                environment:
                  sdk: ^3.5.0
                dependencies:
                  flutter:
                    sdk: flutter
                  equatable: 2.0.5
                dev_dependencies:
                  flutter_test:
                    sdk: flutter
                  flutter_lints: 4.0.0
                flutter:
                  uses-material-design: true
                """);
        assertEquals(List.of(), parsed.errors());
        assertEquals(List.of(new DependencyRef("PUB", "equatable", "2.0.5"), new DependencyRef("PUB", "flutter_lints", "4.0.0")),
                parsed.deps());
    }

    // Review Focus: rangos y path/git son errores corregibles.
    @Test
    void rangesPathAndGitAreRejected() {
        var parsed = DependencyManifest.pubspec("""
                dependencies:
                  a: ^1.0.0
                  b: any
                  c:
                    path: ../c
                  d:
                    git: https://x
                """);
        assertEquals(4, parsed.errors().size(), parsed.errors().toString());
        assertTrue(parsed.errors().get(0).contains("a"));
    }

    @Test
    void nugetRequestsMustBeExact() {
        assertEquals(List.of(), DependencyManifest.validateRequests(List.of(new PackageRequest("Newtonsoft.Json", "13.0.3"))));
        assertEquals(2, DependencyManifest.validateRequests(List.of(new PackageRequest("A", "[1.0,2.0)"),
                new PackageRequest("B; rm", "1.0.0"))).size());
    }
}
