# Misiones durables y corrección automática — diseño

**Fecha**: 2026-10-05
**Estado**: diseño aprobado por el fundador en conversación (secciones 1 a 4, enfoque A); pendiente de revisión de este spec.
**Base**: bloque 4 de la hoja de ruta (`2026-10-01-development-group-fase1-roadmap.md`, spec `2026-10-01-development-group-fase1-design.md`
§9) y el núcleo del bloque 7 (§8). Aria v1 (`2026-10-03-aria-v1-design.md`), QA casos de uso (`2026-10-05-qa-casos-de-uso-design.md`).
Rama `misiones-durables` (sobre `neo-cierre`).

## Objetivo

Que un reinicio de `company-core` (redeploy, caída, reinicio de la máquina) no corte una misión del Development Group, y
que una misión que falla no se abandone: el equipo la corrige solo contra las HU aprobadas, sus criterios de aceptación y
sus escenarios, con frenos de reintentos y de costo. Hoy las misiones viven en hilos en memoria; tras un reinicio quedan
congeladas en un estado de trabajo y hay que corregirlas por Cypher (pasó con `MISSION-1790905978528`).

**Éxito**: el fundador reinicia `company-core` a mitad de una misión y la misión termina igual (`VERIFIED` o `FAILED` según
su código) con una línea en el chat que dice que se retomó; y una misión con validación `FAILED` se corrige sola hasta
`VERIFIED` o queda `BLOCKED` con el motivo.

## Decisiones del fundador (2026-10-05)

1. **Enfoque A**: retomar a partir de lo que ya se guarda (estado, ronda, incremento, requisitos, plan de Neo, tareas con su
   commit, git). Sin un sistema nuevo de etapas (el `DevelopmentStage` del spec §9 queda descartado por ahora).
2. **Una interrupción no es una falla**: estado nuevo `INTERRUPTED`; nunca `FAILED` por un reinicio (no cuenta para el corte
   del orquestador, ni manda el correo de falla, ni ensucia las métricas).
3. **Orden de recuperación**: primero las huérfanas, después las `INTERRUPTED`, al final las que fallaron.
4. **Las que fallan se retoman solas**: el equipo vuelve a leer las HU aprobadas, valida criterios de aceptación y escenarios
   y corrige.
5. **Frenos**: `MAX_AUTO_CORRECTIONS` (3 por incremento) y `MAX_MISSION_COST_USD` (US$ 5 por misión), editables en Settings;
   2 rondas seguidas sin avance también frenan. Al frenar: `BLOCKED`, esperando al fundador.
6. **Datos de prueba borrados** (hecho el 2026-10-05): las 19 misiones `TEST`, sus workspaces, requisitos huérfanos y las 51
   dependencias. No hace falta regla para las `FAILED` viejas: la corrección automática aplica desde la activación. Se
   conservan los `ModelUsage` (gasto real).

**Fuera de este spec**: cola de pedidos (`DevelopmentRequest`) e incrementos encadenados (bloque siguiente, se apoya en
este); estrategias nuevas con búsqueda web y log de auditoría por error (resto del bloque 7); retomar solas las misiones de
discovery y de los equipos de análisis (sus agentes están apagados: quedan `INTERRUPTED`).

## 1. Qué se recupera, en qué orden y cuándo

| Caso | Cómo lo reconoce Java | Qué hace |
|---|---|---|
| **Huérfana** | Misión en un estado de trabajo (`CREATED`, `PLANNING`, `DELEGATING`, `RESEARCHING`, `WAITING_AGENT_RESULTS`, `EVALUATING`, `CONSOLIDATING`, `EXECUTING`) que no está en el registro de misiones vivas | Development Group (`TEAM-DEVELOPMENT`): se **retoma** (§2). Discovery y otros equipos: pasa a **`INTERRUPTED`** |
| **`INTERRUPTED`** | Estado nuevo, con `interruptedAt` y el motivo | Se relanza a pedido ("reanuda MISSION-X", botón en Missions, `POST /api/company/missions/{id}/resume`). No consume `MAX_EVIDENCE_ROUNDS` |
| **Falló** | Development Group con requisitos aprobados y: misión `FAILED`, o `AWAITING_INVESTOR` con la última validación `FAILED` | **Corrección automática** (§3) |

**No entran en la corrección automática** (son decisiones del fundador): validación `UNVALIDATED` (dependencias pendientes 🔴,
sandbox caído), `CANCELLED`, misiones sin requisitos aprobados, `AWAITING_SPEC_APPROVAL`, `COMPLETED`, `BLOCKED`.

**Orden**: huérfanas → `INTERRUPTED` retomables → falladas. Dentro de cada grupo, la más antigua primero (`createdAt`).
Corre **una misión de desarrollo a la vez** desde la recuperación: si hay una misión de desarrollo viva (lanzada por el
fundador o por la recuperación), la recuperación espera. Las misiones que lanza el fundador no se encolan (sin cambios).

**Cuándo corre** (`MissionRecoveryService`):
- Al arrancar (`ApplicationReadyEvent`, después de `CompanyMemoryInitializer`): primero los agentes en `WORKING` pasan a
  `IDLE`; después se recorre el orden de arriba.
- Al terminar cada misión (gancho en `MissionExecutor.advanceMission` para `AWAITING_INVESTOR`, `FAILED` y `BLOCKED`, en un
  hilo propio, como el del orquestador): si quedó "falló", se evalúa la corrección; además se despacha la siguiente
  pendiente.
- Chequeo `@Scheduled` cada 5 minutos como red de seguridad.

Evento por cada acción (`EMPRESA_MISSION_RESUMED`, `EMPRESA_MISSION_INTERRUPTED`, `EMPRESA_MISSION_CORRECTION_STARTED`,
`EMPRESA_MISSION_BLOCKED`), línea en "dame un status" y correo al quedar `BLOCKED`.

## 2. Retomar una huérfana del Development Group

**Registro de misiones vivas** (`MissionExecutor`): un `Set` concurrente; `submit` agrega la misión al lanzar el hilo y la
quita en un `finally`. Al arrancar está vacío, así que toda misión en estado de trabajo es huérfana.

**`ResumePoint`** (función pura; paquete nuevo `com.aicompany.core.recovery`, junto con `CorrectionReport` y `MissionRecoveryService`): recibe un `MissionSnapshot` armado en Java (estado,
`evidenceRound`, `buildingIncrement`/`builtIncrement`, última versión de requisitos con su estado y si tiene un ajuste
reclamado, si hay plan completado para la ronda en curso, tareas de la ronda con estado y `commitSha`) y devuelve una etapa:

| Etapa | Condición | Qué hace al retomar |
|---|---|---|
| `REQUIREMENTS` | Sin versión de requisitos, o la última con un ajuste reclamado (`decisionClaimedAt` y `pendingAdjustment`) | Aria escribe de nuevo; para un ajuste, con la misma instrucción. Requiere guardar `pendingAdjustment` en la versión al reclamar un `ADJUST` (`RequirementsMemoryService.claimDecision`) |
| `AWAIT_APPROVAL` | Última versión `DRAFT` sin reclamar y misión en un estado de trabajo | `awaitApproval` (pasa a `AWAITING_SPEC_APPROVAL`, correo), sin modelo |
| `PLAN` | Requisitos aprobados y sin tarea `PLANNING` `COMPLETED` para la ronda en curso | Neo planifica la misma ronda y el mismo incremento (`currentIncrement`); en una ronda de evidencia, el camino de siempre (`reexecuteTeamMission`) con el pedido original |
| `GENERATE` | Plan para la ronda y alguna tarea `WORK` de la ronda sin `COMPLETED`+`commitSha` | La estrategia sigue desde la primera tarea sin commit |
| `VERIFY` | Todas las tareas `WORK` de la ronda con commit | La estrategia salta la generación y verifica desde el último commit |

La ronda en curso es `Mission.evidenceRound`. El pedido de una ronda de evidencia sale de la última `Decision`
`REQUEST_MORE_EVIDENCE` guardada; el de un incremento, de `buildingIncrement`; el de una corrección, del `CorrectionReport`
guardado con la ronda (§3). Retomar nunca gasta otra ronda ni otro número de ronda.

**Retomar la generación sin repetir trabajo** (`DevelopmentTeamStrategy` + `DevelopmentWorkspaceService`):
1. **Workspace limpio**: antes de generar, `resetTo(missionId, sha)` (`git reset --hard <sha>` + `git clean -fd`) al último
   commit registrado en Neo4j (el `commitSha` más reciente de las tareas de la misión, o el del scaffold). Un commit que
   llegó a git pero no a Neo4j se descarta y su tarea se repite. Sin workspace (o sin el commit registrado) → `BLOCKED` con
   el motivo.
2. **Lo ya hecho se salta**: el scaffold y cada tarea `WORK` de la ronda en `COMPLETED` con `commitSha` alcanzable desde
   `HEAD` entran directo a `committed`; sus `packages` salen del `DevelopmentResult` guardado como resultado de la tarea.
   Lo mismo para los commits de Forjai de la ronda (`-API-ROUTES`, `-DEPENDENCIES`).
3. **`createTask` no pisa lo terminado**: hoy `MERGE … SET t.status='PENDING'` resetea una tarea ya completada. Pasa a poner
   `PENDING` solo al crear (`ON CREATE SET`) o si la tarea no está `COMPLETED`.
4. **La tarea que estaba `RUNNING` se repite desde cero** (su llamada al modelo se perdió).
5. **La verificación siempre se repite completa** (chequeos estáticos, sandbox, autofix, correcciones de compilación y
   diagnóstico 360), con sus contadores desde cero.

En una misión que no se cortó no hay nada que saltar: el flujo es exactamente el de hoy.

**Discovery y equipos de análisis**: una huérfana pasa a `INTERRUPTED` (`advanceMission`, sin correo de falla). "Reanuda
MISSION-X" la relanza como la primera vez (`executeAsync` para la ronda 0; `reexecuteAsync` con el pedido guardado para una
ronda de evidencia), con los chequeos de agentes encendidos de siempre. Una `INTERRUPTED` del Development Group (p. ej. por
workspace ausente antes de este cambio) se reanuda con `ResumePoint`.

## 3. Corrección automática de las que fallan

**Disparo**: §1 "Falló". La misión no pide decisión todavía: `MissionRecoveryService` evalúa los frenos y, si pasan, lanza
una ronda de corrección.

**`CorrectionReport`** (función pura): desde la última tarea `VALIDATION` de la misión (`staticChecks`, `sandboxResult`,
reporte de cobertura) arma:
- escenarios sin test aprobado (con el test que falló o se saltó y su salida);
- chequeos estáticos fallidos con su detalle;
- el paso del sandbox que falló con el final de su salida;
- errores de compilación (`CompilerErrorParser`) con archivo y línea;
- si la misión falló antes de tener código: el motivo guardado (requisitos inválidos de Aria, plan inválido de Neo, ningún
  commit).

Y un **conteo** de cosas que fallan (escenarios sin test aprobado + chequeos fallidos + pasos del sandbox fallidos + errores de
compilación distintos) para el freno de "sin avance". El texto se guarda con la ronda (`Mission.correctionRequests`, lista de
JSON por ronda) para poder retomarla.

**Ronda de corrección**: una ronda nueva sobre el mismo repositorio (`evidenceRound + 1`, ids `-R<n>`, CAS de
`claimNextRound`) que **no consume** `MAX_EVIDENCE_ROUNDS` (contador propio `Mission.autoCorrections`, por incremento:
`autoCorrectionsByIncrement`; `MissionService.evidenceRoundsUsed` descuenta incrementos y correcciones). Recibe:
- el bloque del incremento en curso (`RequirementsText.incrementBlock`: HU, criterios, escenarios);
- `CORRECCIÓN AUTOMÁTICA (Java)` con el `CorrectionReport`;
- la regla: "corrige hasta que todos los escenarios del incremento pasen; no borres, renombres ni debilites tests".

Corre por el camino de las rondas de equipo (`reexecuteTeamMission`): plan guardado si pasa la revalidación, si no Neo
replanifica; el reparto por agente lo hace `routeInvestorFeedback` como hoy (fallback: el reporte completo a cada uno). Si la
falla fue antes del código, la ronda repite esa etapa: Aria con el motivo del rechazo (sin requisitos aprobados) o Neo (sin
plan). Al terminar se verifica como siempre; si llega a `VERIFIED`, `AWAITING_INVESTOR` como hoy (y el incremento cuenta como
construido).

**Frenos** (Java, antes de lanzar cada ronda):

| Freno | Fuente | Default |
|---|---|---|
| Rondas automáticas del incremento en curso | policy `MAX_AUTO_CORRECTIONS` | 3 |
| Costo de modelos de la misión | policy `MAX_MISSION_COST_USD`, contra `ModelCostService.missionCost(id).totalUsd()` | 5 |
| Sin avance | el conteo del `CorrectionReport` no bajó en las 2 últimas rondas de corrección | fijo |

Si salta uno: `BLOCKED` (`advanceMission`, correo), con motivo, rondas hechas, costo y lo que sigue fallando. Las dos policies
nuevas entran a `PolicyKey`, al catálogo de `CompanyPolicyService` (> 0), a `types.ts` y a las etiquetas de Settings.

**Decisiones sobre una `BLOCKED`** (`recordDecision` la acepta, como `AWAITING_INVESTOR` y `FAILED`):
- `REQUEST_MORE_EVIDENCE` con la indicación del fundador → ronda con esa indicación antepuesta al `CorrectionReport`; no
  consume `MAX_EVIDENCE_ROUNDS` y pone en cero las correcciones del incremento.
- `APPROVE` → `COMPLETED`; `REJECT` → `CANCELLED`.

El orquestador trata `BLOCKED` e `INTERRUPTED` como "esperando al fundador" (ni terminada ni rota): no avanza ni cuenta para
el corte por 2 ciclos fallidos.

## 4. Chat, Command Center y documentación

- **Chat** (Java): "dame un status" agrega "Retomadas tras el reinicio: …", "En corrección automática: MISSION-X (ronda 2 de
  3)", "Bloqueadas (esperan tu indicación): …" e "Interrumpidas: … (\"reanuda <misión>\")"; comando de gobernanza "reanuda
  MISSION-X" (antes de las menciones); "¿cómo va MISSION-X?" muestra las rondas de corrección con su pedido.
- **Missions**: colores de `INTERRUPTED` y `BLOCKED` (`statusColor.ts`), motivo visible, botón "Reanudar" en `INTERRUPTED`;
  `BLOCKED` acepta las decisiones de siempre.
- **Borrar**: `INTERRUPTED` y `BLOCKED` se pueden borrar (`DELETABLE_STATUSES`).
- **Docs**: `docs/EVENTS.md` (eventos nuevos), `docs/ARCHITECTURE.md`, `docs/HISTORY.md`, `docs/FALTANTES.md` (misiones
  durables), y en `CLAUDE.md` la advertencia "antes de reiniciar" se reemplaza por cómo funciona la recuperación.

## 5. Errores

- La recuperación nunca impide el arranque: cada misión se recupera en su `try`; si falla, se anota (log, evento) y se sigue
  con la siguiente.
- Retomar o corregir usa `submit`, así que una excepción termina en `safeFail` → `FAILED` → la misión vuelve a evaluarse con
  sus frenos: una falla de infraestructura (sandbox o modelo caído) termina en `BLOCKED` por "sin avance" o por el contador,
  nunca en un bucle.
- Un reinicio durante una ronda de corrección retoma esa misma ronda (su pedido está guardado), sin gastar otra.
- Dos despachos simultáneos (arranque + fin de misión + chequeo) no lanzan dos veces la misma misión: el registro de misiones
  vivas se consulta y se llena de forma atómica, y la ronda nueva pasa por el CAS `claimNextRound`.

## 6. Pruebas

**Unitarias**: `ResumePoint` (cada fila de §2), `CorrectionReport` (cada fuente y el conteo), frenos (contador, costo, sin
avance), `MissionRecoveryService` (orden, una a la vez, discovery → `INTERRUPTED`, no toca `UNVALIDATED`/`CANCELLED`/sin
requisitos), `DevelopmentTeamStrategy` (salta tareas commiteadas con sus paquetes, limpia el workspace, sin workspace →
`BLOCKED`), `createTask` no pisa `COMPLETED` (verificación de la Cypher contra Neo4j real en solo lectura con `EXPLAIN`),
decisiones sobre `BLOCKED`, chat ("reanuda", status) y policies.

**En vivo** (con misiones de prueba nuevas, registradas en `docs/HISTORY.md`):
1. Arrancar una misión del Development Group y reiniciar `company-core` durante la generación: se retoma sin repetir las
   tareas commiteadas y termina.
2. Reiniciar durante la verificación: vuelve a verificar y termina.
3. Una misión que falla la validación: se corrige sola hasta `VERIFIED`, o queda `BLOCKED` con su motivo al agotar un freno.
