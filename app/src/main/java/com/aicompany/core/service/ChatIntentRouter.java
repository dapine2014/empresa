package com.aicompany.core.service;

import com.aicompany.core.model.ProductView;

import com.aicompany.core.model.CatalogStatus;

import com.aicompany.core.model.CatalogProduct;

import com.aicompany.core.model.FinanceSummary;

import java.util.Optional;

import com.aicompany.core.model.DecisionResponse;

import com.aicompany.core.model.TeamMemberInfo;

import com.aicompany.core.model.TaskIds;

import com.aicompany.core.model.ChatReply;
import com.aicompany.core.model.ConversationTurn;

import com.aicompany.core.model.AgentStatusResponse;
import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.DecisionCommand;
import com.aicompany.core.model.InvestorDecision;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.OpportunitySummary;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.ProductStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    /**
     * Solo el id EXACTO de uno de los 3 equipos (case-sensitive, mismo
     * principio determinista que MISSION-<id>). Nunca se infiere el equipo
     * de frases como "Engineering Team" (spec de Proyecto B §1).
     */
    private static final Pattern TEAM_ID_TOKEN =
            Pattern.compile("\\bTEAM-[A-Z]+(?:-[A-Z]+)*\\b");

    private static final Pattern MISSION_ID =
            Pattern.compile("(?i)(MISSION-[\\w-]+)");

    private static final Pattern APPROVE =
            Pattern.compile("(?i)\\b(aprueba|apruebo|aprobar)\\b");

    private static final Pattern REJECT =
            Pattern.compile("(?i)\\b(rechaza|rechazo|rechazar)\\b");

    private static final Pattern MORE_EVIDENCE =
            Pattern.compile("(?i)mas\\s+evidencia");

    // \b explícito: "prueba" (contains) también matcheaba dentro de
    // "aprueba" ("a" + "prueba"), rompiendo la detección de decisión --
    // reproducido en vivo (fallo real en test antes de este ajuste).
    private static final Pattern TEST_ENVIRONMENT =
            Pattern.compile("\\bprueba");

    // Bug real encontrado en la revisión final de Creative/Product
    // Intelligence + Marketing & Growth: "necesita" (contains) también
    // matcheaba como PREFIJO de "necesito"/"necesitamos" -- a diferencia
    // de TEST_ENVIRONMENT arriba (donde "arte"/"prueba" aparecían en
    // MEDIO de una palabra ajena), acá "necesitamos" sí empieza en un
    // borde de palabra real, así que un \b solo no alcanza. La distinción
    // es semántica, no solo léxica: "necesita"/"necesitan" (3ª persona)
    // preguntan qué necesita una misión/entidad consultada; "necesito"/
    // "necesitamos" (1ª persona) expresan una necesidad del USUARIO, sin
    // relación con qué misión espera aprobación. Se listan ambas formas
    // de 3ª persona explícitamente en vez de un prefijo + exclusión.
    private static final Pattern NEEDS_APPROVAL_VERB =
            Pattern.compile("\\bnecesita\\b|\\bnecesitan\\b");

    // Boundary explícito por el mismo motivo que TEST_ENVIRONMENT. Y
    // deliberadamente NO usa normalize() (que saca tildes): "estás"
    // (verbo) normaliza a "estas" y choca con el pronombre demostrativo
    // "estas" -- reproducido en vivo por un test existente que se rompió
    // ("Hola, ¿cómo estás?" empezaba a interpretarse como una referencia).
    // Sobre el texto original con tilde, "estás" (verbo) y "estas"
    // (pronombre) son literales distintos y no chocan.
    private static final Pattern REFERENCE_PRONOUN =
            Pattern.compile("(?i)\\b(esas|esos|estas|estos|ellas|ellos)\\b");

    // Cuantificadores sobre el foco que NO son pronombres demostrativos --
    // "las dos misiones están aprobadas" (la prueba definitiva del
    // usuario) no contiene ningún pronombre de REFERENCE_PRONOUN, así que
    // sin este segundo gate el mensaje nunca entraba a handleReference.
    private static final Pattern FOCUS_QUANTIFIER =
            Pattern.compile("(?i)\\b(las dos|los dos|ambas|ambos|todas|todos)\\b");

    // Formas adjetivas de la decisión ("aprobada(s)", "rechazada(s)") --
    // distintas de APPROVE/REJECT de arriba (formas verbales, exigen un
    // MISSION-<id> explícito en detectDecision). Un comando sobre el foco
    // conversacional nunca trae un MISSION-<id> en el mensaje.
    private static final Pattern COMMAND_APPROVE =
            Pattern.compile("\\b(aprueba|apruebo|aprobar|aprobad[oa]s?)\\b");

    private static final Pattern COMMAND_REJECT =
            Pattern.compile("\\b(rechaza|rechazo|rechazar|rechazad[oa]s?)\\b");

    // 10 turnos (20 mensajes) -- suficiente para continuidad real de
    // charla sin dejar crecer el prompt del CEO sin límite (reportado
    // por el usuario: "no está recordando las charlas que tengo con el
    // CEO" -- antes CeoService.chat no mandaba ningún turno anterior).
    private static final int HISTORY_LIMIT = 20;

    private static final Pattern PRODUCT_STATUS_PREDICATE =
            Pattern.compile("\\b(desarroll\\w*|mvp|publicad\\w*|lanzad\\w*)\\b");

    // Dos disclaimers distintos a propósito (singular vs. plural) -- ver
    // formatMissionStatus/formatProductStatusAnswer. No unificar la
    // redacción: forzar un solo texto sería un cambio más grande y más
    // riesgoso que simplemente nombrarlos.
    private static final String NO_DEVELOPMENT_EVIDENCE_DISCLAIMER_SINGLE =
            " No tengo registro de ninguna AgentTask de desarrollo real, evento de "
                    + "desarrollo iniciado, ni artefacto/repositorio/build para esta "
                    + "misión — no puedo afirmar que el desarrollo haya comenzado.";

    private static final String NO_DEVELOPMENT_EVIDENCE_DISCLAIMER_MULTI =
            " Ninguna de estas misiones tiene evidencia real de desarrollo (AgentTask de "
                    + "desarrollo, evento de desarrollo iniciado o artefacto/repositorio/build) "
                    + "salvo que se indique lo contrario arriba — no asumas que el desarrollo "
                    + "comenzó solo porque el workflow de análisis haya terminado.";

    private final MissionService missionService;
    private final CeoService ceoService;
    private final MissionMemoryService missionMemory;
    private final OpportunityMemoryService opportunityMemory;
    private final CustomerMemoryService customerMemory;
    private final CompanyMemoryService companyMemory;
    private final ConversationMemoryService conversationMemory;
    private final CompanyPolicyService companyPolicyService;
    private final CustomerService customerService;
    private final ProductStatusService productStatusService;
    private final String defaultCeoModel;
    private final TeamMemoryService teamMemory;
    private final FinanceService financeService;
    private final DependencyMemoryService dependencyMemory;
    private final ProductService productService;
    private final ModelHealthService modelHealth;
    private final ProductOrchestrator orchestrator;
    private final ApiKeyService apiKeys;
    private final AutonomyService autonomy;
    private final com.aicompany.core.prospecting.ProspectingMemoryService prospectingMemory;
    private final com.aicompany.core.prospecting.StrategyProposalService strategyService;
    private final com.aicompany.core.outreach.OutreachService outreachService;
    private final com.aicompany.core.outreach.OutreachMemoryService outreachMemory;

    /** Spec orquestador §4 (2026-09-28): gobernanza del ciclo de producto, antes que los comandos de producto. */
    private static final Pattern ORCHESTRATOR_COMMAND = Pattern.compile(
            "^\\s*(?:@\\S+[\\s,]+)*(?:por favor\\s+)?(pausa|reanuda)(?:\\s+(?:el|al))?\\s+orquestador\\s*[.!]?\\s*$");

    /** Spec modo automático (2026-09-29): comandos del fundador, en la gobernanza junto al orquestador. */
    private static final Pattern AUTONOMY_COMMAND = Pattern.compile(
            "^\\s*(?:@\\S+[\\s,]+)*(?:por favor\\s+)?(?:(pon) todo en automatico"
                    + "|(pausa|reanuda|enciende|activa|apaga|desactiva)(?:\\s+el)?(?:\\s+modo)?\\s+automatico)"
                    + "(?:[\\s,]+por favor)?\\s*[.!]?\\s*$");

    /** Spec búsqueda de prospectos §3: decisión del fundador sobre una estrategia propuesta (🔴). */
    private static final Pattern STRATEGY_COMMAND = Pattern.compile(
            "^\\s*(?:@\\S+[\\s,]+)*(?:por favor\\s+)?(aprueba|rechaza)\\s+la\\s+estrategia\\s+(.+?)\\s*[.!]?\\s*$");

    /** Spec contacto con prospectos §5: decisiones del fundador sobre correos y prospectos (🔴). */
    private static final Pattern OUTREACH_APPROVE_ALL = Pattern.compile(
            "^\\s*(?:@\\S+[\\s,]+)*(?:por favor\\s+)?aprueba\\s+(?:todos\\s+)?los\\s+correos\\s*[.!]?\\s*$");
    private static final Pattern OUTREACH_ONE = Pattern.compile(
            "^\\s*(?:@\\S+[\\s,]+)*(?:por favor\\s+)?(aprueba|descarta)\\s+el\\s+correo\\s+(?:a|para|de)\\s+(.+?)\\s*[.!]?\\s*$");
    private static final Pattern PROSPECT_RESPONSE = Pattern.compile(
            "^\\s*(.+?)\\s+respondio\\s+(interesado|no interesado|pidio baja|que no)\\s*[.!]?\\s*$");
    private static final Pattern PROSPECT_CONVERT = Pattern.compile(
            "^\\s*(?:@\\S+[\\s,]+)*(?:por favor\\s+)?convierte\\s+a\\s+(.+?)\\s+en\\s+cliente\\s*[.!]?\\s*$");

    /** Spec catálogo §6 B (2026-09-28): comandos del fundador sobre un producto, interpretados en Java. */
    private static final Pattern PRODUCT_COMMAND = Pattern.compile(
            "^\\s*(?:por favor\\s+)?(pausa|reanuda|retira|reactiva)(?:\\s+el)?(?:\\s+(?:producto|servicio))?\\s+(.+?)\\s*[.!]?\\s*$");
    private static final Pattern PRODUCT_NEEDS = Pattern.compile("le falta (?:a |al )?(?:producto |servicio )?(.+?) para (?:venderse|vender)");
    private final PromptMemoryService promptMemory;

    public ChatIntentRouter(
            MissionService missionService,
            CeoService ceoService,
            MissionMemoryService missionMemory,
            OpportunityMemoryService opportunityMemory,
            CustomerMemoryService customerMemory,
            CompanyMemoryService companyMemory,
            ConversationMemoryService conversationMemory,
            CompanyPolicyService companyPolicyService,
            CustomerService customerService,
            ProductStatusService productStatusService,
            @Value("${ollama.ceo-model}") String defaultCeoModel,
            TeamMemoryService teamMemory,
            PromptMemoryService promptMemory,
            FinanceService financeService,
            DependencyMemoryService dependencyMemory,
            ProductService productService,
            ModelHealthService modelHealth,
            ProductOrchestrator orchestrator,
            ApiKeyService apiKeys,
            AutonomyService autonomy,
            com.aicompany.core.prospecting.ProspectingMemoryService prospectingMemory,
            com.aicompany.core.prospecting.StrategyProposalService strategyService,
            com.aicompany.core.outreach.OutreachService outreachService,
            com.aicompany.core.outreach.OutreachMemoryService outreachMemory) {

        this.missionService = missionService;
        this.ceoService = ceoService;
        this.missionMemory = missionMemory;
        this.opportunityMemory = opportunityMemory;
        this.customerMemory = customerMemory;
        this.companyMemory = companyMemory;
        this.conversationMemory = conversationMemory;
        this.companyPolicyService = companyPolicyService;
        this.customerService = customerService;
        this.productStatusService = productStatusService;
        this.defaultCeoModel = defaultCeoModel;
        this.teamMemory = teamMemory;
        this.promptMemory = promptMemory;
        this.financeService = financeService;
        this.dependencyMemory = dependencyMemory;
        this.productService = productService;
        this.modelHealth = modelHealth;
        this.orchestrator = orchestrator;
        this.apiKeys = apiKeys;
        this.autonomy = autonomy;
        this.prospectingMemory = prospectingMemory;
        this.strategyService = strategyService;
        this.outreachService = outreachService;
        this.outreachMemory = outreachMemory;
    }

    /**
     * Graba cada turno (mensaje entrante + respuesta final) sin importar
     * qué camino de {@link #resolve} lo haya resuelto — auditable, mismo
     * criterio que {@code Decision}/{@code Evidence}. La lógica de
     * routing en sí no cambió de forma, solo se movió a {@link #resolve}.
     */
    public String route(String message) {
        return routeReplies(message).stream()
                .map(r -> r.agentId().equals("ceo") ? r.text() : r.name() + ": " + r.text())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    /**
     * Spec 2026-09-27 §5: por defecto responde Alex; con @menciones responden esos agentes, en orden. La gobernanza
     * (arranque de misión, decisiones) se resuelve antes que las menciones. Graba el mensaje del usuario una vez y
     * cada respuesta con el agentId de quien habla.
     */
    public List<ChatReply> routeReplies(String message) {

        var replies = resolveReplies(message);

        conversationMemory.recordMessage("user", message);
        for (var reply : replies) {
            conversationMemory.recordMessage(reply.agentId(), reply.text());
        }

        return replies;
    }

    private List<ChatReply> resolveReplies(String message) {

        if (isGovernance(message)) {
            return List.of(ceoReply(resolve(message)));
        }

        var agents = companyMemory.agents();
        var mentions = MentionResolver.resolve(message, agents);

        if (!mentions.unknown().isEmpty()) {
            var known = agents.stream().map(a -> a.get("name") + " (" + a.get("id") + ")")
                    .collect(java.util.stream.Collectors.joining(", "));
            return List.of(ceoReply("No conozco a " + mentions.unknown() + " en Forjai. Agentes: " + known + "."));
        }

        if (!mentions.agentIds().isEmpty()) {
            return mentions.agentIds().stream().map(id -> agentReply(id, message, agents)).toList();
        }

        return List.of(ceoReply(resolve(message)));
    }

    private boolean isGovernance(String message) {
        return AUTONOMY_COMMAND.matcher(normalize(message)).matches()
                || STRATEGY_COMMAND.matcher(normalize(message)).matches()
                || OUTREACH_APPROVE_ALL.matcher(normalize(message)).matches()
                || OUTREACH_ONE.matcher(normalize(message)).matches()
                || PROSPECT_RESPONSE.matcher(normalize(message)).matches()
                || PROSPECT_CONVERT.matcher(normalize(message)).matches()
                || ORCHESTRATOR_COMMAND.matcher(normalize(message)).matches()
                || PRODUCT_COMMAND.matcher(normalize(message)).matches()
                || MISSION_START.matcher(message).find()
                || detectFreeMissionStart(normalize(message))
                || detectDecision(message) != null;
    }

    private ChatReply ceoReply(String text) {
        return new ChatReply("ceo", companyMemory.agentName("ceo").orElse("CEO"), text);
    }

    private ChatReply agentReply(String agentId, String message, List<Map<String, Object>> agents) {

        var agent = agents.stream().filter(a -> agentId.equals(a.get("id"))).findFirst().orElseThrow();
        var name = String.valueOf(agent.get("name"));
        var data = missionData(message, agentId);

        try {
            String text;
            if ("ceo".equals(agentId)) {
                text = ceoService.chat(name, companyMemory.teamRosterDescription(), historyFor("ceo", agents),
                        data.message(), this::answerMemoryTopic, promptMemory.activePrompt("ceo"),
                        companyMemory.agentModel("ceo", defaultCeoModel));
            } else {
                var speaker = new CeoService.ChatSpeaker(agentId, name, String.valueOf(agent.get("role")),
                        String.valueOf(agent.get("personality")));
                text = ceoService.agentChat(speaker, companyMemory.teamRosterDescription(), historyFor(agentId, agents),
                        data.message(), this::answerMemoryTopic, promptMemory.activePrompt(agentId),
                        companyMemory.agentModel(agentId, defaultCeoModel));
            }
            return new ChatReply(agentId, name, flagValidationOverclaim("ceo".equals(agentId) ? text : cutForeignSpeakers(text, agentId, agents), data));
        } catch (Exception ex) {
            return new ChatReply(agentId, name, name + " no pudo responder: "
                    + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }

    /**
     * Verificado en vivo: con "Responde solo por ti" en el prompt, Sofia igual escribió una sección "**Max (…):**" con
     * números inventados. Java corta la respuesta en la primera línea que arranca con el nombre de otro agente.
     */
    private static String cutForeignSpeakers(String text, String speakerId, List<Map<String, Object>> agents) {
        if (text == null) {
            return null;
        }
        var lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            var line = lines[i].strip().replaceFirst("^[#*>\\-\\s]+", "");
            for (var agent : agents) {
                var otherName = String.valueOf(agent.get("name"));
                if (speakerId.equals(agent.get("id")) || otherName.isBlank()) {
                    continue;
                }
                if (line.matches("(?i)" + Pattern.quote(otherName) + "\\s*(\\(|:|\\*).*")) {
                    var kept = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, i)).strip();
                    return kept + "\n\n(" + otherName + " responde por sí misma/o; Forjai recortó lo que se escribió en su nombre.)";
                }
            }
        }
        return text;
    }

    /** Mensaje para el modelo (con los datos reales antepuestos, si los hay) y si alguno de esos resultados está validado. */
    private record MissionData(String message, boolean injected, boolean anyValidated) {
    }

    /**
     * Verificado en vivo (MISSION-E2E-DISC): los agentes mencionados no veían sus propios resultados y el modelo casi
     * nunca pide la herramienta. Si el mensaje nombra una misión que existe, Java antepone sus datos reales; a un agente
     * con tareas en esa misión, solo las suyas (con todas, Max resumía también lo de Sofia).
     */
    private MissionData missionData(String message, String speakerId) {
        var matcher = MISSION_ID.matcher(message);
        var blocks = new ArrayList<String>();
        var anyValidated = false;
        while (matcher.find()) {
            var missionId = matcher.group(1).toUpperCase(Locale.ROOT);
            var mission = missionMemory.find(missionId);
            if (mission.isEmpty()) {
                continue;
            }
            var tasks = missionMemory.tasks(missionId);
            var own = tasks.stream().filter(t -> speakerId.equals(t.agentId())).toList();
            var shown = own.isEmpty() ? tasks : own;
            var others = tasks.stream().filter(t -> !speakerId.equals(t.agentId()))
                    .map(t -> t.agentId() + " / " + t.action() + " (" + t.status() + ")")
                    .collect(Collectors.joining(", "));
            blocks.add(formatMissionDetails(mission.get(), shown)
                    + (own.isEmpty() ? "" : "\nOtras tareas de la misión (sus resultados los cuentan esos agentes, no tú): "
                            + (others.isEmpty() ? "ninguna" : others) + "."));
            anyValidated |= shown.stream().anyMatch(t -> t.result() != null && t.result().contains("\"VALIDATED\""));
        }
        return blocks.isEmpty()
                ? new MissionData(message, false, false)
                : new MissionData(CeoService.JAVA_MEMORY_DATA_MARKER + "\n" + String.join("\n\n", blocks)
                        + "\n\nMENSAJE DEL FUNDADOR:\n" + message, true, anyValidated);
    }

    private static final Pattern VALIDATION_CLAIM = Pattern.compile(
            "(?<!no )(?<!sin )(?<!no esta )(?<!no estan )\\b(validad[oa]s?|valido|valide|validamos|validaron)\\b"
                    + "|\\bdemanda (comprobada|confirmada|probada)\\b");

    static final String VALIDATION_OVERCLAIM_NOTE = "⚠️ Nota de Forjai: los resultados registrados de esta misión están en "
            + "NOT_VALIDATED; nada de lo anterior está validado con clientes o transacciones reales.";

    /** Verificado en vivo: "demanda validada" sobre un resultado NOT_VALIDATED. Lo marca Java, no el modelo. */
    private String flagValidationOverclaim(String text, MissionData data) {
        if (text == null || !data.injected() || data.anyValidated()) {
            return text;
        }
        return VALIDATION_CLAIM.matcher(normalize(text)).find() ? text + "\n\n" + VALIDATION_OVERCLAIM_NOTE : text;
    }

    /** Resultado real de cada tarea de la misión, armado en Java desde el AgentResult guardado (nunca por el modelo). */
    private String formatMissionDetails(String missionId) {
        var mission = missionMemory.find(missionId);
        if (mission.isEmpty()) {
            return "No tengo ese dato registrado. No existe ninguna misión con id " + missionId + " en Company Memory.";
        }
        return formatMissionDetails(mission.get(), missionMemory.tasks(missionId));
    }

    private String formatMissionDetails(MissionResponse mission, List<AgentTask> tasks) {
        var lines = new ArrayList<String>();
        lines.add(formatMissionStatus(mission, tasks));
        for (var task : tasks) {
            lines.add("- " + task.agentId() + " / " + task.action() + " (" + task.status() + "): "
                    + summarizeTaskResult(task.result()));
        }
        return String.join("\n", lines);
    }

    private String summarizeTaskResult(String result) {
        if (result == null || result.isBlank()) {
            return "sin resultado registrado.";
        }
        try {
            var node = JSON.readTree(result);
            if (!node.isObject()) {
                return truncate(result, 400);
            }
            var parts = new ArrayList<String>();
            var status = node.path("verificationStatus").asString("");
            if (!status.isBlank()) {
                parts.add("verificationStatus=" + status);
            }
            var recommendation = node.path("recommendation").asString("");
            if (!recommendation.isBlank()) {
                parts.add("recomendación: " + truncate(recommendation, 400));
            }
            var facts = new ArrayList<String>();
            node.path("facts").forEach(f -> { if (facts.size() < 3) facts.add(truncate(f.asString(""), 200)); });
            if (!facts.isEmpty()) {
                parts.add("hechos: " + String.join(" | ", facts));
            }
            var sources = new ArrayList<String>();
            node.path("evidence").forEach(e -> { if (sources.size() < 3) sources.add(e.path("source").asString("")); });
            if (!sources.isEmpty()) {
                parts.add("evidencia: " + String.join(", ", sources));
            }
            var calculations = new ArrayList<String>();
            node.path("calculations").forEach(c -> {
                if (calculations.size() < 5) {
                    calculations.add(c.path("name").asString("") + " = " + formatNumber(c.path("result").asDouble()));
                }
            });
            if (!calculations.isEmpty()) {
                parts.add("cálculos: " + String.join("; ", calculations));
            }
            return parts.isEmpty() ? truncate(result, 400) : String.join(". ", parts) + ".";
        } catch (Exception ex) {
            return truncate(result, 400);
        }
    }

    private static String formatNumber(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    /**
     * Historial desde el punto de vista de {@code speakerId}: sus propios turnos como "ceo" (assistant), los del
     * fundador como "user" y los de otros agentes como "user" etiquetados con nombre y rol (spec 2026-09-27 §5).
     */
    private List<ConversationTurn> historyFor(String speakerId, List<Map<String, Object>> agents) {
        return conversationMemory.recentMessages(HISTORY_LIMIT).stream()
                .map(turn -> {
                    if ("user".equals(turn.role())) {
                        return turn;
                    }
                    if (speakerId.equals(turn.role())) {
                        return new ConversationTurn("ceo", turn.content());
                    }
                    var other = agents.stream().filter(a -> turn.role().equals(a.get("id"))).findFirst();
                    var label = other.map(a -> a.get("name") + " (" + a.get("role") + ")").orElse(turn.role());
                    return new ConversationTurn("user", "[" + label + "]: " + turn.content());
                })
                .toList();
    }

    private String resolve(String message) {

        var orchestratorCommand = ORCHESTRATOR_COMMAND.matcher(normalize(message));
        if (orchestratorCommand.matches()) {
            return handleOrchestratorCommand("pausa".equals(orchestratorCommand.group(1)));
        }

        var autonomyCommand = AUTONOMY_COMMAND.matcher(normalize(message));
        if (autonomyCommand.matches()) {
            var verb = autonomyCommand.group(1) != null ? autonomyCommand.group(1) : autonomyCommand.group(2);
            return handleAutonomyCommand(List.of("pon", "reanuda", "enciende", "activa").contains(verb));
        }

        var strategyCommand = STRATEGY_COMMAND.matcher(normalize(message));
        if (strategyCommand.matches()) {
            return handleStrategyCommand("aprueba".equals(strategyCommand.group(1)), strategyCommand.group(2));
        }

        var normalizedMessage = normalize(message);
        if (OUTREACH_APPROVE_ALL.matcher(normalizedMessage).matches()) {
            return handleApproveAllDrafts();
        }
        var outreachOne = OUTREACH_ONE.matcher(normalizedMessage);
        if (outreachOne.matches()) {
            return handleOneDraft("aprueba".equals(outreachOne.group(1)), outreachOne.group(2));
        }
        var prospectResponse = PROSPECT_RESPONSE.matcher(normalizedMessage);
        if (prospectResponse.matches()) {
            return handleProspectResponse(prospectResponse.group(1), prospectResponse.group(2));
        }
        var prospectConvert = PROSPECT_CONVERT.matcher(normalizedMessage);
        if (prospectConvert.matches()) {
            return handleProspectConvert(prospectConvert.group(1));
        }

        var productCommand = PRODUCT_COMMAND.matcher(normalize(message));
        if (productCommand.matches()) {
            return handleProductCommand(productCommand.group(1), productCommand.group(2), message);
        }

        var missionStartMatcher = MISSION_START.matcher(message);

        if (missionStartMatcher.find()) {

            var missionId = missionStartMatcher.group(1).toUpperCase(Locale.ROOT);
            var teamId = detectTeamToken(message);

            if (teamId != null && !TeamMemoryService.KNOWN_TEAM_IDS.contains(teamId)) {
                return unknownTeamMessage(teamId);
            }

            // Una misión iniciada por un comando real de chat del
            // fundador es trabajo real, no una prueba de desarrollo.
            var response = startMission(missionId, message, teamId);

            return "He recibido " + missionId + ". Estado: " + response.status() + "."
                    + teamSuffix(teamId)
                    + " La misión está procesándose en segundo plano. Consulta "
                    + "/api/company/missions/" + missionId
                    + "/details para ver el progreso y las tareas.";
        }

        if (detectFreeMissionStart(normalize(message))) {
            return handleFreeMissionStart(message);
        }

        var decision = detectDecision(message);

        if (decision != null) {
            return handleDecision(decision, message);
        }

        var missionStatusId = detectMissionStatusQuery(message);

        var isFocusGovernanceCommand = detectReferenceCommand(normalize(message)) != null
                && (REFERENCE_PRONOUN.matcher(message).find() || FOCUS_QUANTIFIER.matcher(message).find());

        if (missionStatusId != null && !isFocusGovernanceCommand) {
            return handleMissionStatusQuery(missionStatusId);
        }

        var referenceMatcher = REFERENCE_PRONOUN.matcher(message);
        var focusQuantifierMatcher = FOCUS_QUANTIFIER.matcher(message);

        if (referenceMatcher.find() || focusQuantifierMatcher.find()) {
            return handleReference(message);
        }

        var query = detectQuery(message);

        if (query != null) {
            return handleQuery(query);
        }

        return ceoService.chat(
                companyMemory.agentName("ceo").orElse("CEO"),
                companyMemory.teamRosterDescription(),
                historyFor("ceo", companyMemory.agents()),
                message,
                this::answerMemoryTopic,
                promptMemory.activePrompt("ceo"),
                companyMemory.agentModel("ceo", defaultCeoModel)
        );
    }

    /**
     * Una instrucción real de negocio, en lenguaje libre, sin ningún
     * {@code MISSION-<número>} explícito todavía (por eso no la atrapa
     * {@link #MISSION_START}) — reportado por el usuario en vivo: su
     * instrucción real contenía la frase "sin mi aprobación" como
     * restricción, y el router determinista la interpretaba como una
     * CONSULTA (por el keyword {@code aprobacion} de
     * {@code MISSIONS_NEEDING_ATTENTION}) en vez de crear la misión.
     * Por eso este chequeo corre *antes* que {@code detectDecision}/
     * {@code detectQuery}.
     */
    private String detectTeamToken(String message) {
        var matcher = TEAM_ID_TOKEN.matcher(message);
        return matcher.find() ? matcher.group() : null;
    }

    private String unknownTeamMessage(String teamToken) {
        return "No inicié ninguna misión: " + teamToken + " no es un equipo de Forjai. Equipos válidos: "
                + TeamMemoryService.KNOWN_TEAM_IDS.stream().sorted().toList() + ".";
    }

    /** Sin equipo se llama la sobrecarga de siempre (mismo contrato que antes de esta feature). */
    private MissionResponse startMission(String missionId, String message, String teamId) {
        return teamId == null
                ? missionService.start(missionId, message, "PRODUCTION", null)
                : missionService.start(missionId, message, "PRODUCTION", null, teamId);
    }

    private static String teamSuffix(String teamId) {
        return teamId == null ? "" : " Equipo responsable: " + teamId + ".";
    }

    private boolean detectFreeMissionStart(String normalized) {

        if (!normalized.contains("mision")) {
            return false;
        }

        return normalized.contains("inicia")
                || normalized.contains("iniciar")
                || normalized.contains("comienza")
                || normalized.contains("empieza")
                || normalized.contains("crea")
                || normalized.contains("lanza")
                || normalized.contains("arranca");
    }

    /**
     * El {@code instruction} es el mensaje completo, tal cual -- el
     * mismo contrato que ya usa {@code MissionExecutor}/{@code AgentRuntime}
     * para cualquier misión (texto libre, sin parsear a una estructura
     * rígida). El id se genera acá porque el fundador no dio uno
     * explícito -- timestamp, sin heurística de nombres (acordado con el
     * usuario: simple, sin riesgo de colisión).
     */
    private String handleFreeMissionStart(String message) {

        var teamId = detectTeamToken(message);

        if (teamId != null && !TeamMemoryService.KNOWN_TEAM_IDS.contains(teamId)) {
            return unknownTeamMessage(teamId);
        }

        var missionId = "MISSION-" + Instant.now().toEpochMilli();

        log.info("CHAT_INTENT_FREE_MISSION_START missionId={} teamId={}", missionId, teamId);

        // Una misión iniciada por un comando real de chat del fundador
        // es trabajo real, no una prueba de desarrollo.
        var response = startMission(missionId, message, teamId);

        conversationMemory.setLastMentioned("MISSION", List.of(missionId));

        return "Creé la misión " + missionId + " con tu descripción y la mandé a "
                + "procesar en segundo plano. Estado: " + response.status() + "."
                + teamSuffix(teamId)
                + " Consulta /api/company/missions/" + missionId
                + "/details para ver el progreso y las tareas.";
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

        Optional<DecisionResponse> response;
        try {
            response = missionService.recordDecision(
                    decision.missionId(),
                    new DecisionCommand(decision.decision(), message)
            );
        } catch (IllegalStateException ex) {
            // Spec evidence-rounds: el límite de vueltas (o una misión en curso) se explica en el chat.
            return "No se registró la decisión sobre " + decision.missionId() + ": " + ex.getMessage();
        }

        if (response.isEmpty()) {
            return "No encontré la misión " + decision.missionId() + ".";
        }

        if (response.get().evidenceRound() != null) {
            return evidenceRoundStarted(decision.missionId(), response.get().evidenceRound());
        }

        return "Decisión registrada: " + decision.decision()
                + " sobre " + decision.missionId()
                + ". Quedó guardada como Decision real, no fue una ejecución directa de chat.";
    }

    /**
     * Un {@code MISSION-<id>} explícito que no fue ni inicio ni decisión —
     * el fundador está preguntando por el estado real de esa misión
     * puntual. Reportado en vivo: sin esta rama, este caso caía al chat
     * general y el CEO alucinó "el desarrollo del MVP está en curso"
     * sobre una misión COMPLETED con agentes IDLE.
     */
    private String detectMissionStatusQuery(String message) {

        var matcher = MISSION_ID.matcher(message);

        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /**
     * Resuelve 100% en Java, sin pasar por Ollama — mismo criterio que
     * {@code formatAgentStatus}/{@code formatCompanyStatus}. Es la única
     * garantía dura de esta feature (ver
     * docs/superpowers/specs/2026-09-20-chat-grounding-product-status-design.md).
     */
    private String handleMissionStatusQuery(String missionId) {

        log.info("CHAT_INTENT_MISSION_STATUS missionId={}", missionId);

        var mission = missionMemory.find(missionId);

        if (mission.isEmpty()) {
            return "No tengo ese dato registrado. No existe ninguna misión con id "
                    + missionId + " en Company Memory.";
        }

        conversationMemory.setLastMentioned("MISSION", List.of(missionId));

        return formatMissionStatus(mission.get(), missionMemory.tasks(missionId));
    }

    /**
     * {@code workflowStatus} (MissionStatus) y {@code productStatus}
     * (ProductStatusService) son preguntas distintas — nunca se infiere
     * una de la otra.
     */
    private String formatMissionStatus(MissionResponse mission, List<AgentTask> tasks) {

        var productStatus = productStatusService.resolve(mission.missionId());

        var taskLines = tasks.stream()
                .map(t -> t.agentId() + "=" + t.action() + " " + t.status())
                .collect(Collectors.joining(", "));

        var involvedAgentIds = tasks.stream()
                .map(AgentTask::agentId)
                .collect(Collectors.toSet());

        var agentStatusLines = missionMemory.latestTaskPerAgent().stream()
                .filter(a -> involvedAgentIds.contains(a.agentId()))
                .map(a -> a.name() + " (" + a.role() + "): " + a.status())
                .collect(Collectors.joining(", "));

        var rounds = formatEvidenceRounds(mission.missionId(), tasks);

        var closing = productStatus.ordinal() < ProductStatus.DEVELOPMENT.ordinal()
                ? NO_DEVELOPMENT_EVIDENCE_DISCLAIMER_SINGLE
                : "";

        return mission.missionId() + ": workflowStatus=" + mission.status()
                + " (esto es el estado del proceso de análisis/decisión interno, "
                + "NO implica nada sobre si el producto está en desarrollo, publicado "
                + "o generando ingresos). productStatus=" + productStatus
                + (rounds.isEmpty() ? ". Tareas de esta misión: " + taskLines + "." : "." + rounds)
                + " Estado actual de los agentes involucrados: " + agentStatusLines
                + "." + closing + fallbackTasks(mission.missionId()) + missionFinance(mission.missionId()) + formatFinancialCriteria(mission) + formatTeamExecution(mission, tasks);
    }

    /** Equipo, commits reales y estado de validación — 100% desde Neo4j, nunca redactado por el LLM. */
    private String formatTeamExecution(MissionResponse mission, List<AgentTask> tasks) {

        if (mission.teamId() == null) {
            return "";
        }

        var out = new StringBuilder(" Equipo responsable: ").append(mission.teamId()).append(".");

        var commits = tasks.stream()
                .filter(t -> t.commitSha() != null && !t.commitSha().isBlank())
                .map(t -> t.agentId() + "=" + t.commitSha().substring(0, Math.min(7, t.commitSha().length())))
                .collect(Collectors.joining(", "));

        if (!commits.isEmpty()) {
            out.append(" Commits: ").append(commits).append(".");
        }

        tasks.stream()
                .filter(t -> t.validationStatus() != null)
                .findFirst()
                .ifPresent(t -> out.append(" Validación estática: ").append(t.validationStatus())
                        .append(" (esta fase no ejecuta código)."));

        return out.toString();
    }

    /**
     * Expone {@code MissionResponse.financialCriteria} (Task 5) + su
     * evaluación real contra resultados reales ({@code CustomerService.netProfit},
     * Task 7) cuando el fundador pregunta por el estado de una misión
     * puntual — mismo criterio de grounding que el resto de este método:
     * nunca se afirma cumplimiento sin datos reales.
     */
    private String formatFinancialCriteria(MissionResponse mission) {

        if (mission.financialCriteria() == null) {
            return " Esta misión no tiene un objetivo financiero estructurado declarado.";
        }

        var criteria = mission.financialCriteria();
        var profit = customerService.netProfit(mission.missionId());
        var evaluation = profit.financialCriteriaEvaluation();
        var deadlineText = criteria.deadline() == null ? "sin plazo definido" : criteria.deadline().toString();

        if (evaluation == null) {
            return String.format(Locale.ROOT,
                    " Objetivo financiero declarado: %s >= %.2f %s (%s). Sin resultados reales registrados "
                            + "todavía para evaluar cumplimiento.",
                    criteria.metric(), criteria.targetAmount(), criteria.currency(), deadlineText);
        }

        return String.format(Locale.ROOT,
                " Objetivo financiero declarado: %s >= %.2f %s (%s). Resultado real: %.2f %s (%.1f%% del "
                        + "objetivo) -- %s.",
                criteria.metric(), criteria.targetAmount(), criteria.currency(), deadlineText,
                profit.netProfitUsd(), criteria.currency(), evaluation.progressPct(),
                evaluation.criterionMet() ? "objetivo cumplido" : "objetivo no cumplido todavía");
    }

    private enum ReferencePredicate {
        ENVIRONMENT_TEST,
        FAILED,
        NEEDS_APPROVAL,
        PRODUCT_STATUS
    }

    private ReferencePredicate detectReferencePredicate(String normalized) {

        if (TEST_ENVIRONMENT.matcher(normalized).find()) {
            return ReferencePredicate.ENVIRONMENT_TEST;
        }

        if (normalized.contains("fallaron")
                || normalized.contains("fallidas")
                || normalized.contains("fallida")) {
            return ReferencePredicate.FAILED;
        }

        if (normalized.contains("aprobacion") || NEEDS_APPROVAL_VERB.matcher(normalized).find()) {
            return ReferencePredicate.NEEDS_APPROVAL;
        }

        if (PRODUCT_STATUS_PREDICATE.matcher(normalized).find()) {
            return ReferencePredicate.PRODUCT_STATUS;
        }

        return null;
    }

    /**
     * A diferencia de {@link #detectDecision} (que exige un
     * {@code MISSION-<id>} explícito en el mensaje), esto resuelve un
     * comando de gobernanza ("las dos misiones están aprobadas", "esas
     * quedan rechazadas") contra el foco conversacional — la "prueba
     * definitiva" pedida por el usuario: la Company Chat debe poder
     * *operar*, no solo responder preguntas. Chequeado antes que
     * {@link #detectReferencePredicate} dentro de {@link #handleReference}
     * porque un comando y una consulta nunca son ambiguos entre sí en el
     * mismo mensaje.
     */
    private InvestorDecision detectReferenceCommand(String normalized) {

        if (MORE_EVIDENCE.matcher(normalized).find()) {
            return InvestorDecision.REQUEST_MORE_EVIDENCE;
        }

        if (COMMAND_APPROVE.matcher(normalized).find()) {
            return InvestorDecision.APPROVE;
        }

        if (COMMAND_REJECT.matcher(normalized).find()) {
            return InvestorDecision.REJECT;
        }

        return null;
    }

    /**
     * Aplica la MISMA gobernanza real que {@link #handleDecision} /
     * {@code POST /missions/{id}/decision} — {@link MissionService#recordDecision}
     * — a cada misión del foco, una por una. Nunca le pide al LLM que
     * "interprete" el comando: el router ya resolvió qué decisión es y
     * sobre qué misiones aplica, antes de tocar Ollama. Si una misión
     * puntual no está en un estado que admita la decisión (p. ej. ya fue
     * decidida antes), esa falla no debe ocultar el éxito de las demás —
     * se reporta el resultado real por misión, no un todo-o-nada.
     */
    private String handleReferenceCommand(InvestorDecision command, String message, List<String> missionIds) {

        log.info("CHAT_INTENT_REFERENCE_COMMAND decision={} missionIds={}", command, missionIds);

        var lines = new ArrayList<String>();
        var anySucceeded = false;

        for (var missionId : missionIds) {
            try {
                var response = missionService.recordDecision(missionId, new DecisionCommand(command, message));

                if (response.isPresent() && response.get().evidenceRound() != null) {
                    lines.add("✅ " + evidenceRoundStarted(missionId, response.get().evidenceRound()));
                    anySucceeded = true;
                } else if (response.isPresent()) {
                    lines.add("✅ " + missionId + " " + pastParticipleFor(command) + ".");
                    anySucceeded = true;
                } else {
                    lines.add("❌ " + missionId + " no se encontró.");
                }
            } catch (IllegalStateException e) {
                lines.add("❌ " + missionId + " no se pudo procesar (no está en un estado que admita esta decisión).");
            }
        }

        var closing = anySucceeded ? " Las decisiones fueron registradas en Company Memory." : "";

        return String.join(" ", lines) + closing;
    }

    /** Spec evidence-rounds (revisión 2026-09-27): qué ronda arrancó, de cuántas, y quiénes trabajan (100% Java). */
    private String evidenceRoundStarted(String missionId, int round) {
        var max = (int) companyPolicyService.activeValue(PolicyKey.MAX_EVIDENCE_ROUNDS);
        var teamId = missionMemory.teamId(missionId).orElse(null);
        var who = teamId == null
                ? "Sales, Product, Finance, Engineering y QA"
                : teamMemory.snapshot(teamId).members().stream().map(TeamMemberInfo::name)
                        .collect(Collectors.joining(", "));
        return "Arrancó la ronda " + round + " de " + max + " de más evidencia sobre " + missionId + ". Trabajan: "
                + who + ". Te aviso por aquí y por correo cuando vuelva a esperar tu decisión.";
    }

    /** Rondas de una misión: vacío si nunca pidió más evidencia (misiones previas a la feature incluidas). */
    private String formatEvidenceRounds(String missionId, List<AgentTask> tasks) {
        var round = missionMemory.evidenceRound(missionId);
        var requests = missionMemory.evidenceRequests(missionId);
        if (round == 0 && requests.isEmpty()) {
            return "";
        }
        var max = (int) companyPolicyService.activeValue(PolicyKey.MAX_EVIDENCE_ROUNDS);
        var byRound = new java.util.TreeMap<Integer, List<AgentTask>>();
        tasks.forEach(t -> byRound.computeIfAbsent(TaskIds.roundOf(t.taskId()), k -> new ArrayList<>()).add(t));
        var lines = new ArrayList<String>();
        lines.add(" Ronda de evidencia " + round + " de " + max + " (queda" + (max - round == 1 ? " 1" : "n " + Math.max(0, max - round)) + ").");
        byRound.forEach((r, list) -> {
            // Los pedidos se alinean desde el final: uno registrado antes de existir las rondas no disparó ninguna.
            var index = requests.size() - round + (r - 1);
            var label = r == 0 ? "Ronda 0" : "Ronda " + r + " (pedido: \""
                    + (index >= 0 && index < requests.size() ? requests.get(index) : "") + "\")";
            lines.add(" " + label + ": " + list.stream().map(t -> t.agentId() + "=" + t.action() + " " + t.status())
                    .collect(Collectors.joining(", ")) + ".");
        });
        return String.join("", lines);
    }

    private String pastParticipleFor(InvestorDecision decision) {

        return switch (decision) {
            case APPROVE -> "aprobada";
            case REJECT -> "rechazada";
            case REQUEST_MORE_EVIDENCE -> "necesita más evidencia";
        };
    }

    /**
     * Resuelve un pronombre demostrativo ("esas"/"esos") contra el foco
     * de la conversación — nunca contra el texto de una respuesta
     * anterior, siempre contra el dato real y actual de esas entidades
     * puntuales en Neo4j. Si el predicado no matchea nada reconocido,
     * cae al chat general con el foco disponible vía el topic
     * {@code LAST_MENTIONED} (grounded, no historial crudo al LLM).
     */
    private String handleReference(String message) {

        var focus = conversationMemory.lastMentioned();

        if (focus.isEmpty()) {
            return "No tengo claro a qué te referís — no mencioné ninguna misión todavía en esta conversación.";
        }

        var normalized = normalize(message);

        var command = detectReferenceCommand(normalized);

        if (command != null) {
            return handleReferenceCommand(command, message, focus.get().ids());
        }

        var predicate = detectReferencePredicate(normalized);

        if (predicate == null) {
            log.info("CHAT_INTENT_REFERENCE predicate=none focusSize={}", focus.get().ids().size());
            // Sin esta pista, el LLM no tiene forma de saber a qué tipo
            // de entidad se refiere "esas" -- reproducido en vivo: sin
            // ella, ignoraba la pregunta y contestaba sobre el equipo en
            // vez de las misiones (alucinando de nuevo). La pista solo
            // aclara el TIPO de referencia (dato ya conocido acá, en
            // Java); el contenido real sigue viniendo exclusivamente de
            // la herramienta LAST_MENTIONED, nunca de esta nota.
            var hint = "[Nota: \"esas\"/\"esos\" en este mensaje se refiere a las últimas "
                    + focus.get().type().toLowerCase(Locale.ROOT) + "(es) mencionadas en esta "
                    + "conversación. Si necesitás saber cuáles son o algo sobre ellas, "
                    + "usá la herramienta con topic=LAST_MENTIONED antes de responder — "
                    + "no asumas ni inventes cuáles son.] ";

            return ceoService.chat(
                    companyMemory.agentName("ceo").orElse("CEO"),
                    companyMemory.teamRosterDescription(),
                    historyFor("ceo", companyMemory.agents()),
                    hint + message,
                    this::answerMemoryTopic,
                    promptMemory.activePrompt("ceo"),
                    companyMemory.agentModel("ceo", defaultCeoModel)
            );
        }

        log.info("CHAT_INTENT_REFERENCE predicate={} focusSize={}", predicate, focus.get().ids().size());

        var missions = missionMemory.findByIds(focus.get().ids());

        if (predicate == ReferencePredicate.PRODUCT_STATUS) {
            return formatProductStatusAnswer(missions);
        }

        return formatReferenceAnswer(predicate, missions);
    }

    private String formatReferenceAnswer(ReferencePredicate predicate, List<MissionResponse> missions) {

        var matching = missions.stream()
                .filter(m -> matchesReferencePredicate(predicate, m))
                .toList();

        var total = missions.size();

        var description = switch (predicate) {
            case ENVIRONMENT_TEST -> "pertenecen al entorno de pruebas";
            case FAILED -> "fallaron";
            case NEEDS_APPROVAL -> "están esperando tu aprobación (AWAITING_INVESTOR)";
            case PRODUCT_STATUS -> throw new IllegalStateException(
                    "PRODUCT_STATUS se resuelve en formatProductStatusAnswer, nunca aquí");
        };

        if (matching.size() == total) {
            return "Correcto. Esas " + total + " misión(es) " + description + ".";
        }

        if (matching.isEmpty()) {
            return "No, ninguna de esas " + total + " misión(es) " + description + ".";
        }

        var matchingIds = matching.stream()
                .map(MissionResponse::missionId)
                .collect(Collectors.joining(", "));

        return matching.size() + " de " + total + " misión(es) " + description + ": " + matchingIds + ".";
    }

    /**
     * PRODUCT_STATUS no encaja en el patrón "cuántas de estas coinciden
     * con X" de {@link #formatReferenceAnswer} — cada misión del foco
     * tiene su propio productStatus real, así que se lista una por una.
     * Mismo criterio de grounding que {@code handleMissionStatusQuery}:
     * nunca se afirma desarrollo real sin evidencia.
     */
    private String formatProductStatusAnswer(List<MissionResponse> missions) {

        if (missions.isEmpty()) {
            return "No tengo ese dato registrado. No hay ninguna misión en el foco de esta conversación.";
        }

        var statuses = missions.stream()
                .collect(Collectors.toMap(
                        MissionResponse::missionId,
                        m -> productStatusService.resolve(m.missionId())
                ));

        var lines = missions.stream()
                .map(m -> m.missionId() + ": productStatus=" + statuses.get(m.missionId()))
                .collect(Collectors.joining(", "));

        var anyBeforeDevelopment = statuses.values().stream()
                .anyMatch(s -> s.ordinal() < ProductStatus.DEVELOPMENT.ordinal());

        var closing = anyBeforeDevelopment
                ? NO_DEVELOPMENT_EVIDENCE_DISCLAIMER_MULTI
                : "";

        return "Estado de producto real: " + lines + "." + closing;
    }

    private boolean matchesReferencePredicate(ReferencePredicate predicate, MissionResponse mission) {

        return switch (predicate) {
            case ENVIRONMENT_TEST -> "TEST".equals(mission.environment());
            case FAILED -> mission.status() == MissionStatus.FAILED;
            case NEEDS_APPROVAL -> mission.status() == MissionStatus.AWAITING_INVESTOR;
            case PRODUCT_STATUS -> throw new IllegalStateException(
                    "PRODUCT_STATUS se resuelve en formatProductStatusAnswer, nunca aquí");
        };
    }

    private enum QueryIntent {
        TEAM_DETAILS,
        AGENT_STATUS,
        MISSIONS_NEEDING_ATTENTION,
        FAILED_MISSIONS,
        TEST_MISSIONS,
        OPPORTUNITIES,
        COMPANY_PROFIT,
        COMPANY_STATUS,
        DEPENDENCIES,
        MODELS,
        OUTREACH_DRAFTS,
        OUTREACH_STATUS,
        PROSPECTS,
        PROSPECTING,
        AUTONOMY,
        ORCHESTRATOR,
        API_KEYS,
        PRODUCTS,
        PRODUCT_DETAIL
    }

    private record TeamKeywordRule(String teamId, List<Pattern> topicKeywordPatterns) {}

    /**
     * Compila cada keyword con un boundary IZQUIERDO explícito ({@code \b}),
     * sin exigir uno a la derecha -- varias keywords son deliberadamente
     * prefijos de palabras más largas (p. ej. {@code "creativ"} debe seguir
     * matcheando "creativa"/"creatividad", {@code "telemetria"} como
     * prefijo). Sin el boundary izquierdo, {@code String.contains} dejaba
     * pasar falsos positivos reales: {@code "arte"} matcheaba dentro de
     * "parte"/"comparte"/"aparte"/"reparte" (bug real de la revisión final
     * de rama, reproducido con "¿qué parte del equipo está trabajando
     * ahora?" enrutando a Creative/PI en vez de AGENT_STATUS).
     */
    private static List<Pattern> compileTeamKeywordPatterns(List<String> keywords) {

        return keywords.stream()
                .map(keyword -> Pattern.compile("\\b" + Pattern.quote(keyword)))
                .toList();
    }

    private static final List<TeamKeywordRule> TEAM_KEYWORD_RULES = List.of(
            new TeamKeywordRule(TeamMemoryService.TEAM_ENGINEERING,
                    compileTeamKeywordPatterns(List.of("ingenieria", "engineering"))),
            new TeamKeywordRule(TeamMemoryService.TEAM_CREATIVE_PRODUCT_INTELLIGENCE,
                    compileTeamKeywordPatterns(List.of(
                            "creativ", "product intelligence", "visual", "arte", "telemetria", "analytics"))),
            new TeamKeywordRule(TeamMemoryService.TEAM_MARKETING_GROWTH,
                    compileTeamKeywordPatterns(List.of(
                            "marketing", "growth", "crecimiento", "comunidad", "community")))
    );

    private record QueryMatch(QueryIntent intent, String teamId) {}

    private QueryMatch detectQuery(String message) {

        var normalized = normalize(message);

        // Subproyecto 2 (2026-09-28): dependencias de Engineering que esperan la decisión del fundador (🔴).
        if (normalized.contains("dependencia")) {
            return new QueryMatch(QueryIntent.DEPENDENCIES, null);
        }

        // Keys de modelos editables desde Settings (2026-09-29).
        if (normalized.matches(".*\\b(api ?keys?|keys?)\\b.*")) {
            return new QueryMatch(QueryIntent.API_KEYS, null);
        }

        // Spec contacto con prospectos (2026-09-30).
        if (normalized.matches(".*\\bcorreos?\\b.*\\b(aprobar|pendientes?)\\b.*")) {
            return new QueryMatch(QueryIntent.OUTREACH_DRAFTS, null);
        }
        if (normalized.contains("a quien contactamos") || normalized.contains("quien respondio")) {
            return new QueryMatch(QueryIntent.OUTREACH_STATUS, null);
        }

        // Spec búsqueda de prospectos (2026-09-30).
        // "estrategias" solo con contexto de búsqueda: suelto capturaba preguntas de negocio (revisión final).
        if (normalized.contains("busqueda de clientes")
                || normalized.matches(".*\\bestrategias?\\b(\\s+\\w+){0,2}\\s+pendientes?\\b.*")
                || normalized.matches(".*\\bestrategias?\\s+de\\s+(busqueda|prospeccion|prospectos)\\b.*")) {
            return new QueryMatch(QueryIntent.PROSPECTING, null);
        }
        if (normalized.matches(".*\\bprospectos?\\b.*")) {
            return new QueryMatch(QueryIntent.PROSPECTS, null);
        }

        // Spec modo automático (2026-09-29): "¿qué está en automático?", "modo automático".
        if (normalized.matches(".*\\b(modo automatico|en automatico)\\b.*")) {
            return new QueryMatch(QueryIntent.AUTONOMY, null);
        }

        // Spec orquestador (2026-09-28).
        if (normalized.contains("orquestador")) {
            return new QueryMatch(QueryIntent.ORCHESTRATOR, null);
        }

        // Spec salud de modelos (2026-09-28).
        if (normalized.contains("estado de los modelos") || normalized.matches(".*\\bmodelos?\\b.*(caid|responde|funcion|estado).*")
                || normalized.contains("modelos caidos")) {
            return new QueryMatch(QueryIntent.MODELS, null);
        }

        // Spec catálogo (2026-09-28).
        var needs = PRODUCT_NEEDS.matcher(normalized);
        if (needs.find()) {
            return new QueryMatch(QueryIntent.PRODUCT_DETAIL, needs.group(1).strip());
        }
        if (normalized.contains("producto") || normalized.contains("catalogo")) {
            return new QueryMatch(QueryIntent.PRODUCTS, null);
        }

        var teamGate = normalized.contains("equipo") || normalized.contains("team")
                || normalized.contains("lidera") || normalized.contains("lider");

        if (teamGate) {
            // Chequeado antes que AGENT_STATUS a propósito: "equipo" solo
            // ya dispara AGENT_STATUS (bug real corregido en una ronda
            // anterior, ver CLAUDE.md) -- preguntar por un equipo puntual
            // ("el equipo creativo", "quién lidera marketing") es más
            // específico que el estado general de agentes.
            for (var rule : TEAM_KEYWORD_RULES) {
                if (rule.topicKeywordPatterns().stream().anyMatch(p -> p.matcher(normalized).find())) {
                    return new QueryMatch(QueryIntent.TEAM_DETAILS, rule.teamId());
                }
            }
        }

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
            return new QueryMatch(QueryIntent.AGENT_STATUS, null);
        }

        if (normalized.contains("fallaron")
                || normalized.contains("fallidas")
                || normalized.contains("fallida")
                || normalized.contains("misiones fallo")) {
            // Chequeo antes que MISSIONS_NEEDING_ATTENTION a propósito:
            // antes una sola consulta mezclaba AWAITING_INVESTOR y FAILED
            // bajo "necesitan tu aprobación" -- una misión FAILED no
            // necesita aprobación, reportado por el usuario ("de las 25,
            // 15 AWAITING_INVESTOR y 10 FAILED, pero el texto decía que
            // las 25 necesitaban aprobación"). Consultas separadas, cada
            // una estricta sobre su propio status real.
            return new QueryMatch(QueryIntent.FAILED_MISSIONS, null);
        }

        if (TEST_ENVIRONMENT.matcher(normalized).find() || normalized.contains("entorno de test")) {
            // "¿qué misiones están en prueba?" -- Mission.environment=TEST,
            // cualquier status. Reportado por el usuario: sin esto, ~25
            // misiones de desarrollo (MISSION-STRUCTURED-*, MVP-*, etc.)
            // contaminaban toda pregunta de negocio real.
            return new QueryMatch(QueryIntent.TEST_MISSIONS, null);
        }

        if (normalized.contains("aprobacion")
                || normalized.contains("bloquead")
                || NEEDS_APPROVAL_VERB.matcher(normalized).find()) {
            return new QueryMatch(QueryIntent.MISSIONS_NEEDING_ATTENTION, null);
        }

        if (normalized.contains("oportunidad")) {
            return new QueryMatch(QueryIntent.OPPORTUNITIES, null);
        }

        // Spec finanzas (2026-09-27): costos frente a ganancias, balance y movimientos.
        if (normalized.contains("gastado")
                || normalized.contains("gasto")
                || normalized.contains("dinero")
                || normalized.contains("ganancia")
                || normalized.contains("beneficio")
                || normalized.contains("costo")
                || normalized.contains("movimiento")
                || normalized.contains("balance")
                || normalized.contains("finanza")
                || normalized.contains("ingreso")) {
            return new QueryMatch(QueryIntent.COMPANY_PROFIT, null);
        }

        if (normalized.contains("status")
                || normalized.contains("estado general")
                || normalized.contains("estado actual")
                || normalized.contains("estado de forjai")
                || normalized.contains("estado de la empresa")) {
            // Catch-all deliberado, chequeado al final: reportado por el
            // usuario -- "dame un status" no matcheaba ningún keyword
            // específico y caía al chat general, donde el CEO inventaba
            // un resumen completo con placeholders sin rellenar
            // ("[Nombre del cliente]", "[Precio]") porque no existía
            // ninguna fuente real de la que sacar esos datos. Un resumen
            // agregado de la empresa es tan determinista como contar una
            // lista -- no hay ninguna razón para dejárselo al modelo.
            return new QueryMatch(QueryIntent.COMPANY_STATUS, null);
        }

        return null;
    }

    private String handleQuery(QueryMatch match) {

        log.info("CHAT_INTENT_QUERY intent={} teamId={}", match.intent(), match.teamId());

        if (match.intent() == QueryIntent.TEAM_DETAILS) {
            return answerMemoryTopic("TEAM_DETAILS:" + match.teamId());
        }

        if (match.intent() == QueryIntent.PRODUCT_DETAIL) {
            return formatProductDetail(match.teamId());
        }

        return answerMemoryTopic(match.intent().name());
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

        if (topic != null && topic.startsWith("TEAM_DETAILS:")) {
            return formatTeamDetails(topic.substring("TEAM_DETAILS:".length()));
        }

        if (topic != null && topic.startsWith("MISSION_DETAILS:")) {
            return formatMissionDetails(topic.substring("MISSION_DETAILS:".length()).strip());
        }

        return switch (topic) {
            case "AGENT_STATUS" -> formatAgentStatus(missionMemory.latestTaskPerAgent());
            case "MISSIONS_NEEDING_ATTENTION" -> formatMissionsNeedingAttention(missionMemory.findAll(50));
            case "FAILED_MISSIONS" -> formatFailedMissions(missionMemory.findAll(50));
            case "TEST_MISSIONS" -> formatTestMissions(missionMemory.findAll(50));
            case "LAST_MENTIONED" -> formatLastMentioned();
            case "OPPORTUNITIES" -> formatOpportunities(opportunityMemory.listRecent(20));
            case "COMPANY_PROFIT" -> formatFinance(financeService.summary(null), 10);
            case "COMPANY_STATUS" -> formatCompanyStatus();
            case "DEPENDENCIES" -> formatPendingDependencies();
            case "PRODUCTS" -> formatCatalog();
            case "MODELS" -> formatModelsHealth();
            case "OUTREACH_DRAFTS" -> formatDrafts();
            case "OUTREACH_STATUS" -> formatOutreachStatus();
            case "PROSPECTS" -> formatProspects();
            case "PROSPECTING" -> formatProspecting();
            case "AUTONOMY" -> formatAutonomy();
            case "ORCHESTRATOR" -> formatOrchestrator();
            case "API_KEYS" -> formatApiKeys();
            default -> "Dato no reconocido: " + topic + ".";
        };
    }

    /**
     * Snapshot real de uno de los 3 equipos de Forjai (ver
     * {@code TeamMemoryService.KNOWN_TEAM_IDS}) — cruza
     * {@link TeamMemoryService#snapshot(String)} (miembros, roles,
     * roleCode, capabilities, modelo, líder) con
     * {@code missionMemory.latestTaskPerAgent()} (status/tarea actual
     * real) — mismo criterio que {@code formatMissionStatus}: "quién es"
     * y "qué está haciendo ahora" son preguntas distintas, ninguna se
     * infiere de la otra. 100% Java, nunca pasa por Ollama. Cada línea
     * arranca con el {@code agentId} real (no solo el nombre) — es el
     * identificador que el resto del chat usa para referirse al agente,
     * y hay un test que lo exige explícitamente; no lo saques. Un
     * {@code teamId} desconocido (fuera de {@code KNOWN_TEAM_IDS})
     * nunca llega a Neo4j — devuelve directamente el mismo disclaimer
     * que un dato no registrado, para no poder "descubrir" un equipo
     * inexistente por prueba y error.
     */
    private String formatTeamDetails(String teamId) {

        if (!TeamMemoryService.KNOWN_TEAM_IDS.contains(teamId)) {
            return "No tengo ese dato registrado.";
        }

        var snapshot = teamMemory.snapshot(teamId);

        if (snapshot.members().isEmpty()) {
            return "No tengo ese dato registrado. Ese equipo todavía no está registrado en Company Memory.";
        }

        var statusByAgentId = missionMemory.latestTaskPerAgent().stream()
                .collect(Collectors.toMap(AgentStatusResponse::agentId, a -> a));

        var lines = snapshot.members().stream()
                .map(m -> {
                    var status = statusByAgentId.get(m.agentId());
                    var leaderTag = m.agentId().equals(snapshot.leaderAgentId()) ? " (líder)" : "";
                    var statusText = status != null ? status.status() : "no registrado";
                    var taskText = status != null && status.action() != null
                            ? ", tarea actual: " + status.action() + " (" + status.taskStatus() + ")"
                            : "";

                    return m.agentId() + ": " + m.name() + leaderTag + " — " + m.role() + " ["
                            + java.util.Objects.toString(m.roleCode(), "no registrado") + "]: "
                            + "capabilities=" + String.join(", ", m.capabilities())
                            + ", model=" + java.util.Objects.toString(m.model(), "no registrado")
                            + ", status=" + statusText + taskText;
                })
                .collect(Collectors.joining(" | "));

        return snapshot.teamName() + " (" + snapshot.status() + "): " + lines;
    }

    /**
     * Snapshot agregado y 100% real de la empresa — reportado por el
     * usuario: "dame un status" caía al chat general, y sin ninguna
     * fuente real de la que sacar un resumen completo, el CEO (LLM)
     * rellenaba una plantilla con placeholders literales sin sustituir
     * ("[Nombre del cliente]", "[Precio]") y afirmaba recomendaciones
     * sobre datos que no existían. Cada número acá sale de una consulta
     * real a Neo4j, nunca del modelo — el CEO solo redacta sobre esto,
     * nunca lo completa.
     */
    private String formatCompanyStatus() {

        var missions = missionMemory.findAll(50).stream()
                .filter(m -> "PRODUCTION".equals(m.environment()))
                .toList();

        var active = missions.stream()
                .filter(m -> m.status() != MissionStatus.AWAITING_INVESTOR
                        && m.status() != MissionStatus.FAILED
                        && m.status() != MissionStatus.COMPLETED
                        && m.status() != MissionStatus.CANCELLED)
                .count();

        var awaitingInvestor = missions.stream()
                .filter(m -> m.status() == MissionStatus.AWAITING_INVESTOR)
                .count();

        var failed = missions.stream()
                .filter(m -> m.status() == MissionStatus.FAILED)
                .count();

        var rerunning = missions.stream()
                .filter(m -> m.status() != MissionStatus.AWAITING_INVESTOR && m.status() != MissionStatus.FAILED
                        && m.status() != MissionStatus.COMPLETED && m.status() != MissionStatus.CANCELLED)
                .filter(m -> missionMemory.evidenceRound(m.missionId()) > 0)
                .count();

        var agentStatuses = missionMemory.latestTaskPerAgent();

        var working = agentStatuses.stream().filter(a -> "WORKING".equals(a.status())).count();
        var idle = agentStatuses.stream().filter(a -> "IDLE".equals(a.status())).count();

        var opportunities = opportunityMemory.countOpportunities();

        var customerCounts = customerMemory.countCustomersAndProspects();
        var customers = customerCounts[0];
        var prospects = customerCounts[1];

        // Spec finanzas (2026-09-27): mismo cálculo que la pantalla Finanzas (incluye gastos y correcciones).
        var finance = financeService.summary(null);
        var catalog = productService.list();

        return String.format(
                Locale.ROOT,
                "Estado actual de Forjai: capital disponible US$%.2f. "
                        + "Agentes: %d trabajando, %d inactivo(s). "
                        + "Misiones (producción): %d activa(s)%s, %d esperando tu aprobación, %d fallida(s). "
                        + "Oportunidades registradas: %d. Prospectos (leads): %d. Clientes reales: %d. "
                        + "Ingresos: US$%.2f. Costos: US$%.2f. Ganancias: US$%.2f. Balance: US$%.2f. "
                        + "Productos: %d listo(s) para vender, %d en construcción, %d idea(s).",
                finance.balanceUsd(), working, idle,
                active, rerunning > 0 ? " (" + rerunning + " re-ejecutándose por más evidencia)" : "",
                awaitingInvestor, failed,
                opportunities, prospects, customers,
                finance.revenueUsd(), finance.costsUsd(), finance.profitUsd(), finance.balanceUsd(),
                countProducts(catalog, CatalogStatus.READY_TO_SELL), countProducts(catalog, CatalogStatus.IN_CONSTRUCTION),
                countProducts(catalog, CatalogStatus.IDEA)
        ) + orchestratorLine() + downModelsLine();
    }

    /** Keys de modelos (2026-09-29): solo la pista, el origen y quién usa cada proveedor; nunca la key. */
    private String formatApiKeys() {
        return "Keys de los modelos (se cambian en Settings → Keys de modelos): " + apiKeys.list().stream().map(k -> {
            var who = k.agents().isEmpty() ? "" : " (" + String.join(", ", k.agents()) + ")";
            return switch (k.source()) {
                case "FOUNDER" -> k.provider() + who + ": " + k.hint() + ", cambiada desde Settings el "
                        + SINCE.format(java.time.Instant.parse(k.updatedAt()));
                case "ENV" -> k.provider() + who + ": " + k.hint() + ", del .env";
                default -> k.provider() + who + ": sin key";
            };
        }).collect(Collectors.joining("; ")) + ".";
    }

    private String handleOrchestratorCommand(boolean pause) {
        companyPolicyService.createVersion(PolicyKey.ORCHESTRATOR_ENABLED, pause ? 0 : 1,
                (pause ? "Pausado" : "Reanudado") + " por el fundador desde el chat");
        log.info("CHAT_INTENT_ORCHESTRATOR_{}", pause ? "PAUSE" : "RESUME");
        return pause
                ? "Orquestador pausado: no arranca ciclos nuevos ni avanza el actual (las misiones ya lanzadas terminan igual). "
                        + "Para seguir: \"reanuda el orquestador\"."
                : "Orquestador reanudado: en el próximo chequeo (cada 15 minutos o al terminar una misión) retoma el ciclo.";
    }

    private String handleAutonomyCommand(boolean on) {
        var products = autonomy.setProducts(on, "el chat");
        var clients = autonomy.setClients(on, "el chat");
        log.info("CHAT_INTENT_AUTONOMY_{} products={} clients={}", on ? "ON" : "OFF", products, clients);
        var state = on ? "encendido" : "apagado";
        return "Modo automático — Crear productos y servicios: " + (products ? state : "ya estaba " + state)
                + ". Buscar clientes: " + (clients ? state : "ya estaba " + state) + "."
                + (on ? " Retoman en su próximo chequeo." : " Lo ya lanzado termina igual.")
                + " Contactar clientes o vender sigue siendo decisión tuya.";
    }

    private static String frontText(AutonomyService.Front front) {
        return front.enabled() ? "encendido"
                : "apagado" + (front.pauseReason() == null ? "" : " (" + front.pauseReason() + ")");
    }

    /** Spec modo automático (2026-09-29): cada frente, qué hace el orquestador y lo que espera al fundador, en Java. */
    private String formatAutonomy() {
        var view = autonomy.view();
        var waiting = view.waiting();
        return "Modo automático — Crear productos y servicios: " + frontText(view.products())
                + ". Buscar clientes: " + frontText(view.clients()) + ". " + formatOrchestrator()
                + " Esperando tu decisión: " + waiting.orchestratorMissions() + " misiones del orquestador, "
                + waiting.pendingDependencies() + (waiting.pendingDependencies() == 1 ? " dependencia pendiente"
                        : " dependencias pendientes")
                + " y " + waiting.pendingStrategies() + " estrategias por aprobar."
                + " Contactar clientes o vender sigue siendo decisión tuya.";
    }

    private String handleApproveAllDrafts() {
        var results = outreachService.approveAll();
        if (results.isEmpty()) {
            return "No hay correos por aprobar.";
        }
        var sent = results.stream().filter(d -> "SENT".equals(d.status())).count();
        var queued = results.stream().filter(d -> "APPROVED".equals(d.status())).toList();
        return "Correos aprobados: " + sent + " enviado(s)"
                + (queued.isEmpty() ? "." : "; " + queued.size() + " quedan para después: "
                        + queued.stream().map(d -> d.prospectName() + " (" + d.error() + ")").collect(Collectors.joining("; ")) + ".");
    }

    private String handleOneDraft(boolean approve, String name) {
        var wanted = com.aicompany.core.prospecting.ProspectValidator.normalize(name);
        var pending = outreachMemory.drafts("PENDING_APPROVAL");
        var exact = pending.stream().filter(d -> com.aicompany.core.prospecting.ProspectValidator.normalize(d.prospectName()).equals(wanted)).toList();
        var matches = exact.isEmpty()
                ? pending.stream().filter(d -> com.aicompany.core.prospecting.ProspectValidator.normalize(d.prospectName()).contains(wanted)).toList()
                : exact;
        if (matches.size() != 1) {
            return matches.isEmpty() ? "No hay un correo por aprobar para \"" + name + "\"."
                    : "Coinciden varios: " + matches.stream().map(d -> d.prospectName()).collect(Collectors.joining(", "))
                            + ". Escribe el nombre exacto.";
        }
        var draft = matches.get(0);
        if (!approve) {
            outreachService.discard(draft.id());
            return "Correo a " + draft.prospectName() + " descartado.";
        }
        var result = outreachService.approve(draft.id());
        return "SENT".equals(result.status()) ? "Correo a " + draft.prospectName() + " enviado a " + draft.to() + "."
                : "Correo a " + draft.prospectName() + " aprobado; queda pendiente: " + result.error();
    }

    private Optional<com.aicompany.core.prospecting.Prospect> prospectByName(String name) {
        var wanted = com.aicompany.core.prospecting.ProspectValidator.normalize(name);
        var all = prospectingMemory.prospects();
        var exact = all.stream().filter(p -> com.aicompany.core.prospecting.ProspectValidator.normalize(p.name()).equals(wanted)).toList();
        var matches = exact.isEmpty()
                ? all.stream().filter(p -> com.aicompany.core.prospecting.ProspectValidator.normalize(p.name()).contains(wanted)).toList()
                : exact;
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private String handleProspectResponse(String name, String answer) {
        var prospect = prospectByName(name);
        if (prospect.isEmpty()) {
            return "No encontré un único prospecto que se llame \"" + name + "\".";
        }
        var response = switch (answer) {
            case "interesado" -> "INTERESTED";
            case "no interesado", "que no" -> "NOT_INTERESTED";
            default -> "OPTED_OUT";
        };
        outreachService.respond(prospect.get().id(), response);
        return "Anotado: " + prospect.get().name() + " → " + answer
                + ("OPTED_OUT".equals(response) ? " (no se le vuelve a escribir, ni a su dominio)." : ".");
    }

    private String handleProspectConvert(String name) {
        var prospect = prospectByName(name);
        if (prospect.isEmpty()) {
            return "No encontré un único prospecto que se llame \"" + name + "\".";
        }
        var customerId = outreachService.convert(prospect.get().id());
        return prospect.get().name() + " ya es cliente real (" + customerId + "): puedes registrarle ventas en Finanzas.";
    }

    private String formatDrafts() {
        var drafts = outreachMemory.drafts("PENDING_APPROVAL");
        if (drafts.isEmpty()) {
            return "No hay correos por aprobar.";
        }
        return "Correos por aprobar (" + drafts.size() + "; nada se envía sin tu aprobación — \"aprueba los correos\" o "
                + "\"aprueba el correo a X\"):\n" + drafts.stream()
                .map(d -> "- " + d.prospectName() + " <" + d.to() + ">: " + d.subject())
                .collect(Collectors.joining("\n"));
    }

    private String formatOutreachStatus() {
        var contacted = prospectingMemory.prospects().stream()
                .filter(p -> p.outreachStatus() != null && !"DRAFTED".equals(p.outreachStatus())).toList();
        if (contacted.isEmpty()) {
            return "Todavía no contactamos a ningún prospecto.";
        }
        return "Prospectos contactados:\n" + contacted.stream()
                .map(p -> "- " + p.name() + " (" + p.productName() + "): " + p.outreachStatus())
                .collect(Collectors.joining("\n"));
    }

    private String handleStrategyCommand(boolean approve, String name) {
        var matches = strategyService.findByName(name);
        if (matches.isEmpty()) {
            return "No hay ninguna estrategia pendiente que se llame \"" + name + "\". Pendientes: "
                    + pendingStrategyNames() + ".";
        }
        if (matches.size() > 1) {
            return "Hay varias estrategias pendientes que coinciden: "
                    + matches.stream().map(s -> s.name()).collect(Collectors.joining(", "))
                    + ". Escribe el nombre exacto.";
        }
        var strategy = matches.get(0);
        if (approve) {
            strategyService.approve(strategy.id());
            return "Estrategia \"" + strategy.name() + "\" aprobada: entra a la rotación de la búsqueda de clientes.";
        }
        strategyService.reject(strategy.id());
        return "Estrategia \"" + strategy.name() + "\" rechazada: no se vuelve a proponer.";
    }

    private String pendingStrategyNames() {
        var names = strategyService.views().stream().filter(v -> "PENDING_APPROVAL".equals(v.status()))
                .map(v -> v.name()).toList();
        return names.isEmpty() ? "ninguna" : String.join(", ", names);
    }

    private String formatProspects() {
        var prospects = prospectingMemory.prospects();
        // Revisión final: "dame un status" cuenta todos los LEAD; acá se separan los de la búsqueda de los que
        // mencionaron las discoveries, para que las dos respuestas no se contradigan.
        var fromDiscoveries = Math.max(0, customerMemory.countCustomersAndProspects()[1] - prospects.size());
        var discoveries = fromDiscoveries == 0 ? ""
                : " Además hay " + fromDiscoveries + " candidatos que mencionaron las discoveries (sin contacto "
                        + "verificado): pregunta por las oportunidades para verlos.";
        if (prospects.isEmpty()) {
            return "Todavía no hay prospectos de la búsqueda de clientes: solo trabaja para productos listos para vender "
                    + "y con \"Buscar clientes\" encendido." + discoveries;
        }
        return "Prospectos de la búsqueda de clientes (" + prospects.size() + "; contactarlos es decisión tuya):"
                + discoveries + "\n" + prospects.stream().limit(30)
                .map(p -> "- " + p.name() + " (" + p.productName() + "): "
                        + (p.contactEmail() != null ? p.contactEmail() + " (fuente: " + p.contactEmailSource() + ")"
                                : "formulario " + p.contactFormUrl())
                        + " — " + p.fitReason() + " [" + p.url() + "]")
                .collect(Collectors.joining("\n"));
    }

    private String formatProspecting() {
        var runs = prospectingMemory.runs(5);
        var last = runs.isEmpty() ? "todavía no corrió"
                : runs.get(0).status().equals("COMPLETED")
                        ? "última corrida " + SINCE.format(runs.get(0).startedAt()) + ": " + runs.get(0).valid()
                                + " válidos de " + runs.get(0).found() + " (" + runs.get(0).strategyId() + ")"
                        : "última corrida falló: " + runs.get(0).error();
        var strategies = strategyService.views().stream()
                .map(v -> v.name() + " [" + v.status() + "] " + v.runs() + " corridas, "
                        + String.format(Locale.ROOT, "%.1f", v.validPerRun()) + " válidos/corrida")
                .collect(Collectors.joining("; "));
        return "Búsqueda de clientes: " + last + ". Estrategias: " + strategies + ". Pendientes de aprobar: "
                + pendingStrategyNames() + ".";
    }

    private static String orchestratorStep(com.aicompany.core.model.OrchestratorStatus status) {
        return switch (status) {
            case CHOOSING -> "eligiendo qué construir";
            case DISCOVERING -> "buscando ideas con una discovery";
            case PROPOSING -> "completando la ficha de";
            case BUILDING -> "construyendo";
            case READY -> "terminó: dejó listo para vender";
            case FAILED -> "falló con";
            case STOPPED -> "se detuvo con";
        };
    }

    private String orchestratorProduct(com.aicompany.core.model.OrchestratorRun run) {
        if (run.productId() == null) {
            return "";
        }
        return " " + productService.view(run.productId()).map(v -> v.product().name()).orElse(run.productId());
    }

    /** Spec orquestador §4: paso actual, producto, misiones y motivo de la elección, formateados en Java. */
    private String formatOrchestrator() {
        var view = orchestrator.current();
        var power = view.enabled() ? "encendido" : "pausado" + (view.pauseReason() == null ? "" : " (" + view.pauseReason() + ")")
                + "; \"reanuda el orquestador\" para seguir";
        var run = view.run();
        if (run == null) {
            return "El orquestador está " + power + " y no hay ningún ciclo en curso: arranca uno solo cuando Forjai no "
                    + "tiene productos listos para vender ni en construcción.";
        }
        var text = new StringBuilder("El orquestador está " + power + ". Ciclo " + run.id() + ": "
                + orchestratorStep(run.status()) + orchestratorProduct(run) + ".");
        if (run.choiceReason() != null) {
            text.append(" Motivo de la elección: ").append(run.choiceReason());
        }
        if (run.discoveryMissionId() != null) {
            text.append(" Discovery: ").append(run.discoveryMissionId()).append(".");
        }
        if (run.buildMissionId() != null) {
            text.append(" Construcción: ").append(run.buildMissionId()).append(".");
        }
        if (run.failureReason() != null) {
            text.append(" Motivo del fallo: ").append(run.failureReason());
        }
        if (!view.steps().isEmpty()) {
            var last = view.steps().get(view.steps().size() - 1);
            text.append(" Último paso (").append(SINCE.format(last.at())).append("): ").append(last.detail());
        }
        return text.toString();
    }

    private String orchestratorLine() {
        var view = orchestrator.current();
        if (view.run() == null || !view.run().status().active()) {
            return " Orquestador: " + (view.enabled() ? "encendido, sin ciclo en curso." : "pausado.");
        }
        return " Orquestador: " + orchestratorStep(view.run().status()) + orchestratorProduct(view.run())
                + (view.enabled() ? "." : " (pausado).");
    }

    private String formatAgentStatus(List<AgentStatusResponse> statuses) {

        var lines = statuses.stream()
                .map(this::formatOneAgentStatus)
                .collect(Collectors.joining("; "));

        return "Estado real de los agentes: " + lines + ".";
    }

    /**
     * {@code a.status()} (WORKING/IDLE) es el estado propio del agente,
     * distinto de {@code a.taskStatus()} (el status de su última
     * AgentTask) — ver {@code AgentStatusResponse}. Un agente
     * {@code WORKING} muestra la tarea en curso; uno {@code IDLE} con
     * historial muestra qué hizo por última vez y cómo terminó, sin
     * confundir ninguna de las dos cosas con "lo que el agente está
     * haciendo ahora".
     */
    private String formatOneAgentStatus(AgentStatusResponse a) {

        var base = statusDot(a.status()) + " " + a.name() + " (" + a.role() + "): " + a.status();
        // Subproyecto 2 (2026-09-28): el modelo real de cada agente (editable desde el Command Center).
        var model = companyMemory.agentModel(a.agentId(), defaultCeoModel);
        var modelSuffix = model == null || model.isBlank() ? "" : " — modelo " + model
                + (modelHealth.isDown(model) ? " (caído: usa su suplente " + modelHealth.fallbackFor(a.agentId()) + ")" : "");

        if (a.missionId() == null) {
            return base + modelSuffix;
        }

        if ("WORKING".equals(a.status())) {
            return base + " (" + a.missionId()
                    + (a.action() == null ? "" : ", " + a.action())
                    + ")" + modelSuffix;
        }

        return base + " — última tarea: " + a.action() + " (" + a.missionId()
                + "), resultado: " + a.taskStatus() + modelSuffix;
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

    /**
     * Estrictamente {@code AWAITING_INVESTOR} — antes también incluía
     * {@code FAILED} bajo la misma etiqueta "necesitan tu aprobación",
     * que era engañosa: una misión fallida no está esperando aprobación,
     * está esperando otra cosa (ver {@link #formatFailedMissions}).
     * Reportado por el usuario con datos reales (25 misiones, 15
     * AWAITING_INVESTOR + 10 FAILED, todas presentadas como si
     * necesitaran aprobación).
     */
    private String formatMissionsNeedingAttention(List<MissionResponse> missions) {

        var awaitingApproval = missions.stream()
                .filter(m -> m.status() == MissionStatus.AWAITING_INVESTOR)
                .filter(m -> "PRODUCTION".equals(m.environment()))
                .toList();

        conversationMemory.setLastMentioned(
                "MISSION",
                awaitingApproval.stream().map(MissionResponse::missionId).toList()
        );

        if (awaitingApproval.isEmpty()) {
            return "No hay ninguna misión que necesite tu aprobación en este momento.";
        }

        var lines = awaitingApproval.stream()
                .map(m -> m.missionId() + " (" + m.status() + ")")
                .collect(Collectors.joining(", "));

        return "Tenés " + awaitingApproval.size()
                + " misión(es) que necesitan tu aprobación: " + lines + ".";
    }

    private String formatFailedMissions(List<MissionResponse> missions) {

        var failed = missions.stream()
                .filter(m -> m.status() == MissionStatus.FAILED)
                .filter(m -> "PRODUCTION".equals(m.environment()))
                .toList();

        conversationMemory.setLastMentioned(
                "MISSION",
                failed.stream().map(MissionResponse::missionId).toList()
        );

        if (failed.isEmpty()) {
            return "No hay ninguna misión fallida en este momento.";
        }

        var lines = failed.stream()
                .map(MissionResponse::missionId)
                .collect(Collectors.joining(", "));

        return "Tenés " + failed.size() + " misión(es) fallida(s): " + lines + ".";
    }

    /**
     * {@code environment == "TEST"}, cualquier status — el histórico de
     * misiones de desarrollo (~25 al momento de escribir esto:
     * {@code MISSION-STRUCTURED-*}, {@code MVP-*}, {@code MISSION-DEBUG-*},
     * etc.) que antes contaminaba toda consulta de negocio real. No es
     * una lista completa de ids (sería larga y poco útil) — desglose por
     * status, mismo estilo que el mockup del usuario.
     */
    private String formatTestMissions(List<MissionResponse> missions) {

        var test = missions.stream()
                .filter(m -> "TEST".equals(m.environment()))
                .toList();

        conversationMemory.setLastMentioned(
                "MISSION",
                test.stream().map(MissionResponse::missionId).toList()
        );

        if (test.isEmpty()) {
            return "No hay ninguna misión en entorno de prueba en este momento.";
        }

        var lines = test.stream()
                .map(m -> m.missionId() + " (" + m.status() + ")")
                .collect(Collectors.joining(", "));

        return "Tenés " + test.size() + " misión(es) en entorno de prueba: " + lines + ".";
    }

    /**
     * Detalle real del foco actual de la conversación — usado por
     * {@link #handleReference} cuando el predicado no matchea nada
     * reconocido (fallback grounded al chat general) y por la
     * herramienta {@code query_company_memory} del LLM.
     */
    private String formatLastMentioned() {

        var focus = conversationMemory.lastMentioned();

        if (focus.isEmpty()) {
            return "No hay ninguna mención reciente de misiones en esta conversación.";
        }

        var missions = missionMemory.findByIds(focus.get().ids());

        var lines = missions.stream()
                .map(m -> m.missionId() + " (environment=" + m.environment()
                        + ", workflowStatus=" + m.status()
                        + ", productStatus=" + productStatusService.resolve(m.missionId()) + ")")
                .collect(Collectors.joining(", "));

        return "Las últimas misiones mencionadas fueron: " + lines + ".";
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

    private static final Map<CatalogStatus, String> CATALOG_LABELS = Map.of(
            CatalogStatus.READY_TO_SELL, "Listos para vender", CatalogStatus.IN_CONSTRUCTION, "En construcción",
            CatalogStatus.IDEA, "Ideas", CatalogStatus.PAUSED, "Pausados", CatalogStatus.RETIRED, "Retirados");

    private static final java.time.format.DateTimeFormatter SINCE =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(java.time.ZoneOffset.UTC);

    /** Spec salud de modelos (2026-09-28): qué modelo remoto cayó, desde cuándo y quién trabaja con suplente. */
    private String formatModelsHealth() {
        var models = modelHealth.snapshot();
        if (models.isEmpty()) {
            return "No hay modelos remotos en uso: todos los agentes usan modelos locales.";
        }
        return "Estado de los modelos: " + models.stream().map(h -> "DOWN".equals(h.status())
                        ? "⚠ " + ModelHealthService.shortName(h.model()) + " no responde desde " + SINCE.format(h.since())
                        + " — " + String.join(", ", h.affectedAgents()) + " trabajan con su suplente ("
                        + h.affectedAgents().stream().map(modelHealth::fallbackFor).distinct().collect(Collectors.joining(", "))
                        + ")"
                        : "✅ " + ModelHealthService.shortName(h.model()) + " (" + String.join(", ", h.affectedAgents()) + ")")
                .collect(Collectors.joining("; ")) + ".";
    }

    private String downModelsLine() {
        var down = modelHealth.snapshot().stream().filter(h -> "DOWN".equals(h.status()))
                .map(h -> ModelHealthService.shortName(h.model()) + " desde " + SINCE.format(h.since()))
                .toList();
        return down.isEmpty() ? "" : " ⚠ Modelos caídos: " + String.join(", ", down)
                + " (sus agentes trabajan con el suplente local).";
    }

    private static long countProducts(List<ProductView> views, CatalogStatus status) {
        return views.stream().filter(v -> v.product().status() == status).count();
    }

    private String formatCatalog() {
        var views = productService.list();
        if (views.isEmpty()) {
            return "El catálogo está vacío: Forjai todavía no tiene productos ni servicios. Puedes crear uno en la pantalla "
                    + "Productos; las misiones de discovery con una oferta también crean ideas.";
        }
        var parts = new ArrayList<String>();
        for (var status : List.of(CatalogStatus.READY_TO_SELL, CatalogStatus.IN_CONSTRUCTION, CatalogStatus.IDEA,
                CatalogStatus.PAUSED, CatalogStatus.RETIRED)) {
            var names = views.stream().filter(v -> v.product().status() == status).map(v -> v.product().name()
                            + (v.product().priceOnRequest() ? " (a cotizar)" : v.product().priceUsd() > 0
                            ? String.format(Locale.ROOT, " (US$%.2f)", v.product().priceUsd()) : ""))
                    .toList();
            if (!names.isEmpty()) {
                parts.add(CATALOG_LABELS.get(status) + ": " + String.join(", ", names));
            }
        }
        return "Catálogo de Forjai. " + String.join(". ", parts) + ".";
    }

    private String formatProductDetail(String name) {
        var found = resolveProduct(name);
        if (found.product() == null) {
            return found.message();
        }
        var v = productService.view(found.product().id()).orElseThrow();
        var p = v.product();
        var requirements = v.missing().isEmpty()
                ? (p.status() == CatalogStatus.READY_TO_SELL ? "✅ Cumple todos los requisitos para venderse."
                        : "✅ Cumple los requisitos: puede pasar a listo para vender.")
                : "Para venderse: " + v.missing().stream().map(m -> "❌ " + m).collect(Collectors.joining(" "));
        return p.name() + " (" + p.kind() + ", " + CATALOG_LABELS.get(p.status()).toLowerCase(Locale.ROOT) + "). "
                + requirements + " Misiones de demanda: " + p.validatedBy() + "; de construcción: " + p.builtBy()
                + ". Mercados: " + p.markets() + ", idiomas: " + p.languages() + ".";
    }

    private record ProductMatch(CatalogProduct product, String message) {
    }

    /** Por nombre sin mayúsculas ni tildes: el nombre exacto gana; si hay varios candidatos no se adivina. */
    private ProductMatch resolveProduct(String rawName) {
        var name = rawName.replaceAll("@[\\p{L}\\p{N}_-]+", "").strip();
        var candidates = productService.findByName(name);
        var exact = candidates.stream().filter(p -> ProductService.normalize(p.name()).equals(ProductService.normalize(name)))
                .toList();
        if (exact.size() == 1) {
            return new ProductMatch(exact.get(0), null);
        }
        if (candidates.size() == 1) {
            return new ProductMatch(candidates.get(0), null);
        }
        if (candidates.isEmpty()) {
            return new ProductMatch(null, "No encontré ningún producto que se llame \"" + name + "\". " + formatCatalog());
        }
        return new ProductMatch(null, "Hay varios productos con ese nombre: " + candidates.stream().map(CatalogProduct::name)
                .collect(Collectors.joining(", ")) + ". ¿Cuál? Escribe el nombre completo.");
    }

    private String handleProductCommand(String verb, String name, String message) {
        var found = resolveProduct(name);
        if (found.product() == null) {
            return found.message();
        }
        var target = switch (verb) {
            case "pausa" -> CatalogStatus.PAUSED;
            case "retira" -> CatalogStatus.RETIRED;
            case "reactiva" -> CatalogStatus.IDEA;
            default -> null;
        };
        var done = switch (verb) {
            case "pausa" -> "pausado (la búsqueda de clientes se frena)";
            case "retira" -> "retirado";
            case "reactiva" -> "reactivado como idea";
            default -> "reanudado";
        };
        try {
            var view = productService.changeStatus(found.product().id(), target, message, ProductService.FOUNDER);
            return "Listo: " + view.product().name() + " quedó " + done + ". Estado actual: "
                    + CATALOG_LABELS.get(view.product().status()).toLowerCase(Locale.ROOT) + ".";
        } catch (IllegalArgumentException ex) {
            return "No se cambió " + found.product().name() + ": " + ex.getMessage();
        }
    }

    /** Dependencias PENDING_APPROVAL con su motivo, 100% desde Neo4j (se aprueban en la pantalla Dependencias). */
    @SuppressWarnings("unchecked")
    private String formatPendingDependencies() {
        var pending = dependencyMemory.list().stream()
                .filter(d -> "PENDING_APPROVAL".equals(d.get("status")))
                .toList();
        if (pending.isEmpty()) {
            return "No hay dependencias esperando tu aprobación.";
        }
        var lines = pending.stream().map(d -> d.get("id") + " (" + d.get("missionId") + ", pedida por "
                        + d.get("requestedByAgent") + "): " + String.join("; ", (List<String>) d.getOrDefault("reasons", List.of())))
                .collect(Collectors.joining(" | "));
        return pending.size() + " dependencia(s) esperando tu aprobación: " + lines
                + ". Apruébalas o recházalas en la pantalla Dependencias del Command Center.";
    }

    private String fallbackTasks(String missionId) {
        var used = missionMemory.modelsUsed(missionId);
        return used.isEmpty() ? "" : " Tareas hechas con suplente local: " + used.entrySet().stream()
                .map(e -> e.getKey() + " (" + e.getValue() + ")").collect(Collectors.joining(", ")) + ".";
    }

    private String missionFinance(String missionId) {
        var f = financeService.summary(missionId);
        return f.entries().isEmpty() ? "" : String.format(Locale.ROOT,
                " Finanzas de la misión: ingresos US$%.2f, costos US$%.2f, ganancias US$%.2f.",
                f.revenueUsd(), f.costsUsd(), f.profitUsd());
    }

    /** Spec finanzas (2026-09-27): costos, ganancias, balance y últimos movimientos, 100% desde FinanceService. */
    private String formatFinance(FinanceSummary s, int lastN) {
        var head = String.format(Locale.ROOT, "Costos: US$%.2f. Ganancias: US$%.2f (ingresos US$%.2f). Balance: US$%.2f "
                + "(capital semilla US$%.2f).", s.costsUsd(), s.profitUsd(), s.revenueUsd(), s.balanceUsd(), s.seedCapitalUsd());
        if (s.entries().isEmpty()) {
            return head + " Todavía no hay movimientos registrados.";
        }
        var from = Math.max(0, s.entries().size() - lastN);
        var lines = s.entries().subList(from, s.entries().size()).stream()
                .map(e -> String.format(Locale.ROOT, "%s %s US$%.2f \"%s\"%s%s", e.occurredAt().toString().substring(0, 10),
                        e.type(), e.amountUsd(), e.description(), e.targetId() == null ? "" : " (corrige " + e.targetId() + ")",
                        "TEST".equals(e.environment()) ? " [prueba]" : ""))
                .collect(Collectors.joining("; "));
        return head + " Últimos movimientos: " + lines + ".";
    }

    private static String normalize(String text) {

        var decomposed = Normalizer.normalize(
                text.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        );

        return decomposed.replaceAll("\\p{M}", "");
    }
}
