# Company Chat: grounding anti-alucinación + `ProductStatus` — diseño

**Fecha**: 2026-09-20
**Estado**: aprobado por el usuario en brainstorming, pendiente de plan de implementación.

## Contexto y objetivo

Caso real reportado por el usuario: preguntó al Company Chat por
`MISSION-1789884929871` (con el `MISSION-<id>` explícito en el mensaje). En
Company Memory esa misión está `COMPLETED`, todos los agentes están `IDLE`, y
la última tarea de `engineering` es `DELIVERY_FEASIBILITY = COMPLETED`. El CEO
respondió: *"El desarrollo del MVP está en curso."* — una afirmación sin
ningún respaldo real.

Causa raíz verificada en el código (no solo inferida): `ChatIntentRouter`
tiene ramas deterministas para inicio de misión, decisión, referencia
pronominal y una lista fija de `QueryIntent` por keywords — pero **ninguna
rama resuelve "el usuario mencionó un `MISSION-<id>` explícito y quiere saber
su estado"**. Ese caso cae directo a `CeoService.chat` (chat general), que
solo tiene tool-calling opcional (`query_company_memory`) sobre un enum de
topics agregados (`AGENT_STATUS`, `COMPANY_STATUS`, etc.) — ninguno de ellos
da detalle de una misión puntual. El modelo queda libre de narrar sin ningún
dato grounded de esa misión en el mensaje.

Un segundo problema, conceptual: el sistema nunca distinguió el estado del
*workflow interno de análisis* (`MissionStatus`: `CREATED → ... →
AWAITING_INVESTOR → COMPLETED`) del estado del *producto/negocio real*
(¿existe una oferta diseñada? ¿hay desarrollo real? ¿está publicado? ¿está
generando ingresos?). `DELIVERY_FEASIBILITY` (tarea de `engineering` durante
el discovery) es un estudio de factibilidad — no significa que el desarrollo
haya empezado, y el código no tenía ningún concepto para expresar esa
diferencia.

### Decisión de alcance: dos proyectos, esta ronda es solo el primero

Este pedido incluye también (reglas 11/12 del pedido original) que **aprobar**
una misión dispare **ejecución real de código/infraestructura** (repo real,
build, deploy) — confirmado explícitamente con el usuario que "desarrollo"
debe significar eso, no una tarea estructurada más tipo `AgentResult`. Esa
pieza requiere infraestructura que hoy no existe (control de repos, CI,
despliegue, políticas de autonomía/costo de `empresa.md` §5 para gastar en
infraestructura real) y es un proyecto de arquitectura propio. Se decidió
explícitamente con el usuario:

- **Proyecto A (este documento)**: arregla el bug reportado — el chat nunca
  afirma estado de producto sin evidencia real, y modela `ProductStatus` con
  sus 7 estados, aunque hoy solo sean alcanzables los primeros 4.
- **Proyecto B (fuera de este documento)**: ejecución real de código/infra
  tras aprobación — su propio `superpowers:brainstorming` después de que A
  esté implementado, porque A deja las bases (`ProductStatus`, la regla de
  "nunca sin evidencia") que B necesita para poder reportar sus resultados
  sin repetir el mismo problema.

Todo lo de abajo es exclusivamente Proyecto A.

## Decisiones de diseño

### 1. `ProductStatus`: nuevo enum, separado de `MissionStatus`

`model/ProductStatus.java`: `DISCOVERY, DESIGN, DEVELOPMENT, QA, PUBLISHED,
MONETIZING, BUSINESS_SUCCESS`. `MissionStatus` (workflow de análisis/decisión)
no cambia — sigue significando exactamente lo que significa hoy
(`CLAUDE.md`, sección "Flujo de misión"). Nunca se usa uno para inferir el
otro: una misión `COMPLETED` (workflow) puede tener `productStatus=DISCOVERY`
sin contradicción — son dos preguntas distintas.

### 2. `ProductStatusService`: motor de reglas determinista, sin LLM

Servicio nuevo, `resolve(String missionId) -> ProductStatus`, evaluado en
este orden (la señal real más fuerte gana; no son pasos secuenciales
obligatorios, son señales independientes verificadas de la más alta a la más
baja):

1. **`BUSINESS_SUCCESS`**: `netProfit > seedCapitalUsd`, mismo cálculo que
   `CustomerService.netProfit` (reutiliza `CustomerMemoryService.
   totalRevenueAndCost(missionId)` + `AppProperties.seedCapitalUsd()`).
2. **`MONETIZING`**: existe ≥1 `Transaction` real para la misión. Nuevo
   método `CustomerMemoryService.transactionCount(String missionId)` (no
   basta con revisar si `totalRevenueAndCost` es distinto de cero: una
   transacción real con `revenueUsd=0, costUsd=0` — caso raro pero posible —
   igual debe contar).
3. **`PUBLISHED`**, **`QA`**, **`DEVELOPMENT`**: hoy no existe ninguna señal
   real para estos tres (esa señal es exactamente lo que el Proyecto B tiene
   que producir — `AgentTask` de desarrollo real, evento
   `EMPRESA_DEVELOPMENT_STARTED`, artefacto/repo/build). Se implementan como
   tres métodos privados que **siempre devuelven `false`** hoy, con un
   comentario explícito marcándolos como punto de enganche del Proyecto B —
   no se borran del enum ni de la cadena de evaluación, para que activarlos
   después sea agregar la condición real, no rediseñar el servicio.
   **Regla dura explícita**: una `AgentTask` con `action="QUALITY_RISK_REVIEW"`
   (la tarea de discovery de `qa`) **nunca** cuenta como evidencia de `QA`
   real — son conceptos distintos (uno es revisión de riesgos sobre
   hipótesis, el otro sería QA real sobre un producto que ya existe).
4. **`DESIGN`**: existe una `AgentTask` con `action="OFFER_DESIGN"` (la tarea
   de `product`) y `status="COMPLETED"` para esa misión.
5. **`DISCOVERY`**: default — cualquier misión sin ninguna señal más fuerte
   (incluye toda misión reciente hoy mismo, típicamente).

Depende de `MissionMemoryService.tasks(missionId)` (ya existe, devuelve
`List<AgentTask>`) y `CustomerMemoryService` — no persiste nada nuevo en
Neo4j, se recalcula en cada consulta (siempre fresco, sin riesgo de
desincronización con las señales reales).

### 3. `ChatIntentRouter`: nueva rama determinista para "estado de una misión puntual"

Se agrega justo después de `detectDecision()` (que ya extrae un
`MISSION-<id>` explícito y devuelve `null` si el mensaje no es una decisión
de gobernanza): si el mensaje trae un `MISSION-<id>` explícito y no fue
capturado por inicio de misión ni por decisión, se resuelve **100% en Java,
sin llamar a Ollama** — mismo criterio que `formatAgentStatus`/
`formatCompanyStatus`. Esto es exactamente el caso real reportado: queda
cerrado sin depender de que el LLM decida bien pedir la herramienta.

`handleMissionStatusQuery(missionId)`:

- Si `missionMemory.find(missionId)` está vacío: devuelve exactamente
  **"No tengo ese dato registrado."** seguido de una aclaración breve (no
  existe esa misión en Company Memory) — la oración exacta es obligatoria
  (pedido explícito del usuario), el resto es contexto adicional.
- Si existe: arma una respuesta con:
  - `workflowStatus` (el `MissionStatus` real) **más una aclaración
    explícita en el propio texto** de que es el estado del análisis
    interno, no del producto (ej.: "workflowStatus=COMPLETED — esto es el
    cierre del proceso de análisis/decisión, no implica nada sobre si el
    producto está en desarrollo, publicado o generando ingresos").
  - `productStatus` calculado por `ProductStatusService`.
  - El desglose real de `AgentTask` de esa misión (`missionMemory.
    tasks(missionId)`: `agentId`/`action`/`status`).
  - El `Agent.status` **actual** (global, `WORKING`/`IDLE`) de cada agente
    involucrado en la misión, cruzando con `missionMemory.
    latestTaskPerAgent()` — para no confundir "cómo terminó su tarea en
    esta misión" con "qué está haciendo ese agente ahora mismo" (pueden
    diferir: un agente `IDLE` hoy pudo estar `WORKING` en otra misión luego).
  - Si `productStatus` está por debajo de `DEVELOPMENT`: una frase de cierre
    obligatoria — *"No tengo registro de ninguna AgentTask de desarrollo
    real, evento de desarrollo iniciado, ni artefacto/repositorio/build para
    esta misión — no puedo afirmar que el desarrollo haya comenzado."*
- Registra `conversationMemory.setLastMentioned("MISSION", List.of(missionId))`
  para que un follow-up pronominal ("¿y esa, cuándo se acerca a producción?")
  seguya funcionando sobre el foco.

Esta rama **nunca pasa por Ollama** — la respuesta es una función pura de
datos reales, igual que el resto de `ChatIntentRouter`. Es la única garantía
dura de este diseño; ver limitación conocida más abajo.

### 4. Extensión de `handleReference`/`formatLastMentioned` para follow-ups sin id explícito

Se agrega `ReferencePredicate.PRODUCT_STATUS` (keywords: `"desarrollo"`,
`"desarrollando"`, `"mvp"`, `"publicad"`, `"produccion"` en el sentido de
"lanzado", no confundir con `Mission.environment`). `formatReferenceAnswer`
gana un caso que resuelve `ProductStatusService.resolve` por cada misión del
foco y arma la misma respuesta grounded que el punto 3 (versión abreviada:
lista `missionId → productStatus` con la misma aclaración de que no implica
desarrollo real si aplica). `formatLastMentioned` también agrega
`productStatus` a su detalle por misión. Esto cubre la variante de la
regla 10 sin `MISSION-<id>` literal en el mensaje (ej.: "¿esa está en
desarrollo?" después de haber mencionado la misión antes en la charla).

### 5. `CeoService`: refuerzo del system prompt (best-effort, no garantía dura)

Para el chat general que no cae en ninguna rama determinista (preguntas que
ni mencionan un `MISSION-<id>` ni usan un pronombre de foco reconocido), se
agregan al `systemPrompt()`/mensaje de sistema de `chat()` estas reglas
explícitas:

- `workflowStatus` de una misión nunca implica nada sobre el estado real del
  producto — son conceptos distintos, nunca se infiere uno del otro.
- Una `AgentTask` `DELIVERY_FEASIBILITY` completada es un estudio de
  factibilidad, **no** significa que el desarrollo haya comenzado.
- Si preguntan por el estado de desarrollo/negocio de una misión y ese dato
  exacto no está en este mensaje (ni vino de `query_company_memory`), la
  respuesta debe ser exactamente: "No tengo ese dato registrado." — nunca
  asumir que un paso avanzó porque otro paso anterior terminó.

Esta es una instrucción al modelo, no una garantía verificable en tests —
mismo límite que ya tiene hoy el resto del chat general (ver sección
"Limitación conocida, no resuelta" más abajo).

### 6. Sin cambios de API REST ni frontend

`productStatus` es interno a esta rama del chat — no se agrega a
`MissionResponse`, `MissionController` ni al Command Center web en esta
ronda (decisión YAGNI explícita del usuario: exponerlo ahí tiene más sentido
cuando el Proyecto B le dé contenido real a `DEVELOPMENT`/`QA`/`PUBLISHED`).

## Testing

- **`ProductStatusServiceTest`** (nuevo): las 5 reglas de derivación en
  orden — `DESIGN` cuando `OFFER_DESIGN` está `COMPLETED` (aunque
  `DELIVERY_FEASIBILITY` también lo esté), `QUALITY_RISK_REVIEW` completada
  **no** produce `QA`, `MONETIZING` con una `Transaction` real (incluso con
  `revenueUsd=0`), `BUSINESS_SUCCESS` cuando `netProfit > seedCapitalUsd`,
  `DISCOVERY` por defecto sin ninguna señal.
- **Test de protección anti-alucinación** (regla 13 del pedido original,
  nuevo, sin Ollama — puro sobre `ChatIntentRouter.handleMissionStatusQuery`
  con fakes/mocks de las memorias): fixture con `Mission.status=COMPLETED`,
  las 5 `AgentTask` `COMPLETED` (incluida `DELIVERY_FEASIBILITY`), todos los
  agentes `IDLE`, sin transacciones. Asserts: la respuesta **no** contiene
  ninguna de las substrings `"en curso"`/`"desarrollando"`/`"comenzó el
  desarrollo"`, **sí** contiene `productStatus=DISCOVERY` (u otro alcanzable
  hoy, nunca `DEVELOPMENT`+), y **sí** contiene la frase de cierre de "no
  puedo afirmar que el desarrollo haya comenzado".
- **`ChatIntentRouterTest`**: nueva rama — mensaje con `MISSION-<id>`
  explícito que no es inicio/decisión resuelve a `handleMissionStatusQuery`;
  misión inexistente devuelve "No tengo ese dato registrado."; nuevo
  `ReferencePredicate.PRODUCT_STATUS` sobre el foco conversacional.
- **No se agrega** el test de la regla 12 (integración `APPROVE` → tareas
  reales → `Agent.status=WORKING`) — pertenece al Proyecto B, donde `APPROVE`
  todavía no dispara ninguna tarea nueva hoy. Se deja pendiente,
  explícitamente, para cuando se diseñe B.

## Limitación conocida, no resuelta

La única garantía **dura** de este diseño cubre las preguntas que mencionan
un `MISSION-<id>` explícito o un pronombre de foco reconocido (puntos 3 y 4)
— ahí la respuesta es una función pura de Neo4j, nunca pasa por el modelo. El
chat general sin ninguno de esos dos anclajes (pregunta ambigua, sin id ni
foco previo) sigue dependiendo de que el LLM respete el system prompt
reforzado (punto 5) — mismo límite que ya acepta hoy el resto de
`CeoService.chat` para cualquier dato no cubierto por un `QueryIntent`. No es
un objetivo de esta ronda resolver ese caso general (requeriría, por
ejemplo, forzar sistemáticamente una llamada a `query_company_memory` antes
de cualquier respuesta, lo que cambia la latencia/costo de cada mensaje del
chat — decisión de producto que no se pidió acá).

## Fuera de alcance de esta ronda (documentado, no descartado)

- **Proyecto B completo** (ejecución real de código/infra tras aprobación,
  reglas 11/12 del pedido original): su propio `superpowers:brainstorming`
  posterior.
- **`productStatus` en REST/frontend**: ver decisión 6.
- **Forzar tool-calling obligatorio en cada turno del chat general**: ver
  "Limitación conocida" arriba — cambiaría latencia/costo de todo el chat,
  no solo del caso reportado.
