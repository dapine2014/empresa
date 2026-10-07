# Diego: migraciones en todos los ambientes y chequeo de SQL — diseño

**Fecha**: 2026-10-07
**Estado**: diseño aprobado por el fundador en conversación; pendiente de revisión de este spec.
**Base**: Diego como DBA (`2026-10-02-diego-dba-postgresql-design.md`), misiones durables y corrección automática
(`2026-10-05-misiones-durables-design.md`, revisión 2026-10-06). Rama `diego-ambientes` (sobre `misiones-durables`).

## Objetivo

Decisión del fundador (2026-10-07): *yo entrego los ambientes; el DBA crea las bases y aplica las migraciones en los
ambientes según lo requiere la aplicación; los tests no van a la base, se mockean.* Por eso **PostgreSQL dentro del
sandbox queda descartado** (los tests siguen con fakes en memoria y el sandbox sin base).

Hoy una misión aplica sus migraciones en **una sola** conexión (la que nombra el plan de Neo o la única del motor) y un
error de sintaxis SQL recién aparece al aplicar en la base del fundador.

**Éxito**: con TEST y PRODUCTION asociados a una misión, al quedar el código `VERIFIED` Diego aplica las migraciones
primero en TEST y luego en PRODUCTION; si TEST falla, PRODUCTION no se toca y Diego lo corrige solo; una migración con
SQL inválido se corrige antes del `VERIFIED`.

## 1. Cascada por ambientes

- **Qué ambientes**: todas las conexiones asociadas a la misión (`(:Mission)-[:USES_DATABASE]->`) del motor del plan
  (`TeamPlan.DatabaseNeed.engine`, hoy `POSTGRESQL`). `database.connectionName` del plan deja de elegir una sola: si el
  plan nombra una, esa va primero dentro de su ambiente. Nunca se toca una conexión no asociada a la misión.
- **Orden**: primero `TEST`, después `PRODUCTION`; dentro de cada ambiente, por nombre. Determinista, en Java
  (`EnvironmentCascade`, función pura: conexiones → lista ordenada).
- **Corte**: si un ambiente falla, los siguientes no se aplican (quedan "sin aplicar: falló <ambiente> en V<n>").
- **Por ambiente** sigue igual que hoy (`DatabaseSchemaApplier`): crea la base si falta, `forjai_schema_history` con
  checksum, transacción por migración, lo ya aplicado no se repite; errores redactados (clave, host, usuario).
- **Registro**: el estado por conexión se guarda en la misión (`Mission.databaseApplies`: JSON `{conexión: {environment,
  applied[], failedVersion, result, at}}`), reemplazando las propiedades de una sola conexión (`databaseConnection`,
  `databaseApplied`, `databaseFailedVersion`, `databaseLastResult`; se leen como respaldo en misiones viejas). La tarea
  `<misión>-DATABASE` (Diego) resume la cascada; una `Evidence` por migración aplicada y conexión
  (`database:<conexión>@<sha>/V<n>`, como hoy); eventos `EMPRESA_DATABASE_SCHEMA_APPLIED|FAILED` por conexión con
  `environment`.

## 2. Si un ambiente falla, la corrección automática se encarga

- En `DevelopmentTeamStrategy`, la cascada corre cuando el código queda `VERIFIED` y **antes** de registrar el
  `verifiedCommit`. Si algún ambiente falla: se agrega el chequeo `DATABASE_APPLIED` en `FAIL` (detalle: ambiente,
  conexión, `V<n>` y el error redactado), el `validationStatus` pasa a `FAILED` y no se registra `verifiedCommit`. Con
  la validación `FAILED` la misión entra en la corrección automática ya existente (20 rondas, estrategia, `BLOCKED`):
  el `CorrectionReport` lleva ese chequeo fallido, así que Diego recibe el error real.
- Si todos los ambientes aplican: chequeo `DATABASE_APPLIED` en `PASS` ("TEST: V1–V3; PRODUCTION: V1–V3") y
  `verifiedCommit` como hoy.
- **Migraciones editables**: `MigrationFilesGate` hoy rechaza editar una migración ya commiteada. Pasa a rechazar solo
  las que están **aplicadas en algún ambiente** de la misión (según `Mission.databaseApplies`); una migración que falló
  y no quedó aplicada en ninguno se puede corregir (el historial con checksum de cada base protege igual a las aplicadas).
- "aplica el esquema de MISSION-X" (chat, botón, `POST …/database/apply`) corre la misma cascada sobre el
  `verifiedCommit`, con la misión detenida, como hoy; su resultado se muestra pero no cambia el `validationStatus`.

## 3. Chequeo de sintaxis SQL sin base

- `MigrationSqlCheck` (Java, puro): parsea cada migración con **JSqlParser** (dependencia nueva de `company-core`,
  licencia Apache-2.0/LGPL dual, versión exacta fijada en el `pom.xml`).
- Solo bloquea en sentencias que JSqlParser soporta bien: `CREATE TABLE`, `CREATE INDEX`, `ALTER TABLE`, `INSERT`,
  `UPDATE`, `DELETE`, `DROP`. Las demás (funciones, `DO $$ … $$`, extensiones, `COMMENT`, tipos propios) se dejan pasar
  sin analizar. La división en sentencias respeta comillas, comentarios y bloques `$$`.
- Se integra donde ya corren `MigrationFilesGate`/`SecretLiteralGate`/`DatabaseContractGate` al generar (tarea de Diego):
  un error devuelve la corrección exacta ("V2__clientes.sql, sentencia 3 (línea 14): <mensaje del parser>") y se
  reintenta como los demás gates.

## 4. Lo que ve el fundador

- **Missions** (sección de base de datos): una línea por ambiente: "TEST (base-pruebas) ✅ V1–V3", "PRODUCTION
  (base-prod) — sin aplicar: TEST falló en V3".
- **Chat**: "¿cómo va la base de MISSION-X?" responde por ambiente; "aplica el esquema de MISSION-X" corre la cascada.

## 5. Fuera de alcance

PostgreSQL en el sandbox (descartado por decisión del fundador); consultas a pedido; MongoDB/DocumentDB y
Firebase/Firestore (specs propios); ambientes distintos de `TEST`/`PRODUCTION`.

## 6. Pruebas

Unitarias: `EnvironmentCascade` (orden, conexión nombrada primero, solo las de la misión y del motor), corte tras una
falla, `DATABASE_APPLIED` PASS/FAIL y `verifiedCommit` solo con todo aplicado, `MigrationFilesGate` (aplicada → no
editable; fallida sin aplicar → editable), `MigrationSqlCheck` (error con sentencia y línea; sentencias no soportadas
pasan; `$$` y comentarios), chat y Missions por ambiente. Integración: el test real de `DatabaseSchemaApplier` con
Testcontainers ya existe; se agrega un caso de cascada con dos bases efímeras (TEST con una migración rota → la segunda
no se toca). En vivo: una misión con dos conexiones de prueba del fundador (o dos bases en su servidor de pruebas).
