package com.aicompany.core.delivery;

import com.aicompany.core.service.GitCommandRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Arma de forma atómica el zip de un incremento: ENTREGA.md, código, bundle, compilados, extras y migraciones. El
 * {@code .part} y los temporales viven en {@code .staging/<missionId>/} (mismo sistema de archivos que el zip, así el
 * {@code ATOMIC_MOVE} sirve) con nombres propios de este proceso: un reinicio no deja nada a la vista y
 * {@link #cleanOrphans} los borra al arrancar.
 */
@Component
public class DeliveryPackager {

    private static final Logger log = LoggerFactory.getLogger(DeliveryPackager.class);
    private static final Pattern MISSION_ID = Pattern.compile("^[A-Z0-9-]+$");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    /** Del staging del runner solo entran los compilados (revisión final, 1). */
    private static final Set<String> BUILD_ROOTS = Set.of("juego", "app");
    /** Carpetas del staging que deja el runner: {@code incremento-N} (runner anterior) o {@code incremento-N-<jobId>}. */
    private static final Pattern RUNNER_STAGING = Pattern.compile("incremento-[0-9]+(-[0-9a-f-]{36})?");
    /** Lo que un reinicio pudo dejar del runner sin consumir: a esta edad ya no lo espera nadie. */
    static final Duration RUNNER_STAGING_MAX_AGE = Duration.ofHours(1);

    public record Assembly(String missionId, int increment, String commitSha, String stackProfile, Path staging,
                           String entregaMd, Map<String, String> extraFiles, Map<String, String> migrations) { }

    private final Path deliveriesRoot;
    private final GitCommandRunner git;
    private final Path productsRoot;
    /** Marca los temporales de este proceso: los de otro (un reinicio) son huérfanos. */
    private final String runId = UUID.randomUUID().toString();

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

    private Path stagingRoot() {
        return deliveriesRoot.resolve(".staging");
    }

    /** Staging de un runner anterior a las carpetas por job (sin {@code stagingDir} en la respuesta). */
    public Path stagingDir(String missionId, int increment) {
        checkMission(missionId);
        return stagingRoot().resolve(missionId).resolve("incremento-" + increment);
    }

    /**
     * La carpeta que devolvió el job PACKAGE ({@code incremento-N-<jobId>}); null → la del runner anterior. Un nombre
     * que no es de este incremento se rechaza (nunca una ruta libre).
     */
    public Path stagingDir(String missionId, int increment, String jobStaging) {
        if (jobStaging == null || jobStaging.isBlank()) {
            return stagingDir(missionId, increment);
        }
        checkMission(missionId);
        if (!jobStaging.matches("incremento-" + increment + "-[0-9a-f-]{36}")) {
            throw new IllegalArgumentException("Carpeta de staging inválida: " + jobStaging);
        }
        return stagingRoot().resolve(missionId).resolve(jobStaging);
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
        var unique = runId + "-" + UUID.randomUUID();
        var work = stagingRoot().resolve(a.missionId());
        var part = work.resolve("incremento-" + a.increment() + "-" + unique + ".zip.part");
        var tmp = work.resolve("src-" + a.increment() + "-" + unique);
        var repo = productsRoot.resolve(a.missionId());
        try {
            Files.createDirectories(zip.getParent());
            Files.createDirectories(tmp);
            var srcZip = tmp.resolve("src.zip");
            var bundle = tmp.resolve("codigo-fuente.bundle");
            git.run(repo, "archive", "--format=zip", "-o", srcZip.toString(), a.commitSha());
            git.run(repo, "bundle", "create", bundle.toString(), "--all");

            var written = new HashSet<String>();
            try (OutputStream out = Files.newOutputStream(part); var zos = new ZipOutputStream(out)) {
                putText(zos, written, "ENTREGA.md", a.entregaMd());
                try (var src = new ZipFile(srcZip.toFile())) {
                    for (var e : java.util.Collections.list(src.entries())) {
                        // El árbol lo escribió código generado: un nombre con barra invertida o .. tampoco entra (revisión final, 1).
                        var bare = e.isDirectory() && e.getName().endsWith("/")
                                ? e.getName().substring(0, e.getName().length() - 1) : e.getName();
                        if (!safeEntryName(bare)) {
                            log.warn("DELIVERY mission={} source entry skipped: {}", a.missionId(), printable(e.getName()));
                            continue;
                        }
                        if (!add(written, "codigo-fuente/" + e.getName())) {
                            continue;
                        }
                        zos.putNextEntry(new ZipEntry("codigo-fuente/" + e.getName()));
                        if (!e.isDirectory()) {
                            try (var in = src.getInputStream(e)) {
                                in.transferTo(zos);
                            }
                        }
                        zos.closeEntry();
                    }
                }
                putFile(zos, written, "codigo-fuente.bundle", bundle);
                var staging = a.staging();
                if (staging != null && Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
                    try (var walk = Files.walk(staging)) {
                        for (var p : (Iterable<Path>) walk.sorted()::iterator) {
                            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                                continue;
                            }
                            var name = staging.relativize(p).toString();
                            if (!safeEntryName(name) || !insideBuildRoot(name)) {
                                log.warn("DELIVERY mission={} staging entry skipped: {}", a.missionId(), printable(name));
                                continue;
                            }
                            putFile(zos, written, name, p);
                        }
                    }
                }
                if (a.extraFiles() != null) {
                    for (var e : a.extraFiles().entrySet()) {
                        putText(zos, written, e.getKey(), e.getValue());
                    }
                }
                if (a.migrations() != null) {
                    for (var e : a.migrations().entrySet()) {
                        putText(zos, written, "base-de-datos/migraciones/" + e.getKey(), e.getValue());
                    }
                }
            }
            Files.move(part, zip, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return zip;
        } finally {
            Files.deleteIfExists(part);
            deleteTree(tmp);
            discardStaging(a.staging());
        }
    }

    /** Borra la carpeta de staging de un job (consumida o abandonada). Nunca lanza. */
    public void discardStaging(Path staging) {
        if (staging == null || !staging.toAbsolutePath().normalize().startsWith(stagingRoot())) {
            return;
        }
        try {
            deleteTree(staging);
        } catch (IOException ex) {
            log.warn("DELIVERY staging {} not deleted: {}", staging, ex.getMessage());
        }
    }

    /**
     * Revisión final (4): al arrancar borra los {@code .part} y temporales de otro proceso (un reinicio a mitad del
     * armado) y el staging del runner que nadie consumió en {@link #RUNNER_STAGING_MAX_AGE}. Nunca lanza.
     */
    public void cleanOrphans() {
        if (!Files.isDirectory(stagingRoot(), LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        var cutoff = Instant.now().minus(RUNNER_STAGING_MAX_AGE);
        try (var missions = Files.list(stagingRoot())) {
            for (var mission : missions.filter(m -> Files.isDirectory(m, LinkOption.NOFOLLOW_LINKS)).toList()) {
                try (var entries = Files.list(mission)) {
                    for (var e : entries.toList()) {
                        var name = e.getFileName().toString();
                        var ours = name.contains(runId);
                        var leftover = (name.endsWith(".zip.part") || name.startsWith("src-")) && !ours;
                        var unconsumed = RUNNER_STAGING.matcher(name).matches()
                                && Files.getLastModifiedTime(e, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff);
                        if (leftover || unconsumed) {
                            deleteTree(e);
                            log.info("DELIVERY orphan removed: {}", e);
                        }
                    }
                } catch (IOException ex) {
                    log.warn("DELIVERY orphans in {} not cleaned: {}", mission, ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.warn("DELIVERY orphans not cleaned: {}", ex.getMessage());
        }
    }

    /** Dentro de {@code juego/} o {@code app/}: ENTREGA.md, docker/ y base-de-datos/ los escribe Java, nunca el staging. */
    private static boolean insideBuildRoot(String name) {
        var slash = name.indexOf('/');
        return slash > 0 && BUILD_ROOTS.contains(name.substring(0, slash));
    }

    /** Nombre de entrada del zip seguro: relativo, sin barra invertida, sin segmentos . o .. ni caracteres de control. */
    static boolean safeEntryName(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0) {
            return false;
        }
        for (var i = 0; i < name.length(); i++) {
            var c = name.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        for (var segment : name.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static String printable(String name) {
        return name.replaceAll("\\p{Cntrl}", "?");
    }

    private static void checkMission(String missionId) {
        if (missionId == null || !MISSION_ID.matcher(missionId).matches()) {
            throw new IllegalArgumentException("missionId inválido: " + missionId);
        }
    }

    /** false (y log) si la entrada ya está en el zip: una repetida haría fallar el armado entero. */
    private static boolean add(Set<String> written, String name) {
        if (written.add(name)) {
            return true;
        }
        log.warn("DELIVERY duplicate zip entry skipped: {}", printable(name));
        return false;
    }

    private static void putText(ZipOutputStream zos, Set<String> written, String name, String content) throws IOException {
        if (!add(written, name)) {
            return;
        }
        zos.putNextEntry(new ZipEntry(name));
        zos.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static void putFile(ZipOutputStream zos, Set<String> written, String name, Path file) throws IOException {
        if (!add(written, name)) {
            return;
        }
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
