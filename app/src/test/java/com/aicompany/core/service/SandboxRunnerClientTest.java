package com.aicompany.core.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SandboxRunnerClientTest {

    private static final String RESULT = """
            {"overall":"PASS","steps":[{"name":"test","status":"PASS","exitCode":0,"durationMs":10,
            "outputTail":"","testsPassed":2,"testsFailed":0}]}""";

    @Test
    void sendsTheTokenAndParsesTheResult() {
        var builder = RestClient.builder().baseUrl("http://runner");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://runner/jobs"))
                .andExpect(header("X-Sandbox-Token", "t0k"))
                .andExpect(jsonPath("$.jobType").value("VERIFY"))
                .andExpect(jsonPath("$.stackProfile").value("DOTNET_APP"))
                .andRespond(withSuccess(RESULT, MediaType.APPLICATION_JSON));

        var client = new SandboxRunnerClient(builder.build(), "t0k");
        var result = client.verify("M-1", "a".repeat(40), "DOTNET_APP");

        assertTrue(result.isPresent());
        assertEquals(2, result.get().testsPassed());
        server.verify();
    }

    // Review Focus: runner caído o error → vacío con motivo, nunca excepción ni resultado inventado.
    @Test
    void aRunnerFailureIsEmptyWithAReason() {
        var builder = RestClient.builder().baseUrl("http://runner");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://runner/jobs")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        var client = new SandboxRunnerClient(builder.build(), "t0k");

        assertTrue(client.verify("M-1", "a".repeat(40), "DOTNET_APP").isEmpty());
        assertNotNull(client.lastError());
    }

    @Test
    void fetchDependenciesSendsTheRequestAndParsesThePackages() {
        var builder = RestClient.builder().baseUrl("http://runner");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://runner/jobs"))
                .andExpect(jsonPath("$.jobType").value("FETCH_DEPENDENCIES"))
                .andExpect(jsonPath("$.dependencies.ecosystem").value("NUGET"))
                .andExpect(jsonPath("$.dependencies.packages[0].name").value("Newtonsoft.Json"))
                .andRespond(withSuccess("""
                        {"jobId":"fetch-1","status":"PASS","outputTail":"",
                         "packages":[{"name":"Newtonsoft.Json","version":"13.0.3","licenseExpression":"MIT",
                                      "licenseText":null,"buildCode":false,"buildCodeFiles":[]}]}""", MediaType.APPLICATION_JSON));

        var client = new SandboxRunnerClient(builder.build(), "t0k");
        var result = client.fetchDependencies("NUGET",
                java.util.List.of(new com.aicompany.core.model.DependencyRef("NUGET", "Newtonsoft.Json", "13.0.3")));

        assertEquals("MIT", result.orElseThrow().packages().get(0).licenseExpression());
        server.verify();
    }

    @Test
    void aFailedPromotionIsFalse() {
        var builder = RestClient.builder().baseUrl("http://runner");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://runner/jobs")).andRespond(withBadRequest().body("{\"error\":\"No existe el staging\"}"));

        var client = new SandboxRunnerClient(builder.build(), "t0k");

        assertFalse(client.promoteDependencies("NUGET", "fetch-x",
                java.util.List.of(new com.aicompany.core.model.DependencyRef("NUGET", "A", "1.0.0"))));
        assertNotNull(client.lastError());
    }

    @Test
    void packageJobSendsTargetsAndTheAndroidRelease() {
        var builder = RestClient.builder().baseUrl("http://runner");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://runner/jobs"))
                .andExpect(jsonPath("$.jobType").value("PACKAGE"))
                .andExpect(jsonPath("$.increment").value(2))
                .andExpect(jsonPath("$.targets[0]").value("windows"))
                .andExpect(jsonPath("$.androidRelease.alias").value("forjai"))
                .andRespond(withSuccess(RESULT, MediaType.APPLICATION_JSON));

        var client = new SandboxRunnerClient(builder.build(), "t0k");
        var release = new com.aicompany.core.delivery.AndroidSigningKeyService.Plain("QUJD", "forjai", "clave123");
        assertTrue(client.packageJob("M-1", "a".repeat(40), "GODOT_DOTNET_GAME", 2, java.util.List.of("windows"), release).isPresent());
        server.verify();
    }

    @Test
    void packageJobFailureIsEmptyWithAReason() {
        var builder = RestClient.builder().baseUrl("http://runner");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://runner/jobs")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        var client = new SandboxRunnerClient(builder.build(), "t0k");
        assertTrue(client.packageJob("M-1", "a".repeat(40), "DOTNET_APP", 1, java.util.List.of("windows"), null).isEmpty());
        assertNotNull(client.lastError());
    }
}
