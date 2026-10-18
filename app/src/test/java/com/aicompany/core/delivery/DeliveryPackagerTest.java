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
        assertEquals(List.of(), leftovers());
    }

    @Test
    void theZipAndTheMissionFolderTakeTheOwnerOfTheDeliveriesRoot() throws Exception {
        var zip = packager.assemble(new DeliveryPackager.Assembly("M-1", 1, sha, "GODOT_DOTNET_GAME",
                packager.stagingDir("M-1", 1), "# doc", Map.of(), Map.of()));
        var owner = Files.getOwner(tmp.resolve("entregas"));
        assertEquals(owner, Files.getOwner(zip));
        assertEquals(owner, Files.getOwner(zip.getParent()));
    }

    /** Todo lo que no es un zip terminado: .part, temporales y staging. */
    List<Path> leftovers() throws IOException {
        try (var walk = Files.walk(tmp.resolve("entregas"))) {
            return walk.filter(Files::isRegularFile).filter(p -> !p.toString().endsWith(".zip")).toList();
        }
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
    void symlinksInTheStagingAreNeverFollowedNorPackaged() throws Exception {
        var outside = Files.createDirectories(tmp.resolve("fuera"));
        Files.writeString(outside.resolve("secreto.txt"), "SECRETO");
        var staging = packager.stagingDir("M-1", 3);
        Files.createDirectories(staging.resolve("juego"));
        Files.writeString(staging.resolve("juego/ok.bin"), "ok");
        Files.createSymbolicLink(staging.resolve("enlace-archivo"), outside.resolve("secreto.txt"));
        Files.createSymbolicLink(staging.resolve("enlace-dir"), outside);
        var zip = packager.assemble(new DeliveryPackager.Assembly("M-1", 3, sha, "X", staging, "# d", Map.of(), Map.of()));
        var names = zipEntries(zip);
        assertTrue(names.contains("juego/ok.bin"));
        assertTrue(names.stream().noneMatch(n -> n.contains("enlace") || n.contains("secreto")), names.toString());
        try (var z = new ZipFile(zip.toFile())) {
            for (var e : java.util.Collections.list(z.entries())) {
                if (!e.isDirectory()) {
                    assertFalse(new String(z.getInputStream(e).readAllBytes()).contains("SECRETO"), e.getName());
                }
            }
        }
    }

    @Test
    void aMissionIdWithPathTraversalIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> packager.zipPath("../x", 1));
    }

    // Revisión final (1): del staging solo entran juego/ y app/; nombres peligrosos y repetidos se omiten.
    @Test
    void stagingEntriesOutsideTheBuildsOrWithUnsafeNamesAreSkippedAndDuplicatesNeverBreakTheZip() throws Exception {
        var staging = packager.stagingDir("M-1", 1, "incremento-1-" + java.util.UUID.randomUUID());
        Files.createDirectories(staging.resolve("juego/windows"));
        Files.writeString(staging.resolve("juego/windows/Juego.exe"), "exe");
        Files.writeString(staging.resolve("juego/windows/..\\..\\..\\fuera.txt"), "zip-slip");
        Files.writeString(staging.resolve("juego/windows/con\u0007control"), "x");
        Files.writeString(staging.resolve("ENTREGA.md"), "falsa");
        Files.createDirectories(staging.resolve("docker"));
        Files.writeString(staging.resolve("docker/Dockerfile"), "FROM evil");
        Files.createDirectories(staging.resolve("base-de-datos"));
        Files.writeString(staging.resolve("base-de-datos/LEEME.md"), "falso");
        Files.createDirectories(staging.resolve("app"));
        Files.writeString(staging.resolve("app/index.html"), "<html>");

        var zip = packager.assemble(new DeliveryPackager.Assembly("M-1", 1, sha, "FLUTTER_WEB_APP", staging, "# real",
                Map.of("docker/Dockerfile", "FROM nginx", "base-de-datos/LEEME.md", "# real"), Map.of()));

        var names = zipEntries(zip);
        assertTrue(names.containsAll(List.of("juego/windows/Juego.exe", "app/index.html", "docker/Dockerfile")), names.toString());
        assertTrue(names.stream().noneMatch(n -> n.contains("..") || n.contains("\\") || n.contains("\u0007")), names.toString());
        assertEquals(names.size(), new java.util.HashSet<>(names).size(), names.toString());
        try (var z = new ZipFile(zip.toFile())) {
            assertEquals("# real", new String(z.getInputStream(z.getEntry("ENTREGA.md")).readAllBytes()));
            assertEquals("FROM nginx", new String(z.getInputStream(z.getEntry("docker/Dockerfile")).readAllBytes()));
            assertEquals("# real", new String(z.getInputStream(z.getEntry("base-de-datos/LEEME.md")).readAllBytes()));
        }
    }

    @Test
    void unsafeEntryNamesAreDetected() {
        for (var bad : List.of("..\\..\\x", "a/../b", "/abs", "a//b", "./a", "a/\u0001b", "")) {
            assertFalse(DeliveryPackager.safeEntryName(bad), bad);
        }
        assertTrue(DeliveryPackager.safeEntryName("juego/windows/data_Juego_windows_x86_64/Godot.dll"));
    }

    // Revisión final (3): la carpeta del job debe ser de este incremento; nunca una ruta libre.
    @Test
    void theJobStagingNameMustBelongToTheIncrement() {
        var job = "incremento-2-" + java.util.UUID.randomUUID();
        assertEquals(tmp.resolve("entregas/.staging/M-1").resolve(job).toAbsolutePath().normalize(),
                packager.stagingDir("M-1", 2, job));
        assertEquals(packager.stagingDir("M-1", 2), packager.stagingDir("M-1", 2, null));
        assertThrows(IllegalArgumentException.class, () -> packager.stagingDir("M-1", 2, "../../etc"));
        assertThrows(IllegalArgumentException.class, () -> packager.stagingDir("M-1", 2, "incremento-1-" + java.util.UUID.randomUUID()));
    }

    // Revisión final (4): el .part vive en .staging; un armado fallido no deja nada y borra el staging del job.
    @Test
    void aFailedAssemblyLeavesNothingBehindAndDiscardsTheJobStaging() throws Exception {
        var staging = packager.stagingDir("M-1", 1, "incremento-1-" + java.util.UUID.randomUUID());
        Files.createDirectories(staging.resolve("juego/linux"));
        Files.writeString(staging.resolve("juego/linux/Juego.x86_64"), "bin");
        assertThrows(IOException.class, () -> packager.assemble(new DeliveryPackager.Assembly("M-1", 1, "f".repeat(40),
                "GODOT_DOTNET_GAME", staging, "# doc", Map.of(), Map.of())));
        assertFalse(Files.exists(staging));
        assertEquals(List.of(), leftovers());
    }

    @Test
    void orphansFromAnotherProcessAndOldRunnerStagingAreCleanedAtStartup() throws Exception {
        var mission = Files.createDirectories(tmp.resolve("entregas/.staging/M-1"));
        Files.writeString(mission.resolve("incremento-1-otro-proceso.zip.part"), "a medias");
        Files.createDirectories(mission.resolve("src-1-otro-proceso"));
        var old = Files.createDirectories(mission.resolve("incremento-1-" + java.util.UUID.randomUUID()));
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofHours(2))));
        var fresh = Files.createDirectories(mission.resolve("incremento-2-" + java.util.UUID.randomUUID()));

        packager.cleanOrphans();

        try (var left = Files.list(mission)) {
            assertEquals(List.of(fresh.getFileName().toString()), left.map(p -> p.getFileName().toString()).toList());
        }
    }
}
