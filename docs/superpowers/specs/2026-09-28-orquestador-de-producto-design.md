# Orquestador del ciclo de producto — diseño

**Fecha**: 2026-09-28
**Estado**: diseño aprobado por el fundador en conversación (enfoque A); pendiente de revisión de este spec.
**Subproyecto 2 del "ciclo de producto"** (orden: 1 catálogo ✅ → 2 orquestador → 3 búsqueda diaria de prospectos →
4 contacto con prospectos).

## Contexto y objetivo

Visión del fundador: "si Forjai no tiene productos o servicios y el inversionista no propone uno, los agentes deben crear
un producto o servicio y buscarle clientes". Ya existen las piezas (discovery que crea ideas en el catálogo, equipos que
construyen, requisitos en Java para "listo para vender", rondas de evidencia, suplente local si un modelo cae); falta
**quién decide y cuándo**. El orquestador detecta que no hay nada que vender, elige qué construir, lo construye y lo deja
listo para vender, de forma autónoma y visible.

## Decisiones del fundador (2026-09-28)

1. **Uno a la vez**: nunca más de un producto en creación autónoma; el límite es una policy editable.
2. **Disparador**: arranca solo si el catálogo no tiene ningún producto `READY_TO_SELL` ni `IN_CONSTRUCTION` (las ideas no
   cuentan: puede tomar una y construirla). Con uno listo, se detiene y pasa la posta a la búsqueda de clientes.
3. **Elección**: Java filtra las ideas con evidencia web de demanda; entre esas, **Alex elige** con su motivo y Java
   valida que eligió una de la lista. Sin ideas con evidencia → primero una discovery.
4. **Servicios**: los **diseña el equipo Creative** (pasos, entregables, plantillas, tiempos); de ahí sale la forma de
   entrega que exige el catálogo. **Software**: lo construye Engineering y lo verifica el sandbox.
5. **Enfoque A**: un ciclo persistente en Neo4j avanzado por un chequeo periódico (sobrevive a reinicios).

## 1. Ciclo persistente (`(:OrchestratorRun)`)

`{id, status, productId, discoveryMissionId, buildMissionId, choiceReason, attempts, startedAt, updatedAt, endedAt,
failureReason}` y `(:OrchestratorRun)-[:HAS_STEP]->(:OrchestratorStep {at, step, detail})` (historial inmutable).

Estados: `CHOOSING` → (`DISCOVERING` →) `PROPOSING` → `BUILDING` → `READY` | `FAILED` | `STOPPED`.

## 2. Cuándo avanza (`ProductOrchestrator`)

- Un chequeo **cada 15 minutos** y además al terminar cada misión (`MissionExecutor` avisa al orquestador con setter
  opcional, como el catálogo). Cada chequeo es idempotente: mira el estado real en Neo4j y avanza como mucho un paso.
- Policies nuevas (editables en Settings): `ORCHESTRATOR_ENABLED` (1/0, default 1) y `MAX_AUTONOMOUS_PRODUCTS`
  (default 1).
- **Arranca un ciclo** si: `ORCHESTRATOR_ENABLED = 1`, no hay ciclos activos (`CHOOSING/DISCOVERING/PROPOSING/BUILDING`)
  por encima del límite, y el catálogo no tiene `READY_TO_SELL` ni `IN_CONSTRUCTION`.

## 3. Pasos

1. **CHOOSING**: candidatos = productos `IDEA` con evidencia web de demanda (`ProductMemoryService.evidence`). **Las ideas
   creadas por el fundador (`createdBy: human`) van primero y se eligen sin pasar por Alex.** Si no hay candidatos:
   lanzar una discovery (`MissionService.start`, `PRODUCTION`, instrucción fija de búsqueda mundial de un producto o
   servicio pequeño vendible con el capital disponible) → `DISCOVERING`. Con candidatos: Alex elige
   (`CeoService.chooseProduct`, `format` = `{productId, reason}`); Java valida que el id esté en la lista (si no, o si el
   modelo falla: gana el candidato con más misiones de demanda y, a igualdad, el más reciente) → `PROPOSING`.
2. **DISCOVERING**: cuando la discovery termina (`AWAITING_INVESTOR`) → volver a `CHOOSING` (la automatización del
   catálogo ya creó la idea). Si falla → `FAILED`.
3. **PROPOSING**: Alex completa la ficha (`CeoService.proposeProductSheet`, schema `{kind, targetCustomer, markets[],
   languages[], priceUsd, priceOnRequest, estimatedCostUsd, delivery}`), usando los resultados de las misiones de demanda
   (Max, Luna). Java valida (montos ≥ 0, kind válido, markets no vacíos → `WORLDWIDE` por defecto) y actualiza el producto
   con actor `orchestrator`, luego lo pasa a `IN_CONSTRUCTION` → lanza la construcción:
   - `SOFTWARE` → misión de `TEAM-ENGINEERING` con la ficha como instrucción; `BUILT_BY` esa misión.
   - `SERVICE` → misión de `TEAM-CREATIVE-PRODUCT-INTELLIGENCE` para diseñar la entrega; `BUILT_BY` esa misión.
   → `BUILDING`.
4. **BUILDING**: cuando la misión termina:
   - `SERVICE`: Alex resume la entrega desde los resultados de Creative (`CeoService.summarizeDelivery`, schema
     `{delivery}`) y Java la guarda en el producto.
   - La automatización existente (`ProductAutomation.buildFinished`) intenta `READY_TO_SELL`.
   - Si queda `READY_TO_SELL` → `READY` (fin). Si no (requisitos faltantes o misión `FAILED`/`UNVALIDATED`): si quedan
     rondas (`MAX_EVIDENCE_ROUNDS`), pedir una ronda de corrección con lo que falta (mismo mecanismo que
     `REQUEST_MORE_EVIDENCE`, actor `orchestrator`) y seguir en `BUILDING`; sin rondas → `FAILED` con el motivo.
5. Un producto `PAUSED` o `RETIRED` por el fundador durante el ciclo → `STOPPED` (el orquestador nunca lo mueve).

Todas las misiones que lanza son `PRODUCTION` y quedan con `launchedBy: orchestrator`.

## 4. Control, visibilidad y avisos

- **Correo**: al arrancar un ciclo, al elegir qué construir (con el motivo), al quedar listo, al fallar.
- **Eventos**: `EMPRESA_ORCHESTRATOR_STARTED|CHOSE|BUILDING|READY|FAILED|STOPPED`.
- **Chat** (Java): "¿qué está haciendo el orquestador?" → paso actual, producto, misiones y motivo de la elección;
  "dame un status" agrega la línea del orquestador; "pausa el orquestador" / "reanuda el orquestador" cambian
  `ORCHESTRATOR_ENABLED` (comandos de gobernanza, antes de las menciones). Pausado, no arranca ciclos nuevos ni avanza
  el actual (las misiones ya lanzadas terminan igual).
- **Command Center**: en Productos, el ciclo en curso y su historial de pasos; en Settings, las dos policies.

## Testing

- Disparador: con un `READY_TO_SELL` o `IN_CONSTRUCTION` no arranca; con solo ideas sí; con `ORCHESTRATOR_ENABLED = 0`
  no; con un ciclo activo y límite 1 no arranca otro.
- CHOOSING: idea del fundador gana sin llamar a Alex; sin candidatos lanza discovery; Alex elige una fuera de la lista →
  regla de Java; el modelo falla → regla de Java.
- PROPOSING: ficha inválida se rechaza (montos negativos); software lanza Engineering y servicio Creative, con `BUILT_BY`.
- BUILDING: listo → `READY`; requisitos faltantes con rondas → ronda de corrección; sin rondas → `FAILED`; servicio →
  entrega guardada antes de evaluar.
- Producto pausado → `STOPPED`; orquestador pausado no avanza.
- Idempotencia: dos chequeos seguidos sin cambios no lanzan dos misiones.
- Chat: estado, status, pausar/reanudar. En vivo: un ciclo completo con `ORCHESTRATOR_ENABLED = 1` (con el catálogo sin
  productos listos) hasta `READY` o `FAILED` explicado; después se decide qué hacer con lo creado.

## Fuera de alcance

- Varios productos en paralelo (el límite existe, pero se prueba con 1); búsqueda de clientes (subproyecto 3); contacto y
  venta (subproyecto 4); presupuesto de créditos de IA (queda para cuando se pague una API).
