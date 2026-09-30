# Búsqueda diaria de prospectos — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Con "Buscar clientes" encendido, Forjai busca cada día prospectos reales y contactables para sus productos `READY_TO_SELL`, con estrategias rotadas por Java y estrategias nuevas de Marketing que aprueba el fundador.

**Architecture:** Paquete nuevo `com.aicompany.core.prospecting`: funciones puras (`StrategySelector`, `ProspectValidator`), memoria Neo4j (`ProspectingMemoryService`) y el orquestador de la corrida (`ProspectingService`). El modelo solo investiga (`CeoService.searchProspects` reutiliza el flujo de dos turnos con `search_web_evidence`) y propone (`proposeProspectingStrategy`); todo lo demás lo decide y valida Java. Se expone por `ProspectingController`, `AutonomyService` (interruptor), el chat y una pantalla nueva.

**Tech Stack:** Java 21, Spring Boot 4.1.1 (`@Scheduled`), Neo4j driver plano, JUnit 5 + Mockito; React + Vite + TS, `@tanstack/react-query`.

**Spec:** `docs/superpowers/specs/2026-09-30-busqueda-de-prospectos-design.md`

## Global Constraints

- Solo productos `CatalogStatus.READY_TO_SELL`.
- Nunca `format` y `tools` en la misma llamada al modelo.
- Prospecto válido: nombre en su propia página, email **literalmente** en `contactSourceUrl` o `contactFormUrl` que responde, dominio no repetido para el producto. El modelo nunca decide validez.
- Prospectos = `Customer {status:'LEAD'}` con `contactEmail`, `contactEmailSource`, `contactFormUrl`, `fitReason`, `strategy`, `foundAt`, `domain`, `url` + `(:Product)-[:HAS_PROSPECT]->(:Customer)` + `Evidence` WEB `verified=false`.
- Policies nuevas: `PROSPECTING_ENABLED` (0/1, default 0, admite 0), `MAX_PROSPECTS_PER_DAY` (default 10, > 0).
- Estrategias nuevas: `PENDING_APPROVAL` hasta decisión del fundador (🔴); con una pendiente no se pide otra; nombres únicos (incluidas rechazadas y el catálogo base).
- Contactar prospectos está fuera de alcance (🔴, subproyecto 6).
- Eventos `EMPRESA_PROSPECTING_RUN_COMPLETED|RUN_FAILED|STRATEGY_PROPOSED|STRATEGY_APPROVED|STRATEGY_REJECTED`.
- Errores: `IllegalArgumentException` → 500 con mensaje (convención); una corrida nunca propaga excepciones fuera de `ProspectingService`.
- Jackson 3 (`tools.jackson.*`). `api/types.ts` refleja a mano los records.
- Enmienda al spec (Task 1): "Cuándo" se implementa como chequeo horario (`fixedDelay` 1 h, `initialDelay` 3 min) que corre si hoy no hubo corrida y ya son ≥ 08:00 UTC (cubre cron y arranque en un solo método), y se agrega `POST /api/company/prospecting/runs` ("correr ahora", acción del fundador, ignora interruptor y "una por día") para operar y verificar en vivo.

## Review Focus

- Un email que aparece en la página con otra capitalización (`Info@Acme.com` vs `info@acme.com`) debe validar (test en Task 3: `emailMatchIsCaseInsensitive`).
- Dos prospectos del mismo dominio en una misma corrida: se guarda uno solo (test en Task 3: `sameDomainTwiceInOneBatchKeepsTheFirst`).
- `www.acme.com` y `acme.com` son el mismo dominio (test en Task 3: `wwwIsIgnoredForDuplicates`).
- Producto con `languages` vacío o `markets` con un país: la búsqueda usa `en` y ese país; `WORLDWIDE` → sin país (test en Task 5: `searchScopeComesFromTheSheet`).
- "aprueba la estrategia X" con MISSION-id ausente no debe caer en decisiones de misión ni en comandos de producto (test en Task 8: `strategyCommandsGoToStrategies`).

---

### Task 1: Policies `PROSPECTING_ENABLED` y `MAX_PROSPECTS_PER_DAY` (+ enmienda del spec)

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/PolicyKey.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CompanyPolicyService.java` (`defaults` `:55-56`, `validateValue` `:163-177`)
- Modify: `docs/superpowers/specs/2026-09-30-busqueda-de-prospectos-design.md` (sección 1 "Cuándo" y sección 3 "API")
- Test: `app/src/test/java/com/aicompany/core/service/CompanyPolicyDefaultsTest.java`

**Interfaces:**
- Produces: `PolicyKey.PROSPECTING_ENABLED`, `PolicyKey.MAX_PROSPECTS_PER_DAY`.

- [ ] **Step 1: Tests que fallan** (agregar a `CompanyPolicyDefaultsTest`)

```java
    // Spec búsqueda de prospectos (2026-09-30): apagada por defecto, 10 prospectos por día.
    @Test
    void prospectingIsOffByDefaultWithTenPerDay() {
        var defaults = CompanyPolicyService.defaults(new AppProperties("Forjai", 50, 60));

        assertEquals(0.0, defaults.get(PolicyKey.PROSPECTING_ENABLED));
        assertEquals(10.0, defaults.get(PolicyKey.MAX_PROSPECTS_PER_DAY));
    }

    @Test
    void theProspectingSwitchAcceptsZeroAndOneOnly() {
        assertDoesNotThrow(() -> CompanyPolicyService.validateValue(PolicyKey.PROSPECTING_ENABLED, 0));
        assertDoesNotThrow(() -> CompanyPolicyService.validateValue(PolicyKey.PROSPECTING_ENABLED, 1));
        assertThrows(IllegalArgumentException.class, () -> CompanyPolicyService.validateValue(PolicyKey.PROSPECTING_ENABLED, 3));
        assertThrows(IllegalArgumentException.class, () -> CompanyPolicyService.validateValue(PolicyKey.MAX_PROSPECTS_PER_DAY, 0));
    }
```

- [ ] **Step 2: Ver que fallan**

Run: `cd app && mvn -q test -Dtest=CompanyPolicyDefaultsTest`
Expected: error de compilación (`PROSPECTING_ENABLED` no existe).

- [ ] **Step 3: Implementar**

`PolicyKey`: agregar `PROSPECTING_ENABLED,` y `MAX_PROSPECTS_PER_DAY` al final (con coma después de `MAX_AUTONOMOUS_PRODUCTS`).

`CompanyPolicyService.defaults`, después de `MAX_AUTONOMOUS_PRODUCTS`:

```java
        map.put(PolicyKey.PROSPECTING_ENABLED, 0.0);
        map.put(PolicyKey.MAX_PROSPECTS_PER_DAY, 10.0);
```

`validateValue`: reemplazar `if (key == PolicyKey.ORCHESTRATOR_ENABLED) {` por
`if (key == PolicyKey.ORCHESTRATOR_ENABLED || key == PolicyKey.PROSPECTING_ENABLED) {` y el mensaje por
`key + " solo admite 0 (apagado) o 1 (encendido)"`.

Spec, sección 1, reemplazar el bullet "**Cuándo**…" por:

```markdown
- **Cuándo**: un chequeo cada hora (`@Scheduled(fixedDelay = 1 h, initialDelay = 3 min)`) corre la búsqueda si hoy
  (UTC) no hubo corrida y ya son las 08:00 UTC o más — cubre la hora fija y el arranque. Corre solo si
  `PROSPECTING_ENABLED = 1` y hay productos `READY_TO_SELL`. Un producto por corrida, rotando (el que lleva más tiempo sin
  corrida). Nunca dos corridas simultáneas (`synchronized`). El fundador puede forzar una corrida
  (`POST /api/company/prospecting/runs`), aunque el interruptor esté apagado o ya haya corrido hoy.
```

Spec, sección 3 "API": agregar `POST /runs` (correr ahora) a la lista de endpoints.

- [ ] **Step 4: Ver que pasan**

Run: `cd app && mvn -q test -Dtest=CompanyPolicyDefaultsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/PolicyKey.java app/src/main/java/com/aicompany/core/service/CompanyPolicyService.java app/src/test/java/com/aicompany/core/service/CompanyPolicyDefaultsTest.java docs/superpowers/specs/2026-09-30-busqueda-de-prospectos-design.md
git commit -m "Policies de la búsqueda de prospectos (apagada por defecto, 10 por día)"
```

---

### Task 2: Modelos y `StrategySelector`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/prospecting/StrategyOption.java`
- Create: `app/src/main/java/com/aicompany/core/prospecting/BaseStrategy.java`
- Create: `app/src/main/java/com/aicompany/core/prospecting/RunStat.java`
- Create: `app/src/main/java/com/aicompany/core/prospecting/ProspectCandidate.java`
- Create: `app/src/main/java/com/aicompany/core/prospecting/ProspectBatch.java`
- Create: `app/src/main/java/com/aicompany/core/prospecting/StrategyProposal.java`
- Create: `app/src/main/java/com/aicompany/core/prospecting/StrategySelector.java`
- Test: `app/src/test/java/com/aicompany/core/prospecting/StrategySelectorTest.java`

**Interfaces:**
- Produces:
  - `record StrategyOption(String id, String name, String description, String searchHints)`
  - `enum BaseStrategy { DIRECTORIES, COMMUNITIES, COMPETITOR_CUSTOMERS, NICHE_LISTS; StrategyOption option(); static List<StrategyOption> options(); }` (ids `BASE-<NAME>`)
  - `record RunStat(String productId, String strategyId, int valid, Instant startedAt)`
  - `record ProspectCandidate(String name, String url, String contactEmail, String contactFormUrl, String contactSourceUrl, String fitReason)`
  - `record ProspectBatch(List<ProspectCandidate> prospects)`
  - `record StrategyProposal(String name, String description, String searchHints)`
  - `StrategySelector.choose(List<StrategyOption> options, String productId, List<RunStat> history)` → `StrategyOption`

- [ ] **Step 1: Test que falla**

```java
package com.aicompany.core.prospecting;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Spec búsqueda de prospectos §1: Java elige la estrategia del día. */
class StrategySelectorTest {

    private static final StrategyOption A = new StrategyOption("A", "a", "d", "h");
    private static final StrategyOption B = new StrategyOption("B", "b", "d", "h");
    private static final StrategyOption C = new StrategyOption("C", "c", "d", "h");

    private static RunStat run(String product, String strategy, int valid, String at) {
        return new RunStat(product, strategy, valid, Instant.parse(at));
    }

    @Test
    void unusedStrategiesGoFirstInCatalogOrder() {
        var history = List.of(run("P1", "A", 5, "2026-09-01T08:00:00Z"));

        assertEquals("B", StrategySelector.choose(List.of(A, B, C), "P1", history).id());
    }

    @Test
    void otherProductsHistoryDoesNotCount() {
        var history = List.of(run("P2", "A", 5, "2026-09-01T08:00:00Z"));

        assertEquals("A", StrategySelector.choose(List.of(A, B), "P1", history).id());
    }

    @Test
    void whenAllWereUsedTheBestYieldWinsButNotYesterdays() {
        var history = List.of(
                run("P1", "A", 1, "2026-09-01T08:00:00Z"),
                run("P1", "B", 6, "2026-09-02T08:00:00Z"),
                run("P1", "C", 3, "2026-09-03T08:00:00Z"),
                run("P1", "B", 8, "2026-09-04T08:00:00Z"));

        // B tiene el mejor rendimiento pero fue la de ayer: gana C (3) sobre A (1).
        assertEquals("C", StrategySelector.choose(List.of(A, B, C), "P1", history).id());
    }

    @Test
    void aTieGoesToTheOneUsedLongestAgo() {
        var history = List.of(
                run("P1", "A", 2, "2026-09-01T08:00:00Z"),
                run("P1", "B", 2, "2026-09-02T08:00:00Z"),
                run("P1", "C", 9, "2026-09-03T08:00:00Z"));

        assertEquals("A", StrategySelector.choose(List.of(A, B, C), "P1", history).id());
    }

    @Test
    void aSingleOptionIsRepeated() {
        var history = List.of(run("P1", "A", 0, "2026-09-01T08:00:00Z"));

        assertEquals("A", StrategySelector.choose(List.of(A), "P1", history).id());
    }

    @Test
    void theBaseCatalogHasFourStrategiesWithStableIds() {
        var ids = BaseStrategy.options().stream().map(StrategyOption::id).toList();

        assertEquals(List.of("BASE-DIRECTORIES", "BASE-COMMUNITIES", "BASE-COMPETITOR_CUSTOMERS", "BASE-NICHE_LISTS"), ids);
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd app && mvn -q test -Dtest=StrategySelectorTest`
Expected: error de compilación (paquete `prospecting` vacío).

- [ ] **Step 3: Implementar**

`StrategyOption.java`:

```java
package com.aicompany.core.prospecting;

/** Una estrategia de búsqueda de prospectos: del catálogo base o aprobada por el fundador. */
public record StrategyOption(String id, String name, String description, String searchHints) {
}
```

`BaseStrategy.java`:

```java
package com.aicompany.core.prospecting;

import java.util.Arrays;
import java.util.List;

/** Spec búsqueda de prospectos §1: catálogo base fijo (el orden es el de prueba de las no usadas). */
public enum BaseStrategy {
    DIRECTORIES("Directorios del rubro",
            "Directorios, asociaciones y cámaras de empresas del rubro del cliente objetivo.",
            "directorio de empresas, asociación, miembros, listado de proveedores"),
    COMMUNITIES("Comunidades con el problema",
            "Foros, comunidades y grupos donde el cliente objetivo pide ayuda o sufre el problema que resuelve el producto.",
            "foro, comunidad, grupo, pregunta, recomendación, alguien sabe"),
    COMPETITOR_CUSTOMERS("Clientes de competidores",
            "Empresas que aparecen como clientes de competidores: casos de éxito, testimonios, reseñas.",
            "caso de éxito, testimonio, cliente de, reseña, review"),
    NICHE_LISTS("Listas del nicho",
            "Listas públicas del nicho: rankings, 'top N', listados de empresas o creadores.",
            "top, ranking, lista de, mejores, best");

    private final String label;
    private final String description;
    private final String hints;

    BaseStrategy(String label, String description, String hints) {
        this.label = label;
        this.description = description;
        this.hints = hints;
    }

    public StrategyOption option() {
        return new StrategyOption("BASE-" + name(), label, description, hints);
    }

    public static List<StrategyOption> options() {
        return Arrays.stream(values()).map(BaseStrategy::option).toList();
    }
}
```

`RunStat.java`:

```java
package com.aicompany.core.prospecting;

import java.time.Instant;

/** Resultado de una corrida completada: base del rendimiento por estrategia. */
public record RunStat(String productId, String strategyId, int valid, Instant startedAt) {
}
```

`ProspectCandidate.java`:

```java
package com.aicompany.core.prospecting;

/** Lo que devuelve Sofía por prospecto; Java lo valida antes de guardarlo. */
public record ProspectCandidate(String name, String url, String contactEmail, String contactFormUrl,
                                String contactSourceUrl, String fitReason) {
}
```

`ProspectBatch.java`:

```java
package com.aicompany.core.prospecting;

import java.util.List;

public record ProspectBatch(List<ProspectCandidate> prospects) {

    public ProspectBatch {
        prospects = prospects == null ? List.of() : List.copyOf(prospects);
    }
}
```

`StrategyProposal.java`:

```java
package com.aicompany.core.prospecting;

/** Propuesta semanal del equipo Marketing & Growth (la aprueba el fundador). */
public record StrategyProposal(String name, String description, String searchHints) {
}
```

`StrategySelector.java`:

```java
package com.aicompany.core.prospecting;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Spec búsqueda de prospectos §1 (función pura): primero las estrategias que el producto nunca usó, en orden de catálogo;
 * después la de mejor rendimiento (válidos por corrida) sin repetir la de la corrida anterior; a igualdad, la usada hace
 * más tiempo.
 */
public final class StrategySelector {

    private StrategySelector() {
    }

    public static StrategyOption choose(List<StrategyOption> options, String productId, List<RunStat> history) {
        if (options.isEmpty()) {
            throw new IllegalStateException("No hay estrategias de búsqueda disponibles.");
        }
        var own = history.stream().filter(r -> productId.equals(r.productId())).toList();
        for (var option : options) {
            if (own.stream().noneMatch(r -> option.id().equals(r.strategyId()))) {
                return option;
            }
        }
        var last = own.stream().max(Comparator.comparing(RunStat::startedAt)).map(RunStat::strategyId).orElse(null);
        var candidates = options.size() > 1
                ? options.stream().filter(o -> !o.id().equals(last)).toList()
                : options;
        return candidates.stream()
                .max(Comparator.<StrategyOption>comparingDouble(o -> yield(own, o.id()))
                        .thenComparing(o -> lastUse(own, o.id()), Comparator.reverseOrder()))
                .orElseThrow();
    }

    private static double yield(List<RunStat> own, String strategyId) {
        return own.stream().filter(r -> strategyId.equals(r.strategyId())).mapToInt(RunStat::valid).average().orElse(0);
    }

    private static Instant lastUse(List<RunStat> own, String strategyId) {
        return own.stream().filter(r -> strategyId.equals(r.strategyId())).map(RunStat::startedAt)
                .max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
    }
}
```

- [ ] **Step 4: Ver que pasan**

Run: `cd app && mvn -q test -Dtest=StrategySelectorTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/prospecting app/src/test/java/com/aicompany/core/prospecting
git commit -m "Prospectos: catálogo base de estrategias y StrategySelector"
```

---

### Task 3: `ProspectValidator`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/prospecting/ProspectValidator.java`
- Test: `app/src/test/java/com/aicompany/core/prospecting/ProspectValidatorTest.java`

**Interfaces:**
- Consumes: `ProspectCandidate` (Task 2).
- Produces:
  - `new ProspectValidator(Function<String, String> fetch)`
  - `ProspectValidator.Result(List<ProspectCandidate> valid, List<String> rejections)`
  - `Result validate(List<ProspectCandidate> candidates, Set<String> knownDomains)`
  - `static String domain(String url)` (host en minúsculas sin `www.`, o `null`)

- [ ] **Step 1: Test que falla**

```java
package com.aicompany.core.prospecting;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Spec búsqueda de prospectos §1: validez decidida en Java, nunca por el modelo. */
class ProspectValidatorTest {

    private final Map<String, String> pages = new HashMap<>();
    private final ProspectValidator validator = new ProspectValidator(url -> {
        var page = pages.get(url);
        if (page == null) {
            throw new IllegalStateException("La URL respondió con estado 404: " + url);
        }
        return page;
    });

    private static ProspectCandidate withEmail(String name, String url, String email, String source) {
        return new ProspectCandidate(name, url, email, null, source, "Publican contenido largo cada semana");
    }

    @Test
    void aProspectWithItsNameOnItsPageAndTheEmailOnTheSourceIsValid() {
        pages.put("https://acme.com", "Bienvenidos a Acmé Studio, agencia de contenido");
        pages.put("https://acme.com/contacto", "Escríbenos a hola@acme.com");

        var result = validator.validate(List.of(withEmail("Acme Studio", "https://acme.com", "hola@acme.com",
                "https://acme.com/contacto")), Set.of());

        assertEquals(1, result.valid().size());
        assertTrue(result.rejections().isEmpty());
    }

    @Test
    void emailMatchIsCaseInsensitive() {
        pages.put("https://acme.com", "Acme Studio");
        pages.put("https://acme.com/c", "Contacto: Info@Acme.com");

        var result = validator.validate(List.of(withEmail("Acme Studio", "https://acme.com", "info@acme.com",
                "https://acme.com/c")), Set.of());

        assertEquals(1, result.valid().size());
    }

    @Test
    void anEmailThatIsNotOnTheSourcePageIsRejected() {
        pages.put("https://acme.com", "Acme Studio");
        pages.put("https://acme.com/c", "Formulario de contacto sin email");

        var result = validator.validate(List.of(withEmail("Acme Studio", "https://acme.com", "ventas@acme.com",
                "https://acme.com/c")), Set.of());

        assertTrue(result.valid().isEmpty());
        assertTrue(result.rejections().get(0).contains("Acme Studio"), result.rejections().toString());
        assertTrue(result.rejections().get(0).contains("contacto"), result.rejections().toString());
    }

    @Test
    void aNameThatIsNotOnItsPageIsRejected() {
        pages.put("https://blog.com/tendencias", "Tendencias del sector 2026");
        pages.put("https://blog.com/c", "hola@blog.com");

        var result = validator.validate(List.of(withEmail("Studio PixelCraft", "https://blog.com/tendencias",
                "hola@blog.com", "https://blog.com/c")), Set.of());

        assertTrue(result.valid().isEmpty());
        assertTrue(result.rejections().get(0).contains("nombre"), result.rejections().toString());
    }

    @Test
    void aReachableContactFormIsEnoughWithoutEmail() {
        pages.put("https://acme.com", "Acme Studio");
        pages.put("https://acme.com/form", "<form>");

        var result = validator.validate(List.of(new ProspectCandidate("Acme Studio", "https://acme.com", null,
                "https://acme.com/form", null, "Necesitan repurposing")), Set.of());

        assertEquals(1, result.valid().size());
    }

    @Test
    void withoutAnyVerifiableContactItIsRejected() {
        pages.put("https://acme.com", "Acme Studio");

        var result = validator.validate(List.of(new ProspectCandidate("Acme Studio", "https://acme.com", null, null,
                null, "Necesitan repurposing")), Set.of());

        assertTrue(result.valid().isEmpty());
    }

    @Test
    void anUnreachablePageIsRejected() {
        var result = validator.validate(List.of(withEmail("Acme Studio", "https://acme.com", "a@acme.com",
                "https://acme.com/c")), Set.of());

        assertTrue(result.rejections().get(0).contains("no responde"), result.rejections().toString());
    }

    @Test
    void aNonHttpUrlIsRejected() {
        var result = validator.validate(List.of(withEmail("Acme", "ftp://acme.com", "a@acme.com", "ftp://acme.com")),
                Set.of());

        assertTrue(result.valid().isEmpty());
    }

    @Test
    void aKnownDomainIsRejectedAsRepeated() {
        pages.put("https://www.acme.com", "Acme Studio");
        pages.put("https://acme.com/c", "a@acme.com");

        var result = validator.validate(List.of(withEmail("Acme Studio", "https://www.acme.com", "a@acme.com",
                "https://acme.com/c")), Set.of("acme.com"));

        assertTrue(result.rejections().get(0).contains("repetido"), result.rejections().toString());
    }

    @Test
    void sameDomainTwiceInOneBatchKeepsTheFirst() {
        pages.put("https://acme.com", "Acme Studio");
        pages.put("https://acme.com/equipo", "Acme Studio equipo");
        pages.put("https://acme.com/c", "a@acme.com");

        var result = validator.validate(List.of(
                withEmail("Acme Studio", "https://acme.com", "a@acme.com", "https://acme.com/c"),
                withEmail("Acme Studio", "https://acme.com/equipo", "a@acme.com", "https://acme.com/c")), Set.of());

        assertEquals(1, result.valid().size());
        assertEquals(1, result.rejections().size());
    }

    @Test
    void wwwIsIgnoredForDuplicates() {
        assertEquals("acme.com", ProspectValidator.domain("https://www.ACME.com/x"));
        assertNull(ProspectValidator.domain("no es url"));
    }

    @Test
    void missingFitReasonIsRejected() {
        pages.put("https://acme.com", "Acme Studio");

        var result = validator.validate(List.of(new ProspectCandidate("Acme Studio", "https://acme.com", null,
                "https://acme.com", null, " ")), Set.of());

        assertTrue(result.valid().isEmpty());
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd app && mvn -q test -Dtest=ProspectValidatorTest`
Expected: error de compilación (`ProspectValidator` no existe).

- [ ] **Step 3: Implementar**

```java
package com.aicompany.core.prospecting;

import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Spec búsqueda de prospectos §1: un prospecto vale solo si su nombre aparece en su propia página y tiene un contacto
 * público verificable (email literalmente en la página que lo cita, o formulario que responde), sin repetir dominio.
 * Lección de la rama del 18-sep: los agentes devolvían segmentos de mercado con nombre y una fuente genérica.
 */
public final class ProspectValidator {

    public record Result(List<ProspectCandidate> valid, List<String> rejections) {
    }

    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");

    private final Function<String, String> fetch;

    public ProspectValidator(Function<String, String> fetch) {
        this.fetch = fetch;
    }

    public Result validate(List<ProspectCandidate> candidates, Set<String> knownDomains) {
        var valid = new ArrayList<ProspectCandidate>();
        var rejections = new ArrayList<String>();
        var seen = new HashSet<>(knownDomains);
        for (var c : candidates) {
            var problem = problem(c, seen);
            if (problem == null) {
                valid.add(c);
                seen.add(domain(c.url()));
            } else {
                rejections.add((blank(c.name()) ? "(sin nombre)" : c.name()) + ": " + problem);
            }
        }
        return new Result(valid, rejections);
    }

    private String problem(ProspectCandidate c, Set<String> seen) {
        if (blank(c.name()) || blank(c.url()) || blank(c.fitReason())) {
            return "faltan nombre, URL o por qué le sirve el producto";
        }
        var domain = domain(c.url());
        if (!http(c.url()) || domain == null) {
            return "la URL no es http(s)";
        }
        if (seen.contains(domain)) {
            return "repetido (" + domain + " ya fue prospectado)";
        }
        var page = page(c.url());
        if (page == null) {
            return "su página no responde";
        }
        if (!normalize(page).contains(normalize(c.name()))) {
            return "el nombre no aparece en su página";
        }
        if (!blank(c.contactEmail())) {
            var email = c.contactEmail().strip();
            if (!EMAIL.matcher(email).matches() || !http(c.contactSourceUrl())) {
                return "contacto no verificable (email o página de origen inválidos)";
            }
            var source = page(c.contactSourceUrl());
            if (source != null && source.toLowerCase(Locale.ROOT).contains(email.toLowerCase(Locale.ROOT))) {
                return null;
            }
            return "contacto no verificable (el email no aparece en " + c.contactSourceUrl() + ")";
        }
        if (http(c.contactFormUrl()) && page(c.contactFormUrl()) != null) {
            return null;
        }
        return "sin contacto verificable (ni email ni formulario)";
    }

    private String page(String url) {
        try {
            var text = fetch.apply(url);
            return text == null || text.isBlank() ? null : text;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static String domain(String url) {
        try {
            var host = URI.create(url.strip()).getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean http(String url) {
        return url != null && (url.strip().startsWith("http://") || url.strip().startsWith("https://"));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("\\s+", " ").strip();
    }
}
```

Nota: el test `anUnreachablePageIsRejected` espera "no responde"; el mensaje es "su página no responde" (contiene el texto).

- [ ] **Step 4: Ver que pasan**

Run: `cd app && mvn -q test -Dtest=ProspectValidatorTest`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/prospecting/ProspectValidator.java app/src/test/java/com/aicompany/core/prospecting/ProspectValidatorTest.java
git commit -m "Prospectos: ProspectValidator (nombre en su página, contacto verificable, sin repetir dominio)"
```

---

### Task 4: Búsqueda con alcance y llamadas al modelo

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/evidence/EvidenceAcquisitionService.java` (overload de `searchEvidence`, `:54`)
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java` (`executeTool` `:805` gana alcance opcional; métodos nuevos `searchProspects`, `proposeProspectingStrategy` y sus schemas junto a `SHEET_SCHEMA` `:1232`)
- Test: `app/src/test/java/com/aicompany/core/evidence/EvidenceAcquisitionServiceTest.java`
- Test: `app/src/test/java/com/aicompany/core/service/CeoServiceProspectingTest.java`

**Interfaces:**
- Consumes: `ProspectBatch`, `StrategyOption`, `StrategyProposal` (Task 2).
- Produces:
  - `EvidenceAcquisitionService.searchEvidence(String query, String country, String language)` (country/language `null` → sin `gl`/`hl`)
  - `CeoService.searchProspects(String productText, StrategyOption strategy, String country, String language, String model)` → `ProspectBatch`
  - `CeoService.proposeProspectingStrategy(String performanceText, String model)` → `StrategyProposal`

- [ ] **Step 1: Tests que fallan**

En `EvidenceAcquisitionServiceTest`:

```java
    // Spec búsqueda de prospectos §1: WORLDWIDE → sin país (el sesgo a CO no aplica a la prospección).
    @Test
    void aScopedSearchUsesTheGivenCountryAndLanguageEvenWhenNull() {
        var searchPort = mock(WebSearchPort.class);
        when(searchPort.search("agencias de contenido", null, "en", 10)).thenReturn(List.of(
                new WebSearchResult("Acme", "https://acme.com", "agencia")));
        var service = new EvidenceAcquisitionService(searchPort, mock(WebPageFetcher.class), "CO", "es");

        var candidates = service.searchEvidence("agencias de contenido", null, "en");

        assertEquals(1, candidates.size());
        verify(searchPort).search("agencias de contenido", null, "en", 10);
    }
```

`CeoServiceProspectingTest.java`:

```java
package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.EvidenceAcquisitionService;
import com.aicompany.core.prospecting.StrategyOption;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec búsqueda de prospectos: Sofía investiga con la herramienta; Kira propone estrategias. */
class CeoServiceProspectingTest {

    private final OpenAiCompatibleClient remote = mock(OpenAiCompatibleClient.class);
    private final EvidenceAcquisitionService evidence = mock(EvidenceAcquisitionService.class);
    private final CeoService ceoService = new CeoService(mock(RestClient.class), JsonMapper.builder().build(),
            evidence, mock(CompanyEventPublisher.class), new SimpleMeterRegistry(), Map.of("nvidia-discovery", remote));
    private final StrategyOption strategy = new StrategyOption("BASE-DIRECTORIES", "Directorios", "desc", "directorio");

    @SuppressWarnings("unchecked")
    @Test
    void theSearchUsesTheToolWithTheSheetScopeAndNeverCombinesFormatAndTools() {
        when(remote.complete(anyString(), anyList(), isNotNull(), eq(false), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("", List.of(Map.of("function",
                        Map.of("name", "search_web_evidence", "arguments", Map.of("query", "content agencies"))))));
        when(evidence.searchEvidence("content agencies", null, "en")).thenReturn(List.of());
        when(remote.complete(anyString(), anyList(), isNull(), eq(true), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("{\"prospects\":[{\"name\":\"Acme\","
                        + "\"url\":\"https://acme.com\",\"contactEmail\":\"a@acme.com\",\"contactFormUrl\":\"\","
                        + "\"contactSourceUrl\":\"https://acme.com/c\",\"fitReason\":\"publican mucho\"}]}", List.of()));

        var batch = ceoService.searchProspects("Producto: Pack", strategy, null, "en", "nvidia-discovery:m");

        assertEquals(1, batch.prospects().size());
        assertEquals("Acme", batch.prospects().get(0).name());
        verify(evidence).searchEvidence("content agencies", null, "en");
        verify(remote, never()).complete(anyString(), anyList(), isNotNull(), eq(true), anyInt());
    }

    @Test
    void withoutAToolCallItStillReturnsTheFinalBatch() {
        when(remote.complete(anyString(), anyList(), isNotNull(), eq(false), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("no necesito buscar", List.of()));
        when(remote.complete(anyString(), anyList(), isNull(), eq(true), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("{\"prospects\":[]}", List.of()));

        var batch = ceoService.searchProspects("Producto: Pack", strategy, "US", "en", "nvidia-discovery:m");

        assertTrue(batch.prospects().isEmpty());
        verify(evidence, never()).searchEvidence(anyString(), any(), any());
    }

    @Test
    void kiraProposesAStrategy() {
        when(remote.complete(anyString(), anyList(), isNull(), eq(true), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("{\"name\":\"Podcasts del nicho\","
                        + "\"description\":\"Invitados de podcasts\",\"searchHints\":\"podcast, episodio\"}", List.of()));

        var proposal = ceoService.proposeProspectingStrategy("Directorios: 2 válidos por corrida", "nvidia-discovery:m");

        assertEquals("Podcasts del nicho", proposal.name());
    }
}
```

- [ ] **Step 2: Ver que fallan**

Run: `cd app && mvn -q test -Dtest=EvidenceAcquisitionServiceTest,CeoServiceProspectingTest`
Expected: error de compilación (`searchEvidence(String,String,String)` y `searchProspects` no existen).

- [ ] **Step 3: Implementar**

`EvidenceAcquisitionService`: el método existente delega; agregar el overload.

```java
    public List<EvidenceCandidate> searchEvidence(String query) {
        return searchEvidence(query, defaultCountry, defaultLanguage);
    }

    /** Spec búsqueda de prospectos §1: alcance explícito (country/language null = sin restringir). */
    public List<EvidenceCandidate> searchEvidence(String query, String country, String language) {

        var results = searchPort.search(query, country, language, 10);

        return results.stream()
                .map(result -> new EvidenceCandidate(
                        query,
                        result.url(),
                        result.title(),
                        safe(result.description()),
                        "WEB"
                ))
                .toList();
    }
```

(borrar el cuerpo viejo del método de un argumento; el `.map` se mueve al overload).

`CeoService`:

1. Record privado junto a `ToolCall` (`:1059`): `private record SearchScope(String country, String language) {}`.
2. `executeTool`: agregar el parámetro final `SearchScope scope` y reemplazar
   `evidenceAcquisitionService.searchEvidence(toolCall.query())` por
   `scope == null ? evidenceAcquisitionService.searchEvidence(toolCall.query()) : evidenceAcquisitionService.searchEvidence(toolCall.query(), scope.country(), scope.language())`.
   En la llamada existente de `executeAgentTask` pasar `null` como último argumento.
3. Schemas junto a `SHEET_SCHEMA`:

```java
    private static final Map<String, Object> PROSPECTS_SCHEMA = Map.of("type", "object",
            "properties", Map.of("prospects", Map.of("type", "array", "items", Map.of("type", "object",
                    "properties", Map.of(
                            "name", Map.of("type", "string"),
                            "url", Map.of("type", "string"),
                            "contactEmail", Map.of("type", "string"),
                            "contactFormUrl", Map.of("type", "string"),
                            "contactSourceUrl", Map.of("type", "string"),
                            "fitReason", Map.of("type", "string")),
                    "required", List.of("name", "url", "contactEmail", "contactFormUrl", "contactSourceUrl", "fitReason")))),
            "required", List.of("prospects"));

    private static final Map<String, Object> STRATEGY_SCHEMA = Map.of("type", "object",
            "properties", Map.of(
                    "name", Map.of("type", "string"),
                    "description", Map.of("type", "string"),
                    "searchHints", Map.of("type", "string")),
            "required", List.of("name", "description", "searchHints"));
```

4. Métodos (junto a `proposeProductSheet`):

```java
    /**
     * Spec búsqueda de prospectos §1: Sofía busca prospectos reales para un producto con la estrategia del día. Mismo
     * patrón de dos turnos que las tareas (nunca format + tools); el alcance de la búsqueda sale de la ficha. Java valida
     * cada prospecto después (ProspectValidator).
     */
    public com.aicompany.core.prospecting.ProspectBatch searchProspects(
            String productText, com.aicompany.core.prospecting.StrategyOption strategy, String country, String language,
            String model) {

        var task = """
                Busca en la web EMPRESAS O PERSONAS CONCRETAS que podrían comprar este producto de Forjai hoy.
                Estrategia del día: %s — %s (pistas de búsqueda: %s).
                PRODUCTO:
                %s
                """.formatted(strategy.name(), strategy.description(), strategy.searchHints(), productText);

        var toolTurn = callModel("PROSPECTING_TOOL_CALL", "sales", model, List.of(
                Map.of("role", "system", "content", toolDecisionSystemPrompt("sales")),
                Map.of("role", "user", "content", task)), null, AGENT_TOOLS, true);

        var toolCall = !toolTurn.toolCalls().isEmpty()
                ? parseStructuredToolCall(toolTurn.toolCalls().get(0))
                : detectInlineToolCall(toolTurn.content());

        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", teamSystemPrompt("sales", null)));
        messages.add(Map.of("role", "user", "content", task + """

                REGLAS:
                - Solo empresas o personas reales y nombradas, con su propia página web (url). Nunca un segmento de
                  mercado ni un artículo genérico.
                - contactEmail: solo si el email figura en una página pública; contactSourceUrl es esa página. Si no hay
                  email, contactFormUrl con la página del formulario de contacto. Deja vacío lo que no tengas.
                - fitReason: por qué este producto le sirve, en una frase.
                - No inventes nada: Forjai verifica que el nombre esté en su página y el email en su fuente.
                - Lista vacía si no encontraste ninguno.
                FORMATO: {"prospects": [{"name", "url", "contactEmail", "contactFormUrl", "contactSourceUrl", "fitReason"}]}
                """));

        if (toolCall != null) {
            var execution = executeTool("sales", null, null, toolCall, new SearchScope(country, language));
            messages.add(Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("function",
                    Map.of("name", toolCall.name(), "arguments", Map.of("query", toolCall.query()))))));
            messages.add(Map.of("role", "tool", "content", execution.json()));
        }

        var response = callModel("PROSPECTING_SEARCH", "sales", model, messages, PROSPECTS_SCHEMA, null, false).content();
        try {
            return jsonMapper.readValue(normalizeJsonResponse(response), com.aicompany.core.prospecting.ProspectBatch.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Sofía no devolvió prospectos en JSON válido: " + ex.getMessage(), ex);
        }
    }

    /** Spec búsqueda de prospectos §2: Kira propone una estrategia nueva (la aprueba el fundador). */
    public com.aicompany.core.prospecting.StrategyProposal proposeProspectingStrategy(String performanceText, String model) {
        var prompt = """
                Propón UNA estrategia nueva para encontrar prospectos (clientes posibles) de los productos de Forjai,
                distinta de las que ya existen. Mira el rendimiento: válidos por corrida de cada estrategia.
                RENDIMIENTO Y ESTRATEGIAS EXISTENTES:
                %s
                FORMATO: {"name": "<nombre corto>", "description": "<dónde y cómo buscar>", "searchHints": "<palabras clave>"}
                """.formatted(performanceText);
        return callStructured("PROSPECTING_STRATEGY", "growth-content", prompt, null, model, STRATEGY_SCHEMA,
                com.aicompany.core.prospecting.StrategyProposal.class);
    }
```

Si `callModel`, `parseStructuredToolCall`, `detectInlineToolCall`, `teamSystemPrompt` o `toolDecisionSystemPrompt` tienen otra firma, adaptarse a la real y anotar el `Ruling:` (el comportamiento exigido es el de los tests).

- [ ] **Step 4: Ver que pasan**

Run: `cd app && mvn -q test -Dtest=EvidenceAcquisitionServiceTest,CeoServiceProspectingTest,CeoServiceToolFormatGuardTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/evidence/EvidenceAcquisitionService.java app/src/main/java/com/aicompany/core/service/CeoService.java app/src/test/java/com/aicompany/core/evidence/EvidenceAcquisitionServiceTest.java app/src/test/java/com/aicompany/core/service/CeoServiceProspectingTest.java
git commit -m "CeoService: búsqueda de prospectos con alcance de la ficha y propuesta de estrategias"
```

---

### Task 5: `ProspectingMemoryService` y la corrida diaria (`ProspectingService`)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/prospecting/ProspectingRun.java`
- Create: `app/src/main/java/com/aicompany/core/prospecting/StoredStrategy.java`
- Create: `app/src/main/java/com/aicompany/core/prospecting/Prospect.java`
- Create: `app/src/main/java/com/aicompany/core/prospecting/ProspectingMemoryService.java`
- Create: `app/src/main/java/com/aicompany/core/prospecting/ProspectingService.java`
- Test: `app/src/test/java/com/aicompany/core/prospecting/ProspectingServiceTest.java`

**Interfaces:**
- Consumes: Tasks 1-4; `ProductService.list()` → `List<ProductView>` (`view.product()` → `CatalogProduct`); `CompanyMemoryService.agentModel(String, String)`; `CompanyPolicyService.activeValue(PolicyKey)`; `WebPageFetcher.fetch(String)`; `CompanyEventPublisher.publish(type, missionId, taskId, agentId, data)`; `AlertMailService.send(subject, body, critical)`.
- Produces:
  - `record ProspectingRun(String id, String productId, String strategyId, String status, int found, int valid, List<String> rejections, String error, Instant startedAt, Instant endedAt)`
  - `record StoredStrategy(String id, String name, String description, String searchHints, String status, String proposedBy, Instant proposedAt, Instant decidedAt)`
  - `record Prospect(String id, String productId, String productName, String name, String url, String contactEmail, String contactEmailSource, String contactFormUrl, String fitReason, String strategyId, Instant foundAt)`
  - `ProspectingMemoryService`: `saveRun(ProspectingRun)`, `List<ProspectingRun> runs(int limit)`, `List<RunStat> stats()`, `boolean ranOn(LocalDate)`, `int prospectsOn(LocalDate)`, `Set<String> knownDomains(String productId)`, `void saveProspect(String productId, ProspectCandidate c, String domain, String strategyId, String runId)`, `List<Prospect> prospects()`, `List<StoredStrategy> strategies()`, `Optional<StoredStrategy> strategy(String id)`, `void saveStrategy(StoredStrategy)`, `void decideStrategy(String id, String status, Instant at)`, `int pendingStrategies()`
  - `ProspectingService`: `Optional<ProspectingRun> runIfDue()` (scheduled), `ProspectingRun runNow()`, `List<StrategyOption> options()`, `boolean enabled()`

- [ ] **Step 1: Test que falla**

```java
package com.aicompany.core.prospecting;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.WebPageFetcher;
import com.aicompany.core.model.CatalogProduct;
import com.aicompany.core.model.CatalogStatus;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.ProductView;
import com.aicompany.core.service.AlertMailService;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.CompanyPolicyService;
import com.aicompany.core.service.ProductService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec búsqueda de prospectos §1: la corrida diaria. */
class ProspectingServiceTest {

    private final ProspectingMemoryService memory = mock(ProspectingMemoryService.class);
    private final ProductService products = mock(ProductService.class);
    private final CeoService ceo = mock(CeoService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final CompanyPolicyService policies = mock(CompanyPolicyService.class);
    private final WebPageFetcher fetcher = mock(WebPageFetcher.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final AlertMailService mail = mock(AlertMailService.class);
    private final ProspectingService service = new ProspectingService(memory, products, ceo, companyMemory, policies,
            fetcher, events, mail, "qwen3:8b");
    private final List<ProspectingRun> saved = new ArrayList<>();

    {
        when(policies.activeValue(PolicyKey.PROSPECTING_ENABLED)).thenReturn(1.0);
        when(policies.activeValue(PolicyKey.MAX_PROSPECTS_PER_DAY)).thenReturn(10.0);
        when(companyMemory.agentModel(anyString(), anyString())).thenReturn("nvidia-discovery:m");
        when(memory.stats()).thenReturn(List.of());
        when(memory.strategies()).thenReturn(List.of());
        when(memory.knownDomains(anyString())).thenReturn(Set.of());
        doAnswer(inv -> saved.add(inv.getArgument(0))).when(memory).saveRun(any());
        when(fetcher.fetch("https://acme.com")).thenReturn("Acme Studio");
        when(fetcher.fetch("https://acme.com/c")).thenReturn("hola@acme.com");
    }

    private static CatalogProduct product(String id, CatalogStatus status, String target, List<String> markets,
                                          List<String> languages) {
        return new CatalogProduct(id, "Pack " + id, "desc", "SERVICE", target, 39, false, 5, "48 h", markets, languages,
                status, null, "product", Instant.now(), Instant.now(), List.of(), List.of());
    }

    private void catalog(CatalogProduct... ps) {
        when(products.list()).thenReturn(java.util.Arrays.stream(ps).map(p -> new ProductView(p, List.of(), List.of())).toList());
    }

    private static ProspectCandidate acme() {
        return new ProspectCandidate("Acme Studio", "https://acme.com", "hola@acme.com", "", "https://acme.com/c",
                "Publican mucho contenido");
    }

    private ProspectingRun lastRun() {
        return saved.get(saved.size() - 1);
    }

    @Test
    void itDoesNothingWhenOff() {
        when(policies.activeValue(PolicyKey.PROSPECTING_ENABLED)).thenReturn(0.0);
        catalog(product("P1", CatalogStatus.READY_TO_SELL, "Creadores", List.of("WORLDWIDE"), List.of("en")));

        assertTrue(service.runIfDue().isEmpty());
        verifyNoInteractions(ceo);
    }

    @Test
    void itDoesNothingWithoutProductsReadyToSell() {
        catalog(product("P1", CatalogStatus.IN_CONSTRUCTION, "Creadores", List.of("WORLDWIDE"), List.of("en")));

        assertThrows(IllegalArgumentException.class, service::runNow);
        verifyNoInteractions(ceo);
    }

    @Test
    void itRunsOnlyOncePerDay() {
        when(memory.ranOn(any())).thenReturn(true);
        catalog(product("P1", CatalogStatus.READY_TO_SELL, "Creadores", List.of("WORLDWIDE"), List.of("en")));

        assertTrue(service.runIfDue().isEmpty());
        verifyNoInteractions(ceo);
    }

    @Test
    void aValidProspectIsSavedAndTheRunCompletes() {
        catalog(product("P1", CatalogStatus.READY_TO_SELL, "Creadores", List.of("WORLDWIDE"), List.of("en")));
        when(ceo.searchProspects(anyString(), any(), any(), any(), anyString()))
                .thenReturn(new ProspectBatch(List.of(acme())));

        var run = service.runNow();

        assertEquals("COMPLETED", run.status());
        assertEquals(1, run.found());
        assertEquals(1, run.valid());
        assertEquals("BASE-DIRECTORIES", run.strategyId());
        verify(memory).saveProspect(eq("P1"), eq(acme()), eq("acme.com"), eq("BASE-DIRECTORIES"), eq(run.id()));
        verify(events).publish(eq("EMPRESA_PROSPECTING_RUN_COMPLETED"), isNull(), isNull(), eq("sales"), anyMap());
        verify(mail).send(contains("prospectos"), contains("Acme Studio"), eq(false));
    }

    @Test
    void searchScopeComesFromTheSheet() {
        catalog(product("P1", CatalogStatus.READY_TO_SELL, "Creadores", List.of("US"), List.of()));
        when(ceo.searchProspects(anyString(), any(), any(), any(), anyString())).thenReturn(new ProspectBatch(List.of()));

        service.runNow();

        verify(ceo).searchProspects(contains("Creadores"), any(), eq("US"), eq("en"), eq("nvidia-discovery:m"));

        catalog(product("P2", CatalogStatus.READY_TO_SELL, "Creadores", List.of("WORLDWIDE"), List.of("es")));
        service.runNow();

        verify(ceo).searchProspects(anyString(), any(), isNull(), eq("es"), anyString());
    }

    @Test
    void anIncompleteSheetFailsWithoutCallingTheModel() {
        catalog(product("P1", CatalogStatus.READY_TO_SELL, " ", List.of("WORLDWIDE"), List.of("en")));

        var run = service.runNow();

        assertEquals("FAILED", run.status());
        assertTrue(run.error().contains("cliente objetivo"), run.error());
        verifyNoInteractions(ceo);
    }

    @Test
    void theDailyLimitCapsWhatIsSaved() {
        when(policies.activeValue(PolicyKey.MAX_PROSPECTS_PER_DAY)).thenReturn(1.0);
        when(memory.prospectsOn(any())).thenReturn(1);
        catalog(product("P1", CatalogStatus.READY_TO_SELL, "Creadores", List.of("WORLDWIDE"), List.of("en")));
        when(ceo.searchProspects(anyString(), any(), any(), any(), anyString()))
                .thenReturn(new ProspectBatch(List.of(acme())));

        var run = service.runNow();

        assertEquals(0, run.valid());
        assertTrue(run.rejections().stream().anyMatch(r -> r.contains("límite")), run.rejections().toString());
        verify(memory, never()).saveProspect(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void aModelFailureLeavesTheRunFailed() {
        catalog(product("P1", CatalogStatus.READY_TO_SELL, "Creadores", List.of("WORLDWIDE"), List.of("en")));
        when(ceo.searchProspects(anyString(), any(), any(), any(), anyString()))
                .thenThrow(new IllegalStateException("Modelo remoto no responde"));

        var run = service.runNow();

        assertEquals("FAILED", run.status());
        assertTrue(run.error().contains("no responde"));
        verify(events).publish(eq("EMPRESA_PROSPECTING_RUN_FAILED"), isNull(), isNull(), eq("sales"), anyMap());
    }

    @Test
    void productsRotateByLongestWithoutARun() {
        catalog(product("P1", CatalogStatus.READY_TO_SELL, "Creadores", List.of("WORLDWIDE"), List.of("en")),
                product("P2", CatalogStatus.READY_TO_SELL, "Agencias", List.of("WORLDWIDE"), List.of("en")));
        when(memory.stats()).thenReturn(List.of(new RunStat("P1", "BASE-DIRECTORIES", 2, Instant.parse("2026-09-29T08:00:00Z"))));
        when(ceo.searchProspects(anyString(), any(), any(), any(), anyString())).thenReturn(new ProspectBatch(List.of()));

        var run = service.runNow();

        assertEquals("P2", run.productId());
    }

    @Test
    void approvedStrategiesJoinTheCatalogButPendingOnesDoNot() {
        when(memory.strategies()).thenReturn(List.of(
                new StoredStrategy("S1", "Podcasts", "d", "h", "APPROVED", "growth-content", Instant.now(), Instant.now()),
                new StoredStrategy("S2", "Eventos", "d", "h", "PENDING_APPROVAL", "growth-content", Instant.now(), null)));

        var ids = service.options().stream().map(StrategyOption::id).toList();

        assertTrue(ids.contains("S1"));
        assertFalse(ids.contains("S2"));
        assertEquals(5, ids.size());
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd app && mvn -q test -Dtest=ProspectingServiceTest`
Expected: error de compilación (`ProspectingService` no existe).

- [ ] **Step 3: Implementar**

`ProspectingRun.java`:

```java
package com.aicompany.core.prospecting;

import java.time.Instant;
import java.util.List;

/** Una corrida de búsqueda: status COMPLETED | FAILED. */
public record ProspectingRun(String id, String productId, String strategyId, String status, int found, int valid,
                             List<String> rejections, String error, Instant startedAt, Instant endedAt) {
}
```

`StoredStrategy.java`:

```java
package com.aicompany.core.prospecting;

import java.time.Instant;

/** Estrategia propuesta por Marketing: status PENDING_APPROVAL | APPROVED | REJECTED. */
public record StoredStrategy(String id, String name, String description, String searchHints, String status,
                             String proposedBy, Instant proposedAt, Instant decidedAt) {
}
```

`Prospect.java`:

```java
package com.aicompany.core.prospecting;

import java.time.Instant;

/** Prospecto guardado (Customer {status:'LEAD'} unido a su producto por HAS_PROSPECT). */
public record Prospect(String id, String productId, String productName, String name, String url, String contactEmail,
                       String contactEmailSource, String contactFormUrl, String fitReason, String strategyId,
                       Instant foundAt) {
}
```

`ProspectingMemoryService.java`:

```java
package com.aicompany.core.prospecting;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Spec búsqueda de prospectos: (:ProspectingRun), (:ProspectingStrategy) y los prospectos como
 * (:Product)-[:HAS_PROSPECT]->(:Customer {status:'LEAD'})-[:HAS_EVIDENCE]->(:Evidence {sourceType:'WEB', verified:false}).
 */
@Service
public class ProspectingMemoryService {

    private final Driver driver;

    public ProspectingMemoryService(Driver driver) {
        this.driver = driver;
    }

    public void saveRun(ProspectingRun run) {
        var props = new HashMap<String, Object>();
        props.put("productId", run.productId());
        props.put("strategyId", run.strategyId());
        props.put("status", run.status());
        props.put("found", run.found());
        props.put("valid", run.valid());
        props.put("rejections", run.rejections());
        props.put("error", run.error());
        props.put("startedAt", run.startedAt().toString());
        props.put("endedAt", run.endedAt() == null ? null : run.endedAt().toString());
        write("MERGE (r:ProspectingRun {id:$id}) SET r += $props", Map.of("id", run.id(), "props", props));
    }

    public List<ProspectingRun> runs(int limit) {
        return read("MATCH (r:ProspectingRun) RETURN r ORDER BY r.startedAt DESC LIMIT $limit", Map.of("limit", limit),
                ProspectingMemoryService::run);
    }

    public List<RunStat> stats() {
        return read("MATCH (r:ProspectingRun {status:'COMPLETED'}) RETURN r ORDER BY r.startedAt", Map.of(),
                rec -> {
                    var r = rec.get("r");
                    return new RunStat(r.get("productId").asString(), r.get("strategyId").asString(),
                            r.get("valid").asInt(0), Instant.parse(r.get("startedAt").asString()));
                });
    }

    public boolean ranOn(LocalDate day) {
        return !read("MATCH (r:ProspectingRun) WHERE r.startedAt STARTS WITH $day RETURN r LIMIT 1",
                Map.of("day", day.toString()), rec -> rec).isEmpty();
    }

    public int prospectsOn(LocalDate day) {
        return read("MATCH (:Product)-[:HAS_PROSPECT]->(c:Customer) WHERE c.foundAt STARTS WITH $day RETURN count(c) AS n",
                Map.of("day", day.toString()), rec -> rec.get("n").asInt()).get(0);
    }

    public Set<String> knownDomains(String productId) {
        return new HashSet<>(read("MATCH (:Product {id:$id})-[:HAS_PROSPECT]->(c:Customer) WHERE c.domain IS NOT NULL "
                + "RETURN c.domain AS d", Map.of("id", productId), rec -> rec.get("d").asString()));
    }

    public void saveProspect(String productId, ProspectCandidate c, String domain, String strategyId, String runId) {
        var id = "PROSPECT-" + UUID.randomUUID();
        var now = Instant.now().toString();
        var props = new HashMap<String, Object>();
        props.put("name", c.name().strip());
        props.put("status", "LEAD");
        props.put("url", c.url().strip());
        props.put("domain", domain);
        props.put("contactEmail", blank(c.contactEmail()) ? null : c.contactEmail().strip());
        props.put("contactEmailSource", blank(c.contactEmail()) ? null : c.contactSourceUrl().strip());
        props.put("contactFormUrl", blank(c.contactFormUrl()) ? null : c.contactFormUrl().strip());
        props.put("fitReason", c.fitReason().strip());
        props.put("strategy", strategyId);
        props.put("prospectingRunId", runId);
        props.put("identifiedByAgent", "sales");
        props.put("foundAt", now);
        props.put("createdAt", now);
        props.put("updatedAt", now);
        write("MATCH (p:Product {id:$productId}) CREATE (c:Customer {id:$id}) SET c += $props "
                        + "MERGE (p)-[:HAS_PROSPECT]->(c) "
                        + "CREATE (e:Evidence {id:$id + '-EVIDENCE'}) SET e.description=$fit, e.source=$url, "
                        + "e.sourceType='WEB', e.verified=false, e.agentId='sales', e.updatedAt=$now "
                        + "MERGE (c)-[:HAS_EVIDENCE]->(e)",
                Map.of("productId", productId, "id", id, "props", props, "fit", c.fitReason().strip(),
                        "url", c.url().strip(), "now", now));
    }

    public List<Prospect> prospects() {
        return read("MATCH (p:Product)-[:HAS_PROSPECT]->(c:Customer) RETURN p.id AS pid, p.name AS pname, c "
                + "ORDER BY c.foundAt DESC", Map.of(), rec -> {
                    var c = rec.get("c");
                    return new Prospect(c.get("id").asString(), rec.get("pid").asString(), rec.get("pname").asString(null),
                            c.get("name").asString(), c.get("url").asString(null), c.get("contactEmail").asString(null),
                            c.get("contactEmailSource").asString(null), c.get("contactFormUrl").asString(null),
                            c.get("fitReason").asString(null), c.get("strategy").asString(null),
                            Instant.parse(c.get("foundAt").asString()));
                });
    }

    public List<StoredStrategy> strategies() {
        return read("MATCH (s:ProspectingStrategy) RETURN s ORDER BY s.proposedAt", Map.of(), rec -> strategy(rec.get("s")));
    }

    public Optional<StoredStrategy> strategy(String id) {
        return read("MATCH (s:ProspectingStrategy {id:$id}) RETURN s", Map.of("id", id), rec -> strategy(rec.get("s")))
                .stream().findFirst();
    }

    public void saveStrategy(StoredStrategy s) {
        var props = new HashMap<String, Object>();
        props.put("name", s.name());
        props.put("description", s.description());
        props.put("searchHints", s.searchHints());
        props.put("status", s.status());
        props.put("proposedBy", s.proposedBy());
        props.put("proposedAt", s.proposedAt().toString());
        write("MERGE (s:ProspectingStrategy {id:$id}) SET s += $props", Map.of("id", s.id(), "props", props));
    }

    public void decideStrategy(String id, String status, Instant at) {
        write("MATCH (s:ProspectingStrategy {id:$id}) SET s.status=$status, s.decidedAt=$at, s.decidedBy='human'",
                Map.of("id", id, "status", status, "at", at.toString()));
    }

    public int pendingStrategies() {
        return read("MATCH (s:ProspectingStrategy {status:'PENDING_APPROVAL'}) RETURN count(s) AS n", Map.of(),
                rec -> rec.get("n").asInt()).get(0);
    }

    private static StoredStrategy strategy(Value s) {
        return new StoredStrategy(s.get("id").asString(), s.get("name").asString(), s.get("description").asString(null),
                s.get("searchHints").asString(null), s.get("status").asString(), s.get("proposedBy").asString(null),
                Instant.parse(s.get("proposedAt").asString()),
                s.get("decidedAt").isNull() ? null : Instant.parse(s.get("decidedAt").asString()));
    }

    private static ProspectingRun run(Record rec) {
        var r = rec.get("r");
        return new ProspectingRun(r.get("id").asString(), r.get("productId").asString(null),
                r.get("strategyId").asString(null), r.get("status").asString(), r.get("found").asInt(0),
                r.get("valid").asInt(0), r.get("rejections").asList(Value::asString, List.of()),
                r.get("error").asString(null), Instant.parse(r.get("startedAt").asString()),
                r.get("endedAt").isNull() ? null : Instant.parse(r.get("endedAt").asString()));
    }

    private void write(String cypher, Map<String, Object> params) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, params);
                return null;
            });
        }
    }

    private <T> List<T> read(String cypher, Map<String, Object> params, java.util.function.Function<Record, T> map) {
        try (var session = driver.session()) {
            return session.run(cypher, params).list(map::apply);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
```

`ProspectingService.java`:

```java
package com.aicompany.core.prospecting;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.WebPageFetcher;
import com.aicompany.core.model.CatalogProduct;
import com.aicompany.core.model.CatalogStatus;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.ProductView;
import com.aicompany.core.service.AlertMailService;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.CompanyPolicyService;
import com.aicompany.core.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Spec búsqueda de prospectos §1: una corrida por día (≥ 08:00 UTC) para un producto READY_TO_SELL, con la estrategia que
 * elige Java; Sofía investiga y Java valida y guarda. Nunca propaga excepciones: la corrida queda FAILED con el motivo.
 */
@Service
public class ProspectingService {

    private static final Logger log = LoggerFactory.getLogger(ProspectingService.class);
    static final int RUN_HOUR_UTC = 8;

    private final ProspectingMemoryService memory;
    private final ProductService products;
    private final CeoService ceo;
    private final CompanyMemoryService companyMemory;
    private final CompanyPolicyService policies;
    private final WebPageFetcher fetcher;
    private final CompanyEventPublisher events;
    private final AlertMailService mail;
    private final String defaultModel;

    public ProspectingService(ProspectingMemoryService memory, ProductService products, CeoService ceo,
                              CompanyMemoryService companyMemory, CompanyPolicyService policies, WebPageFetcher fetcher,
                              CompanyEventPublisher events, AlertMailService mail,
                              @Value("${ollama.agent-model}") String defaultModel) {
        this.memory = memory;
        this.products = products;
        this.ceo = ceo;
        this.companyMemory = companyMemory;
        this.policies = policies;
        this.fetcher = fetcher;
        this.events = events;
        this.mail = mail;
        this.defaultModel = defaultModel;
    }

    public boolean enabled() {
        return policies.activeValue(PolicyKey.PROSPECTING_ENABLED) >= 1;
    }

    /** Catálogo base + estrategias aprobadas por el fundador. */
    public List<StrategyOption> options() {
        return Stream.concat(BaseStrategy.options().stream(), memory.strategies().stream()
                        .filter(s -> "APPROVED".equals(s.status()))
                        .map(s -> new StrategyOption(s.id(), s.name(), s.description(), s.searchHints())))
                .toList();
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 180_000)
    public synchronized Optional<ProspectingRun> runIfDue() {
        var now = Instant.now().atZone(ZoneOffset.UTC);
        if (!enabled() || now.getHour() < RUN_HOUR_UTC || memory.ranOn(now.toLocalDate()) || ready().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(runNow());
    }

    /** Acción del fundador ("correr ahora"): ignora el interruptor y la regla de una por día. */
    public synchronized ProspectingRun runNow() {
        var ready = ready();
        if (ready.isEmpty()) {
            throw new IllegalArgumentException("No hay productos listos para vender: no hay a quién buscarle clientes.");
        }
        var stats = memory.stats();
        var product = ready.stream().min(Comparator.comparing((CatalogProduct p) -> lastRun(stats, p.id()))
                .thenComparing(CatalogProduct::id)).orElseThrow();
        var strategy = StrategySelector.choose(options(), product.id(), stats);
        var id = "PROSPECTING-" + UUID.randomUUID();
        var started = Instant.now();
        try {
            if (product.targetCustomer() == null || product.targetCustomer().isBlank()) {
                return fail(id, product, strategy, started, "La ficha de " + product.name() + " no tiene cliente objetivo.");
            }
            var runsForProduct = (int) stats.stream().filter(s -> product.id().equals(s.productId())).count();
            var batch = ceo.searchProspects(sheet(product), strategy, country(product), language(product, runsForProduct),
                    companyMemory.agentModel("sales", defaultModel));
            var validation = new ProspectValidator(fetcher::fetch).validate(batch.prospects(), memory.knownDomains(product.id()));
            var rejections = new ArrayList<>(validation.rejections());
            var room = (int) policies.activeValue(PolicyKey.MAX_PROSPECTS_PER_DAY)
                    - memory.prospectsOn(LocalDate.now(ZoneOffset.UTC));
            var saved = new ArrayList<ProspectCandidate>();
            for (var c : validation.valid()) {
                if (saved.size() >= room) {
                    rejections.add(c.name() + ": límite diario de prospectos alcanzado");
                    continue;
                }
                memory.saveProspect(product.id(), c, ProspectValidator.domain(c.url()), strategy.id(), id);
                saved.add(c);
            }
            var run = new ProspectingRun(id, product.id(), strategy.id(), "COMPLETED", batch.prospects().size(),
                    saved.size(), rejections, null, started, Instant.now());
            memory.saveRun(run);
            events.publish("EMPRESA_PROSPECTING_RUN_COMPLETED", null, null, "sales", Map.of("runId", id,
                    "productId", product.id(), "strategy", strategy.id(), "found", run.found(), "valid", run.valid()));
            if (!saved.isEmpty()) {
                mail.send("Forjai: " + saved.size() + " prospectos nuevos para " + product.name(),
                        "Estrategia: " + strategy.name() + ".\n\n" + saved.stream()
                                .map(c -> "- " + c.name() + " (" + c.url() + "): " + c.fitReason())
                                .collect(Collectors.joining("\n"))
                                + "\n\nContactarlos sigue siendo decisión tuya. Los ves en la pantalla Prospectos.", false);
            }
            log.info("PROSPECTING run={} product={} strategy={} found={} valid={}", id, product.id(), strategy.id(),
                    run.found(), run.valid());
            return run;
        } catch (Exception ex) {
            log.error("PROSPECTING run {} failed", id, ex);
            return fail(id, product, strategy, started, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private ProspectingRun fail(String id, CatalogProduct product, StrategyOption strategy, Instant started, String error) {
        var run = new ProspectingRun(id, product.id(), strategy.id(), "FAILED", 0, 0, List.of(), error, started, Instant.now());
        memory.saveRun(run);
        events.publish("EMPRESA_PROSPECTING_RUN_FAILED", null, null, "sales",
                Map.of("runId", id, "productId", product.id(), "error", error));
        return run;
    }

    private List<CatalogProduct> ready() {
        return products.list().stream().map(ProductView::product)
                .filter(p -> p.status() == CatalogStatus.READY_TO_SELL).toList();
    }

    private static Instant lastRun(List<RunStat> stats, String productId) {
        return stats.stream().filter(s -> productId.equals(s.productId())).map(RunStat::startedAt)
                .max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
    }

    static String country(CatalogProduct p) {
        var markets = p.markets() == null ? List.<String>of() : p.markets();
        return markets.isEmpty() || markets.contains("WORLDWIDE") ? null : markets.get(0);
    }

    static String language(CatalogProduct p, int runsForProduct) {
        var languages = p.languages() == null ? List.<String>of() : p.languages();
        return languages.isEmpty() ? "en" : languages.get(runsForProduct % languages.size());
    }

    private static String sheet(CatalogProduct p) {
        return "Nombre: " + p.name() + "\nDescripción: " + p.description() + "\nTipo: " + p.kind()
                + "\nCliente objetivo: " + p.targetCustomer()
                + "\nPrecio: " + (p.priceOnRequest() ? "a cotizar" : "US$" + p.priceUsd())
                + "\nMercados: " + String.join(", ", p.markets() == null ? List.of() : p.markets())
                + "\nIdiomas: " + String.join(", ", p.languages() == null ? List.of() : p.languages())
                + (p.delivery() == null || p.delivery().isBlank() ? "" : "\nEntrega: " + p.delivery());
    }
}
```

Nota: en `aValidProspectIsSavedAndTheRunCompletes` el test verifica `saveProspect(... eq(run.id()))`; `runNow` devuelve el mismo `id` que usa al guardar.

- [ ] **Step 4: Ver que pasan**

Run: `cd app && mvn -q test -Dtest=ProspectingServiceTest`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/prospecting app/src/test/java/com/aicompany/core/prospecting/ProspectingServiceTest.java
git commit -m "Prospectos: corrida diaria con estrategia de Java, validación y límite diario"
```

---

### Task 6: Estrategias propuestas por Marketing y decisión del fundador

**Files:**
- Create: `app/src/main/java/com/aicompany/core/prospecting/StrategyProposalService.java`
- Test: `app/src/test/java/com/aicompany/core/prospecting/StrategyProposalServiceTest.java`

**Interfaces:**
- Consumes: `ProspectingMemoryService` (Task 5), `ProspectingService.options()` (Task 5), `CeoService.proposeProspectingStrategy` (Task 4), `RunStat`.
- Produces:
  - `record StrategyView(String id, String name, String description, String status, String proposedBy, int runs, double validPerRun)`
  - `StrategyProposalService.proposeWeekly()` (scheduled) → `Optional<StoredStrategy>`
  - `StrategyProposalService.approve(String id)` / `reject(String id)` → `StoredStrategy`
  - `StrategyProposalService.views()` → `List<StrategyView>` (catálogo base + guardadas, con rendimiento)
  - `StrategyProposalService.findByName(String text)` → `List<StoredStrategy>` (pendientes cuyo nombre normalizado es igual o contiene el texto)

- [ ] **Step 1: Test que falla**

```java
package com.aicompany.core.prospecting;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.AlertMailService;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec búsqueda de prospectos §2: Kira propone, el fundador decide (🔴). */
class StrategyProposalServiceTest {

    private final ProspectingMemoryService memory = mock(ProspectingMemoryService.class);
    private final CeoService ceo = mock(CeoService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final AlertMailService mail = mock(AlertMailService.class);
    private final StrategyProposalService service = new StrategyProposalService(memory, ceo, companyMemory, events, mail,
            "qwen3:8b");

    {
        when(memory.strategies()).thenReturn(List.of());
        when(memory.stats()).thenReturn(List.of());
        when(companyMemory.agentModel(anyString(), anyString())).thenReturn("nvidia-creative:m");
    }

    private static StoredStrategy stored(String id, String name, String status) {
        return new StoredStrategy(id, name, "d", "h", status, "growth-content", Instant.now(), null);
    }

    @Test
    void aValidProposalIsSavedAsPendingAndAnnounced() {
        when(ceo.proposeProspectingStrategy(anyString(), anyString()))
                .thenReturn(new StrategyProposal("Podcasts del nicho", "Invitados de podcasts", "podcast"));

        var saved = service.proposeWeekly().orElseThrow();

        assertEquals("PENDING_APPROVAL", saved.status());
        verify(memory).saveStrategy(argThat(s -> "Podcasts del nicho".equals(s.name())));
        verify(events).publish(eq("EMPRESA_PROSPECTING_STRATEGY_PROPOSED"), isNull(), isNull(), eq("growth-content"), anyMap());
        verify(mail).send(contains("estrategia"), contains("Podcasts del nicho"), eq(false));
    }

    @Test
    void withAPendingProposalNoOtherIsRequested() {
        when(memory.strategies()).thenReturn(List.of(stored("S1", "Eventos", "PENDING_APPROVAL")));

        assertTrue(service.proposeWeekly().isEmpty());
        verifyNoInteractions(ceo);
    }

    @Test
    void aRepeatedNameIsNotSavedEvenIfItWasRejectedOrIsBase() {
        when(memory.strategies()).thenReturn(List.of(stored("S1", "Eventos", "REJECTED")));
        when(ceo.proposeProspectingStrategy(anyString(), anyString()))
                .thenReturn(new StrategyProposal("eventos", "otra vez", "x"))
                .thenReturn(new StrategyProposal("Directorios del rubro", "base", "x"));

        assertTrue(service.proposeWeekly().isEmpty());
        assertTrue(service.proposeWeekly().isEmpty());
        verify(memory, never()).saveStrategy(any());
    }

    @Test
    void aModelFailureDoesNotPropagate() {
        when(ceo.proposeProspectingStrategy(anyString(), anyString())).thenThrow(new IllegalStateException("caído"));

        assertTrue(service.proposeWeekly().isEmpty());
    }

    @Test
    void approvingAPendingStrategy() {
        when(memory.strategy("S1")).thenReturn(Optional.of(stored("S1", "Eventos", "PENDING_APPROVAL")));

        service.approve("S1");

        verify(memory).decideStrategy(eq("S1"), eq("APPROVED"), any());
        verify(events).publish(eq("EMPRESA_PROSPECTING_STRATEGY_APPROVED"), isNull(), isNull(), eq("human"), anyMap());
    }

    @Test
    void decidingANonPendingStrategyIsRejected() {
        when(memory.strategy("S1")).thenReturn(Optional.of(stored("S1", "Eventos", "APPROVED")));

        assertThrows(IllegalArgumentException.class, () -> service.reject("S1"));
        verify(memory, never()).decideStrategy(anyString(), anyString(), any());
    }

    @Test
    void viewsIncludeBaseAndStoredWithPerformance() {
        when(memory.strategies()).thenReturn(List.of(stored("S1", "Eventos", "APPROVED")));
        when(memory.stats()).thenReturn(List.of(
                new RunStat("P1", "BASE-DIRECTORIES", 4, Instant.now()),
                new RunStat("P2", "BASE-DIRECTORIES", 2, Instant.now())));

        var views = service.views();

        assertEquals(5, views.size());
        var directories = views.stream().filter(v -> v.id().equals("BASE-DIRECTORIES")).findFirst().orElseThrow();
        assertEquals(2, directories.runs());
        assertEquals(3.0, directories.validPerRun());
    }

    @Test
    void findByNamePrefersTheExactPendingName() {
        when(memory.strategies()).thenReturn(List.of(stored("S1", "Podcasts", "PENDING_APPROVAL"),
                stored("S2", "Podcasts del nicho", "PENDING_APPROVAL"), stored("S3", "Eventos", "APPROVED")));

        assertEquals(List.of("S1"), service.findByName("podcasts").stream().map(StoredStrategy::id).toList());
        assertEquals(List.of("S2"), service.findByName("del nicho").stream().map(StoredStrategy::id).toList());
        assertTrue(service.findByName("eventos").isEmpty());
    }
}
```

- [ ] **Step 2: Ver que falla**

Run: `cd app && mvn -q test -Dtest=StrategyProposalServiceTest`
Expected: error de compilación.

- [ ] **Step 3: Implementar**

```java
package com.aicompany.core.prospecting;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.service.AlertMailService;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Spec búsqueda de prospectos §2: una propuesta semanal de Kira; entra al catálogo solo si el fundador la aprueba. */
@Service
public class StrategyProposalService {

    public record StrategyView(String id, String name, String description, String status, String proposedBy, int runs,
                               double validPerRun) {
    }

    private static final Logger log = LoggerFactory.getLogger(StrategyProposalService.class);

    private final ProspectingMemoryService memory;
    private final CeoService ceo;
    private final CompanyMemoryService companyMemory;
    private final CompanyEventPublisher events;
    private final AlertMailService mail;
    private final String defaultModel;

    public StrategyProposalService(ProspectingMemoryService memory, CeoService ceo, CompanyMemoryService companyMemory,
                                   CompanyEventPublisher events, AlertMailService mail,
                                   @Value("${ollama.agent-model}") String defaultModel) {
        this.memory = memory;
        this.ceo = ceo;
        this.companyMemory = companyMemory;
        this.events = events;
        this.mail = mail;
        this.defaultModel = defaultModel;
    }

    @Scheduled(cron = "0 0 9 * * MON", zone = "UTC")
    public synchronized Optional<StoredStrategy> proposeWeekly() {
        var stored = memory.strategies();
        if (stored.stream().anyMatch(s -> "PENDING_APPROVAL".equals(s.status()))) {
            return Optional.empty();
        }
        try {
            var proposal = ceo.proposeProspectingStrategy(performanceText(),
                    companyMemory.agentModel("growth-content", defaultModel));
            var problem = problem(proposal, stored);
            if (problem != null) {
                log.warn("PROSPECTING strategy proposal discarded: {}", problem);
                return Optional.empty();
            }
            var strategy = new StoredStrategy("STRATEGY-" + UUID.randomUUID(), proposal.name().strip(),
                    proposal.description().strip(), proposal.searchHints() == null ? "" : proposal.searchHints().strip(),
                    "PENDING_APPROVAL", "growth-content", Instant.now(), null);
            memory.saveStrategy(strategy);
            events.publish("EMPRESA_PROSPECTING_STRATEGY_PROPOSED", null, null, "growth-content",
                    Map.of("strategyId", strategy.id(), "name", strategy.name()));
            mail.send("Forjai: Kira propone una estrategia para buscar clientes",
                    strategy.name() + ": " + strategy.description()
                            + "\n\nApruébala o recházala en la pantalla Prospectos o en el chat "
                            + "(\"aprueba la estrategia " + strategy.name() + "\").", false);
            return Optional.of(strategy);
        } catch (Exception ex) {
            log.error("PROSPECTING strategy proposal failed", ex);
            return Optional.empty();
        }
    }

    public StoredStrategy approve(String id) {
        return decide(id, "APPROVED", "EMPRESA_PROSPECTING_STRATEGY_APPROVED");
    }

    public StoredStrategy reject(String id) {
        return decide(id, "REJECTED", "EMPRESA_PROSPECTING_STRATEGY_REJECTED");
    }

    private StoredStrategy decide(String id, String status, String event) {
        var strategy = memory.strategy(id)
                .orElseThrow(() -> new IllegalArgumentException("No existe la estrategia " + id + "."));
        if (!"PENDING_APPROVAL".equals(strategy.status())) {
            throw new IllegalArgumentException("La estrategia \"" + strategy.name() + "\" ya fue decidida ("
                    + strategy.status() + ").");
        }
        var now = Instant.now();
        memory.decideStrategy(id, status, now);
        events.publish(event, null, null, "human", Map.of("strategyId", id, "name", strategy.name()));
        return new StoredStrategy(strategy.id(), strategy.name(), strategy.description(), strategy.searchHints(), status,
                strategy.proposedBy(), strategy.proposedAt(), now);
    }

    public List<StrategyView> views() {
        var stats = memory.stats();
        var base = BaseStrategy.options().stream()
                .map(o -> view(o.id(), o.name(), o.description(), "BASE", null, stats));
        var stored = memory.strategies().stream()
                .map(s -> view(s.id(), s.name(), s.description(), s.status(), s.proposedBy(), stats));
        return Stream.concat(base, stored).toList();
    }

    /** Pendientes por nombre: el nombre exacto gana; si no, las que lo contienen. */
    public List<StoredStrategy> findByName(String text) {
        var wanted = ProspectValidator.normalize(text);
        var pending = memory.strategies().stream().filter(s -> "PENDING_APPROVAL".equals(s.status())).toList();
        var exact = pending.stream().filter(s -> ProspectValidator.normalize(s.name()).equals(wanted)).toList();
        return exact.isEmpty()
                ? pending.stream().filter(s -> ProspectValidator.normalize(s.name()).contains(wanted)).toList()
                : exact;
    }

    private static StrategyView view(String id, String name, String description, String status, String proposedBy,
                                     List<RunStat> stats) {
        var own = stats.stream().filter(r -> id.equals(r.strategyId())).toList();
        var perRun = own.stream().mapToInt(RunStat::valid).average().orElse(0);
        return new StrategyView(id, name, description, status, proposedBy, own.size(), perRun);
    }

    private String performanceText() {
        return views().stream()
                .map(v -> "- " + v.name() + " [" + v.status() + "]: " + v.runs() + " corridas, "
                        + String.format(java.util.Locale.ROOT, "%.1f", v.validPerRun()) + " válidos por corrida. "
                        + v.description())
                .collect(Collectors.joining("\n"));
    }

    private static String problem(StrategyProposal proposal, List<StoredStrategy> stored) {
        if (proposal == null || proposal.name() == null || proposal.name().isBlank()
                || proposal.description() == null || proposal.description().isBlank()) {
            return "sin nombre o sin descripción";
        }
        var name = ProspectValidator.normalize(proposal.name());
        var taken = Stream.concat(BaseStrategy.options().stream().map(StrategyOption::name),
                stored.stream().map(StoredStrategy::name)).map(ProspectValidator::normalize);
        return taken.anyMatch(name::equals) ? "nombre repetido: " + proposal.name() : null;
    }
}
```

`ProspectValidator.normalize` pasa de package-private a `public static` (este servicio está en el mismo paquete; el chat en Task 8 también lo usa).

- [ ] **Step 4: Ver que pasan**

Run: `cd app && mvn -q test -Dtest=StrategyProposalServiceTest,ProspectValidatorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/prospecting app/src/test/java/com/aicompany/core/prospecting/StrategyProposalServiceTest.java
git commit -m "Prospectos: propuesta semanal de estrategias de Marketing y decisión del fundador"
```

---

### Task 7: `ProspectingController` e interruptor de clientes en `AutonomyService`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/controller/ProspectingController.java`
- Modify: `app/src/main/java/com/aicompany/core/service/AutonomyService.java`
- Modify: `app/src/main/java/com/aicompany/core/controller/SpaController.java` (ruta `/prospectos`)
- Test: `app/src/test/java/com/aicompany/core/controller/ProspectingControllerTest.java`
- Test: `app/src/test/java/com/aicompany/core/service/AutonomyServiceTest.java`

**Interfaces:**
- Consumes: `ProspectingService`, `StrategyProposalService`, `ProspectingMemoryService` (Tasks 5-6).
- Produces:
  - `GET /api/company/prospecting/prospects` → `List<Prospect>`; `GET /runs` → `List<ProspectingRun>` (20); `GET /strategies` → `List<StrategyView>`; `POST /runs` → `ProspectingRun`; `PUT /strategies/{id}/approve|reject` → `StoredStrategy`.
  - `AutonomyService`: constructor gana `ProspectingMemoryService`; `setClients(boolean, String)` → `boolean`; `Waiting(int orchestratorMissions, int pendingDependencies, int pendingStrategies)`; `clients` = `Front(enabled, true, pauseReason)`.

- [ ] **Step 1: Tests que fallan**

`ProspectingControllerTest.java`:

```java
package com.aicompany.core.controller;

import com.aicompany.core.prospecting.ProspectingMemoryService;
import com.aicompany.core.prospecting.ProspectingRun;
import com.aicompany.core.prospecting.ProspectingService;
import com.aicompany.core.prospecting.StrategyProposalService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProspectingControllerTest {

    private final ProspectingService prospecting = mock(ProspectingService.class);
    private final StrategyProposalService strategies = mock(StrategyProposalService.class);
    private final ProspectingMemoryService memory = mock(ProspectingMemoryService.class);
    private final ProspectingController controller = new ProspectingController(prospecting, strategies, memory);

    @Test
    void runNowDelegatesToTheService() {
        var run = new ProspectingRun("R1", "P1", "BASE-DIRECTORIES", "COMPLETED", 0, 0, List.of(), null, Instant.now(), Instant.now());
        when(prospecting.runNow()).thenReturn(run);

        assertSame(run, controller.runNow());
    }

    @Test
    void runsAreTheLastTwenty() {
        controller.runs();

        verify(memory).runs(20);
    }

    @Test
    void approveAndRejectDelegate() {
        controller.approve("S1");
        controller.reject("S2");

        verify(strategies).approve("S1");
        verify(strategies).reject("S2");
    }
}
```

En `AutonomyServiceTest`: el constructor pasa a `new AutonomyService(policies, missionMemory, dependencies, prospectingMemory)` con
`private final com.aicompany.core.prospecting.ProspectingMemoryService prospectingMemory = mock(com.aicompany.core.prospecting.ProspectingMemoryService.class);`.
Reemplazar el test `clientsCannotBeTurnedOnYet` por:

```java
    private void clientsOn(boolean on) {
        when(policies.activeValue(PolicyKey.PROSPECTING_ENABLED)).thenReturn(on ? 1.0 : 0.0);
        when(policies.snapshot(PolicyKey.PROSPECTING_ENABLED)).thenReturn(new PolicySnapshot(
                "PROSPECTING_ENABLED", 1, on ? 1.0 : 0.0, "system", "Valor inicial de seed", Instant.now(), List.of()));
    }

    // Spec búsqueda de prospectos §3: el interruptor de clientes ya existe.
    @Test
    void clientsCanBeTurnedOnFromTheDashboard() {
        productsOn(true, "seed");
        clientsOn(false);

        var view = service.update(new AutonomyService.AutonomyCommand(null, true), "el Dashboard");

        verify(policies).createVersion(PolicyKey.PROSPECTING_ENABLED, 1, "Encendido desde el Dashboard");
        assertTrue(view.clients().available());
    }

    @Test
    void turningClientsOnWhenOnCreatesNoVersion() {
        clientsOn(true);

        assertFalse(service.setClients(true, "el chat"));
        verify(policies, never()).createVersion(eq(PolicyKey.PROSPECTING_ENABLED), anyDouble(), anyString());
    }

    @Test
    void pendingStrategiesWaitForTheFounder() {
        productsOn(true, "seed");
        clientsOn(true);
        when(prospectingMemory.pendingStrategies()).thenReturn(1);

        assertEquals(1, service.view().waiting().pendingStrategies());
    }
```

En `theViewCountsWhatWaitsForTheFounderAndClientsAreNotAvailable`: renombrar a `theViewCountsWhatWaitsForTheFounder`, agregar `clientsOn(false);` al inicio y reemplazar `assertFalse(view.clients().available());` por `assertTrue(view.clients().available());`.

En `AutonomyControllerTest` y `ChatIntentRouterTest` (`autonomyView`): `new AutonomyService.Waiting(0, 0)` → `new AutonomyService.Waiting(0, 0, 0)` y `new AutonomyService.Waiting(3, 1)` → `new AutonomyService.Waiting(3, 1, 0)`.

- [ ] **Step 2: Ver que fallan**

Run: `cd app && mvn -q test -Dtest=ProspectingControllerTest,AutonomyServiceTest`
Expected: error de compilación.

- [ ] **Step 3: Implementar**

`ProspectingController.java`:

```java
package com.aicompany.core.controller;

import com.aicompany.core.prospecting.Prospect;
import com.aicompany.core.prospecting.ProspectingMemoryService;
import com.aicompany.core.prospecting.ProspectingRun;
import com.aicompany.core.prospecting.ProspectingService;
import com.aicompany.core.prospecting.StoredStrategy;
import com.aicompany.core.prospecting.StrategyProposalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Spec búsqueda de prospectos §3: prospectos, corridas y estrategias; aprobar/rechazar es del fundador (🔴). */
@RestController
@RequestMapping("/api/company/prospecting")
public class ProspectingController {

    private final ProspectingService prospecting;
    private final StrategyProposalService strategies;
    private final ProspectingMemoryService memory;

    public ProspectingController(ProspectingService prospecting, StrategyProposalService strategies,
                                 ProspectingMemoryService memory) {
        this.prospecting = prospecting;
        this.strategies = strategies;
        this.memory = memory;
    }

    @GetMapping("/prospects")
    public List<Prospect> prospects() {
        return memory.prospects();
    }

    @GetMapping("/runs")
    public List<ProspectingRun> runs() {
        return memory.runs(20);
    }

    @PostMapping("/runs")
    public ProspectingRun runNow() {
        return prospecting.runNow();
    }

    @GetMapping("/strategies")
    public List<StrategyProposalService.StrategyView> strategies() {
        return strategies.views();
    }

    @PutMapping("/strategies/{id}/approve")
    public StoredStrategy approve(@PathVariable("id") String id) {
        return strategies.approve(id);
    }

    @PutMapping("/strategies/{id}/reject")
    public StoredStrategy reject(@PathVariable("id") String id) {
        return strategies.reject(id);
    }
}
```

`AutonomyService`:
- `Waiting` gana `int pendingStrategies`.
- Campo y constructor: `ProspectingMemoryService prospectingMemory` (último parámetro).
- `view()`:

```java
    public AutonomyView view() {
        var pending = (int) dependencies.list().stream().filter(d -> "PENDING_APPROVAL".equals(d.get("status"))).count();
        return new AutonomyView(front(PolicyKey.ORCHESTRATOR_ENABLED), front(PolicyKey.PROSPECTING_ENABLED),
                new Waiting(missionMemory.countAwaitingLaunchedBy("orchestrator"), pending,
                        prospectingMemory.pendingStrategies()));
    }

    private Front front(PolicyKey key) {
        var on = policies.activeValue(key) >= 1;
        String reason = null;
        if (!on) {
            var policy = policies.snapshot(key);
            reason = policy == null ? null : policy.changeReason();
        }
        return new Front(on, true, reason);
    }
```

- `setProducts` y el nuevo `setClients` comparten:

```java
    public boolean setProducts(boolean on, String origin) {
        return set(PolicyKey.ORCHESTRATOR_ENABLED, on, origin);
    }

    public boolean setClients(boolean on, String origin) {
        return set(PolicyKey.PROSPECTING_ENABLED, on, origin);
    }

    private boolean set(PolicyKey key, boolean on, String origin) {
        if ((policies.activeValue(key) >= 1) == on) {
            return false;
        }
        policies.createVersion(key, on ? 1 : 0, (on ? "Encendido" : "Apagado") + " desde " + origin);
        return true;
    }
```

- `update`: reemplazar el rechazo de `clients` por `if (command.clients() != null) { setClients(command.clients(), origin); }`. Borrar `productsOn()` si queda sin uso. Actualizar el javadoc de la clase ("Buscar clientes" = `PROSPECTING_ENABLED`).

`SpaController`: agregar `"/prospectos",` a la lista explícita de rutas (después de `"/productos",`).

- [ ] **Step 4: Ver que pasan**

Run: `cd app && mvn -q test -Dtest=ProspectingControllerTest,AutonomyServiceTest,AutonomyControllerTest,ChatIntentRouterTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/controller app/src/main/java/com/aicompany/core/service/AutonomyService.java app/src/test/java/com/aicompany/core
git commit -m "API de prospectos y el interruptor Buscar clientes en el modo automático"
```

---

### Task 8: Chat — prospectos, búsqueda, estrategias y "todo en automático" con clientes

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Test: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `ProspectingMemoryService.prospects()/runs(int)`, `StrategyProposalService.views()/findByName(String)/approve/reject`, `AutonomyService.setClients` (Tasks 5-7).
- Produces: nada que usen tareas posteriores.

- [ ] **Step 1: Tests que fallan**

Mocks y constructor (agregar al final):

```java
    private final com.aicompany.core.prospecting.ProspectingMemoryService prospectingMemory =
            mock(com.aicompany.core.prospecting.ProspectingMemoryService.class);
    private final com.aicompany.core.prospecting.StrategyProposalService strategyService =
            mock(com.aicompany.core.prospecting.StrategyProposalService.class);
```

`orchestrator, apiKeys, autonomy` → `orchestrator, apiKeys, autonomy, prospectingMemory, strategyService`.

Tests nuevos (junto a los del modo automático):

```java
    // Spec búsqueda de prospectos §3: el fundador ve y decide desde el chat, en Java.
    private static com.aicompany.core.prospecting.Prospect prospect(String name, String product) {
        return new com.aicompany.core.prospecting.Prospect("C1", "P1", product, name, "https://acme.com", "hola@acme.com",
                "https://acme.com/c", null, "Publican mucho", "BASE-DIRECTORIES", Instant.parse("2026-09-30T08:10:00Z"));
    }

    @Test
    void theProspectsQueryListsThemWithContactAndSource() {
        when(prospectingMemory.prospects()).thenReturn(List.of(prospect("Acme Studio", "Pack")));

        var response = router.route("¿qué prospectos tenemos?");

        assertTrue(response.contains("Acme Studio"), response);
        assertTrue(response.contains("hola@acme.com"), response);
        assertTrue(response.contains("https://acme.com/c"), response);
        assertTrue(response.contains("Pack"), response);
        verifyNoInteractions(ceoService);
    }

    @Test
    void theSearchStatusShowsTheLastRunAndStrategies() {
        when(prospectingMemory.runs(5)).thenReturn(List.of(new com.aicompany.core.prospecting.ProspectingRun("R1", "P1",
                "BASE-DIRECTORIES", "COMPLETED", 6, 2, List.of("X: repetido"), null,
                Instant.parse("2026-09-30T08:00:00Z"), Instant.parse("2026-09-30T08:05:00Z"))));
        when(strategyService.views()).thenReturn(List.of(new com.aicompany.core.prospecting.StrategyProposalService.StrategyView(
                "BASE-DIRECTORIES", "Directorios del rubro", "d", "BASE", null, 1, 2.0)));

        var response = router.route("¿cómo va la búsqueda de clientes?");

        assertTrue(response.contains("2 válidos de 6"), response);
        assertTrue(response.contains("Directorios del rubro"), response);
        verifyNoInteractions(ceoService);
    }

    @Test
    void strategyCommandsGoToStrategies() {
        var pending = new com.aicompany.core.prospecting.StoredStrategy("S1", "Podcasts", "d", "h", "PENDING_APPROVAL",
                "growth-content", Instant.now(), null);
        when(strategyService.findByName("podcasts")).thenReturn(List.of(pending));

        var response = router.route("aprueba la estrategia Podcasts");

        verify(strategyService).approve("S1");
        verify(products, never()).changeStatus(anyString(), any(), anyString(), anyString());
        verify(missionService, never()).recordDecision(anyString(), any());
        assertTrue(response.contains("Podcasts"), response);
    }

    @Test
    void anAmbiguousStrategyNameListsWithoutDeciding() {
        when(strategyService.findByName("pod")).thenReturn(List.of(
                new com.aicompany.core.prospecting.StoredStrategy("S1", "Podcasts", "d", "h", "PENDING_APPROVAL", "g", Instant.now(), null),
                new com.aicompany.core.prospecting.StoredStrategy("S2", "Podcasts B2B", "d", "h", "PENDING_APPROVAL", "g", Instant.now(), null)));

        var response = router.route("rechaza la estrategia pod");

        verify(strategyService, never()).reject(anyString());
        assertTrue(response.contains("Podcasts B2B"), response);
    }

    @Test
    void turnEverythingOnIncludesClients() {
        router.route("pon todo en automático");

        verify(autonomy).setProducts(true, "el chat");
        verify(autonomy).setClients(true, "el chat");
    }
```

Actualizar `turnEverythingOnFromTheChat`: la respuesta ya no dice "próximamente" → reemplazar `assertTrue(response.contains("próximamente"), response);` por `assertTrue(response.contains("Buscar clientes"), response);`. En `theAutonomyQueryShowsEachFrontAndWhatWaitsForTheFounder` reemplazar la línea de "Buscar clientes: próximamente" por `assertTrue(response.contains("Buscar clientes: apagado"), response);`.

- [ ] **Step 2: Ver que fallan**

Run: `cd app && mvn -q test -Dtest=ChatIntentRouterTest`
Expected: error de compilación (constructor).

- [ ] **Step 3: Implementar**

1. Campos + constructor: `ProspectingMemoryService prospectingMemory`, `StrategyProposalService strategyService` (al final).
2. Patrón junto a `AUTONOMY_COMMAND`:

```java
    /** Spec búsqueda de prospectos §3: decisión del fundador sobre una estrategia propuesta (🔴). */
    private static final Pattern STRATEGY_COMMAND = Pattern.compile(
            "^\\s*(?:@\\S+[\\s,]+)*(?:por favor\\s+)?(aprueba|rechaza)\\s+la\\s+estrategia\\s+(.+?)\\s*[.!]?\\s*$");
```

3. `isGovernance`: agregar `|| STRATEGY_COMMAND.matcher(normalize(message)).matches()`.
4. `resolve`, después del bloque de `autonomyCommand`:

```java
        var strategyCommand = STRATEGY_COMMAND.matcher(normalize(message));
        if (strategyCommand.matches()) {
            return handleStrategyCommand("aprueba".equals(strategyCommand.group(1)), strategyCommand.group(2));
        }
```

5. `handleAutonomyCommand`: encender/apagar ambos frentes:

```java
    private String handleAutonomyCommand(boolean on) {
        var products = autonomy.setProducts(on, "el chat");
        var clients = autonomy.setClients(on, "el chat");
        log.info("CHAT_INTENT_AUTONOMY_{} products={} clients={}", on ? "ON" : "OFF", products, clients);
        var state = on ? "encendido" : "apagado";
        return "Modo automático — Crear productos y servicios: " + (products ? state : "ya estaba " + state)
                + ". Buscar clientes: " + (clients ? state : "ya estaba " + state) + "."
                + (on ? " Retoman en su próximo chequeo." : " Lo ya lanzado termina igual.")
                + " Contactar clientes o vender sigue siendo decisión tuya.";
    }
```

6. `formatAutonomy`: reemplazar `". Buscar clientes: próximamente (todavía no existe). "` por
   `". Buscar clientes: " + frontText(view.clients()) + ". "`, y usar el mismo helper para productos:

```java
    private static String frontText(AutonomyService.Front front) {
        return front.enabled() ? "encendido"
                : "apagado" + (front.pauseReason() == null ? "" : " (" + front.pauseReason() + ")");
    }
```

   Agregar a "Esperando tu decisión" `+ ", " + waiting.pendingStrategies() + " estrategias por aprobar"` antes del punto final.
7. `QueryIntent`: agregar `PROSPECTS, PROSPECTING` antes de `AUTONOMY`. `detectQuery`, antes del bloque de `AUTONOMY`:

```java
        // Spec búsqueda de prospectos (2026-09-30).
        if (normalized.contains("busqueda de clientes") || normalized.contains("estrategias")) {
            return new QueryMatch(QueryIntent.PROSPECTING, null);
        }
        if (normalized.matches(".*\\bprospectos?\\b.*")) {
            return new QueryMatch(QueryIntent.PROSPECTS, null);
        }
```

   Switch de topics: `case "PROSPECTS" -> formatProspects(); case "PROSPECTING" -> formatProspecting();`.
8. Métodos:

```java
    private String handleStrategyCommand(boolean approve, String name) {
        var matches = strategyService.findByName(name);
        if (matches.isEmpty()) {
            return "No hay ninguna estrategia pendiente que se llame \"" + name + "\". Pendientes: "
                    + pendingStrategyNames() + ".";
        }
        if (matches.size() > 1) {
            return "Hay varias estrategias pendientes que coinciden: "
                    + matches.stream().map(s -> s.name()).collect(Collectors.joining(", "))
                    + ". Escribe el nombre exacto.";
        }
        var strategy = matches.get(0);
        if (approve) {
            strategyService.approve(strategy.id());
            return "Estrategia \"" + strategy.name() + "\" aprobada: entra a la rotación de la búsqueda de clientes.";
        }
        strategyService.reject(strategy.id());
        return "Estrategia \"" + strategy.name() + "\" rechazada: no se vuelve a proponer.";
    }

    private String pendingStrategyNames() {
        var names = strategyService.views().stream().filter(v -> "PENDING_APPROVAL".equals(v.status()))
                .map(v -> v.name()).toList();
        return names.isEmpty() ? "ninguna" : String.join(", ", names);
    }

    private String formatProspects() {
        var prospects = prospectingMemory.prospects();
        if (prospects.isEmpty()) {
            return "Todavía no hay prospectos: la búsqueda solo trabaja para productos listos para vender y con "
                    + "\"Buscar clientes\" encendido.";
        }
        return "Prospectos (" + prospects.size() + "; contactarlos es decisión tuya):\n" + prospects.stream().limit(30)
                .map(p -> "- " + p.name() + " (" + p.productName() + "): "
                        + (p.contactEmail() != null ? p.contactEmail() + " (fuente: " + p.contactEmailSource() + ")"
                                : "formulario " + p.contactFormUrl())
                        + " — " + p.fitReason() + " [" + p.url() + "]")
                .collect(Collectors.joining("\n"));
    }

    private String formatProspecting() {
        var runs = prospectingMemory.runs(5);
        var last = runs.isEmpty() ? "todavía no corrió"
                : runs.get(0).status().equals("COMPLETED")
                        ? "última corrida " + SINCE.format(runs.get(0).startedAt()) + ": " + runs.get(0).valid()
                                + " válidos de " + runs.get(0).found() + " (" + runs.get(0).strategyId() + ")"
                        : "última corrida falló: " + runs.get(0).error();
        var strategies = strategyService.views().stream()
                .map(v -> v.name() + " [" + v.status() + "] " + v.runs() + " corridas, "
                        + String.format(Locale.ROOT, "%.1f", v.validPerRun()) + " válidos/corrida")
                .collect(Collectors.joining("; "));
        return "Búsqueda de clientes: " + last + ". Estrategias: " + strategies + ". Pendientes de aprobar: "
                + pendingStrategyNames() + ".";
    }
```

Si `SINCE` o `Locale` no están importados/definidos con ese nombre, usar los existentes en el archivo (`SINCE` ya existe, ver `formatApiKeys`).

- [ ] **Step 4: Ver que pasan**

Run: `cd app && mvn -q test -Dtest=ChatIntentRouterTest`
Expected: PASS.

- [ ] **Step 5: Suite completa**

Run: `cd app && mvn test 2>&1 | grep -E "Tests run:.*Skipped: 0$|BUILD" | tail -2`
Expected: `BUILD SUCCESS`, 0 failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Chat: prospectos, búsqueda de clientes, estrategias y todo en automático con clientes"
```

---

### Task 9: Frontend — pantalla Prospectos, interruptor de clientes y Settings

**Files:**
- Create: `app/frontend/src/pages/ProspectsPage.tsx`
- Modify: `app/frontend/src/api/types.ts`, `app/frontend/src/api/client.ts`
- Modify: `app/frontend/src/App.tsx` (ruta `prospectos`), `app/frontend/src/components/Layout.tsx` (link)
- Modify: `app/frontend/src/components/AutonomyPanel.tsx`
- Modify: `app/frontend/src/pages/SettingsPage.tsx` (`POLICY_LABELS`)

**Interfaces:**
- Consumes: endpoints de Task 7; `AutonomyView.waiting.pendingStrategies`.

- [ ] **Step 1: Tipos y cliente**

`types.ts` (al final; y `waiting` de `AutonomyView` gana `pendingStrategies: number`):

```ts
// Búsqueda de prospectos (spec 2026-09-30): reflejo a mano de Prospect / ProspectingRun / StrategyView / StoredStrategy.
export interface Prospect {
  id: string
  productId: string
  productName: string | null
  name: string
  url: string | null
  contactEmail: string | null
  contactEmailSource: string | null
  contactFormUrl: string | null
  fitReason: string | null
  strategyId: string | null
  foundAt: string
}

export interface ProspectingRun {
  id: string
  productId: string | null
  strategyId: string | null
  status: 'COMPLETED' | 'FAILED'
  found: number
  valid: number
  rejections: string[]
  error: string | null
  startedAt: string
  endedAt: string | null
}

export interface StrategyView {
  id: string
  name: string
  description: string | null
  status: 'BASE' | 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED'
  proposedBy: string | null
  runs: number
  validPerRun: number
}
```

`client.ts` (importar los tipos):

```ts
  prospects: () => request<Prospect[]>('/api/company/prospecting/prospects'),
  prospectingRuns: () => request<ProspectingRun[]>('/api/company/prospecting/runs'),
  runProspectingNow: () => request<ProspectingRun>('/api/company/prospecting/runs', { method: 'POST' }),
  prospectingStrategies: () => request<StrategyView[]>('/api/company/prospecting/strategies'),
  decideStrategy: (id: string, decision: 'approve' | 'reject') =>
    request<unknown>(`/api/company/prospecting/strategies/${encodeURIComponent(id)}/${decision}`, { method: 'PUT' }),
```

- [ ] **Step 2: Pantalla**

`ProspectsPage.tsx`:

```tsx
import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'

// Búsqueda de prospectos (spec 2026-09-30 §3): prospectos por producto, estrategias con rendimiento y corridas.
// Contactar es decisión del fundador (subproyecto 6); acá solo se ven.
export default function ProspectsPage() {
  const queryClient = useQueryClient()
  const [feedback, setFeedback] = useState<string | null>(null)
  const prospects = useQuery({ queryKey: ['prospects'], queryFn: api.prospects, refetchInterval: 30_000 })
  const runs = useQuery({ queryKey: ['prospectingRuns'], queryFn: api.prospectingRuns, refetchInterval: 30_000 })
  const strategies = useQuery({ queryKey: ['prospectingStrategies'], queryFn: api.prospectingStrategies, refetchInterval: 30_000 })
  const refresh = () => {
    for (const key of ['prospects', 'prospectingRuns', 'prospectingStrategies', 'autonomy']) {
      void queryClient.invalidateQueries({ queryKey: [key] })
    }
  }
  const runNow = useMutation({
    mutationFn: api.runProspectingNow,
    onSuccess: (run) => {
      setFeedback(run.status === 'COMPLETED' ? `Corrida lista: ${run.valid} válidos de ${run.found}.` : `Falló: ${run.error}`)
      refresh()
    },
    onError: (e: Error) => setFeedback(e.message),
  })
  const decide = useMutation({
    mutationFn: ({ id, decision }: { id: string; decision: 'approve' | 'reject' }) => api.decideStrategy(id, decision),
    onSuccess: () => {
      setFeedback(null)
      refresh()
    },
    onError: (e: Error) => setFeedback(e.message),
  })

  const byProduct = new Map<string, NonNullable<typeof prospects.data>>()
  for (const p of prospects.data ?? []) {
    const key = p.productName ?? p.productId
    byProduct.set(key, [...(byProduct.get(key) ?? []), p])
  }

  return (
    <div>
      <h1>Prospectos</h1>
      <p className="hint">Contactar prospectos o venderles sigue siendo decisión tuya.</p>
      <button disabled={runNow.isPending} onClick={() => runNow.mutate()}>
        {runNow.isPending ? 'Buscando… (puede tardar varios minutos)' : 'Buscar ahora'}
      </button>
      {feedback && <p className={runNow.isError || decide.isError ? 'error' : 'feedback'}>{feedback}</p>}

      <h2>Por producto</h2>
      {byProduct.size === 0 && <p className="hint">Todavía no hay prospectos.</p>}
      {[...byProduct.entries()].map(([product, list]) => (
        <div key={product} className="card">
          <h3>
            {product} ({list.length})
          </h3>
          <ul>
            {list.map((p) => (
              <li key={p.id}>
                <strong>{p.url ? <a href={p.url} target="_blank" rel="noreferrer">{p.name}</a> : p.name}</strong> —{' '}
                {p.contactEmail ? (
                  <>
                    {p.contactEmail} (<a href={p.contactEmailSource ?? '#'} target="_blank" rel="noreferrer">fuente</a>)
                  </>
                ) : (
                  <a href={p.contactFormUrl ?? '#'} target="_blank" rel="noreferrer">formulario</a>
                )}{' '}
                · {p.fitReason} · <span className="hint">{p.strategyId} · {new Date(p.foundAt).toLocaleString()}</span>
              </li>
            ))}
          </ul>
        </div>
      ))}

      <h2>Estrategias</h2>
      <table>
        <thead>
          <tr>
            <th>Estrategia</th>
            <th>Estado</th>
            <th>Corridas</th>
            <th>Válidos/corrida</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {(strategies.data ?? []).map((s) => (
            <tr key={s.id}>
              <td title={s.description ?? ''}>{s.name}</td>
              <td>{s.status}</td>
              <td>{s.runs}</td>
              <td>{s.validPerRun.toFixed(1)}</td>
              <td>
                {s.status === 'PENDING_APPROVAL' && (
                  <>
                    <button disabled={decide.isPending} onClick={() => decide.mutate({ id: s.id, decision: 'approve' })}>
                      Aprobar
                    </button>{' '}
                    <button
                      className="danger"
                      disabled={decide.isPending}
                      onClick={() => decide.mutate({ id: s.id, decision: 'reject' })}
                    >
                      Rechazar
                    </button>
                  </>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <h2>Últimas corridas</h2>
      <ul>
        {(runs.data ?? []).map((r) => (
          <li key={r.id}>
            {new Date(r.startedAt).toLocaleString()} · {r.productId} · {r.strategyId} ·{' '}
            {r.status === 'COMPLETED' ? `${r.valid} válidos de ${r.found}` : <span className="error">falló: {r.error}</span>}
            {r.rejections.length > 0 && (
              <details>
                <summary>Descartados ({r.rejections.length})</summary>
                <ul>
                  {r.rejections.map((x, i) => (
                    <li key={i}>{x}</li>
                  ))}
                </ul>
              </details>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
```

`App.tsx`: `import ProspectsPage from './pages/ProspectsPage'` y `<Route path="prospectos" element={<ProspectsPage />} />` después de `productos`. `Layout.tsx`: `<NavLink to="/prospectos">Prospectos</NavLink>` después de Productos.

- [ ] **Step 3: Dashboard y Settings**

`AutonomyPanel.tsx`:
- Interruptor "Buscar clientes": reemplazar el `<label className="hint">…disabled…</label>` por un checkbox igual al de productos con `checked={view.clients.enabled}`, `disabled={mutation.isPending || !view.clients.available}` y `onChange={() => mutation.mutate({ clients: !view.clients.enabled })}`, texto "Buscar clientes".
- "Todo en automático": `onChange={() => mutation.mutate({ products: !allOn, ...(view.clients.available ? { clients: !allOn } : {}) })}`.
- Motivo de pausa de clientes: `{!view.clients.enabled && view.clients.pauseReason && view.clients.pauseReason !== 'Valor inicial de seed' && <p className="hint">Clientes: {view.clients.pauseReason}</p>}`.
- Fila "Clientes": query `const runs = useQuery({ queryKey: ['prospectingRuns'], queryFn: api.prospectingRuns, refetchInterval: 30_000 })`; mostrar la última corrida: `última corrida <fecha> · <strategyId> · <valid> válidos de <found>` (o `falló: <error>`), o "todavía no corrió"; link `<Link to="/prospectos">ver prospectos</Link>`.
- "Esperando tu decisión": agregar `· <Link to="/prospectos">{view.waiting.pendingStrategies} estrategias por aprobar</Link>`.

`SettingsPage.tsx` `POLICY_LABELS`:

```ts
  PROSPECTING_ENABLED: 'Búsqueda de clientes encendida (1 = sí, 0 = no)',
  MAX_PROSPECTS_PER_DAY: 'Prospectos nuevos por día (máximo)',
```

- [ ] **Step 4: Build y lint**

Run: `cd app/frontend && npm run build && npm run lint`
Expected: build OK; lint sin hallazgos nuevos.

- [ ] **Step 5: Commit**

```bash
git add app/frontend/src
git commit -m "Command Center: pantalla Prospectos e interruptor Buscar clientes"
```

---

### Task 10: Documentación, despliegue y verificación en vivo

**Files:**
- Modify: `CLAUDE.md`, `docs/EVENTS.md`, `docs/HISTORY.md`

- [ ] **Step 1: Documentación**

`CLAUDE.md`, en "Datos reales del fundador vs. hallazgos de agentes", después del bullet del orquestador, un bullet nuevo:

```markdown
- **Búsqueda diaria de prospectos** (spec `2026-09-30-busqueda-de-prospectos-design.md`; paquete `prospecting`, `ProspectingController` `/api/company/prospecting/**`, pantalla `/prospectos`): con `PROSPECTING_ENABLED = 1` (interruptor "Buscar clientes"), un chequeo horario corre una vez por día (≥ 08:00 UTC) para un producto `READY_TO_SELL` (rotando). Java elige la estrategia (`StrategySelector`: catálogo base `BaseStrategy` + las aprobadas; primero las no usadas, luego mejor rendimiento sin repetir la anterior), Sofía busca (`CeoService.searchProspects`, dos turnos, alcance de la ficha: `WORLDWIDE` → sin país) y `ProspectValidator` exige nombre en su página y email literal en su fuente (o formulario que responde), sin repetir dominio. Válidos → `Customer {status:'LEAD'}` + `(:Product)-[:HAS_PROSPECT]`, hasta `MAX_PROSPECTS_PER_DAY`; `(:ProspectingRun)` con motivos de descarte. Los lunes Kira propone una estrategia (`StrategyProposalService`, `PENDING_APPROVAL`, una a la vez, nombres únicos) que el fundador aprueba o rechaza (🔴; chat "aprueba/rechaza la estrategia X"). `POST /runs` = "Buscar ahora". Contactar sigue siendo 🔴 (subproyecto 6).
```

En "Command Center web", agregar **Prospectos** a la lista de pantallas y en el Dashboard cambiar "Buscar clientes" próximamente por `= policy PROSPECTING_ENABLED`.

`docs/EVENTS.md`: línea nueva con `EMPRESA_PROSPECTING_RUN_COMPLETED` `{runId, productId, strategy, found, valid}`, `EMPRESA_PROSPECTING_RUN_FAILED` `{runId, productId, error}`, `EMPRESA_PROSPECTING_STRATEGY_PROPOSED|APPROVED|REJECTED` `{strategyId, name}` (agentId `sales`, `growth-content` o `human`; sin `missionId`).

`docs/HISTORY.md`:

```markdown

### Búsqueda diaria de prospectos (subproyecto 3)

**Decisiones del fundador** (2026-09-30): solo para productos listos para vender; Java rota estrategias y Sofía ejecuta, y cada semana Marketing propone una nueva que el fundador aprueba; prospecto válido = contacto público verificable. Lección de la rama del 18-sep (segmentos con nombre y fuente genérica, corregido solo por prompt): acá la validez la decide Java (nombre en su propia página, email literal en su fuente).
```

- [ ] **Step 2: Suite, build y commit**

Run: `cd app && mvn test 2>&1 | grep -E "Tests run:.*Skipped: 0$|BUILD" | tail -2 && cd frontend && npm run build`
Expected: `BUILD SUCCESS`; build OK.

```bash
git add CLAUDE.md docs/EVENTS.md docs/HISTORY.md
git commit -m "Documentar la búsqueda diaria de prospectos"
```

- [ ] **Step 3: Desplegar**

Sin misiones en curso ni agentes `WORKING`: `docker compose build company-core && docker compose up -d company-core`; esperar `/actuator/health` `UP`.

- [ ] **Step 4: Verificar en vivo**

- `GET /api/company/autonomy` → `clients.available=true`, `enabled=false`; `GET /api/company/prospecting/strategies` → 4 estrategias base.
- Sin productos `READY_TO_SELL`: `POST /api/company/prospecting/runs` → 500 "No hay productos listos para vender…".
- Con un producto `READY_TO_SELL` (si no hay uno real, pedir al fundador cuál usar; nunca crear productos por cuenta propia), `POST /runs` → corrida `COMPLETED` o `FAILED` explicada; revisar en `GET /prospects` que cada email aparezca en su `contactEmailSource` (abrir 1-2 a mano) y leer los motivos de descarte.
- Registrar en `docs/HISTORY.md` (commit "HISTORY: verificación en vivo de la búsqueda de prospectos").
