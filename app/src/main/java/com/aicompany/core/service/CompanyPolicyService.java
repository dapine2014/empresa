package com.aicompany.core.service;

import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.PolicySnapshot;
import com.aicompany.core.model.PolicyVersionSummary;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Company Financial Policies: parámetros de negocio de Forjai
 * (capital semilla, ventana de tiempo, umbrales de clasificación de
 * éxito, multiplicador de alerta de {@code ContradictionDetector}),
 * versionados y editables desde el Command Center. Mirror exacto de
 * {@link PromptMemoryService} -- mismo invariante duro: en todo
 * momento existe exactamente una relación {@code HAS_ACTIVE_POLICY}
 * por {@code CompanyPolicy}. Ver
 * docs/superpowers/specs/2026-09-22-financial-policies-design.md.
 *
 * <p>Catálogo fijo en código ({@link PolicyKey}): agregar una octava
 * política es un cambio de código, no de datos. Las reglas de dominio
 * deterministas ({@code netProfitUsd}, validación matemática de
 * {@code Calculation}, gates de evidencia) no viven acá -- nunca se
 * vuelven configurables.
 */
@Service
public class CompanyPolicyService {

    private final Driver driver;

    public CompanyPolicyService(Driver driver) {
        this.driver = driver;
    }

    private static final Map<PolicyKey, Double> DEFAULTS = defaults();

    private static Map<PolicyKey, Double> defaults() {
        var map = new LinkedHashMap<PolicyKey, Double>();
        map.put(PolicyKey.SEED_CAPITAL_USD, 50.0);
        map.put(PolicyKey.CHALLENGE_DAYS, 60.0);
        map.put(PolicyKey.CONTRADICTION_SEED_CAPITAL_MULTIPLE, 100.0);
        map.put(PolicyKey.SUCCESS_THRESHOLD_GOOD, 50.0);
        map.put(PolicyKey.SUCCESS_THRESHOLD_VERY_GOOD, 100.0);
        map.put(PolicyKey.SUCCESS_THRESHOLD_EXCELLENT, 1000.0);
        map.put(PolicyKey.SUCCESS_THRESHOLD_EXTRAORDINARY, 5000.0);
        return map;
    }

    /**
     * Idempotente: cualquier {@code PolicyKey} sin {@code HAS_ACTIVE_POLICY}
     * todavía recibe su versión 1 con el default histórico (los mismos
     * números que antes vivían hardcoded en {@code AppProperties}/
     * {@code MissionExecutor}/{@code CustomerService}/
     * {@code ContradictionDetector}). Llamado desde
     * {@code CompanyMemoryInitializer}.
     */
    public void ensureDefaultPolicies() {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                for (var entry : DEFAULTS.entrySet()) {
                    tx.run(
                            "MERGE (p:CompanyPolicy {key:$key}) "
                                    + "WITH p WHERE NOT (p)-[:HAS_ACTIVE_POLICY]->(:PolicyVersion) "
                                    + "MERGE (v:PolicyVersion {id: $key + '-v1'}) "
                                    + "ON CREATE SET v.key = $key, v.version = 1, v.value = $value, "
                                    + "v.createdBy = 'system', v.changeReason = 'Valor inicial de seed', "
                                    + "v.createdAt = $createdAt "
                                    + "MERGE (p)-[:HAS_POLICY_VERSION]->(v) "
                                    + "MERGE (p)-[:HAS_ACTIVE_POLICY]->(v)",
                            Map.of(
                                    "key", entry.getKey().name(),
                                    "value", entry.getValue(),
                                    "createdAt", Instant.now().toString()));
                }
                return null;
            });
        }
    }

    /**
     * Hot path de lectura -- resuelto por {@code MissionExecutor}/
     * {@code CustomerService}/{@code ChatIntentRouter} antes de cada uso.
     */
    public double activeValue(PolicyKey key) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (:CompanyPolicy {key:$key})-[:HAS_ACTIVE_POLICY]->(v:PolicyVersion) "
                                    + "RETURN v.value AS value",
                            Map.of("key", key.name()))
                    .list(r -> r.get("value").asDouble())
                    .stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No hay política activa para " + key + " -- ¿faltó ensureDefaultPolicies()?"));
        }
    }

    public List<PolicySnapshot> snapshotAll() {
        var snapshots = new ArrayList<PolicySnapshot>();
        for (var key : PolicyKey.values()) {
            snapshots.add(snapshot(key));
        }
        return snapshots;
    }

    public PolicySnapshot snapshot(PolicyKey key) {
        try (var session = driver.session()) {

            var active = session.run(
                    "MATCH (:CompanyPolicy {key:$key})-[:HAS_ACTIVE_POLICY]->(v:PolicyVersion) "
                            + "RETURN v.version AS version, v.value AS value, "
                            + "v.createdBy AS createdBy, v.changeReason AS changeReason, v.createdAt AS createdAt",
                    Map.of("key", key.name())
            ).list();

            if (active.isEmpty()) {
                throw new IllegalStateException("No hay política activa para " + key);
            }

            var a = active.get(0);

            var history = session.run(
                    "MATCH (:CompanyPolicy {key:$key})-[:HAS_POLICY_VERSION]->(v:PolicyVersion) "
                            + "RETURN v.version AS version, v.value AS value, v.createdBy AS createdBy, "
                            + "v.changeReason AS changeReason, v.createdAt AS createdAt ORDER BY v.version DESC",
                    Map.of("key", key.name())
            ).list(r -> new PolicyVersionSummary(
                    r.get("version").asInt(),
                    r.get("value").asDouble(),
                    r.get("createdBy").asString(),
                    r.get("changeReason").asString(),
                    Instant.parse(r.get("createdAt").asString())
            ));

            return new PolicySnapshot(
                    key.name(),
                    a.get("version").asInt(),
                    a.get("value").asDouble(),
                    a.get("createdBy").asString(),
                    a.get("changeReason").asString(),
                    Instant.parse(a.get("createdAt").asString()),
                    history
            );
        }
    }

    /**
     * Crea una versión nueva y la activa en la misma transacción --
     * a diferencia del prompt de agentes, acá no hay un paso de
     * "borrador": editar una política es siempre efectivo de inmediato.
     */
    public PolicySnapshot createVersion(PolicyKey key, double value, String changeReason) {

        if (changeReason == null || changeReason.isBlank()) {
            throw new IllegalArgumentException("changeReason no puede estar vacío");
        }

        if (value <= 0) {
            throw new IllegalArgumentException("El valor de una política financiera debe ser positivo");
        }

        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                var result = tx.run(
                        "MATCH (p:CompanyPolicy {key:$key}) "
                                + "OPTIONAL MATCH (p)-[:HAS_POLICY_VERSION]->(existing:PolicyVersion) "
                                + "WITH p, coalesce(max(existing.version), 0) + 1 AS nextVersion "
                                + "CREATE (v:PolicyVersion {id: $key + '-v' + toString(nextVersion), "
                                + "key: $key, version: nextVersion, value: $value, "
                                + "createdBy: 'human', changeReason: $changeReason, createdAt: $createdAt}) "
                                + "MERGE (p)-[:HAS_POLICY_VERSION]->(v) "
                                + "WITH p, v "
                                + "OPTIONAL MATCH (p)-[old:HAS_ACTIVE_POLICY]->(:PolicyVersion) "
                                + "DELETE old "
                                + "CREATE (p)-[:HAS_ACTIVE_POLICY]->(v) "
                                + "RETURN v",
                        Map.of(
                                "key", key.name(), "value", value, "changeReason", changeReason,
                                "createdAt", Instant.now().toString()));

                if (result.list().isEmpty()) {
                    throw new IllegalArgumentException("No existe la política " + key);
                }

                return null;
            });
        }

        return snapshot(key);
    }

    /** Rollback: reactiva una versión existente, nunca crea contenido nuevo. */
    public PolicySnapshot activateVersion(PolicyKey key, int version) {

        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                var result = tx.run(
                        "MATCH (p:CompanyPolicy {key:$key})-[:HAS_POLICY_VERSION]->(v:PolicyVersion {version:$version}) "
                                + "OPTIONAL MATCH (p)-[old:HAS_ACTIVE_POLICY]->(:PolicyVersion) "
                                + "DELETE old "
                                + "CREATE (p)-[:HAS_ACTIVE_POLICY]->(v) "
                                + "RETURN v",
                        Map.of("key", key.name(), "version", version));

                if (result.list().isEmpty()) {
                    throw new IllegalArgumentException(
                            "No existe la versión " + version + " para la política " + key);
                }

                return null;
            });
        }

        return snapshot(key);
    }
}
