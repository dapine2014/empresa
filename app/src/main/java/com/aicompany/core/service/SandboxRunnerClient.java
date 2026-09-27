package com.aicompany.core.service;

import com.aicompany.core.model.SandboxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/**
 * Cliente del sandbox-runner (spec 2026-09-26 §2). company-core nunca toca un motor de contenedores:
 * solo esta API. Cualquier falla → vacío + motivo (la misión queda UNVALIDATED, nunca VERIFIED).
 */
public class SandboxRunnerClient {

    private static final Logger log = LoggerFactory.getLogger(SandboxRunnerClient.class);

    private final RestClient client;
    private final String token;
    private volatile String lastError;

    public SandboxRunnerClient(RestClient client, String token) {
        this.client = client;
        this.token = token;
    }

    public Optional<SandboxResult> verify(String missionId, String commitSha, String stackProfile) {
        try {
            lastError = null;
            var result = client.post().uri("/jobs")
                    .header("X-Sandbox-Token", token == null ? "" : token)
                    .body(Map.of("jobType", "VERIFY", "missionId", missionId, "commitSha", commitSha,
                            "stackProfile", stackProfile))
                    .retrieve()
                    .body(SandboxResult.class);
            if (result == null) {
                lastError = "El sandbox-runner no devolvió resultado.";
                return Optional.empty();
            }
            return Optional.of(result);
        } catch (Exception ex) {
            lastError = "sandbox-runner no disponible: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            log.warn("SANDBOX_UNAVAILABLE mission={} reason={}", missionId, lastError);
            return Optional.empty();
        }
    }

    public String lastError() {
        return lastError;
    }
}
