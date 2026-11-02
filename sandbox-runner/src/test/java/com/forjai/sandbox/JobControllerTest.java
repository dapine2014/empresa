package com.forjai.sandbox;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobControllerTest {

    private final VerifyJobRunner runner = mock(VerifyJobRunner.class);
    private final JobController controller = new JobController(runner, mock(DependencyFetcher.class), mock(DependencyPromoter.class), "secreto");

    private static JobController.JobRequest request(String type, String profile) {
        return new JobController.JobRequest(type, "M-1", "a".repeat(40), profile, null, null, null, null, null, null);
    }

    @Test
    void rejectsAMissingOrWrongToken() {
        assertEquals(401, controller.run(null, request("VERIFY", "DOTNET_APP")).getStatusCode().value());
        assertEquals(401, controller.run("otro", request("VERIFY", "DOTNET_APP")).getStatusCode().value());
        verifyNoInteractions(runner);
    }

    @Test
    void rejectsUnknownProfilesAndJobTypesWithoutRunningAnything() {
        assertEquals(400, controller.run("secreto", request("VERIFY", "bash -c evil")).getStatusCode().value());
        assertEquals(400, controller.run("secreto", request("EXEC", "DOTNET_APP")).getStatusCode().value());
        verifyNoInteractions(runner);
    }

    @Test
    void runsAValidVerifyJob() {
        var result = new SandboxResult("PASS", java.util.List.of());
        when(runner.verify("M-1", "a".repeat(40), ExecutionProfile.DOTNET_APP, "")).thenReturn(result);

        var response = controller.run("secreto", request("VERIFY", "DOTNET_APP"));

        assertEquals(200, response.getStatusCode().value());
        assertSame(result, response.getBody());
    }

    @Test
    void fetchAndPromoteAreRoutedWithTheToken() {
        var fetcher = mock(DependencyFetcher.class);
        var promoter = mock(DependencyPromoter.class);
        var c = new JobController(runner, fetcher, promoter, "secreto");
        var pkgs = List.of(new DependencyRequest.Package("A", "1.0.0"));
        when(fetcher.fetch("NUGET", pkgs)).thenReturn(new FetchedPackage.FetchResult("fetch-1", "PASS", "", List.of()));

        assertEquals(200, c.run("secreto", new JobController.JobRequest("FETCH_DEPENDENCIES", null, null, null, null,
                new DependencyRequest("NUGET", null, pkgs), null, null, null, null)).getStatusCode().value());
        assertEquals(401, c.run("otro", new JobController.JobRequest("PROMOTE_DEPENDENCIES", null, null, null, null,
                new DependencyRequest("NUGET", "fetch-1", pkgs), null, null, null, null)).getStatusCode().value());
        verifyNoInteractions(promoter);
    }

    // Spec 2026-10-02 B1 §3: carpetas de componente de una lista fija, como los perfiles.
    @Test
    void verifyRejectsAComponentDirOutsideTheCatalog() {
        var response = controller.run("secreto", new JobController.JobRequest("VERIFY", "M-1", "a".repeat(40),
                "DOTNET_APP", "src", null, null, null, null, null));
        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(runner);
    }

    @Test
    void verifyPassesTheComponentDir() {
        var result = new SandboxResult("PASS", java.util.List.of());
        when(runner.verify("M-1", "a".repeat(40), ExecutionProfile.FLUTTER_WEB_APP, "app")).thenReturn(result);
        var response = controller.run("secreto", new JobController.JobRequest("VERIFY", "M-1", "a".repeat(40),
                "FLUTTER_WEB_APP", "app", null, null, null, null, null));
        assertSame(result, response.getBody());
    }

    @Test
    void packageRejectsAnUnknownTarget() {
        var response = controller.run("secreto", new JobController.JobRequest("PACKAGE", "M-1", "a".repeat(40),
                "GODOT_DOTNET_GAME", null, null, 1, List.of("windows", "web"), null, null));
        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(runner);
    }

    private static JobController.JobRequest capture(String profile, Integer increment, String mode) {
        return new JobController.JobRequest("DESIGN_CAPTURE", "M-1", "a".repeat(40), profile, null, null, increment,
                null, null, mode);
    }

    // Diseño visual (spec 2026-11-01 §4 y §9): el modo sale de una lista fija, como los perfiles.
    @Test
    void designCaptureRejectsUnknownMode() {
        assertEquals(400, controller.run("secreto", capture("FLUTTER_WEB_APP", 1, "X")).getStatusCode().value());
        assertEquals(400, controller.run("secreto", capture("FLUTTER_WEB_APP", 1, null)).getStatusCode().value());
        assertEquals(400, controller.run("secreto", capture("DOTNET_APP", 1, "MOCKUPS")).getStatusCode().value());
        assertEquals(400, controller.run("secreto", capture("FLUTTER_WEB_APP", 0, "APP")).getStatusCode().value());
        verifyNoInteractions(runner);
    }

    @Test
    void designCaptureRunsCaptureStep() {
        var result = new SandboxResult("PASS", List.of(), "diseno-2-mockups-x");
        when(runner.capture("M-1", "a".repeat(40), ExecutionProfile.FLUTTER_WEB_APP, 2,
                ExecutionProfile.CaptureMode.MOCKUPS, "")).thenReturn(result);
        var response = controller.run("secreto", capture("FLUTTER_WEB_APP", 2, "MOCKUPS"));
        assertEquals(200, response.getStatusCode().value());
        assertSame(result, response.getBody());
    }

    // Revisión Task 11 (3): en una misión con varios componentes la app Flutter vive en su carpeta (app/).
    @Test
    void designCaptureValidatesAndPassesTheComponentDir() {
        var bad = new JobController.JobRequest("DESIGN_CAPTURE", "M-1", "a".repeat(40), "FLUTTER_WEB_APP", "../x", null,
                1, null, null, "APP");
        assertEquals(400, controller.run("secreto", bad).getStatusCode().value());
        verifyNoInteractions(runner);
        var result = new SandboxResult("PASS", List.of(), "diseno-1-app-x");
        when(runner.capture("M-1", "a".repeat(40), ExecutionProfile.FLUTTER_WEB_APP, 1,
                ExecutionProfile.CaptureMode.APP, "app")).thenReturn(result);
        var ok = new JobController.JobRequest("DESIGN_CAPTURE", "M-1", "a".repeat(40), "FLUTTER_WEB_APP", "app", null,
                1, null, null, "APP");
        assertSame(result, controller.run("secreto", ok).getBody());
    }
}
