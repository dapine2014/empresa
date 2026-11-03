package com.aicompany.core.service;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.Mockups;
import com.aicompany.core.agent.model.VisualDesign;
import com.aicompany.core.model.CssTokens;
import com.aicompany.core.model.SandboxResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec 2026-11-01 front con diseño visual: maquetas commiteadas por Forjai y capturadas en el sandbox. */
class DesignCaptureServiceTest {

    private static final String SHA = "c".repeat(40);
    private static final String JOB = "0f8fad5b-d9cb-469f-a165-70867728950e";

    @TempDir
    Path deliveries;

    private final SandboxRunnerClient runner = mock(SandboxRunnerClient.class);
    private final DevelopmentWorkspaceService workspace = mock(DevelopmentWorkspaceService.class);
    private final VisualDesignMemoryService visuals = mock(VisualDesignMemoryService.class);

    private DesignCaptureService service() {
        return new DesignCaptureService(runner, workspace, visuals, JsonMapper.builder().build(), deliveries);
    }

    private static VisualDesign design() {
        return new VisualDesign(VisualDesignWriterTest.system("#FFFFFF"), new Mockups(List.of(
                new Mockups.Mockup("PANT-01", "<div data-id=\"PANT-01\">x</div>")),
                List.of(new Mockups.SvgAsset("logo", "<svg></svg>"))));
    }

    private Path staging(String mission, String dir) throws Exception {
        var path = deliveries.resolve(".staging").resolve(mission).resolve(dir);
        Files.createDirectories(path);
        return path;
    }

    @Test
    void commitsTheMockupsAndReadsTheCapture() throws Exception {
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-MOCKUPS"), eq("forjai"), eq("Forjai"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA, List.of()));
        var dir = "diseno-1-mockups-" + JOB;
        when(runner.designCapture("M-1", SHA, 1, "MOCKUPS"))
                .thenReturn(Optional.of(new SandboxResult("PASS", List.of(), dir)));
        var staging = staging("M-1", dir);
        Files.write(staging.resolve("PANT-01-phone.png"), new byte[]{1, 2});
        Files.writeString(staging.resolve("PANT-01-phone.json"),
                "[{\"id\":\"btn\",\"x\":16,\"y\":139,\"w\":358,\"h\":48,\"color\":\"#1E5AA8\"},"
                        + "{\"id\":\"fuera\",\"x\":0,\"y\":900,\"w\":10,\"h\":10,\"color\":null}]");
        Files.writeString(staging.resolve("notas.txt"), "x");

        var captures = service().captureMockups("M-1", 1, 1, design());

        verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-MOCKUPS"), eq("forjai"), eq("Forjai"), argThat(r -> {
            var byPath = r.files().stream().collect(java.util.stream.Collectors.toMap(
                    DevelopmentResult.GeneratedFile::path, DevelopmentResult.GeneratedFile::content));
            return byPath.get(".forjai/mockups/PANT-01.html").equals(CssTokens.page(design().mockups().screens().get(0),
                    design().system())) && byPath.get(".forjai/mockups/svg/logo.svg").equals("<svg></svg>");
        }));
        assertEquals(Set.of("PANT-01-phone"), captures.pngByScreenSize().keySet());
        assertEquals(new com.aicompany.core.model.Box("btn", 16, 139, 358, 48, "#1E5AA8"),
                captures.boxesByScreenSize().get("PANT-01-phone").get(0));
        assertNull(captures.boxesByScreenSize().get("PANT-01-phone").get(1).color());
        // Revisión R8: el job da PASS aunque falte una pantalla; Java compara lo esperado con lo recibido.
        assertEquals(List.of("PANT-01-desktop"), captures.missing(List.of("PANT-01")));
        verify(visuals).setCaptureDir("M-1", 1, dir);
    }

    // Revisión final del diseño visual (I2): con git real, una maqueta que el ajuste quitó (v1 → v2) sale de
    // .forjai/mockups, así no se sigue capturando ni mostrando.
    @Test
    void anAdjustmentRemovesTheMockupsOfScreensThatAreNoLongerInTheDesign() throws Exception {
        var git = new GitCommandRunner();
        var real = new DevelopmentWorkspaceService(deliveries.resolve("products").toString(), git);
        var service = new DesignCaptureService(runner, real, visuals, JsonMapper.builder().build(), deliveries);
        var dir = "diseno-1-mockups-" + JOB;
        staging("M-1", dir);
        when(runner.designCapture(eq("M-1"), anyString(), eq(1), eq("MOCKUPS")))
                .thenReturn(Optional.of(new SandboxResult("PASS", List.of(), dir)));
        var v1 = new VisualDesign(VisualDesignWriterTest.system("#FFFFFF"), new Mockups(List.of(
                new Mockups.Mockup("PANT-01", "<div data-id=\"PANT-01\">x</div>"),
                new Mockups.Mockup("PANT-02", "<div data-id=\"PANT-02\">y</div>")),
                List.of(new Mockups.SvgAsset("logo", "<svg></svg>"), new Mockups.SvgAsset("viejo", "<svg></svg>"))));
        service.captureMockups("M-1", 1, 1, v1);
        // Un archivo del producto fuera de .forjai/mockups nunca se toca.
        real.commitAgentWork("M-1", "M-1-SCAFFOLD", "forjai", "Forjai", new DevelopmentResult("scaffold", List.of(
                new DevelopmentResult.GeneratedFile("lib/main.dart", "void main() {}"))));

        service.captureMockups("M-1", 1, 2, design());

        var head = real.filesAtCommit("M-1", real.headSha("M-1"));
        assertTrue(head.containsAll(List.of(".forjai/mockups/PANT-01.html", ".forjai/mockups/svg/logo.svg", "lib/main.dart")),
                head.toString());
        assertFalse(head.contains(".forjai/mockups/PANT-02.html"), head.toString());
        assertFalse(head.contains(".forjai/mockups/svg/viejo.svg"), head.toString());
        assertEquals(Optional.of("M-1-MOCKUPS-V2"), real.headTask("M-1"));
    }

    @Test
    void anAdjustedVersionCommitsUnderItsOwnTask() throws Exception {
        when(workspace.commitAgentWork(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA, List.of()));
        var dir = "diseno-1-mockups-" + JOB;
        staging("M-1", dir);
        when(runner.designCapture("M-1", SHA, 1, "MOCKUPS")).thenReturn(Optional.of(new SandboxResult("PASS", List.of(), dir)));

        service().captureMockups("M-1", 1, 3, design());

        verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-MOCKUPS-V3"), eq("forjai"), eq("Forjai"), any());
    }

    @Test
    void anUnavailableRunnerThrowsWithItsError() throws Exception {
        when(workspace.commitAgentWork(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA, List.of()));
        when(runner.designCapture(anyString(), anyString(), anyInt(), anyString())).thenReturn(Optional.empty());
        when(runner.lastError()).thenReturn("sandbox-runner no disponible: connection refused");

        var ex = assertThrows(IllegalStateException.class, () -> service().captureMockups("M-1", 1, 1, design()));

        assertTrue(ex.getMessage().contains("connection refused"));
        verify(visuals, never()).setCaptureDir(anyString(), anyInt(), anyString());
    }

    @Test
    void aFailedCaptureThrowsWithTheStep() throws Exception {
        when(workspace.commitAgentWork(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA, List.of()));
        when(runner.designCapture(anyString(), anyString(), anyInt(), anyString())).thenReturn(Optional.of(
                new SandboxResult("FAIL", List.of(new SandboxResult.StepResult("capture-mockups", "FAIL", 1, 10,
                        "Chrome no arrancó", 0, 0)))));

        var ex = assertThrows(IllegalStateException.class, () -> service().captureMockups("M-1", 1, 1, design()));

        assertTrue(ex.getMessage().contains("Chrome no arrancó"));
    }

    @Test
    void aStagingDirOutsideTheCapturePatternIsRejected() throws Exception {
        when(workspace.commitAgentWork(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord(SHA, List.of()));
        when(runner.designCapture(anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(Optional.of(new SandboxResult("PASS", List.of(), "../../etc")));

        assertThrows(IllegalStateException.class, () -> service().captureMockups("M-1", 1, 1, design()));
    }

    @Test
    void theAppCaptureKeepsOnlyTheRequestedScreens() throws Exception {
        var dir = "diseno-2-app-" + JOB;
        when(runner.designCapture("M-1", SHA, 2, "APP")).thenReturn(Optional.of(new SandboxResult("PASS", List.of(), dir)));
        var staging = staging("M-1", dir);
        Files.write(staging.resolve("PANT-02-phone.png"), new byte[]{1});
        Files.write(staging.resolve("PANT-01-phone.png"), new byte[]{1});

        var captures = service().captureApp("M-1", SHA, 2, Set.of("PANT-02"));

        assertEquals(Set.of("PANT-02-phone"), captures.pngByScreenSize().keySet());
        verifyNoInteractions(workspace);
    }

    @Test
    void theAppCaptureRunsInTheComponentDir() throws Exception {
        var dir = "diseno-2-app-" + JOB;
        when(runner.designCapture("M-1", SHA, 2, "APP", "app"))
                .thenReturn(Optional.of(new SandboxResult("PASS", List.of(), dir)));
        Files.write(staging("M-1", dir).resolve("PANT-01-phone.png"), new byte[]{1});

        var captures = service().captureApp("M-1", SHA, 2, Set.of("PANT-01"), "app");

        assertEquals(Set.of("PANT-01-phone"), captures.pngByScreenSize().keySet());
    }

    // Solo PANT-NN-(phone|desktop).png del staging de la última versión; cualquier otro nombre no existe.
    @Test
    void onlyImageNamesOfTheCapturePatternAreServed() throws Exception {
        var dir = "diseno-1-mockups-" + JOB;
        var staging = staging("M-1", dir);
        Files.write(staging.resolve("PANT-01-phone.png"), new byte[]{9});
        Files.writeString(staging.resolve("PANT-01-phone.json"), "[]");
        when(visuals.latest("M-1")).thenReturn(Optional.of(new VisualDesignMemoryService.Version(2, "{}", "DRAFT", 1, 1, "")));
        when(visuals.captureDir("M-1", 2)).thenReturn(Optional.of(dir));

        assertEquals(staging.resolve("PANT-01-phone.png"), service().mockupImage("M-1", "PANT-01-phone.png").orElseThrow());
        assertTrue(service().mockupImage("M-1", "PANT-01-phone.json").isEmpty());
        assertTrue(service().mockupImage("M-1", "PANT-1-phone.png").isEmpty());
        assertTrue(service().mockupImage("M-1", "../PANT-01-phone.png").isEmpty());
        assertTrue(service().mockupImage("M-1", "PANT-01-desktop.png").isEmpty());
        assertTrue(service().mockupImage("../M-1", "PANT-01-phone.png").isEmpty());
        assertEquals(Set.of("PANT-01-phone"), service().mockupImages("M-1").keySet());
    }

    @Test
    void withoutARecordedCaptureThereAreNoImages() {
        when(visuals.latest("M-1")).thenReturn(Optional.of(new VisualDesignMemoryService.Version(1, "{}", "DRAFT", 1, 1, "")));
        when(visuals.captureDir("M-1", 1)).thenReturn(Optional.empty());

        assertTrue(service().mockupImage("M-1", "PANT-01-phone.png").isEmpty());
        assertTrue(service().mockupCaptures("M-1", 1).isEmpty());
    }

    // Task 11: la versión aprobada sin captura se vuelve a capturar desde un commit que ya tiene las maquetas, sin commit.
    @Test
    void recapturesTheMockupsFromAnExistingCommitWithoutCommitting() throws Exception {
        var dir = "diseno-1-mockups-" + JOB;
        when(runner.designCapture("M-1", SHA, 1, "MOCKUPS")).thenReturn(Optional.of(new SandboxResult("PASS", List.of(), dir)));
        Files.write(staging("M-1", dir).resolve("PANT-01-phone.png"), new byte[]{1});

        var captures = service().recaptureMockups("M-1", SHA, 1, 2);

        assertEquals(Set.of("PANT-01-phone"), captures.pngByScreenSize().keySet());
        verify(visuals).setCaptureDir("M-1", 2, dir);
        verifyNoInteractions(workspace);
    }

    // Revisión R15: la captura de la app queda registrada y sus PNG se sirven con la misma lista blanca y sin seguir enlaces.
    @Test
    void theAppCaptureIsRecordedAndOnlyItsCaptureNamesAreServed() throws Exception {
        var dir = "diseno-2-app-" + JOB;
        when(runner.designCapture("M-1", SHA, 2, "APP", "app"))
                .thenReturn(Optional.of(new SandboxResult("PASS", List.of(), dir)));
        var staging = staging("M-1", dir);
        Files.write(staging.resolve("PANT-01-phone.png"), new byte[]{1});
        Files.writeString(staging.resolve("PANT-01-phone.json"), "[]");
        var outside = Files.write(deliveries.resolve("secreto.png"), new byte[]{7});
        Files.createSymbolicLink(staging.resolve("PANT-02-phone.png"), outside);

        service().captureApp("M-1", SHA, 2, Set.of("PANT-01"), "app");

        verify(visuals).setAppCaptureDir("M-1", dir);
        when(visuals.appCaptureDir("M-1")).thenReturn(Optional.of(dir));
        assertEquals(staging.resolve("PANT-01-phone.png"), service().appImage("M-1", "PANT-01-phone.png").orElseThrow());
        assertTrue(service().appImage("M-1", "PANT-01-phone.json").isEmpty());
        assertTrue(service().appImage("M-1", "../PANT-01-phone.png").isEmpty());
        assertTrue(service().appImage("M-1", "PANT-01-desktop.png").isEmpty());
        assertTrue(service().appImage("M-1", "PANT-02-phone.png").isEmpty());
        assertTrue(service().appImage("../M-1", "PANT-01-phone.png").isEmpty());
        assertEquals(Set.of("PANT-01-phone"), service().appImages("M-1").keySet());
    }

    @Test
    void aRecordedAppDirOutsideTheAppPatternServesNothing() throws Exception {
        var dir = "diseno-1-mockups-" + JOB;
        Files.write(staging("M-1", dir).resolve("PANT-01-phone.png"), new byte[]{1});
        when(visuals.appCaptureDir("M-1")).thenReturn(Optional.of(dir));

        assertTrue(service().appImage("M-1", "PANT-01-phone.png").isEmpty());
        assertTrue(service().appImages("M-1").isEmpty());
    }
}
