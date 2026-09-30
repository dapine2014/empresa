package com.aicompany.core.prospecting;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Spec búsqueda de prospectos: (:ProspectingRun), (:ProspectingStrategy) y los prospectos como
 * (:Product)-[:HAS_PROSPECT]->(:Customer {status:'LEAD'})-[:HAS_EVIDENCE]->(:Evidence {sourceType:'WEB', verified:false}).
 */
@Service
public class ProspectingMemoryService {

    private final Driver driver;

    public ProspectingMemoryService(Driver driver) {
        this.driver = driver;
    }

    public void saveRun(ProspectingRun run) {
        var props = new HashMap<String, Object>();
        props.put("productId", run.productId());
        props.put("strategyId", run.strategyId());
        props.put("status", run.status());
        props.put("found", run.found());
        props.put("valid", run.valid());
        props.put("rejections", run.rejections());
        props.put("error", run.error());
        props.put("startedAt", run.startedAt().toString());
        props.put("endedAt", run.endedAt() == null ? null : run.endedAt().toString());
        write("MERGE (r:ProspectingRun {id:$id}) SET r += $props", Map.of("id", run.id(), "props", props));
    }

    public List<ProspectingRun> runs(int limit) {
        return read("MATCH (r:ProspectingRun) RETURN r ORDER BY r.startedAt DESC LIMIT $limit", Map.of("limit", limit),
                ProspectingMemoryService::run);
    }

    public List<RunStat> stats() {
        return read("MATCH (r:ProspectingRun {status:'COMPLETED'}) RETURN r ORDER BY r.startedAt", Map.of(),
                rec -> {
                    var r = rec.get("r");
                    return new RunStat(r.get("productId").asString(), r.get("strategyId").asString(),
                            r.get("valid").asInt(0), Instant.parse(r.get("startedAt").asString()));
                });
    }

    public boolean ranOn(LocalDate day) {
        return !read("MATCH (r:ProspectingRun) WHERE r.startedAt STARTS WITH $day RETURN r LIMIT 1",
                Map.of("day", day.toString()), rec -> rec).isEmpty();
    }

    public int prospectsOn(LocalDate day) {
        return read("MATCH (:Product)-[:HAS_PROSPECT]->(c:Customer) WHERE c.foundAt STARTS WITH $day RETURN count(c) AS n",
                Map.of("day", day.toString()), rec -> rec.get("n").asInt()).get(0);
    }

    public Set<String> knownDomains(String productId) {
        return new HashSet<>(read("MATCH (:Product {id:$id})-[:HAS_PROSPECT]->(c:Customer) WHERE c.domain IS NOT NULL "
                + "RETURN c.domain AS d", Map.of("id", productId), rec -> rec.get("d").asString()));
    }

    public void saveProspect(String productId, ProspectCandidate c, String domain, String strategyId, String runId) {
        var id = "PROSPECT-" + UUID.randomUUID();
        var now = Instant.now().toString();
        var props = new HashMap<String, Object>();
        props.put("name", c.name().strip());
        props.put("status", "LEAD");
        props.put("url", c.url().strip());
        props.put("domain", domain);
        props.put("contactEmail", blank(c.contactEmail()) ? null : c.contactEmail().strip());
        props.put("contactEmailSource", blank(c.contactEmail()) ? null : c.contactSourceUrl().strip());
        props.put("contactFormUrl", blank(c.contactFormUrl()) ? null : c.contactFormUrl().strip());
        props.put("fitReason", c.fitReason().strip());
        props.put("strategy", strategyId);
        props.put("prospectingRunId", runId);
        props.put("identifiedByAgent", "sales");
        props.put("foundAt", now);
        props.put("createdAt", now);
        props.put("updatedAt", now);
        write("MATCH (p:Product {id:$productId}) CREATE (c:Customer {id:$id}) SET c += $props "
                        + "MERGE (p)-[:HAS_PROSPECT]->(c) "
                        + "CREATE (e:Evidence {id:$id + '-EVIDENCE'}) SET e.description=$fit, e.source=$url, "
                        + "e.sourceType='WEB', e.verified=false, e.agentId='sales', e.updatedAt=$now "
                        + "MERGE (c)-[:HAS_EVIDENCE]->(e)",
                Map.of("productId", productId, "id", id, "props", props, "fit", c.fitReason().strip(),
                        "url", c.url().strip(), "now", now));
    }

    public List<Prospect> prospects() {
        return prospectsWhere("true", Map.of());
    }

    public List<Prospect> prospectsOfRun(String runId) {
        return prospectsWhere("c.prospectingRunId = $runId", Map.of("runId", runId));
    }

    public Optional<Prospect> prospect(String id) {
        return prospectsWhere("c.id = $id", Map.of("id", id)).stream().findFirst();
    }

    private List<Prospect> prospectsWhere(String condition, Map<String, Object> params) {
        return read("MATCH (p:Product)-[:HAS_PROSPECT]->(c:Customer) WHERE " + condition
                + " RETURN p.id AS pid, p.name AS pname, c ORDER BY c.foundAt DESC", params, rec -> {
                    var c = rec.get("c");
                    return new Prospect(c.get("id").asString(), rec.get("pid").asString(), rec.get("pname").asString(null),
                            c.get("name").asString(), c.get("url").asString(null), c.get("contactEmail").asString(null),
                            c.get("contactEmailSource").asString(null), c.get("contactFormUrl").asString(null),
                            c.get("fitReason").asString(null), c.get("strategy").asString(null),
                            Instant.parse(c.get("foundAt").asString()), c.get("outreachStatus").asString(null));
                });
    }

    public List<StoredStrategy> strategies() {
        return read("MATCH (s:ProspectingStrategy) RETURN s ORDER BY s.proposedAt", Map.of(), rec -> strategy(rec.get("s")));
    }

    public Optional<StoredStrategy> strategy(String id) {
        return read("MATCH (s:ProspectingStrategy {id:$id}) RETURN s", Map.of("id", id), rec -> strategy(rec.get("s")))
                .stream().findFirst();
    }

    public void saveStrategy(StoredStrategy s) {
        var props = new HashMap<String, Object>();
        props.put("name", s.name());
        props.put("description", s.description());
        props.put("searchHints", s.searchHints());
        props.put("status", s.status());
        props.put("proposedBy", s.proposedBy());
        props.put("proposedAt", s.proposedAt().toString());
        write("MERGE (s:ProspectingStrategy {id:$id}) SET s += $props", Map.of("id", s.id(), "props", props));
    }

    public void decideStrategy(String id, String status, Instant at) {
        write("MATCH (s:ProspectingStrategy {id:$id}) SET s.status=$status, s.decidedAt=$at, s.decidedBy='human'",
                Map.of("id", id, "status", status, "at", at.toString()));
    }

    public int pendingStrategies() {
        return read("MATCH (s:ProspectingStrategy {status:'PENDING_APPROVAL'}) RETURN count(s) AS n", Map.of(),
                rec -> rec.get("n").asInt()).get(0);
    }

    private static StoredStrategy strategy(Value s) {
        return new StoredStrategy(s.get("id").asString(), s.get("name").asString(), s.get("description").asString(null),
                s.get("searchHints").asString(null), s.get("status").asString(), s.get("proposedBy").asString(null),
                Instant.parse(s.get("proposedAt").asString()),
                s.get("decidedAt").isNull() ? null : Instant.parse(s.get("decidedAt").asString()));
    }

    private static ProspectingRun run(Record rec) {
        var r = rec.get("r");
        return new ProspectingRun(r.get("id").asString(), r.get("productId").asString(null),
                r.get("strategyId").asString(null), r.get("status").asString(), r.get("found").asInt(0),
                r.get("valid").asInt(0), r.get("rejections").asList(Value::asString, List.of()),
                r.get("error").asString(null), Instant.parse(r.get("startedAt").asString()),
                r.get("endedAt").isNull() ? null : Instant.parse(r.get("endedAt").asString()));
    }

    private void write(String cypher, Map<String, Object> params) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, params);
                return null;
            });
        }
    }

    private <T> List<T> read(String cypher, Map<String, Object> params, java.util.function.Function<Record, T> map) {
        try (var session = driver.session()) {
            return session.run(cypher, params).list(map::apply);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
