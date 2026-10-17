# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Qué es esto

Proyecto "Forjai": una empresa real operada principalmente por agentes de IA. El fundador humano (Alexander / `dapine@gmail.com`) define visión y aprueba decisiones reservadas; los agentes investigan, proponen y ejecutan de forma autónoma dentro de las políticas. La implementación vive en `app/` (servicio `company-core`, Spring Boot / Java + SPA React) y `sandbox-runner/`.

## Qué documento leer

| Documento | Para qué |
|---|---|
| `docs/ARCHITECTURE.md` | **Referencia detallada del estado vigente** de cada subsistema (Development Group, Aria, QA, Diego/PostgreSQL, sandbox, dependencias, modelos remotos, finanzas, catálogo, orquestador, prospectos, chat). Leer la sección relevante antes de tocar un subsistema. |
| `docs/HISTORY.md` | El *por qué* y el *cómo se verificó*: decisiones del fundador, bugs reales, verificaciones en vivo. Consultar antes de re-verificar algo o cambiar una decisión no obvia. |
| `docs/FALTANTES.md` | Pendientes (marcar `[x]` al cerrarlos). |
| `docs/STATE.md`, `docs/EVENTS.md` | Estado del sprint; contrato de eventos Kafka. |
| `docs/superpowers/specs/` + `plans/` | Pares diseño/plan fechados por feature. **Mezclan implementado, reemplazado y pendiente** (p. ej. el ledger `2026-09-15` lo reemplazó Finanzas `2026-09-27`; el puente LEAD→cliente `2026-09-17` no existe). Verificar en el código. |
| `empresa.md` | Documento fundacional; su sección "Niveles de autonomía" dice qué requiere al fundador (🟢 automatizable / 🔴 fundador: contactar clientes, vender). |
| `EMPRESA_AI_NUEVO_TODO_EVIDENCE.md`, `Plan%20Maestro%20v0-2.md` | Roadmap vigente; plan maestro. |
| **Obsoletos** | `EMPRESA_AI_TODO.md` (roadmap viejo completo), `status.md`, `docs/KAFKA-BUILD-FIX.md`, `README.md` (nota de un parche MVP 0.7). No usarlos como estado actual. |

## Comandos

Backend dentro de `app/`, con `mvn` del sistema (sin wrapper). Bytecode Java 21 (`--release 21`); `maven-enforcer-plugin` exige JDK ≥ 21 y Maven ≥ 3.9.

```bash
cd app
mvn package                       # jar en target/company-core-0.1.0-SNAPSHOT.jar
mvn -DskipTests package
mvn test                          # suite unitaria (JUnit + Mockito)
mvn test -Dtest=MiClase#miMetodo  # un único test
mvn spring-boot:run               # servicio local en :8081 (NO compila ni sirve la SPA)
```

- No hay lint en el backend. Los tests mockean Neo4j, Kafka y Ollama (los `*MemoryService` no tienen test directo). Excepción: el aplicador de PostgreSQL usa Testcontainers 2; con Podman: `DOCKER_HOST=unix:///run/user/$UID/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true mvn test`.
- La integración real se verifica en vivo y se registra en `docs/HISTORY.md`. Healthcheck: `GET http://localhost:8081/actuator/health`.
- `sandbox-runner/` es un módulo Maven aparte: `cd sandbox-runner && mvn test`.

Frontend (`app/frontend/`, React + Vite + TS; no hay tests, se verifica con lint + build):

```bash
cd app/frontend
npm install
npm run dev      # :5173 con proxy a :8081
npm run build    # tsc -b + vite build → dist/ (el Dockerfile lo copia a src/main/resources/static)
npm run lint     # oxlint — único lint del repo
```

Docker (multi-stage: frontend node → Maven → jre). Neo4j, Ollama y Kafka (`storm-kafka:19092`) deben estar corriendo en la red `ai-company-net`; el compose define `company-core` y `sandbox-runner`.

```bash
docker network create ai-company-net   # una sola vez
docker compose build && docker compose up
```

Sandbox (una vez): `systemctl --user enable --now podman.socket`, `mkdir -p ~/forjai-sandbox-work`, `SANDBOX_RUNNER_TOKEN` en `.env`, `bash sandbox/build-images.sh`.

Las misiones del Development Group sobreviven un reinicio: al arrancar, `MissionRecoveryService` las retoma donde quedaron (las de discovery y otros equipos quedan `INTERRUPTED`: "reanuda MISSION-X"). Igual conviene no reiniciar a mitad de una llamada larga: esa llamada se repite.

## Convenciones que no se pueden romper

- **Determinismo sobre LLM**: todo lo que se puede decidir en Java (ruteo del chat, conteos, validación, contradicciones, finanzas, estado de validación) se decide en Java, nunca pidiéndole al modelo que se revise. Es el hilo conductor del proyecto.
- **Nunca combinar `format` y `tools` en una llamada al modelo** (`CeoService.rejectFormatCombinedWithTools`): por eso cada intento de tarea tiene un turno de decisión con `tools` y otro final con `format`.
- **Jackson 3**: `tools.jackson.*`, nunca `com.fasterxml.jackson.*`.
- **Sin `@Async`**: executors explícitos con `@Qualifier` + `CompletableFuture` (`missionOrchestratorExecutor`, `agentTaskExecutor` en `AsyncConfig`).
- **Errores HTTP**: ningún controller hace manejo fino; `IllegalArgumentException`/`IllegalStateException` → 500. No agregar manejo nuevo.
- **Kafka**: topic `EMPRESA_EVENTS` vía `CompanyEventPublisher`; todo `eventType` empieza por `EMPRESA_`. Solo productor.
- **Neo4j**: driver plano, Cypher a mano en los `*MemoryService`; constraints y seed idempotentes al arrancar (`CompanyMemoryInitializer`; `neo4j/init.cypher` es solo referencia del seed, sin constraints). Instancia compartida: ignorar labels ajenos (`Agente`, `Carrera`, `Vehiculo`).
- **Env vars solo siembran el primer arranque** (`ollama.*-model`, capital semilla, días del desafío): después mandan Neo4j (`Agent.model`, `CompanyPolicy`) y el Command Center.
- **Todo lo configurable se edita en el Command Center** y toda feature debe poder consultarse en el chat.
- **Rebrand "Forjai" solo de marca**: paquete `com.aicompany.core`, prefijo `EMPRESA_*`, `company-core`, `Company {id:'AI-COMPANY'}` no se tocan.
- **Secretos**: keys de modelos y claves de bases se cifran con `SecretCipher` (`FORJAI_SECRETS_KEY` solo en `.env`) y nunca salen por API/chat/eventos (solo los últimos 4 caracteres; errores redactados). Un mensaje de chat con una clave corta el turno sin llamar a ningún modelo.
- **`company-core` nunca ejecuta código generado**: lo hace `sandbox-runner` (Podman sin root, sin red, solo perfiles del catálogo, nunca comandos).
- Rarezas: `AgentResult.java` vive en `com/aicompany/core/` pero declara `package com.aicompany.core.agent.model`; `CeoService.executeAgentTask` tiene un bloque comentado de código muerto (no copiar lógica de ahí).

## Arquitectura en grande

Servicio REST monolítico (Spring Boot 4.1.1, Java 21) que orquesta agentes LLM, con memoria en Neo4j y eventos en Kafka. Detalle de cada pieza en `docs/ARCHITECTURE.md`.

- **Flujo de misión**: `MissionService.start` → `MissionExecutor` (hilo propio) → `AgentRuntime` / `DevelopmentRuntime` → `CeoService` (único punto que llama a un modelo). Estados `PLANNING → DELEGATING → WAITING_AGENT_RESULTS → EVALUATING → CONSOLIDATING → AWAITING_INVESTOR` (+ `AWAITING_SPEC_APPROVAL` en el Development Group; `INTERRUPTED` = cortada por un reinicio sin retomar, `BLOCKED` = un freno de la corrección automática: decide el fundador, ver `docs/ARCHITECTURE.md` "Misiones durables"). `MissionExecutor.advanceMission` es el **único** que persiste transiciones, publica a Kafka y manda correos; cada evento con misión queda en la bitácora (`MissionEventLog`). Una misión nunca llega sola a `COMPLETED`: decide el fundador (`APPROVE`/`REJECT`/`REQUEST_MORE_EVIDENCE` = ronda de evidencia, ids `-R<n>`). Un agente fallido no tumba la misión; si fallan todos, `FAILED`.
- **Discovery** (sin `teamId`): 5 tareas fijas en paralelo (`sales`, `product`, `finance`, `engineering`, `qa`) + `ContradictionDetector` + consolidación del CEO.
- **Misiones por equipo** (`teamId`): el líder planifica (`TeamWorkPlanner`), Java valida (`TeamPlanValidator`) y ejecuta por tipo (`AnalysisTeamStrategy` / `DevelopmentTeamStrategy`).
- **Development Group** (`TEAM-DEVELOPMENT`): Aria levanta el contrato del cliente (el fundador lo aprueba) y desde él escribe requisitos (HU con su ítem del contrato, épicas opcionales, escenarios `ESC-NN` e incrementos) y, en apps y juegos, Kael diseña la interacción o la mecánica; **Neo y Aria resuelven las contradicciones entre ambos (revisión conjunta)**; el fundador aprueba ambos juntos → Neo planifica → Forjai commitea el scaffold → cada agente genera su capa DDD en orden con gates deterministas y commit propio en `products.workspace-root/<missionId>/` → validación estática → sandbox (`restore → build → test → smoke`) con ciclo de corrección → `StaticValidationStatus.compute` decide `VERIFIED`/`FAILED`/`UNVALIDATED` (exige cobertura de todos los escenarios y que la suite no decrezca). Stacks habilitados: `FLUTTER_WEB_APP`, `DOTNET_APP` y `GODOT_DOTNET_GAME` (juegos 2D Godot con partidas simuladas; Kael los programa) (`DevelopmentPhase.CURRENT = 1`).
- **Diego / PostgreSQL** (paquete `database`): el fundador carga conexiones en Settings; los agentes solo ven el contrato `DB_<NOMBRE>_*`. El sandbox no tiene bases (los tests usan fakes): con el código `VERIFIED`, `DatabaseApplyService` aplica las migraciones en cascada por ambiente (TEST antes que PRODUCTION) y Java clasifica la falla como corrección (vuelve a Diego) o problema del ambiente (queda `VERIFIED` y se avisa al fundador).
- **Contrato de agentes**: `AgentResult` con JSON Schema (`AgentResultSchema.SCHEMA`) pasa por `AgentResultValidator` (reintenta) → `EvidenceValidationGate` (no reintenta) → `EvidenceBindingGate` (reintenta); el motivo del rechazo vuelve al prompt como `CORRECCIÓN DEL INTENTO ANTERIOR`.
- **Modelos**: Ollama local o proveedores remotos OpenAI-compatibles (`<proveedor>:<modelo>` en `Agent.model`; `OpenAiCompatibleClient`). `ModelHealthService` marca caídos y desvía al respaldo pago (`paidFallbackModel`) y luego al suplente local (`fallbackModel`). `ModelCostService` registra el costo por misión.
- **Chat** (`ChatIntentRouter`) (las preguntas de estado de un agente y la bitácora de una misión las responde Java): clasifica con regex en Java **antes** de tocar un modelo; el orden de las reglas importa (gobernanza → menciones `@Agente` → consultas deterministas → CEO con `query_company_memory`).
- **Datos del fundador vs. agentes**: clientes reales, ventas, gastos y correcciones (Finanzas, nada se edita ni borra) solo los carga el fundador; los agentes producen `LEAD`s y oportunidades. Contactar o vender es 🔴: los correos a prospectos se envían solo con aprobación del fundador.
- **Autonomía**: orquestador de producto (`ORCHESTRATOR_ENABLED`) y búsqueda diaria de prospectos (`PROSPECTING_ENABLED`), con policies versionadas (`CompanyPolicyService`); los agentes apagados (`Agent.enabled`) nunca reciben llamadas al modelo.

## Command Center (`app/frontend/`)

Interfaz principal del fundador; SPA servida como estáticos por `company-core`, polling con `@tanstack/react-query`.

- `api/types.ts` refleja a mano los records Java: mantenerlos sincronizados.
- `SpaController` reenvía una **lista explícita** de rutas a `index.html`: al agregar una pantalla en `App.tsx`, agregar la ruta también ahí.
- `statusColor.ts` centraliza el mapeo de estados a colores.

## Al implementar

- Cambios de comportamiento vigente → actualizar la sección correspondiente de `docs/ARCHITECTURE.md`; este archivo solo si cambian comandos, convenciones o el mapa general.
- Decisiones de alcance acordadas con el usuario, bugs reales y verificaciones en vivo → `docs/HISTORY.md`.
- Pendientes nuevos o cerrados → `docs/FALTANTES.md`.
