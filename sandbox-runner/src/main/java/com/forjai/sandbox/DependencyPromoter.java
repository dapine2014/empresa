package com.forjai.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** PROMOTE_DEPENDENCIES: staging → caché aprobada (~/forjai-deps/nuget | pub). Solo copia archivos. */
public class DependencyPromoter {

    private static final Pattern JOB_ID = Pattern.compile("^fetch-[A-Za-z0-9-]{1,60}$");

    private final Path depsRoot;

    public DependencyPromoter(Path depsRoot) {
        this.depsRoot = depsRoot;
    }

    public List<String> promote(String ecosystem, String jobId, List<DependencyRequest.Package> packages) {
        if (jobId == null || !JOB_ID.matcher(jobId).matches()) {
            throw new IllegalArgumentException("jobId inválido: " + jobId);
        }
        var staged = depsRoot.resolve("staging").resolve(jobId).resolve("packages");
        if (!Files.isDirectory(staged)) {
            throw new IllegalStateException("No existe el staging del job " + jobId + ".");
        }
        var promoted = new ArrayList<String>();
        for (var p : packages) {
            if (!DependencyRequest.valid(p)) {
                throw new IllegalArgumentException("Paquete inválido: " + p);
            }
            try {
                if ("NUGET".equals(ecosystem)) {
                    var rel = Path.of(p.name().toLowerCase(Locale.ROOT), p.version().toLowerCase(Locale.ROOT));
                    copyTree(staged.resolve(rel), depsRoot.resolve("nuget").resolve(rel));
                } else if ("PUB".equals(ecosystem)) {
                    var folder = p.name() + "-" + p.version();
                    copyTree(staged.resolve("hosted/pub.dev").resolve(folder), depsRoot.resolve("pub/hosted/pub.dev").resolve(folder));
                    var hash = staged.resolve("hosted-hashes/pub.dev").resolve(folder + ".sha256");
                    if (Files.isRegularFile(hash)) {
                        var target = depsRoot.resolve("pub/hosted-hashes/pub.dev").resolve(folder + ".sha256");
                        Files.createDirectories(target.getParent());
                        Files.copy(hash, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } else {
                    throw new IllegalArgumentException("Ecosistema inválido: " + ecosystem);
                }
                promoted.add(p.name() + "@" + p.version());
            } catch (IOException ex) {
                throw new IllegalStateException("No se pudo promover " + p.name() + "@" + p.version() + ": " + ex.getMessage(), ex);
            }
        }
        return promoted;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            throw new IOException("no está en el staging: " + source.getFileName());
        }
        try (var walk = Files.walk(source)) {
            for (var path : walk.toList()) {
                var dest = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
