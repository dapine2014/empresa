# Finanzas: costos frente a ganancias — diseño

**Fecha**: 2026-09-27
**Estado**: diseño aprobado por el fundador en conversación (enfoque A); pendiente de revisión de este spec.
**Subproyecto 1 de "Command Center: todo editable"** (el 2 — modelo por agente y aprobación de dependencias — va aparte).

## Contexto y objetivo

El fundador mide el éxito de Forjai en **costos frente a ganancias** y se entera de todo por el chat. Hoy solo se pueden
registrar clientes y ventas por API, siempre dentro de una misión; los gastos propios de la empresa (dominios,
herramientas) no tienen dónde registrarse, y el Command Center no muestra nada de esto.

Objetivo: que desde el Command Center el fundador registre **clientes, ventas y gastos reales**, corrija errores con
**asientos de corrección**, y vea costos, ganancias y balance en una pantalla y en el chat.

Reimplementa sobre `master` el spec `2026-09-15-financial-ledger-design.md` (rama `worktree-financial-ledger`, ~190
commits atrás) con las decisiones nuevas de abajo. Lo que este spec no cambia de aquel sigue vigente como referencia,
pero **manda este**.

## Decisiones del fundador (2026-09-27)

1. **Correcciones por asiento**: ninguna venta, gasto ni cliente se edita ni se borra; un error se corrige con un
   movimiento que lo compensa (el original queda intacto y el historial es el propio libro).
2. **Misión opcional**: un cliente, una venta o un gasto puede asociarse a una misión o ser un movimiento general de la
   empresa. El producto se suma cuando exista el catálogo (ciclo de producto), sin romper esto.
3. **Evidencia**: una descripción obligatoria ("factura #123", "pago por Nequi") y un link opcional al comprobante; el
   registro queda como "declarado por el fundador". Adjuntar archivos queda fuera (necesita almacenamiento).
4. **Solo USD**: si se cobra o paga en otra moneda, el fundador convierte al registrar (puede anotar el original en la
   descripción).
5. **Enfoque A**: se reutilizan `Customer` y `Transaction`; se agregan `Expense` y `Correction`; Java arma libro y totales.
6. **Cliente = quien compra** (un prospecto de los agentes, `Customer {status:'LEAD'}`, nunca cuenta como cliente).

## 1. Datos (Neo4j; solo el fundador los crea, 🔴)

- **Cliente** (`Customer`, existente): `name`, `contact` opcional, misión opcional (`(:Mission)-[:HAS_CUSTOMER]->` solo si
  viene). Sin `status` (lo que lo distingue de un LEAD).
- **Venta** (`Transaction`, existente): cliente, `description`, `revenueUsd ≥ 0`, `costUsd ≥ 0`, misión opcional
  (`(:Mission)-[:HAS_TRANSACTION]->` solo si viene), `environment`.
- **Gasto** (`Expense`, nuevo): `amountUsd > 0`, `description`, misión opcional (`(:Mission)-[:HAS_EXPENSE]->`),
  `environment`, `recordedAt`. Constraint de unicidad `expense_id`.
- **Corrección** (`Correction`, nuevo): `targetId` (una venta o un gasto existente), `revenueAdjustmentUsd` y
  `costAdjustmentUsd` (con signo; en un gasto solo `costAdjustmentUsd`), `reason` obligatorio, `recordedAt`,
  `(:Correction)-[:CORRECTS]->(target)`. Hereda `environment` y misión del movimiento que corrige. Una corrección no se
  puede corregir (se corrige el original otra vez). Constraint `correction_id`.
- **Evidencia** de cada registro: `(:X)-[:HAS_EVIDENCE]->(:Evidence {description, source?, sourceType:'FOUNDER_DECLARED',
  verified:false, agentId:'human'})`. Si hay link, debe ser `http(s)`. Las rutas por misión existentes
  (`/missions/{id}/customers|transactions`, con su evidencia completa) siguen funcionando igual.
- **Entorno**: `PRODUCTION` por defecto; si hay misión, se toma el de la misión; `TEST` se lista pero no suma.
- Ids: los genera el backend (UUID); el formulario no los pide.

## 2. Cálculo (100% Java, `FinanceService`; sin tocar el modelo)

Sobre movimientos `PRODUCTION`:

- **Ingresos** = Σ `revenueUsd` de ventas + Σ `revenueAdjustmentUsd`.
- **Costos** = Σ `costUsd` de ventas + Σ `amountUsd` de gastos + Σ `costAdjustmentUsd`.
- **Ganancias** = ingresos − costos.
- **Balance** = capital semilla (policy `SEED_CAPITAL_USD`) + ganancias.
- Lo mismo **por misión** (movimientos asociados a esa misión, incluidas las correcciones de sus movimientos).
- **Libro**: cada venta da una línea de ingreso y, si `costUsd > 0`, una de costo; cada gasto, una de costo; cada
  corrección, una o dos líneas con el motivo y el movimiento que corrige. Orden cronológico, monto con signo, balance
  acumulado solo en líneas `PRODUCTION`.
- `GET /missions/{id}/net-profit` pasa a usar este cálculo (incluye gastos y correcciones de la misión); su contrato
  (comparación contra el capital semilla y umbrales de policy) no cambia.

## 3. API (`FinanceController`, nuevo; mismo canal humano que `CustomerController`)

- `GET /api/company/finance` → `{seedCapitalUsd, revenueUsd, costsUsd, profitUsd, balanceUsd, entries[]}` (+ `?missionId=`).
- `GET /api/company/finance/customers` → clientes reales (sin LEADs), con su misión si tiene.
- `POST /api/company/finance/customers` `{name, contact?, missionId?, evidenceDescription, evidenceLink?}`.
- `POST /api/company/finance/sales` `{customerId, description, revenueUsd, costUsd, missionId?, environment?, evidenceDescription, evidenceLink?}`.
- `POST /api/company/finance/expenses` `{description, amountUsd, missionId?, environment?, evidenceDescription, evidenceLink?}`.
- `POST /api/company/finance/corrections` `{targetId, revenueAdjustmentUsd, costAdjustmentUsd, reason, evidenceDescription, evidenceLink?}`.
- Errores: validación → `IllegalArgumentException` (convención del proyecto, 500 con mensaje); misión o cliente o
  movimiento inexistente → mismo manejo que `CustomerController` hoy.
- Kafka (hoy clientes y ventas no publican eventos): `EMPRESA_CUSTOMER_REGISTERED`, `EMPRESA_SALE_RECORDED`,
  `EMPRESA_EXPENSE_RECORDED` y `EMPRESA_CORRECTION_RECORDED`, todos con `agentId="human"`, documentados en `docs/EVENTS.md`.

## 4. Command Center: pantalla "Finanzas" (`/finanzas`)

- **Arriba**: costos, ganancias (en verde/rojo según signo), ingresos y balance frente al capital semilla.
- **Libro**: tabla de movimientos (fecha, tipo, descripción, misión con link, monto con signo, balance acumulado,
  🧪 si es TEST), con botón **"Corregir"** en cada venta o gasto: abre un formulario con "Anular" (precarga el monto
  inverso) o un ajuste libre, más el motivo.
- **Formularios**: registrar cliente, venta (elige cliente existente) y gasto; misión opcional (selector de misiones),
  evidencia (descripción + link opcional).
- Filtro por misión. Ruta en `App.tsx`, `Layout.tsx` y la lista de `SpaController`; `types.ts`/`client.ts` sincronizados.

## 5. Chat (Java, nunca redactado por el modelo)

- Nuevo `QueryIntent.FINANCE` ("costos", "ganancias", "gastos", "cuánto hemos gastado", "movimientos", "balance",
  "finanzas"), antes del catch-all de status: costos, ganancias, ingresos, balance y los últimos 10 movimientos
  (incluidas correcciones con su motivo).
- "Dame un status" suma costos y balance a lo que ya muestra.
- "¿Cómo va MISSION-X?" agrega las finanzas de la misión si tiene movimientos.
- Topic `COMPANY_PROFIT` de `query_company_memory` usa `FinanceService` (incluye gastos y correcciones).

## Testing

- `FinanceService`: totales con ventas, gastos y correcciones; `TEST` listado pero sin sumar; balance acumulado; por
  misión; corrección de un gasto solo ajusta costos; "anular" deja el movimiento en cero.
- Validaciones: descripción de evidencia vacía, link no `http(s)`, montos negativos, `targetId` inexistente, corregir una
  corrección, cliente inexistente en una venta, misión inexistente.
- Controller: los 6 endpoints delegan en el servicio.
- Chat: `FINANCE` (y que no le gane a la gobernanza ni a las menciones), status con costos y balance, misión con finanzas.
- `net-profit` por misión incluye gastos y correcciones de la misión.
- En vivo (por el Command Center y el chat, con registros `TEST`): cliente → venta → gasto → corrección "Anular", y ver
  totales, libro y chat coherentes; después borrar los registros de prueba.

## Fuera de alcance

- Adjuntar archivos de comprobante; varias monedas; catálogo de productos (llega con el ciclo de producto); presupuestos y
  límites de gasto; editar el modelo de cada agente y aprobar dependencias desde la UI (subproyecto 2); que un agente
  registre movimientos (🔴, nunca).
