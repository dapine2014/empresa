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

    // Revisión final (I-2): "clave: X", "password X", JSON, contraseña=, PGPASSWORD=, valores entre comillas.
    private static final java.util.regex.Pattern PASSWORD_IN_TEXT = java.util.regex.Pattern.compile(
            "(?iu)(\"?(?:[A-Za-z0-9]+_)?(?:password|passwd|pwd|clave|contraseña|secret)\"?)(\\s*[:=]\\s*|\\s+)"
                    + "(\"[^\"]*\"|'[^']*'|[^\\s;,}]+)");
    private static final java.util.regex.Pattern URI_WITH_PASSWORD =
            java.util.regex.Pattern.compile("(?i)\\b(postgres(?:ql)?|mongodb(?:\\+srv)?)://([^:/@\\s]+):[^@\\s]+@");

    /** Palabras que siguen a "clave"/"password" en frases normales y no son una clave. */
    private static final java.util.Set<String> NOT_A_SECRET = java.util.Set.of("del", "de", "la", "el", "es", "y", "o",
            "para", "que", "en", "nueva", "mi", "tu", "su");

    /**
     * Spec 2026-10-02 §1: el historial se envía al modelo, así que una clave pegada en el chat nunca se guarda tal cual.
     */
    public static String redactSecrets(String text) {
        if (text == null) {
            return null;
        }
        var matcher = PASSWORD_IN_TEXT.matcher(text);
        var out = new StringBuilder();
        while (matcher.find()) {
            var value = matcher.group(3);
            var plain = value.replaceAll("^[\"']|[\"']$", "");
            var keep = NOT_A_SECRET.contains(plain.toLowerCase(java.util.Locale.ROOT)) || plain.length() < 4;
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(keep ? matcher.group()
                    : matcher.group(1) + matcher.group(2) + "[clave omitida]"));
        }
        matcher.appendTail(out);
        return URI_WITH_PASSWORD.matcher(out.toString()).replaceAll("$1://$2:[clave omitida]@");
    }

    /** Revisión final (C-1): si el mensaje trae una clave, el turno se corta sin llamar a ningún modelo. */
    public static boolean containsSecret(String text) {
        return text != null && !redactSecrets(text).equals(text);
    }

    public void recordMessage(String role, String content) {
        var safeContent = redactSecrets(content);
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run("MERGE (c:Conversation {id:'MAIN'}) "
                                + "CREATE (msg:Message {id:$id, role:$role, content:$content, createdAt:$createdAt}) "
                                + "MERGE (c)-[:HAS_MESSAGE]->(msg)",
                        Map.of(
                                "id", UUID.randomUUID().toString(),
                                "role", role,
                                "content", safeContent,
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
