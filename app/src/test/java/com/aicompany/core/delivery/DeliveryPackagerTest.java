package com.aicompany.core.delivery;

import com.aicompany.core.service.GitCommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryPackagerTest {

    @TempDir Path tmp;
    DeliveryPackager packager;
    String sha;

    @BeforeEach
    void setUp() throws Exception {
        var git = new GitCommandRunner();
        var repo = Files.createDirectories(tmp.resolve("products/M-1"));
        git.run(repo, "init", "-q");
        Files.writeString(repo.resolve("README.md"), "hola");
        git.run(repo, "add", ".");
        git.run(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "x");
        sha = git.run(repo, "rev-parse", "HEAD").trim();
        packager = new DeliveryPackager(tmp.resolve("entregas"), git, tmp.resolve("products"));
    }

    static List<String> zipEntries(Path zip) throws IOException {
        var names = new ArrayList<String>();
        try (var z = new ZipFile(zip.toFile())) {
            z.stream().forEach(e -> names.add(e.getName()));
        }
        return names;
    }

    @Test
    void theZipHasSourceBundleBuildsDocsAndMigrations() throws Exception {
        var staging = packager.stagingDir("M-1", 2);
        Files.createDirectories(staging.resolve("juego/linux"));
        Files.writeString(staging.resolve("juego/linux/Juego.x86_64"), "bin");
        var zip = packager.assemble(new DeliveryPackager.Assembly("M-1", 2, sha, "GODOT_DOTNET_GAME", staging, "# doc",
                Map.of(), Map.of("V1__crear.sql", "create table t(id int);")));
        assertEquals(packager.zipPath("M-1", 2), zip);
        var names = zipEntries(zip);
        assertTrue(names.containsAll(List.of("ENTREGA.md", "juego/linux/Juego.x86_64", "codigo-fuente/README.md",
                "codigo-fuente.bundle", "base-de-datos/migraciones/V1__crear.sql")), names.toString());
        assertFalse(Files.exists(staging));
        assertFalse(Files.exists(zip.resolveSibling("incremento-2.zip.part")));
    }

    @Test
    void aFailedAssemblyLeavesNoZip() throws Exception {
        assertThrows(IOException.class, () -> packager.assemble(new DeliveryPackager.Assembly("M-1", 1, "f".repeat(40),
                "GODOT_DOTNET_GAME", packager.stagingDir("M-1", 1), "# doc", Map.of(), Map.of())));
        assertFalse(Files.exists(packager.zipPath("M-1", 1)));
        try (var s = Files.list(packager.zipPath("M-1", 1).getParent())) {
            assertEquals(0, s.count());
        }
    }

    @Test
    void regeneratingOverwritesTheZip() throws Exception {
        for (var doc : List.of("# uno", "# dos")) {
            packager.assemble(new DeliveryPackager.Assembly("M-1", 1, sha, "X", packager.stagingDir("M-1", 1), doc,
                    Map.of("docker/Dockerfile", "FROM x"), Map.of()));
        }
        var zip = packager.zipPath("M-1", 1);
        try (var s = Files.list(zip.getParent())) {
            assertEquals(1, s.count());
        }
        try (var z = new ZipFile(zip.toFile())) {
            assertEquals("# dos", new String(z.getInputStream(z.getEntry("ENTREGA.md")).readAllBytes()));
            assertNotNull(z.getEntry("docker/Dockerfile"));
        }
    }

    @Test
    void aMissionIdWithPathTraversalIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> packager.zipPath("../x", 1));
    }
}
