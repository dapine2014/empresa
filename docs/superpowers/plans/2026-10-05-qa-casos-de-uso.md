# QA casos de uso → pruebas automatizadas — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que cada escenario `ESC-NN` aprobado de los incrementos construidos tenga un test automatizado que corrió y pasó, que la suite solo crezca y que `VERIFIED` lo decida Java con esa cobertura en lugar de la revisión de Vera.

**Architecture:** el runner devuelve los tests con nombre; Java cruza escenarios del spec aprobado (incrementos 1..N) contra esos nombres. Cobertura y crecimiento son dos `StaticCheck` más (`SCENARIO_COVERAGE`, `TEST_SUITE_GROWTH`), así que `FAILED`/`VERIFIED`, Missions y el chat los muestran con la maquinaria existente. Al generar, dos gates (cobertura en la tarea de Vera, crecimiento en todas) piden la corrección exacta. En misiones con requisitos de Aria la tarea `VALIDATION` deja de llamar al modelo: la arma Java como reporte de cobertura.

**Tech Stack:** Java 21, Spring Boot 4.1.1, JUnit 5 + Mockito (company-core y sandbox-runner), React + TS.

**Spec:** `docs/superpowers/specs/2026-10-05-qa-casos-de-uso-design.md`

## Global Constraints

- Ids de escenario: `ESC-\d{2}`; en .NET también vale `ESC_\d{2}` en el nombre del método (se normaliza a `ESC-NN`).
- `VERIFIED` (misiones con requisitos de Aria) exige 100 % de los escenarios de los incrementos 1..N con ≥ 1 test `PASSED`.
- Tests de incrementos anteriores: no se borran, no se renombran, no se saltan (`Skip`/`skip:`).
- Misiones sin requisitos de Aria: reglas actuales sin cambios (incluida la revisión de Vera).
- Runner viejo sin nombres de tests → nunca `VERIFIED` en misiones con requisitos (cobertura 0).
- Suites en verde: `cd app && mvn test`; `cd sandbox-runner && mvn test`; frontend `npm run lint && npm run build` si se toca.

## Review Focus

1. Un mismo test que nombra dos escenarios (`ESC_03_y_ESC_04_...`) cubre ambos. Test en Task 3.
2. Un test `SKIPPED` en el sandbox no cubre su escenario. Test en Task 3.
3. Una ronda de evidencia sin `verifiedCommit` previo (primera corrida) no falla el gate de crecimiento. Test en Task 3.
4. Un escenario cubierto en backend y fallando en app: cubierto si al menos un test pasó, pero el sandbox ya falla por el test rojo (no se esconde). Test en Task 3.
5. Una misión sin requisitos de Aria sigue llamando a la revisión de Vera y su `VERIFIED` no cambia. Test en Task 5.

---

### Task 1: el runner devuelve los tests con nombre

**Files:**
- Modify: `sandbox-runner/src/main/java/com/forjai/sandbox/TestReportParser.java`, `SandboxResult.java`, `VerifyJobRunner.java`
- Test: `sandbox-runner/src/test/java/com/forjai/sandbox/TestReportParserTest.java`

**Interfaces:**
- Produces: `TestReportParser.TestCounts(int passed, int failed, List<TestCase> tests)`; `SandboxResult.TestCase(String name, String outcome)` con `outcome ∈ PASSED|FAILED|SKIPPED`; `SandboxResult.StepResult(..., int testsPassed, int testsFailed, List<TestCase> tests)` + constructor de 7 argumentos (tests vacío).

- [ ] **Step 1: tests que fallan** (en `TestReportParserTest`):

```java
    // QA casos de uso (2026-10-05): cada test con su nombre y resultado; los Trait del TRX se suman al nombre.
    @Test
    void trxTestsComeWithNameOutcomeAndTraits(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".forjai"));
        Files.writeString(dir.resolve(".forjai/results.trx"), """
                <TestRun><Results>
                  <UnitTestResult testId="a" testName="Crm.Tests.ClientesTests.ESC_01_crea_cliente" outcome="Passed" />
                  <UnitTestResult testId="b" testName="Crm.Tests.ClientesTests.Lista" outcome="Failed" />
                  <UnitTestResult testId="c" testName="Crm.Tests.ClientesTests.Viejo" outcome="NotExecuted" />
                </Results><TestDefinitions>
                  <UnitTest id="b" name="Lista"><Properties><Property><Key>Escenario</Key><Value>ESC-02</Value></Property></Properties></UnitTest>
                </TestDefinitions>
                <ResultSummary><Counters total="3" passed="1" failed="1" /></ResultSummary></TestRun>
                """);
        var counts = TestReportParser.parse(dir);
        assertEquals(1, counts.passed());
        assertEquals(List.of(new SandboxResult.TestCase("Crm.Tests.ClientesTests.ESC_01_crea_cliente", "PASSED"),
                new SandboxResult.TestCase("Crm.Tests.ClientesTests.Lista [ESC-02]", "FAILED"),
                new SandboxResult.TestCase("Crm.Tests.ClientesTests.Viejo", "SKIPPED")), counts.tests());
    }

    @Test
    void flutterJsonTestsComeWithNameAndOutcome(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve(".forjai"));
        Files.writeString(dir.resolve(".forjai/results.json"), String.join("\n",
                "{\"type\":\"testStart\",\"test\":{\"id\":1,\"name\":\"loading /work/test/a_test.dart\"}}",
                "{\"type\":\"testDone\",\"testID\":1,\"result\":\"success\",\"hidden\":true,\"skipped\":false}",
                "{\"type\":\"testStart\",\"test\":{\"id\":2,\"name\":\"ESC-11 crea oportunidad\"}}",
                "{\"type\":\"testDone\",\"testID\":2,\"result\":\"success\",\"hidden\":false,\"skipped\":false}",
                "{\"type\":\"testStart\",\"test\":{\"id\":3,\"name\":\"ESC-12 lista\"}}",
                "{\"type\":\"testDone\",\"testID\":3,\"result\":\"success\",\"hidden\":false,\"skipped\":true}",
                "{\"type\":\"testStart\",\"test\":{\"id\":4,\"name\":\"ESC-13 borra\"}}",
                "{\"type\":\"testDone\",\"testID\":4,\"result\":\"failure\",\"hidden\":false,\"skipped\":false}"));
        var counts = TestReportParser.parse(dir);
        assertEquals(List.of(new SandboxResult.TestCase("ESC-11 crea oportunidad", "PASSED"),
                new SandboxResult.TestCase("ESC-12 lista", "SKIPPED"),
                new SandboxResult.TestCase("ESC-13 borra", "FAILED")), counts.tests());
    }
```

- [ ] **Step 2:** `cd sandbox-runner && mvn -q test -Dtest=TestReportParserTest` → falla (no existe `tests()`).
- [ ] **Step 3: implementación.** `TestCounts` suma `List<SandboxResult.TestCase> tests` (constructor de 2 argumentos = lista vacía). TRX: regex por `<UnitTestResult ...>` (atributos `testId`, `testName`, `outcome`; `Passed`→`PASSED`, `Failed|Error|Timeout|Aborted`→`FAILED`, el resto→`SKIPPED`); de `<UnitTest id="X">…</UnitTest>` los `ESC-\d{2}` que aparezcan en sus `<Value>` se agregan al nombre como ` [ESC-02]`. JSON Flutter: `testStart` guarda `id→name`; `testDone` no oculto: `skipped:true`→`SKIPPED`, `result:"success"`→`PASSED`, otro→`FAILED`; los contadores siguen saliendo como hoy (un `skipped` no cuenta como pasado). `SandboxResult.StepResult` suma `List<TestCase> tests` con constructor de compatibilidad de 7 argumentos; `VerifyJobRunner.runStep` pasa `counts.tests()`.
- [ ] **Step 4:** `cd sandbox-runner && mvn -q test` → todo verde.
- [ ] **Step 5:** commit `Runner: tests con nombre y resultado (TRX con Trait y JSON de Flutter)`.

---

### Task 2: company-core recibe los tests con nombre

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/SandboxResult.java`, `service/ComponentSandbox.java`
- Test: `app/src/test/java/com/aicompany/core/service/ComponentSandboxTest.java`

**Interfaces:**
- Produces: `SandboxResult.TestCase(String name, String outcome)`; `SandboxResult.StepResult.tests()` (nunca null; constructor de 7 argumentos = vacío); `SandboxResult.allTests() → List<TestCase>`.

- [ ] **Step 1: test que falla** en `ComponentSandboxTest`: combinar dos componentes conserva los tests de cada paso `test` (`backend/test` con `ESC_01_x` PASSED y `app/test` con `ESC-11 y` FAILED → `combined.allTests()` contiene los dos).
- [ ] **Step 2:** `cd app && mvn -q test -Dtest=ComponentSandboxTest` → falla.
- [ ] **Step 3:** record `TestCase`; `StepResult` con `tests` (compact constructor: null → `List.of()`); `allTests()`; `ComponentSandbox` copia `step.tests()` al prefijar.
- [ ] **Step 4:** `cd app && mvn -q test` → verde.
- [ ] **Step 5:** commit `Sandbox: los tests con nombre llegan a company-core`.

---

### Task 3: piezas puras de cobertura y crecimiento

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/ScenarioIds.java`, `ScenarioCoverage.java`, `TestInventory.java`, `TestSuiteGrowthGate.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/ScenarioCoverageTest.java`, `TestSuiteGrowthGateTest.java`

**Interfaces:**
- Produces:
  - `ScenarioIds.in(String text) → Set<String>` (`ESC-NN` y `ESC_NN` → `ESC-NN`).
  - `ScenarioCoverage.Scenario(String id, String title, int increment)`; `ScenarioCoverage.Row(Scenario scenario, List<String> passing, List<String> failing)` con `covered()`; `ScenarioCoverage.compute(List<Scenario>, List<SandboxResult.TestCase>) → List<Row>`; `ScenarioCoverage.check(List<Row>) → StaticCheck` (`SCENARIO_COVERAGE`, detalle "19/19 escenarios con test aprobado" o "17/19: sin test aprobado ESC-12 (…), ESC-15 (…)"); `ScenarioCoverage.sourceErrors(List<Scenario>, Map<String,String> testFiles) → List<String>` (al generar: faltan / ids inexistentes).
  - `TestInventory.of(Map<String,String> testFiles) → Map<String, Boolean>` (nombre de test → saltado); `TestInventory.isTestFile(String path)`.
  - `TestSuiteGrowthGate.errors(Map<String,Boolean> before, Map<String,Boolean> now) → List<String>`; `TestSuiteGrowthGate.check(List<SandboxResult.TestCase> before, List<SandboxResult.TestCase> now) → StaticCheck` (`TEST_SUITE_GROWTH`).

- [ ] **Step 1: tests que fallan**:

```java
// ScenarioCoverageTest
@Test void aTestNamingTwoScenariosCoversBoth() {
    var rows = ScenarioCoverage.compute(List.of(esc("ESC-03"), esc("ESC-04")),
            List.of(new SandboxResult.TestCase("Crm.ESC_03_y_ESC_04_flujo", "PASSED")));
    assertTrue(rows.stream().allMatch(ScenarioCoverage.Row::covered));
}
@Test void aSkippedOrFailedTestDoesNotCover() {
    var rows = ScenarioCoverage.compute(List.of(esc("ESC-11"), esc("ESC-12")), List.of(
            new SandboxResult.TestCase("ESC-11 a", "SKIPPED"), new SandboxResult.TestCase("ESC-12 b", "FAILED")));
    assertTrue(rows.stream().noneMatch(ScenarioCoverage.Row::covered));
    var check = ScenarioCoverage.check(rows);
    assertFalse(check.passed());
    assertTrue(check.detail().contains("0/2") && check.detail().contains("ESC-11"), check.detail());
}
@Test void oneComponentPassingIsEnough() {
    var rows = ScenarioCoverage.compute(List.of(esc("ESC-05")), List.of(
            new SandboxResult.TestCase("backend: ESC_05_x", "PASSED"), new SandboxResult.TestCase("ESC-05 app", "FAILED")));
    assertTrue(rows.get(0).covered());
    assertEquals(List.of("ESC-05 app"), rows.get(0).failing());
}
@Test void sourceErrorsListMissingAndUnknownIds() {
    var errors = ScenarioCoverage.sourceErrors(List.of(esc("ESC-01"), esc("ESC-02")), Map.of(
            "backend/tests/A.cs", "[Fact] public void ESC_01_x() {}", "app/test/a_test.dart", "test('ESC-09 z', () {});"));
    assertTrue(errors.stream().anyMatch(e -> e.contains("ESC-02")), errors.toString());
    assertTrue(errors.stream().anyMatch(e -> e.contains("ESC-09") && e.contains("no existe")), errors.toString());
}
// TestSuiteGrowthGateTest
@Test void deletingRenamingOrSkippingATestIsRejected() {
    var before = TestInventory.of(Map.of("backend/tests/A.cs", "[Fact] public void ESC_01_x() {}\n[Fact] public void ESC_02_y() {}",
            "app/test/a_test.dart", "test('ESC-11 z', () {});"));
    var now = TestInventory.of(Map.of("backend/tests/A.cs", "[Fact] public void ESC_01_x() {}\n[Fact(Skip = \"luego\")] public void ESC_02_y() {}",
            "app/test/a_test.dart", "test('ESC-11 zz', () {});"));
    var errors = TestSuiteGrowthGate.errors(before, now);
    assertTrue(errors.stream().anyMatch(e -> e.contains("ESC_02_y") && e.contains("saltado")), errors.toString());
    assertTrue(errors.stream().anyMatch(e -> e.contains("ESC-11 z") && e.contains("borraste o renombraste")), errors.toString());
}
@Test void addingTestsOrHavingNoPreviousVerifiedIsFine() {
    var now = TestInventory.of(Map.of("app/test/a_test.dart", "test('ESC-11 z', () {});\ntest('ESC-12 w', () {});"));
    assertEquals(List.of(), TestSuiteGrowthGate.errors(TestInventory.of(Map.of("app/test/a_test.dart", "test('ESC-11 z', () {});")), now));
    assertEquals(List.of(), TestSuiteGrowthGate.errors(Map.of(), now));
}
@Test void aTestThatPassedAndNoLongerRunsFailsTheSandboxCheck() {
    var check = TestSuiteGrowthGate.check(List.of(new SandboxResult.TestCase("ESC-11 z", "PASSED")),
            List.of(new SandboxResult.TestCase("ESC-12 w", "PASSED")));
    assertFalse(check.passed());
    assertTrue(check.detail().contains("ESC-11 z"), check.detail());
}
```

- [ ] **Step 2:** `mvn -q test -Dtest='ScenarioCoverageTest,TestSuiteGrowthGateTest'` → falla de compilación.
- [ ] **Step 3: implementación.** `TestInventory`: archivos con `TestTriageValidator.isTestFile`; .NET: `\[(Fact|Theory)(\([^)]*\))?\][^{;]*?\b(?:void|Task)\s+(\w+)\s*\(` → nombre = método, saltado si el atributo contiene `Skip`; Dart: `\b(?:test|testWidgets)\s*\(\s*(['"])(.*?)\1` → nombre = descripción, saltado si la llamada contiene `skip:` con `true` o texto. `TestSuiteGrowthGate.errors`: para cada nombre de `before` no saltado: ausente en `now` → "borraste o renombraste el test X"; saltado en `now` → "el test X quedó saltado". `check(before, now)`: los `PASSED` de `before` que no aparecen en `now` → `StaticCheck.fail("TEST_SUITE_GROWTH", …)`; si no, `pass`. `ScenarioCoverage.compute`: por escenario, tests cuyo `ScenarioIds.in(name)` lo contiene; `passing` = `PASSED`, `failing` = el resto.
- [ ] **Step 4:** verde.
- [ ] **Step 5:** commit `QA: cobertura de escenarios y suite que solo crece (piezas puras)`.

---

### Task 4: al generar — regla de Vera y gates

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java`
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java`

**Interfaces:**
- Consumes: Task 3; `RequirementsMemoryService.approved/currentIncrement`; `MissionMemoryService.verifiedCommit`.
- Produces: `DevelopmentTeamStrategy.setRequirements(RequirementsMemoryService)` (opcional); `List<ScenarioCoverage.Scenario> scenarios(String missionId)` (vacío sin requisitos aprobados); `static String qaScenarioRule(List<Scenario>)`.

- [ ] **Step 1: tests que fallan**: (a) con escenarios ESC-01..ESC-02 y una tarea de Vera, si su generación no nombra ESC-02, `runtime.generate` recibe un `extraChecks` que devuelve "faltan tests para ESC-02"; (b) el prompt de Vera contiene la regla con los ids; (c) con `verifiedCommit` cuyo test `ESC_01_x` no está en lo devuelto ni en HEAD, el extraCheck de cualquier tarea devuelve "borraste o renombraste el test ESC_01_x"; (d) sin requisitos, nada cambia (sin regla ni gates).
- [ ] **Step 2:** falla.
- [ ] **Step 3:** en `generate(...)`: si hay escenarios y la tarea es de un miembro con `roleCode` `QA` y kind WORK → check `ScenarioCoverage.sourceErrors(scenarios, testFiles(HEAD + devueltos))`; si hay `verifiedCommit` → check `TestSuiteGrowthGate.errors(inventario(verifiedCommit), inventario(HEAD + devueltos))` para toda tarea. `buildWorkPrompt` agrega `qaScenarioRule` a la tarea de Vera: "Escribe al menos un test por escenario con el id en el nombre (.NET: ESC_03_… o [Trait(\"Escenario\", \"ESC-03\")]; Flutter: test('ESC-03 …')). Escenarios: ESC-01 título; … No borres, renombres ni saltes tests existentes." `scenarios(missionId)`: spec aprobado → `storiesOf(n)` para n en 1..`currentIncrement` → escenarios.
- [ ] **Step 4:** `mvn -q test` verde.
- [ ] **Step 5:** commit `QA: Vera recibe los escenarios y Java exige cobertura y que la suite no decrezca al generar`.

---

### Task 5: validación — cobertura en lugar de la revisión de Vera

**Files:**
- Modify: `DevelopmentTeamStrategy.java`, `model/StaticValidationStatus.java`, `service/MissionMemoryService.java`
- Test: `DevelopmentTeamStrategyTest.java`, `StaticValidationStatusTest.java`

**Interfaces:**
- Produces: `StaticValidationStatus.compute(checks, review, sandbox, boolean coverageMode)` (con `coverageMode`, `review` null no impide `VERIFIED`); `MissionMemoryService.lastVerifiedSandboxJson(String missionId) → Optional<String>`.

- [ ] **Step 1: tests que fallan**: (a) con escenarios, tras el sandbox: no se llama `runtime.review`; los checks incluyen `SCENARIO_COVERAGE` y `TEST_SUITE_GROWTH`; todos cubiertos → `VERIFIED`; uno sin test aprobado → `FAILED` y el detalle lo nombra; la tarea `VALIDATION` queda `COMPLETED` con el reporte; (b) **sin escenarios** se sigue llamando `runtime.review` y el estado se calcula como hoy (Review Focus 5); (c) `compute(..., true)` con review null y todo pasando → `VERIFIED`; con un check fallido → `FAILED`.
- [ ] **Step 2:** falla.
- [ ] **Step 3:** tras el sandbox, si hay escenarios: `checks += ScenarioCoverage.check(compute(scenarios, sandbox.allTests()))` y `checks += TestSuiteGrowthGate.check(tests del último sandbox VERIFIED, sandbox.allTests())`; no se llama al modelo; `memory.updateTask(validationTaskId, "COMPLETED", reporte)` con una línea por escenario; `compute(checks, null, sandbox, true)`. Sandbox ausente → `UNVALIDATED` como hoy.
- [ ] **Step 4:** verde.
- [ ] **Step 5:** commit `QA: VERIFIED exige cobertura de escenarios; la revisión de Vera se reemplaza por el reporte de Java`.

---

### Task 6: chat, docs y despliegue

**Files:**
- Modify: `service/ChatIntentRouter.java`, `CLAUDE.md`, `docs/FALTANTES.md`, `docs/HISTORY.md`
- Test: `ChatIntentRouterTest.java`

- [ ] **Step 1: test que falla**: "¿qué escenarios cubre MISSION-9?" → respuesta en Java con el detalle del último `SCENARIO_COVERAGE` de la misión (lee los `staticChecks` de la última tarea `VALIDATION`); sin cobertura → "MISSION-9 no tiene cobertura de escenarios (misión sin requisitos de Aria o sin validar)".
- [ ] **Step 2–4:** implementar y verde.
- [ ] **Step 5:** docs (CLAUDE.md: Vera y `VERIFIED`; FALTANTES: "Escenarios BDD trazables" y "tests con nombre" `[x]`), commit, `bash sandbox/build-images.sh` no hace falta (solo cambia el runner Java): `docker compose build company-core sandbox-runner && docker compose up -d` tras confirmar que no hay misiones vivas.
- [ ] **Step 6: prueba en vivo**: CRM nuevo con Aria (el fundador aprueba las HU) hasta `VERIFIED` con `SCENARIO_COVERAGE` 100 %; registrar en HISTORY.
