package com.aicompany.core.service;

import com.aicompany.core.config.AppProperties;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.AgentStatusResponse;
import com.aicompany.core.model.LeadResponse;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Consultas reales y actuales de la empresa contra Neo4j, formateadas
 * 100% en Java — contar/enumerar/formatear un estado real es una tarea
 * determinista, nunca se le pide al LLM que la arme (mismo criterio
 * anti-alucinación de todo el proyecto: el bug real de "dame un status"
 * rellenando placeholders inventados, documentado en {@code CLAUDE.md}).
 * Único punto de acceso a estas consultas: tanto el atajo determinista de
 * {@link ChatIntentRouter} (keywords, sin tocar Ollama) como la
 * herramienta {@code query_company_memory} que {@link CeoService#chat}
 * puede pedir para el chat general llaman a los mismos métodos acá —
 * antes de esta clase, cada camino tenía su propia copia del formateo
 * dentro de {@code ChatIntentRouter}, y una consulta que necesitaba un
 * parámetro (p. ej. el detalle de una misión puntual) nunca quedaba
 * disponible para el chat general, solo para el atajo de keywords.
 */
@Service
public class CompanyTools {

    private static final Logger log = LoggerFactory.getLogger(CompanyTools.class);

    // Límites fijos de las consultas "recent*" -- antes literales bare
    // repetidos en el call-site, sin nombre que explique de dónde salen.
    private static final int RECENT_ACTIVITY_LIMIT = 20;
    private static final int RECENT_DECISIONS_LIMIT = 10;

    // getChatHistory: mismo criterio de truncado que ya usa
    // getRecentDecisions (reasoning a 100 caracteres), pero acá hace
    // falta un límite doble -- cantidad de mensajes Y longitud de cada
    // uno. Encontrado en la revisión final de rama: un día con muchos
    // mensajes, sin cap, arma una sola línea de chat gigantesca; y
    // route() graba esa misma respuesta como el turno "ceo" de HOY --
    // preguntar "¿qué hablamos hoy?" repetidas veces sobre el mismo día
    // duplicaba aproximadamente el tamaño del transcript grabado en
    // cada vuelta (cada respuesta contiene la respuesta anterior
    // completa), el mismo problema de payload gigante ya documentado en
    // CLAUDE.md ("dijo 9, enumeró 9 de 25").
    private static final int CHAT_HISTORY_MAX_MESSAGES = 30;
    private static final int CHAT_HISTORY_MESSAGE_MAX_CHARS = 200;

    private final MissionService missionService;
    private final MissionMemoryService missionMemory;
    private final OpportunityMemoryService opportunityMemory;
    private final CustomerMemoryService customerMemory;
    private final ConversationMemoryService conversationMemory;
    private final ActivityMemoryService activityMemory;
    private final AppProperties appProperties;
    private final AlertMailService alertMailService;
    private final CompanyEventPublisher events;

    public CompanyTools(
            MissionService missionService,
            MissionMemoryService missionMemory,
            OpportunityMemoryService opportunityMemory,
            CustomerMemoryService customerMemory,
            ConversationMemoryService conversationMemory,
            ActivityMemoryService activityMemory,
            AppProperties appProperties,
            AlertMailService alertMailService,
            CompanyEventPublisher events) {

        this.missionService = missionService;
        this.missionMemory = missionMemory;
        this.opportunityMemory = opportunityMemory;
        this.customerMemory = customerMemory;
        this.conversationMemory = conversationMemory;
        this.activityMemory = activityMemory;
        this.appProperties = appProperties;
        this.alertMailService = alertMailService;
        this.events = events;
    }

    /**
     * Snapshot agregado y 100% real de la empresa. Cada número acá sale
     * de una consulta real a Neo4j, nunca del modelo.
     */
    public String getCompanyStatus() {

        var missions = missionMemory.findAll(50).stream()
                .filter(m -> "PRODUCTION".equals(m.environment()))
                .toList();

        var active = missions.stream()
                .filter(CompanyTools::isActiveMission)
                .count();

        var awaitingInvestor = missions.stream()
                .filter(m -> m.status() == MissionStatus.AWAITING_INVESTOR)
                .count();

        var failed = missions.stream()
                .filter(m -> m.status() == MissionStatus.FAILED)
                .count();

        var agentStatuses = missionMemory.latestTaskPerAgent();

        var working = agentStatuses.stream().filter(a -> "WORKING".equals(a.status())).count();
        var idle = agentStatuses.stream().filter(a -> "IDLE".equals(a.status())).count();

        var opportunities = opportunityMemory.countOpportunities();

        var customerCounts = customerMemory.countCustomersAndProspects();
        var customers = customerCounts[0];
        var prospects = customerCounts[1];
        var contacted = customerCounts[2];

        var totals = customerMemory.companyWideTotalRevenueAndCost();
        var revenue = totals[0];
        var netProfit = totals[0] - totals[1];

        return String.format(
                Locale.ROOT,
                "Estado actual de Forjai: capital disponible US$%.2f. "
                        + "Agentes: %d trabajando, %d inactivo(s). "
                        + "Misiones (producción): %d activa(s), %d esperando tu aprobación, %d fallida(s). "
                        + "Oportunidades registradas: %d. Prospectos (leads): %d. Contactados: %d. Clientes reales: %d. "
                        + "Ingresos: US$%.2f. Beneficio neto: US$%.2f.",
                appProperties.seedCapitalUsd(), working, idle,
                active, awaitingInvestor, failed,
                opportunities, prospects, contacted, customers,
                revenue, netProfit
        );
    }

    public String getAgentStatus() {

        var lines = missionMemory.latestTaskPerAgent().stream()
                .map(this::formatOneAgentStatus)
                .collect(Collectors.joining("; "));

        return "Estado real de los agentes: " + lines + ".";
    }

    /**
     * {@code a.status()} (WORKING/IDLE) es el estado propio del agente,
     * distinto de {@code a.taskStatus()} (el status de su última
     * AgentTask).
     */
    private String formatOneAgentStatus(AgentStatusResponse a) {

        var base = statusDot(a.status()) + " " + a.name() + " (" + a.role() + "): " + a.status();

        if (a.missionId() == null) {
            return base;
        }

        if ("WORKING".equals(a.status())) {
            return base + " (" + a.missionId()
                    + (a.action() == null ? "" : ", " + a.action())
                    + ")";
        }

        return base + " — última tarea: " + a.action() + " (" + a.missionId()
                + "), resultado: " + a.taskStatus();
    }

    private static final Set<String> STATUS_GREEN =
            Set.of("RUNNING", "WORKING", "ACTIVE", "COMPLETED");
    private static final Set<String> STATUS_RED =
            Set.of("FAILED", "CANCELLED");
    private static final Set<String> STATUS_YELLOW = Set.of(
            "PENDING", "WAITING", "AWAITING_INVESTOR", "CONSOLIDATING",
            "EVALUATING", "WAITING_AGENT_RESULTS", "PLANNING", "DELEGATING", "CREATED"
    );

    /**
     * Mismo mapeo semántico que {@code statusColor.ts} del frontend.
     */
    private static String statusDot(String status) {
        var upper = status.toUpperCase(Locale.ROOT);
        if (STATUS_GREEN.contains(upper)) return "🟢";
        if (STATUS_RED.contains(upper)) return "🔴";
        if (STATUS_YELLOW.contains(upper)) return "🟡";
        return "⚪";
    }

    /**
     * Estrictamente {@code AWAITING_INVESTOR} — una misión {@code FAILED}
     * no "necesita aprobación", tiene su propia consulta
     * ({@link #getFailedMissions()}).
     */
    public String getPendingApprovals() {

        var awaitingApproval = missionMemory.findAll(50).stream()
                .filter(m -> m.status() == MissionStatus.AWAITING_INVESTOR)
                .filter(m -> "PRODUCTION".equals(m.environment()))
                .toList();

        var awaitingApprovalIds = awaitingApproval.stream().map(MissionResponse::missionId).toList();
        conversationMemory.setLastMentioned("MISSION", awaitingApprovalIds);
        conversationMemory.recordChatMention("MISSION", awaitingApprovalIds);

        if (awaitingApproval.isEmpty()) {
            return "No hay ninguna misión que necesite tu aprobación en este momento.";
        }

        var lines = awaitingApproval.stream()
                .map(m -> m.missionId() + " (" + m.status() + ")")
                .collect(Collectors.joining(", "));

        return "Tenés " + awaitingApproval.size()
                + " misión(es) que necesitan tu aprobación: " + lines + ".";
    }

    public String getFailedMissions() {

        var failed = missionMemory.findAll(50).stream()
                .filter(m -> m.status() == MissionStatus.FAILED)
                .filter(m -> "PRODUCTION".equals(m.environment()))
                .toList();

        var failedIds = failed.stream().map(MissionResponse::missionId).toList();
        conversationMemory.setLastMentioned("MISSION", failedIds);
        conversationMemory.recordChatMention("MISSION", failedIds);

        if (failed.isEmpty()) {
            return "No hay ninguna misión fallida en este momento.";
        }

        var lines = failed.stream()
                .map(MissionResponse::missionId)
                .collect(Collectors.joining(", "));

        return "Tenés " + failed.size() + " misión(es) fallida(s): " + lines + ".";
    }

    /**
     * {@code environment == "TEST"}, cualquier status.
     */
    public String getTestMissions() {

        var test = missionMemory.findAll(50).stream()
                .filter(m -> "TEST".equals(m.environment()))
                .toList();

        var testIds = test.stream().map(MissionResponse::missionId).toList();
        conversationMemory.setLastMentioned("MISSION", testIds);
        conversationMemory.recordChatMention("MISSION", testIds);

        if (test.isEmpty()) {
            return "No hay ninguna misión en entorno de prueba en este momento.";
        }

        var lines = test.stream()
                .map(m -> m.missionId() + " (" + m.status() + ")")
                .collect(Collectors.joining(", "));

        return "Tenés " + test.size() + " misión(es) en entorno de prueba: " + lines + ".";
    }

    public String getOpportunities() {

        var opportunities = opportunityMemory.listRecent(20);

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

    /**
     * A diferencia de {@link #getOpportunities()} (lista global, sin
     * prospectos), trae la Opportunity real de una misión puntual junto
     * con sus prospectos reales ordenados por confidence descendente, y
     * setea el foco conversacional {@code type="CUSTOMER"} — solo cuando
     * hay algo real que enfocar (una oportunidad sin prospectos todavía
     * no debe pisar un foco previo, posiblemente todavía útil, con una
     * lista vacía).
     */
    public String getOpportunity(String missionId) {

        missionId = missionId.trim().toUpperCase(Locale.ROOT);

        var opportunity = opportunityMemory.findByMissionId(missionId);

        if (opportunity.isEmpty()) {
            return "No encontré ninguna oportunidad para " + missionId + ".";
        }

        var candidates = opportunityMemory.listCandidatesForMission(missionId);

        if (!candidates.isEmpty()) {
            var candidateIds = candidates.stream().map(LeadResponse::id).toList();
            conversationMemory.setLastMentioned("CUSTOMER", candidateIds);
            conversationMemory.recordChatMention("CUSTOMER", candidateIds);
        }

        var header = "Oportunidad " + opportunity.get().id() + " (misión " + opportunity.get().missionId()
                + ", estado " + opportunity.get().status() + "): " + opportunity.get().description();

        if (candidates.isEmpty()) {
            return header + " Todavía no hay ningún prospecto real identificado para esta oportunidad.";
        }

        var lines = candidates.stream()
                .map(this::formatCandidate)
                .collect(Collectors.joining(" | "));

        return header + " Prospectos reales identificados (" + candidates.size()
                + "), ordenados por probabilidad: " + lines;
    }

    public String getProspects() {

        var leads = opportunityMemory.listLeads();

        if (leads.isEmpty()) {
            return "No hay ningún lead activo para contactar.";
        }

        var lines = leads.stream()
                .map(l -> l.id() + " (misión " + l.missionId() + "): "
                        + l.name() + " — " + l.description())
                .collect(Collectors.joining(" | "));

        return "Tenés " + leads.size()
                + " lead(s) activo(s) para contactar: " + lines;
    }

    public String getFinancialStatus() {

        var totals = customerMemory.companyWideTotalRevenueAndCost();
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

    /**
     * Detalle real de una misión puntual (status, progreso, paso actual,
     * tareas de cada agente) — antes solo disponible para el atajo
     * determinista de keywords, nunca para el chat general del CEO vía
     * herramienta (esa limitación se cierra en la Task 3 de este plan).
     */
    public String getMission(String missionId) {

        missionId = missionId.trim().toUpperCase(Locale.ROOT);

        var details = missionService.details(missionId);

        if (details.isEmpty()) {
            return "No encontré la misión " + missionId + ".";
        }

        conversationMemory.setLastMentioned("MISSION", List.of(missionId));
        conversationMemory.recordChatMention("MISSION", List.of(missionId));

        var mission = details.get().mission();

        var tasksSummary = details.get().tasks().isEmpty()
                ? "sin tareas registradas todavía"
                : details.get().tasks().stream()
                        .map(t -> t.agentId() + ": " + t.status())
                        .collect(Collectors.joining(", "));

        return "La misión " + mission.missionId() + " está en " + mission.status()
                + " (" + mission.progress() + "% completado, paso actual: "
                + mission.currentStep() + "). " + mission.message()
                + ". Tareas de agentes: " + tasksSummary + ".";
    }

    /**
     * Detalle real del foco actual de la conversación — ramifica por
     * {@code type} porque un foco puede ser {@code MISSION} o
     * {@code CUSTOMER} (ver {@link #getOpportunity}).
     */
    public String getLastMentioned() {

        var focus = conversationMemory.lastMentioned();

        if (focus.isEmpty()) {
            return "No hay ninguna mención reciente de misiones ni prospectos en esta conversación.";
        }

        if ("CUSTOMER".equals(focus.get().type())) {
            return formatLastMentionedCustomers(focus.get().ids());
        }

        var missions = missionMemory.findByIds(focus.get().ids());

        var lines = missions.stream()
                .map(m -> m.missionId() + " (environment=" + m.environment() + ", status=" + m.status() + ")")
                .collect(Collectors.joining(", "));

        return "Las últimas misiones mencionadas fueron: " + lines + ".";
    }

    private String formatLastMentionedCustomers(List<String> ids) {

        var candidates = opportunityMemory.findCandidatesByIds(ids);

        if (candidates.isEmpty()) {
            return "No hay datos reales registrados para los prospectos mencionados recientemente.";
        }

        var lines = candidates.stream()
                .map(this::formatCandidate)
                .collect(Collectors.joining(" | "));

        return "Los últimos prospectos mencionados fueron: " + lines + ".";
    }

    /**
     * Línea de tiempo real de la empresa (misiones, tareas, evidencia,
     * decisiones) — reusa {@link ActivityMemoryService#recent}, la misma
     * fuente que ya alimenta la pantalla Activity del Command Center.
     */
    public String getRecentActivity() {

        var items = activityMemory.recent(RECENT_ACTIVITY_LIMIT);

        if (items.isEmpty()) {
            return "Todavía no hay actividad registrada.";
        }

        var lines = items.stream()
                .map(i -> "[" + i.type() + "] " + i.description())
                .collect(Collectors.joining(" | "));

        return "Actividad reciente (" + items.size() + " evento(s)): " + lines;
    }

    /**
     * Decisiones reales del inversionista humano, sin filtrar por
     * misión puntual (para eso está {@link #getMission(String)}). Incluye
     * {@code decidedAt} (una misma misión puede acumular varias
     * decisiones a lo largo del tiempo, p. ej. un
     * {@code REQUEST_MORE_EVIDENCE} seguido de un {@code APPROVE} —
     * sin fecha, dos líneas así son casi indistinguibles) y trunca
     * {@code reasoning} a 100 caracteres (puede ser una instrucción de
     * misión completa en texto libre, ~500 caracteres — mismo criterio
     * de truncado que ya usa {@code ActivityMemoryService}).
     */
    public String getRecentDecisions() {

        var decisions = missionMemory.recentDecisions(RECENT_DECISIONS_LIMIT);

        if (decisions.isEmpty()) {
            return "Todavía no se registró ninguna decisión real.";
        }

        var lines = decisions.stream()
                .map(d -> {
                    var reasoning = d.reasoning().length() > 100
                            ? d.reasoning().substring(0, 100) + "..."
                            : d.reasoning();
                    return d.missionId() + " (" + d.decidedAt() + "): " + d.decision() + " — " + reasoning;
                })
                .collect(Collectors.joining(" | "));

        return "Últimas " + decisions.size() + " decisión(es) real(es): " + lines;
    }

    /**
     * Misiones activas (ni {@code AWAITING_INVESTOR}, {@code FAILED},
     * {@code COMPLETED} ni {@code CANCELLED}) con su id y status real —
     * mismo criterio de "activa" que ya usa {@link #getCompanyStatus()}
     * para el conteo agregado, acá como listado. Setea el foco
     * conversacional {@code type="MISSION"}, mismo criterio que
     * {@link #getPendingApprovals()}/{@link #getFailedMissions()}.
     */
    public String getActiveMissions() {

        var active = missionMemory.findAll(50).stream()
                .filter(CompanyTools::isActiveMission)
                .toList();

        var activeIds = active.stream().map(MissionResponse::missionId).toList();
        conversationMemory.setLastMentioned("MISSION", activeIds);
        conversationMemory.recordChatMention("MISSION", activeIds);

        if (active.isEmpty()) {
            return "No hay ninguna misión activa en este momento.";
        }

        var lines = active.stream()
                .map(m -> m.missionId() + " (" + m.status() + ", " + m.progress() + "%)")
                .collect(Collectors.joining(", "));

        return "Tenés " + active.size() + " misión(es) activa(s): " + lines + ".";
    }

    /**
     * Predicado compartido de "misión activa" — extraído para que
     * {@link #getCompanyStatus()} y {@link #getActiveMissions()} no
     * puedan silenciosamente divergir si algún día se agrega un nuevo
     * {@link MissionStatus} terminal (antes cada método tenía su propia
     * cadena de lambdas inline, idéntica solo por convención de código
     * review, no por el compilador).
     */
    private static boolean isActiveMission(MissionResponse m) {
        return "PRODUCTION".equals(m.environment())
                && m.status() != MissionStatus.AWAITING_INVESTOR
                && m.status() != MissionStatus.FAILED
                && m.status() != MissionStatus.COMPLETED
                && m.status() != MissionStatus.CANCELLED;
    }

    /**
     * Transcript real de un día calendario puntual (formato
     * {@code YYYY-MM-DD}) — la respuesta a "¿qué hablamos el [día]?".
     * Nunca inventa contenido: si ese día no tiene ningún {@code Chat}
     * registrado, lo dice explícitamente.
     */
    public String getChatHistory(String date) {

        var messages = conversationMemory.chatHistoryForDate(date);

        if (messages.isEmpty()) {
            return "No hubo conversación registrada ese día.";
        }

        var totalCount = messages.size();
        var shown = totalCount > CHAT_HISTORY_MAX_MESSAGES
                ? messages.subList(0, CHAT_HISTORY_MAX_MESSAGES)
                : messages;

        var lines = shown.stream()
                .map(m -> m.role() + ": " + truncateChatMessage(m.content()))
                .collect(Collectors.joining(" | "));

        var omittedNote = totalCount > CHAT_HISTORY_MAX_MESSAGES
                ? " (+" + (totalCount - CHAT_HISTORY_MAX_MESSAGES) + " mensaje(s) más, no mostrados)"
                : "";

        return "Charla del " + date + " (" + totalCount + " mensaje(s)): " + lines + omittedNote;
    }

    private static String truncateChatMessage(String content) {
        return content.length() > CHAT_HISTORY_MESSAGE_MAX_CHARS
                ? content.substring(0, CHAT_HISTORY_MESSAGE_MAX_CHARS) + "..."
                : content;
    }

    /**
     * Los días reales en los que se mencionó esta entidad en el chat —
     * la respuesta a "¿en qué días hablamos de MISSION-X?". Acepta
     * cualquier id real (misión u otra entidad), no solo
     * {@code MISSION-<n>} — el atajo determinista de keywords en
     * {@code ChatIntentRouter} solo lo dispara con un
     * {@code MISSION-<id>} explícito, pero el chat general del CEO
     * puede pedirlo con cualquier id real vía la herramienta.
     */
    public String getDaysMentioning(String id) {

        var dates = conversationMemory.daysMentioning(id);

        if (dates.isEmpty()) {
            return "No encontré menciones de " + id + " en el historial de chat.";
        }

        return "Hablamos de " + id + " en " + dates.size() + " día(s): " + String.join(", ", dates) + ".";
    }

    /**
     * Dispara el contacto real (email) a un prospecto ya resuelto por
     * {@code ChatIntentRouter} contra el foco conversacional -- el
     * comando "contactalo" del fundador ejecuta este método de
     * inmediato, sin paso de confirmación intermedio (decisión
     * explícita del usuario, ver el spec).
     */
    public String contactProspect(LeadResponse candidate) {

        if (candidate.contactEmail() == null || candidate.contactEmail().isBlank()) {
            return "No tengo un dato de contacto directo (teléfono/email) registrado para "
                    + "este prospecto, solo la fuente donde se identificó.";
        }

        if (!opportunityMemory.claimForContact(candidate.id())) {
            return "Este prospecto ya tiene un contacto en progreso o ya fue contactado.";
        }

        var subject = ProspectOutreachEmailTemplate.subject(candidate);
        var body = ProspectOutreachEmailTemplate.body(candidate);

        var attemptId = opportunityMemory.recordContactAttempt(
                candidate.id(), candidate.missionId(), candidate.opportunityId(),
                candidate.contactEmail(), subject
        );

        var result = alertMailService.sendToExternal(candidate.contactEmail(), subject, body);

        if (!result.accepted()) {

            try {
                opportunityMemory.markContactFailed(attemptId, candidate.id(), result.errorMessage());

                events.publish(
                        "EMPRESA_PROSPECT_CONTACT_FAILED",
                        candidate.missionId(), null, "ceo",
                        Map.of("leadId", candidate.id(), "attemptId", attemptId, "reason", result.errorMessage())
                );
            } catch (Exception ex) {
                log.warn("No se pudo registrar el fallo de contacto para {} (attempt {}): {}",
                        candidate.id(), attemptId, ex.getMessage());
            }

            return "Intenté enviar el correo, pero no se pudo (" + result.errorMessage() + "). "
                    + "El intento quedó registrado y el prospecto sigue disponible para reintentar.";
        }

        try {
            opportunityMemory.markContactSent(attemptId, candidate.id());

            events.publish(
                    "EMPRESA_PROSPECT_CONTACTED",
                    candidate.missionId(), null, "ceo",
                    Map.of("leadId", candidate.id(), "attemptId", attemptId, "recipientEmail", candidate.contactEmail())
            );
        } catch (Exception ex) {
            log.warn("Correo real enviado a {} pero no se pudo actualizar el registro (attempt {}): {}",
                    candidate.contactEmail(), attemptId, ex.getMessage());
        }

        return "Listo, le mandé un correo real a " + candidate.contactEmail()
                + ". (ContactAttempt " + attemptId + ", estado: SENT)";
    }

    /**
     * Fragmento de formato compartido — también usado por
     * {@code ChatIntentRouter.formatCustomerReferenceAnswer} (la lógica
     * de "contactalo" se queda en el router porque es desambiguación de
     * una referencia conversacional, no una consulta de datos de la
     * empresa, pero necesita el mismo formato de salida). Antepone el
     * {@code status} real cuando ya no es {@code LEAD} (p. ej.
     * {@code DESCARTADO}/{@code CONVERTIDO}): {@code findCandidatesByIds}
     * deliberadamente no filtra por status, así que sin esta marca un
     * lead ya descartado o convertido se presentaría como si siguiera
     * activo.
     */
    public String formatCandidate(LeadResponse c) {

        var status = c.status();

        var statusPrefix = (status != null && !status.isBlank() && !"LEAD".equals(status))
                ? "(" + status + ") "
                : "";

        return statusPrefix + c.name() + " (confidence="
                + String.format(Locale.ROOT, "%.2f", c.confidence())
                + ", fuente: " + c.source() + "): " + c.description();
    }
}
