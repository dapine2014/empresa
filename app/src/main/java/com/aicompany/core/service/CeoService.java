package com.aicompany.core.service;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.agent.model.DevelopmentResultSchema;
import com.aicompany.core.agent.model.StaticReviewResult;
import com.aicompany.core.agent.model.StaticReviewResultSchema;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlanSchema;
import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.agent.model.AgentResultSchema;
import com.aicompany.core.agent.model.AgentTaskOutcome;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.ConversationTurn;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
                                                            "TEAM_DETAILS",
                                                            "AGENT_STATUS",
                                                            "MISSIONS_NEEDING_ATTENTION",
                                                            "FAILED_MISSIONS",
                                                            "TEST_MISSIONS",
                                                            "LAST_MENTIONED",
                                                            "OPPORTUNITIES",
                                                            "COMPANY_PROFIT",
                                                            "COMPANY_STATUS",
                                                            "MISSION_DETAILS",
                                                            "PRODUCTS"
                                                    ),
                                                    "description",
                                                    "TEAM_DETAILS: "
                                                            + "estructura real "
                                                            + "de uno de los 3 "
                                                            + "equipos de "
                                                            + "Forjai (ver "
                                                            + "teamId) -- "
                                                            + "miembros, líder, "
                                                            + "roles, "
                                                            + "roleCode, "
                                                            + "capabilities y "
                                                            + "modelo de cada "
                                                            + "uno. "
                                                            + "AGENT_STATUS: qué "
                                                            + "está "
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
                                                            + "misiones "
                                                            + "mencionadas en "
                                                            + "esta "
                                                            + "conversación. "
                                                            + "OPPORTUNITIES: "
                                                            + "oportunidades "
                                                            + "identificadas. "
                                                            + "COMPANY_PROFIT: "
                                                            + "costos, ganancias, "
                                                            + "ingresos, balance y "
                                                            + "últimos movimientos "
                                                            + "reales (incluye "
                                                            + "gastos y "
                                                            + "correcciones). "
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
                                                            + "resultado real de "
                                                            + "cada agente en una "
                                                            + "misión (ver "
                                                            + "missionId): "
                                                            + "hechos, "
                                                            + "recomendación, "
                                                            + "evidencia y "
                                                            + "cálculos. "
                                                            + "PRODUCTS: catálogo "
                                                            + "real de productos y "
                                                            + "servicios por estado."
                                            ),
                                            "missionId", Map.of(
                                                    "type", "string",
                                                    "description",
                                                    "Obligatorio solo si "
                                                            + "topic=MISSION_DETAILS: "
                                                            + "id exacto, p. ej. "
                                                            + "MISSION-E2E-DISC."
                                            ),
                                            "teamId", Map.of(
                                                    "type", "string",
                                                    "enum", List.of(
                                                            TeamMemoryService.TEAM_DEVELOPMENT,
                                                            TeamMemoryService.TEAM_CREATIVE_PRODUCT_INTELLIGENCE,
                                                            TeamMemoryService.TEAM_MARKETING_GROWTH
                                                    ),
                                                    "description",
                                                    "Obligatorio solo si "
                                                            + "topic=TEAM_DETAILS: "
                                                            + "qué equipo. "
                                                            + "TEAM-DEVELOPMENT: "
                                                            + "Development Group "
                                                            + "(desarrollo de "
                                                            + "software: apps, "
                                                            + "servicios y juegos). "
                                                            + "TEAM-CREATIVE-PRODUCT-INTELLIGENCE: "
                                                            + "diseño de "
                                                            + "interacción/UX/"
                                                            + "game design, "
                                                            + "dirección "
                                                            + "visual/arte, "
                                                            + "telemetría/"
                                                            + "analytics. "
                                                            + "TEAM-MARKETING-GROWTH: "
                                                            + "growth/contenido/"
                                                            + "SEO, gestión de "
                                                            + "comunidad."
                                            )
                                    ),
                                    "required", List.of("topic")
                            )
                    )
            )
    );

    private final RestClient ollama;
    /**
     * Contexto explícito para las llamadas estructuradas de equipos (plan,
     * código, revisión estática). Verificado en vivo: sin options.num_ctx
     * Ollama 0.20 corre qwen3:8b con KvSize 4096 y recorta el prompt en
     * silencio. 16384 entra en la GPU de 8 GB de desarrollo; discovery y
     * chat no cambian (siguen sin options).
     */
    static final int TEAM_CONTEXT_WINDOW_TOKENS = 16_384;

    /**
     * Tope de salida de las llamadas de equipo. Verificado en vivo: sin él,
     * qwen3:8b entró en bucle dentro del JSON y Ollama siguió generando más
     * de una hora (al llenar el contexto lo desplaza y continúa), bloqueando
     * la misión. 6144 tokens alcanzan para varios archivos de código.
     */
    static final int TEAM_MAX_OUTPUT_TOKENS = 6_144;

    private static final java.util.Set<String> TEAM_STRUCTURED_OPERATIONS =
            java.util.Set.of("TEAM_PLANNING", "DEVELOPMENT_TASK", "STATIC_REVIEW");

    /**
     * Prefijo de Agent.model para la API remota compatible con OpenAI (hoy NVIDIA). Decisión del fundador
     * (2026-09-26): Engineering con "nvidia:moonshotai/kimi-k3" tras 18 misiones con qwen3:8b sin build verde.
     */
    static final java.util.regex.Pattern REMOTE_MODEL = java.util.regex.Pattern.compile("^(nvidia(?:-[a-z]+)?):(.+)$");

    /** Proveedor remoto y modelo de un Agent.model con prefijo (decisión del fundador 2026-09-27: una key por grupo). */
    public record RemoteModel(String provider, String model) {
    }

    /** Topics de query_company_memory (los del enum de la herramienta). */
    @SuppressWarnings("unchecked")
    static List<String> companyMemoryTopics() {
        var function = (Map<String, Object>) COMPANY_MEMORY_TOOLS.get(0).get("function");
        var parameters = (Map<String, Object>) function.get("parameters");
        var properties = (Map<String, Object>) parameters.get("properties");
        return (List<String>) ((Map<String, Object>) properties.get("topic")).get("enum");
    }

    /** Forma "proveedor:org/modelo" (un proveedor remoto); un modelo local de Ollama no tiene ese prefijo. */
    private static final java.util.regex.Pattern PROVIDER_PREFIXED =
            java.util.regex.Pattern.compile("^([a-z][a-z0-9-]*):[^:\\s]+/.+$");

    /**
     * Subproyecto 2 (2026-09-28): el modelo de cada agente se edita desde el Command Center. Un proveedor remoto que no
     * está configurado se rechaza al guardar (antes fallaba recién cuando el agente trabajaba).
     */
    public void checkModel(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("El modelo no puede estar vacío.");
        }
        var candidate = model.strip();
        var remote = remoteModel(candidate);
        var prefixed = PROVIDER_PREFIXED.matcher(candidate);
        var provider = remote.map(RemoteModel::provider).orElse(prefixed.matches() ? prefixed.group(1) : null);
        if (provider != null && !remotes.containsKey(provider)) {
            throw new IllegalArgumentException("Proveedor remoto desconocido \"" + provider + "\". Configurados: "
                    + new java.util.TreeSet<>(remotes.keySet()) + " (o un modelo local de Ollama, p. ej. qwen3:8b).");
        }
    }

    static java.util.Optional<RemoteModel> remoteModel(String agentModel) {
        if (agentModel == null) {
            return java.util.Optional.empty();
        }
        var m = REMOTE_MODEL.matcher(agentModel);
        return m.matches() ? java.util.Optional.of(new RemoteModel(m.group(1), m.group(2))) : java.util.Optional.empty();
    }
    static final int REMOTE_TEAM_MAX_OUTPUT_TOKENS = 16_384;
    static final int REMOTE_MAX_OUTPUT_TOKENS = 4_096;

    private final JsonMapper jsonMapper;
    private final EvidenceAcquisitionService evidenceAcquisitionService;
    private final CompanyEventPublisher events;
    private final MeterRegistry meterRegistry;
    private final Map<String, OpenAiCompatibleClient> remotes;

    public CeoService(
            RestClient ollama,
            JsonMapper jsonMapper,
            EvidenceAcquisitionService evidenceAcquisitionService,
            CompanyEventPublisher events,
            MeterRegistry meterRegistry) {
        this(ollama, jsonMapper, evidenceAcquisitionService, events, meterRegistry, Map.of());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CeoService(
            RestClient ollama,
            JsonMapper jsonMapper,
            EvidenceAcquisitionService evidenceAcquisitionService,
            CompanyEventPublisher events,
            MeterRegistry meterRegistry,
            Map<String, OpenAiCompatibleClient> remotes) {

        this.ollama = ollama;
        this.jsonMapper = jsonMapper;
        this.evidenceAcquisitionService = evidenceAcquisitionService;
        this.events = events;
        this.meterRegistry = meterRegistry;
        this.remotes = remotes == null ? Map.of() : remotes;
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
            Function<String, String> companyMemoryQuery,
            String ceoPrompt,
            String model) {

        var system = systemPrompt(ceoPrompt)
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
                + " Memory."
                + "\nRegla dura sobre estado de misiones: el workflowStatus de una"
                + " misión (CREATED/PLANNING/.../AWAITING_INVESTOR/COMPLETED) es el"
                + " estado del proceso de ANÁLISIS INTERNO — nunca lo uses para"
                + " afirmar nada sobre el estado real del producto (si está en"
                + " desarrollo, publicado o generando ingresos). Una AgentTask"
                + " DELIVERY_FEASIBILITY completada es un estudio de factibilidad,"
                + " NO significa que el desarrollo haya comenzado. Si te preguntan"
                + " por el estado de desarrollo/negocio de una misión y no tenés"
                + " ese dato exacto en este mensaje ni de query_company_memory,"
                + " respondé exactamente: \"No tengo ese dato registrado.\" — nunca"
                + " asumas que un paso avanzó porque otro paso anterior terminó.";

        // Verificado en vivo: con varios mencionados, el CEO escribía también por los demás.
        system += "\nResponde solo por ti, en primera persona: nunca escribas respuestas en nombre de otros agentes, "
                + "aunque el fundador los mencione en el mismo mensaje (ellos responden aparte).";
        return conversation("CEO_CHAT", "ceo", system, history, message, companyMemoryQuery, model);
    }

    /** Hablante del chat con varios agentes (spec 2026-09-27 §5). */
    public record ChatSpeaker(String agentId, String name, String role, String personality) {
    }

    /**
     * Un agente responde en el chat (spec 2026-09-27 §5): su identidad, su prompt activo, su modelo y solo
     * query_company_memory (lectura). No lanza misiones, no aprueba, no rechaza ni contacta a nadie.
     */
    public String agentChat(
            ChatSpeaker speaker,
            String teamRoster,
            List<ConversationTurn> history,
            String message,
            Function<String, String> companyMemoryQuery,
            String agentPrompt,
            String model) {

        var promptBlock = agentPrompt == null || agentPrompt.isBlank() ? ""
                : "CÓMO DEBES RAZONAR (definido por el fundador para vos):\n" + agentPrompt + "\n";
        var system = """
                Eres %s, %s de Forjai, una empresa real operada principalmente por agentes de IA. Tu nombre es %s.
                Personalidad: %s
                Estás en el chat de la empresa respondiendo al fundador (a veces junto a otros agentes).
                Responde solo por ti, en primera persona: nunca escribas respuestas, saludos ni opiniones en nombre de
                otros agentes, aunque el fundador los haya mencionado en el mismo mensaje (ellos responden aparte).
                No puedes lanzar misiones, aprobar, rechazar ni contactar a nadie: si te lo piden, dilo y remite a Alex
                (el CEO) o a los comandos de misión. Las acciones reservadas son solo del fundador.
                No inventes clientes, ventas, ingresos, búsquedas ni evidencia. Para datos reales de la empresa usa
                query_company_memory; si no hay dato, responde exactamente: "No tengo ese dato registrado."
                Equipo real (nombre y rol):
                %s
                %s""".formatted(speaker.name(), speaker.role(), speaker.name(), speaker.personality(), teamRoster,
                promptBlock);

        return conversation("AGENT_CHAT", speaker.agentId(), system, history, message, companyMemoryQuery, model);
    }

    /** Encabezado de los datos reales que Java antepone al mensaje: la respuesta basada en ellos no se marca. */
    public static final String JAVA_MEMORY_DATA_MARKER = "DATOS REALES DE FORJAI (consultados por Java):";

    static final String UNBACKED_MEMORY_CLAIM_NOTE =
            "⚠️ Nota de Forjai: esta respuesta dice haber consultado la memoria de la empresa, pero en este turno no "
                    + "se consultó. Los datos salen del historial de la conversación y pueden estar desactualizados.";

    private static final Pattern MEMORY_CLAIM = Pattern.compile(
            "query_company_memory|company memory|segun (la )?memoria|(consulte|revise) (la )?memoria");

    /** Sin llamada real a la herramienta, una respuesta que dice haber consultado la memoria se marca (Java, no el modelo). */
    private String flagUnbackedMemoryClaim(String operation, String actor, String content) {
        if (content == null) {
            return null;
        }
        var normalized = Normalizer.normalize(content, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        if (!MEMORY_CLAIM.matcher(normalized).find()) {
            return content;
        }
        log.warn("UNBACKED_MEMORY_CLAIM operation={} actor={}", operation, actor);
        return content + "\n\n" + UNBACKED_MEMORY_CLAIM_NOTE;
    }

    static final String NO_INVESTOR_COMMENT = "(sin comentario del inversionista)";

    /**
     * Spec evidence-rounds §5 (revisión 2026-09-27): el CEO reparte el pedido del inversionista entre los agentes de la
     * ronda. Java garantiza que nadie quede sin él: si el modelo falla o deja vacío a un agente, recibe el pedido completo.
     */
    public Map<String, String> routeInvestorFeedback(String instruction, String priorResults, String investorRequest,
                                                     List<String> agentIds, String model) {
        var request = investorRequest == null || investorRequest.isBlank() ? NO_INVESTOR_COMMENT : investorRequest.strip();
        var routed = new java.util.LinkedHashMap<String, String>();
        agentIds.forEach(id -> routed.put(id, request));
        if (request.equals(NO_INVESTOR_COMMENT)) {
            return routed;
        }
        try {
            var properties = new java.util.LinkedHashMap<String, Object>();
            agentIds.forEach(id -> properties.put(id, Map.of("type", "string")));
            var schema = Map.<String, Object>of("type", "object", "properties", properties, "required", agentIds);
            var system = """
                    Eres el CEO de Forjai. El inversionista pidió más evidencia sobre una misión. Reparte su pedido entre
                    los agentes: para cada uno, qué debe buscar o corregir en esta nueva ronda, en una o dos frases, sin
                    inventar datos. Si el pedido no le toca a un agente, deja su valor vacío. Responde solo el JSON.
                    """;
            var user = "MISIÓN:\n" + instruction + "\n\nRESULTADOS DE LA RONDA ANTERIOR:\n" + priorResults
                    + "\n\nPEDIDO DEL INVERSIONISTA:\n" + request;
            var reply = callModel("INVESTOR_FEEDBACK_ROUTING", "ceo", model,
                    List.of(Map.<String, Object>of("role", "system", "content", system),
                            Map.<String, Object>of("role", "user", "content", user)),
                    schema, null);
            var node = jsonMapper.readTree(normalizeJsonResponse(reply.content()));
            for (var id : agentIds) {
                var part = node.path(id).asString("");
                if (!part.isBlank()) {
                    routed.put(id, part.strip() + "\n(Pedido original del inversionista: " + request + ")");
                }
            }
        } catch (Exception ex) {
            log.warn("INVESTOR_FEEDBACK_ROUTING failed, every agent gets the full request: {}", ex.getMessage());
        }
        return routed;
    }

    /** Dos turnos: uno con query_company_memory disponible y, si la pidió, el final con el resultado real. */
    private String conversation(String operation, String actor, String system, List<ConversationTurn> history,
                                String message, Function<String, String> companyMemoryQuery, String model) {

        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", system));
        messages.addAll(buildHistoryMessages(history));
        messages.add(Map.of("role", "user", "content", message));

        var turn = callModel(
                operation, actor, model, messages, null, COMPANY_MEMORY_TOOLS
        );

        var topic =
                !turn.toolCalls().isEmpty()
                        ? parseCompanyMemoryTopic(turn.toolCalls().get(0))
                        : detectInlineCompanyMemoryTopic(turn.content());

        if (topic == null) {
            return message.contains(JAVA_MEMORY_DATA_MARKER)
                    ? turn.content()
                    : flagUnbackedMemoryClaim(operation, actor, turn.content());
        }

        log.info("CEO_CHAT_TOOL_CALL topic={}", topic);

        var result = companyMemoryQuery.apply(topic);

        messages.add(Map.of(
                "role", "assistant",
                "content", "",
                "tool_calls", List.of(Map.of(
                        "function", Map.of(
                                "name", "query_company_memory",
                                "arguments", Map.of("topic", topic)
                        )
                ))
        ));

        messages.add(Map.of("role", "tool", "content", result));

        var finalTurn = callModel(
                operation, actor, model, messages, null, null
        );

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
            String taskId,
            String model) {

        var system =
                systemPrompt(null)
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
                        model,
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
                    executeTool(agentId, missionId, taskId, toolCall, null);

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
                        model,
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
                    model,
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
            ToolCall toolCall,
            SearchScope scope) {

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
                    scope == null
                            ? evidenceAcquisitionService.searchEvidence(toolCall.query())
                            : evidenceAcquisitionService.searchEvidence(toolCall.query(), scope.country(),
                                    scope.language());

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

    /** Spec búsqueda de prospectos §1: país/idioma de la búsqueda (null = sin restringir). */
    private record SearchScope(String country, String language) {
    }

    @SuppressWarnings("unchecked")
    String parseCompanyMemoryTopic(Map<String, Object> rawToolCall) {

        var function = (Map<String, Object>) rawToolCall.get("function");

        if (function == null) {
            return null;
        }

        var name = String.valueOf(function.get("name"));
        var arguments = function.get("arguments");

        String topic = null;
        String teamId = null;

        if (arguments instanceof Map<?, ?> argMap) {
            var value = argMap.get("topic");
            topic = value == null ? null : String.valueOf(value);

            var teamIdValue = argMap.get("teamId");
            teamId = teamIdValue == null ? null : String.valueOf(teamIdValue);
        }

        if (!"query_company_memory".equals(name)
                || topic == null
                || topic.isBlank()) {
            return null;
        }

        if ("TEAM_DETAILS".equals(topic)) {
            return "TEAM_DETAILS:" + (teamId == null ? "" : teamId);
        }

        if ("MISSION_DETAILS".equals(topic) && arguments instanceof Map<?, ?> argMap) {
            var missionId = argMap.get("missionId");
            return "MISSION_DETAILS:" + (missionId == null ? "" : String.valueOf(missionId));
        }

        return topic;
    }

    String detectInlineCompanyMemoryTopic(String content) {

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

            if ("TEAM_DETAILS".equals(topic)) {
                var teamId = argumentsNode.path("teamId").asString(null);
                return "TEAM_DETAILS:" + (teamId == null ? "" : teamId);
            }

            if ("MISSION_DETAILS".equals(topic)) {
                var missionId = argumentsNode.path("missionId").asString(null);
                return "MISSION_DETAILS:" + (missionId == null ? "" : missionId);
            }

            return topic;

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
            String agentResults,
            String ceoPrompt,
            String model) {

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
                Map.of("role", "system", "content", systemPrompt(ceoPrompt)),
                Map.of("role", "user", "content", prompt)
        );

        return callModel(
                "MISSION_CONSOLIDATION", "ceo", model, messages, null, null
        ).content();
    }

    /** Plan del líder de un equipo (spec §3): format TeamPlanSchema, sin tools. */
    public TeamPlan planTeamWork(String agentId, String prompt, String agentPrompt, String model) {
        return callStructured("TEAM_PLANNING", agentId, prompt, agentPrompt, model,
                TeamPlanSchema.SCHEMA, TeamPlan.class);
    }

    /** Código real de una tarea WORK (spec §6): format DevelopmentResultSchema, sin tools. */
    public DevelopmentResult generateDevelopmentArtifact(String agentId, String prompt, String agentPrompt, String model) {
        return callStructured("DEVELOPMENT_TASK", agentId, prompt, agentPrompt, model,
                DevelopmentResultSchema.SCHEMA, DevelopmentResult.class);
    }

    /** Revisión estática del repo (spec §7, capa 2): format StaticReviewResultSchema, sin tools. */
    public StaticReviewResult reviewStaticWorkspace(String agentId, String prompt, String agentPrompt, String model) {
        return callStructured("STATIC_REVIEW", agentId, prompt, agentPrompt, model,
                StaticReviewResultSchema.SCHEMA, StaticReviewResult.class);
    }

    /**
     * Una sola llamada con format y SIN tools (regla dura del proyecto). El
     * reintento con corrección vive en el llamador (TeamWorkPlanner /
     * DevelopmentRuntime), igual que el turno final de executeAgentTask.
     */
    private static final Map<String, Object> CHOICE_SCHEMA = Map.of("type", "object",
            "properties", Map.of("productId", Map.of("type", "string"), "reason", Map.of("type", "string")),
            "required", List.of("productId", "reason"));

    private static final Map<String, Object> PROSPECTS_SCHEMA = Map.of("type", "object",
            "properties", Map.of("prospects", Map.of("type", "array", "items", Map.of("type", "object",
                    "properties", Map.of(
                            "name", Map.of("type", "string"),
                            "url", Map.of("type", "string"),
                            "contactEmail", Map.of("type", "string"),
                            "contactFormUrl", Map.of("type", "string"),
                            "contactSourceUrl", Map.of("type", "string"),
                            "fitReason", Map.of("type", "string")),
                    "required", List.of("name", "url", "contactEmail", "contactFormUrl", "contactSourceUrl", "fitReason")))),
            "required", List.of("prospects"));

    private static final Map<String, Object> STRATEGY_SCHEMA = Map.of("type", "object",
            "properties", Map.of(
                    "name", Map.of("type", "string"),
                    "description", Map.of("type", "string"),
                    "searchHints", Map.of("type", "string")),
            "required", List.of("name", "description", "searchHints"));

    private static final Map<String, Object> SHEET_SCHEMA = Map.of("type", "object",
            "properties", Map.of(
                    "kind", Map.of("type", "string", "enum", List.of("SOFTWARE", "SERVICE")),
                    "targetCustomer", Map.of("type", "string"),
                    "markets", Map.of("type", "array", "items", Map.of("type", "string")),
                    "languages", Map.of("type", "array", "items", Map.of("type", "string")),
                    "priceUsd", Map.of("type", "number"),
                    "priceOnRequest", Map.of("type", "boolean"),
                    "estimatedCostUsd", Map.of("type", "number"),
                    "delivery", Map.of("type", "string"),
                    "name", Map.of("type", "string"),
                    "description", Map.of("type", "string")),
            "required", List.of("kind", "targetCustomer", "markets", "languages", "priceUsd", "priceOnRequest",
                    "estimatedCostUsd", "delivery", "name", "description"));

    private static final Map<String, Object> DELIVERY_SCHEMA = Map.of("type", "object",
            "properties", Map.of("delivery", Map.of("type", "string")), "required", List.of("delivery"));

    private record DeliveryAnswer(String delivery) {
    }

    /** Spec orquestador (2026-09-28): Alex elige qué construir; Java valida que el id esté entre los candidatos. */
    public com.aicompany.core.model.ProductChoice chooseProduct(String candidatesText, String model) {
        var prompt = """
                Forjai no tiene ningún producto listo para vender ni en construcción. Elige UNA de estas ideas para
                construir ahora: la de mejor potencial de ventas y ganancias, con clientes en cualquier país, según la
                evidencia indicada. Usa solo los datos dados, sin inventar.
                IDEAS CANDIDATAS:
                %s
                FORMATO: {"productId": "<id exacto de la lista>", "reason": "<por qué, en una o dos frases>"}
                """.formatted(candidatesText);
        return callStructured("ORCHESTRATOR_CHOICE", "ceo", prompt, null, model, CHOICE_SCHEMA,
                com.aicompany.core.model.ProductChoice.class);
    }

    /**
     * Spec búsqueda de prospectos §1: Sofía busca prospectos reales para un producto con la estrategia del día. Mismo
     * patrón de dos turnos que las tareas (nunca format + tools); el alcance de la búsqueda sale de la ficha. Java valida
     * cada prospecto después (ProspectValidator).
     */
    public com.aicompany.core.prospecting.ProspectBatch searchProspects(
            String productText, com.aicompany.core.prospecting.StrategyOption strategy, String country, String language,
            String model) {

        var task = """
                Busca en la web EMPRESAS O PERSONAS CONCRETAS que podrían comprar este producto de Forjai hoy.
                Estrategia del día: %s — %s (pistas de búsqueda: %s).
                PRODUCTO:
                %s
                """.formatted(strategy.name(), strategy.description(), strategy.searchHints(), productText);

        // Verificado en vivo (2026-09-30): la consulta salía en español para un producto en inglés, y una sola búsqueda
        // no alcanzaba para ir de un listado a los contactos. Hasta 2 búsquedas, en el idioma de la ficha.
        var decision = new ArrayList<Map<String, Object>>();
        decision.add(Map.of("role", "system", "content", toolDecisionSystemPrompt("sales")));
        decision.add(Map.of("role", "user", "content", task + "\nEscribe cada consulta de búsqueda en el idioma \""
                + (language == null ? "en" : language) + "\" (el de los clientes del producto). Puedes buscar hasta "
                + MAX_PROSPECT_SEARCHES + " veces; si ya tienes suficiente, responde NINGUNA."));
        var exchanges = new ArrayList<Map<String, Object>>();
        for (int search = 0; search < MAX_PROSPECT_SEARCHES; search++) {
            var turn = callModel("PROSPECTING_TOOL_CALL", "sales", model, decision, null, AGENT_TOOLS, true);
            var call = !turn.toolCalls().isEmpty()
                    ? parseStructuredToolCall(turn.toolCalls().get(0))
                    : detectInlineToolCall(turn.content());
            if (call == null) {
                break;
            }
            var assistant = Map.<String, Object>of("role", "assistant", "content", "", "tool_calls", List.of(Map.of(
                    "function", Map.of("name", call.name(), "arguments", Map.of("query", call.query())))));
            var tool = Map.<String, Object>of("role", "tool", "content", prospectSearch(call.query(), country, language));
            decision.add(assistant);
            decision.add(tool);
            exchanges.add(assistant);
            exchanges.add(tool);
        }

        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", teamSystemPrompt("sales", null)));
        messages.add(Map.of("role", "user", "content", task + """

                REGLAS:
                - Solo empresas o personas reales y nombradas, con su propia página web (url). Nunca un segmento de
                  mercado ni un artículo genérico.
                - contactEmail: solo si el email figura en una página pública; contactSourceUrl es esa página. Si no hay
                  email, contactFormUrl con la página del formulario de contacto. Deja vacío lo que no tengas.
                - fitReason: por qué este producto le sirve, en una frase.
                - No inventes nada: Forjai verifica que el nombre esté en su página y el email en su fuente.
                - Lista vacía si no encontraste ninguno.
                FORMATO: {"prospects": [{"name", "url", "contactEmail", "contactFormUrl", "contactSourceUrl", "fitReason"}]}
                """));

        messages.addAll(exchanges);

        var response = callModel("PROSPECTING_SEARCH", "sales", model, messages, PROSPECTS_SCHEMA, null, false).content();
        try {
            return jsonMapper.readValue(normalizeJsonResponse(response), com.aicompany.core.prospecting.ProspectBatch.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Sofía no devolvió prospectos en JSON válido: " + ex.getMessage(), ex);
        }
    }

    private static final Map<String, Object> OUTREACH_SCHEMA = Map.of("type", "object",
            "properties", Map.of("subject", Map.of("type", "string"), "body", Map.of("type", "string")),
            "required", List.of("subject", "body"));

    /**
     * Spec contacto con prospectos §1: Sofía redacta el primer correo a un prospecto. Java lo verifica después
     * (OutreachDraftValidator) y el fundador lo aprueba antes de enviarlo (🔴).
     */
    public com.aicompany.core.outreach.OutreachDraft draftOutreach(String productSheet, String prospectText,
                                                                   String correction, String model) {
        var prompt = """
                Escribe el primer correo de Forjai a este prospecto para ofrecerle el producto. Breve (máximo 150
                palabras), cordial, en el idioma del prospecto si se deduce de sus datos (si no, en inglés), sin
                exagerar ni prometer nada que no esté en la descripción del producto. Menciona el nombre exacto del
                producto y, si la ficha tiene precio, ese precio exacto en US$ (ningún otro monto). No incluyas links ni
                emails, ni la firma, ni una línea para darse de baja: Forjai los agrega.
                PRODUCTO (ficha):
                %s
                PROSPECTO:
                %s
                FORMATO: {"subject": "<asunto>", "body": "<cuerpo>"}
                """.formatted(productSheet, prospectText)
                + (correction == null || correction.isBlank() ? ""
                        : "\nCORRECCIÓN DEL INTENTO ANTERIOR: " + correction);
        return callStructured("OUTREACH_DRAFT", "sales", prompt, null, model, OUTREACH_SCHEMA,
                com.aicompany.core.outreach.OutreachDraft.class);
    }

    static final int MAX_PROSPECT_SEARCHES = 2;
    static final int PROSPECT_PAGES_PER_SEARCH = 5;

    /**
     * Verificado en vivo (2026-09-30, primera búsqueda real): con título y fragmento Sofía no veía contactos, y el filtro
     * de relevancia de la evidencia (≥30% de términos) descartaba los directorios. Para prospectar, Java entrega el texto
     * de cada página y los contactos que encuentra; la validez la decide ProspectValidator.
     */
    private String prospectSearch(String query, String country, String language) {
        var pages = new ArrayList<Map<String, Object>>();
        try {
            for (var candidate : evidenceAcquisitionService.searchEvidence(query, country, language)) {
                if (pages.size() >= PROSPECT_PAGES_PER_SEARCH) {
                    break;
                }
                try {
                    var digest = com.aicompany.core.prospecting.PageDigest.of(
                            evidenceAcquisitionService.fetchPage(candidate.url()), candidate.url());
                    pages.add(Map.of("title", candidate.title() == null ? "" : candidate.title(), "url", candidate.url(),
                            "text", digest.excerpt(), "emails", digest.emails(), "contactLinks", digest.contactLinks()));
                } catch (Exception ex) {
                    log.info("PROSPECTING_PAGE_SKIPPED url={} reason={}", candidate.url(), ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.warn("PROSPECTING_SEARCH_FAILED query={} reason={}", query, ex.getMessage());
            return "{\"error\": \"La búsqueda web falló: " + ex.getMessage().replace("\"", "'") + "\"}";
        }
        log.info("PROSPECTING_SEARCH query={} pages={}", query, pages.size());
        try {
            return jsonMapper.writeValueAsString(Map.of("query", query, "pages", pages));
        } catch (Exception ex) {
            return "{\"pages\": []}";
        }
    }

    /** Spec búsqueda de prospectos §2: Kira propone una estrategia nueva (la aprueba el fundador). */
    public com.aicompany.core.prospecting.StrategyProposal proposeProspectingStrategy(String performanceText, String model) {
        var prompt = """
                Propón UNA estrategia nueva para encontrar prospectos (clientes posibles) de los productos de Forjai,
                distinta de las que ya existen. Mira el rendimiento: válidos por corrida de cada estrategia.
                RENDIMIENTO Y ESTRATEGIAS EXISTENTES:
                %s
                FORMATO: {"name": "<nombre corto>", "description": "<dónde y cómo buscar>", "searchHints": "<palabras clave>"}
                """.formatted(performanceText);
        return callStructured("PROSPECTING_STRATEGY", "growth-content", prompt, null, model, STRATEGY_SCHEMA,
                com.aicompany.core.prospecting.StrategyProposal.class);
    }

    /** Ficha del producto a partir de la evidencia de las misiones (Java la valida antes de aplicarla). */
    public com.aicompany.core.model.ProductSheet proposeProductSheet(String productText, String evidenceText, String model) {
        var prompt = """
                Completa la ficha de este producto de Forjai para construirlo y venderlo. kind: SOFTWARE si hay que
                programarlo, SERVICE si se entrega como servicio. Mercados: WORLDWIDE salvo que la evidencia diga otra
                cosa; idiomas en código ISO (en, es...). Precio y costo estimado por venta en USD, sacados de la evidencia;
                si no hay precio sustentado, priceOnRequest=true y priceUsd=0. estimatedCostUsd es el costo de cada venta
                y SIEMPRE es mayor que 0, aunque la evidencia hable de "costo marginal ~0": incluye la comisión de la
                plataforma de pago o de venta (por ejemplo, un porcentaje del precio más un fijo), el costo de IA o de
                infraestructura por venta y el de la entrega, con los valores de la evidencia; súmalos. delivery: cómo se
                entrega (vacío si es software). name: el nombre comercial de UN solo producto concreto (por ejemplo
                "Email Signature Generator"), nunca un plan ni una tarea ("Priorizar…", "Seleccionar…", "Antes de…"):
                si la oferta menciona varios candidatos, elige uno. description: qué es y qué recibe el cliente, en 1 a 3
                frases. No inventes datos.
                PRODUCTO:
                %s
                EVIDENCIA DE LAS MISIONES:
                %s
                """.formatted(productText, evidenceText);
        return callStructured("ORCHESTRATOR_SHEET", "ceo", prompt, null, model, SHEET_SCHEMA,
                com.aicompany.core.model.ProductSheet.class);
    }

    /** Forma de entrega de un servicio, resumida de los resultados del equipo Creative. */
    public String summarizeDelivery(String productText, String creativeResults, String model) {
        var prompt = """
                Resume en un párrafo cómo se entrega este servicio de Forjai (pasos, entregables y tiempos), usando solo
                lo que diseñó el equipo Creative.
                SERVICIO:
                %s
                DISEÑO DEL EQUIPO CREATIVE:
                %s
                FORMATO: {"delivery": "<cómo se entrega>"}
                """.formatted(productText, creativeResults);
        return callStructured("ORCHESTRATOR_DELIVERY", "ceo", prompt, null, model, DELIVERY_SCHEMA, DeliveryAnswer.class)
                .delivery();
    }

    private <T> T callStructured(
            String operation, String agentId, String prompt, String agentPrompt, String model,
            Object schema, Class<T> type) {

        String response = null;

        try {

            var messages = List.<Map<String, Object>>of(
                    Map.of("role", "system", "content", teamSystemPrompt(agentId, agentPrompt)),
                    Map.of("role", "user", "content", prompt)
            );

            var message = callModel(operation, agentId, model, messages, schema, null, false);
            response = message.content();
            if (message.truncated()) {
                throw new TruncatedResponseException("La respuesta de " + agentId + " para " + operation
                        + " se cortó por el límite de salida (" + response.length() + " caracteres).");
            }

            var result = jsonMapper.readValue(normalizeJsonResponse(response), type);

            log.info("{}_PARSED agent={}", operation, agentId);

            return result;

        } catch (Exception ex) {

            log.error("{}_ERROR agent={} model={} reason={} response={}",
                    operation, agentId, model, ex.getMessage(), response);

            // Spec 2026-10-01 §5: un corte no es un JSON inválido; quien llama lo pide en lotes más chicos.
            if (ex instanceof TruncatedResponseException truncated) {
                throw truncated;
            }
            if (String.valueOf(ex.getMessage()).contains("end-of-input")) {
                throw new TruncatedResponseException("La respuesta de " + agentId + " para " + operation
                        + " terminó a mitad del JSON (" + (response == null ? 0 : response.length()) + " caracteres).");
            }

            throw new IllegalStateException(
                    "El agente " + agentId + " no devolvió un JSON válido para " + operation + ": "
                            + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()),
                    ex);
        }
    }

    /**
     * System prompt de un miembro de equipo (no el del CEO). El prompt
     * versionado del agente entra como sección aparte y nunca reemplaza
     * estas reglas (mismo criterio que AgentRuntime.buildPrompt).
     */
    private String teamSystemPrompt(String agentId, String agentPrompt) {

        var agentPromptBlock = (agentPrompt == null || agentPrompt.isBlank())
                ? ""
                : "\nCÓMO DEBES RAZONAR (definido por el fundador para vos, no reemplaza las reglas de abajo):\n"
                        + agentPrompt + "\n";

        return """
                Estás trabajando dentro de Forjai como el agente %s, miembro de un equipo real.
                Forjai es una empresa real operada principalmente por agentes de IA.
                %s
                REGLAS:
                - No inventes archivos, commits, resultados de ejecución, clientes ni evidencia.
                - En esta fase nadie ejecuta código: nunca afirmes que algo compila, se ejecuta o pasa tests.
                - Una hipótesis NO es un hecho.
                - Responde ÚNICAMENTE con JSON válido que cumpla el formato pedido, sin Markdown ni texto adicional.

                Responde en español.
                """.formatted(agentId, agentPromptBlock);
    }

    /**
     * {@code agentPrompt} es el prompt activo persistido del CEO
     * (`Agent {id:'ceo'}`, ver {@code PromptMemoryService}) — resuelto
     * por el llamador (`ChatIntentRouter`/`MissionExecutor`), nunca por
     * este servicio directamente (sigue sin depender de Neo4j). Se
     * inserta como una sección aparte, condicional: si está en blanco,
     * el prompt final es byte a byte igual al de antes de esta feature.
     */
    private String systemPrompt(String agentPrompt) {

        var agentPromptBlock = (agentPrompt == null || agentPrompt.isBlank())
                ? ""
                : "\nCÓMO DEBES RAZONAR (definido por el fundador para vos, no reemplaza las reglas de abajo):\n"
                        + agentPrompt + "\n";

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
                %s
                No inventes clientes, ventas, ingresos,
                búsquedas o evidencia.

                Diferencia siempre entre:
                - hecho
                - hipótesis
                - estimación
                - evidencia
                - resultado verificado

                Responde en español.
                """.formatted(agentPromptBlock);
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

    private ModelHealthService modelHealth;

    /** Spec salud de modelos (2026-09-28). Setter opcional: los tests que no lo usan no cambian. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setModelHealth(ModelHealthService modelHealth) {
        this.modelHealth = modelHealth;
    }

    /** La llamada del agente va a su suplente local; nunca en silencio (log, y el aviso lo da ModelHealthService). */
    private ModelMessage viaFallback(String operation, String actor, String model, List<Map<String, Object>> messages,
                                     Object format, List<Map<String, Object>> tools, Boolean think, Exception cause) {
        var fallback = modelHealth.fallbackFor(actor);
        var since = modelHealth.downSince(model);
        if (fallback == null || fallback.isBlank() || remoteModel(fallback).isPresent() && modelHealth.isDown(fallback)) {
            throw new IllegalStateException("El modelo " + model + " no responde en NVIDIA"
                    + (since == null ? "" : " desde " + since) + " y " + actor
                    + " no tiene suplente disponible.", cause);
        }
        log.warn("MODEL_FALLBACK operation={} actor={} model={} fallback={}", operation, actor, model, fallback);
        try {
            return callModel(operation, actor, fallback, messages, format, tools, think);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("El modelo " + model + " no responde en NVIDIA y el suplente " + fallback
                    + " de " + actor + " también falló: " + ex.getMessage(), ex);
        }
    }

    /**
     * Verificado en vivo (MISSION-LOCAL-DISC, 2026-09-28): un modelo local sin "thinking" (qwen3-coder:30b, suplente de
     * Engineering) responde 400 "does not support thinking" si se manda "think". Se reintenta sin esa opción.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> postToOllama(Map<String, Object> body) {
        try {
            return ollama.post().uri("/api/chat").body(body).retrieve().body(Map.class);
        } catch (org.springframework.web.client.HttpClientErrorException.BadRequest ex) {
            if (!body.containsKey("think") || !ex.getResponseBodyAsString().contains("does not support thinking")) {
                throw ex;
            }
            var withoutThink = new java.util.LinkedHashMap<>(body);
            withoutThink.remove("think");
            log.info("OLLAMA_THINK_UNSUPPORTED model={} - retrying without think", body.get("model"));
            return ollama.post().uri("/api/chat").body(withoutThink).retrieve().body(Map.class);
        }
    }

    @SuppressWarnings("unchecked")
    private ModelMessage callModel(
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
    private ModelMessage callModel(
            String operation,
            String actor,
            String model,
            List<Map<String, Object>> messages,
            Object format,
            List<Map<String, Object>> tools,
            Boolean think) {

        rejectFormatCombinedWithTools(operation, format, tools);

        var startedAt = System.nanoTime();

        var remoteRef = remoteModel(model);
        if (remoteRef.isPresent()) {
            var remote = remotes.get(remoteRef.get().provider());
            if (remote == null) {
                throw new IllegalStateException("No hay proveedor remoto configurado para \"" + remoteRef.get().provider()
                        + "\" (modelo " + model + ").");
            }
            var remoteModel = remoteRef.get().model();
            var maxTokens = TEAM_STRUCTURED_OPERATIONS.contains(operation)
                    ? REMOTE_TEAM_MAX_OUTPUT_TOKENS : REMOTE_MAX_OUTPUT_TOKENS;
            // Spec salud de modelos (2026-09-28): un modelo caído no se vuelve a esperar; va al suplente del agente.
            if (modelHealth != null && modelHealth.isDown(model)) {
                return viaFallback(operation, actor, model, messages, format, tools, think, null);
            }
            OpenAiCompatibleClient.RemoteReply reply;
            try {
                // Spec 2026-09-27 §2: herramientas traducidas por el cliente; el guard format+tools ya corrió arriba.
                reply = remote.complete(remoteModel, messages, tools, format != null, maxTokens);
            } catch (RemoteUnavailableException ex) {
                if (modelHealth == null) {
                    throw ex;
                }
                // Verificado en vivo (2026-09-29): con un solo fallo el modelo aún no está DOWN, pero esta llamada
                // igual se repite con el suplente; DOWN (dejar de llamarlo) sigue exigiendo 2 fallos seguidos.
                modelHealth.recordFailure(model, ex.getMessage());
                return viaFallback(operation, actor, model, messages, format, tools, think, ex);
            }
            if (modelHealth != null) {
                modelHealth.recordSuccess(model);
            }
            log.info("REMOTE_MODEL_METRICS operation={} actor={} model={} durationMs={} chars={} toolCalls={}",
                    operation, actor, remoteModel, (System.nanoTime() - startedAt) / 1_000_000,
                    reply.content().length(), reply.toolCalls().size());
            return new ModelMessage(reply.content(), reply.toolCalls(), "length".equals(reply.finishReason()));
        }

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

        if (TEAM_STRUCTURED_OPERATIONS.contains(operation)) {
            body.put("options", Map.of(
                    "num_ctx", TEAM_CONTEXT_WINDOW_TOKENS,
                    "num_predict", TEAM_MAX_OUTPUT_TOKENS));
        }

        Map<String, Object> response;

        try {

            response = postToOllama(body);

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

            return new ModelMessage("Sin respuesta del modelo.", List.of());
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
            return new ModelMessage("Sin respuesta del modelo.", List.of());
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

        return new ModelMessage(content, toolCalls, "length".equals(String.valueOf(response.get("done_reason"))));
    }

    /** truncated: el proveedor cortó la respuesta por el límite de salida (spec 2026-10-01 §5). */
    private record ModelMessage(
            String content,
            List<Map<String, Object>> toolCalls,
            boolean truncated
    ) {
        ModelMessage(String content, List<Map<String, Object>> toolCalls) {
            this(content, toolCalls, false);
        }
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
