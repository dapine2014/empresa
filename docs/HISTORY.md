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
