# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Estado del repositorio

Proyecto "Forjai": una empresa real operada principalmente por agentes de IA. El fundador humano (Alexander / `dapine@gmail.com`) define visión y aprueba decisiones reservadas; los agentes investigan, proponen y ejecutan de forma autónoma dentro de las políticas.

Hay una implementación real y en evolución activa en `app/` (servicio `company-core`, Spring Boot / Java + SPA React). Documentos de referencia:

- `empresa.md` — documento fundacional. Consultar su sección "Niveles de autonomía" antes de asumir qué requiere aprobación humana (🟢 automatizable, p. ej. buscar oportunidades o analizar mercados; 🔴 requiere al fundador, p. ej. contactar clientes o cerrar una venta).
- `Plan%20Maestro%20v0-2.md` — plan maestro (organización, modelo económico, gobernanza, fases técnicas).
- `docs/STATE.md` (estado del sprint), `docs/MISSION-001.md` (primera misión), `docs/EVENTS.md` (contrato de eventos Kafka).
- `docs/HISTORY.md` — el *por qué* y el *cómo se verificó* de cada pieza: decisiones acordadas con el usuario, bugs reales encontrados y verificaciones en vivo (Docker + Neo4j + Kafka + Ollama). Consultarlo antes de re-verificar algo ya probado o de cambiar una decisión no obvia. Este `CLAUDE.md` describe solo el estado vigente.
- `docs/superpowers/specs/` + `plans/` — pares diseño/plan fechados, uno por feature. **Mezclan trabajo implementado y pendiente**: ledger financiero (`2026-09-15`), rondas de evidencia (`2026-09-16`) y puente LEAD→cliente real (`2026-09-17`) **todavía no** existen en el código; development generation (`2026-09-21`, revisado el 2026-09-24 como "misiones por equipo") sí. Verificar en el código antes de asumir que algo de un plan existe.
- `EMPRESA_AI_NUEVO_TODO_EVIDENCE.md` — roadmap vigente. `EMPRESA_AI_TODO.md` es un roadmap anterior ya completo; `status.md` y `docs/KAFKA-BUILD-FIX.md` están obsoletos, no usarlos como estado actual.

## Comandos

Backend: trabajar dentro de `app/`. No hay Maven wrapper (`mvn` del sistema). Bytecode fijado a Java 21 (`--release 21`); `maven-enforcer-plugin` corta el build si JDK < 21 o Maven < 3.9.

```bash
cd app
mvn package                       # jar en target/company-core-0.1.0-SNAPSHOT.jar
mvn -DskipTests package
mvn test                          # suite unitaria (JUnit + Mockito)
mvn test -Dtest=MiClase#miMetodo  # un único test
mvn spring-boot:run               # servicio local en :8081
```

No hay lint en el backend. Los tests unitarios mockean Neo4j, Kafka y Ollama (los `*MemoryService` no llevan test directo; se cubren vía los tests de los servicios que los usan). La integración real se verifica en vivo y se registra en `docs/HISTORY.md`. Healthcheck: `GET http://localhost:8081/actuator/health` (se exponen `health`, `info`, `metrics`).

Frontend (`app/frontend/`, React + Vite + TS):

```bash
cd app/frontend
npm install
npm run dev      # :5173 con proxy a :8081 (vite.config.ts)
npm run build    # dist/ (el Dockerfile lo copia a src/main/resources/static)
npm run lint     # oxlint — único lint del repo
```

`mvn spring-boot:run` **no** compila ni sirve la SPA (sin `frontend-maven-plugin`, deliberado). Backend+frontend juntos como en producción → Docker; desarrollo de frontend → `npm run dev` contra el backend en 8081.

Docker (multi-stage: `frontend-build` node → `build` Maven → runtime `jre`):

```bash
docker network create ai-company-net   # una sola vez
docker compose build && docker compose up
```

`docker-compose.yml` **solo** define `company-core`; Neo4j, Ollama y Kafka deben estar ya corriendo en la red `ai-company-net` (Kafka en `storm-kafka:19092`). El default de Kafka en `application.yml` (`host.docker.internal:9092`) es legado.

**Antes de reconstruir/reiniciar el contenedor**: las misiones se orquestan en threads en memoria y no sobreviven un restart (sin reconciliación). Confirmar que no haya una misión real en curso; si igual pasa, corregir por Cypher (`status='FAILED'` en la misión y en las tareas colgadas en `RUNNING`) y relanzar. Lo mismo aplica a `Agent.status` que quede en `WORKING`.

## Arquitectura

Servicio REST monolítico (Spring Boot 4.1.1, Java 21) que orquesta agentes LLM (Ollama), con memoria en Neo4j y eventos en Kafka.

Convenciones transversales:
- **Jackson 3**: el código usa `tools.jackson.*`, nunca `com.fasterxml.jackson.*` (aunque el `pom.xml` todavía declare `jackson-databind` explícitamente).
- **Sin `@Async`** en la orquestación: executors explícitos inyectados con `@Qualifier` + `CompletableFuture.supplyAsync/runAsync` (pools `missionOrchestratorExecutor` y `agentTaskExecutor` en `AsyncConfig`). No reintroducirlo.
- **Errores HTTP**: ningún controller hace manejo fino; `IllegalArgumentException`/`IllegalStateException` caen al handler default → 500. Es la convención, no agregar manejo nuevo por su cuenta.
- **Determinismo sobre LLM**: todo lo que se puede decidir en Java (ruteo del chat, conteos, validación, contradicciones, evaluación financiera) se hace en Java, nunca pidiéndole al modelo que se revise a sí mismo. Es el hilo conductor de casi todas las decisiones del proyecto.
- **Kafka**: topic `EMPRESA_EVENTS` vía `CompanyEventPublisher`; todo `eventType` debe empezar por `EMPRESA_` (`publish()` lanza si no). El proyecto es 100% productor, no hay consumers.
- **Neo4j**: driver plano (`neo4j-java-driver`, no Spring Data), Cypher a mano en los `*MemoryService`. Constraints y seed se aplican idempotentemente al arrancar (`CompanyMemoryInitializer`, `ApplicationReadyEvent`, con reintentos). `neo4j/init.cypher` es solo referencia del seed, sin constraints. La instancia es compartida con otros proyectos: ignorar labels ajenos (`Agente`, `Carrera`, `Vehiculo`).
- **Rebrand "Forjai"**: solo el nombre de marca. Los identificadores técnicos siguen sin tocar a propósito: paquete `com.aicompany.core`, prefijo `EMPRESA_*`, servicio `company-core`/`ai-company-core`, `Company {id:'AI-COMPANY'}`.
- Rarezas conocidas: `AgentResult.java` vive en `com/aicompany/core/` pero declara `package com.aicompany.core.agent.model`; `CeoService.executeAgentTask` conserva un bloque grande comentado de código muerto (no copiar lógica de ahí).

### Flujo de misión (`MissionService` → `MissionExecutor` → `AgentRuntime` → `CeoService`)

1. `MissionService.start` persiste la misión y lanza `MissionExecutor.executeAsync` (responde de inmediato).
   Si la misión tiene `teamId`, `MissionExecutor` no crea las 5 tareas fijas: ver "Misiones por equipo".
2. Máquina de estados `PLANNING → DELEGATING → WAITING_AGENT_RESULTS → EVALUATING → CONSOLIDATING → AWAITING_INVESTOR`. `MissionExecutor.advanceMission` es el **único** punto que persiste la transición en Neo4j, publica a Kafka y dispara alertas por correo.
3. `DELEGATING` crea 5 tareas fijas: `sales` (MARKET_DISCOVERY), `product` (OFFER_DESIGN), `finance` (UNIT_ECONOMICS), `engineering` (DELIVERY_FEASIBILITY), `qa` (QUALITY_RISK_REVIEW, en paralelo y sin ver a los demás).
4. Cada tarea corre en paralelo vía `AgentRuntime.execute` → `CeoService.executeAgentTask`.
5. **Agent failure ≠ mission failure**: se espera cada agente por separado. Un agente que agota sus intentos recibe hasta `MAX_AGENT_REPLANS` (1) re-ejecuciones desde cero (`EMPRESA_MISSION_REPLANNED`). Si al menos uno completa, se consolida con un bloque `AGENTES_FALLIDOS`; si fallan todos, la misión termina en `FAILED`.
6. `ContradictionDetector` (función pura, reglas deterministas) revisa el conjunto de resultados y antepone `CONTRADICCIONES_DETECTADAS` al texto de consolidación. No bloquea.
7. `CeoService.executeMission` consolida → `AWAITING_INVESTOR`. **Nunca llega a `COMPLETED` sola**: la cierra el inversionista con `POST /missions/{id}/decision` (`APPROVE`→`COMPLETED`, `REJECT`→`CANCELLED`, `REQUEST_MORE_EVIDENCE` no cambia el estado ni re-ejecuta todavía).

Cualquier excepción → `MissionExecutor.safeFail` → `FAILED`. Estados de `AgentTask` (strings): `PENDING → RUNNING → COMPLETED/FAILED`. `Agent.status` (`WORKING`/`IDLE`) es una propiedad persistida distinta del estado de su última tarea; `AgentRuntime` la pone en `IDLE` en un `finally`.

`Mission.environment` (`PRODUCTION`/`TEST`) es explícito (default `PRODUCTION` al crear; al leer, las misiones viejas sin el campo se tratan como `TEST`, salvo `MISSION-001`). `DELETE /missions/{id}` borra de verdad, pero rechaza `MISSION-001`, misiones en curso y misiones con clientes/transacciones reales del fundador. Las `Evidence` solo se borran si quedan huérfanas.

### Misiones por equipo (`Mission.teamId`)

`teamId` opcional, inmutable y validado en `MissionService.start` (uno de los 3 de `TeamMemoryService.KNOWN_TEAM_IDS`, `status=ACTIVE`, con líder y miembros). En el chat solo se reconoce el id exacto (`TEAM-ENGINEERING`…), nunca el nombre del equipo. Con `teamId`: `TeamWorkPlanner` (el líder planifica con `format`, sin `tools`) → `TeamPlanValidator` (miembros reales, una tarea por agente, el líder con tarea, `requiredCapabilities` atómicas y exactas —una lista concatenada se rechaza con una corrección explícita—; en desarrollo: todos los miembros, una `VALIDATION` para quien tenga `QA`, `ownedPaths` literales sin globs, sin duplicados ni solapamiento, `entryPoint`) con 3 intentos y sin plan por defecto. Si el líder declara `participationConflicts` (un miembro sin trabajo real), la misión termina en `FAILED` con su reporte, sin reintentar ni ejecutar → `TeamExecutionStrategy` por `Team.type`: `AnalysisTeamStrategy` (Creative, Marketing: `AgentRuntime` actual vía `AgentTaskBatchRunner`) o `DevelopmentTeamStrategy` (Engineering). Sin `teamId`, discovery exactamente como antes.

**Engineering**: `DevelopmentRuntime` genera `DevelopmentResult` en paralelo (`..` o `.git` → falla sin reintento; ruta absoluta, fuera de `ownedPaths` o con `\` → reintento; la tarea queda `GENERATED`) → `DevelopmentWorkspaceService` hace un commit por agente en `products.workspace-root/<missionId>/` (autor = el agente, trailers `Forjai-Mission`/`Forjai-Task`; si el commit falla, la tarea falla) → `StaticWorkspaceValidator` (capa 1, Git real) → revisión estática del validador (`StaticReviewResult`, gates `RepositoryEvidenceGate` + `ForbiddenClaimsGuard` + `MissingFileClaimGate`, que rechaza declarar faltante un archivo que sí está en el repo) → `validationStatus` calculado por Java (`STATICALLY_VALIDATED`/`UNVALIDATED`/`FAILED`; `FAILED` si falla un chequeo o hay algún finding `BLOCKER` o `MAJOR`) → el CEO consolida y Java agrega el bloque "Estado verificable". Las 3 llamadas de equipo van con `num_ctx=16384` y `num_predict=6144` (sin tope, `qwen3:8b` llegó a generar en bucle más de una hora). Nunca se ejecuta el código generado. `ProductStatus.DEVELOPMENT` = tarea `WORK` completada con `commitSha`; `QA` sigue inalcanzable. Borrar una misión borra también su workspace. En Docker el workspace es el volumen `~/forjai-products` (archivos creados como root: en el host usar `git -c safe.directory='*'`).

**DDD y perfiles de stack** (spec `2026-09-26-sandbox-verification-design.md`, parte 1): el plan de Engineering declara `stackProfile` (catálogo fijo `StackProfile`: `DOTNET_APP`, `GODOT_DOTNET_GAME`, `FLUTTER_WEB_APP`), `boundedContexts` y `ubiquitousLanguage` (≥3 términos); los `ownedPaths` tienen que caer dentro de la estructura DDD del perfil. La capa 1 suma `ENTRY_FILES`, `PROFILE_STRUCTURE` y `DDD_LAYERS` (`DddLayerChecker`, Java, lee `using` de C# e `import` de Dart; `domain` no depende de frameworks ni de otras capas). El sandbox de build/test/arranque es la parte 2.

### Contrato de agentes (`AgentResult`) y gates de validación

La llamada final de cada tarea usa el JSON Schema `AgentResultSchema.SCHEMA` como `format` de Ollama. El contenido pasa por gates en `AgentRuntime.executeInternal`, en este orden:

1. `AgentResultValidator` (sintáctico: obligatorios, `confidence`∈[0,1], recalcula cada `Calculation` `ADD`/`SUBTRACT`, `VALIDATED` exige evidencia `verified=true` con `source`). **Reintenta.**
2. `EvidenceValidationGate` (semántico: `sourceType`∈`WEB|CUSTOMER|TRANSACTION|INTERNAL|NONE`, `verified` + `NONE` prohibido, `WEB` verificado exige URL http(s)). **No reintenta**, falla la tarea de inmediato (asimetría deliberada).
3. `EvidenceBindingGate` ("buscó pero no citó"): si el agente recibió URLs confirmadas de la herramienta de búsqueda, debe citar al menos una. Las URLs se acumulan entre reintentos. **Reintenta.**

Reintentos: hasta 3 intentos (`MAX_RESULT_RETRIES + 1`); el motivo exacto del rechazo se antepone al siguiente prompt como `CORRECCIÓN DEL INTENTO ANTERIOR`. Tras pasar los gates, cada `Evidence` se persiste como nodo deduplicado (`EvidenceDedupKey.stableId` = SHA-256 de source+description, `MERGE`).

`AgentResult` usa `@JsonInclude(NON_EMPTY)`, y sus `List<String>` usan `LenientStringDeserializer` (tolera objetos en vez de strings).

### LLM: Ollama

`CeoService` es el único cliente de Ollama (`/api/chat`, sin streaming) y recibe el `model` de cada llamador. El modelo real de cada uno de los 14 agentes es `Agent.model` en Neo4j (`CompanyMemoryService.agentModel`); se cambia con `PUT /api/company/agents/{id}/model`. Todos los prompts están en español y son fuertemente anti-alucinación.

**Nunca combinar `format` y `tools` en la misma llamada**: Ollama fuerza la gramática y el modelo inventa en vez de llamar la herramienta. `CeoService.rejectFormatCombinedWithTools` lo impide en runtime. Por eso cada intento de tarea tiene dos turnos:
1. Decisión (`toolDecisionSystemPrompt`, `tools=[search_web_evidence]`, sin `format`).
2. Si pidió la herramienta: búsqueda + `confirmReachable` sobre hasta 5 candidatos; los inaccesibles o irrelevantes se descartan antes de llegar al modelo.
3. Final (`AgentRuntime.buildPrompt`, `format: SCHEMA`, sin `tools`).

### Configuración: env vars = solo seed del primer arranque

Variables de entorno con defaults en `application.yml`: `NEO4J_*`, `OLLAMA_BASE_URL`, `OLLAMA_CEO_MODEL`/`OLLAMA_AGENT_MODEL`, `OLLAMA_READ_TIMEOUT` (default `15m`: una llamada más larga se considera colgada y falla), `KAFKA_BOOTSTRAP_SERVERS`, `SERVER_PORT`, `MAIL_HOST`/`MAIL_PORT`, `EVIDENCE_WEB_SEARCH_API_KEY`. `NEO4J_PASSWORD` viene de `.env` en Docker Compose.

**Regla importante**: `ollama.*-model` y `company.seed-capital-usd`/`challenge-days` solo siembran valores la primera vez. Después, el valor real vive en Neo4j (`Agent.model`, `CompanyPolicy`) y cambiar la variable de entorno no tiene efecto. Hay que usar la API o el Command Center.

### Financial Policies y objetivos de misión

- **Company Financial Policies** (`CompanyPolicyService`, enum `PolicyKey`, catálogo fijo de 7: capital semilla, días del desafío, multiplicador de contradicción, 4 umbrales de nivel de venta). Están versionadas: `(:CompanyPolicy)-[:HAS_POLICY_VERSION]->(:PolicyVersion)` + exactamente una `HAS_ACTIVE_POLICY`. Editar crea una versión nueva y la activa; rollback = reactivar una existente. `activeValue(key)` es la lectura que usan `MissionExecutor`/`CustomerService`. Endpoints `/api/company/policies/**`.
- **`Mission.financialCriteria`**: objetivo opcional, estructurado e inmutable, declarado al crear la misión (propiedades `financialCriteria*` aplanadas en `Mission`). Solo el formulario del Command Center lo declara; las misiones iniciadas por chat no lo tienen. Se inyecta como bloque armado en Java en la tarea de `finance`, sin que el agente autodeclare cumplimiento. El cumplimiento se evalúa solo contra transacciones reales en `GET /missions/{id}/net-profit`, y un `deadline` vencido nunca transiciona la misión.
- Las reglas de dominio (`netProfit = revenue - cost`, validación de `Calculation`, gates) no son configurables.

### Datos reales del fundador vs. hallazgos de agentes

- **Customer Validation** (`CustomerController`, `/missions/{id}/customers|transactions|net-profit`): canal exclusivo del fundador; ningún agente puede llamarlo. Exige evidencia validada por el mismo `EvidenceValidationGate`, y `agentId` queda como `"human"`. `net-profit` compara contra la policy de capital semilla y clasifica con los umbrales de policy.
- **Oportunidades** (automático, 🟢): cada misión con resultados crea una `Opportunity {status:'IDENTIFIED'}`, y los `customerCandidates` de los agentes se guardan como `Customer {status:'LEAD'}` vía `HAS_CANDIDATE` (evidencia siempre `verified=false`). Es una relación distinta de `(:Mission)-[:HAS_CUSTOMER]` del flujo humano. Contactar o vender es 🔴 y no se automatiza.

### Evidence Acquisition (`com.aicompany.core.evidence`)

`WebSearchPort` con una sola implementación, `SerperSearchAdapter` (google.serper.dev). `WebPageFetcher` tiene protección SSRF (bloquea privados/loopback/link-local, no sigue redirects, 2 MB máx.; **no** cubre DNS rebinding). `ClaimRelevanceChecker` es heurística léxica (≥30% de términos). `confirmReachable` siempre devuelve `verified=false`: confirma que la URL responde y trata el tema, no que el dato sea cierto. Hay métricas Micrometer `evidence.*` en `/actuator/metrics`.

### Chat (`ChatIntentRouter`)

`POST /api/company/chat` clasifica cada mensaje con regex/keywords **antes** de tocar Ollama, en este orden (el orden importa):
1. `(ejecuta|inicia) MISSION-\d+`.
2. Arranque de misión en lenguaje libre (va antes que las consultas para no perder instrucciones que mencionen palabras de consulta).
3. Decisión sobre `MISSION-<id>` (misma gobernanza que el endpoint).
4. Referencia al foco conversacional ("esas", "ambas"…) → se resuelve contra los datos actuales de `Conversation.lastMentionedIds`, nunca contra el texto anterior.
5. Consultas deterministas (`QueryIntent`: equipos, agentes, misiones por estado, oportunidades, profit, status general) formateadas 100% en Java. Un `MISSION-<id>` suelto también se resuelve en Java (`ProductStatusService`).
6. General → `CeoService.chat` con tool-calling `query_company_memory` (topics fijos, nunca Cypher libre) y los últimos 20 mensajes de `Conversation {id:'MAIN'}` (hilo global único).

Cada turno se graba en `ConversationMemoryService`. `ProductStatus` (estado del producto) está separado de `MissionStatus` y se calcula en cada consulta a partir de señales reales. `DEVELOPMENT`/`QA`/`PUBLISHED` están modelados pero hoy son inalcanzables.

### Agentes, equipos y prompts

- 14 agentes con identidad plana (`name`/`role`/`personality`; la personalidad es solo UI). Solo 6 ejecutan tareas (`ceo` + los 5 delegados). Los otros 8 forman los 3 equipos fijos de `TeamMemoryService.TEAMS` (Engineering, Creative/Product Intelligence, Marketing & Growth; `MEMBER_OF`/`LEADS`, `roleCode`, `capabilities`) y son **solo organizacionales**: crear equipos nunca crea `AgentTask` ni toca `Agent.status`.
- **Prompt versionado** (`PromptMemoryService`): mismo patrón versión inmutable + `HAS_ACTIVE_PROMPT` que las policies. El prompt activo se inyecta como sección adicional en `CeoService.systemPrompt` / `AgentRuntime.buildPrompt`. Nunca reemplaza las reglas anti-alucinación ni el schema, y nunca llega a `toolDecisionSystemPrompt`. En los 8 agentes que no ejecutan tareas no tiene efecto.
- `config/agents/ceo.md` es el formato de referencia para definiciones de agentes.

### Alertas por correo (`AlertMailService`)

Solo se envían para `AWAITING_INVESTOR` y `FAILED` (desde `advanceMission`). Las credenciales SMTP de envío (`Company.systemEmail`/`mailPassword`) y el destinatario (`Company.alertEmail`) viven en Neo4j y se editan en `/api/company/settings`. El envío es `synchronized` (bean mutable) y **nunca lanza**, solo loguea. `management.health.mail.enabled=false` es necesario para que la falta de credenciales no ponga `/actuator/health` en `DOWN`.

## Command Center web (`app/frontend/`)

Interfaz principal para operar la compañía: Dashboard, Chat, Agents (organigrama + editor de prompts), Missions (+ formulario de inicio con objetivo financiero), Activity y Settings (correo + Financial Policies). SPA servida como estáticos por `company-core`; polling con `@tanstack/react-query` (sin WebSocket/SSE). Activity sale de una sola query UNION en Neo4j (`ActivityMemoryService`).

- `api/types.ts` refleja a mano los records Java (sin generación): mantenerlos sincronizados.
- `SpaController` reenvía una **lista explícita** de rutas a `index.html` (no un comodín, que atrapaba `/api/**` mal escritos). Al agregar una pantalla en `App.tsx`, agregar la ruta también ahí.
- `statusColor.ts` centraliza el mapeo de estados a colores.

## Al implementar

Actualizar este archivo cuando cambie el estado vigente (comandos, agentes nuevos en el flujo de misión, pendientes de `docs/STATE.md` que se cierren). Decisiones de alcance acordadas con el usuario, bugs reales y verificaciones en vivo van a `docs/HISTORY.md`, no acá.
