package com.aicompany.core.evidence;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Verificación semántica *ligera*: no entiende el contenido, solo
 * comprueba que la página realmente mencione los términos de la
 * afirmación/búsqueda — determinista, sin llamar a ningún modelo (no
 * "el modelo revisándose a sí mismo").
 *
 * Cierra una brecha real: antes, {@code confirmReachable} aceptaba
 * cualquier página que respondiera, sin importar si tenía algo que ver
 * con lo buscado. Esto no prueba que el dato concreto esté ahí (para eso
 * haría falta NLP/extracción real, fuera de alcance) — solo descarta el
 * caso más burdo: una fuente accesible pero completamente ajena al tema.
 */
public final class ClaimRelevanceChecker {

    private ClaimRelevanceChecker() {
    }

    /** Umbral mínimo de coincidencia para considerar la fuente relacionada. */
    private static final double MIN_MATCH_RATIO = 0.3;

    private static final Set<String> STOPWORDS = Set.of(
            "de", "la", "el", "los", "las", "en", "un", "una", "unos",
            "unas", "para", "por", "con", "sin", "sobre", "entre", "y",
            "o", "u", "que", "es", "son", "su", "sus", "al", "del",
            "como", "más", "menos", "muy", "se", "lo", "le", "les"
    );

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    public static ClaimRelevance check(String claim, String content) {

        var terms = significantTerms(claim);

        if (terms.isEmpty()) {
            // No hay nada que comprobar (claim vacío o solo stopwords) —
            // no podemos evaluar, así que no bloqueamos.
            return new ClaimRelevance(true, 0, 0);
        }

        var normalizedContent = normalize(content);

        var matched = terms.stream()
                .filter(normalizedContent::contains)
                .count();

        var ratio = (double) matched / terms.size();

        return new ClaimRelevance(
                ratio >= MIN_MATCH_RATIO,
                (int) matched,
                terms.size()
        );
    }

    private static LinkedHashSet<String> significantTerms(String claim) {

        var set = new LinkedHashSet<String>();

        if (claim == null || claim.isBlank()) {
            return set;
        }

        var normalized = normalize(claim);

        for (var word : normalized.split("\\s+")) {

            if (word.length() < 3 || STOPWORDS.contains(word)) {
                continue;
            }

            set.add(word);
        }

        return set;
    }

    private static String normalize(String text) {

        var decomposed = Normalizer.normalize(
                text.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        );

        // Quita acentos (é -> e) para que "asesoría"/"asesoria" calcen igual,
        // dado que el HTML crudo no siempre normaliza tildes de forma
        // consistente.
        var withoutAccents = decomposed.replaceAll("\\p{M}", "");

        return NON_WORD.matcher(withoutAccents)
                .replaceAll(" ")
                .trim();
    }

    public record ClaimRelevance(
            boolean supported,
            int matchedTerms,
            int totalTerms
    ) {
    }
}
