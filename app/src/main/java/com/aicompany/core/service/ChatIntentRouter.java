package com.aicompany.core.service;

import com.aicompany.core.model.AgentStatusResponse;
import com.aicompany.core.model.DecisionCommand;
import com.aicompany.core.model.InvestorDecision;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.OpportunitySummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Command Center web: el chat no es {@code POST /chat → LLM → texto}. Un
 * router determinista (regex/keywords, sin llamar a Ollama para decidir
 * la ruta — mismo criterio que {@code ClaimRelevanceChecker}/
 * {@code EvidenceBindingGate}: nunca "el modelo revisándose a sí mismo")
 * decide primero qué tipo de mensaje es:
 *
 * <ol>
 *   <li><b>Inicio de misión</b> — comportamiento ya existente, movido
 *       aquí desde {@code CompanyController}.</li>
 *   <li><b>Decisión</b> ("aprueba"/"rechaza"/"pide más evidencia" +
 *       {@code MISSION-<id>} explícito) — llama <b>directo</b> a
 *       {@link MissionService#recordDecision}, la misma gobernanza que
 *       {@code POST /missions/{id}/decision}, nunca una ruta paralela ni
 *       una ejecución "de chat" — si el mensaje no nombra una misión
 *       explícita, no se adivina: cae al chat general.</li>
 *   <li><b>Consulta</b> (estado de agentes, misiones que necesitan
 *       aprobación, oportunidades, gasto de la compañía) — la respuesta
 *       se arma <b>determinísticamente en Java</b>, sin pasar por Ollama.
 *       Verificado en vivo antes de esto: pasarle los datos reales a
 *       {@code CeoService.answerGroundedQuery} (una versión anterior de
 *       este router) para que el modelo los "fraseara" parecía razonable,
 *       pero con una lista de 25 misiones el modelo directamente
 *       subcontó (dijo "9" y enumeró solo 9) — no inventó datos falsos,
 *       pero tampoco enumeró correctamente los reales. Contar/enumerar
 *       una lista real es una tarea puramente determinista: no hay
 *       ninguna razón para dejarle ese trabajo a un LLM cuando Java lo
 *       hace sin margen de error.</li>
 *   <li><b>General</b> — {@link CeoService#chat}, sin cambios.</li>
 * </ol>
 */
@Service
public class ChatIntentRouter {

    private static final Logger log =
            LoggerFactory.getLogger(ChatIntentRouter.class);

    private static final Pattern MISSION_START =
            Pattern.compile("(?i)\\b(?:ejecuta|inicia)\\s+(MISSION-\\d+)\\b");

    private static final Pattern MISSION_ID =
            Pattern.compile("(?i)(MISSION-[\\w-]+)");

    private static final Pattern APPROVE =
            Pattern.compile("(?i)\\b(aprueba|apruebo|aprobar)\\b");

    private static final Pattern REJECT =
            Pattern.compile("(?i)\\b(rechaza|rechazo|rechazar)\\b");

    private static final Pattern MORE_EVIDENCE =
            Pattern.compile("(?i)mas\\s+evidencia");

    private final MissionService missionService;
    private final CeoService ceoService;
    private final MissionMemoryService missionMemory;
    private final OpportunityMemoryService opportunityMemory;
    private final CustomerMemoryService customerMemory;
    private final CompanyMemoryService companyMemory;

    public ChatIntentRouter(
            MissionService missionService,
            CeoService ceoService,
            MissionMemoryService missionMemory,
            OpportunityMemoryService opportunityMemory,
            CustomerMemoryService customerMemory,
            CompanyMemoryService companyMemory) {

        this.missionService = missionService;
        this.ceoService = ceoService;
        this.missionMemory = missionMemory;
        this.opportunityMemory = opportunityMemory;
        this.customerMemory = customerMemory;
        this.companyMemory = companyMemory;
    }

    public String route(String message) {

        var missionStartMatcher = MISSION_START.matcher(message);

        if (missionStartMatcher.find()) {

            var missionId = missionStartMatcher.group(1).toUpperCase(Locale.ROOT);
            var response = missionService.start(missionId, message);

            return "He recibido " + missionId + ". Estado: " + response.status()
                    + ". La misión está procesándose en segundo plano. Consulta "
                    + "/api/company/missions/" + missionId
                    + "/details para ver el progreso y las tareas.";
        }

        var decision = detectDecision(message);

        if (decision != null) {
            return handleDecision(decision, message);
        }

        var query = detectQuery(message);

        if (query != null) {
            return handleQuery(query);
        }

        return ceoService.chat(
                companyMemory.agentName("ceo").orElse("CEO"),
                companyMemory.teamRosterDescription(),
                message,
                this::answerMemoryTopic
        );
    }

    private record DetectedDecision(String missionId, InvestorDecision decision) {
    }

    private DetectedDecision detectDecision(String message) {

        var missionIdMatcher = MISSION_ID.matcher(message);

        if (!missionIdMatcher.find()) {
            // Sin un MISSION-<id> explícito no hay a qué misión decidir
            // -- nunca se adivina, cae al chat general.
            return null;
        }

        var missionId = missionIdMatcher.group(1).toUpperCase(Locale.ROOT);

        if (MORE_EVIDENCE.matcher(normalize(message)).find()) {
            return new DetectedDecision(missionId, InvestorDecision.REQUEST_MORE_EVIDENCE);
        }

        if (APPROVE.matcher(message).find()) {
            return new DetectedDecision(missionId, InvestorDecision.APPROVE);
        }

        if (REJECT.matcher(message).find()) {
            return new DetectedDecision(missionId, InvestorDecision.REJECT);
        }

        return null;
    }

    private String handleDecision(DetectedDecision decision, String message) {

        log.info(
                "CHAT_INTENT_DECISION mission={} decision={}",
                decision.missionId(),
                decision.decision()
        );

        var response = missionService.recordDecision(
                decision.missionId(),
                new DecisionCommand(decision.decision(), message)
        );

        if (response.isEmpty()) {
            return "No encontré la misión " + decision.missionId() + ".";
        }

        return "Decisión registrada: " + decision.decision()
                + " sobre " + decision.missionId()
                + ". Quedó guardada como Decision real, no fue una ejecución directa de chat.";
    }

    private enum QueryIntent {
        AGENT_STATUS,
        MISSIONS_NEEDING_ATTENTION,
        OPPORTUNITIES,
        COMPANY_PROFIT
    }

    private QueryIntent detectQuery(String message) {

        var normalized = normalize(message);

        if (normalized.contains("equipo")
                || normalized.contains("agente")
                || normalized.contains("trabajando")) {
            // Deliberadamente amplio (antes exigía "agente" Y
            // "trabaj"/"estado" juntos, lo que dejaba afuera preguntas
            // reales como "preséntame al equipo", "¿quién está
            // trabajando ahora?" o "¿qué está haciendo cada agente?" --
            // ninguna de esas tres contiene ambas palabras a la vez,
            // reproducido en vivo por el usuario). Caían al chat
            // general, donde el CEO (LLM) elaboraba por encima del
            // roster real inyectado en el prompt e incluso agregaba
            // disclaimers de privacidad contradictorios. Todas son la
            // misma pregunta de fondo (estado real de los agentes), así
            // que resuelven igual: 100% desde Neo4j, sin pasar por el
            // modelo.
            return QueryIntent.AGENT_STATUS;
        }

        if (normalized.contains("aprobacion")
                || normalized.contains("bloquead")
                || normalized.contains("necesita")) {
            return QueryIntent.MISSIONS_NEEDING_ATTENTION;
        }

        if (normalized.contains("oportunidad")) {
            return QueryIntent.OPPORTUNITIES;
        }

        if (normalized.contains("gastado")
                || normalized.contains("gasto")
                || normalized.contains("dinero")
                || normalized.contains("ganancia")
                || normalized.contains("beneficio")) {
            return QueryIntent.COMPANY_PROFIT;
        }

        return null;
    }

    private String handleQuery(QueryIntent intent) {

        log.info("CHAT_INTENT_QUERY intent={}", intent);

        return answerMemoryTopic(intent.name());
    }

    /**
     * Resuelve un {@code topic} real contra Neo4j reusando los mismos
     * formatters deterministas de arriba — llamado tanto por el atajo de
     * keywords ({@link #handleQuery}, sin pasar por Ollama) como por la
     * herramienta {@code query_company_memory} que {@link CeoService#chat}
     * puede pedir para el chat general (ver {@code CeoService} para el
     * porqué: antes de esa herramienta, el CEO alucinaba estos datos en
     * cualquier frase que no matcheara exactamente un keyword).
     */
    String answerMemoryTopic(String topic) {

        return switch (topic) {
            case "AGENT_STATUS" -> formatAgentStatus(missionMemory.latestTaskPerAgent());
            case "MISSIONS_NEEDING_ATTENTION" -> formatMissionsNeedingAttention(missionMemory.findAll(50));
            case "OPPORTUNITIES" -> formatOpportunities(opportunityMemory.listRecent(20));
            case "COMPANY_PROFIT" -> formatCompanyProfit(customerMemory.companyWideTotalRevenueAndCost());
            default -> "Dato no reconocido: " + topic + ".";
        };
    }

    private String formatAgentStatus(List<AgentStatusResponse> statuses) {

        var lines = statuses.stream()
                .map(a -> statusDot(a.status()) + " " + a.name() + " (" + a.role() + "): " + a.status()
                        + (a.missionId() == null
                        ? ""
                        : " (" + a.missionId()
                        + (a.action() == null ? "" : ", " + a.action())
                        + ")"))
                .collect(Collectors.joining("; "));

        return "Estado real de los agentes: " + lines + ".";
    }

    private static final java.util.Set<String> STATUS_GREEN =
            java.util.Set.of("RUNNING", "WORKING", "ACTIVE", "COMPLETED");
    private static final java.util.Set<String> STATUS_RED =
            java.util.Set.of("FAILED", "CANCELLED");
    private static final java.util.Set<String> STATUS_YELLOW = java.util.Set.of(
            "PENDING", "WAITING", "AWAITING_INVESTOR", "CONSOLIDATING",
            "EVALUATING", "WAITING_AGENT_RESULTS", "PLANNING", "DELEGATING", "CREATED"
    );

    /**
     * Mismo mapeo semántico que {@code statusColor.ts} del frontend
     * (misma fuente de verdad para el color/emoji de un estado, no dos
     * heurísticas distintas que puedan desincronizarse).
     */
    private static String statusDot(String status) {
        var upper = status.toUpperCase(Locale.ROOT);
        if (STATUS_GREEN.contains(upper)) return "🟢";
        if (STATUS_RED.contains(upper)) return "🔴";
        if (STATUS_YELLOW.contains(upper)) return "🟡";
        return "⚪";
    }

    private String formatMissionsNeedingAttention(List<MissionResponse> missions) {

        var needingAttention = missions.stream()
                .filter(m -> m.status() == MissionStatus.AWAITING_INVESTOR
                        || m.status() == MissionStatus.FAILED)
                .toList();

        if (needingAttention.isEmpty()) {
            return "No hay ninguna misión que necesite tu aprobación en este momento.";
        }

        var lines = needingAttention.stream()
                .map(m -> m.missionId() + " (" + m.status() + ")")
                .collect(Collectors.joining(", "));

        return "Tenés " + needingAttention.size()
                + " misión(es) que necesitan tu aprobación: " + lines + ".";
    }

    private String formatOpportunities(List<OpportunitySummary> opportunities) {

        if (opportunities.isEmpty()) {
            return "Todavía no hay ninguna oportunidad identificada.";
        }

        var lines = opportunities.stream()
                .map(o -> o.id() + " (misión " + o.missionId() + ", estado "
                        + o.status() + "): " + o.description())
                .collect(Collectors.joining(" | "));

        return "Tenés " + opportunities.size()
                + " oportunidad(es) identificada(s): " + lines;
    }

    private String formatCompanyProfit(double[] totals) {

        var revenue = totals[0];
        var cost = totals[1];
        var netProfit = revenue - cost;

        return String.format(
                Locale.ROOT,
                "Ingresos totales reales: US$%.2f. Costos totales reales: "
                        + "US$%.2f. Utilidad neta real: US$%.2f.",
                revenue, cost, netProfit
        );
    }

    private static String normalize(String text) {

        var decomposed = Normalizer.normalize(
                text.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        );

        return decomposed.replaceAll("\\p{M}", "");
    }
}
