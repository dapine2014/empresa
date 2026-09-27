package com.forjai.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DependencyFetcherTest {

    @TempDir
    Path deps;

    private final List<List<String>> calls = new ArrayList<>();

    private DependencyFetcher fetcher(ProcessExecutor executor) {
        return new DependencyFetcher(deps, "unix:///s", "forjai-fetch", "localhost/forjai-sandbox/egress-proxy:1", executor,
                3, java.time.Duration.ZERO);
    }

    private static final ProcessExecutor.Execution READY =
            new ProcessExecutor.Execution(0, "Accepting HTTP Socket connections at conn3 local=[::]:3128", false);

    @Test
    void theRestoreRunsOnTheInternalNetworkThroughTheProxyAndTheProxyIsAlwaysRemoved() {
        var result = fetcher((command, dir, timeout) -> {
            calls.add(command);
            return command.contains("logs") ? READY : new ProcessExecutor.Execution(0, "ok", false);
        }).fetch("NUGET", List.of(new DependencyRequest.Package("Newtonsoft.Json", "13.0.3")));

        assertEquals("PASS", result.status());
        var restore = calls.stream().filter(c -> c.contains("dotnet")).findFirst().orElseThrow();
        assertTrue(restore.contains("--network=forjai-fetch"), restore.toString());
        assertTrue(restore.stream().anyMatch(a -> a.startsWith("HTTPS_PROXY=http://forjai-egress-")), restore.toString());
        assertTrue(calls.stream().anyMatch(c -> c.containsAll(List.of("rm", "-f")) && c.stream().anyMatch(a -> a.startsWith("forjai-egress-"))));
    }

    // Review Focus: el proxy no arranca → FAIL con motivo, sin restore y sin proxy colgado.
    @Test
    void aProxyThatDoesNotStartFailsTheJob() {
        var result = fetcher((command, dir, timeout) -> {
            calls.add(command);
            var starting = command.contains("-d");
            return new ProcessExecutor.Execution(starting ? 125 : 0, starting ? "no such image" : "", false);
        }).fetch("PUB", List.of(new DependencyRequest.Package("equatable", "2.0.5")));

        assertEquals("FAIL", result.status());
        assertTrue(result.outputTail().contains("proxy"), result.outputTail());
        assertTrue(calls.stream().noneMatch(c -> c.contains("flutter")));
        assertTrue(calls.stream().anyMatch(c -> c.containsAll(List.of("rm", "-f"))));
    }

    @Test
    void invalidNamesOrVersionsAreRejectedBeforeRunningAnything() {
        var result = fetcher((command, dir, timeout) -> {
            calls.add(command);
            return new ProcessExecutor.Execution(0, "", false);
        }).fetch("NUGET", List.of(new DependencyRequest.Package("x;rm -rf /", "^1.0.0")));

        assertEquals("FAIL", result.status());
        assertTrue(calls.isEmpty());
    }

    // Verificado en vivo (MISSION-DEPS-VERIFY-2): la restauración arrancaba antes de que squid escuchara y fallaba
    // de forma intermitente. Se espera a que el proxy anuncie "Accepting HTTP Socket connections".
    @Test
    void theRestoreWaitsUntilTheProxyIsListening() {
        var logsCalls = new java.util.concurrent.atomic.AtomicInteger();
        fetcher((command, dir, timeout) -> {
            calls.add(command);
            if (command.contains("logs")) {
                return logsCalls.incrementAndGet() < 2 ? new ProcessExecutor.Execution(0, "starting", false) : READY;
            }
            return new ProcessExecutor.Execution(0, "", false);
        }).fetch("NUGET", List.of(new DependencyRequest.Package("A", "1.0.0")));

        var logsIndex = -1;
        var restoreIndex = -1;
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).contains("logs")) logsIndex = i;
            if (calls.get(i).contains("dotnet") && restoreIndex < 0) restoreIndex = i;
        }
        assertEquals(2, logsCalls.get());
        assertTrue(logsIndex < restoreIndex, calls.toString());
    }

    @Test
    void aProxyThatNeverBecomesReadyFailsWithoutRestoring() {
        var result = fetcher((command, dir, timeout) -> {
            calls.add(command);
            return new ProcessExecutor.Execution(0, "starting", false);
        }).fetch("NUGET", List.of(new DependencyRequest.Package("A", "1.0.0")));

        assertEquals("FAIL", result.status());
        assertTrue(result.outputTail().contains("no quedó listo"), result.outputTail());
        assertTrue(calls.stream().noneMatch(c -> c.contains("dotnet")));
    }
}
