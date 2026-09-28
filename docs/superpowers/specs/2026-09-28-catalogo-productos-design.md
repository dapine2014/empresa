# Catálogo de productos y servicios — diseño

**Fecha**: 2026-09-28
**Estado**: diseño aprobado por el fundador en conversación (enfoque A); pendiente de revisión de este spec.
**Subproyecto 1 del "ciclo de producto"** (orden aprobado: 1 catálogo → 3 búsqueda diaria de prospectos → 2 orquestador
→ 4 contacto con prospectos).

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

## 1. Datos (Neo4j)

`(:Product {id, name, description, kind, targetCustomer, priceUsd, priceOnRequest, estimatedCostUsd, delivery, status,
createdBy, createdAt, updatedAt})`

- `kind`: `SOFTWARE` | `SERVICE`.
- `priceUsd` (≥ 0) o `priceOnRequest: true` ("a cotizar"); `estimatedCostUsd` (≥ 0), costo estimado por venta.
- `delivery`: cómo se entrega (obligatorio en `SERVICE` para estar listo).
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

## Testing

- `ProductReadiness`: cada requisito por separado, software vs servicio, a cotizar, margen cero o negativo.
- `ProductService`: transiciones válidas e inválidas, agente vs fundador (pausar/retirar solo fundador), rechazo con la
  lista de requisitos faltantes, edición que invalida "listo" → vuelve a construcción con historial, eventos.
- Finanzas por producto (`FinanceCalculator` filtrando por `productId`).
- Chat: lista, detalle por nombre sin tildes, requisitos faltantes, status con conteo.
- En vivo (Command Center y chat, con datos de prueba): crear producto → intentar "listo" y ver qué falta → asociar la
  misión de discovery y la de Engineering verificadas existentes → completar precio y costo → "listo" → pausar → reanudar;
  después borrar los datos de prueba.

## Fuera de alcance

- Que los agentes creen o muevan productos por su cuenta (lo hace el orquestador, subproyecto 2); en el chat los agentes
  son solo lectura.
- Búsqueda de prospectos (subproyecto 3), contacto y venta (subproyecto 4), precios en otras monedas, variantes o planes
  de un producto, inventario.
