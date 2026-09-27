package com.forjai.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PackageMetadataReaderTest {

    @TempDir
    Path staging;

    // Estructura real verificada: <id>/<version>/<id>.nuspec con <license type="expression">.
    @Test
    void readsNugetLicenseExpressionAndBuildCode() throws Exception {
        var dir = Files.createDirectories(staging.resolve("newtonsoft.json/13.0.3"));
        Files.writeString(dir.resolve("newtonsoft.json.nuspec"), """
                <package><metadata><id>Newtonsoft.Json</id><version>13.0.3</version>
                <license type="expression">MIT</license></metadata></package>""");
        var withBuild = Files.createDirectories(staging.resolve("evil.pkg/1.0.0/build"));
        Files.writeString(withBuild.resolve("evil.pkg.targets"), "<Project/>");
        Files.writeString(staging.resolve("evil.pkg/1.0.0/evil.pkg.nuspec"),
                "<package><metadata><id>Evil.Pkg</id><version>1.0.0</version><licenseUrl>http://x</licenseUrl></metadata></package>");

        var packages = PackageMetadataReader.nuget(staging);

        var json = packages.stream().filter(p -> p.name().equals("Newtonsoft.Json")).findFirst().orElseThrow();
        assertEquals("13.0.3", json.version());
        assertEquals("MIT", json.licenseExpression());
        assertFalse(json.buildCode());
        var evil = packages.stream().filter(p -> p.name().equals("Evil.Pkg")).findFirst().orElseThrow();
        assertNull(evil.licenseExpression());
        assertTrue(evil.buildCode());
        assertEquals(List.of("build/evil.pkg.targets"), evil.buildCodeFiles());
    }

    // Estructura real verificada: hosted/pub.dev/<name>-<version>/LICENSE.
    @Test
    void readsPubLicenseTextAndHooks() throws Exception {
        var pkg = Files.createDirectories(staging.resolve("hosted/pub.dev/equatable-2.0.5"));
        Files.writeString(pkg.resolve("LICENSE"), "MIT License\n\nPermission is hereby granted, free of charge");
        Files.createDirectories(staging.resolve("hosted/pub.dev/native_thing-0.1.0/hook"));

        var packages = PackageMetadataReader.pub(staging);

        var equatable = packages.stream().filter(p -> p.name().equals("equatable")).findFirst().orElseThrow();
        assertEquals("2.0.5", equatable.version());
        assertTrue(equatable.licenseText().startsWith("MIT License"));
        assertFalse(equatable.buildCode());
        assertTrue(packages.stream().filter(p -> p.name().equals("native_thing")).findFirst().orElseThrow().buildCode());
    }
}
