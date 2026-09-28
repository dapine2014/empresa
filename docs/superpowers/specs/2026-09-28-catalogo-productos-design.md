# Catálogo de productos y servicios — diseño

**Fecha**: 2026-09-28
**Estado**: diseño aprobado por el fundador en conversación (enfoque A); pendiente de revisión de este spec.
**Subproyecto 1 del "ciclo de producto"**. Orden (revisado por el fundador el 2026-09-28, "somos una empresa IA, no tiene
sentido limitarnos"): 1 catálogo → 2 orquestador (sin producto ni propuesta del inversionista, Forjai investiga y crea
uno) → 3 búsqueda diaria de prospectos → 4 contacto con prospectos. Segunda fase: revender lo creado a otros clientes.

## Contexto y objetivo

Visión del fundador: Forjai necesita productos y servicios que ofrecer; si no los tiene y el inversionista no propone uno,
investiga, crea uno y luego consigue clientes. Hoy Forjai no tiene dónde registrar qué ofrece: las misiones investigan y
construyen, las ventas se registran en Finanzas, pero nada dice "esto es lo que vendemos".

Objetivo: un **catálogo** con el ciclo de vida de cada producto o servicio, cuyo paso a "listo para vender" lo controla
Java con requisitos verificables, editable desde el Command Center y consultable en el chat. Es la base de la búsqueda
diaria de prospectos (subproyecto 3) y del orquestador (subproyecto 2).

## Decisiones del fundador (2026-09-28)

1. **Autonomía**: los agentes pueden llevar un producto hasta "listo para vender" sin aprobación; el fundador lo frena
   (pausa) o lo retira cuando quiera. Marcarlo listo solo habilita la **búsqueda** de prospectos (🟢); contactar y vender
   siguen siendo 🔴 (subproyecto 4).
2. **Requisitos para "listo para vender"** (todos, verificados en Java): precio y cliente objetivo; evidencia de demanda;
   producto construido y verificado; margen estimado positivo.
3. **Enfoque A**: entidad `Product` propia con máquina de estados, no reutilizar `Opportunity` ni `Mission`.
4. Todo editable desde la UI, con historial de cambios (regla general del fundador).
5. **Mercado mundial siempre**: el cliente objetivo de un producto declara mercados e idiomas, con "mundial" por defecto.
6. **Un producto se vende muchas veces** (segunda fase: revender a otros clientes); el catálogo y Finanzas acumulan sus
   ventas por producto.
7. **Automatizaciones deterministas desde el día uno** (A) y **comandos del fundador en el chat** (B), ver §6.

## 1. Datos (Neo4j)

`(:Product {id, name, description, kind, targetCustomer, priceUsd, priceOnRequest, estimatedCostUsd, delivery, status,
createdBy, createdAt, updatedAt})`

- `kind`: `SOFTWARE` | `SERVICE`.
- `priceUsd` (≥ 0) o `priceOnRequest: true` ("a cotizar"); `estimatedCostUsd` (≥ 0), costo estimado por venta.
- `delivery`: cómo se entrega (obligatorio en `SERVICE` para estar listo).
- `markets` (lista; default `["WORLDWIDE"]`) e `languages` (lista; default `["en", "es"]`): dónde y en qué idiomas se
  buscan clientes. Nunca un país por defecto.
- `status`: `IDEA` | `IN_CONSTRUCTION` | `READY_TO_SELL` | `PAUSED` | `RETIRED`.
- `createdBy`: `"human"` o el `agentId` que lo creó.
- Relaciones: `(:Product)-[:VALIDATED_BY]->(:Mission)` (misiones de discovery que respaldan la demanda) y
  `(:Product)-[:BUILT_BY]->(:Mission)` (misiones que lo construyen: Engineering, Creative, Marketing).
- **Historial**: `(:Product)-[:HAS_CHANGE]->(:ProductChange {id, actor, field, from, to, reason, at})`, un nodo por campo
  cambiado o por cambio de estado. Nada del historial se edita ni se borra.
- **Finanzas**: `Transaction` (venta) gana `productId` opcional (`(:Transaction)-[:OF_PRODUCT]->(:Product)`); las ventas
  existentes no cambian. El resumen de Finanzas admite filtrar por producto (ingresos, costos y ganancias del producto).
- Constraint `product_id`.

## 2. Estados y reglas (Java, `ProductService`)

- Transiciones: `IDEA → IN_CONSTRUCTION → READY_TO_SELL`, y volver atrás entre esos tres; cualquiera → `PAUSED`;
  `PAUSED` → el estado anterior; cualquiera → `RETIRED`; `RETIRED` → `IDEA` (reactivar).
- **Quién**: fundador (`"human"`) y agentes pueden crear y mover entre `IDEA`, `IN_CONSTRUCTION` y `READY_TO_SELL`.
  **Solo el fundador** pausa, reanuda, retira y reactiva. Un agente que lo intenta recibe un rechazo explícito.
- **Requisitos para `READY_TO_SELL`** (`ProductReadiness`, función pura sobre el producto y los datos de sus misiones):
  1. `targetCustomer` no vacío y (`priceUsd > 0` o `priceOnRequest`).
  2. Al menos una misión `VALIDATED_BY` sin `teamId` con alguna `Evidence {sourceType:'WEB'}` en sus tareas.
  3. `SOFTWARE`: al menos una misión `BUILT_BY` con una tarea `VALIDATION` en `VERIFIED`. `SERVICE`: `delivery` no vacío.
  4. Margen estimado positivo: `priceOnRequest` o `priceUsd > estimatedCostUsd`.
  Si falta alguno, el rechazo enumera exactamente cuáles (mismo texto en pantalla y chat), y el producto no cambia.
- Editar un producto en `READY_TO_SELL` que deje de cumplir un requisito lo devuelve a `IN_CONSTRUCTION` con un cambio
  en el historial que dice por qué (el catálogo nunca muestra "listo" algo que no cumple).
- Eventos: `EMPRESA_PRODUCT_CREATED`, `EMPRESA_PRODUCT_UPDATED`, `EMPRESA_PRODUCT_STATUS_CHANGED` (`agentId` = actor).

## 3. API (`ProductController`, `/api/company/products`)

- `GET /api/company/products` (lista con estado y requisitos cumplidos/pendientes), `GET /{id}` (detalle + historial +
  finanzas del producto).
- `POST` crear, `PUT /{id}` editar campos (con `reason` opcional), `PUT /{id}/status` `{status, reason}`,
  `PUT /{id}/missions` `{validatedBy[], builtBy[]}` (asociar misiones).
- El actor viene del canal: el Command Center actúa como `"human"`; el orquestador (subproyecto 2) llamará al servicio
  con el `agentId`. Errores: `IllegalArgumentException` (convención del proyecto; la pantalla muestra el mensaje).

## 4. Command Center: pantalla "Productos" (`/productos`)

- Catálogo agrupado por estado; cada producto muestra tipo, precio, cliente objetivo y **requisitos para vender**
  (✅/❌ con el motivo).
- Detalle: editar campos, asociar misiones (selector), botones de estado según quién puede (pausar/retirar/reanudar/
  reactivar), historial de cambios, e ingresos/costos/ganancias del producto.
- Crear producto (formulario). En Finanzas, el formulario de venta gana un selector de producto opcional.
- Ruta en `App.tsx`, `Layout.tsx` y `SpaController`; `types.ts`/`client.ts` sincronizados.

## 5. Chat (Java)

- "¿qué productos tenemos?" / "catálogo": lista por estado.
- "¿cómo va el producto X?" / "¿qué le falta a X para venderse?": estado, requisitos (✅/❌), misiones y finanzas del
  producto. X se reconoce por nombre (sin mayúsculas ni tildes) o por id.
- "Dame un status" suma "Productos: N listos para vender, M en construcción".
- Topic `PRODUCTS` en `query_company_memory`.

## 6. Automatizaciones (A) y comandos en el chat (B)

**A. Deterministas, en Java (sin decisión del modelo)**:
- **Discovery → Idea**: al consolidar una misión de discovery (sin `teamId`) cuyo resultado de `product` (Luna,
  `OFFER_DESIGN`) trae una oferta, Java crea un producto `IDEA` (`createdBy: "product"`) con nombre y descripción de la
  oferta y la misión como `VALIDATED_BY`. Idempotente por misión (no duplica si la misión se re-ejecuta en una ronda de
  evidencia: actualiza la misma idea con historial). Si no hay oferta, no crea nada.
- **Construcción verificada → intento de "listo"**: cuando una misión `BUILT_BY` de un producto termina con su
  `VALIDATION` en `VERIFIED` (o, en un servicio, la misión de equipo completa), Java pasa el producto a
  `IN_CONSTRUCTION` si estaba en `IDEA` y evalúa los requisitos: si se cumplen, `READY_TO_SELL`; si no, deja en el
  historial qué falta. Un producto `PAUSED` o `RETIRED` nunca se mueve solo.
- Eventos y chat reflejan estos cambios con el actor (`product`, `system`).

**B. Comandos del fundador en el chat** (interpretados en Java, mismo nivel que "aprueba MISSION-X"): "pausa el producto
X", "reanuda X", "retira X", "reactiva X". X por nombre (sin mayúsculas ni tildes) o id; si es ambiguo, el chat lista los
candidatos y no cambia nada. Los agentes mencionados en el chat siguen siendo solo lectura.

## Testing

- `ProductReadiness`: cada requisito por separado, software vs servicio, a cotizar, margen cero o negativo.
- Automatizaciones: discovery con oferta crea una idea (y una ronda no la duplica); discovery sin oferta no crea nada;
  construcción `VERIFIED` con requisitos → `READY_TO_SELL`, sin requisitos → `IN_CONSTRUCTION` con lo que falta; `PAUSED`
  no se mueve.
- Comandos del chat: pausar/reanudar/retirar/reactivar por nombre sin tildes; nombre ambiguo lista candidatos sin cambiar.
- `ProductService`: transiciones válidas e inválidas, agente vs fundador (pausar/retirar solo fundador), rechazo con la
  lista de requisitos faltantes, edición que invalida "listo" → vuelve a construcción con historial, eventos.
- Finanzas por producto (`FinanceCalculator` filtrando por `productId`).
- Chat: lista, detalle por nombre sin tildes, requisitos faltantes, status con conteo.
- En vivo (Command Center y chat, con datos de prueba): crear producto → intentar "listo" y ver qué falta → asociar la
  misión de discovery y la de Engineering verificadas existentes → completar precio y costo → "listo" → pausar → reanudar;
  después borrar los datos de prueba.

## Fuera de alcance

- Lanzar misiones para crear un producto cuando no hay ninguno (orquestador, subproyecto 2). Adaptar o personalizar un
  producto por cliente (segunda fase).
- Búsqueda de prospectos (subproyecto 3), contacto y venta (subproyecto 4), precios en otras monedas, variantes o planes
  de un producto, inventario.
