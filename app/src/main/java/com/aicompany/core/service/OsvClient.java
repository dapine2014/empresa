package com.aicompany.core.service;

import com.aicompany.core.model.DependencyRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Vulnerabilidades conocidas de un paquete en OSV (api.osv.dev, spec §3). Bloqueantes: HIGH/CRITICAL y las de
 * severidad desconocida (criterio conservador). Si OSV no responde, vacío: la política lo deja pendiente.
 */
public class OsvClient {

    private static final Logger log = LoggerFactory.getLogger(OsvClient.class);

    private final RestClient client;

    public OsvClient(RestClient client) {
        this.client = client;
    }

    @SuppressWarnings("unchecked")
    public Optional<List<String>> blockingVulnerabilities(DependencyRef ref) {
        try {
            var body = Map.of("package", Map.of("name", ref.name(),
                            "ecosystem", "NUGET".equals(ref.ecosystem()) ? "NuGet" : "Pub"),
                    "version", ref.version());
            var response = client.post().uri("/v1/query").body(body).retrieve().body(Map.class);
            var vulns = response == null ? List.<Map<String, Object>>of()
                    : (List<Map<String, Object>>) response.getOrDefault("vulns", List.of());
            var blocking = new ArrayList<String>();
            for (var vuln : vulns) {
                var db = (Map<String, Object>) vuln.getOrDefault("database_specific", Map.of());
                var severity = String.valueOf(db.getOrDefault("severity", "UNKNOWN")).toUpperCase(Locale.ROOT);
                if (!List.of("LOW", "MODERATE", "MEDIUM").contains(severity)) {
                    blocking.add(String.valueOf(vuln.get("id")));
                }
            }
            return Optional.of(blocking);
        } catch (Exception ex) {
            log.warn("OSV_UNAVAILABLE package={} reason={}", ref.id(), ex.getMessage());
            return Optional.empty();
        }
    }
}
