package com.aicompany.core.delivery;

import com.aicompany.core.service.GitCommandRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Arma de forma atómica el zip de un incremento: ENTREGA.md, código, bundle, compilados, extras y migraciones. */
@Component
public class DeliveryPackager {

    private static final Pattern MISSION_ID = Pattern.compile("^[A-Z0-9-]+$");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    public record Assembly(String missionId, int increment, String commitSha, String stackProfile, Path staging,
                           String entregaMd, Map<String, String> extraFiles, Map<String, String> migrations) { }

    private final Path deliveriesRoot;
    private final GitCommandRunner git;
    private final Path productsRoot;

    @Autowired
    public DeliveryPackager(@Value("${products.deliveries-root}") String deliveriesRoot, GitCommandRunner git,
                            @Value("${products.workspace-root}") String productsRoot) {
        this(Path.of(deliveriesRoot), git, Path.of(productsRoot));
    }

    public DeliveryPackager(Path deliveriesRoot, GitCommandRunner git, Path productsRoot) {
        this.deliveriesRoot = deliveriesRoot.toAbsolutePath().normalize();
        this.git = git;
        this.productsRoot = productsRoot.toAbsolutePath().normalize();
    }

    public Path stagingDir(String missionId, int increment) {
        checkMission(missionId);
        return deliveriesRoot.resolve(".staging").resolve(missionId).resolve("incremento-" + increment);
    }

    public Path zipPath(String missionId, int increment) {
        checkMission(missionId);
        return deliveriesRoot.resolve(missionId).resolve("incremento-" + increment + ".zip");
    }

    public Path assemble(Assembly a) throws IOException {
        checkMission(a.missionId());
        if (a.commitSha() == null || !SHA.matcher(a.commitSha()).matches()) {
            throw new IllegalArgumentException("Commit inválido: " + a.commitSha());
        }
        var zip = zipPath(a.missionId(), a.increment());
        var part = zip.resolveSibling(zip.getFileName() + ".part");
        var tmp = deliveriesRoot.resolve(".staging").resolve(a.missionId()).resolve("src-" + a.increment());
        var repo = productsRoot.resolve(a.missionId());
        try {
            Files.createDirectories(zip.getParent());
            deleteTree(tmp);
            Files.createDirectories(tmp);
            var srcZip = tmp.resolve("src.zip");
            var bundle = tmp.resolve("codigo-fuente.bundle");
            git.run(repo, "archive", "--format=zip", "-o", srcZip.toString(), a.commitSha());
            git.run(repo, "bundle", "create", bundle.toString(), "--all");

            try (OutputStream out = Files.newOutputStream(part); var zos = new ZipOutputStream(out)) {
                putText(zos, "ENTREGA.md", a.entregaMd());
                try (var src = new ZipFile(srcZip.toFile())) {
                    for (var e : java.util.Collections.list(src.entries())) {
                        var entry = new ZipEntry("codigo-fuente/" + e.getName());
                        zos.putNextEntry(entry);
                        if (!e.isDirectory()) {
                            try (var in = src.getInputStream(e)) {
                                in.transferTo(zos);
                            }
                        }
                        zos.closeEntry();
                    }
                }
                putFile(zos, "codigo-fuente.bundle", bundle);
                var staging = a.staging();
                if (staging != null && Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
                    try (var walk = Files.walk(staging)) {
                        for (var p : (Iterable<Path>) walk.sorted()::iterator) {
                            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                                putFile(zos, staging.relativize(p).toString().replace('\\', '/'), p);
                            }
                        }
                    }
                }
                if (a.extraFiles() != null) {
                    for (var e : a.extraFiles().entrySet()) {
                        putText(zos, e.getKey(), e.getValue());
                    }
                }
                if (a.migrations() != null) {
                    for (var e : a.migrations().entrySet()) {
                        putText(zos, "base-de-datos/migraciones/" + e.getKey(), e.getValue());
                    }
                }
            }
            Files.move(part, zip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            deleteTree(a.staging());
            deleteTree(tmp);
            return zip;
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(part);
            deleteTree(tmp);
            throw ex;
        }
    }

    private static void checkMission(String missionId) {
        if (missionId == null || !MISSION_ID.matcher(missionId).matches()) {
            throw new IllegalArgumentException("missionId inválido: " + missionId);
        }
    }

    private static void putText(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static void putFile(ZipOutputStream zos, String name, Path file) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        Files.copy(file, zos);
        zos.closeEntry();
    }

    private static void deleteTree(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (var p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
