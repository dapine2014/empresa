# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Estado del repositorio

Proyecto "Forjai": una empresa real operada principalmente por agentes de IA. El fundador humano (Alexander / `dapine@gmail.com`) define visión y aprueba decisiones reservadas; los agentes investigan, proponen y ejecutan de forma autónoma dentro de las políticas.

Ya hay **una implementación real y en evolución activa** en `app/` (servicio `company-core`, Spring Boot / Java) — no es solo un esqueleto inicial. El repositorio está bajo git (`git log` tiene el historial real; consúltalo en vez de asumir que no existe). Documentos de referencia, en orden de detalle:

- `empresa.md` — documento fundacional (niveles de autonomía 🟢🟡🔴, formato de reportes, diagramas).
- `Plan%20Maestro%20v0-2.md` — plan maestro v0.2 (organización, modelo económico, gobernanza, fases técnicas).
- `docs/STATE.md` — estado del sprint y qué falta para cerrar Sprint 0.
- `docs/MISSION-001.md` — la primera misión (descubrir y validar el primer negocio real).
- `docs/EVENTS.md` — contrato de eventos Kafka.
- `docs/HISTORY.md` — **registro detallado y cronológico** de cada feature: decisiones de diseño acordadas con el usuario, bugs reales encontrados (muchos en producción), y verificaciones en vivo (Docker + Neo4j + Kafka + Ollama reales). Este `CLAUDE.md` describe el sistema *como es hoy*; `docs/HISTORY.md` tiene el *por qué* y el *cómo se verificó* de cada pieza. Consultalo antes de re-verificar algo que ya se probó en vivo, o para entender el razonamiento detrás de una decisión no obvia.
- `docs/superpowers/plans/` — specs y planes de implementación (flujo `writing-plans`/`executing-plans`) para trabajo **todavía no incorporado al código**: ledger financiero, puente LEAD→cliente real, rondas de evidencia, memoria conversacional del chat. Antes de asumir que una de estas features ya existe, verificar en el código — este `CLAUDE.md` solo documenta lo ya implementado.
- `EMPRESA_AI_TODO.md` — estado y roadmap de una fase anterior de esta implementación (contrato `AgentResult`, JSON Schema, Evidence Engine v1, Customer Validation, agente `qa`, eventos Kafka) — ese roadmap ya está **completo**.
- `EMPRESA_AI_NUEVO_TODO_EVIDENCE.md` — el roadmap **vigente**: Evidence Acquisition Engine (búsqueda + fetch web real, ya implementado — ver "Evidence Acquisition" más abajo), Agent Failure != Mission Failure (implementado), memoria empresarial avanzada (parcial). `status.md` quedó "en pausa" en un punto de configuración de Kafka muy anterior a todo esto; no lo uses como estado actual.

Consulta esos documentos antes de tomar decisiones de diseño; en particular la sección "Niveles de autonomía" de `empresa.md` antes de asumir qué acciones requieren aprobación humana (nivel 🟢 automatizable sin aprobación vs. 🔴 que requiere al fundador — p. ej. contactar clientes o cerrar una venta es 🔴, buscar oportunidades o analizar mercados es 🟢).

## Comandos

Trabajar siempre dentro de `app/`. No hay Maven wrapper; se usa `mvn` del sistema. El build fija el bytecode a Java 21 (`--release 21` vía `<java.version>`), así que compila con cualquier JDK ≥ 21 aunque sea más nuevo; `maven-enforcer-plugin` corta el build en `validate` si el JDK es < 21 o Maven < 3.9.

```bash
cd app
mvn package                       # compila + empaqueta el jar (target/company-core-0.1.0-SNAPSHOT.jar)
mvn -DskipTests package           # empaqueta sin tests
mvn test                          # ejecuta la suite unitaria
mvn test -Dtest=MiClase#miMetodo  # ejecuta un único test
mvn spring-boot:run               # arranca el servicio en local (puerto 8081)
```

No hay tooling de lint configurado. La suite unitaria está en `src/test/java` y
cubre la validación de resultados de agentes, el inicio de misiones y el
enrutamiento del chat del CEO. Las integraciones con Neo4j, Kafka y Ollama se
mantienen fuera de estas pruebas para que no dependan de Docker.

Healthcheck: `GET http://localhost:8081/actuator/health` (actuator en el classpath; solo se exponen `health`, `info` y `metrics`, con probes de readiness/liveness activadas; el indicador de correo está deshabilitado — ver "Alertas por correo" más abajo).

**Frontend** (`app/frontend/`, SPA React+Vite+TS — ver "Command Center web" más abajo):

```bash
cd app/frontend
npm install
npm run dev      # dev server en :5173, con proxy a :8081 (vite.config.ts) — no requiere Docker
npm run build    # compila a dist/, lo que copia el Dockerfile a src/main/resources/static
```

`mvn spring-boot:run` en local **no** compila ni sirve la SPA (no hay `frontend-maven-plugin`, deliberado): para probar el backend+frontend juntos como en producción, usar Docker; para desarrollar el frontend, `npm run dev` aparte apuntando al backend real en 8081.

Docker (la imagen construye con Maven **y** con Node en multi-stage — 3 stages: `frontend-build` (`node:22-alpine`, compila la SPA) → `build` (Maven, copia `dist/` a `src/main/resources/static` antes de `mvn package`) → runtime `jre`):

```bash
docker network create ai-company-net   # red externa requerida (una sola vez)
docker compose build
docker compose up
```

`docker-compose.yml` **solo** define `company-core`. Neo4j, Ollama y Kafka se esperan ya en ejecución y accesibles, todos en la red `ai-company-net`; `docker-compose.yml` fija `KAFKA_BOOTSTRAP_SERVERS=storm-kafka:19092` (listener interno agregado a Kafka para `company-core`). El default de `application.yml` para Kafka (`host.docker.internal:9092`) es legado y solo aplica si corres `mvn spring-boot:run` en local sin exportar `KAFKA_BOOTSTRAP_SERVERS`; Neo4j/Ollama sí traen default a `localhost` para ese caso.

**Importante antes de reconstruir/reiniciar el contenedor**: `MissionExecutor` orquesta misiones en threads en memoria del proceso — no sobrevive a un restart, y no hay reconciliación automática. Confirmar que no haya una misión real en curso antes de un `docker compose build && up`; si igual pasa, corregir a mano por Cypher (`status='FAILED'` en la misión y en las tareas colgadas en `RUNNING`) y relanzar la instrucción.

## Arquitectura

Servicio REST monolítico (Spring Boot 4.1.1, Java 21) que orquesta agentes LLM. Starter `spring-boot-starter-kafka` (nuevo en Boot 4).

**Jackson 3**: el código de la app usa `tools.jackson.*` (p. ej. `JsonMapper` en `CompanyEventPublisher`), no `com.fasterxml.jackson.*`. Ojo: `app/pom.xml` todavía declara explícitamente `com.fasterxml.jackson.core:jackson-databind` y `docs/KAFKA-BUILD-FIX.md` describe el problema con la API vieja — ambos están **desactualizados** respecto al código actual; no te guíes por ellos.

### Entradas HTTP

- `CompanyController` (`/api/company`): `GET /agents` (identidad estática), `GET /agents/status` (estado real, ver "Agent.status" más abajo), `GET /activity` (línea de tiempo derivada de Neo4j), `GET`/`PUT /settings` (correo de alertas y credenciales SMTP), `POST /chat` (delega todo en `ChatIntentRouter`).
- `MissionController` (`/api/company/missions`): `POST` para iniciar, `GET` lista misiones recientes (límite fijo 50), `GET /{id}` estado, `GET /{id}/details` estado + tareas, `POST /{id}/decision` decisión real del inversionista humano.
- `CustomerController` (`/api/company/missions/{missionId}/...`): `POST /customers`, `POST /transactions`, `GET /net-profit` — ver "Customer Validation" más abajo.
- `SpaController`: sin API real, solo reenvía una **lista explícita** de rutas de la SPA (`/`, `/chat`, `/agents`, `/missions`, `/missions/{id}`, `/activity`, `/settings`) a `index.html`. Deliberadamente no es un comodín (`"/{path:[^.]*}"` atrapaba también `/api/**` mal escritos y les devolvía 200+HTML en vez de 404) — mantener esta lista sincronizada a mano con `App.tsx` al agregar pantallas nuevas.

Ningún controller de este proyecto tiene manejo fino de errores HTTP: una `IllegalArgumentException`/`IllegalStateException` sin capturar cae al handler default de Spring → 500. Es la convención existente, no un descuido puntual.

### Flujo de misión (`MissionService` → `MissionExecutor` → `AgentRuntime` → `CeoService`)

1. `MissionService.start` persiste la misión en Neo4j (`ensureMission`) y lanza `MissionExecutor.executeAsync`, devolviendo el estado inmediatamente (procesamiento en segundo plano).
2. `MissionExecutor` corre en el pool `missionOrchestratorExecutor` (2 hilos) y avanza una máquina de estados: `PLANNING → DELEGATING → WAITING_AGENT_RESULTS → EVALUATING → CONSOLIDATING → AWAITING_INVESTOR`. `MissionExecutor.advanceMission` es el único punto que persiste en Neo4j y publica a Kafka para cada transición, en la misma operación (evita desincronización entre las dos memorias); también dispara `AlertMailService` cuando la misión llega a `AWAITING_INVESTOR` o `FAILED` (ver "Alertas por correo").
3. En `DELEGATING` crea **5 tareas fijas hardcodeadas**: `sales` (MARKET_DISCOVERY), `product` (OFFER_DESIGN), `finance` (UNIT_ECONOMICS), `engineering` (DELIVERY_FEASIBILITY), `qa` (QUALITY_RISK_REVIEW — corre en paralelo con las otras 4 sin acceso a sus resultados, para evaluar riesgos/huecos de evidencia de forma independiente).
4. Cada tarea se ejecuta en paralelo vía `AgentRuntime.execute` sobre el pool `agentTaskExecutor` (4–8 hilos); `AgentRuntime` construye el prompt y llama a `CeoService.executeAgentTask`.
5. Se espera cada agente por separado (no `CompletableFuture.allOf(...).join()` — un solo agente no recuperable ya no tumba el trabajo de los demás, ver "Agent failure ≠ Mission failure"). `CeoService.executeMission` consolida los resultados de los que sí completaron. La misión queda en `AWAITING_INVESTOR` — **nunca llega a `COMPLETED` automáticamente**; lo cierra el inversionista humano (ver "Decisión del inversionista").

**Concurrencia**: **no se usa el proxy `@Async`** para la orquestación (patch deliberado, ver `README.md`). Se usan executors explícitos inyectados con `@Qualifier` + `CompletableFuture.supplyAsync/runAsync`. No reintroducir `@Async` en estos caminos. `AsyncConfig` todavía lleva `@EnableAsync` y define ambos pools; la anotación es inofensiva pero ningún método `@Async` la usa.

Ante cualquier excepción, `MissionExecutor.safeFail` deja la misión en `FAILED` (progreso 100). `RESEARCHING`/`EXECUTING` del enum `MissionStatus` siguen sin ningún código que los use. Estados de tarea (`AgentTask`, strings, no enum): `PENDING → RUNNING → COMPLETED` / `FAILED`.

### Contrato estructurado de agentes (`AgentResult`) y pipeline de validación

Las tareas de agente devuelven JSON restringido por el JSON Schema formal `AgentResultSchema.SCHEMA` (`agent/model/AgentResultSchema.java`) — el campo `"format"` de la llamada `AGENT_TASK` a Ollama, no el modo débil `"json"`. `normalizeJsonResponse` limpia fences Markdown como red de seguridad; si el parseo falla, `IllegalStateException`.

El schema solo garantiza la *forma*; el contenido pasa por una cadena de gates en `AgentRuntime.executeInternal`, en este orden:

1. **`AgentResultValidator`** (sintáctico): campos obligatorios no nulos, `confidence` en `[0,1]`, cada `Calculation` recalculado (solo `ADD`/`SUBTRACT`), cada `Evidence` con `description`/`sourceType` (y `source` si `verified=true`), `verificationStatus` restringido a `NOT_VALIDATED|PARTIALLY_VALIDATED|VALIDATED` (`VALIDATED` exige ≥1 evidencia `verified=true` con `source`).
2. **`EvidenceValidationGate`** (semántico — `agent/validation/EvidenceValidationGate.java`): `sourceType` debe ser `WEB|CUSTOMER|TRANSACTION|INTERNAL|NONE`; `verified=true` con `sourceType=NONE` se rechaza; `verified=true` con `sourceType=WEB` exige que `source` empiece con `http(s)://`. **Sin reintento** — falla la tarea de inmediato (asimetría deliberada frente a los otros gates).
3. **`EvidenceBindingGate`** ("buscó pero no citó" — `agent/validation/EvidenceBindingGate.java`): si el agente pidió `search_web_evidence` y recibió URLs confirmadas (`confirmReachable`), su `AgentResult.evidence[]` final debe citar al menos una — si no, rechaza. `CeoService.executeAgentTask` devuelve `AgentTaskOutcome(result, confirmedEvidenceUrls)`; las URLs confirmadas se acumulan (`LinkedHashSet.addAll`) entre reintentos, nunca se reasignan (un reintento puede no volver a pedir la herramienta).

`AgentResultValidator` y `EvidenceBindingGate` **sí reintentan** (ver abajo); `EvidenceValidationGate` no. Los tres son gates duros por tarea; `ContradictionDetector` (siguiente sección) opera después, a nivel de misión, y no falla la tarea.

**Reintento**: `AgentRuntime.executeInternal` reintenta hasta `MAX_RESULT_RETRIES + 1` veces (hoy: 3 intentos), tanto por excepción de parseo como por rechazo de `AgentResultValidator`/`EvidenceBindingGate`. El motivo exacto del rechazo se antepone al prompt del siguiente intento como bloque `CORRECCIÓN DEL INTENTO ANTERIOR` — el feedback es siempre determinista (nunca "el modelo revisándose a sí mismo"). Solo tras agotar los intentos se lanza `IllegalStateException` (tarea `FAILED`). A partir del intento 2 se publica `EMPRESA_TASK_RETRY` a Kafka.

`AgentResult` lleva `@JsonInclude(Include.NON_EMPTY)` (omite listas vacías/strings en blanco al serializar, compacta el prompt de consolidación y lo persistido en Neo4j). Los campos `List<String>` (`facts`, `hypotheses`, `estimates`, `evidenceRequired`, `risks`) usan `@JsonDeserialize(contentUsing = LenientStringDeserializer.class)` — si el modelo devuelve un objeto en vez de string, rescata `description`/`text`/`value`/`content`/`name`/`detail`/`summary` o cae al JSON crudo, nunca lanza excepción de parseo.

Nota de paquete: `AgentResult.java` vive en `src/main/java/com/aicompany/core/AgentResult.java` pero declara `package com.aicompany.core.agent.model;` — no coincide con su carpeta (compila igual, Maven no lo exige).

`CeoService.executeAgentTask` conserva un bloque grande comentado (parseo de una versión anterior) — código muerto, no dupliques lógica a partir de él.

### Detección de contradicciones entre agentes (`ContradictionDetector`)

Corre en `MissionExecutor` sobre el `List<AgentResult>` completo de la misión, justo antes de consolidar — algo que los gates por-tarea no pueden ver. Reglas deterministas (nunca otra llamada al modelo):

1. `verificationStatus=VALIDATED` con `confidence` baja (< 0.5), o `NOT_VALIDATED` con `confidence` muy alta (> 0.85).
2. El mismo nombre de `Calculation` (normalizado) reportado con resultados distintos por agentes distintos.
3. `Calculation.result` que supera 100x el capital semilla (`company.seed-capital-usd`) sin ninguna `Evidence.verified=true` en ese `AgentResult`.
4. Dentro de un mismo `AgentResult` (`detectFactHypothesisBlending`): el mismo enunciado no puede estar a la vez en `facts` y en `hypotheses`/`estimates`; `facts` no debería contener lenguaje de cobertura (`podría`, `probablemente`, `se estima`, etc. — lista `HEDGE_MARKERS`, heurístico léxico, puede tener falsos positivos/negativos).

Si detecta algo, lo antepone al texto que recibe `CeoService.executeMission` como bloque `CONTRADICCIONES_DETECTADAS` — no bloquea la misión, solo obliga al CEO a verlo al consolidar.

### Agent failure ≠ Mission failure

`MissionExecutor.executeInternal` espera cada agente por separado (`futuresByAgent`) y captura éxito/fallo en `AgentExecutionOutcome`. Si **todos** fallan, no hay nada que consolidar y la misión sí termina en `FAILED`. Si **al menos uno** completó, la misión sigue: `serializeAgentResults`/`ContradictionDetector` operan solo sobre los que completaron, y el texto de consolidación incluye un bloque `AGENTES_FALLIDOS` (mismo patrón que `CONTRADICCIONES_DETECTADAS`) — la consolidación del CEO decide cómo tratar el hueco, no un `catch` genérico.

### Replanificación automática

Un agente que agota sus 3 intentos internos todavía no se acepta como definitivamente fallido: `MissionExecutor.replanFailedAgents` le da hasta `MAX_AGENT_REPLANS` (hoy: 1) oportunidades más de correr su tarea **desde cero** (no una continuación del intento fallido) antes de incluirlo en `AGENTES_FALLIDOS`. Publica `EMPRESA_MISSION_REPLANNED` por intento. `MAX_AGENT_REPLANS=1` es deliberadamente bajo — una segunda oportunidad completa, no una corrección incremental.

### Evidence Engine (persistencia y deduplicación)

`MissionMemoryService.recordEvidence` escribe cada `AgentResult.Evidence` como nodo `Evidence` de primera clase (`(:AgentTask)-[:HAS_EVIDENCE]->(:Evidence)`), llamado desde `AgentRuntime` una vez que los gates sintáctico + semántico pasan. El id del nodo es `EvidenceDedupKey.stableId(source, description)` (`evidence/EvidenceDedupKey.java`, SHA-256 de ambos campos normalizados) — si dos tareas (de la misma o distinta misión) citan la misma fuente+descripción, el `MERGE` de Cypher apunta al mismo nodo (`ON CREATE SET` fija el contenido la primera vez, `ON MATCH SET` solo refresca `updatedAt`), pero cada tarea igual gana su propia relación `HAS_EVIDENCE`.

`neo4j/init.cypher` (referencia manual) **no** incluye ningún `CREATE CONSTRAINT` — desincronizado de `CompanyMemoryService.initializeSchema()`, solo úsalo para el seed.

### Customer Validation (`CustomerController` → `CustomerService` → `CustomerMemoryService`)

Registrar un cliente o venta **real** es un flujo separado del de misiones — no pasa por `AgentResult` ni por un LLM. Canal de entrada de datos para el fundador humano; ningún flujo permite a un agente llamarlo autónomamente:

- `POST /missions/{missionId}/customers` (`CustomerCommand`): registra un `Customer`, exige una `Evidence` que pasa por el **mismo** `EvidenceValidationGate` que usan los agentes (sobrecarga que no depende de `AgentResult`). 404 si la misión no existe.
- `POST /missions/{missionId}/transactions` (`TransactionCommand`): venta ligada a un `customerId` existente (404 si no), calcula `netProfitUsd = revenueUsd - costUsd`, exige su propia evidencia.
- `GET /missions/{missionId}/net-profit`: suma todas las `Transaction` de la misión, devuelve `netProfitUsd`, `successCriterionMet` (`> company.seed-capital-usd`) y `successLevel` (`NINGUNO|BUENO|MUY_BUENO|EXCELENTE|EXTRAORDINARIO`, umbrales fijos: >US$50, >US$100, ≥US$1.000, ≥US$5.000 — no escalan con el capital semilla configurado).

Grafo: `(:Mission)-[:HAS_CUSTOMER]->(:Customer)-[:HAS_EVIDENCE]->(:Evidence)`, `(:Mission)-[:HAS_TRANSACTION]->(:Transaction)-[:FOR_CUSTOMER]->(:Customer)`, `(:Transaction)-[:HAS_EVIDENCE]->(:Evidence)` — a diferencia de `MissionMemoryService.recordEvidence`, acá el `agentId` guardado es el literal `"human"`.

### Flujo Opportunity → Customer candidato (`OpportunityMemoryService`) — automático, 100% nivel 🟢

`MissionExecutor` llama a `OpportunityMemoryService.recordOpportunity(missionId, instruction)` siempre que la misión produce al menos un resultado — un nodo `Opportunity {status:'IDENTIFIED'}` por misión (`status` nunca avanza automáticamente; es del inversionista humano). `AgentResult.customerCandidates: List<CustomerCandidate>` (`name`/`description`/`source`/`sourceType`, sin `verified`) se persiste vía `OpportunityMemoryService.recordCandidate` como `Customer {status:'LEAD'}` (`(:Opportunity)-[:HAS_CANDIDATE]->(:Customer)`, evidencia siempre `verified=false`) — **relación distinta** de `(:Mission)-[:HAS_CUSTOMER]->(:Customer)` del flujo humano; ambas comparten el label `Customer` en distintas etapas (LEAD/PROSPECT/CUSTOMER/PAYING_CUSTOMER) pero llegan por caminos separados.

Pasos posteriores del flujo objetivo (`contact`, cerrar venta) son nivel 🔴 de `empresa.md` §5 y requieren aprobación humana — no se automatizan.

### Relaciones funcionales (grafo)

`(:Mission)-[:INVOLVES_AGENT]->(:Agent)` conecta cada uno de los 5 agentes delegados directamente a la misión (además de `LED_BY` hacia el CEO), creada en `MissionMemoryService.createTask`. `CustomerMemoryService.registerCustomer` también intenta `MATCH (o:Opportunity {id:...}) MERGE (o)-[:HAS_CUSTOMER]->(c)` (deliberadamente `MATCH`, no `MERGE`, de la Opportunity — si todavía no existe, no pasa nada; nunca crea una Opportunity vacía como efecto secundario).

### Decisión del inversionista humano (`MissionController.decide` → `MissionService.recordDecision`)

`POST /missions/{missionId}/decision` (`DecisionCommand{decision: APPROVE|REJECT|REQUEST_MORE_EVIDENCE, reasoning}`) — el punto de `empresa.md` §5 nivel 🔴 donde el fundador registra su decisión real. Solo sobre una misión en `AWAITING_INVESTOR` o `FAILED` (`IllegalStateException` si no). `APPROVE` → `COMPLETED`, `REJECT` → `CANCELLED`, `REQUEST_MORE_EVIDENCE` no cambia el estado (sin re-ejecución automática todavía). `MissionMemoryService.recordDecision` persiste `(:Mission)-[:HAS_DECISION]->(:Decision {decision, reasoning, decidedAt})` — `decisionId` incluye timestamp, no es idempotente (una misión puede acumular varias decisiones). Publica `EMPRESA_MISSION_DECISION_RECORDED` siempre, más `EMPRESA_MISSION_UPDATED` cuando cambia el estado.

De los 5 tipos que agrupaba esta pieza del roadmap (`Decision`/`Lesson`/`Strategy`/`Prediction`/`CapitalAllocation`), solo `Decision` tiene hoy un disparador real — los otros 4 solo tienen el constraint de unicidad `id` en Neo4j (`CompanyMemoryService.initializeSchema`, junto con otros 19 labels especulativos del roadmap de memoria avanzada), sin propiedades/relaciones ni código que los use.

### Memoria: Neo4j

Acceso con el driver plano `neo4j-java-driver` (no Spring Data Neo4j); Cypher a mano en `MissionMemoryService`/`CompanyMemoryService`/etc. Nodos en uso real: `Company {id:'AI-COMPANY'}`, `Agent`, `Mission`, `AgentTask`, `Opportunity`, `Customer`, `Transaction`, `Evidence`, `Decision`, `Conversation`, `Message`. Relaciones: `WORKS_FOR`, `HAS_CEO`, `HAS_MISSION`, `LED_BY`, `INVOLVES_AGENT`, `HAS_TASK`, `ASSIGNED_TASK`, `HAS_OPPORTUNITY`, `HAS_CANDIDATE`, `HAS_CUSTOMER`, `HAS_TRANSACTION`, `FOR_CUSTOMER`, `HAS_EVIDENCE`, `HAS_DECISION`.

El esquema (constraints) y el seed de company + agents se aplican **idempotentemente al arrancar** (`CompanyMemoryInitializer` en `ApplicationReadyEvent`), con reintentos (12 × 2 s) esperando a que Neo4j esté disponible. `neo4j/init.cypher` es la misma inicialización en forma de referencia manual (solo el seed, sin constraints — ver arriba).

Este Neo4j es una **instancia compartida** en la máquina de desarrollo (otros proyectos también la usan) — `SHOW CONSTRAINTS`/labels de otros proyectos (`Agente`, `Carrera`, `Vehiculo`) no tienen relación con `empresa`; ignóralos.

### Eventos: Kafka

Se publican en el topic `EMPRESA_EVENTS` vía `CompanyEventPublisher`. **Regla dura**: todo `eventType` debe empezar por `EMPRESA_` — `publish()` lanza `IllegalArgumentException` si no. Envelope y catálogo completo en `docs/EVENTS.md`. Cobertura: ciclo de vida de misión y tarea (`CREATED/STARTED/UPDATED/FAILED/RETRY/REPLANNED`), decisión del inversionista, y Evidence Acquisition (`SEARCH_STARTED/COMPLETED`, `VERIFIED`/`REJECTED` por candidato).

**Métricas** (`/actuator/metrics`, vía Micrometer/`MeterRegistry`, sin exportador Prometheus registrado — usa el `SimpleMeterRegistry` en memoria default de Boot): `evidence.search.requests`/`.duration` (tag `agent`), `evidence.candidate.verified`/`.rejected` (tags `agent`, `reason` en rejected = nombre simple de la excepción), `evidence.candidate.duration` (tags `agent`, `outcome`).

### LLM: Ollama

`CeoService` es el **único** cliente de Ollama: `RestClient` POST a `/api/chat`, no streaming. Dos modelos: CEO en `qwen2.5-coder:14b` (razonamiento/consolidación, más lento), agentes en `qwen3:8b` (ejecución paralela — ver por qué no `qwen2.5-coder:7b` en `docs/HISTORY.md`). Todos los system/user prompts están en español y son fuertemente anti-alucinación.

Las tareas de agente (`executeAgentTask`) hacen hasta dos llamadas por intento, **nunca combinando `format` y `tools` en la misma llamada** (Ollama fuerza la gramática del schema y el modelo no puede emitir una tool call — reproducido en vivo con `qwen3:8b` inventando una URL falsa; `CeoService.callModel` tiene un guard duro, `rejectFormatCombinedWithTools`, que lanza si algún cambio futuro combina ambos):

1. **Turno de decisión** (`toolDecisionSystemPrompt` + resumen corto de la tarea, sin `format`, con `tools=[search_web_evidence]`, `think: true`): decide si necesita evidencia real.
2. Si pidió la herramienta, `CeoService.executeTool` ejecuta `searchEvidence` y llama `confirmReachable` sobre hasta `CANDIDATES_TO_CONFIRM = 5` candidatos **antes** de devolver nada al modelo — los que no responden o no son relevantes se descartan ahí mismo (log `TOOL_CANDIDATE_REJECTED`) y nunca llegan al modelo.
3. **Turno final** (prompt completo de `AgentRuntime.buildPrompt`, `format: AgentResultSchema.SCHEMA`, sin `tools`, `think: false`): produce el `AgentResult` con la evidencia real ya en el historial.

El chat del CEO (`CeoService.chat`) y la consolidación de misión son una sola llamada, sin `format` ni `tools` (excepto el tool-calling de `query_company_memory` en el chat, ver "Chat Intent Router" más abajo — ahí no hace falta el diseño de dos turnos porque el chat nunca usa `format`).

### Configuración

Todo por variables de entorno (defaults de dev en `app/src/main/resources/application.yml`): `NEO4J_URI`/`NEO4J_USERNAME`/`NEO4J_PASSWORD`, `OLLAMA_BASE_URL`, `ollama.ceo-model`/`ollama.agent-model` (default `qwen2.5-coder:14b`/`qwen2.5-coder:7b` — nota: el agente real en uso es `qwen3:8b` vía `OLLAMA_AGENT_MODEL`, ver `docker-compose.yml`), `KAFKA_BOOTSTRAP_SERVERS`, `SERVER_PORT` (8081), `MAIL_HOST`/`MAIL_PORT` (default `smtp.gmail.com:587`, transporte genérico sin credenciales de cuenta — esas viven en Neo4j, ver "Alertas por correo"). Capital semilla US$50 y ventana de 60 días en `company.*` (`AppProperties`). `NEO4J_PASSWORD` se toma de `.env` en Docker Compose.

### Definiciones de agentes

`config/agents/ceo.md` es la definición operativa del CEO (cargo, misión, prohibiciones, escalamiento humano). Al añadir agentes nuevos, seguir ese formato en `config/agents/`.

### Evidence Acquisition (`com.aicompany.core.evidence.*`)

Búsqueda web + recuperación de páginas, conectada al flujo real de agentes (ver "LLM: Ollama" arriba), con dos capas separadas:

- `EvidenceCandidate` (`claim`/`url`/`title`/`snippet`/`sourceType`): un resultado de búsqueda, **sin** `verified` — encontrar una URL no es evidencia.
- `WebSearchPort` (interfaz) + `SerperSearchAdapter` (única implementación — `google.serper.dev`, 2.500 consultas gratis sin tarjeta; historial de por qué no DuckDuckGo/Brave en `docs/HISTORY.md`). `POST /search`, header `X-API-KEY`, respuesta `organic[]` (`title`/`link`/`snippet`). Config: `evidence.web-search.base-url`/`EVIDENCE_WEB_SEARCH_API_KEY`. `parseResults(String)` es package-private para testear sin red.
- `WebPageFetcher`: recupera una URL real con protección SSRF (bloquea `localhost`/loopback/rangos privados/link-local incluyendo `169.254.169.254`, exige `http(s)`, no sigue redirects, timeout, límite de 2 MB leído por stream). **Limitación conocida no resuelta**: no protege contra DNS rebinding (la IP se resuelve una vez, sin pinning).
- `EvidenceAcquisitionService.searchEvidence(query)` → `List<EvidenceCandidate>`. `confirmReachable(candidate)` → `AgentResult.Evidence` con **`verified=false` siempre** (confirma que la URL respondió y el contenido está relacionado con el `claim`, no que el dato concreto esté verificado — desviación deliberada del pseudocódigo original del roadmap).
- **`ClaimRelevanceChecker`**: heurística léxica determinista (sin modelo), normaliza claim y contenido, exige ≥30% de coincidencia de términos significativos (≥3 letras, fuera de stopwords en español). No prueba que el dato concreto esté en la página, solo descarta fuentes accesibles pero ajenas al tema. Pendiente: extracción/NLP real del dato concreto (no bloqueante).

## Command Center web (`app/frontend/`)

Interfaz web como forma **principal** de operar la compañía ("torre de control", no un chatbot en terminal); la consola queda para debugging. v1: **Dashboard + Chat + Agents + Missions + Activity + Settings**. Decisiones de arquitectura: SPA React+Vite+TS servida como estáticos por `company-core` (un solo despliegue); polling simple (`@tanstack/react-query`, `refetchInterval`, sin WebSocket/SSE); Activity derivado de Neo4j con una sola query (`ActivityMemoryService.recent`, UNION de `AgentTask`/`Mission`/`Evidence`/`Decision`), no un `KafkaConsumer` nuevo (sería el primer consumer del proyecto, hoy 100% productor).

### Endpoints de solo lectura

- `GET /agents/status` (`MissionMemoryService.latestTaskPerAgent`): para cada `Agent`, `status` (propiedad real del nodo, ver "Agent.status" abajo) + `taskStatus` (de su `AgentTask` más reciente) + `name`/`role`/`personality`.
- `GET /missions` (límite fijo 50, v1 no pagina) y `GET /activity`.

### Chat Intent Router (`ChatIntentRouter`)

El chat no es `POST /chat → LLM → texto`. `ChatIntentRouter.route()` graba cada turno completo (mensaje + respuesta) en `ConversationMemoryService` sin importar qué camino lo resolvió, y clasifica el mensaje **antes** de tocar Ollama (regex/keywords deterministas, nunca "el modelo revisándose a sí mismo" decidiendo la ruta), en este orden:

1. **Arranque de misión con id explícito** (`(ejecuta|inicia) MISSION-\d+`) → `MissionService.start`/`recordDecision` según corresponda.
2. **Arranque de misión en lenguaje libre** (`detectFreeMissionStart`: mensaje contiene "mision" + verbo de arranque) → corre **antes** que decisión/consulta para no perder una instrucción real que mencione incidentalmente una keyword de consulta (p. ej. "sin mi aprobación"); genera un id (`"MISSION-" + timestamp`) y pasa la instrucción completa tal cual a `missionService.start(...)` — los agentes ya trabajan sobre texto libre para cualquier misión, no hace falta parsear la instrucción a una estructura rígida.
3. **Decisión con `MISSION-<id>` explícito** (`aprueba`/`rechaza`/`pide más evidencia`) → llama directo a `MissionService.recordDecision` — misma gobernanza que el endpoint dedicado, nunca una ruta paralela.
4. **Referencia al foco conversacional** (`handleReference`, ver "Memoria conversacional" abajo): pronombre demostrativo plural o cuantificador de foco (`las dos`/`ambas`/`todas`) sobre las últimas misiones mencionadas — puede ser un **comando** de gobernanza (`COMMAND_APPROVE`/`COMMAND_REJECT`, formas adjetivas: "están aprobadas") o una **consulta** sobre el foco.
5. **Consulta determinista** (`detectQuery` → `QueryIntent`): `AGENT_STATUS`, `MISSIONS_NEEDING_ATTENTION` (estrictamente `AWAITING_INVESTOR`, filtrado a `environment=PRODUCTION`), `FAILED_MISSIONS` (estrictamente `FAILED`, `PRODUCTION`), `TEST_MISSIONS` (`environment=TEST`), `OPPORTUNITIES`, `COMPANY_PROFIT`, `COMPANY_STATUS` (snapshot agregado — capital, agentes, misiones, oportunidades, prospectos/clientes, ingresos — catch-all al final para pedidos genéricos de resumen). Todas se formatean **100% en Java**, sin pasar por Ollama — contar/enumerar es una tarea determinista, delegarla a un LLM introduce subconteo o alucinación con volumen real de datos.
6. **General** → `CeoService.chat(ceoName, teamRoster, message, history, companyMemoryQuery)`, con tool-calling real (`query_company_memory`, mismo enum de topics que las consultas deterministas — nunca Cypher libre) y los últimos `HISTORY_LIMIT=20` mensajes (10 turnos) de `Conversation {id:'MAIN'}` para memoria real de la charla (sin resumen/compactación — límite fijo simple).

`ProductStatus` (`DISCOVERY/DESIGN/DEVELOPMENT/QA/PUBLISHED/MONETIZING/BUSINESS_SUCCESS`,
`model/ProductStatus.java`) es el estado real del *producto*, deliberadamente
separado de `MissionStatus` (el workflow de análisis/decisión) — nunca se
infiere uno del otro. `ProductStatusService.resolve(missionId)` lo calcula en
cada consulta a partir de señales reales (`AgentTask` `OFFER_DESIGN`
completada → `DESIGN`; `Transaction` real → `MONETIZING`; `netProfit` sobre
capital semilla → `BUSINESS_SUCCESS`), sin persistir nada nuevo.
`DEVELOPMENT`/`QA`/`PUBLISHED` quedan modelados pero **inalcanzables** hoy
(siempre `false` en el servicio) — son el punto de enganche de una futura
ejecución real de código/infraestructura tras la aprobación del
inversionista, todavía sin diseñar. Un `MISSION-<id>` explícito en el chat
que no sea inicio ni decisión se resuelve **100% en Java** contra
`ProductStatusService` + `MissionMemoryService` (nunca pasa por Ollama) —
mismo motivo que llevó a esto: el chat afirmó una vez "el desarrollo está en
curso" sobre una misión `COMPLETED` con agentes `IDLE`, sin ninguna
evidencia real.

`Mission.environment` (`PRODUCTION`/`TEST`) es una propiedad real y persistida, nunca heurística sobre el nombre del `missionId`; default `PRODUCTION` si se omite en `POST /missions` (`environmentOrDefault()`) — quien inicia una misión de prueba debe marcarla `"TEST"` explícitamente. `find()`/`findAll()` usan `coalesce(m.environment, 'TEST')` al leer (compatibilidad con misiones viejas sin el campo), con la excepción de `MISSION-001` (misión fundacional real) migrada explícitamente a `PRODUCTION`.

### Memoria conversacional (`ConversationMemoryService`)

Un solo hilo global (`Conversation {id:'MAIN'}`, no hay concepto de usuario/sesión), persistido en Neo4j (no en memoria del proceso). `Conversation.lastMentionedType`/`lastMentionedIds` guarda el "foco" — hoy solo tipo `"MISSION"` — actualizado por las consultas que listan misiones (`MISSIONS_NEEDING_ATTENTION`/`FAILED_MISSIONS`/`TEST_MISSIONS`) y por el arranque de misión en lenguaje libre. Una referencia (pronombre + predicado reconocido, p. ej. `esas`+`prueba`→environment, `fallaron`→FAILED, `aprobacion`/`necesita`→AWAITING_INVESTOR) se resuelve consultando el dato **real y actual** de esos `missionId` puntuales (`MissionMemoryService.findByIds`) — nunca el texto de la respuesta anterior. Sin foco → mensaje determinista de "no tengo claro a qué te referís"; predicado no reconocido → cae al chat general con el foco expuesto como topic `LAST_MENTIONED` de `query_company_memory` (nunca revela los datos en texto plano, solo indica qué tipo de entidad está en foco).

`ConversationMemoryService.recentMessages(limit)` alimenta el historial del chat general (distinto del "foco": esto es continuidad de charla libre, no resolución de referencias a entidades).

### Frontend: estructura

`app/frontend/src/`: `api/client.ts` + `api/types.ts` (tipos a mano reflejando los records Java — sin generación automática, mantener sincronizados), `components/Layout.tsx` (sidebar + logo + badge de salud vía polling de `/actuator/health`), `pages/{Dashboard,Chat,Agents,Missions,MissionDetail,Activity,Settings}Page.tsx`, `statusColor.ts` (mapeo de estados a 🟢🟡🔴⚪, un solo lugar), `humanize.ts` (`MARKET_DISCOVERY` → "Market Discovery").

### Identidad de agentes (`Agent.name`/`role`/`personality`)

Identidad plana (sin `RoleVersion`/`AgentVersion`) separada del rol funcional: Alex/CEO, Sofia/Sales, Max/Finance, Luna/Product, Neo/Engineering, Vera/QA. **Personalidad es solo UI** — no se inyecta en los prompts de `AgentRuntime`/`CeoService` (excepción: `CeoService.chat` sí agrega el nombre real del CEO y el roster real del equipo al system message de esa llamada específica, para que el CEO no invente su propio nombre o rol al presentarse — ver `docs/HISTORY.md`). `CompanyMemoryService.initializeCompanyAndAgents()` siembra/migra estos campos in-place en cada arranque.

### Agent.status ≠ AgentTask.status

`Agent.status` es una propiedad real y persistida en el nodo `Agent` (**no** derivada de la última tarea): solo `WORKING`/`IDLE` — ningún otro valor tiene señal real en el código hoy. `AgentRuntime.executeInternal` hace `memory.setAgentStatus(agentId, "WORKING")` al empezar y `"IDLE"` en un `finally` que envuelve todo el método (vuelve a `IDLE` en éxito, fallo tras reintentos, o cualquier excepción inesperada). `AgentStatusResponse.taskStatus` es el status de la última `AgentTask`, explícitamente separado de `status`. **Límite conocido no resuelto**: si el proceso se reinicia con un agente realmente `WORKING`, no hay reconciliación — queda en `WORKING` hasta su próxima tarea real (mismo tipo de limitación que el DNS rebinding de `WebPageFetcher`).

### Alertas por correo (`AlertMailService`, `empresa.md` §18)

De los 6 tipos de alerta que exige `empresa.md`, solo 2 tienen señal determinista real y disparan correo hoy: misión que llega a `AWAITING_INVESTOR` ("decisión estratégica") y misión que termina en `FAILED` ("fallo crítico") — disparado desde `MissionExecutor.advanceMission`, el único punto que ya centraliza las transiciones de estado.

**Dos cuentas de correo distintas**: `Company.alertEmail` (a quién llegan las alertas, seed `dapine@gmail.com`) y `Company.systemEmail`/`Company.mailPassword` (cuenta y App Password propias de la empresa para *enviar* — texto plano en Neo4j, decisión de alcance MVP). Ambas editables desde `GET`/`PUT /api/company/settings` (`SettingsPage.tsx`); `mailPassword` es write-only, nunca se lee de vuelta. `AlertMailService.send(subject, body, critical)` usa `JavaMailSenderImpl` concreto, actualiza `setUsername`/`setPassword` antes de cada envío (el bean es singleton mutable — el método es `synchronized` para que dos misiones en paralelo no se pisen las credenciales), arma HTML+texto plano vía `MimeMessageHelper` multipart (`AlertEmailTemplate.html` — franja azul para `AWAITING_INVESTOR`, roja para `FAILED`, sin logo embebido, con `<meta charset="UTF-8">`). **Nunca lanza** — cualquier fallo de correo se loguea `WARN` y no interrumpe el flujo de misiones.

`management.health.mail.enabled=false` en `application.yml`: sin esto, `MailHealthIndicator` (autoconfigurado por `spring-boot-starter-mail`) tumba `/actuator/health` a `DOWN` cuando no hay credenciales de cuenta cargadas, rompiendo el badge del Command Center — el correo es best-effort, no debe gatear la salud general de la app.

### Rebrand: "Forjai"

Nombre de marca del proyecto (antes "AI Company") — aplicado a toda la documentación, prompts del sistema reales (`CeoService`/`AgentRuntime`), seed de `Company.name` en Neo4j, y el frontend (logo `Logo.tsx` + wordmark en `Layout.tsx`). **Deliberadamente sin tocar** (identificadores técnicos, no el nombre de marca): paquete Java `com.aicompany.core`, prefijo de eventos Kafka `EMPRESA_*`, nombre del contenedor/servicio Docker (`ai-company-core`/`company-core`), id técnico `Company {id:'AI-COMPANY'}` en Neo4j, y el nombre del repositorio de GitHub.

## Al implementar

Cuando avances más allá del núcleo actual, actualiza este archivo: comandos reales de lint/test cuando existan, agentes nuevos incorporados al flujo de misión, y los pendientes de `docs/STATE.md` que se vayan cerrando (Junta AI multiagente, ledger financiero, motor de aprobaciones humanas, scheduler de misiones, herramientas de investigación web, dossiers Markdown). Para cambios que involucren una decisión de alcance acordada con el usuario, un bug real encontrado, o una verificación en vivo — agregalos a `docs/HISTORY.md`, no acá; este archivo describe el estado vigente, no el historial de cómo se llegó a él.
