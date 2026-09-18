# Consultas nuevas del chat: actividad reciente, decisiones, misiones activas (Sub-proyecto B de "Company Chat completo") — diseño

**Fecha**: 2026-09-18
**Estado**: aprobado por el usuario en brainstorming, pendiente de plan de implementación.

## Contexto y objetivo

Continuación directa del Sub-proyecto A (`CompanyTools`, ya implementado y mergeado en esta rama). El usuario pidió, como parte de completar el "Company Chat" antes de seguir con nuevas misiones comerciales, que el chat también pueda responder:

- "¿Qué actividad reciente hay?" — hoy `ActivityMemoryService.recent(limit)` ya alimenta la pantalla Activity del Command Center, pero el chat no tiene ningún camino para consultarla.
- "¿Qué decisiones tomé?"/"¿por qué aprobamos esa misión?" — los nodos `Decision` reales existen (`(:Mission)-[:HAS_DECISION]->(:Decision {decision, reasoning, decidedAt})`) desde la ronda de "Decisión del inversionista humano", pero nunca se agregó ninguna consulta de listado sobre ellos — ni en el chat ni en ningún endpoint.
- "¿Qué misiones están activas?" — hoy solo existe un **conteo** agregado dentro de `COMPANY_STATUS` (`active = ...count()`), nunca un listado con los ids y su status real; las otras consultas de misiones (`MISSIONS_NEEDING_ATTENTION`/`FAILED_MISSIONS`/`TEST_MISSIONS`) filtran a subconjuntos estrictos que deliberadamente excluyen las misiones "en curso".

Ninguna de las tres es nueva infraestructura — mismo patrón exacto que los 11 métodos ya existentes de `CompanyTools`: formateo 100% determinista en Java, nunca delegado al LLM.

## Decisiones de diseño

### 1. Tres métodos nuevos en `CompanyTools`, mismo patrón que los 11 existentes

```java
String getRecentActivity();     // reusa ActivityMemoryService.recent(20), ya existente
String getRecentDecisions();    // nuevo: MissionMemoryService.recentDecisions(10)
String getActiveMissions();     // misiones PRODUCTION con status "activo" (mismo criterio que ya usa getCompanyStatus), listadas con id+status+progreso, no solo contadas
```

`CompanyTools` gana `ActivityMemoryService` como dependencia nueva de constructor (las otras cuatro — `MissionService`, `MissionMemoryService`, `OpportunityMemoryService`, `CustomerMemoryService`, `ConversationMemoryService`, `AppProperties` — ya existen de la ronda anterior).

`getActiveMissions` reusa exactamente el mismo criterio de "activa" que ya usa `getCompanyStatus` (`status` distinto de `AWAITING_INVESTOR`/`FAILED`/`COMPLETED`/`CANCELLED`, `environment=PRODUCTION`) — no se inventa un segundo criterio de "actividad" que pueda desincronizarse del conteo agregado. Setea el foco conversacional `type="MISSION"` con los ids listados, mismo criterio que `getPendingApprovals`/`getFailedMissions`/`getTestMissions`.

`getRecentDecisions` no toca el foco conversacional — un listado de decisiones históricas no es un conjunto sobre el que tenga sentido "aprobar las dos" después.

### 2. `MissionMemoryService.recentDecisions(int limit)` — método nuevo, y un modelo nuevo `DecisionActivity`

`DecisionResponse` (el modelo ya existente) no sirve para esto: se usa como confirmación de escritura de `recordDecision` y no carga `reasoning`. Nuevo record `com.aicompany.core.model.DecisionActivity(String decisionId, String missionId, String decision, String reasoning, Instant decidedAt)` — `decision` como `String` (no el enum `InvestorDecision`), mismo criterio que `ActivityItem.type()` (ya es texto plano, no un enum, porque solo se usa para mostrar, nunca para lógica de negocio).

```cypher
MATCH (d:Decision)
RETURN d.id AS id, d.missionId AS missionId, d.decision AS decision, d.reasoning AS reasoning, d.decidedAt AS decidedAt
ORDER BY d.decidedAt DESC
LIMIT $limit
```

### 3. Tres topics nuevos en `query_company_memory`, sin parámetro `id`

`RECENT_ACTIVITY`, `RECENT_DECISIONS`, `ACTIVE_MISSIONS` — se agregan al enum existente de topics (que ya tiene 11 valores tras el Sub-proyecto A: los 9 originales + `MISSION_DETAILS`/`OPPORTUNITY_DETAILS`), ninguno requiere `id`. `ChatIntentRouter.answerMemoryTopic` gana los 3 `case` nuevos, delegando a los métodos de `CompanyTools` de la decisión 1.

### 4. Tres keywords nuevas en `ChatIntentRouter.detectQuery`

Insertadas después del chequeo de `TEST_MISSIONS` (que ya usa el patrón `\bprueba`) y antes de `MISSIONS_NEEDING_ATTENTION`, en este orden:

```java
if (normalized.contains("actividad")) {
    return QueryIntent.RECENT_ACTIVITY;
}

if (normalized.contains("decision")) {
    return QueryIntent.RECENT_DECISIONS;
}

if (normalized.contains("mision") && normalized.contains("activa")) {
    return QueryIntent.ACTIVE_MISSIONS;
}
```

Ninguna de las tres colisiona con un keyword existente (verificado contra el archivo real: ningún chequeo actual usa "actividad", "decision" o la combinación "mision"+"activa"). `ACTIVE_MISSIONS` exige ambas palabras juntas (no "activa" sola) para no disparar sobre frases sueltas no relacionadas con misiones.

## Testing

- `CompanyToolsTest`: 3 casos nuevos (uno por método), con datos reales/vacíos, verificando el mismo criterio de "activa" que ya prueba `getCompanyStatus`.
- `ChatIntentRouterTest`: 3 casos nuevos de ruteo por keyword (mismo patrón que los existentes), más la actualización de `passesCompanyMemoryQueryCallbackThatResolvesAllKnownTopics` para cubrir los 3 topics nuevos.
- Sin test directo para `MissionMemoryService.recentDecisions` (convención ya establecida: los `*MemoryService` son integración Neo4j pura, sin test unitario directo).
- **Verificación en vivo pendiente**: con misiones/decisiones reales, confirmar que las 3 consultas nuevas devuelven datos reales y que `getActiveMissions` coincide exactamente con el conteo de `active` que ya muestra `COMPANY_STATUS` para los mismos datos.

## Fuera de alcance de esta ronda (documentado, no descartado)

- Exponer estos 3 topics nuevos con paginación o filtros adicionales (por agente, por rango de fechas) — no hay pedido concreto todavía.
- Un endpoint REST dedicado para decisiones (hoy solo existe como consulta de chat) — fuera de alcance, mismo criterio que el resto de las consultas deterministas del chat (no todas necesitan un endpoint espejo).
