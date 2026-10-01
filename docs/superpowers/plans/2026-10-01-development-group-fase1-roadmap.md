# Development Group fase 1 — hoja de ruta de planes

**Spec**: `docs/superpowers/specs/2026-10-01-development-group-fase1-design.md`
**Rama**: `development-group-fase1`

El spec abarca varios subsistemas; se implementa en 9 planes, en este orden. Cada plan deja la suite verde
(`cd app && mvn test`, `cd sandbox-runner && mvn test`, `cd app/frontend && npm run lint && npm run build`) y se
escribe en detalle recién cuando el anterior está terminado, sobre el código real de ese momento.

| # | Plan | Spec | Depende de | Entrega verificable |
|---|------|------|-----------|---------------------|
| 1 | `2026-10-01-dg1-equipo.md` — el grupo de 10, `TEAM-DEVELOPMENT`, roles y capas, asignación dinámica, Vera con tests + revisión | §1, §4 (reglas de miembros) | — | Neo planifica solo con los agentes necesarios; Vera escribe tests y revisa; suite verde |
| 2 | `dg2-sandbox-seis-pasos` — startup separado de smoke, `testCases[]` desde TRX/JSON | §6 | — | `sandbox-runner` devuelve 6 pasos y tests con nombre |
| 3 | `dg3-generacion` — `ElidedCodeGate`, entrega por partes ante respuesta cortada | §5 | — | Archivos con `...` se reintentan; respuestas cortadas se completan en lotes |
| 4 | `dg4-misiones-durables` — `DevelopmentRequest`, cola, etapas con checkpoint, reconciliación al arrancar | §2, §9 (sin incrementos) | 1 | Una misión de desarrollo sobrevive un reinicio y se retoma |
| 5 | `dg5-aria` — `RequirementsSpec`, validador, aprobación de HUs, instrucciones, plan por incrementos | §3, §9 (incrementos), §11 (instrucciones) | 4 | Aria entrega HUs + BDD; el fundador aprueba o ajusta; los incrementos se encolan |
| 6 | `dg6-plan-dag-y-verificacion` — `dependsOn`/`scenarioIds`, DAG sin ciclos, `ScenarioCoverage`, nuevo `VERIFIED` | §4 (DAG), §7 | 1, 2, 5 | Ningún escenario sin dueño ni sin test aprobado |
| 7 | `dg7-correccion` — `CorrectionPlanner`, rondas hasta cero, estrategias con búsqueda web, `BLOCKED`, log | §8 | 4, 6 | Los errores se corrigen hasta cero o la misión queda `BLOCKED` con el log |
| 8 | `dg8-entrega-chat-ui` — `DeliveryResult`, chat, pantalla Missions, Settings | §10, §11 | 4–7 | Todo consultable en chat y Command Center |
| 9 | `dg9-aceptacion` — prueba en vivo (Hello World Flutter + reinicio), `HISTORY.md`, `CLAUDE.md` | Testing | 1–8 | Hello World `VERIFIED` por chat |
