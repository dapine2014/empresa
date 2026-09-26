package com.forjai.sandbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobControllerTest {

    private final VerifyJobRunner runner = mock(VerifyJobRunner.class);
    private final JobController controller = new JobController(runner, "secreto");

    private static JobController.JobRequest request(String type, String profile) {
        return new JobController.JobRequest(type, "M-1", "a".repeat(40), profile);
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
}
