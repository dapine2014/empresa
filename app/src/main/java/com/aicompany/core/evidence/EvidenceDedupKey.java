package com.aicompany.core.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Clave estable para deduplicar evidencia: {@code SHA-256(fuente
 * normalizada + descripción normalizada)}. Dos agentes (incluso en
 * misiones distintas) que citen la misma URL con la misma descripción
 * calculan el mismo id — así {@code MissionMemoryService.recordEvidence}
 * puede usar {@code MERGE} sobre ese id en vez de crear un nodo
 * {@code Evidence} nuevo por cada tarea, evitando guardar el mismo dato
 * diez veces solo porque diez agentes encontraron la misma fuente
 * (`EMPRESA_AI_NUEVO_TODO_EVIDENCE.md` §17).
 *
 * Deliberadamente no distingue entre misiones: si la fuente+descripción
 * es realmente la misma, es la misma evidencia sin importar quién ni
 * cuándo la encontró — es la contraparte de identidad de
 * {@link ClaimRelevanceChecker}, que decide si una fuente es relevante,
 * no si es la misma que otra ya vista.
 */
public final class EvidenceDedupKey {

    private EvidenceDedupKey() {
    }

    public static String stableId(String source, String description) {

        var key = normalize(source) + "|" + normalize(description);

        return "EVIDENCE-" + sha256Hex(key);
    }

    private static String normalize(String value) {

        if (value == null) {
            return "";
        }

        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("/+$", "")
                .replaceAll("\\s+", " ");
    }

    private static String sha256Hex(String value) {

        try {

            var digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);

        } catch (NoSuchAlgorithmException ex) {

            throw new IllegalStateException(
                    "SHA-256 no disponible en esta JVM", ex);
        }
    }
}
