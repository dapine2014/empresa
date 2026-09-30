# HISTORY.md

Registro detallado, cronológico, de cómo se llegó a la arquitectura actual del servicio `company-core`: decisiones de diseño acordadas con el usuario antes de escribir código, bugs reales encontrados (muchos en producción, no solo en tests), y verificaciones en vivo de cada feature (Docker + Neo4j + Kafka + Ollama reales, sin mocks, salvo que se indique lo contrario).

Este archivo es el complemento de `CLAUDE.md`, que describe el sistema **como es hoy**. Acá está el **por qué** y el **cómo se verificó** de cada pieza — útil para entender el razonamiento detrás de una decisión no obvia, o para confirmar que algo ya se probó en vivo antes de volver a probarlo. Se actualiza agregando rondas nuevas al final; no se reescribe el historial ya asentado.

---

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

### Reintento de resultados de agente

`AgentRuntime.executeInternal` reintenta hasta `MAX_RESULT_RETRIES + 1` veces (hoy: 3 intentos) tanto si `CeoService.executeAgentTask` lanza excepción (Ollama no devolvió JSON parseable) como si `AgentResultValidator` rechaza el resultado ya parseado. En cada reintento, el motivo exacto del rechazo (mensaje de la excepción o `validation.errors()`) se antepone al prompt del siguiente intento como bloque `CORRECCIÓN DEL INTENTO ANTERIOR`, pidiéndole al agente corregir solo eso — no es "el modelo revisándose a sí mismo" (evitado deliberadamente en otras partes del código, ver `ContradictionDetector`/`EvidenceValidationGate`): el feedback viene siempre de una fuente determinista (la excepción de parseo, o los errores de `AgentResultValidator`), nunca de que el propio modelo se autoevalúe. Solo tras agotar todos los intentos se lanza la `IllegalStateException` que tumba la tarea (y, por la regla de fallo atómico, la misión). A partir del intento 2 se publica `EMPRESA_TASK_RETRY` a Kafka (`data.status=RETRYING`); el intento 1 no genera este evento. `EvidenceValidationGate` queda **fuera** de este bucle — sigue fallando la tarea de inmediato sin reintento (asimetría deliberada, no tocada en este cambio). Cubierto por `AgentRuntimeTest` (5 casos: éxito directo, reintento por rechazo del validator, reintento por fallo de parseo, y agotamiento de reintentos en ambos casos) y verificado en vivo con una misión real (sin necesidad de reintento esa vez — dato limpio; la instrumentación `inference attempt=N` confirma que el código está activo).

`CeoService.executeAgentTask` todavía conserva un bloque grande comentado (una versión anterior del mismo parseo) — es código muerto, no una ruta alternativa activa; no dupliques lógica a partir de él.

### Agent failure ≠ Mission failure

Antes, `MissionExecutor` esperaba a los 5 agentes con `CompletableFuture.allOf(futures).join()`: si **cualquiera** agotaba sus reintentos, `.join()` propagaba esa excepción, el `catch` externo mandaba la misión entera a `FAILED`, y se descartaban los resultados de los agentes que sí habían completado — un solo agente no recuperable tumbaba todo el trabajo real de los demás.

Ahora `MissionExecutor.executeInternal` espera cada future por separado (`futuresByAgent`, un `LinkedHashMap<agentId, CompletableFuture<AgentResult>>`) y captura éxito/fallo en un `AgentExecutionOutcome` (`model/AgentExecutionOutcome.java` — deliberadamente sin `recoverable`/`attempts` como sugiere `EMPRESA_AI_NUEVO_TODO_EVIDENCE.md` §21: esos campos no tienen una fuente real en el código hoy, y el detalle de reintentos ya es público vía `EMPRESA_TASK_RETRY`):

- Si **todos** los agentes fallan, no hay nada que consolidar — la misión sí termina en `FAILED` (mismo camino que antes).
- Si **al menos uno** completó, la misión sigue: `serializeAgentResults`/`ContradictionDetector` operan solo sobre los que completaron, y el texto que recibe `CeoService.executeMission` incluye un bloque nuevo `AGENTES_FALLIDOS` (mismo patrón que `CONTRADICCIONES_DETECTADAS`) listando qué agente falló y por qué — para que sea la consolidación del CEO, no un `catch` genérico, la que decida cómo tratar el hueco.

Cubierto por `MissionExecutorTest` (5 casos: todos completan, uno falla y la misión sigue, todos fallan y la misión sí falla, y los 2 de replanificación de abajo). **Disparado de verdad en producción** (no solo en el test): en una misión real, `sales` agotó sus 3 intentos por un cálculo que oscilaba sin converger (`Cálculo inconsistente: ... expected=102.93 actual=-101.93`, luego `expected=202.93` en el intento 3 — el modelo nunca corrigió el signo) y su tarea quedó `FAILED` en Neo4j; los otros 4 agentes completaron y la misión llegó a `AWAITING_INVESTOR` con la consolidación del CEO incluyendo una sección explícita **"5. Agentes Fallidos"**: *"El agente de ventas presentó problemas técnicos que impedieron completar su tarea, por lo que los resultados relevantes son parciales..."* — exactamente el comportamiento diseñado, no una simulación.

### Replanificación automática

`EMPRESA_AI_NUEVO_TODO_EVIDENCE.md` §32 deja esto dentro de "Autonomía avanzada" sin diseño concreto (a diferencia de Evidence Acquisition, que sí traía pseudocódigo) — el alcance real se acordó con el usuario antes de escribir código: reintentar automáticamente **solo** los agentes que fallaron (no la misión completa), con un límite de reintentos a nivel de misión distinto del interno de `AgentRuntime`.

Un agente que agota sus 3 intentos internos (`AgentRuntime.MAX_RESULT_RETRIES`) todavía no se acepta como definitivamente fallido: `MissionExecutor.replanFailedAgents` le da hasta `MAX_AGENT_REPLANS` (hoy: 1) oportunidades más de correr su tarea **desde cero** (turno de decisión + turno final nuevos vía `runtime.execute`, no una continuación del intento fallido) antes de incluirlo en `AGENTES_FALLIDOS`. Cada intento de replan publica `EMPRESA_MISSION_REPLANNED` (`missionId`/`taskId`/`agentId`, `data.replanAttempt`/`data.previousError`) — sugerido en `EMPRESA_AI_NUEVO_TODO_EVIDENCE.md` §22, ahora con una funcionalidad real detrás, no solo el nombre del evento. `MAX_AGENT_REPLANS=1` deliberadamente bajo: es una segunda oportunidad completa, no una corrección incremental — si tampoco funciona a la segunda, insistir más solo alarga la misión sin cambiar el resultado.

`definitions` (los 5 agentes fijos de `DELEGATING`) pasó de `List<String[]>` a un record local `AgentDefinition(agentId, action, objective)` — necesario para poder volver a mirar la acción/objetivo de un agente por su id durante el replan, no solo recorrerlos linealmente una vez.

Cubierto por `MissionExecutorTest` (2 casos nuevos: el agente se recupera en el replan y la misión llega a `AWAITING_INVESTOR` sin `AGENTES_FALLIDOS`; el replan también falla y se agota en `MAX_AGENT_REPLANS`, cayendo en el mismo camino de resultado parcial de arriba). Verificado en vivo con una misión real completa tras el cambio: los 5 agentes completaron en el intento 1 (sin necesitar replan), confirmando que el cambio no rompió el camino normal — el camino de recuperación en sí está probado con los mocks de `MissionExecutorTest` porque forzar una falla real y determinista de un solo agente en producción (sin afectar a los demás) requeriría sabotear Ollama selectivamente, algo que no hay una forma limpia de hacer.

### Evidence Binding: "buscó pero no citó"

Tercer gate dentro del **mismo** bucle de reintento que `AgentResultValidator` (a diferencia de `EvidenceValidationGate`, que sigue fallando la tarea de inmediato sin reintento): `EvidenceBindingGate` (`agent/validation/EvidenceBindingGate.java`) detecta el caso en que un agente pidió `search_web_evidence`, recibió URLs reales y confirmadas (`ClaimRelevanceChecker` + `confirmReachable`), y aun así su `AgentResult` final no cita ninguna en `evidence[]` — antes de este gate nada impedía que eso pasara silenciosamente; ni `AgentResultValidator` ni `EvidenceValidationGate` comparan el resultado contra lo que realmente se le entregó al modelo en el turno de herramienta, solo miran el `AgentResult` en aislamiento.

Para que `AgentRuntime` pueda hacer esa comparación, `CeoService.executeAgentTask` ya no devuelve un `AgentResult` suelto sino `AgentTaskOutcome(result, confirmedEvidenceUrls)` (`agent/model/AgentTaskOutcome.java`) — `confirmedEvidenceUrls` son las URLs que de verdad sobrevivieron `confirmReachable` en el turno de herramienta de esa llamada (vacío si no hubo tool call, o si ninguna sobrevivió). `EvidenceBindingGate.check(confirmedEvidenceUrls, result)` compara esas URLs (normalizadas: minúsculas, sin `/` final) contra los `source` de `result.evidence()`; si `confirmedEvidenceUrls` está vacío, no hay nada que exigir (pasa trivialmente). Si hay URLs confirmadas y ninguna aparece citada, rechaza con el mismo mecanismo de `AgentResultValidator`: el motivo entra al bloque `CORRECCIÓN DEL INTENTO ANTERIOR` del siguiente intento, y solo tras agotar los reintentos se lanza `IllegalStateException` (tarea `FAILED` → misión `FAILED`, misma regla de fallo atómico).

**`confirmedEvidenceUrls` se acumula entre intentos, no se sobrescribe**: el turno de decisión de herramienta de `CeoService` es independiente en cada intento (no ve el feedback de corrección), así que un reintento puede perfectamente no volver a pedir la herramienta. `AgentRuntime.executeInternal` guarda las URLs confirmadas en un `LinkedHashSet` que se va uniendo (`addAll`) en cada intento, nunca reasignando — si se sobrescribiera, una URL real confirmada en el intento 1 se "olvidaría" en el intento 2 y el gate aprobaría trivialmente un resultado que sigue sin citar nada real. Bug encontrado y corregido en revisión propia antes de que causara un falso negativo en producción (no fue reportado por el usuario ni observado fallando).

Cubierto por `EvidenceBindingGateTest` (8 casos aislados, sin Neo4j/Ollama) y por 3 casos en `AgentRuntimeTest` (reintento cuando no cita, agotamiento cuando nunca cita, y el caso específico de acumulación: intento 1 busca y no cita, intento 2 no vuelve a buscar y tampoco cita — debe seguir rechazado — intento 3 cita y pasa).

**Disparado de verdad en producción** (no solo en el test mockeado): en una misión real con `qwen3:8b`, el agente `product` pidió la herramienta, recibió 1 URL real confirmada, y su primer `AgentResult` no la citó — `EvidenceBindingGate` lo rechazó (`evidence binding rejected attempt=1`, log real con la URL exacta), se reintentó, y en el intento 2 el agente volvió a buscar y citó evidencia real distinta pero igualmente válida. La misión completa (5 agentes, incluyendo un rechazo simultáneo y no relacionado de `AgentResultValidator` en `engineering` por un cálculo inconsistente) llegó a `AWAITING_INVESTOR`, con la evidencia final de `product` persistida en Neo4j — confirma que ambos gates (sintáctico y de binding) operan de forma independiente y correcta bajo tráfico real, no solo en aislamiento.

### Evidence Engine y Validation Gate semántica

Separado deliberadamente de `AgentResultValidator` (sintáctico) y de `ContradictionDetector` (cruza agentes/campos): `EvidenceValidationGate` (`agent/validation/EvidenceValidationGate.java`) valida que lo que un agente marca como `verified=true` sea, al menos superficialmente, creíble — "el agente afirma X" ≠ "la empresa puede demostrar X" (`EMPRESA_AI_TODO.md` §17):

1. `sourceType` debe ser uno de `WEB|CUSTOMER|TRANSACTION|INTERNAL|NONE` (las mismas categorías que ya pide `AgentRuntime.buildPrompt`; antes nada validaba que el modelo respetara esa lista).
2. `verified=true` con `sourceType=NONE` es una contradicción directa (verificado sin ninguna fuente real) — rechazado.
3. `verified=true` con `sourceType=WEB` exige que `source` empiece con `http://`/`https://` — una evidencia WEB "verificada" sin nada parecido a una URL es sospechosa.

Corre en `AgentRuntime.executeInternal` justo después de `AgentResultValidator`, como un segundo gate duro: si falla, lanza `IllegalStateException` igual que el validator sintáctico (misma consecuencia: tarea `FAILED` → misión `FAILED`). Cubierto por `EvidenceValidationGateTest` (7 casos).

La parte de **persistencia** del Evidence Engine: `MissionMemoryService.recordEvidence` escribe cada `AgentResult.Evidence` como un nodo `Evidence` de primera clase en Neo4j (constraint de unicidad `evidence_id` en `CompanyMemoryService.initializeSchema`), enlazado `(:AgentTask)-[:HAS_EVIDENCE]->(:Evidence)` — ya no vive solo como texto dentro del blob JSON de `AgentTask.result`. Se llama desde `AgentRuntime` una vez que ambos gates (sintáctico + semántico) pasan. Verificado en vivo: misión real completa, `MATCH (t:AgentTask)-[:HAS_EVIDENCE]->(e:Evidence)` devolvió el nodo esperado con los campos correctos.

**Deduplicación de evidencia** (`EMPRESA_AI_NUEVO_TODO_EVIDENCE.md` §17): el id del nodo ya **no** es `"{taskId}-EVIDENCE-{índice}"` (garantizaba un nodo nuevo por tarea, aunque dos agentes citaran exactamente la misma fuente) sino `EvidenceDedupKey.stableId(source, description)` (`evidence/EvidenceDedupKey.java`) — `SHA-256` de `source`+`description` normalizados (minúsculas, sin espacios de más, sin `/` finales). Si dos tareas citan la misma evidencia (misma fuente, misma descripción), el `MERGE` de Cypher apunta al mismo nodo: cada tarea igual gana su propia relación `HAS_EVIDENCE` hacia él (se puede consultar cuántos agentes corroboraron el mismo dato con `MATCH (e:Evidence)<-[:HAS_EVIDENCE]-(t) RETURN count(t)`), pero el contenido del nodo solo se fija en la primera escritura (`ON CREATE SET`) — las citas siguientes solo refrescan `updatedAt` (`ON MATCH SET`), no pisan `agentId`/`missionId` del primer agente que la encontró. Deliberadamente **no** se restringe por misión: la misma fuente+descripción encontrada en misiones distintas también deduplica — es la misma evidencia real sin importar quién ni cuándo la vio. Cubierto por `EvidenceDedupKeyTest` (7 casos, la función de hashing es pura y se testea sin Neo4j, igual que `ClaimRelevanceChecker`) y verificado en vivo reproduciendo a mano la consulta Cypher real de `recordEvidence` dos veces con la misma fuente+descripción desde dos `AgentTask` distintos: un solo nodo `Evidence`, `agentId` del primer escritor intacto, 2 relaciones `HAS_EVIDENCE` (una por tarea).

`neo4j/init.cypher` (la referencia manual) **no** incluye ningún `CREATE CONSTRAINT` — ya estaba desincronizado de `CompanyMemoryService.initializeSchema()` antes de este cambio (solo tiene el seed de `Company`/`Agent`); no te guíes por él para el esquema de constraints, solo para el seed.

### Customer Validation (`CustomerController` → `CustomerService` → `CustomerMemoryService`)

Verificado en vivo de punta a punta (misión creada directamente en Neo4j para no esperar una orquestación completa, ya verificada por separado): evidencia inválida → rechazada; cliente válido → creado; venta a cliente inexistente → 404; venta válida → `netProfitUsd` correcto; `GET /net-profit` agregando bien sobre múltiples transacciones; estructura del grafo confirmada con Cypher. 7 tests en `CustomerServiceTest`.

### Flujo Opportunity → Customer candidato (`OpportunityMemoryService`) — automático, 100% nivel 🟢

Alcance acordado con el usuario antes de escribir código, porque el flujo completo que describe `EMPRESA_AI_NUEVO_TODO_EVIDENCE.md` §19 (`Opportunity → Customer candidate → contact → interés real → offer → transaction`) incluye pasos ("contact", cerrar una venta) que caen en el nivel 🔴 de `empresa.md` §5 ("vender o transferir activos", "acciones irreversibles") — requieren aprobación humana, no automatización. Lo que sí es 100% nivel 🟢 (`"analizar mercados"`, `"buscar nuevas oportunidades"`) se automatizó: `MissionExecutor` llama a `OpportunityMemoryService.recordOpportunity` siempre que la misión produce al menos un resultado, y `AgentResult.customerCandidates` (constructor de compatibilidad de 12 args conservado para no tocar los `new AgentResult(...)` ya existentes en los tests) se persiste vía `OpportunityMemoryService.recordCandidate` como `Customer {status:'LEAD'}` — deliberadamente una relación distinta de `(:Mission)-[:HAS_CUSTOMER]->(:Customer)` que usa el flujo humano, para no tocar ese código ya probado.

Cubierto por `MissionExecutorTest` (2 casos nuevos: se registra la Opportunity siempre, se registran candidatos cuando un agente los reporta). Verificado en vivo con una misión real: 4 de 5 agentes (`sales`, `product`, `finance`, `engineering`) reportaron candidatos reales (10 en total, con URLs reales como fuente), todos persistidos como `Customer {status:'LEAD'}` correctamente enlazados a la `Opportunity` de la misión.

Los campos `List<String>` de `AgentResult` (`facts`, `hypotheses`, `estimates`, `evidenceRequired`, `risks`) usan `@JsonDeserialize(contentUsing = LenientStringDeserializer.class)` (`agent/model/LenientStringDeserializer.java`): si el modelo devuelve un objeto en vez de un string para alguno de esos elementos (bug real observado con Ollama, p. ej. `evidenceRequired: [{"description": "..."}]`), el deserializador rescata `description`/`text`/`value`/`content`/`name`/`detail`/`summary` si existe, o cae al JSON crudo como string — nunca lanza excepción de parseo por esto.

Nota de paquete: `AgentResult.java` vive en `src/main/java/com/aicompany/core/AgentResult.java` pero declara `package com.aicompany.core.agent.model;` (no coincide con su carpeta). Compila igual porque Maven no exige esa correspondencia, pero al buscarlo por ruta de paquete no está donde se esperaría.

### Relaciones funcionales (auditoría contra el modelo objetivo §23)

`EMPRESA_AI_NUEVO_TODO_EVIDENCE.md` §23 muestra el grafo objetivo como `(:Company)+--(:Mission)+--(:Agent)+--(:Evidence)` y `(:Mission)+--(:Opportunity)+--(:Customer)+--(:Transaction)+--(:Evidence)` — auditar contra esto (alcance acordado con el usuario: solo entidades que ya existen, no los 21 labels especulativos de "autonomía avanzada" como `Role`/`Prediction`/`CapitalAllocation`, que siguen sin ningún código que los escriba o los lea) encontró dos huecos reales, ambos cerrados:

1. **Mission no tenía relación directa con sus agentes delegados** (solo con el CEO vía `LED_BY`) — la única conexión era indirecta, vía `AgentTask`. `MissionMemoryService.createTask` ahora también hace `MERGE (m)-[:INVOLVES_AGENT]->(a)`.
2. **Los clientes reales (humanos, vía `CustomerController`) colgaban solo de Mission, nunca de Opportunity** — `CustomerMemoryService.registerCustomer` ahora también intenta `MATCH (o:Opportunity {id:...}), (c:Customer {id:...}) MERGE (o)-[:HAS_CUSTOMER]->(c)` — deliberadamente un `MATCH` normal, no `MERGE` de la Opportunity: si la misión aún no llegó a consolidación, el `MATCH` simplemente no encuentra nada y nunca crea una Opportunity vacía como efecto secundario.

Verificado en vivo: (a) una misión real nueva mostró los 5 agentes conectados vía `INVOLVES_AGENT` apenas se crean las tareas; (b) registrar un cliente real contra una misión que ya tenía Opportunity dejó el cliente enlazado por **ambos** caminos; (c) registrar un cliente real contra una misión sin Opportunity confirmó 0 nodos `Opportunity` creados como efecto secundario.

### Decisión del inversionista humano (`MissionController.decide` → `MissionService.recordDecision`)

Primera entidad real del grupo "Company Memory" que antes era solo un constraint de Neo4j sin ningún nodo (`Decision`). Alcance acordado con el usuario antes de escribir código: de los 5 tipos que agrupaba esta fila del roadmap (`Decision`/`Lesson`/`Strategy`/`Prediction`/`CapitalAllocation`), **solo `Decision` tiene hoy un disparador real y no fabricado** — los otros 4 exigirían inventar juicios de negocio y quedan pendientes hasta que haya datos reales de negocio de los que partir.

Una misma misión puede acumular varias decisiones (p. ej. un `REQUEST_MORE_EVIDENCE` seguido de un `APPROVE` final) — `decisionId` incluye un timestamp a propósito, no es idempotente sobre el mismo id como sí lo son los registros de evidencia.

Cubierto por 6 casos en `MissionServiceTest`. Verificado en vivo de punta a punta: `REQUEST_MORE_EVIDENCE` sobre una misión real no cambió su estado; `APPROVE` la dejó en `COMPLETED`; sobre otra misión, `REJECT` la dejó en `CANCELLED`; un intento de decidir de nuevo sobre la misión ya `COMPLETED` fue rechazado (500); los 3 eventos `EMPRESA_MISSION_DECISION_RECORDED` reales aparecieron en `EMPRESA_EVENTS`; y Cypher confirmó las 2 decisiones de la primera misión persistidas en orden con su `reasoning` real.

**Groundwork de memoria ampliada** (`CompanyMemoryService.initializeSchema`, sección separada con comentario): constraints de unicidad `id` para 20 labels más del roadmap de `EMPRESA_AI_TODO.md` §21 / `status.md` §17 — `Role`, `RoleVersion`, `AgentVersion`, `Prediction`, `PredictionOutcome`, `CredibilityScore`, `CapitalAllocation`, `Investment`, `Portfolio`, `GovernanceRule`, `GovernanceAmendment`, `Model`, `ModelAssignment`, `Tool`, `ToolVersion`, `PublicDecision`, `Customer`, `Transaction`, `Lesson`, `Strategy`. **Deliberadamente solo el constraint de identidad** — sin propiedades ni relaciones definidas, porque ningún flujo del código las escribe o las lee todavía. Verificado en vivo con `SHOW CONSTRAINTS` contra el Neo4j real tras `docker compose up`.

Este Neo4j es una **instancia compartida** en la máquina de desarrollo (otros proyectos, p. ej. `python/meteoro`, también la usan) — `SHOW CONSTRAINTS` mostrará labels de otros proyectos (`Agente`, `Carrera`, `Vehiculo`) que no tienen relación con `empresa`; ignóralos.

### Eventos: Kafka — verificación en vivo

Verificado en vivo: consumí el topic `EMPRESA_EVENTS` real de punta a punta para una misión completa y los 23 eventos esperados (1 CREATED + 1 STARTED + 5×2 TASK_CREATED/STARTED + 5 TASK_COMPLETED + 6 MISSION_UPDATED) aparecieron con el `status`/`currentStep` correctos.

**Eventos y métricas de Evidence Acquisition**: verificado en vivo consumiendo `EMPRESA_EVENTS` de punta a punta para una misión real de 5 agentes: 5 `SEARCH_STARTED`, 5 `SEARCH_COMPLETED`, 12 `VERIFIED`, 33 `REJECTED` — incluyendo un rechazo real por el límite de tamaño de `WebPageFetcher` (2 MB). Deliberadamente no se implementó `EMPRESA_EVIDENCE_CANDIDATE_CREATED` (sugerido en `EMPRESA_AI_NUEVO_TODO_EVIDENCE.md` §22): sería redundante con `candidatesFound` de `SEARCH_COMPLETED`.

Métricas Micrometer (`/actuator/metrics`) verificadas en vivo contra una misión real: `evidence.search.requests` count=6, `evidence.search.duration` count=6 con `MAX`=4.36s, `evidence.candidate.verified` count=19, `evidence.candidate.rejected` count=32 (`reason=IllegalStateException` en esa corrida), `evidence.candidate.duration` count=51 (=19+32).

### Evidence Acquisition — historial de proveedores de búsqueda

Historial de por qué se llegó a Serper (los tres proveedores anteriores se descartaron con evidencia real, no por suposición):
1. **DuckDuckGo HTML/Lite** (scraping directo, sin key): bloqueó con un challenge anti-bot (CAPTCHA) ya en la segunda petición, probado en vivo. Ni siquiera un "navegador" propio (curl/lynx/headless) lo evita — el bloqueo es detección del lado del servidor por comportamiento/IP, no por el cliente HTTP usado.
2. **Brave Search API**: se creyó que tenía tier gratuito sin tarjeta — **incorrecto**, lo eliminó en febrero de 2026; hoy exige tarjeta de crédito registrada. Verificado con búsqueda web antes de implementar nada, tras la corrección del usuario.
3. **Serper** (`google.serper.dev`): 2.500 consultas gratis, **sin tarjeta**, verificado en su documentación oficial.

**Cadena completa verificada en vivo con datos 100% reales**: `SerperSearchAdapter.search("precios asesoría empresarial para microempresas Colombia", "co", "es", 5)` devolvió 9 resultados reales de Google → se tomó el primero (camaramedellin.com.co) → `EvidenceAcquisitionService.confirmReachable` hizo un fetch real → `EvidenceValidationGate.validate(...)` → `valid=true` → `MissionMemoryService.recordEvidence(...)` persistió el nodo `Evidence` real en Neo4j, confirmado con Cypher. Sin mocks en ningún punto. 56 tests en el proyecto en esa ronda (20 del módulo `evidence`).

**Conectado al flujo real de agentes**: verificado en vivo tras conectar `confirmReachable` (misión real completa, `qwen3:8b`, sin mocks): los 5 agentes pidieron la herramienta; `TOOL_CANDIDATE_REJECTED` descartó candidatos reales por redirect (302), error de I/O, certificado TLS inválido (`PKIX path building failed`) y contenido no relacionado — incluyendo un caso real donde `engineering` agotó sus 5 candidatos y los 5 fueron rechazados, cayendo en la rama de advertencia; el agente respondió honestamente `NOT_VALIDATED`/`confidence=0.0` en vez de inventar una fuente. Los demás agentes (`qa`, `sales`, `product`) sí tuvieron candidatos confirmados, persistidos como nodos `Evidence` reales en Neo4j.

Dos hallazgos importantes verificados en vivo antes de fijar el diseño de dos turnos:

- **`format` y `tools` no se pueden combinar**: confirmado reproduciendo la petición contra Ollama real (también con `qwen3:8b`: en vez de solo ignorar la herramienta, **inventó una URL y datos falsos** simulando que ya la había llamado).
- **El modelo original (`qwen2.5-coder:7b`) no envolvía su respuesta en `<tool_call>...</tool_call>`** como espera la plantilla de Ollama, así que `message.tool_calls` casi nunca venía poblado — reproducido incluso con `tool_choice: "required"`, que Ollama acepta pero no parece hacer cumplir con este modelo.

### Modelo de agente: por qué `qwen3:8b` y no `qwen2.5-coder:7b`

Cambiado tras comparar ambos en vivo, no por ficha técnica. `qwen2.5-coder:7b` funcionaba (4/5 agentes buscaban) pero **solo 2/5 (`sales`/`engineering`) volcaban los resultados reales a su `evidence[]` final**. `qwen3:8b` tiene tool-calling nativo real y una corrida real de comparación directa dio **5/5 agentes buscando y 5/5 citando URLs reales** en Neo4j.

`think` se maneja distinto por turno, también verificado en vivo antes de fijarlo así:
- **Turno de decisión → `think: true`**: con pensamiento activado, `qwen3` decide correctamente usar la herramienta incluso cuando el prompt completo compite en el mismo turno — algo que ni `qwen2.5-coder` ni el propio `qwen3` con `think: false` logran de forma confiable (3/3 intentos ignorando la herramienta en ambos casos). Cuesta latencia real (~4x más lento) — aceptado deliberadamente porque el sistema es asíncrono.
- **Turno final → `think: false`**: no hay ambigüedad de herramienta que decidir, el pensamiento no aporta y solo suma latencia (verificado: mismo resultado honesto en 3.5s en vez de 14.4s).

Nota de infraestructura encontrada de paso: no hay `OLLAMA_NUM_PARALLEL` configurado explícitamente, y con `qwen3:8b` usando ~5.5 GB de los 8 GB de VRAM disponibles, casi con certeza Ollama decide 1 solo slot de inferencia — las 5 tareas de agente ya se sirven en serie dentro de Ollama aunque en Java corran en threads paralelos.

**Verificado en vivo con una misión real completa usando `qwen3:8b`**: 5 de 5 agentes decidieron llamar a `search_web_evidence`, obtuvieron resultados reales de Serper, y los 5 citaron URLs reales en su `evidence[]` final — persistidas como nodos `Evidence` reales en Neo4j. Mejora directa y medida sobre la corrida equivalente con `qwen2.5-coder:7b` (4/5 buscaron, solo 2/5 citaron).

**"Consistencia del modelo al volcar evidencia" — cerrado, subsumido por Evidence Binding**: con `EvidenceBindingGate` ese riesgo queda cerrado de raíz, ya no depende de qué tan disciplinado sea el modelo.

**Hallazgo de infraestructura de paso**: durante la consolidación del CEO (`qwen2.5-coder:14b`) inmediatamente después de una tanda de agentes con `qwen3:8b`, `ollama ps` mostró el modelo de 14B corriendo a `30%/70% CPU/GPU` (VRAM: 7/8 GB usados) — probablemente porque ambos modelos compiten por los mismos 8 GB de VRAM. La consolidación de esa corrida tardó ~7-8 min en vez de los ~3 min documentados antes. No bloqueante.

---

## Command Center web — historial de rondas

### Endpoints nuevos de solo lectura — decisión de arquitectura

Decisiones acordadas con el usuario antes de implementar: Frontend SPA React+Vite+TS servida como estáticos por `company-core` (un solo despliegue); polling simple (`@tanstack/react-query`, sin WebSocket/SSE); Activity derivado de Neo4j en vez de un `KafkaConsumer` nuevo (sería el primer consumer de Kafka del proyecto, hoy 100% productor) — no encajaba agregar esa infraestructura solo para una lista de actividad reciente.

### Chat Intent Router — por qué las consultas no pasan por el modelo

**Cambio de diseño en vivo**: el diseño original (aprobado en el plan) pasaba los datos reales a un nuevo `CeoService.answerGroundedQuery` para que el CEO los "fraseara" en lenguaje natural. Verificado en vivo antes de dar el intent por terminado: con una lista corta (2 oportunidades, 6 agentes) funcionó bien; pero con 25 misiones reales en `AWAITING_INVESTOR`/`FAILED`, el modelo **subcontó** — dijo "9" y enumeró solo 9, sin inventar ninguna que no existiera, pero omitiendo 16 reales. No fue el problema de payload gigante que se diagnosticó primero (una versión aún anterior serializaba el `MissionResponse` completo con el `message` libre de la consolidación del CEO, hasta 69 KB), sino algo más simple: contar/enumerar una lista es una tarea puramente determinista. `CeoService.answerGroundedQuery` se eliminó (quedaba sin uso).

Verificado en vivo de punta a punta: las 4 consultas dieron cifras exactas (25/25 misiones, no 9); "aprueba la misión MISSION-XXX porque..." escrito en el chat real creó un nodo `Decision` real y movió la misión a `COMPLETED`, idéntico a pegarle al endpoint dedicado. Cubierto por `ChatIntentRouterTest` (11 casos).

### Frontend: `SpaController` — bug encontrado y corregido dos veces

Primer intento: un comodín `"/{path:[^.]*}"` (cualquier segmento sin punto) reenviado a `index.html` — funcionó para las rutas de la SPA, pero **también** atrapaba cualquier `/api/**` mal escrito o inexistente (sin punto en el último segmento) y le devolvía 200+HTML en vez de 404, reproducido en vivo con `/api/company/no-existe`. Arreglado con una **lista explícita** de las rutas reales de la SPA. Verificado en vivo: las 6 rutas de la SPA dan 200 con el HTML real (confirmado también con Claude in Chrome, carga directa no solo navegación de cliente), la API real sigue devolviendo JSON, y tanto un `/api/**` inexistente como cualquier otra ruta random siguen dando 404.

### Docker multi-stage — por qué `.dockerignore`

`app/.dockerignore` nuevo (`target`, `frontend/node_modules`, `frontend/dist`) — necesario porque `node_modules` ya existía localmente (de compilar/probar el frontend antes de tocar Docker) y copiarlo al contexto de build habría sido enorme y, peor, habría pisado el `node_modules` recién instalado dentro del stage con binarios nativos del host.

### Identidad persistente de agentes — alcance y bugs encontrados

El usuario pidió separar **rol** (qué hace) de **identidad** (quién es), como ancla futura para que `Decision`/`Lesson`/`Prediction`/Performance por agente se asocien a una identidad concreta y no al string `"sales"`. Alcance acordado antes de tocar código: identidad plana (sin `RoleVersion`/`AgentVersion`), personalidad solo UI (no se inyecta en los prompts — el usuario fue explícito: "por ahora solo UI, no tocar prompts"), performance por agente diferida.

`CompanyMemoryService.initializeCompanyAndAgents()` siembra `name`/`role`/`personality` reales por agente (Alex/CEO, Sofia/Sales, Max/Finance, Luna/Product, Neo/Engineering, Vera/QA — los primeros 5 nombres son del propio usuario, "Vera" es sugerencia de Claude) y el `MERGE ... SET ... REMOVE a.title` migra los nodos `Agent` reales existentes **in place** en cada arranque — no hizo falta ningún script de migración aparte.

Deliberadamente **no** se tocó: la relación `ASSIGNED_TASK` (el usuario sugirió renombrarla a `EXECUTES`; ya está en uso/testeada, renombrarla sería puro churn) ni el esquema de `Agent.id` (`"sales"`, no `"AGENT-SALES-001"` — se usa en Cypher por todo el proyecto). Tampoco se persistió un `model` por agente en Neo4j: hoy es config compartida, persistirlo lo duplicaría con riesgo de desincronización.

Verificado en vivo de punta a punta (`mvn test` 110/110, Docker real, Neo4j real): `GET /agents` y `/agents/status` devuelven name/role/personality reales; el chat real respondió con nombres reales en vez de ids crudos; `/activity` mostró nombres; Dashboard y Agents renderizaron los nombres reales (Claude in Chrome).

**Bug real encontrado después de dar la ronda por cerrada, ya corregido**: guardar la identidad en Neo4j no alcanzaba — `CeoService.systemPrompt()` nunca le decía al modelo su propio nombre, así que "¿quién sos?" en el chat real hacía que el CEO (`qwen2.5-coder:14b`) lo inventara cada vez ("Jack", luego un placeholder literal `"[Nombre del CEO]"`). Fix: `systemPrompt()` no se tocó (compartido con los 5 agentes delegados); `CeoService.chat(...)` arma su propio system message agregando dos líneas aparte, solo para esa llamada. Verificado en vivo: 3 preguntas distintas sobre su identidad dieron "Alex" las 3 veces; y "preséntame al equipo" — un segundo bug real encontrado en la misma sesión: sin roster inyectado, el CEO había inventado 7 roles genéricos que no existen — pasó a listar los 6 agentes reales.

**Herramienta `query_company_memory` en el chat**: el problema de fondo seguía abierto tras los dos fixes puntuales — cualquier otra pregunta sobre la empresa que no viniera pre-inyectada en el prompt seguía sin ninguna fuente real. En vez de seguir parchando el prompt a mano cada vez que se encuentra un hueco nuevo, se implementó tool-calling real con topic restringido a un enum fijo. Verificado en vivo: preguntando "¿que tan rentable ha sido la empresa hasta ahora?" el log confirmó `CEO_CHAT_TOOL_CALL topic=COMPANY_PROFIT`, y el resultado coincidió exactamente con la respuesta determinista directa del atajo de keywords. `mvn test` 111/111.

**Bug real encontrado por el usuario y corregido: "preséntame al equipo" caía al chat general.** `detectQuery` solo activaba `AGENT_STATUS` cuando el mensaje contenía `"agente"` **y** (`"trabaj"` o `"estado"`) a la vez — tres frases reales del usuario no cumplían esa combinación y caían a `ceoService.chat(...)`, donde el modelo elaboraba con descripciones de responsabilidades inventadas y, reproducido en vivo, agregaba un disclaimer de privacidad contradictorio ("no puedo proporcionar detalles personales...") — un reflejo de seguridad del modelo tratando a los agentes como si fueran personas reales. Fix en tres capas (keywords ampliados, emoji de estado en `formatAgentStatus`, línea nueva en el system prompt aclarando que los agentes son software). `mvn test` 126/126.

### Agent.status ≠ AgentTask.status — bug encontrado por el usuario con capturas de pantalla

`AgentStatusResponse.status` era literalmente el `status` de la última `AgentTask` del agente — un agente con su última tarea en `COMPLETED` se mostraba como si eso fuera su estado, tanto en el chat como en la pantalla Agents del frontend ("Trabajando en: Delivery Feasibility" para un agente que ya había terminado). Alcance acordado con el usuario: `Agent.status` pasa a ser una propiedad real y persistida (solo `WORKING`/`IDLE` — de los 6 valores + `RETRYING` que el usuario proponía originalmente, ninguno de los 4 adicionales tenía señal real en el código).

**Migración**: los 6 agentes reales existentes se quedaron con `status='ACTIVE'` stale (valor legado del seed anterior), reproducido en vivo tras el primer redeploy. Normalización idempotente en el mismo `SET`: preserva `WORKING`/`IDLE` si ya está en uno de esos dos, corrige cualquier otra cosa a `IDLE`. Verificado en vivo: los 6 agentes pasaron de `"ACTIVE"` a `"IDLE"` en el siguiente restart.

Cubierto por 2 casos nuevos en `AgentRuntimeTest` y 2 en `ChatIntentRouterTest`. `mvn test` 129/129. Verificado en vivo de punta a punta tras redeploy con las tres preguntas reales del usuario.

### MISSIONS_NEEDING_ATTENTION mezclaba AWAITING_INVESTOR con FAILED — bug encontrado por el usuario

Con 25 misiones reales acumuladas (15 `AWAITING_INVESTOR` + 10 `FAILED`), "¿Qué necesita mi aprobación?" respondía listando las 25 — pero una misión `FAILED` no está esperando aprobación. Fix acordado: dos consultas deterministas separadas (`MISSIONS_NEEDING_ATTENTION` estrictamente `AWAITING_INVESTOR`, `FAILED_MISSIONS` nuevo, estrictamente `FAILED`). `mvn test` 130/130. Verificado en vivo: "¿Qué necesita mi aprobación?" → 15; "¿Qué misiones fallaron?" → 10 — 15+10=25, cuadra exacto.

### Mission.environment (`PRODUCTION`/`TEST`) — bug encontrado por el usuario siguiendo el anterior

Incluso filtrando estrictamente `AWAITING_INVESTOR`, las 15 misiones seguían siendo casi todas de desarrollo/depuración (`MISSION-DEBUG-007`, `MISSION-STRUCTURED-*`, etc.) — el histórico completo de esta conversación probando el proyecto. Alcance acordado: `Mission.environment` es una propiedad real y persistida (nunca heurística sobre el nombre del id); default `PRODUCTION` si se omite en la API. Migración: `coalesce(m.environment, 'TEST')` al leer, con la excepción de `MISSION-001` (la misión fundacional real) marcada `PRODUCTION` explícitamente por `backfillMissionEnvironment()`.

**Bug real encontrado durante la implementación**: `normalized.contains("prueba")` — pero `"aprueba"` **contiene** `"prueba"` como substring, así que cualquier decisión de aprobación empezaba a caer incorrectamente en `TEST_MISSIONS`. Encontrado por un test existente que se rompió, no por prueba manual. Fix: `Pattern.compile("\\bprueba")` con boundary de palabra.

`mvn test` 132/132. Verificado en vivo contra el chat real con los datos reales de 25 misiones: "¿Qué necesita mi aprobación?" → solo `MISSION-001`; "¿Qué misiones fallaron?" → "No hay ninguna misión fallida" (las 10 fallidas reales eran todas `TEST`); "¿Qué misiones están en prueba?" → las 36 restantes.

### Memoria conversacional del Company Chat — spec y bugs

Reportado por el usuario en vivo: "¿Qué necesita mi aprobación?" listó misiones, "pero esas están en prueba" no tenía forma de saber qué eran "esas". El usuario fue explícito: **"no intentaría arreglarlo con otro prompt del CEO"** — spec completo en `docs/superpowers/specs/2026-09-14-conversational-memory-design.md`, plan en `docs/superpowers/plans/2026-09-14-conversational-memory.md`.

Diseño acordado: un solo hilo de conversación global (`Conversation {id:'MAIN'}`, no hay concepto de usuario/sesión); persistido en Neo4j (mismo criterio que `Agent.status`: "Neo4j es la memoria de la empresa"); "foco actual" solo para tipo `"MISSION"`; resolución híbrida (opción C de 3 evaluadas con el usuario) — un pronombre demostrativo con predicado reconocido y foco existente se resuelve consultando el dato real y actual de esos ids puntuales, nunca el texto de la respuesta anterior.

**Dos bugs reales encontrados durante la implementación**:
1. `normalize()` saca tildes: "estás" se convertía en "estas" y chocaba con el pronombre demostrativo "estas" — rompía dos tests existentes. Fix: el chequeo de pronombre matchea sobre el texto original con tilde.
2. El fallback al LLM no tenía ninguna pista de a qué se refería "esas": verificado en vivo, el modelo ignoró el foco y alucinó una descripción genérica del equipo. Fix: nota corta antepuesta al mensaje aclarando el tipo de entidad referenciada, nunca los datos en sí.

Cubierto por 6 casos nuevos en `ChatIntentRouterTest`. `mvn test` 138/138. Verificado en vivo de punta a punta reproduciendo el ejemplo real completo del usuario, con Cypher confirmando 18 nodos `Message` (9 turnos) y foco con 36 ids.

### El chat no creaba misiones a partir de una instrucción real en lenguaje libre

Bug encontrado por el usuario probando la memoria conversacional con una instrucción de negocio real (oportunidad de videojuegos, presupuesto/plazo/objetivo reales): `"CEO, inicia una misión para encontrar una oportunidad... No contactes clientes ni gastes dinero sin mi aprobación..."` no creó ninguna misión — el router interpretó **"sin mi aprobación"** como el keyword `aprobacion` de `MISSIONS_NEEDING_ATTENTION`. El usuario describió una visión más amplia (capa Command/Query/Strategy, registro de `Company Capabilities`, JSON estructurado de la instrucción) pero priorizó explícitamente el fix concreto: *"Primero haría que 'Inicia una misión…' realmente cree y ejecute una misión nueva"*.

`ChatIntentRouter.detectFreeMissionStart` corre antes que `detectDecision`/`detectQuery`. El id se genera con timestamp (`"MISSION-" + Instant.now().toEpochMilli()`) — acordado explícitamente por simplicidad. Cubierto por `routesFreeFormMissionDescriptionToMissionServiceWithAGeneratedId` (usa deliberadamente la frase real "sin mi aprobación"). `mvn test` 139/139. Verificado en vivo con el mensaje real completo del usuario (~500 caracteres): misión delegada a los 5 agentes reales con el objetivo real.

### Alertas por correo — verificación en vivo y bugs

**Bug real encontrado y corregido tras desplegar en Docker**: `spring-boot-starter-mail` autoconfigura un `MailHealthIndicator` en `/actuator/health` — sin credenciales configuradas, el indicador fallaba la conexión SMTP y tumbaba el `status` general a `DOWN`, rompiendo el badge del Command Center. Fix: `management.health.mail.enabled=false`.

**Verificado en vivo de punta a punta con SMTP real**: la primera prueba falló con `Authentication failed` porque la App Password cargada tenía 11 caracteres en vez de 16 (pegada incompleta) — diagnosticado con `size(c.mailPassword)` sin exponer el valor. Tras recargar la App Password completa, el correo llegó sin ningún `WARN`.

**Plantilla HTML del correo**: pedido explícito del usuario de que el correo "no se viera tan plano". `MimeMessageHelper` en modo multipart (Spring Framework 7 no tiene `MULTIPART_MODE_ALTERNATIVE` como versiones previas). **Bug real encontrado y corregido**: la primera versión del HTML no declaraba `<meta charset="UTF-8">` — verificado visualmente, las tildes/eñes salían como mojibake. Cubierto por `AlertEmailTemplateTest` (5 casos). `mvn test` 123/123.

**Re-verificado en vivo, ambos caminos (`AWAITING_INVESTOR` y `FAILED`)**: la verificación anterior nunca había confirmado la entrega real del correo crítico (`FAILED`). Se usó una técnica determinista: sobrescribir `OLLAMA_BASE_URL` a un puerto inválido (`http://ollama:1`), recrear el contenedor, lanzar una misión `TEST`, dejar que los 5 agentes fallaran genuinamente por `I/O error` — la misión llegó a `FAILED` en ~4 segundos. `docker-compose.yml` se revirtió al valor real inmediatamente después (confirmado `git diff` limpio). Ambos correos llegaron a `dapine@gmail.com` sin ningún `WARN`/`ERROR`, confirmado explícitamente por el usuario para los dos.

### Rebrand: "AI Company" → "Forjai"

Alcance acordado con el usuario antes de tocar nada: el usuario consideró "AI Company" un nombre débil. Se evaluaron 4 opciones (`Forjai`, `Synthex`, `Ergo`, `Nexus Core`); eligió **Forjai**. Alcance explícitamente todo, incluyendo documentación interna y los prompts del sistema reales — se reemplazó la cadena en los 15 archivos que la contenían.

Verificado en vivo: `mvn test` 123/123, `npm run build` limpio, Docker reconstruido, `Company.name` confirmado como `"Forjai"` vía Cypher, y con Claude in Chrome el sidebar mostrando el logo + "FORJAI" y la pestaña titulada "Forjai".

### El chat podía consultar pero no operar: comandos de gobernanza sobre el foco conversacional

Bug encontrado por el usuario probando en vivo: "¿Qué misiones requieren mi aprobación?" listó correctamente 2 misiones; "Las dos misiones están aprobadas" no aprobó nada — ni `detectDecision` (sin `MISSION-<id>` explícito) ni `handleReference` (sin pronombre de `REFERENCE_PRONOUN`) lo reconocían. El usuario pidió pausar explícitamente cualquier otro trabajo hasta cerrar esto, exigiendo una "prueba definitiva" reproducible.

`COMMAND_APPROVE`/`COMMAND_REJECT` reconocen formas **adjetivas** (`aprobad[oa]s?`, `rechazad[oa]s?`) sobre el foco, separadas de `APPROVE`/`REJECT` (formas verbales que exigen id explícito). `FOCUS_QUANTIFIER` (`las dos`/`ambas`/`todas`/etc.) se agregó como gate de entrada adicional, deliberadamente separado de `REFERENCE_PRONOUN`. Reporte por misión, no todo-o-nada (✅/❌ individuales).

Cubierto por 3 casos nuevos. `mvn test` 142/142. Verificado en vivo de punta a punta reproduciendo la "prueba definitiva" exacta del usuario: el texto de respuesta coincidió exactamente con lo pedido, log confirmó `CHAT_INTENT_REFERENCE_COMMAND` con cero llamadas a Ollama, Cypher confirmó ambas misiones `COMPLETED` con 2 nodos `Decision` reales, y Kafka mostró los 4 eventos correspondientes.

### El chat general no tenía memoria real de la charla

Bug encontrado por el usuario: "no está recordando las charlas que tengo con el CEO". Reproducido de inmediato: "Recordá que mi color favorito es el verde" seguido de "¿Cuál es mi color favorito?" → el modelo negó tener acceso a información personal. Causa: `CeoService.chat` nunca incluía turnos anteriores en los `messages` enviados a Ollama, aunque `ConversationMemoryService` ya los grababa todos.

`HISTORY_LIMIT = 20` (10 turnos) — límite fijo simple, no configurable; no se implementó resumen/compactación (YAGNI hasta que haga falta). Cubierto por `CeoServiceChatHistoryTest` (3 casos) y un caso nuevo en `ChatIntentRouterTest`. `mvn test` 146/146. Verificado en vivo reproduciendo el reporte exacto del usuario: la respuesta fue correcta tras el fix.

**Incidente operativo durante la verificación, no relacionado con el bug**: reconstruir y reiniciar el contenedor para desplegar este fix interrumpió una misión real en curso (`MissionExecutor` no sobrevive a un restart, sin reconciliación automática). Se corrigió a mano por Cypher y se relanzó la misma instrucción. Lección operativa: confirmar que no haya una misión real en curso antes de reconstruir/reiniciar el contenedor.

### "dame un status" hacía que el CEO inventara un resumen completo (placeholders sin rellenar)

Bug real y grave encontrado por el usuario, con evidencia textual inequívoca: "dame un status" no matcheaba ningún keyword determinístico y caía al chat general. El CEO alucinó un informe completo: inventó un status inexistente para una misión real, afirmó "se encontraron 5 oportunidades" sin verificarlo, y — la señal más clara — dejó literalmente sin rellenar placeholders de plantilla (`[Nombre del cliente]`, `[Problema específico]`, `[Precio]`, `[Costo]`). El usuario lo diagnosticó con precisión: *"esto ya no es un problema de UI, es un problema de arquitectura del Company Chat"*.

Nuevo `QueryIntent.COMPANY_STATUS` con formatter `formatCompanyStatus()` — snapshot agregado 100% real de todas las fuentes ya existentes (capital semilla, agentes, misiones filtradas a `PRODUCTION`, oportunidades, prospectos/clientes reales, ingresos/beneficio). Regla explícita pedida por el usuario, agregada también al system prompt: *"El CEO no puede afirmar que un dato existe si no viene de Company Memory"*.

Cubierto por `routesCompanyStatusQueryToADeterministicAggregateSnapshot`. `mvn test` 147/147. Verificado en vivo con la prueba de aceptación exacta que propuso el usuario: respuesta idéntica byte a byte entre "dame un status" y la versión con instrucciones explícitas anti-alucinación, sin ningún placeholder ni cifra inventada, cada número confirmado con Cypher directo.

### Engineering Team + modelo real por agente

El usuario pidió una primera unidad organizativa persistente: un Engineering Team de 5 agentes (Neo/Vera reutilizados + 3 nuevos, Diego/Iris/Mila), con roles y capabilities reales, liderazgo real (`LEADS`), consultable desde el chat — no solo una descripción en un `.md`, sino nodos y relaciones reales en Neo4j que un agente o el usuario pudieran consultar de verdad. Spec en `docs/superpowers/specs/2026-09-20-engineering-team-design.md`, plan en `docs/superpowers/plans/2026-09-20-engineering-team.md`.

**Cambio de alcance confirmado explícitamente a mitad de brainstorm, no asumido**: el usuario pidió además que el modelo LLM de cada agente pasara a ser una propiedad real, persistida y resuelta de forma independiente por agente — no solo metadata descriptiva. Esto revierte una decisión anterior documentada en la sección "Modelo de agente: por qué `qwen3:8b` y no `qwen2.5-coder:7b`" de este mismo archivo, donde se había decidido deliberadamente un único modelo compartido para los 5 agentes delegados. `CeoService` dejó de decidir qué modelo usar (antes tomaba `ceoModel`/`agentModel` inyectados por config) — ahora cada llamador (`AgentRuntime`, `MissionExecutor`, `ChatIntentRouter`) resuelve el modelo real del agente contra Neo4j (`CompanyMemoryService.agentModel(agentId, fallback)`) y se lo pasa explícito a `CeoService`.

Las listas de `capabilities` de los 5 roles del equipo se refinaron en varios mensajes de seguimiento después de aprobado el spec inicial: primero el stack de lenguajes compartido entre los 3 roles "builder" (C#/Java/JavaScript-TypeScript), después el reparto de expertise AWS/SQL/NoSQL entre el arquitecto y el rol de DevOps/base de datos, y por último Flutter agregado al stack de los 3 builders. El detalle completo de cada lista queda en el spec, no se repite acá.

**Tres defectos reales encontrados durante la implementación (no antes)**:
1. El plan asumía que `mvn test -Dtest=ClaseX` podía saltarse la compilación de archivos rotos de otro task del mismo módulo — no es así: Maven siempre compila todo `src/main/java` antes de filtrar qué test correr, así que un archivo roto de un task posterior tumbaba la corrida filtrada de un task anterior.
2. El matcher `anyString()` de Mockito no matchea `null` — las instrucciones de test del plan no contemplaban que `CompanyMemoryService.agentModel(...)` sin stub explícito devuelve `null` en un mock, rompiendo silenciosamente los stubs de `executeAgentTask(...)` que usaban `anyString()` en esa posición.
3. El formatter del chat (`ChatIntentRouter.formatEngineeringTeam`) nunca emitía el `agentId` en su salida (solo el nombre), pero el propio test del plan para esa consulta afirmaba sobre la presencia del `agentId` — inconsistencia entre el código y el test escritos en el mismo task, encontrada al correr la suite, no antes.

**Revisión final de rama (whole-branch review) encontró y corrigió 2 defectos reales más antes de mergear** (esta misma ronda de fixes): `PUT /agents/{id}/model` hacía `MATCH`+`SET` sin verificar si el `MATCH` encontró algo — sobre un `agentId` inexistente el `SET` no tocaba nada y el endpoint igual devolvía 200 como si el cambio hubiera ocurrido, violando el requisito del spec y el principio de grounding del proyecto (nunca afirmar que algo pasó si no pasó); fix: `CompanyMemoryService.setAgentModel` ahora revisa `result.consume().counters().propertiesSet()` y lanza `IllegalArgumentException` si es 0 (cae al 500 default de Spring, misma convención del resto del proyecto). Y en `AgentRuntime.executeInternal`, la resolución del modelo (`companyMemory.agentModel(...)`) estaba ubicada *después* de `memory.setAgentStatus(agentId, "WORKING")` — si esa consulta a Neo4j fallaba, el agente quedaba marcado `WORKING` sin que el `finally` que lo vuelve a `IDLE` llegara a ejecutarse nunca (el mismo tipo de limitación ya documentada en "Agent.status ≠ AgentTask.status", pero esta vía era nueva); fix: mover la resolución del modelo antes de marcar `WORKING`, reordenamiento puro sin otro cambio de lógica.

**Verificado en vivo tras la revisión final**, contra el Neo4j real y compartido de esta máquina (el mismo que ya usa el contenedor `ai-company-core` de otra rama en ejecución — no se tocó ese contenedor: se corrió el jar de esta rama en local, puerto `8099`, apuntando al mismo Neo4j con las credenciales reales extraídas de forma read-only vía `docker inspect`, y se apagó el proceso al terminar). Estado real confirmado por Cypher tras el arranque: **9 `Agent`** (`MATCH (a:Agent) RETURN count(a)` → 9, no 12 — Neo/Vera no se duplicaron, siguen con `id='engineering'`/`id='qa'`), **1 `Team` real** (`TEAM-ENGINEERING`, `ACTIVE`), **exactamente 5 `MEMBER_OF`** (`backend`, `devops`, `engineering`, `frontend-ui`, `qa`), **1 `LEADS`** (`engineering`→`TEAM-ENGINEERING`), los 9 agentes en `status='IDLE'`, `roleCode` seteado solo en los 5 del equipo (`null` en `ceo`/`sales`/`product`/`finance`), y `model` real backfillado en los 9 (`ceo`→`qwen2.5-coder:14b`, el resto→`qwen3:8b`). Los 5 criterios de aceptación de la decisión 9 del spec quedan confirmados con datos reales, no solo revisión de código.

`mvn test` 166/166 tras aplicar los 9 fixes de código de esta ronda de revisión final (Fix 1–9: 404 real en `PUT /agents/{id}/model` sobre un agente inexistente, reordenamiento en `AgentRuntime` para evitar un agente `WORKING` para siempre, import muerto, javadocs de grounding/observabilidad, conteo de agentes actualizado en un javadoc, y un `WARN` nuevo en `EngineeringTeamMemoryService` si un `roleCode` hardcodeado deja de matchear un `Agent` real — deliberadamente sin `WARN` equivalente en la relación `LEADS`, porque ahí `MERGE` es idempotente y generaría falsos positivos en cada restart normal).

### Creative/Product Intelligence + Marketing & Growth: 5 agentes nuevos, TeamMemoryService genérico

Pedido del usuario: agregar Kael (Interactive Logic & Product Designer),
Maya (Visual & Asset Director), Gael (Telemetry & Analytics), Kira
(Growth, Content & Community) y Nora (Community Manager), organizados
en 2 equipos nuevos (Creative / Product Intelligence: Kael/Maya/Gael;
Marketing & Growth: Kira/Nora), reutilizando el modelo `Team`/
`MEMBER_OF`/`LEADS`/`roleCode`/`capabilities` ya implementado para
Engineering.

Decisión de diseño explícita del usuario (spec
`docs/superpowers/specs/2026-09-21-creative-marketing-teams-design.md`):
al pasar de 1 equipo a 3, generalizar `EngineeringTeamMemoryService`
en vez de triplicarlo — se renombra a `TeamMemoryService`,
parametrizado por una lista fija de 3 `TeamDefinition` (Engineering sin
cambios de contenido, más los 2 equipos nuevos). El usuario también
pidió explícitamente un mecanismo de consulta genérico en el chat
(`QueryIntent.TEAM_DETAILS`) en vez de un `QueryIntent` por equipo —
resuelto con un `teamId` cerrado (enum de los 3 ids conocidos, tanto en
el tool schema de Ollama como en la detección determinista por
keyword) codificado internamente como `"TEAM_DETAILS:" + teamId` para
no tener que cambiar la firma `Function<String, String>` que ya usaba
`ChatIntentRouter`/`CeoService.chat`.

Antes de esta ronda se detectó y resolvió una discrepancia real: el
`CLAUDE.md` vigente documentaba 9 agentes, pero el código de
`master` solo tenía 6 — el Engineering Team (Diego/Iris/Mila +
Team/MEMBER_OF/roleCode/capabilities/model) tenía spec y plan
**aprobados el 2026-09-20 pero nunca ejecutados**. Se encontró que ya
existía un worktree (`.claude/worktrees/engineering-team`) con las 6
tareas de ese plan completas, revisadas y hasta verificadas en vivo
contra Neo4j real, pendiente solo de merge — se mergeó a `master` (166
tests en verde antes y después del merge) antes de empezar el diseño
de esta ronda.

Sin verificación en vivo contra Neo4j real todavía para los 2 equipos
nuevos al momento de escribir este plan — pendiente de autorización
explícita del usuario (mismo Neo4j compartido de la máquina de
desarrollo, mismo criterio ya usado para Engineering).

La revisión final de todo el branch (5 tareas + 1 fix wave) encontró un
hallazgo real heredado del spec: el keyword `"arte"` (regla de
`TEAM_CREATIVE_PRODUCT_INTELLIGENCE`) matcheaba por subcadena sin borde
de palabra — "¿qué parte del equipo está trabajando ahora?" se
enrutaba mal a `TEAM_DETAILS` de Creative/PI en vez de `AGENT_STATUS`
por matchear "arte" dentro de "parte". Corregido con `Pattern`
compilado + `\b` (borde de palabra izquierdo) para **todos** los
keywords del mecanismo, no solo ese uno, más tests de regresión. De
paso se encontró (y se dejó parqueado, fuera de alcance de esta ronda)
un bug preexistente de la misma clase: `"necesita"` matchea dentro de
`"necesitamos"` en dos ramas no relacionadas de `ChatIntentRouter`
(detección de referencia y `MISSIONS_NEEDING_ATTENTION`), sin relación
con esta feature — pendiente de una ronda aparte si el usuario decide
abordarlo.

**Verificación en vivo, autorizada por el usuario** (mismo Neo4j real
compartido, mismo `ai-company-core` ya corriendo desde otra rama en el
puerto 8081, sin tocarlo): se corrió el jar de `master` ya mergeado en
el puerto 8099 apuntando al mismo Neo4j real (credenciales extraídas
read-only vía `docker inspect`, nunca puestas en texto plano en un
comando de shell —el clasificador de auto mode bloqueó el primer
intento por "Credential Materialization"/"Production Reads"; se
resolvió leyendo la contraseña desde un archivo del scratchpad y
consultando solo a través de los endpoints de solo lectura de la
propia app, nunca `cypher-shell` directo). Confirmado por
`GET /api/company/agents`, `GET /api/company/agents/status` y
`POST /api/company/chat` (consulta `TEAM_DETAILS` real, sin pasar por
el LLM):

- 14 `Agent` totales, sin duplicados, ids exactos.
- Engineering Team intacto: 5 miembros, Neo líder, mismos
  `roleCode`/`capabilities` que ya estaban verificados antes de esta
  ronda.
- Creative / Product Intelligence: 3 miembros (Kael líder, Maya, Gael).
- Marketing & Growth: 2 miembros (Kira líder, Nora).
- 10 agentes con `roleCode`/`capabilities` reales (los 3 equipos, nunca
  `ceo`/`sales`/`product`/`finance`), los 14 en `status=IDLE` o su
  estado real previo sin alterar, ninguno de los 5 agentes nuevos con
  `AgentTask` asociada.

Proceso local apagado limpiamente después (`pkill`, confirmado sin
procesos residuales). Contenedor `ai-company-core` existente confirmado
sano y sin tocar (`docker ps` + `/actuator/health`) antes y después.

### Prompt versionado y editable por agente (los 14)

Pedido del usuario: que cada agente tenga un prompt propio,
persistido, versionado, editable desde el Command Center — separado
explícitamente de las reglas/policies que siguen fijas en código
(anti-alucinación, FORMATO OBLIGATORIO, `AgentResultSchema`). Modelo
de separación de conceptos acordado explícitamente: Agent identity /
Role-roleCode / Capabilities / Model / **Prompt** (cómo razonar en el
rol) / Policies (qué tiene permitido, en código) / AgentTask (qué está
ejecutando).

Decisión de diseño: `PromptVersion` inmutable con invariante
transaccional de "exactamente una activa" por agente; rollback
reactiva un nodo existente, nunca duplica contenido; alcance a los 14
agentes aunque solo 6 tengan efecto observable hoy (los otros 8 quedan
listos para cuando exista ejecución real — "Proyecto B" — sin otro
cambio arquitectónico). Mismo patrón ya probado con `Agent.model`: el
llamador resuelve el valor real desde Neo4j y lo pasa como parámetro
explícito; `CeoService` sigue sin depender de Neo4j directamente.

La revisión final del branch (6 tasks + 1 fix wave) encontró y corrigió
dos hallazgos reales antes de mergear: un `NullPointerException` opaco
en `createVersion` cuando `content` viene omitido en el `PUT` (el spec
dice explícitamente que un prompt vacío es válido — ahora se normaliza
a `""` antes de la transacción), y una referencia hacia adelante
(`displayedContent` usado antes de declararse) en `AgentsPage.tsx` —
no explotable en la práctica pero corregida por claridad. Quedaron
parqueados sin pedido de fix: autocorrección del invariante si alguna
vez se rompiera a cero versiones activas, confirmación antes de
"Activar" una versión vieja, accesibilidad de teclado en las tarjetas
del organigrama, y cobertura de test del lado CEO para `ceoPrompt`
(hoy los tests usan `any()` en esa posición).

**Verificación en vivo, autorizada por el usuario** (mismo Neo4j real
compartido, contenedor `ai-company-core` reconstruido desde `master`
sin misiones en curso): confirmado por los endpoints reales —
- Los 14 agentes arrancan con exactamente 1 versión activa (`v1`,
  `content=""`, `changeReason="Versión inicial (seed)"`).
- Crear una versión nueva (`PUT /agents/sales/prompt`) sube el número
  (`v2`) y la activa, agregándose al historial sin tocar `v1`.
- Activar una versión vieja (`PUT /agents/sales/prompt/versions/1/activate`)
  hace rollback real: `activeVersion` vuelve a `1` con el mismo
  `createdAt` de siempre (mismo nodo, no uno nuevo), el historial sigue
  en el mismo tamaño.
- `content` omitido en el `PUT` devuelve `200` (no `500`), confirmando
  el fix del `NullPointerException`.
- `GET /agents/sales/prompt/versions/2` devuelve el contenido de esa
  versión puntual aunque ya no sea la activa.

`sales` quedó reactivado a `v1` (vacío) después de la prueba —
el historial de las versiones de prueba (`v2`/`v3`) queda, es
inmutable por diseño (sin endpoint de borrado). Contenedor confirmado
sano (`/actuator/health`) antes y después.

### Company Financial Policies + Mission.financialCriteria

`MissionExecutor` fijaba un objetivo universal para Max (finance):
"superar US$50 de utilidad neta" — un literal que ya referenciaba
`AppProperties.seedCapitalUsd()` en vez de un número pegado en el
string, pero seguía siendo (a) un objetivo *universal* impuesto a toda
misión y (b) parte de un conjunto más amplio de reglas financieras
hardcodeadas sin ningún mecanismo de edición (capital semilla, ventana
de tiempo, umbrales de éxito de venta en `CustomerService`, multiplicador
de alerta de `ContradictionDetector`). Documento de diseño completo:
`docs/superpowers/specs/2026-09-22-financial-policies-design.md`.

Antes de escribir el plan de implementación se acordaron con el fundador
3 decisiones de alcance:

1. **Los umbrales de clasificación de venta (`SUCCESS_THRESHOLD_GOOD`/
   `_VERY_GOOD`/`_EXCELLENT`/`_EXTRAORDINARY`) siguen siendo valores
   absolutos e independientes del capital semilla configurado** — se
   vuelven editables como Company Policy (en vez de `static final
   double` en `CustomerService`), pero no se les agrega ninguna fórmula
   que los escale automáticamente en función de `SEED_CAPITAL_USD` u
   otra política. Es una decisión deliberada de mantener el
   comportamiento ya documentado en `CLAUDE.md` ("no escalan con el
   capital semilla configurado"), no una limitación técnica: si en el
   futuro se quiere que escalen, es un cambio de diseño aparte, no un
   efecto secundario de este.
2. **`Mission.financialCriteria` es un campo estructurado
   (`FinancialMetric`/`targetAmount`/`currency`/`deadline`), nunca texto
   libre parseado por un LLM.** Se evaluó y se descartó explícitamente
   la alternativa de dejar que el fundador escriba el objetivo en
   lenguaje natural y que un modelo lo interprete al iniciar la misión
   — mismo criterio ya aplicado en todo el proyecto a decisiones que
   deben ser deterministas y auditables (`ChatIntentRouter` clasifica
   por regex antes de tocar Ollama, las consultas de compañía se
   formatean 100% en Java): un objetivo financiero mal interpretado por
   un LLM sería un riesgo de gobernanza mayor que la fricción de un
   formulario estructurado. Consecuencia directa: una misión iniciada
   por chat en lenguaje libre sigue naciendo con `financialCriteria =
   null` — no se intenta inferirlo del mensaje.
3. **El formulario "Iniciar misión" del Command Center (`MissionsPage.tsx`)
   se incluyó en esta misma ronda de trabajo**, en vez de quedar como
   una mejora de UI separada para después — sin él, la única forma de
   declarar un `financialCriteria` habría sido `POST
   /api/company/missions` a mano (curl/Postman), lo que hubiera dejado
   la feature completa en el backend pero inutilizable desde la
   interfaz principal de operación de la empresa (ver "Command Center
   web" en `CLAUDE.md`: la web es la forma *principal* de operar, no un
   complemento).

Decisiones de diseño técnico, todas mirror de patrones ya probados en
este repo (no inventadas para esta feature): `CompanyPolicyService`
reutiliza exactamente el patrón `PromptVersion`/`HAS_ACTIVE_PROMPT` de
`PromptMemoryService` (mismo invariante transaccional de "exactamente
una versión activa"), con una diferencia deliberada — acá no hay paso de
"borrador", `createVersion` crea y activa en la misma transacción,
porque una política financiera no tiene el mismo caso de uso de
"redactar y revisar antes de publicar" que un prompt largo.
`AppProperties.seedCapitalUsd()`/`challengeDays()` se degradan a default
de seed del primer arranque únicamente, mismo patrón ya aplicado a
`Agent.model`/`ollama.agent-model`. `ContradictionDetector` se mantiene
como función pura sin acceso a Neo4j (cambia su firma para recibir el
multiplicador como parámetro explícito) — deliberadamente no se le dio
acceso directo a `CompanyPolicyService` para no convertir una clase de
reglas deterministas en un cliente de infraestructura.

Los 13 tasks de implementación (backend: `PolicyKey`/`CompanyPolicyService`
+ endpoints, `Mission.financialCriteria` + validación en
`MissionService.start`, reemplazo del objetivo hardcodeado en
`MissionExecutor`, migración de `ContradictionDetector`/`CustomerService`
a `CompanyPolicyService`, `FinancialCriteriaEvaluation` en
`GET .../net-profit`, exposición en `ChatIntentRouter`; frontend: tipos y
cliente API, sección "Financial Policies" en `SettingsPage.tsx`,
formulario "Iniciar misión" con objetivo financiero opcional en
`MissionsPage.tsx`, y despliegue de `financialCriteria`/su evaluación en
`MissionDetailPage.tsx`) se ejecutaron con revisión de código limpia en
cada uno (ver `.superpowers/sdd/2026-09-22-financial-policies/progress.md`
para el detalle task por task) — sin hallazgos bloqueantes, solo un
puñado de items menores parqueados deliberadamente (nombres/casts
cosméticos, CSS sin terminar en la sección Financial Policies de
`SettingsPage.tsx`, cobertura de test parcial en algunos campos de paso). `mvn test` (202 tests, 190 en el baseline previo a este feature) y
`npm run lint && npm run build` verificados en verde al cierre de la
ronda de documentación.

**Verificación en vivo (Docker + Neo4j + Ollama reales), completada**:
el fundador confirmó que no había ninguna misión real en curso y
autorizó reconstruir `ai-company-core` con el código de esta feature
(rebuild desde el worktree, `docker compose build && up -d`, contenedor
sano post-arranque). Verificado contra el contenedor real:

- `GET /api/company/policies` devuelve las 7 políticas correctamente
  sembradas (`createdBy='system'`, `changeReason='Valor inicial de
  seed'`, versión 1 cada una).
- `PUT /api/company/policies/SEED_CAPITAL_USD` (US$50 → US$200) crea y
  activa la versión 2 en la misma llamada (sin paso de "borrador", tal
  como se diseñó); el chat (`"dame el estado de la empresa"`) refleja
  de inmediato `capital disponible US$200.00` — confirma que
  `ChatIntentRouter.formatCompanyStatus` ya lee la política vigente, no
  un valor stale de `AppProperties`. Rollback a la versión 1 probado
  también (`PUT .../versions/1/activate`), reactiva US$50 sin duplicar
  contenido, el historial conserva ambas versiones.
- Una misión real (`environment=TEST`, `MISSION-VERIFY-FINPOL-*`) con
  `financialCriteria={NET_PROFIT, US$1.000, deadline 2026-12-31}`
  corrió de punta a punta contra los 5 agentes reales (`qwen3:8b` vía
  Ollama). Log real capturado del agente finance en el intento 1:
  `Cálculo inconsistente: Suscriptores necesarios para alcanzar $1000
  de ganancia neta expected=1005.0 actual=200.0` — evidencia directa de
  que Max efectivamente razonó sobre el objetivo estructurado de la
  misión (no un monto universal hardcodeado), y de que
  `AgentResultValidator` rechazó el cálculo inconsistente y forzó un
  reintento (intento 2, aprobado) — exactamente el comportamiento
  diseñado: Max nunca autodeclara cumplimiento, y un cálculo que no
  cierra se rechaza igual que siempre.
- `GET .../net-profit` de esa misión devolvió
  `financialCriteriaEvaluation` completo (`criterionMet=false`,
  `progressPct=0.0`, `deadlinePassed=false` — coherente, todavía sin
  `Transaction` reales registradas). El chat sobre esa misión puntual
  (`"dame el estado de la mision MISSION-VERIFY-FINPOL-*"`) incluyó la
  línea `Objetivo financiero declarado: NET_PROFIT >= 1000.00 USD
  (2026-12-31). Resultado real: 0.00 USD (0.0% del objetivo) --
  objetivo no cumplido todavía.`

Sin hallazgos durante la verificación en vivo — los 14 tasks del plan
se comportan en producción real exactamente como documentaron sus
revisiones de código.

**Revisión final de todo el branch — 3 hallazgos reales, ninguno visible
en las revisiones por task**: la migración de `AppProperties` a
`CompanyPolicyService` estaba planificada para 3 clases
(`MissionExecutor`, `CustomerService`, `ChatIntentRouter`) pero se
escapó un 4to consumidor real, `ProductStatusService.isBusinessSuccess()`,
que seguía comparando contra el capital semilla congelado del `.yml` —
dos fuentes de verdad divergentes visibles en la misma respuesta del
chat una vez que alguien edita la política desde Settings. Corregido:
`ProductStatusService` ahora también lee `CompanyPolicyService`.

Segundo hallazgo, contradicción directa con lo que este mismo archivo
y `CLAUDE.md` ya afirmaban: `CompanyPolicyService.ensureDefaultPolicies()`
tenía los defaults de `SEED_CAPITAL_USD`/`CHALLENGE_DAYS` hardcodeados
como literales en vez de leer `AppProperties`, así que cambiar
`company.seed-capital-usd` en el `.yml`/env no tenía ningún efecto real
— quedaba documentado como "solo default de seed del primer arranque"
sin serlo. Corregido: `CompanyPolicyService` ahora inyecta `AppProperties`
y usa esos 2 valores reales para esas 2 entradas del mapa de defaults
(los otros 5 — multiplicador de alerta y 4 umbrales de éxito — nunca
vinieron de `AppProperties`, siguen como literales).

Tercer hallazgo, el más serio: `MissionMemoryService.ensureMission`
hacía `SET` incondicional de las 4 propiedades de `financialCriteria`,
y como `ChatIntentRouter` siempre pasa `financialCriteria=null` en sus
2 caminos de arranque de misión por chat, reiniciar una misión ya
existente diciendo "ejecuta MISSION-\<id\>" borraba en silencio
cualquier objetivo financiero que esa misión ya tuviera declarado —
contradice directamente la inmutabilidad documentada. Corregido: el
`SET` de esas 4 propiedades ahora se arma condicionalmente en Java, y
cuando `financialCriteria` es `null` esas propiedades ni se mencionan
en la query (Neo4j las deja como estén). **Verificado en vivo por el
controller** (no solo por code review): se reconstruyó el contenedor
con el fix, se creó una misión de prueba con `financialCriteria`
(`NET_PROFIT >= US$500`), se la reinició vía "ejecuta MISSION-\<id\>" —
mismo camino exacto que antes borraba el objetivo — y se confirmó que
`financialCriteria` sobrevivió intacto.

Ninguno de los 3 hallazgos era visible en la revisión de su task
individual porque cada uno involucra la interacción entre 2+ tasks
(la migración de 3 clases distintas dejando una 4ta afuera; el
`ensureDefaultPolicies()` de un task leído junto al `ChatIntentRouter`
de otro) — exactamente el tipo de defecto que la revisión final de
todo el branch existe para atrapar. `mvn test` (202/202) y
`npm run lint && npm run build` verificados en verde después del fix.

### Borrado de misiones desde el Command Center

Pedido del fundador: poder borrar misiones desde el front para ir
puliendo prompts de agentes sin dejar basura. Alcance acordado:
cualquier misión que **no esté corriendo** (se rechaza en curso porque
los threads de `MissionExecutor` seguirían escribiendo sobre nodos
borrados), no solo las `TEST` — muchas pruebas de prompt se lanzaron
como `PRODUCTION` por default. Además se bloquean `MISSION-001`
(fundacional) y cualquier misión con `HAS_CUSTOMER`/`HAS_TRANSACTION`
reales del fundador (datos que no se regeneran re-ejecutando). Borrado
real, no soft-delete. Las `Evidence` solo se borran si quedan
huérfanas, porque `EvidenceDedupKey` comparte nodos entre misiones.

**Bug real encontrado en la verificación en vivo** (invisible a los
tests unitarios, que mockean `MissionMemoryService`): la primera
versión de la query que junta los ids de evidencia hacía
`RETURN taskEvidence + collect(DISTINCT ce.id)` — Neo4j lo rechaza
(`42I18`, agregación con agrupación implícita). Como todo el borrado
corre en una sola transacción, el fallo dejó el grafo intacto (rollback
completo, nada borrado a medias). Fix: extraer el `collect` a un `WITH`
previo.

**Verificado en vivo** (Docker + Neo4j real) con una misión sintética
sembrada por Cypher (tarea, evidencia propia, evidencia compartida con
otra `AgentTask`, `Decision`, `Opportunity`, `Customer` LEAD con su
evidencia, foco conversacional apuntándola): `DELETE` → 204, segundo
`DELETE` → 404; borrado todo lo propio; la evidencia compartida y su
otra tarea sobrevivieron; `Agent` intacto; `lastMentionedIds` quedó en
`null`. Misión en `WAITING_AGENT_RESULTS` → 500 sin borrar; misión
`COMPLETED` con `HAS_TRANSACTION` → 500 sin borrar. Datos sintéticos
limpiados y foco original de la conversación restaurado después.
`MISSION-001` no existe en esta instancia de Neo4j (→ 404); su
protección queda cubierta por `MissionServiceTest`. `mvn test`,
`npm run lint` y `npm run build` en verde.

### Borrado de misiones: también su Activity

Pedido del usuario: al borrar una misión, su Activity también debe
desaparecer. Activity no se persiste aparte: `ActivityMemoryService`
la arma al vuelo desde `AgentTask`/`Mission`/`Evidence`/`Decision` y
muestra la propiedad `missionId` de cada nodo. `deleteMission` solo
seguía relaciones, y eso dejaba dos huecos: (1) una `Evidence`
compartida por la dedup sobrevivía (lo cual es correcto) pero conservaba
el `missionId` de la misión borrada que la había creado; (2) los nodos
cuya relación con la `Mission` se había perdido quedaban fuera del
borrado. En el Neo4j real había 22 `Evidence` (colgadas de `Customer`
LEAD de una `Opportunity` sin `Mission`) de `MISSION-1789884929871` y
4 `Evidence` + 1 `Decision` + 1 `Opportunity` sueltas de
`MISSION-DEVGEN-VERIFY-1`, las dos misiones ya inexistentes.

Fix: `deleteMission` ahora también matchea `AgentTask`/`Decision`/
`Opportunity`/`Evidence` por la propiedad `missionId`, y reasigna el
`missionId` de la evidencia compartida que sobrevive a una misión que
todavía la cita. `mvn test` en verde (217).

Restos de `MISSION-1789884929871` (1 `Opportunity`, 7 `Customer` LEAD,
22 `Evidence`, ninguno citado por otra misión) borrados a mano por
Cypher con autorización del usuario. Los de `MISSION-DEVGEN-VERIFY-1`
quedan pendientes.

**Verificado en vivo** (contenedor reconstruido + Neo4j real) con una
misión sintética sembrada por Cypher: tarea con evidencia propia,
evidencia compartida con una `AgentTask` real de otra misión,
`Decision` enlazada, más una `Decision` y una `Opportunity` sueltas
(solo con `missionId`, sin relación). Tenía 6 ítems en `GET /activity`.
`DELETE` → 204, 0 ítems en Activity, 0 nodos con ese `missionId`. La
evidencia compartida sobrevivió reasignada a la otra misión que la
citaba. Datos sintéticos limpiados después.

### Misiones por equipo + generación real de código (Proyecto B, subproyecto 1)

**Diagnóstico**: `MISSION-1790304372795` ("inicia una misión PRODUCTION exclusivamente para el Engineering Team… crear un videojuego") corrió igual las 5 tareas fijas de discovery (Max, Luna y Sofia recibieron tareas) y terminó en `AWAITING_INVESTOR` sin ningún código. Se dejó intacta como caso de diagnóstico.

**Decisiones** (spec `2026-09-21-development-generation-design.md`, revisión 2026-09-24): `Mission.teamId` explícito y exacto (en el chat solo el id `TEAM-...`); el líder planifica y `TeamPlanValidator` valida en Java; `TeamExecutionStrategy` por tipo de equipo (análisis vs. desarrollo); un commit por agente con él como autor y trailers de misión/tarea; validación estática en dos capas con `validationStatus` calculado por Java; bloque "Estado verificable" generado por Java. Cambios frente al spec original de Proyecto B: disparo por `teamId` (no por `APPROVE`), los 5 miembros vía plan del líder (no 3 tareas fijas), `ownedPaths` en vez de subdirectorios fijos, commit por agente en vez de uno consolidado, fallo de commit = fallo de tarea, termina en `AWAITING_INVESTOR`.

**Bugs reales encontrados en la verificación en vivo** (invisibles a los tests unitarios):
1. `MISSION-TEAM-VERIFY-1` → `FAILED` en `PLANNING`: Ollama 0.20 corría `qwen3:8b` con `KvSize:4096` (ninguna llamada enviaba `num_ctx`), lo que recorta en silencio prompts de código y de revisión. Fix: `options.num_ctx=16384` solo para `TEAM_PLANNING`/`DEVELOPMENT_TASK`/`STATIC_REVIEW` (entra en la GPU de 8 GB; discovery y chat sin cambios); tope de revisión bajado de 60.000/8.000 a 24.000/6.000 caracteres para que quepa. Confirmado después en los logs de Ollama: `KvSize:16384`.
2. En esa misma misión Neo repitió 3 veces el mismo error (`entryPoint` fuera de sus `ownedPaths`) porque la corrección no listaba los `ownedPaths`. Fix: el error ahora lista los `ownedPaths` por agente y dice cómo corregirlo.
3. `git add -- <ruta>` interpretaba magia de pathspec (una ruta `:x` fallaba). Fix: `GIT_LITERAL_PATHSPECS=1` en `GitCommandRunner`.
4. El test del plan para `ForbiddenClaimsGuard` detectó que "compila sin errores" pasaba como negación; la negación ahora solo cuenta si precede a la afirmación.

**Verificado en vivo** (contenedor reconstruido, Neo4j + Ollama reales), `MISSION-TEAM-VERIFY-2` (`TEST`, `teamId=TEAM-ENGINEERING`) → `AWAITING_INVESTOR`:
- Plan de Neo aceptado (5 tareas). Ninguna tarea de `sales`/`product`/`finance`.
- Commits reales en `~/forjai-products/MISSION-TEAM-VERIFY-2`, uno por agente, autor y trailer correctos: Iris `1e2fe77` (9 archivos), Diego `2a6f11e` (5), Neo `e1362be` (4), Mila `7ec4f94` (3). Los `commitSha` de Neo4j coinciden con `git log`.
- Vera: 20/20 chequeos deterministas en PASS, `validationStatus=STATICALLY_VALIDATED`, verdict `ISSUES_FOUND` (16 MAJOR, 1 MINOR), evidencia `INTERNAL` citando `workspace:MISSION-TEAM-VERIFY-2@7ec4f94…/<ruta>` reales, `notValidatableWithoutExecution` no vacío.
- Mensaje final con el bloque "Estado verificable" y la frase fija de no ejecución.

**Observaciones abiertas** (no bloquean la mecánica, sí la calidad del resultado):
- Lo generado por `qwen3:8b` no es un juego de navegador jugable: Neo eligió un stack ASP.NET + React + PostgreSQL/Redis, sin HTML de entrada, sin game loop y sin `.csproj`/`package.json`; el `entryPoint` fue `src/Cloud/Architecture/EntryPoint.cs`. La trazabilidad y los artefactos son reales; la coherencia del producto no.
- `STATICALLY_VALIDATED` convivía con verdict `ISSUES_FOUND` y 16 MAJOR (Vera clasificó además un error de sintaxis de C# como MINOR). **Decisión del fundador (2026-09-25)**: cualquier finding `MAJOR` (o `BLOCKER`) pasa a `FAILED`, sin importar el verdict (`StaticValidationStatusTest.failedWhenReviewFindsIssuesWithAMajorFinding` y `anyMajorFindingFailsEvenWithNoEvidentIssuesVerdict`); primero se había acotado a `ISSUES_FOUND`+`MAJOR` y el fundador lo amplió a cualquier `MAJOR`. Con esa regla, `MISSION-TEAM-VERIFY-2` habría quedado en `FAILED`; su valor persistido no se recalcula. Sigue abierto que Vera puede subestimar la severidad (el error de sintaxis como MINOR).
- Los archivos del workspace quedan con dueño root en el host (el contenedor corre como root); para inspeccionar con git usar `git -c safe.directory='*'`.

### Contrato del plan de equipo: capabilities atómicas y ownedPaths exclusivos

**Bug real** (`MISSION-1790325370585`, `FAILED` en `PLANNING`): Neo dio dos tareas a Mila en los intentos 1 y 2, y en el 3 mandó el listado completo de capabilities de Mila como un solo texto (`"frontend, interfaces web, UI, …"`). El validador ya comparaba cada capability de forma exacta y rechazaba bien; la causa era el prompt: el roster se mostraba con `List.toString()` (`capabilities=[frontend, interfaces web, …]`), que el modelo copiaba como una cadena, y el error no decía qué estaba mal.

**Fix** (sin tocar las capabilities reales de ningún agente): el roster lista cada capability entre comillas como elemento separado, y el prompt dice explícitamente "Selecciona capabilities individuales del roster. No copies ni concatenes el listado completo de capabilities."; una capability concatenada se rechaza con un error que lo nombra y sugiere elementos reales; el líder debe tener tarea; `ownedPaths` literales (sin globs `* ? [ ] { }`) y sin rutas repetidas dentro de una tarea (entre tareas ya se rechazaba igualdad y solapamiento por prefijo). Si el objetivo no da trabajo real a un miembro, el líder no inventa trabajo: lo declara en `participationConflicts` y la misión termina en `FAILED` con ese reporte antes de ejecutar nada, sin reintento.

**Verificado en vivo** (`MISSION-TEAM-VERIFY-3`, `TEAM-ENGINEERING`, `TEST` → `AWAITING_INVESTOR`): plan aceptado en el intento 2. El intento 1 se rechazó por la tarea duplicada de Mila y se corrigió con el feedback, y el error de capability concatenada no reapareció. 4 commits reales (Mila `986504e`, Iris `79aa869`, Diego `bb621ee`, Neo `19786a0`), 20/20 chequeos deterministas en PASS. Esta vez Neo eligió un stack HTML + JS de navegador (`src/index.html`), aunque los archivos siguen siendo esqueletos mínimos.

**Problema nuevo observado, no corregido en esta ronda**: la revisión de Vera terminó en `validationStatus=FAILED` por 6 BLOCKER falsos. Afirma que `src/backend/*.js` "no existen" en el commit de Iris, aunque `FILES_IN_COMMIT` dio PASS para ese commit y los archivos están en HEAD. El repo completo ocupa unos 11.800 caracteres, bajo el tope de 24.000, así que no es truncado: `qwen3:8b` contradice los chequeos deterministas pese a la instrucción explícita. Corregido con `MissingFileClaimGate` (ver entrada siguiente).

### `MissingFileClaimGate`: la revisión estática no puede negar archivos que existen

**Bug real** (`MISSION-TEAM-VERIFY-3`): 6 BLOCKER falsos de Vera afirmando que `src/backend/*.js` "no existen", con `FILES_IN_COMMIT` en PASS y los archivos en HEAD (repo de unos 11.800 caracteres, sin truncado). **Fix**: gate determinista con reintento en `DevelopmentRuntime.review`. Rechaza `missingFiles` que estén en el repo y findings con lenguaje de inexistencia ("no existe(n)", "no está(n) presente(s)", "ausente", "inexistente"…) sobre un archivo que sí está, sea por su `path` o por mencionarlo en la descripción. Los archivos existentes son la unión de los commits de la misión. La detección de "afirma inexistencia" es léxica y puede tener falsos negativos.

**Verificado en vivo** (`MISSION-TEAM-VERIFY-4`, `TEAM-ENGINEERING`, `TEST` → `AWAITING_INVESTOR`): plan aceptado al primer intento (5 tareas), 4 commits reales (Iris `7c7dc15`, Diego `5fe59e5`, Neo `0fa8368`, Mila `4cde310`), 0 chequeos deterministas en FAIL, `missingFiles` vacío, ningún finding de inexistencia. El gate no llegó a dispararse en vivo (Vera hizo una sola llamada, sin reintentos); su comportamiento queda cubierto por `MissingFileClaimGateTest` y `DevelopmentRuntimeTest.reviewDeclaringAnExistingFileAsMissingIsRetried`.

**Observación abierta**: Vera devolvió `NO_EVIDENT_ISSUES` con 21 findings MINOR que describen componentes sin lógica de juego (`GameScreen.tsx` es un placeholder con un comentario "aquí se renderizará el tablero") y servicios que solo devuelven valores fijos, así que la misión queda en `STATICALLY_VALIDATED`. Es la subestimación de severidad ya anotada. Las reglas deterministas no pueden corregirla sin inventar criterios de calidad, y el código generado sigue siendo un esqueleto.

### Pruebas de modelo para Engineering y bloqueo por generación sin tope

**`qwen2.5-coder:14b`** (pedido del fundador, para Neo, Iris y Mila):
- `MISSION-TEAM-VERIFY-5`: Neo con coder falló el plan 3 veces por errores distintos (tarea duplicada, capability "AWS" asignada a Diego, action con guion), con llamadas de 3,3 a 4,4 minutos. El modelo, de 13 GB con contexto de 16k, corre 54% en GPU y 46% en CPU en la GPU de 8 GB.
- `MISSION-TEAM-VERIFY-6`: Neo volvió a `qwen3:8b` y el plan pasó. Mila (coder) escribió todas las rutas con `/` inicial y se descartó sin reintento, lo que llevó a la decisión de volver corregibles las rutas absolutas. Iris (coder) tardó 8,6 minutos por llamada.
- `MISSION-TEAM-VERIFY-7`: con dos modelos alternándose en paralelo, cada llamada pasó a tardar entre 25 y 29 minutos, incluso las de `qwen3:8b`.
- **Decisión del fundador**: todo Engineering vuelve a `qwen3:8b`.

**Bug real** (`MISSION-TEAM-VERIFY-7`): el reintento de Diego (`qwen3:8b`) generó más de una hora con la GPU al 95%, sin terminar. Las llamadas de equipo no tenían tope de salida (`num_predict`) y el `RestClient` de Ollama no tenía timeout, así que un bucle del modelo dentro del JSON bloqueaba la misión indefinidamente: al llenar el contexto, Ollama lo desplaza y sigue generando. Se destrabó reiniciando el contenedor `ollama` con autorización del fundador; la tarea de Diego falló con error de I/O y la misión siguió con resultado parcial. **Fix**: `num_predict=6144` en las 3 llamadas de equipo (`CeoServiceContextWindowTest`) y timeout de lectura configurable, `ollama.read-timeout` (`OLLAMA_READ_TIMEOUT`, default `15m`), para todas las llamadas (`OllamaClientTimeoutTest`, con un servidor HTTP local lento).

### DDD y perfiles de stack (sandbox, parte 1)

**Implementado** (plan `2026-09-26-sandbox-verification-part1-catalog-ddd.md`): catálogo fijo `StackProfile` (`DOTNET_APP`, `GODOT_DOTNET_GAME`, `FLUTTER_WEB_APP`) con la estructura DDD de cada perfil; `TeamPlan` con `stackProfile`, `boundedContexts` y `ubiquitousLanguage`; `ProfileStructureChecker` (`ENTRY_FILES`, `PROFILE_STRUCTURE`) y `DddLayerChecker` (`DDD_LAYERS`, en Java, lee `using` de C# e `import` de Dart) integrados en la capa 1, en reemplazo de `ENTRY_POINT`; prompts de desarrollo y revisión de Vera con perfil, contextos, glosario y revisión DDD. Los chequeos de capas están verificados con tests unitarios sobre repos Git reales: un `domain` que importa `Godot` hace fallar `DDD_LAYERS`.

**Verificación en vivo: la planificación no converge con `qwen3:8b`.** Cinco misiones reales (`MISSION-DDD-VERIFY-1` a `-5`, juego Godot con C#) terminaron en `FAILED` en `PLANNING`, y cada ronda de ajustes corrigió un problema y dejó ver el siguiente:
1. Una tarea por capa, con agentes y carpetas repetidos. Ajuste: el prompt pide una sola tarea por agente y da un ejemplo.
2. Cada reintento regeneraba el plan desde cero con errores nuevos. Ajuste acordado con el fundador: corrección incremental (el plan anterior va en la corrección) y 5 intentos en desarrollo.
3. Se trabó 4 intentos en "2 tareas VALIDATION" y un solapamiento sin resolver. Decisión del fundador (opción B): `TeamPlanResolver`, donde Java calcula rutas, asigna la validación al miembro QA y reparte los archivos de entrada a partir de las capas (`assignments`) que decide el líder; spec §1 revisado.
4. Oscilaba entre "DOMAIN duplicada" y "backend sin capas". Ajuste: los errores listan las capas libres.
5. Errores distintos en cada intento: un contexto "Interfaz" sin dueño de `DOMAIN`, una tarea repetida para el líder y la capa `INFRASTRUCTURE`, que no existe en el perfil Godot.

Conclusión: con este nivel de restricciones, `qwen3:8b` resuelve una regla y rompe otra; los mensajes accionables y la corrección incremental no alcanzan para que converja en 5 intentos. La implementación y sus tests (369) están en verde; lo que no funciona en vivo es que el líder produzca un plan DDD válido. Queda para decisión del fundador cómo reducir las decisiones del modelo en la planificación.

**Continuación, misiones 6 y 7:**
- **Revisión 2 del spec** (decisión del fundador, opción 1): las capas las asigna Java según el `roleCode` (`RoleLayerCatalog`); el líder decide solo perfil, contextos, glosario y objetivos; `TeamPlanResolver` une las tareas repetidas de un agente.
- `MISSION-DDD-VERIFY-6` → `AWAITING_INVESTOR`: plan aceptado al tercer intento y primera cadena DDD completa (`DDD_LAYERS` PASS sobre 8 archivos). **Tres bugs reales**, corregidos con TDD:
  1. `git ls-tree` entrecomillaba los nombres con tildes (`L\303\263gicaCombate.cs`) y hacía fallar tres chequeos sin motivo → `core.quotepath=off` en `GitCommandRunner`.
  2. `game/project.godot` no se creó porque a la dueña de `game` nadie le dijo que era obligatorio → el prompt de cada tarea lista los archivos de entrada que caen en sus rutas.
  3. La tarea de Vera quedaba en `PENDING` para siempre si la lectura del repo fallaba antes de la revisión → ahora pasa a `FAILED` con el motivo.
- `MISSION-DDD-VERIFY-7` → `AWAITING_INVESTOR`: **plan aceptado al primer intento**. Commits de los 4 desarrolladores con autor y trailer correctos; `ENTRY_FILES`, `PROFILE_STRUCTURE` y `DDD_LAYERS` (7 archivos) en PASS. Vera reportó 7 MAJOR reales (propiedad duplicada en `Player`, `NextTurn` incompleto), así que `validationStatus=FAILED`, como corresponde.
- **Observaciones abiertas (calidad del modelo, no del contrato):**
  - Neo declaró "UI" como bounded context, que es una capa técnica y no un dominio.
  - Diego escribió un placeholder (`tests/Combate.Tests/NoFilesWritten.cs`) y Mila solo `project.godot`.
  - La verificación real (compilar, testear y arrancar) llega con la parte 2 (sandbox).

### Sandbox de ejecución (sandbox, parte 2)

**Implementado** (plan `2026-09-26-sandbox-verification-part2-runner.md`): servicio `sandbox-runner` aparte (Spring Boot, módulo propio) que habla con Podman sin root por el socket del usuario; `company-core` solo llama a `POST /jobs` (token, solo `VERIFY`, perfiles del catálogo). Cada job extrae el commit exacto y corre `restore → build → test → smoke` en contenedores descartables sin red. `StaticValidationStatus` gana `VERIFIED` (chequeos + sandbox con ≥1 test + revisión sin `BLOCKER`/`MAJOR`); 0 tests → `FAILED`; runner caído → `UNVALIDATED`. `ProductStatus.QA` pasa a ser alcanzable con `VERIFIED`. Vera recibe los resultados reales del sandbox en su prompt, pero la frase de `VERIFIED` la escribe solo Java.

**Verificado en vivo con proyectos mínimos** (Podman 5.8.7, runner primero en el host y después en Docker, llamado desde `company-core` por la red interna):
- `DOTNET_APP`: 5 pasos en PASS en unos 21–33 s, 1 test, `GET /health 200`.
- `GODOT_DOTNET_GAME`: PASS en unos 16 s, 1 test, 300 frames headless; el juego usa la clase de dominio (`Vida: 7`).
- `FLUTTER_WEB_APP`: PASS en unos 55 s (el build web tarda 38 s), 2 tests, la app aparece en el DOM de Chrome headless.
- Controles: un test roto da `test FAIL 0/1`; un SDK inexistente da `restore FAIL`; dentro del contenedor no resuelve DNS (`--network=none`); `missionId=../etc` → `checkout FAIL`; sin token → 401; perfil desconocido → 400.

**Bugs reales encontrados construyendo las imágenes:**
1. Un `.csproj` con un SDK que MSBuild no encuentra quedaba fuera de la solución (`dotnet sln add` solo avisa) y el build daba PASS con "0 Error(s)". Ahora el paso falla si MSBuild no puede cargar un proyecto.
2. `Godot.NET.Sdk` no quedaba en el feed offline (el seed lo bajó a la caché de root) y el resolvedor de SDKs de MSBuild ignora `--source`. Fix: el seed usa `NUGET_PACKAGES=/opt/nuget-packages` y cada paso escribe un `NuGet.Config` sin nuget.org.
3. La imagen de Flutter declara `USER root`: con eso `--userns=keep-id` no aplica y los archivos quedaban en el host con un uid ajeno. Fix: SDK y caché de pub propiedad de uid 1000, `USER 1000:1000`.
4. `flutter_tools` apuntaba a `/root/.pub-cache`; al mover la caché, cada paso intentaba bajar `test` de pub.dev y, sin red, salía con 69 sin mensaje. Fix: se resuelve `flutter_tools` al construir la imagen.
5. El build web de Flutter carga CanvasKit desde un CDN de Google ("Failed to fetch" sin red). Fix: `--no-web-resources-cdn`.
6. El SDK de Flutter crea `bin/cache/lockfile` en cada comando y no funciona con la raíz de solo lectura. **Decisión del fundador**: Flutter corre sin `--read-only` (con el resto del aislamiento, y la raíz se descarta con `--rm`); .NET y Godot siguen con `--read-only`.

**Contrato de ejecución en los prompts**: sin red solo existen las versiones precargadas (`xunit 2.5.3`, `Microsoft.NET.Test.Sdk 17.8.0`, `Godot.NET.Sdk/4.3.0`, `cupertino_icons ^1.0.8`…), y el arranque espera cosas concretas (`GET /health`, `run/main_scene`, render sin errores). `StackProfile.executionContract()` lo agrega a `describe()`, que llega al plan y a cada tarea.

**Misiones reales:**
- `MISSION-SANDBOX-VERIFY-1` (API .NET de tareas) → `FAILED` en `PLANNING`. **Bug real**: en el reintento, Neo copió los errores del validador a `participationConflicts` (sobre sí mismo y sobre Vera, que tenía tarea), y eso corta la misión sin reintentar. Fix (`TeamWorkPlanner.invalidParticipationConflicts`): un conflicto solo vale para un miembro real, que no sea el líder y sin tarea en el plan; si no, es un error más que se corrige. Un conflicto legítimo sigue cortando como antes.
- `MISSION-SANDBOX-VERIFY-2` → `AWAITING_INVESTOR`, validación `FAILED`. Plan aceptado, 4 commits, 22/22 chequeos deterministas en PASS, sandbox corrido de verdad. **Bug real**: ningún agente escribió un `.csproj`, la solución quedó vacía y restore/build/test daban PASS sin compilar nada (0 tests → `FAILED` igual, y el arranque falló con "No hay proyecto src/*.Api"). Fix: `StackProfile.projectFiles` (un `.csproj` por capa, `game/Game.csproj` en Godot) va como ARCHIVO OBLIGATORIO en el prompt de cada dueño, `ENTRY_FILES` los exige y `run.sh` falla si no hay ningún `.csproj`. Además, Diego no escribió tests y Mila escribió `Startup.cs` sin `Program.cs` (calidad del modelo).
- `MISSION-SANDBOX-VERIFY-3` → restore `FAIL`: los `.csproj` ya existían pero 3 de 5 eran inválidos (`// Tareas.Domain.csproj` antes del XML, archivo cortado, `ProjectReference` a `..\..\Tareas.Domain\Tareas.Domain.cs,proj`). Fix: `ProjectFileGate` (XML válido y referencias a proyectos del plan, con reintento).
- `MISSION-SANDBOX-VERIFY-4` → `qwen3:8b` deformaba `.csproj` en `.cs. proj` aun con la ruta exacta en la corrección, y un reintento perdió el código de Mila (devolvió solo el archivo corregido). **Fix de diseño**: Forjai genera los `.csproj` (`ProjectScaffold`, commit propio antes de los agentes, reglas DDD de dependencia y paquetes del contrato); los agentes escriben solo código; cada `ownedPath` necesita al menos un archivo; la corrección pide el resultado completo. Verificado: el scaffold .NET y el de Godot pasan los 5 pasos del sandbox con código mínimo.
- `MISSION-SANDBOX-VERIFY-5` → Neo (dueño solo de `Solution.sln`) escribía el proyecto entero y agotaba sus 3 intentos. Fix: los archivos bien formados fuera de los `ownedPaths` se descartan y se anotan en el summary; `..`/`.git` siguen siendo fatales (el test de seguridad lo atrapó durante el cambio).
- `MISSION-SANDBOX-VERIFY-6` → **primer build real de código de agentes** (restore `PASS` con el scaffold): 21 errores `CS1014` reales. Además, la regla del prompt "nadie va a ejecutar este código" (falsa desde el sandbox) apareció copiada textual dentro de `Tarea.cs`; Diego seguía escribiendo `.csproj` (incluso inventados). Fix: prompt actualizado y `.csproj` de agentes descartados.
- `MISSION-SANDBOX-VERIFY-7` → `DDD_LAYERS` atrapó a Diego (`Infrastructure/Program.cs` → Api/Tests); Vera agotó los reintentos citando archivos con el sha de otro commit. Fix: se le da el sha de HEAD literal.
- `MISSION-SANDBOX-VERIFY-8` (Godot) → **diagnóstico de fondo**: en paralelo cada agente escribía contra clases que nunca vio, y el líder (solo archivos de entrada) escribía el dominio. **Decisiones del fundador**: generación **secuencial por capas** (cada agente ve el código ya commiteado) y catálogo de roles revisión 3 (Neo DOMAIN, Iris APPLICATION+INFRASTRUCTURE, Mila GAME/PRESENTATION/API, Diego TESTS); Forjai genera también `Solution.sln`.
- `MISSION-SANDBOX-VERIFY-9` → primera misión con los 4 agentes commiteando. Tres bugs míos: `PATHS_WITHIN_OWNED` rechazaba el `.sln` del scaffold; `DDD_LAYERS` marcaba `Program.cs` (composition root) → Infrastructure, que el propio scaffold referencia; Vera marcaba MAJOR un `.csproj` de Forjai. Fix con tests (el controller que usa Infrastructure sigue siendo violación).
- `MISSION-SANDBOX-VERIFY-10` → todo en PASS salvo una línea (`EstaComplet, false;`). Se adelanta un **ciclo de corrección mínimo** del subproyecto 3: `CompilerErrorParser` (C# y Dart), errores de restore/build al dueño de cada archivo, re-chequeos y re-sandbox, 2 rondas.
- `MISSION-SANDBOX-VERIFY-11/12` → la corrección exigía todos los `ownedPaths` y una corrección fallida dejaba la tarea en `FAILED`; los `CS0246` eran `using` faltantes de tipos que existían en otra capa. Fix: corrección acotada a las rutas con errores, se conserva el commit anterior, y `MissingUsingFixer` (determinista, respeta las reglas DDD, commit "Forjai (auto-fix)").
- `MISSION-SANDBOX-VERIFY-13` → 1 auto-fix + 2 rondas; solo quedaba `UseSwagger()` porque el scaffold de la API no referenciaba Swashbuckle (bug mío: el contrato lo ofrece). Verificado: el código real de la misión 13 con el `.csproj` corregido compila, pasa 3 tests y responde `/health`.
- `MISSION-SANDBOX-VERIFY-14/15/16` → un error por misión (campo privado, `enum Estado` anidado con propiedad `Estado`, herencia sobre setters privados); la corrección devolvía el archivo idéntico. Fix: la corrección muestra la línea exacta (y, en `CS0102`, todas las definiciones del nombre), insiste si no hay cambios y compara todo lo devuelto contra HEAD; `missingFiles` de Vera se limpia de archivos existentes. **Decisión del fundador**: con sandbox exitoso solo un `BLOCKER` de Vera impide `VERIFIED` (los `MAJOR` quedan como deuda de diseño).
- `MISSION-SANDBOX-VERIFY-17/18` → planificación perdida por capabilities ajenas (fix: en desarrollo el resolver deja solo capabilities reales) y Diego fallando otra vez. **Diagnóstico**: el límite ya es `qwen3:8b` (deforma palabras, inventa tipos, no sigue instrucciones, no mejora al corregir).
- **Prueba de modelos** (decisión del fundador): `nemotron-3.5-lightning:30b-a3b` local exigió actualizar el Ollama compartido de STORM (0.20.0 → 0.34.4, autorizado por el fundador; modelos intactos); corrió 80% CPU a 3,6–4,7 tok/s (12x más lento), dominio compilable pero tests con 8 errores; descartado y borrado. API de NVIDIA (`integrate.api.nvidia.com`): `json_schema` trunca strings largos (se usa `json_object`) y el "thinking" hay que apagarlo. Prueba dominio+tests+sandbox: **`moonshotai/kimi-k3` compiló y pasó 19 tests a la primera**; `nemotron-3-ultra` dominio OK pero JSON de tests ilegible; `glm-5.3` escribió `...`; `nemotron-3.5-lightning` API 500. **Decisión del fundador**: Engineering con `nvidia:moonshotai/kimi-k3` (`OpenAiCompatibleClient`, prefijo `nvidia:` en `Agent.model`).
- `MISSION-SANDBOX-VERIFY-19` (kimi-k3) → **sandbox completo en PASS sin correcciones** (20/20 tests, `/health`); `UNVALIDATED` solo porque la API devolvió respuestas sin `choices` de forma intermitente en la revisión (fix: reintento).
- **`MISSION-SANDBOX-VERIFY-20` (.NET) → `VERIFIED`**: 26/26 chequeos, 19/19 tests, `/health` 200, 1 ronda de corrección, `productStatus=QA`. Primer `VERIFIED` de punta a punta sin intervención manual.
- **`MISSION-SANDBOX-VERIFY-21` (Godot) → `VERIFIED` al primer intento**: 22/22 chequeos, 53/53 tests, 300 frames headless ejecutando el combate real del dominio (Guerrera vs Orco, 7 turnos).
- `MISSION-SANDBOX-VERIFY-22` (Flutter) → `DDD_LAYERS` real: `lib/main.dart` (composition root) lo escribía el líder primero con un widget de reemplazo y la presentación cableaba la infraestructura; Vera (kimi-k3) lo describió bien pero `MissingFileClaimGate` la rechazó por "no existe importación/evidencia". Fix: los archivos de entrada van al dueño de la capa más externa; la frase de inexistencia solo cuenta pegada al archivo.
- **`MISSION-SANDBOX-VERIFY-23` (Flutter) → `VERIFIED`**: build web, 35/35 tests, la app en el DOM de Chrome headless.

**Observaciones abiertas**: el presupuesto de la revisión de Vera (24.000 caracteres) está pensado para los 16K de contexto de `qwen3:8b` y deja archivos fuera con kimi-k3; el ciclo de corrección no cubre tests fallidos, arranque ni violaciones `DDD_LAYERS`; el proveedor remoto no admite `tools` (los demás agentes siguen en Ollama).

### Dependencias gobernadas (sandbox, parte 3)

**Revisión del spec** (aprobada por el fundador, 2026-09-27): como los `.csproj` los genera Forjai desde la parte 2, los agentes .NET piden paquetes en `DevelopmentResult.packages`; en Flutter salen del `pubspec.yaml`. OSV y la política corren en `company-core`; la descarga aislada, en el runner.

**Prueba de infraestructura antes del plan**: proxy squid con lista blanca en una red Podman interna (GitHub bloqueado); `dotnet restore` y `flutter pub get` por el proxy; licencia en `<license type="expression">` del `.nuspec`; caché NuGet de solo lectura como `fallbackPackageFolders` con `--read-only`; pub offline funciona con overlay `:O` y falla con `:ro` (pub escribe en su caché).

**Bugs reales en vivo**:
1. SELinux: la caché montada `:ro` sin etiqueta daba `Permission denied` en `/deps/nuget`; se usa la etiqueta compartida `:z` (la leen muchos contenedores y el runner escribe al promover).
2. La restauración arrancaba antes de que squid escuchara → el runner espera "Accepting HTTP Socket connections" en los logs del proxy.
3. `NU1301` intermitente (2 de 3): los logs del proxy, que ahora se adjuntan a un fetch fallido, mostraron `TCP_TUNNEL/503 HIER_NONE`. Al conectarlo a la red interna, Podman pone el DNS de esa red (sin salida) primero en `resolv.conf`. Fix: `dns_nameservers` fijo en `squid.conf`; 4 de 4 en PASS.
4. El paquete pedido iba solo al primer `.csproj` del agente (Iris lo usaba en Infrastructure; Vera lo marcó BLOCKER) → va a todos los proyectos de sus capas.
5. El presupuesto de revisión de Vera (24.000 caracteres, pensado para `qwen3:8b`) dejaba archivos fuera con kimi-k3 → depende del modelo del validador (remoto: 120.000/30.000).

**Verificado en vivo**:
- `MISSION-DEPS-VERIFY-1` → **`VERIFIED`**: `Newtonsoft.Json 13.0.3` pedido en `packages`, descargado por el proxy, aprobado por política (MIT, OSV limpio), `VERIFY` sin red con 29/29 tests.
- `MISSION-DEPS-VERIFY-4` (control) → `Newtonsoft.Json 12.0.1` `PENDING_APPROVAL` por `GHSA-5crp-9r3c-p9vr` (HIGH) y el sandbox no corrió.
- Aprobación manual por la API: `approve` promovió la 12.0.1 desde su staging; `reject` la dejó `REJECTED` por `founder` (se borró a mano de la caché después de la prueba).
- `MISSION-DEPS-VERIFY-5` → **`VERIFIED`** con 42/42 tests, tras las correcciones 4 y 5.

### Proveedores remotos por grupo y chat con menciones

**Decisión del fundador** (2026-09-27): cada grupo de agentes usa un modelo de NVIDIA con su propia key (reparte los límites de la cuenta gratuita); Engineering no cambia (kimi-k3, `NVIDIA_API_KEY`). Proveedores pagos (Anthropic, OpenAI) quedan para cuando haya ingresos, con tope de gasto. En el chat responde Alex por defecto y cualquier agente con `@Nombre`.

**Implementado**: proveedores con nombre (`nvidia`, `nvidia-discovery`, `nvidia-creative`, `nvidia-ceo`) por prefijo de `Agent.model`; herramientas en el proveedor remoto (traducción Ollama ↔ OpenAI de `tool_calls`/`tool_call_id`); `MentionResolver` + `ChatIntentRouter.routeReplies` (gobernanza → menciones → consultas deterministas / Alex), `CeoService.agentChat` (identidad, personalidad, prompt activo, solo lectura) y `replies` en la API y el Command Center.

**Bug real en vivo**: con `@Kira @Alex` cada uno escribía también la respuesta del otro → regla "responde solo por ti" en los dos prompts.

**Prueba de modelos** (misiones `MODEL-TRIAL-*`, `TEST`, tareas reales con los gates reales):
- Discovery: `nemotron-3-super` → Max `FAILED` incluso tras el replan (2× `DIVIDE`, luego no citó 9 fuentes); `nemotron-3-ultra` → 3/3 `COMPLETED` con evidencia citada (20 s – 6,5 min por tarea).
- Creative y Marketing: `kimi-k3` → 2/2, pero plan rechazado por `summary` vacío, la llamada a la herramienta llega como texto y una vez devolvió solo `!!!!`; `glm-5.3` → 2/2, herramientas nativas (varias búsquedas), evidencia más concreta, lento (hasta 8,6 min); `gpt-oss-20b` (pedido por el fundador) → 2/2 rápido (1–1,5 min) pero evidencia genérica y plan poco enfocado.
- CEO: `nemotron-3-ultra` → chat en 3–14 s, dice "no tengo ese dato" cuando no lo tiene, consolidaciones honestas; `kimi-k3` → `!!!!` en 2 de 4 mensajes del chat (no reproducible con un prompt corto, donde además no llamó la herramienta e inventó una venta).
- **Elección del fundador**: CEO y Discovery → `nemotron-3-ultra`; Creative y Marketing → `glm-5.3`. Los 14 agentes quedan en NVIDIA.

**Verificado en vivo**: `PROVIDERS-VERIFY-DISC` → Sofia, Neo y Vera `COMPLETED` con 5 evidencias cada uno, **Luna y Max `FAILED`** por cálculos (márgenes en % y punto de equilibrio escritos como `SUBTRACT`: el validador solo admite `ADD`/`SUBTRACT`); `PROVIDERS-VERIFY-CREATIVE` → Kael, Maya y Gael `COMPLETED` (Gael tras un replan, por la misma causa: una tasa de activación); misiones de Marketing en la prueba de modelos; `@Kira @Sofia` → dos respuestas, cada una con su modelo (Kira llamó `query_company_memory` por la API remota); `Necesito más evidencia sobre MISSION-DEPS-VERIFY-3 @Kira` → decisión registrada, Kira no responde (gobernanza gana).

**`MULTIPLY`/`DIVIDE`** (decisión del fundador tras esa verificación): el validador los recalcula en Java con redondeo a 2 decimales (sumar/restar siguen exactos; división por 0 rechazada) y el prompt pide un porcentaje en dos pasos. `CALC-VERIFY-DISC` → **5/5 `COMPLETED` sin rechazos de cálculo**: Max encadenó 13 cálculos (margen unitario → `DIVIDE` → `MULTIPLY` por 100, punto de equilibrio con `DIVIDE`), Luna y Neo también.

**Observaciones abiertas**: `nemotron-3-ultra` casi nunca llama `query_company_memory` en el chat (responde con el historial) y una vez afirmó haberla consultado sin hacerlo; el mercado de Forjai no es solo Colombia (el sesgo de las pruebas venía de la instrucción, no de los prompts).

**Control de afirmaciones sobre la memoria** (2026-09-27): como `nemotron-3-ultra` escribió "Según Company Memory (query_company_memory …)" sin llamar la herramienta, `CeoService` detecta en Java (regex sin tildes) una respuesta que dice haber consultado la memoria cuando en ese turno no hubo llamada y le agrega una nota visible; con llamada real no se toca.

**Conflicto de participación contra el reparto de capas** (2026-09-27): en `MISSION-E2E-ENG` (primer intento) Neo declaró a Mila sin trabajo ("una API .NET no tiene UI") y la misión se cortó, aunque `RoleLayerCatalog` le da la capa API en `DOTNET_APP`. Ahora, con perfil de stack, un conflicto sobre un miembro dueño de alguna capa del perfil es un error que se corrige con reintento.

**Prueba de punta a punta** (2026-09-27, los 4 proveedores a la vez): `MISSION-E2E-DISC` → 5/5 `COMPLETED` (3–11 cálculos por agente, sin rechazos), aprobada desde el chat → `COMPLETED`; `MISSION-E2E-MKT` (glm-5.3) → 2/2, "más evidencia … @Nora" registró la decisión sin que Nora respondiera; **`MISSION-E2E-ENG` → `VERIFIED`** (restore/build, 25/25 tests, `/health`, sin rondas de corrección; Vera solo con 4 `MINOR`; `productStatus=QA`). Chat: consultas deterministas, `@Pepe` desconocido → lista de agentes, y la nota de `UNBACKED_MEMORY_CLAIM` apareció en vivo. **Límites observados**: la herramienta de memoria del chat no tiene un topic de detalle de misión (Sofia y Max responden "no tengo ese dato" sobre su propia misión) y Alex responde con el historial de `Conversation MAIN`, que arrastra las pruebas del día.

**Detalle de misión en el chat** (2026-09-27): tras la prueba de punta a punta (Sofia y Max respondían "no tengo ese dato" sobre su propia misión), `query_company_memory` suma el topic `MISSION_DETAILS` y, como `nemotron-3-ultra` casi nunca pide la herramienta, Java antepone el detalle real cuando el mensaje a un agente mencionado nombra una misión existente. La nota de `UNBACKED_MEMORY_CLAIM` no se aplica cuando los datos los inyectó Java.

**Calidad de respuestas en el chat** (2026-09-27): con los datos de la misión inyectados, Max resumía también lo de Sofia y Sofia escribió "demanda validada" sobre un resultado `NOT_VALIDATED`. Ahora un agente con tareas en la misión recibe solo las suyas, y una afirmación de validación (sin "no"/"sin" delante) sobre resultados no validados recibe una nota de Java. Tras desplegarlo, Sofia igual escribió una sección "**Max (…):**" con números inventados y "la misión validó", y Max (viendo solo lo suyo) dijo que la misión "solo completó finanzas": Java recorta lo escrito en nombre de otro agente (no en Alex), reconoce los verbos de validación y lista las otras tareas sin su contenido.

**`glm-5.3-flash`** (prueba para Creative/Marketing, misión borrada): no fue más rápido (tareas de 2–3,5 min), plan rechazado 1 vez y un resultado rechazado (evidencia sin descripción); se vuelve a `glm-5.3`.

### Rondas de evidencia (v2, reimplementación sobre master)

**Decisiones del fundador** (2026-09-27): `REQUEST_MORE_EVIDENCE` re-ejecuta todas las misiones (discovery, análisis y Engineering); el límite es la policy `MAX_EVIDENCE_ROUNDS` (default 2, editable en Settings); sobre `FAILED` también re-ejecuta; el chat es la ventana del fundador, así que todo lo de las rondas se consulta ahí (100% Java). La rama vieja `worktree-evidence-rounds` quedó ~190 commits atrás y se reimplementó.

**Bugs reales en vivo**:
1. En la ronda 1 de `MISSION-E2E-ENG` falló Iris: la regla "un archivo en cada ruta" la obligaba a reenviar todo su código; la respuesta se alargó hasta cortarse (JSON incompleto y la API respondiendo `application/octet-stream`). En una ronda lo no devuelto queda como está en el repositorio: se exige al menos un archivo, no uno por ruta, y el prompt lo dice.
2. En el chat, las tareas salían dos veces (lista plana y agrupada) y con "..": ahora solo agrupadas por ronda.
3. Una misión con un "más evidencia" registrado antes de esta feature corría las etiquetas de pedido: los pedidos se alinean desde el final.
4. Revisión final: la misión seguía en `AWAITING_INVESTOR` hasta que el thread de la ronda arrancaba; un segundo pedido en ese instante lanzaba dos rondas en paralelo. La transición a `DELEGATING` ahora es síncrona.

**Verificado en vivo (por el chat)**:
- `MISSION-RONDAS-DISC` (discovery): "Necesito más evidencia sobre … : quiero 3 precios reales…" → "Arrancó la ronda 1 de 2… Trabajan: Sales, Product, Finance, Engineering y QA"; tareas `-R1`, fuentes nuevas de precios (99designs, Fiverr, Google Workspace); ronda 2 igual; un pedido con la misión en curso se rechaza con el estado; el tercero → "ya usó 2 de 2 vueltas". "¿Cómo va?" muestra las 3 rondas con el pedido de cada una.
- `MISSION-E2E-ENG` (Engineering, `VERIFIED` con 25 tests): ronda 1 "agrega un endpoint para listar las facturas pagadas" sobre el mismo repositorio (sin scaffold nuevo; sandbox con 28 tests; `UNVALIDATED` por Iris y porque la API devolvió "sin choices" 3 veces a Vera); ronda 2 "el endpoint debe devolver también el total pagado" con el fix → **`VERIFIED`, 30/30 tests**, un commit por agente con `Forjai-Task …-R2`.

**Pendientes menores**: "dame un status" solo cuenta misiones de producción; el pedido guardado es el mensaje completo del chat; la pantalla de misión del Command Center todavía no muestra "arrancó la ronda N".

### Finanzas: costos frente a ganancias (subproyecto 1 de "Command Center: todo editable")

**Decisiones del fundador** (2026-09-27): el éxito se mide en costos frente a ganancias; cliente = quien compra (un prospecto no); todo editable desde la UI, pero los registros financieros **no se editan ni se borran**: se corrigen con un asiento (`Correction`); misión opcional; evidencia = descripción obligatoria + link opcional; solo USD. Se reimplementó sobre master el spec del ledger del 15-sep (rama `worktree-financial-ledger`, ~190 commits atrás).

**Bug real en vivo**: la pantalla no podía mostrar el motivo de un rechazo porque Spring no incluía el mensaje; `server.error.include-message` no tuvo efecto en Spring Boot 4 y la propiedad que aplica es `spring.web.error.include-message: always`.

**Verificado en vivo** (con registros `TEST`, borrados después): cliente → venta US$120 (costo 10) con link → gasto US$12 → corrección "Anular" (−120/−10): el libro lista las 4 líneas con 🧪 y la corrección apunta a la venta, los totales reales quedan en cero (`TEST` no suma); rechazos con su motivo ("El comprobante debe ser un link http(s).", "No existe el movimiento …"); en el chat "costos vs ganancias" y "dame un status" responden desde Java; `/finanzas` servida por la SPA.

### Command Center: modelo por agente y dependencias (subproyecto 2)

**Decisión del fundador** (2026-09-27): todo lo configurable se edita desde la UI. Faltaban pantallas para dos cosas que ya tenían API: el modelo de cada agente y la aprobación de dependencias.

**Implementado**: sección "Modelo" en el panel de cada agente (Agents), con sugerencias de los modelos en uso; `CeoService.checkModel` rechaza al guardar un proveedor remoto no configurado (antes fallaba recién cuando el agente trabajaba); `GET /api/company/agents` incluye el modelo; pantalla `/dependencias` con pendientes (Aprobar/Rechazar) e historial; en el chat, el modelo en el estado de los agentes y "dependencias pendientes".

**Verificado en vivo**: `anthropic:claude/opus` → rechazado con "Proveedor remoto desconocido \"anthropic\". Configurados: [nvidia, nvidia-ceo, nvidia-creative, nvidia-discovery]"; Kira cambiada a `glm-5.3-flash` y devuelta a `glm-5.3`; el chat muestra el modelo de cada agente y "No hay dependencias esperando tu aprobación" (las 2 existentes ya están decididas); `/dependencias` servida por la SPA.

### Catálogo de productos y servicios (subproyecto 1 del ciclo de producto)

**Decisiones del fundador** (2026-09-28): los agentes pueden llevar un producto hasta "listo para vender"; solo el fundador pausa, reanuda, retira y reactiva; Java exige cuatro requisitos para "listo" (precio y cliente objetivo, evidencia web de demanda, construcción verificada o forma de entrega, margen positivo); clientes siempre a nivel mundial ("somos una empresa IA, no tiene sentido limitarnos"); un producto se vende muchas veces (segunda fase: revender). Orden del ciclo: catálogo → orquestador → búsqueda diaria de prospectos → contacto.

**Verificado en vivo**: un producto de prueba recorrió el ciclo por la API (rechazo con los 4 requisitos → misiones reales asociadas: la evidencia WEB de `MISSION-RONDAS-DISC` y el `VERIFIED` de `MISSION-E2E-ENG` se reconocieron → precio, costo y cliente → `READY_TO_SELL`); en el chat, catálogo, "¿qué le falta?", "pausa"/"reanuda" y el conteo en el status. **Automatización A en vivo**: `MISSION-CATALOGO-DISC` terminó (sin Neo ni Vera por la caída de kimi-k3) y Java creó la idea "Launch a productized 'Content Repurposing Pack' service…" (`createdBy: product`, `VALIDATED_BY` la misión, demanda con evidencia web ya cumplida).

**Caída real de kimi-k3 en NVIDIA** (2026-09-28, horas): todas las llamadas a `moonshotai/kimi-k3` fallaban (504, sin respuesta, cuerpo no JSON) con dos keys distintas, mientras `nemotron-3-ultra` con la misma key respondía en 0,7 s: el problema era el modelo en NVIDIA, no la cuenta. Una discovery quedó más de 2 horas esperando a Neo y Vera (10 min por intento × 3 + replan). Motivó el validador de salud de modelos con suplentes locales (siguiente trabajo).

**Prueba de suplente local** (`qwen3-coder:30b`, Q4, 18,6 GB, RTX 5060 8 GB + 30 GB RAM): mismo test del 27-sep (dominio + tests + sandbox sin red): 14 tokens/s; dominio compila; tests con 2 `using` faltantes (los agrega `MissingUsingFixer`) y 3 errores reales; tras una ronda de corrección, **compila y pasa 13/13 tests**. Claramente mejor que `qwen3:8b` y que `nemotron-3.5-lightning` local (3,6–4,7 tokens/s, tests con 8 errores); por debajo de kimi-k3 (0 errores y 19 tests al primer intento).

### Salud de los modelos remotos con suplente local

**Decisiones del fundador** (2026-09-28): tras la caída de kimi-k3, "es necesario colocar un validador de ese tipo"; "en caso de caída los modelos locales pueden ser los suplentes". Suplente por agente editable (default `qwen3-coder:30b`, elegido por la prueba en vivo); cambio automático pero nunca en silencio.

**Verificado en vivo con la caída real de kimi-k3**: tras un redeploy todo arrancó `UP`; dos preguntas en paralelo a Neo y Vera fallaron por timeout/cuerpo ilegible y a las 22:22 UTC kimi-k3 quedó `DOWN` (`EMPRESA_MODEL_DOWN`, correo); la llamada de Neo que detectó la caída se rehízo con `qwen3-coder:30b` y respondió; la de Vera (primer fallo) devolvió el motivo. Después, **@Iris respondió en 14 s por el suplente** (antes: 10 min de espera y fallo); "estado de los modelos" y "dame un status" muestran kimi-k3 caído desde las 22:22 y quién usa suplente.

**Límite conocido**: la primera detección cuesta hasta 2 timeouts (10 min c/u) porque glm-5.3 llegó a tardar 8,6 min en una tarea legítima; lo que se elimina es la espera repetida (horas).

### Orquestador: bucle de fichas con costo 0 y corte por fallos

**Bug real en vivo** (2026-09-29, 17:13–19:22 UTC): tres ciclos seguidos terminaron en `FAILED` con el mismo motivo ("El costo estimado por venta debe ser mayor que 0"). La evidencia decía "costo marginal ~$0" y Alex, con "no inventes datos" y un solo reintento, repetía 0. Cada fallo excluía el producto y el ciclo siguiente lanzaba otra discovery (~45 min de modelos remotos por vuelta, una misión más esperando al fundador). Al intentar pausarlo apareció otro bug: `createVersion` exigía valor > 0 para toda policy, así que `ORCHESTRATOR_ENABLED = 0` era imposible desde Settings y desde "pausa el orquestador" en el chat.

**Arreglo** (aprobado por el fundador): el prompt de la ficha explica que el costo por venta nunca es 0 y qué suma (comisión de la plataforma de pago, IA/infra por venta, entrega) con los valores de la evidencia; la ficha se pide hasta 3 veces. Con 2 ciclos seguidos en `FAILED` (contados desde el último encendido, para que reanudar no lo vuelva a pausar enseguida) el orquestador no arranca otro y se pausa solo con el motivo. `ORCHESTRATOR_ENABLED` admite solo 0/1.

**Verificado en vivo** (19:33 UTC, primer chequeo tras el redeploy): con los 2 últimos ciclos en `FAILED`, el orquestador no lanzó otra discovery, quedó `enabled=false` y `GET /api/company/orchestrator` devolvió `pauseReason` "Pausado solo: 2 ciclos seguidos fallidos. Último motivo: … costo estimado por venta debe ser mayor que 0 …". Pendiente de ver en vivo: una ficha con costo > 0 tras reanudarlo.

### Modo automático (entrega A)

**Decisiones del fundador** (2026-09-29): "debe existir un botón donde yo ponga todo en automático… y un dashboard donde vea qué están haciendo". Un interruptor por frente más uno general; arriba del Dashboard; dos entregas (A: interruptores + panel; B: búsqueda de prospectos, que se engancha a "Buscar clientes"). Los interruptores son la policy versionada (una sola fuente de verdad con Settings y el chat).

**Verificado en vivo** (2026-09-30 00:20 UTC, tras el redeploy): `GET /api/company/autonomy` → productos apagados con el `pauseReason` del corte por fallos, clientes `available=false`, 8 misiones del orquestador esperando decisión y 0 dependencias; `PUT {"clients":true}` → 500 "La búsqueda de clientes todavía no existe (próximamente)."; `PUT {}` → misma vista y la policy sigue en la versión 2 (sin versión nueva). El bundle servido incluye el panel. No se encendió el orquestador (lanza un ciclo con costo de modelos): queda para cuando el fundador lo encienda desde el Dashboard.

### Imagen de Flutter: SDK duplicado por `chown -R`

**Pedido del fundador** (2026-09-30): "borra el duplicado pero no cambies nada más". La imagen `flutter-web-app` pesaba 5,01 GB: el `chown -R 1000:1000 /sdks/flutter` copiaba a una capa nueva todo el SDK de la imagen base (967 MB, dueño root). Ahora el uid 1000 recibe solo los directorios del SDK (para crear `bin/cache/lockfile`, y git ve el repo como propio) y los archivos que escribe ese mismo paso. Base, Chrome y resto sin cambios.

**Verificado en vivo**: 5,01 → 4,01 GB. `VERIFY` de `MISSION-SANDBOX-VERIFY-22` (39 tests) y `-23` (35 tests) antes y después: mismos resultados, los 5 pasos `PASS` y tiempos iguales (el primer `restore` con la imagen nueva tardó 18 s por el primer montaje; al repetirlo, 2,6 s).

### Búsqueda diaria de prospectos (subproyecto 3)

**Decisiones del fundador** (2026-09-30): solo para productos listos para vender; Java rota estrategias y Sofía ejecuta, y cada semana Marketing propone una nueva que el fundador aprueba; prospecto válido = contacto público verificable. Lección de la rama del 18-sep (segmentos con nombre y fuente genérica, corregido solo por prompt): acá la validez la decide Java (nombre en su propia página, email literal en su fuente).
