# Búsqueda diaria de prospectos — diseño

**Fecha**: 2026-09-30
**Estado**: diseño aprobado por el fundador en conversación (partes 1 y 2); pendiente de revisión de este spec.
**Subproyecto 3 del ciclo de producto** (entrega B del modo automático). Orden acordado: 5 búsqueda de prospectos (este)
→ 6 contacto con prospectos (portar `worktree-prospect-chat-quality`) → 7 reventa.

## Contexto y objetivo

Visión del fundador (2026-09-27): con un producto listo, Forjai busca clientes "a diario con estrategias nuevas hasta que
el inversionista la frene". Hoy solo existen los `Customer {status:'LEAD'}` que cada discovery guarda de pasada
(`HAS_CANDIDATE`), sin búsqueda propia ni atada a un producto. Lección de la rama del 18-sep
(`2026-09-18-real-prospect-quality-design.md`): los agentes devolvían segmentos de mercado con nombre de empresa y
fuente genérica, y solo se intentó corregir por prompt. Acá lo valida Java.

## Decisiones del fundador (2026-09-30)

1. **Solo para productos `READY_TO_SELL`** (con su ficha: cliente objetivo, precio, mercados, idiomas).
2. **Estrategias**: Java rota un catálogo y Sofía (Sales) ejecuta; **una vez por semana** el equipo Marketing & Growth
   propone una estrategia nueva que entra al catálogo **solo si el fundador la aprueba** (🔴).
3. **Prospecto válido = contacto público verificable** (email que figura en una página pública, o formulario), con la
   URL de donde salió. Nunca inventado.
4. Contactar sigue siendo 🔴 (subproyecto 6).

## 1. Ciclo diario (`ProspectingService`)

- **Cuándo**: un chequeo cada hora (`@Scheduled(fixedDelay = 1 h, initialDelay = 3 min)`) corre la búsqueda si hoy
  (UTC) no hubo corrida y ya son las 08:00 UTC o más — cubre la hora fija y el arranque. Corre solo si
  `PROSPECTING_ENABLED = 1` y hay productos `READY_TO_SELL`. Un producto por corrida, rotando (el que lleva más tiempo sin
  corrida). Nunca dos corridas simultáneas (`synchronized`). El fundador puede forzar una corrida
  (`POST /api/company/prospecting/runs`), aunque el interruptor esté apagado o ya haya corrido hoy.
- **Estrategia del día (Java, `StrategySelector`, función pura)**: catálogo base fijo (`ProspectingStrategy` enum):
  - `DIRECTORIES` — directorios y asociaciones de empresas del rubro del cliente objetivo.
  - `COMMUNITIES` — foros, comunidades y grupos donde se pide o se sufre el problema que resuelve el producto.
  - `COMPETITOR_CUSTOMERS` — clientes visibles de competidores (casos de éxito, reseñas, testimonios).
  - `NICHE_LISTS` — listas públicas del nicho ("top N…", rankings, listados).
  más las `(:ProspectingStrategy {status:'APPROVED'})` del fundador. Regla: primero las que el producto nunca usó (orden
  del catálogo); después, la de mayor rendimiento (válidos / corridas) para ese producto, sin repetir la del día anterior
  para el mismo producto; a igualdad, la usada hace más tiempo.
- **Búsqueda (Sofía)**: `CeoService.searchProspects(productSheet, strategy, model)` con el mismo patrón de dos turnos que
  las tareas (decisión con `search_web_evidence` sin `format` → búsqueda real + `confirmReachable` → turno final con
  `PROSPECTS_SCHEMA` sin `tools`). Nunca `format` y `tools` juntos. País/idioma de la búsqueda desde la ficha:
  `WORLDWIDE` → sin `gl`; `hl` = idioma de la ficha rotando por corrida. Salida:
  `{prospects: [{name, url, contactEmail, contactFormUrl, contactSourceUrl, fitReason}]}`.
- **Validación (Java, `ProspectValidator`)**, por prospecto; lo que no pasa se descarta con motivo:
  1. `name`, `url`, `fitReason` no vacíos; `url` http(s).
  2. `WebPageFetcher.fetch(url)` responde (SSRF actual) y el `name` aparece en el texto (normalizado sin tildes ni
     mayúsculas).
  3. Contacto: `contactEmail` con formato de email y que aparezca **literalmente** en `fetch(contactSourceUrl)`; o, sin
     email, `contactFormUrl` responde. Sin ninguno de los dos → descartado.
  4. Sin duplicados: el dominio de `url` no fue prospectado antes para ese producto ni descartado por el fundador.
- **Persistencia**: válidos como `Customer {status:'LEAD', contactEmail, contactEmailSource, contactFormUrl, fitReason,
  strategy, foundAt}` (mismos nombres de campo que la rama del subproyecto 6), `(:Product)-[:HAS_PROSPECT]->(:Customer)`,
  con `Evidence` WEB `verified=false` de la `url`. Hasta `MAX_PROSPECTS_PER_DAY` (policy, default 10) por día.
- **Registro**: `(:ProspectingRun {id, date, productId, strategy, found, valid, rejections: [motivo…], startedAt,
  endedAt, status: COMPLETED|FAILED, error})`. De ahí sale el rendimiento. Un fallo (modelo caído, búsqueda caída) deja
  la corrida `FAILED` con el motivo y no reintenta hasta el día siguiente.

## 2. Estrategias nuevas de Marketing & Growth

- Una vez por semana (`@Scheduled`, lunes 09:00 UTC) y solo si no hay otra `PENDING_APPROVAL`: Kira (`growth-content`)
  recibe el rendimiento por estrategia (armado en Java) y propone una: `CeoService.proposeProspectingStrategy`
  (`format` = `{name, description, searchHints}`, sin `tools`).
- Java valida (nombre no vacío, no repetido contra el catálogo base ni contra las guardadas, incluidas las rechazadas;
  descripción no vacía) y guarda `(:ProspectingStrategy {id, name, description, searchHints, status: PENDING_APPROVAL,
  proposedBy:'growth-content', proposedAt, decidedAt, decidedBy})`. Inválida → se registra y no se guarda.
- El fundador aprueba o rechaza (🔴): `PUT /api/company/prospecting/strategies/{id}/approve|reject` o por chat.

## 3. Control y visibilidad

- **Interruptor**: policy `PROSPECTING_ENABLED` (0/1, default 0, admite 0 como `ORCHESTRATOR_ENABLED`). `AutonomyService`
  gana `setClients(boolean, origin)` y `clients.available = true`; "Todo en automático" manda los dos campos. Policy
  `MAX_PROSPECTS_PER_DAY` (default 10) en Settings.
- **API** (`ProspectingController`, `/api/company/prospecting`): `GET /prospects` (por producto), `GET /runs` (últimas
  20), `GET /strategies` (catálogo base + guardadas, con rendimiento), `POST /runs` (buscar ahora), `PUT /strategies/{id}/approve|reject`.
  `GET /api/company/autonomy` suma `waiting.pendingStrategies` y la fila de clientes muestra la última corrida.
- **Command Center**: pantalla nueva **Prospectos** (`/prospectos`, agregar a `App.tsx` y a `SpaController`): prospectos
  por producto (nombre, contacto + fuente, por qué encaja, estrategia, fecha), estrategias con rendimiento y
  aprobar/rechazar, últimas corridas con motivos de descarte. Dashboard: interruptor "Buscar clientes" activo y fila
  Clientes con la última corrida.
- **Chat** (Java): "¿qué prospectos tenemos?", "prospectos de X", "¿cómo va la búsqueda de clientes?", "¿qué estrategias
  hay pendientes?"; comandos de gobernanza "aprueba/rechaza la estrategia X" (nombre exacto gana, varios parciales →
  lista sin cambiar); "¿qué está en automático?" incluye clientes; "pon todo en automático" enciende ambos frentes.
- **Correo**: resumen por corrida con prospectos nuevos; aviso de estrategia propuesta.
- **Eventos**: `EMPRESA_PROSPECTING_RUN_COMPLETED` `{runId, productId, strategy, found, valid}`,
  `EMPRESA_PROSPECTING_RUN_FAILED` `{runId, productId, error}`, `EMPRESA_PROSPECTING_STRATEGY_PROPOSED|APPROVED|REJECTED`
  `{strategyId, name}`.

## Errores

- Convención del proyecto (`IllegalArgumentException` → 500 con mensaje). Aprobar/rechazar una estrategia que no está
  `PENDING_APPROVAL` → rechazo con motivo.
- Una corrida nunca tumba el servicio: excepción → `ProspectingRun FAILED` + evento + log.
- Si la ficha del producto está incompleta (sin cliente objetivo) la corrida no llama al modelo: `FAILED` con el motivo.

## Testing

- `StrategySelectorTest`: no usadas primero; luego mejor rendimiento; no repite la de ayer; empate → la más antigua;
  aprobadas entran, pendientes/rechazadas no.
- `ProspectValidatorTest`: nombre ausente de la página → descarte; email no presente literalmente → descarte; formulario
  que responde → válido; dominio repetido → descarte; URL no http(s) → descarte; página que falla → descarte.
- `ProspectingServiceTest`: apagado o sin productos listos → no corre; ficha incompleta → FAILED sin modelo; límite
  diario; rotación de productos; modelo caído → FAILED; una corrida por día.
- Estrategias: propuesta válida → PENDING; repetida → no se guarda; con una pendiente no se pide otra; aprobar/rechazar.
- `AutonomyServiceTest`: `clients` ahora disponible; "todo" enciende ambos; sin cambio no crea versión.
- `ChatIntentRouterTest`: consultas y comandos nuevos sin modelo; no chocan con comandos de producto ni del orquestador.
- En vivo: con un producto `READY_TO_SELL` real (o uno de prueba `TEST` si todavía no hay), encender "Buscar clientes",
  forzar una corrida, revisar prospectos (contacto verificado en su página) y motivos de descarte.

## Fuera de alcance

- Contactar (subproyecto 6), puntaje de calidad por prospecto, presupuesto de consultas de Serper más allá del límite
  diario, prospectar para ideas o productos en construcción.
