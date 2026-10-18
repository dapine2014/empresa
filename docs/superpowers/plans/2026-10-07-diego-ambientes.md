# Diego: migraciones en todos los ambientes y chequeo de SQL — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que Diego aplique las migraciones en todas las conexiones de la misión, en cascada TEST → PRODUCTION (cortando si un ambiente falla y entregando el error a la corrección automática), y que una migración con SQL inválido se corrija antes del `VERIFIED`.

**Architecture:** `EnvironmentCascade` (función pura) ordena las conexiones; `DatabaseApplyService` aplica en cascada con el `DatabaseSchemaApplier` de siempre y guarda el estado por conexión; `DevelopmentTeamStrategy` agrega el chequeo `DATABASE_APPLIED` (un ambiente fallido deja la validación `FAILED`, lo que activa la corrección automática existente); `MigrationFilesGate` deja editar las migraciones no aplicadas en ningún ambiente; `MigrationSqlCheck` (JSqlParser) valida la sintaxis al generar.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Neo4j driver plano, JDBC PostgreSQL, Testcontainers 2 (PostgreSQL efímero), JSqlParser (nuevo), JUnit 5 + Mockito, React + TS.

**Spec:** `docs/superpowers/specs/2026-10-07-diego-ambientes-design.md`

## Global Constraints

- Orden de la cascada: `TEST` antes que `PRODUCTION`; dentro de cada ambiente por nombre; la conexión que nombra el plan (`database.connectionName`) va primera dentro de su ambiente. Solo conexiones asociadas a la misión y del motor del plan.
- Si un ambiente falla, los siguientes no se aplican.
- Un ambiente fallido en la aplicación automática tras `VERIFIED` ⇒ chequeo `DATABASE_APPLIED` `FAIL`, `validationStatus` `FAILED`, sin `verifiedCommit`. Todo aplicado ⇒ `DATABASE_APPLIED` `PASS`.
- `MigrationFilesGate`: solo es inmutable una migración aplicada en algún ambiente de la misión.
- `MigrationSqlCheck` bloquea solo `CREATE TABLE`, `CREATE INDEX`, `ALTER TABLE`, `INSERT`, `UPDATE`, `DELETE`, `DROP`; lo demás pasa sin analizar.
- Errores de conexión siempre redactados (clave, host, usuario), como hoy.
- Los tests no usan bases reales salvo el test de integración con Testcontainers ya existente (`DOCKER_HOST=unix:///run/user/$UID/podman/podman.sock TESTCONTAINERS_RYUK_DISABLED=true`).
- Convenciones: Jackson 3, sin `@Async`, `IllegalArgumentException`/`IllegalStateException` → 500, determinismo en Java, comentarios en español citando "Spec Diego ambientes §N".
- `cd app && mvn test`; `cd app/frontend && npm run build && npm run lint`.

## Review Focus

1. **Una migración aplicada en TEST y fallida en PRODUCTION** (p. ej. datos distintos): la corrección no puede editar la ya aplicada en TEST; debe ir en una migración nueva (Task 2).
2. **Conexión que no responde** (PRODUCTION caída): cuenta como falla del ambiente, con el error redactado, y no deja la cascada a medias sin registro (Task 1).
3. **Misiones viejas con una sola conexión** (propiedades `databaseApplied`…): se leen como respaldo; nada se pierde (Task 1).
4. **SQL de PostgreSQL válido que JSqlParser no entiende** (`DO $$`, `CREATE FUNCTION`, `CREATE EXTENSION`, `GENERATED ALWAYS AS IDENTITY`): nunca bloquea (Task 3).
5. **"aplica el esquema" a pedido con un ambiente fallido**: muestra el resultado por ambiente y no cambia el `validationStatus` (Task 1/2).

---

### Task 1: Cascada por ambientes en `DatabaseApplyService`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/database/EnvironmentCascade.java`
- Modify: `app/src/main/java/com/aicompany/core/database/DatabaseApplyService.java` (`apply`, `applyLatest`, `status`, `connection`)
- Modify: `app/src/main/java/com/aicompany/core/service/MissionMemoryService.java` (`recordDatabaseApply` por conexión, lectura de `Mission.databaseApplies` con respaldo a las propiedades viejas)
- Test: `app/src/test/java/com/aicompany/core/database/EnvironmentCascadeTest.java`, `DatabaseApplyServiceTest.java`, el test de integración de `DatabaseSchemaApplier` (Testcontainers)

**Interfaces:**
- Produces:
  - `static List<DatabaseConnection> EnvironmentCascade.order(List<DatabaseConnection> ofMission, String engine, String namedFirst)`.
  - `record DatabaseApplyService.CascadeResult(List<EnvironmentResult> environments, boolean allApplied, String text)`; `record EnvironmentResult(String connection, String environment, List<Integer> applied, Integer failedVersion, String error, boolean skipped)`.
  - `DatabaseApplyService.apply(missionId, commitSha, plan)` devuelve `CascadeResult` (antes `String`); `applyLatest` devuelve el `text`.
  - `MissionDatabaseStatus` pasa a `(List<EnvironmentStatus> environments, List<MigrationLine> migrations, String lastResult)` con `record EnvironmentStatus(String connection, String environment, List<Integer> applied, Integer failedVersion, String state)` (`APPLIED`/`FAILED`/`SKIPPED`/`PENDING`).
  - `MissionMemoryService.recordDatabaseApply(missionId, connection, environment, applied, failedVersion, text)`; `Map<String, MissionMemoryService.DatabaseApply> databaseApplies(missionId)` (JSON en `Mission.databaseApplies`; si falta, arma una entrada desde `databaseConnection`/`databaseApplied`/`databaseFailedVersion`/`databaseLastResult`); `Set<Integer> appliedAnywhere(missionId)`.

- [ ] **Step 1: Tests que fallan**
  - `EnvironmentCascadeTest`: TEST antes que PRODUCTION; por nombre dentro del ambiente; la nombrada primero en su ambiente; excluye otro motor; lista vacía → vacía.
  - `DatabaseApplyServiceTest` (applier y conexiones mockeados): dos conexiones TEST/PRODUCTION → aplica en las dos en ese orden; TEST con error → PRODUCTION `skipped` y nunca llamado al applier; conexión que lanza → falla de ese ambiente con error redactado (Review Focus 2); una `Evidence` por migración aplicada y conexión; eventos por conexión con `environment`.
  - Integración (Testcontainers, mismo patrón que el test existente del applier): dos bases efímeras, la primera con una migración rota → la segunda queda sin historial (`forjai_schema_history` no existe o vacía).
- [ ] **Step 2: RED.**
- [ ] **Step 3: Implementar** `EnvironmentCascade.order` (filtro por motor, `Comparator` por ambiente `TEST`<`PRODUCTION`, luego nombrada primero, luego nombre); `apply` recorre la lista, corta tras la primera falla y marca el resto `skipped`; graba cada ambiente con `recordDatabaseApply`; arma `text` ("TEST (x): V1–V3 aplicadas · PRODUCTION (y): sin aplicar, falló TEST en V3"); `status` arma `EnvironmentStatus` desde `databaseApplies`. Mantener la tarea `<misión>-DATABASE` (estado `COMPLETED` si `allApplied`, si no `FAILED`).
- [ ] **Step 4: Verificar la Cypher nueva con `EXPLAIN`** contra Neo4j real (solo lectura, sin imprimir la clave).
- [ ] **Step 5: GREEN + suite** (`mvn test`; el de integración con las variables de Podman).
- [ ] **Step 6: Commit** — `"Diego: cascada de migraciones por ambiente"`.

### Task 2: `DATABASE_APPLIED` en la validación y migraciones editables

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (~550-563: cascada antes de `recordVerifiedCommit`; ~1862-1888: `existing` para `MigrationFilesGate`)
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/MigrationFilesGate.java` (`check(files, existing, Set<Integer> applied)`)
- Test: `DevelopmentTeamStrategyTest`, `MigrationFilesGateTest`

**Interfaces:**
- Consumes: Task 1 (`CascadeResult`, `appliedAnywhere`).
- Produces: chequeo `StaticCheck` con nombre `DATABASE_APPLIED`.

- [ ] **Step 1: Tests que fallan**
  - Estrategia: con base y `VERIFIED`, `apply` con todo aplicado → `DATABASE_APPLIED` PASS y `recordVerifiedCommit`; con un ambiente fallido → `DATABASE_APPLIED` FAIL con ambiente, conexión, `V<n>` y error, `validationStatus` `FAILED`, **sin** `recordVerifiedCommit`; sin base → sin el chequeo (como hoy).
  - `MigrationFilesGateTest`: editar una migración aplicada en algún ambiente → error (mensaje actual); editar una commiteada que no está aplicada en ninguno → permitido; Review Focus 1: V2 aplicada en TEST y fallida en PRODUCTION → no editable (está aplicada en TEST).
- [ ] **Step 2: RED.**
- [ ] **Step 3: Implementar.** La cascada corre si `status == VERIFIED && databaseApply != null`; el chequeo se agrega a `checks` y se recalcula el estado (`FAILED` si falló); `recordVerifiedCommit` solo si el estado final es `VERIFIED`. En `databaseChecks`, pasar `memory.appliedAnywhere(missionId)` a `MigrationFilesGate.check`; el mensaje del error explica "aplicada en <ambiente>".
- [ ] **Step 4: GREEN + suite.**
- [ ] **Step 5: Commit** — `"Diego: la falla de un ambiente vuelve a la corrección; se corrigen las migraciones no aplicadas"`.

### Task 3: `MigrationSqlCheck` con JSqlParser

**Files:**
- Modify: `app/pom.xml` (dependencia `com.github.jsqlparser:jsqlparser`, última versión 5.x disponible en Maven Central, exacta)
- Create: `app/src/main/java/com/aicompany/core/agent/validation/MigrationSqlCheck.java`
- Modify: `DevelopmentTeamStrategy.databaseChecks` (agregar el chequeo junto a `MigrationFilesGate`)
- Test: `app/src/test/java/com/aicompany/core/agent/validation/MigrationSqlCheckTest.java`

**Interfaces:**
- Produces: `static List<String> MigrationSqlCheck.errors(List<DevelopmentResult.GeneratedFile> files)` — un mensaje por error: `"<archivo>, sentencia <k> (línea <n>): <mensaje del parser>. Corrige el SQL de PostgreSQL."`.

- [ ] **Step 1: Tests que fallan**: `CREATE TABLE clientes (id uuid PRIMARY KEY, nombre text NOT NULL` (sin cerrar) → error con sentencia y línea; `CREATE TABLE`/`CREATE UNIQUE INDEX`/`ALTER TABLE … ADD CONSTRAINT`/`INSERT` válidos → sin errores; `DO $$ BEGIN … END $$;`, `CREATE FUNCTION … $$ … $$ LANGUAGE plpgsql;`, `CREATE EXTENSION IF NOT EXISTS pgcrypto;`, `id bigint GENERATED ALWAYS AS IDENTITY` → sin errores (Review Focus 4); punto y coma dentro de comillas o comentarios `--`/`/* */` no corta la sentencia; archivos fuera de `db/postgres/migrations/` se ignoran.
- [ ] **Step 2: RED.**
- [ ] **Step 3: Implementar**: dividir respetando comillas simples, `--`, `/* */` y bloques `$tag$…$tag$`; clasificar por la primera palabra clave (ignorando comentarios y espacios); para las soportadas, `CCJSqlParserUtil.parse(sentencia)`; un `JSQLParserException` → error; si JSqlParser falla en una construcción válida de PostgreSQL dentro de una sentencia soportada (p. ej. `GENERATED ALWAYS AS IDENTITY`), ajustar: la lista de lo soportado se valida contra los tests de Step 1, y lo que el parser no maneje se excluye explícitamente (nunca bloquear SQL válido).
- [ ] **Step 4: GREEN + suite.** Verificar la licencia del jar en el `pom` de JSqlParser (Apache-2.0/LGPL dual) y anotarla en el reporte.
- [ ] **Step 5: Commit** — `"Diego: chequeo de sintaxis SQL de las migraciones"`.

### Task 4: Chat, Missions y documentación

**Files:**
- Modify: `ChatIntentRouter` ("¿cómo va la base de MISSION-X?" y "aplica el esquema" por ambiente), `app/frontend/src/api/types.ts`, `app/frontend/src/pages/MissionDetailPage.tsx` (sección de base de datos por ambiente)
- Modify: `docs/ARCHITECTURE.md` (Diego), `docs/EVENTS.md` (eventos con `environment`), `docs/HISTORY.md`, `docs/FALTANTES.md` (PostgreSQL en el sandbox: descartado por decisión del fundador; cascada hecha), `CLAUDE.md` solo si cambia el mapa general
- Test: `ChatIntentRouterTest`

- [ ] **Step 1: Tests que fallan** (chat): con dos ambientes → la respuesta lista "TEST (x) …" y "PRODUCTION (y) …" con sus versiones y el motivo si quedó sin aplicar.
- [ ] **Step 2–3: Implementar** chat y la sección de Missions (una línea por ambiente con ✅/❌/⏸ y versiones).
- [ ] **Step 4: GREEN + suite + build/lint del frontend; docs.**
- [ ] **Step 5: Commit** — `"Diego: el chat y Missions muestran la base por ambiente; docs"`.
