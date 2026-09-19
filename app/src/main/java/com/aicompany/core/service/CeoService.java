package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.AgentResultSchema;
import com.aicompany.core.agent.model.AgentTaskOutcome;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.llm.LlmProvider;
import com.aicompany.core.llm.LlmResponse;
import com.aicompany.core.llm.NvidiaNimLlmProvider;
import com.aicompany.core.llm.OllamaLlmProvider;
import com.aicompany.core.model.ConversationTurn;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

@Service
public class CeoService {

    private static final Logger log =
            LoggerFactory.getLogger(CeoService.class);

    /**
     * Definición de la única herramienta disponible hoy para las tareas de
     * agente. Formato "function calling" de Ollama (compatible con OpenAI).
     */
    private static final List<Map<String, Object>> AGENT_TOOLS = List.of(
            Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", "search_web_evidence",
                            "description",
                            "Busca fuentes web públicas y reales para "
                                    + "validar una afirmación empresarial. "
                                    + "Devuelve una lista de resultados con "
                                    + "título, URL y resumen. Nunca inventes "
                                    + "una URL: usa únicamente las que "
                                    + "devuelva esta herramienta.",
                            "parameters", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "query", Map.of(
                                                    "type", "string",
                                                    "description",
                                                    "La consulta de "
                                                            + "búsqueda, en "
                                                            + "español."
                                            )
                                    ),
                                    "required", List.of("query")
                            )
                    )
            )
    );

    /**
     * Herramienta disponible solo en {@link #chat}: Neo4j es la memoria
     * operacional y fuente de verdad de la empresa (misiones, agentes,
     * oportunidades, finanzas reales) — antes de esta herramienta, el chat
     * del CEO no tenía forma de consultarla y alucinaba cuando le
     * preguntaban algo que no venía pre-inyectado en el system prompt (p.
     * ej. "preséntame al equipo" inventó 7 roles genéricos que no existen).
     * {@code topic} está deliberadamente restringido a un enum fijo, nunca
     * Cypher libre: mismo criterio de todo el proyecto de no exponerle al
     * modelo un canal para ejecutar consultas arbitrarias.
     */
    private static final List<Map<String, Object>> COMPANY_MEMORY_TOOLS = List.of(
            Map.of(
                    "type", "function",
                    "function", Map.of(
                            "name", "query_company_memory",
                            "description",
                            "Consulta datos reales y actuales de la "
                                    + "empresa en Neo4j (memoria "
                                    + "operacional) — nunca inventes estos "
                                    + "datos, pedí la herramienta si no los "
                                    + "tenés en este mensaje.",
                            "parameters", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "topic", Map.of(
                                                    "type", "string",
                                                    "enum", List.of(
                                                            "AGENT_STATUS",
                                                            "MISSIONS_NEEDING_ATTENTION",
                                                            "FAILED_MISSIONS",
                                                            "TEST_MISSIONS",
                                                            "LAST_MENTIONED",
                                                            "OPPORTUNITIES",
                                                            "LEADS",
                                                            "COMPANY_PROFIT",
                                                            "COMPANY_STATUS",
                                                            "MISSION_DETAILS",
                                                            "OPPORTUNITY_DETAILS",
                                                            "RECENT_ACTIVITY",
                                                            "RECENT_DECISIONS",
                                                            "ACTIVE_MISSIONS",
                                                            "CHAT_HISTORY",
                                                            "MENTIONED_DATES"
                                                    ),
                                                    "description",
                                                    "AGENT_STATUS: qué está "
                                                            + "haciendo cada "
                                                            + "agente ahora. "
                                                            + "MISSIONS_NEEDING_ATTENTION: "
                                                            + "misiones reales "
                                                            + "(no de prueba) "
                                                            + "que esperan tu "
                                                            + "aprobación "
                                                            + "(AWAITING_INVESTOR "
                                                            + "exclusivamente, "
                                                            + "nunca fallidas). "
                                                            + "FAILED_MISSIONS: "
                                                            + "misiones reales "
                                                            + "que fallaron. "
                                                            + "TEST_MISSIONS: "
                                                            + "misiones de "
                                                            + "prueba/desarrollo "
                                                            + "(cualquier "
                                                            + "estado) -- nunca "
                                                            + "cuentan como "
                                                            + "actividad "
                                                            + "empresarial real. "
                                                            + "LAST_MENTIONED: "
                                                            + "detalle real de "
                                                            + "las últimas "
                                                            + "misiones o "
                                                            + "prospectos "
                                                            + "mencionados en "
                                                            + "esta "
                                                            + "conversación. "
                                                            + "OPPORTUNITIES: "
                                                            + "oportunidades "
                                                            + "identificadas "
                                                            + "(lista global, "
                                                            + "sin prospectos). "
                                                            + "LEADS: "
                                                            + "candidatos de "
                                                            + "cliente (LEAD) "
                                                            + "que un agente "
                                                            + "identificó y "
                                                            + "todavía no se "
                                                            + "contactaron ni "
                                                            + "convirtieron. "
                                                            + "COMPANY_PROFIT: "
                                                            + "ingresos/costos/"
                                                            + "utilidad reales "
                                                            + "acumulados. "
                                                            + "COMPANY_STATUS: "
                                                            + "resumen agregado "
                                                            + "y real de la "
                                                            + "empresa (capital, "
                                                            + "agentes, misiones, "
                                                            + "oportunidades, "
                                                            + "clientes, "
                                                            + "finanzas) -- "
                                                            + "usala siempre que "
                                                            + "te pidan un "
                                                            + "'status' o "
                                                            + "resumen general, "
                                                            + "nunca inventes "
                                                            + "ese resumen vos. "
                                                            + "MISSION_DETAILS: "
                                                            + "estado, progreso "
                                                            + "y tareas reales "
                                                            + "de UNA misión "
                                                            + "puntual -- "
                                                            + "requiere el "
                                                            + "parámetro id "
                                                            + "con el "
                                                            + "MISSION-<numero> "
                                                            + "exacto. "
                                                            + "OPPORTUNITY_DETAILS: "
                                                            + "la oportunidad "
                                                            + "real y sus "
                                                            + "prospectos "
                                                            + "reales de UNA "
                                                            + "misión puntual "
                                                            + "-- requiere el "
                                                            + "parámetro id "
                                                            + "con el "
                                                            + "MISSION-<numero> "
                                                            + "exacto (el id "
                                                            + "de la misión, "
                                                            + "nunca el id "
                                                            + "interno de la "
                                                            + "Opportunity). "
                                                            + "RECENT_ACTIVITY: "
                                                            + "línea de tiempo "
                                                            + "reciente de la "
                                                            + "empresa (misiones, "
                                                            + "tareas, evidencia, "
                                                            + "decisiones). "
                                                            + "RECENT_DECISIONS: "
                                                            + "últimas decisiones "
                                                            + "reales del "
                                                            + "inversionista "
                                                            + "humano, sin "
                                                            + "filtrar por "
                                                            + "misión puntual. "
                                                            + "ACTIVE_MISSIONS: "
                                                            + "misiones reales "
                                                            + "en curso (ni "
                                                            + "esperando "
                                                            + "aprobación, ni "
                                                            + "fallidas, ni "
                                                            + "completadas, ni "
                                                            + "canceladas) con "
                                                            + "su status y "
                                                            + "progreso real. "
                                                            + "CHAT_HISTORY: "
                                                            + "transcript real "
                                                            + "de la charla de "
                                                            + "un día puntual -- "
                                                            + "requiere el "
                                                            + "parámetro id con "
                                                            + "la fecha exacta "
                                                            + "en formato "
                                                            + "YYYY-MM-DD. "
                                                            + "MENTIONED_DATES: "
                                                            + "los días reales "
                                                            + "en los que se "
                                                            + "mencionó una "
                                                            + "entidad puntual "
                                                            + "en el chat -- "
                                                            + "requiere el "
                                                            + "parámetro id con "
                                                            + "el id real de "
                                                            + "esa entidad "
                                                            + "(p. ej. un "
                                                            + "MISSION-<numero>)."
                                            ),
                                            "id", Map.of(
                                                    "type", "string",
                                                    "description",
                                                    "El MISSION-<numero> "
                                                            + "exacto -- solo "
                                                            + "necesario para "
                                                            + "MISSION_DETAILS "
                                                            + "y "
                                                            + "OPPORTUNITY_DETAILS. "
                                                            + "Omitilo para "
                                                            + "cualquier otro "
                                                            + "topic."
                                            )
                                    ),
                                    "required", List.of("topic")
                            )
                    )
            )
    );

    private final RestClient ollama;
    private final String agentModel;
    private final JsonMapper jsonMapper;
    private final EvidenceAcquisitionService evidenceAcquisitionService;
    private final CompanyEventPublisher events;
    private final MeterRegistry meterRegistry;
    private final LlmProvider ceoProvider;
    private final OllamaLlmProvider ollamaFallbackProvider;
    private final AiBudgetService aiBudgetService;

    public CeoService(
            RestClient ollama,
            @Value("${ollama.agent-model}") String agentModel,
            JsonMapper jsonMapper,
            EvidenceAcquisitionService evidenceAcquisitionService,
            CompanyEventPublisher events,
            MeterRegistry meterRegistry,
            @Qualifier("ceoProvider") LlmProvider ceoProvider,
            @Qualifier("ollamaLlmProvider") OllamaLlmProvider ollamaFallbackProvider,
            AiBudgetService aiBudgetService) {

        this.ollama = ollama;
        this.agentModel = agentModel;
        this.jsonMapper = jsonMapper;
        this.evidenceAcquisitionService = evidenceAcquisitionService;
        this.events = events;
        this.meterRegistry = meterRegistry;
        this.ceoProvider = ceoProvider;
        this.ollamaFallbackProvider = ollamaFallbackProvider;
        this.aiBudgetService = aiBudgetService;
    }

    /**
     * {@code companyMemoryQuery} resuelve un {@code topic} real contra
     * Neo4j (reutiliza los mismos formatters deterministas que ya usa
     * {@code ChatIntentRouter} para sus intents de consulta por
     * keyword) — {@code CeoService} no depende de Neo4j directamente, solo
     * de este callback, para mantener la separación existente ("CeoService
     * es el único cliente de Ollama").
     */
    public String chat(
            String ceoName,
            String teamRoster,
            List<ConversationTurn> history,
            String message,
            BiFunction<String, String, String> companyMemoryQuery) {

        var system = systemPrompt()
                + "\nTu nombre real es " + ceoName
                + " — ese es tu nombre, no inventes otro si te preguntan quién sos."
                + "\nEste es tu equipo real (nombre y rol) — nunca inventes"
                + " otros integrantes ni cargos genéricos si te piden"
                + " presentar al equipo:\n" + teamRoster
                + "\nEstos agentes son identidades de software de la"
                + " empresa, no personas reales — no apliques"
                + " consideraciones de privacidad de datos personales al"
                + " hablar de ellos ni te niegues a describirlos por eso."
                + "\nRegla dura sobre datos de la empresa: si no tenés un"
                + " dato real (vía la herramienta query_company_memory o"
                + " ya presente en esta conversación), nunca lo inventes"
                + " ni lo completes con un placeholder de plantilla sin"
                + " rellenar (ejemplos prohibidos: \"[Nombre del"
                + " cliente]\", \"[Precio]\", \"[Problema específico]\")."
                + " Decí explícitamente que no tenés ese dato registrado"
                + " en vez de inventar una cifra, un nombre o una"
                + " recomendación sobre algo que no existe en Company"
                + " Memory.";

        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", system));
        messages.addAll(buildHistoryMessages(history));
        messages.add(Map.of("role", "user", "content", message));

        var turn = callCeo("CEO_CHAT", messages, COMPANY_MEMORY_TOOLS);

        var query =
                !turn.toolCalls().isEmpty()
                        ? parseCompanyMemoryTopic(turn.toolCalls().get(0))
                        : detectInlineCompanyMemoryTopic(turn.content());

        if (query == null) {
            return turn.content();
        }

        log.info("CEO_CHAT_TOOL_CALL topic={} id={}", query.topic(), query.id());

        var result = companyMemoryQuery.apply(query.topic(), query.id());

        var toolCallArguments = query.id() == null
                ? Map.of("topic", query.topic())
                : Map.of("topic", query.topic(), "id", query.id());

        messages.add(Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", List.of(Map.of(
                        "function", Map.of(
                                "name", "query_company_memory",
                                "arguments", toolCallArguments
                        )
                ))
        ));

        messages.add(Map.of("role", "tool", "content", result));

        var finalTurn = callCeo("CEO_CHAT", messages, null);

        return finalTurn.content();
    }

    /**
     * Traduce los turnos ya persistidos por {@code ConversationMemoryService}
     * al formato de mensajes de Ollama, en el mismo orden cronológico en
     * que se grabaron. {@code role="ceo"} (como lo graba
     * {@code ChatIntentRouter.route}) se mapea a {@code "assistant"} —
     * Ollama no conoce el rol {@code "ceo"}; cualquier otro valor se deja
     * tal cual (hoy nunca ocurre, pero no hay razón para lanzar por esto).
     */
    List<Map<String, Object>> buildHistoryMessages(List<ConversationTurn> history) {

        return history.stream()
                .map(turn -> Map.<String, Object>of(
                        "role", "ceo".equals(turn.role()) ? "assistant" : turn.role(),
                        "content", turn.content()
                ))
                .toList();
    }

    /**
     * Ejecuta la tarea de un agente en hasta dos turnos con Ollama:
     *
     * 1. Un turno con la herramienta {@code search_web_evidence} disponible
     *    (sin `format`, porque `format` y `tools` no pueden combinarse —
     *    verificado en vivo: con ambos presentes el modelo queda forzado a
     *    la gramática del schema y no puede pedir la herramienta). Si el
     *    modelo la pide, la ejecutamos de verdad contra
     *    {@link EvidenceAcquisitionService} y le devolvemos el resultado
     *    real como turno "tool".
     * 2. Un turno final con `format: AgentResultSchema.SCHEMA` (sin
     *    `tools`) para obligar al modelo a producir el `AgentResult`
     *    estructurado, ahora con la evidencia real (si la pidió) ya en el
     *    historial de la conversación.
     *
     * Nota sobre el modelo: qwen2.5-coder no envuelve su respuesta en
     * `&lt;tool_call&gt;...&lt;/tool_call&gt;` como espera la plantilla de
     * Ollama, así que `message.tool_calls` casi nunca viene poblado —
     * verificado en vivo. Por eso {@link #detectInlineToolCall} también
     * intenta reconocer un llamado a herramienta cuando viene como JSON
     * suelto en `message.content`.
     *
     * El turno de decisión usa un prompt **corto y separado**
     * ({@code taskSummary}), no el {@code prompt} completo con el
     * contrato JSON del `AgentResult`: verificado en vivo que cuando el
     * "FORMATO OBLIGATORIO" está presente en el mismo turno, el modelo lo
     * ignora casi siempre y llena directamente esa plantilla (incluso con
     * {@code tool_choice: "required"}) — la plantilla JSON concreta le
     * gana a la instrucción de usar la herramienta.
     *
     * <p>Devuelve un {@link AgentTaskOutcome}, no solo el
     * {@link AgentResult}: también expone qué URLs se confirmaron de
     * verdad en el turno de herramienta (si hubo uno), para que
     * {@code AgentRuntime.executeInternal} pueda correr
     * {@code EvidenceBindingGate} — detectar "buscó evidencia real pero no
     * la citó" no se puede hacer mirando solo el `AgentResult` final.
     */
    public AgentTaskOutcome executeAgentTask(
            String agentId,
            String prompt,
            String taskSummary,
            String missionId,
            String taskId) {

        var system =
                systemPrompt()
                        + "\nTu rol específico en esta tarea es: "
                        + agentId
                        + ".";

        var toolDecisionMessages = List.<Map<String, Object>>of(
                Map.of(
                        "role", "system",
                        "content", toolDecisionSystemPrompt(agentId)
                ),
                Map.of("role", "user", "content", taskSummary)
        );

        var toolTurn =
                callModel(
                        "AGENT_TOOL_CALL",
                        agentId,
                        agentModel,
                        toolDecisionMessages,
                        null,
                        AGENT_TOOLS,
                        true
                );

        var toolCall =
                !toolTurn.toolCalls().isEmpty()
                        ? parseStructuredToolCall(toolTurn.toolCalls().get(0))
                        : detectInlineToolCall(toolTurn.content());

        log.info(
                "AGENT_TOOL_TURN agent={} rawToolCalls={} content={} toolCallDetected={}",
                agentId,
                toolTurn.toolCalls(),
                toolTurn.content(),
                toolCall != null
        );

        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", system));
        messages.add(Map.of("role", "user", "content", prompt));

        var confirmedEvidenceUrls = List.<String>of();

        if (toolCall != null) {

            log.info(
                    "TASK agent={} requested tool={} query={}",
                    agentId,
                    toolCall.name(),
                    toolCall.query()
            );

            var toolExecution =
                    executeTool(agentId, missionId, taskId, toolCall);

            confirmedEvidenceUrls = toolExecution.confirmedUrls();

            messages.add(Map.of(
                    "role", "assistant",
                    "content", "",
                    "tool_calls", List.of(Map.of(
                            "function", Map.of(
                                    "name", toolCall.name(),
                                    "arguments", Map.of(
                                            "query", toolCall.query()
                                    )
                            )
                    ))
            ));

            messages.add(Map.of(
                    "role", "tool",
                    "content", toolExecution.json()
            ));
        }

        var finalTurn =
                callModel(
                        "AGENT_TASK",
                        agentId,
                        agentModel,
                        messages,
                        AgentResultSchema.SCHEMA,
                        null,
                        false
                );

        var response = finalTurn.content();

        try {

            var normalizedResponse =
                    normalizeJsonResponse(response);

            log.info(
                    "AGENT_RESULT_RAW agent={} response={}",
                    agentId,
                    response
            );

            log.info(
                    "AGENT_RESULT_NORMALIZED agent={} response={}",
                    agentId,
                    normalizedResponse
            );

            var result =
                    jsonMapper.readValue(
                            normalizedResponse,
                            AgentResult.class
                    );

            log.info(
                    "AGENT_RESULT_PARSED agent={} verificationStatus={} confidence={}",
                    agentId,
                    result.verificationStatus(),
                    result.confidence()
            );

            return new AgentTaskOutcome(result, confirmedEvidenceUrls);

        } catch (Exception ex) {

            log.error(
                    "AGENT_RESULT_PARSE_ERROR agent={} model={} reason={}",
                    agentId,
                    agentModel,
                    ex.getMessage(),
                    ex
            );

            throw new IllegalStateException(
                    "El agente "
                            + agentId
                            + " no devolvió un AgentResult JSON válido.",
                    ex
            );
        }
    }

    /**
     * Cuántos candidatos de {@code searchEvidence} se confirman de verdad
     * (fetch + {@link com.aicompany.core.evidence.ClaimRelevanceChecker})
     * por llamada a la herramienta. Serper puede devolver hasta 10; no
     * tiene sentido hacerle fetch a los 10 — con los primeros
     * {@code CANDIDATES_TO_CONFIRM} alcanza para encontrar 1-2 fuentes
     * reales y relacionadas, y evita que un solo turno de herramienta
     * dispare 10 requests HTTP salientes en serie.
     */
    private static final int CANDIDATES_TO_CONFIRM = 5;

    /**
     * Ejecuta {@code search_web_evidence} de verdad. Si la búsqueda falla
     * (sin API key, error del proveedor, etc.), no tumba la tarea — le
     * devuelve al modelo un resultado de error para que pueda seguir
     * (declarando NOT_VALIDATED, por ejemplo) en vez de que la excepción se
     * propague.
     *
     * No basta con buscar: antes de devolverle candidatos al modelo, cada
     * uno pasa por {@code evidenceAcquisitionService.confirmReachable}
     * (fetch real + verificación léxica de relevancia). Un candidato que no
     * responde o cuyo contenido no tiene relación con la búsqueda se
     * descarta aquí — no llega al modelo, para que no pueda citarlo como si
     * fuera una fuente real. Si ninguno sobrevive, se le informa al modelo
     * explícitamente para que no invente evidencia.
     *
     * <p>Eventos Kafka (`EMPRESA_AI_NUEVO_TODO_EVIDENCE.md` §22, prefijo
     * obligatorio {@code EMPRESA_}): {@code EMPRESA_EVIDENCE_SEARCH_STARTED}
     * al recibir la query, {@code EMPRESA_EVIDENCE_SEARCH_COMPLETED} con el
     * conteo de candidatos crudos, y por cada candidato confirmado o
     * descartado {@code EMPRESA_EVIDENCE_VERIFIED}/{@code
     * EMPRESA_EVIDENCE_REJECTED}. Deliberadamente **no** se emite un
     * {@code EMPRESA_EVIDENCE_CANDIDATE_CREATED} por resultado crudo (lo
     * sugiere el doc de referencia): sería redundante con el conteo que ya
     * va en {@code SEARCH_COMPLETED} y multiplicaría el volumen de eventos
     * sin agregar información nueva — mismo criterio que llevó a que
     * {@code confirmReachable} nunca marque {@code verified=true} solo por
     * un fetch exitoso.
     *
     * <p>Devuelve, además del JSON para el turno "tool", las URLs que
     * realmente sobrevivieron {@code confirmReachable} en esta llamada
     * ({@link ToolExecutionResult#confirmedUrls()}) — es la fuente de
     * verdad que usa {@code EvidenceBindingGate} para decidir si el
     * agente citó lo que de verdad se le entregó.
     *
     * <p>Observabilidad formal (además de los eventos Kafka, que son un
     * registro de ocurrencias discretas, no series numéricas agregables):
     * métricas Micrometer expuestas en {@code /actuator/metrics}, con tag
     * {@code agent} para poder desglosar por agente —
     * {@code evidence.search.requests} (contador),
     * {@code evidence.search.duration} (timer de la llamada real a
     * {@code searchEvidence}), {@code evidence.candidate.verified}/
     * {@code evidence.candidate.rejected} (contadores; el segundo con tag
     * {@code reason} = nombre simple de la excepción, para no explotar
     * cardinalidad con el mensaje completo) y
     * {@code evidence.candidate.duration} (timer de {@code confirmReachable}
     * por candidato, con tag {@code outcome=verified|rejected}).
     */
    private ToolExecutionResult executeTool(
            String agentId,
            String missionId,
            String taskId,
            ToolCall toolCall) {

        events.publish(
                "EMPRESA_EVIDENCE_SEARCH_STARTED",
                missionId,
                taskId,
                agentId,
                Map.of("query", toolCall.query())
        );

        meterRegistry.counter("evidence.search.requests", "agent", agentId)
                .increment();

        try {

            var searchTimer = Timer.start(meterRegistry);

            var candidates =
                    evidenceAcquisitionService.searchEvidence(
                            toolCall.query()
                    );

            searchTimer.stop(
                    meterRegistry.timer("evidence.search.duration", "agent", agentId)
            );

            events.publish(
                    "EMPRESA_EVIDENCE_SEARCH_COMPLETED",
                    missionId,
                    taskId,
                    agentId,
                    Map.of(
                            "query", toolCall.query(),
                            "candidatesFound", candidates.size()
                    )
            );

            var confirmed = new ArrayList<Map<String, Object>>();
            var confirmedUrls = new ArrayList<String>();

            for (var candidate : candidates) {

                if (confirmed.size() >= CANDIDATES_TO_CONFIRM) {
                    break;
                }

                var confirmTimer = Timer.start(meterRegistry);

                try {

                    var evidence =
                            evidenceAcquisitionService.confirmReachable(
                                    candidate
                            );

                    confirmTimer.stop(
                            meterRegistry.timer(
                                    "evidence.candidate.duration",
                                    "agent", agentId, "outcome", "verified"
                            )
                    );

                    meterRegistry.counter("evidence.candidate.verified", "agent", agentId)
                            .increment();

                    confirmed.add(Map.of(
                            "title",
                            candidate.title() == null
                                    ? ""
                                    : candidate.title(),
                            "url",
                            evidence.source(),
                            "snippet",
                            candidate.snippet() == null
                                    ? ""
                                    : candidate.snippet(),
                            "confirmado",
                            evidence.description()
                    ));

                    confirmedUrls.add(evidence.source());

                    events.publish(
                            "EMPRESA_EVIDENCE_VERIFIED",
                            missionId,
                            taskId,
                            agentId,
                            Map.of(
                                    "query", toolCall.query(),
                                    "url", candidate.url(),
                                    "title", candidate.title() == null ? "" : candidate.title()
                            )
                    );

                } catch (Exception unreachableOrUnrelated) {

                    confirmTimer.stop(
                            meterRegistry.timer(
                                    "evidence.candidate.duration",
                                    "agent", agentId, "outcome", "rejected"
                            )
                    );

                    meterRegistry.counter(
                            "evidence.candidate.rejected",
                            "agent", agentId,
                            "reason", unreachableOrUnrelated.getClass().getSimpleName()
                    ).increment();

                    log.info(
                            "TOOL_CANDIDATE_REJECTED agent={} url={} reason={}",
                            agentId,
                            candidate.url(),
                            unreachableOrUnrelated.getMessage()
                    );

                    events.publish(
                            "EMPRESA_EVIDENCE_REJECTED",
                            missionId,
                            taskId,
                            agentId,
                            Map.of(
                                    "query", toolCall.query(),
                                    "url", candidate.url() == null ? "" : candidate.url(),
                                    "reason", unreachableOrUnrelated.getMessage() == null
                                            ? "motivo desconocido"
                                            : unreachableOrUnrelated.getMessage()
                            )
                    );
                }
            }

            if (confirmed.isEmpty()) {
                return new ToolExecutionResult(
                        jsonMapper.writeValueAsString(
                                Map.of(
                                        "advertencia",
                                        "Ninguno de los resultados de búsqueda fue "
                                                + "accesible y relacionado con la "
                                                + "consulta. No hay evidencia real "
                                                + "disponible para esta afirmación."
                                )
                        ),
                        List.of()
                );
            }

            return new ToolExecutionResult(
                    jsonMapper.writeValueAsString(confirmed),
                    confirmedUrls
            );

        } catch (Exception ex) {

            log.warn(
                    "TOOL_CALL_FAILED agent={} tool={} query={} reason={}",
                    agentId,
                    toolCall.name(),
                    toolCall.query(),
                    ex.getMessage()
            );

            return new ToolExecutionResult(
                    jsonMapper.writeValueAsString(
                            Map.of(
                                    "error",
                                    "No se pudo completar la búsqueda: "
                                            + (ex.getMessage() == null
                                            ? "error desconocido"
                                            : ex.getMessage())
                            )
                    ),
                    List.of()
            );
        }
    }

    private record ToolExecutionResult(
            String json,
            List<String> confirmedUrls) {
    }

    @SuppressWarnings("unchecked")
    private ToolCall parseStructuredToolCall(
            Map<String, Object> rawToolCall) {

        var function =
                (Map<String, Object>) rawToolCall.get("function");

        if (function == null) {
            return null;
        }

        var name = String.valueOf(function.get("name"));
        var arguments = function.get("arguments");

        String query = null;

        if (arguments instanceof Map<?, ?> argMap) {

            var value = argMap.get("query");

            query = value == null ? null : String.valueOf(value);
        }

        if (!"search_web_evidence".equals(name)
                || query == null
                || query.isBlank()) {
            return null;
        }

        return new ToolCall(name, query);
    }

    private ToolCall detectInlineToolCall(String content) {

        if (content == null || content.isBlank()) {
            return null;
        }

        try {

            var normalized = normalizeJsonResponse(content);
            var node = jsonMapper.readTree(normalized);

            if (!node.isObject()) {
                return null;
            }

            var name = node.path("name").asString(null);
            var argumentsNode = node.path("arguments");

            if (!"search_web_evidence".equals(name)
                    || !argumentsNode.isObject()) {
                return null;
            }

            var query = argumentsNode.path("query").asString(null);

            if (query == null || query.isBlank()) {
                return null;
            }

            return new ToolCall(name, query);

        } catch (Exception ex) {
            return null;
        }
    }

    private record ToolCall(String name, String query) {
    }

    record CompanyMemoryQuery(String topic, String id) {
    }

    @SuppressWarnings("unchecked")
    CompanyMemoryQuery parseCompanyMemoryTopic(Map<String, Object> rawToolCall) {

        var function = (Map<String, Object>) rawToolCall.get("function");

        if (function == null) {
            return null;
        }

        var name = String.valueOf(function.get("name"));
        var arguments = function.get("arguments");

        String topic = null;
        String id = null;

        if (arguments instanceof Map<?, ?> argMap) {
            var topicValue = argMap.get("topic");
            topic = topicValue == null ? null : String.valueOf(topicValue);
            var idValue = argMap.get("id");
            id = idValue == null ? null : String.valueOf(idValue);
        }

        if (!"query_company_memory".equals(name)
                || topic == null
                || topic.isBlank()) {
            return null;
        }

        return new CompanyMemoryQuery(topic, id);
    }

    CompanyMemoryQuery detectInlineCompanyMemoryTopic(String content) {

        if (content == null || content.isBlank()) {
            return null;
        }

        try {

            var normalized = normalizeJsonResponse(content);
            var node = jsonMapper.readTree(normalized);

            if (!node.isObject()) {
                return null;
            }

            var name = node.path("name").asString(null);
            var argumentsNode = node.path("arguments");

            if (!"query_company_memory".equals(name)
                    || !argumentsNode.isObject()) {
                return null;
            }

            var topic = argumentsNode.path("topic").asString(null);

            if (topic == null || topic.isBlank()) {
                return null;
            }

            var id = argumentsNode.path("id").asString(null);

            return new CompanyMemoryQuery(topic, id);

        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Prompt corto, deliberadamente sin el "FORMATO OBLIGATORIO" del
     * AgentResult (ver el javadoc de {@link #executeAgentTask}) — su único
     * propósito es que el modelo decida si necesita buscar evidencia
     * real antes de continuar.
     */
    private String toolDecisionSystemPrompt(String agentId) {

        return """
                Eres el agente %s de Forjai. Antes de hacer cualquier
                otra cosa, evalúa si la tarea que se te describe necesita
                evidencia real de mercado (precios, competencia, demanda,
                costos típicos) que no tengas todavía.

                Si la necesitas, responde ÚNICAMENTE con este JSON, sin
                ningún otro texto:
                {"name": "search_web_evidence", "arguments": {"query": "..."}}

                Si NO la necesitas, responde únicamente con la palabra:
                NINGUNA
                """.formatted(agentId);
    }

    public String executeMission(
            String instruction,
            String agentResults) {

        var prompt = """
                Actúa como CEO de Forjai.
                Consolida los resultados de los agentes y determina el siguiente paso.
                No conviertas hipótesis en hechos.
                Si no existe evidencia real de mercado,
                declara que la misión todavía no está validada.

                INSTRUCCIÓN:
                %s

                RESULTADOS DE AGENTES:
                %s
                """.formatted(
                instruction,
                agentResults
        );

        var messages = List.<Map<String, Object>>of(
                Map.of("role", "system", "content", systemPrompt()),
                Map.of("role", "user", "content", prompt)
        );

        return callCeo("MISSION_CONSOLIDATION", messages, null).content();
    }

    /**
     * Único punto por el que pasan las dos llamadas del CEO ({@code chat}
     * y {@code executeMission}) al proveedor configurado — aplica el
     * presupuesto diario y el fallback a Ollama descritos en
     * `docs/superpowers/specs/2026-09-18-nvidia-ceo-provider-design.md`.
     * {@code executeAgentTask} no pasa por acá: sigue llamando a
     * {@link #callModel} (Ollama directo) sin cambios.
     *
     * <p>Un {@code content} nulo/vacío SIN {@code tool_calls} cuenta como
     * fallo (el truncamiento real observado en el spike con
     * `gpt-oss-20b`) SOLO cuando el proveedor configurado es NVIDIA — con
     * Ollama (hoy el 100% del tráfico real) nunca se validó esto antes de
     * este plan, y hacerlo incondicional propagaría una respuesta vacía
     * real de Ollama como excepción, tumbando el turno de chat (perdiendo
     * el registro en el historial) o, peor, toda la misión completa vía
     * `MissionExecutor.safeFail` — contradiciendo "Agent failure != Mission
     * failure" (`CLAUDE.md`). Un {@code content} vacío CON {@code
     * tool_calls} es un turno de herramienta normal, no un fallo, para
     * cualquier proveedor.
     */
    private LlmResponse callCeo(
            String operation,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools) {

        var usingNvidia = ceoProvider instanceof NvidiaNimLlmProvider;

        if (usingNvidia && aiBudgetService.isExhausted()) {

            log.info("CEO_PROVIDER_FALLBACK operation={} reason=BUDGET_EXHAUSTED", operation);

            events.publish(
                    "EMPRESA_CEO_PROVIDER_FALLBACK",
                    null, null, "ceo",
                    Map.of("operation", operation, "reason", "BUDGET_EXHAUSTED")
            );

            return ollamaFallbackProvider.chat(operation, messages, tools);
        }

        try {

            var response = ceoProvider.chat(operation, messages, tools);

            var noContentNoToolCalls =
                    (response.content() == null || response.content().isBlank())
                            && (response.toolCalls() == null || response.toolCalls().isEmpty());

            if (noContentNoToolCalls && usingNvidia) {
                throw new IllegalStateException(
                        "Respuesta vacía del proveedor CEO (sin contenido ni tool_calls)."
                );
            }

            if (usingNvidia) {
                aiBudgetService.recordCall();
            }

            if (noContentNoToolCalls) {
                log.warn("CEO_EMPTY_RESPONSE operation={} provider={}",
                        operation, ceoProvider.getClass().getSimpleName());
            }

            log.info("CEO_PROVIDER_CALL operation={} provider={}",
                    operation, ceoProvider.getClass().getSimpleName());

            return response;

        } catch (Exception ex) {

            if (!usingNvidia) {
                throw ex;
            }

            log.warn("CEO_PROVIDER_FALLBACK operation={} reason=RATE_LIMIT_OR_ERROR", operation, ex);

            events.publish(
                    "EMPRESA_CEO_PROVIDER_FALLBACK",
                    null, null, "ceo",
                    Map.of("operation", operation, "reason", "RATE_LIMIT_OR_ERROR")
            );

            return ollamaFallbackProvider.chat(operation, messages, tools);
        }
    }

    private String systemPrompt() {

        return """
                Eres el CEO de Forjai,
                una empresa real operada principalmente por agentes de IA.

                Capital semilla inicial: US$50.
                Horizonte: 60 días.

                La empresa utiliza IA para operar y crear negocios;
                no vende la plataforma de IA como producto.

                Debes buscar valor económico real,
                exigir evidencia y conservar iniciativa estratégica.

                El inversionista puede aprobar, rechazar
                o proponer una alternativa.

                No inventes clientes, ventas, ingresos,
                búsquedas o evidencia.

                Diferencia siempre entre:
                - hecho
                - hipótesis
                - estimación
                - evidencia
                - resultado verificado

                Responde en español.
                """;
    }

    private String normalizeJsonResponse(String response) {

        if (response == null) {
            throw new IllegalStateException(
                    "Respuesta del modelo null."
            );
        }

        var normalized = response.trim();

        /*
         * Caso:
         *
         * ```json
         * { ... }
         * ```
         */
        if (normalized.startsWith("```json")) {

            normalized = normalized.substring(
                    "```json".length()
            ).trim();

            if (normalized.endsWith("```")) {
                normalized = normalized.substring(
                        0,
                        normalized.length() - 3
                ).trim();
            }
        }

        /*
         * Caso:
         *
         * ```
         * { ... }
         * ```
         */
        else if (normalized.startsWith("```")) {

            normalized = normalized.substring(
                    3
            ).trim();

            if (normalized.endsWith("```")) {
                normalized = normalized.substring(
                        0,
                        normalized.length() - 3
                ).trim();
            }
        }

        /*
         * Si el modelo agregó texto antes/después,
         * intentamos recuperar exclusivamente el objeto JSON.
         */
        int start = normalized.indexOf('{');
        int end = normalized.lastIndexOf('}');

        if (start >= 0 && end > start) {

            normalized =
                    normalized.substring(
                            start,
                            end + 1
                    );
        }

        return normalized;
    }

    /**
     * Sin modificador de acceso a propósito: permite que
     * {@code CeoServiceToolFormatGuardTest} verifique que esta protección
     * realmente lanza, sin depender de {@code RestClient}/Ollama real.
     *
     * No combinar nunca 'format' y 'tools' en la misma llamada a Ollama:
     * verificado en vivo (con qwen2.5-coder:7b y con qwen3:8b) que Ollama
     * fuerza la gramática del `format` y el modelo ya no puede pedir la
     * herramienta — con qwen3 además inventó una URL y datos falsos
     * simulando que sí la había llamado. Si esto se dispara, es un error
     * de programación (alguien juntó los dos turnos por accidente), no
     * una condición esperada en runtime.
     */
    void rejectFormatCombinedWithTools(
            String operation,
            Object format,
            List<Map<String, Object>> tools) {

        if (format != null && tools != null && !tools.isEmpty()) {

            throw new IllegalArgumentException(
                    "callModel: 'format' y 'tools' no pueden combinarse "
                            + "en la misma llamada a Ollama (operation="
                            + operation
                            + ") — fuerza al modelo a alucinar una "
                            + "respuesta de herramienta falsa en vez de "
                            + "pedirla de verdad. Usa dos llamadas "
                            + "separadas."
            );
        }
    }

    @SuppressWarnings("unchecked")
    private LlmResponse callModel(
            String operation,
            String actor,
            String model,
            List<Map<String, Object>> messages,
            Object format,
            List<Map<String, Object>> tools) {

        return callModel(operation, actor, model, messages, format, tools, null);
    }

    /**
     * @param think Modelos con "modo pensamiento" (p. ej. qwen3) generan un
     *              razonamiento previo separado del contenido final
     *              ({@code message.thinking}, no mezclado con
     *              {@code message.content}). Verificado en vivo: para
     *              nuestro turno de decisión (¿busco evidencia o no?) el
     *              pensamiento mejora notablemente la confiabilidad de la
     *              decisión, a costa de latencia (varios segundos más por
     *              llamada); para el turno final (llenar el contrato ya
     *              con la evidencia en mano) no aporta y solo suma
     *              latencia, así que ahí se desactiva. {@code null} deja
     *              el default del modelo (para operaciones que no usan
     *              modelos con pensamiento, como el CEO).
     */
    private LlmResponse callModel(
            String operation,
            String actor,
            String model,
            List<Map<String, Object>> messages,
            Object format,
            List<Map<String, Object>> tools,
            Boolean think) {

        rejectFormatCombinedWithTools(operation, format, tools);

        var startedAt = System.nanoTime();

        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("stream", false);
        body.put("messages", messages);

        if (format != null) {
            body.put("format", format);
        }

        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

        if (think != null) {
            body.put("think", think);
        }

        Map<String, Object> response;

        try {

            response = ollama
                    .post()
                    .uri("/api/chat")
                    .body(body)
                    .retrieve()
                    .body(Map.class);

        } catch (Exception ex) {

            var durationMs =
                    (System.nanoTime() - startedAt)
                            / 1_000_000;

            log.error(
                    "OLLAMA_ERROR operation={} actor={} model={} durationMs={}",
                    operation,
                    actor,
                    model,
                    durationMs,
                    ex
            );

            throw ex;
        }

        var localDurationMs =
                (System.nanoTime() - startedAt)
                        / 1_000_000;

        if (response == null) {

            log.warn(
                    "OLLAMA_EMPTY_RESPONSE operation={} actor={} model={} durationMs={}",
                    operation,
                    actor,
                    model,
                    localDurationMs
            );

            return new LlmResponse("Sin respuesta del modelo.", List.of());
        }

        logMetrics(
                operation,
                actor,
                model,
                localDurationMs,
                response
        );

        var msg =
                (Map<String, Object>) response.get("message");

        if (msg == null) {
            return new LlmResponse("Sin respuesta del modelo.", List.of());
        }

        var content = String.valueOf(msg.get("content"));
        var rawToolCalls = msg.get("tool_calls");

        List<Map<String, Object>> toolCalls = new ArrayList<>();

        if (rawToolCalls instanceof List<?> list) {

            for (var item : list) {

                if (item instanceof Map<?, ?> map) {
                    toolCalls.add((Map<String, Object>) map);
                }
            }
        }

        return new LlmResponse(content, toolCalls);
    }

    private void logMetrics(
            String operation,
            String actor,
            String model,
            long localDurationMs,
            Map<String, Object> response) {

        long totalDurationMs =
                nanosToMillis(
                        response.get("total_duration")
                );

        long loadDurationMs =
                nanosToMillis(
                        response.get("load_duration")
                );

        long promptEvalDurationMs =
                nanosToMillis(
                        response.get("prompt_eval_duration")
                );

        long evalDurationMs =
                nanosToMillis(
                        response.get("eval_duration")
                );

        int promptTokens =
                intValue(
                        response.get("prompt_eval_count")
                );

        int outputTokens =
                intValue(
                        response.get("eval_count")
                );

        double tokensPerSecond =
                evalDurationMs > 0
                        ? (outputTokens * 1000.0)
                        / evalDurationMs
                        : 0.0;

        log.info(
                "OLLAMA_METRICS operation={} actor={} model={} " +
                "durationMs={} ollamaDurationMs={} loadMs={} " +
                "promptTokens={} outputTokens={} promptEvalMs={} " +
                "outputEvalMs={} tokensPerSec={}",
                operation,
                actor,
                model,
                localDurationMs,
                totalDurationMs,
                loadDurationMs,
                promptTokens,
                outputTokens,
                promptEvalDurationMs,
                evalDurationMs,
                String.format(
                        "%.2f",
                        tokensPerSecond
                )
        );
    }

    private long nanosToMillis(Object value) {

        if (!(value instanceof Number number)) {
            return 0L;
        }

        return number.longValue()
                / 1_000_000L;
    }

    private int intValue(Object value) {

        if (!(value instanceof Number number)) {
            return 0;
        }

        return number.intValue();
    }
}
