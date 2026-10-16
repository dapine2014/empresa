# Juegos por niveles y escenas — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que un juego se parta en niveles o escenas con sus HU (Aria, aprobadas por el fundador), que Kael diseñe por nivel y declare sus escenas, que Forjai genere escenas `.tscn` válidas y que Vera reciba y respete un contrato de las escenas antes del sandbox.

**Architecture:** Parte 1 suma `levels` al `RequirementsSpec` y `levelIds` a los incrementos (Java deriva `userStoryIds`). Parte 2 suma `scenes` y `levels` al `GameDesign`. Parte 3 suma, en `DevelopmentTeamStrategy`, un commit de Forjai con los esqueletos `.tscn`, tres gates de generación (`GameSceneScriptsGate`, `GameMainSceneGate` contra el diseño, `ReservedGamePathsGate` con las escenas), un extractor de contrato sin modelo (`SceneContract`), el gate de Vera (`PlaythroughContractGate`), QA siempre al final en juegos y `Scene`/`Do` en el runner fijo. Todo determinista en Java.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Jackson 3 (`tools.jackson.*`; las anotaciones siguen en `com.fasterxml.jackson.annotation`), JUnit 5 + Mockito, React + TS (oxlint), Godot 4.3 .NET (C#) en el sandbox.

**Spec:** `docs/superpowers/specs/2026-10-16-juegos-por-niveles-design.md`

## Global Constraints

- Solo con `experience: GAME` cambian el spec y la validación de Aria; apps y `NONE` no cambian (los `levels` que mande un spec no-GAME se ignoran).
- Ids: niveles `NIVEL-01`, `NIVEL-02`… (en orden, únicos); escenas `ESCENA-01`, `ESCENA-02`…; `ESCENA-01` es la escena principal (`run/main_scene`).
- `kind` de un nivel: `LEVEL` o `SCENE`. Tope de 10 niveles (`MAX_LEVELS = 10`) y 8 escenas (`MAX_SCENES = 8`).
- `script` de una escena: `^[A-Z][A-Za-z0-9]*$`, único; archivo `game/<script>.cs` con `public partial class <script> : Node2D` y escena `game/<script>.tscn`.
- Cada HU en exactamente un nivel; cada nivel en exactamente un incremento; Java calcula `userStoryIds` del incremento como la unión de las HU de sus niveles (en el orden de los niveles).
- Las escenas con niveles exponen `public int Level { get; }` y `public void StartLevel(int level)`.
- Compatibilidad: un spec `GAME` sin `levels` y un diseño sin `scenes` siguen como hoy: sin esqueletos ni gates nuevos. El runner es compatible: `Scene` y `Do` son opcionales.
- Determinismo sobre LLM: la validación, el contrato y los gates se deciden en Java, nunca preguntándole al modelo.
- Nunca `format` + `tools` en una llamada; Jackson 3 (`tools.jackson.*`); sin `@Async`; sin manejo HTTP nuevo.
- Textos al fundador y a los agentes en español.
- Commits terminan con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. No stagear `.superpowers`.
- Backend: `cd app && mvn test` (o `-Dtest=Clase`). Frontend: `cd app/frontend && npm run lint && npm run build`.

## Review Focus

1. **Aria manda HU en `userStoryIds` y también `levelIds`, en desacuerdo** → mandan los niveles: Java pisa `userStoryIds` con la unión de las HU de los niveles (test en Task 1).
2. **Un spec GAME viejo sin `levels` (misión anterior) se vuelve a cargar o se construye su incremento 2** → sin cambios: no hay escenas, ni esqueletos, ni gates nuevos (tests en Task 1, Task 6 y Task 9).
3. **Kael nombra una escena como una clase del runner (`Playthrough`, `PlaythroughRunner`)** → error del validador, porque si no el build choca con `Forjai.Playthroughs` (test en Task 3).
4. **Vera usa un miembro heredado de Godot (`GetNode`, `Position`) o castea `main` a un tipo que no es escena (`(Node2D)main`)** → lo heredado de la lista fija se permite; un cast de `main` a algo que no es escena es un error con las escenas existentes (tests en Task 9).
5. **Comentarios y strings con texto parecido a código en Main.cs o en una partida (`// public int Viejo`, `"main.Pelota"`)** → no entran al contrato ni disparan el gate (tests en Task 8 y Task 9).

---

## File Structure

| Archivo | Responsabilidad |
|---|---|
| `app/src/main/java/com/aicompany/core/agent/model/RequirementsSpec.java` (mod) | `Level`, `levels`, `Increment.levelIds`, `withLevelStories()`, `levelsOf(n)`, schema |
| `app/src/main/java/com/aicompany/core/agent/validation/RequirementsSpecValidator.java` (mod) | Reglas de niveles en GAME |
| `app/src/main/java/com/aicompany/core/agent/validation/RequirementsText.java` (mod) | Niveles en el bloque del incremento y en el resumen (aprobación, detalle, chat) |
| `app/src/main/java/com/aicompany/core/service/RequirementsWriter.java` (mod) | Reglas de niveles en el prompt de Aria; normaliza antes de validar |
| `app/src/main/java/com/aicompany/core/agent/model/InteractionDesign.java` (mod) | `Scene`, `LevelDesign`, `GameDesign.scenes/levels`, schema |
| `app/src/main/java/com/aicompany/core/agent/validation/InteractionDesignValidator.java` (mod) | Reglas de escenas y niveles |
| `app/src/main/java/com/aicompany/core/agent/validation/InteractionDesignText.java` (mod) | Escenas, niveles y reglas de escena en el bloque y el resumen |
| `app/src/main/java/com/aicompany/core/service/InteractionDesignWriter.java` (mod) | Prompt de Kael con escenas y niveles |
| `app/src/main/java/com/aicompany/core/agent/validation/JointReviewValidator.java` (mod) | `NIVEL-NN` y `ESCENA-NN` como referencias |
| `app/src/main/java/com/aicompany/core/service/JointReviewWriter.java` (mod) | Texto de referencias válidas |
| `app/src/main/java/com/aicompany/core/agent/validation/GameScenes.java` (nuevo) | Escenas de los incrementos 1..N, rutas, `Approved` |
| `app/src/main/java/com/aicompany/core/model/GameSceneFiles.java` (nuevo) | Esqueleto `.tscn` de una escena |
| `app/src/main/java/com/aicompany/core/agent/validation/GameSceneScriptsGate.java` (nuevo) | Script de cada escena, clase, `Level`/`StartLevel` |
| `app/src/main/java/com/aicompany/core/agent/validation/GameMainSceneGate.java` (mod) | `run/main_scene` contra ESCENA-01 |
| `app/src/main/java/com/aicompany/core/agent/validation/ReservedGamePathsGate.java` (mod) | Los `.tscn` de Forjai reservados |
| `app/src/main/java/com/aicompany/core/agent/validation/SceneContract.java` (nuevo) | Miembros públicos de una clase de escena, sin modelo |
| `app/src/main/java/com/aicompany/core/agent/validation/PlaythroughContractGate.java` (nuevo) | Partidas de Vera contra el contrato |
| `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (mod) | Commit de esqueletos, gates, contrato a Vera, QA al final |
| `app/src/main/java/com/aicompany/core/model/GameRunnerFiles.java` (mod) | `Playthrough.Scene`, `Do(Action<Node>)` |
| `app/frontend/src/api/types.ts`, `app/frontend/src/pages/MissionDetailPage.tsx` (mod) | Niveles en requisitos; escenas y niveles en el diseño |
| `docs/ARCHITECTURE.md`, `docs/HISTORY.md`, `docs/FALTANTES.md` (mod) | Docs |

---

### Task 1: Niveles en el spec de Aria y su validación

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/model/RequirementsSpec.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/RequirementsSpecValidator.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/RequirementsSpecValidatorTest.java`

**Interfaces:**
- Produces:
  - `record RequirementsSpec.Level(String id, String kind, String name, String description, List<String> userStoryIds)` con `userStoryIdsOrEmpty()`.
  - `RequirementsSpec` gana el componente `List<Level> levels` (último, después de `experience`); siguen los constructores de 6 y 7 argumentos (levels vacío).
  - `record Increment(int number, String goal, List<String> userStoryIds, List<String> levelIds)` con `levelIdsOrEmpty()`; sigue el constructor de 3 argumentos (levelIds vacío).
  - `List<Level> RequirementsSpec.levelsOrEmpty()`, `List<Level> RequirementsSpec.levelsOf(int increment)` (en el orden de `levels`), `RequirementsSpec RequirementsSpec.withLevelStories()`.
  - `RequirementsSpecValidator.MAX_LEVELS = 10`.

- [ ] **Step 1: Write the failing tests**

Agregar a `RequirementsSpecValidatorTest`:

```java
    // Juegos por niveles (spec 2026-10-16 §1): el Pong del fundador en 3 niveles, uno por incremento.
    private static RequirementsSpec pong(List<Level> levels, List<Increment> increments) {
        var stories = List.of(hu("HU-01", "jugador", List.of(), esc("ESC-01")),
                hu("HU-02", "jugador", List.of(), esc("ESC-02")),
                hu("HU-03", "jugador", List.of(), esc("ESC-03")));
        return new RequirementsSpec("Pong", List.of(), List.of(), List.of(new Role("jugador", "juega")), stories,
                increments, "GAME", levels).withLevelStories();
    }

    private static Level level(String id, String kind, String... stories) {
        return new Level(id, kind, "Nivel " + id.substring(6), "distinto", List.of(stories));
    }

    private static Increment inc(int n, String... levels) {
        return new Increment(n, "goal " + n, List.of(), List.of(levels));
    }

    private static final List<Level> PONG_LEVELS = List.of(level("NIVEL-01", "LEVEL", "HU-01"),
            level("NIVEL-02", "LEVEL", "HU-02"), level("NIVEL-03", "LEVEL", "HU-03"));

    @Test
    void aGameSplitInLevelsPassesAndJavaDerivesTheStoriesOfEachIncrement() {
        var spec = pong(PONG_LEVELS, List.of(inc(1, "NIVEL-01"), inc(2, "NIVEL-02"), inc(3, "NIVEL-03")));
        assertEquals(List.of(), RequirementsSpecValidator.validate(spec));
        assertEquals(List.of("HU-02"), spec.incrementsOrEmpty().get(1).userStoryIds());
        assertEquals(List.of("NIVEL-02"), spec.levelsOf(2).stream().map(Level::id).toList());
    }

    // Review Focus 1: si Aria manda userStoryIds y levelIds en desacuerdo, mandan los niveles.
    @Test
    void theLevelsOverrideTheStoriesThatAriaListedInTheIncrement() {
        var spec = pong(PONG_LEVELS, List.of(new Increment(1, "MVP", List.of("HU-03"), List.of("NIVEL-01", "NIVEL-02")),
                inc(2, "NIVEL-03")));
        assertEquals(List.of("HU-01", "HU-02"), spec.incrementsOrEmpty().get(0).userStoryIds());
        assertEquals(List.of(), RequirementsSpecValidator.validate(spec));
    }

    @Test
    void aGameNeedsLevels() {
        var errors = RequirementsSpecValidator.validate(pong(List.of(),
                List.of(new Increment(1, "MVP", List.of("HU-01", "HU-02", "HU-03")))));
        assertTrue(errors.stream().anyMatch(e -> e.contains("levels")), errors.toString());
    }

    @Test
    void levelIdsGoInOrderAndKindIsLevelOrScene() {
        var errors = RequirementsSpecValidator.validate(pong(List.of(level("NIVEL-02", "LEVEL", "HU-01"),
                        level("NIVEL-01", "BOSS", "HU-02", "HU-03")),
                List.of(inc(1, "NIVEL-02", "NIVEL-01"))));
        var all = String.join("\n", errors);
        assertTrue(all.contains("debe tener id NIVEL-01"), all);
        assertTrue(all.contains("BOSS"), all);
    }

    @Test
    void everyStoryIsInExactlyOneLevel() {
        var errors = RequirementsSpecValidator.validate(pong(List.of(level("NIVEL-01", "LEVEL", "HU-01", "HU-02"),
                        level("NIVEL-02", "LEVEL", "HU-02", "HU-09")),
                List.of(inc(1, "NIVEL-01"), inc(2, "NIVEL-02"))));
        var all = String.join("\n", errors);
        assertTrue(all.contains("HU-02 debe estar en exactamente un nivel (está en 2)"), all);
        assertTrue(all.contains("HU-03 debe estar en exactamente un nivel (está en 0)"), all);
        assertTrue(all.contains("NIVEL-02 lista HU-09, que no existe"), all);
    }

    @Test
    void everyLevelIsInExactlyOneIncrementAndIncrementsNeedLevels() {
        var errors = RequirementsSpecValidator.validate(pong(PONG_LEVELS,
                List.of(inc(1, "NIVEL-01", "NIVEL-02"), inc(2, "NIVEL-02", "NIVEL-07"), inc(3))));
        var all = String.join("\n", errors);
        assertTrue(all.contains("NIVEL-02 debe estar en exactamente un incremento (está en 2)"), all);
        assertTrue(all.contains("NIVEL-03 debe estar en exactamente un incremento (está en 0)"), all);
        assertTrue(all.contains("El incremento 2 lista NIVEL-07, que no existe en levels"), all);
        assertTrue(all.contains("El incremento 3 no tiene niveles"), all);
    }

    @Test
    void atMostTenLevels() {
        var levels = new ArrayList<Level>();
        for (int i = 1; i <= 11; i++) {
            levels.add(level("NIVEL-%02d".formatted(i), "LEVEL", i == 1 ? new String[]{"HU-01", "HU-02", "HU-03"} : new String[]{}));
        }
        var errors = RequirementsSpecValidator.validate(pong(levels, List.of(inc(1, levels.stream().map(Level::id)
                .toArray(String[]::new)))));
        assertTrue(errors.stream().anyMatch(e -> e.contains("máximo 10")), errors.toString());
    }

    // Review Focus 2 y compatibilidad: apps y NONE no cambian; los levels que mande un spec no-GAME se ignoran.
    @Test
    void appsIgnoreLevels() {
        var spec = new RequirementsSpec("CRM", List.of(), List.of(), ROLES,
                List.of(hu("HU-01", "vendedor", List.of(), esc("ESC-01"))),
                List.of(new Increment(1, "MVP", List.of("HU-01"))), "APP",
                List.of(level("NIVEL-09", "RARO", "HU-77"))).withLevelStories();
        assertEquals(List.of(), RequirementsSpecValidator.validate(spec));
        assertEquals(List.of("HU-01"), spec.incrementsOrEmpty().get(0).userStoryIds());
    }
```

Nota: el test `experienceIsRequiredAndMustBeKnown` usa `withExperience("GAME")` sin niveles y espera `List.of()`. Con esta tarea un GAME sin niveles es un error: cambiar esa línea a `assertEquals(List.of(), RequirementsSpecValidator.validate(withExperience("NONE")));` y agregar debajo `assertTrue(RequirementsSpecValidator.validate(withExperience("GAME")).stream().anyMatch(e -> e.contains("levels")));`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn test -Dtest=RequirementsSpecValidatorTest`
Expected: FAIL de compilación (`Level`, constructor de 8 argumentos, `withLevelStories`, `levelsOf` no existen).

- [ ] **Step 3: Implement the model**

En `RequirementsSpec.java`:

1. Cambiar la cabecera del record (el componente nuevo va al final):

```java
public record RequirementsSpec(String summary, @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> assumptions, @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> outOfScope, List<Role> roles,
                               List<UserStory> userStories, List<Increment> increments, String experience,
                               List<Level> levels) {

    /** Specs guardados antes de Spec 2026-10-14 Kael §1: sin tipo de experiencia (null). */
    public RequirementsSpec(String summary, List<String> assumptions, List<String> outOfScope, List<Role> roles,
                            List<UserStory> userStories, List<Increment> increments) {
        this(summary, assumptions, outOfScope, roles, userStories, increments, null, null);
    }

    /** Specs anteriores a juegos por niveles (spec 2026-10-16): sin niveles. */
    public RequirementsSpec(String summary, List<String> assumptions, List<String> outOfScope, List<Role> roles,
                            List<UserStory> userStories, List<Increment> increments, String experience) {
        this(summary, assumptions, outOfScope, roles, userStories, increments, experience, null);
    }
```

2. Agregar el record `Level` después de `UserStory`:

```java
    /** Juegos por niveles (spec 2026-10-16 §1): un nivel jugable (LEVEL) o una escena que no es nivel (SCENE). */
    public record Level(String id, String kind, String name, String description,
                        @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> userStoryIds) {

        public Level {
            userStoryIds = orEmpty(userStoryIds);
        }

        public List<String> userStoryIdsOrEmpty() {
            return userStoryIds == null ? List.of() : userStoryIds.stream().filter(Objects::nonNull).toList();
        }
    }
```

3. Reemplazar `Increment`:

```java
    public record Increment(int number, String goal, @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> userStoryIds,
                            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> levelIds) {

        public Increment {
            userStoryIds = orEmpty(userStoryIds);
            levelIds = orEmpty(levelIds);
        }

        public Increment(int number, String goal, List<String> userStoryIds) {
            this(number, goal, userStoryIds, null);
        }

        public List<String> userStoryIdsOrEmpty() {
            return userStoryIds == null ? List.of() : userStoryIds;
        }

        public List<String> levelIdsOrEmpty() {
            return levelIds == null ? List.of() : levelIds.stream().filter(Objects::nonNull).toList();
        }
    }
```

4. En el constructor compacto agregar `levels = orEmpty(levels);`.

5. Agregar después de `incrementsOrEmpty()`:

```java
    public List<Level> levelsOrEmpty() {
        return levels == null ? List.of() : levels.stream().filter(Objects::nonNull).toList();
    }

    /** Los niveles del incremento, en el orden del juego (el de levels). */
    public List<Level> levelsOf(int increment) {
        var ids = incrementsOrEmpty().stream().filter(i -> i.number() == increment)
                .flatMap(i -> i.levelIdsOrEmpty().stream()).collect(java.util.stream.Collectors.toSet());
        return levelsOrEmpty().stream().filter(l -> ids.contains(l.id())).toList();
    }

    /**
     * Juegos por niveles (spec 2026-10-16 §1): en un GAME con niveles, las HU de cada incremento son la unión de las de
     * sus niveles (en el orden de levels). Pisa lo que Aria haya puesto en userStoryIds. Apps y NONE quedan igual.
     */
    public RequirementsSpec withLevelStories() {
        if (experienceOrNull() != Experience.GAME || levelsOrEmpty().isEmpty()) {
            return this;
        }
        var derived = incrementsOrEmpty().stream().map(i -> {
            var stories = levelsOrEmpty().stream().filter(l -> i.levelIdsOrEmpty().contains(l.id()))
                    .flatMap(l -> l.userStoryIdsOrEmpty().stream()).distinct().toList();
            return new Increment(i.number(), i.goal(), stories, i.levelIdsOrEmpty());
        }).toList();
        return new RequirementsSpec(summary, assumptions, outOfScope, roles, userStories, derived, experience, levels);
    }
```

6. En `SCHEMA`: agregar `"levelIds", stringArray()` a las `properties` de los incrementos (sin sumarlo a `required`) y la propiedad de primer nivel (sin sumarla a `required`):

```java
                    "levels", Map.of("type", "array", "items", Map.of("type", "object",
                            "properties", Map.of("id", Map.of("type", "string"),
                                    "kind", Map.of("type", "string", "enum", List.of("LEVEL", "SCENE")),
                                    "name", Map.of("type", "string"), "description", Map.of("type", "string"),
                                    "userStoryIds", stringArray()),
                            "required", List.of("id", "kind", "name", "description", "userStoryIds"))),
```

`Map.of` admite hasta 10 pares: las `properties` de primer nivel pasan de 7 a 8, así que alcanza.

- [ ] **Step 4: Implement the validator**

En `RequirementsSpecValidator`: agregar `public static final int MAX_LEVELS = 10;`, `private static final Pattern NIVEL = Pattern.compile("NIVEL-\\d{2}");` y, antes de `return errors;`, la llamada `validateLevels(spec, storyIds, errors);` con:

```java
    /** Juegos por niveles (spec 2026-10-16 §1): solo en GAME; apps y NONE ignoran levels. */
    private static void validateLevels(RequirementsSpec spec, java.util.Set<String> storyIds, List<String> errors) {
        if (spec.experienceOrNull() != RequirementsSpec.Experience.GAME) {
            return;
        }
        var levels = spec.levelsOrEmpty();
        if (levels.isEmpty()) {
            errors.add("En un juego, levels debe partir el juego en niveles o escenas (NIVEL-01, NIVEL-02…), cada uno "
                    + "con sus HU en userStoryIds; los incrementos listan levelIds.");
            return;
        }
        if (levels.size() > MAX_LEVELS) {
            errors.add("Hay " + levels.size() + " niveles o escenas: máximo " + MAX_LEVELS + ".");
        }
        var levelIds = new HashSet<String>();
        var storyCount = new HashMap<String, Integer>();
        for (int i = 0; i < levels.size(); i++) {
            var level = levels.get(i);
            var expected = "NIVEL-%02d".formatted(i + 1);
            if (!expected.equals(level.id())) {
                errors.add("El nivel " + (i + 1) + " debe tener id " + expected + " (tiene " + level.id()
                        + "): los ids van en el orden del juego.");
            }
            if (level.id() != null && NIVEL.matcher(level.id()).matches() && !levelIds.add(level.id())) {
                errors.add("El nivel " + level.id() + " está repetido.");
            }
            if (!"LEVEL".equals(level.kind()) && !"SCENE".equals(level.kind())) {
                errors.add(level.id() + ": kind debe ser LEVEL (un nivel jugable) o SCENE (menú, game over, créditos); "
                        + "recibido: \"" + level.kind() + "\".");
            }
            if (level.name() == null || level.name().isBlank()) {
                errors.add(level.id() + " necesita name.");
            }
            for (var id : level.userStoryIdsOrEmpty()) {
                if (!storyIds.contains(id)) {
                    errors.add(level.id() + " lista " + id + ", que no existe en userStories.");
                }
                storyCount.merge(id, 1, Integer::sum);
            }
        }
        for (var id : new java.util.TreeSet<>(storyIds)) {
            var n = storyCount.getOrDefault(id, 0);
            if (n != 1) {
                errors.add(id + " debe estar en exactamente un nivel (está en " + n + ").");
            }
        }
        var levelCount = new HashMap<String, Integer>();
        for (var increment : spec.incrementsOrEmpty()) {
            if (increment.levelIdsOrEmpty().isEmpty()) {
                errors.add("El incremento " + increment.number() + " no tiene niveles: lista sus niveles completos en "
                        + "levelIds (p. ej. [\"NIVEL-01\"]).");
            }
            for (var id : increment.levelIdsOrEmpty()) {
                if (levels.stream().noneMatch(l -> id.equals(l.id()))) {
                    errors.add("El incremento " + increment.number() + " lista " + id + ", que no existe en levels.");
                }
                levelCount.merge(id, 1, Integer::sum);
            }
        }
        for (var level : levels) {
            var n = levelCount.getOrDefault(level.id(), 0);
            if (level.id() != null && n != 1) {
                errors.add(level.id() + " debe estar en exactamente un incremento (está en " + n + "): un nivel nunca "
                        + "se parte entre incrementos.");
            }
        }
    }
```

`storyIds` ya existe en `validate` como `HashSet<String>` (los ids válidos de HU). En un GAME con niveles, `withLevelStories()` ya llenó los `userStoryIds`, así que las reglas de siempre por incremento (HU en exactamente un incremento, tope de escenarios, dependencias) siguen valiendo sin cambios.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd app && mvn test -Dtest=RequirementsSpecValidatorTest`
Expected: PASS.

- [ ] **Step 6: Run the full suite (los records cambiaron de forma)**

Run: `cd app && mvn test`
Expected: PASS. Si algún test construye `new RequirementsSpec(... , experience)` con 7 argumentos o `new Increment(n, goal, ids)`, sigue compilando por los constructores conservados. Un test que arme un GAME válido sin niveles y espere `List.of()` del validador se ajusta como `experienceIsRequiredAndMustBeKnown` (Step 1).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/model/RequirementsSpec.java app/src/main/java/com/aicompany/core/agent/validation/RequirementsSpecValidator.java app/src/test/java/com/aicompany/core/agent/validation/RequirementsSpecValidatorTest.java
git commit -m "Juegos por niveles: Aria parte el juego en niveles con sus HU y Java deriva las HU de cada incremento

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Aria escribe los niveles y el fundador los ve al aprobar

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/RequirementsText.java`
- Modify: `app/src/main/java/com/aicompany/core/service/RequirementsWriter.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/RequirementsTextTest.java`
- Test: `app/src/test/java/com/aicompany/core/service/RequirementsWriterTest.java`

**Interfaces:**
- Consumes: `RequirementsSpec.levelsOf(int)`, `withLevelStories()`, `Level` (Task 1).
- Produces: `RequirementsText.summary(spec)` y `RequirementsText.incrementBlock(spec, n)` con los niveles. El resumen ya lo usan el mensaje de `AWAITING_SPEC_APPROVAL` (`MissionExecutor.awaitApproval`), el detalle (`RequirementsView.summary`) y el chat («muéstrame las historias de MISSION-X»), así que no hay que tocarlos.

- [ ] **Step 1: Write the failing tests**

En `RequirementsTextTest` agregar:

```java
    private static final RequirementsSpec PONG = new RequirementsSpec("Pong", List.of(), List.of(),
            List.of(new Role("jugador", "juega")),
            List.of(new UserStory("HU-01", "jugador", "jugar hasta 50 puntos", "ganar", List.of("c"), List.of(),
                            List.of(new Scenario("ESC-01", "punto", List.of("d"), List.of("c"), List.of("e")))),
                    new UserStory("HU-02", "jugador", "que la pelota acelere", "un reto", List.of("c"), List.of(),
                            List.of(new Scenario("ESC-02", "rápida", List.of("d"), List.of("c"), List.of("e"))))),
            List.of(new Increment(1, "Nivel 1 jugable", List.of(), List.of("NIVEL-01")),
                    new Increment(2, "Más velocidad", List.of(), List.of("NIVEL-02"))), "GAME",
            List.of(new Level("NIVEL-01", "LEVEL", "Nivel 1", "pelota normal hasta 50 puntos", List.of("HU-01")),
                    new Level("NIVEL-02", "LEVEL", "Nivel 2", "la pelota aumenta de velocidad", List.of("HU-02"))))
            .withLevelStories();

    // Juegos por niveles (spec 2026-10-16 §1): el fundador ve la división al aprobar (mensaje, detalle y chat).
    @Test
    void theSummaryShowsTheLevelsOfEachIncrement() {
        var summary = RequirementsText.summary(PONG);
        assertTrue(summary.contains("Incremento 1 (Nivel 1 jugable): NIVEL-01 Nivel 1 — HU-01 jugar hasta 50 puntos"), summary);
        assertTrue(summary.contains("Incremento 2 (Más velocidad): NIVEL-02 Nivel 2 — HU-02 que la pelota acelere"), summary);
    }

    @Test
    void theIncrementBlockListsItsLevels() {
        var block = RequirementsText.incrementBlock(PONG, 2);
        assertTrue(block.contains("Niveles o escenas de este incremento:\n- NIVEL-02 (LEVEL) Nivel 2: la pelota aumenta "
                + "de velocidad — HU-02"), block);
        assertFalse(block.contains("NIVEL-01 (LEVEL)"), block);
    }

    @Test
    void withoutLevelsTheTextsDoNotChange() {
        assertFalse(RequirementsText.summary(SPEC).contains("NIVEL-"), RequirementsText.summary(SPEC));
        assertFalse(RequirementsText.incrementBlock(SPEC, 1).contains("Niveles o escenas"));
    }
```

En `RequirementsWriterTest`, siguiendo el patrón de los tests existentes del archivo (mock de `ceo.writeRequirements` que devuelve un spec y captura del prompt), agregar dos tests:

```java
    // Juegos por niveles: el prompt pide niveles y levelIds; lo guardado ya tiene las HU derivadas.
    @Test
    void aGameIsSavedWithTheStoriesDerivedFromItsLevels() {
        var game = new RequirementsSpec("Pong", List.of(), List.of(), List.of(new RequirementsSpec.Role("jugador", "x")),
                List.of(new RequirementsSpec.UserStory("HU-01", "jugador", "jugar", "ganar", List.of("c"), List.of(),
                        List.of(new RequirementsSpec.Scenario("ESC-01", "t", List.of("d"), List.of("c"), List.of("e"))))),
                List.of(new RequirementsSpec.Increment(1, "MVP", List.of(), List.of("NIVEL-01"))), "GAME",
                List.of(new RequirementsSpec.Level("NIVEL-01", "LEVEL", "Nivel 1", "normal", List.of("HU-01"))));
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceo.writeRequirements(eq("product-owner"), prompt.capture(), any(), any())).thenReturn(game);

        var written = writer.write("M-1", "un pong", Optional.empty(), null);

        assertEquals(List.of("HU-01"), written.spec().incrementsOrEmpty().get(0).userStoryIds());
        verify(requirements).saveDraft(eq("M-1"), argThat(json -> json.contains("\"userStoryIds\":[\"HU-01\"]")), any());
        assertTrue(prompt.getValue().contains("levels"), prompt.getValue());
        assertTrue(prompt.getValue().contains("un nivel o escena por incremento"), prompt.getValue());
    }
```

Adaptar los nombres de los mocks (`ceo`, `requirements`, `writer`) a los del archivo: leer el `@BeforeEach` o los campos de `RequirementsWriterTest` antes de escribirlo. Si `saveDraft` devuelve `int`, stubbearlo con `when(requirements.saveDraft(anyString(), anyString(), any())).thenReturn(1)`, como el resto del archivo.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn test -Dtest='RequirementsTextTest,RequirementsWriterTest'`
Expected: FAIL (el resumen no muestra niveles; el prompt no los pide; el spec guardado no tiene HU derivadas).

- [ ] **Step 3: Implement the texts**

En `RequirementsText.incrementBlock`, armar un bloque de niveles e insertarlo antes de «Construye SOLO estas historias»:

```java
        var levels = spec.levelsOf(increment);
        var levelBlock = levels.isEmpty() ? "" : "Niveles o escenas de este incremento:\n" + levels.stream()
                .map(l -> "- " + l.id() + " (" + l.kind() + ") " + l.name() + ": " + l.description() + " — "
                        + String.join(", ", l.userStoryIdsOrEmpty()))
                .collect(Collectors.joining("\n")) + "\n";
```

y reemplazar el `return` por esta forma exacta (`levelBlock` termina en `\n` o es `""`, así que sin niveles el texto queda igual que hoy):

```java
        return """
                REQUISITOS APROBADOS POR EL FUNDADOR (Aria, Product Owner): INCREMENTO %d de %d — %s
                %sRoles: %s
                %sConstruye SOLO estas historias, con estos escenarios (cada escenario debe poder probarse):
                %s
                NO construyas en este incremento: %s
                Fuera de alcance del producto: %s
                """.formatted(increment, total, goal, experience,
                spec.rolesOrEmpty().stream().map(RequirementsSpec.Role::name).collect(Collectors.joining(", ")),
                levelBlock, stories, later.isEmpty() ? "(nada; es el último incremento)" : later,
                spec.outOfScopeOrEmpty().isEmpty() ? "(nada declarado)" : String.join("; ", spec.outOfScopeOrEmpty()));
```

En `RequirementsText.summary`, reemplazar el armado de `increments` por:

```java
        var increments = spec.incrementsOrEmpty().stream().map(i -> {
                    var levels = spec.levelsOf(i.number());
                    var body = levels.isEmpty()
                            ? spec.storiesOf(i.number()).stream().map(s -> s.id() + " " + s.iWant())
                                    .collect(Collectors.joining(", "))
                            : levels.stream().map(l -> l.id() + " " + l.name() + " — " + l.userStoryIdsOrEmpty().stream()
                                    .map(id -> spec.userStoriesOrEmpty().stream().filter(s -> id.equals(s.id()))
                                            .findFirst().map(s -> s.id() + " " + s.iWant()).orElse(id))
                                    .collect(Collectors.joining(", "))).collect(Collectors.joining(" · "));
                    return "Incremento " + i.number() + " (" + i.goal() + "): " + body;
                })
                .collect(Collectors.joining(". "));
```

- [ ] **Step 4: Implement the writer**

En `RequirementsWriter.write`, justo después de `spec = ceo.writeRequirements(...)` (dentro del `try`) agregar `spec = spec == null ? null : spec.withLevelStories();`. Así lo validado y lo guardado tienen las HU derivadas.

En `prompt(...)`, agregar a REGLAS (después de la línea de `increments`):

```
                - Si experience es GAME: levels parte el juego en niveles o escenas, NIVEL-01, NIVEL-02… en el orden
                  del juego; kind LEVEL (un nivel jugable) o SCENE (menú, game over, créditos); description dice qué
                  tiene de distinto (p. ej. "la pelota aumenta de velocidad"); cada HU en exactamente un nivel (máximo
                  10 niveles). Cada incremento lista sus niveles completos en levelIds (Java calcula sus userStoryIds;
                  déjalos vacíos): un nivel o escena por incremento cuando se puede jugar o mostrar solo; si no,
                  agrúpalos y explica por qué en el goal. El incremento 1 debe ser jugable de punta a punta. Un nivel
                  nunca se parte entre incrementos. Apps y NONE no llevan levels.
```

y en el JSON de ejemplo agregar después de `"increments": [...]`:

```
                 "levels": [{"id": "NIVEL-01", "kind": "LEVEL", "name": "...", "description": "...", "userStoryIds": ["HU-01"]}]
```

con la línea de `increments` como `"increments": [{"number": 1, "goal": "...", "userStoryIds": ["HU-01", "HU-02"], "levelIds": ["NIVEL-01"]}],` (la coma va al final para seguir con `levels`).

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd app && mvn test -Dtest='RequirementsTextTest,RequirementsWriterTest,MissionExecutor*Test,ChatIntentRouter*Test'`
Expected: PASS (los tests de chat y del executor no deberían cambiar: los specs sin niveles producen el mismo texto).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/RequirementsText.java app/src/main/java/com/aicompany/core/service/RequirementsWriter.java app/src/test/java/com/aicompany/core/agent/validation/RequirementsTextTest.java app/src/test/java/com/aicompany/core/service/RequirementsWriterTest.java
git commit -m "Juegos por niveles: Aria propone los niveles por incremento y el fundador los ve al aprobar

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Kael diseña las escenas y cada nivel, con su validación

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/model/InteractionDesign.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/InteractionDesignValidator.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/InteractionDesignValidatorTest.java`

**Interfaces:**
- Consumes: `RequirementsSpec.levelsOrEmpty()`, `Level` (Task 1).
- Produces:
  - `record InteractionDesign.Scene(String id, String name, String script, String purpose)`.
  - `record InteractionDesign.LevelDesign(String level, String scene, List<Property> parameters, String advanceRule)`.
  - `GameDesign` gana los componentes `List<Scene> scenes, List<LevelDesign> levels` (al final); sigue un constructor con los 10 argumentos de hoy.
  - `InteractionDesignValidator.MAX_SCENES = 8`, `InteractionDesignValidator.SCRIPT` (`Pattern` `[A-Z][A-Za-z0-9]*`), `InteractionDesignValidator.RESERVED_SCRIPTS` (`Set.of("Playthrough", "PlaythroughRunner", "Step", "StepKind")`).

- [ ] **Step 1: Write the failing tests**

En `InteractionDesignValidatorTest` agregar (usar los constructores de `GameDesign` que ya use el archivo para armar un diseño de juego válido; si no hay un helper, este):

```java
    static final RequirementsSpec PONG = new RequirementsSpec("Pong", List.of(), List.of(),
            List.of(new RequirementsSpec.Role("jugador", "d")),
            List.of(new RequirementsSpec.UserStory("HU-01", "jugador", "q", "p", List.of("c"), List.of(),
                            List.of(new RequirementsSpec.Scenario("ESC-01", "a", List.of("d"), List.of("c"), List.of("e")))),
                    new RequirementsSpec.UserStory("HU-02", "jugador", "q", "p", List.of("c"), List.of(),
                            List.of(new RequirementsSpec.Scenario("ESC-02", "b", List.of("d"), List.of("c"), List.of("e"))))),
            List.of(new RequirementsSpec.Increment(1, "N1", List.of(), List.of("NIVEL-01")),
                    new RequirementsSpec.Increment(2, "N2", List.of(), List.of("NIVEL-02"))), "GAME",
            List.of(new RequirementsSpec.Level("NIVEL-01", "LEVEL", "Nivel 1", "normal", List.of("HU-01")),
                    new RequirementsSpec.Level("NIVEL-02", "LEVEL", "Nivel 2", "rápida", List.of("HU-02"))))
            .withLevelStories();

    static GameDesign pongDesign(List<Scene> scenes, List<LevelDesign> levels) {
        return new GameDesign(List.of("PLAYING"), List.of(), List.of(), List.of(new Control("move_up", "Up", "sube")),
                List.of(new Rule("REGLA-01", "move_up apretado", "la paleta sube"),
                        new Rule("REGLA-02", "puntaje >= 50", "pasa al nivel 2")),
                List.of("REGLA-02"), List.of(), List.of(),
                List.of(new ScenarioRule("ESC-01", "REGLA-01"), new ScenarioRule("ESC-02", "REGLA-01")),
                List.of("hipótesis"), scenes, levels);
    }

    static final Scene MAIN = new Scene("ESCENA-01", "Cancha", "Main", "donde se juega");
    static final LevelDesign N1 = new LevelDesign("NIVEL-01", "ESCENA-01",
            List.of(new Property("velocidad_pelota", 200.0)), "REGLA-02");
    static final LevelDesign N2 = new LevelDesign("NIVEL-02", "ESCENA-01",
            List.of(new Property("velocidad_pelota", 300.0)), "");

    // Juegos por niveles (spec 2026-10-16 §2).
    @Test void aDesignWithScenesAndOneEntryPerLevelPasses() {
        assertEquals(List.of(), InteractionDesignValidator.validateGame(pongDesign(List.of(MAIN), List.of(N1, N2)), PONG));
    }

    @Test void everyLevelOfAriaHasExactlyOneDesignAndNoExtras() {
        var errors = InteractionDesignValidator.validateGame(pongDesign(List.of(MAIN), List.of(N1,
                new LevelDesign("NIVEL-07", "ESCENA-01", List.of(), ""))), PONG);
        var all = String.join("\n", errors);
        assertTrue(all.contains("NIVEL-02 no tiene diseño"), all);
        assertTrue(all.contains("NIVEL-07 no existe en los niveles de Aria"), all);
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
                List.of(new LevelDesign("NIVEL-01", "ESCENA-01", List.of(), "REGLA-02"),
                        new LevelDesign("NIVEL-02", "ESCENA-01", List.of(), ""))), PONG);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Playthrough") && e.contains("runner")), errors.toString());
    }

    @Test void theMainSceneIsRequiredAndLevelsUseExistingScenes() {
        var errors = InteractionDesignValidator.validateGame(pongDesign(List.of(new Scene("ESCENA-02", "Menu", "Menu", "x")),
                List.of(new LevelDesign("NIVEL-01", "ESCENA-05", List.of(), "REGLA-02"),
                        new LevelDesign("NIVEL-02", "ESCENA-02", List.of(), ""))), PONG);
        var all = String.join("\n", errors);
        assertTrue(all.contains("ESCENA-01"), all);
        assertTrue(all.contains("NIVEL-01 usa la escena ESCENA-05, que no está en scenes"), all);
    }

    @Test void theAdvanceRuleIsRequiredExceptInTheLastLevelAndMustExist() {
        var errors = InteractionDesignValidator.validateGame(pongDesign(List.of(MAIN),
                List.of(new LevelDesign("NIVEL-01", "ESCENA-01", List.of(), ""),
                        new LevelDesign("NIVEL-02", "ESCENA-01", List.of(), "REGLA-09"))), PONG);
        var all = String.join("\n", errors);
        assertTrue(all.contains("NIVEL-01 necesita advanceRule"), all);
        assertTrue(all.contains("NIVEL-02: advanceRule REGLA-09 no está en rules"), all);
    }

    @Test void levelParametersAreNumbersAndThereAreAtMostEightScenes() {
        var many = new java.util.ArrayList<Scene>();
        for (int i = 1; i <= 9; i++) {
            many.add(new Scene("ESCENA-%02d".formatted(i), "E" + i, "Escena" + i, "x"));
        }
        var errors = InteractionDesignValidator.validateGame(pongDesign(many,
                List.of(new LevelDesign("NIVEL-01", "ESCENA-01", List.of(new Property("pelotas", null)), "REGLA-02"), N2)),
                PONG);
        var all = String.join("\n", errors);
        assertTrue(all.contains("máximo 8"), all);
        assertTrue(all.contains("NIVEL-01: el parámetro \"pelotas\" necesita un número"), all);
    }

    // Compatibilidad: un spec de juego sin niveles no exige escenas.
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
    /** Juegos por niveles (spec 2026-10-16 §2): una escena Godot; script es la clase C# y el nombre de sus archivos. */
    public record Scene(String id, String name, String script, String purpose) { }

    /** Por cada nivel de Aria: dónde ocurre, sus parámetros y la regla que pasa al siguiente (vacía en el último). */
    public record LevelDesign(String level, String scene, List<Property> parameters, String advanceRule) {
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
                        Map.entry("levels", array(Map.of("type", "object", "properties", Map.of("level", str(),
                                "scene", str(), "parameters", array(property()), "advanceRule", str()),
                                "required", List.of("level", "scene", "parameters", "advanceRule"))))
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
    /** Juegos por niveles (spec 2026-10-16 §2): solo si Aria partió el juego en niveles. */
    private static void validateLevels(GameDesign design, RequirementsSpec spec, Set<String> rules, List<String> errors) {
        var ariaLevels = nn(spec.levelsOrEmpty()).stream().map(RequirementsSpec.Level::id).filter(Objects::nonNull).toList();
        if (ariaLevels.isEmpty()) {
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
        var designed = new HashMap<String, Integer>();
        for (var l : nn(design.levels())) {
            designed.merge(String.valueOf(l.level()), 1, Integer::sum);
            if (!ariaLevels.contains(l.level())) {
                errors.add("Hay un diseño para " + l.level() + ", que no existe en los niveles de Aria " + ariaLevels + ".");
                continue;
            }
            if (l.scene() == null || !sceneIds.contains(l.scene())) {
                errors.add(l.level() + " usa la escena " + l.scene() + ", que no está en scenes.");
            }
            nn(l.parameters()).stream().filter(p -> p.value() == null).forEach(p -> errors.add(l.level()
                    + ": el parámetro \"" + p.name() + "\" necesita un número en value (p. ej. velocidad_pelota=200)."));
            var last = l.level().equals(ariaLevels.get(ariaLevels.size() - 1));
            var rule = l.advanceRule() == null ? "" : l.advanceRule().strip();
            if (rule.isEmpty() && !last) {
                errors.add(l.level() + " necesita advanceRule: la REGLA-NN que pasa al nivel siguiente.");
            } else if (!rule.isEmpty() && !rules.contains(rule)) {
                errors.add(l.level() + ": advanceRule " + rule + " no está en rules.");
            }
        }
        for (var id : ariaLevels) {
            var n = designed.getOrDefault(id, 0);
            if (n == 0) {
                errors.add(id + " no tiene diseño: agrega uno en levels con level \"" + id + "\".");
            } else if (n > 1) {
                errors.add(id + " tiene " + n + " diseños: deja uno.");
            }
        }
    }
```

(`HashMap` ya entra por `java.util.*`.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd app && mvn test -Dtest=InteractionDesignValidatorTest` y después `cd app && mvn test`
Expected: PASS en ambos.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/model/InteractionDesign.java app/src/main/java/com/aicompany/core/agent/validation/InteractionDesignValidator.java app/src/test/java/com/aicompany/core/agent/validation/InteractionDesignValidatorTest.java
git commit -m "Juegos por niveles: Kael declara las escenas y diseña cada nivel de Aria

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Escenas y niveles en los textos de Kael, Neo y Vera, y en la revisión conjunta

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/InteractionDesignText.java`
- Modify: `app/src/main/java/com/aicompany/core/service/InteractionDesignWriter.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/JointReviewValidator.java`
- Modify: `app/src/main/java/com/aicompany/core/service/JointReviewWriter.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/GameScenes.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/InteractionDesignTextTest.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/JointReviewValidatorTest.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/GameScenesTest.java` (nuevo)

**Interfaces:**
- Consumes: `Scene`, `LevelDesign`, `GameDesign.scenes()/levels()` (Task 3); `RequirementsSpec.levelsOf` (Task 1).
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

import com.aicompany.core.agent.model.InteractionDesign.*;
import com.aicompany.core.agent.model.RequirementsSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Juegos por niveles (spec 2026-10-16 §3.1): qué escenas necesita cada incremento. */
class GameScenesTest {

    static final RequirementsSpec SPEC = new RequirementsSpec("Pong", List.of(), List.of(),
            List.of(new RequirementsSpec.Role("jugador", "d")),
            List.of(new RequirementsSpec.UserStory("HU-01", "jugador", "q", "p", List.of("c"), List.of(), List.of()),
                    new RequirementsSpec.UserStory("HU-02", "jugador", "q", "p", List.of("c"), List.of(), List.of())),
            List.of(new RequirementsSpec.Increment(1, "N1", List.of(), List.of("NIVEL-01")),
                    new RequirementsSpec.Increment(2, "Fin", List.of(), List.of("NIVEL-02"))), "GAME",
            List.of(new RequirementsSpec.Level("NIVEL-01", "LEVEL", "Nivel 1", "x", List.of("HU-01")),
                    new RequirementsSpec.Level("NIVEL-02", "SCENE", "Game over", "x", List.of("HU-02"))))
            .withLevelStories();

    static final GameDesign GAME = new GameDesign(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(),
            List.of(new Scene("ESCENA-01", "Cancha", "Main", "x"), new Scene("ESCENA-02", "Fin", "GameOver", "x"),
                    new Scene("ESCENA-03", "Créditos", "Credits", "x")),
            List.of(new LevelDesign("NIVEL-01", "ESCENA-01", List.of(), "REGLA-01"),
                    new LevelDesign("NIVEL-02", "ESCENA-02", List.of(), "")));

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

`hasLevels(GAME, "ESCENA-02")` es `true` (NIVEL-02 es una escena SCENE de Aria, pero ocurre en ESCENA-02): **ruling** — toda escena donde ocurre un nivel o escena de Aria expone `Level`/`StartLevel`; así el runner puede arrancar cualquier nivel o escena directo.

En `InteractionDesignTextTest`, con un diseño y un spec como los de `GameScenesTest` (copiarlos al archivo como constantes privadas, porque los de otra clase de test son package-private y sirven, pero conviene que el test se lea solo):

```java
    // Juegos por niveles (spec 2026-10-16 §2-§3): Kael, Iris y Vera ven las escenas y los niveles del incremento.
    @Test void theGameBlockListsTheScenesAndTheLevelsOfTheIncrement() {
        var design = new InteractionDesign("GAME", null, GameScenesTest.GAME);
        var block = InteractionDesignText.incrementBlock(design, GameScenesTest.SPEC, 1);
        assertTrue(block.contains("Escenas (Forjai ya creó cada game/<script>.tscn con el script en el nodo raíz; no las escribas):"), block);
        assertTrue(block.contains("- ESCENA-01 Cancha → game/Main.tscn + game/Main.cs (clase Main, escena principal)"), block);
        assertFalse(block.contains("ESCENA-02 Fin"), block);
        assertTrue(block.contains("- NIVEL-01 en ESCENA-01: {} — pasa al siguiente con REGLA-01"), block);
        assertTrue(block.contains("public partial class <script> : Node2D"), block);
        assertTrue(block.contains("public int Level { get; }"), block);
        assertTrue(block.contains("run/main_scene=\"res://Main.tscn\""), block);
    }

    @Test void theSummaryShowsScenesAndLevels() {
        var summary = InteractionDesignText.summary(new InteractionDesign("GAME", null, GameScenesTest.GAME));
        assertTrue(summary.contains("Escenas: ESCENA-01 Cancha (Main), ESCENA-02 Fin (GameOver), ESCENA-03 Créditos (Credits)"), summary);
        assertTrue(summary.contains("Niveles: NIVEL-01 en ESCENA-01 {} → REGLA-01; NIVEL-02 en ESCENA-02 {}"), summary);
    }
```

En `JointReviewValidatorTest`:

```java
    // Juegos por niveles: NIVEL-NN es referencia de requisitos; ESCENA-NN y NIVEL-NN del diseño, de diseño.
    @Test void levelsAndScenesAreValidReferences() {
        var design = new InteractionDesign("GAME", null, GameScenesTest.GAME);
        assertTrue(JointReviewValidator.requirementIds(GameScenesTest.SPEC).contains("NIVEL-02"));
        assertTrue(JointReviewValidator.designIds(design).containsAll(List.of("ESCENA-01", "NIVEL-01")));
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

/** Juegos por niveles (spec 2026-10-16 §3): las escenas del diseño de Kael que necesita cada incremento. */
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
            spec.levelsOf(n).forEach(l -> levels.add(l.id()));
        }
        var used = new HashSet<String>();
        used.add(MAIN);
        nn(game.levels()).stream().filter(l -> levels.contains(l.level())).forEach(l -> used.add(l.scene()));
        return nn(game.scenes()).stream().filter(s -> used.contains(s.id())).toList();
    }

    public static Optional<InteractionDesign.Scene> main(InteractionDesign.GameDesign game) {
        return nn(game.scenes()).stream().filter(s -> MAIN.equals(s.id())).findFirst();
    }

    /** Las escenas donde ocurre un nivel o escena de Aria exponen Level y StartLevel. */
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
            var levels = spec.levelsOf(increment).stream().map(RequirementsSpec.Level::id).toList();
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
                    nn(game.levels()).stream().filter(l -> levels.contains(l.level())).map(InteractionDesignText::level)
                            .collect(Collectors.joining("\n")),
                    main);
        }
```

con el helper:

```java
    private static String level(InteractionDesign.LevelDesign l) {
        var rule = l.advanceRule() == null || l.advanceRule().isBlank() ? "" : " — pasa al siguiente con " + l.advanceRule().strip();
        return "- " + l.level() + " en " + l.scene() + ": " + nn(l.parameters()).stream()
                .map(p -> p.name() + "=" + num(p.value())).collect(Collectors.joining(", ", "{", "}")) + rule;
    }
```

y concatenar `sceneBlock` al final del text block del juego (`return """…""".formatted(...) + sceneBlock;`). En el text block del juego, la última línea («Expón en el script de la escena principal…») queda igual.

En `summary`, en la rama de juego, agregar al final (antes del punto final, manteniendo el formato):

```java
        var scenes = nn(g.scenes()).isEmpty() ? "" : " Escenas: " + nn(g.scenes()).stream()
                .map(s -> s.id() + " " + s.name() + " (" + s.script() + ")").collect(Collectors.joining(", "))
                + ". Niveles: " + nn(g.levels()).stream().map(l -> l.level() + " en " + l.scene() + " "
                        + nn(l.parameters()).stream().map(p -> p.name() + "=" + num(p.value()))
                        .collect(Collectors.joining(", ", "{", "}"))
                        + (l.advanceRule() == null || l.advanceRule().isBlank() ? "" : " → " + l.advanceRule().strip()))
                .collect(Collectors.joining("; ")) + ".";
```

y sumar `+ scenes` al `return` del juego.

- [ ] **Step 5: Implement Kael's prompt**

En `InteractionDesignWriter.prompt`, en el texto de juego, agregar después de la línea de `balanceHypotheses`:

```
                - Si las HU traen niveles o escenas (NIVEL-NN): scenes ESCENA-01, ESCENA-02… (máximo 8) con name,
                  script (nombre de la clase C# y de sus archivos: empieza en mayúscula, sin espacios, p. ej. Main, Menu;
                  nunca Playthrough ni PlaythroughRunner) y purpose; ESCENA-01 es la escena principal. levels: uno por
                  cada NIVEL-NN de Aria con la escena donde ocurre (scene), sus parameters numéricos (p. ej.
                  velocidad_pelota=200, pelotas=1, puntaje_meta=50) y advanceRule (la REGLA-NN que pasa al siguiente
                  nivel; vacía solo en el último).
```

y en el JSON de ejemplo agregar antes de la `}` final: `,"scenes":[{"id":"ESCENA-01","name":"...","script":"Main","purpose":"..."}],"levels":[{"level":"NIVEL-01","scene":"ESCENA-01","parameters":[{"name":"...","value":1}],"advanceRule":"REGLA-03"}]`.

Y como las HU de Aria ya llegan por `RequirementsText.incrementBlock` (que con Task 2 lista los niveles), Kael los ve sin más cambios.

- [ ] **Step 6: Implement the joint review references**

En `JointReviewValidator.requirementIds` agregar `spec.levelsOrEmpty().forEach(l -> { if (l.id() != null) ids.add(l.id()); });`. En `designIds`, dentro de `if (design.game() != null)`, agregar:

```java
            design.game().scenes().forEach(s -> {
                if (s.id() != null) ids.add(s.id());
            });
            design.game().levels().forEach(l -> {
                if (l.level() != null) ids.add(l.level());
            });
```

Cambiar el mensaje «requirementRefs necesita al menos una HU-NN o ESC-NN.» por «requirementRefs necesita al menos una HU-NN, ESC-NN o NIVEL-NN.». En `JointReviewWriter` (línea ~55), cambiar `requirementRefs (HU-NN/ESC-NN)` por `requirementRefs (HU-NN/ESC-NN/NIVEL-NN)` y `REGLA-NN o el id de una entidad en juegos` por `REGLA-NN, ESCENA-NN, NIVEL-NN o el id de una entidad en juegos`. Si un test de `JointReviewValidatorTest` compara el mensaje viejo literalmente, actualizarlo.

- [ ] **Step 7: Run tests to verify they pass**

Run: `cd app && mvn test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/GameScenes.java app/src/main/java/com/aicompany/core/agent/validation/InteractionDesignText.java app/src/main/java/com/aicompany/core/service/InteractionDesignWriter.java app/src/main/java/com/aicompany/core/agent/validation/JointReviewValidator.java app/src/main/java/com/aicompany/core/service/JointReviewWriter.java app/src/test/java/com/aicompany/core/agent/validation/GameScenesTest.java app/src/test/java/com/aicompany/core/agent/validation/InteractionDesignTextTest.java app/src/test/java/com/aicompany/core/agent/validation/JointReviewValidatorTest.java
git commit -m "Juegos por niveles: escenas y niveles en los prompts, el resumen y la revisión conjunta

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Command Center: niveles en los requisitos y escenas en el diseño

**Files:**
- Modify: `app/frontend/src/api/types.ts`
- Modify: `app/frontend/src/pages/MissionDetailPage.tsx`

**Interfaces:**
- Consumes: JSON de `RequirementsSpec` (`levels`, `increments[].levelIds`) y de `GameDesign` (`scenes`, `levels`) de Tasks 1 y 3.

- [ ] **Step 1: Types**

En `types.ts`:

```ts
export interface RequirementsLevel {
  id: string
  kind: 'LEVEL' | 'SCENE'
  name: string
  description: string
  userStoryIds: string[]
}
```

En `RequirementsSpec`: `increments: { number: number; goal: string; userStoryIds: string[]; levelIds?: string[] }[]` y `levels?: RequirementsLevel[]`. En `GameDesign`: `scenes?: { id: string; name: string; script: string; purpose: string }[]` y `levels?: { level: string; scene: string; parameters: DesignProperty[]; advanceRule: string }[]`.

- [ ] **Step 2: Requirements**

En `MissionRequirements` (`MissionDetailPage.tsx`, bloque `spec.increments.map`), cuando el incremento tiene niveles, mostrar cada nivel como subtítulo con sus HU debajo; sin niveles, igual que hoy. Extraer el render de una HU a una función local para no duplicarlo:

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
        const levels = (spec.levels ?? []).filter((l) => (inc.levelIds ?? []).includes(l.id))
        return (
          <div key={inc.number}>
            <h3>
              Incremento {inc.number}: {inc.goal}
            </h3>
            {levels.length === 0
              ? spec.userStories.filter((s) => inc.userStoryIds.includes(s.id)).map(story)
              : levels.map((l) => (
                  <div key={l.id}>
                    <h4>
                      {l.id} {l.name} ({l.kind === 'SCENE' ? 'escena' : 'nivel'}): {l.description}
                    </h4>
                    {spec.userStories.filter((s) => l.userStoryIds.includes(s.id)).map(story)}
                  </div>
                ))}
          </div>
        )
      })}
```

`RequirementsStory` ya existe en `types.ts`; importarlo si el archivo no lo importa.

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
                  <li key={l.level}>
                    {l.level} en {l.scene}: {props(l.parameters)}
                    {l.advanceRule ? ` → ${l.advanceRule}` : ''}
                  </li>
                ))}
              </ul>
            </>
          )}
```

- [ ] **Step 4: Lint and build**

Run: `cd app/frontend && npm run lint && npm run build`
Expected: sin errores.

- [ ] **Step 5: Commit**

```bash
git add app/frontend/src/api/types.ts app/frontend/src/pages/MissionDetailPage.tsx
git commit -m "Command Center: niveles por incremento en los requisitos; escenas y niveles en el diseño del juego

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Forjai commitea los esqueletos de escena

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/GameSceneFiles.java`
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java`
- Test: `app/src/test/java/com/aicompany/core/model/GameSceneFilesTest.java` (nuevo)
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java`

**Interfaces:**
- Consumes: `GameScenes.Approved`, `GameScenes.upTo`, `GameScenes.scenePath` (Task 4).
- Produces:
  - `GameSceneFiles.skeleton(String script)` → `GeneratedFile` en `game/<script>.tscn`;
  - en `DevelopmentTeamStrategy`: `Optional<GameScenes.Approved> approvedGameScenes(String missionId)` (package-private) y `List<String> sceneSkeletonPaths(TeamMissionContext context)` (package-private; las rutas `.tscn` con el prefijo del componente, vacías si no hay escenas). Las usan Task 7 y Task 9.

**Ruling:** el spec dice «el scaffold escribe» y lista `ProjectScaffold` en las pruebas. `ProjectScaffold` hoy no conoce el diseño (solo perfil y contextos) y lo llaman también los cálculos de paquetes. Los esqueletos van en una clase propia (`GameSceneFiles`) y en un commit propio de Forjai (`<missionId>-SCENES`, `-SCENES-R<n>` en rondas) justo después del scaffold, con la misma regla de no pisar lo que existe. Costo si está mal: mover una llamada.

- [ ] **Step 1: Write the failing tests**

`GameSceneFilesTest.java`:

```java
package com.aicompany.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Juegos por niveles (spec 2026-10-16 §3.1): la escena la escribe Forjai, con el script en el nodo raíz. */
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
            List.of(new RequirementsSpec.UserStory("HU-01", "jugador", "q", "p", List.of("c"), List.of(), List.of()),
                    new RequirementsSpec.UserStory("HU-02", "jugador", "q", "p", List.of("c"), List.of(), List.of())),
            List.of(new RequirementsSpec.Increment(1, "N1", List.of(), List.of("NIVEL-01")),
                    new RequirementsSpec.Increment(2, "Fin", List.of(), List.of("NIVEL-02"))), "GAME",
            List.of(new RequirementsSpec.Level("NIVEL-01", "LEVEL", "Nivel 1", "x", List.of("HU-01")),
                    new RequirementsSpec.Level("NIVEL-02", "SCENE", "Game over", "x", List.of("HU-02"))))
            .withLevelStories();

    private static final InteractionDesign.GameDesign LEVELED_GAME = new InteractionDesign.GameDesign(List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(new InteractionDesign.Scene("ESCENA-01", "Cancha", "Main", "x"),
                    new InteractionDesign.Scene("ESCENA-02", "Fin", "GameOver", "x")),
            List.of(new InteractionDesign.LevelDesign("NIVEL-01", "ESCENA-01", List.of(), "REGLA-01"),
                    new InteractionDesign.LevelDesign("NIVEL-02", "ESCENA-02", List.of(), "")));

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

    // Juegos por niveles (spec 2026-10-16 §3.1): Forjai commitea la escena de cada nivel del incremento, antes de Kael.
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

Imports nuevos del test si faltan: `com.aicompany.core.agent.model.InteractionDesign`, `com.aicompany.core.agent.model.RequirementsSpec`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn test -Dtest='GameSceneFilesTest,DevelopmentTeamStrategyTest'`
Expected: FAIL (`GameSceneFiles` no existe; no hay commit `M-1-SCENES`).

- [ ] **Step 3: Implement `GameSceneFiles`**

```java
package com.aicompany.core.model;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.agent.validation.GameScenes;

/**
 * Juegos por niveles (spec 2026-10-16 §3.1): la escena .tscn de cada escena del diseño la escribe Forjai. Verificado en
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
     * Juegos por niveles (spec 2026-10-16 §3): spec y diseño de juego aprobados con escenas. Vacío sin ellos (apps,
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
        // Juegos por niveles (spec 2026-10-16 §3.1): las escenas del incremento, antes de que genere nadie.
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

### Task 7: Gates de las escenas para Kael

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/GameSceneScriptsGate.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/GameMainSceneGate.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/ReservedGamePathsGate.java`
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (`gameChecks`)
- Test: `app/src/test/java/com/aicompany/core/agent/validation/GameGatesTest.java`
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java`

**Interfaces:**
- Consumes: `approvedGameScenes`, `sceneSkeletonPaths` (Task 6); `GameScenes` (Task 4).
- Produces:
  - `record GameSceneScriptsGate.SceneScript(String id, String script, String path, boolean levels)`; `path` es la ruta del `.cs` con el prefijo del componente.
  - `GameSceneScriptsGate.check(List<GeneratedFile> returned, Map<String, String> existing, List<SceneScript> scenes, List<String> owned)`.
  - `GameMainSceneGate.check(List<GeneratedFile> returned, Map<String, String> existing, String expectedMainScene)`; `expectedMainScene` es `"Main.tscn"` o null. Se mantiene la sobrecarga de 2 argumentos.
  - `ReservedGamePathsGate.check(List<GeneratedFile> files, boolean isQa, java.util.Set<String> forjaiScenes)`; se mantiene la sobrecarga de 2 argumentos.

- [ ] **Step 1: Write the failing tests**

En `GameGatesTest`:

```java
    // Juegos por niveles (spec 2026-10-16 §3.1).
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
 * Juegos por niveles (spec 2026-10-16 §3.1): cada escena del incremento tiene su script con su clase; las que tienen
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
            var code = SceneContract.strip(content);
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

`SceneContract.strip` lo crea Task 8. **Para no depender del orden**, en esta tarea crear `SceneContract.java` mínimo, solo con `strip`. Task 8 le suma la extracción:

```java
package com.aicompany.core.agent.validation;

/** Juegos por niveles (spec 2026-10-16 §3.2): lee el código C# de las escenas sin modelo. */
public final class SceneContract {

    private SceneContract() {
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
        // Juegos por niveles (spec 2026-10-16 §3.1): escenas del incremento, sus scripts y la escena principal.
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
git add app/src/main/java/com/aicompany/core/agent/validation/GameSceneScriptsGate.java app/src/main/java/com/aicompany/core/agent/validation/SceneContract.java app/src/main/java/com/aicompany/core/agent/validation/GameMainSceneGate.java app/src/main/java/com/aicompany/core/agent/validation/ReservedGamePathsGate.java app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java app/src/test/java/com/aicompany/core/agent/validation/GameGatesTest.java app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java
git commit -m "Juegos por niveles: gates de escenas para Kael (script y clase, Level/StartLevel, escena principal del diseño, .tscn de Forjai)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Contrato de las escenas (extracción sin modelo)

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/SceneContract.java` (creado mínimo en Task 7)
- Test: `app/src/test/java/com/aicompany/core/agent/validation/SceneContractTest.java` (nuevo)

**Interfaces:**
- Consumes: `SceneContract.strip` (Task 7).
- Produces:
  - `record SceneContract.Member(String kind, String type, String name, String parameters)`; `kind` es `PROPERTY`, `FIELD` o `METHOD` y `parameters` es `""` salvo en los métodos.
  - `static List<Member> SceneContract.extract(String className, java.util.Collection<String> sources)`: miembros públicos de todas las partes `partial` de la clase, en orden de aparición, sin repetir nombres y sin los que empiezan con `_` (los overrides de Godot).
  - `static String SceneContract.render(Member m)`: por ejemplo `int Level { get; }`, `void StartLevel(int level)` o `Label Puntaje`.

- [ ] **Step 1: Write the failing tests**

```java
package com.aicompany.core.agent.validation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Juegos por niveles (spec 2026-10-16 §3.2): Java lee los miembros públicos de la escena, sin modelo. */
class SceneContractTest {

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
        var members = SceneContract.extract("Main", List.of(MAIN));
        assertEquals(List.of("Level", "Puntaje", "VelocidadBase", "Pelota", "Direccion", "StartLevel", "EstaJugando"),
                members.stream().map(SceneContract.Member::name).toList());
        assertEquals("int Level { get; }", SceneContract.render(members.get(0)));
        assertEquals("void StartLevel(int level)", SceneContract.render(members.get(5)));
        assertEquals("ColorRect Pelota", SceneContract.render(members.get(3)));
        assertEquals("METHOD", members.get(6).kind());
    }

    // Review Focus 5: comentarios y strings no entran al contrato.
    @Test void commentsAndStringsDoNotCount() {
        var names = SceneContract.extract("Main", List.of(MAIN)).stream().map(SceneContract.Member::name).toList();
        assertFalse(names.contains("Viejo"));
        assertFalse(names.contains("Falso"));
        assertFalse(names.contains("Comentado"));
        assertFalse(names.contains("_Ready"));
        assertFalse(names.contains("Oculto"));
    }

    @Test void partialClassesAcrossFilesAreMergedAndOtherClassesIgnored() {
        var other = "public partial class Main { public void Reiniciar() { } }\npublic class Ayuda { public int X; }";
        var names = SceneContract.extract("Main", List.of(MAIN, other)).stream().map(SceneContract.Member::name).toList();
        assertTrue(names.contains("Reiniciar"), names.toString());
        assertFalse(names.contains("X"), names.toString());
    }

    @Test void genericAndNullableTypesAreRead() {
        var src = "public partial class Main : Node2D { public List<ColorRect> Pelotas { get; } = new(); public int? Meta; }";
        var members = SceneContract.extract("Main", List.of(src));
        assertEquals("List<ColorRect> Pelotas { get; }", SceneContract.render(members.get(0)));
        assertEquals("int? Meta", SceneContract.render(members.get(1)));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn test -Dtest=SceneContractTest`
Expected: FAIL de compilación (`extract`, `Member` y `render` no existen).

- [ ] **Step 3: Implement**

Agregar a `SceneContract`:

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

Run: `cd app && mvn test -Dtest='SceneContractTest,GameGatesTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/SceneContract.java app/src/test/java/com/aicompany/core/agent/validation/SceneContractTest.java
git commit -m "Juegos por niveles: Java extrae el contrato de cada escena (miembros públicos) sin modelo

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Vera recibe el contrato, el gate lo hace cumplir y QA va al final

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/PlaythroughContractGate.java`
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/PlaythroughContractGateTest.java` (nuevo)
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java`

**Interfaces:**
- Consumes: `SceneContract.extract/render/strip` (Task 8); `approvedGameScenes`, `GameScenes` (Tasks 4 y 6).
- Produces:
  - `PlaythroughContractGate.check(List<GeneratedFile> files, Map<String, List<SceneContract.Member>> contracts)`; la clave del mapa es el `script` de cada escena del diseño, y solo mira los archivos bajo `game/playthroughs/`.
  - `PlaythroughContractGate.GODOT_MEMBERS`, la lista fija de lo heredado de Node/Node2D que se permite.
  - `DevelopmentTeamStrategy.orderTasks(List<PlannedTask> work, TeamPlan plan, java.util.function.Predicate<String> isQa)`; sigue la versión de 2 argumentos (sin QA al final).
  - `String DevelopmentTeamStrategy.sceneContractBlock(TeamMissionContext context, String headSha)`, package-private.

- [ ] **Step 1: Write the failing tests**

`PlaythroughContractGateTest.java`:

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Juegos por niveles (spec 2026-10-16 §3.3): las partidas de Vera solo usan lo que existe en la escena. */
class PlaythroughContractGateTest {

    static final Map<String, List<SceneContract.Member>> CONTRACTS = Map.of(
            "Main", SceneContract.extract("Main", List.of(SceneContractTest.MAIN)),
            "Menu", SceneContract.extract("Menu", List.of("public partial class Menu : Node2D { public bool Visible2; }")));

    private static List<String> check(String body) {
        return PlaythroughContractGate.check(List.of(new GeneratedFile("game/playthroughs/ESC_01_x.cs", body)), CONTRACTS);
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
        assertEquals(List.of(), PlaythroughContractGate.check(List.of(new GeneratedFile("tests/X.cs",
                "var j = (Main)main; j.Nada = 1;")), CONTRACTS));
    }
}
```

En `DevelopmentTeamStrategyTest`:

```java
    // Juegos por niveles (spec 2026-10-16 §3.2): en un juego, Vera genera después del dueño de game aunque Neo la
    // ponga antes y las dos rutas caigan en la capa GAME.
    @Test
    void inAGameQaGoesLast() {
        var plan = context().plan();
        var vera = new PlannedTask("qa", "WORK", "TESTS", "Partidas", List.of("QA"), List.of("game/playthroughs"));
        var neo = new PlannedTask("engineering", "WORK", "GAME", "Juego", List.of("x"), List.of("game"));
        var ordered = DevelopmentTeamStrategy.orderTasks(List.of(vera, neo), plan, "qa"::equals);
        assertEquals(List.of("engineering", "qa"), ordered.stream().map(PlannedTask::agentId).toList());
    }

    // Vera recibe el contrato leído del código commiteado de la escena (sin modelo).
    @Test
    void veraGetsTheSceneContractFromTheCommittedCode() throws Exception {
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

        assertTrue(prompt.getValue().contains("CONTRATO DE LAS ESCENAS"), prompt.getValue());
        assertTrue(prompt.getValue().contains("- Main (game/Main.tscn, escena principal): int Level { get; }; "
                + "void StartLevel(int level); int Puntaje"), prompt.getValue());
        assertTrue(prompt.getValue().contains("Do(main => ((Main)main).StartLevel(2))"), prompt.getValue());
    }
```

Nota para el test de Vera: `withQaWork()` del archivo arma hoy el contexto fullstack. Para el juego, crear en el test un `TeamMissionContext` a partir de `context()` que reemplace la tarea `VALIDATION` de `qa` por dos tareas de `qa`: una `WORK` con ownedPaths `["game/playthroughs"]` y una `VALIDATION`. Copiar cómo arma `withQaWork()` el plan con `new TeamPlan(...)`. Agregar los stubs de commit de `M-1-QA` como en `qaWithATestsTaskReviewsUnderItsOwnTaskId`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd app && mvn test -Dtest='PlaythroughContractGateTest,DevelopmentTeamStrategyTest'`
Expected: FAIL.

- [ ] **Step 3: Implement `PlaythroughContractGate`**

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
 * Juegos por niveles (spec 2026-10-16 §3.3): antes del sandbox, las partidas de Vera solo usan miembros que existen en
 * la escena (el Pong 3 usaba Main.Pelota, FinalScoreLabel y EstadoJuego, que no existían). Reconoce (X)main, main as X
 * y las variables asignadas con ellos; lo que no reconoce lo juzga el compilador en el sandbox.
 */
public final class PlaythroughContractGate {

    /** Lo heredado de Node/Node2D que una partida puede usar sobre la escena. */
    public static final Set<String> GODOT_MEMBERS = Set.of("GetNode", "GetNodeOrNull", "GetChildren", "GetChild",
            "GetChildCount", "FindChild", "Position", "GlobalPosition", "Visible", "Name", "IsInsideTree", "GetTree",
            "Rotation", "Scale");

    private static final Pattern SCENE = Pattern.compile("\\bScene\\s*=>\\s*\"([^\"]*)\"");
    private static final Pattern CAST_ACCESS = Pattern.compile("\\(\\s*\\(\\s*([A-Za-z_]\\w*)\\s*\\)\\s*([A-Za-z_]\\w*)\\s*\\)\\s*\\.\\s*([A-Za-z_]\\w*)");
    private static final Pattern MAIN_CAST = Pattern.compile("\\(\\s*([A-Za-z_]\\w*)\\s*\\)\\s*main\\b|\\bmain\\s+as\\s+([A-Za-z_]\\w*)");
    private static final Pattern ALIAS = Pattern.compile("\\b(?:var|[A-Z]\\w*)\\s+([A-Za-z_]\\w*)\\s*=\\s*(?:\\(\\s*([A-Za-z_]\\w*)\\s*\\)\\s*main\\b|main\\s+as\\s+([A-Za-z_]\\w*))");

    private PlaythroughContractGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> files,
                                     Map<String, List<SceneContract.Member>> contracts) {
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
            var code = SceneContract.strip(f.content());
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

    private static void member(String path, String type, String name, Map<String, List<SceneContract.Member>> contracts,
                               Set<String> errors) {
        var members = contracts.get(type);
        if (GODOT_MEMBERS.contains(name) || members.stream().anyMatch(m -> m.name().equals(name))) {
            return;
        }
        errors.add(path + ": " + type + "." + name + " no existe; existen: "
                + members.stream().map(SceneContract.Member::name).collect(Collectors.joining(", ")) + ".");
    }
}
```

El test `validMembersCastsAliasesAndAsPass` incluye `Do(m => ((Main)m).StartLevel(2))`: `CAST_ACCESS` acepta cualquier variable castada a una escena, y `MAIN_CAST` solo juzga los casts de `main`.

- [ ] **Step 4: Implement in `DevelopmentTeamStrategy`**

1. `orderTasks`: la versión de 2 argumentos delega en `orderTasks(work, plan, agentId -> false)`; la nueva:

```java
    /** Juegos por niveles (spec 2026-10-16 §3.2): en un juego, QA (las partidas de Vera) va siempre al final. */
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

2. Contrato de escena para Vera:

```java
    /** Contratos de las escenas del incremento, leídos del código commiteado en headSha (sin modelo). */
    Map<String, List<com.aicompany.core.agent.validation.SceneContract.Member>> sceneContracts(TeamMissionContext context,
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
        var contracts = new LinkedHashMap<String, List<com.aicompany.core.agent.validation.SceneContract.Member>>();
        for (var s : nnScenes(a)) {
            contracts.put(s.script(), com.aicompany.core.agent.validation.SceneContract.extract(s.script(), sources));
        }
        return contracts;
    }

    private static List<com.aicompany.core.agent.model.InteractionDesign.Scene> nnScenes(
            com.aicompany.core.agent.validation.GameScenes.Approved a) {
        return com.aicompany.core.agent.validation.GameScenes.upTo(a.game(), a.spec(), a.increment());
    }

    /** Juegos por niveles (spec 2026-10-16 §3.2): lo que recibe Vera; vacío si no es un juego con escenas. */
    String sceneContractBlock(TeamMissionContext context, String headSha) {
        var contracts = sceneContracts(context, headSha);
        if (contracts.isEmpty()) {
            return "";
        }
        var main = approvedGameScenes(context.missionId())
                .flatMap(a -> com.aicompany.core.agent.validation.GameScenes.main(a.game()))
                .map(com.aicompany.core.agent.model.InteractionDesign.Scene::script).orElse("");
        return "\n\nCONTRATO DE LAS ESCENAS (Java lo leyó del código commiteado de Kael, commit " + headSha
                + "): en las partidas solo puedes usar estos miembros de cada escena; cualquier otro no existe.\n"
                + contracts.entrySet().stream().map(e -> "- " + e.getKey() + " ("
                        + com.aicompany.core.agent.validation.GameScenes.scenePath(e.getKey())
                        + (e.getKey().equals(main) ? ", escena principal" : "") + "): "
                        + (e.getValue().isEmpty() ? "(sin miembros públicos)" : e.getValue().stream()
                        .map(com.aicompany.core.agent.validation.SceneContract::render).collect(Collectors.joining("; "))))
                .collect(Collectors.joining("\n"))
                + "\nPara otra escena que no sea la principal: public override string Scene => \"<script>\";. Para arrancar "
                + "en un nivel: yield return Do(main => ((" + main + ")main).StartLevel(2)); antes de los Press/Wait.\n";
    }
```

3. En el loop de generación, en las dos ramas de `generate(...)` (ronda > 0 y primera), sumar al prompt `+ (isQa(context, task.agentId()) ? sceneContractBlock(context, generationHead) : "")` justo después de `buildWorkPrompt(context, task)` (y de `ROUND_RULE` en la rama de ronda). En una ronda de corrección `generationHead` es el último commit, así que el contrato se recalcula desde ahí.

4. En `gameChecks`, para `qa`: calcular `var contracts = qa ? sceneContracts(context, headSha) : Map.<String, List<com.aicompany.core.agent.validation.SceneContract.Member>>of();` y en el `return result -> {…}`:

```java
            if (qa && !contracts.isEmpty()) {
                errors.addAll(com.aicompany.core.agent.validation.PlaythroughContractGate.check(result.files(), contracts));
            }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd app && mvn test`
Expected: PASS (incluido `fullstackTasksRunBackendThenAppThenTests`, que usa la versión de 2 argumentos).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/PlaythroughContractGate.java app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java app/src/test/java/com/aicompany/core/agent/validation/PlaythroughContractGateTest.java app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java
git commit -m "Juegos por niveles: Vera recibe el contrato de las escenas, el gate lo hace cumplir antes del sandbox y QA va al final

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: El runner arranca otra escena o un nivel (`Scene` y `Do`)

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
    // Juegos por niveles (spec 2026-10-16 §3.1): una partida puede cargar otra escena y preparar el nivel.
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

Expected: `restore` y `build` con rc 0; `test` con rc 1, porque ESC_03 falla a propósito. En `playthroughs.json`: `ESC_01_nivel2` PASSED, `ESC_02_menu` PASSED y `ESC_03_falla` FAILED con «falló la aserción: debe fallar». Registrar el resultado (o los bugs encontrados y su arreglo) en el ledger y luego en `docs/HISTORY.md` (Task 11).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/GameRunnerFiles.java app/src/test/java/com/aicompany/core/model/GameRunnerFilesTest.java
git commit -m "Runner de partidas: Scene elige la escena y Do prepara el nivel (verificado en el sandbox Godot)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Documentación y suite completa

**Files:**
- Modify: `docs/ARCHITECTURE.md`, `docs/HISTORY.md`, `docs/FALTANTES.md`

- [ ] **Step 1: ARCHITECTURE**

En la sección de Aria (spec y validación), agregar un párrafo «Juegos por niveles (2026-10-16)» con:

- `levels` y `levelIds`;
- Java deriva `userStoryIds`;
- las reglas del validador;
- dónde lo ve el fundador.

En la sección de Kael / juegos, agregar:

- `scenes` y `levels` del diseño y sus reglas;
- los esqueletos `.tscn` de Forjai (commit `-SCENES`, reservados);
- `GameSceneScriptsGate`, `GameMainSceneGate` contra ESCENA-01, el contrato de escena para Vera y `PlaythroughContractGate`;
- QA al final en juegos;
- `Scene` y `Do` del runner;
- la compatibilidad con juegos sin niveles.

En la revisión conjunta, mencionar `NIVEL-NN` y `ESCENA-NN` como referencias.

- [ ] **Step 2: HISTORY**

Agregar una entrada «Juegos por niveles y escenas (2026-10-16)» con:

- la decisión del fundador, citada del spec;
- las 3 causas del Pong 3;
- el ruling de `GameSceneFiles` aparte de `ProjectScaffold`;
- el resultado de la verificación manual del runner (Task 10 Step 5);
- que queda pendiente el despliegue y la prueba en vivo.

- [ ] **Step 3: FALTANTES**

Agregar como pendientes:

- [ ] el despliegue de la revisión conjunta y de juegos por niveles, con OK del fundador;
- [ ] la prueba en vivo del Pong de 3 niveles.

Si alguna entrada de FALTANTES del Pong 3 («contrato Kael↔Vera», «Main.tscn sin script») queda cubierta por el código, marcarla `[x]` con la referencia a este plan. La parte "en vivo" sigue pendiente.

- [ ] **Step 4: Suite completa y frontend**

Run: `cd app && mvn test` → PASS (anotar el número de tests en el ledger).
Run: `cd app/frontend && npm run lint && npm run build` → sin errores.
Run: `cd sandbox-runner && mvn test` → PASS (no debería cambiar).

- [ ] **Step 5: Commit**

```bash
git add docs/ARCHITECTURE.md docs/HISTORY.md docs/FALTANTES.md
git commit -m "Docs: juegos por niveles y escenas

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: Despliegue y prueba en vivo (requiere OK del fundador; no es de un subagente)

Esta tarea la hace el controlador con el fundador, no un implementador.

- [ ] **Step 1:** Consultar en un paso aparte si hay misiones vivas (`GET /api/company/missions` filtrando estados no terminales). Si hay alguna, parar y avisar.
- [ ] **Step 2:** Con el OK del fundador, reconstruir y levantar `company-core` con `docker compose build company-core && docker compose up -d company-core`. Así entran juntas la revisión conjunta (Task 8 del plan `2026-10-15`) y este trabajo. El `sandbox-runner` no cambia: el runner de partidas vive en el repositorio de cada misión. Verificar `GET http://localhost:8081/actuator/health`.
- [ ] **Step 3:** Prender el inhibidor de suspensión con `systemd-inhibit --what=sleep:idle:handle-lid-switch …`, incluido `idle`.
- [ ] **Step 4:** Con el OK del fundador, crear una misión del Development Group con el Pong de 3 niveles: «Un Pong: nivel 1 pelota normal hasta 50 puntos; nivel 2 la pelota aumenta de velocidad; nivel 3 dos pelotas». Comprobar estas condiciones:
  - Aria propone `levels` con 3 niveles y un incremento por nivel (o explica en el `goal` por qué agrupa);
  - Kael diseña ESCENA-01 y un `levels` por nivel;
  - la revisión conjunta corre;
  - el mensaje de aprobación muestra la división.
- [ ] **Step 5:** El fundador aprueba o ajusta. Seguir el incremento 1 hasta `VERIFIED`, revisando que:
  - existe el commit `-SCENES` con `game/Main.tscn`;
  - Kael no escribe la `.tscn`;
  - Vera recibe el contrato y genera después de Kael;
  - ninguna partida usa miembros inexistentes.
- [ ] **Step 6:** Registrar el resultado en `docs/HISTORY.md`. Con el OK del fundador, «construye el incremento 2».

---

## Self-Review

**Cobertura del spec:**

| Spec | Tasks |
|---|---|
| §1 levels, kind, ids, HU en un nivel, levelIds, Java deriva las HU | 1 |
| §1 instrucciones a Aria | 2 |
| §1 validador y tope de 10 | 1 |
| §1 lo que ve el fundador (mensaje, detalle y chat vía `summary`) | 2 y 5 |
| §1 `incrementBlock` | 2 |
| §2 scenes, levels y su validación (incluye el tope de 8) | 3 |
| §2 `InteractionDesignText` | 4 |
| §2 revisión conjunta | 4 |
| §3.1 esqueletos y reserva | 6 y 7 |
| §3.1 `GameSceneScriptsGate`, `GameMainSceneGate`, `Level`/`StartLevel` | 7 |
| §3.1 runner `Scene` y `Do` | 10 |
| §3.2 QA al final | 9 |
| §3.2 contrato y recálculo en la ronda | 8 y 9 |
| §3.3 `PlaythroughContractGate` | 9 |
| Compatibilidad | 1, 3, 6 (test sin escenas) y 10 (parámetro opcional) |
| Qué ve el fundador: partidas por nivel en el reporte (sale solo de los nombres de las partidas, sin código nuevo) | 2, 5 y 10 |
| Pruebas | Todas las tasks |
| En vivo | 12 |

**Rulings:**

1. Los esqueletos van en `GameSceneFiles`, aparte de `ProjectScaffold`, con un commit propio (Task 6).
2. Toda escena donde ocurre un nivel o escena de Aria expone `Level`/`StartLevel` (Task 4).
3. Los `levels` de un spec no-GAME se ignoran (Task 1).
4. `approvedGameScenes` falla abierto si no puede leer Neo4j, como `scenarios()`: rigen las reglas de antes (Task 6).
5. Lo heredado de Node/Node2D que se permite es una lista fija (Task 9).

**Tipos consistentes:**

- `GameScenes.Approved(spec, game, increment)` se define en la Task 4 y lo usan las 6, 7 y 9.
- `SceneContract.strip` es de la Task 7 y `extract`/`render`/`Member` de la Task 8; los usa la Task 9.
- `GameSceneScriptsGate.SceneScript(id, script, path, levels)` sale de la Task 7.
- `orderTasks(work, plan, isQa)` sale de la Task 9.
