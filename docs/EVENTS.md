# AI Company — Kafka Event Contract

Regla obligatoria: todo `eventType` publicado por la empresa DEBE comenzar con `EMPRESA_`.

Topic por defecto: `EMPRESA_EVENTS`.

Envelope:
```json
{
  "eventId": "uuid",
  "eventType": "EMPRESA_TASK_STARTED",
  "timestamp": "2026-09-07T00:00:00Z",
  "companyId": "AI-COMPANY",
  "missionId": "MISSION-001",
  "taskId": "MISSION-001-SALES",
  "agentId": "sales",
  "data": {"status": "RUNNING", "result": "Agente iniciado."}
}
```

Eventos iniciales: EMPRESA_MISSION_CREATED, EMPRESA_MISSION_STARTED, EMPRESA_TASK_CREATED, EMPRESA_TASK_STARTED, EMPRESA_TASK_COMPLETED y EMPRESA_TASK_FAILED.

Eventos de transición de misión (`MissionExecutor.advanceMission`, uno por cada avance de la máquina de estados): EMPRESA_MISSION_UPDATED (`data.status` en `PLANNING|DELEGATING|WAITING_AGENT_RESULTS|EVALUATING|CONSOLIDATING|AWAITING_INVESTOR`) y EMPRESA_MISSION_FAILED (`data.status=FAILED`) — antes de esto, estas transiciones solo se escribían en Neo4j, nunca llegaban a Kafka. Verificado en vivo consumiendo el topic `EMPRESA_EVENTS` de punta a punta para una misión real.

EMPRESA_TASK_RETRY (`AgentRuntime.executeInternal`, `data.status=RETRYING`): se publica cuando el resultado de un agente es rechazado (por `AgentResultValidator` o porque `CeoService.executeAgentTask` no pudo parsear la respuesta de Ollama) y se reintenta con el motivo del rechazo como corrección — antes de esto, un solo rechazo tumbaba la tarea (y por la regla de fallo atómico, la misión entera) sin darle al agente oportunidad de corregirse. No se publica en el intento 1; solo a partir del intento 2. Máximo de intentos: `AgentRuntime.MAX_RESULT_RETRIES + 1` (hoy, 3).

Eventos de Evidence Acquisition (`CeoService.executeTool`, `EMPRESA_AI_NUEVO_TODO_EVIDENCE.md` §22), uno por cada llamada real a `search_web_evidence`:

- `EMPRESA_EVIDENCE_SEARCH_STARTED` — al recibir la query del modelo, antes de llamar a Serper. `data: {query}`.
- `EMPRESA_EVIDENCE_SEARCH_COMPLETED` — tras la búsqueda real. `data: {query, candidatesFound}` (candidatos crudos, antes de confirmarlos).
- `EMPRESA_EVIDENCE_VERIFIED` — por cada candidato que pasó `confirmReachable` (fetch real + `ClaimRelevanceChecker`). `data: {query, url, title}`.
- `EMPRESA_EVIDENCE_REJECTED` — por cada candidato descartado (no responde, redirect, TLS inválido, tamaño excedido, o contenido no relacionado). `data: {query, url, reason}` con el motivo exacto.

No se implementó `EMPRESA_EVIDENCE_CANDIDATE_CREATED` (sugerido en el doc de referencia): sería redundante con `candidatesFound` de `SEARCH_COMPLETED` y multiplicaría el volumen de eventos sin aportar información nueva — decisión documentada en el javadoc de `CeoService.executeTool`.

Verificado en vivo consumiendo `EMPRESA_EVENTS` de punta a punta para una misión real (5 agentes): 5 `SEARCH_STARTED`, 5 `SEARCH_COMPLETED`, 12 `VERIFIED`, 33 `REJECTED` — incluyendo un rechazo real por límite de tamaño de `WebPageFetcher` (2 MB) y varios por contenido no relacionado con la query.

EMPRESA_MISSION_REPLANNED (`MissionExecutor.replanFailedAgents`, `EMPRESA_AI_NUEVO_TODO_EVIDENCE.md` §22): se publica cada vez que un agente que agotó sus 3 intentos internos (`EMPRESA_TASK_RETRY` cubre esos) recibe una oportunidad más a nivel de misión de correr su tarea desde cero. `data: {replanAttempt, previousError}`. Máximo de intentos: `MissionExecutor.MAX_AGENT_REPLANS` (hoy, 1) — ver "Replanificación automática" en `CLAUDE.md`.
