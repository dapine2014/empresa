package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.AgentResult;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Gate determinista (no el modelo revisándose a sí mismo, mismo criterio
 * que {@link ContradictionDetector}/{@link EvidenceValidationGate}) que
 * detecta el caso "el agente buscó evidencia real y la recibió, pero su
 * {@code AgentResult} final no la usa" — antes de este gate, nada
 * impedía que un agente pidiera {@code search_web_evidence}, recibiera
 * URLs reales y confirmadas, y aun así devolviera {@code evidence: []}
 * (o evidencia inventada) sin que ninguna validación lo notara: tanto
 * {@link AgentResultValidator} como {@link EvidenceValidationGate} solo
 * miran el {@code AgentResult} en aislamiento, nunca contra lo que
 * realmente se le entregó al modelo en el turno de herramienta.
 *
 * Corre dentro del mismo bucle de reintento que {@link AgentResultValidator}
 * (a diferencia de {@link EvidenceValidationGate}, que falla la tarea de
 * inmediato sin reintento): si el agente buscó y no citó, se le da la
 * oportunidad de corregir citando la fuente real antes de fallar la tarea.
 *
 * <p>{@code confirmedEvidenceUrls} debe ser la acumulación de **todos**
 * los intentos anteriores de la misma tarea, no solo el actual —
 * {@code AgentRuntime.executeInternal} lo arma así a propósito: el turno
 * de decisión de herramienta de {@code CeoService} es independiente en
 * cada intento (no ve el feedback de corrección), así que un reintento
 * puede perfectamente no volver a buscar nada. Si se pasara solo el
 * conjunto del intento actual, esa URL real que sí se confirmó antes se
 * "olvidaría" y el gate aprobaría trivialmente un resultado que sigue sin
 * citar nada real.
 */
@Component
public class EvidenceBindingGate {

    public BindingResult check(
            Collection<String> confirmedEvidenceUrls,
            AgentResult result) {

        if (confirmedEvidenceUrls == null || confirmedEvidenceUrls.isEmpty()) {
            // No hubo búsqueda confirmada esta vez (no pidió la
            // herramienta, o ningún candidato sobrevivió confirmReachable)
            // — no hay nada que el agente debiera haber citado.
            return new BindingResult(true, List.of());
        }

        var citedSources =
                result.evidence() == null
                        ? List.<String>of()
                        : result.evidence().stream()
                                .map(AgentResult.Evidence::source)
                                .filter(Objects::nonNull)
                                .map(EvidenceBindingGate::normalize)
                                .toList();

        var citedAtLeastOne =
                confirmedEvidenceUrls.stream()
                        .map(EvidenceBindingGate::normalize)
                        .anyMatch(citedSources::contains);

        if (citedAtLeastOne) {
            return new BindingResult(true, List.of());
        }

        return new BindingResult(
                false,
                List.of(
                        "El agente pidió evidencia real y recibió "
                                + confirmedEvidenceUrls.size()
                                + " fuente(s) confirmada(s) ("
                                + String.join(", ", confirmedEvidenceUrls)
                                + ") pero evidence[] en el resultado final no "
                                + "cita ninguna de ellas. Usa al menos una de "
                                + "esas URLs exactas como source en evidence[], "
                                + "o si de verdad ninguna sirve, explícalo en "
                                + "recommendation en vez de dejar evidence[] "
                                + "vacío o inventado."
                )
        );
    }

    private static String normalize(String url) {

        if (url == null) {
            return "";
        }

        return url.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("/+$", "");
    }

    public record BindingResult(boolean bound, List<String> errors) {
    }
}
