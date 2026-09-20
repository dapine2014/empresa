package com.aicompany.core.service;

import com.aicompany.core.model.ConversationTurn;
import com.aicompany.core.model.LastMentioned;
import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Un solo hilo de conversación global ({@code Conversation {id:'MAIN'}}
 * — no hay concepto de usuario/sesión en esta app, un solo fundador
 * opera todo). Los mensajes ya no cuelgan directo de {@code Conversation}:
 * cada uno vive bajo un {@code Chat {date}} (uno por día calendario,
 * {@code MERGE} idempotente por fecha) — {@code (:Conversation)-[:HAS_CHAT]->
 * (:Chat)-[:HAS_MESSAGE]->(:Message)}. Esto convierte la conversación en
 * un grafo histórico real (se puede preguntar "¿qué hablamos el [día]?"
 * y "¿en qué días hablamos de X?", ver {@link #chatHistoryForDate}/
 * {@link #daysMentioning}) en vez de solo una lista plana de mensajes.
 *
 * <p>El "foco actual" ({@link #setLastMentioned}/{@link #lastMentioned})
 * sigue viviendo como propiedades directas del nodo {@code Conversation},
 * sin cambios — resuelve pronombres del turno actual ("esas"/"esos"),
 * un problema distinto de la memoria histórica por día. Los edges
 * {@code MENTIONS} del {@code Chat} de hoy hacia una entidad real
 * ({@link #recordChatMention}) son un rastro histórico *adicional*, no
 * un reemplazo del foco.
 */
@Service
public class ConversationMemoryService {

    private final Driver driver;
    private final Clock clock;

    public ConversationMemoryService(Driver driver, Clock clock) {
        this.driver = driver;
        this.clock = clock;
    }

    public void recordMessage(String role, String content) {
        var today = LocalDate.now(clock).toString();
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (c:Conversation {id:'MAIN'}) "
                                + "MERGE (c)-[:HAS_CHAT]->(chat:Chat {date:$today}) "
                                + "CREATE (msg:Message {id:$id, role:$role, content:$content, createdAt:$createdAt}) "
                                + "MERGE (chat)-[:HAS_MESSAGE]->(msg)",
                        Map.of(
                                "today", today,
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
     * Registra que el {@code Chat} de hoy mencionó estas entidades reales
     * — rastro histórico independiente del foco mutable de arriba, para
     * poder responder después "¿en qué días hablamos de MISSION-5?" (ver
     * {@link #daysMentioning}). {@code type} solo soporta los mismos dos
     * valores que ya soporta el foco ({@code "MISSION"}/{@code "CUSTOMER"}
     * — cualquier otro valor se trata como {@code Customer}, ver el switch
     * de abajo, mismo criterio simple de dos ramas ya usado en el resto
     * del proyecto). Sin efecto si {@code ids} está vacío.
     */
    public void recordChatMention(String type, List<String> ids) {

        if (ids.isEmpty()) {
            return;
        }

        var label = "MISSION".equals(type) ? "Mission" : "Customer";
        var today = LocalDate.now(clock).toString();

        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (c:Conversation {id:'MAIN'}) "
                                + "MERGE (c)-[:HAS_CHAT]->(chat:Chat {date:$today}) "
                                + "WITH chat UNWIND $ids AS id "
                                + "MATCH (e:" + label + " {id:id}) "
                                + "MERGE (chat)-[:MENTIONS]->(e)",
                        Map.of("today", today, "ids", ids));
                return null;
            });
        }
    }

    /**
     * Los últimos {@code limit} turnos reales, en orden cronológico
     * (el más viejo primero) — para darle al chat general continuidad
     * de charla real (p. ej. "recordá que mi color favorito es el
     * verde" seguido de "¿cuál es mi color favorito?"). Distinto del
     * "foco" de {@link #lastMentioned}, que resuelve referencias a
     * misiones, no continuidad conversacional. No filtra por
     * {@code Chat}/fecha a propósito: es una ventana de continuidad
     * inmediata sobre el hilo completo, no memoria histórica por día
     * (para eso está {@link #chatHistoryForDate}).
     */
    public List<ConversationTurn> recentMessages(int limit) {
        try (var session = driver.session()) {
            var records = session.run(
                    "MATCH (c:Conversation {id:'MAIN'})-[:HAS_CHAT]->(:Chat)-[:HAS_MESSAGE]->(m:Message) "
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

    /**
     * Todos los mensajes reales de un día calendario puntual (formato
     * {@code YYYY-MM-DD}), en orden cronológico — la respuesta real a
     * "¿qué hablamos el [día]?". Lista vacía si ese día no tiene ningún
     * {@code Chat} registrado (nunca lanza).
     */
    public List<ConversationTurn> chatHistoryForDate(String date) {
        try (var session = driver.session()) {
            var records = session.run(
                    "MATCH (chat:Chat {date:$date})-[:HAS_MESSAGE]->(m:Message) "
                            + "RETURN m.role AS role, m.content AS content "
                            + "ORDER BY m.createdAt",
                    Map.of("date", date)
            ).list();

            return records.stream()
                    .map(r -> new ConversationTurn(r.get("role").asString(), r.get("content").asString()))
                    .toList();
        }
    }

    /**
     * Las fechas reales (orden cronológico) en las que el {@code Chat}
     * de ese día mencionó la entidad {@code id} — ver
     * {@link #recordChatMention}. Sin importar el label real de la
     * entidad (Mission o Customer): las relaciones {@code MENTIONS} solo
     * apuntan a esos dos tipos, así que no hace falta filtrar por label
     * acá para leer.
     */
    public List<String> daysMentioning(String id) {
        try (var session = driver.session()) {
            return session.run(
                    "MATCH (chat:Chat)-[:MENTIONS]->(e {id:$id}) "
                            + "RETURN chat.date AS date ORDER BY chat.date",
                    Map.of("id", id)
            ).list(r -> r.get("date").asString());
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

    /**
     * Reengancha los {@code Message} que quedaron colgando directo de
     * {@code Conversation} (de antes de que existiera {@code Chat}) bajo
     * el {@code Chat} correspondiente a la fecha real de su
     * {@code createdAt} (los primeros 10 caracteres de un
     * {@code Instant} en formato ISO-8601, {@code "YYYY-MM-DD..."}, son
     * exactamente la fecha calendario). Idempotente — corre en cada
     * arranque vía {@code CompanyMemoryInitializer}; un {@code Message}
     * que ya cuelga de un {@code Chat} no matchea el patrón
     * ({@code (:Conversation)-[:HAS_MESSAGE]->(:Message)} directo) y se
     * ignora. Excluye explícitamente cualquier {@code Message} con
     * {@code createdAt} nulo ({@code WHERE m.createdAt IS NOT NULL}) —
     * sin este guard, {@code left(null, 10)} devuelve {@code null} y el
     * siguiente {@code MERGE (chat:Chat {date:null})} lanza
     * {@code "Cannot merge node using null property value"}, lo que
     * tumbaría el arranque de la aplicación (corre desde un listener de
     * {@code ApplicationReadyEvent}) — mismo patrón defensivo ya
     * establecido para {@code MissionMemoryService.recentDecisions}
     * ({@code WHERE d.decidedAt IS NOT NULL}).
     *
     * <p><b>Límite conocido, no resuelto</b> (mismo criterio que el DNS
     * rebinding de {@code WebPageFetcher}): esta migración agrupa por
     * fecha <b>UTC</b> (los primeros 10 caracteres del {@code Instant}
     * ISO-8601 de {@code createdAt}, que siempre se genera en UTC vía
     * {@code Instant.now().toString()}), mientras que las escrituras en
     * vivo ({@link #recordMessage}/{@link #recordChatMention}) agrupan
     * por {@code LocalDate.now(clock)} usando la zona horaria
     * <b>local</b> del servidor (el {@code Clock} inyectado, por
     * default {@code Clock.systemDefaultZone()}). En un entorno que no
     * esté en UTC, un mensaje cercano a la medianoche local podría
     * quedar bajo un día calendario distinto según si pasó por
     * {@link #recordMessage} directamente o si fue migrado. El
     * despliegue actual de Docker no fija {@code TZ}, así que la zona
     * local del contenedor ya es UTC y esto no se manifiesta hoy en
     * producción — documentado por si cambia, no corregido en este
     * cambio.
     */
    public void migrateMessagesToChats() {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MATCH (c:Conversation {id:'MAIN'})-[r:HAS_MESSAGE]->(m:Message) "
                                + "WHERE m.createdAt IS NOT NULL "
                                + "WITH c, r, m, left(m.createdAt, 10) AS date "
                                + "MERGE (c)-[:HAS_CHAT]->(chat:Chat {date:date}) "
                                + "MERGE (chat)-[:HAS_MESSAGE]->(m) "
                                + "DELETE r");
                return null;
            });
        }
    }
}
