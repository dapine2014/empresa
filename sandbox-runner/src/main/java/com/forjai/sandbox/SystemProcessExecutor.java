package com.forjai.sandbox;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class SystemProcessExecutor implements ProcessExecutor {

    @Override
    public Execution run(List<String> command, Path directory, long timeoutSeconds) {
        try {
            var process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
            var output = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    return "";
                }
            });
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                String partial;
                try {
                    partial = output.get(5, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    partial = output.getNow("");
                }
                return new Execution(-1, partial, true);
            }
            return new Execution(process.exitValue(), output.join(), false);
        } catch (IOException ex) {
            return new Execution(-1, "No se pudo ejecutar " + command.get(0) + ": " + ex.getMessage(), false);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Execution(-1, "Interrumpido", false);
        }
    }
}
