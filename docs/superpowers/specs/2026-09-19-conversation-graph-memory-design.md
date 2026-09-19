# Memoria conversacional como grafo histórico por día (Sub-proyecto C de "Company Chat completo") — diseño

**Fecha**: 2026-09-19
**Estado**: aprobado por el usuario en brainstorming, pendiente de plan de implementación.

## Contexto y objetivo

Continuación del orden aprobado para "Company Chat completo" (A → B → E → C → D). El usuario pidió evaluar si la conversación con el CEO debería modelarse como un grafo histórico real en Neo4j — hoy, aunque ya vive dentro de Neo4j (`ConversationMemoryService`), estructuralmente es solo una lista enlazada plana más un puntero mutable, no un grafo semántico:

- `(:Conversation {id:'MAIN'})-[:HAS_MESSAGE]->(:Message {role, content, createdAt})` — un log lineal de todos los turnos, sin ninguna otra estructura.
- El "foco actual" (`lastMentionedType`/`lastMentionedIds`/`lastMentionedAt`) son propiedades escalares mutables del nodo `Conversation`, un solo puntero que se pisa en cada consulta nueva — nunca un registro histórico de qué se mencionó cada día.
- El historial que recibe el CEO (`recentMessages(20)`) es una ventana fija de texto plano, sin ningún vínculo hacia las entidades reales (`Mission`/`Customer`/`Decision`) de las que hablaban esos mensajes.

Alcance acordado con el usuario antes de escribir este spec (3 preguntas explícitas):
1. **Solo chat con el CEO** — el fundador no habla directo con agentes individuales (Sofia, Max, etc.); esa es una capacidad nueva distinta, fuera de alcance de esta ronda.
2. **Agrupación por día calendario**: un nodo `Chat` nuevo por cada día real, sin heurística de "cambio de tema" ni ventana de inactividad.
3. **Tres capacidades nuevas** que hoy no existen: preguntar por un día pasado, vincular cada día a las entidades reales de las que se habló, y una forma de memoria más allá de la ventana fija de 20 mensajes — resuelta explícitamente como recuperación bajo demanda (opción 1 de 3 evaluadas), no subiendo el límite fijo ni generando resúmenes automáticos vía LLM.

## Decisiones de diseño

### 1. Modelo de datos: `Chat` por día, entre `Conversation` y `Message`

```
(:Conversation {id:'MAIN'})
  -[:HAS_CHAT]->
(:Chat {date:'2026-09-19'})        // uno por día calendario, MERGE por fecha
  -[:HAS_MESSAGE]->
(:Message {id, role, content, createdAt})

(:Chat)-[:MENTIONS]->(:Mission)    // o (:Customer) — mismos 2 tipos que ya soporta el foco actual
```

`ConversationMemoryService.recordMessage` pasa de `MERGE (c:Conversation)-[:HAS_MESSAGE]->(msg)` a `MERGE (c:Conversation)-[:HAS_CHAT]->(chat:Chat {date:$today})-[:HAS_MESSAGE]->(msg)` — idempotente por construcción (la fecha actúa como clave dentro de la única `Conversation` global, no hace falta un constraint de unicidad nuevo dado el volumen esperado: como mucho unos pocos miles de nodos `Chat` a lo largo de años).

**El foco actual (`lastMentionedType`/`lastMentionedIds`) no se toca** — sigue siendo el único mecanismo para resolver pronombres demostrativos del turno actual ("esas"/"las dos"). Los edges `MENTIONS` nuevos son un rastro histórico *adicional*, nunca un reemplazo.

### 2. Migración de los mensajes ya existentes

Todos los `Message` reales ya persistidos cuelgan directo de `Conversation` (sin `Chat` intermedio). `ConversationMemoryService.migrateMessagesToChats()` (idempotente, llamada desde `CompanyMemoryInitializer` en el mismo `ApplicationReadyEvent` que ya corre otras migraciones de este tipo — `Mission.environment`, normalización de `Agent.status`): para cada `Message` sin un `Chat` intermedio, calcula la fecha desde su `createdAt`, hace `MERGE` del `Chat` correspondiente, y reengancha la relación (`(:Conversation)-[:HAS_MESSAGE]->(:Message)` directo se reemplaza por `(:Chat)-[:HAS_MESSAGE]->(:Message)`, borrando el edge viejo).

### 3. Consulta nueva: "¿qué hablamos el [día]?" — topic `CHAT_HISTORY`

Nuevo topic en `query_company_memory`, reusando el parámetro `id` ya existente en el schema — para este topic específico lleva una fecha, no un `MISSION-<n>` (la descripción del schema documenta esta diferencia por topic, mismo patrón ya usado para `MISSION_DETAILS`/`OPPORTUNITY_DETAILS`).

`ChatIntentRouter.detectQuery` gana una detección de fecha **deliberadamente acotada**: solo `"hoy"`, `"ayer"`, y una fecha explícita en formato `DD/MM` o `DD/MM/YYYY` — nada de nombres de día de la semana ("el lunes") ni expresiones relativas más complejas ("la semana pasada"). Verificado que ninguna de estas palabras/patrones colisiona con un keyword existente.

Resuelve a `MATCH (chat:Chat {date:$fecha})-[:HAS_MESSAGE]->(m) RETURN m.role, m.content, m.createdAt ORDER BY m.createdAt`, formateado 100% en Java (una línea `role: content` por mensaje) — mismo criterio anti-alucinación de todo el proyecto: el CEO solo parafrasea sobre el transcript real, nunca inventa. Sin `Chat` para esa fecha → `"No hubo conversación registrada ese día."` (determinista).

### 4. Consulta nueva: "¿en qué días hablamos de X?" — topic `MENTIONED_DATES`

Nuevo topic, `id` = el id real de la entidad (`MISSION-<n>` o el id de un `Customer`). Resuelve a `MATCH (chat:Chat)-[:MENTIONS]->(e {id:$id}) RETURN chat.date ORDER BY chat.date`.

Cada call-site que **ya** llama `conversationMemory.setLastMentioned(type, ids)` (`CompanyTools.getPendingApprovals`/`getFailedMissions`/`getTestMissions`/`getActiveMissions`/`getOpportunity`, `ChatIntentRouter.resolveMissionDetailsQuery`/`resolveOpportunityForMissionQuery`, el arranque de misión libre) gana además una llamada a `conversationMemory.recordChatMention(type, ids)`, que hace `MERGE (chat:Chat {date:hoy})-[:MENTIONS]->(e)` por cada id — reusa los mismos puntos de detección ya existentes, sin ninguna lógica nueva de "qué se mencionó". Sin ningún `MENTIONS` para ese id → `"No encontré menciones de esa entidad en el historial de chat."`.

### 5. Memoria más allá de la ventana fija de 20 mensajes: recuperación explícita, no un límite más alto

`recentMessages(20)` (la continuidad inmediata de charla que ya usa `CeoService.chat`) **no cambia**. La capacidad 3 del usuario se resuelve con las dos consultas de arriba: si el CEO necesita algo de hace días, lo pide vía `query_company_memory` con datos reales (mismo patrón de todo el proyecto: nunca todo en la ventana de contexto, siempre grounded vía tool-call bajo demanda) — en vez de subir el límite fijo (reintroduce el problema de costo/latencia de prompt que llevó a fijarlo en 20) o generar resúmenes automáticos vía LLM (le pediría al modelo "interpretar" en vez de solo parafrasear datos reales, exactamente lo que este proyecto evita en todos lados — ver "dame un status" en `CLAUDE.md`).

## Testing

- `ChatIntentRouterTest`: casos nuevos para el ruteo por keyword de `CHAT_HISTORY` (`"hoy"`/`"ayer"`/fecha explícita) y `MENTIONED_DATES` (frase + `MISSION-<id>` explícito), incluyendo que ninguno colisiona con un intent existente.
- `CompanyToolsTest`: casos nuevos para los formatters `getChatHistory(fecha)`/`getDaysMentioning(id)`, mismo patrón que los 14 métodos ya existentes.
- `CeoServiceCompanyMemoryTopicTest`: extender el test de "resuelve todos los topics conocidos" con los 2 topics nuevos.
- `ConversationMemoryService` (incluida la migración `migrateMessagesToChats`): sin test directo — integración Neo4j pura, mismo criterio ya establecido para el resto de los `*MemoryService`. Verificación en vivo: confirmar que los mensajes históricos reales quedan reenganchados bajo su `Chat` correcto, que un `Chat` nuevo se crea al primer mensaje de un día real, y que las dos consultas nuevas devuelven datos reales contra el historial real de esta sesión.

## Fuera de alcance de esta ronda (documentado, no descartado)

- Chat directo con agentes individuales (Sofia, Max, etc.), no solo con el CEO.
- Agrupación por tema/sesión en vez de por día calendario.
- Resúmenes automáticos (vía LLM) de días pasados.
- Resolución de fechas relativas más allá de "hoy"/"ayer"/fecha explícita (nombres de día de la semana, "la semana pasada", etc.).
- Foco conversacional con múltiples focos simultáneos (hoy sigue siendo un solo puntero mutable) — no pedido en esta ronda.
