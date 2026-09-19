# Chat con prospectos reales por misión (Sub-proyecto A) — diseño

**Fecha**: 2026-09-18
**Estado**: aprobado por el usuario en brainstorming, pendiente de plan de implementación.

## Contexto y objetivo

Reportado por el usuario probando en vivo: preguntar *"¿qué oportunidades
concretas tenemos en la misión MISSION-X y qué prospectos reales están
asociados a cada una?"* devolvió el párrafo completo de instrucciones de la
misión (la `description` cruda de `Opportunity`) y **ningún** prospecto —
pese a que la misión sí generó 7 `Customer{status:'LEAD'}` reales, con
nombre, descripción y fuente, verificados a mano con Cypher. Un follow-up
("contactalo") hizo que el CEO respondiera que no tiene acceso a datos
personales — técnicamente cierto (nadie se los dio), pero el chat nunca
intentó buscarlos en Neo4j, donde sí existen.

Causas confirmadas en el código real:

1. `QueryIntent.OPPORTUNITIES` (`ChatIntentRouter`) siempre llama a
   `opportunityMemory.listRecent(20)` — una lista global sin filtro de
   misión, ignorando cualquier `MISSION-<id>` mencionado en el mensaje.
2. `formatOpportunities` muestra `Opportunity.description`, que es
   literalmente la instrucción completa de la misión
   (`MissionExecutor.recordOpportunity(missionId, instruction)`), no un
   resumen.
3. Ningún formatter conecta `Opportunity` con sus `Customer{status:'LEAD'}`
   vía `HAS_CANDIDATE` — la mitad de la pregunta del usuario no tiene
   ningún camino de código que la responda.
4. El foco conversacional (`LastMentioned`) solo rastrea `type="MISSION"` —
   no hay forma de que un follow-up como "contactalo" resuelva contra un
   prospecto mencionado en el turno anterior.

Objetivo de esta ronda: que el chat muestre los prospectos reales de una
misión, ordenados por probabilidad, y que un follow-up sobre "ese
prospecto" resuelva contra el foco — sin inventar nunca un dato de contacto
que no existe. Esta ronda **no** construye ningún mecanismo de contacto
real (teléfono/email) — eso es una feature aparte, todavía sin diseñar.

Este documento es el resultado de una sesión de `superpowers:brainstorming`
(path arquitectural) — cada decisión de abajo fue presentada como
alternativas al usuario y aprobada explícitamente antes de escribir este
spec.

## Decisiones de diseño

### 1. `CustomerCandidate` gana `confidence`, el agente lo autoreporta

`AgentResult.CustomerCandidate` gana un 5º campo `confidence` (double,
0.0–1.0), mismo patrón que `AgentResult.confidence` ya usa a nivel de
resultado completo — no es un score calculado por el sistema, es el agente
el que lo reporta al identificar el candidato. `AgentResultSchema`'s
`CUSTOMER_CANDIDATE_ITEM_SCHEMA` lo agrega como campo `required`
(consistente con el resto de campos de ese objeto). El prompt
(`AgentRuntime.buildPrompt`) instruye explícitamente: `confidence` alto
solo para una empresa nombrada y verificable con fuente específica de esa
empresa; bajo para un segmento de mercado genérico (ver spec del
Sub-proyecto B para el resto de esa instrucción). `OpportunityMemoryService
.recordCandidate` persiste `confidence` en el nodo `Customer`.

### 2. `OPPORTUNITIES` con `MISSION-<id>` explícito trae prospectos ordenados

Sin cambios al comportamiento global (sin id explícito, sigue siendo la
lista de las 20 oportunidades más recientes, sin prospectos — no se
sobrecarga esa vista). Con un `MISSION-<id>` explícito en el mensaje, la
consulta se limita a la `Opportunity` de esa misión y trae además sus
`Customer{status:'LEAD'}` vía `HAS_CANDIDATE`, **ordenados por `confidence`
descendente**. La descripción de la oportunidad se sigue mostrando tal cual
(truncarla o resumirla mejor queda fuera de alcance de esta ronda — no fue
parte de lo que se aprobó, solo se discutió y se descartó explícitamente
seguir por ese camino en esta ronda).

### 3. Foco conversacional gana `type="CUSTOMER"`

`ConversationMemoryService`/`LastMentioned` ya soportan un `type` de
foco genérico (hoy solo se usa `"MISSION"`) — se usa el mismo mecanismo
para prospectos: al listar los prospectos de una oportunidad, se guarda
`LastMentioned("CUSTOMER", idsEnOrdenDeProbabilidad)`.

### 4. Nueva resolución de referencia a un prospecto ("contactalo", "el contacto de X")

Mismo patrón arquitectónico que la resolución de detalles de misión
(`ChatIntentRouter.resolveMissionDetailsQuery`, agregado en la ronda
anterior de esta sesión): un chequeo nuevo en `resolve()`, antes de las
consultas agregadas, que solo actúa si hay un foco `type="CUSTOMER"`
vigente.

- Si el mensaje menciona el nombre de uno de los prospectos en foco
  (coincidencia de substring, case-insensitive) → resuelve ese.
- Si no menciona ningún nombre y hay más de uno en foco → resuelve el de
  **mayor `confidence`** (primero en la lista, ya viene ordenada) y lo
  aclara explícitamente en la respuesta ("te muestro el de mayor
  probabilidad: X — avisame si te referías a otro").
- Si no hay ningún foco `type="CUSTOMER"` vigente → no hay nada que
  resolver, cae al chat general (nunca se adivina sin foco, mismo criterio
  que `detectDecision`/`resolveMissionDetailsQuery`).

La respuesta siempre muestra lo que existe de verdad (`name`,
`description`, `source`, `confidence`) y declara explícitamente cuando no
hay dato de contacto directo — nunca inventa teléfono/email. **No dispara
ninguna acción real** (no hay integración de telefonía/email construida
todavía) — es puramente informativo, mismo alcance que el resto de este
router.

## Testing

- `AgentRuntimeTest`/`AgentResultValidatorTest` (o donde corresponda):
  confirmar que `AgentResultSchema` sigue siendo válido con el campo nuevo.
- `ChatIntentRouterTest`: consulta de oportunidades con `MISSION-<id>`
  trae los prospectos ordenados por confidence; consulta sin id sigue
  igual que hoy (regresión); resolución de "contactalo" con un solo
  prospecto en foco; con varios (asume el de mayor confidence, lo aclara);
  sin foco (cae al chat general); mención explícita de un nombre entre
  varios en foco.
- `MissionExecutorTest`: el test existente que construye
  `AgentResult.CustomerCandidate` se actualiza con el campo nuevo.

## Fuera de alcance de esta ronda (documentado, no descartado)

- **Resumir/truncar `Opportunity.description`**: se discutió, no se
  aprobó para esta ronda — sigue mostrando la instrucción completa.
- **Contacto real (teléfono/email, integración de telefonía)**: feature
  aparte, mencionada por el usuario (Twilio/Pipecat) pero puesta en pausa
  antes de este spec — "contactalo" en esta ronda es solo informativo.
- **Calidad de los prospectos que generan los agentes** (que sean empresas
  reales y no segmentos de mercado): es el Sub-proyecto B, spec aparte.
