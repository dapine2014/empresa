package com.aicompany.core.agent.validation;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Heurística léxica (mismo espíritu que HEDGE_MARKERS de
 * ContradictionDetector): en esta fase nadie ejecuta código, así que una
 * afirmación de que compila/se ejecuta/funciona/pasa tests se rechaza. Una
 * afirmación precedida por una negación ("no", "sin", "nunca") se permite:
 * es justamente lo que se quiere que Vera diga. Puede tener falsos negativos.
 */
@Component
public class ForbiddenClaimsGuard {

    private static final List<Pattern> CLAIMS = List.of(
            Pattern.compile("\\bcompila\\b"),
            Pattern.compile("\\bcompilado\\b"),
            Pattern.compile("\\bse ejecuta\\b"),
            Pattern.compile("\\bejecuta correctamente\\b"),
            Pattern.compile("\\bfunciona\\b"),
            Pattern.compile("\\bfuncionan\\b"),
            Pattern.compile("\\bpasa(n)? (los|las) (tests|pruebas)\\b"),
            Pattern.compile("\\bes jugable\\b"),
            Pattern.compile("\\bcorre correctamente\\b")
    );

    private static final Pattern NEGATION = Pattern.compile("\\b(no|sin|nunca|imposible)\\b");

    public List<String> violations(List<String> texts) {

        var violations = new ArrayList<String>();

        if (texts == null) {
            return violations;
        }

        for (var text : texts) {

            if (text == null) {
                continue;
            }

            for (var sentence : text.split("[.!?\\n]")) {

                var normalized = normalize(sentence);

                if (normalized.isBlank()) {
                    continue;
                }

                if (CLAIMS.stream().anyMatch(p -> isAffirmedClaim(p, normalized))) {
                    violations.add("Afirmación no verificable sin ejecutar código: \"" + sentence.strip()
                            + "\". Esta fase no ejecuta código; decláralo en notValidatableWithoutExecution.");
                }
            }
        }

        return violations;
    }

    /**
     * La negación solo cuenta si aparece ANTES de la afirmación: "no se puede
     * afirmar que compila" se permite, "compila sin errores" no.
     */
    private static boolean isAffirmedClaim(Pattern claim, String sentence) {

        var matcher = claim.matcher(sentence);

        while (matcher.find()) {
            if (!NEGATION.matcher(sentence.substring(0, matcher.start())).find()) {
                return true;
            }
        }

        return false;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
