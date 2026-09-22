# Company Financial Policies + Mission.financialCriteria — Diseño

**Fecha:** 2026-09-22
**Estado:** aprobado por el fundador humano, pendiente de plan de implementación.

## Contexto y motivación

`MissionExecutor` fijaba un objetivo universal para Max (finance): "superar
US$50 de utilidad neta" — un literal que en una ronda anterior se corrigió
para al menos referenciar `AppProperties.seedCapitalUsd()` en vez de un
número pegado en el string, pero que seguía siendo (a) un objetivo
*universal* impuesto a toda misión y (b) parte de un conjunto más amplio de
reglas financieras hardcodeadas en distintos archivos sin ningún mecanismo
de edición:

| Regla | Dónde vive hoy | Valor |
|---|---|---|
| Capital semilla | `AppProperties.seedCapitalUsd()` | US$50 |
| Ventana de tiempo | `AppProperties.challengeDays()` | 60 días |
| Objetivo de Max | `MissionExecutor` (hardcoded) | "superar US$50 de utilidad neta" |
| Umbrales de éxito de venta | `CustomerService` (`static final double`) | 50/100/1.000/5.000 |
| Multiplicador de alerta financiera | `ContradictionDetector.SEED_CAPITAL_MULTIPLE_THRESHOLD` | 100x |
| Fórmula de ganancia real | `CustomerService.netProfit()` | `revenue - cost` |
| Validación matemática | `AgentResultValidator` | recalcula cada `Calculation` |

El fundador confirmó una separación en 4 capas, y que las dos últimas filas
(fórmula de ganancia, validación matemática) **no** se tocan — son reglas de
dominio deterministas, no políticas de negocio configurables.

## Las 4 capas (separación de responsabilidades)

1. **Company Financial Policies** — parámetros de la empresa, versionados y
   editables desde el Command Center. Bloque 1 de este documento.
2. **Mission.financialCriteria** — objetivo financiero puntual de una
   misión concreta, estructurado, opcional, inmutable una vez creada la
   misión. Bloque 2.
3. **Agent Prompt** — cómo razona cada agente (sistema ya existente, sin
   cambios en este documento).
4. **Domain Financial Rules** — `netProfitUsd = revenueUsd - costUsd`,
   validación de `Calculation` (`AgentResultValidator`), gates de evidencia
   (`EvidenceValidationGate`/`EvidenceBindingGate`), enforcement de
   gobernanza/approvals. Código puro, determinista, **no editable por
   ningún mecanismo de este documento**.

Estas 4 capas quedan deliberadamente desacopladas: cambiar una política de
la empresa (capa 1) nunca reescribe el objetivo de una misión ya creada
(capa 2); un criterio financiero de misión nunca reemplaza ni fuerza los
valores que reporta un agente (capa 4); el prompt (capa 3) puede referenciar
las capas 1 y 2 como contexto, pero nunca sustituye lo que ellas garantizan.

---

## Bloque 1 — Company Financial Policies

### Modelo Neo4j

Mismo patrón que `PromptVersion` (ya construido y probado en este repo):

- `(:CompanyPolicy {key})` — nodo ancla, `key` es uno de 7 valores fijos en
  código (enum `PolicyKey`), no un catálogo dinámico:
  - `SEED_CAPITAL_USD`
  - `CHALLENGE_DAYS`
  - `CONTRADICTION_SEED_CAPITAL_MULTIPLE`
  - `SUCCESS_THRESHOLD_GOOD`
  - `SUCCESS_THRESHOLD_VERY_GOOD`
  - `SUCCESS_THRESHOLD_EXCELLENT`
  - `SUCCESS_THRESHOLD_EXTRAORDINARY`

  Agregar una octava política es un cambio de código (nuevo valor de enum +
  default de seed), no de datos — mismo criterio que la lista fija
  `TeamMemoryService.TEAMS`.

- `(:CompanyPolicy)-[:HAS_POLICY_VERSION]->(:PolicyVersion {id, key,
  version, value, createdBy, changeReason, createdAt})` — historial
  completo, inmutable.
- `(:CompanyPolicy)-[:HAS_ACTIVE_POLICY]->(:PolicyVersion)` — exactamente
  una, invariante garantizada transaccionalmente igual que
  `HAS_ACTIVE_PROMPT`.

`AppProperties.seedCapitalUsd()`/`challengeDays()` se degradan a **default
de seed en el primer arranque únicamente** — mismo patrón ya aplicado a
`ollama.agent-model`/`ollama.ceo-model` (ver "LLM: Ollama" en `CLAUDE.md`).
`ensureDefaultPolicies()` los usa como valor inicial de v1 para
`SEED_CAPITAL_USD`/`CHALLENGE_DAYS`; los otros 5 se siembran con su valor
hardcoded actual (100/50/100/1.000/5.000). Cambiar el `.yml` después del
primer arranque no pisa una política ya sembrada — igual que con
`Agent.model`.

### `CompanyPolicyService`

Mirror de `PromptMemoryService`:

```java
public enum PolicyKey {
    SEED_CAPITAL_USD, CHALLENGE_DAYS, CONTRADICTION_SEED_CAPITAL_MULTIPLE,
    SUCCESS_THRESHOLD_GOOD, SUCCESS_THRESHOLD_VERY_GOOD,
    SUCCESS_THRESHOLD_EXCELLENT, SUCCESS_THRESHOLD_EXTRAORDINARY
}

record PolicyVersionSummary(int version, double value, String createdBy, String changeReason, Instant createdAt) {}
record PolicySnapshot(String key, int activeVersion, double activeValue, String createdBy, String changeReason, Instant updatedAt, List<PolicyVersionSummary> history) {}
record PolicyCommand(double value, @NotBlank String changeReason) {}

class CompanyPolicyService {
    void ensureDefaultPolicies();                                   // idempotente, ApplicationReadyEvent
    List<PolicySnapshot> snapshotAll();
    PolicySnapshot snapshot(PolicyKey key);
    double activeValue(PolicyKey key);                              // hot path de lectura
    PolicySnapshot createVersion(PolicyKey key, double value, String changeReason); // crea Y activa (a diferencia de los prompts, acá no hay paso de "borrador")
    PolicySnapshot activateVersion(PolicyKey key, int version);      // rollback
}
```

Validación: `value` debe ser `> 0` en `createVersion` — rechazo con
`IllegalArgumentException` (mismo criterio de errores del resto del
proyecto: sin captura fina, cae al handler default de Spring → 500).

### Endpoints (`CompanyController`)

```
GET /api/company/policies                                    -> List<PolicySnapshot>
PUT /api/company/policies/{key}                               -> PolicySnapshot   (body: PolicyCommand)
PUT /api/company/policies/{key}/versions/{version}/activate    -> PolicySnapshot
```

`{key}` se parsea a `PolicyKey`; un valor fuera del enum lanza
`IllegalArgumentException` (500), misma convención que
`updateAgentModel` con un `agentId` inexistente.

### Migración de consumidores existentes

- **`ContradictionDetector`**: se mantiene como función pura, sin
  dependencia nueva a Neo4j (es una clase de reglas deterministas, no debe
  volverse un cliente de `CompanyPolicyService`). Cambia su firma para
  recibir el multiplicador como parámetro explícito, igual que ya recibe
  `seedCapitalUsd`:
  `detect(List<AgentResult> results, double seedCapitalUsd, double seedCapitalMultipleThreshold)`.
  Quien resuelve ambos valores desde `CompanyPolicyService` es
  `MissionExecutor`, antes de llamarlo.
- **`CustomerService`**: pierde la dependencia a `AppProperties` (su único
  uso era `seedCapitalUsd()`) y gana `CompanyPolicyService`. Los 4
  `static final double` de umbrales se reemplazan por
  `companyPolicyService.activeValue(PolicyKey.SUCCESS_THRESHOLD_*)` dentro
  de `successLevel(...)`, preservando exactamente los operadores actuales
  (`>=` para EXTRAORDINARIO/EXCELENTE, `>` para MUY_BUENO/BUENO).

### Frontend

Nueva sección "Financial Policies" dentro de `SettingsPage.tsx` — tabla de
las 7 políticas (label legible, valor activo, versión), editable inline
(motivo de cambio obligatorio al guardar, igual que el editor de prompt de
agentes), con historial y botón "Activar" por versión para rollback. Mismo
patrón UX que `PromptEditor`, adaptado a un valor numérico simple en vez de
texto largo — componente nuevo (`PolicyEditor`), no una generalización
forzada del componente de prompts (dominios distintos).

---

## Bloque 2 — `Mission.financialCriteria`

### Modelo

Propiedades aplanadas y opcionales en el nodo `Mission` (mismo criterio que
`Mission.environment` — sin nodo aparte, es 1:0..1 y pequeño):

```
m.financialCriteriaMetric        // string, nombre del enum FinancialMetric (hoy solo NET_PROFIT)
m.financialCriteriaTargetAmount  // double
m.financialCriteriaCurrency      // string, normalizado a mayúsculas, default "USD"
m.financialCriteriaDeadline      // string ISO (LocalDate), nullable incluso si el resto está presente
```

Si la misión no declaró objetivo financiero, las 4 propiedades quedan
ausentes (Cypher: asignar `null` a una propiedad la remueve, no hace falta
lógica condicional extra al persistir). Lectura con el mismo criterio de
compatibilidad hacia atrás que `environment` (`IS NULL` → ausente).

`financialCriteria` es **inmutable una vez creada la misión** — no existe
`PUT` para editarlo después. Se declara una sola vez, al arrancar.

### Validación determinista al crear la misión (`MissionService.start`)

- `currency`: si viene vacío/null, se normaliza a `"USD"` antes de
  persistir.
- `metric`: debe ser un valor soportado de `FinancialMetric` (hoy solo
  `NET_PROFIT`) — valor inválido en el body ya falla en la deserialización
  Jackson del enum, mismo resultado 500 que el resto del proyecto.
- `targetAmount`: debe ser `> 0`.
- `deadline`: opcional. Si está presente, debe ser una fecha válida
  respecto al contexto temporal de la misión — se rechaza si `deadline` es
  anterior a la fecha de creación de la misión (un plazo ya vencido al
  nacer no tiene sentido).

### DTOs

```java
public enum FinancialMetric { NET_PROFIT }

public record FinancialCriteriaCommand(
        FinancialMetric metric,
        double targetAmount,
        String currency,
        LocalDate deadline
) {
    public String currencyOrDefault() {
        return currency == null || currency.isBlank()
                ? "USD" : currency.toUpperCase(Locale.ROOT);
    }
}

public record FinancialCriteriaResponse(
        FinancialMetric metric,
        double targetAmount,
        String currency,
        LocalDate deadline
) {}
```

`MissionCommand` gana `FinancialCriteriaCommand financialCriteria`
(nullable). `MissionResponse` gana `FinancialCriteriaResponse
financialCriteria` (nullable).

### `MissionMemoryService`

- `ensureMission(missionId, instruction, environment, FinancialCriteriaCommand financialCriteria)`
  — extiende el `SET` existente para incluir las 4 propiedades (`null`
  cuando `financialCriteria` es `null`).
- Nuevo método de lectura dedicado y liviano:
  `financialCriteria(String missionId) -> Optional<FinancialCriteriaResponse>`
  (separado de `find()` para no acoplar el mock de tests de
  `MissionExecutor` al resto de `MissionResponse`).
- `find()`/`findAll()` también mapean las 4 propiedades a
  `FinancialCriteriaResponse` dentro de `MissionResponse` cuando están
  presentes.

### `MissionExecutor` — reemplazo completo del objetivo hardcodeado

Ya no existe un objetivo universal. El texto que recibe Max se arma en dos
partes, 100% Java, nunca interpretado por un LLM:

1. **Siempre**: instrucción base de la tarea `UNIT_ECONOMICS` + el capital
   semilla vigente, resuelto vía
   `companyPolicyService.activeValue(PolicyKey.SEED_CAPITAL_USD)` (ya no
   `appProperties.seedCapitalUsd()`).
2. **Si `financialCriteria != null`** (resuelto vía
   `memory.financialCriteria(missionId)`): se agrega un bloque estructurado
   —  "esta misión tiene un objetivo financiero explícito: {metric} ≥
   {targetAmount} {currency} para {deadline}" — con la instrucción
   explícita de que Max debe **analizar cómo alcanzarlo sin alterar los
   valores que reporte**: el criterio orienta el análisis y permite evaluar
   la propuesta, nunca fuerza ni redondea los números que Max calcula. Esto
   refuerza en texto lo que `AgentResultValidator` ya garantiza en código
   (cada `Calculation` se recalcula, no se acepta lo que no cierra).
3. **Si `financialCriteria == null`**: no se afirma ningún monto "a
   superar" — Max solo razona con el capital semilla vigente.

`MissionExecutor` gana `CompanyPolicyService` como dependencia nueva del
constructor. El punto donde hoy llama a
`contradictionDetector.detect(agentResults, appProperties.seedCapitalUsd())`
pasa a resolver también el multiplicador:
`contradictionDetector.detect(agentResults, seedCapitalUsd, companyPolicyService.activeValue(PolicyKey.CONTRADICTION_SEED_CAPITAL_MULTIPLE))`.

**Punto III (Max nunca autodeclara cumplimiento):** el `AgentResult` de Max
no gana ningún campo de "objetivo cumplido" — el cumplimiento se evalúa
exclusivamente en el Bloque 3, con código determinista, nunca por el
modelo.

---

## Bloque 3 — Evaluación determinista y superficies de consulta

### Evaluación de cumplimiento (`CustomerService.netProfit`)

`GET /missions/{id}/net-profit` es hoy el único punto donde se compara
contra resultados reales (`Transaction` registradas por el fundador —
nunca por un agente). Se extiende para incluir, cuando la misión tiene
`financialCriteria`:

```java
public record FinancialCriteriaEvaluation(
        FinancialMetric metric,
        double targetAmount,
        String currency,
        LocalDate deadline,
        boolean criterionMet,      // netProfit >= targetAmount (metric NET_PROFIT)
        double progressPct,        // netProfit / targetAmount * 100
        Boolean deadlinePassed     // null si no hay deadline; informativo, sin consecuencia automática
) {}
```

`MissionProfitResponse` gana `FinancialCriteriaEvaluation
financialCriteriaEvaluation` (nullable — ausente si la misión no declaró
criterio). Ningún vencimiento de `deadline` cierra, falla ni transiciona la
misión automáticamente — coherente con que solo el inversionista humano
decide el estado final de una misión (`POST /missions/{id}/decision`).

`CustomerService` gana `MissionMemoryService` como dependencia nueva (para
`financialCriteria(missionId)`).

### Chat / CEO (`ChatIntentRouter`)

El lookup determinista ya existente de `MISSION-<id>` (100% Java, resuelve
`ProductStatusService` + `MissionMemoryService`, nunca pasa por Ollama — ver
"Chat Intent Router" en `CLAUDE.md`) se extiende para incluir
`financialCriteria` y su evaluación (reutilizando
`CustomerService.netProfit(missionId)`) en el texto formateado. No hace
falta un `QueryIntent` nuevo ni un topic nuevo de `query_company_memory` —
ya es el mismo punto que resuelve el estado puntual de una misión.

### Frontend

- **`MissionDetailPage.tsx`**: muestra `financialCriteria` (si existe) y
  `financialCriteriaEvaluation` (del `GET .../net-profit`) — extensión de
  la página existente, sin componente nuevo.
- **`MissionsPage.tsx`**: formulario nuevo "Iniciar misión" — `missionId`
  autogenerado (`"MISSION-" + Date.now()`, mismo criterio que ya usa
  `ChatIntentRouter.detectFreeMissionStart` para misiones por chat, sin
  pedirle al fundador que invente un id), `instruction` (textarea),
  `environment` (select PRODUCTION/TEST, default PRODUCTION), y una
  sub-sección opcional colapsable "Objetivo financiero" (checkbox que
  revela `metric`/`targetAmount`/`currency`/`deadline`). Al enviar: `POST
  /api/company/missions`, invalida la query de `missions` en react-query.
- **`SettingsPage.tsx`**: sección "Financial Policies" (Bloque 1).

---

## Explícitamente fuera de alcance de este documento

- **No hay parsing LLM de criterios financieros desde texto libre.** Una
  misión iniciada por Chat en lenguaje libre
  (`ChatIntentRouter.detectFreeMissionStart`) sigue naciendo con
  `financialCriteria = null` — válido, es opcional — hasta que exista un
  mecanismo explícito (el formulario de este documento, u otro futuro) para
  declararlo. No se interpreta el texto del mensaje para inferirlo.
- **La fórmula de ganancia (`netProfitUsd = revenueUsd - costUsd`) y la
  validación matemática de `Calculation` no se tocan.** Son reglas de
  dominio deterministas (capa 4), no políticas de negocio.
- **Ningún vencimiento de `deadline` dispara automatización** (alerta,
  fallo de misión, etc.) — es puramente informativo en esta ronda.
- **El catálogo de `PolicyKey` es fijo en código**, no un mecanismo
  genérico de configuración arbitraria clave-valor — agregar una política
  nueva es un cambio de código (YAGNI: 7 valores conocidos no justifican un
  editor de esquema dinámico).

## Consistencia con el resto del repo

- Reutiliza exactamente el patrón `PromptVersion`/`HAS_ACTIVE_PROMPT` ya
  construido y probado (`PromptMemoryService`), aplicado ahora a
  `CompanyPolicy`/`PolicyVersion`.
- Reutiliza exactamente el patrón de "env var = solo default de seed del
  primer arranque, valor real vive en Neo4j" ya aplicado a `Agent.model`.
- Reutiliza exactamente el patrón de propiedad opcional aplanada +
  compatibilidad hacia atrás por `IS NULL`/`coalesce` ya aplicado a
  `Mission.environment`.
- Mantiene la convención de errores del proyecto (`IllegalArgumentException`/
  `IllegalStateException` sin captura fina → 500 vía el handler default de
  Spring) sin introducir manejo de errores nuevo.
