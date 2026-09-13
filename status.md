# AI Company — STATUS

**Fecha:** 2026-09-07  
**Estado:** En pausa — se continúa posteriormente desde la configuración de Kafka.

## 1. Visión

AI Company es una empresa real operada principalmente por agentes de IA.

- Capital semilla inicial: **US$50**.
- Duración máxima del reto: **60 días**.
- Éxito mínimo: una actividad empresarial real con utilidad neta superior a **US$50**, respaldada por evidencia.
- Las decisiones financieras, legales, contractuales, irreversibles o estratégicas relevantes requieren aprobación humana.
- Neo4j: memoria estructurada / source of truth.
- Kafka: backbone de eventos.
- Ollama: razonamiento local.
- Todos los eventos Kafka de la empresa deben comenzar con `EMPRESA_`.

## 2. Arquitectura actual

```text
                    ┌─────────────────────┐
                    │       Neo4j         │
                    │   bolt://neo4j:7687 │
                    └──────────┬──────────┘
                               │
              ┌────────────────┴────────────────┐
              │                                 │
        ┌─────▼─────┐                     ┌─────▼──────┐
        │  Ollama   │                     │ company-   │
        │  :11434   │────────────────────▶│   core     │
        └───────────┘                     │   :8081    │
                                          └─────┬──────┘
                                                │
                                                ▼
                                         ┌──────────────┐
                                         │  storm-kafka │
                                         │EMPRESA_EVENTS│
                                         └──────────────┘
```

Red Docker `ai-company-net`:

```text
neo4j           172.26.0.3
ollama           172.26.0.4
storm-kafka      172.26.0.5
ai-company-core  172.26.0.2
```

## 3. company-core

- Spring Boot **4.1.1**
- Java **21.0.12**
- Tomcat **11.0.24**
- Kafka client **4.2.1**
- Neo4j Driver **6.1.0**

La aplicación arranca correctamente y Neo4j conecta correctamente.

Endpoints existentes:

```text
POST /api/company/chat
GET  /api/company/agents
POST /api/company/missions
GET  /api/company/missions/{missionId}
GET  /api/company/missions/{missionId}/details
```

## 4. Agentes

- CEO / Chief Executive Officer AI
- Sales / Director of Sales AI
- Product / Chief Product AI
- Finance / Chief Finance AI
- Engineering / Chief Engineering AI
- QA & Operations / QA & Operations AI

## 5. Ejecución asíncrona

Pools:

```text
missionOrchestratorExecutor
core = 2
max = 2
queue = 25
prefix = mission-orchestrator-

agentTaskExecutor
core = 4
max = 8
queue = 100
prefix = agent-task-
```

Se utiliza `CompletableFuture`.

Flujo:

```text
CREATED
  ↓
PLANNING
  ↓
DELEGATING
  ↓
WAITING_AGENT_RESULTS
  ↓
EVALUATING
  ↓
CONSOLIDATING
  ↓
AWAITING_INVESTOR
```

`MISSION-006` confirmó ejecución paralela de Sales, Product, Finance y Engineering.

## 6. Neo4j

Neo4j almacena:

- Company
- Agent
- Mission
- AgentTask
- relaciones entre compañía, misiones, agentes y tareas
- estados y resultados

La inicialización del schema fue separada de las escrituras para evitar el error de Neo4j relacionado con schema + write en la misma transacción.

## 7. Kafka

Topic previsto:

```text
EMPRESA_EVENTS
```

Todos los eventos deben validar:

```java
eventType.startsWith("EMPRESA_")
```

Eventos previstos:

```text
EMPRESA_MISSION_CREATED
EMPRESA_MISSION_STARTED
EMPRESA_TASK_STARTED
EMPRESA_TASK_COMPLETED
EMPRESA_TASK_FAILED
```

`CompanyEventPublisher` ya fue corregido para Spring Boot 4 / Jackson 3:

```java
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
```

Debe usar:

```java
private final JsonMapper mapper;
```

No usar en esa clase:

```text
com.fasterxml.jackson.databind.ObjectMapper
com.fasterxml.jackson.core.JsonProcessingException
```

## 8. Problema actual de Kafka

`company-core` arranca, pero `KafkaAdmin` no puede obtener metadata.

Configuración actual observada por `company-core`:

```text
bootstrap.servers = [host.docker.internal:9092]
```

Kafka anuncia:

```text
PLAINTEXT_HOST://localhost:9092
```

Por eso el cliente intenta conectarse desde el contenedor a:

```text
localhost/127.0.0.1:9092
```

y aparecen:

```text
Node 1 disconnected
Connection to node 1 (localhost/127.0.0.1:9092) could not be established
```

Finalmente:

```text
KafkaAdmin
Could not configure topics
java.util.concurrent.TimeoutException
```

La aplicación continúa arrancando y Tomcat queda disponible en `8081`.

## 9. Configuración actual de storm-kafka

Se verificó:

```text
KAFKA_LISTENERS=PLAINTEXT_HOST://0.0.0.0:9092,SSL://0.0.0.0:29092

KAFKA_ADVERTISED_LISTENERS=PLAINTEXT_HOST://localhost:9092,SSL://192.168.49.1:29092

KAFKA_INTER_BROKER_LISTENER_NAME=SSL

KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT_HOST:PLAINTEXT,SSL:SSL
```

### Regla importante

**NO cambiar ni romper el listener SSL de Storm.**

Storm utiliza SSL y esa configuración debe preservarse funcionalmente.

## 10. Cambio ya realizado

Se ejecutó:

```bash
docker network connect ai-company-net storm-kafka
```

Resultado:

```text
storm-kafka      172.26.0.5
ai-company-core  172.26.0.2
```

Ahora ambos están en `ai-company-net`.

## 11. Próximo paso

Revisar el bloque completo de `storm-kafka` en el `docker-compose.yml`:

```bash
grep -A60 -B5 "storm-kafka:" docker-compose.yml
```

Objetivo:

Configurar un listener interno para `company-core`, manteniendo intacta la comunicación SSL que utiliza Storm.

Diseño objetivo:

```text
                       storm-kafka
                  ┌──────────────────┐
                  │                  │
company-core ────▶│ listener interno │
                  │                  │
Fedora ──────────▶│ localhost:9092   │
                  │                  │
Storm ───────────▶│ SSL:29092        │
                  │                  │
                  └──────────────────┘
                           │
                           ▼
                    EMPRESA_EVENTS
```

Una posibilidad es agregar un listener interno como:

```text
INTERNAL://storm-kafka:19092
```

pero **NO aplicarlo todavía** hasta revisar el compose completo y las variables SSL existentes.

## 12. Estado funcional al pausar

### Funcionando

- company-core
- Spring Boot 4.1.1
- Java 21
- Neo4j
- company-core → Neo4j
- Ollama
- ejecución asíncrona de misiones
- ejecución paralela de agentes
- creación y seguimiento de tareas
- persistencia en Neo4j
- CompanyEventPublisher con Jackson 3
- storm-kafka
- red `ai-company-net`

### Pendiente

- Resolver `advertised.listeners` de Kafka.
- Mantener SSL de Storm funcionando.
- Configurar comunicación interna `company-core → storm-kafka`.
- Confirmar creación del topic `EMPRESA_EVENTS`.
- Ejecutar una misión real y comprobar eventos Kafka.
- Verificar que todos los eventos tengan prefijo `EMPRESA_`.
- Continuar evolución misión → oportunidad → cliente → venta → ingreso real.

## 13. Punto exacto para continuar

Último comando:

```bash
docker network connect ai-company-net storm-kafka
```

Última configuración Kafka confirmada:

```text
KAFKA_LISTENERS=PLAINTEXT_HOST://0.0.0.0:9092,SSL://0.0.0.0:29092

KAFKA_ADVERTISED_LISTENERS=PLAINTEXT_HOST://localhost:9092,SSL://192.168.49.1:29092

KAFKA_INTER_BROKER_LISTENER_NAME=SSL

KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT_HOST:PLAINTEXT,SSL:SSL
```

**Continuar desde aquí.**


## 15. Experimentos fundamentales del proyecto

### 15.1 La empresa se rediseña a sí misma

Entre misión y misión, el CEO de IA podrá proponer y, dentro de los límites de gobernanza, ejecutar cambios en la organización:

- Crear roles nuevos.
- Eliminar roles que no aportaron.
- Reescribir descripciones, responsabilidades y prompts.
- Reorganizar la distribución del trabajo según evidencia y resultados.

El organigrama pasa a ser un artefacto aprendido, no exclusivamente un diseño humano.

**Pregunta experimental:** ¿la IA converge hacia una estructura parecida a una empresa humana o descubre una estructura extraña que funciona mejor que la diseñada inicialmente por Alexander?

### 15.2 Mercado interno de predicciones

Antes de comprometerse con una oportunidad, los agentes podrán apostar puntos de credibilidad sobre predicciones concretas, por ejemplo:

```text
"Esta oportunidad conseguirá un cliente antes del día 20."
"El margen neto será superior al 40%."
```

Las predicciones se resolverán contra la realidad. Los agentes que acierten ganarán credibilidad y los que fallen perderán peso relativo en decisiones futuras.

**Objetivo:** construir una gobernanza auto-calibrante basada en desempeño predictivo.

### 15.3 Cartera de apuestas paralelas con reasignación de capital

El sistema podrá operar como cartera en lugar de una única misión → negocio:

```text
US$50
 ├── Apuesta 1 → US$10
 ├── Apuesta 2 → US$10
 ├── Apuesta 3 → US$10
 ├── Apuesta 4 → US$10
 └── Apuesta 5 → US$10
```

El CEO podrá proponer semanalmente reasignaciones de capital hacia lo que presente mayor tracción y detener apuestas perdedoras.

**Qué se quiere medir:** pensamiento de cartera, coste de oportunidad, reasignación dinámica y capacidad de matar iniciativas fallidas.

### 15.4 Constitución enmendable y estudio de autonomía

La empresa parte de una política:

```text
🟢 Autónomo
🟡 Requiere aprobación
🔴 Prohibido / reservado al humano
```

La organización podrá proponer enmiendas a sus propias reglas, pero los cambios de gobernanza requieren ratificación humana.

Cada propuesta conservará:

- Regla a cambiar.
- Motivo.
- Evidencia.
- Beneficio esperado.
- Riesgos.
- Decisión del inversionista.
- Resultado posterior.

**Pregunta experimental:** ¿la empresa solicita más autonomía para actividades razonables o aparecen solicitudes preocupantes de expansión de poder?

### 15.5 Gabinete multi-modelo

Las funciones podrán utilizar modelos diferentes:

```text
CEO         → Modelo A
Finanzas    → Modelo B
Escéptico   → Modelo C
Engineering → Modelo D
Sales       → Modelo E
```

El CEO podrá seleccionar el modelo según el rendimiento histórico observado.

Se registrarán modelo, tarea, resultado, calidad, coste, tiempo y, cuando sea posible, tasa objetiva de acierto.

**Pregunta experimental:** ¿la diversidad de modelos produce mejores decisiones que un único modelo interpretando todos los papeles?

### 15.6 Auto-mejora mediante herramientas

Engineering podrá construir herramientas cuando una misión necesite capacidades que todavía no existan, por ejemplo:

```text
scraper
formateador
transformador de datos
conector
validador
generador de reportes
herramienta de análisis
```

Las herramientas podrán conservarse y reutilizarse en futuras misiones.

La auto-mejora queda limitada a herramientas, software y procesos; no incluye modificación de los pesos de los modelos.

Cada herramienta deberá registrar origen, versión, motivo, misión, resultados, reutilizaciones, errores y decisión de conservarla, modificarla o eliminarla.

### 15.7 Transparencia pública

Se evaluará publicar un feed vivo de actividad de la empresa para reforzar trazabilidad y honestidad.

Como mínimo podría mostrar:

```text
Decisión
Motivo
Agentes involucrados
Capital asignado
Gasto
Ingreso
Resultado
Estado
```

Esta capa podría convertirse además en parte de la propuesta de valor o en una futura línea de negocio.

## 16. Preguntas científicas principales

### Organización
¿Una organización de agentes de IA descubre una estructura distinta de la humana inicial y esa estructura funciona mejor?

### Confianza
¿El desempeño predictivo permite construir una jerarquía interna de credibilidad más eficaz que una jerarquía fija?

### Capital
¿Los agentes pueden gestionar una cartera de pequeñas apuestas y reasignar capital hacia señales reales de tracción?

### Autonomía
¿La organización aprende a pedir autonomía proporcional al riesgo o aparecen patrones de expansión de poder?

### Diversidad de modelos
¿Un gabinete multi-modelo supera a un único modelo desempeñando todos los roles?

### Auto-mejora
¿La empresa mejora materialmente al construir y reutilizar sus propias herramientas?

## 17. Nuevas entidades potenciales para Neo4j

A medida que se implementen estos experimentos, considerar:

```text
Role
RoleVersion
AgentVersion
Prediction
PredictionOutcome
CredibilityScore
CapitalAllocation
Investment
Portfolio
GovernanceRule
GovernanceAmendment
Model
ModelAssignment
Tool
ToolVersion
PublicDecision
```

Estas entidades deberán relacionarse con Company, Mission, Agent, Task, Opportunity, Customer, Decision, Transaction, Evidence y Lesson.

## 18. Restricción de gobernanza

La autonomía adaptativa no elimina la autoridad del inversionista humano.

La empresa puede:

```text
observar
analizar
proponer
experimentar
ejecutar operaciones permitidas
aprender
reorganizarse dentro de los límites
```

pero cambios fundamentales de gobernanza, compromisos relevantes de capital, contratos, acciones irreversibles y riesgos materiales continúan sujetos a aprobación humana.

> La empresa puede aprender a gobernarse mejor; no puede convertirse unilateralmente en la autoridad final sobre sí misma.

## 14. Principio de arquitectura

> La IA puede operar la empresa, pero el mercado decide si la empresa merece existir.
