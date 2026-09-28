package com.aicompany.core.service;

import com.aicompany.core.model.CatalogProduct;
import com.aicompany.core.model.CatalogStatus;
import com.aicompany.core.model.ProductChange;
import com.aicompany.core.model.ProductEvidence;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Neo4j del catálogo (spec 2026-09-28): (:Product), VALIDATED_BY/BUILT_BY a Mission y (:Product)-[:HAS_CHANGE]->
 * (:ProductChange) inmutable. Sin lógica de negocio: las reglas viven en ProductService/ProductReadiness.
 */
@Service
public class ProductMemoryService {

    private static final String RETURN_PRODUCT = "OPTIONAL MATCH (p)-[:VALIDATED_BY]->(v:Mission) "
            + "OPTIONAL MATCH (p)-[:BUILT_BY]->(b:Mission) "
            + "RETURN p, collect(DISTINCT v.id) AS validatedBy, collect(DISTINCT b.id) AS builtBy";

    private final Driver driver;

    public ProductMemoryService(Driver driver) {
        this.driver = driver;
    }

    public void create(CatalogProduct p) {
        write("CREATE (p:Product {id:$id}) SET p += $props", Map.of("id", p.id(), "props", props(p)));
    }

    public void save(CatalogProduct p) {
        write("MATCH (p:Product {id:$id}) SET p += $props", Map.of("id", p.id(), "props", props(p)));
    }

    public Optional<CatalogProduct> find(String id) {
        try (var session = driver.session()) {
            return session.run("MATCH (p:Product {id:$id}) " + RETURN_PRODUCT, Map.of("id", id == null ? "" : id))
                    .list(ProductMemoryService::product).stream().findFirst();
        }
    }

    public List<CatalogProduct> all() {
        try (var session = driver.session()) {
            return session.run("MATCH (p:Product) WITH p ORDER BY p.createdAt " + RETURN_PRODUCT)
                    .list(ProductMemoryService::product);
        }
    }

    public void addChange(String productId, ProductChange c) {
        var params = new HashMap<String, Object>();
        params.put("productId", productId);
        params.put("id", "PRODUCT-CHANGE-" + UUID.randomUUID());
        params.put("actor", c.actor());
        params.put("field", c.field());
        params.put("from", c.from());
        params.put("to", c.to());
        params.put("reason", c.reason());
        params.put("at", c.at().toString());
        write("MATCH (p:Product {id:$productId}) CREATE (p)-[:HAS_CHANGE]->(:ProductChange {id:$id, actor:$actor, "
                + "field:$field, from:$from, to:$to, reason:$reason, at:$at})", params);
    }

    public List<ProductChange> history(String productId) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Product {id:$id})-[:HAS_CHANGE]->(c:ProductChange) RETURN c ORDER BY c.at",
                    Map.of("id", productId)).list(r -> {
                var c = r.get("c");
                return new ProductChange(c.get("actor").asString(null), c.get("field").asString(null),
                        c.get("from").asString(null), c.get("to").asString(null), c.get("reason").asString(null),
                        Instant.parse(c.get("at").asString()));
            });
        }
    }

    public void link(String productId, List<String> validatedBy, List<String> builtBy) {
        write("MATCH (p:Product {id:$id}) OPTIONAL MATCH (p)-[r:VALIDATED_BY|BUILT_BY]->() DELETE r "
                        + "WITH DISTINCT p "
                        + "OPTIONAL MATCH (v:Mission) WHERE v.id IN $validatedBy "
                        + "FOREACH (_ IN CASE WHEN v IS NULL THEN [] ELSE [1] END | MERGE (p)-[:VALIDATED_BY]->(v)) "
                        + "WITH DISTINCT p "
                        + "OPTIONAL MATCH (b:Mission) WHERE b.id IN $builtBy "
                        + "FOREACH (_ IN CASE WHEN b IS NULL THEN [] ELSE [1] END | MERGE (p)-[:BUILT_BY]->(b))",
                Map.of("id", productId, "validatedBy", validatedBy, "builtBy", builtBy));
    }

    /** Demanda: discovery (sin teamId) con Evidence WEB en sus tareas. Construcción: una VALIDATION en VERIFIED. */
    public ProductEvidence evidence(CatalogProduct p) {
        try (var session = driver.session()) {
            var demand = session.run("MATCH (m:Mission)-[:HAS_TASK]->(:AgentTask)-[:HAS_EVIDENCE]->(:Evidence {sourceType:'WEB'}) "
                            + "WHERE m.id IN $ids AND m.teamId IS NULL RETURN count(m) > 0 AS ok",
                    Map.of("ids", p.validatedBy())).single().get("ok").asBoolean();
            var built = session.run("MATCH (m:Mission)-[:HAS_TASK]->(:AgentTask {kind:'VALIDATION', validationStatus:'VERIFIED'}) "
                            + "WHERE m.id IN $ids RETURN count(m) > 0 AS ok",
                    Map.of("ids", p.builtBy())).single().get("ok").asBoolean();
            return new ProductEvidence(demand, built);
        }
    }

    public boolean missionExists(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (m:Mission {id:$id}) RETURN count(m) > 0 AS ok", Map.of("id", missionId))
                    .single().get("ok").asBoolean();
        }
    }

    public List<String> productsBuiltBy(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (p:Product)-[:BUILT_BY]->(:Mission {id:$id}) RETURN p.id AS id",
                    Map.of("id", missionId)).list(r -> r.get("id").asString());
        }
    }

    /** Idea que la automatización creó a partir de esa misión de discovery (para no duplicarla en una ronda). */
    public Optional<String> ideaFromMission(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (p:Product {sourceMissionId:$id}) RETURN p.id AS id LIMIT 1", Map.of("id", missionId))
                    .list(r -> r.get("id").asString()).stream().findFirst();
        }
    }

    public void markSourceMission(String productId, String missionId) {
        write("MATCH (p:Product {id:$id}) SET p.sourceMissionId = $missionId", Map.of("id", productId, "missionId", missionId));
    }

    private static CatalogProduct product(Record r) {
        var p = r.get("p");
        return new CatalogProduct(p.get("id").asString(), p.get("name").asString(null), p.get("description").asString(null),
                p.get("kind").asString("SOFTWARE"), p.get("targetCustomer").asString(null), p.get("priceUsd").asDouble(0),
                p.get("priceOnRequest").asBoolean(false), p.get("estimatedCostUsd").asDouble(0),
                p.get("delivery").asString(null), p.get("markets").asList(v -> v.asString(), List.of("WORLDWIDE")),
                p.get("languages").asList(v -> v.asString(), List.of("en", "es")),
                CatalogStatus.valueOf(p.get("status").asString("IDEA")),
                p.get("statusBeforePause").isNull() ? null : CatalogStatus.valueOf(p.get("statusBeforePause").asString()),
                p.get("createdBy").asString(null), Instant.parse(p.get("createdAt").asString()),
                Instant.parse(p.get("updatedAt").asString()),
                r.get("validatedBy").asList(v -> v.asString()), r.get("builtBy").asList(v -> v.asString()));
    }

    private static Map<String, Object> props(CatalogProduct p) {
        var m = new HashMap<String, Object>();
        m.put("name", p.name());
        m.put("description", p.description());
        m.put("kind", p.kind());
        m.put("targetCustomer", p.targetCustomer());
        m.put("priceUsd", p.priceUsd());
        m.put("priceOnRequest", p.priceOnRequest());
        m.put("estimatedCostUsd", p.estimatedCostUsd());
        m.put("delivery", p.delivery());
        m.put("markets", p.markets());
        m.put("languages", p.languages());
        m.put("status", p.status().name());
        m.put("statusBeforePause", p.statusBeforePause() == null ? null : p.statusBeforePause().name());
        m.put("createdBy", p.createdBy());
        m.put("createdAt", p.createdAt().toString());
        m.put("updatedAt", p.updatedAt().toString());
        return m;
    }

    private void write(String cypher, Map<String, Object> params) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, params);
                return null;
            });
        }
    }
}
