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

### Flujo de misión (`MissionService` → `MissionExecutor` → `AgentRuntime` → `CeoService`)

1. `MissionService.start` persiste la misión en Neo4j (`ensureMission`) y lanza `MissionExecutor.executeAsync`, devolviendo el estado inmediatamente (procesamiento en segundo plano).
2. `MissionExecutor` corre en el pool `missionOrchestratorExecutor` (2 hilos) y avanza una máquina de estados: `PLANNING → DELEGATING → WAITING_AGENT_RESULTS → EVALUATING → CONSOLIDATING → AWAITING_INVESTOR`.
3. En `DELEGATING` crea **4 tareas fijas hardcodeadas**: `sales` (MARKET_DISCOVERY), `product` (OFFER_DESIGN), `finance` (UNIT_ECONOMICS), `engineering` (DELIVERY_FEASIBILITY). El agente `qa` existe en el grafo pero aún no participa.
4. Cada tarea se ejecuta en paralelo vía `AgentRuntime.execute` sobre el pool `agentTaskExecutor` (4–8 hilos); `AgentRuntime` construye el prompt y llama a `CeoService.executeAgentTask`.
5. Tras `allOf().join()`, `CeoService.executeMission` consolida los resultados. La misión queda en `AWAITING_INVESTOR` — **nunca llega a `COMPLETED` automáticamente**; lo cierra el inversionista humano.

Concurrencia: **no se usa el proxy `@Async`** para la orquestación (fue un patch deliberado, ver `README.md`). Se usan executors explícitos inyectados con `@Qualifier` + `CompletableFuture.supplyAsync/runAsync`. No reintroducir `@Async` en estos caminos. `AsyncConfig` todavía lleva `@EnableAsync` y define ambos pools (`missionOrchestratorExecutor`, `agentTaskExecutor`); la anotación es inofensiva pero ningún método `@Async` la usa.

Ante cualquier excepción, `MissionExecutor.safeFail` deja la misión en `FAILED` (progreso 100). Otros valores del enum `MissionStatus` (`RESEARCHING`, `EXECUTING`, `COMPLETED`, `CANCELLED`) están definidos pero el executor todavía no los usa.

Estados de tarea (`AgentTask`, strings, no enum): `PENDING → RUNNING → COMPLETED` / `FAILED`.

Si **cualquier** tarea de agente falla, `allOf().join()` propaga la excepción y la misión entera termina en `FAILED` (no hay resultado parcial ni reintento por tarea).

### Contrato estructurado de agentes (`AgentResult`) y validación

Las tareas de agente ya no devuelven texto libre: `CeoService.executeAgentTask` le pide a Ollama `"format": "json"` y parsea la respuesta a `AgentResult` (`facts`, `hypotheses`, `estimates`, `evidence`, `evidenceRequired`, `calculations`, `risks`, `recommendation`, `confidence`, `verificationStatus`). `normalizeJsonResponse` primero limpia fences Markdown (```json ... ```) y recorta al primer `{`/último `}` antes de deserializar; si el parseo falla, lanza `IllegalStateException`.

`AgentRuntime.executeInternal` pasa el `AgentResult` ya parseado por `AgentResultValidator` (una *validation gate* independiente del agente): campos obligatorios no nulos, `confidence` en `[0,1]`, cada `Calculation` recalculado (solo soporta `ADD`/`SUBTRACT`; cualquier otra operación es un error), cada `Evidence` con `description`/`sourceType` y `source` si `verified=true`, y `verificationStatus` restringido a `NOT_VALIDATED|PARTIALLY_VALIDATED|VALIDATED` — `VALIDATED` exige al menos una evidencia `verified=true` con `source`. Un resultado inválido (o un fallo de parseo) se convierte en `IllegalStateException`, la tarea queda `FAILED` y, por la regla de arriba, toda la misión termina en `FAILED`.

`CeoService.executeAgentTask` todavía conserva un bloque grande comentado (una versión anterior del mismo parseo) — es código muerto, no una ruta alternativa activa; no dupliques lógica a partir de él.

Nota de paquete: `AgentResult.java` vive en `src/main/java/com/aicompany/core/AgentResult.java` pero declara `package com.aicompany.core.agent.model;` (no coincide con su carpeta). Compila igual porque Maven no exige esa correspondencia, pero al buscarlo por ruta de paquete no está donde se esperaría.

### Memoria: Neo4j

Acceso con el driver plano `neo4j-java-driver` (no Spring Data Neo4j); consultas Cypher a mano en `MissionMemoryService` y `CompanyMemoryService`. Modelo de grafo: `Company {id:'AI-COMPANY'}`, `Agent` (ceo/sales/product/finance/engineering/qa), `Mission`, `AgentTask`, `Opportunity`, con relaciones `WORKS_FOR`, `HAS_MISSION`, `LED_BY`, `HAS_TASK`, `ASSIGNED_TASK`.

El esquema (constraints) y el seed de company + agents se aplican **idempotentemente al arrancar** (`CompanyMemoryInitializer` en `ApplicationReadyEvent`), con reintentos (12 × 2 s) esperando a que Neo4j esté disponible. `neo4j/init.cypher` es la misma inicialización en forma de referencia manual.

### Eventos: Kafka

Los eventos se publican en el topic `EMPRESA_EVENTS` vía `CompanyEventPublisher`. **Regla dura**: todo `eventType` debe empezar por `EMPRESA_` — `publish()` lanza `IllegalArgumentException` si no. Envelope y catálogo de eventos en `docs/EVENTS.md`.

Cobertura actual (incompleta): se emiten `EMPRESA_MISSION_STARTED` (una sola vez, al enviar la orquestación) y los eventos de tarea `EMPRESA_TASK_CREATED/STARTED/COMPLETED/FAILED`. Las transiciones de misión `PLANNING → DELEGATING → … → AWAITING_INVESTOR` y `FAILED` **solo se escriben en Neo4j**, no en Kafka (`MissionExecutor` llama a `memory.updateMission` sin `events.publishMission`). `EMPRESA_MISSION_CREATED` figura en `docs/EVENTS.md` pero nunca se publica.

### LLM: Ollama

`CeoService` es el **único** cliente de Ollama: `RestClient` POST a `/api/chat`, no streaming. Usa dos modelos distintos según el rol (visto en pruebas: CEO en `qwen2.5-coder:14b` para razonamiento/consolidación, agentes en `qwen2.5-coder:7b` para ejecución paralela — el 14B es notablemente más lento, ver `EMPRESA_AI_TODO.md` §5). Las llamadas de tipo `AGENT_TASK` piden `format: json` a Ollama (ver contrato `AgentResult` arriba); el chat del CEO y la consolidación de misión no. Todos los system/user prompts están en español y son fuertemente anti-alucinación: los agentes no deben inventar clientes, ventas, ingresos, búsquedas web ni evidencia, y deben separar hecho / hipótesis / estimación / resultado verificado.

### Configuración

Todo por variables de entorno (defaults de dev en `app/src/main/resources/application.yml`): `NEO4J_URI` / `NEO4J_USERNAME` / `NEO4J_PASSWORD`, `OLLAMA_BASE_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `SERVER_PORT` (8081). Capital semilla US$50 y ventana de 60 días viven en `company.*` (`AppProperties`). `NEO4J_PASSWORD` se toma de `.env` en Docker Compose.

`ollama.ceo-model` / `ollama.agent-model` traen default a `qwen2.5-coder:14b` / `qwen2.5-coder:7b` (override vía `OLLAMA_CEO_MODEL` / `OLLAMA_AGENT_MODEL`, como hace `docker-compose.yml`); `mvn spring-boot:run` en local ya arranca sin exportar nada.

### Definiciones de agentes

`config/agents/ceo.md` es la definición operativa del CEO (cargo, misión, prohibiciones, escalamiento humano). Al añadir agentes nuevos, seguir ese formato en `config/agents/`.

## Al implementar

Cuando avances más allá del núcleo actual, actualiza este archivo: comandos reales de lint/test cuando existan, agentes nuevos incorporados al flujo de misión, y los pendientes de `docs/STATE.md` que se vayan cerrando (Junta AI multiagente, ledger financiero, motor de aprobaciones humanas, scheduler de misiones, herramientas de investigación web, dossiers Markdown).
