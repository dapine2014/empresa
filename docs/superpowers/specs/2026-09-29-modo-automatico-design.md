# Modo automático y panel "qué están haciendo" — diseño

**Fecha**: 2026-09-29
**Estado**: diseño aprobado por el fundador en conversación (partes 1 y 2); pendiente de revisión de este spec.
**Entrega A de 2**: interruptores + panel. La búsqueda diaria de prospectos (subproyecto 3 del ciclo de producto) es la
entrega B, con su propio spec; se engancha al interruptor "Buscar clientes" que acá queda como "próximamente".

## Contexto y objetivo

Pedido del fundador: "debe existir un botón donde yo ponga todo en automático, o sea que se active la búsqueda de
clientes y/o la creación de productos o servicios, y un dashboard donde vea qué están haciendo". Hoy la creación de
productos existe (el orquestador) pero solo se enciende con la policy `ORCHESTRATOR_ENABLED` en Settings o por chat, y su
ciclo se ve dentro de Productos. La búsqueda de clientes no existe.

## Decisiones del fundador (2026-09-29)

1. **Un interruptor por frente y uno general** ("Todo en automático").
2. **Dos entregas** (A: interruptores + panel; B: búsqueda de prospectos).
3. **Arriba del Dashboard actual** (pantalla de inicio), no en una pantalla nueva.
4. Contactar o vender sigue siendo 🔴 aunque todo esté en automático.

## 1. Interruptores

Tarjeta **"Modo automático"** al tope del Dashboard:

| Interruptor | Efecto |
|---|---|
| Todo en automático | Enciende/apaga los frentes disponibles. Se muestra encendido si todos lo están, "parcial" si alguno. |
| Crear productos y servicios | Policy `ORCHESTRATOR_ENABLED` (1/0). |
| Buscar clientes | Deshabilitado, "próximamente" (`available: false`). |

- **Una sola fuente de verdad**: la policy versionada. El botón, Settings y el chat cambian lo mismo; el historial
  registra el motivo ("Encendido desde el Dashboard", "Pausado solo: 2 ciclos seguidos fallidos…").
- **Pausado solo** (corte por fallos): el interruptor aparece apagado con `pauseReason` en rojo. Encenderlo reinicia el
  conteo de fallos (ya implementado: se cuenta desde la activación de la versión vigente).
- **Al encender** no arranca nada en el momento: la tarjeta dice "retoma en el próximo chequeo (cada 15 minutos o al
  terminar una misión)".
- Leyenda fija: "Contactar clientes o vender sigue siendo decisión tuya".
- **API**: `PUT /api/company/autonomy` con `{products: boolean}` (campo `clients` reservado para la entrega B; si llega
  mientras no existe → `IllegalArgumentException`). `AutonomyService` crea la versión de la policy solo si cambia el
  valor (idempotente: encender algo encendido no crea versión), actor `human`, motivo "Encendido/Apagado desde el
  Dashboard". "Todo" lo arma la UI mandando todos los campos disponibles.

## 2. Panel "Qué están haciendo"

Debajo de los interruptores:

1. **Productos y servicios**: paso actual en lenguaje simple (mismas etiquetas que Productos y el chat), producto,
   misión en curso con progreso y link; últimos 5 pasos con hora. Sin ciclo en curso, el porqué: pausado (con motivo),
   hay un producto listo para vender o en construcción, o "esperando el próximo chequeo".
2. **Clientes**: "próximamente".
3. **Agentes trabajando ahora**: agentes `WORKING` con misión y acción (de `/agents/status`, ya existente).
4. **Esperando tu decisión**: misiones lanzadas por el orquestador (`launchedBy: orchestrator`) en `AWAITING_INVESTOR` y
   dependencias `PENDING_APPROVAL`, con links a Missions y Dependencias.

**API**: `GET /api/company/autonomy` →
`{products: {enabled, available: true, pauseReason}, clients: {enabled: false, available: false, pauseReason: null},
waiting: {orchestratorMissions, pendingDependencies}}`. El ciclo y los agentes salen de `/orchestrator` y
`/agents/status`. Polling cada 15 s (`@tanstack/react-query`), sin WebSocket.

`MissionMemoryService` suma un conteo de misiones `AWAITING_INVESTOR` con `launchedBy = 'orchestrator'`.

## 3. Chat (Java, sin modelo)

- Consulta "¿qué está en automático?" / "modo automático" → estado de cada frente (con motivo si está apagado), qué hace
  ahora el orquestador (reusa `formatOrchestrator`) y la línea "esperando tu decisión".
- Comandos del fundador, en la gobernanza junto a "pausa/reanuda el orquestador": "pon todo en automático" /
  "activa el modo automático" → enciende los frentes disponibles; "apaga el automático" / "desactiva el modo
  automático" → los apaga. Responden qué cambió y qué sigue "próximamente". Mismo `AutonomyService` que el botón,
  motivo "desde el chat".

## Errores

- Convención del proyecto: `IllegalArgumentException` → 500 con mensaje visible (`include-message`); la tarjeta muestra
  el mensaje y deja el interruptor como estaba (sin estado optimista).
- Fallo al leer el orquestador o los conteos: el panel muestra "no disponible" en esa fila; los interruptores siguen.

## Testing

- `AutonomyServiceTest`: encender/apagar productos crea versión con el motivo; sin cambio no crea versión; `clients`
  mientras no existe → rechazo; la vista trae `pauseReason` solo apagado y los conteos.
- `ChatIntentRouterTest`: consulta de modo automático (sin llamar al modelo); "pon todo en automático" y "apaga el
  automático" llaman al servicio y la respuesta menciona clientes "próximamente"; no chocan con "pausa el orquestador" ni
  con arranques de misión.
- Controller: GET y PUT delegan al servicio.
- Frontend: `npm run build` + `npm run lint`; tipos en `api/types.ts`.
- En vivo: encender desde el Dashboard → la policy muestra la versión "desde el Dashboard" y el orquestador retoma en el
  siguiente chequeo; apagar desde el chat → el Dashboard lo refleja; el panel muestra el ciclo y lo que espera decisión.

## Fuera de alcance

- La búsqueda de prospectos (entrega B) y el contacto con prospectos (🔴).
- Métricas de costo de modelos por frente (no hay datos hoy).
