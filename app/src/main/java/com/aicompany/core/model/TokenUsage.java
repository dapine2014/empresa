package com.aicompany.core.model;

import java.util.Map;

/**
 * Costo real por misión (2026-10-04): tokens de una llamada a un modelo, del "usage" de la API compatible con OpenAI
 * (verificado en vivo en Bedrock). {@code cached} es la parte de la entrada que vino de la caché del proveedor.
 */
public record TokenUsage(long input, long cached, long output) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0);

    /** Precio por millón de tokens (US$). */
    public record Price(double input, double cached, double output) {
        public static final Price FREE = new Price(0, 0, 0);
    }

    @SuppressWarnings("unchecked")
    public static TokenUsage fromOpenAi(Object usage) {
        if (!(usage instanceof Map<?, ?> map)) {
            return ZERO;
        }
        var details = map.get("prompt_tokens_details") instanceof Map<?, ?> d ? d : Map.of();
        return new TokenUsage(number(map.get("prompt_tokens")), number(details.get("cached_tokens")),
                number(map.get("completion_tokens")));
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }

    public double costUsd(Price price) {
        var fresh = Math.max(0, input - cached);
        return (fresh * price.input() + cached * price.cached() + output * price.output()) / 1_000_000.0;
    }
}
