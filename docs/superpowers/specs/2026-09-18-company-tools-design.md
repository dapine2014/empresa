# `CompanyTools`: consultas con parámetros reales para el chat (Sub-proyecto A de "Company Chat completo") — diseño

**Fecha**: 2026-09-18
**Estado**: aprobado por el usuario en brainstorming, pendiente de plan de implementación.

## Contexto y objetivo

El usuario propuso una arquitectura completa de "Company Chat" como interfaz oficial única de Forjai (Query/Command/Strategy, una capa `CompanyTools`, contexto conversacional rico, contacto real a prospectos, CEO en NVIDIA) y pidió congelar nuevas misiones/features comerciales hasta cerrarla. Se decompuso en 5 sub-proyectos independientes, en este orden acordado: **A** (esta ronda) → B (consultas nuevas: actividad, decisiones, misiones activas) → E (CEO en NVIDIA, spec ya aprobado, solo falta plan) → C (contexto conversacional rico) → D (contacto real a prospectos por email, nivel 🔴).

Auditoría contra el código real (no contra la percepción del usuario, que databa de bugs ya corregidos en esta misma sesión): la mayoría del checklist de "Company Chat completo" ya existe y funciona — `COMPANY_STATUS`, `AGENT_STATUS`, detalles de misión, oportunidades con prospectos, "contactalo" informativo, comandos de gobernanza que ejecutan de verdad (`aprueba las dos`), foco conversacional. El problema real de arquitectura, confirmado leyendo `ChatIntentRouter`/`CeoService`: `answerMemoryTopic(String topic)` ya es un despachador único usado tanto por el atajo determinista de keywords como por la herramienta `query_company_memory` del CEO — pero **no soporta parámetros**. Por eso cada consulta que necesita un id (`resolveMissionDetailsQuery`, `resolveOpportunityForMissionQuery`) tuvo que resolverse aparte, con su propio chequeo en `resolve()`, y **nunca quedó disponible para el chat general del CEO** — si la pregunta del usuario no matchea exactamente el keyword determinista, el CEO no tiene forma de pedir el detalle de una misión o de una oportunidad puntual vía la herramienta, y mantiene el riesgo de alucinar que este proyecto lleva evitando desde el principio.

Este documento es el resultado de una sesión de `superpowers:brainstorming` (path arquitectural) — cada decisión de abajo fue presentada como alternativas al usuario y aprobada explícitamente antes de escribir este spec.

## Decisiones de diseño

### 1. `CompanyTools`, clase nueva, solo consultas (nunca comandos de gobernanza)

Se evaluaron 3 enfoques: (1) mínimo, agregar un parámetro `id` al `topic` actual sin ninguna clase nueva; (2) `CompanyTools` nueva, solo consultas; (3) `CompanyTools` con consultas y comandos de gobernanza juntos, fiel al diagrama original del usuario. Se descartó (1) por no dar la interfaz con nombres que pedía el usuario, y (3) porque mezclaría lectura (formateo determinista) con escritura (gobernanza real vía `MissionService`) en una sola clase — contra el criterio ya establecido en este proyecto de mantener responsabilidades separadas (`OpportunityMemoryService` vs `CustomerMemoryService` vs `MissionService`, cada uno con un dominio acotado). El usuario aprobó explícitamente el (2).

`com.aicompany.core.service.CompanyTools` (nueva), inyectada en `ChatIntentRouter` y en `CeoService` (mismo patrón de constructor con dependencias explícitas que ya usa el resto del proyecto — nunca un service locator). Reemplaza el `switch` de `ChatIntentRouter.answerMemoryTopic` y toda la lógica de formateo que hoy vive inline ahí (`formatAgentStatus`, `formatMissionsNeedingAttention`, `formatFailedMissions`, `formatTestMissions`, `formatOpportunities`, `formatLeads`, `formatCompanyProfit`, `formatCompanyStatus`, `formatLastMentioned`, `formatMissionDetails`, `formatOpportunityWithCandidates`) — todos migran **sin cambiar su comportamiento observable**, solo de ubicación.

Métodos de `CompanyTools` (todos devuelven un `String` ya formateado en español — mismo criterio ya probado en este proyecto: contar/enumerar/formatear un estado real es una tarea determinista, nunca se le pide al LLM que la arme, evita el bug ya documentado de "dame un status" donde el CEO rellenaba placeholders inventados):

```java
String getCompanyStatus();
String getAgentStatus();
String getPendingApprovals();      // AWAITING_INVESTOR estricto, PRODUCTION
String getFailedMissions();        // FAILED estricto, PRODUCTION
String getTestMissions();          // environment=TEST, cualquier status
String getOpportunities();         // lista global, sin id — sin cambios
String getOpportunity(String missionId);   // NUEVO soporte de parámetro real
String getProspects();             // leads globales — sin cambios
String getProspect(String candidateId);    // NUEVO: un prospecto puntual por id
String getFinancialStatus();
String getMission(String missionId);       // NUEVO soporte de parámetro real
String getLastMentioned();
```

**Efectos secundarios se quedan dentro de los métodos, no se mueven al llamador**: `getPendingApprovals`/`getFailedMissions`/`getTestMissions`/`getOpportunity` ya setean el foco conversacional (`conversationMemory.setLastMentioned(...)`) como parte de su lógica actual — esto se conserva tal cual dentro de `CompanyTools`, para que el foco quede consistente sin importar si la pregunta se resolvió por el atajo de keywords o por la herramienta del CEO (ya funciona así hoy, porque ambos caminos llaman al mismo método; con esta refactorización se preserva esa garantía, no se relaja).

### 2. La herramienta `query_company_memory` gana un parámetro `id` opcional, y dos topics nuevos

Se evaluó agregar un tool nuevo separado (`query_company_entity(type, id)`) contra extender el tool único existente — se eligió extender el existente: un solo tool con un enum de topics restringido es más simple de razonar para el modelo que dos tools con semántica solapada, y es el patrón que ya usa este proyecto (`empresa.md` nunca expone Cypher libre ni multiplica herramientas sin necesidad).

`CeoService.COMPANY_MEMORY_TOOLS`: el schema del tool gana un parámetro `id` (`"type": "string"`, no `required` — solo se usa cuando `topic` lo necesita). Dos topics nuevos en el enum: `MISSION_DETAILS` (requiere `id`=missionId) y `OPPORTUNITY_DETAILS` (requiere `id`=missionId — la Opportunity se busca por `missionId`, no por su propio `Opportunity.id`, mismo criterio que `OpportunityMemoryService.findByMissionId` ya usa; el modelo nunca necesita conocer el id interno `MISSION-X-OPPORTUNITY`). La descripción del tool le aclara al modelo qué topics necesitan `id` y cuáles no. `parseCompanyMemoryTopic`/`detectInlineCompanyMemoryTopic` (los dos parsers ya existentes, uno para `tool_calls` reales y otro para la red de seguridad de `qwen2.5-coder` que a veces no puebla `tool_calls`) se extienden para extraer también `id` cuando está presente, sin cambiar su forma general.

`ChatIntentRouter.answerMemoryTopic(String topic)` pasa a `answerMemoryTopic(String topic, String id)` (nueva firma; `id` puede ser `null` para los topics que no lo necesitan) y delega a `CompanyTools` según el topic:

```java
case "MISSION_DETAILS" -> companyTools.getMission(id);
case "OPPORTUNITY_DETAILS" -> companyTools.getOpportunity(id);
// resto de topics: sin cambios, delegan a CompanyTools.getX() sin id
```

### 3. `resolveMissionDetailsQuery`/`resolveOpportunityForMissionQuery` no se tocan, solo dejan de formatear inline

La detección determinista de "esta pregunta es sobre `MISSION-<id>`" (keywords + regex, ya correcta y ya testeada) **se queda en `ChatIntentRouter`, sin cambios de comportamiento**. Lo único que cambia es que `handleMissionDetailsQuery`/`handleOpportunityForMissionQuery` dejan de tener la lógica de formateo inline y en su lugar llaman a `companyTools.getMission(missionId)`/`companyTools.getOpportunity(missionId)`.

`resolveCustomerReference`/`handleCustomerReference` (la lógica de "contactalo") tampoco se tocan en su lógica de desambiguación (sustring de nombre, mayor confidence, aclaración) — pero para obtener el dato puntual ya formateado de un prospecto, usan `companyTools.getProspect(candidateId)` en vez de llamar directo a `opportunityMemory.findCandidatesByIds` + formatear inline.

## Testing

- `CompanyToolsTest` (nueva): un caso por método, verificando que cada uno delega correctamente a los `*MemoryService`/`ConversationMemoryService` correspondientes y produce el mismo texto que hoy producen los formatters existentes (mockeando los servicios, mismo patrón que `ChatIntentRouterTest`).
- `ChatIntentRouterTest`: los casos existentes que hoy verifican el contenido exacto de las respuestas (`routesCompanyStatusQueryToADeterministicAggregateSnapshot`, etc.) se actualizan para mockear `CompanyTools` en vez de los `*MemoryService` directamente — verifican que `ChatIntentRouter` llama al método correcto de `CompanyTools`, no que reimplementa el formateo (la lógica de formateo ya la cubre `CompanyToolsTest`).
- `CeoServiceTest` (o el archivo que ya cubre el callback de `query_company_memory`): casos nuevos para `MISSION_DETAILS`/`OPPORTUNITY_DETAILS` con `id`, y confirmar que el parseo de `id` desde `tool_calls` funciona para ambos parsers (`parseCompanyMemoryTopic`/`detectInlineCompanyMemoryTopic`).
- **Verificación en vivo pendiente** (misma convención del resto del proyecto): con una misión real y una pregunta de chat general (sin keyword exacto) que fuerce al CEO a pedir `MISSION_DETAILS` o `OPPORTUNITY_DETAILS` vía tool-call, confirmar en el log `CEO_CHAT_TOOL_CALL topic=MISSION_DETAILS id=...` y que la respuesta trae el dato real, no alucinado.

## Fuera de alcance de esta ronda (documentado, no descartado)

- **Consultas nuevas** (actividad reciente, decisiones, lista genérica de misiones activas): Sub-proyecto B, ronda aparte — se agregan como métodos nuevos de `CompanyTools` cuando llegue esa ronda, sobre la base que deja esta.
- **Contexto conversacional rico** (`lastMissionId`/`lastOpportunityId`/`lastProspects` como objeto multi-campo): Sub-proyecto C — esta ronda no toca `ConversationMemoryService`/`LastMentioned` más allá de seguir usándolos tal cual existen hoy.
- **Contacto real a prospectos**: Sub-proyecto D — `getProspect`/`getProspects` siguen siendo puramente informativos, nunca ejecutan ni preparan un envío real.
- **CEO en NVIDIA**: Sub-proyecto E, spec ya aprobado por separado (`2026-09-18-nvidia-ceo-provider-design.md`) — `CompanyTools` no depende de qué proveedor de LLM esté detrás del CEO, así que esta ronda no tiene ninguna dependencia con esa.
