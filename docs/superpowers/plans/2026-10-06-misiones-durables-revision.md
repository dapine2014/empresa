# Misiones durables — revisión 2026-10-06: plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Aplicar la revisión del fundador: 20 rondas de corrección, estrategia nueva con búsqueda en internet cuando no hay avance, `BLOCKED` tratada como `FAILED`, "manos humanas" (verificar sin modelo lo que corrigió un desarrollador) y que una falla antes del código no quede bloqueada sin salida.

**Architecture:** Se apoya en lo ya construido en `com.aicompany.core.recovery` (`MissionRecoveryService`, `CorrectionReport`, `CorrectionRound`, `RecoveryMemoryService`) y en `MissionExecutor`/`DevelopmentTeamStrategy`. La estrategia es una pieza nueva (`CorrectionStrategist`): Java busca y lee páginas, Neo propone con `format` sin `tools`, Java valida. La verificación humana es un modo de `DevelopmentTeamStrategy` que solo corre chequeos, sandbox y cobertura sobre `HEAD`.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Jackson 3, Neo4j driver plano, JUnit 5 + Mockito, React + TS.

**Spec:** `docs/superpowers/specs/2026-10-05-misiones-durables-design.md` — sección "Revisión 2026-10-06" (manda sobre §3/§4 donde los contradiga).

## Global Constraints

- `MAX_AUTO_CORRECTIONS` default **20**; `MAX_MISSION_COST_USD` default **10**.
- "Sin avance" (2 rondas automáticas seguidas sin bajar el conteo) **no bloquea**: marca que la ronda siguiente necesita estrategia. Solo bloquean las 20 rondas del incremento o el costo.
- Estrategia: hasta 5 resultados de búsqueda, idioma `en`, hasta 3 páginas leídas con `EvidenceAcquisitionService.fetchPage`; Neo `engineering` con `format`, **nunca** `format` + `tools`; Java exige ≥1 fuente entre las URLs leídas y estrategia distinta de las ya intentadas para la huella; hasta 3 intentos; sin estrategia válida la ronda sigue sin ella.
- La estrategia corre en el hilo de la ronda (nunca en el despachador) y se guarda en el pedido de la ronda (retomar no vuelve a buscar).
- Evento nuevo: `EMPRESA_CORRECTION_STRATEGY_PROPOSED` (`data: {round, fingerprint, sources}`).
- Verificación humana: sin ninguna llamada a modelo; tarea `VALIDATION` `<misión>-HUMAN-VERIFY-<n>`; estados aceptados `BLOCKED`, `FAILED`, `AWAITING_INVESTOR`, `INTERRUPTED` y misión no viva; resultado `FAILED` → corrección con el contador del incremento en cero.
- Convenciones: Jackson 3, sin `@Async`, `IllegalArgumentException`/`IllegalStateException` → 500, determinismo en Java, `advanceMission` único punto de transición, comentarios en español con "Spec misiones durables, revisión 2026-10-06 §N".
- `cd app && mvn test`; `cd app/frontend && npm run build && npm run lint`.

## Review Focus

1. **Búsqueda web caída o sin resultados** (Serper sin key, 429, páginas que no responden): la ronda debe seguir sin estrategia, nunca fallar ni bloquear (Task 3).
2. **Neo inventa fuentes** (URLs que no se leyeron) o repite una estrategia ya intentada: Java la rechaza y reintenta; tras 3, sin estrategia (Task 3).
3. **Reinicio durante la búsqueda de estrategia**: la ronda retomada no debe buscar dos veces si la estrategia ya quedó guardada, ni gastar otra ronda (Task 4).
4. **"verifica MISSION-X" sobre una misión viva o en curso**: se rechaza con mensaje claro, nunca corre en paralelo (Task 5).
5. **Desarrollador que no hizo commit** (cambios sin commitear en el workspace): la verificación usa `HEAD` y debe decir que lo no commiteado no se verificó, sin borrarlo (Task 5).

---

### Task 1: 20 rondas, US$ 10, "sin avance" como disparador, `BLOCKED` = `FAILED` y falla antes del código

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CompanyPolicyService.java` (defaults)
- Modify: `app/src/main/java/com/aicompany/core/recovery/MissionRecoveryService.java` (`brake`, nuevo `needsStrategy`)
- Modify: `app/src/main/java/com/aicompany/core/service/MissionService.java` (`STOPPED`), `app/src/main/java/com/aicompany/core/database/DatabaseApplyService.java` (estados permitidos)
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java` (bloqueo sin repositorio ~729), `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (~152, scaffold)
- Test: `CompanyPolicyDefaultsTest`, `MissionRecoveryServiceTest`, `MissionServiceRequirementsTest`, `MissionExecutorTeamTest`, `DevelopmentTeamStrategyTest`, test de `DatabaseApplyService` existente

**Interfaces:**
- Produces: `public boolean MissionRecoveryService.needsStrategy(String missionId, int increment, int failingNow)` (true si las 2 últimas rondas automáticas del incremento desde la última marca del fundador no bajaron y `failingNow` tampoco); `brake` ya no devuelve "sin avance".

- [ ] **Step 1: Tests que fallan**

```java
// CompanyPolicyDefaultsTest — Revisión 2026-10-06 §1
@Test void theRevisedBrakeDefaults() {
    var d = CompanyPolicyService.defaults(appProperties);
    assertEquals(20.0, d.get(PolicyKey.MAX_AUTO_CORRECTIONS));
    assertEquals(10.0, d.get(PolicyKey.MAX_MISSION_COST_USD));
}

// MissionRecoveryServiceTest — Revisión §2: sin avance ya no bloquea; pide estrategia
@Test void noProgressAsksForAStrategyInsteadOfBlocking() {
    when(recovery.correctionRounds("M-1")).thenReturn(List.of(new CorrectionRound(1, 1, "a", 4, false),
            new CorrectionRound(2, 1, "b", 4, false)));
    assertTrue(service.brake("M-1", 1, 4).isEmpty());
    assertTrue(service.needsStrategy("M-1", 1, 4));
    assertFalse(service.needsStrategy("M-1", 1, 3));
}
```

Ajustar los tests existentes de "sin avance bloquea" (`noProgressInTwoRoundsBlocks` y similares) a la regla nueva, y los que usan 3 como tope al valor que stubbean (el stub de `MAX_AUTO_CORRECTIONS` sigue mandando).

```java
// MissionServiceRequirementsTest — Revisión §4: se construye el incremento siguiente sobre una BLOCKED
@Test void anIncrementCanBeBuiltOnABlockedMission() {
    when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.BLOCKED)));
    when(requirements.approved("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1,
            "{\"increments\":[{\"number\":1},{\"number\":2}]}", "APPROVED", "", null)));
    when(requirements.builtIncrement("M-9")).thenReturn(1);
    when(memory.evidenceRound("M-9")).thenReturn(3);
    assertDoesNotThrow(() -> service.buildIncrement("M-9", 2));
}

// MissionExecutorTeamTest — Revisión §6: sin repositorio que nunca existió, la ronda sigue (no BLOCKED)
@Test void aRoundWithoutRepositoryThatNeverExistedRuns() { /* round 1, headIfAny vacío, memory.tasks sin commitSha → development.execute llamado, nunca block */ }
@Test void aRoundWhoseRepositoryWasLostIsBlocked() { /* round 1, headIfAny vacío, una tarea con commitSha → block(...) */ }

// DevelopmentTeamStrategyTest — Revisión §6: ronda > 0 sin HEAD crea el scaffold de nuevo
@Test void aLaterRoundWithoutRepositoryWritesTheScaffold() {
    /* contexto de ronda 1, headIfAny vacío → commitAgentWork(..., "M-1-SCAFFOLD", ...) llamado */
}
```

Completar los cuerpos con los fixtures existentes de cada clase (los tests `aRoundAfterTheFirstWithoutRepositoryIsBlocked` / `…WithRepositoryRuns` de la tanda final son la base: el primero debe pasar a exigir commits previos).

- [ ] **Step 2: Correr y ver que fallan** — `cd app && mvn test -Dtest='CompanyPolicyDefaultsTest,MissionRecoveryServiceTest,MissionServiceRequirementsTest,MissionExecutorTeamTest,DevelopmentTeamStrategyTest'`.

- [ ] **Step 3: Implementar**
- Defaults: `MAX_AUTO_CORRECTIONS` 20.0, `MAX_MISSION_COST_USD` 10.0 (comentario "Revisión 2026-10-06 §1").
- `brake`: quitar el bloque "sin avance". Nuevo:

```java
    /** Revisión 2026-10-06 §2: dos rondas automáticas seguidas sin bajar lo que falla piden una estrategia nueva. */
    public boolean needsStrategy(String missionId, int increment, int failingNow) {
        var auto = CorrectionRound.automaticSinceFounder(recovery.correctionRounds(missionId), increment);
        if (auto.size() < 2) {
            return false;
        }
        var before = auto.get(auto.size() - 2).failing();
        var last = auto.get(auto.size() - 1).failing();
        return last >= before && failingNow >= last;
    }
```

- `MissionService.STOPPED` agrega `MissionStatus.BLOCKED`; `DatabaseApplyService` (su conjunto de estados detenidos) agrega `BLOCKED`.
- `MissionExecutor` (bloqueo sin repositorio): bloquear solo si `memory.tasks(missionId).stream().anyMatch(t -> t.commitSha() != null)`; si no, seguir.
- `DevelopmentTeamStrategy` (~152): `scaffold = head.isPresent() ? existingScaffold(context) : commitProjectScaffold(context);`.
- Actualizar el javadoc de la clase `MissionRecoveryService` y `docs` no (va en Task 6).

- [ ] **Step 4: Correr** los tests del Step 2 y luego `cd app && mvn test` → PASS.
- [ ] **Step 5: Commit** — `git commit -m "Durables (rev): 20 rondas, US$ 10, sin avance pide estrategia, BLOCKED como FAILED, falla antes del código"`.

---

### Task 2: Huellas de error y memoria de estrategias

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/recovery/CorrectionReport.java`
- Create: `app/src/main/java/com/aicompany/core/recovery/CorrectionStrategy.java`
- Modify: `app/src/main/java/com/aicompany/core/recovery/RecoveryMemoryService.java`
- Test: `app/src/test/java/com/aicompany/core/recovery/CorrectionReportTest.java`

**Interfaces:**
- Produces:
  - `CorrectionReport` gana `List<String> failures()` (cada cosa que falla, una por elemento, el mismo texto de las líneas) y `static String fingerprint(String failure)` (minúsculas; reemplaza `:\d+`, `\(\d+,\d+\)`, números de 4+ dígitos y rutas `/tmp/…`/`job-…` por marcadores; colapsa espacios). El record pasa a `CorrectionReport(String text, int failing, List<String> failures)`; los llamadores de `text()`/`failing()` no cambian.
  - `record CorrectionStrategy(String fingerprint, int round, String strategy, List<String> sources)`.
  - `RecoveryMemoryService.addStrategy(String missionId, CorrectionStrategy s)`, `List<CorrectionStrategy> strategies(String missionId)` (JSON en `Mission.correctionStrategies`, mismo patrón que `correctionRounds`), `void updateCorrectionRequest(String missionId, int round, String request)` (reescribe el `request` de esa ronda en `Mission.correctionRounds`).

- [ ] **Step 1: Tests que fallan**

```java
@Test void eachFailureIsListedAndFingerprintsIgnoreLineNumbersAndPaths() {
    var report = CorrectionReport.from(Optional.of(validation(/* DDD_LAYERS FAIL + un paso build con 2 errores CS0246 en líneas distintas del mismo archivo */)), "");
    assertEquals(report.failing(), report.failures().size());
    assertEquals(CorrectionReport.fingerprint("src/A.cs(12,5): error CS0246: The type 'Foo' could not be found"),
            CorrectionReport.fingerprint("src/A.cs(40,9): error CS0246: The type 'Foo' could not be found"));
    assertNotEquals(CorrectionReport.fingerprint("error CS0246: 'Foo'"), CorrectionReport.fingerprint("error CS0103: 'Foo'"));
}
```

- [ ] **Step 2: RED** — `cd app && mvn test -Dtest=CorrectionReportTest`.
- [ ] **Step 3: Implementar** (agregar `failures` llenando la lista en cada `lines.add(...)` de fallas reales —no en el encabezado ni en la regla—; `RecoveryMemoryService` con los tres métodos, `synchronized` en las escrituras).
- [ ] **Step 4: Verificar la Cypher nueva con `EXPLAIN`** contra Neo4j real (solo lectura, sin imprimir la clave), como en el plan anterior.
- [ ] **Step 5: GREEN + suite** — `cd app && mvn test`.
- [ ] **Step 6: Commit** — `"Durables (rev): huellas de error y memoria de estrategias"`.

---

### Task 3: `CorrectionStrategist` (búsqueda web + Neo + validación Java)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/recovery/CorrectionStrategist.java`
- Create: `app/src/main/java/com/aicompany/core/agent/model/StrategyProposal.java` (`record StrategyProposal(String strategy, List<String> sources)` + `SCHEMA`)
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java` (`proposeCorrectionStrategy`)
- Test: `app/src/test/java/com/aicompany/core/recovery/CorrectionStrategistTest.java`

**Interfaces:**
- Consumes: `CorrectionReport.failures()`, `fingerprint`; `RecoveryMemoryService.strategies`; `WebSearchPort.search(query, country, language, limit)` → `List<WebSearchResult>` (leer el record real); `EvidenceAcquisitionService.fetchPage(url)` → HTML; `CeoService` (patrón `callStructured`, como `triageFailingTests`).
- Produces: `Optional<CorrectionStrategy> CorrectionStrategist.propose(String missionId, int round, CorrectionReport current, List<CorrectionReport> previous, String stack)`.

- [ ] **Step 1: Tests que fallan** (mocks de `WebSearchPort`, `EvidenceAcquisitionService`, `CeoService`, `RecoveryMemoryService`, `CompanyMemoryService`)

```java
@Test void picksThePersistentErrorSearchesAndAcceptsAStrategyCitingAReadPage() { /* failure repetida en previous y current → query contiene "CS0246" y ".NET 8"; fetchPage de 2 urls; ceo devuelve sources=[url1] → Optional presente con fingerprint de esa falla */ }
@Test void anInventedSourceIsRejectedAndRetried() { /* 1er intento sources=["https://inventada"] → 2do intento válido; verify proposeCorrectionStrategy 2 veces y el prompt del 2do contiene "CORRECCIÓN" */ }
@Test void aRepeatedStrategyIsRejected() { /* strategies() ya tiene la misma estrategia normalizada para la huella → 3 intentos iguales → Optional.empty */ }
@Test void aSearchFailureGivesNoStrategyAndNeverThrows() { /* search lanza → Optional.empty, ceo nunca llamado */ }
@Test void noReadablePageGivesNoStrategy() { /* fetchPage lanza o vacío para todas → Optional.empty */ }
```

- [ ] **Step 2: RED.**
- [ ] **Step 3: Implementar**

```java
/**
 * Revisión 2026-10-06 §3: cuando una misión no avanza, Java elige el error persistente, lo busca en internet y lee las
 * páginas; Neo propone una estrategia (format, sin tools) y Java exige que cite una página leída y que no repita una
 * estrategia ya intentada para ese error. Nunca lanza: sin estrategia válida, la ronda sigue sin ella.
 */
@Service
public class CorrectionStrategist {
    static final int MAX_RESULTS = 5, MAX_PAGES = 3, MAX_ATTEMPTS = 3, EXCERPT_CHARS = 4000;
    // dependencias por constructor: WebSearchPort, EvidenceAcquisitionService, CeoService, RecoveryMemoryService,
    // CompanyMemoryService (modelo de "engineering"), PromptMemoryService (prompt activo de "engineering").

    public Optional<CorrectionStrategy> propose(String missionId, int round, CorrectionReport current,
                                                List<CorrectionReport> previous, String stack) {
        try {
            var failure = persistentFailure(current, previous);              // §3: repetida en los 2 últimos y el actual, o la primera
            if (failure == null) return Optional.empty();
            var fingerprint = CorrectionReport.fingerprint(failure);
            var tried = recovery.strategies(missionId).stream().filter(s -> s.fingerprint().equals(fingerprint)).toList();
            var pages = readPages(searchQuery(failure, stack));                 // url → extracto sin HTML, ≤ EXCERPT_CHARS
            if (pages.isEmpty()) return Optional.empty();
            String correction = null;
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                var proposal = ceo.proposeCorrectionStrategy("engineering", prompt(failure, tried, pages, correction),
                        promptMemory.activePrompt("engineering"), companyMemory.agentModel("engineering", defaultModel));
                var errors = errors(proposal, pages.keySet(), tried);
                if (errors.isEmpty()) {
                    return Optional.of(new CorrectionStrategy(fingerprint, round, proposal.strategy().strip(),
                            proposal.sources().stream().filter(pages::containsKey).toList()));
                }
                correction = String.join("\n", errors);
            }
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("STRATEGY - {} round {}: {}", missionId, round, ex.getMessage());
            return Optional.empty();
        }
    }
}
```

Detalles obligatorios: `searchQuery` = mensaje y código del error sin rutas ni números de línea + `stack`; `readPages` usa `fetchPage`, quita etiquetas/espacios y corta a `EXCERPT_CHARS` (si ya existe un helper de texto sin HTML —p. ej. `PageDigest` en `prospecting`— reutilizarlo); `errors` exige `strategy` no vacía, ≥1 `source` dentro de las URLs leídas, ninguna fuente fuera de ellas, y estrategia normalizada (minúsculas, sin espacios repetidos) distinta de las de `tried`. `prompt` en español, anti-alucinación ("usa SOLO las páginas de abajo; cita sus URLs exactas"), con el error, lo ya intentado y los extractos con su URL. `CeoService.proposeCorrectionStrategy(agentId, prompt, agentPrompt, model)` = `callStructured("CORRECTION_STRATEGY", …, StrategyProposal.SCHEMA, StrategyProposal.class)`. `stack` lo arma quien llama (Task 4).

- [ ] **Step 4: GREEN + suite.**
- [ ] **Step 5: Commit** — `"Durables (rev): CorrectionStrategist busca en internet y valida la estrategia en Java"`.

---

### Task 4: La ronda de corrección usa la estrategia

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/recovery/MissionRecoveryService.java` (`dispatchCorrection`)
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java` (`correctAsync`)
- Test: `MissionRecoveryServiceTest`, `MissionExecutorTeamTest`

**Interfaces:**
- Consumes: Task 1 `needsStrategy`; Task 2 `failures`, `addStrategy`, `updateCorrectionRequest`; Task 3 `CorrectionStrategist.propose`.
- Produces: `MissionExecutor.correctAsync(String missionId, int round, String request, boolean withStrategy)` (la versión de 3 argumentos delega con `false`); setter opcional `setCorrectionStrategist(CorrectionStrategist)`.

- [ ] **Step 1: Tests que fallan**

```java
// MissionRecoveryServiceTest: sin avance → la corrección se lanza con estrategia
@Test void noProgressLaunchesTheCorrectionWithStrategy() { /* rondas 4,4 y failingNow 4 → correctAsync(eq("M-1"), eq(3), anyString(), eq(true)) */ }

// MissionExecutorTeamTest: con estrategia, se antepone al pedido, se guarda y se publica
@Test void aCorrectionWithStrategyPrependsSavesAndPublishesIt() {
    /* strategist.propose → Optional(new CorrectionStrategy("fp", 3, "Reescribe X desde cero", List.of("https://a")));
       correctAsync("M-9", 3, "CORRECCIÓN AUTOMÁTICA (Java):\n- x", true).get();
       verify(recovery).addStrategy(eq("M-9"), any());
       verify(recovery).updateCorrectionRequest(eq("M-9"), eq(3), startsWith("ESTRATEGIA NUEVA (error persistente): Reescribe X desde cero"));
       verify(events).publish(eq("EMPRESA_CORRECTION_STRATEGY_PROPOSED"), eq("M-9"), isNull(), eq("engineering"), anyMap());
       verify(development).execute(argThat(c -> c.round() == 3 && c.plan().workTasks().stream()
               .allMatch(t -> t.objective().contains("ESTRATEGIA NUEVA"))), any()); */
}
// Review Focus 3: retomar una corrección cuya estrategia ya quedó guardada no vuelve a buscar
@Test void resumingACorrectionWithASavedStrategyDoesNotSearchAgain() { /* correctionRound(3).request empieza con "ESTRATEGIA NUEVA" → resumeAsync(CORRECTION, 3) → strategist nunca llamado */ }
// Review Focus 1: sin estrategia, la ronda corre igual
@Test void withoutAStrategyTheRoundRunsAnyway() { /* propose → Optional.empty → development.execute llamado, sin addStrategy */ }
```

- [ ] **Step 2: RED.**
- [ ] **Step 3: Implementar**
- `dispatchCorrection`: `var withStrategy = needsStrategy(missionId, increment, report.failing());` y `executor.correctAsync(missionId, round, report.text(), withStrategy)`.
- `MissionExecutor.correctAsync(missionId, round, request, withStrategy)`: el `Runnable` del `submit`, antes de `reexecuteInternal`, si `withStrategy && correctionStrategist != null`:
  - arma `current` (`CorrectionReport` con la última validación), `previous` (los reportes de las 2 rondas anteriores: rehacerlos desde las `VALIDATION` de esas rondas, o guardar `failures` en `CorrectionRound` — elegir lo más simple y decirlo en el reporte), `stack` desde los componentes del último plan (`.NET 8` si `DOTNET_APP`, `Flutter 3.41` si `FLUTTER_WEB_APP`);
  - `propose(...)`; si hay estrategia: `request = "ESTRATEGIA NUEVA (error persistente): " + s.strategy() + "\nFuentes: " + String.join(", ", s.sources()) + "\n\n" + request`; `recoveryMemory.addStrategy`, `recoveryMemory.updateCorrectionRequest(missionId, round, request)`, evento `EMPRESA_CORRECTION_STRATEGY_PROPOSED` (`agentId "engineering"`, `data {round, fingerprint, sources}`).
- Retomar (`resumeRound` CORRECTION) usa el pedido guardado tal cual (ya trae la estrategia): no llama al estratega.

- [ ] **Step 4: GREEN + suite.**
- [ ] **Step 5: Commit** — `"Durables (rev): la ronda de corrección lleva la estrategia nueva"`.

---

### Task 5: Manos humanas: "verifica MISSION-X"

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (método `verifyHead`)
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java` (`verifyAsync`)
- Modify: `app/src/main/java/com/aicompany/core/recovery/MissionRecoveryService.java` (`verify(missionId)`) y `RecoveryMemoryService` (marca de reinicio del contador)
- Modify: `app/src/main/java/com/aicompany/core/recovery/CorrectionRound.java` (`automaticSinceFounder` respeta la marca)
- Modify: `MissionController` (`POST /{missionId}/verify`), `ChatIntentRouter` ("verifica MISSION-X", gobernanza), `app/frontend/src/api/client.ts` + `MissionDetailPage.tsx` (botón "Verificar (manos humanas)")
- Test: `DevelopmentTeamStrategyTest`, `MissionExecutorTeamTest`, `MissionRecoveryServiceTest`, `MissionControllerRequirementsTest`, `ChatIntentRouterTest`

**Interfaces:**
- Produces: `DevelopmentTeamStrategy.verifyHead(TeamMissionContext context, String validationTaskId) : TeamExecutionResult` (sin modelo); `MissionExecutor.verifyAsync(String missionId) : CompletableFuture<Void>`; `MissionRecoveryService.verify(String missionId)`; `RecoveryMemoryService.resetCounter(String missionId, int increment)` y la marca la respeta `CorrectionRound.automaticSinceFounder` (rondas posteriores a la última marca o ronda del fundador).

- [ ] **Step 1: Tests que fallan**

```java
// DevelopmentTeamStrategyTest — verifyHead nunca llama al modelo y verifica HEAD
@Test void verifyHeadRunsChecksSandboxAndCoverageOnHeadWithoutAnyModel() {
    /* headIfAny = "h".repeat(40); staticValidator/sandbox stubs; verifyHead(ctx, "M-1-HUMAN-VERIFY-1")
       → verifyNoInteractions(runtime) (ni generate ni repair), ceoService nunca llamado, sandbox sobre "h"*40,
       memory.recordStaticValidation("M-1-HUMAN-VERIFY-1", ...) */
}
// Review Focus 5: lo no commiteado no se borra ni se verifica, y se avisa
@Test void verifyHeadDoesNotTouchUncommittedFilesAndSaysSo() { /* verify(workspace, never()).discardUncommitted(...); el resumen de la tarea menciona "sin commit" si `git status --porcelain` no está vacío (agregar workspace.hasUncommitted(missionId) con su test real-git) */ }

// MissionRecoveryServiceTest — Review Focus 4 y estados
@Test void verifyIsRejectedForALiveOrRunningMission() { /* isLive → IllegalStateException; estado PLANNING → IllegalStateException */ }
@Test void verifyResetsTheCounterAndRuns() { /* BLOCKED → resetCounter("M-1", inc) y executor.verifyAsync("M-1") */ }
@Test void theCounterRestartsAfterAHumanVerification() { /* 20 rondas auto + marca → brake(...).isEmpty() */ }

// ChatIntentRouterTest
@Test void verifyIsAGovernanceCommand() { /* "verifica MISSION-9" → recovery.verify("MISSION-9"), respuesta "Verificando MISSION-9…", ceo sin llamar */ }

// MissionControllerRequirementsTest
@Test void verifyIsAnEndpoint() { /* controller.verify("M-9") → recoveryService.verify("M-9") */ }
```

- [ ] **Step 2: RED.**
- [ ] **Step 3: Implementar**
- `DevelopmentTeamStrategy.verifyHead`: con el plan del contexto, `committed` = un solo `CommittedWork(validationTaskId, "human", headSha, filesAtCommit(head))`; reutiliza `staticValidator.validate`, el sandbox por componentes (`verifyComponents`), la cobertura (`ScenarioCoverage` + `TestSuiteGrowthGate`) y `StaticValidationStatus.compute(checks, null, sandbox, coverageMode)` **sin** autofix, reparación, diagnóstico 360 ni revisión de Vera; graba `recordSandboxResult`, `recordStaticValidation`, `recordVerifiedCommit` si `VERIFIED`; devuelve el resultado como la ejecución normal. Extraer helpers privados compartidos si hace falta, sin cambiar el flujo normal.
- `MissionExecutor.verifyAsync`: `advanceMission(EVALUATING, …, "Verificación humana")`; en `submit`: crea la tarea `VALIDATION` `<misión>-HUMAN-VERIFY-<n>` (n = cantidad de tareas con ese prefijo + 1) para el agente `qa`, toma el último plan guardado y el equipo, llama a `verifyHead`, y termina en `AWAITING_INVESTOR` con el estado de la validación (mismo cierre que una ronda: `advanceMission(AWAITING_INVESTOR, …)`, que ya confirma el incremento si `VERIFIED`).
- `MissionRecoveryService.verify`: `synchronized`; misión existe, estado en {`BLOCKED`, `FAILED`, `AWAITING_INVESTOR`, `INTERRUPTED`}, `!executor.isLive`, del Development Group y con repositorio (`headIfAny`); `resetCounter(missionId, increment)`; `executor.verifyAsync(missionId)`.
- Chat: patrón `(?i)^\s*verifica(?:r)?\s+(MISSION_REF)\b`, en gobernanza antes de `PRODUCT_COMMAND` (como "reanuda"), respuesta "Verificando MISSION-X sobre el último commit, sin modelos. Te aviso el resultado." y "No se pudo: …" en error.
- Frontend: `api.verifyMission(id)` (`POST …/verify`), botón visible en `BLOCKED`/`FAILED`/`AWAITING_INVESTOR`/`INTERRUPTED` con la ayuda "Para cuando un desarrollador corrigió el código en ~/forjai-products/<misión> e hizo commit".

- [ ] **Step 4: GREEN + suite + build/lint del frontend.**
- [ ] **Step 5: Commit** — `"Durables (rev): manos humanas — verifica MISSION-X sin modelos"`.

---

### Task 6: Chat de estrategias y documentación

**Files:**
- Modify: `ChatIntentRouter` (`recoveryLine`, `formatEvidenceRounds`), `docs/ARCHITECTURE.md`, `docs/EVENTS.md`, `docs/HISTORY.md`, `docs/FALTANTES.md`, `CLAUDE.md` (solo si cambia algo del mapa general)
- Test: `ChatIntentRouterTest`

- [ ] **Step 1: Tests que fallan**

```java
@Test void theStatusShowsTheLastStrategyOfAMissionInCorrection() { /* strategies() con una de "https://learn.microsoft.com/x" → "nueva estrategia: … (fuente: learn.microsoft.com)" */ }
@Test void theRoundProgressShowsItsStrategy() { /* ronda de corrección cuyo request empieza con "ESTRATEGIA NUEVA" → "¿cómo va?" muestra "estrategia: …" */ }
```

- [ ] **Step 2–4:** implementar, GREEN, suite completa y build/lint.
- [ ] **Step 5: Docs:** ARCHITECTURE (subsección "Misiones durables": 20 rondas, US$ 10, estrategia con búsqueda web, manos humanas, BLOCKED = FAILED, falla antes del código), EVENTS (`EMPRESA_CORRECTION_STRATEGY_PROPOSED`), HISTORY (entrada "revisión 2026-10-06", verificación en vivo pendiente), FALTANTES (la corrección del bloque 7 queda hecha salvo el log de auditoría por error).
- [ ] **Step 6: Commit** — `"Durables (rev): el chat muestra las estrategias; docs"`.
