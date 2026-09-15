package com.aicompany.core.service;

import com.aicompany.core.model.ConversationTurn;
import com.aicompany.core.model.LastMentioned;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Un solo hilo de conversación global ({@code Conversation {id:'MAIN'}}
 * — no hay concepto de usuario/sesión en esta app, un solo fundador
 * opera todo). Cada mensaje (entrante o respuesta final) se persiste vía
 * {@link #recordMessage}, sin importar qué camino de
 * {@code ChatIntentRouter} lo resolvió.
 *
 * <p>El "foco actual" ({@link #setLastMentioned}/{@link #lastMentioned})
 * vive como propiedades directas del nodo {@code Conversation}, no como
 * un nodo aparte — es un valor mutable de "último estado", no un hecho
 * histórico que valga versionar. Permite que el router resuelva
 * referencias conversacionales ("esas"/"esos") consultando el dato real
 * actual de esas entidades, nunca el texto de una respuesta anterior que
 * puede estar desactualizado.
 */
@Service
public class ConversationMemoryService {

    private final Driver driver;

    public ConversationMemoryService(Driver driver) {
        this.driver = driver;
    }

    public void recordMessage(String role, String content) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (c:Conversation {id:'MAIN'}) "
                                + "CREATE (msg:Message {id:$id, role:$role, content:$content, createdAt:$createdAt}) "
                                + "MERGE (c)-[:HAS_MESSAGE]->(msg)",
                        Map.of(
                                "id", UUID.randomUUID().toString(),
                                "role", role,
                                "content", content,
                                "createdAt", Instant.now().toString()
                        ));
                return null;
            });
        }
    }

    public void setLastMentioned(String type, List<String> ids) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (c:Conversation {id:'MAIN'}) "
                                + "SET c.lastMentionedType=$type, c.lastMentionedIds=$ids, c.lastMentionedAt=$now",
                        Map.of(
                                "type", type,
                                "ids", ids,
                                "now", Instant.now().toString()
                        ));
                return null;
            });
        }
    }

    /**
     * Los últimos {@code limit} turnos reales, en orden cronológico
     * (el más viejo primero) — para darle al chat general continuidad
     * de charla real (p. ej. "recordá que mi color favorito es el
     * verde" seguido de "¿cuál es mi color favorito?"), algo que antes
     * no existía: cada llamada a {@code CeoService.chat} solo mandaba
     * el mensaje del turno actual. Distinto del "foco" de
     * {@link #lastMentioned}, que resuelve referencias a misiones, no
     * continuidad conversacional.
     */
    public List<ConversationTurn> recentMessages(int limit) {
        try (var session = driver.session()) {
            var records = session.run(
                    "MATCH (c:Conversation {id:'MAIN'})-[:HAS_MESSAGE]->(m:Message) "
                            + "RETURN m.role AS role, m.content AS content "
                            + "ORDER BY m.createdAt DESC LIMIT $limit",
                    Map.of("limit", limit)
            ).list();

            var turns = records.stream()
                    .map(r -> new ConversationTurn(r.get("role").asString(), r.get("content").asString()))
                    .toList();

            return turns.reversed();
        }
    }

    public Optional<LastMentioned> lastMentioned() {
        try (var session = driver.session()) {
            var records = session.run(
                    "MATCH (c:Conversation {id:'MAIN'}) "
                            + "WHERE c.lastMentionedIds IS NOT NULL "
                            + "RETURN c.lastMentionedType AS type, c.lastMentionedIds AS ids"
            ).list();

            return records.stream().findFirst().map(r -> new LastMentioned(
                    r.get("type").asString(),
                    r.get("ids").asList(v -> v.asString())
            ));
        }
    }
}
