package com.aicompany.core.service;

import com.aicompany.core.model.DecisionCommand;
import com.aicompany.core.model.InvestorDecision;
import com.aicompany.core.model.LeadResponse;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

    // Gate de resolveCustomerReference. Un bare "contains(\"contact\")" es
    // demasiado amplio: la consulta LEADS preexistente ("¿qué leads tengo
    // para contactar?") contiene la substring "contact" vía "contactar" --
    // con un foco CUSTOMER activo (p. ej. de una consulta de oportunidades
    // de una misión puntual), esa pregunta genérica quedaba interceptada
    // como si fuera una referencia a un solo prospecto en foco, en vez de
    // devolver el listado completo de LEADs (encontrado en code review).
    // Matchea formas imperativas ("contacta"/"contactalo"/"contactame"/...)
    // y la frase "contacto de" -- deliberadamente NO matchea el infinitivo
    // "contactar" (la frase LEADS existente) ni "contacto"/"contactos"
    // como sustantivo suelto sin "de".
    private static final Pattern CONTACT_REFERENCE =
            Pattern.compile("(?i)\\bcontacta(lo|la|me|los|las)?\\b|\\bcontacto de\\b");

    // 10 turnos (20 mensajes) -- suficiente para continuidad real de
    // charla sin dejar crecer el prompt del CEO sin límite (reportado
    // por el usuario: "no está recordando las charlas que tengo con el
    // CEO" -- antes CeoService.chat no mandaba ningún turno anterior).
    private static final int HISTORY_LIMIT = 20;

    private final MissionService missionService;
    private final CeoService ceoService;
    private final MissionMemoryService missionMemory;
    private final OpportunityMemoryService opportunityMemory;
    private final CompanyMemoryService companyMemory;
    private final ConversationMemoryService conversationMemory;
    private final CompanyTools companyTools;

    public ChatIntentRouter(
            MissionService missionService,
            CeoService ceoService,
            MissionMemoryService missionMemory,
            OpportunityMemoryService opportunityMemory,
            CompanyMemoryService companyMemory,
            ConversationMemoryService conversationMemory,
            CompanyTools companyTools) {

        this.missionService = missionService;
        this.ceoService = ceoService;
        this.missionMemory = missionMemory;
        this.opportunityMemory = opportunityMemory;
        this.companyMemory = companyMemory;
        this.conversationMemory = conversationMemory;
        this.companyTools = companyTools;
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

        var opportunityForMissionId = resolveOpportunityForMissionQuery(message);

        if (opportunityForMissionId.isPresent()) {
            return handleOpportunityForMissionQuery(opportunityForMissionId.get());
        }

        var missionDetailsId = resolveMissionDetailsQuery(message);

        if (missionDetailsId.isPresent()) {
            return handleMissionDetailsQuery(missionDetailsId.get());
        }

        var customerFocusIds = resolveCustomerReference(message);

        if (customerFocusIds.isPresent()) {
            return handleCustomerReference(customerFocusIds.get(), message);
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
     * "¿Cómo va MISSION-X?" caía al chat general y el CEO respondía "no
     * tengo acceso" pese a que {@code MissionService.details} ya existe
     * y tiene el dato real (reportado por el usuario). Mismo criterio
     * que {@link #detectDecision}: sin un {@code MISSION-<id>} explícito
     * y sin foco previo de una sola misión, no hay a qué misión
     * responder — nunca se adivina, cae al chat general.
     */
    private Optional<String> resolveMissionDetailsQuery(String message) {

        var normalized = normalize(message);

        var hasKeyword = normalized.contains("como va")
                || normalized.contains("como vamos")
                || normalized.contains("avance")
                || normalized.contains("progreso")
                || normalized.contains("detalles")
                || normalized.contains("que esta haciendo");

        if (!hasKeyword) {
            return Optional.empty();
        }

        var missionIdMatcher = MISSION_ID.matcher(message);

        if (missionIdMatcher.find()) {
            return Optional.of(missionIdMatcher.group(1).toUpperCase(Locale.ROOT));
        }

        if (!normalized.contains("mision")) {
            // "¿qué está haciendo cada agente?" matchea el keyword pero
            // no menciona ninguna misión -- es AGENT_STATUS, no esto.
            return Optional.empty();
        }

        return conversationMemory.lastMentioned()
                .filter(focus -> "MISSION".equals(focus.type()) && focus.ids().size() == 1)
                .map(focus -> focus.ids().get(0));
    }

    private String handleMissionDetailsQuery(String missionId) {

        log.info(
                "CHAT_INTENT_MISSION_DETAILS missionId={}",
                missionId
        );

        return companyTools.getMission(missionId);
    }

    /**
     * "¿qué oportunidades concretas tenemos en la misión MISSION-X y qué
     * prospectos reales están asociados?" caía en la consulta global
     * {@code OPPORTUNITIES} (ignorando el {@code MISSION-<id>} del
     * mensaje) y nunca mostraba los prospectos reales, pese a que sí
     * existen como {@code Customer{status:'LEAD'}} enlazados vía
     * {@code HAS_CANDIDATE} (reportado por el usuario). Mismo criterio
     * que {@link #resolveMissionDetailsQuery}: solo actúa con un
     * {@code MISSION-<id>} explícito en el mensaje — sin uno, el
     * comportamiento global de {@code OPPORTUNITIES} (lista de las 20
     * más recientes, sin prospectos) no cambia.
     */
    private Optional<String> resolveOpportunityForMissionQuery(String message) {

        var normalized = normalize(message);

        if (!normalized.contains("oportunidad")) {
            return Optional.empty();
        }

        var missionIdMatcher = MISSION_ID.matcher(message);

        if (!missionIdMatcher.find()) {
            return Optional.empty();
        }

        return Optional.of(missionIdMatcher.group(1).toUpperCase(Locale.ROOT));
    }

    private String handleOpportunityForMissionQuery(String missionId) {

        log.info("CHAT_INTENT_OPPORTUNITY_DETAILS missionId={}", missionId);

        return companyTools.getOpportunity(missionId);
    }

    /**
     * "contactalo"/"el contacto de X" caía al chat general, que
     * respondía que no tenía acceso a datos personales — técnicamente
     * cierto (nadie se los dio), pero el chat nunca intentó buscarlos
     * en Neo4j, donde sí existen como {@code Customer{status:'LEAD'}}
     * (reportado por el usuario). Mismo criterio que
     * {@link #handleReference} (foco {@code type="MISSION"}), pero para
     * el foco {@code type="CUSTOMER"} que arma
     * {@link #handleOpportunityForMissionQuery}. Sin un foco
     * {@code CUSTOMER} vigente no hay nada que resolver — nunca se
     * adivina, cae al chat general.
     */
    private Optional<List<String>> resolveCustomerReference(String message) {

        var normalized = normalize(message);

        if (!CONTACT_REFERENCE.matcher(normalized).find()) {
            return Optional.empty();
        }

        return conversationMemory.lastMentioned()
                .filter(focus -> "CUSTOMER".equals(focus.type()) && !focus.ids().isEmpty())
                .map(focus -> focus.ids());
    }

    /**
     * Nunca inventa un teléfono/email: esta ronda es puramente
     * informativa (no hay integración de telefonía/email construida
     * todavía) — solo muestra lo que existe de verdad y aclara
     * explícitamente cuando no hay dato de contacto directo.
     */
    private String handleCustomerReference(List<String> focusIds, String message) {

        log.info("CHAT_INTENT_CUSTOMER_REFERENCE focusSize={}", focusIds.size());

        var candidates = opportunityMemory.findCandidatesByIds(focusIds);

        if (candidates.isEmpty()) {
            return "No tengo datos registrados de esos prospectos en Company Memory.";
        }

        var normalizedMessage = normalize(message);

        var mentioned = candidates.stream()
                .filter(c -> !c.name().isBlank() && normalizedMessage.contains(normalize(c.name())))
                .findFirst();

        if (mentioned.isPresent()) {
            return formatCustomerReferenceAnswer(mentioned.get(), false);
        }

        if (candidates.size() == 1) {
            return formatCustomerReferenceAnswer(candidates.get(0), false);
        }

        var topId = focusIds.get(0);

        var top = candidates.stream()
                .filter(c -> c.id().equals(topId))
                .findFirst()
                .orElse(candidates.get(0));

        return formatCustomerReferenceAnswer(top, true);
    }

    private String formatCustomerReferenceAnswer(LeadResponse candidate, boolean clarifyTopChoice) {

        var intro = clarifyTopChoice ? "Te muestro el de mayor probabilidad: " : "";

        var clarifyNote = clarifyTopChoice ? " Avisame si te referías a otro." : "";

        var contactNote = " No tengo un dato de contacto directo (teléfono/email) registrado para "
                + "este prospecto, solo la fuente donde se identificó.";

        return intro + companyTools.formatCandidate(candidate) + "." + contactNote + clarifyNote;
    }

    private enum ReferencePredicate {
        ENVIRONMENT_TEST,
        FAILED,
        NEEDS_APPROVAL
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
            // Genérico a propósito: sin foco de NINGÚN tipo todavía, no
            // hay razón para asumir que el usuario hablaba de una misión
            // en particular (el foco también puede ser CUSTOMER).
            return "No tengo claro a qué te referís — no mencioné ninguna misión ni prospecto "
                    + "todavía en esta conversación.";
        }

        var normalized = normalize(message);

        // Comando/predicado (detectReferenceCommand/detectReferencePredicate/
        // formatReferenceAnswer) son específicos de misiones -- un foco
        // CUSTOMER (p. ej. de handleOpportunityForMissionQuery) no tiene
        // nada que "aprobar"/"rechazar" ni un status de entorno que
        // consultar así. Con un foco que no es MISSION, esta resolución
        // no aplica y cae directo al mismo fallback grounded de abajo.
        if ("MISSION".equals(focus.get().type())) {

            var command = detectReferenceCommand(normalized);

            if (command != null) {
                return handleReferenceCommand(command, message, focus.get().ids());
            }

            var predicate = detectReferencePredicate(normalized);

            if (predicate != null) {
                log.info("CHAT_INTENT_REFERENCE predicate={} focusSize={}", predicate, focus.get().ids().size());

                var missions = missionMemory.findByIds(focus.get().ids());

                return formatReferenceAnswer(predicate, missions);
            }
        }

        log.info("CHAT_INTENT_REFERENCE predicate=none focusSize={}", focus.get().ids().size());
        // Sin esta pista, el LLM no tiene forma de saber a qué tipo
        // de entidad se refiere "esas" -- reproducido en vivo: sin
        // ella, ignoraba la pregunta y contestaba sobre el equipo en
        // vez de las misiones (alucinando de nuevo). La pista solo
        // aclara el TIPO de referencia (dato ya conocido acá, en
        // Java); el contenido real sigue viniendo exclusivamente de
        // la herramienta LAST_MENTIONED, nunca de esta nota. Mismo
        // fallback tanto para un predicado MISSION no reconocido como
        // para cualquier otro tipo de foco (p. ej. CUSTOMER).
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

    private String formatReferenceAnswer(ReferencePredicate predicate, List<MissionResponse> missions) {

        var matching = missions.stream()
                .filter(m -> matchesReferencePredicate(predicate, m))
                .toList();

        var total = missions.size();

        var description = switch (predicate) {
            case ENVIRONMENT_TEST -> "pertenecen al entorno de pruebas";
            case FAILED -> "fallaron";
            case NEEDS_APPROVAL -> "están esperando tu aprobación (AWAITING_INVESTOR)";
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

    private boolean matchesReferencePredicate(ReferencePredicate predicate, MissionResponse mission) {

        return switch (predicate) {
            case ENVIRONMENT_TEST -> "TEST".equals(mission.environment());
            case FAILED -> mission.status() == MissionStatus.FAILED;
            case NEEDS_APPROVAL -> mission.status() == MissionStatus.AWAITING_INVESTOR;
        };
    }

    private enum QueryIntent {
        AGENT_STATUS,
        MISSIONS_NEEDING_ATTENTION,
        FAILED_MISSIONS,
        TEST_MISSIONS,
        OPPORTUNITIES,
        LEADS,
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

        if (normalized.contains("lead")
                || normalized.contains("prospecto")
                || normalized.contains("a quien contacto")) {
            return QueryIntent.LEADS;
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

        return answerMemoryTopic(intent.name(), null);
    }

    /**
     * Resuelve un {@code topic} real delegando a {@link CompanyTools} —
     * llamado tanto por el atajo de keywords ({@link #handleQuery}, sin
     * pasar por Ollama, siempre con {@code id=null}) como por la
     * herramienta {@code query_company_memory} que {@link CeoService#chat}
     * puede pedir para el chat general ({@code id} solo es necesario para
     * {@code MISSION_DETAILS}/{@code OPPORTUNITY_DETAILS}).
     */
    String answerMemoryTopic(String topic, String id) {

        return switch (topic) {
            case "AGENT_STATUS" -> companyTools.getAgentStatus();
            case "MISSIONS_NEEDING_ATTENTION" -> companyTools.getPendingApprovals();
            case "FAILED_MISSIONS" -> companyTools.getFailedMissions();
            case "TEST_MISSIONS" -> companyTools.getTestMissions();
            case "LAST_MENTIONED" -> companyTools.getLastMentioned();
            case "OPPORTUNITIES" -> companyTools.getOpportunities();
            case "LEADS" -> companyTools.getProspects();
            case "COMPANY_PROFIT" -> companyTools.getFinancialStatus();
            case "COMPANY_STATUS" -> companyTools.getCompanyStatus();
            case "MISSION_DETAILS" -> companyTools.getMission(id);
            case "OPPORTUNITY_DETAILS" -> companyTools.getOpportunity(id);
            default -> "Dato no reconocido: " + topic + ".";
        };
    }

    private static String normalize(String text) {

        var decomposed = Normalizer.normalize(
                text.toLowerCase(Locale.ROOT),
                Normalizer.Form.NFD
        );

        return decomposed.replaceAll("\\p{M}", "");
    }
}
