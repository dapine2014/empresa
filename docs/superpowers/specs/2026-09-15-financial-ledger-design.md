# Ledger financiero — diseño

**Fecha**: 2026-09-15
**Estado**: aprobado por el usuario en brainstorming, pendiente de plan de implementación.

## Contexto y objetivo

Hoy Forjai ya registra ventas reales (`Customer`/`Transaction`, con `netProfitUsd` por transacción y `CustomerMemoryService.companyWideTotalRevenueAndCost()` agregando ingresos/costos de venta a nivel empresa, usado por el chat en `COMPANY_STATUS`). Lo que falta es un **libro contable real**: un balance de la empresa que parta del capital semilla (`company.seed-capital-usd`, US$50) y sume/reste todos los movimientos de dinero reales — incluyendo gastos operativos propios de operar la empresa (dominios, herramientas, infraestructura de nube si algún día se paga) que hoy no tienen ninguna entidad donde registrarse.

Este documento es el resultado de una sesión de brainstorming (proceso `superpowers:brainstorming`, path arquitectural) — cada decisión de abajo fue presentada como opción A/B al usuario y aprobada explícitamente antes de escribir este spec.

## Decisiones de diseño

### 1. `Expense` es una entidad nueva; `Transaction` no se toca

El ledger **no duplica** `Transaction` en una nueva tabla/nodo "LedgerEntry" persistido. Se agrega únicamente `Expense` (gasto operativo, algo que hoy no existe como entidad) y el ledger se arma como una **vista calculada** en `LedgerService`, combinando `Transaction` (leído, no copiado) + `Expense` (nuevo) en Java — mismo patrón que `ActivityMemoryService.recent()`, que ya arma una línea de tiempo por UNION sobre entidades existentes sin duplicarlas.

Cada `Transaction` real se traduce en el ledger a **dos líneas** (no una neta): `SALE_REVENUE` (monto = `revenueUsd`) y `SALE_COST` (monto = `costUsd`, si es mayor a 0) — para que sea contabilidad real (bruta), no solo el neto que ya expone `GET /net-profit`.

### 2. Modelo de datos: `Expense`

Nodo nuevo en Neo4j:

```
(:Expense {
  id: string,            // UUID
  amountUsd: double,     // > 0, siempre positivo en el nodo; el signo se aplica al formatear el ledger
  description: string,   // qué fue el gasto (ej. "Dominio forjai.com") — obligatorio, no vive en Evidence
  environment: string,   // "PRODUCTION" | "TEST", default PRODUCTION si se omite
  createdAt: datetime
})
```

Relaciones:
- `(:Expense)-[:HAS_EVIDENCE]->(:Evidence)` — misma forma de `Evidence` que `Customer`/`Transaction` (`description`/`source`/`sourceType`/`verified`), validada por el **mismo** `EvidenceValidationGate.validate(List<Evidence>)` que ya usan esos dos (sobrecarga existente, no depende de `AgentResult`). `agentId` guardado en el nodo `Evidence` es el literal `"human"`, igual que `Customer`/`Transaction`.
- `(:Mission)-[:HAS_EXPENSE]->(:Expense)` — solo si `missionId` viene en el comando. Si no, el `Expense` no cuelga de ninguna misión (gasto general de la empresa).

Constraint de unicidad `expense_id` agregado a `CompanyMemoryService.initializeSchema()`.

**Inmutable**: no hay `PUT`/`DELETE` para `Expense` — mismo criterio que `Customer`/`Transaction`/`Decision`. Cómo corregir un gasto cargado por error queda fuera de alcance de esta ronda (ver "Fuera de alcance" abajo).

### 3. Quién registra un `Expense`

Solo el fundador humano, mismo canal de confianza que `CustomerController` (empresa.md nivel 🔴: gastar dinero real requiere aprobación humana, ningún agente lo hace autónomamente). No hay ningún flujo que permita a un agente llamar a este endpoint.

### 4. `Expense.environment` — mismo patrón que `Mission.environment`

Justo porque esta misma feature se va a probar en desarrollo, un `Expense` de prueba contaminaría el balance real igual que pasaba con las misiones de `TEST` antes de que existiera `Mission.environment`. Mismo criterio: default `PRODUCTION` si se omite; quien carga un gasto de prueba debe marcarlo `"TEST"` explícitamente.

El balance del ledger (`GET /ledger`) se calcula **solo sobre `PRODUCTION`** — tanto para `Expense.environment` como, indirectamente, para `Transaction` (filtrando por el `environment` de su `Mission`). Las líneas `TEST` se **listan igual** en la respuesta (visibilidad, mismo criterio que la columna de entorno en `MissionsPage.tsx`) pero no suman al balance ni llevan `runningBalanceUsd`.

### 5. Endpoints (`LedgerController`, nuevo)

- **`POST /api/company/ledger/expenses`** (`ExpenseCommand{amountUsd, description, missionId?, environment?, evidence}`): registra un `Expense`. Un solo endpoint (no anidado bajo `/missions/{missionId}/...`) porque `missionId` es opcional — evita duplicar la ruta para gasto-de-misión vs. gasto-general. Si `missionId` viene y no existe, sigue el mismo manejo de errores que ya usa `CustomerController` para un caso equivalente (verificar el código real de `CustomerController` al implementar, no asumir un status HTTP específico de memoria). Evidencia inválida → rechazada por `EvidenceValidationGate`, mismo camino que `Customer`/`Transaction`.

- **`GET /api/company/ledger`**: devuelve

  ```json
  {
    "seedCapitalUsd": 50.0,
    "balanceUsd": 38.0,
    "entries": [
      {
        "type": "SALE_REVENUE" | "SALE_COST" | "OPERATING_EXPENSE",
        "amountUsd": -12.0,
        "description": "Dominio forjai.com",
        "missionId": null,
        "environment": "PRODUCTION",
        "occurredAt": "2026-09-10T14:00:00Z",
        "runningBalanceUsd": 38.0
      }
    ]
  }
  ```

  `entries` ordenadas cronológicamente ascendente. `amountUsd` con signo (`SALE_REVENUE` positivo; `SALE_COST`/`OPERATING_EXPENSE` negativos) para que sumar la lista dé el balance. `runningBalanceUsd` solo se calcula y aparece en líneas `PRODUCTION` (acumulado en orden sobre esas líneas); en líneas `TEST` viene `null`. Sin paginación (mismo límite fijo/sin límite que `GET /missions`, no es una preocupación real todavía).

### 6. Capas internas

- `ExpenseMemoryService` (nuevo, Neo4j) — crea/lista `Expense`, mismo patrón que `CustomerMemoryService`.
- `CustomerMemoryService` gana un método de lectura nuevo (`listAllTransactions()` o similar) que devuelve las `Transaction` reales con su `missionId`/`environment` (vía la `Mission` a la que pertenecen) — hoy solo existe el agregado `companyWideTotalRevenueAndCost()`, que suma pero no expone las filas individuales.
- `LedgerService` (nuevo, sin acceso directo a Neo4j) — orquesta: pide transacciones a `CustomerMemoryService` y gastos a `ExpenseMemoryService`, arma la lista de `LedgerEntry` (record DTO, no persistido), ordena, calcula balance. Es la capa que `LedgerController` y `ChatIntentRouter` comparten (mismo criterio que los formatters deterministas ya existentes: una sola fuente de verdad para el cálculo, reusada por API y chat).

### 7. Integración con el chat (`ChatIntentRouter`)

- `formatCompanyStatus()` **agrega** una cláusula con el balance real (no reemplaza lo que ya muestra de ingresos/beneficio de venta) — ej.: `"...Balance actual: US$38.00 (capital semilla US$50.00 + ingresos US$0.00 - costos de venta US$0.00 - gastos operativos US$12.00)..."`. Usa `LedgerService` para el cálculo, no duplica la aritmética.
- Nuevo `QueryIntent.LEDGER` con keywords (`"gasto"`/`"gastos"`/`"ledger"`/`"libro contable"`/`"movimientos"`), chequeado antes del catch-all `COMPANY_STATUS` (mismo orden que las demás keywords específicas). `formatLedger()` nuevo — lista las últimas N líneas (mismo nivel de detalle que `formatFailedMissions`, sin abrir a un tamaño ilimitado en el texto de chat) + el balance actual, 100% determinista.
- `LEDGER` se agrega al enum `topic` de la herramienta `query_company_memory` en `CeoService` (mismo patrón que los topics existentes).

### 8. Frontend (`app/frontend/`)

- `LedgerPage.tsx` nueva: banner con `balanceUsd`/`seedCapitalUsd`, tabla de `entries` (tipo, descripción, monto con signo, entorno 🏢/🧪, misión si aplica — con link a `MissionDetailPage` si `missionId` no es null), y un formulario para `POST /ledger/expenses` (monto, descripción, misión opcional, campos de evidencia) — mismo patrón de interacción que `SettingsPage.tsx`.
- Ruta `/ledger` agregada a `App.tsx`, entrada de sidebar en `Layout.tsx`, y sumada a la lista explícita de `SpaController` (recordatorio: esa lista se mantiene a mano, ver `CLAUDE.md`).
- `api/types.ts` gana `LedgerEntry`/`LedgerResponse`/`ExpenseCommand`; `api/client.ts` gana `getLedger()`/`createExpense()`.

## Testing

- `LedgerServiceTest`: combina `Transaction`+`Expense` mockeados en el orden/signo correcto, balance corriente solo sobre `PRODUCTION`, líneas `TEST` presentes sin `runningBalanceUsd`, cada `Transaction` se parte en `SALE_REVENUE`+`SALE_COST` (o solo `SALE_REVENUE` si `costUsd=0`).
- Validación de `Expense` (evidencia rechazada, `missionId` inexistente → 404, `environment` default `PRODUCTION`) — nivel test unitario, mismo patrón que `CustomerServiceTest`.
- `LedgerControllerTest` (o equivalente `CompanyControllerTest`-style): los dos endpoints nuevos.
- `ChatIntentRouterTest`: ruteo nuevo a `LEDGER`, assertion actualizada de `COMPANY_STATUS` con la cláusula de balance, nueva rama `LEDGER` en el test del callback de `query_company_memory`.
- `ExpenseMemoryService`/el método nuevo de `CustomerMemoryService` sin test directo (integración Neo4j, mismo criterio que el resto de los `*MemoryService`).

## Fuera de alcance de esta ronda (documentado, no descartado)

- **Editar/borrar un `Expense`**: inmutable como el resto de los registros financieros del proyecto. Si se carga un gasto por error, no hay corrección automática todavía — queda para una ronda aparte si se vuelve un problema real.
- **Enforcement de presupuesto**: el ledger no bloquea un `Expense` que deje el balance en negativo o que supere el capital semilla — solo informa. Ninguna decisión de gasto está automatizada (nivel 🔴 de `empresa.md`), así que el fundador ya está en el loop antes de cargar el gasto.
- **Paginación en `GET /ledger`**: mismo criterio que `GET /missions` (límite fijo o sin límite) — no es una preocupación real con el volumen actual.
- **Categorías de gasto** (ej. `INFRASTRUCTURE`/`MARKETING`/`TOOLING`): se evaluó y se descartó por ahora — no hay ningún gasto operativo real todavía que necesite agruparse por categoría; agregar un enum sin un caso de uso real sería especular. `description` (texto libre) alcanza.
