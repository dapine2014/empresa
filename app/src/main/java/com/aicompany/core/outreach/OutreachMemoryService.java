package com.aicompany.core.outreach;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Spec contacto con prospectos: (:Customer)-[:HAS_DRAFT]->(:ContactDraft), (:Customer)-[:HAS_CONTACT_ATTEMPT]->
 * (:ContactAttempt), (:OptOut {value}) y Customer.outreachStatus. Customer.status sigue 'LEAD' (Finanzas).
 */
@Service
public class OutreachMemoryService {

    static final String DEFAULT_SIGNATURE = "Forjai — forjai.com";

    private final Driver driver;

    public OutreachMemoryService(Driver driver) {
        this.driver = driver;
    }

    public void saveDraft(ContactDraft d) {
        var props = new HashMap<String, Object>();
        props.put("prospectId", d.prospectId());
        props.put("prospectName", d.prospectName());
        props.put("productId", d.productId());
        props.put("to", d.to());
        props.put("subject", d.subject());
        props.put("body", d.body());
        props.put("status", d.status());
        props.put("error", d.error());
        props.put("createdAt", d.createdAt().toString());
        write("MATCH (c:Customer {id:$prospectId}) MERGE (d:ContactDraft {id:$id}) SET d += $props "
                + "MERGE (c)-[:HAS_DRAFT]->(d)", Map.of("prospectId", d.prospectId(), "id", d.id(), "props", props));
    }

    public Optional<ContactDraft> draft(String id) {
        return read("MATCH (d:ContactDraft {id:$id}) RETURN d", Map.of("id", id), OutreachMemoryService::draft)
                .stream().findFirst();
    }

    public List<ContactDraft> drafts(String status) {
        return read("MATCH (d:ContactDraft {status:$status}) RETURN d ORDER BY d.createdAt", Map.of("status", status),
                OutreachMemoryService::draft);
    }

    public List<ContactDraft> allDrafts(int limit) {
        return read("MATCH (d:ContactDraft) RETURN d ORDER BY d.createdAt DESC LIMIT $limit", Map.of("limit", limit),
                OutreachMemoryService::draft);
    }

    public void setDraftStatus(String id, String status, String error, Instant sentAt) {
        var params = new HashMap<String, Object>();
        params.put("id", id);
        params.put("status", status);
        params.put("error", error);
        params.put("sentAt", sentAt == null ? null : sentAt.toString());
        write("MATCH (d:ContactDraft {id:$id}) SET d.status=$status, d.error=$error, "
                + "d.sentAt=coalesce($sentAt, d.sentAt)", params);
    }

    public void editDraft(String id, String subject, String body) {
        write("MATCH (d:ContactDraft {id:$id}) SET d.subject=$subject, d.body=$body, d.editedByFounder=true",
                Map.of("id", id, "subject", subject, "body", body));
    }

    public Optional<String> outreachStatus(String prospectId) {
        return read("MATCH (c:Customer {id:$id}) RETURN c.outreachStatus AS s", Map.of("id", prospectId),
                rec -> rec.get("s").asString(null)).stream().filter(s -> s != null).findFirst();
    }

    public void setOutreachStatus(String prospectId, String status) {
        var params = new HashMap<String, Object>();
        params.put("id", prospectId);
        params.put("status", status);
        write("MATCH (c:Customer {id:$id}) SET c.outreachStatus=$status", params);
    }

    /** CAS: solo un envío por prospecto (DRAFTED → CONTACT_IN_PROGRESS). */
    public boolean claimForContact(String prospectId) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> !tx.run("MATCH (c:Customer {id:$id}) WHERE c.outreachStatus = 'DRAFTED' "
                    + "SET c.outreachStatus = 'CONTACT_IN_PROGRESS' RETURN c.id", Map.of("id", prospectId)).list().isEmpty());
        }
    }

    public String recordAttempt(String prospectId, String draftId, String to, String subject) {
        var id = "ATTEMPT-" + UUID.randomUUID();
        write("MATCH (c:Customer {id:$prospectId}) CREATE (a:ContactAttempt {id:$id, draftId:$draftId, channel:'EMAIL', "
                        + "destination:$to, subject:$subject, status:'PENDING', requestedBy:'human', initiatedAt:$now}) "
                        + "MERGE (c)-[:HAS_CONTACT_ATTEMPT]->(a)",
                Map.of("prospectId", prospectId, "id", id, "draftId", draftId, "to", to, "subject", subject,
                        "now", Instant.now().toString()));
        return id;
    }

    public void finishAttempt(String attemptId, boolean sent, String error) {
        var params = new HashMap<String, Object>();
        params.put("id", attemptId);
        params.put("status", sent ? "SENT" : "FAILED");
        params.put("error", error);
        params.put("now", Instant.now().toString());
        write("MATCH (a:ContactAttempt {id:$id}) SET a.status=$status, a.errorMessage=$error, "
                + "a.sentAt = CASE WHEN $status = 'SENT' THEN $now ELSE null END", params);
    }

    public int sentOn(LocalDate day) {
        return read("MATCH (a:ContactAttempt {status:'SENT'}) WHERE a.sentAt STARTS WITH $day RETURN count(a) AS n",
                Map.of("day", day.toString()), rec -> rec.get("n").asInt()).get(0);
    }

    public boolean optedOut(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        var value = email.strip().toLowerCase(Locale.ROOT);
        var domain = value.substring(value.indexOf('@') + 1);
        return !read("MATCH (o:OptOut) WHERE o.value IN [$email, $domain] RETURN o LIMIT 1",
                Map.of("email", value, "domain", domain), rec -> rec).isEmpty();
    }

    public void optOut(String value) {
        write("MERGE (o:OptOut {value:$value}) ON CREATE SET o.createdAt=$now",
                Map.of("value", value.strip().toLowerCase(Locale.ROOT), "now", Instant.now().toString()));
    }

    public Set<String> optedOutDomains() {
        return new HashSet<>(read("MATCH (o:OptOut) WHERE NOT o.value CONTAINS '@' RETURN o.value AS v", Map.of(),
                rec -> rec.get("v").asString()));
    }

    public String signature() {
        return read("MATCH (c:Company {id:'AI-COMPANY'}) RETURN coalesce(c.outreachSignature, $d) AS s",
                Map.of("d", DEFAULT_SIGNATURE), rec -> rec.get("s").asString()).stream().findFirst().orElse(DEFAULT_SIGNATURE);
    }

    public void setSignature(String signature) {
        write("MATCH (c:Company {id:'AI-COMPANY'}) SET c.outreachSignature=$s", Map.of("s", signature));
    }

    public void linkConverted(String prospectId, String customerId) {
        write("MATCH (p:Customer {id:$pid}), (c:Customer {id:$cid}) MERGE (p)-[:CONVERTED_TO]->(c)",
                Map.of("pid", prospectId, "cid", customerId));
    }

    private static ContactDraft draft(Record rec) {
        var d = rec.get("d");
        return new ContactDraft(d.get("id").asString(), d.get("prospectId").asString(), d.get("prospectName").asString(null),
                d.get("productId").asString(null), d.get("to").asString(), d.get("subject").asString(),
                d.get("body").asString(), d.get("status").asString(), d.get("error").asString(null),
                Instant.parse(d.get("createdAt").asString()),
                d.get("sentAt").isNull() ? null : Instant.parse(d.get("sentAt").asString()));
    }

    private void write(String cypher, Map<String, Object> params) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, params);
                return null;
            });
        }
    }

    private <T> List<T> read(String cypher, Map<String, Object> params, Function<Record, T> map) {
        try (var session = driver.session()) {
            return session.run(cypher, params).list(map::apply);
        }
    }
}
