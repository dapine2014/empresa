package com.aicompany.core.agent.model;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.deser.std.StdDeserializer;

import java.util.List;

/**
 * Algunos modelos de Ollama devuelven, para campos que deberían ser
 * {@code List<String>} (facts, hypotheses, estimates, evidenceRequired,
 * risks), elementos que son objetos JSON en vez de strings, p. ej.
 * {@code {"description": "..."}}, en lugar de {@code "..."}.
 *
 * Esta clase evita que eso tumbe el parseo de {@link AgentResult}: si el
 * elemento ya es texto lo usa tal cual; si es un objeto, intenta rescatar
 * un campo de texto habitual y, si no encuentra ninguno, cae al JSON
 * completo como string.
 */
public class LenientStringDeserializer extends StdDeserializer<String> {

    private static final List<String> TEXT_LIKE_KEYS = List.of(
            "description",
            "text",
            "value",
            "content",
            "name",
            "detail",
            "summary"
    );

    public LenientStringDeserializer() {
        super(String.class);
    }

    @Override
    public String deserialize(
            JsonParser p,
            DeserializationContext ctxt) throws JacksonException {

        JsonNode node = p.readValueAsTree();

        if (node == null || node.isNull()) {
            return null;
        }

        if (node.isTextual()) {
            return node.asString();
        }

        if (node.isObject()) {

            for (var key : TEXT_LIKE_KEYS) {

                var value = node.get(key);

                if (value != null && value.isTextual()) {
                    return value.asString();
                }
            }

            return node.toString();
        }

        if (node.isValueNode()) {
            return node.asString();
        }

        return node.toString();
    }
}
