package com.aicompany.core.service;

import com.aicompany.core.model.DependencyFetch;
import com.aicompany.core.model.DependencyRef;
import com.aicompany.core.model.SandboxResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.List;
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
        return verify(missionId, commitSha, stackProfile, "");
    }

    private MissionCancellations cancellations;

    /** Cancelar una misión en curso (2026-10-15): una misión cancelada no lanza más jobs al sandbox. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCancellations(MissionCancellations cancellations) {
        this.cancellations = cancellations;
    }

    /** Spec 2026-10-02 B1 §3: VERIFY de la carpeta de un componente ("" = raíz). */
    public Optional<SandboxResult> verify(String missionId, String commitSha, String stackProfile, String componentDir) {
        if (cancellations != null && cancellations.isCancelled(missionId)) {
            throw new MissionCancellations.MissionCancelledException(missionId);
        }
        try {
            lastError = null;
            var body = new java.util.LinkedHashMap<String, Object>(Map.of("jobType", "VERIFY", "missionId", missionId,
                    "commitSha", commitSha, "stackProfile", stackProfile));
            if (componentDir != null && !componentDir.isEmpty()) {
                body.put("componentDir", componentDir);
            }
            var result = client.post().uri("/jobs")
                    .header("X-Sandbox-Token", token == null ? "" : token)
                    .body(body)
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

    /** Entrega del producto: job PACKAGE (un paso por target). Nunca lanza; el body (con la clave) jamás se loguea. */
    public Optional<SandboxResult> packageJob(String missionId, String commitSha, String stackProfile, int increment,
                                              List<String> targets,
                                              com.aicompany.core.delivery.AndroidSigningKeyService.Plain release) {
        if (cancellations != null && cancellations.isCancelled(missionId)) {
            throw new MissionCancellations.MissionCancelledException(missionId);
        }
        try {
            lastError = null;
            var body = new java.util.LinkedHashMap<String, Object>();
            body.put("jobType", "PACKAGE");
            body.put("missionId", missionId);
            body.put("commitSha", commitSha);
            body.put("stackProfile", stackProfile);
            body.put("increment", increment);
            body.put("targets", targets);
            if (release != null) {
                body.put("androidRelease", Map.of("keystoreBase64", release.keystoreBase64(),
                        "alias", release.alias(), "password", release.password()));
            }
            var result = client.post().uri("/jobs")
                    .header("X-Sandbox-Token", token == null ? "" : token)
                    .body(body)
                    .retrieve()
                    .body(SandboxResult.class);
            if (result == null) {
                lastError = "El sandbox-runner no devolvió resultado.";
                return Optional.empty();
            }
            return Optional.of(result);
        } catch (MissionCancellations.MissionCancelledException ex) {
            throw ex;
        } catch (Exception ex) {
            lastError = "sandbox-runner no disponible: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            log.warn("SANDBOX_UNAVAILABLE mission={} job=PACKAGE reason={}", missionId, lastError);
            return Optional.empty();
        }
    }

    /** Parte 3: descarga aislada por el proxy con lista blanca. Nunca lanza (vacío + lastError). */
    public Optional<DependencyFetch> fetchDependencies(String ecosystem, List<DependencyRef> packages) {
        try {
            lastError = null;
            var result = client.post().uri("/jobs")
                    .header("X-Sandbox-Token", token == null ? "" : token)
                    .body(Map.of("jobType", "FETCH_DEPENDENCIES", "dependencies", Map.of("ecosystem", ecosystem,
                            "packages", packages.stream().map(p -> Map.of("name", p.name(), "version", p.version())).toList())))
                    .retrieve()
                    .body(DependencyFetch.class);
            return Optional.ofNullable(result);
        } catch (Exception ex) {
            lastError = "sandbox-runner no disponible para descargar dependencias: " + ex.getMessage();
            log.warn("DEPENDENCY_FETCH_UNAVAILABLE reason={}", lastError);
            return Optional.empty();
        }
    }

    /** Parte 3: staging → caché aprobada. false si el runner no pudo promover. */
    public boolean promoteDependencies(String ecosystem, String jobId, List<DependencyRef> packages) {
        try {
            lastError = null;
            client.post().uri("/jobs")
                    .header("X-Sandbox-Token", token == null ? "" : token)
                    .body(Map.of("jobType", "PROMOTE_DEPENDENCIES", "dependencies", Map.of("ecosystem", ecosystem,
                            "jobId", jobId,
                            "packages", packages.stream().map(p -> Map.of("name", p.name(), "version", p.version())).toList())))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception ex) {
            lastError = "No se pudo promover las dependencias: " + ex.getMessage();
            log.warn("DEPENDENCY_PROMOTE_FAILED job={} reason={}", jobId, lastError);
            return false;
        }
    }

    public String lastError() {
        return lastError;
    }
}
