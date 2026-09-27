package com.aicompany.core.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Menciones del chat (spec 2026-09-27 §5): {@code @Nombre} o {@code @agentId} contra los agentes reales de Neo4j, sin
 * distinguir mayúsculas ni tildes, en orden de aparición y sin duplicados. La {@code @} tiene que ir al inicio o tras
 * un espacio (un email no es una mención). Lo desconocido se reporta, nunca se adivina.
 */
public final class MentionResolver {

    public record Resolution(List<String> agentIds, List<String> unknown) {
    }

    private static final Pattern MENTION = Pattern.compile("(?:^|\\s)@([\\p{L}\\p{N}_-]+)");

    private MentionResolver() {
    }

    public static Resolution resolve(String message, List<Map<String, Object>> agents) {
        var ids = new LinkedHashSet<String>();
        var unknown = new ArrayList<String>();
        var matcher = MENTION.matcher(message == null ? "" : message);
        while (matcher.find()) {
            var token = matcher.group(1);
            var key = normalize(token);
            var match = agents.stream()
                    .filter(a -> key.equals(normalize(String.valueOf(a.get("id")))) || key.equals(normalize(String.valueOf(a.get("name")))))
                    .map(a -> String.valueOf(a.get("id")))
                    .findFirst();
            if (match.isPresent()) {
                ids.add(match.get());
            } else if (!unknown.contains(token)) {
                unknown.add(token);
            }
        }
        return new Resolution(List.copyOf(ids), List.copyOf(unknown));
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
