# Forjai — Kafka Event Contract

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

EMPRESA_MISSION_DECISION_RECORDED (`MissionService.recordDecision`, `agentId="human"` siempre): se publica cada vez que el fundador humano registra una decisión real sobre una misión en `AWAITING_INVESTOR`/`FAILED` vía `POST /api/company/missions/{missionId}/decision`. `data: {decision, reasoning}` (`decision` uno de `APPROVE|REJECT|REQUEST_MORE_EVIDENCE`). Se publica siempre, además del `EMPRESA_MISSION_UPDATED` normal cuando la decisión sí cambia el estado de la misión (`APPROVE`→`COMPLETED`, `REJECT`→`CANCELLED`) — ver "Decisión del inversionista humano" en `CLAUDE.md`.

EMPRESA_MISSION_EVIDENCE_ROUND_STARTED (`MissionExecutor.reexecuteAsync`, `agentId="human"`): se publica al arrancar una ronda de evidencia tras un `REQUEST_MORE_EVIDENCE` aceptado (dentro de `MAX_EVIDENCE_ROUNDS`). `data: {evidenceRound, reasoning}`.

EMPRESA_CUSTOMER_REGISTERED, EMPRESA_SALE_RECORDED, EMPRESA_EXPENSE_RECORDED, EMPRESA_CORRECTION_RECORDED (`FinanceService`, `agentId="human"` siempre; spec `2026-09-27-finanzas-costos-ganancias-design.md`): se publican cuando el fundador registra un cliente, una venta, un gasto o una corrección desde el Command Center (`/api/company/finance/**`). `missionId` solo si el registro tiene misión. `data`: `{customerId, name}`, `{saleId, revenueUsd, costUsd, environment}`, `{expenseId, amountUsd, environment}` y `{correctionId, targetId, revenueAdjustmentUsd, costAdjustmentUsd, reason}`.

EMPRESA_PRODUCT_CREATED, EMPRESA_PRODUCT_UPDATED, EMPRESA_PRODUCT_STATUS_CHANGED (`ProductService`, `agentId` = actor: `human`, el agente que lo hizo, o `system` para las automatizaciones; spec `2026-09-28-catalogo-productos-design.md`): alta, edición y cambio de estado de un producto del catálogo. `data`: `{productId, name, kind}`, `{productId}` y `{productId, from, to}`.

EMPRESA_MODEL_DOWN, EMPRESA_MODEL_UP (`ModelHealthService`, `agentId="system"`; spec `2026-09-28-salud-de-modelos-design.md`): un modelo remoto dejó de responder (2 fallos de disponibilidad seguidos) o volvió (el ping cada 30 s respondió). `data`: `{model, error, affectedAgents}` y `{model, downSince}`. Mientras está caído, sus agentes trabajan con su suplente local.

EMPRESA_ORCHESTRATOR_STARTED, EMPRESA_ORCHESTRATOR_CHOSE, EMPRESA_ORCHESTRATOR_BUILDING, EMPRESA_ORCHESTRATOR_READY, EMPRESA_ORCHESTRATOR_FAILED, EMPRESA_ORCHESTRATOR_STOPPED (`ProductOrchestrator`, `agentId="orchestrator"`, sin `missionId`; spec `2026-09-28-orquestador-de-producto-design.md`): pasos del ciclo de producto autónomo. `data`: `{runId}`, `{runId, productId, reason}`, `{runId, productId, missionId, teamId}` y `{runId, detail}` para los tres finales (`STOPPED` = el fundador pausó o retiró el producto).

EMPRESA_API_KEY_UPDATED (`ApiKeyService`, `agentId="human"`): el fundador cambió la key de un proveedor de modelos desde Settings, después de que NVIDIA la aceptara. `data: {provider}`. Nunca lleva la key ni parte de ella.

EMPRESA_MISSION_DELETED (`MissionService.delete`, `agentId="human"` siempre): se publica cuando el fundador borra una misión terminada (`AWAITING_INVESTOR`/`FAILED`/`COMPLETED`/`CANCELLED`) vía `DELETE /api/company/missions/{missionId}` — limpieza de misiones de prueba. `data: {previousStatus}`. Nunca se publica para `MISSION-001` (protegida), una misión en curso, ni una con clientes/ventas reales (esas se rechazan antes de borrar). Es el último evento de ese `missionId`: su historial previo en Kafka queda, pero ya no existe en Neo4j.

EMPRESA_TEAM_PLAN_CREATED (`TeamWorkPlanner.plan`, `agentId` = líder del equipo): el plan del líder de una misión con `teamId` pasó `TeamPlanValidator`. `taskId` = `<missionId>-<LÍDER>-PLAN`. `data: {teamId, tasks}` — ver "Misiones por equipo" en `CLAUDE.md`.

EMPRESA_TEAM_PLAN_REJECTED (`TeamWorkPlanner.plan`, `agentId` = líder): un intento de plan fue rechazado (validación determinista o respuesta no parseable) y se reintenta con corrección. `data: {attempt, errors}`. Tras 3 intentos rechazados la misión termina en `FAILED` (no hay plan por defecto).

EMPRESA_TASK_COMMITTED (`DevelopmentTeamStrategy`, `agentId` = autor del commit): la tarea WORK de un agente de Engineering quedó registrada como un commit real en el workspace de la misión. `data: {commitSha, files}`. Se publica antes del `EMPRESA_TASK_COMPLETED` de esa tarea; si el commit falla, la tarea termina en `EMPRESA_TASK_FAILED` y este evento no se publica.

EMPRESA_STATIC_VALIDATION_COMPLETED (`DevelopmentTeamStrategy`, `agentId` = validador del plan, capability `QA`): terminó la validación estática (chequeos deterministas + revisión del validador). `data: {validationStatus, failedChecks}` — `validationStatus` ∈ `VERIFIED|STATICALLY_VALIDATED|UNVALIDATED|FAILED`, calculado por Java. Solo `VERIFIED` implica que compiló, pasó ≥1 test y arrancó en el sandbox.

EMPRESA_SANDBOX_VERIFICATION_COMPLETED (`DevelopmentTeamStrategy`, `agentId` = `sandbox`, `taskId` = la tarea VALIDATION): el `sandbox-runner` terminó un job `VERIFY` sobre el HEAD del workspace de la misión. `data: {overall, testsPassed, testsFailed}` — `overall` ∈ `PASS|FAIL`. No se publica si el runner no respondió ni si los chequeos deterministas fallaron (el sandbox no corre).

EMPRESA_DEPENDENCY_REQUESTED (`DependencyService`, `agentId` = quien pidió el paquete): un paquete (pedido o transitivo) no pasó la política y quedó `PENDING_APPROVAL` (🔴). `data: {dependency, reasons}` — `dependency` = `ECOSISTEMA:nombre@versión`.

EMPRESA_DEPENDENCY_APPROVED (`DependencyService` con `approvedBy=policy`, o `DependencyController` con `agentId=human` y `approvedBy=founder`): el paquete quedó aprobado y promovido a la caché. `data: {dependency, approvedBy}`.

EMPRESA_DEPENDENCY_REJECTED (`DependencyController`, `agentId=human`): el fundador rechazó el paquete. `data: {dependency}`.
