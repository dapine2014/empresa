package com.aicompany.core.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ejecuta git real vía ProcessBuilder (sin librería Git en el classpath).
 * Aislado de la config del usuario/sistema (GIT_CONFIG_GLOBAL/NOSYSTEM) y
 * con safe.directory=* porque en Docker el workspace es un bind mount de
 * otro dueño.
 */
@Component
public class GitCommandRunner {

    private static final long TIMEOUT_SECONDS = 30;

    public String run(Path dir, String... args) throws IOException {

        var command = new ArrayList<String>();
        command.add("git");
        command.add("-c");
        command.add("safe.directory=*");
        // Verificado en vivo: sin esto git devuelve "L\303\263gica.cs" entrecomillado para nombres con tildes.
        command.add("-c");
        command.add("core.quotepath=off");
        command.addAll(List.of(args));

        var builder = new ProcessBuilder(command).directory(dir.toFile());
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        // Las rutas vienen de agentes: siempre literales, nunca magia de pathspec (":(glob)...", ":!...").
        builder.environment().put("GIT_LITERAL_PATHSPECS", "1");

        var process = builder.start();
        var stderr = CompletableFuture.supplyAsync(() -> readQuietly(process.getErrorStream()));
        var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        boolean finished;

        try {
            finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("git " + args[0] + " interrumpido", ex);
        }

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("git " + args[0] + " superó el timeout de " + TIMEOUT_SECONDS + "s");
        }

        if (process.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", args) + " falló (" + process.exitValue() + "): "
                    + stderr.join().trim());
        }

        return stdout;
    }

    private static String readQuietly(InputStream stream) {
        try {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }
}
