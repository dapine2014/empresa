# Memoria conversacional del Company Chat — diseño

**Estado:** aprobado por el usuario, pendiente de implementación.
**Fecha:** 2026-09-14

## Contexto y problema

El Company Chat (`ChatIntentRouter` + `CeoService.chat`) es hoy 100% sin estado: cada `POST /api/company/chat` es independiente, no hay ningún concepto de sesión ni de historial persistido (`ChatRequest` solo tiene `message`; `ChatPage.tsx` guarda los mensajes solo en el navegador, se pierden al refrescar).

Esto rompe conversaciones de seguimiento reales. Ejemplo reportado por el usuario en vivo:

```
Tú: "¿Qué necesita mi aprobación?"
CEO: "Tenés 15 misión(es)... MISSION-DEBUG-007, MISSION-STRUCTURED-001, ..."
Tú: "pero esas están en prueba"
CEO: (no tiene forma de saber qué son "esas")
```

El proyecto tiene una regla ya establecida y reafirmada varias veces en esta misma sesión de trabajo: **nunca resolver estos problemas con "otro prompt del CEO"** — cuando algo puede resolverse determinísticamente contra Neo4j, se resuelve así; el LLM solo narra sobre datos reales, nunca decide ni inventa estructura. Este diseño sigue ese mismo criterio para la resolución de referencias conversacionales.

## Alcance acordado con el usuario (antes de escribir código)

1. **Un solo hilo de conversación global** — no hay concepto de usuario/sesión en la app (un solo fundador operando todo), así que no se inventa uno. Toda la conversación del Command Center es un único hilo continuo, sin importar desde qué pestaña se escriba.
2. **Persistida en Neo4j**, no en memoria del proceso — consistente con el criterio ya aplicado en `Agent.status`: "Neo4j es la memoria de la empresa", un hecho real debe vivir ahí, no perderse en cada restart del contenedor (que en esta etapa de desarrollo pasa seguido).
3. **Enfoque híbrido (C de 3 propuestos)**: resolución determinista primero para los casos reconocidos (pronombre + predicado + foco guardado); si no matchea, cae al chat general — pero con el foco expuesto como una herramienta más de `query_company_memory`, nunca como texto crudo de historial para que el LLM "interprete". El LLM sigue sin poder inventar en ningún camino.
4. **Tipo de foco soportado en v1: solo `MISSION`** — es el único caso con un pedido concreto (el ejemplo real del usuario). `AGENT`/`OPPORTUNITY` como tipos de foco quedan para cuando haya un pedido real, mismo criterio de "no inventar generalidad sin uso" ya aplicado repetidas veces en este proyecto (alertas: 6 tipos pedidos, 2 implementados; estados de agente: 6 propuestos, 2 con señal real).

## Modelo de datos (Neo4j)

```cypher
(:Conversation {id:'MAIN'})-[:HAS_MESSAGE]->(:Message {id, role, content, createdAt})
```

- `Conversation {id:'MAIN'}` — nodo singleton, creado idempotentemente (mismo patrón que `Company {id:'AI-COMPANY'}`).
- `Message {id, role, content, createdAt}` — `role` es `"user"` o `"ceo"`. Se crea uno por cada mensaje entrante y uno por cada respuesta final, sin importar qué camino del router lo resolvió (consulta determinista, decisión, o chat general) — así el historial queda completo para auditoría, aunque solo una parte de las consultas actualice el foco.
- **Índice `RANGE` en `Message.createdAt` desde el día uno** — la sesión de profiling de Neo4j que hicimos antes de este diseño encontró que `latestTaskPerAgent()` trae *todas* las `AgentTask` de un agente y ordena en memoria por no tener este tipo de índice; no se repite ese error acá, donde el volumen de mensajes va a crecer sin límite natural.
- **El "foco actual"** vive como propiedades directas en el nodo `Conversation` (no un nodo aparte — es un valor mutable de "último estado", no un hecho histórico que valga la pena versionar):
  - `Conversation.lastMentionedType` — `"MISSION"` (único valor posible en v1).
  - `Conversation.lastMentionedIds` — lista de `missionId` strings.
  - `Conversation.lastMentionedAt` — timestamp, sin uso funcional en v1 (no hay expiración del foco), pero se guarda para permitir agregarla después sin migración.

## Flujo del router

`ChatIntentRouter.route(message)` gana un chequeo nuevo, ubicado **antes** de `detectQuery` (el matcheo de keywords existente) y **después** de `detectDecision` (una decisión real con `MISSION-<id>` explícito sigue teniendo prioridad absoluta, sin cambios):

1. **Detectar pronombre demostrativo**: el mensaje normalizado contiene `esas`/`esos`/`estas`/`estos`/`ellas`/`ellos` como palabra completa (boundary explícito, mismo criterio que ya usamos para `\bprueba` — evitar falsos positivos tipo "aprueba").
2. **Si no hay pronombre** → sigue el flujo actual sin cambios (decisión → `detectQuery` → chat general).
3. **Si hay pronombre**:
   a. **Sin foco guardado** (`Conversation.lastMentionedIds` vacío/no existe) → responde determinísticamente: *"No tengo claro a qué te referís — no mencioné ninguna misión todavía en esta conversación."* Sin tocar Ollama.
   b. **Con foco guardado, predicado reconocido** (mismo vocabulario que ya dispara `TEST_MISSIONS`/`FAILED_MISSIONS`/`MISSIONS_NEEDING_ATTENTION`: `\bprueba` → environment; `fallaron`/`fallidas`/`fallida` → status FAILED; `aprobacion`/`necesita` → status AWAITING_INVESTOR) → **resuelve consultando el dato real actual** de esos `missionId` puntuales en Neo4j (nunca el texto de la respuesta anterior, que puede estar desactualizado) y responde con el estilo "Correcto/Incorrecto, N de esas M misión(es)...". Sin tocar Ollama.
   c. **Con foco guardado, predicado no reconocido** → cae al chat general (`CeoService.chat`), con el foco disponible vía el topic nuevo `LAST_MENTIONED` de `query_company_memory` — fallback C: el LLM puede responder preguntas más libres, pero solo citando datos reales que consulta, nunca narrando sobre su propio historial de texto.

**Qué consultas actualizan el foco** (solo las que listan misiones, ya existentes): `MISSIONS_NEEDING_ATTENTION`, `FAILED_MISSIONS`, `TEST_MISSIONS`. Cada una, después de armar su respuesta de texto de siempre, hace `conversationMemory.setLastMentioned("MISSION", idsListados)`.

## Componentes nuevos

- **`ConversationMemoryService`** (nuevo, mismo patrón que `MissionMemoryService`/`OpportunityMemoryService`): `recordMessage(role, content)`, `recentMessages(limit)` (no usado por el LLM en v1 dado el enfoque C, pero expuesto para futuro/depuración), `setLastMentioned(type, ids)`, `lastMentioned()` → `Optional<LastMentioned(type, ids)>`.
- **`MissionMemoryService.findByIds(List<String> missionIds)`** (nuevo): `MATCH (m:Mission) WHERE m.id IN $ids RETURN ...` — una sola consulta batch, no N llamadas sueltas, para resolver el predicado sobre el foco.
- **`ChatIntentRouter`**: el chequeo de pronombre/predicado descrito arriba, más las llamadas a `setLastMentioned` en los 3 formatters de misiones ya existentes.
- **`CeoService`**: el enum `topic` de `query_company_memory` gana `LAST_MENTIONED`; su formatter en `ChatIntentRouter.answerMemoryTopic` describe el foco actual (o "no hay ninguna mención reciente" si está vacío).

## Fuera de alcance de esta ronda (documentado, no descartado)

- Tipos de foco además de `MISSION` (agentes, oportunidades) — sin pedido concreto todavía.
- Expiración del foco por tiempo (`lastMentionedAt` se guarda pero no se usa para invalidar).
- Resumir/truncar el historial de `Message` para mantenerlo acotado (v1 no pagina ni purga — mismo nivel de "no hay límite" que ya tienen `MissionMemoryService.findAll` con su límite fijo de 50, pero acá ni eso; si se vuelve un problema real se agrega paginación entonces, no antes).
- Multi-usuario/sesiones por pestaña — no existe la necesidad hoy.

## Testing

- `ConversationMemoryService`: sin test directo (integración con Neo4j, mismo criterio que el resto de los `*MemoryService` de este proyecto).
- `ChatIntentRouterTest`: casos nuevos con `ConversationMemoryService` mockeado —
  - pronombre + predicado reconocido + foco existente → respuesta determinista correcta, sin interactuar con `ceoService`.
  - pronombre + sin foco → mensaje de "no tengo claro", sin interactuar con `ceoService`.
  - pronombre + predicado no reconocido + foco existente → cae a `ceoService.chat`, y el topic `LAST_MENTIONED` del callback resuelve el foco real.
  - una consulta de misiones exitosa (p. ej. `TEST_MISSIONS`) llama a `conversationMemory.setLastMentioned("MISSION", ...)` con los ids correctos.
- Verificación en vivo (antes de dar la ronda por cerrada, mismo patrón que el resto de esta sesión): reproducir el ejemplo real completo — "¿Qué necesita mi aprobación?" → "pero esas están en prueba" — contra el chat real, confirmando `CHAT_INTENT_QUERY` (o el intent nuevo) en el log y **cero** `CEO_CHAT` para el camino feliz.
