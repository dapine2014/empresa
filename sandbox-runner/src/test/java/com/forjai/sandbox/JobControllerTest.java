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
        return new JobController.JobRequest(type, "M-1", "a".repeat(40), profile, null);
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
        when(runner.verify("M-1", "a".repeat(40), ExecutionProfile.DOTNET_APP)).thenReturn(result);

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

        assertEquals(200, c.run("secreto", new JobController.JobRequest("FETCH_DEPENDENCIES", null, null, null,
                new DependencyRequest("NUGET", null, pkgs))).getStatusCode().value());
        assertEquals(401, c.run("otro", new JobController.JobRequest("PROMOTE_DEPENDENCIES", null, null, null,
                new DependencyRequest("NUGET", "fetch-1", pkgs))).getStatusCode().value());
        verifyNoInteractions(promoter);
    }
}
