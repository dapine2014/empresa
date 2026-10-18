# Contrato, épicas y juegos por niveles (entrega A) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- Aria levanta un contrato con el pedido del cliente y el fundador lo aprueba. Desde ese contrato salen las HU, agrupadas en épicas cuando hace falta, y el diseño de Kael.
- En los juegos, cada nivel o escena del contrato tiene su diseño, Forjai genera las escenas `.tscn` y Vera recibe una ficha de cada escena que Java hace cumplir antes del sandbox.

**Architecture:**
- **Partes 1 y 2:** `ClientContract` es un documento nuevo, versionado en Neo4j y con su propia parada de aprobación (el mismo `AWAITING_SPEC_APPROVAL`). Java copia los ítems aprobados al `RequirementsSpec`, así el spec queda autocontenido. Cada HU referencia su ítem y Java valida la cobertura y agrupa las épicas sin modelo.
- **Parte 3:** Kael diseña un `levels` por ítem `LEVEL`/`SCENE` y declara sus escenas.
- **Parte 4:** se reutiliza el plan anterior (`2026-10-16-juegos-por-niveles`), con los niveles ahora como ítems `MOD-NN` del contrato:
  - esqueletos de Forjai;
  - gates;
  - ficha de la escena (`SceneSheet`);
  - `PlaythroughSheetGate`;
  - QA al final;
  - runner `Scene`/`Do`.

**Tech Stack:**
- Java 21 y Spring Boot 4.1.1.
- Neo4j con driver plano.
- Jackson 3: `tools.jackson.*`; las anotaciones siguen en `com.fasterxml.jackson.annotation`.
- JUnit 5 + Mockito.
- React + TS (oxlint).
- Godot 4.3 .NET en el sandbox.

**Spec:** `docs/superpowers/specs/2026-10-16-contrato-epicas-y-juegos-design.md`

## Global Constraints

**Contrato e ítems**
- El contrato vale para todo el Development Group (APP, GAME, NONE).
- Ítems del contrato: `MOD-01`, `MOD-02`…, únicos y en orden. Máximo 10 (`ContractValidator.MAX_ITEMS = 10`).
- `kind`: `MODULE` o `SYSTEM` en APP/NONE; `LEVEL`, `SCENE` o `SYSTEM` en GAME.
- En GAME: al menos un `LEVEL`. Con 2 o más ítems `LEVEL`/`SCENE`, `useEpics` debe ser `true`. `epicsReason` nunca va vacía.

**Aprobación**
- El estado sigue siendo `AWAITING_SPEC_APPROVAL`. La etapa del contrato se llama «Contrato listo (Aria)».
- `APPROVE`/`ADJUST`/`REJECT` deciden lo que esté en DRAFT, sea el contrato o las HU.
- Con la policy `SPEC_APPROVAL_REQUIRED` en 0, el contrato se aprueba por policy (`approvedBy` `policy`).

**HU y épicas**
- Cada HU lleva `contractItem` (`MOD-NN`) y cada ítem del contrato tiene al menos una HU.
- En GAME, las HU de un ítem `LEVEL`/`SCENE` van todas en el mismo incremento.
- Las épicas las agrupa Java por `contractItem`. El modelo nunca las escribe.

**Escenas de juego**
- Escenas `ESCENA-01`… y `ESCENA-01` es la principal. Máximo 8 (`MAX_SCENES = 8`).
- `script`: `^[A-Z][A-Za-z0-9]*$`, único y distinto de `Playthrough`, `PlaythroughRunner`, `Step` y `StepKind`.
- Las escenas donde ocurre un ítem `LEVEL`/`SCENE` exponen `public int Level { get; }` y `public void StartLevel(int level)`.
- El «contrato con Vera» del plan anterior se llama ahora **ficha de la escena**: `SceneSheet`, `PlaythroughSheetGate`, «FICHA DE LAS ESCENAS».

**Compatibilidad**
- Una misión sin contrato (anterior a esto) sigue como hoy: HU sin `contractItem`, sin épicas, sin reglas nuevas.
- Un juego sin `scenes` en el diseño no genera esqueletos ni aplica los gates nuevos.
- El runner es compatible: `Scene` y `Do` son opcionales.

**Convenciones del proyecto**
- Determinismo sobre LLM: validación, cobertura, épicas, ficha y gates se deciden en Java.
- Nunca `format` + `tools` en una llamada. Jackson 3. Sin `@Async`. Sin manejo HTTP nuevo. Todo `eventType` empieza por `EMPRESA_`.
- Textos al fundador y a los agentes en español.
- Commits con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. No stagear `.superpowers`.
- Backend: `cd app && mvn test` (o `-Dtest=Clase`). Frontend: `cd app/frontend && npm run lint && npm run build`.

## Rulings del plan (decididos al escribirlo)

1. **La experiencia la manda el contrato.** Java copia la `experience` del contrato al spec de Aria con `withContract`, en vez de rechazarla y gastar un intento si no coincide. Lo que se aprueba es igual de determinista.
2. **La etapa nueva de recuperación es solo `CONTRACT_ADJUSTMENT`.** El spec nombraba también `CONTRACT`, pero escribir el contrato queda dentro de `REQUIREMENTS`: `executeTeamMission` no repite un contrato ya aprobado. Un contrato en DRAFT se retoma en `AWAIT_APPROVAL`.
3. **`RequirementsWriter.write` mantiene sus 4 argumentos.** Lee el contrato aprobado con `ContractMemoryService`, que llega por un setter opcional. Así no cambian las decenas de stubs de los tests del ejecutor.
4. **Kael recibe la lista de niveles y escenas del contrato aparte de las HU.** Un juego de un solo nivel no lleva épicas y, sin esa lista, Kael no vería el ítem que tiene que diseñar.

## Review Focus

1. **El fundador ajusta el contrato varias veces seguidas, o hace doble clic en «aprobar».** Cada ajuste deja una versión nueva y solo la primera decisión sobre una versión gana (CAS). Tests en Tasks 2 y 6.
2. **Un reinicio corta la misión con el contrato en DRAFT o con un ajuste del contrato reclamado.** Retoma en la espera o en el ajuste, sin repetir un contrato aprobado ni saltar a las HU. Tests en Task 5.
3. **Una misión vieja sin contrato pide un ajuste de HU o construye el incremento 2.** Nada cambia: sin `contractItems`, sin reglas nuevas ni épicas en los textos. Tests en Tasks 3 y 4.
4. **Aria pone un `contractItem` inexistente o deja un ítem sin HU.** Error con el motivo exacto y el reintento de siempre. Tests en Task 3.
5. **«aprueba el contrato de MISSION-X» o «muéstrame el contrato de MISSION-X» en el chat.** Se rutea a requisitos, nunca a la decisión del inversionista. Tests en Task 6.

---

## File Structure

| Archivo | Responsabilidad |
|---|---|
| `agent/model/ClientContract.java` (nuevo) | Contrato: `Item`, schema |
| `agent/validation/ContractValidator.java` (nuevo) | Reglas del contrato |
| `agent/validation/ContractText.java` (nuevo) | Contrato como texto (aprobación, chat, prompt de Aria) |
| `service/ContractMemoryService.java` (nuevo) | Versiones `(:Mission)-[:HAS_CONTRACT]->(:ContractVersion)` |
| `service/ContractWriter.java` (nuevo) | Aria levanta el contrato (3 intentos con la corrección) |
| `service/CeoService.java` (mod) | `writeContract` (format sin tools) |
| `service/MissionMemoryService.java` (mod) | El borrado de la misión incluye `HAS_CONTRACT` |
| `agent/model/RequirementsSpec.java` (mod) | `contractItems`, `useEpics`, `UserStory.contractItem`, `withContract`, `itemsOf`, `gameLevels` |
| `agent/validation/RequirementsSpecValidator.java` (mod) | Cobertura del contrato; nivel no partido |
| `service/RequirementsWriter.java` (mod) | Aria escribe las HU desde el contrato aprobado |
| `agent/validation/RequirementsText.java` (mod) | HU agrupadas por épica |
| `service/MissionExecutor.java` (mod) | Parada del contrato, continuar, ajustar, retomar |
| `recovery/ResumePoint.java`, `MissionSnapshot.java`, `MissionRecoveryService.java` (mod) | Retomar el contrato |
| `model/ContractView.java` (nuevo), `model/RequirementsView.java` (mod), `service/MissionService.java` (mod) | Decidir el contrato; contrato en la vista |
| `service/ChatIntentRouter.java` (mod) | Ver, aprobar, rechazar y ajustar el contrato por chat |
| Kael, escenas, gates, ficha, runner | Igual que el plan anterior (ver Tasks 7, 8 y 10 a 14) |
| `app/frontend/src/api/types.ts`, `pages/MissionDetailPage.tsx` (mod) | Contrato, épicas, escenas y niveles |

Todas las rutas Java cuelgan de `app/src/main/java/com/aicompany/core/`, y los tests, de `app/src/test/java/com/aicompany/core/`.

---

### Task 1: El contrato del cliente: modelo, validación y texto

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/model/ClientContract.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/ContractValidator.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/ContractText.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/ContractValidatorTest.java` (nuevo)
- Test: `app/src/test/java/com/aicompany/core/agent/validation/ContractTextTest.java` (nuevo)

**Interfaces:**
- Produces:
  - `record ClientContract(String summary, String experience, List<Item> items, List<String> outOfScope, Boolean useEpics, String epicsReason)`, con:
    - `itemsOrEmpty()`, `outOfScopeOrEmpty()`;
    - `boolean epics()` (`Boolean.TRUE.equals(useEpics)`);
    - `RequirementsSpec.Experience experienceOrNull()`;
    - `static final Map<String, Object> SCHEMA`.
  - `record ClientContract.Item(String id, String kind, String name, String description)`.
  - `ContractValidator.validate(ClientContract)` → `List<String>` (vacía = válido), con `ContractValidator.MAX_ITEMS = 10`.
  - `ContractText.summary(ClientContract c, int version)`: contrato para el fundador (mensaje, chat).
  - `ContractText.forPrompt(ClientContract c)`: los ítems para el prompt de Aria.
  - `ContractText.kindLabel(String kind)`: `MODULE`→«Módulo», `SYSTEM`→«Sistema», `LEVEL`→«Nivel», `SCENE`→«Escena»; otro valor queda igual.

- [ ] **Step 1: Write the failing tests**

`ContractValidatorTest.java`:

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.ClientContract;
import com.aicompany.core.agent.model.ClientContract.Item;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Contrato (spec 2026-10-16 contrato §1): Java valida el contrato que levanta Aria. */
class ContractValidatorTest {

    static final ClientContract PONG = new ClientContract("Un Pong de 3 niveles.", "GAME",
            List.of(new Item("MOD-01", "SYSTEM", "Controles y puntaje", "dos paletas"),
                    new Item("MOD-02", "LEVEL", "Nivel 1", "pelota normal hasta 50 puntos"),
                    new Item("MOD-03", "LEVEL", "Nivel 2", "la pelota aumenta de velocidad"),
                    new Item("MOD-04", "LEVEL", "Nivel 3", "dos pelotas")),
            List.of("sonido"), true, "un nivel por épica");

    static final ClientContract FORM = new ClientContract("Un formulario de contacto.", "APP",
            List.of(new Item("MOD-01", "MODULE", "Contacto", "nombre, correo y mensaje")),
            List.of(), false, "una app pequeña de un solo módulo");

    @Test void aValidGameAndASmallAppPass() {
        assertEquals(List.of(), ContractValidator.validate(PONG));
        assertEquals(List.of(), ContractValidator.validate(FORM));
    }

    @Test void requiredFieldsAndExperience() {
        var errors = String.join("\n", ContractValidator.validate(new ClientContract(" ", "WEB", List.of(), List.of(),
                null, "")));
        assertTrue(errors.contains("summary"), errors);
        assertTrue(errors.contains("experience"), errors);
        assertTrue(errors.contains("items debe listar"), errors);
        assertTrue(errors.contains("useEpics"), errors);
        assertTrue(errors.contains("epicsReason"), errors);
    }

    @Test void idsGoInOrderAndKindsDependOnTheExperience() {
        var errors = String.join("\n", ContractValidator.validate(new ClientContract("x", "APP",
                List.of(new Item("MOD-02", "MODULE", "Ventas", "x"), new Item("MOD-01", "LEVEL", "Nivel", "x"),
                        new Item("MOD-03", "MODULE", " ", "x")), List.of(), true, "módulos")));
        assertTrue(errors.contains("El ítem 1 debe tener id MOD-01 (tiene MOD-02)"), errors);
        assertTrue(errors.contains("MOD-01: kind debe ser MODULE o SYSTEM en APP; recibido: \"LEVEL\""), errors);
        assertTrue(errors.contains("MOD-03 necesita name"), errors);
    }

    @Test void aGameNeedsALevelAndEpicsWhenItHasSeveralLevelsOrScenes() {
        var noLevel = String.join("\n", ContractValidator.validate(new ClientContract("x", "GAME",
                List.of(new Item("MOD-01", "SCENE", "Menú", "x")), List.of(), false, "uno")));
        assertTrue(noLevel.contains("al menos un ítem LEVEL"), noLevel);
        var noEpics = String.join("\n", ContractValidator.validate(new ClientContract(PONG.summary(), "GAME",
                PONG.items(), List.of(), false, "no hace falta")));
        assertTrue(noEpics.contains("useEpics: true"), noEpics);
        var oneLevel = new ClientContract("x", "GAME", List.of(new Item("MOD-01", "LEVEL", "Nivel", "x")), List.of(),
                false, "un solo nivel");
        assertEquals(List.of(), ContractValidator.validate(oneLevel));
    }

    @Test void atMostTenItems() {
        var items = new ArrayList<Item>();
        for (int i = 1; i <= 11; i++) {
            items.add(new Item("MOD-%02d".formatted(i), "MODULE", "M" + i, "x"));
        }
        var errors = ContractValidator.validate(new ClientContract("x", "APP", items, List.of(), true, "módulos"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("máximo 10")), errors.toString());
    }
}
```

`ContractTextTest.java`:

```java
package com.aicompany.core.agent.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Contrato (spec 2026-10-16 contrato §1): lo que ve el fundador y lo que recibe Aria. */
class ContractTextTest {

    @Test void theSummaryShowsEveryItemWithItsKind() {
        var text = ContractText.summary(ContractValidatorTest.PONG, 1);
        assertTrue(text.startsWith("Contrato (versión 1, juego): Un Pong de 3 niveles."), text);
        assertTrue(text.contains("\nMOD-01 [Sistema] Controles y puntaje — dos paletas"), text);
        assertTrue(text.contains("\nMOD-03 [Nivel] Nivel 2 — la pelota aumenta de velocidad"), text);
        assertTrue(text.contains("\nFuera de alcance: sonido · Épicas: sí (un nivel por épica)."), text);
    }

    @Test void aSmallAppSaysItHasNoEpics() {
        var text = ContractText.summary(ContractValidatorTest.FORM, 2);
        assertTrue(text.startsWith("Contrato (versión 2, aplicación):"), text);
        assertTrue(text.contains("Fuera de alcance: (nada declarado) · Épicas: no (una app pequeña de un solo módulo)."), text);
    }

    @Test void thePromptListsTheItems() {
        var text = ContractText.forPrompt(ContractValidatorTest.PONG);
        assertTrue(text.contains("- MOD-02 [Nivel] Nivel 1: pelota normal hasta 50 puntos"), text);
        assertTrue(text.contains("Épicas: sí"), text);
        assertTrue(text.contains("Tipo: GAME"), text);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn test -Dtest='ContractValidatorTest,ContractTextTest'`
Expected: FAIL de compilación (`ClientContract`, `ContractValidator` y `ContractText` no existen).

- [ ] **Step 3: Implement `ClientContract`**

```java
package com.aicompany.core.agent.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Contrato (spec 2026-10-16 contrato §1): la solicitud del cliente que Aria levanta antes de escribir historias (hoy el
 * cliente es el fundador). Sin detalle: el detalle va en las HU, que salen de aquí. Cada ítem es un módulo, un sistema
 * base o, en un juego, un nivel o una escena; con useEpics cada ítem es una épica.
 */
public record ClientContract(String summary, String experience, List<Item> items,
                             @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> outOfScope,
                             Boolean useEpics, String epicsReason) {

    public record Item(String id, String kind, String name, String description) {
    }

    public ClientContract {
        items = items == null ? List.of() : items;
        outOfScope = outOfScope == null ? List.of() : outOfScope;
    }

    public List<Item> itemsOrEmpty() {
        return items == null ? List.of() : items.stream().filter(Objects::nonNull).toList();
    }

    public List<String> outOfScopeOrEmpty() {
        return outOfScope == null ? List.of() : outOfScope.stream().filter(Objects::nonNull).toList();
    }

    public boolean epics() {
        return Boolean.TRUE.equals(useEpics);
    }

    public RequirementsSpec.Experience experienceOrNull() {
        return RequirementsSpec.Experience.of(experience).orElse(null);
    }

    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "summary", Map.of("type", "string"),
                    "experience", Map.of("type", "string", "enum", List.of("APP", "GAME", "NONE")),
                    "items", Map.of("type", "array", "items", Map.of("type", "object",
                            "properties", Map.of("id", Map.of("type", "string"),
                                    "kind", Map.of("type", "string", "enum", List.of("MODULE", "SYSTEM", "LEVEL", "SCENE")),
                                    "name", Map.of("type", "string"), "description", Map.of("type", "string")),
                            "required", List.of("id", "kind", "name", "description"))),
                    "outOfScope", Map.of("type", "array", "items", Map.of("type", "string")),
                    "useEpics", Map.of("type", "boolean"),
                    "epicsReason", Map.of("type", "string")),
            "required", List.of("summary", "experience", "items", "outOfScope", "useEpics", "epicsReason"));
}
```

- [ ] **Step 4: Implement `ContractValidator`**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.ClientContract;
import com.aicompany.core.agent.model.RequirementsSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Contrato (spec 2026-10-16 contrato §1): reglas de Java sobre el contrato de Aria (lista vacía = válido). */
public final class ContractValidator {

    public static final int MAX_ITEMS = 10;
    private static final List<String> APP_KINDS = List.of("MODULE", "SYSTEM");
    private static final List<String> GAME_KINDS = List.of("LEVEL", "SCENE", "SYSTEM");
    private static final Set<String> PLAYABLE = Set.of("LEVEL", "SCENE");

    private ContractValidator() {
    }

    public static List<String> validate(ClientContract contract) {
        if (contract == null) {
            return List.of("El contrato está vacío.");
        }
        var errors = new ArrayList<String>();
        if (contract.summary() == null || contract.summary().isBlank()) {
            errors.add("summary debe decir en 2 o 3 frases qué pide el cliente.");
        }
        var experience = contract.experienceOrNull();
        if (experience == null) {
            errors.add("experience debe ser APP (el producto tiene pantallas), GAME (es un juego) o NONE (solo API o "
                    + "servicio, sin ninguna interfaz); recibido: \"" + contract.experience() + "\".");
        }
        var items = contract.itemsOrEmpty();
        if (items.isEmpty()) {
            errors.add("items debe listar lo que pidió el cliente: módulos (MOD-01, MOD-02…) o, en un juego, sus niveles "
                    + "y escenas.");
        }
        if (items.size() > MAX_ITEMS) {
            errors.add("Hay " + items.size() + " ítems: máximo " + MAX_ITEMS + ".");
        }
        var kinds = experience == RequirementsSpec.Experience.GAME ? GAME_KINDS : APP_KINDS;
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            var expected = "MOD-%02d".formatted(i + 1);
            if (!expected.equals(item.id())) {
                errors.add("El ítem " + (i + 1) + " debe tener id " + expected + " (tiene " + item.id()
                        + "): los ids van en orden.");
            }
            if (experience != null && !kinds.contains(item.kind())) {
                errors.add(item.id() + ": kind debe ser " + String.join(" o ", kinds) + " en " + experience.name()
                        + "; recibido: \"" + item.kind() + "\".");
            }
            if (item.name() == null || item.name().isBlank()) {
                errors.add(item.id() + " necesita name.");
            }
        }
        if (contract.useEpics() == null) {
            errors.add("useEpics debe ser true (lleva épicas) o false (no las necesita).");
        }
        if (contract.epicsReason() == null || contract.epicsReason().isBlank()) {
            errors.add("epicsReason debe explicar por qué lleva épicas o por qué no.");
        }
        if (experience == RequirementsSpec.Experience.GAME) {
            if (items.stream().noneMatch(i -> "LEVEL".equals(i.kind()))) {
                errors.add("Un juego necesita al menos un ítem LEVEL (un nivel jugable).");
            }
            var playable = items.stream().filter(i -> PLAYABLE.contains(i.kind())).count();
            if (playable >= 2 && !contract.epics()) {
                errors.add("Un juego con varios niveles o escenas lleva épicas (useEpics: true): cada nivel o escena es "
                        + "una épica.");
            }
        }
        return errors;
    }
}
```

- [ ] **Step 5: Implement `ContractText`**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.ClientContract;
import com.aicompany.core.agent.model.RequirementsSpec;

import java.util.stream.Collectors;

/** Contrato (spec 2026-10-16 contrato §1): el contrato como texto, armado por Java. */
public final class ContractText {

    private ContractText() {
    }

    public static String kindLabel(String kind) {
        if (kind == null) {
            return "";
        }
        return switch (kind) {
            case "MODULE" -> "Módulo";
            case "SYSTEM" -> "Sistema";
            case "LEVEL" -> "Nivel";
            case "SCENE" -> "Escena";
            default -> kind;
        };
    }

    private static String experienceLabel(RequirementsSpec.Experience experience) {
        if (experience == null) {
            return "sin tipo";
        }
        return switch (experience) {
            case APP -> "aplicación";
            case GAME -> "juego";
            case NONE -> "servicio sin interfaz";
        };
    }

    /** Lo que ve el fundador (mensaje de la espera, chat): un ítem por línea. */
    public static String summary(ClientContract c, int version) {
        var items = c.itemsOrEmpty().stream()
                .map(i -> i.id() + " [" + kindLabel(i.kind()) + "] " + i.name() + " — " + i.description())
                .collect(Collectors.joining("\n"));
        return "Contrato (versión " + version + ", " + experienceLabel(c.experienceOrNull()) + "): " + c.summary()
                + "\n" + items
                + "\nFuera de alcance: " + (c.outOfScopeOrEmpty().isEmpty() ? "(nada declarado)"
                : String.join("; ", c.outOfScopeOrEmpty()))
                + " · Épicas: " + (c.epics() ? "sí" : "no") + " (" + c.epicsReason() + ").";
    }

    /** Lo que recibe Aria al escribir las HU. */
    public static String forPrompt(ClientContract c) {
        return "Tipo: " + c.experience() + "\n" + c.summary() + "\n"
                + c.itemsOrEmpty().stream().map(i -> "- " + i.id() + " [" + kindLabel(i.kind()) + "] " + i.name() + ": "
                        + i.description()).collect(Collectors.joining("\n"))
                + "\nFuera de alcance: " + (c.outOfScopeOrEmpty().isEmpty() ? "(nada declarado)"
                : String.join("; ", c.outOfScopeOrEmpty()))
                + "\nÉpicas: " + (c.epics() ? "sí (cada ítem agrupa sus historias)" : "no") + "\n";
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd app && mvn test -Dtest='ContractValidatorTest,ContractTextTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/model/ClientContract.java app/src/main/java/com/aicompany/core/agent/validation/ContractValidator.java app/src/main/java/com/aicompany/core/agent/validation/ContractText.java app/src/test/java/com/aicompany/core/agent/validation/ContractValidatorTest.java app/src/test/java/com/aicompany/core/agent/validation/ContractTextTest.java
git commit -m "Contrato del cliente: modelo, validación en Java y texto para el fundador

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Aria levanta el contrato: memoria en Neo4j y escritor

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/ContractMemoryService.java`
- Create: `app/src/main/java/com/aicompany/core/service/ContractWriter.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java` (junto a `writeRequirements`, línea ~1255)
- Modify: `app/src/main/java/com/aicompany/core/service/MissionMemoryService.java:507` (borrado de la misión)
- Test: `app/src/test/java/com/aicompany/core/service/ContractWriterTest.java` (nuevo)

**Interfaces:**
- Consumes: `ClientContract`, `ContractValidator`, `ContractText` (Task 1).
- Produces:
  - `ContractMemoryService` con:
    - `record Version(int version, String contractJson, String status, String instruction, Instant createdAt)`;
    - `int saveDraft(String missionId, String contractJson, String instruction)`, que deja la DRAFT anterior en `SUPERSEDED`;
    - `Optional<Version> latest(String missionId)` y `Optional<Version> approved(String missionId)`;
    - `boolean claimDecision(String missionId, int version, String pendingAdjustment)` (CAS);
    - `Optional<String> pendingAdjustment(String missionId)`;
    - `void approve(String missionId, int version, String approvedBy)` y `void reject(String missionId, int version)`.
  - `ContractWriter` con:
    - `public static final String ARIA = "product-owner"`;
    - `record Written(int version, ClientContract contract)`;
    - `Written write(String missionId, String instruction, Optional<ClientContract> previous, String adjustment)`, que lanza `IllegalStateException` tras 3 intentos inválidos.
  - `CeoService.writeContract(String agentId, String prompt, String agentPrompt, String model)` → `ClientContract`.

Sin test directo de `ContractMemoryService`: los `*MemoryService` no lo tienen (CLAUDE.md). Copia el patrón de `RequirementsMemoryService`, que ya se verificó en vivo.

- [ ] **Step 1: Write the failing test**

`ContractWriterTest.java`:

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.ClientContract;
import com.aicompany.core.agent.model.ClientContract.Item;
import com.aicompany.core.event.CompanyEventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Contrato (spec 2026-10-16 contrato §1): Aria levanta el contrato; Java valida y ella corrige hasta 3 veces. */
class ContractWriterTest {

    private final CeoService ceo = mock(CeoService.class);
    private final MissionMemoryService missions = mock(MissionMemoryService.class);
    private final ContractMemoryService contracts = mock(ContractMemoryService.class);
    private final CompanyMemoryService company = mock(CompanyMemoryService.class);
    private final PromptMemoryService prompts = mock(PromptMemoryService.class);
    private final ContractWriter writer = new ContractWriter(ceo, missions, contracts, company, prompts,
            mock(CompanyEventPublisher.class), JsonMapper.builder().build(), "qwen3:8b");

    private static final ClientContract VALID = new ClientContract("Un Pong.", "GAME",
            List.of(new Item("MOD-01", "LEVEL", "Nivel 1", "pelota normal")), List.of(), false, "un solo nivel");
    private static final ClientContract INVALID = new ClientContract("Un Pong.", "GAME",
            List.of(new Item("MOD-01", "SCENE", "Menú", "x")), List.of(), false, "uno");

    {
        when(company.agentModel(eq("product-owner"), anyString())).thenReturn("nvidia:x/y");
        when(prompts.activePrompt("product-owner")).thenReturn("");
        when(contracts.saveDraft(anyString(), anyString(), any())).thenReturn(1);
    }

    @Test void aValidContractIsSavedAsADraft() {
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceo.writeContract(eq("product-owner"), prompt.capture(), any(), any())).thenReturn(VALID);

        var written = writer.write("M-1", "un pong", Optional.empty(), null);

        assertEquals(1, written.version());
        verify(contracts).saveDraft(eq("M-1"), argThat(json -> json.contains("\"MOD-01\"")), isNull());
        verify(missions).createTask("M-1-CONTRACT", "M-1", "product-owner", "CLIENT_CONTRACT", "REQUIREMENTS");
        assertTrue(prompt.getValue().contains("PEDIDO DEL CLIENTE"), prompt.getValue());
        assertTrue(prompt.getValue().contains("un pong"), prompt.getValue());
        assertTrue(prompt.getValue().contains("useEpics"), prompt.getValue());
    }

    @Test void anInvalidContractIsRetriedWithTheExactReason() {
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceo.writeContract(eq("product-owner"), prompt.capture(), any(), any())).thenReturn(INVALID, VALID);

        writer.write("M-1", "un pong", Optional.empty(), null);

        assertTrue(prompt.getAllValues().get(1).contains("CORRECCIÓN DEL INTENTO ANTERIOR"));
        assertTrue(prompt.getAllValues().get(1).contains("al menos un ítem LEVEL"));
    }

    @Test void threeInvalidAttemptsFailTheTask() {
        when(ceo.writeContract(any(), any(), any(), any())).thenReturn(INVALID);

        var ex = assertThrows(IllegalStateException.class, () -> writer.write("M-1", "un pong", Optional.empty(), null));

        assertTrue(ex.getMessage().contains("3 intentos"));
        verify(missions).updateTask(eq("M-1-CONTRACT"), eq("FAILED"), anyString());
        verify(contracts, never()).saveDraft(any(), any(), any());
    }

    // Review Focus 1: cada ajuste del fundador parte de la versión anterior y deja una versión nueva.
    @Test void anAdjustmentStartsFromThePreviousVersion() {
        when(contracts.latest("M-1")).thenReturn(Optional.of(new ContractMemoryService.Version(1, "{}", "DRAFT", "", null)));
        when(contracts.saveDraft(anyString(), anyString(), any())).thenReturn(2);
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceo.writeContract(eq("product-owner"), prompt.capture(), any(), any())).thenReturn(VALID);

        var written = writer.write("M-1", "un pong", Optional.of(VALID), "el nivel 3 tiene dos pelotas");

        assertEquals(2, written.version());
        verify(missions).createTask("M-1-CONTRACT-V2", "M-1", "product-owner", "CLIENT_CONTRACT", "REQUIREMENTS");
        verify(contracts).saveDraft(eq("M-1"), anyString(), eq("el nivel 3 tiene dos pelotas"));
        assertTrue(prompt.getValue().contains("VERSIÓN ANTERIOR"), prompt.getValue());
        assertTrue(prompt.getValue().contains("AJUSTE DEL FUNDADOR:\nel nivel 3 tiene dos pelotas"), prompt.getValue());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && mvn test -Dtest=ContractWriterTest`
Expected: FAIL de compilación (`ContractWriter`, `ContractMemoryService` y `CeoService.writeContract` no existen).

- [ ] **Step 3: Implement `ContractMemoryService`**

```java
package com.aicompany.core.service;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Contrato (spec 2026-10-16 contrato §1): versiones del contrato por misión ((:Mission)-[:HAS_CONTRACT]->). */
@Service
public class ContractMemoryService {

    public record Version(int version, String contractJson, String status, String instruction, Instant createdAt) {
    }

    private final Driver driver;

    public ContractMemoryService(Driver driver) {
        this.driver = driver;
    }

    public int saveDraft(String missionId, String contractJson, String instruction) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var next = tx.run("MATCH (m:Mission {id:$id}) OPTIONAL MATCH (m)-[:HAS_CONTRACT]->(c) "
                        + "RETURN coalesce(max(c.version), 0) + 1 AS v", Map.of("id", missionId)).single().get("v").asInt();
                tx.run("MATCH (:Mission {id:$id})-[:HAS_CONTRACT]->(c:ContractVersion {status:'DRAFT'}) "
                        + "SET c.status = 'SUPERSEDED'", Map.of("id", missionId));
                tx.run("MATCH (m:Mission {id:$id}) CREATE (m)-[:HAS_CONTRACT]->(:ContractVersion {version:$v, "
                                + "contractJson:$json, status:'DRAFT', instruction:$instruction, createdAt:datetime()})",
                        Map.of("id", missionId, "v", next, "json", contractJson,
                                "instruction", instruction == null ? "" : instruction));
                return next;
            });
        }
    }

    public Optional<Version> latest(String missionId) {
        return query(missionId, "");
    }

    public Optional<Version> approved(String missionId) {
        return query(missionId, " AND c.status = 'APPROVED'");
    }

    private Optional<Version> query(String missionId, String filter) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_CONTRACT]->(c:ContractVersion) WHERE true" + filter
                            + " RETURN c.version AS v, c.contractJson AS j, c.status AS st, c.instruction AS i, "
                            + "c.createdAt AS t ORDER BY c.version DESC LIMIT 1", Map.of("id", missionId))
                    .list(r -> new Version(r.get("v").asInt(), r.get("j").asString(), r.get("st").asString(),
                            r.get("i").isNull() ? "" : r.get("i").asString(),
                            r.get("t").isNull() ? null : r.get("t").asZonedDateTime().toInstant()))
                    .stream().findFirst();
        }
    }

    /** Review Focus 1: CAS contra dos decisiones simultáneas sobre la misma versión (doble clic). */
    public boolean claimDecision(String missionId, int version, String pendingAdjustment) {
        var params = new java.util.HashMap<String, Object>();
        params.put("id", missionId);
        params.put("v", version);
        params.put("adjust", pendingAdjustment);
        try (var session = driver.session()) {
            return session.executeWrite(tx -> !tx.run("MATCH (:Mission {id:$id})-[:HAS_CONTRACT]->(c:ContractVersion "
                    + "{version:$v}) WHERE c.status = 'DRAFT' AND c.decisionClaimedAt IS NULL SET c.decisionClaimedAt = "
                    + "datetime(), c.pendingAdjustment = $adjust RETURN c.version", params).list().isEmpty());
        }
    }

    /** El ajuste reclamado sobre la última versión DRAFT (Aria no llegó a escribir la nueva). */
    public Optional<String> pendingAdjustment(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_CONTRACT]->(c:ContractVersion {status:'DRAFT'}) "
                            + "WHERE c.decisionClaimedAt IS NOT NULL AND c.pendingAdjustment IS NOT NULL "
                            + "RETURN c.pendingAdjustment AS a ORDER BY c.version DESC LIMIT 1", Map.of("id", missionId))
                    .list(r -> r.get("a").asString()).stream().findFirst();
        }
    }

    public void approve(String missionId, int version, String approvedBy) {
        write("MATCH (:Mission {id:$id})-[:HAS_CONTRACT]->(c:ContractVersion {version:$v}) "
                + "SET c.status='APPROVED', c.approvedBy=$by, c.approvedAt=datetime()",
                Map.of("id", missionId, "v", version, "by", approvedBy));
    }

    public void reject(String missionId, int version) {
        write("MATCH (:Mission {id:$id})-[:HAS_CONTRACT]->(c:ContractVersion {version:$v}) SET c.status='REJECTED'",
                Map.of("id", missionId, "v", version));
    }

    private void write(String cypher, Map<String, Object> params) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, params);
                return null;
            });
        }
    }
}
```

- [ ] **Step 4: Implement `CeoService.writeContract`**

Junto a `writeRequirements`:

```java
    /** Contrato (spec 2026-10-16 contrato §1): el contrato de Aria (format ClientContract.SCHEMA, sin tools). */
    public com.aicompany.core.agent.model.ClientContract writeContract(String agentId, String prompt, String agentPrompt,
                                                                        String model) {
        return callStructured("CLIENT_CONTRACT", agentId, prompt, agentPrompt, model,
                com.aicompany.core.agent.model.ClientContract.SCHEMA,
                com.aicompany.core.agent.model.ClientContract.class);
    }
```

- [ ] **Step 5: Implement `ContractWriter`**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.ClientContract;
import com.aicompany.core.agent.validation.ContractValidator;
import com.aicompany.core.event.CompanyEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/**
 * Contrato (spec 2026-10-16 contrato §1): Aria levanta el contrato con el pedido del cliente (hoy, el fundador); Java lo
 * valida (ContractValidator) y ella corrige con el motivo exacto, hasta 3 intentos. Cada contrato válido es una versión.
 */
@Service
public class ContractWriter {

    private static final Logger log = LoggerFactory.getLogger(ContractWriter.class);
    public static final String ARIA = RequirementsWriter.ARIA;
    static final int MAX_ATTEMPTS = 3;

    public record Written(int version, ClientContract contract) {
    }

    private final CeoService ceo;
    private final MissionMemoryService missions;
    private final ContractMemoryService contracts;
    private final CompanyMemoryService company;
    private final PromptMemoryService prompts;
    private final CompanyEventPublisher events;
    private final JsonMapper json;
    private final String defaultModel;

    public ContractWriter(CeoService ceo, MissionMemoryService missions, ContractMemoryService contracts,
                          CompanyMemoryService company, PromptMemoryService prompts, CompanyEventPublisher events,
                          JsonMapper json, @Value("${ollama.agent-model}") String defaultModel) {
        this.ceo = ceo;
        this.missions = missions;
        this.contracts = contracts;
        this.company = company;
        this.prompts = prompts;
        this.events = events;
        this.json = json;
        this.defaultModel = defaultModel;
    }

    public Written write(String missionId, String instruction, Optional<ClientContract> previous, String adjustment) {
        var nextVersion = contracts.latest(missionId).map(v -> v.version() + 1).orElse(1);
        var taskId = missionId + "-CONTRACT" + (nextVersion > 1 ? "-V" + nextVersion : "");
        missions.createTask(taskId, missionId, ARIA, "CLIENT_CONTRACT", "REQUIREMENTS");
        missions.updateTask(taskId, "RUNNING", "Aria está levantando el contrato.");
        missions.setAgentStatus(ARIA, "WORKING");
        try {
            var model = company.agentModel(ARIA, defaultModel);
            var agentPrompt = prompts.activePrompt(ARIA);
            var base = prompt(instruction, previous, adjustment);
            String feedback = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                var prompt = feedback == null ? base : base + """

                        CORRECCIÓN DEL INTENTO ANTERIOR (Java rechazó el contrato por esto; corrígelo todo):
                        %s
                        """.formatted(feedback);
                ClientContract contract;
                try {
                    contract = ceo.writeContract(ARIA, prompt, agentPrompt, model);
                } catch (MissionCancellations.MissionCancelledException ex) {
                    throw ex; // cancelar la misión no es una respuesta inválida: no se reintenta.
                } catch (Exception ex) {
                    feedback = "- " + (ex.getMessage() == null ? "Respuesta no procesable." : ex.getMessage());
                    continue;
                }
                var errors = ContractValidator.validate(contract);
                if (errors.isEmpty()) {
                    var contractJson = json.writeValueAsString(contract);
                    var version = contracts.saveDraft(missionId, contractJson, adjustment);
                    missions.updateTask(taskId, "COMPLETED", contractJson);
                    log.info("MISSION {} - contract v{} accepted items={}", missionId, version,
                            contract.itemsOrEmpty().size());
                    return new Written(version, contract);
                }
                feedback = "- " + String.join("\n- ", errors);
                log.warn("MISSION {} - contract rejected attempt={} errors={}", missionId, attempt, feedback);
            }
            var message = "Aria no produjo un contrato válido después de " + MAX_ATTEMPTS + " intentos:\n" + feedback;
            missions.updateTask(taskId, "FAILED", message);
            throw new IllegalStateException(message);
        } finally {
            missions.setAgentStatus(ARIA, "IDLE");
        }
    }

    private String prompt(String instruction, Optional<ClientContract> previous, String adjustment) {
        var base = """
                Eres Aria, Product Owner del Development Group de Forjai. Antes de escribir historias levantas el CONTRATO
                con el cliente: su solicitud, sin detalle técnico (el detalle va después, en las historias de usuario,
                que saldrán de este contrato). Decides QUÉ se pide, nunca CÓMO se construye.

                PEDIDO DEL CLIENTE (hoy, el fundador):
                %s

                REGLAS:
                - summary: qué pide el cliente, en 2 o 3 frases.
                - experience: GAME si es un juego; APP si las personas lo usan con pantallas; NONE solo si no tiene
                  ninguna interfaz (API o servicio).
                - items: lo que pidió el cliente, una línea cada uno: MOD-01, MOD-02… en orden (máximo 10). kind: en APP
                  o NONE, MODULE (un módulo, p. ej. Inventarios en un software contable) o SYSTEM (un sistema base que
                  usan varios: usuarios, permisos); en GAME, LEVEL (un nivel jugable), SCENE (una escena que no es nivel:
                  menú, game over, créditos) o SYSTEM (controles, puntaje, guardado). En un juego los niveles van en el
                  orden en que se juegan. description: qué tiene de distinto, en una frase con los números que dio el
                  cliente (p. ej. "pelota normal hasta 50 puntos").
                - outOfScope: lo que queda fuera a propósito.
                - useEpics: true si varias historias van a tocar el mismo módulo, nivel o escena (cada ítem será una
                  épica que agrupa sus historias); false si el producto es pequeño y no hace falta agrupar. Un juego con
                  varios niveles o escenas siempre lleva épicas. epicsReason: por qué sí o por qué no.
                - No inventes lo que el cliente no pidió. Español, frases cortas.

                Responde SOLO este JSON, con exactamente estos nombres de campos:
                {"summary": "...", "experience": "APP|GAME|NONE",
                 "items": [{"id": "MOD-01", "kind": "MODULE|SYSTEM|LEVEL|SCENE", "name": "...", "description": "..."}],
                 "outOfScope": ["..."], "useEpics": true, "epicsReason": "..."}
                """.formatted(instruction);
        if (previous.isEmpty()) {
            return base;
        }
        return base + """

                VERSIÓN ANTERIOR (el fundador pidió un ajuste; conserva lo que no contradiga el ajuste):
                %s

                AJUSTE DEL FUNDADOR:
                %s
                """.formatted(json.writeValueAsString(previous.get()), adjustment == null ? "" : adjustment);
    }
}
```

`MissionCancellations.MissionCancelledException` ya existe en `com.aicompany.core.service` (lo usa `JointReviewWriter`). Si `ceo.writeContract` está mockeado y lanza otra excepción, cuenta como un intento.

- [ ] **Step 6: El borrado de la misión incluye el contrato**

En `MissionMemoryService` (línea ~507), cambiar `"OPTIONAL MATCH (m)-[:HAS_TASK|HAS_DECISION|HAS_OPPORTUNITY|HAS_REQUIREMENTS"` por `"OPTIONAL MATCH (m)-[:HAS_TASK|HAS_DECISION|HAS_OPPORTUNITY|HAS_REQUIREMENTS|HAS_CONTRACT"` y actualizar el comentario de la línea anterior: `// 2026-10-15: también las HU de Aria y los diseños de Kael (quedaban huérfanos); 2026-10-16: el contrato.`

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd app && mvn test -Dtest='ContractWriterTest,MissionMemoryService*Test'`
Expected: PASS. Si no hay test de `MissionMemoryService`, Maven avisa «No tests matching» para ese patrón: correr solo `-Dtest=ContractWriterTest`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/ContractMemoryService.java app/src/main/java/com/aicompany/core/service/ContractWriter.java app/src/main/java/com/aicompany/core/service/CeoService.java app/src/main/java/com/aicompany/core/service/MissionMemoryService.java app/src/test/java/com/aicompany/core/service/ContractWriterTest.java
git commit -m "Contrato del cliente: Aria lo levanta con reintentos y queda versionado en Neo4j

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Las HU referencian el contrato: modelo y validación

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/model/RequirementsSpec.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/RequirementsSpecValidator.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/RequirementsSpecValidatorTest.java`

**Interfaces:**
- Consumes: `ClientContract`, `ClientContract.Item` (Task 1).
- Produces:
  - `RequirementsSpec` gana dos componentes al final: `List<ClientContract.Item> contractItems, Boolean useEpics`. Los llena Java y nunca el modelo. Se conservan los constructores de 6 y 7 argumentos, que los dejan en null.
  - `UserStory` gana `String contractItem` al final. Se conserva el constructor de 7 argumentos (`contractItem` null).
  - Métodos nuevos de `RequirementsSpec`:
    - `List<ClientContract.Item> contractItemsOrEmpty()`;
    - `boolean epics()`;
    - `List<UserStory> storiesOfItem(String itemId)`, en el orden de `userStories`;
    - `List<ClientContract.Item> itemsOf(int increment)`: los ítems con al menos una HU en el incremento, en el orden del contrato;
    - `List<ClientContract.Item> gameLevels()`: los ítems `LEVEL` o `SCENE`, en orden;
    - `List<ClientContract.Item> gameLevelsOf(int increment)`: `itemsOf(increment)` filtrado a `LEVEL`/`SCENE`;
    - `RequirementsSpec withContract(ClientContract contract)`: copia `experience`, ítems y `useEpics` del contrato. Con `null` deja ítems y `useEpics` en null y la `experience` igual.

- [ ] **Step 1: Write the failing tests**

Agregar a `RequirementsSpecValidatorTest` (imports: `com.aicompany.core.agent.model.ClientContract`):

```java
    // Contrato (spec 2026-10-16 contrato §2): las HU salen del contrato aprobado; Java valida la cobertura.
    private static final ClientContract PONG_CONTRACT = new ClientContract("Un Pong de 2 niveles.", "GAME",
            List.of(new ClientContract.Item("MOD-01", "SYSTEM", "Controles", "paletas"),
                    new ClientContract.Item("MOD-02", "LEVEL", "Nivel 1", "normal"),
                    new ClientContract.Item("MOD-03", "LEVEL", "Nivel 2", "rápida")),
            List.of(), true, "un nivel por épica");

    private static UserStory hu(String id, String item, Scenario... scenarios) {
        return new UserStory(id, "vendedor", "quiero algo", "para algo", List.of("criterio"), List.of(),
                List.of(scenarios), item);
    }

    private static RequirementsSpec fromContract(List<UserStory> stories, List<Increment> increments) {
        return new RequirementsSpec("Pong", List.of(), List.of(), ROLES, stories, increments, "APP")
                .withContract(PONG_CONTRACT);
    }

    @Test
    void storiesFromTheContractPassAndJavaGroupsThemByItem() {
        var spec = fromContract(List.of(hu("HU-01", "MOD-01", esc("ESC-01")), hu("HU-02", "MOD-02", esc("ESC-02")),
                        hu("HU-03", "MOD-03", esc("ESC-03"))),
                List.of(new Increment(1, "Nivel 1 jugable", List.of("HU-01", "HU-02")),
                        new Increment(2, "Más velocidad", List.of("HU-03"))));
        assertEquals(List.of(), RequirementsSpecValidator.validate(spec));
        assertEquals("GAME", spec.experience());          // la experiencia la manda el contrato (ruling 1)
        assertTrue(spec.epics());
        assertEquals(List.of("MOD-01", "MOD-02"), spec.itemsOf(1).stream().map(ClientContract.Item::id).toList());
        assertEquals(List.of("MOD-02"), spec.gameLevelsOf(1).stream().map(ClientContract.Item::id).toList());
        assertEquals(List.of("MOD-02", "MOD-03"), spec.gameLevels().stream().map(ClientContract.Item::id).toList());
        assertEquals(List.of("HU-03"), spec.storiesOfItem("MOD-03").stream().map(UserStory::id).toList());
    }

    // Review Focus 4.
    @Test
    void everyStoryNeedsAnExistingItemAndEveryItemNeedsAStory() {
        var spec = fromContract(List.of(hu("HU-01", "MOD-01", esc("ESC-01")), hu("HU-02", null, esc("ESC-02")),
                        hu("HU-03", "MOD-09", esc("ESC-03"))),
                List.of(new Increment(1, "MVP", List.of("HU-01", "HU-02", "HU-03"))));
        var all = String.join("\n", RequirementsSpecValidator.validate(spec));
        assertTrue(all.contains("HU-02: contractItem debe decir a qué ítem del contrato pertenece"), all);
        assertTrue(all.contains("HU-03: contractItem MOD-09 no está en el contrato"), all);
        assertTrue(all.contains("MOD-02 (Nivel 1) no tiene historias"), all);
        assertTrue(all.contains("MOD-03 (Nivel 2) no tiene historias"), all);
    }

    @Test
    void inAGameALevelIsNeverSplitBetweenIncrements() {
        var spec = fromContract(List.of(hu("HU-01", "MOD-01", esc("ESC-01")), hu("HU-02", "MOD-02", esc("ESC-02")),
                        hu("HU-03", "MOD-02", esc("ESC-03")), hu("HU-04", "MOD-03", esc("ESC-04")),
                        hu("HU-05", "MOD-01", esc("ESC-05"))),
                List.of(new Increment(1, "MVP", List.of("HU-01", "HU-02")),
                        new Increment(2, "Resto", List.of("HU-03", "HU-04", "HU-05"))));
        var all = String.join("\n", RequirementsSpecValidator.validate(spec));
        assertTrue(all.contains("MOD-02 (Nivel 1) está partido en los incrementos [1, 2]"), all);
        // un SYSTEM sí puede crecer en varios incrementos
        assertFalse(all.contains("MOD-01 (Controles) está partido"), all);
    }

    // Review Focus 3: sin contrato (misión vieja) no hay reglas nuevas; withContract(null) limpia lo que invente el modelo.
    @Test
    void withoutAContractNothingChanges() {
        var old = spec(ROLES, List.of(hu("HU-01", "vendedor", List.of(), esc("ESC-01"))),
                List.of(new Increment(1, "MVP", List.of("HU-01"))));
        assertEquals(List.of(), RequirementsSpecValidator.validate(old));
        var invented = new RequirementsSpec("CRM", List.of(), List.of(), ROLES,
                List.of(hu("HU-01", "MOD-07", esc("ESC-01"))), List.of(new Increment(1, "MVP", List.of("HU-01"))),
                "APP", List.of(new ClientContract.Item("MOD-07", "MODULE", "x", "x")), true).withContract(null);
        assertEquals(List.of(), invented.contractItemsOrEmpty());
        assertFalse(invented.epics());
        assertEquals("APP", invented.experience());
        assertEquals(List.of(), RequirementsSpecValidator.validate(invented));
    }
```

El helper `hu(String, String, Scenario...)` es una sobrecarga nueva: el archivo ya tiene `hu(String, String, List<String>, Scenario...)`, que no cambia. Las HU del test usan el rol `vendedor` de `ROLES`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn test -Dtest=RequirementsSpecValidatorTest`
Expected: FAIL de compilación (constructor de 9 argumentos, `UserStory` de 8, `withContract` e `itemsOf` no existen).

- [ ] **Step 3: Implement the model**

En `RequirementsSpec.java`:

1. Cabecera del record (componentes nuevos al final) y constructores de compatibilidad:

```java
public record RequirementsSpec(String summary, @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> assumptions, @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> outOfScope, List<Role> roles,
                               List<UserStory> userStories, List<Increment> increments, String experience,
                               List<ClientContract.Item> contractItems, Boolean useEpics) {

    /** Specs guardados antes de Spec 2026-10-14 Kael §1: sin tipo de experiencia (null). */
    public RequirementsSpec(String summary, List<String> assumptions, List<String> outOfScope, List<Role> roles,
                            List<UserStory> userStories, List<Increment> increments) {
        this(summary, assumptions, outOfScope, roles, userStories, increments, null, null, null);
    }

    /** Specs anteriores al contrato (spec 2026-10-16): sin ítems del contrato ni épicas. */
    public RequirementsSpec(String summary, List<String> assumptions, List<String> outOfScope, List<Role> roles,
                            List<UserStory> userStories, List<Increment> increments, String experience) {
        this(summary, assumptions, outOfScope, roles, userStories, increments, experience, null, null);
    }
```

2. `UserStory` con `contractItem` al final, más el constructor de compatibilidad (dentro del record):

```java
    public record UserStory(String id, String role, String iWant, String soThat, @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> acceptanceCriteria,
                            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> dependsOn, List<Scenario> scenarios,
                            String contractItem) {

        /** HU anteriores al contrato (spec 2026-10-16): sin ítem. */
        public UserStory(String id, String role, String iWant, String soThat, List<String> acceptanceCriteria,
                         List<String> dependsOn, List<Scenario> scenarios) {
            this(id, role, iWant, soThat, acceptanceCriteria, dependsOn, scenarios, null);
        }
```

(el constructor compacto y los `…OrEmpty()` de `UserStory` quedan igual).

3. Métodos nuevos, después de `scenarioCount`:

```java
    private static final java.util.Set<String> PLAYABLE = java.util.Set.of("LEVEL", "SCENE");

    public List<ClientContract.Item> contractItemsOrEmpty() {
        return contractItems == null ? List.of() : contractItems.stream().filter(Objects::nonNull).toList();
    }

    public boolean epics() {
        return Boolean.TRUE.equals(useEpics);
    }

    /** Las HU de un ítem del contrato (su épica), en el orden de userStories. */
    public List<UserStory> storiesOfItem(String itemId) {
        return userStoriesOrEmpty().stream().filter(s -> itemId != null && itemId.equals(s.contractItem())).toList();
    }

    /** Los ítems del contrato con al menos una HU en el incremento, en el orden del contrato. */
    public List<ClientContract.Item> itemsOf(int increment) {
        var items = storiesOf(increment).stream().map(UserStory::contractItem).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        return contractItemsOrEmpty().stream().filter(i -> items.contains(i.id())).toList();
    }

    /** Los niveles y escenas del contrato (en un juego), en el orden en que se juegan. */
    public List<ClientContract.Item> gameLevels() {
        return contractItemsOrEmpty().stream().filter(i -> PLAYABLE.contains(i.kind())).toList();
    }

    public List<ClientContract.Item> gameLevelsOf(int increment) {
        return itemsOf(increment).stream().filter(i -> PLAYABLE.contains(i.kind())).toList();
    }

    /**
     * Contrato (spec 2026-10-16 contrato §2): Java copia al spec los ítems del contrato aprobado, si lleva épicas y su
     * experiencia (manda el contrato). Sin contrato (misión anterior) los deja vacíos aunque el modelo los haya inventado.
     */
    public RequirementsSpec withContract(ClientContract contract) {
        if (contract == null) {
            return new RequirementsSpec(summary, assumptions, outOfScope, roles, userStories, increments, experience,
                    null, null);
        }
        return new RequirementsSpec(summary, assumptions, outOfScope, roles, userStories, increments,
                contract.experience(), contract.itemsOrEmpty(), contract.epics());
    }
```

4. En `SCHEMA`, en las `properties` de `userStories`, agregar `"contractItem", Map.of("type", "string")` (sin sumarlo a `required`). El `Map.of` de esas propiedades pasa de 7 a 8 pares y alcanza. `contractItems` y `useEpics` **no** van en el schema: el modelo no los escribe.

- [ ] **Step 4: Implement the validator**

En `RequirementsSpecValidator`, antes de `return errors;`, llamar `validateContract(spec, errors);`:

```java
    /** Contrato (spec 2026-10-16 contrato §2): solo con los ítems del contrato aprobado (misiones viejas: nada). */
    private static void validateContract(RequirementsSpec spec, List<String> errors) {
        var items = spec.contractItemsOrEmpty();
        if (items.isEmpty()) {
            return;
        }
        var ids = items.stream().map(com.aicompany.core.agent.model.ClientContract.Item::id).toList();
        for (var story : spec.userStoriesOrEmpty()) {
            if (story.contractItem() == null || story.contractItem().isBlank()) {
                errors.add(story.id() + ": contractItem debe decir a qué ítem del contrato pertenece (" + ids + ").");
            } else if (!ids.contains(story.contractItem())) {
                errors.add(story.id() + ": contractItem " + story.contractItem() + " no está en el contrato " + ids + ".");
            }
        }
        var incrementOf = new HashMap<String, Integer>();
        spec.incrementsOrEmpty().forEach(i -> i.userStoryIdsOrEmpty().forEach(id -> incrementOf.put(id, i.number())));
        for (var item : items) {
            var stories = spec.storiesOfItem(item.id());
            if (stories.isEmpty()) {
                errors.add(item.id() + " (" + item.name() + ") no tiene historias: todo lo del contrato necesita al "
                        + "menos una HU.");
                continue;
            }
            if (spec.experienceOrNull() == RequirementsSpec.Experience.GAME
                    && ("LEVEL".equals(item.kind()) || "SCENE".equals(item.kind()))) {
                var increments = stories.stream().map(s -> incrementOf.get(s.id())).filter(java.util.Objects::nonNull)
                        .distinct().sorted().toList();
                if (increments.size() > 1) {
                    errors.add(item.id() + " (" + item.name() + ") está partido en los incrementos " + increments
                            + ": un nivel o escena va completo en un incremento.");
                }
            }
        }
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd app && mvn test -Dtest=RequirementsSpecValidatorTest` y después `cd app && mvn test`
Expected: PASS en ambos. Los tests que construyen `UserStory` con 7 argumentos o `RequirementsSpec` con 6 o 7 siguen compilando por los constructores conservados.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/model/RequirementsSpec.java app/src/main/java/com/aicompany/core/agent/validation/RequirementsSpecValidator.java app/src/test/java/com/aicompany/core/agent/validation/RequirementsSpecValidatorTest.java
git commit -m "Contrato: cada HU referencia su ítem del contrato y Java valida la cobertura y que un nivel no se parta

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Aria escribe las HU desde el contrato y el fundador las ve por épica

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/RequirementsWriter.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/RequirementsText.java`
- Test: `app/src/test/java/com/aicompany/core/service/RequirementsWriterTest.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/RequirementsTextTest.java`

**Interfaces:**
- Consumes:
  - `ContractMemoryService.approved` y `ContractMemoryService.Version` (Task 2);
  - `ContractText.forPrompt` y `ContractText.kindLabel` (Task 1);
  - `withContract`, `itemsOf` y `storiesOfItem` (Task 3).
- Produces:
  - `RequirementsWriter.setContracts(ContractMemoryService)`: setter con `@Autowired(required = false)`;
  - `RequirementsText.summary` y `incrementBlock` agrupan por épica cuando `spec.epics()`.
  - El resumen ya lo usan el mensaje de `AWAITING_SPEC_APPROVAL`, el detalle (`RequirementsView.summary`) y el chat, así que no hay que tocarlos.

- [ ] **Step 1: Write the failing tests**

En `RequirementsWriterTest` (imports: `com.aicompany.core.agent.model.ClientContract`, `org.mockito.ArgumentCaptor`):

```java
    private static final ClientContract CONTRACT = new ClientContract("Un CRM de ventas.", "APP",
            List.of(new ClientContract.Item("MOD-01", "MODULE", "Clientes", "alta y búsqueda")), List.of(), false,
            "pequeño");

    // Contrato (spec 2026-10-16 contrato §2): con contrato aprobado, Aria escribe desde él y Java copia sus ítems.
    @Test
    void withAnApprovedContractAriaWritesFromItAndJavaCopiesItsItems() {
        var contracts = mock(ContractMemoryService.class);
        when(contracts.approved("M-1")).thenReturn(Optional.of(new ContractMemoryService.Version(1,
                JsonMapper.builder().build().writeValueAsString(CONTRACT), "APPROVED", "", null)));
        writer.setContracts(contracts);
        var withItem = new RequirementsSpec("CRM", List.of(), List.of(), List.of(new Role("vendedor", "x")),
                List.of(new UserStory("HU-01", "vendedor", "q", "p", List.of("c"), List.of(),
                        List.of(new Scenario("ESC-01", "t", List.of("d"), List.of("c"), List.of("e"))), "MOD-01")),
                List.of(new Increment(1, "MVP", List.of("HU-01"))), "APP");
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceo.writeRequirements(eq("product-owner"), prompt.capture(), any(), any())).thenReturn(withItem);

        var written = writer.write("M-1", "un crm", Optional.empty(), null);

        assertEquals(List.of("MOD-01"), written.spec().contractItemsOrEmpty().stream().map(ClientContract.Item::id).toList());
        verify(requirements).saveDraft(eq("M-1"), argThat(json -> json.contains("\"contractItems\"")), any());
        assertTrue(prompt.getValue().contains("CONTRATO APROBADO POR EL CLIENTE"), prompt.getValue());
        assertTrue(prompt.getValue().contains("- MOD-01 [Módulo] Clientes: alta y búsqueda"), prompt.getValue());
        assertTrue(prompt.getValue().contains("contractItem"), prompt.getValue());
    }

    // Review Focus 3: sin contrato (misión vieja), el prompt y lo guardado no cambian.
    @Test
    void withoutAContractThePromptStaysAsBefore() {
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceo.writeRequirements(eq("product-owner"), prompt.capture(), any(), any())).thenReturn(VALID);

        var written = writer.write("M-1", "un crm", Optional.empty(), null);

        assertTrue(prompt.getValue().contains("PEDIDO DEL FUNDADOR"), prompt.getValue());
        assertFalse(prompt.getValue().contains("CONTRATO APROBADO"), prompt.getValue());
        assertEquals(List.of(), written.spec().contractItemsOrEmpty());
    }
```

En `RequirementsTextTest` (imports: `com.aicompany.core.agent.model.ClientContract`):

```java
    private static final RequirementsSpec PONG = new RequirementsSpec("Pong", List.of(), List.of(),
            List.of(new Role("jugador", "juega")),
            List.of(new UserStory("HU-01", "jugador", "mover la paleta", "devolver", List.of("c"), List.of(),
                            List.of(new Scenario("ESC-01", "mueve", List.of("d"), List.of("c"), List.of("e"))), "MOD-01"),
                    new UserStory("HU-02", "jugador", "jugar hasta 50 puntos", "ganar", List.of("c"), List.of(),
                            List.of(new Scenario("ESC-02", "punto", List.of("d"), List.of("c"), List.of("e"))), "MOD-02"),
                    new UserStory("HU-03", "jugador", "que la pelota acelere", "un reto", List.of("c"), List.of(),
                            List.of(new Scenario("ESC-03", "rápida", List.of("d"), List.of("c"), List.of("e"))), "MOD-03")),
            List.of(new Increment(1, "Nivel 1 jugable", List.of("HU-01", "HU-02")),
                    new Increment(2, "Más velocidad", List.of("HU-03"))), "GAME",
            List.of(new ClientContract.Item("MOD-01", "SYSTEM", "Controles", "paletas"),
                    new ClientContract.Item("MOD-02", "LEVEL", "Nivel 1", "pelota normal hasta 50 puntos"),
                    new ClientContract.Item("MOD-03", "LEVEL", "Nivel 2", "la pelota aumenta de velocidad")), true);

    // Contrato (spec 2026-10-16 contrato §2): con épicas, el fundador y el equipo ven las HU agrupadas por ítem.
    @Test
    void theSummaryGroupsTheStoriesByEpic() {
        var summary = RequirementsText.summary(PONG);
        assertTrue(summary.contains("Incremento 1 (Nivel 1 jugable): Épica MOD-01 Controles — HU-01 mover la paleta"
                + " · Épica MOD-02 Nivel 1 — HU-02 jugar hasta 50 puntos"), summary);
        assertTrue(summary.contains("Incremento 2 (Más velocidad): Épica MOD-03 Nivel 2 — HU-03 que la pelota acelere"), summary);
    }

    @Test
    void theIncrementBlockListsItsEpics() {
        var block = RequirementsText.incrementBlock(PONG, 2);
        assertTrue(block.contains("Épicas de este incremento:\n- MOD-03 [Nivel] Nivel 2: la pelota aumenta de velocidad — HU-03"), block);
        assertFalse(block.contains("- MOD-02 [Nivel]"), block);
    }

    // Review Focus 3: sin épicas (app pequeña o misión vieja) los textos no cambian.
    @Test
    void withoutEpicsTheTextsDoNotChange() {
        assertFalse(RequirementsText.summary(SPEC).contains("Épica"), RequirementsText.summary(SPEC));
        assertFalse(RequirementsText.incrementBlock(SPEC, 1).contains("Épicas de este incremento"));
    }
```

`SPEC` es la constante sin contrato que ya usa el archivo; si se llama distinto, usar esa.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn test -Dtest='RequirementsWriterTest,RequirementsTextTest'`
Expected: FAIL (`setContracts` no existe; los textos no agrupan por épica).

- [ ] **Step 3: Implement the writer**

En `RequirementsWriter`:

1. Campo y setter (después del constructor):

```java
    private ContractMemoryService contracts;

    /** Contrato (spec 2026-10-16 contrato §2): opcional; sin él (o sin contrato aprobado) Aria escribe desde el pedido. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setContracts(ContractMemoryService contracts) {
        this.contracts = contracts;
    }

    private Optional<com.aicompany.core.agent.model.ClientContract> approvedContract(String missionId) {
        if (contracts == null) {
            return Optional.empty();
        }
        return contracts.approved(missionId).map(v -> json.readValue(v.contractJson(),
                com.aicompany.core.agent.model.ClientContract.class));
    }
```

2. En `write`, antes de `var base = prompt(...)`: `var contract = approvedContract(missionId);`. Cambiar la llamada a `prompt(instruction, previous, adjustment, contract)`. Justo después de `spec = ceo.writeRequirements(ARIA, prompt, agentPrompt, model);` (dentro del `try`) agregar `spec = spec == null ? null : spec.withContract(contract.orElse(null));`. Así lo validado, lo guardado y lo que ve la corrección (`understood`) ya tienen los ítems.

3. `prompt` recibe `Optional<ClientContract> contract`. Reemplazar la línea `PEDIDO DEL FUNDADOR:\n%s` del text block por `%s`, y como primer argumento de `.formatted(...)` pasar:

```java
        var source = contract.isEmpty() ? "PEDIDO DEL FUNDADOR:\n" + instruction
                : "CONTRATO APROBADO POR EL CLIENTE (la fuente de todo lo que escribes):\n"
                  + com.aicompany.core.agent.validation.ContractText.forPrompt(contract.get())
                  + "PEDIDO ORIGINAL (referencia): " + instruction;
```

Si hay contrato, agregar a `REGLAS` (al final de `base`, antes del JSON de respuesta, como texto concatenado):

```java
        var contractRules = contract.isEmpty() ? "" : """
                - contractItem: cada historia dice a qué ítem del contrato pertenece (MOD-NN). Todo ítem del contrato
                  tiene al menos una historia; no escribas historias de algo que el contrato no pidió. experience es la
                  del contrato.
                - increments: un módulo, nivel o escena por incremento cuando se puede usar o jugar solo; si no,
                  agrúpalos y explica por qué en el goal. En un juego, un nivel o escena va completo en un solo
                  incremento y el incremento 1 debe ser jugable de punta a punta. Un SYSTEM puede crecer en varios.
                """;
```

La forma más simple de insertarlo es partir el text block en dos en la línea `Responde SOLO este JSON…`: `reglas + contractRules + respuesta`. Con contrato, el JSON de ejemplo de `userStories` suma `"contractItem": "MOD-01"`: aplicar `.replace("\"dependsOn\": [\"HU-..\"],", "\"dependsOn\": [\"HU-..\"], \"contractItem\": \"MOD-01\",")` sobre el texto de respuesta solo cuando `contract.isPresent()`. Sin contrato, el prompt queda idéntico al de hoy.

- [ ] **Step 4: Implement the texts**

En `RequirementsText`:

1. Helper para el texto de una épica:

```java
    private static String storyIds(RequirementsSpec spec, int increment, String itemId) {
        return spec.storiesOf(increment).stream().filter(s -> itemId.equals(s.contractItem()))
                .map(RequirementsSpec.UserStory::id).collect(Collectors.joining(", "));
    }
```

2. En `incrementBlock`, antes del `return`:

```java
        var epics = !spec.epics() ? "" : "Épicas de este incremento:\n" + spec.itemsOf(increment).stream()
                .map(i -> "- " + i.id() + " [" + ContractText.kindLabel(i.kind()) + "] " + i.name() + ": "
                        + i.description() + " — " + storyIds(spec, increment, i.id()))
                .collect(Collectors.joining("\n")) + "\n";
```

y en el text block, cambiar la línea `Construye SOLO estas historias…` por `%sConstruye SOLO estas historias…`, pasando `epics` en `.formatted(...)` justo antes de `stories`. `epics` termina en `\n` o es `""`, así que sin épicas el texto queda igual que hoy.

3. En `summary`, reemplazar el armado de `increments` por:

```java
        var increments = spec.incrementsOrEmpty().stream().map(i -> {
                    var body = !spec.epics()
                            ? spec.storiesOf(i.number()).stream().map(s -> s.id() + " " + s.iWant())
                                    .collect(Collectors.joining(", "))
                            : spec.itemsOf(i.number()).stream().map(item -> "Épica " + item.id() + " " + item.name()
                                    + " — " + spec.storiesOf(i.number()).stream()
                                    .filter(s -> item.id().equals(s.contractItem()))
                                    .map(s -> s.id() + " " + s.iWant()).collect(Collectors.joining(", ")))
                                    .collect(Collectors.joining(" · "));
                    return "Incremento " + i.number() + " (" + i.goal() + "): " + body;
                })
                .collect(Collectors.joining(". "));
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd app && mvn test -Dtest='RequirementsWriterTest,RequirementsTextTest,MissionExecutor*Test,ChatIntentRouterTest'`
Expected: PASS. Los tests del chat y del ejecutor no cambian: los specs sin épicas producen el mismo texto.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/RequirementsWriter.java app/src/main/java/com/aicompany/core/agent/validation/RequirementsText.java app/src/test/java/com/aicompany/core/service/RequirementsWriterTest.java app/src/test/java/com/aicompany/core/agent/validation/RequirementsTextTest.java
git commit -m "Contrato: Aria escribe las HU desde el contrato aprobado y el fundador las ve agrupadas por épica

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: La misión se detiene en el contrato y lo retoma tras un reinicio

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java` (`executeTeamMission` ~561, `resumeInternal` ~808, junto a `continueAfterApprovalAsync` ~1044)
- Modify: `app/src/main/java/com/aicompany/core/recovery/ResumePoint.java`
- Modify: `app/src/main/java/com/aicompany/core/recovery/MissionSnapshot.java`
- Modify: `app/src/main/java/com/aicompany/core/recovery/MissionRecoveryService.java` (~455, setters ~257)
- Test: `app/src/test/java/com/aicompany/core/service/MissionExecutorTeamTest.java`
- Test: `app/src/test/java/com/aicompany/core/recovery/ResumePointTest.java`

**Interfaces:**
- Consumes: `ContractWriter.write/Written`, `ContractMemoryService` (Task 2); `ContractText.summary` (Task 1).
- Produces:
  - En `MissionExecutor`:
    - `setContracts(ContractWriter, ContractMemoryService)`, con `@Autowired(required = false)`;
    - `CompletableFuture<Void> continueAfterContractAsync(String missionId)`;
    - `CompletableFuture<Void> adjustContractAsync(String missionId, String adjustment)`.
  - `ResumePoint.Stage.CONTRACT_ADJUSTMENT`.
  - `MissionSnapshot` gana `String latestContractStatus, String pendingContractAdjustment` al final. El constructor de 15 argumentos de hoy se conserva y los deja en null.
  - `MissionRecoveryService.setContracts(ContractMemoryService)`, con `@Autowired(required = false)`.
  - Eventos `EMPRESA_CONTRACT_READY`, `EMPRESA_CONTRACT_APPROVED` (por policy), `EMPRESA_CONTRACT_ADJUSTED`.

- [ ] **Step 1: Write the failing tests**

En `MissionExecutorTeamTest`, junto a `withAria()`:

```java
    private final ContractWriter contractWriter = mock(ContractWriter.class);
    private final ContractMemoryService contracts = mock(ContractMemoryService.class);
    private static final com.aicompany.core.agent.model.ClientContract CONTRACT =
            new com.aicompany.core.agent.model.ClientContract("Un CRM.", "APP",
                    List.of(new com.aicompany.core.agent.model.ClientContract.Item("MOD-01", "MODULE", "Clientes", "alta")),
                    List.of(), false, "pequeño");

    private void withContract() throws Exception {
        withAria();
        executor.setContracts(contractWriter, contracts);
        when(contractWriter.write(eq("M-9"), eq("Un CRM"), any(), any()))
                .thenReturn(new ContractWriter.Written(1, CONTRACT));
    }

    // Contrato (spec 2026-10-16 contrato §1): la misión se detiene en el contrato; Aria no escribe HU todavía.
    @Test
    void aDevelopmentMissionStopsAtTheContractWaitingForTheFounder() throws Exception {
        withContract();
        when(companyPolicyService.activeValue(PolicyKey.SPEC_APPROVAL_REQUIRED)).thenReturn(1.0);

        executor.executeAsync("M-9", "Un CRM").get();

        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.AWAITING_SPEC_APPROVAL), anyInt(),
                eq("Contrato listo (Aria)"), argThat(m -> m.contains("MOD-01 [Módulo] Clientes")));
        verify(writer, never()).write(any(), any(), any(), any());
        verify(events).publish(eq("EMPRESA_CONTRACT_READY"), eq("M-9"), isNull(), eq("product-owner"), anyMap());
    }

    @Test
    void withAnApprovedContractTheMissionGoesStraightToTheStories() throws Exception {
        withContract();
        when(contracts.approved("M-9")).thenReturn(Optional.of(new ContractMemoryService.Version(1, "{}", "APPROVED", "", null)));
        when(companyPolicyService.activeValue(PolicyKey.SPEC_APPROVAL_REQUIRED)).thenReturn(1.0);

        executor.executeAsync("M-9", "Un CRM").get();

        verify(contractWriter, never()).write(any(), any(), any(), any());
        verify(writer).write(eq("M-9"), eq("Un CRM"), eq(Optional.empty()), isNull());
        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.AWAITING_SPEC_APPROVAL), anyInt(),
                eq("Requisitos listos (Aria)"), anyString());
    }

    @Test
    void withTheApprovalPolicyOffTheContractIsApprovedByPolicyAndAriaWritesTheStories() throws Exception {
        withContract();
        when(companyPolicyService.activeValue(PolicyKey.SPEC_APPROVAL_REQUIRED)).thenReturn(0.0);
        when(planner.plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), anyString(), any())).thenReturn(planned("TEAM-DEVELOPMENT"));

        executor.executeAsync("M-9", "Un CRM").get();

        var order = inOrder(contracts, writer);
        order.verify(contracts).approve("M-9", 1, "policy");
        order.verify(writer).write(eq("M-9"), eq("Un CRM"), eq(Optional.empty()), isNull());
        verify(events).publish(eq("EMPRESA_CONTRACT_APPROVED"), eq("M-9"), isNull(), eq("policy"), anyMap());
    }

    @Test
    void afterApprovingTheContractAriaWritesTheStoriesAndWaitsAgain() throws Exception {
        withContract();
        when(companyPolicyService.activeValue(PolicyKey.SPEC_APPROVAL_REQUIRED)).thenReturn(1.0);

        executor.continueAfterContractAsync("M-9").get();

        verify(writer).write(eq("M-9"), eq("Un CRM"), eq(Optional.empty()), isNull());
        verify(contractWriter, never()).write(any(), any(), any(), any());
        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.AWAITING_SPEC_APPROVAL), anyInt(),
                eq("Requisitos listos (Aria)"), anyString());
    }

    // Review Focus 1: el ajuste parte de la versión anterior y la misión vuelve a esperar el contrato.
    @Test
    void adjustingTheContractRewritesItAndWaitsAgain() throws Exception {
        withContract();
        when(contracts.latest("M-9")).thenReturn(Optional.of(new ContractMemoryService.Version(1,
                JsonMapper.builder().build().writeValueAsString(CONTRACT), "DRAFT", "", null)));
        when(contractWriter.write(eq("M-9"), eq("Un CRM"), eq(Optional.of(CONTRACT)), eq("agrega inventarios")))
                .thenReturn(new ContractWriter.Written(2, CONTRACT));

        executor.adjustContractAsync("M-9", "agrega inventarios").get();

        verify(events).publish(eq("EMPRESA_CONTRACT_ADJUSTED"), eq("M-9"), isNull(), eq("human"),
                argThat(d -> Integer.valueOf(2).equals(d.get("version"))));
        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.AWAITING_SPEC_APPROVAL), anyInt(),
                eq("Contrato listo (Aria)"), argThat(m -> m.contains("versión 2")));
        verify(writer, never()).write(any(), any(), any(), any());
    }

    // Review Focus 2: al retomar, un contrato en DRAFT vuelve a la espera y un ajuste reclamado se repite.
    @Test
    void resumingWithTheContractInDraftWaitsForTheFounderAgain() throws Exception {
        withContract();
        when(contracts.latest("M-9")).thenReturn(Optional.of(new ContractMemoryService.Version(3,
                JsonMapper.builder().build().writeValueAsString(CONTRACT), "DRAFT", "", null)));

        executor.resumeAsync("M-9", resumeAt(com.aicompany.core.recovery.ResumePoint.Stage.AWAIT_APPROVAL)).get();

        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.AWAITING_SPEC_APPROVAL), anyInt(),
                eq("Contrato listo (Aria)"), argThat(m -> m.contains("versión 3")));
        verifyNoInteractions(contractWriter);
    }

    @Test
    void resumingAClaimedContractAdjustmentRepeatsIt() throws Exception {
        withContract();
        when(contracts.latest("M-9")).thenReturn(Optional.of(new ContractMemoryService.Version(1,
                JsonMapper.builder().build().writeValueAsString(CONTRACT), "DRAFT", "", null)));
        when(contracts.pendingAdjustment("M-9")).thenReturn(Optional.of("agrega inventarios"));
        when(contractWriter.write(eq("M-9"), eq("Un CRM"), eq(Optional.of(CONTRACT)), eq("agrega inventarios")))
                .thenReturn(new ContractWriter.Written(2, CONTRACT));

        executor.resumeAsync("M-9", resumeAt(com.aicompany.core.recovery.ResumePoint.Stage.CONTRACT_ADJUSTMENT)).get();

        verify(contractWriter).write(eq("M-9"), eq("Un CRM"), eq(Optional.of(CONTRACT)), eq("agrega inventarios"));
    }
```

`resumeAt(Stage)` ya existe en el archivo (línea ~820). `withAria()` stubbea `writer.write(... Optional.empty(), isNull())` y `requirements.approved`, así que el resto del flujo sigue igual que en los tests vecinos.

En `ResumePointTest`:

```java
    // Contrato (spec 2026-10-16 contrato §1): el contrato en DRAFT espera; un ajuste reclamado se repite.
    private static MissionSnapshot withContract(String contractStatus, String contractAdjustment) {
        return new MissionSnapshot("M-1", "TEAM-DEVELOPMENT", 0, null, false, null, false, null, false, List.of(), null,
                false, false, null, false, contractStatus, contractAdjustment);
    }

    @Test void aDraftContractWaitsForTheFounder() {
        assertEquals(ResumePoint.Stage.AWAIT_APPROVAL, ResumePoint.of(withContract("DRAFT", null)).stage());
    }

    @Test void aClaimedContractAdjustmentIsRepeated() {
        assertEquals(ResumePoint.Stage.CONTRACT_ADJUSTMENT, ResumePoint.of(withContract("DRAFT", "agrega inventarios")).stage());
    }

    @Test void anApprovedContractWithoutStoriesGoesToAria() {
        assertEquals(ResumePoint.Stage.REQUIREMENTS, ResumePoint.of(withContract("APPROVED", null)).stage());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn test -Dtest='MissionExecutorTeamTest,ResumePointTest'`
Expected: FAIL de compilación (`setContracts`, `continueAfterContractAsync`, `adjustContractAsync`, `CONTRACT_ADJUSTMENT` y el constructor de 17 argumentos no existen).

- [ ] **Step 3: Implement the recovery model**

`MissionSnapshot`: agregar `String latestContractStatus, String pendingContractAdjustment` al final de los componentes. Agregar al javadoc: `{@code latestContractStatus}/{@code pendingContractAdjustment} (contrato, spec 2026-10-16): estado del último contrato y el ajuste reclamado sin terminar, o null.`. Agregar el constructor de compatibilidad de 15 argumentos (el canónico de hoy):

```java
    public MissionSnapshot(String missionId, String teamId, int round, Integer incrementOfRound, boolean correctionRound,
                           String latestRequirementsStatus, boolean requirementsApproved, String pendingAdjustment,
                           boolean planForRound, List<RoundTask> workTasks, String humanVerifyTask,
                           boolean designNeeded, boolean designForLatestSpec, String pendingDesignAdjustment,
                           boolean jointReviewPending) {
        this(missionId, teamId, round, incrementOfRound, correctionRound, latestRequirementsStatus, requirementsApproved,
                pendingAdjustment, planForRound, workTasks, humanVerifyTask, designNeeded, designForLatestSpec,
                pendingDesignAdjustment, jointReviewPending, null, null);
    }
```

El constructor de 14 argumentos de hoy llama a `this(..., false)` con 15 argumentos y sigue funcionando a través de este.

`ResumePoint`: agregar `CONTRACT_ADJUSTMENT` al enum `Stage`, con el javadoc «CONTRACT_ADJUSTMENT (spec 2026-10-16): Aria rehace el contrato con el ajuste reclamado; un contrato DRAFT se retoma en AWAIT_APPROVAL.». En `of`, al principio del bloque `if (!s.requirementsApproved()) {`:

```java
            // Contrato (spec 2026-10-16): mientras el contrato espera, todavía no hay HU.
            if (s.pendingContractAdjustment() != null) {
                return new ResumePoint(Stage.CONTRACT_ADJUSTMENT, s.round(), kind, increment);
            }
            if ("DRAFT".equals(s.latestContractStatus())) {
                return new ResumePoint(Stage.AWAIT_APPROVAL, s.round(), kind, increment);
            }
```

`MissionRecoveryService`:

```java
    private com.aicompany.core.service.ContractMemoryService contracts;

    /** Contrato (spec 2026-10-16): para retomar el contrato (opcional en tests: sin él, nunca se retoma ahí). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setContracts(com.aicompany.core.service.ContractMemoryService contracts) {
        this.contracts = contracts;
    }
```

y en el `return new MissionSnapshot(...)` (línea ~477), agregar los dos argumentos finales:

```java
                jointReviewPending,
                contracts == null ? null : contracts.latest(missionId).map(c -> c.status()).orElse(null),
                contracts == null ? null : contracts.pendingAdjustment(missionId).orElse(null));
```

(Si `MissionRecoveryService` importa `com.aicompany.core.service.*`, usar el nombre corto.)

- [ ] **Step 4: Implement the executor**

En `MissionExecutor`:

1. Campos y setter, después de `ariaApplies`:

```java
    private ContractWriter contractWriter;
    private ContractMemoryService contractMemory;

    /** Contrato (spec 2026-10-16): opcional (sin él, Aria escribe las HU directo desde el pedido, como antes). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setContracts(ContractWriter contractWriter, ContractMemoryService contractMemory) {
        this.contractWriter = contractWriter;
        this.contractMemory = contractMemory;
    }

    private boolean contractApplies() {
        return contractWriter != null && contractMemory != null;
    }

    /** Contrato (spec 2026-10-16 §1): el fundador (el cliente) aprueba o ajusta el contrato antes de que haya HU. */
    private void awaitContractApproval(String missionId, ContractWriter.Written written) {
        advanceMission(missionId, MissionStatus.AWAITING_SPEC_APPROVAL, 4, "Contrato listo (Aria)",
                "Aria levantó el contrato con tu pedido. Espera tu aprobación antes de escribir las historias: "
                        + com.aicompany.core.agent.validation.ContractText.summary(written.contract(), written.version()));
    }

    private ContractWriter.Written latestContract(String missionId) {
        var latest = contractMemory.latest(missionId).orElseThrow();
        return new ContractWriter.Written(latest.version(), jsonMapper.readValue(latest.contractJson(),
                com.aicompany.core.agent.model.ClientContract.class));
    }

    private ContractWriter.Written rewriteContract(String missionId, String instruction, String adjustment) {
        var previous = contractMemory.latest(missionId).map(v -> jsonMapper.readValue(v.contractJson(),
                com.aicompany.core.agent.model.ClientContract.class));
        return contractWriter.write(missionId, instruction, previous, adjustment);
    }
```

2. En `executeTeamMission`, reemplazar el bloque `if (ariaApplies(mode)) { … return; }` por:

```java
        if (ariaApplies(mode)) {
            // Contrato (spec 2026-10-16 §1): primero el contrato con el cliente; nunca se repite uno aprobado.
            if (contractApplies() && contractMemory.approved(missionId).isEmpty()) {
                advanceMission(missionId, MissionStatus.PLANNING, 2, "Contrato (Aria)",
                        "Aria está levantando el contrato con tu pedido.");
                var contract = contractWriter.write(missionId, instruction, java.util.Optional.empty(), null);
                events.publish("EMPRESA_CONTRACT_READY", missionId, null, RequirementsWriter.ARIA,
                        Map.of("version", contract.version(), "items", contract.contract().itemsOrEmpty().size()));
                if (companyPolicyService.activeValue(PolicyKey.SPEC_APPROVAL_REQUIRED) >= 1.0) {
                    awaitContractApproval(missionId, contract);
                    return;
                }
                contractMemory.approve(missionId, contract.version(), "policy");
                events.publish("EMPRESA_CONTRACT_APPROVED", missionId, null, "policy",
                        Map.of("version", contract.version(), "approvedBy", "policy"));
            }
            writeStoriesAndDesign(missionId, instruction, teamId, mode);
            return;
        }
```

y mover el cuerpo de antes (desde `advanceMission(missionId, MissionStatus.PLANNING, 3, "Requisitos (Aria)", …)` hasta `planAndRunIncrement(missionId, instruction, teamId, mode, written.spec(), 1, 0);`) **sin cambios** a:

```java
    /** Spec 2026-10-03 Aria + 2026-10-14 Kael + revisión conjunta: HU, diseño y revisión; luego espera o planifica. */
    private void writeStoriesAndDesign(String missionId, String instruction, String teamId, TeamExecutionMode mode) {
        // … cuerpo movido tal cual …
    }
```

3. Junto a `continueAfterApprovalAsync`:

```java
    /** Contrato (spec 2026-10-16 §1): aprobado el contrato, Aria escribe las HU desde él. */
    public CompletableFuture<Void> continueAfterContractAsync(String missionId) {
        // Síncrono, como continueAfterApprovalAsync: la misión deja AWAITING_SPEC_APPROVAL aunque el hilo espere en la cola.
        advanceMission(missionId, MissionStatus.PLANNING, 3, "Requisitos (Aria)",
                "Contrato aprobado: Aria escribe las historias de usuario.");
        return submit(missionId, "continue after contract", () -> {
            var teamId = memory.teamId(missionId).orElseThrow();
            var instruction = memory.instructionOf(missionId).orElse("");
            var mode = TeamExecutionMode.forTeamType(TeamMemoryService.teamType(teamId).orElseThrow());
            writeStoriesAndDesign(missionId, instruction, teamId, mode);
        });
    }

    /** Contrato (spec 2026-10-16 §1): Aria rehace el contrato con el ajuste del fundador y vuelve a esperar. */
    public CompletableFuture<Void> adjustContractAsync(String missionId, String adjustment) {
        advanceMission(missionId, MissionStatus.PLANNING, 2, "Contrato (Aria)", "Aria ajusta el contrato: " + adjustment);
        return submit(missionId, "contract adjustment", () -> adjustContract(missionId, adjustment));
    }

    private void adjustContract(String missionId, String adjustment) {
        var written = rewriteContract(missionId, memory.instructionOf(missionId).orElse(""), adjustment);
        events.publish("EMPRESA_CONTRACT_ADJUSTED", missionId, null, "human",
                Map.of("version", written.version(), "instruction", adjustment));
        awaitContractApproval(missionId, written);
    }
```

4. En `resumeInternal`:
   - el `case AWAIT_APPROVAL` empieza con el contrato:

```java
            case AWAIT_APPROVAL -> {
                // Contrato (spec 2026-10-16): un contrato en DRAFT es lo que espera al fundador (todavía no hay HU).
                if (contractApplies() && contractMemory.latest(missionId).filter(c -> "DRAFT".equals(c.status())).isPresent()) {
                    awaitContractApproval(missionId, latestContract(missionId));
                    return;
                }
                var written = latestRequirements(missionId);
                publishReady(missionId, written);
                awaitApproval(missionId, written, draftDesignFor(missionId, written.version()));
            }
```

   - y se agrega `case CONTRACT_ADJUSTMENT -> adjustContract(missionId, contractMemory.pendingAdjustment(missionId).orElseThrow());`.

   Con `return` dentro de un `case ->` de un switch statement en un método `void`, el `switch` compila. Si el compilador se queja, usar `if/else`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd app && mvn test -Dtest='MissionExecutorTeamTest,MissionExecutorTest,ResumePointTest,MissionRecoveryServiceTest'`
Expected: PASS. Los tests existentes no llaman a `setContracts`, así que siguen por el flujo de antes.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/MissionExecutor.java app/src/main/java/com/aicompany/core/recovery/ResumePoint.java app/src/main/java/com/aicompany/core/recovery/MissionSnapshot.java app/src/main/java/com/aicompany/core/recovery/MissionRecoveryService.java app/src/test/java/com/aicompany/core/service/MissionExecutorTeamTest.java app/src/test/java/com/aicompany/core/recovery/ResumePointTest.java
git commit -m "Contrato: la misión se detiene en el contrato, lo ajusta y lo retoma tras un reinicio

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: El fundador decide el contrato (botones, API y chat)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/ContractView.java`
- Modify: `app/src/main/java/com/aicompany/core/model/RequirementsView.java`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionService.java` (`requirements` ~258, `decideRequirements` ~277, setters ~233)
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java` (patrones ~1158, `handleRequirements` ~1241, `isRequirementsCommand` ~494, `formatCompanyStatus` ~2216)
- Test: `app/src/test/java/com/aicompany/core/service/MissionServiceRequirementsTest.java`
- Test: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes:
  - `ContractMemoryService` (Task 2);
  - `MissionExecutor.continueAfterContractAsync` y `adjustContractAsync` (Task 5);
  - `ContractText.summary` (Task 1).
- Produces:
  - `record ContractView(int version, String status, Integer approvedVersion, ClientContract contract, String summary)`;
  - `RequirementsView` gana `ContractView contract` al final y conserva un constructor de 9 argumentos (`contract` null);
  - `MissionService.setContracts(ContractMemoryService)`, con `@Autowired(required = false)`;
  - eventos `EMPRESA_CONTRACT_APPROVED` (`human`) y `EMPRESA_CONTRACT_REJECTED`.
  - El endpoint `POST /{missionId}/requirements/decision` y los botones de hoy deciden el contrato sin cambios en el controller.

- [ ] **Step 1: Write the failing tests**

En `MissionServiceRequirementsTest`:

```java
    private final ContractMemoryService contracts = mock(ContractMemoryService.class);

    private void withDraftContract(int version) {
        service.setContracts(contracts);
        when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_SPEC_APPROVAL)));
        when(contracts.latest("M-9")).thenReturn(Optional.of(new ContractMemoryService.Version(version,
                "{\"summary\":\"Un CRM.\",\"experience\":\"APP\",\"items\":[],\"outOfScope\":[],\"useEpics\":false,"
                        + "\"epicsReason\":\"x\"}", "DRAFT", "", null)));
        when(contracts.claimDecision(anyString(), anyInt(), any())).thenReturn(true);
    }

    // Contrato (spec 2026-10-16 contrato §1): con el contrato en DRAFT, la decisión es sobre el contrato.
    @Test
    void approvingADraftContractContinuesToTheStories() {
        withDraftContract(2);

        var view = service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.APPROVE, null));

        verify(contracts).approve("M-9", 2, "founder");
        verify(executor).continueAfterContractAsync("M-9");
        verify(executor, never()).continueAfterApprovalAsync(anyString());
        verify(requirements, never()).approve(anyString(), anyInt(), anyString());
        verify(events).publish(eq("EMPRESA_CONTRACT_APPROVED"), eq("M-9"), isNull(), eq("human"), anyMap());
        assertNotNull(view);
    }

    @Test
    void adjustingADraftContractGoesToAriaAndKaelCannotAdjustIt() {
        withDraftContract(1);

        service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.ADJUST, " agrega inventarios "));
        verify(contracts).claimDecision("M-9", 1, "agrega inventarios");
        verify(executor).adjustContractAsync("M-9", "agrega inventarios");

        assertThrows(IllegalStateException.class, () -> service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.ADJUST, "x", RequirementsDecisionCommand.Target.KAEL)));
        assertThrows(IllegalArgumentException.class, () -> service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.ADJUST, " ")));
    }

    @Test
    void rejectingADraftContractCancelsTheMission() {
        withDraftContract(1);

        service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.REJECT, null));

        verify(contracts).reject("M-9", 1);
        verify(memory).updateMission(eq("M-9"), eq(MissionStatus.CANCELLED), eq(100), eq("Contrato rechazado"), anyString());
        verify(events).publish(eq("EMPRESA_CONTRACT_REJECTED"), eq("M-9"), isNull(), eq("human"), anyMap());
    }

    // Review Focus 1: doble clic sobre el contrato.
    @Test
    void aSecondDecisionOnTheSameContractVersionIsRejected() {
        withDraftContract(1);
        when(contracts.claimDecision(anyString(), anyInt(), any())).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> service.decideRequirements("M-9", new RequirementsDecisionCommand(
                RequirementsDecisionCommand.RequirementsDecision.APPROVE, null)));
        verify(executor, never()).continueAfterContractAsync(anyString());
    }

    @Test
    void theViewIncludesTheContract() {
        withDraftContract(1);

        var view = service.requirements("M-9").orElseThrow();

        assertEquals(1, view.contract().version());
        assertEquals("DRAFT", view.contract().status());
        assertTrue(view.contract().summary().startsWith("Contrato (versión 1, aplicación): Un CRM."), view.contract().summary());
    }
```

`RequirementsDecisionCommand(decision, instruction, Target)` y el de 2 argumentos (Target ARIA) ya existen. `missionIn` existe en el archivo.

En `ChatIntentRouterTest`, siguiendo el patrón de los tests de «aprueba las HU de MISSION-X» del archivo (buscar `APPROVE` o `historias` y copiar cómo mockean `missionService`):

```java
    // Contrato (spec 2026-10-16 contrato §1) + Review Focus 5: el contrato se ve, se aprueba y se rechaza por chat.
    @Test
    void theFounderSeesAndApprovesTheContractByChat() {
        var contract = new com.aicompany.core.model.ContractView(1, "DRAFT", null, null, "Contrato (versión 1, juego): Pong");
        when(missionService.requirements("MISSION-X")).thenReturn(Optional.of(new RequirementsView("MISSION-X",
                "AWAITING_SPEC_APPROVAL", null, null, null, 0, 0, null, "", contract)));

        assertTrue(router.route("muéstrame el contrato de MISSION-X").contains("Contrato (versión 1, juego): Pong"));
        assertTrue(router.route("aprueba el contrato de MISSION-X").contains("Contrato de MISSION-X aprobado: Aria escribe las historias"));
        verify(missionService).decideRequirements(eq("MISSION-X"), argThat(c ->
                c.decision() == RequirementsDecisionCommand.RequirementsDecision.APPROVE));
        verify(missionService, never()).recordDecision(anyString(), any());
    }

    @Test
    void anAdjustmentWhileTheContractWaitsGoesToAria() {
        var contract = new com.aicompany.core.model.ContractView(1, "DRAFT", null, null, "Contrato");
        when(missionService.requirements("MISSION-X")).thenReturn(Optional.of(new RequirementsView("MISSION-X",
                "AWAITING_SPEC_APPROVAL", null, null, null, 0, 0, null, "", contract)));

        assertTrue(router.route("para MISSION-X: el nivel 3 tiene dos pelotas").contains("contrato de MISSION-X"));
    }
```

`router.route(String)` devuelve el texto de la respuesta, como en el resto del archivo. `MISSION-X` matchea `MISSION_REF`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn test -Dtest='MissionServiceRequirementsTest,ChatIntentRouterTest'`
Expected: FAIL de compilación (`ContractView`, `setContracts` y el constructor de 10 argumentos de `RequirementsView` no existen).

- [ ] **Step 3: Implement the views**

`ContractView.java`:

```java
package com.aicompany.core.model;

import com.aicompany.core.agent.model.ClientContract;

/** Contrato (spec 2026-10-16 contrato §1): el último contrato de la misión, su estado y el aprobado. */
public record ContractView(int version, String status, Integer approvedVersion, ClientContract contract, String summary) {
}
```

`RequirementsView`: agregar `ContractView contract` como último componente y el constructor de compatibilidad:

```java
    public RequirementsView(String missionId, String status, Integer version, String versionStatus,
                            Integer approvedVersion, int builtIncrement, int incrementsTotal, RequirementsSpec spec,
                            String summary) {
        this(missionId, status, version, versionStatus, approvedVersion, builtIncrement, incrementsTotal, spec, summary,
                null);
    }
```

- [ ] **Step 4: Implement `MissionService`**

1. Setter:

```java
    private ContractMemoryService contracts;

    /** Contrato (spec 2026-10-16): opcional en tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setContracts(ContractMemoryService contracts) {
        this.contracts = contracts;
    }

    private ContractView contractView(String missionId) {
        if (contracts == null) {
            return null;
        }
        return contracts.latest(missionId).map(v -> {
            var contract = JSON.readValue(v.contractJson(), com.aicompany.core.agent.model.ClientContract.class);
            return new ContractView(v.version(), v.status(),
                    contracts.approved(missionId).map(ContractMemoryService.Version::version).orElse(null), contract,
                    com.aicompany.core.agent.validation.ContractText.summary(contract, v.version()));
        }).orElse(null);
    }
```

2. En `requirements(missionId)`, pasar `contractView(missionId)` como último argumento del `new RequirementsView(...)`.

3. En `decideRequirements`, justo después del chequeo de `AWAITING_SPEC_APPROVAL` y antes de `var version = requirementsMemory.latest(...)`:

```java
        // Contrato (spec 2026-10-16 §1): mientras el contrato está en DRAFT, la decisión es sobre el contrato.
        var draftContract = contracts == null ? Optional.<ContractMemoryService.Version>empty()
                : contracts.latest(missionId).filter(c -> "DRAFT".equals(c.status()));
        if (draftContract.isPresent()) {
            decideContract(mission, draftContract.get(), command);
            return requirements(missionId).orElseThrow();
        }
```

con:

```java
    private void decideContract(MissionResponse mission, ContractMemoryService.Version contract,
                                RequirementsDecisionCommand command) {
        var missionId = mission.missionId();
        if (command.decision() == RequirementsDecisionCommand.RequirementsDecision.ADJUST
                && command.targetOrAria() == RequirementsDecisionCommand.Target.KAEL) {
            throw new IllegalStateException("El contrato de " + missionId + " es de Aria: ajústalo sin «Kael».");
        }
        if (command.decision() != RequirementsDecisionCommand.RequirementsDecision.REJECT) {
            requireAgentsOn(mission.teamId(), "decidir sobre el contrato de " + missionId, true);
        }
        if (command.decision() == RequirementsDecisionCommand.RequirementsDecision.ADJUST
                && (command.instruction() == null || command.instruction().isBlank())) {
            throw new IllegalArgumentException("Para ajustar el contrato escribe la instrucción para Aria.");
        }
        var adjustment = command.decision() == RequirementsDecisionCommand.RequirementsDecision.ADJUST
                ? command.instruction().strip() : null;
        if (!contracts.claimDecision(missionId, contract.version(), adjustment)) {
            throw new IllegalStateException("La versión " + contract.version() + " del contrato de " + missionId
                    + " ya tiene una decisión en curso.");
        }
        switch (command.decision()) {
            case APPROVE -> {
                contracts.approve(missionId, contract.version(), "founder");
                events.publish("EMPRESA_CONTRACT_APPROVED", missionId, null, "human",
                        Map.of("version", contract.version()));
                executor.continueAfterContractAsync(missionId);
            }
            case ADJUST -> executor.adjustContractAsync(missionId, adjustment);
            case REJECT -> {
                contracts.reject(missionId, contract.version());
                memory.updateMission(missionId, MissionStatus.CANCELLED, 100, "Contrato rechazado",
                        "El fundador rechazó el contrato de Aria.");
                events.publishMission("EMPRESA_MISSION_UPDATED", missionId, MissionStatus.CANCELLED.name(), 100,
                        "Contrato rechazado", "El fundador rechazó el contrato de Aria.");
                events.publish("EMPRESA_CONTRACT_REJECTED", missionId, null, "human",
                        Map.of("version", contract.version()));
            }
        }
    }
```

(`MissionResponse.missionId()` y `teamId()` existen; ver `missionIn` en el test.) `requirements(missionId)` devuelve la vista con `version` null mientras no haya HU: es válido.

- [ ] **Step 5: Implement the chat**

En `ChatIntentRouter`:

1. Patrones:
   - `APPROVE_STORIES`, `REJECT_STORIES` y `REQUIREMENTS_WORD` suman `contrato` a su lista: `(?:hu|historias|requisitos|contrato)`.
   - Patrón nuevo, junto a `SHOW_DESIGN`:

```java
    /** Contrato (spec 2026-10-16): "muéstrame el contrato de MISSION-X". */
    private static final Pattern SHOW_CONTRACT = Pattern.compile(
            "(?i)\\b(?:muestrame|mostrame|ver|dame|cual es)\\s+(?:el\\s+)?contrato\\s+de\\s+(" + MISSION_REF + ")");
```

2. `isRequirementsCommand` suma `|| SHOW_CONTRACT.matcher(normalized).find()`.

3. Helper:

```java
    private boolean contractWaiting(String missionId) {
        return missionService.requirements(missionId).map(RequirementsView::contract)
                .map(c -> "DRAFT".equals(c.status())).orElse(false);
    }
```

4. En `handleRequirements`:
   - antes de `var show = SHOW_STORIES.matcher(normalized);`:

```java
            var showContract = SHOW_CONTRACT.matcher(normalized);
            if (showContract.find()) {
                var missionId = showContract.group(1).toUpperCase(Locale.ROOT);
                return missionService.requirements(missionId).map(RequirementsView::contract)
                        .map(c -> c.summary() + " (" + c.status() + ")")
                        .orElse(missionId + " no tiene contrato de Aria.");
            }
```

   - En el bloque de `approve`, antes de `decideRequirements`: `var contract = contractWaiting(missionId);`. Después: `if (contract) return "Contrato de " + missionId + " aprobado: Aria escribe las historias de usuario.";` y si no, la respuesta de hoy.
   - En `reject`, igual: `if (contract) return "Contrato de " + missionId + " rechazado: la misión quedó cancelada.";`.
   - En `adjust`: `return contractWaiting(missionId) ? "Le pasé el ajuste a Aria; te aviso cuando el contrato de " + missionId + " esté listo." : "Le pasé el ajuste a Aria; te aviso cuando las historias de " + missionId + " estén listas.";`. Calcularlo **antes** de `decideRequirements`, porque después el contrato ya no está en DRAFT.

5. En `formatCompanyStatus`, en el `.map(id -> …)` de `awaitingSpec`: `contractWaiting(id) ? id + " (contrato)" : missionService.interactionDesign(id).isPresent() ? id + " (HU + diseño)" : id`. En el texto de ayuda, agregar `"muéstrame el contrato de <misión>"`.

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd app && mvn test -Dtest='MissionServiceRequirementsTest,ChatIntentRouterTest'` y después `cd app && mvn test`
Expected: PASS en ambos.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/ContractView.java app/src/main/java/com/aicompany/core/model/RequirementsView.java app/src/main/java/com/aicompany/core/service/MissionService.java app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java app/src/test/java/com/aicompany/core/service/MissionServiceRequirementsTest.java app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java
git commit -m "Contrato: el fundador lo aprueba, ajusta o rechaza con los mismos botones y por chat

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Kael diseña las escenas y cada nivel, con su validación

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/model/InteractionDesign.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/InteractionDesignValidator.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/InteractionDesignValidatorTest.java`

**Interfaces:**
- Consumes: `RequirementsSpec.gameLevels()` (ítems `LEVEL`/`SCENE` del contrato, en orden) y `ClientContract.Item(String id, String kind, String name, String description)` (Tasks 1 y 3).
- Produces:
  - `record InteractionDesign.Scene(String id, String name, String script, String purpose)`.
  - `record InteractionDesign.LevelDesign(String item, String scene, List<Property> parameters, String advanceRule)`.
  - `GameDesign` gana los componentes `List<Scene> scenes, List<LevelDesign> levels` (al final); sigue un constructor con los 10 argumentos de hoy.
  - `InteractionDesignValidator.MAX_SCENES = 8`, `InteractionDesignValidator.SCRIPT` (`Pattern` `[A-Z][A-Za-z0-9]*`), `InteractionDesignValidator.RESERVED_SCRIPTS` (`Set.of("Playthrough", "PlaythroughRunner", "Step", "StepKind")`).

- [ ] **Step 1: Write the failing tests**

En `InteractionDesignValidatorTest` agregar (usar los constructores de `GameDesign` que ya use el archivo para armar un diseño de juego válido; si no hay un helper, este):

```java
    static final RequirementsSpec PONG = new RequirementsSpec("Pong", List.of(), List.of(),
            List.of(new RequirementsSpec.Role("jugador", "d")),
            List.of(new RequirementsSpec.UserStory("HU-01", "jugador", "q", "p", List.of("c"), List.of(),
                            List.of(new RequirementsSpec.Scenario("ESC-01", "a", List.of("d"), List.of("c"), List.of("e"))), "MOD-01"),
                    new RequirementsSpec.UserStory("HU-02", "jugador", "q", "p", List.of("c"), List.of(),
                            List.of(new RequirementsSpec.Scenario("ESC-02", "b", List.of("d"), List.of("c"), List.of("e"))), "MOD-02")),
            List.of(new RequirementsSpec.Increment(1, "N1", List.of("HU-01")),
                    new RequirementsSpec.Increment(2, "N2", List.of("HU-02"))), "GAME",
            List.of(new ClientContract.Item("MOD-01", "LEVEL", "Nivel 1", "normal"),
                    new ClientContract.Item("MOD-02", "LEVEL", "Nivel 2", "rápida")), true);

    static GameDesign pongDesign(List<Scene> scenes, List<LevelDesign> levels) {
        return new GameDesign(List.of("PLAYING"), List.of(), List.of(), List.of(new Control("move_up", "Up", "sube")),
                List.of(new Rule("REGLA-01", "move_up apretado", "la paleta sube"),
                        new Rule("REGLA-02", "puntaje >= 50", "pasa al nivel 2")),
                List.of("REGLA-02"), List.of(), List.of(),
                List.of(new ScenarioRule("ESC-01", "REGLA-01"), new ScenarioRule("ESC-02", "REGLA-01")),
                List.of("hipótesis"), scenes, levels);
    }

    static final Scene MAIN = new Scene("ESCENA-01", "Cancha", "Main", "donde se juega");
    static final LevelDesign N1 = new LevelDesign("MOD-01", "ESCENA-01",
            List.of(new Property("velocidad_pelota", 200.0)), "REGLA-02");
    static final LevelDesign N2 = new LevelDesign("MOD-02", "ESCENA-01",
            List.of(new Property("velocidad_pelota", 300.0)), "");

    // Juegos por niveles (spec 2026-10-16 contrato §3).
    @Test void aDesignWithScenesAndOneEntryPerLevelPasses() {
        assertEquals(List.of(), InteractionDesignValidator.validateGame(pongDesign(List.of(MAIN), List.of(N1, N2)), PONG));
    }

    @Test void everyLevelOfTheContractHasExactlyOneDesignAndNoExtras() {
        var errors = InteractionDesignValidator.validateGame(pongDesign(List.of(MAIN), List.of(N1,
                new LevelDesign("MOD-07", "ESCENA-01", List.of(), ""))), PONG);
        var all = String.join("\n", errors);
        assertTrue(all.contains("MOD-02 no tiene diseño"), all);
        assertTrue(all.contains("MOD-07 no es un nivel o escena del contrato"), all);
    }

    @Test void scenesNeedValidUniqueIdsAndScripts() {
        var errors = InteractionDesignValidator.validateGame(pongDesign(List.of(MAIN,
                        new Scene("ESCENA-01", "Otra", "main", "x"), new Scene("ESCENA-03", "Menu", "Main", "x")),
                List.of(N1, N2)), PONG);
        var all = String.join("\n", errors);
        assertTrue(all.contains("La escena ESCENA-01 está repetida"), all);
        assertTrue(all.contains("\"main\""), all);                 // script inválido
        assertTrue(all.contains("El script Main está repetido"), all);
    }

    // Review Focus 3: una escena no puede llamarse como una clase del runner de Forjai.
    @Test void aSceneCannotUseARunnerClassName() {
        var errors = InteractionDesignValidator.validateGame(pongDesign(
                List.of(new Scene("ESCENA-01", "Cancha", "Playthrough", "x")),
                List.of(new LevelDesign("MOD-01", "ESCENA-01", List.of(), "REGLA-02"),
                        new LevelDesign("MOD-02", "ESCENA-01", List.of(), ""))), PONG);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Playthrough") && e.contains("runner")), errors.toString());
    }

    @Test void theMainSceneIsRequiredAndLevelsUseExistingScenes() {
        var errors = InteractionDesignValidator.validateGame(pongDesign(List.of(new Scene("ESCENA-02", "Menu", "Menu", "x")),
                List.of(new LevelDesign("MOD-01", "ESCENA-05", List.of(), "REGLA-02"),
                        new LevelDesign("MOD-02", "ESCENA-02", List.of(), ""))), PONG);
        var all = String.join("\n", errors);
        assertTrue(all.contains("ESCENA-01"), all);
        assertTrue(all.contains("MOD-01 usa la escena ESCENA-05, que no está en scenes"), all);
    }

    @Test void theAdvanceRuleIsRequiredExceptInTheLastLevelAndMustExist() {
        var errors = InteractionDesignValidator.validateGame(pongDesign(List.of(MAIN),
                List.of(new LevelDesign("MOD-01", "ESCENA-01", List.of(), ""),
                        new LevelDesign("MOD-02", "ESCENA-01", List.of(), "REGLA-09"))), PONG);
        var all = String.join("\n", errors);
        assertTrue(all.contains("MOD-01 necesita advanceRule"), all);
        assertTrue(all.contains("MOD-02: advanceRule REGLA-09 no está en rules"), all);
    }

    @Test void levelParametersAreNumbersAndThereAreAtMostEightScenes() {
        var many = new java.util.ArrayList<Scene>();
        for (int i = 1; i <= 9; i++) {
            many.add(new Scene("ESCENA-%02d".formatted(i), "E" + i, "Escena" + i, "x"));
        }
        var errors = InteractionDesignValidator.validateGame(pongDesign(many,
                List.of(new LevelDesign("MOD-01", "ESCENA-01", List.of(new Property("pelotas", null)), "REGLA-02"), N2)),
                PONG);
        var all = String.join("\n", errors);
        assertTrue(all.contains("máximo 8"), all);
        assertTrue(all.contains("MOD-01: el parámetro \"pelotas\" necesita un número"), all);
    }

    // Una escena del contrato (menú, game over) no necesita advanceRule; el último LEVEL tampoco.
    @Test void aSceneItemDoesNotNeedAnAdvanceRule() {
        var menuFirst = new RequirementsSpec("Pong", List.of(), List.of(), PONG.rolesOrEmpty(), PONG.userStoriesOrEmpty(),
                PONG.incrementsOrEmpty(), "GAME", List.of(new ClientContract.Item("MOD-01", "SCENE", "Menú", "inicio"),
                        new ClientContract.Item("MOD-02", "LEVEL", "Nivel 1", "normal")), true);
        assertEquals(List.of(), InteractionDesignValidator.validateGame(pongDesign(List.of(MAIN),
                List.of(new LevelDesign("MOD-01", "ESCENA-01", List.of(), ""),
                        new LevelDesign("MOD-02", "ESCENA-01", List.of(), ""))), menuFirst));
    }

    // Compatibilidad: un spec de juego sin contrato (misión vieja) no exige escenas.
    @Test void aGameWithoutLevelsDoesNotNeedScenes() {
        var oldSpec = new RequirementsSpec("Pong", List.of(), List.of(), List.of(new RequirementsSpec.Role("jugador", "d")),
                PONG.userStoriesOrEmpty(), List.of(new RequirementsSpec.Increment(1, "MVP", List.of("HU-01", "HU-02"))),
                "GAME");
        assertEquals(List.of(), InteractionDesignValidator.validateGame(pongDesign(List.of(), List.of()), oldSpec));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn test -Dtest=InteractionDesignValidatorTest`
Expected: FAIL de compilación (`Scene`, `LevelDesign` y el constructor de 12 argumentos no existen).

- [ ] **Step 3: Implement the model**

En `InteractionDesign.java`, junto a `ScenarioRule`:

```java
    /** Juegos por niveles (spec 2026-10-16 contrato §3): una escena Godot; script es la clase C# y el nombre de sus archivos. */
    public record Scene(String id, String name, String script, String purpose) { }

    /** Por cada nivel o escena del contrato (item MOD-NN): dónde ocurre, sus parámetros y la regla que pasa al siguiente (vacía en el último). */
    public record LevelDesign(String item, String scene, List<Property> parameters, String advanceRule) {
        public LevelDesign { parameters = parameters == null ? List.of() : parameters; }
    }
```

`GameDesign`: agregar `List<Scene> scenes, List<LevelDesign> levels` al final de los componentes; en el constructor compacto `scenes = scenes == null ? List.of() : scenes; levels = levels == null ? List.of() : levels;`; y un constructor de compatibilidad:

```java
        /** Diseños anteriores a juegos por niveles (spec 2026-10-16): sin escenas ni niveles. */
        public GameDesign(List<String> states, List<Transition> transitions, List<Entity> entities, List<Control> controls,
                          List<Rule> rules, List<String> win, List<String> lose, List<Property> difficulty,
                          List<ScenarioRule> scenarioRules, List<String> balanceHypotheses) {
            this(states, transitions, entities, controls, rules, win, lose, difficulty, scenarioRules, balanceHypotheses,
                    null, null);
        }
```

En `GameDesign.SCHEMA`, las `properties` pasan de 10 a 12 entradas: `Map.of` admite 10 pares, así que cambiar ese `Map.of(` por `Map.ofEntries(` con `Map.entry(...)` por cada propiedad, y agregar (sin sumarlas a `required`):

```java
                        Map.entry("scenes", array(Map.of("type", "object", "properties", Map.of("id", str(), "name", str(),
                                "script", str(), "purpose", str()), "required", List.of("id", "name", "script", "purpose")))),
                        Map.entry("levels", array(Map.of("type", "object", "properties", Map.of("item", str(),
                                "scene", str(), "parameters", array(property()), "advanceRule", str()),
                                "required", List.of("item", "scene", "parameters", "advanceRule"))))
```

- [ ] **Step 4: Implement the validator**

En `InteractionDesignValidator` agregar las constantes:

```java
    public static final int MAX_SCENES = 8;
    public static final Pattern SCRIPT = Pattern.compile("[A-Z][A-Za-z0-9]*");
    /** Clases del runner de Forjai (namespace Forjai.Playthroughs): una escena con ese nombre choca en el build. */
    public static final Set<String> RESERVED_SCRIPTS = Set.of("Playthrough", "PlaythroughRunner", "Step", "StepKind");
    private static final Pattern ESCENA = Pattern.compile("ESCENA-\\d{2}");
```

En `validateGame`, antes de `return errors;`, llamar `validateLevels(design, spec, rules, errors);` (`rules` es el `LinkedHashSet` de ids de reglas que ya arma el método) con:

```java
    /** Juegos por niveles (spec 2026-10-16 contrato §3): solo si el contrato tiene niveles o escenas. */
    private static void validateLevels(GameDesign design, RequirementsSpec spec, Set<String> rules, List<String> errors) {
        var levelItems = spec.gameLevels().stream().map(ClientContract.Item::id).filter(Objects::nonNull).toList();
        if (levelItems.isEmpty()) {
            return;
        }
        var scenes = nn(design.scenes());
        if (scenes.size() > MAX_SCENES) {
            errors.add("Hay " + scenes.size() + " escenas: máximo " + MAX_SCENES + ".");
        }
        var sceneIds = new LinkedHashSet<String>();
        var scripts = new HashSet<String>();
        for (var s : scenes) {
            if (s.id() == null || !ESCENA.matcher(s.id()).matches()) {
                errors.add("El id de escena \"" + s.id() + "\" no tiene el formato ESCENA-01.");
            } else if (!sceneIds.add(s.id())) {
                errors.add("La escena " + s.id() + " está repetida.");
            }
            if (s.script() == null || !SCRIPT.matcher(s.script()).matches()) {
                errors.add(s.id() + ": script \"" + s.script() + "\" debe ser un nombre de clase C# que empieza en "
                        + "mayúscula, sin espacios (p. ej. Main, Menu).");
            } else if (RESERVED_SCRIPTS.contains(s.script())) {
                errors.add(s.id() + ": script " + s.script() + " es una clase del runner de Forjai: usa otro nombre.");
            } else if (!scripts.add(s.script())) {
                errors.add("El script " + s.script() + " está repetido: cada escena tiene el suyo.");
            }
        }
        if (!sceneIds.contains("ESCENA-01")) {
            errors.add("scenes necesita ESCENA-01: la escena principal (la que abre el juego).");
        }
        var lastLevel = spec.gameLevels().stream().filter(i -> "LEVEL".equals(i.kind())).reduce((a, b) -> b)
                .map(ClientContract.Item::id).orElse(null);
        var designed = new HashMap<String, Integer>();
        for (var l : nn(design.levels())) {
            designed.merge(String.valueOf(l.item()), 1, Integer::sum);
            if (!levelItems.contains(l.item())) {
                errors.add("Hay un diseño para " + l.item() + ", que no es un nivel o escena del contrato " + levelItems + ".");
                continue;
            }
            if (l.scene() == null || !sceneIds.contains(l.scene())) {
                errors.add(l.item() + " usa la escena " + l.scene() + ", que no está en scenes.");
            }
            nn(l.parameters()).stream().filter(p -> p.value() == null).forEach(p -> errors.add(l.item()
                    + ": el parámetro \"" + p.name() + "\" necesita un número en value (p. ej. velocidad_pelota=200)."));
            var isLevel = spec.gameLevels().stream().anyMatch(i -> l.item().equals(i.id()) && "LEVEL".equals(i.kind()));
            var rule = l.advanceRule() == null ? "" : l.advanceRule().strip();
            if (rule.isEmpty() && isLevel && !l.item().equals(lastLevel)) {
                errors.add(l.item() + " necesita advanceRule: la REGLA-NN que pasa al nivel siguiente.");
            } else if (!rule.isEmpty() && !rules.contains(rule)) {
                errors.add(l.item() + ": advanceRule " + rule + " no está en rules.");
            }
        }
        for (var id : levelItems) {
            var n = designed.getOrDefault(id, 0);
            if (n == 0) {
                errors.add(id + " no tiene diseño: agrega uno en levels con item \"" + id + "\".");
            } else if (n > 1) {
                errors.add(id + " tiene " + n + " diseños: deja uno.");
            }
        }
    }
```

(`HashMap` ya entra por `java.util.*`. Importar `com.aicompany.core.agent.model.ClientContract` en el validador y en el test.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd app && mvn test -Dtest=InteractionDesignValidatorTest` y después `cd app && mvn test`
Expected: PASS en ambos.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/model/InteractionDesign.java app/src/main/java/com/aicompany/core/agent/validation/InteractionDesignValidator.java app/src/test/java/com/aicompany/core/agent/validation/InteractionDesignValidatorTest.java
git commit -m "Juegos por niveles: Kael declara las escenas y diseña cada nivel o escena del contrato

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---


### Task 8: Escenas y niveles en los textos de Kael, Neo y Vera, y en la revisión conjunta

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/InteractionDesignText.java`
- Modify: `app/src/main/java/com/aicompany/core/service/InteractionDesignWriter.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/JointReviewValidator.java`
- Modify: `app/src/main/java/com/aicompany/core/service/JointReviewWriter.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/GameScenes.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/InteractionDesignTextTest.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/JointReviewValidatorTest.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/GameScenesTest.java` (nuevo)
- Test: `app/src/test/java/com/aicompany/core/service/InteractionDesignWriterTest.java`

**Interfaces:**
- Consumes: `Scene`, `LevelDesign`, `GameDesign.scenes()/levels()` (Task 7); `RequirementsSpec.gameLevelsOf(int)` y `ClientContract.Item` (Task 3).
- Produces, en `GameScenes` (`com.aicompany.core.agent.validation`):
  - `record Approved(RequirementsSpec spec, InteractionDesign.GameDesign game, int increment)`;
  - `static final String MAIN = "ESCENA-01"`;
  - `static List<InteractionDesign.Scene> upTo(InteractionDesign.GameDesign game, RequirementsSpec spec, int increment)`: ESCENA-01 más las escenas de los niveles de los incrementos 1..N, en el orden de `scenes`, sin repetir;
  - `static Optional<InteractionDesign.Scene> main(InteractionDesign.GameDesign game)`;
  - `static boolean hasLevels(InteractionDesign.GameDesign game, String sceneId)`;
  - `static String scenePath(String script)` (`"game/" + script + ".tscn"`) y `static String scriptPath(String script)` (`"game/" + script + ".cs"`).

- [ ] **Step 1: Write the failing tests**

`GameScenesTest.java`:

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.ClientContract;
import com.aicompany.core.agent.model.InteractionDesign.*;
import com.aicompany.core.agent.model.RequirementsSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Juegos por niveles (spec 2026-10-16 contrato §4): qué escenas necesita cada incremento. */
class GameScenesTest {

    static final RequirementsSpec SPEC = new RequirementsSpec("Pong", List.of(), List.of(),
            List.of(new RequirementsSpec.Role("jugador", "d")),
            List.of(new RequirementsSpec.UserStory("HU-01", "jugador", "q", "p", List.of("c"), List.of(), List.of(), "MOD-01"),
                    new RequirementsSpec.UserStory("HU-02", "jugador", "q", "p", List.of("c"), List.of(), List.of(), "MOD-02")),
            List.of(new RequirementsSpec.Increment(1, "N1", List.of("HU-01")),
                    new RequirementsSpec.Increment(2, "Fin", List.of("HU-02"))), "GAME",
            List.of(new ClientContract.Item("MOD-01", "LEVEL", "Nivel 1", "x"),
                    new ClientContract.Item("MOD-02", "SCENE", "Game over", "x")), true);

    static final GameDesign GAME = new GameDesign(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(),
            List.of(new Scene("ESCENA-01", "Cancha", "Main", "x"), new Scene("ESCENA-02", "Fin", "GameOver", "x"),
                    new Scene("ESCENA-03", "Créditos", "Credits", "x")),
            List.of(new LevelDesign("MOD-01", "ESCENA-01", List.of(), "REGLA-01"),
                    new LevelDesign("MOD-02", "ESCENA-02", List.of(), "")));

    @Test void eachIncrementGetsTheMainSceneAndTheScenesOfItsLevelsAndThePreviousOnes() {
        assertEquals(List.of("Main"), GameScenes.upTo(GAME, SPEC, 1).stream().map(Scene::script).toList());
        assertEquals(List.of("Main", "GameOver"), GameScenes.upTo(GAME, SPEC, 2).stream().map(Scene::script).toList());
    }

    @Test void pathsAndLevels() {
        assertEquals("game/Main.tscn", GameScenes.scenePath("Main"));
        assertEquals("game/Main.cs", GameScenes.scriptPath("Main"));
        assertEquals("Main", GameScenes.main(GAME).orElseThrow().script());
        assertTrue(GameScenes.hasLevels(GAME, "ESCENA-01"));
        assertFalse(GameScenes.hasLevels(GAME, "ESCENA-03"));
    }

    @Test void withoutScenesThereIsNothing() {
        var old = new GameDesign(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of());
        assertEquals(List.of(), GameScenes.upTo(old, SPEC, 1));
        assertTrue(GameScenes.main(old).isEmpty());
    }
}
```

`hasLevels(GAME, "ESCENA-02")` es `true` (MOD-02 es una escena SCENE del contrato y ocurre en ESCENA-02): **ruling** — toda escena donde ocurre un nivel o escena del contrato expone `Level`/`StartLevel`; así el runner puede arrancar cualquier nivel o escena directo.

En `InteractionDesignTextTest`, con un diseño y un spec como los de `GameScenesTest` (copiarlos al archivo como constantes privadas, porque los de otra clase de test son package-private y sirven, pero conviene que el test se lea solo):

```java
    // Juegos por niveles (spec 2026-10-16 contrato §3-§4): Kael, Iris y Vera ven las escenas y los niveles del incremento.
    @Test void theGameBlockListsTheScenesAndTheLevelsOfTheIncrement() {
        var design = new InteractionDesign("GAME", null, GameScenesTest.GAME);
        var block = InteractionDesignText.incrementBlock(design, GameScenesTest.SPEC, 1);
        assertTrue(block.contains("Escenas (Forjai ya creó cada game/<script>.tscn con el script en el nodo raíz; no las escribas):"), block);
        assertTrue(block.contains("- ESCENA-01 Cancha → game/Main.tscn + game/Main.cs (clase Main, escena principal)"), block);
        assertFalse(block.contains("ESCENA-02 Fin"), block);
        assertTrue(block.contains("- MOD-01 en ESCENA-01: {} — pasa al siguiente con REGLA-01"), block);
        assertTrue(block.contains("public partial class <script> : Node2D"), block);
        assertTrue(block.contains("public int Level { get; }"), block);
        assertTrue(block.contains("run/main_scene=\"res://Main.tscn\""), block);
    }

    @Test void theSummaryShowsScenesAndLevels() {
        var summary = InteractionDesignText.summary(new InteractionDesign("GAME", null, GameScenesTest.GAME));
        assertTrue(summary.contains("Escenas: ESCENA-01 Cancha (Main), ESCENA-02 Fin (GameOver), ESCENA-03 Créditos (Credits)"), summary);
        assertTrue(summary.contains("Niveles: MOD-01 en ESCENA-01 {} → REGLA-01; MOD-02 en ESCENA-02 {}"), summary);
    }
```

En `JointReviewValidatorTest`:

```java
    // Juegos por niveles: MOD-NN es referencia de requisitos; ESCENA-NN y MOD-NN del diseño, de diseño.
    @Test void levelsAndScenesAreValidReferences() {
        var design = new InteractionDesign("GAME", null, GameScenesTest.GAME);
        assertTrue(JointReviewValidator.requirementIds(GameScenesTest.SPEC).contains("MOD-02"));
        assertTrue(JointReviewValidator.designIds(design).containsAll(List.of("ESCENA-01", "MOD-01")));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn test -Dtest='GameScenesTest,InteractionDesignTextTest,JointReviewValidatorTest'`
Expected: FAIL (`GameScenes` no existe; los textos no tienen escenas).

- [ ] **Step 3: Implement `GameScenes`**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.RequirementsSpec;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Juegos por niveles (spec 2026-10-16 contrato §4): las escenas del diseño de Kael que necesita cada incremento. */
public final class GameScenes {

    public static final String MAIN = "ESCENA-01";

    /** Spec y diseño de juego aprobados de una misión, con el incremento que se construye. */
    public record Approved(RequirementsSpec spec, InteractionDesign.GameDesign game, int increment) {
    }

    private GameScenes() {
    }

    /** ESCENA-01 y las escenas de los niveles de los incrementos 1..N, en el orden de scenes y sin repetir. */
    public static List<InteractionDesign.Scene> upTo(InteractionDesign.GameDesign game, RequirementsSpec spec, int increment) {
        var levels = new HashSet<String>();
        for (int n = 1; n <= increment; n++) {
            spec.gameLevelsOf(n).forEach(l -> levels.add(l.id()));
        }
        var used = new HashSet<String>();
        used.add(MAIN);
        nn(game.levels()).stream().filter(l -> levels.contains(l.item())).forEach(l -> used.add(l.scene()));
        return nn(game.scenes()).stream().filter(s -> used.contains(s.id())).toList();
    }

    public static Optional<InteractionDesign.Scene> main(InteractionDesign.GameDesign game) {
        return nn(game.scenes()).stream().filter(s -> MAIN.equals(s.id())).findFirst();
    }

    /** Las escenas donde ocurre un nivel o escena del contrato exponen Level y StartLevel. */
    public static boolean hasLevels(InteractionDesign.GameDesign game, String sceneId) {
        return nn(game.levels()).stream().anyMatch(l -> Objects.equals(sceneId, l.scene()));
    }

    public static String scenePath(String script) {
        return "game/" + script + ".tscn";
    }

    public static String scriptPath(String script) {
        return "game/" + script + ".cs";
    }

    private static <T> List<T> nn(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }
}
```

- [ ] **Step 4: Implement the texts**

En `InteractionDesignText.incrementBlock`, en la rama de juego, antes del `return`, armar:

```java
        var sceneBlock = "";
        if (!nn(game.scenes()).isEmpty()) {
            var scenes = GameScenes.upTo(game, spec, increment);
            var levels = spec.gameLevelsOf(increment).stream().map(ClientContract.Item::id).toList();
            var main = GameScenes.main(game).map(InteractionDesign.Scene::script).orElse("Main");
            sceneBlock = """
                    Escenas (Forjai ya creó cada game/<script>.tscn con el script en el nodo raíz; no las escribas):
                    %s
                    Niveles de este incremento (parámetros y regla para pasar al siguiente):
                    %s
                    Cada escena es public partial class <script> : Node2D en game/<script>.cs y crea por código, en _Ready, TODOS los nodos que usa (AddChild): la .tscn no tiene hijos, nunca los esperes ni uses GetNode de hijos de la escena.
                    Las escenas con niveles exponen public int Level { get; } y public void StartLevel(int level) (StartLevel deja el nivel listo para jugar; las partidas simuladas lo usan para arrancar directo en un nivel).
                    game/project.godot declara run/main_scene="res://%s.tscn" (ESCENA-01).
                    """.formatted(
                    scenes.stream().map(s -> "- " + s.id() + " " + s.name() + " → " + GameScenes.scenePath(s.script())
                                    + " + " + GameScenes.scriptPath(s.script()) + " (clase " + s.script()
                                    + (GameScenes.MAIN.equals(s.id()) ? ", escena principal" : "") + "): " + s.purpose())
                            .collect(Collectors.joining("\n")),
                    nn(game.levels()).stream().filter(l -> levels.contains(l.item())).map(InteractionDesignText::level)
                            .collect(Collectors.joining("\n")),
                    main);
        }
```

con el helper:

```java
    private static String level(InteractionDesign.LevelDesign l) {
        var rule = l.advanceRule() == null || l.advanceRule().isBlank() ? "" : " — pasa al siguiente con " + l.advanceRule().strip();
        return "- " + l.item() + " en " + l.scene() + ": " + nn(l.parameters()).stream()
                .map(p -> p.name() + "=" + num(p.value())).collect(Collectors.joining(", ", "{", "}")) + rule;
    }
```

y concatenar `sceneBlock` al final del text block del juego (`return """…""".formatted(...) + sceneBlock;`). En el text block del juego, la última línea («Expón en el script de la escena principal…») queda igual.

En `summary`, en la rama de juego, agregar al final (antes del punto final, manteniendo el formato):

```java
        var scenes = nn(g.scenes()).isEmpty() ? "" : " Escenas: " + nn(g.scenes()).stream()
                .map(s -> s.id() + " " + s.name() + " (" + s.script() + ")").collect(Collectors.joining(", "))
                + ". Niveles: " + nn(g.levels()).stream().map(l -> l.item() + " en " + l.scene() + " "
                        + nn(l.parameters()).stream().map(p -> p.name() + "=" + num(p.value()))
                        .collect(Collectors.joining(", ", "{", "}"))
                        + (l.advanceRule() == null || l.advanceRule().isBlank() ? "" : " → " + l.advanceRule().strip()))
                .collect(Collectors.joining("; ")) + ".";
```

y sumar `+ scenes` al `return` del juego.

- [ ] **Step 5: Implement Kael's prompt**

En `InteractionDesignWriter.prompt`, en el texto de juego, agregar después de la línea de `balanceHypotheses`:

```
                - Si hay NIVELES Y ESCENAS DEL CONTRATO (arriba): scenes ESCENA-01, ESCENA-02… (máximo 8) con name,
                  script (nombre de la clase C# y de sus archivos: empieza en mayúscula, sin espacios, p. ej. Main, Menu;
                  nunca Playthrough ni PlaythroughRunner) y purpose; ESCENA-01 es la escena principal. levels: uno por
                  cada MOD-NN de esa lista con item (el MOD-NN), la escena donde ocurre (scene), sus parameters numéricos (p. ej.
                  velocidad_pelota=200, pelotas=1, puntaje_meta=50) y advanceRule (la REGLA-NN que pasa al siguiente
                  nivel; vacía en el último LEVEL y en las SCENE).
```

y en el JSON de ejemplo agregar antes de la `}` final: `,"scenes":[{"id":"ESCENA-01","name":"...","script":"Main","purpose":"..."}],"levels":[{"item":"MOD-01","scene":"ESCENA-01","parameters":[{"name":"...","value":1}],"advanceRule":"REGLA-03"}]`.

Además, en `prompt(...)`, antes de `var base = …`, armar la lista de niveles y escenas del contrato (vale también sin épicas, p. ej. un juego de un solo nivel) y sumarla a `base` después de las HU:

```java
        var levels = spec.gameLevels().isEmpty() ? "" : "\nNIVELES Y ESCENAS DEL CONTRATO (cada uno lleva su entrada en levels):\n"
                + spec.gameLevels().stream().map(i -> "- " + i.id() + " [" + i.kind() + "] " + i.name() + ": " + i.description())
                .collect(Collectors.joining("\n")) + "\n";
```

con `base` = `"Eres Kael…\n\n" + "HISTORIAS DE USUARIO DE ARIA (todas las del producto):\n" + allStories + levels + "\n" + rules`. Agregar en `InteractionDesignWriterTest` (siguiendo el patrón del archivo: mock de `ceo.writeGameDesign` y captura del prompt) un test `theGamePromptListsTheLevelsAndScenesOfTheContract` que con `GameScenesTest.SPEC` verifique que el prompt contiene `"- MOD-01 [LEVEL] Nivel 1: x"` y `"- MOD-02 [SCENE] Game over: x"`.

- [ ] **Step 6: Implement the joint review references**

En `JointReviewValidator.requirementIds` agregar `spec.gameLevels().forEach(l -> { if (l.id() != null) ids.add(l.id()); });`. En `designIds`, dentro de `if (design.game() != null)`, agregar:

```java
            design.game().scenes().forEach(s -> {
                if (s.id() != null) ids.add(s.id());
            });
            design.game().levels().forEach(l -> {
                if (l.item() != null) ids.add(l.item());
            });
```

Cambiar el mensaje «requirementRefs necesita al menos una HU-NN o ESC-NN.» por «requirementRefs necesita al menos una HU-NN, ESC-NN o MOD-NN.». En `JointReviewWriter` (línea ~55), cambiar `requirementRefs (HU-NN/ESC-NN)` por `requirementRefs (HU-NN/ESC-NN/MOD-NN)` y `REGLA-NN o el id de una entidad en juegos` por `REGLA-NN, ESCENA-NN, MOD-NN o el id de una entidad en juegos`. Si un test de `JointReviewValidatorTest` compara el mensaje viejo literalmente, actualizarlo.

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd app && mvn test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/GameScenes.java app/src/main/java/com/aicompany/core/agent/validation/InteractionDesignText.java app/src/main/java/com/aicompany/core/service/InteractionDesignWriter.java app/src/main/java/com/aicompany/core/agent/validation/JointReviewValidator.java app/src/main/java/com/aicompany/core/service/JointReviewWriter.java app/src/test/java/com/aicompany/core/agent/validation/GameScenesTest.java app/src/test/java/com/aicompany/core/agent/validation/InteractionDesignTextTest.java app/src/test/java/com/aicompany/core/agent/validation/JointReviewValidatorTest.java app/src/test/java/com/aicompany/core/service/InteractionDesignWriterTest.java
git commit -m "Juegos por niveles: escenas y niveles en los prompts, el resumen y la revisión conjunta

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---


### Task 9: Command Center: contrato, épicas, escenas y niveles

**Files:**
- Modify: `app/frontend/src/api/types.ts`
- Modify: `app/frontend/src/pages/MissionDetailPage.tsx` (`MissionRequirements` ~191, `MissionDesign`)

**Interfaces:**
- Consumes JSON de:
  - `RequirementsView.contract` (`ContractView`, Task 6);
  - `RequirementsSpec.contractItems`, `useEpics` y `userStories[].contractItem` (Task 3);
  - `GameDesign.scenes` y `levels[].item` (Task 7).

Hoy `MissionRequirements` devuelve `null` si no hay `spec`. Con el contrato en DRAFT todavía no hay HU, y **sin este cambio el fundador no vería los botones para aprobar el contrato**.

- [ ] **Step 1: Types**

En `types.ts`:

```ts
// Contrato (spec 2026-10-16): la solicitud del cliente (ClientContract / ContractView en Java).
export interface ContractItem {
  id: string
  kind: 'MODULE' | 'SYSTEM' | 'LEVEL' | 'SCENE'
  name: string
  description: string
}

export interface ClientContract {
  summary: string
  experience: 'APP' | 'GAME' | 'NONE'
  items: ContractItem[]
  outOfScope: string[]
  useEpics: boolean
  epicsReason: string
}

export interface ContractView {
  version: number
  status: string
  approvedVersion: number | null
  contract: ClientContract
  summary: string
}
```

Cambios en tipos existentes:
- `RequirementsStory`: `contractItem?: string | null`.
- `RequirementsSpec`: `contractItems?: ContractItem[] | null` y `useEpics?: boolean | null`.
- `RequirementsView`: `contract?: ContractView | null`.
- En `GameDesign`: `scenes?: { id: string; name: string; script: string; purpose: string }[]` y `levels?: { item: string; scene: string; parameters: DesignProperty[]; advanceRule: string }[]`. Usar el nombre del tipo de propiedad que ya use `GameDesign` para `entities[].properties`; si no es `DesignProperty`, usar ese.

- [ ] **Step 2: Requirements**

En `MissionRequirements`:

1. Constantes y helper arriba del componente:

```tsx
const KIND_LABEL: Record<string, string> = { MODULE: 'Módulo', SYSTEM: 'Sistema', LEVEL: 'Nivel', SCENE: 'Escena' }

function ContractSection({ view }: { view: ContractView }) {
  const c = view.contract
  return (
    <>
      <h3>
        Contrato (versión {view.version} — {view.status})
      </h3>
      <p>{c.summary}</p>
      <ul>
        {c.items.map((i) => (
          <li key={i.id}>
            <strong>{i.id}</strong> [{KIND_LABEL[i.kind] ?? i.kind}] {i.name} — {i.description}
          </li>
        ))}
      </ul>
      {c.outOfScope.length > 0 && <p>Fuera de alcance: {c.outOfScope.join('; ')}</p>}
      <p>
        Épicas: {c.useEpics ? 'sí' : 'no'} ({c.epicsReason})
      </p>
    </>
  )
}
```

Importar `ContractView` en el import de tipos del archivo.

2. Reemplazar `if (!view || !view.spec) { return null }` y lo que sigue hasta el `return (` por:

```tsx
  const view = query.data
  if (!view || (!view.spec && !view.contract)) {
    return null
  }
  const contractWaiting = view.contract?.status === 'DRAFT'
  const spec = view.spec
  const nextIncrement = view.builtIncrement + 1
  const canBuild = view.approvedVersion != null && nextIncrement <= view.incrementsTotal && INCREMENT_READY.has(status)
```

3. En el JSX:
   - Debajo de `<h2>Requisitos (Aria)</h2>`, agregar `{view.contract && <ContractSection view={view.contract} />}`.
   - Envolver todo lo que usa `spec` (versión, tipo, resumen, roles, incrementos, supuestos, fuera de alcance) en `{spec && (<>…</>)}`.
   - El bloque de botones (`status === 'AWAITING_SPEC_APPROVAL'`) queda fuera de ese envoltorio.
   - Con `contractWaiting`, el selector de destino muestra solo Aria (`{hasDesign && !contractWaiting && <option value="KAEL">Kael</option>}`) y el texto de los botones dice «Aprobar contrato» / «Rechazar contrato».
   - El `effectiveTarget` pasa a `hasDesign && !contractWaiting ? target : 'ARIA'`.

4. HU por épica, dentro del `spec.increments.map`. Extraer el render de una HU a una función local para no duplicarlo:

```tsx
        {spec.increments.map((inc) => {
          const story = (s: RequirementsStory) => (
            <div key={s.id}>
              <p>
                <strong>{s.id}</strong> ({s.role}): quiero {s.iWant} para {s.soThat}
              </p>
              <ul>
                {s.acceptanceCriteria.map((c) => (
                  <li key={c}>{c}</li>
                ))}
                {s.scenarios.map((e) => (
                  <li key={e.id}>
                    {e.id} {e.title}: DADO {e.given.join(' y ')} CUANDO {e.when.join(' y ')} ENTONCES{' '}
                    {e.then.join(' y ')}
                  </li>
                ))}
              </ul>
            </div>
          )
          const stories = spec.userStories.filter((s) => inc.userStoryIds.includes(s.id))
          const epics = spec.useEpics
            ? (spec.contractItems ?? []).filter((i) => stories.some((s) => s.contractItem === i.id))
            : []
          return (
            <div key={inc.number}>
              <h3>
                Incremento {inc.number}: {inc.goal}
              </h3>
              {epics.length === 0
                ? stories.map(story)
                : epics.map((i) => (
                    <div key={i.id}>
                      <h4>
                        Épica {i.id} [{KIND_LABEL[i.kind] ?? i.kind}] {i.name}: {i.description}
                      </h4>
                      {stories.filter((s) => s.contractItem === i.id).map(story)}
                    </div>
                  ))}
            </div>
          )
        })}
```

`RequirementsStory` ya existe en `types.ts`: importarlo si el archivo no lo importa.

- [ ] **Step 3: Design**

En `MissionDesign`, dentro de `{game && (<>…</>)}`, después de «Reglas» y antes de «Gana»:

```tsx
          {(game.scenes ?? []).length > 0 && (
            <>
              <h3>Escenas</h3>
              <ul>
                {(game.scenes ?? []).map((s) => (
                  <li key={s.id}>
                    {s.id} {s.name} ({s.script}): {s.purpose}
                  </li>
                ))}
              </ul>
              <h3>Niveles</h3>
              <ul>
                {(game.levels ?? []).map((l) => (
                  <li key={l.item}>
                    {l.item} en {l.scene}: {props(l.parameters)}
                    {l.advanceRule ? ` → ${l.advanceRule}` : ''}
                  </li>
                ))}
              </ul>
            </>
          )}
```

`props(...)` es el helper que el archivo ya usa para mostrar las `properties` de las entidades. Si se llama distinto, usar ese.

- [ ] **Step 4: Lint and build**

Run: `cd app/frontend && npm run lint && npm run build`
Expected: sin errores.

- [ ] **Step 5: Commit**

```bash
git add app/frontend/src/api/types.ts app/frontend/src/pages/MissionDetailPage.tsx
git commit -m "Command Center: contrato con sus botones, HU por épica y escenas y niveles del diseño del juego

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Forjai commitea los esqueletos de escena

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/GameSceneFiles.java`
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java`
- Test: `app/src/test/java/com/aicompany/core/model/GameSceneFilesTest.java` (nuevo)
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java`

**Interfaces:**
- Consumes: `GameScenes.Approved`, `GameScenes.upTo`, `GameScenes.scenePath` (Task 8).
- Produces:
  - `GameSceneFiles.skeleton(String script)` → `GeneratedFile` en `game/<script>.tscn`;
  - en `DevelopmentTeamStrategy`: `Optional<GameScenes.Approved> approvedGameScenes(String missionId)` (package-private) y `List<String> sceneSkeletonPaths(TeamMissionContext context)` (package-private; las rutas `.tscn` con el prefijo del componente, vacías si no hay escenas). Las usan Task 11 y Task 13.

**Ruling:** el spec dice «el scaffold escribe» y lista `ProjectScaffold` en las pruebas. `ProjectScaffold` hoy no conoce el diseño (solo perfil y contextos) y lo llaman también los cálculos de paquetes. Los esqueletos van en una clase propia (`GameSceneFiles`) y en un commit propio de Forjai (`<missionId>-SCENES`, `-SCENES-R<n>` en rondas) justo después del scaffold, con la misma regla de no pisar lo que existe. Costo si está mal: mover una llamada.

- [ ] **Step 1: Write the failing tests**

`GameSceneFilesTest.java`:

```java
package com.aicompany.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Juegos por niveles (spec 2026-10-16 contrato §4): la escena la escribe Forjai, con el script en el nodo raíz. */
class GameSceneFilesTest {

    @Test void theSkeletonIsAValidSceneWithTheScriptOnTheRootNode() {
        var file = GameSceneFiles.skeleton("Main");
        assertEquals("game/Main.tscn", file.path());
        assertEquals("""
                [gd_scene load_steps=2 format=3]

                [ext_resource type="Script" path="res://Main.cs" id="1"]

                [node name="Main" type="Node2D"]
                script = ExtResource("1")
                """, file.content());
    }
}
```

En `DevelopmentTeamStrategyTest`, agregar un helper y dos tests. Usa `context()` (juego, componente raíz `""`) y `stubHappyPath()`:

```java
    private static final RequirementsSpec LEVELED = new RequirementsSpec("Pong", List.of(), List.of(),
            List.of(new RequirementsSpec.Role("jugador", "d")),
            List.of(new RequirementsSpec.UserStory("HU-01", "jugador", "q", "p", List.of("c"), List.of(), List.of(), "MOD-01"),
                    new RequirementsSpec.UserStory("HU-02", "jugador", "q", "p", List.of("c"), List.of(), List.of(), "MOD-02")),
            List.of(new RequirementsSpec.Increment(1, "N1", List.of("HU-01")),
                    new RequirementsSpec.Increment(2, "Fin", List.of("HU-02"))), "GAME",
            List.of(new ClientContract.Item("MOD-01", "LEVEL", "Nivel 1", "x"),
                    new ClientContract.Item("MOD-02", "SCENE", "Game over", "x")), true);

    private static final InteractionDesign.GameDesign LEVELED_GAME = new InteractionDesign.GameDesign(List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(new InteractionDesign.Scene("ESCENA-01", "Cancha", "Main", "x"),
                    new InteractionDesign.Scene("ESCENA-02", "Fin", "GameOver", "x")),
            List.of(new InteractionDesign.LevelDesign("MOD-01", "ESCENA-01", List.of(), "REGLA-01"),
                    new InteractionDesign.LevelDesign("MOD-02", "ESCENA-02", List.of(), "")));

    private void withLeveledGame(int increment) throws Exception {
        var mapper = JsonMapper.builder().build();
        var requirements = mock(RequirementsMemoryService.class);
        when(requirements.approved("M-1")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1,
                mapper.writeValueAsString(LEVELED), "APPROVED", "", java.time.Instant.now())));
        when(requirements.currentIncrement("M-1")).thenReturn(increment);
        strategy.setRequirements(requirements);
        var designs = mock(InteractionDesignMemoryService.class);
        when(designs.approved("M-1")).thenReturn(Optional.of(new InteractionDesignMemoryService.Version(1,
                mapper.writeValueAsString(new InteractionDesign("GAME", null, LEVELED_GAME)), "APPROVED", 1, "")));
        strategy.setInteractionDesign(designs);
    }

    // Juegos por niveles (spec 2026-10-16 contrato §4): Forjai commitea la escena de cada nivel del incremento, antes de Kael.
    @Test
    void forjaiCommitsTheSceneSkeletonsOfTheIncrementBeforeTheAgents() throws Exception {
        stubHappyPath();
        withLeveledGame(1);
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-SCENES"), eq("forjai"), eq("Forjai"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord("8".repeat(40), List.of("game/Main.tscn")));
        // stubHappyPath devuelve "contenido" para cualquier ruta: la escena tiene que faltar en el HEAD.
        when(workspace.readFileAtCommit(eq("M-1"), anyString(), eq("game/Main.tscn"))).thenReturn(null);
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        var inOrder = inOrder(workspace, runtime);
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-SCAFFOLD"), eq("forjai"), eq("Forjai"), any());
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-SCENES"), eq("forjai"), eq("Forjai"),
                argThat(r -> r.files().stream().map(GeneratedFile::path).toList().equals(List.of("game/Main.tscn"))));
        inOrder.verify(runtime).generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), anyString(), anyList(),
                anyList(), anyList(), any());
    }

    // Review Focus 2: sin escenas en el diseño (juego viejo) no hay commit de escenas.
    @Test
    void withoutScenesThereIsNoSceneCommit() throws Exception {
        stubHappyPath();
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        verify(workspace, never()).commitAgentWork(eq("M-1"), eq("M-1-SCENES"), anyString(), anyString(), any());
    }
```

Imports nuevos del test si faltan: `com.aicompany.core.agent.model.ClientContract`, `com.aicompany.core.agent.model.InteractionDesign`, `com.aicompany.core.agent.model.RequirementsSpec`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn test -Dtest='GameSceneFilesTest,DevelopmentTeamStrategyTest'`
Expected: FAIL (`GameSceneFiles` no existe; no hay commit `M-1-SCENES`).

- [ ] **Step 3: Implement `GameSceneFiles`**

```java
package com.aicompany.core.model;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.agent.validation.GameScenes;

/**
 * Juegos por niveles (spec 2026-10-16 contrato §4): la escena .tscn de cada escena del diseño la escribe Forjai. Verificado en
 * el Pong 3 (MISSION-KAEL-PONG-1792113455): Kael escribía Main.tscn a mano sin el script en el nodo raíz y las 9
 * partidas caían en el paso 0. Kael crea los nodos por código en _Ready.
 */
public final class GameSceneFiles {

    private GameSceneFiles() {
    }

    public static GeneratedFile skeleton(String script) {
        return new GeneratedFile(GameScenes.scenePath(script), """
                [gd_scene load_steps=2 format=3]

                [ext_resource type="Script" path="res://%s.cs" id="1"]

                [node name="%s" type="Node2D"]
                script = ExtResource("1")
                """.formatted(script, script));
    }
}
```

- [ ] **Step 4: Implement in `DevelopmentTeamStrategy`**

1. Junto a `designConformance` agregar:

```java
    /**
     * Juegos por niveles (spec 2026-10-16 contrato §4): spec y diseño de juego aprobados con escenas. Vacío sin ellos (apps,
     * juegos anteriores) o si no se pueden leer: en ese caso rigen las reglas de antes, como en {@link #scenarios}.
     */
    java.util.Optional<com.aicompany.core.agent.validation.GameScenes.Approved> approvedGameScenes(String missionId) {
        if (designs == null || requirementsMemory == null) {
            return java.util.Optional.empty();
        }
        try {
            var design = designs.approved(missionId);
            var spec = requirementsMemory.approved(missionId);
            if (design == null || design.isEmpty() || spec == null || spec.isEmpty()) {
                return java.util.Optional.empty();
            }
            var game = jsonMapper.readValue(design.get().designJson(),
                    com.aicompany.core.agent.model.InteractionDesign.class).game();
            if (game == null || game.scenes().isEmpty()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new com.aicompany.core.agent.validation.GameScenes.Approved(
                    jsonMapper.readValue(spec.get().specJson(), com.aicompany.core.agent.model.RequirementsSpec.class),
                    game, Math.max(1, requirementsMemory.currentIncrement(missionId))));
        } catch (Exception ex) {
            log.warn("MISSION {} - could not read the approved game scenes: {}", missionId, safeMessage(ex, "sin detalle"));
            return java.util.Optional.empty();
        }
    }

    /** Las escenas de los incrementos 1..N con el prefijo del componente del juego. */
    private List<DevelopmentResult.GeneratedFile> sceneSkeletons(TeamMissionContext context) {
        var component = context.plan().resolvedComponents().stream()
                .filter(c -> c.profile() == StackProfile.GODOT_DOTNET_GAME).findFirst();
        if (component.isEmpty()) {
            return List.of();
        }
        return approvedGameScenes(context.missionId())
                .map(a -> com.aicompany.core.agent.validation.GameScenes.upTo(a.game(), a.spec(), a.increment()))
                .orElse(List.of()).stream()
                .map(s -> com.aicompany.core.model.GameSceneFiles.skeleton(s.script()))
                .map(f -> new DevelopmentResult.GeneratedFile(component.get().prefix(f.path()), f.content()))
                .toList();
    }

    List<String> sceneSkeletonPaths(TeamMissionContext context) {
        return sceneSkeletons(context).stream().map(DevelopmentResult.GeneratedFile::path).toList();
    }

    /** Commitea las escenas que faltan en el HEAD (nunca pisa una existente); null si no hay nada que commitear. */
    private CommittedWork commitSceneSkeletons(TeamMissionContext context, String headSha) {
        var skeletons = sceneSkeletons(context);
        var missing = skeletons.stream()
                .filter(f -> headSha == null || readOrNull(context.missionId(), headSha, f.path()) == null).toList();
        if (missing.isEmpty()) {
            return null;
        }
        var id = context.missionId() + "-SCENES" + (context.round() > 0 ? "-R" + context.round() : "");
        try {
            var record = workspace.commitAgentWork(context.missionId(), id, "forjai", "Forjai",
                    new DevelopmentResult("Escenas de Godot generadas por Forjai (script en el nodo raíz)", missing));
            return new CommittedWork(id, "forjai", record.sha(), record.files());
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudieron generar las escenas del juego: "
                    + safeMessage(ex, "error de Git"), ex);
        }
    }
```

2. En `execute`, después de `var generationHead = scaffold == null ? null : scaffold.commitSha();` (línea ~189) agregar:

```java
        // Juegos por niveles (spec 2026-10-16 contrato §4): las escenas del incremento, antes de que genere nadie.
        var scenes = commitSceneSkeletons(context, generationHead);
        if (scenes != null) {
            committed.add(scenes);
            generationHead = scenes.commitSha();
        }
        forjaiFiles.addAll(sceneSkeletonPaths(context));
```

`committed` y `forjaiFiles` se declaran antes de esa línea (`committed` en la línea anterior, `forjaiFiles` unas líneas después): mover la declaración de `forjaiFiles` arriba si hace falta, sin cambiar su contenido. Las escenas en `forjaiFiles` quedan como archivos permitidos de Forjai en la validación estática (igual que `api_routes.dart`).

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd app && mvn test -Dtest='GameSceneFilesTest,DevelopmentTeamStrategyTest'`
Expected: PASS. Si un test existente de juego verifica el número exacto de `commitAgentWork` o el orden de `committed`, no cambia: sin diseño aprobado no hay commit de escenas.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/GameSceneFiles.java app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java app/src/test/java/com/aicompany/core/model/GameSceneFilesTest.java app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java
git commit -m "Juegos por niveles: Forjai commitea la escena .tscn de cada escena del incremento antes de Kael

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Gates de las escenas para Kael

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/GameSceneScriptsGate.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/GameMainSceneGate.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/ReservedGamePathsGate.java`
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (`gameChecks`)
- Test: `app/src/test/java/com/aicompany/core/agent/validation/GameGatesTest.java`
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java`

**Interfaces:**
- Consumes: `approvedGameScenes`, `sceneSkeletonPaths` (Task 10); `GameScenes` (Task 8).
- Produces:
  - `record GameSceneScriptsGate.SceneScript(String id, String script, String path, boolean levels)`; `path` es la ruta del `.cs` con el prefijo del componente.
  - `GameSceneScriptsGate.check(List<GeneratedFile> returned, Map<String, String> existing, List<SceneScript> scenes, List<String> owned)`.
  - `GameMainSceneGate.check(List<GeneratedFile> returned, Map<String, String> existing, String expectedMainScene)`; `expectedMainScene` es `"Main.tscn"` o null. Se mantiene la sobrecarga de 2 argumentos.
  - `ReservedGamePathsGate.check(List<GeneratedFile> files, boolean isQa, java.util.Set<String> forjaiScenes)`; se mantiene la sobrecarga de 2 argumentos.

- [ ] **Step 1: Write the failing tests**

En `GameGatesTest`:

```java
    // Juegos por niveles (spec 2026-10-16 contrato §4).
    private static final List<GameSceneScriptsGate.SceneScript> SCENES = List.of(
            new GameSceneScriptsGate.SceneScript("ESCENA-01", "Main", "game/Main.cs", true));

    @Test void everySceneHasItsScriptWithItsClassAndLevelMembers() {
        var owned = List.of("game");
        assertTrue(GameSceneScriptsGate.check(List.of(), Map.of(), SCENES, owned).get(0).contains("Falta game/Main.cs"));
        var noLevels = GameSceneScriptsGate.check(List.of(new GeneratedFile("game/Main.cs",
                "public partial class Main : Node2D { }")), Map.of(), SCENES, owned);
        assertTrue(noLevels.stream().anyMatch(e -> e.contains("public int Level")), noLevels.toString());
        assertTrue(noLevels.stream().anyMatch(e -> e.contains("StartLevel")), noLevels.toString());
        var wrongClass = GameSceneScriptsGate.check(List.of(new GeneratedFile("game/Main.cs",
                "public class Juego : Node { }")), Map.of(), SCENES, owned);
        assertTrue(wrongClass.stream().anyMatch(e -> e.contains("public partial class Main : Node2D")), wrongClass.toString());
        var ok = "public partial class Main : Node2D { public int Level { get; private set; } = 1;\n"
                + "  public void StartLevel(int level) { Level = level; } }";
        assertEquals(List.of(), GameSceneScriptsGate.check(List.of(new GeneratedFile("game/Main.cs", ok)), Map.of(), SCENES, owned));
        // En una ronda, Kael no lo devuelve: vale el del repositorio.
        assertEquals(List.of(), GameSceneScriptsGate.check(List.of(), Map.of("game/Main.cs", ok), SCENES, owned));
        // Quien no es dueño de game/ no responde por las escenas.
        assertEquals(List.of(), GameSceneScriptsGate.check(List.of(), Map.of(), SCENES, List.of("src/Pong.Domain")));
    }

    @Test void theMainSceneMustBeTheOneOfTheDesign() {
        var godot = new GeneratedFile("game/project.godot", "[application]\nrun/main_scene=\"res://Juego.tscn\"\n");
        var errors = GameMainSceneGate.check(List.of(godot), Map.of("game/Juego.tscn", "", "game/Main.tscn", ""), "Main.tscn");
        assertTrue(errors.stream().anyMatch(e -> e.contains("res://Main.tscn")), errors.toString());
        assertEquals(List.of(), GameMainSceneGate.check(List.of(new GeneratedFile("game/project.godot",
                "[application]\nrun/main_scene=\"res://Main.tscn\"\n")), Map.of("game/Main.tscn", ""), "Main.tscn"));
    }

    @Test void theScenesOfForjaiAreReserved() {
        var errors = ReservedGamePathsGate.check(List.of(new GeneratedFile("game/Main.tscn", "x")), false,
                java.util.Set.of("game/Main.tscn"));
        assertEquals(List.of("game/Main.tscn es de Forjai: no lo escribas (tu escena crea sus nodos en _Ready)."), errors);
        assertEquals(List.of(), ReservedGamePathsGate.check(List.of(new GeneratedFile("game/Main.cs", "x")), false,
                java.util.Set.of("game/Main.tscn")));
    }
```

En `DevelopmentTeamStrategyTest`, un test de que los gates nuevos se aplican a la generación del dueño de `game` (en `context()` es `engineering`, con ownedPaths `["Juego.sln", "game"]`):

```java
    @Test
    @SuppressWarnings("unchecked")
    void withScenesTheOwnerOfGameMustWriteTheirScriptsAndCannotWriteTheScenes() throws Exception {
        stubHappyPath();
        withLeveledGame(1);
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-SCENES"), eq("forjai"), eq("Forjai"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord("8".repeat(40), List.of("game/Main.tscn")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context(), progress);

        var checks = ArgumentCaptor.forClass(java.util.function.Function.class);
        verify(runtime).generate(eq("M-1-ENGINEERING"), anyString(), anyString(), anyString(), anyList(), anyList(),
                anyList(), checks.capture());
        var gate = (java.util.function.Function<DevelopmentResult, List<String>>) checks.getValue();
        var errors = gate.apply(new DevelopmentResult("r", List.of(new GeneratedFile("game/Main.tscn", "x"))));
        assertTrue(errors.stream().anyMatch(e -> e.contains("game/Main.tscn es de Forjai")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("Falta game/Main.cs")), errors.toString());
    }
```

(`stubHappyPath` hace que `readFileAtCommit` devuelva `"contenido"` para cualquier ruta: el `Main.cs` "existente" no tiene la clase, así que el gate pide la clase correcta en vez de «Falta». Para este test, agregar antes de `execute`: `when(workspace.readFileAtCommit(eq("M-1"), anyString(), eq("game/Main.cs"))).thenReturn(null);` y `when(workspace.readFileAtCommit(eq("M-1"), anyString(), eq("game/Main.tscn"))).thenReturn(null);`. Así el esqueleto falta en el HEAD (se commitea) y el script también, y el gate dice «Falta game/Main.cs».)

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn test -Dtest='GameGatesTest,DevelopmentTeamStrategyTest'`
Expected: FAIL (`GameSceneScriptsGate` no existe; faltan las sobrecargas).

- [ ] **Step 3: Implement `GameSceneScriptsGate`**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Juegos por niveles (spec 2026-10-16 contrato §4): cada escena del incremento tiene su script con su clase; las que tienen
 * niveles exponen Level y StartLevel (las partidas de Vera los usan para arrancar en un nivel).
 */
public final class GameSceneScriptsGate {

    public record SceneScript(String id, String script, String path, boolean levels) {
    }

    private static final Pattern LEVEL = Pattern.compile("public\\s+int\\s+Level\\b");
    private static final Pattern START_LEVEL = Pattern.compile("public\\s+void\\s+StartLevel\\s*\\(\\s*int\\b");

    private GameSceneScriptsGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> returned, Map<String, String> existing,
                                     List<SceneScript> scenes, List<String> owned) {
        var errors = new ArrayList<String>();
        for (var scene : scenes) {
            if (!OwnedPaths.coveredByAny(owned, scene.path())) {
                continue;
            }
            var content = returned.stream().filter(Objects::nonNull)
                    .filter(f -> scene.path().equals(f.path())).map(DevelopmentResult.GeneratedFile::content)
                    .filter(Objects::nonNull).findFirst().orElse(existing.get(scene.path()));
            var declaration = "public partial class " + scene.script() + " : Node2D";
            if (content == null) {
                errors.add("Falta " + scene.path() + ": la escena " + scene.id() + " necesita su script con "
                        + declaration + " (Forjai ya creó la .tscn que lo carga).");
                continue;
            }
            var code = SceneSheet.strip(content);
            if (!Pattern.compile("\\bpartial\\s+class\\s+" + Pattern.quote(scene.script()) + "\\s*:\\s*(?:Godot\\.)?Node2D\\b")
                    .matcher(code).find()) {
                errors.add(scene.path() + " debe declarar " + declaration + " (la .tscn de Forjai lo carga en su nodo raíz).");
            }
            if (scene.levels() && !LEVEL.matcher(code).find()) {
                errors.add(scene.path() + ": la escena " + scene.id() + " tiene niveles y debe exponer public int Level { get; }.");
            }
            if (scene.levels() && !START_LEVEL.matcher(code).find()) {
                errors.add(scene.path() + ": la escena " + scene.id() + " tiene niveles y debe exponer "
                        + "public void StartLevel(int level).");
            }
        }
        return errors;
    }
}
```

`SceneSheet.strip` lo crea Task 12. **Para no depender del orden**, en esta tarea crear `SceneSheet.java` mínimo, solo con `strip`. Task 12 le suma la extracción:

```java
package com.aicompany.core.agent.validation;

/** Juegos por niveles (spec 2026-10-16 contrato §4): lee el código C# de las escenas sin modelo. */
public final class SceneSheet {

    private SceneSheet() {
    }

    /** El código sin comentarios ni literales de texto (quedan como ""), para que no confundan a los patrones. */
    public static String strip(String source) {
        var out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
                out.append(' ');
            } else if (c == '"' || c == '\'') {
                boolean verbatim = c == '"' && i > 0 && source.charAt(i - 1) == '@';
                int j = i + 1;
                while (j < source.length()) {
                    char d = source.charAt(j);
                    if (!verbatim && d == '\\') { j += 2; continue; }
                    if (d == c) {
                        if (verbatim && j + 1 < source.length() && source.charAt(j + 1) == '"') { j += 2; continue; }
                        break;
                    }
                    j++;
                }
                out.append(c).append(c);
                i = Math.min(source.length(), j + 1);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
```

- [ ] **Step 4: Implement the overloads**

`GameMainSceneGate`: la versión de 2 argumentos delega en `check(returned, existing, null)`; en la de 3, después de calcular `m.group(1)` y antes de verificar que existe:

```java
        if (expectedMainScene != null && !expectedMainScene.equals(m.group(1))) {
            return List.of(godotPath + ": run/main_scene debe ser \"res://" + expectedMainScene
                    + "\" (ESCENA-01 del diseño de Kael); declara \"res://" + m.group(1) + "\".");
        }
```

`ReservedGamePathsGate`: la versión de 2 argumentos delega en `check(files, isQa, java.util.Set.of())`; en la de 3, como primer caso del `if`:

```java
            if (forjaiScenes.contains(path)) {
                errors.add(path + " es de Forjai: no lo escribas (tu escena crea sus nodos en _Ready).");
            } else if (under(StackProfile.FORJAI_GAME_ROOT, path)) {
```

- [ ] **Step 5: Wire in `gameChecks`**

En `DevelopmentTeamStrategy.gameChecks(context, agentId, headSha)`:

1. Cambiar la firma para recibir los `owned` de la tarea: `gameChecks(TeamMissionContext context, String agentId, List<String> owned, String headSha)`, y en `generate(...)` la llamada pasa a `gameChecks(context, agentId, owned, headSha)`.
2. Después de armar `existing` (los `.tscn` del HEAD), agregar:

```java
        // Juegos por niveles (spec 2026-10-16 contrato §4): escenas del incremento, sus scripts y la escena principal.
        var approved = approvedGameScenes(context.missionId());
        var sceneScripts = new ArrayList<com.aicompany.core.agent.validation.GameSceneScriptsGate.SceneScript>();
        var existingScripts = new java.util.LinkedHashMap<String, String>();
        String mainScene = null;
        if (approved.isPresent()) {
            var a = approved.get();
            for (var s : com.aicompany.core.agent.validation.GameScenes.upTo(a.game(), a.spec(), a.increment())) {
                var path = component == null ? com.aicompany.core.agent.validation.GameScenes.scriptPath(s.script())
                        : component.prefix(com.aicompany.core.agent.validation.GameScenes.scriptPath(s.script()));
                sceneScripts.add(new com.aicompany.core.agent.validation.GameSceneScriptsGate.SceneScript(s.id(),
                        s.script(), path, com.aicompany.core.agent.validation.GameScenes.hasLevels(a.game(), s.id())));
                var content = headSha == null ? null : readOrNull(context.missionId(), headSha, path);
                if (content != null) {
                    existingScripts.put(path, content);
                }
            }
            mainScene = com.aicompany.core.agent.validation.GameScenes.main(a.game())
                    .map(s -> s.script() + ".tscn").orElse(null);
        }
        var forjaiScenes = java.util.Set.copyOf(sceneSkeletonPaths(context));
        var expectedMain = mainScene;
```

3. En el `return result -> {…}`: reemplazar la línea de `GameMainSceneGate` por `GameMainSceneGate.check(result.files(), existing, expectedMain)`, la de `ReservedGamePathsGate` por `ReservedGamePathsGate.check(result.files(), qa, forjaiScenes)` y agregar:

```java
            if (!qa) {
                errors.addAll(com.aicompany.core.agent.validation.GameSceneScriptsGate.check(result.files(),
                        existingScripts, sceneScripts, owned));
            }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd app && mvn test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/GameSceneScriptsGate.java app/src/main/java/com/aicompany/core/agent/validation/SceneSheet.java app/src/main/java/com/aicompany/core/agent/validation/GameMainSceneGate.java app/src/main/java/com/aicompany/core/agent/validation/ReservedGamePathsGate.java app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java app/src/test/java/com/aicompany/core/agent/validation/GameGatesTest.java app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java
git commit -m "Juegos por niveles: gates de escenas para Kael (script y clase, Level/StartLevel, escena principal del diseño, .tscn de Forjai)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: Ficha de las escenas (extracción sin modelo)

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/SceneSheet.java` (creado mínimo en Task 11)
- Test: `app/src/test/java/com/aicompany/core/agent/validation/SceneSheetTest.java` (nuevo)

**Interfaces:**
- Consumes: `SceneSheet.strip` (Task 11).
- Produces:
  - `record SceneSheet.Member(String kind, String type, String name, String parameters)`; `kind` es `PROPERTY`, `FIELD` o `METHOD` y `parameters` es `""` salvo en los métodos.
  - `static List<Member> SceneSheet.extract(String className, java.util.Collection<String> sources)`: miembros públicos de todas las partes `partial` de la clase, en orden de aparición, sin repetir nombres y sin los que empiezan con `_` (los overrides de Godot).
  - `static String SceneSheet.render(Member m)`: por ejemplo `int Level { get; }`, `void StartLevel(int level)` o `Label Puntaje`.

- [ ] **Step 1: Write the failing tests**

```java
package com.aicompany.core.agent.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Juegos por niveles (spec 2026-10-16 contrato §4): Java lee los miembros públicos de la escena, sin modelo. */
class SceneSheetTest {

    static final String MAIN = """
            using Godot;
            // public int Viejo { get; set; }   (comentado: no cuenta)
            public partial class Main : Node2D
            {
                public int Level { get; private set; } = 1;
                public int Puntaje => _puntaje;
                public static readonly float VelocidadBase = 200f;
                public ColorRect Pelota;
                public Vector2 Direccion { get; set; }
                private int _puntaje;
                internal int Oculto;
                public override void _Ready() { var s = "public int Falso;"; }
                public void StartLevel(int level) { Level = level; }
                public bool EstaJugando() => true;
                /* public void Comentado() {} */
            }
            """;

    @Test void publicPropertiesFieldsAndMethodsAreExtracted() {
        var members = SceneSheet.extract("Main", List.of(MAIN));
        assertEquals(List.of("Level", "Puntaje", "VelocidadBase", "Pelota", "Direccion", "StartLevel", "EstaJugando"),
                members.stream().map(SceneSheet.Member::name).toList());
        assertEquals("int Level { get; }", SceneSheet.render(members.get(0)));
        assertEquals("void StartLevel(int level)", SceneSheet.render(members.get(5)));
        assertEquals("ColorRect Pelota", SceneSheet.render(members.get(3)));
        assertEquals("METHOD", members.get(6).kind());
    }

    // Review Focus 5: comentarios y strings no entran a la ficha.
    @Test void commentsAndStringsDoNotCount() {
        var names = SceneSheet.extract("Main", List.of(MAIN)).stream().map(SceneSheet.Member::name).toList();
        assertFalse(names.contains("Viejo"));
        assertFalse(names.contains("Falso"));
        assertFalse(names.contains("Comentado"));
        assertFalse(names.contains("_Ready"));
        assertFalse(names.contains("Oculto"));
    }

    @Test void partialClassesAcrossFilesAreMergedAndOtherClassesIgnored() {
        var other = "public partial class Main { public void Reiniciar() { } }\npublic class Ayuda { public int X; }";
        var names = SceneSheet.extract("Main", List.of(MAIN, other)).stream().map(SceneSheet.Member::name).toList();
        assertTrue(names.contains("Reiniciar"), names.toString());
        assertFalse(names.contains("X"), names.toString());
    }

    @Test void genericAndNullableTypesAreRead() {
        var src = "public partial class Main : Node2D { public List<ColorRect> Pelotas { get; } = new(); public int? Meta; }";
        var members = SceneSheet.extract("Main", List.of(src));
        assertEquals("List<ColorRect> Pelotas { get; }", SceneSheet.render(members.get(0)));
        assertEquals("int? Meta", SceneSheet.render(members.get(1)));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn test -Dtest=SceneSheetTest`
Expected: FAIL de compilación (`extract`, `Member` y `render` no existen).

- [ ] **Step 3: Implement**

Agregar a `SceneSheet`:

```java
    public record Member(String kind, String type, String name, String parameters) {
    }

    private static final java.util.regex.Pattern MEMBER = java.util.regex.Pattern.compile(
            "\\bpublic\\s+(?:(?:static|readonly|override|virtual|new|sealed|const|async)\\s+)*"
                    + "([A-Za-z_][\\w.]*(?:<[^;{}()=]*?>)?\\??(?:\\[\\])?)\\s+([A-Za-z_]\\w*)\\s*(\\(|\\{|=>|=|;)");

    public static List<Member> extract(String className, java.util.Collection<String> sources) {
        var out = new java.util.ArrayList<Member>();
        var seen = new java.util.HashSet<String>();
        var header = java.util.regex.Pattern.compile("\\bclass\\s+" + java.util.regex.Pattern.quote(className) + "\\b[^{]*\\{");
        for (var source : sources) {
            if (source == null) {
                continue;
            }
            var code = strip(source);
            var m = header.matcher(code);
            while (m.find()) {
                var body = body(code, m.end());
                var member = MEMBER.matcher(topLevel(body));
                while (member.find()) {
                    var name = member.group(2);
                    if (name.startsWith("_") || name.equals(className) || !seen.add(name)) {
                        continue;
                    }
                    var after = member.group(3);
                    if (after.equals("(")) {
                        var close = body.indexOf(')', bodyIndex(body, member.end()));
                        out.add(new Member("METHOD", member.group(1), name, body.substring(bodyIndex(body, member.end()),
                                Math.max(bodyIndex(body, member.end()), close)).strip().replaceAll("\\s+", " ")));
                    } else {
                        out.add(new Member(after.equals("{") || after.equals("=>") ? "PROPERTY" : "FIELD",
                                member.group(1), name, ""));
                    }
                }
            }
        }
        return out;
    }

    public static String render(Member m) {
        return switch (m.kind()) {
            case "METHOD" -> m.type() + " " + m.name() + "(" + m.parameters() + ")";
            case "PROPERTY" -> m.type() + " " + m.name() + " { get; }";
            default -> m.type() + " " + m.name();
        };
    }

    /** El cuerpo de la clase desde la llave de apertura (start = después de '{') hasta su cierre. */
    private static String body(String code, int start) {
        int depth = 1;
        int i = start;
        while (i < code.length() && depth > 0) {
            char c = code.charAt(i);
            if (c == '{') depth++;
            if (c == '}') depth--;
            i++;
        }
        return code.substring(start, Math.max(start, i - 1));
    }

    /**
     * El cuerpo con el contenido de las llaves anidadas (cuerpos de métodos y de propiedades) reemplazado por espacios,
     * del mismo largo: las posiciones coinciden con body y solo quedan las declaraciones de nivel de clase.
     */
    private static String topLevel(String body) {
        var out = new StringBuilder(body);
        int depth = 0;
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (c == '{') {
                depth++;
                if (depth > 1) out.setCharAt(i, ' ');
            } else if (c == '}') {
                if (depth > 1) out.setCharAt(i, ' ');
                depth--;
            } else if (depth > 0 && c != '\n') {
                out.setCharAt(i, ' ');
            }
        }
        return out.toString();
    }

    private static int bodyIndex(String body, int matchEnd) {
        return Math.min(matchEnd, body.length());
    }
```

Cómo se arma: `topLevel` deja intactas las declaraciones de primer nivel y blanquea todo lo que está entre llaves internas. Así `public int Level { get; private set; } = 1;` matchea por `{`, y `var s = "..."` dentro de `_Ready` no aparece. Las posiciones del matcher sobre `topLevel(body)` sirven en `body`, porque los dos tienen el mismo largo, y de ahí sale el texto de los parámetros.

**Si un caso de los tests no pasa con este armado**, ajustar `MEMBER` o `topLevel` hasta que pasen los cuatro tests, sin cambiarlos. Tienen que seguir valiendo los nombres exactos y que no entre nada de comentarios, strings ni cuerpos.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd app && mvn test -Dtest='SceneSheetTest,GameGatesTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/SceneSheet.java app/src/test/java/com/aicompany/core/agent/validation/SceneSheetTest.java
git commit -m "Juegos por niveles: Java extrae la ficha de cada escena (miembros públicos) sin modelo

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Vera recibe la ficha, el gate lo hace cumplir y QA va al final

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/PlaythroughSheetGate.java`
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/PlaythroughSheetGateTest.java` (nuevo)
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java`

**Interfaces:**
- Consumes: `SceneSheet.extract/render/strip` (Task 12); `approvedGameScenes`, `GameScenes` (Tasks 8 y 10).
- Produces:
  - `PlaythroughSheetGate.check(List<GeneratedFile> files, Map<String, List<SceneSheet.Member>> contracts)`; la clave del mapa es el `script` de cada escena del diseño, y solo mira los archivos bajo `game/playthroughs/`.
  - `PlaythroughSheetGate.GODOT_MEMBERS`, la lista fija de lo heredado de Node/Node2D que se permite.
  - `DevelopmentTeamStrategy.orderTasks(List<PlannedTask> work, TeamPlan plan, java.util.function.Predicate<String> isQa)`; sigue la versión de 2 argumentos (sin QA al final).
  - `String DevelopmentTeamStrategy.sceneSheetBlock(TeamMissionContext context, String headSha)`, package-private.

- [ ] **Step 1: Write the failing tests**

`PlaythroughSheetGateTest.java`:

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Juegos por niveles (spec 2026-10-16 contrato §4): las partidas de Vera solo usan lo que existe en la escena. */
class PlaythroughSheetGateTest {

    static final Map<String, List<SceneSheet.Member>> CONTRACTS = Map.of(
            "Main", SceneSheet.extract("Main", List.of(SceneSheetTest.MAIN)),
            "Menu", SceneSheet.extract("Menu", List.of("public partial class Menu : Node2D { public bool Visible2; }")));

    private static List<String> check(String body) {
        return PlaythroughSheetGate.check(List.of(new GeneratedFile("game/playthroughs/ESC_01_x.cs", body)), CONTRACTS);
    }

    @Test void anUnknownMemberIsRejectedWithTheExistingOnes() {
        var errors = check("yield return Assert(() => ((Main)main).Pelotaa.Position.X > 0, \"x\");");
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("Main.Pelotaa no existe"), errors.get(0));
        assertTrue(errors.get(0).contains("Level, Puntaje"), errors.get(0));
    }

    @Test void validMembersCastsAliasesAndAsPass() {
        assertEquals(List.of(), check("""
                public override IEnumerable<Step> Steps(Node main) {
                    var juego = (Main)main;
                    Main otro = main as Main;
                    yield return Do(m => ((Main)m).StartLevel(2));
                    yield return Assert(() => juego.Level == 2 && otro.Puntaje == 0 && ((Main)main).Pelota != null, "ok");
                }
                """));
    }

    @Test void aliasesAreChecked() {
        var errors = check("var juego = main as Main;\nyield return Assert(() => juego.EstadoJuego == 1, \"x\");");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Main.EstadoJuego no existe")), errors.toString());
    }

    @Test void theSceneMustBeOneOfTheDesign() {
        var errors = check("public override string Scene => \"Creditos\";");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Creditos") && e.contains("Main, Menu")), errors.toString());
    }

    // Review Focus 4: lo heredado de Godot se permite; castear main a algo que no es escena, no.
    @Test void inheritedGodotMembersAreAllowedButMainCastToANonSceneIsNot() {
        assertEquals(List.of(), check("yield return Assert(() => ((Main)main).GetNode<Label>(\"L\") != null "
                + "&& ((Main)main).Position.X == 0, \"x\");"));
        var errors = check("var n = (Node2D)main;");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Node2D no es una escena del diseño")), errors.toString());
    }

    // Review Focus 5: un string con texto parecido a un acceso no dispara el gate.
    @Test void stringsDoNotCount() {
        assertEquals(List.of(), check("yield return Assert(() => true, \"((Main)main).Inexistente no se usa\");"));
    }

    @Test void filesOutsidePlaythroughsAreIgnored() {
        assertEquals(List.of(), PlaythroughSheetGate.check(List.of(new GeneratedFile("tests/X.cs",
                "var j = (Main)main; j.Nada = 1;")), CONTRACTS));
    }
}
```

En `DevelopmentTeamStrategyTest`:

```java
    // Juegos por niveles (spec 2026-10-16 contrato §4): en un juego, Vera genera después del dueño de game aunque Neo la
    // ponga antes y las dos rutas caigan en la capa GAME.
    @Test
    void inAGameQaGoesLast() {
        var plan = context().plan();
        var vera = new PlannedTask("qa", "WORK", "TESTS", "Partidas", List.of("QA"), List.of("game/playthroughs"));
        var neo = new PlannedTask("engineering", "WORK", "GAME", "Juego", List.of("x"), List.of("game"));
        var ordered = DevelopmentTeamStrategy.orderTasks(List.of(vera, neo), plan, "qa"::equals);
        assertEquals(List.of("engineering", "qa"), ordered.stream().map(PlannedTask::agentId).toList());
    }

    // Vera recibe la ficha leído del código commiteado de la escena (sin modelo).
    @Test
    void veraGetsTheSceneSheetFromTheCommittedCode() throws Exception {
        stubHappyPath();
        withLeveledGame(1);
        when(workspace.commitAgentWork(eq("M-1"), eq("M-1-SCENES"), eq("forjai"), eq("Forjai"), any()))
                .thenReturn(new DevelopmentWorkspaceService.CommitRecord("8".repeat(40), List.of("game/Main.tscn")));
        when(workspace.readFileAtCommit(eq("M-1"), anyString(), eq("game/Main.tscn"))).thenReturn(null);
        when(workspace.filesAtCommit(eq("M-1"), anyString())).thenReturn(List.of("game/Main.cs", "game/Main.tscn"));
        when(workspace.readFileAtCommit(eq("M-1"), anyString(), eq("game/Main.cs"))).thenReturn(
                "public partial class Main : Node2D { public int Level { get; private set; } public void StartLevel(int level) { } public int Puntaje; }");
        var context = withQaWork(); // usar el helper existente que agrega una tarea WORK de Vera; si el existente arma
                                    // un plan fullstack, crear aquí la variante de juego (ver nota abajo).
        var prompt = ArgumentCaptor.forClass(String.class);
        when(runtime.generate(eq("M-1-QA"), anyString(), anyString(), prompt.capture(), anyList(), anyList(), anyList(), any()))
                .thenReturn(CompletableFuture.completedFuture(dev("game/playthroughs/ESC_01_x.cs")));
        when(runtime.review(anyString(), anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        strategy.execute(context, progress);

        assertTrue(prompt.getValue().contains("FICHA DE LAS ESCENAS"), prompt.getValue());
        assertTrue(prompt.getValue().contains("- Main (game/Main.tscn, escena principal): int Level { get; }; "
                + "void StartLevel(int level); int Puntaje"), prompt.getValue());
        assertTrue(prompt.getValue().contains("Do(main => ((Main)main).StartLevel(2))"), prompt.getValue());
    }
```

Nota para el test de Vera: `withQaWork()` del archivo arma hoy el contexto fullstack. Para el juego, crear en el test un `TeamMissionContext` a partir de `context()` que reemplace la tarea `VALIDATION` de `qa` por dos tareas de `qa`: una `WORK` con ownedPaths `["game/playthroughs"]` y una `VALIDATION`. Copiar cómo arma `withQaWork()` el plan con `new TeamPlan(...)`. Agregar los stubs de commit de `M-1-QA` como en `qaWithATestsTaskReviewsUnderItsOwnTaskId`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn test -Dtest='PlaythroughSheetGateTest,DevelopmentTeamStrategyTest'`
Expected: FAIL.

- [ ] **Step 3: Implement `PlaythroughSheetGate`**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;
import com.aicompany.core.model.StackProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Juegos por niveles (spec 2026-10-16 contrato §4): antes del sandbox, las partidas de Vera solo usan miembros que existen en
 * la escena (el Pong 3 usaba Main.Pelota, FinalScoreLabel y EstadoJuego, que no existían). Reconoce (X)main, main as X
 * y las variables asignadas con ellos; lo que no reconoce lo juzga el compilador en el sandbox.
 */
public final class PlaythroughSheetGate {

    /** Lo heredado de Node/Node2D que una partida puede usar sobre la escena. */
    public static final Set<String> GODOT_MEMBERS = Set.of("GetNode", "GetNodeOrNull", "GetChildren", "GetChild",
            "GetChildCount", "FindChild", "Position", "GlobalPosition", "Visible", "Name", "IsInsideTree", "GetTree",
            "Rotation", "Scale");

    private static final Pattern SCENE = Pattern.compile("\\bScene\\s*=>\\s*\"([^\"]*)\"");
    private static final Pattern CAST_ACCESS = Pattern.compile("\\(\\s*\\(\\s*([A-Za-z_]\\w*)\\s*\\)\\s*([A-Za-z_]\\w*)\\s*\\)\\s*\\.\\s*([A-Za-z_]\\w*)");
    private static final Pattern MAIN_CAST = Pattern.compile("\\(\\s*([A-Za-z_]\\w*)\\s*\\)\\s*main\\b|\\bmain\\s+as\\s+([A-Za-z_]\\w*)");
    private static final Pattern ALIAS = Pattern.compile("\\b(?:var|[A-Z]\\w*)\\s+([A-Za-z_]\\w*)\\s*=\\s*(?:\\(\\s*([A-Za-z_]\\w*)\\s*\\)\\s*main\\b|main\\s+as\\s+([A-Za-z_]\\w*))");

    private PlaythroughSheetGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> files,
                                     Map<String, List<SceneSheet.Member>> contracts) {
        var errors = new LinkedHashSet<String>();
        var scenes = contracts.keySet().stream().sorted().collect(Collectors.joining(", "));
        for (var f : files.stream().filter(Objects::nonNull).toList()) {
            var path = f.path() == null ? "" : OwnedPaths.normalize(f.path());
            if (!path.contains(StackProfile.PLAYTHROUGHS_ROOT + "/") || f.content() == null) {
                continue;
            }
            var scene = SCENE.matcher(f.content());
            while (scene.find()) {
                if (!contracts.containsKey(scene.group(1))) {
                    errors.add(path + ": Scene \"" + scene.group(1) + "\" no es una escena del diseño (existen: " + scenes + ").");
                }
            }
            var code = SceneSheet.strip(f.content());
            var cast = MAIN_CAST.matcher(code);
            while (cast.find()) {
                var type = cast.group(1) != null ? cast.group(1) : cast.group(2);
                if (!contracts.containsKey(type)) {
                    errors.add(path + ": " + type + " no es una escena del diseño: main es una de " + scenes + ".");
                }
            }
            var aliases = new HashMap<String, String>();
            var alias = ALIAS.matcher(code);
            while (alias.find()) {
                var type = alias.group(2) != null ? alias.group(2) : alias.group(3);
                if (contracts.containsKey(type)) {
                    aliases.put(alias.group(1), type);
                }
            }
            var access = CAST_ACCESS.matcher(code);
            while (access.find()) {
                if (contracts.containsKey(access.group(1))) {
                    member(path, access.group(1), access.group(3), contracts, errors);
                }
            }
            aliases.forEach((variable, type) -> {
                var use = Pattern.compile("(?<![\\w.])" + Pattern.quote(variable) + "\\s*\\.\\s*([A-Za-z_]\\w*)").matcher(code);
                while (use.find()) {
                    member(path, type, use.group(1), contracts, errors);
                }
            });
        }
        return new ArrayList<>(errors);
    }

    private static void member(String path, String type, String name, Map<String, List<SceneSheet.Member>> contracts,
                               Set<String> errors) {
        var members = contracts.get(type);
        if (GODOT_MEMBERS.contains(name) || members.stream().anyMatch(m -> m.name().equals(name))) {
            return;
        }
        errors.add(path + ": " + type + "." + name + " no existe; existen: "
                + members.stream().map(SceneSheet.Member::name).collect(Collectors.joining(", ")) + ".");
    }
}
```

El test `validMembersCastsAliasesAndAsPass` incluye `Do(m => ((Main)m).StartLevel(2))`: `CAST_ACCESS` acepta cualquier variable castada a una escena, y `MAIN_CAST` solo juzga los casts de `main`.

- [ ] **Step 4: Implement in `DevelopmentTeamStrategy`**

1. `orderTasks`: la versión de 2 argumentos delega en `orderTasks(work, plan, agentId -> false)`; la nueva:

```java
    /** Juegos por niveles (spec 2026-10-16 contrato §4): en un juego, QA (las partidas de Vera) va siempre al final. */
    static List<PlannedTask> orderTasks(List<PlannedTask> work, TeamPlan plan, java.util.function.Predicate<String> isQa) {
        var components = plan.resolvedComponents();
        if (components.isEmpty()) {
            return work;
        }
        var game = plan.hasComponent(StackProfile.GODOT_DOTNET_GAME);
        return work.stream()
                .sorted(java.util.Comparator.comparingInt(task -> game && isQa.test(task.agentId()) ? 2_000
                        : task.ownedPathsOrEmpty().stream()
                        .map(path -> rank(components, plan.contextNames(), path))
                        .min(Integer::compare).orElse(Integer.MAX_VALUE)))
                .toList();
    }
```

y en `execute`: `var ordered = orderTasks(work, plan, agentId -> isQa(context, agentId));`.

2. Ficha de escena para Vera:

```java
    /** Contratos de las escenas del incremento, leídos del código commiteado en headSha (sin modelo). */
    Map<String, List<com.aicompany.core.agent.validation.SceneSheet.Member>> sceneContracts(TeamMissionContext context,
                                                                                               String headSha) {
        var approved = approvedGameScenes(context.missionId());
        if (approved.isEmpty() || headSha == null) {
            return Map.of();
        }
        var a = approved.get();
        var sources = new ArrayList<String>();
        try {
            for (var path : workspace.filesAtCommit(context.missionId(), headSha)) {
                if (path.endsWith(".cs") && (path.startsWith("game/") || path.contains("/game/"))
                        && !path.contains(StackProfile.PLAYTHROUGHS_ROOT + "/") && !path.contains(StackProfile.FORJAI_GAME_ROOT + "/")) {
                    var content = readOrNull(context.missionId(), headSha, path);
                    if (content != null) {
                        sources.add(content);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("MISSION {} - could not read the scene scripts: {}", context.missionId(), safeMessage(ex, "sin detalle"));
            return Map.of();
        }
        var contracts = new LinkedHashMap<String, List<com.aicompany.core.agent.validation.SceneSheet.Member>>();
        for (var s : nnScenes(a)) {
            contracts.put(s.script(), com.aicompany.core.agent.validation.SceneSheet.extract(s.script(), sources));
        }
        return contracts;
    }

    private static List<com.aicompany.core.agent.model.InteractionDesign.Scene> nnScenes(
            com.aicompany.core.agent.validation.GameScenes.Approved a) {
        return com.aicompany.core.agent.validation.GameScenes.upTo(a.game(), a.spec(), a.increment());
    }

    /** Juegos por niveles (spec 2026-10-16 contrato §4): lo que recibe Vera; vacío si no es un juego con escenas. */
    String sceneSheetBlock(TeamMissionContext context, String headSha) {
        var contracts = sceneContracts(context, headSha);
        if (contracts.isEmpty()) {
            return "";
        }
        var main = approvedGameScenes(context.missionId())
                .flatMap(a -> com.aicompany.core.agent.validation.GameScenes.main(a.game()))
                .map(com.aicompany.core.agent.model.InteractionDesign.Scene::script).orElse("");
        return "\n\nFICHA DE LAS ESCENAS (Java lo leyó del código commiteado de Kael, commit " + headSha
                + "): en las partidas solo puedes usar estos miembros de cada escena; cualquier otro no existe.\n"
                + contracts.entrySet().stream().map(e -> "- " + e.getKey() + " ("
                        + com.aicompany.core.agent.validation.GameScenes.scenePath(e.getKey())
                        + (e.getKey().equals(main) ? ", escena principal" : "") + "): "
                        + (e.getValue().isEmpty() ? "(sin miembros públicos)" : e.getValue().stream()
                        .map(com.aicompany.core.agent.validation.SceneSheet::render).collect(Collectors.joining("; "))))
                .collect(Collectors.joining("\n"))
                + "\nPara otra escena que no sea la principal: public override string Scene => \"<script>\";. Para arrancar "
                + "en un nivel: yield return Do(main => ((" + main + ")main).StartLevel(2)); antes de los Press/Wait.\n";
    }
```

3. En el loop de generación, en las dos ramas de `generate(...)` (ronda > 0 y primera), sumar al prompt `+ (isQa(context, task.agentId()) ? sceneSheetBlock(context, generationHead) : "")` justo después de `buildWorkPrompt(context, task)` (y de `ROUND_RULE` en la rama de ronda). En una ronda de corrección `generationHead` es el último commit, así que la ficha se recalcula desde ahí.

4. En `gameChecks`, para `qa`: calcular `var contracts = qa ? sceneContracts(context, headSha) : Map.<String, List<com.aicompany.core.agent.validation.SceneSheet.Member>>of();` y en el `return result -> {…}`:

```java
            if (qa && !contracts.isEmpty()) {
                errors.addAll(com.aicompany.core.agent.validation.PlaythroughSheetGate.check(result.files(), contracts));
            }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd app && mvn test`
Expected: PASS (incluido `fullstackTasksRunBackendThenAppThenTests`, que usa la versión de 2 argumentos).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/PlaythroughSheetGate.java app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java app/src/test/java/com/aicompany/core/agent/validation/PlaythroughSheetGateTest.java app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java
git commit -m "Juegos por niveles: Vera recibe el ficha de las escenas, el gate lo hace cumplir antes del sandbox y QA va al final

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: El runner arranca otra escena o un nivel (`Scene` y `Do`)

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/GameRunnerFiles.java`
- Test: `app/src/test/java/com/aicompany/core/model/GameRunnerFilesTest.java`

**Interfaces:**
- Produces (C#, namespace `Forjai.Playthroughs`):
  - `public virtual string? Scene => null;`, en `Playthrough`;
  - `protected static Step Do(Action<Node> prepare)`;
  - `StepKind.Do`;
  - `Step(..., Action<Node>? Prepare = null)`.

- [ ] **Step 1: Write the failing test**

En `GameRunnerFilesTest`:

```java
    // Juegos por niveles (spec 2026-10-16 contrato §4): una partida puede cargar otra escena y preparar el nivel.
    @Test void aPlaythroughCanChooseItsSceneAndPrepareTheLevel() {
        var p = GameRunnerFiles.PLAYTHROUGH;
        assertTrue(p.contains("public virtual string? Scene => null;"), p);
        assertTrue(p.contains("protected static Step Do(Action<Node> prepare) => new(StepKind.Do, null, 0, null, \"preparar\", prepare);"), p);
        assertTrue(p.contains("public enum StepKind { Press, Wait, Assert, Do }"), p);
        assertTrue(p.contains("Action<Node>? Prepare = null"), p);
        var run = GameRunnerFiles.RUNNER.substring(GameRunnerFiles.RUNNER.indexOf("private async Task<Result> Run("));
        assertTrue(run.contains("var scenePath = playthrough.Scene == null ? mainPath : \"res://\" + playthrough.Scene + \".tscn\";"), run);
        assertTrue(run.contains("ResourceLoader.Exists(scenePath)"), run);
        assertTrue(run.contains("case StepKind.Do:"), run);
        assertTrue(run.contains("s.Prepare!(main);"), run);
        // La instancia de la partida se crea antes de cargar la escena (decide cuál).
        assertTrue(run.indexOf("Activator.CreateInstance(type)") < run.indexOf("GD.Load<PackedScene>(scenePath)"), run);
        assertTrue(run.indexOf("GD.Seed(0);") < run.indexOf("GD.Load<PackedScene>(scenePath)"), run);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd app && mvn test -Dtest=GameRunnerFilesTest`
Expected: FAIL.

- [ ] **Step 3: Implement**

En `PLAYTHROUGH`, dentro de `Playthrough`, después de `Steps`:

```csharp
                /// <summary>La escena que carga la partida (el script de una escena del diseño); null = la principal.</summary>
                public virtual string? Scene => null;
```

y después de `Assert`:

```csharp
                /// <summary>Prepara el estado antes de jugar, p. ej. Do(main => ((Main)main).StartLevel(2)).</summary>
                protected static Step Do(Action<Node> prepare) => new(StepKind.Do, null, 0, null, "preparar", prepare);
```

y cambiar:

```csharp
            public enum StepKind { Press, Wait, Assert, Do }

            public sealed record Step(StepKind Kind, string? Action, int Frames, Func<bool>? Check, string? Description,
                Action<Node>? Prepare = null);
```

En `RUNNER`, dentro de `Run`, reemplazar desde `GD.Seed(0);` hasta `var playthrough = (Playthrough)Activator.CreateInstance(type)!;` por:

```csharp
                        var playthrough = (Playthrough)Activator.CreateInstance(type)!;
                        var scenePath = playthrough.Scene == null ? mainPath : "res://" + playthrough.Scene + ".tscn";
                        if (!ResourceLoader.Exists(scenePath))
                        {
                            return Fail(type, 0, "la escena " + scenePath + " no existe");
                        }
                        // Cada partida arranca de la misma semilla, sin depender de las partidas anteriores.
                        GD.Seed(0);
                        main = GD.Load<PackedScene>(scenePath).Instantiate();
                        // Como en el juego real: la escena cuelga de la raíz y es la escena actual.
                        GetTree().Root.AddChild(main);
                        GetTree().CurrentScene = main;
                        await NextFrame();
```

y en el `switch` agregar:

```csharp
                                case StepKind.Do:
                                    s.Prepare!(main);
                                    break;
```

El resto del `foreach` (que ahora usa `playthrough.Steps(main)`) no cambia. Hay que cuidar el escape de Java: en el text block, `"res://"` y `".tscn"` van con comillas normales. Solo el literal que ya estaba (`\\"`) lleva escape doble.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd app && mvn test -Dtest=GameRunnerFilesTest`
Expected: PASS.

- [ ] **Step 5: Verificación manual en el sandbox (obligatoria; en Kael híbrido encontró 2 bugs que los tests no veían)**

Armar un proyecto mínimo con el runner real y correrlo en la imagen del sandbox:

```bash
cd /home/alex/Documentos/projectos/empresa/app && mvn -q -DskipTests compile
S=$(mktemp -d ~/forjai-sandbox-work/niveles-XXXX)
cat > /tmp/claude-dump.jsh <<EOF
import com.aicompany.core.model.*;
import java.nio.file.*;
var root = Path.of("$S");
for (var f : ProjectScaffold.generate(StackProfile.GODOT_DOTNET_GAME, java.util.List.of("Pong"))) { var p = root.resolve(f.path()); Files.createDirectories(p.getParent()); Files.writeString(p, f.content()); }
for (var s : java.util.List.of("Main", "Menu")) { var f = GameSceneFiles.skeleton(s); Files.writeString(root.resolve(f.path()), f.content()); }
/exit
EOF
jshell --class-path target/classes /tmp/claude-dump.jsh
```

(Usar el scratchpad de la sesión en vez de `/tmp` para el `.jsh`.) Después escribir a mano en `$S`:

- `game/project.godot`, con `[application]\nrun/main_scene="res://Main.tscn"\n[dotnet]\nproject/assembly_name="Game"\n` (copiar el bloque `[dotnet]` y el resto del `project.godot` de `~/forjai-products/MISSION-KAEL-PONG-1792113455/game/project.godot`);
- `game/Main.cs`, con `public partial class Main : Node2D { public int Level { get; private set; } = 1; public override void _Ready() { AddChild(new ColorRect { Name = "Pelota" }); } public void StartLevel(int level) { Level = level; } }` (con `using Godot;`);
- `game/Menu.cs`, con `public partial class Menu : Node2D { public bool Listo => true; }`;
- `game/playthroughs/ESC_01_nivel2.cs`, que usa `Do(main => ((Main)main).StartLevel(2))` y `Assert(() => ((Main)main).Level == 2, "nivel 2")`;
- `game/playthroughs/ESC_02_menu.cs`, con `public override string? Scene => "Menu";` y `Assert(() => ((Menu)main).Listo, "menú")`;
- `game/playthroughs/ESC_03_falla.cs`, con `Do(main => ((Main)main).StartLevel(3))` y `Assert(() => ((Main)main).Level == 2, "debe fallar")`.

Los `src/Pong.Domain` y `src/Pong.Application` del scaffold necesitan al menos un `.cs` cada uno (por ejemplo `namespace Pong.Domain; public static class Marca { }`), y `tests/Pong.Tests`, un test xUnit trivial.

```bash
cd "$S" && git init -q && git add -A && git -c user.email=f@f -c user.name=f commit -qm x
for step in restore build test; do
  podman run --rm --network=none --read-only --tmpfs /tmp --tmpfs /home -e HOME=/home \
    -v "$S":/work:Z -v ~/forjai-deps/nuget:/deps/nuget:ro,z localhost/forjai-sandbox/godot-dotnet-game:2 /forjai/run.sh $step; echo "rc=$?"
done
cat "$S/.forjai/playthroughs.json"
```

(La ruta de la caché NuGet y los flags exactos se copian de cómo `sandbox-runner` lanza el contenedor: `grep -n "podman\|/deps/nuget" sandbox-runner/src/main/java -r` antes de correrlo; si `restore` necesita red, montar la caché que ya usa el runner.)

Expected: `restore` y `build` con rc 0; `test` con rc 1, porque ESC_03 falla a propósito. En `playthroughs.json`: `ESC_01_nivel2` PASSED, `ESC_02_menu` PASSED y `ESC_03_falla` FAILED con «falló la aserción: debe fallar». Registrar el resultado (o los bugs encontrados y su arreglo) en el ledger y luego en `docs/HISTORY.md` (Task 15).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/GameRunnerFiles.java app/src/test/java/com/aicompany/core/model/GameRunnerFilesTest.java
git commit -m "Runner de partidas: Scene elige la escena y Do prepara el nivel (verificado en el sandbox Godot)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---


### Task 15: Documentación y suite completa

**Files:**
- Modify: `docs/ARCHITECTURE.md`, `docs/HISTORY.md`, `docs/FALTANTES.md`, `docs/EVENTS.md`, `CLAUDE.md`

- [ ] **Step 1: ARCHITECTURE**

**En la sección de Aria**, agregar un párrafo «Contrato y épicas (2026-10-16)» con:
- el `ClientContract` (ítems `MOD-NN` y sus `kind`, `useEpics`, `epicsReason`) y sus reglas (`ContractValidator`);
- la primera parada «Contrato listo (Aria)» en `AWAITING_SPEC_APPROVAL`: los mismos botones y el chat («muéstrame / aprueba / rechaza el contrato de MISSION-X», «para MISSION-X: …»), y la aprobación por policy;
- `withContract`: Java copia los ítems, `useEpics` y la experiencia;
- `contractItem` en cada HU, la cobertura y el nivel no partido;
- las épicas agrupadas por Java en los textos;
- el retome (`CONTRACT_ADJUSTMENT`, `AWAIT_APPROVAL` con el contrato en DRAFT);
- la compatibilidad con misiones sin contrato.

**En la sección de Kael / juegos**, agregar:
- `scenes` y `levels` (uno por ítem `LEVEL`/`SCENE` del contrato) y sus reglas;
- los esqueletos `.tscn` de Forjai (commit `-SCENES`, reservados);
- `GameSceneScriptsGate`, `GameMainSceneGate` contra ESCENA-01, la ficha de la escena para Vera (`SceneSheet`) y `PlaythroughSheetGate`;
- QA al final en juegos;
- `Scene` y `Do` del runner;
- la compatibilidad con juegos sin escenas.

**En la revisión conjunta**, mencionar `MOD-NN` y `ESCENA-NN` como referencias.

- [ ] **Step 2: EVENTS**

En `docs/EVENTS.md` agregar `EMPRESA_CONTRACT_READY`, `EMPRESA_CONTRACT_APPROVED`, `EMPRESA_CONTRACT_ADJUSTED` y `EMPRESA_CONTRACT_REJECTED`, con su payload:
- `version`;
- `items` en READY;
- `approvedBy` cuando es `policy`;
- `instruction` en ADJUSTED.

- [ ] **Step 3: HISTORY**

Agregar una entrada «Contrato, épicas y juegos por niveles (2026-10-16)» con:
- las decisiones del fundador, citadas del spec: un juego es igual que una aplicación; del contrato salen las épicas, las HU y los documentos de ingeniería; las épicas son opcionales y las propone Aria; entrega A antes que B;
- las causas del Pong 3;
- los rulings de este plan y el de `GameSceneFiles` aparte de `ProjectScaffold`;
- el resultado de la verificación manual del runner (Task 14 Step 5);
- que quedan pendientes el despliegue, la prueba en vivo y la entrega B.

- [ ] **Step 4: FALTANTES**

Agregar como pendientes:
- [ ] el despliegue de la revisión conjunta y de esta entrega, con OK del fundador;
- [ ] la prueba en vivo del Pong de 3 niveles con contrato;
- [ ] **entrega B**: refinar HU tras cada entrega, ajustar lo construido y cambiar un contrato aprobado. Es el pendiente de Aria «HU nuevas después de aprobar».

Si alguna entrada del Pong 3 («contrato Kael↔Vera», «Main.tscn sin script») queda cubierta por el código, marcarla `[x]` con la referencia a este plan. La parte en vivo sigue pendiente.

- [ ] **Step 5: CLAUDE.md**

En «Arquitectura en grande», en la línea del **Development Group**, cambiar «Aria escribe requisitos (HU + escenarios `ESC-NN` + incrementos)» por «Aria levanta el contrato del cliente (el fundador lo aprueba) y desde él escribe requisitos (HU con su ítem del contrato, épicas opcionales, escenarios `ESC-NN` e incrementos)».

- [ ] **Step 6: Suite completa y frontend**

Run: `cd app && mvn test` → PASS (anotar el número de tests en el ledger).
Run: `cd app/frontend && npm run lint && npm run build` → sin errores.
Run: `cd sandbox-runner && mvn test` → PASS (no debería cambiar).

- [ ] **Step 7: Commit**

```bash
git add docs/ARCHITECTURE.md docs/HISTORY.md docs/FALTANTES.md docs/EVENTS.md CLAUDE.md
git commit -m "Docs: contrato del cliente, épicas y juegos por niveles (entrega A)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 16: Despliegue y prueba en vivo (requiere OK del fundador; no es de un subagente)

Esta tarea la hace el controlador con el fundador, no un implementador.

- [ ] **Step 1: Misiones vivas.** Consultar en un paso aparte si hay misiones vivas (`GET /api/company/missions` filtrando estados no terminales). Si hay alguna, parar y avisar.
- [ ] **Step 2: Despliegue.** Con el OK del fundador, reconstruir y levantar `company-core`: `docker compose build company-core && docker compose up -d company-core`. Así entran juntas la revisión conjunta (Task 8 del plan `2026-10-15`) y este trabajo. El `sandbox-runner` no cambia, porque el runner de partidas vive en el repositorio de cada misión. Verificar `GET http://localhost:8081/actuator/health`.
- [ ] **Step 3: Inhibidor.** Prender el inhibidor de suspensión con `systemd-inhibit --what=sleep:idle:handle-lid-switch …`, incluido `idle`.
- [ ] **Step 4: Misión y contrato.** Con el OK del fundador, crear una misión del Development Group con el Pong de 3 niveles: «Un Pong: nivel 1 pelota normal hasta 50 puntos; nivel 2 la pelota aumenta de velocidad; nivel 3 dos pelotas». Comprobar que:
  - la misión se detiene en «Contrato listo (Aria)»;
  - el contrato tiene los 3 niveles como ítems `LEVEL` (más los `SYSTEM` que proponga Aria) y lleva épicas.
- [ ] **Step 5: Aprobación del contrato.** El fundador aprueba o ajusta el contrato. Comprobar que:
  - cada HU tiene su `contractItem` y hay un incremento por nivel, o el `goal` explica por qué se agrupan;
  - Kael diseña ESCENA-01 y un `levels` por nivel;
  - la revisión conjunta corre;
  - el mensaje muestra las HU por épica.
- [ ] **Step 6: Incremento 1.** El fundador aprueba HU y diseño. Seguir el incremento 1 hasta `VERIFIED` y revisar que:
  - existe el commit `-SCENES` con `game/Main.tscn`;
  - Kael no escribe la `.tscn`;
  - Vera recibe la ficha y genera después de Kael;
  - ninguna partida usa miembros inexistentes.
- [ ] **Step 7: Registro.** Registrar el resultado en `docs/HISTORY.md`. Con el OK del fundador, «construye el incremento 2».

---

## Self-Review

| Spec | Task |
|---|---|
| Parte 1: `ClientContract`, ítems, kinds, tope, épicas, validación | 1 |
| Parte 1: escritura con 3 intentos y versiones en Neo4j | 2 |
| Parte 1: parada, aprobar, ajustar, rechazar, doble clic, policy, chat, detalle | 5, 6 y 9 |
| Parte 2: HU desde el contrato, `contractItem`, cobertura, nivel no partido, épicas por Java | 3 y 4 |
| Parte 2: textos por épica (aprobación, detalle, chat, Neo y agentes) | 4 y 9 |
| Parte 3: `scenes`, `levels` por ítem, reglas, textos, revisión conjunta | 7, 8 y 9 |
| Parte 4: esqueletos, gates, ficha, `PlaythroughSheetGate`, QA al final, runner | 10 a 14 |
| Compatibilidad y retome | 3, 4, 5 y 10 a 13 |
| Pruebas en vivo | 16 |

**Placeholders:** ninguno. Donde un test depende de un helper que ya existe en el archivo (`resumeAt`, `missionIn`, `router.route`, `props`), se nombra el helper y qué hacer si se llama distinto.

**Tipos coherentes:**
- `ClientContract.Item(id, kind, name, description)` en las Tasks 1, 3, 7, 8 y 10;
- `LevelDesign(item, scene, parameters, advanceRule)` en las Tasks 7, 8, 9 y 10;
- `gameLevels()` y `gameLevelsOf(int)` en las Tasks 3, 7 y 8;
- `ContractWriter.Written(version, contract)` en las Tasks 2 y 5.
