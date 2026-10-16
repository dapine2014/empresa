# Revisión conjunta Neo + Aria Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Después de que Kael diseña una app o un juego, Neo detecta las contradicciones entre las HU de Aria y el diseño, Aria decide cada una, Java aplica los cambios (ajuste a Aria y/o a Kael) y revisa una segunda vez, antes de que el fundador apruebe.

**Architecture:** Un modelo (`JointReview`) con dos esquemas JSON (detección de Neo y decisión de Aria) validados por `JointReviewValidator` (Java puro). `JointReviewWriter` hace las dos llamadas con reintento y corrección como los demás writers. `JointReviewService` decide si la revisión aplica, la persiste (`JointReviewMemoryService`), publica el evento y devuelve qué ajustar. `MissionExecutor` aplica el resultado reusando `rewriteRequirements` y `InteractionDesignWriter.write`, y la recuperación suma la etapa `JOINT_REVIEW`.

**Tech Stack:** Spring Boot 4.1.1 / Java 21, Neo4j (driver plano), Jackson 3 (`tools.jackson.*`), JUnit 5 + Mockito, React + Vite + TS (oxlint).

**Spec:** `docs/superpowers/specs/2026-10-15-revision-conjunta-design.md`

## Global Constraints

- Rama: `modelo-retirado` (ya tiene la cadena de modelos gratis y la cancelación); commits con el trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Determinismo: todo lo decidible en Java (ids válidos, cobertura de decisiones, agrupar ajustes, topes, cuándo aplica) se decide en Java; el modelo solo detecta (Neo) y decide (Aria).
- Nunca `format` + `tools` en una llamada: las dos llamadas usan `CeoService.callStructured` (format, sin tools).
- Jackson 3: `tools.jackson.*`; las anotaciones siguen siendo `com.fasterxml.jackson.annotation.*` (como `InteractionDesign`).
- Sin `@Async`; los `*MemoryService` no tienen test directo (convención del repo).
- Ids exactos: contradicciones `CONTRA-NN` (dos dígitos, desde 01, en orden); lados `DESIGN` | `REQUIREMENTS`; estados de `JointReview`: `COHERENT`, `RESOLVED`, `UNRESOLVED`, `FAILED`, `SKIPPED`.
- Topes: `JointReviewValidator.MAX_CONTRADICTIONS = 10`; `JointReviewWriter.MAX_ATTEMPTS = 3`; `JointReviewService.ROUNDS = 2` (la ronda 2 solo detecta: lo que quede es `UNRESOLVED`).
- Prefijo de los ajustes que genera la revisión: `JointReviewService.PREFIX = "Decisión del líder técnico (Neo) con el PO (Aria):"`.
- Agentes: Neo = `engineering`, Aria = `RequirementsWriter.ARIA`, Kael = `InteractionDesignWriter.KAEL`.
- Policy nueva `JOINT_REVIEW_ENABLED` (0/1, semilla 1). Evento nuevo `EMPRESA_JOINT_REVIEW_COMPLETED` (`agentId` `engineering`).
- La revisión **nunca** deja la misión `FAILED`: si falla, se registra `FAILED` y la misión sigue a la aprobación con un aviso.
- Todo consultable en el chat (Java, sin modelo) y visible en el Command Center; `api/types.ts` se mantiene a mano.

## Review Focus

1. Un ajuste del fundador que Neo propone deshacer: los ajustes del fundador van al prompt de Neo como intocables (test en Task 4: el prompt los contiene y excluye los ajustes de la propia revisión).
2. Un cambio a las HU en la revisión obliga a rediseñar a Kael sobre las HU nuevas, y la segunda revisión corre sobre ese par nuevo, no sobre el viejo (test en Task 6).
3. Un reinicio entre que Kael terminó y la revisión: la misión retoma en `JOINT_REVIEW`, no se queda esperando aprobación sin revisión (test en Task 6, `ResumePointTest`).
4. Neo encuentra contradicciones en la ronda 2: quedan `UNRESOLVED`, sin tercera vuelta ni llamada a Aria (test en Task 5).
5. Con `SPEC_APPROVAL_REQUIRED = 0` la revisión corre igual antes de la aprobación automática (test en Task 6).

---

### Task 1: Modelo y validador de la revisión

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/model/JointReview.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/JointReviewValidator.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/JointReviewValidatorTest.java`

**Interfaces:**
- Produces:
  - `JointReview.Contradiction(String id, List<String> requirementRefs, List<String> designRefs, String requirementSays, String designSays, Proposal proposal)`, `JointReview.Proposal(String side, String change)`, `JointReview.Detection(List<Contradiction> contradictions)` con `SCHEMA`, `JointReview.Decision(String id, String side, String change, String reason)`, `JointReview.Resolution(List<Decision> decisions)` con `SCHEMA`; constantes `JointReview.DESIGN = "DESIGN"`, `JointReview.REQUIREMENTS = "REQUIREMENTS"`.
  - `JointReviewValidator.validateDetection(Detection, RequirementsSpec, InteractionDesign) -> List<String>` (errores; vacío = válido).
  - `JointReviewValidator.validateResolution(Resolution, List<Contradiction>) -> List<String>`.
  - `JointReviewValidator.MAX_CONTRADICTIONS = 10`.

- [ ] **Step 1: Write the failing test**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.InteractionDesign.*;
import com.aicompany.core.agent.model.JointReview;
import com.aicompany.core.agent.model.JointReview.*;
import com.aicompany.core.agent.model.RequirementsSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Revisión conjunta (spec 2026-10-15): Java valida lo que Neo detecta y lo que Aria decide. */
public class JointReviewValidatorTest {

    // Públicos: los reusan JointReviewWriterTest y JointReviewServiceTest (otro paquete).
    public static final RequirementsSpec SPEC = new RequirementsSpec("Pong", List.of("800x600"), List.of(),
            List.of(new RequirementsSpec.Role("jugador", "d")),
            List.of(new RequirementsSpec.UserStory("HU-01", "jugador", "q", "p", List.of("c"), List.of(),
                    List.of(new RequirementsSpec.Scenario("ESC-01", "a", List.of("d"), List.of("c"), List.of("e"))))),
            List.of(new RequirementsSpec.Increment(1, "MVP", List.of("HU-01"))), "GAME");

    public static final InteractionDesign GAME = new InteractionDesign("GAME", null, new GameDesign(List.of("PLAYING"),
            List.of(), List.of(new Entity("pelota", "Pelota", List.of(new Property("x", 400.0)))),
            List.of(new Control("move_up", "Up", "sube")), List.of(new Rule("REGLA-01", "move_up", "sube")),
            List.of(), List.of("REGLA-01"), List.of(), List.of(new ScenarioRule("ESC-01", "REGLA-01")), List.of()));

    public static final InteractionDesign APP = new InteractionDesign("APP", new AppDesign(
            List.of(new Screen("PANT-01", "Lista", "ver", "jugador", List.of())),
            List.of(new Flow("HU-01", List.of("PANT-01"), List.of())), List.of(new ScenarioScreen("ESC-01", "PANT-01")),
            List.of()), null);

    static Contradiction contra(String id, List<String> req, List<String> design) {
        return new Contradiction(id, req, design, "las HU dicen 800", "el diseño usa 640",
                new Proposal(JointReview.DESIGN, "usar 800"));
    }

    @Test void aValidDetectionHasNoErrors() {
        var d = new Detection(List.of(contra("CONTRA-01", List.of("HU-01", "ESC-01"), List.of("REGLA-01", "pelota"))));
        assertEquals(List.of(), JointReviewValidator.validateDetection(d, SPEC, GAME));
    }

    @Test void anEmptyDetectionIsCoherent() {
        assertEquals(List.of(), JointReviewValidator.validateDetection(new Detection(List.of()), SPEC, GAME));
        assertEquals(List.of(), JointReviewValidator.validateDetection(new Detection(null), SPEC, GAME));
    }

    @Test void referencesMustExistOnEachSide() {
        var d = new Detection(List.of(contra("CONTRA-01", List.of("HU-09"), List.of("REGLA-07"))));
        var errors = String.join("\n", JointReviewValidator.validateDetection(d, SPEC, GAME));
        assertTrue(errors.contains("HU-09"), errors);
        assertTrue(errors.contains("REGLA-07"), errors);
    }

    @Test void inAnAppTheDesignRefsAreScreens() {
        var ok = new Detection(List.of(contra("CONTRA-01", List.of("HU-01"), List.of("PANT-01"))));
        assertEquals(List.of(), JointReviewValidator.validateDetection(ok, SPEC, APP));
        var bad = new Detection(List.of(contra("CONTRA-01", List.of("HU-01"), List.of("REGLA-01"))));
        assertFalse(JointReviewValidator.validateDetection(bad, SPEC, APP).isEmpty());
    }

    @Test void eachSideNeedsAtLeastOneReferenceAndTheTextsAreRequired() {
        var d = new Detection(List.of(new Contradiction("CONTRA-01", List.of(), List.of(), " ", "", new Proposal("OTRO", ""))));
        var errors = String.join("\n", JointReviewValidator.validateDetection(d, SPEC, GAME));
        assertTrue(errors.contains("requirementRefs"), errors);
        assertTrue(errors.contains("designRefs"), errors);
        assertTrue(errors.contains("requirementSays"), errors);
        assertTrue(errors.contains("side"), errors);
        assertTrue(errors.contains("change"), errors);
    }

    @Test void idsAreSequentialAndUnique() {
        var d = new Detection(List.of(contra("CONTRA-02", List.of("HU-01"), List.of("REGLA-01")),
                contra("CONTRA-02", List.of("HU-01"), List.of("REGLA-01"))));
        var errors = String.join("\n", JointReviewValidator.validateDetection(d, SPEC, GAME));
        assertTrue(errors.contains("CONTRA-01"), errors);
    }

    @Test void atMostTenContradictions() {
        var list = new ArrayList<Contradiction>();
        for (int i = 1; i <= 11; i++) {
            list.add(contra("CONTRA-%02d".formatted(i), List.of("HU-01"), List.of("REGLA-01")));
        }
        var errors = String.join("\n", JointReviewValidator.validateDetection(new Detection(list), SPEC, GAME));
        assertTrue(errors.contains("10"), errors);
    }

    @Test void ariaDecidesEachContradictionExactlyOnce() {
        var contradictions = List.of(contra("CONTRA-01", List.of("HU-01"), List.of("REGLA-01")),
                contra("CONTRA-02", List.of("HU-01"), List.of("REGLA-01")));
        var ok = new Resolution(List.of(new Decision("CONTRA-01", "DESIGN", "usar 800", "las HU mandan"),
                new Decision("CONTRA-02", "REQUIREMENTS", "aceptar 640", "es mejor")));
        assertEquals(List.of(), JointReviewValidator.validateResolution(ok, contradictions));

        var missingAndExtra = new Resolution(List.of(new Decision("CONTRA-01", "DESIGN", "x", "y"),
                new Decision("CONTRA-01", "DESIGN", "x", "y"), new Decision("CONTRA-09", "DESIGN", "x", "y")));
        var errors = String.join("\n", JointReviewValidator.validateResolution(missingAndExtra, contradictions));
        assertTrue(errors.contains("CONTRA-02"), errors);
        assertTrue(errors.contains("CONTRA-09"), errors);
        assertTrue(errors.contains("dos veces"), errors);
    }

    @Test void aDecisionNeedsAValidSideAChangeAndAReason() {
        var contradictions = List.of(contra("CONTRA-01", List.of("HU-01"), List.of("REGLA-01")));
        var bad = new Resolution(List.of(new Decision("CONTRA-01", "NINGUNO", " ", null)));
        var errors = String.join("\n", JointReviewValidator.validateResolution(bad, contradictions));
        assertTrue(errors.contains("side"), errors);
        assertTrue(errors.contains("change"), errors);
        assertTrue(errors.contains("reason"), errors);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && mvn -q test -Dtest=JointReviewValidatorTest`
Expected: COMPILATION ERROR (`JointReview` no existe).

- [ ] **Step 3: Write the model**

```java
package com.aicompany.core.agent.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;
import java.util.Map;

/**
 * Revisión conjunta (spec 2026-10-15, decisión del fundador: "cuando se generan contradicciones el líder técnico decide
 * junto con el PO"): Neo detecta las contradicciones entre las HU de Aria y el diseño de Kael y propone; Aria decide.
 */
public final class JointReview {

    public static final String DESIGN = "DESIGN";
    public static final String REQUIREMENTS = "REQUIREMENTS";

    private JointReview() {
    }

    public record Proposal(String side, String change) {
    }

    public record Contradiction(String id,
                                @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> requirementRefs,
                                @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> designRefs,
                                String requirementSays, String designSays, Proposal proposal) {
        public Contradiction {
            requirementRefs = requirementRefs == null ? List.of() : requirementRefs;
            designRefs = designRefs == null ? List.of() : designRefs;
        }
    }

    public record Detection(List<Contradiction> contradictions) {
        public Detection {
            contradictions = contradictions == null ? List.of() : contradictions;
        }

        public static final Map<String, Object> SCHEMA = Map.of("type", "object",
                "properties", Map.of("contradictions", Map.of("type", "array", "items", Map.of("type", "object",
                        "properties", Map.of(
                                "id", str(),
                                "requirementRefs", strings(),
                                "designRefs", strings(),
                                "requirementSays", str(),
                                "designSays", str(),
                                "proposal", Map.of("type", "object", "properties", Map.of(
                                                "side", Map.of("type", "string", "enum", List.of(DESIGN, REQUIREMENTS)),
                                                "change", str()),
                                        "required", List.of("side", "change"))),
                        "required", List.of("id", "requirementRefs", "designRefs", "requirementSays", "designSays",
                                "proposal")))),
                "required", List.of("contradictions"));
    }

    public record Decision(String id, String side, String change, String reason) {
    }

    public record Resolution(List<Decision> decisions) {
        public Resolution {
            decisions = decisions == null ? List.of() : decisions;
        }

        public static final Map<String, Object> SCHEMA = Map.of("type", "object",
                "properties", Map.of("decisions", Map.of("type", "array", "items", Map.of("type", "object",
                        "properties", Map.of(
                                "id", str(),
                                "side", Map.of("type", "string", "enum", List.of(DESIGN, REQUIREMENTS)),
                                "change", str(),
                                "reason", str()),
                        "required", List.of("id", "side", "change", "reason")))),
                "required", List.of("decisions"));
    }

    private static Map<String, Object> str() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> strings() {
        return Map.of("type", "array", "items", Map.of("type", "string"));
    }
}
```

- [ ] **Step 4: Write the validator**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.JointReview;
import com.aicompany.core.agent.model.RequirementsSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Revisión conjunta (spec 2026-10-15): Java valida en código lo que Neo detecta y lo que Aria decide; el motivo vuelve
 * como CORRECCIÓN DEL INTENTO ANTERIOR. Nunca le pide a un modelo que se revise.
 */
public final class JointReviewValidator {

    public static final int MAX_CONTRADICTIONS = 10;
    private static final Set<String> SIDES = Set.of(JointReview.DESIGN, JointReview.REQUIREMENTS);

    private JointReviewValidator() {
    }

    public static List<String> validateDetection(JointReview.Detection detection, RequirementsSpec spec,
                                                 InteractionDesign design) {
        var errors = new ArrayList<String>();
        var contradictions = detection == null ? List.<JointReview.Contradiction>of() : detection.contradictions();
        if (contradictions.size() > MAX_CONTRADICTIONS) {
            errors.add("Máximo " + MAX_CONTRADICTIONS + " contradicciones (hay " + contradictions.size()
                    + "): junta las que tengan la misma causa.");
        }
        var requirementIds = requirementIds(spec);
        var designIds = designIds(design);
        var seen = new HashSet<String>();
        for (int i = 0; i < contradictions.size(); i++) {
            var c = contradictions.get(i);
            var expected = "CONTRA-%02d".formatted(i + 1);
            var label = c == null || c.id() == null ? expected : c.id();
            if (c == null) {
                errors.add(expected + ": contradicción vacía.");
                continue;
            }
            if (!expected.equals(c.id())) {
                errors.add("La contradicción " + (i + 1) + " debe tener id " + expected + " (tiene " + c.id() + ").");
            }
            if (c.id() != null && !seen.add(c.id())) {
                errors.add(c.id() + " está repetida.");
            }
            if (c.requirementRefs().isEmpty()) {
                errors.add(label + ": requirementRefs necesita al menos una HU-NN o ESC-NN.");
            }
            for (var ref : c.requirementRefs()) {
                if (!requirementIds.contains(ref)) {
                    errors.add(label + ": " + ref + " no existe en las HU (existen " + requirementIds + ").");
                }
            }
            if (c.designRefs().isEmpty()) {
                errors.add(label + ": designRefs necesita al menos un id del diseño.");
            }
            for (var ref : c.designRefs()) {
                if (!designIds.contains(ref)) {
                    errors.add(label + ": " + ref + " no existe en el diseño (existen " + designIds + ").");
                }
            }
            if (blank(c.requirementSays())) {
                errors.add(label + ": requirementSays vacío (qué dicen las HU).");
            }
            if (blank(c.designSays())) {
                errors.add(label + ": designSays vacío (qué dice el diseño).");
            }
            var proposal = c.proposal();
            if (proposal == null || !SIDES.contains(proposal.side())) {
                errors.add(label + ": proposal.side debe ser DESIGN o REQUIREMENTS.");
            }
            if (proposal == null || blank(proposal.change())) {
                errors.add(label + ": proposal.change vacío (el cambio concreto).");
            }
        }
        return errors;
    }

    public static List<String> validateResolution(JointReview.Resolution resolution,
                                                  List<JointReview.Contradiction> contradictions) {
        var errors = new ArrayList<String>();
        var decisions = resolution == null ? List.<JointReview.Decision>of() : resolution.decisions();
        var expected = new LinkedHashMap<String, Integer>();
        contradictions.forEach(c -> expected.put(c.id(), 0));
        for (var d : decisions) {
            if (d == null) {
                errors.add("Hay una decisión vacía.");
                continue;
            }
            if (!expected.containsKey(d.id())) {
                errors.add(d.id() + " no es una contradicción de Neo (son " + expected.keySet() + ").");
                continue;
            }
            if (expected.merge(d.id(), 1, Integer::sum) == 2) {
                errors.add(d.id() + " está decidida dos veces: una sola decisión por contradicción.");
            }
            if (!SIDES.contains(d.side())) {
                errors.add(d.id() + ": side debe ser DESIGN o REQUIREMENTS.");
            }
            if (blank(d.change())) {
                errors.add(d.id() + ": change vacío (el cambio concreto que se aplica).");
            }
            if (blank(d.reason())) {
                errors.add(d.id() + ": reason vacío (por qué lo decides así).");
            }
        }
        expected.forEach((id, count) -> {
            if (count == 0) {
                errors.add(id + " no tiene decisión: decide cada contradicción.");
            }
        });
        return errors;
    }

    static Set<String> requirementIds(RequirementsSpec spec) {
        var ids = new java.util.TreeSet<String>();
        for (var story : spec.userStoriesOrEmpty()) {
            ids.add(story.id());
            story.scenariosOrEmpty().forEach(s -> ids.add(s.id()));
        }
        return ids;
    }

    static Set<String> designIds(InteractionDesign design) {
        var ids = new java.util.TreeSet<String>();
        if (design.app() != null) {
            design.app().screens().forEach(s -> ids.add(s.id()));
        }
        if (design.game() != null) {
            design.game().rules().forEach(r -> ids.add(r.id()));
            design.game().entities().forEach(e -> ids.add(e.id()));
        }
        ids.remove(null);
        return ids;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd app && mvn -q test -Dtest=JointReviewValidatorTest`
Expected: PASS (9 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/model/JointReview.java \
  app/src/main/java/com/aicompany/core/agent/validation/JointReviewValidator.java \
  app/src/test/java/com/aicompany/core/agent/validation/JointReviewValidatorTest.java
git commit -m "Revisión conjunta: modelo y validador de lo que detecta Neo y decide Aria"
```

---

### Task 2: Persistencia, policy y borrado en cascada

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/JointReviewMemoryService.java`
- Modify: `app/src/main/java/com/aicompany/core/model/PolicyKey.java` (agregar `JOINT_REVIEW_ENABLED` al final)
- Modify: `app/src/main/java/com/aicompany/core/service/CompanyPolicyService.java` (semilla 1.0 junto a `SPEC_APPROVAL_REQUIRED`; `validateValue` acepta solo 0/1)
- Modify: `app/src/main/java/com/aicompany/core/service/RequirementsMemoryService.java` y `InteractionDesignMemoryService.java` (método `instructions`)
- Modify: `app/src/main/java/com/aicompany/core/service/MissionMemoryService.java` (cascada `HAS_JOINT_REVIEW`)
- Modify: `app/frontend/src/api/types.ts` (`PolicyKey` suma `'JOINT_REVIEW_ENABLED'`), `app/frontend/src/pages/SettingsPage.tsx` (etiqueta)
- Test: `app/src/test/java/com/aicompany/core/service/CompanyPolicyDefaultsTest.java`

**Interfaces:**
- Produces:
  - `JointReviewMemoryService.Review(int number, int round, int specVersion, int designVersion, String status, String contradictionsJson, String decisionsJson, String detail, String at)`.
  - `int save(String missionId, int round, int specVersion, int designVersion, String status, String contradictionsJson, String decisionsJson, String detail)` (devuelve `number`, 1, 2, … por misión).
  - `Optional<Review> closedFor(String missionId, int specVersion, int designVersion)`; `List<Review> all(String missionId)` (orden por `number`).
  - `List<String> RequirementsMemoryService.instructions(String missionId)` y `List<String> InteractionDesignMemoryService.instructions(String missionId)`: las `instruction` no vacías en orden de versión.
  - `PolicyKey.JOINT_REVIEW_ENABLED`.

- [ ] **Step 1: Write the failing test** (agregar a `CompanyPolicyDefaultsTest`, que ya existe)

```java
    // Revisión conjunta (spec 2026-10-15): encendida por defecto, solo 0 o 1.
    @Test
    void jointReviewIsSeededOnAndOnlyAcceptsZeroOrOne() {
        var defaults = CompanyPolicyService.defaults(new AppProperties("Forjai", 50, 60));
        assertEquals(1.0, defaults.get(PolicyKey.JOINT_REVIEW_ENABLED));
        assertDoesNotThrow(() -> CompanyPolicyService.validateValue(PolicyKey.JOINT_REVIEW_ENABLED, 0));
        assertDoesNotThrow(() -> CompanyPolicyService.validateValue(PolicyKey.JOINT_REVIEW_ENABLED, 1));
        assertThrows(IllegalArgumentException.class, () -> CompanyPolicyService.validateValue(PolicyKey.JOINT_REVIEW_ENABLED, 2));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && mvn -q test -Dtest=CompanyPolicyDefaultsTest`
Expected: COMPILATION ERROR (`JOINT_REVIEW_ENABLED` no existe).

- [ ] **Step 3: Implement policy**

En `PolicyKey`: agregar `JOINT_REVIEW_ENABLED` después de `MAX_MISSION_COST_USD` (la coma en la línea anterior).
En `CompanyPolicyService`, junto a `SPEC_APPROVAL_REQUIRED`:

```java
        // Revisión conjunta (spec 2026-10-15): Neo y Aria resuelven las contradicciones de HU y diseño antes del fundador.
        map.put(PolicyKey.JOINT_REVIEW_ENABLED, 1.0);
```

y en `validateValue`, sumar `|| key == PolicyKey.JOINT_REVIEW_ENABLED` a la condición de 0/1.

Frontend: en `types.ts` agregar `| 'JOINT_REVIEW_ENABLED'` a la unión `PolicyKey`; en `SettingsPage.tsx`, junto a `SPEC_APPROVAL_REQUIRED`:

```ts
  JOINT_REVIEW_ENABLED: 'Revisión conjunta de Neo y Aria de HU y diseño antes de tu aprobación (1 = sí, 0 = no)',
```

- [ ] **Step 4: Implement memory** (sin test directo, convención del repo)

```java
package com.aicompany.core.service;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Revisión conjunta (spec 2026-10-15): {@code (:Mission)-[:HAS_JOINT_REVIEW]->(:JointReview {number, round,
 * specVersion, designVersion, status, contradictionsJson, decisionsJson, detail, at})}. Cerrada = cualquier estado
 * (se guarda solo al terminar).
 */
@Service
public class JointReviewMemoryService {

    public record Review(int number, int round, int specVersion, int designVersion, String status,
                         String contradictionsJson, String decisionsJson, String detail, String at) {
    }

    private final Driver driver;

    public JointReviewMemoryService(Driver driver) {
        this.driver = driver;
    }

    public int save(String missionId, int round, int specVersion, int designVersion, String status,
                    String contradictionsJson, String decisionsJson, String detail) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var next = tx.run("MATCH (m:Mission {id:$id}) OPTIONAL MATCH (m)-[:HAS_JOINT_REVIEW]->(r) "
                        + "RETURN coalesce(max(r.number), 0) + 1 AS n", Map.of("id", missionId)).single().get("n").asInt();
                tx.run("MATCH (m:Mission {id:$id}) CREATE (m)-[:HAS_JOINT_REVIEW]->(:JointReview {number:$n, round:$round, "
                                + "specVersion:$spec, designVersion:$design, status:$status, contradictionsJson:$c, "
                                + "decisionsJson:$d, detail:$detail, at:$at})",
                        Map.of("id", missionId, "n", next, "round", round, "spec", specVersion, "design", designVersion,
                                "status", status, "c", contradictionsJson == null ? "[]" : contradictionsJson,
                                "d", decisionsJson == null ? "[]" : decisionsJson, "detail", detail == null ? "" : detail,
                                "at", Instant.now().toString()));
                return next;
            });
        }
    }

    public Optional<Review> closedFor(String missionId, int specVersion, int designVersion) {
        return query(missionId, " AND r.specVersion = $spec AND r.designVersion = $design",
                Map.of("id", missionId, "spec", specVersion, "design", designVersion)).stream().reduce((a, b) -> b);
    }

    public List<Review> all(String missionId) {
        return query(missionId, "", Map.of("id", missionId));
    }

    private List<Review> query(String missionId, String filter, Map<String, Object> params) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_JOINT_REVIEW]->(r:JointReview) WHERE true" + filter
                            + " RETURN r ORDER BY r.number", params)
                    .list(rec -> {
                        var r = rec.get("r");
                        return new Review(r.get("number").asInt(), r.get("round").asInt(), r.get("specVersion").asInt(),
                                r.get("designVersion").asInt(), r.get("status").asString(),
                                r.get("contradictionsJson").asString("[]"), r.get("decisionsJson").asString("[]"),
                                r.get("detail").asString(""), r.get("at").asString(""));
                    });
        }
    }
}
```

En `RequirementsMemoryService` (usar el mismo estilo de sesión de la clase):

```java
    /** Revisión conjunta (spec 2026-10-15): las instrucciones de cada versión, en orden (para no deshacer las del fundador). */
    public List<String> instructions(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_REQUIREMENTS]->(r:RequirementsVersion) "
                            + "WHERE r.instruction IS NOT NULL AND r.instruction <> '' RETURN r.instruction AS i ORDER BY r.version",
                    Map.of("id", missionId)).list(r -> r.get("i").asString());
        }
    }
```

En `InteractionDesignMemoryService`, lo mismo con `HAS_INTERACTION_DESIGN` y `InteractionDesignVersion` (`d.instruction`, `ORDER BY d.version`).

En `MissionMemoryService.deleteMission`, en la línea de la cascada que hoy dice `HAS_TASK|HAS_DECISION|HAS_OPPORTUNITY|HAS_REQUIREMENTS"` + `"|HAS_INTERACTION_DESIGN]->(n) "`, agregar `|HAS_JOINT_REVIEW`:

```java
                                + "|HAS_INTERACTION_DESIGN|HAS_JOINT_REVIEW]->(n) " +
```

- [ ] **Step 5: Run tests**

Run: `cd app && mvn -q test -Dtest=CompanyPolicyDefaultsTest && cd frontend && npm run lint && npm run build`
Expected: PASS; lint sin warnings nuevos; build OK.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/JointReviewMemoryService.java \
  app/src/main/java/com/aicompany/core/model/PolicyKey.java \
  app/src/main/java/com/aicompany/core/service/CompanyPolicyService.java \
  app/src/main/java/com/aicompany/core/service/RequirementsMemoryService.java \
  app/src/main/java/com/aicompany/core/service/InteractionDesignMemoryService.java \
  app/src/main/java/com/aicompany/core/service/MissionMemoryService.java \
  app/src/test/java/com/aicompany/core/service/CompanyPolicyDefaultsTest.java \
  app/frontend/src/api/types.ts app/frontend/src/pages/SettingsPage.tsx
git commit -m "Revisión conjunta: persistencia, policy JOINT_REVIEW_ENABLED y borrado en cascada"
```

---

### Task 3: Llamadas al modelo (CeoService)

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java` (junto a `proposeCorrectionStrategy`, línea ~1293)

**Interfaces:**
- Produces:
  - `JointReview.Detection detectContradictions(String agentId, String prompt, String agentPrompt, String model)` → operación `JOINT_REVIEW_DETECT`.
  - `JointReview.Resolution decideContradictions(String agentId, String prompt, String agentPrompt, String model)` → operación `JOINT_REVIEW_DECIDE`.

- [ ] **Step 1: Implement** (son dos delegaciones a `callStructured`, igual que `proposeCorrectionStrategy`; se cubren con los tests del writer en Task 4, que mockean `CeoService`)

```java
    /** Revisión conjunta (spec 2026-10-15): Neo lista las contradicciones entre HU y diseño. format sin tools. */
    public com.aicompany.core.agent.model.JointReview.Detection detectContradictions(String agentId, String prompt,
                                                                                  String agentPrompt, String model) {
        return callStructured("JOINT_REVIEW_DETECT", agentId, prompt, agentPrompt, model,
                com.aicompany.core.agent.model.JointReview.Detection.SCHEMA,
                com.aicompany.core.agent.model.JointReview.Detection.class);
    }

    /** Revisión conjunta (spec 2026-10-15): Aria decide cada contradicción. format sin tools. */
    public com.aicompany.core.agent.model.JointReview.Resolution decideContradictions(String agentId, String prompt,
                                                                                   String agentPrompt, String model) {
        return callStructured("JOINT_REVIEW_DECIDE", agentId, prompt, agentPrompt, model,
                com.aicompany.core.agent.model.JointReview.Resolution.SCHEMA,
                com.aicompany.core.agent.model.JointReview.Resolution.class);
    }
```

- [ ] **Step 2: Compile**

Run: `cd app && mvn -q compile`
Expected: sin errores.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/CeoService.java
git commit -m "Revisión conjunta: llamadas estructuradas de Neo (detectar) y Aria (decidir)"
```

---

### Task 4: JointReviewWriter (Neo detecta, Aria decide, con reintento)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/JointReviewWriter.java`
- Test: `app/src/test/java/com/aicompany/core/service/JointReviewWriterTest.java`

**Interfaces:**
- Consumes: Task 1 (`JointReview.*`, `JointReviewValidator`), Task 3 (`detectContradictions`, `decideContradictions`).
- Produces:
  - `JointReviewWriter.NEO = "engineering"`, `JointReviewWriter.MAX_ATTEMPTS = 3`.
  - `List<JointReview.Contradiction> detect(String missionId, RequirementsSpec spec, InteractionDesign design, List<String> founderAdjustments)` — lanza `IllegalStateException` con los motivos si se agotan los intentos.
  - `List<JointReview.Decision> decide(String missionId, RequirementsSpec spec, InteractionDesign design, List<JointReview.Contradiction> contradictions)` — idem.

- [ ] **Step 1: Write the failing test**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.JointReview;
import com.aicompany.core.agent.model.JointReview.*;
import com.aicompany.core.agent.validation.JointReviewValidatorTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Revisión conjunta (spec 2026-10-15): Neo detecta y Aria decide; Java valida y reintenta con la corrección. */
class JointReviewWriterTest {

    private final CeoService ceo = mock(CeoService.class);
    private final CompanyMemoryService company = mock(CompanyMemoryService.class);
    private final PromptMemoryService prompts = mock(PromptMemoryService.class);
    private final JointReviewWriter writer = new JointReviewWriter(ceo, company, prompts, "qwen3:8b");

    private static final Contradiction VALID = new Contradiction("CONTRA-01", List.of("HU-01"), List.of("REGLA-01"),
            "800x600", "x > 640", new Proposal(JointReview.DESIGN, "usar 800"));
    private static final Contradiction INVALID = new Contradiction("CONTRA-01", List.of("HU-09"), List.of("REGLA-01"),
            "800x600", "x > 640", new Proposal(JointReview.DESIGN, "usar 800"));

    {
        when(company.agentModel(anyString(), anyString())).thenReturn("nvidia:x/y");
        when(prompts.activePrompt(anyString())).thenReturn("");
    }

    @Test void neoGetsTheStoriesTheDesignAndTheFoundersAdjustmentsAsUntouchable() {
        when(ceo.detectContradictions(anyString(), anyString(), anyString(), anyString())).thenReturn(new Detection(List.of()));

        var found = writer.detect("M-1", JointReviewValidatorTest.SPEC, JointReviewValidatorTest.GAME,
                List.of("la paleta va a la derecha"));

        assertEquals(List.of(), found);
        verify(ceo).detectContradictions(eq("engineering"), argThat(p -> p.contains("HU-01") && p.contains("REGLA-01")
                && p.contains("la paleta va a la derecha") && p.contains("nunca")), anyString(), anyString());
    }

    @Test void anInvalidDetectionIsRetriedWithTheCorrection() {
        when(ceo.detectContradictions(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new Detection(List.of(INVALID)), new Detection(List.of(VALID)));

        assertEquals(List.of(VALID), writer.detect("M-1", JointReviewValidatorTest.SPEC, JointReviewValidatorTest.GAME, List.of()));
        verify(ceo).detectContradictions(anyString(), argThat(p -> p.contains("CORRECCIÓN DEL INTENTO ANTERIOR")
                && p.contains("HU-09")), anyString(), anyString());
    }

    @Test void threeInvalidDetectionsFailWithTheReasons() {
        when(ceo.detectContradictions(anyString(), anyString(), anyString(), anyString())).thenReturn(new Detection(List.of(INVALID)));

        var ex = assertThrows(IllegalStateException.class,
                () -> writer.detect("M-1", JointReviewValidatorTest.SPEC, JointReviewValidatorTest.GAME, List.of()));
        assertTrue(ex.getMessage().contains("HU-09"), ex.getMessage());
        verify(ceo, times(3)).detectContradictions(anyString(), anyString(), anyString(), anyString());
    }

    @Test void ariaDecidesWithNeosContradictionsInFront() {
        when(ceo.decideContradictions(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new Resolution(List.of(new Decision("CONTRA-01", "DESIGN", "usar 800", "las HU mandan"))));

        var decisions = writer.decide("M-1", JointReviewValidatorTest.SPEC, JointReviewValidatorTest.GAME, List.of(VALID));

        assertEquals("usar 800", decisions.get(0).change());
        verify(ceo).decideContradictions(eq(RequirementsWriter.ARIA), argThat(p -> p.contains("CONTRA-01")
                && p.contains("x > 640") && p.contains("usar 800")), anyString(), anyString());
    }

    @Test void aMissingDecisionIsRetried() {
        when(ceo.decideContradictions(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new Resolution(List.of()),
                        new Resolution(List.of(new Decision("CONTRA-01", "REQUIREMENTS", "aceptar 640", "mejor"))));

        assertEquals(1, writer.decide("M-1", JointReviewValidatorTest.SPEC, JointReviewValidatorTest.GAME, List.of(VALID)).size());
        verify(ceo).decideContradictions(anyString(), argThat(p -> p.contains("CORRECCIÓN DEL INTENTO ANTERIOR")),
                anyString(), anyString());
    }

    @Test void aModelErrorCountsAsAnAttempt() {
        when(ceo.detectContradictions(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("json roto")).thenReturn(new Detection(List.of()));

        assertEquals(List.of(), writer.detect("M-1", JointReviewValidatorTest.SPEC, JointReviewValidatorTest.GAME, List.of()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && mvn -q test -Dtest=JointReviewWriterTest`
Expected: COMPILATION ERROR (`JointReviewWriter` no existe).

- [ ] **Step 3: Implement**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.JointReview;
import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.agent.validation.JointReviewValidator;
import com.aicompany.core.agent.validation.RequirementsText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Revisión conjunta (spec 2026-10-15): Neo (líder técnico) detecta las contradicciones entre las HU de Aria y el diseño
 * de Kael y propone; Aria (PO, dueña del alcance) decide cada una. Java valida y reintenta con el motivo exacto, hasta
 * 3 intentos por paso. Las instrucciones de la revisión van fijas en Java (como CorrectionStrategist).
 */
@Service
public class JointReviewWriter {

    private static final Logger log = LoggerFactory.getLogger(JointReviewWriter.class);
    public static final String NEO = "engineering";
    static final int MAX_ATTEMPTS = 3;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CeoService ceo;
    private final CompanyMemoryService company;
    private final PromptMemoryService prompts;
    private final String defaultModel;

    public JointReviewWriter(CeoService ceo, CompanyMemoryService company, PromptMemoryService prompts,
                             @Value("${ollama.agent-model}") String defaultModel) {
        this.ceo = ceo;
        this.company = company;
        this.prompts = prompts;
        this.defaultModel = defaultModel;
    }

    public List<JointReview.Contradiction> detect(String missionId, RequirementsSpec spec, InteractionDesign design,
                                                  List<String> founderAdjustments) {
        var base = context(spec, design) + """

                Eres Neo, líder técnico del Development Group. Revisa si el DISEÑO contradice las HISTORIAS (o al revés).
                Es contradicción: algo que el diseño hace y las HU, sus escenarios o sus supuestos niegan (o al revés);
                valores incompatibles (tamaños, límites, velocidades, cantidades); una regla o pantalla que no puede
                cumplir un escenario (p. ej. el game over por un borde donde no está la paleta); y, en un juego, una
                regla de colisión o rebote sin condición de dirección (la pelota invierte cada frame y se queda pegada).
                NO es contradicción: estilo, nombres o preferencias.
                Para cada una: id CONTRA-01, CONTRA-02… en orden; requirementRefs (HU-NN/ESC-NN) y designRefs (ids del
                diseño: PANT-NN en apps; REGLA-NN o el id de una entidad en juegos); qué dice cada lado; y tu propuesta:
                side DESIGN (cambiar el diseño) o REQUIREMENTS (cambiar las HU) y el cambio concreto (con números).
                Máximo 10. Si no hay ninguna, responde {"contradictions": []}.
                """ + founder(founderAdjustments);
        return withRetries(missionId, "detect", base,
                prompt -> ceo.detectContradictions(NEO, prompt, prompts.activePrompt(NEO), model(NEO)),
                detection -> JointReviewValidator.validateDetection(detection, spec, design),
                JointReview.Detection::contradictions);
    }

    public List<JointReview.Decision> decide(String missionId, RequirementsSpec spec, InteractionDesign design,
                                             List<JointReview.Contradiction> contradictions) {
        var base = context(spec, design) + """

                Eres Aria, Product Owner: el alcance es tuyo. Neo (líder técnico) encontró estas contradicciones entre
                tus historias y el diseño de Kael, cada una con su propuesta:
                %s
                Decide CADA una exactamente una vez: side DESIGN (se cambia el diseño) o REQUIREMENTS (se cambian tus
                HU), el cambio concreto que se aplica (puedes aceptar el de Neo o escribir otro) y la razón. No puedes
                descartar una contradicción.
                """.formatted(JSON.writeValueAsString(contradictions));
        return withRetries(missionId, "decide", base,
                prompt -> ceo.decideContradictions(RequirementsWriter.ARIA, prompt,
                        prompts.activePrompt(RequirementsWriter.ARIA), model(RequirementsWriter.ARIA)),
                resolution -> JointReviewValidator.validateResolution(resolution, contradictions),
                JointReview.Resolution::decisions);
    }

    private <R, T> List<T> withRetries(String missionId, String step, String base, Function<String, R> call,
                                       Function<R, List<String>> validate, Function<R, List<T>> items) {
        String feedback = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            var prompt = feedback == null ? base : base + """

                    CORRECCIÓN DEL INTENTO ANTERIOR (Java rechazó la respuesta por esto; corrígelo todo):
                    %s
                    """.formatted(feedback);
            try {
                var result = call.apply(prompt);
                var errors = validate.apply(result);
                if (errors.isEmpty()) {
                    return items.apply(result);
                }
                feedback = "- " + String.join("\n- ", errors);
            } catch (RuntimeException ex) {
                feedback = "- " + (ex.getMessage() == null ? "Respuesta no procesable." : ex.getMessage());
            }
            log.warn("MISSION {} - joint review {} rejected attempt={} errors={}", missionId, step, attempt, feedback);
        }
        throw new IllegalStateException("La revisión conjunta (" + step + ") no produjo una respuesta válida después de "
                + MAX_ATTEMPTS + " intentos:\n" + feedback);
    }

    private static String context(RequirementsSpec spec, InteractionDesign design) {
        var stories = spec.incrementNumbers().stream().map(n -> RequirementsText.incrementBlock(spec, n))
                .collect(Collectors.joining("\n"));
        var flat = JSON.writeValueAsString(design.app() != null ? design.app() : design.game());
        return "HISTORIAS DE USUARIO DE ARIA (con supuestos y fuera de alcance):\n" + stories
                + "\nSupuestos: " + String.join("; ", spec.assumptionsOrEmpty())
                + "\nFuera de alcance: " + String.join("; ", spec.outOfScopeOrEmpty())
                + "\n\nDISEÑO DE KAEL (" + design.experience() + "):\n" + flat;
    }

    private static String founder(List<String> adjustments) {
        if (adjustments == null || adjustments.isEmpty()) {
            return "";
        }
        return "\nDECISIONES DEL FUNDADOR (nunca propongas un cambio que las deshaga):\n- "
                + String.join("\n- ", adjustments) + "\n";
    }

    private String model(String agentId) {
        return company.agentModel(agentId, defaultModel);
    }
}
```

Si `RequirementsText.incrementBlock` ya incluye supuestos y fuera de alcance, quitar las dos líneas `Supuestos`/`Fuera de alcance` para no duplicarlos (leer `RequirementsText.java:16-44` antes).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd app && mvn -q test -Dtest=JointReviewWriterTest`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/JointReviewWriter.java \
  app/src/test/java/com/aicompany/core/service/JointReviewWriterTest.java
git commit -m "Revisión conjunta: Neo detecta y Aria decide, con validación y reintento"
```

---

### Task 5: JointReviewService (cuándo aplica, ronda, persistencia, evento)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/JointReviewService.java`
- Test: `app/src/test/java/com/aicompany/core/service/JointReviewServiceTest.java`

**Interfaces:**
- Consumes: Task 2 (`JointReviewMemoryService`, `PolicyKey.JOINT_REVIEW_ENABLED`, `instructions`), Task 4 (`JointReviewWriter.detect/decide`).
- Produces:
  - `JointReviewService.PREFIX = "Decisión del líder técnico (Neo) con el PO (Aria):"`, `ROUNDS = 2`.
  - `record Outcome(String status, List<JointReview.Contradiction> contradictions, List<JointReview.Decision> decisions, String requirementsAdjustment, String designAdjustment, String detail)` con `boolean needsChanges()` (true si hay algún ajuste no nulo).
  - `Outcome review(String missionId, RequirementsSpec spec, int specVersion, InteractionDesign design, int designVersion, int round)`.
  - `public static final String ARIA_CHANGED_ADJUSTMENT = "Aria cambió las historias de usuario: rehaz el diseño sobre las HU actuales."` (el texto que hoy `MissionExecutor.awaitApprovalAfterAria` escribe como literal; Task 6 lo reemplaza por esta constante).
  - `static List<String> founderAdjustments(List<String> instructions)`: excluye las que empiezan por `PREFIX` y las que empiezan por `"Aria cambió las historias de usuario"`.
  - `String founderNote(List<Outcome> outcomes)`: el texto para el mensaje de aprobación (vacío si no hubo revisión).

- [ ] **Step 1: Write the failing test**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.JointReview;
import com.aicompany.core.agent.model.JointReview.*;
import com.aicompany.core.agent.validation.JointReviewValidatorTest;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.PolicyKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Revisión conjunta (spec 2026-10-15): cuándo aplica, qué ajusta y qué queda registrado. */
class JointReviewServiceTest {

    private final JointReviewWriter writer = mock(JointReviewWriter.class);
    private final JointReviewMemoryService memory = mock(JointReviewMemoryService.class);
    private final RequirementsMemoryService requirements = mock(RequirementsMemoryService.class);
    private final InteractionDesignMemoryService designs = mock(InteractionDesignMemoryService.class);
    private final CompanyPolicyService policies = mock(CompanyPolicyService.class);
    private final AgentAvailability availability = mock(AgentAvailability.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final JointReviewService service = new JointReviewService(writer, memory, requirements, designs, policies,
            availability, events);

    private static final Contradiction C1 = new Contradiction("CONTRA-01", List.of("HU-01"), List.of("REGLA-01"),
            "800x600", "x > 640", new Proposal(JointReview.DESIGN, "usar 800"));
    private static final Contradiction C2 = new Contradiction("CONTRA-02", List.of("HU-01"), List.of("pelota"),
            "velocidad constante", "+5 %", new Proposal(JointReview.DESIGN, "sin aceleración"));

    {
        when(policies.activeValue(PolicyKey.JOINT_REVIEW_ENABLED)).thenReturn(1.0);
        when(availability.isEnabled(anyString())).thenReturn(true);
        when(requirements.instructions("M-1")).thenReturn(List.of());
        when(designs.instructions("M-1")).thenReturn(List.of());
    }

    @Test void aCoherentDesignPassesWithoutCallingAria() {
        when(writer.detect(anyString(), any(), any(), anyList())).thenReturn(List.of());

        var outcome = service.review("M-1", JointReviewValidatorTest.SPEC, 2, JointReviewValidatorTest.GAME, 3, 1);

        assertEquals("COHERENT", outcome.status());
        assertFalse(outcome.needsChanges());
        verify(writer, never()).decide(anyString(), any(), any(), anyList());
        verify(memory).save(eq("M-1"), eq(1), eq(2), eq(3), eq("COHERENT"), anyString(), anyString(), anyString());
        verify(events).publish(eq("EMPRESA_JOINT_REVIEW_COMPLETED"), eq("M-1"), isNull(), eq("engineering"), anyMap());
    }

    @Test void ariasDecisionsAreGroupedIntoOneAdjustmentPerSide() {
        when(writer.detect(anyString(), any(), any(), anyList())).thenReturn(List.of(C1, C2));
        when(writer.decide(anyString(), any(), any(), anyList())).thenReturn(List.of(
                new Decision("CONTRA-01", "DESIGN", "usar 800", "las HU mandan"),
                new Decision("CONTRA-02", "REQUIREMENTS", "la pelota acelera 5 %", "más desafío")));

        var outcome = service.review("M-1", JointReviewValidatorTest.SPEC, 2, JointReviewValidatorTest.GAME, 3, 1);

        assertEquals("RESOLVED", outcome.status());
        assertTrue(outcome.needsChanges());
        assertTrue(outcome.designAdjustment().startsWith(JointReviewService.PREFIX));
        assertTrue(outcome.designAdjustment().contains("CONTRA-01") && outcome.designAdjustment().contains("usar 800"));
        assertFalse(outcome.designAdjustment().contains("CONTRA-02"));
        assertTrue(outcome.requirementsAdjustment().contains("CONTRA-02 (HU-01 ↔ pelota)"));
    }

    @Test void inTheSecondRoundWhatRemainsIsUnresolvedAndAriaIsNotCalled() {
        when(writer.detect(anyString(), any(), any(), anyList())).thenReturn(List.of(C1));

        var outcome = service.review("M-1", JointReviewValidatorTest.SPEC, 2, JointReviewValidatorTest.GAME, 3, 2);

        assertEquals("UNRESOLVED", outcome.status());
        assertFalse(outcome.needsChanges());
        verify(writer, never()).decide(anyString(), any(), any(), anyList());
    }

    @Test void aFailedReviewDoesNotBlockTheMission() {
        when(writer.detect(anyString(), any(), any(), anyList())).thenThrow(new IllegalStateException("sin respuesta válida"));

        var outcome = service.review("M-1", JointReviewValidatorTest.SPEC, 2, JointReviewValidatorTest.GAME, 3, 1);

        assertEquals("FAILED", outcome.status());
        assertFalse(outcome.needsChanges());
        assertTrue(outcome.detail().contains("sin respuesta válida"));
    }

    @Test void withThePolicyOffOrNeoOrAriaOffItIsSkipped() {
        when(policies.activeValue(PolicyKey.JOINT_REVIEW_ENABLED)).thenReturn(0.0);
        assertEquals("SKIPPED", service.review("M-1", JointReviewValidatorTest.SPEC, 2, JointReviewValidatorTest.GAME, 3, 1).status());

        when(policies.activeValue(PolicyKey.JOINT_REVIEW_ENABLED)).thenReturn(1.0);
        when(availability.isEnabled("engineering")).thenReturn(false);
        var neoOff = service.review("M-1", JointReviewValidatorTest.SPEC, 2, JointReviewValidatorTest.GAME, 3, 1);
        assertEquals("SKIPPED", neoOff.status());
        assertTrue(neoOff.detail().contains("Neo"));
        verifyNoInteractions(writer);
    }

    @Test void theFoundersAdjustmentsReachNeoButNotTheReviewsOwn() {
        when(requirements.instructions("M-1")).thenReturn(List.of("sin sonido", JointReviewService.PREFIX + " x"));
        when(designs.instructions("M-1")).thenReturn(List.of("Aria cambió las historias de usuario: rehaz el diseño",
                "la paleta a la derecha"));
        when(writer.detect(anyString(), any(), any(), anyList())).thenReturn(List.of());

        service.review("M-1", JointReviewValidatorTest.SPEC, 2, JointReviewValidatorTest.GAME, 3, 1);

        verify(writer).detect(anyString(), any(), any(), eq(List.of("sin sonido", "la paleta a la derecha")));
    }

    @Test void theFounderNoteListsWhatWasResolvedAndWhatRemains() {
        var resolved = new JointReviewService.Outcome("RESOLVED", List.of(C1),
                List.of(new Decision("CONTRA-01", "DESIGN", "usar 800", "las HU mandan")), null, "x", "");
        var unresolved = new JointReviewService.Outcome("UNRESOLVED", List.of(C2), List.of(), null, null, "");

        var note = service.founderNote(List.of(resolved, unresolved));

        assertTrue(note.contains("Neo y Aria resolvieron 1 contradicción"), note);
        assertTrue(note.contains("CONTRA-01 (HU-01 ↔ REGLA-01) → diseño: usar 800"), note);
        assertTrue(note.contains("⚠ Sin resolver: CONTRA-02"), note);
        assertEquals("", service.founderNote(List.of()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && mvn -q test -Dtest=JointReviewServiceTest`
Expected: COMPILATION ERROR (`JointReviewService` no existe).

- [ ] **Step 3: Implement**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.JointReview;
import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.PolicyKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Revisión conjunta (spec 2026-10-15, decisión del fundador: "cuando se generan contradicciones el líder técnico decide
 * junto con el PO"). Java decide si aplica, en qué ronda se está y qué ajuste va a cada lado; Neo y Aria solo detectan
 * y deciden. Nunca deja la misión FAILED: si falla, queda registrada y la misión sigue a la aprobación del fundador.
 */
@Service
public class JointReviewService {

    private static final Logger log = LoggerFactory.getLogger(JointReviewService.class);
    public static final String PREFIX = "Decisión del líder técnico (Neo) con el PO (Aria):";
    public static final int ROUNDS = 2;
    public static final String ARIA_CHANGED_ADJUSTMENT =
            "Aria cambió las historias de usuario: rehaz el diseño sobre las HU actuales.";
    private static final String ARIA_CHANGED = "Aria cambió las historias de usuario";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Outcome(String status, List<JointReview.Contradiction> contradictions,
                          List<JointReview.Decision> decisions, String requirementsAdjustment, String designAdjustment,
                          String detail) {
        public boolean needsChanges() {
            return requirementsAdjustment != null || designAdjustment != null;
        }
    }

    private final JointReviewWriter writer;
    private final JointReviewMemoryService memory;
    private final RequirementsMemoryService requirements;
    private final InteractionDesignMemoryService designs;
    private final CompanyPolicyService policies;
    private final AgentAvailability availability;
    private final CompanyEventPublisher events;

    public JointReviewService(JointReviewWriter writer, JointReviewMemoryService memory,
                              RequirementsMemoryService requirements, InteractionDesignMemoryService designs,
                              CompanyPolicyService policies, AgentAvailability availability, CompanyEventPublisher events) {
        this.writer = writer;
        this.memory = memory;
        this.requirements = requirements;
        this.designs = designs;
        this.policies = policies;
        this.availability = availability;
        this.events = events;
    }

    public boolean enabled() {
        return policies.activeValue(PolicyKey.JOINT_REVIEW_ENABLED) >= 1.0;
    }

    public Outcome review(String missionId, RequirementsSpec spec, int specVersion, InteractionDesign design,
                          int designVersion, int round) {
        Outcome outcome;
        if (!enabled()) {
            outcome = new Outcome("SKIPPED", List.of(), List.of(), null, null, "La revisión conjunta está apagada (policy).");
        } else if (!availability.isEnabled(JointReviewWriter.NEO)) {
            outcome = new Outcome("SKIPPED", List.of(), List.of(), null, null, "Neo está apagado: no hubo revisión conjunta.");
        } else if (!availability.isEnabled(RequirementsWriter.ARIA)) {
            outcome = new Outcome("SKIPPED", List.of(), List.of(), null, null, "Aria está apagada: no hubo revisión conjunta.");
        } else {
            outcome = run(missionId, spec, design, round);
        }
        memory.save(missionId, round, specVersion, designVersion, outcome.status(),
                JSON.writeValueAsString(outcome.contradictions()), JSON.writeValueAsString(outcome.decisions()),
                outcome.detail());
        log.info("MISSION {} - joint review round={} status={} contradictions={}", missionId, round, outcome.status(),
                outcome.contradictions().size());
        events.publish("EMPRESA_JOINT_REVIEW_COMPLETED", missionId, null, JointReviewWriter.NEO, Map.of(
                "round", round, "status", outcome.status(), "contradictions", outcome.contradictions().size(),
                "resolvedBy", Map.of(
                        "requirements", count(outcome.decisions(), JointReview.REQUIREMENTS),
                        "design", count(outcome.decisions(), JointReview.DESIGN))));
        return outcome;
    }

    private Outcome run(String missionId, RequirementsSpec spec, InteractionDesign design, int round) {
        try {
            var founder = founderAdjustments(concat(requirements.instructions(missionId), designs.instructions(missionId)));
            var contradictions = writer.detect(missionId, spec, design, founder);
            if (contradictions.isEmpty()) {
                return new Outcome("COHERENT", List.of(), List.of(), null, null, "");
            }
            if (round >= ROUNDS) {
                return new Outcome("UNRESOLVED", contradictions, List.of(), null, null, "");
            }
            var decisions = writer.decide(missionId, spec, design, contradictions);
            return new Outcome("RESOLVED", contradictions, decisions,
                    adjustment(contradictions, decisions, JointReview.REQUIREMENTS),
                    adjustment(contradictions, decisions, JointReview.DESIGN), "");
        } catch (RuntimeException ex) {
            log.warn("MISSION {} - joint review failed: {}", missionId, ex.getMessage());
            return new Outcome("FAILED", List.of(), List.of(), null, null,
                    "La revisión conjunta no se pudo completar: " + ex.getMessage());
        }
    }

    static List<String> founderAdjustments(List<String> instructions) {
        return instructions.stream().filter(i -> i != null && !i.isBlank())
                .filter(i -> !i.startsWith(PREFIX) && !i.startsWith(ARIA_CHANGED)).toList();
    }

    private static String adjustment(List<JointReview.Contradiction> contradictions, List<JointReview.Decision> decisions,
                                     String side) {
        var lines = decisions.stream().filter(d -> side.equals(d.side()))
                .map(d -> "- " + d.id() + " (" + refs(contradictions, d.id()) + "): " + d.change()).toList();
        return lines.isEmpty() ? null : PREFIX + "\n" + String.join("\n", lines);
    }

    private static String refs(List<JointReview.Contradiction> contradictions, String id) {
        return contradictions.stream().filter(c -> c.id().equals(id)).findFirst()
                .map(c -> String.join(", ", c.requirementRefs()) + " ↔ " + String.join(", ", c.designRefs()))
                .orElse("");
    }

    public String founderNote(List<Outcome> outcomes) {
        var parts = new ArrayList<String>();
        var resolved = outcomes.stream().filter(o -> "RESOLVED".equals(o.status())).toList();
        var decisions = resolved.stream().flatMap(o -> o.decisions().stream().map(d -> "%s (%s) → %s: %s".formatted(
                d.id(), refs(o.contradictions(), d.id()), JointReview.DESIGN.equals(d.side()) ? "diseño" : "HU", d.change())))
                .toList();
        if (!decisions.isEmpty()) {
            parts.add("Neo y Aria resolvieron " + decisions.size() + (decisions.size() == 1 ? " contradicción: "
                    : " contradicciones: ") + String.join("; ", decisions) + ".");
        }
        outcomes.stream().filter(o -> "UNRESOLVED".equals(o.status())).forEach(o -> parts.add("⚠ Sin resolver: "
                + o.contradictions().stream().map(c -> c.id() + " (" + c.requirementSays() + " ↔ " + c.designSays() + ")")
                .collect(Collectors.joining("; ")) + "."));
        outcomes.stream().filter(o -> "FAILED".equals(o.status()) || "SKIPPED".equals(o.status()))
                .filter(o -> !o.detail().isBlank()).forEach(o -> parts.add("⚠ " + o.detail()));
        if (parts.isEmpty() && outcomes.stream().anyMatch(o -> "COHERENT".equals(o.status()))) {
            parts.add("Neo y Aria revisaron HU y diseño: sin contradicciones.");
        }
        return String.join(" ", parts);
    }

    private static int count(List<JointReview.Decision> decisions, String side) {
        return (int) decisions.stream().filter(d -> side.equals(d.side())).count();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        var all = new ArrayList<String>(a);
        all.addAll(b);
        return all;
    }
}
```

Nota del Step 1: en `theFounderNoteListsWhatWasResolvedAndWhatRemains` el `refs` usa los `requirementRefs`/`designRefs` de la contradicción, por eso el texto esperado es `CONTRA-01 (HU-01 ↔ REGLA-01) → diseño: usar 800`. Con una sola decisión, «Neo y Aria resolvieron 1 contradicción: …».

- [ ] **Step 4: Run test to verify it passes**

Run: `cd app && mvn -q test -Dtest=JointReviewServiceTest`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/JointReviewService.java \
  app/src/test/java/com/aicompany/core/service/JointReviewServiceTest.java
git commit -m "Revisión conjunta: cuándo aplica, ajustes por lado, segunda ronda y registro"
```

---

### Task 6: Integración en MissionExecutor y recuperación

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java` (`executeTeamMission` ~493, `awaitApprovalAfterAria` ~277, `redesign` ~969, `resumeInternal` ~715, `awaitApproval` ~533)
- Modify: `app/src/main/java/com/aicompany/core/recovery/ResumePoint.java` (Stage `JOINT_REVIEW`)
- Modify: `app/src/main/java/com/aicompany/core/recovery/MissionSnapshot.java` (campo `jointReviewPending`)
- Modify: `app/src/main/java/com/aicompany/core/recovery/MissionRecoveryService.java:~442` (calcularlo)
- Test: `app/src/test/java/com/aicompany/core/recovery/ResumePointTest.java`, `app/src/test/java/com/aicompany/core/service/MissionExecutorTeamTest.java` (o el test que hoy cubre el flujo Aria → Kael → `AWAITING_SPEC_APPROVAL`; buscarlo con `grep -ln "Requisitos y diseño listos" app/src/test`)

**Interfaces:**
- Consumes: Task 5 (`JointReviewService.review`, `Outcome`, `founderNote`, `ROUNDS`, `enabled()`), Task 2 (`JointReviewMemoryService.closedFor`).
- Produces:
  - `MissionExecutor.setJointReview(JointReviewService)` (setter `@Autowired(required = false)`, como los demás opcionales).
  - `private Reviewed reviewed(String missionId, RequirementsWriter.Written written, InteractionDesignWriter.Written designed)` con `record Reviewed(RequirementsWriter.Written written, InteractionDesignWriter.Written designed, String note)`.
  - `ResumePoint.Stage.JOINT_REVIEW`; `MissionSnapshot` con el componente final `boolean jointReviewPending` (los constructores cortos pasan `false`).

**Lógica de `reviewed`** (Java, en `MissionExecutor`):

```java
    /** Revisión conjunta (spec 2026-10-15): antes de que el fundador vea HU y diseño, Neo y Aria resuelven contradicciones. */
    record Reviewed(RequirementsWriter.Written written, InteractionDesignWriter.Written designed, String note) {
    }

    private Reviewed reviewed(String missionId, RequirementsWriter.Written written, InteractionDesignWriter.Written designed) {
        if (jointReview == null || designed == null) {
            return new Reviewed(written, designed, "");
        }
        var outcomes = new java.util.ArrayList<JointReviewService.Outcome>();
        for (int round = 1; round <= JointReviewService.ROUNDS; round++) {
            advanceMission(missionId, MissionStatus.PLANNING, 7, "Revisión conjunta (Neo + Aria)",
                    "Neo y Aria revisan que las HU y el diseño no se contradigan (ronda " + round + ").");
            var outcome = jointReview.review(missionId, written.spec(), written.version(), designed.design(),
                    designed.version(), round);
            outcomes.add(outcome);
            if (!outcome.needsChanges()) {
                break;
            }
            if (outcome.requirementsAdjustment() != null) {
                var previousDesign = designed.design();
                written = rewriteRequirements(missionId, memory.instructionOf(missionId).orElse(""),
                        outcome.requirementsAdjustment());
                events.publish("EMPRESA_REQUIREMENTS_ADJUSTED", missionId, null, JointReviewWriter.NEO,
                        Map.of("version", written.version(), "instruction", outcome.requirementsAdjustment()));
                if (designMemory != null) {
                    designMemory.supersedeDraft(missionId);
                }
                publishReady(missionId, written);
                var designAdjustment = JointReviewService.ARIA_CHANGED_ADJUSTMENT
                        + (outcome.designAdjustment() == null ? "" : "\n" + outcome.designAdjustment());
                designed = design(missionId, written, java.util.Optional.of(previousDesign), designAdjustment);
                if (designed == null) {
                    break; // Aria cambió la experiencia a NONE: ya no hay diseño que revisar.
                }
            } else {
                designed = designWriter.write(missionId, written.spec(), written.version(),
                        java.util.Optional.of(designed.design()), outcome.designAdjustment());
                events.publish("EMPRESA_INTERACTION_DESIGN_ADJUSTED", missionId, null, JointReviewWriter.NEO,
                        Map.of("version", designed.version(), "instruction", outcome.designAdjustment()));
            }
        }
        return new Reviewed(written, designed, jointReview.founderNote(outcomes));
    }
```

En `MissionExecutor.awaitApprovalAfterAria`, usar `JointReviewService.ARIA_CHANGED_ADJUSTMENT` en lugar del literal (mismo texto), para que la revisión lo reconozca como ajuste automático y no como decisión del fundador.

**Dónde se llama** (siempre que haya un diseño nuevo antes de esperar al fundador o aprobar por policy):

1. `executeTeamMission`, tras `var designed = design(...)`:

```java
            var reviewed = reviewed(missionId, written, designed);
            written = reviewed.written();
            designed = reviewed.designed();
            if (companyPolicyService.activeValue(PolicyKey.SPEC_APPROVAL_REQUIRED) >= 1.0) {
                awaitApproval(missionId, written, designed, reviewed.note());
                return;
            }
```

(las variables `written`/`designed` dejan de ser efectivamente finales: declararlas sin `var` final implícito o usar `var` normal; si alguna lambda las captura, copiar a una local final antes). El camino de policy en 0 queda igual pero con los valores revisados (Review Focus 5).

2. `awaitApprovalAfterAria`: reemplazar el `awaitApproval(missionId, written, design(...))` por

```java
        var designed = design(missionId, written, previousDesign, JointReviewService.ARIA_CHANGED_ADJUSTMENT);
        var reviewed = reviewed(missionId, written, designed);
        awaitApproval(missionId, reviewed.written(), reviewed.designed(), reviewed.note());
```

3. `redesign`: tras `events.publish("EMPRESA_INTERACTION_DESIGN_ADJUSTED", …)`, reemplazar `awaitApproval(missionId, written, designed)` por

```java
        var reviewed = reviewed(missionId, written, designed);
        awaitApproval(missionId, reviewed.written(), reviewed.designed(), reviewed.note());
```

4. `resumeInternal`: el caso `DESIGN` pasa por `reviewed`; caso nuevo:

```java
            case JOINT_REVIEW -> {
                var written = latestRequirements(missionId);
                var reviewed = reviewed(missionId, written, draftDesignFor(missionId, written.version()));
                awaitApproval(missionId, reviewed.written(), reviewed.designed(), reviewed.note());
            }
```

5. `awaitApproval`: sumar el parámetro `String reviewNote` (sobrecarga de 3 parámetros que llama con `""`, para no tocar los demás llamadores) y agregar ` " " + reviewNote` al final del texto cuando no esté vacío.

**Recuperación:** en `ResumePoint`, dentro de `if ("DRAFT".equals(s.latestRequirementsStatus()) && s.designNeeded())`, después del chequeo de `!s.designForLatestSpec()`:

```java
                if (s.jointReviewPending()) {
                    return new ResumePoint(Stage.JOINT_REVIEW, s.round(), kind, increment);
                }
```

En `MissionRecoveryService`, donde hoy calcula `designForLatest`: si `designForLatest` y la policy está encendida (inyectar `JointReviewService` como setter opcional y usar `enabled()`), `jointReviewPending = reviews.closedFor(missionId, latestSpecVersion, latestDesignVersion).isEmpty()` (inyectar `JointReviewMemoryService` como setter opcional; sin él, `false`). Pasarlo como último argumento de `new MissionSnapshot(...)`.

- [ ] **Step 1: Write the failing tests**

En `ResumePointTest` (seguir el estilo de los casos `DESIGN`/`DESIGN_ADJUSTMENT` que ya existen):

```java
    // Revisión conjunta (spec 2026-10-15): diseño DRAFT del spec DRAFT sin revisión cerrada → se retoma la revisión.
    @Test
    void aDraftPairWithoutAClosedReviewResumesInTheJointReview() {
        var s = new MissionSnapshot("M-1", "TEAM-DEVELOPMENT", 0, 1, false, "DRAFT", false, null, false, List.of(), null,
                true, true, null, true);
        assertEquals(ResumePoint.Stage.JOINT_REVIEW, ResumePoint.of(s).stage());
    }

    @Test
    void aDraftPairAlreadyReviewedWaitsForTheFounder() {
        var s = new MissionSnapshot("M-1", "TEAM-DEVELOPMENT", 0, 1, false, "DRAFT", false, null, false, List.of(), null,
                true, true, null, false);
        assertEquals(ResumePoint.Stage.AWAIT_APPROVAL, ResumePoint.of(s).stage());
    }
```

En el test del flujo de equipo (el que hoy verifica `"Requisitos y diseño listos (Aria + Kael)"`), con `JointReviewService` mockeado vía `executor.setJointReview(review)`:

```java
    // Revisión conjunta (spec 2026-10-15): un cambio de diseño decidido por Neo y Aria rehace a Kael y se revisa de nuevo.
    @Test
    void aDesignDecisionRedesignsAndReviewsTheNewPairBeforeTheFounder() {
        // Reusar el arranque del test existente de Aria → Kael → AWAITING_SPEC_APPROVAL (mismos stubs de
        // requirementsWriter/designWriter/políticas), y además:
        var review = mock(JointReviewService.class);
        executor.setJointReview(review);
        when(review.review(eq("M-1"), any(), anyInt(), any(), eq(1), eq(1))).thenReturn(new JointReviewService.Outcome(
                "RESOLVED", List.of(), List.of(), null, JointReviewService.PREFIX + "\n- CONTRA-01 (HU-01 ↔ REGLA-01): usar 800", ""));
        when(review.review(eq("M-1"), any(), anyInt(), any(), eq(2), eq(2))).thenReturn(new JointReviewService.Outcome(
                "COHERENT", List.of(), List.of(), null, null, ""));
        when(review.founderNote(anyList())).thenReturn("Neo y Aria resolvieron 1 contradicción: CONTRA-01.");
        // designWriter devuelve la versión 1 en el primer write y la 2 en el segundo (el de la revisión).

        // …ejecutar la misión como en el test existente…

        verify(designWriter).write(eq("M-1"), any(), anyInt(), any(), startsWith(JointReviewService.PREFIX));
        verify(review).review(eq("M-1"), any(), anyInt(), any(), eq(2), eq(2));
        verify(memory).updateMission(eq("M-1"), eq(MissionStatus.AWAITING_SPEC_APPROVAL), anyInt(), anyString(),
                contains("Neo y Aria resolvieron 1 contradicción"));
    }

    @Test
    void withAutoApprovalTheReviewRunsBeforeApproving() {
        // Igual que el test existente con SPEC_APPROVAL_REQUIRED = 0, más el mock de review devolviendo COHERENT:
        // verify(review).review(…, eq(1)) ocurre antes que requirementsMemory.approveWithDesign(…) (InOrder).
    }
```

Completar los dos tests copiando los stubs del test vecino existente (el implementador lo tiene delante): el plan no los repite porque dependen del fixture actual de ese archivo; lo exigible es lo que verifican las líneas `verify` de arriba.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn -q test -Dtest='ResumePointTest,MissionExecutorTeamTest'`
Expected: COMPILATION ERROR (`JOINT_REVIEW`, el constructor de 15 argumentos y `setJointReview` no existen).

- [ ] **Step 3: Implement** lo descrito arriba (ResumePoint, MissionSnapshot con el campo final y los constructores cortos pasando `false`, MissionRecoveryService, MissionExecutor).

- [ ] **Step 4: Run the whole suite**

Run: `cd app && mvn test`
Expected: BUILD SUCCESS (la suite completa; hoy son 1476 tests más los nuevos).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/MissionExecutor.java \
  app/src/main/java/com/aicompany/core/recovery/ResumePoint.java \
  app/src/main/java/com/aicompany/core/recovery/MissionSnapshot.java \
  app/src/main/java/com/aicompany/core/recovery/MissionRecoveryService.java \
  app/src/test/java/com/aicompany/core/recovery/ResumePointTest.java \
  app/src/test/java/com/aicompany/core/service/MissionExecutorTeamTest.java
git commit -m "Revisión conjunta en el flujo: antes de la aprobación, tras cada ajuste y al retomar"
```

---

### Task 7: Consulta (API, chat, Command Center) y docs

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/JointReviewView.java`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionService.java` (método `jointReviews`), `app/src/main/java/com/aicompany/core/controller/MissionController.java` (GET), `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java` (regla `SHOW_REVIEW`)
- Modify: `app/frontend/src/api/types.ts`, `app/frontend/src/api/client.ts`, `app/frontend/src/pages/MissionDetailPage.tsx`
- Modify: `docs/ARCHITECTURE.md` (sección «Misiones por equipo», tras el párrafo de Kael), `docs/EVENTS.md`, `docs/HISTORY.md`, `docs/FALTANTES.md`, `CLAUDE.md` (línea del Development Group en «Arquitectura en grande»)
- Test: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: Task 2 (`JointReviewMemoryService.all`).
- Produces:
  - `JointReviewView(int number, int round, int specVersion, int designVersion, String status, List<JointReview.Contradiction> contradictions, List<JointReview.Decision> decisions, String detail, String at)`.
  - `List<JointReviewView> MissionService.jointReviews(String missionId)` (vacía sin el memory service: setter opcional).
  - `GET /api/company/missions/{missionId}/joint-review` → `List<JointReviewView>`.

- [ ] **Step 1: Write the failing chat tests**

```java
    // Revisión conjunta (spec 2026-10-15): el chat responde la revisión en Java, sin modelo.
    @Test
    void theJointReviewIsAnsweredInJava() {
        when(missionService.jointReviews("MISSION-PONG-2")).thenReturn(List.of(new com.aicompany.core.model.JointReviewView(
                1, 1, 1, 1, "RESOLVED",
                List.of(new com.aicompany.core.agent.model.JointReview.Contradiction("CONTRA-01", List.of("HU-02"),
                        List.of("REGLA-07"), "800x600", "x > 640",
                        new com.aicompany.core.agent.model.JointReview.Proposal("DESIGN", "usar 800")))),
                List.of(new com.aicompany.core.agent.model.JointReview.Decision("CONTRA-01", "DESIGN", "usar 800", "las HU mandan")),
                "", "2026-10-15T20:00:00Z")));

        var response = router.route("muéstrame la revisión de MISSION-PONG-2");

        assertTrue(response.contains("Ronda 1: RESOLVED"), response);
        assertTrue(response.contains("CONTRA-01 (HU-02 ↔ REGLA-07): 800x600 ↔ x > 640 → diseño: usar 800 (las HU mandan)"), response);
        verifyNoInteractions(ceoService);
    }

    @Test
    void contradictionsQuestionAlsoShowsTheReviewAndSaysWhenThereIsNone() {
        when(missionService.jointReviews("MISSION-PONG-2")).thenReturn(List.of());

        var response = router.route("¿qué contradicciones resolvieron en MISSION-PONG-2?");

        assertTrue(response.contains("MISSION-PONG-2 no tiene revisión conjunta"), response);
        verifyNoInteractions(ceoService);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && mvn -q test -Dtest=ChatIntentRouterTest`
Expected: COMPILATION ERROR (`JointReviewView`, `jointReviews` no existen).

- [ ] **Step 3: Implement backend**

```java
package com.aicompany.core.model;

import com.aicompany.core.agent.model.JointReview;

import java.util.List;

/** Revisión conjunta (spec 2026-10-15): una ronda de Neo + Aria tal como la ve el fundador. */
public record JointReviewView(int number, int round, int specVersion, int designVersion, String status,
                              List<JointReview.Contradiction> contradictions, List<JointReview.Decision> decisions,
                              String detail, String at) {
}
```

En `MissionService` (setter opcional `setJointReviews(JointReviewMemoryService)`, como `setAgentAvailability`):

```java
    /** Revisión conjunta (spec 2026-10-15): las rondas de la misión, en orden. */
    public List<JointReviewView> jointReviews(String missionId) {
        if (jointReviews == null) {
            return List.of();
        }
        return jointReviews.all(missionId).stream().map(r -> new JointReviewView(r.number(), r.round(), r.specVersion(),
                r.designVersion(), r.status(),
                List.of(JSON.readValue(r.contradictionsJson(), com.aicompany.core.agent.model.JointReview.Contradiction[].class)),
                List.of(JSON.readValue(r.decisionsJson(), com.aicompany.core.agent.model.JointReview.Decision[].class)),
                r.detail(), r.at())).toList();
    }
```

(`JSON` es el `JsonMapper` estático que `MissionService` ya usa en `interactionDesign`.)

En `MissionController`, junto a `interaction-design`:

```java
    /** Revisión conjunta (spec 2026-10-15): rondas de Neo + Aria (lista vacía = sin revisión). */
    @GetMapping("/{missionId}/joint-review")
    public List<JointReviewView> jointReview(@PathVariable String missionId) {
        return missionService.jointReviews(missionId);
    }
```

En `ChatIntentRouter`, junto a `SHOW_DESIGN` (el texto llega normalizado: sin tildes y en minúsculas):

```java
    /** Revisión conjunta (spec 2026-10-15): "muéstrame la revisión de MISSION-X", "¿qué contradicciones … MISSION-X?". */
    private static final Pattern SHOW_REVIEW = Pattern.compile(
            "(?i)(?:\\b(?:muestrame|mostrame|ver|dame|cual es)\\s+(?:la\\s+)?revision(?:\\s+conjunta)?\\s+de\\s+"
                    + "|\\bcontradicciones\\b.*?)(" + MISSION_REF + ")");
```

Sumarlo a `isRequirementsCommand` (`|| SHOW_REVIEW.matcher(normalized).find()`) y en `handleRequirements`, antes de `showDesign`:

```java
            var showReview = SHOW_REVIEW.matcher(normalized);
            if (showReview.find()) {
                var missionId = showReview.group(1).toUpperCase(Locale.ROOT);
                var reviews = missionService.jointReviews(missionId);
                if (reviews.isEmpty()) {
                    return missionId + " no tiene revisión conjunta de Neo y Aria.";
                }
                return "Revisión conjunta de " + missionId + ":\n" + reviews.stream().map(ChatIntentRouter::formatReview)
                        .collect(Collectors.joining("\n"));
            }
```

```java
    private static String formatReview(JointReviewView r) {
        var lines = new ArrayList<String>();
        lines.add("Ronda " + r.round() + ": " + r.status() + " (HU v" + r.specVersion() + ", diseño v" + r.designVersion() + ")"
                + (r.detail() == null || r.detail().isBlank() ? "" : " — " + r.detail()));
        for (var c : r.contradictions()) {
            var decision = r.decisions().stream().filter(d -> d.id().equals(c.id())).findFirst();
            lines.add("- " + c.id() + " (" + String.join(", ", c.requirementRefs()) + " ↔ " + String.join(", ", c.designRefs())
                    + "): " + c.requirementSays() + " ↔ " + c.designSays()
                    + decision.map(d -> " → " + ("DESIGN".equals(d.side()) ? "diseño" : "HU") + ": " + d.change()
                    + " (" + d.reason() + ")").orElse(" → sin resolver"));
        }
        return String.join("\n", lines);
    }
```

- [ ] **Step 4: Implement frontend**

`types.ts`:

```ts
// Revisión conjunta (spec 2026-10-15): JointReviewView.
export interface JointReviewContradiction {
  id: string
  requirementRefs: string[]
  designRefs: string[]
  requirementSays: string
  designSays: string
  proposal: { side: 'DESIGN' | 'REQUIREMENTS'; change: string } | null
}
export interface JointReviewDecision {
  id: string
  side: 'DESIGN' | 'REQUIREMENTS'
  change: string
  reason: string
}
export interface JointReviewView {
  number: number
  round: number
  specVersion: number
  designVersion: number
  status: 'COHERENT' | 'RESOLVED' | 'UNRESOLVED' | 'FAILED' | 'SKIPPED'
  contradictions: JointReviewContradiction[]
  decisions: JointReviewDecision[]
  detail: string
  at: string
}
```

`client.ts`, junto a `interactionDesign`:

```ts
  jointReview: (missionId: string) => request<JointReviewView[]>(`/api/company/missions/${missionId}/joint-review`),
```

(agregar `JointReviewView` al `import type { … }` del archivo).

`MissionDetailPage.tsx`, componente nuevo y montado justo después de `<MissionDesign … />` (`{mission.teamId && <MissionJointReview missionId={mission.missionId} />}`):

```tsx
/** Revisión conjunta (spec 2026-10-15): Neo detecta contradicciones entre HU y diseño, Aria decide, Java aplica. */
function MissionJointReview({ missionId }: { missionId: string }) {
  const query = useQuery({
    queryKey: ['jointReview', missionId],
    queryFn: () => api.jointReview(missionId),
    refetchInterval: 10_000,
  })
  const reviews = query.data ?? []
  if (reviews.length === 0) return null
  return (
    <section>
      <h2>Revisión conjunta (Neo + Aria)</h2>
      {reviews.map((r) => (
        <div key={r.number}>
          <p>
            <strong>Ronda {r.round}</strong>: {r.status} (HU v{r.specVersion}, diseño v{r.designVersion})
            {r.detail && <span className="hint"> — {r.detail}</span>}
          </p>
          <ul>
            {r.contradictions.map((c) => {
              const d = r.decisions.find((x) => x.id === c.id)
              return (
                <li key={c.id}>
                  <code>{c.id}</code> ({c.requirementRefs.join(', ')} ↔ {c.designRefs.join(', ')}): {c.requirementSays} ↔{' '}
                  {c.designSays}
                  {d ? (
                    <>
                      {' '}
                      → <strong>{d.side === 'DESIGN' ? 'diseño' : 'HU'}</strong>: {d.change}{' '}
                      <span className="hint">({d.reason})</span>
                    </>
                  ) : (
                    <> → ⚠ sin resolver</>
                  )}
                </li>
              )
            })}
          </ul>
        </div>
      ))}
    </section>
  )
}
```

- [ ] **Step 5: Docs**

- `docs/ARCHITECTURE.md`, después del párrafo «**Kael: diseño de interacción y de juego**»: un párrafo «**Revisión conjunta (Neo + Aria)**» con: cuándo corre (tras cada diseño nuevo en APP/GAME, antes de `AWAITING_SPEC_APPROVAL` o de la aprobación por policy, también tras ajustes del fundador), `JointReviewWriter` (Neo detecta con `JOINT_REVIEW_DETECT`, Aria decide con `JOINT_REVIEW_DECIDE`, 3 intentos con `JointReviewValidator`), `JointReviewService` (grupos por lado con `PREFIX`, `ROUNDS = 2`, la ronda 2 solo detecta → `UNRESOLVED`, `FAILED`/`SKIPPED` nunca bloquean, los ajustes del fundador van al prompt de Neo como intocables), `MissionExecutor.reviewed` (cambios a HU → Aria y luego Kael; solo diseño → Kael), `(:JointReview)` y la cascada, `ResumePoint.Stage.JOINT_REVIEW`, policy `JOINT_REVIEW_ENABLED`, `GET …/joint-review`, sección en el detalle y chat.
- `docs/EVENTS.md`: `EMPRESA_JOINT_REVIEW_COMPLETED` (`JointReviewService`, `agentId="engineering"`): `data: {round, status, contradictions, resolvedBy: {requirements, design}}`.
- `docs/HISTORY.md`: sección «Revisión conjunta Neo + Aria (2026-10-15)» con la decisión del fundador (frase literal), las 5 contradicciones del Pong que la motivaron y la elección de la revisión conjunta.
- `docs/FALTANTES.md`: «[ ] Prueba en vivo de la revisión conjunta: un juego nuevo (no el Pong) que llegue coherente sin intervención y termine `VERIFIED` (tercera condición para dar a Kael por ajustado)».
- `CLAUDE.md`, en la línea del Development Group: «…Kael diseña la interacción o la mecánica; **Neo y Aria resuelven las contradicciones entre ambos (revisión conjunta)**; el fundador aprueba ambos juntos…».

- [ ] **Step 6: Run everything**

Run: `cd app && mvn test && cd frontend && npm run lint && npm run build`
Expected: BUILD SUCCESS; lint sin warnings nuevos (hoy hay 1 preexistente en `SettingsPage.tsx`); build OK.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/JointReviewView.java \
  app/src/main/java/com/aicompany/core/service/MissionService.java \
  app/src/main/java/com/aicompany/core/controller/MissionController.java \
  app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java \
  app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java \
  app/frontend/src/api/types.ts app/frontend/src/api/client.ts app/frontend/src/pages/MissionDetailPage.tsx \
  docs/ARCHITECTURE.md docs/EVENTS.md docs/HISTORY.md docs/FALTANTES.md CLAUDE.md
git commit -m "Revisión conjunta: API, chat, sección en el detalle de la misión y docs"
```

---

### Task 8: Despliegue y prueba en vivo (solo con OK del fundador)

- [ ] **Step 1:** Consultar misiones vivas en un paso aparte (`curl -s localhost:8081/api/company/missions`); si hay alguna no terminada, parar y preguntar al fundador.
- [ ] **Step 2:** `docker compose build company-core && docker compose up -d company-core`; esperar `GET /actuator/health` = UP; confirmar `JOINT_REVIEW_ENABLED = 1` en `GET /api/company/policies`.
- [ ] **Step 3:** Con OK del fundador, lanzar un juego nuevo (p. ej. «Un Breakout de una sola fila de ladrillos: la paleta abajo se mueve con las flechas, la pelota rompe ladrillos y suma puntos; si la pelota cae, game over.», `TEAM-DEVELOPMENT`, `TEST`) bajo `systemd-inhibit` mientras dure.
- [ ] **Step 4:** Al llegar a `AWAITING_SPEC_APPROVAL`, leer `GET …/joint-review` y el mensaje; reportar al fundador lo que Neo y Aria resolvieron, sin corregir a mano. Aprobar solo con su OK.
- [ ] **Step 5:** Registrar el resultado (revisión y estado final de la misión) en `docs/HISTORY.md` y cerrar o actualizar el pendiente en `FALTANTES.md`.
