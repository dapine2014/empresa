# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Estado del repositorio

Proyecto "AI Company": una empresa real operada principalmente por agentes de IA. El fundador humano (Alexander / `dapine@gmail.com`) define visión y aprueba decisiones reservadas; los agentes investigan, proponen y ejecutan de forma autónoma dentro de las políticas.

Ya hay **una implementación inicial** en `app/` (servicio `company-core`, Spring Boot / Java). El resto sigue siendo diseño. El repositorio **aún no está bajo control de versiones** (no hay git), así que no hay historial que consultar. Documentos de referencia, en orden de detalle:

- `empresa.md` — documento fundacional (niveles de autonomía 🟢🟡🔴, formato de reportes, diagramas).
- `Plan%20Maestro%20v0-2.md` — plan maestro v0.2 (organización, modelo económico, gobernanza, fases técnicas).
- `docs/STATE.md` — estado del sprint y qué falta para cerrar Sprint 0.
- `docs/MISSION-001.md` — la primera misión (descubrir y validar el primer negocio real).
- `docs/EVENTS.md` — contrato de eventos Kafka.
- `EMPRESA_AI_TODO.md` — estado, hallazgos y roadmap más recientes (fechado el día de hoy); más al día que `docs/STATE.md` y `status.md` (este último quedó "en pausa" en un punto de configuración de Kafka anterior al contrato `AgentResult` descrito abajo).

Consulta esos documentos antes de tomar decisiones de diseño; en particular la sección "Niveles de autonomía" de `empresa.md` antes de asumir qué acciones requieren aprobación humana. Las **alertas importantes** deben enviarse al correo `dapine@gmail.com` (aún no implementado).

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

Healthcheck: `GET http://localhost:8081/actuator/health` (actuator en el classpath; solo se exponen `health` e `info`, con probes de readiness/liveness activadas).

Docker (la imagen construye con Maven en multi-stage):

```bash
docker network create ai-company-net   # red externa requerida (una sola vez)
docker compose build
docker compose up
```

`docker-compose.yml` **solo** define `company-core`. Neo4j, Ollama y Kafka se esperan ya en ejecución y accesibles, todos en la red `ai-company-net`; `docker-compose.yml` fija `KAFKA_BOOTSTRAP_SERVERS=storm-kafka:19092` (listener interno agregado a Kafka para `company-core`, ver `status.md` §9-10 y `EMPRESA_AI_TODO.md` §4.4). El default de `application.yml` para Kafka (`host.docker.internal:9092`) es el legado previo a ese listener y solo aplica si corres `mvn spring-boot:run` en local sin exportar `KAFKA_BOOTSTRAP_SERVERS`; Neo4j/Ollama sí traen default a `localhost` para ese caso.

## Arquitectura

Servicio REST monolítico (Spring Boot 4.1.1, Java 21) que orquesta agentes LLM. Starter `spring-boot-starter-kafka` (nuevo en Boot 4).

**Jackson 3**: el código de la app usa `tools.jackson.*` (p. ej. `JsonMapper` en `CompanyEventPublisher`), no `com.fasterxml.jackson.*`. Ojo: `app/pom.xml` todavía declara explícitamente `com.fasterxml.jackson.core:jackson-databind` y `docs/KAFKA-BUILD-FIX.md` describe el problema con la API vieja — ambos están **desactualizados** respecto al código actual; no te guíes por ellos.

### Entradas HTTP

- `CompanyController` (`/api/company`): `GET /agents`, `POST /chat`. En `/chat`, si el mensaje contiene `(ejecuta|inicia) MISSION-<n>` se dispara una misión; si no, es conversación directa con el CEO.
- `MissionController` (`/api/company/missions`): `POST` para iniciar, `GET /{id}` estado, `GET /{id}/details` estado + tareas.
- `CustomerController` (`/api/company/missions/{missionId}/...`): `POST /customers`, `POST /transactions`, `GET /net-profit` — ver "Customer Validation" más abajo.

### Flujo de misión (`MissionService` → `MissionExecutor` → `AgentRuntime` → `CeoService`)

1. `MissionService.start` persiste la misión en Neo4j (`ensureMission`) y lanza `MissionExecutor.executeAsync`, devolviendo el estado inmediatamente (procesamiento en segundo plano).
2. `MissionExecutor` corre en el pool `missionOrchestratorExecutor` (2 hilos) y avanza una máquina de estados: `PLANNING → DELEGATING → WAITING_AGENT_RESULTS → EVALUATING → CONSOLIDATING → AWAITING_INVESTOR`.
3. En `DELEGATING` crea **5 tareas fijas hardcodeadas**: `sales` (MARKET_DISCOVERY), `product` (OFFER_DESIGN), `finance` (UNIT_ECONOMICS), `engineering` (DELIVERY_FEASIBILITY), `qa` (QUALITY_RISK_REVIEW — evalúa riesgos/huecos de evidencia/supuestos no verificados en la oportunidad, de forma **independiente**: corre en paralelo con las otras 4 y no tiene acceso a sus resultados, así se lo dice su propio prompt para que no alucine referencias a "lo que dijeron los demás"). Verificado en vivo: misión real con los 5 agentes completando y `qa` produciendo un `AgentResult` coherente e independiente.
4. Cada tarea se ejecuta en paralelo vía `AgentRuntime.execute` sobre el pool `agentTaskExecutor` (4–8 hilos); `AgentRuntime` construye el prompt y llama a `CeoService.executeAgentTask`.
5. Tras `allOf().join()`, `CeoService.executeMission` consolida los resultados. La misión queda en `AWAITING_INVESTOR` — **nunca llega a `COMPLETED` automáticamente**; lo cierra el inversionista humano.

Concurrencia: **no se usa el proxy `@Async`** para la orquestación (fue un patch deliberado, ver `README.md`). Se usan executors explícitos inyectados con `@Qualifier` + `CompletableFuture.supplyAsync/runAsync`. No reintroducir `@Async` en estos caminos. `AsyncConfig` todavía lleva `@EnableAsync` y define ambos pools (`missionOrchestratorExecutor`, `agentTaskExecutor`); la anotación es inofensiva pero ningún método `@Async` la usa.

Ante cualquier excepción, `MissionExecutor.safeFail` deja la misión en `FAILED` (progreso 100). Otros valores del enum `MissionStatus` (`RESEARCHING`, `EXECUTING`, `COMPLETED`, `CANCELLED`) están definidos pero el executor todavía no los usa.

Estados de tarea (`AgentTask`, strings, no enum): `PENDING → RUNNING → COMPLETED` / `FAILED`.

Si **cualquier** tarea de agente falla, `allOf().join()` propaga la excepción y la misión entera termina en `FAILED` (no hay resultado parcial ni reintento por tarea).

### Contrato estructurado de agentes (`AgentResult`) y validación

Las tareas de agente ya no devuelven texto libre: `CeoService.executeAgentTask` le pide a Ollama structured outputs — el campo `"format"` de la llamada `AGENT_TASK` ya no es el string `"json"` (modo débil, solo garantiza JSON válido) sino el JSON Schema formal `AgentResultSchema.SCHEMA` (`agent/model/AgentResultSchema.java`), que Ollama usa para restringir la gramática de generación a la forma exacta de `AgentResult` (tipos, `enum` de `verificationStatus`/`operation`, objetos anidados `evidence`/`calculations` con `additionalProperties: false`). Verificado en vivo contra `qwen2.5-coder:7b` (Ollama 0.20.0): el modelo devuelve JSON ya conforme al schema, sin fences Markdown. `normalizeJsonResponse` se conserva como red de seguridad (limpia fences ```json ... ``` y recorta al primer `{`/último `}`) por si el modelo aún así antepone texto; si el parseo falla, lanza `IllegalStateException`.

El schema **no reemplaza** al `AgentResultValidator`: solo obliga a que la forma sea correcta, no a que el contenido sea semánticamente válido (un cálculo con aritmética incorrecta, por ejemplo, pasa el schema pero el validator lo rechaza — confirmado con una respuesta real de Ollama). Los campos de texto que el validator exige no vacíos (`agent`, `action`, `recommendation`, `Evidence.description`, `Evidence.sourceType`, `Calculation.name`) llevan `"minLength": 1` en el schema — sin eso, un agente puede devolver `recommendation: ""` (JSON válido y conforme al schema) y el validator lo rechaza igual, tumbando la tarea y, por la regla de fallo atómico, toda la misión (reproducido una vez en vivo con el agente `sales` antes de este ajuste).

**Verificado end-to-end en vivo** (`docker compose build` + `up`, misión real vía `POST /api/company/missions` contra Ollama 0.20.0 real, sin mocks): los 4 agentes (`sales`/`product`/`finance`/`engineering`) completaron con `AgentResult` válidos y la misión llegó a `AWAITING_INVESTOR` con una consolidación coherente del CEO. La consolidación en `qwen2.5-coder:14b` es la parte lenta del ciclo (~3 min en esta corrida), consistente con los benchmarks de `EMPRESA_AI_TODO.md` §5.

### Detección de contradicciones entre agentes

`ContradictionDetector` (`agent/validation/ContradictionDetector.java`) corre en `MissionExecutor`, sobre el `List<AgentResult>` completo de la misión (los 5 agentes juntos), justo antes de armar el prompt de consolidación — algo que `AgentResultValidator` no puede ver porque valida cada `AgentResult` aislado. Reglas deterministas (no otra llamada al modelo "revisándose a sí mismo"):

1. `verificationStatus=VALIDATED` con `confidence` baja (< 0.5), o `NOT_VALIDATED` con `confidence` muy alta (> 0.85).
2. El mismo nombre de `Calculation` (normalizado) reportado con resultados distintos por agentes distintos.
3. Un `Calculation.result` que supera 100x el capital semilla (`company.seed-capital-usd`) sin ninguna `Evidence.verified=true` en ese mismo `AgentResult` — el caso real documentado en `EMPRESA_AI_TODO.md` §14.2 (Costos US$52.000 / Ingresos US$600 con capital de US$50).

Si detecta algo, lo loguea (`WARN`) y lo antepone al texto que recibe `CeoService.executeMission` como bloque `CONTRADICCIONES_DETECTADAS` — no bloquea la misión ni sustituye a `AgentResultValidator`; solo obliga al CEO a verlo al consolidar. Cubierto por `ContradictionDetectorTest` (9 casos) y probado en una misión real de punta a punta (sin contradicciones esa vez, dato limpio).

Una 4ª regla, dentro de un mismo `AgentResult` (no entre agentes), ataca específicamente "no mezclar hechos con hipótesis" (`detectFactHypothesisBlending`):
1. El mismo enunciado (normalizado) no puede estar a la vez en `facts` y en `hypotheses`/`estimates`.
2. `facts` no debería contener lenguaje de cobertura típico de hipótesis/estimación (`podría`, `probablemente`, `se estima`, `aproximadamente`, `tal vez`, etc. — lista en `HEDGE_MARKERS`, en español porque el system prompt exige responder en español).

Esto es heurístico (léxico, no semántico) y puede tener falsos negativos/positivos.

**Verificado con contaminación real del modelo** (no fabricada): contra el prompt real de producción (`AgentRuntime.buildPrompt` + `AgentResultSchema`), 4 llamadas reales a `qwen2.5-coder:7b` en distintos agentes/acciones dejaron `facts` vacío o limpio — el prompt anti-alucinación actual ya es bastante disciplinado en la práctica. Para probar el detector como red de seguridad (no como re-prueba del prompt), se aisló un prompt de stress-test *sin* las reglas anti-mezcla, pidiendo explícitamente "incluye tus proyecciones como si fueran hechos confirmados": el modelo respondió con `facts: ["Capital inicial estimado: US$50.", ...]`, y `ContradictionDetector` lo atrapó correctamente por contener `'estimado'`. En ese mismo caso real, `AgentResultValidator` también lo habría rechazado (por separado) por `confidence=75` fuera de `[0,1]` — en el pipeline real, cualquiera de los dos que se ejecute primero ya tumba la tarea; son capas redundantes, no una sustituyendo a la otra.

`AgentResult` lleva `@JsonInclude(Include.NON_EMPTY)` a nivel de clase: al serializar (no al leer) omite listas vacías y strings en blanco. Esto compacta tanto el JSON que recibe el CEO en `MissionExecutor.serializeAgentResults` como lo que `AgentRuntime.toJson` persiste en Neo4j. Medido con los 4 `AgentResult` reales de una misión (`MISSION-E2E-VERIFY-2`): 2402 → 2056 caracteres (~14%). Reenviando ese mismo prompt de consolidación real contra `qwen2.5-coder:14b` (antes de este ajuste), Ollama reportó `prompt_eval_count=821` tokens — muy por debajo de los ~4075 tokens que documentaba `EMPRESA_AI_TODO.md` §12.2, porque esa cifra es de la era **previa** al contrato `AgentResult`/JSON Schema (texto narrativo libre), no comparable con el prompt estructurado actual. Con la compactación `NON_EMPTY` el ahorro adicional es modesto (~14% del payload de agentes) — la mayor parte del tamaño ya era contenido semántico real (`recommendation`, `risks`, `evidenceRequired`), no ruido de formato.

`AgentRuntime.executeInternal` pasa el `AgentResult` ya parseado por `AgentResultValidator` (una *validation gate* independiente del agente): campos obligatorios no nulos, `confidence` en `[0,1]`, cada `Calculation` recalculado (solo soporta `ADD`/`SUBTRACT`; cualquier otra operación es un error), cada `Evidence` con `description`/`sourceType` y `source` si `verified=true`, y `verificationStatus` restringido a `NOT_VALIDATED|PARTIALLY_VALIDATED|VALIDATED` — `VALIDATED` exige al menos una evidencia `verified=true` con `source`. Un resultado inválido (o un fallo de parseo) se convierte en `IllegalStateException`, la tarea queda `FAILED` y, por la regla de arriba, toda la misión termina en `FAILED`.

`CeoService.executeAgentTask` todavía conserva un bloque grande comentado (una versión anterior del mismo parseo) — es código muerto, no una ruta alternativa activa; no dupliques lógica a partir de él.

### Evidence Engine y Validation Gate semántica

Separado deliberadamente de `AgentResultValidator` (sintáctico) y de `ContradictionDetector` (cruza agentes/campos): `EvidenceValidationGate` (`agent/validation/EvidenceValidationGate.java`) valida que lo que un agente marca como `verified=true` sea, al menos superficialmente, creíble — "el agente afirma X" ≠ "la empresa puede demostrar X" (`EMPRESA_AI_TODO.md` §17):

1. `sourceType` debe ser uno de `WEB|CUSTOMER|TRANSACTION|INTERNAL|NONE` (las mismas categorías que ya pide `AgentRuntime.buildPrompt`; antes nada validaba que el modelo respetara esa lista).
2. `verified=true` con `sourceType=NONE` es una contradicción directa (verificado sin ninguna fuente real) — rechazado.
3. `verified=true` con `sourceType=WEB` exige que `source` empiece con `http://`/`https://` — una evidencia WEB "verificada" sin nada parecido a una URL es sospechosa.

Corre en `AgentRuntime.executeInternal` justo después de `AgentResultValidator`, como un segundo gate duro: si falla, lanza `IllegalStateException` igual que el validator sintáctico (misma consecuencia: tarea `FAILED` → misión `FAILED`). Cubierto por `EvidenceValidationGateTest` (7 casos).

La parte de **persistencia** del Evidence Engine: `MissionMemoryService.recordEvidence` escribe cada `AgentResult.Evidence` como un nodo `Evidence` de primera clase en Neo4j (`id = "{taskId}-EVIDENCE-{índice}"`, constraint de unicidad `evidence_id` en `CompanyMemoryService.initializeSchema`), enlazado `(:AgentTask)-[:HAS_EVIDENCE]->(:Evidence)` — ya no vive solo como texto dentro del blob JSON de `AgentTask.result`. Se llama desde `AgentRuntime` una vez que ambos gates (sintáctico + semántico) pasan. Verificado en vivo: misión real completa, `MATCH (t:AgentTask)-[:HAS_EVIDENCE]->(e:Evidence)` devolvió el nodo esperado con los campos correctos.

`neo4j/init.cypher` (la referencia manual) **no** incluye ningún `CREATE CONSTRAINT` — ya estaba desincronizado de `CompanyMemoryService.initializeSchema()` antes de este cambio (solo tiene el seed de `Company`/`Agent`); no te guíes por él para el esquema de constraints, solo para el seed.

### Customer Validation (`CustomerController` → `CustomerService` → `CustomerMemoryService`)

Registrar un cliente o una venta **real** es un flujo separado del de misiones — no pasa por un `AgentResult` ni por un LLM. Hoy es un canal de entrada de datos (pensado para el fundador humano; no hay ningún flujo que permita a un agente llamarlo autónomamente):

- `POST /api/company/missions/{missionId}/customers` (`CustomerCommand`): registra un `Customer`, exige una `Evidence` (misma forma que la de `AgentResult`: `description`/`source`/`sourceType`/`verified`) que pasa por el **mismo** `EvidenceValidationGate` que usan los agentes (`evidenceGate.validate(List<Evidence>)`, sobrecarga nueva que no depende de `AgentResult`) — un `verified=true` con `sourceType=NONE`, por ejemplo, se rechaza igual que si lo hubiera declarado un agente. 404 si la misión no existe (`IllegalArgumentException`, sin handler global → 500; no hay manejo de errores HTTP más fino en ningún controller de este proyecto, se mantiene esa convención).
- `POST /api/company/missions/{missionId}/transactions` (`TransactionCommand`): registra una venta ligada a un `customerId` ya existente (404 si no existe — `Optional` vacío desde `CustomerMemoryService`, mismo patrón que `MissionController`), calcula `netProfitUsd = revenueUsd - costUsd`, y exige su propia evidencia (recibo, comprobante, contrato).
- `GET /api/company/missions/{missionId}/net-profit`: suma todas las `Transaction` de la misión y devuelve `netProfitUsd`, `successCriterionMet` (`> company.seed-capital-usd`) y `successLevel` (`NINGUNO|BUENO|MUY_BUENO|EXCELENTE|EXTRAORDINARIO`, umbrales fijos de `EMPRESA_AI_TODO.md` §2.4: >US$50, >US$100, ≥US$1.000, ≥US$5.000 — no escalan con el capital semilla configurado, son los niveles de éxito documentados).

Grafo: `(:Mission)-[:HAS_CUSTOMER]->(:Customer)-[:HAS_EVIDENCE]->(:Evidence)`, `(:Mission)-[:HAS_TRANSACTION]->(:Transaction)-[:FOR_CUSTOMER]->(:Customer)`, `(:Transaction)-[:HAS_EVIDENCE]->(:Evidence)` (un solo nodo `Evidence` por registro, id `"{customerId|transactionId}-EVIDENCE"`; a diferencia de `MissionMemoryService.recordEvidence`, aquí el `agentId` guardado es el literal `"human"`).

Verificado en vivo de punta a punta (misión creada directamente en Neo4j para no esperar una orquestación completa, ya verificada por separado): evidencia inválida → rechazada; cliente válido → creado; venta a cliente inexistente → 404; venta válida → `netProfitUsd` correcto; `GET /net-profit` agregando bien sobre múltiples transacciones; estructura del grafo confirmada con Cypher. 7 tests en `CustomerServiceTest`.

Los campos `List<String>` de `AgentResult` (`facts`, `hypotheses`, `estimates`, `evidenceRequired`, `risks`) usan `@JsonDeserialize(contentUsing = LenientStringDeserializer.class)` (`agent/model/LenientStringDeserializer.java`): si el modelo devuelve un objeto en vez de un string para alguno de esos elementos (bug real observado con Ollama, p. ej. `evidenceRequired: [{"description": "..."}]`), el deserializador rescata `description`/`text`/`value`/`content`/`name`/`detail`/`summary` si existe, o cae al JSON crudo como string — nunca lanza excepción de parseo por esto.

Nota de paquete: `AgentResult.java` vive en `src/main/java/com/aicompany/core/AgentResult.java` pero declara `package com.aicompany.core.agent.model;` (no coincide con su carpeta). Compila igual porque Maven no exige esa correspondencia, pero al buscarlo por ruta de paquete no está donde se esperaría.

### Memoria: Neo4j

Acceso con el driver plano `neo4j-java-driver` (no Spring Data Neo4j); consultas Cypher a mano en `MissionMemoryService` y `CompanyMemoryService`. Modelo de grafo **en uso real** (con propiedades, relaciones y código que lee/escribe): `Company {id:'AI-COMPANY'}`, `Agent` (ceo/sales/product/finance/engineering/qa), `Mission`, `AgentTask`, `Opportunity`, `Evidence`, con relaciones `WORKS_FOR`, `HAS_MISSION`, `LED_BY`, `HAS_TASK`, `ASSIGNED_TASK`, `HAS_EVIDENCE` (`AgentTask`→`Evidence`, ver "Evidence Engine" arriba).

**Groundwork de memoria ampliada** (`CompanyMemoryService.initializeSchema`, sección separada con comentario): constraints de unicidad `id` para 21 labels más del roadmap de `EMPRESA_AI_TODO.md` §21 / `status.md` §17 — `Role`, `RoleVersion`, `AgentVersion`, `Prediction`, `PredictionOutcome`, `CredibilityScore`, `CapitalAllocation`, `Investment`, `Portfolio`, `GovernanceRule`, `GovernanceAmendment`, `Model`, `ModelAssignment`, `Tool`, `ToolVersion`, `PublicDecision`, `Customer`, `Decision`, `Transaction`, `Lesson`, `Strategy`. **Deliberadamente solo el constraint de identidad** — sin propiedades ni relaciones definidas, porque ningún flujo del código las escribe o las lee todavía (son para features que no existen aún: organización autoevolutiva, mercado de predicción, cartera de capital, gobernanza, gabinete multi-modelo, auto-mejora de herramientas, Customer Validation, transparencia pública). Cuando se implemente cada funcionalidad, ahí se definen las propiedades y relaciones reales según lo que esa funcionalidad concreta necesite — no hay un diseño de grafo ya decidido más allá del label y el `id`. Verificado en vivo con `SHOW CONSTRAINTS` contra el Neo4j real tras `docker compose up`.

Este Neo4j es una **instancia compartida** en la máquina de desarrollo (otros proyectos, p. ej. `python/meteoro`, también la usan) — `SHOW CONSTRAINTS` mostrará labels de otros proyectos (`Agente`, `Carrera`, `Vehiculo`) que no tienen relación con `empresa`; ignóralos.

El esquema (constraints) y el seed de company + agents se aplican **idempotentemente al arrancar** (`CompanyMemoryInitializer` en `ApplicationReadyEvent`), con reintentos (12 × 2 s) esperando a que Neo4j esté disponible. `neo4j/init.cypher` es la misma inicialización en forma de referencia manual.

### Eventos: Kafka

Los eventos se publican en el topic `EMPRESA_EVENTS` vía `CompanyEventPublisher`. **Regla dura**: todo `eventType` debe empezar por `EMPRESA_` — `publish()` lanza `IllegalArgumentException` si no. Envelope y catálogo de eventos en `docs/EVENTS.md`.

Cobertura: `EMPRESA_MISSION_CREATED` (`MissionService.start`, antes de lanzar la orquestación), `EMPRESA_MISSION_STARTED` (`MissionExecutor.executeAsync`), los eventos de tarea `EMPRESA_TASK_CREATED/STARTED/COMPLETED/FAILED`, y cada transición de la máquina de estados de la misión (`PLANNING → DELEGATING → WAITING_AGENT_RESULTS → EVALUATING → CONSOLIDATING → AWAITING_INVESTOR`) como `EMPRESA_MISSION_UPDATED`, o `EMPRESA_MISSION_FAILED` en el caso terminal. `MissionExecutor.advanceMission` es el único punto que llama a `memory.updateMission` — hace la escritura en Neo4j y la publicación en Kafka en la misma operación, para que las dos memorias no se desincronicen (antes, `memory.updateMission` se llamaba directamente en 6 sitios distintos sin publicar nada a Kafka). Verificado en vivo: consumí el topic `EMPRESA_EVENTS` real de punta a punta para una misión completa y los 23 eventos esperados (1 CREATED + 1 STARTED + 5×2 TASK_CREATED/STARTED + 5 TASK_COMPLETED + 6 MISSION_UPDATED) aparecieron con el `status`/`currentStep` correctos.

### LLM: Ollama

`CeoService` es el **único** cliente de Ollama: `RestClient` POST a `/api/chat`, no streaming. Usa dos modelos distintos según el rol (visto en pruebas: CEO en `qwen2.5-coder:14b` para razonamiento/consolidación, agentes en `qwen2.5-coder:7b` para ejecución paralela — el 14B es notablemente más lento, ver `EMPRESA_AI_TODO.md` §5). Las llamadas de tipo `AGENT_TASK` piden `format: json` a Ollama (ver contrato `AgentResult` arriba); el chat del CEO y la consolidación de misión no. Todos los system/user prompts están en español y son fuertemente anti-alucinación: los agentes no deben inventar clientes, ventas, ingresos, búsquedas web ni evidencia, y deben separar hecho / hipótesis / estimación / resultado verificado.

### Configuración

Todo por variables de entorno (defaults de dev en `app/src/main/resources/application.yml`): `NEO4J_URI` / `NEO4J_USERNAME` / `NEO4J_PASSWORD`, `OLLAMA_BASE_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `SERVER_PORT` (8081). Capital semilla US$50 y ventana de 60 días viven en `company.*` (`AppProperties`). `NEO4J_PASSWORD` se toma de `.env` en Docker Compose.

`ollama.ceo-model` / `ollama.agent-model` traen default a `qwen2.5-coder:14b` / `qwen2.5-coder:7b` (override vía `OLLAMA_CEO_MODEL` / `OLLAMA_AGENT_MODEL`, como hace `docker-compose.yml`); `mvn spring-boot:run` en local ya arranca sin exportar nada.

### Definiciones de agentes

`config/agents/ceo.md` es la definición operativa del CEO (cargo, misión, prohibiciones, escalamiento humano). Al añadir agentes nuevos, seguir ese formato en `config/agents/`.

## Al implementar

Cuando avances más allá del núcleo actual, actualiza este archivo: comandos reales de lint/test cuando existan, agentes nuevos incorporados al flujo de misión, y los pendientes de `docs/STATE.md` que se vayan cerrando (Junta AI multiagente, ledger financiero, motor de aprobaciones humanas, scheduler de misiones, herramientas de investigación web, dossiers Markdown).
