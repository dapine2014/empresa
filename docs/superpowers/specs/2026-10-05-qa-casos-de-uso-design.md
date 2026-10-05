# QA: casos de uso → pruebas automatizadas — diseño

**Fecha**: 2026-10-05
**Estado**: diseño aprobado por el fundador en conversación (decisiones a, b y c); pendiente de revisión de este spec.
**Base**: Aria v1 (`2026-10-03-aria-v1-design.md`, escenarios `ESC-NN` aprobados por el fundador), sandbox
(`2026-09-26-sandbox-verification-design.md`), diagnóstico 360 de tests rojos (B1). Rama `qa-casos-de-uso`.

## Objetivo

Definición del fundador (2026-10-03): *la QA valida los casos de uso, que se vuelven casos de prueba, y los automatiza
para que en un futuro, si hay un cambio, sea más fácil correr las pruebas y hallar bugs.*

Cada escenario aprobado (`ESC-NN`) del incremento que se construye queda cubierto por al menos un test automatizado
que corrió y pasó en el sandbox; la suite solo crece entre incrementos y rondas; `VERIFIED` lo decide Java con esa
cobertura, no una revisión de código de Vera.

Por qué ahora (verificado en vivo, `MISSION-ARIA-CRM-1791093806`): el incremento 2 quedó `VERIFIED` con la app en
10/10 tests — los mismos del incremento 1: Vera no escribió ningún test de app para ESC-11..ESC-19. Hoy `VERIFIED`
solo exige ≥ 1 test y que pasen todos. Y la revisión estática de Vera bloqueó una app correcta
(`MISSION-B1-INV-1791031663`) y en otras corridas reportó "riesgos no verificados por presupuesto".

## Decisiones del fundador (2026-10-05)

- a) `VERIFIED` exige el **100 %** de los escenarios del incremento cubiertos por tests que pasaron.
- b) Se **quita** la revisión estática con `BLOCKER` de Vera; la reemplaza un reporte de cobertura armado por Java.
- c) Los tests de incrementos anteriores **se siguen corriendo y exigiendo** y no se pueden borrar ni debilitar.

## 1. Trazabilidad escenario → test

- Vera recibe en su prompt los escenarios del incremento (los de `RequirementsText.incrementBlock`, ya existe) y la
  regla: al menos un test por escenario, con el id en el nombre.
  - .NET (xUnit): el id en el nombre del método (`ESC_03_...`) o `[Trait("Escenario", "ESC-03")]`.
  - Flutter: el id al inicio de la descripción (`test('ESC-03 ...')` / `testWidgets('ESC-03 ...')`).
- Un escenario se cubre en el componente que corresponda (backend, app o ambos); basta un test que pase en
  cualquiera. Vera decide dónde; Java solo verifica.
- **Al generar** (Java, reintento con la lista exacta, como los demás gates): `ScenarioCoverageGate` busca en los
  archivos de test del repositorio + los devueltos los ids `ESC-\d{2}` del incremento; si falta alguno → "faltan tests
  para ESC-04, ESC-07" y Vera reintenta. Un id que no existe en el spec → reintento (no se inventan escenarios).

## 2. Tests con nombre en el sandbox

- El runner devuelve, por paso `test`, la lista de tests con nombre y resultado: `StepResult.tests: [{name, outcome}]`
  (`outcome` ∈ `PASSED|FAILED|SKIPPED`), además de los contadores que ya existen.
  - .NET: del TRX (`UnitTestResult testName` + `outcome`) y de los `Trait` si están (`TestReportParser`).
  - Flutter: del JSON de `flutter test --machine` (eventos `testStart` → nombre, `testDone` → `result`/`skipped`).
- `company-core` copia el contrato (`model/SandboxResult`); un runner viejo sin `tests` se trata como lista vacía.
  Cierra el pendiente "tests con nombre" de `docs/FALTANTES.md` (bloque 2).

## 3. Cobertura y `VERIFIED`

- `ScenarioCoverage` (Java, puro): escenarios del spec aprobado para los incrementos **1..N** (el que se construye y los
  anteriores, decisión c) × tests del sandbox → por escenario: tests que lo nombran y si alguno `PASSED`.
- `StaticValidationStatus.compute` pasa a: `VERIFIED` si pasan los chequeos, el sandbox, **todos los escenarios de los
  incrementos 1..N tienen ≥ 1 test que pasó** y no hay test fallido; si no, `FAILED` con el motivo ("ESC-12 sin test
  aprobado"). El "≥ 1 test" general queda subsumido.
- Misiones sin requisitos de Aria (anteriores o de otros equipos): reglas actuales, sin cambios.

## 4. La suite solo crece

- Al generar, `TestSuiteGrowthGate` (Java) compara los archivos y nombres de test del repositorio contra los del último
  commit `VERIFIED` de la misión (`Mission.verifiedCommit`): un test que existía y desaparece, cambia de nombre o se
  marca `Skip` → reintento ("no borres ni saltes el test ESC_02_..."). Agregar tests o archivos siempre se permite.
- En el sandbox, un test que pasaba en el último `VERIFIED` y ahora falta → `FAILED` (doble red: el gate mira el código,
  esto mira lo que corrió).
- El diagnóstico 360 sigue igual (Neo dictamina si el test o el código está mal); su corrección de QA ya no puede
  borrar ni bajar tests, y ahora además lo verifica este gate.

## 5. Vera: de revisora a dueña de la suite

- La tarea `VALIDATION` (`<misión>-QA-REVIEW`) deja de llamar al modelo para revisar código: la arma Java como
  **reporte de cobertura** — escenario por escenario: test(s), componente y resultado; tests nuevos de la ronda; tests
  heredados. Se guarda como hoy (`staticChecks` + resultado) para Missions y el chat.
- Se quitan del camino crítico: `StaticReviewResult` con `BLOCKER/MAJOR`, `RepositoryEvidenceGate`,
  `ForbiddenClaimsGuard` y `MissingFileClaimGate` para esta tarea (quedan en el código para misiones viejas si hace
  falta, sin usarse en las nuevas).
- Vera sigue con su tarea `WORK` de tests (después del código, como hoy), ahora con la regla de trazabilidad.

## 6. Ventanas del fundador

- Missions: sección "Cobertura (QA)" con la tabla escenario → test → resultado.
- Chat: "¿qué escenarios cubre MISSION-X?" / "¿qué escenarios fallan en MISSION-X?" (Java); el status de una misión
  incluye "cobertura 17/19".

## Fuera de esta versión

- Tests de punta a punta contra la API real (p. ej. el 409 de ESC-10 por HTTP): necesitan ambientes (B2).
- Cobertura de código (líneas): no se mide; se mide cobertura de escenarios.

## Errores

- Vera no cubre todos los escenarios en sus intentos → la tarea falla con la lista; la misión queda `FAILED` con
  "cobertura incompleta" (se corrige con una ronda).
- El runner no devuelve nombres (versión vieja) → `UNVALIDATED` con el motivo, nunca `VERIFIED`.

## Testing

Unitarios: `ScenarioCoverageGate` (falta, id inexistente, ids en .cs y .dart), `TestReportParser` con nombres (TRX y
JSON de Flutter), `ScenarioCoverage` (incrementos 1..N, fallido, sin test), `TestSuiteGrowthGate` (borrado, renombre,
`Skip`, agregado permitido), `StaticValidationStatus` con cobertura, reporte de Vera sin modelo, chat.

En vivo (`docs/HISTORY.md`): un CRM nuevo con Aria hasta `VERIFIED` con 100 % de cobertura; un incremento 2 donde la
cobertura del 1 se sigue exigiendo; un intento de borrar un test rechazado.
