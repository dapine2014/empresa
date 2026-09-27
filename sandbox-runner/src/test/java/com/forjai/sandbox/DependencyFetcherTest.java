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
        return new DependencyFetcher(deps, "unix:///s", "forjai-fetch", "localhost/forjai-sandbox/egress-proxy:1", executor);
    }

    @Test
    void theRestoreRunsOnTheInternalNetworkThroughTheProxyAndTheProxyIsAlwaysRemoved() {
        var result = fetcher((command, dir, timeout) -> {
            calls.add(command);
            return new ProcessExecutor.Execution(0, "ok", false);
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
}
