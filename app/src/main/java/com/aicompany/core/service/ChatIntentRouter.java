package com.aicompany.core.service;

import com.aicompany.core.config.AppProperties;
import com.aicompany.core.model.AgentStatusResponse;
import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.DecisionCommand;
import com.aicompany.core.model.InvestorDecision;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import com.aicompany.core.model.OpportunitySummary;
import com.aicompany.core.model.ProductStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
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

    // \b explícito: "prueba" (contains) también matcheaba dentro de
    // "aprueba" ("a" + "prueba"), rompiendo la detección de decisión --
    // reproducido en vivo (fallo real en test antes de este ajuste).
    private static final Pattern TEST_ENVIRONMENT =
            Pattern.compile("\\bprueba");

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

    private final MissionService missionService;
    private final CeoService ceoService;
    private final MissionMemoryService missionMemory;
    private final OpportunityMemoryService opportunityMemory;
    private final CustomerMemoryService customerMemory;
    private final CompanyMemoryService companyMemory;
    private final ConversationMemoryService conversationMemory;
    private final AppProperties appProperties;
    private final ProductStatusService productStatusService;

    public ChatIntentRouter(
            MissionService missionService,
            CeoService ceoService,
            MissionMemoryService missionMemory,
            OpportunityMemoryService opportunityMemory,
            CustomerMemoryService customerMemory,
            CompanyMemoryService companyMemory,
            ConversationMemoryService conversationMemory,
            AppProperties appProperties,
            ProductStatusService productStatusService) {

        this.missionService = missionService;
        this.ceoService = ceoService;
        this.missionMemory = missionMemory;
        this.opportunityMemory = opportunityMemory;
        this.customerMemory = customerMemory;
        this.companyMemory = companyMemory;
        this.conversationMemory = conversationMemory;
        this.appProperties = appProperties;
        this.productStatusService = productStatusService;
    }

    /**
     * Graba cada turno (mensaje entrante + respuesta final) sin importar
     * qué camino de {@link #resolve} lo haya resuelto — auditable, mismo
     * criterio que {@code Decision}/{@code Evidence}. La lógica de
     * routing en sí no cambió de forma, solo se movió a {@link #resolve}.
     */
    public String route(String message) {

        var response = resolve(message);

        conversationMemory.recordMessage("user", message);
        conversationMemory.recordMessage("ceo", response);

        return response;
    }

    private String resolve(String message) {

        var missionStartMatcher = MISSION_START.matcher(message);

        if (missionStartMatcher.find()) {

            var missionId = missionStartMatcher.group(1).toUpperCase(Locale.ROOT);
            // Una misión iniciada por un comando real de chat del
            // fundador es trabajo real, no una prueba de desarrollo.
            var response = missionService.start(missionId, message, "PRODUCTION");

            return "He recibido " + missionId + ". Estado: " + response.status()
                    + ". La misión está procesándose en segundo plano. Consulta "
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

        if (missionStatusId != null) {
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
                conversationMemory.recentMessages(HISTORY_LIMIT),
                message,
                this::answerMemoryTopic
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

        var missionId = "MISSION-" + Instant.now().toEpochMilli();

        log.info("CHAT_INTENT_FREE_MISSION_START missionId={}", missionId);

        // Una misión iniciada por un comando real de chat del fundador
        // es trabajo real, no una prueba de desarrollo.
        var response = missionService.start(missionId, message, "PRODUCTION");

        conversationMemory.setLastMentioned("MISSION", List.of(missionId));

        return "Creé la misión " + missionId + " con tu descripción y la mandé a "
                + "procesar en segundo plano. Estado: " + response.status()
                + ". Consulta /api/company/missions/" + missionId
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

        var closing = productStatus.ordinal() < ProductStatus.DEVELOPMENT.ordinal()
                ? " No tengo registro de ninguna AgentTask de desarrollo real, evento de "
                        + "desarrollo iniciado, ni artefacto/repositorio/build para esta "
                        + "misión — no puedo afirmar que el desarrollo haya comenzado."
                : "";

        return mission.missionId() + ": workflowStatus=" + mission.status()
                + " (esto es el estado del proceso de análisis/decisión interno, "
                + "NO implica nada sobre si el producto está en desarrollo, publicado "
                + "o generando ingresos). productStatus=" + productStatus
                + ". Tareas de esta misión: " + taskLines
                + ". Estado actual de los agentes involucrados: " + agentStatusLines
                + "." + closing;
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

        if (normalized.contains("aprobacion") || normalized.contains("necesita")) {
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

                if (response.isPresent()) {
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
                    conversationMemory.recentMessages(HISTORY_LIMIT),
                    hint + message,
                    this::answerMemoryTopic
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
                ? " Ninguna de estas misiones tiene evidencia real de desarrollo (AgentTask de "
                        + "desarrollo, evento de desarrollo iniciado o artefacto/repositorio/build) "
                        + "salvo que se indique lo contrario arriba — no asumas que el desarrollo "
                        + "comenzó solo porque el workflow de análisis haya terminado."
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
        AGENT_STATUS,
        MISSIONS_NEEDING_ATTENTION,
        FAILED_MISSIONS,
        TEST_MISSIONS,
        OPPORTUNITIES,
        COMPANY_PROFIT,
        COMPANY_STATUS
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
            return QueryIntent.FAILED_MISSIONS;
        }

        if (TEST_ENVIRONMENT.matcher(normalized).find() || normalized.contains("entorno de test")) {
            // "¿qué misiones están en prueba?" -- Mission.environment=TEST,
            // cualquier status. Reportado por el usuario: sin esto, ~25
            // misiones de desarrollo (MISSION-STRUCTURED-*, MVP-*, etc.)
            // contaminaban toda pregunta de negocio real.
            return QueryIntent.TEST_MISSIONS;
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
            return QueryIntent.COMPANY_STATUS;
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
            case "FAILED_MISSIONS" -> formatFailedMissions(missionMemory.findAll(50));
            case "TEST_MISSIONS" -> formatTestMissions(missionMemory.findAll(50));
            case "LAST_MENTIONED" -> formatLastMentioned();
            case "OPPORTUNITIES" -> formatOpportunities(opportunityMemory.listRecent(20));
            case "COMPANY_PROFIT" -> formatCompanyProfit(customerMemory.companyWideTotalRevenueAndCost());
            case "COMPANY_STATUS" -> formatCompanyStatus();
            default -> "Dato no reconocido: " + topic + ".";
        };
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

        var agentStatuses = missionMemory.latestTaskPerAgent();

        var working = agentStatuses.stream().filter(a -> "WORKING".equals(a.status())).count();
        var idle = agentStatuses.stream().filter(a -> "IDLE".equals(a.status())).count();

        var opportunities = opportunityMemory.countOpportunities();

        var customerCounts = customerMemory.countCustomersAndProspects();
        var customers = customerCounts[0];
        var prospects = customerCounts[1];

        var totals = customerMemory.companyWideTotalRevenueAndCost();
        var revenue = totals[0];
        var netProfit = totals[0] - totals[1];

        return String.format(
                Locale.ROOT,
                "Estado actual de Forjai: capital disponible US$%.2f. "
                        + "Agentes: %d trabajando, %d inactivo(s). "
                        + "Misiones (producción): %d activa(s), %d esperando tu aprobación, %d fallida(s). "
                        + "Oportunidades registradas: %d. Prospectos (leads): %d. Clientes reales: %d. "
                        + "Ingresos: US$%.2f. Beneficio neto: US$%.2f.",
                appProperties.seedCapitalUsd(), working, idle,
                active, awaitingInvestor, failed,
                opportunities, prospects, customers,
                revenue, netProfit
        );
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
