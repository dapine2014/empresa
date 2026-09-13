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
