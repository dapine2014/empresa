package com.forjai.sandbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Única API del runner (spec §2): nunca acepta comandos; perfil y pasos salen del catálogo. */
@RestController
public class JobController {

    public record JobRequest(String jobType, String missionId, String commitSha, String stackProfile,
                             DependencyRequest dependencies) {
    }

    private final VerifyJobRunner runner;
    private final DependencyFetcher fetcher;
    private final DependencyPromoter promoter;
    private final String token;

    public JobController(VerifyJobRunner runner, DependencyFetcher fetcher, DependencyPromoter promoter,
                         @Value("${sandbox.token}") String token) {
        this.runner = runner;
        this.fetcher = fetcher;
        this.promoter = promoter;
        this.token = token;
    }

    @PostMapping("/jobs")
    public ResponseEntity<?> run(
            @RequestHeader(value = "X-Sandbox-Token", required = false) String requestToken,
            @RequestBody JobRequest request) {

        if (requestToken == null || token == null || token.isBlank()
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                        requestToken.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).body(Map.of("error", "token inválido"));
        }

        if ("FETCH_DEPENDENCIES".equals(request.jobType()) && request.dependencies() != null) {
            return ResponseEntity.ok(fetcher.fetch(request.dependencies().ecosystem(), request.dependencies().packages()));
        }

        if ("PROMOTE_DEPENDENCIES".equals(request.jobType()) && request.dependencies() != null) {
            try {
                return ResponseEntity.ok(Map.of("promoted", promoter.promote(request.dependencies().ecosystem(),
                        request.dependencies().jobId(), request.dependencies().packages())));
            } catch (IllegalArgumentException | IllegalStateException ex) {
                return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
            }
        }

        if (!"VERIFY".equals(request.jobType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "jobType no soportado: " + request.jobType()));
        }

        var profile = ExecutionProfile.parse(request.stackProfile());
        if (profile.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "stackProfile desconocido: " + request.stackProfile()));
        }

        return ResponseEntity.ok(runner.verify(request.missionId(), request.commitSha(), profile.get()));
    }
}
