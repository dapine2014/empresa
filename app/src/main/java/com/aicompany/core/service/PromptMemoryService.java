package com.aicompany.core.service;

import com.aicompany.core.model.PromptSnapshot;
import com.aicompany.core.model.PromptVersionSummary;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Prompt propio, persistido y versionado, de cada uno de los 14
 * agentes — capa aparte sobre los {@code Agent} ya creados por
 * {@link CompanyMemoryService} (no los crea, solo los enriquece).
 * Ver docs/superpowers/specs/2026-09-21-agent-prompt-versioning-design.md.
 *
 * <p>Invariante dura: en todo momento existe exactamente una relación
 * {@code HAS_ACTIVE_PROMPT} por {@code Agent}. Editar (crear versión)
 * y hacer rollback (activar una versión existente) son cada uno una
 * sola transacción Cypher que borra la relación activa anterior y crea
 * la nueva — nunca queda un estado intermedio con cero o dos activas.
 * Rollback nunca crea un {@code PromptVersion} nuevo, solo reapunta
 * {@code HAS_ACTIVE_PROMPT} a un nodo ya existente.
 */
@Service
public class PromptMemoryService {

    private final Driver driver;

    public PromptMemoryService(Driver driver) {
        this.driver = driver;
    }

    /**
     * Idempotente: cualquier {@code Agent} sin {@code HAS_ACTIVE_PROMPT}
     * todavía recibe una versión 1 de contenido vacío, y queda activa.
     * Llamado desde {@code CompanyMemoryInitializer} después de que los
     * agentes ya existan.
     */
    public void ensureDefaultPrompts() {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (a:Agent) WHERE NOT (a)-[:HAS_ACTIVE_PROMPT]->(:PromptVersion) "
                                + "MERGE (v:PromptVersion {id: a.id + '-v1'}) "
                                + "ON CREATE SET v.agentId = a.id, v.version = 1, v.content = '', "
                                + "v.createdBy = 'human', v.changeReason = 'Versión inicial (seed)', "
                                + "v.createdAt = $createdAt "
                                + "MERGE (a)-[:HAS_PROMPT_VERSION]->(v) "
                                + "MERGE (a)-[:HAS_ACTIVE_PROMPT]->(v)",
                        Map.of("createdAt", Instant.now().toString()));
                return null;
            });
        }
    }

    /**
     * Contenido de la versión activa de este agente — resuelto por
     * {@code AgentRuntime}/{@code MissionExecutor}/{@code ChatIntentRouter}
     * antes de cada llamada real a Ollama, nunca por {@code CeoService}
     * (que sigue sin depender de Neo4j directamente). {@code ""} si el
     * agente todavía no tiene ninguna versión (defensivo, no debería
     * pasar en la práctica tras {@link #ensureDefaultPrompts()}).
     */
    public String activePrompt(String agentId) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (a:Agent {id:$agentId})-[:HAS_ACTIVE_PROMPT]->(v:PromptVersion) "
                                    + "RETURN v.content AS content",
                            Map.of("agentId", agentId))
                    .list(r -> r.get("content").asString())
                    .stream().findFirst()
                    .orElse("");
        }
    }

    /**
     * Lectura completa para el Command Center web — versión activa +
     * historial (sin el contenido de cada versión vieja, ver
     * {@link #versionContent}). Lanza {@code IllegalArgumentException}
     * si el agente no existe o no tiene ninguna versión todavía.
     */
    public PromptSnapshot snapshot(String agentId) {
        try (var session = driver.session()) {

            var active = session.run(
                    "MATCH (a:Agent {id:$agentId})-[:HAS_ACTIVE_PROMPT]->(v:PromptVersion) "
                            + "RETURN v.version AS version, v.content AS content, "
                            + "v.createdBy AS createdBy, v.changeReason AS changeReason, "
                            + "v.createdAt AS createdAt",
                    Map.of("agentId", agentId)
            ).list();

            if (active.isEmpty()) {
                throw new IllegalArgumentException("No existe prompt activo para el agente " + agentId);
            }

            var a = active.get(0);

            var versions = session.run(
                    "MATCH (:Agent {id:$agentId})-[:HAS_PROMPT_VERSION]->(v:PromptVersion) "
                            + "RETURN v.version AS version, v.createdBy AS createdBy, "
                            + "v.changeReason AS changeReason, v.createdAt AS createdAt "
                            + "ORDER BY v.version DESC",
                    Map.of("agentId", agentId)
            ).list(r -> new PromptVersionSummary(
                    r.get("version").asInt(),
                    r.get("createdBy").asString(),
                    r.get("changeReason").asString(),
                    Instant.parse(r.get("createdAt").asString())
            ));

            return new PromptSnapshot(
                    agentId,
                    a.get("version").asInt(),
                    a.get("content").asString(),
                    a.get("createdBy").asString(),
                    a.get("changeReason").asString(),
                    Instant.parse(a.get("createdAt").asString()),
                    versions
            );
        }
    }

    /**
     * Contenido completo de una versión puntual (para previsualizar
     * antes de activar una del historial). Lanza
     * {@code IllegalArgumentException} si no existe esa versión para
     * ese agente.
     */
    public String versionContent(String agentId, int version) {
        try (var session = driver.session()) {
            return session.run(
                            "MATCH (:Agent {id:$agentId})-[:HAS_PROMPT_VERSION]->(v:PromptVersion {version:$version}) "
                                    + "RETURN v.content AS content",
                            Map.of("agentId", agentId, "version", version))
                    .list(r -> r.get("content").asString())
                    .stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No existe la versión " + version + " para el agente " + agentId));
        }
    }

    /**
     * Crea una versión nueva (siguiente número, nunca pisa una vieja) y
     * la activa — una sola transacción: si el {@code MATCH} del agente
     * no encuentra nada, la consulta completa no devuelve filas y se
     * lanza {@code IllegalArgumentException}, sin dejar nada a medio
     * escribir.
     */
    public PromptSnapshot createVersion(String agentId, String content, String changeReason) {

        if (changeReason == null || changeReason.isBlank()) {
            throw new IllegalArgumentException("changeReason no puede estar vacío");
        }

        var safeContent = content == null ? "" : content;

        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                var result = tx.run(
                        "MATCH (a:Agent {id:$agentId}) "
                                + "OPTIONAL MATCH (a)-[:HAS_PROMPT_VERSION]->(existing:PromptVersion) "
                                + "WITH a, coalesce(max(existing.version), 0) + 1 AS nextVersion "
                                + "CREATE (v:PromptVersion {id: $agentId + '-v' + toString(nextVersion), "
                                + "agentId: $agentId, version: nextVersion, content: $content, "
                                + "createdBy: 'human', changeReason: $changeReason, createdAt: $createdAt}) "
                                + "MERGE (a)-[:HAS_PROMPT_VERSION]->(v) "
                                + "WITH a, v "
                                + "OPTIONAL MATCH (a)-[old:HAS_ACTIVE_PROMPT]->(:PromptVersion) "
                                + "DELETE old "
                                + "CREATE (a)-[:HAS_ACTIVE_PROMPT]->(v) "
                                + "RETURN v",
                        Map.of("agentId", agentId, "content", safeContent, "changeReason", changeReason,
                                "createdAt", Instant.now().toString()));

                if (result.list().isEmpty()) {
                    throw new IllegalArgumentException("No existe el agente " + agentId);
                }

                return null;
            });
        }

        return snapshot(agentId);
    }

    /**
     * Rollback: reactiva una versión ya existente del historial — nunca
     * crea un {@code PromptVersion} nuevo. Una sola transacción: si el
     * {@code MATCH} de la versión objetivo no encuentra nada, la
     * consulta no devuelve filas y se lanza
     * {@code IllegalArgumentException}.
     */
    public PromptSnapshot activateVersion(String agentId, int version) {

        try (var session = driver.session()) {
            session.executeWrite(tx -> {

                var result = tx.run(
                        "MATCH (a:Agent {id:$agentId})-[:HAS_PROMPT_VERSION]->(v:PromptVersion {version:$version}) "
                                + "OPTIONAL MATCH (a)-[old:HAS_ACTIVE_PROMPT]->(:PromptVersion) "
                                + "DELETE old "
                                + "CREATE (a)-[:HAS_ACTIVE_PROMPT]->(v) "
                                + "RETURN v",
                        Map.of("agentId", agentId, "version", version));

                if (result.list().isEmpty()) {
                    throw new IllegalArgumentException(
                            "No existe la versión " + version + " para el agente " + agentId);
                }

                return null;
            });
        }

        return snapshot(agentId);
    }
}
