# Kael híbrido Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kael diseña la interacción de apps y juegos (aprobada junto con las HU de Aria) y programa juegos 2D Godot verificados con partidas simuladas en el sandbox.

**Architecture:** Parte 1 copia el molde de Aria: `experience` en el spec de Aria, una tarea nueva de Kael con `format` sin `tools` (`InteractionDesignWriter`), validador puro en Java, versiones en Neo4j, aprobación conjunta y un chequeo `DESIGN_CONFORMANCE` tras el sandbox. Parte 2 habilita `GODOT_DOTNET_GAME`/`GAME_DEV` en fase 1, agrega gates deterministas y un runner fijo de Forjai (C#) que ejecuta en Godot headless las partidas que escribe Vera; el sandbox-runner suma esos resultados a los tests con nombre.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Jackson 3 (`tools.jackson.*`; las anotaciones siguen en `com.fasterxml.jackson.annotation`), Neo4j driver plano, JUnit 5 + Mockito, React + TS (Command Center), Godot 4.3 .NET (net8.0), Podman.

**Spec:** `docs/superpowers/specs/2026-10-14-kael-hibrido-design.md`

## Global Constraints

- Determinismo sobre LLM: toda regla (validación del diseño, reglas de Neo, conformidad, gates) en Java; nunca se le pide al modelo que se revise.
- Nunca combinar `format` y `tools` en una llamada al modelo.
- Jackson 3 (`tools.jackson.databind.json.JsonMapper`); nunca `com.fasterxml.jackson.databind`.
- Sin `@Async`; el trabajo de la misión corre por `MissionExecutor.submit`.
- Errores HTTP: `IllegalArgumentException`/`IllegalStateException`, sin manejo nuevo en controllers.
- Kafka: `eventType` con prefijo `EMPRESA_`; eventos nuevos `EMPRESA_INTERACTION_DESIGN_READY`, `EMPRESA_INTERACTION_DESIGN_ADJUSTED`.
- Neo4j: Cypher a mano en `*MemoryService`.
- Agente de Kael: id `interaction-design`, `roleCode` `GAME_DEV`.
- Ids: pantallas `PANT-\d{2}`, reglas `REGLA-\d{2}`, escenarios `ESC-\d{2}`, HU `HU-\d{2}`; acciones de input `[a-z][a-z0-9_]*`.
- Topes del diseño: 15 pantallas, 20 reglas, 8 controles. Validador: 3 intentos.
- Partidas: `GD.Seed(0)`, `--fixed-fps 60`, tope 3.600 frames por partida, salida `/work/.forjai/playthroughs.json`.
- Assets prohibidos en `game/`: `.png .jpg .jpeg .svg .wav .ogg .mp3 .ttf .otf .glb .gltf`.
- Imagen nueva del sandbox: `localhost/forjai-sandbox/godot-dotnet-game:2`.
- Todo lo nuevo se consulta en el chat; `api/types.ts` refleja los records Java.
- Comandos: `cd app && mvn test -Dtest=<Clase>`; `cd sandbox-runner && mvn test -Dtest=<Clase>`; `cd app/frontend && npm run lint && npm run build`.
- Commits terminan con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Un juego pedido con la palabra "pantalla"** ("un Pong con pantalla de game over"): `RequestedComponents` exigiría Flutter; con `experience` declarado manda `ExperienceRule` y `RequestedComponents` no corre → test en Task 7.
2. **Ajuste a Kael y luego aprobar**: el lock de la decisión (`decisionClaimedAt` en la versión de HU) se libera cuando Kael guarda el diseño nuevo; la aprobación posterior pasa → test en Task 6.
3. **Misión con requisitos anteriores a esta rama** (spec sin `experience`): aprobar, retomar y planificar funcionan sin Kael ni reglas nuevas → tests en Tasks 5, 6 y 7.
4. **Ajuste a Aria con un diseño en `DRAFT`**: el diseño viejo pasa a `SUPERSEDED`; aprobar con un diseño de otra versión de HU se rechaza → test en Task 6.
5. **Kael escribe en `game/playthroughs/` o cualquiera en `game/forjai/`**: reintento con el motivo, nunca se pisa el runner ni las partidas de Vera → test en Task 9.

---

## File Structure

**Nuevos (company-core, `app/src/main/java/com/aicompany/core/`)**
- `agent/model/InteractionDesign.java` — records `AppDesign`, `GameDesign` y el contenedor `InteractionDesign`; schemas.
- `agent/validation/InteractionDesignValidator.java` — reglas puras del diseño.
- `agent/validation/InteractionDesignText.java` — bloque por incremento y resumen para chat/prompt.
- `agent/validation/ExperienceRule.java` — Neo respeta `experience` (lee la línea `TIPO DE EXPERIENCIA:`).
- `agent/validation/GameAssetGate.java`, `GameMainSceneGate.java`, `InjectableRandomGate.java`, `ReservedGamePathsGate.java`.
- `agent/validation/DesignConformance.java` — chequeo `DESIGN_CONFORMANCE`.
- `model/GameRunnerFiles.java` — contenido fijo de `game/forjai/*` (runner C#).
- `model/InteractionDesignView.java` — vista para API/UI.
- `service/InteractionDesignMemoryService.java` — versiones en Neo4j.
- `service/InteractionDesignWriter.java` — tarea de Kael.

**Modificados (company-core)**: `RequirementsSpec`, `RequirementsSpecValidator`, `RequirementsWriter`, `RequirementsText`, `RequirementsMemoryService`, `RequirementsDecisionCommand`, `CeoService`, `MissionExecutor`, `MissionService`, `MissionController`, `ResumePoint`, `MissionSnapshot`, `MissionRecoveryService`, `TeamWorkPlanner`, `StackProfile`, `RoleLayerCatalog`, `TeamPlanResolver`, `ProjectScaffold`, `TestInventory`, `DevelopmentTeamStrategy`, `ChatIntentRouter`.

**sandbox**: `sandbox/images/godot-dotnet-game/run.sh`, `sandbox/build-images.sh`, `sandbox-runner/.../ExecutionProfile.java`, `sandbox-runner/.../TestReportParser.java`.

**frontend**: `app/frontend/src/api/types.ts`, `api/client.ts`, `pages/MissionDetailPage.tsx`.

**docs**: `docs/ARCHITECTURE.md`, `docs/EVENTS.md`, `docs/FALTANTES.md`, `docs/HISTORY.md`.

---

### Task 0: Spike — Godot headless corre assemblies de `dotnet build` e inyecta inputs

Throwaway en el scratchpad; nada se commitea. Si falla, **parar y avisar al fundador** antes de seguir con las Tasks 8-11.

**Files:** solo en `$SCRATCH/spike-godot/` (scratchpad de la sesión).

- [ ] **Step 1: Proyecto mínimo**

```bash
S=$SCRATCH/spike-godot && mkdir -p $S/game/forjai && cd $S
cat > game/project.godot <<'EOF'
config_version=5
[application]
config/name="Spike"
run/main_scene="res://Main.tscn"
[dotnet]
project/assembly_name="Game"
[input]
move_right={"deadzone": 0.5, "events": []}
EOF
cat > game/Game.csproj <<'EOF'
<Project Sdk="Godot.NET.Sdk/4.3.0"><PropertyGroup><TargetFramework>net8.0</TargetFramework><EnableDynamicLoading>true</EnableDynamicLoading></PropertyGroup></Project>
EOF
cat > game/Main.cs <<'EOF'
using Godot;
public partial class Main : Node2D { public int X; public override void _PhysicsProcess(double d) { if (Input.IsActionPressed("move_right")) X++; } }
EOF
cat > game/Main.tscn <<'EOF'
[gd_scene load_steps=2 format=3]
[ext_resource type="Script" path="res://Main.cs" id="1"]
[node name="Main" type="Node2D"]
script = ExtResource("1")
EOF
```

Copiar además `game/forjai/Playthrough.cs`, `PlaythroughRunner.cs` y `Runner.tscn` con el contenido exacto de Task 10 (Step 3) y una partida:

```bash
mkdir -p game/playthroughs && cat > game/playthroughs/ESC_01_avanza.cs <<'EOF'
using System.Collections.Generic; using Godot; using Forjai.Playthroughs;
public sealed class ESC_01_avanza : Playthrough {
    public override IEnumerable<Step> Steps(Node main) {
        yield return Press("move_right", frames: 30);
        yield return Assert(() => ((Main)main).X >= 25, "X avanzó");
    }
}
EOF
```

- [ ] **Step 2: Correr en la imagen actual**

```bash
podman run --rm --network=none -v $S:/work:Z -w /work localhost/forjai-sandbox/godot-dotnet-game:1 bash -c '
  mkdir -p $HOME/.nuget/NuGet && cp /forjai/NuGet.Config $HOME/.nuget/NuGet/ && mkdir -p .forjai &&
  dotnet build game/Game.csproj -c Debug &&
  godot --headless --path /work/game --import ; 
  godot --headless --fixed-fps 60 --path /work/game res://forjai/Runner.tscn -- --out=/work/.forjai/playthroughs.json; echo rc=$?;
  cat .forjai/playthroughs.json'
```

Expected: `rc=0` y `[{"name":"ESC_01_avanza","outcome":"PASSED",...}]`. Probar también una aserción falsa (`X >= 100`) → `rc=1` y `"outcome":"FAILED"`.

- [ ] **Step 3: Registrar el resultado**

Anotar en el mensaje al fundador: si hizo falta `--import`, si `ParseInputEvent` funcionó sin ventana, y cualquier flag extra. Si algo cambió respecto de Task 10/11 (p. ej. `--import` necesario), aplicar ese ajuste al código de esas tasks. Si no funciona de ninguna forma: **STOP**.

---

### Task 1: Aria declara `experience`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/model/RequirementsSpec.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/RequirementsSpecValidator.java`
- Modify: `app/src/main/java/com/aicompany/core/service/RequirementsWriter.java` (prompt)
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/RequirementsText.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/RequirementsSpecValidatorTest.java`, `RequirementsTextTest.java`

**Interfaces:**
- Produces: `RequirementsSpec.experience()` (`String`, `null` en specs viejos); `RequirementsSpec.Experience` enum `APP, GAME, NONE` con `static Optional<Experience> of(String)`; `RequirementsSpec.experienceOrNull()` → `Experience` o `null`; constructor anterior de 6 args conservado (experience = null); `RequirementsText.EXPERIENCE_LINE = "TIPO DE EXPERIENCIA: "` y `incrementBlock` incluye `TIPO DE EXPERIENCIA: <X>` cuando no es null.

- [ ] **Step 1: Tests que fallan**

En `RequirementsSpecValidatorTest` agregar (usar el spec válido que ya arma la clase; si se llama distinto, construirlo como `VALID` de `RequirementsWriterTest`):

```java
@Test
void experienceIsRequiredAndMustBeKnown() {
    var missing = withExperience(null);
    assertTrue(RequirementsSpecValidator.validate(missing).stream().anyMatch(e -> e.contains("experience")));
    assertTrue(RequirementsSpecValidator.validate(withExperience("WEB")).stream().anyMatch(e -> e.contains("experience")));
    assertEquals(List.of(), RequirementsSpecValidator.validate(withExperience("GAME")));
}

private static RequirementsSpec withExperience(String experience) {
    return new RequirementsSpec("Pong", List.of(), List.of(), List.of(new RequirementsSpec.Role("jugador", "x")),
            List.of(new RequirementsSpec.UserStory("HU-01", "jugador", "mover", "jugar", List.of("c"), List.of(),
                    List.of(new RequirementsSpec.Scenario("ESC-01", "t", List.of("d"), List.of("c"), List.of("e"))))),
            List.of(new RequirementsSpec.Increment(1, "MVP", List.of("HU-01"))), experience);
}
```

En `RequirementsTextTest`:

```java
@Test
void theIncrementBlockCarriesTheExperienceLine() {
    var spec = /* mismo spec de 7 args con "GAME" */;
    assertTrue(RequirementsText.incrementBlock(spec, 1).contains("TIPO DE EXPERIENCIA: GAME"));
    var legacy = /* constructor de 6 args */;
    assertFalse(RequirementsText.incrementBlock(legacy, 1).contains("TIPO DE EXPERIENCIA"));
}
```

**Importante**: el validador rechaza `experience` ausente **solo al escribir** (specs nuevos); los specs viejos guardados no se revalidan, así que no se rompe nada.

- [ ] **Step 2: Correr y ver que falla**

Run: `cd app && mvn test -Dtest=RequirementsSpecValidatorTest,RequirementsTextTest`
Expected: FAIL (no compila: constructor de 7 args).

- [ ] **Step 3: Implementar**

`RequirementsSpec`: agregar el componente `String experience` al final del record, el constructor de 6 args que delega con `null`, el enum y el schema:

```java
public enum Experience { APP, GAME, NONE;
    public static java.util.Optional<Experience> of(String value) {
        return value == null ? java.util.Optional.empty()
                : java.util.Arrays.stream(values()).filter(e -> e.name().equals(value.strip())).findFirst();
    }
}

public Experience experienceOrNull() {
    return Experience.of(experience).orElse(null);
}
```

En `SCHEMA` agregar `"experience", Map.of("type", "string", "enum", List.of("APP", "GAME", "NONE"))` a `properties` y `"experience"` a `required`. (`Map.of` admite hasta 10 pares; `properties` tiene 6 → 7, OK.)

`RequirementsSpecValidator.validate`, al principio tras el null-check:

```java
if (RequirementsSpec.Experience.of(spec.experience()).isEmpty()) {
    errors.add("experience debe ser APP (el producto tiene pantallas), GAME (es un juego) o NONE (solo API o "
            + "servicio, sin ninguna interfaz); recibido: \"" + spec.experience() + "\".");
}
```

`RequirementsWriter.prompt`: agregar a REGLAS
`- experience: GAME si el pedido es un juego; APP si las personas usan el producto con pantallas; NONE solo si no tiene ninguna interfaz (API o servicio).`
y al JSON de ejemplo `"experience": "APP|GAME|NONE",` tras `"summary"`.

`RequirementsText`:

```java
public static final String EXPERIENCE_LINE = "TIPO DE EXPERIENCIA: ";
```

y en `incrementBlock`, antes de `Roles:`, la línea `EXPERIENCE_LINE + spec.experienceOrNull().name()` cuando `experienceOrNull() != null` (vacía si es null). Agregar `" Tipo: " + experience` al principio de `summary` cuando no es null.

- [ ] **Step 4: Correr y ver que pasa**

Run: `cd app && mvn test -Dtest=RequirementsSpecValidatorTest,RequirementsTextTest,RequirementsWriterTest`
Expected: PASS. Si `RequirementsWriterTest.VALID` (6 args) ahora no valida por `experience`, agregar `"APP"` a esos specs de prueba.

- [ ] **Step 5: Suite completa y commit**

Run: `cd app && mvn test` → PASS (ajustar otros specs de prueba de 6 args que pasen por el validador agregando `experience`).

```bash
git add app/src && git commit -m "Aria declara el tipo de experiencia (APP, GAME, NONE) en su spec

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Modelo y validador del diseño de Kael

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/model/InteractionDesign.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/InteractionDesignValidator.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/InteractionDesignValidatorTest.java`

**Interfaces:**
- Produces:
  - `InteractionDesign(String experience, AppDesign app, GameDesign game)`; `AppDesign(List<Screen> screens, List<Flow> flows, List<ScenarioScreen> scenarioScreens, List<String> frictionRisks)`; `Screen(String id, String name, String purpose, String role, List<String> states)`; `Flow(String story, List<String> screens, List<String> actions)`; `ScenarioScreen(String scenario, String screen)`.
  - `GameDesign(List<String> states, List<Transition> transitions, List<Entity> entities, List<Control> controls, List<Rule> rules, List<String> win, List<String> lose, List<Property> difficulty, List<ScenarioRule> scenarioRules, List<String> balanceHypotheses)`; `Transition(String from, String to, String trigger)`; `Entity(String id, String name, List<Property> properties)`; `Property(String name, Double value)`; `Control(String action, String key, String effect)`; `Rule(String id, String condition, String effect)`; `ScenarioRule(String scenario, String target)` (target = `REGLA-NN` o una `action`).
  - `AppDesign.SCHEMA`, `GameDesign.SCHEMA` (`Map<String,Object>`).
  - `InteractionDesignValidator.validateApp(AppDesign, RequirementsSpec) → List<String>`, `validateGame(GameDesign, RequirementsSpec) → List<String>`; constantes `MAX_SCREENS = 15`, `MAX_RULES = 20`, `MAX_CONTROLS = 8`.

- [ ] **Step 1: Tests que fallan**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign.*;
import com.aicompany.core.agent.model.RequirementsSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InteractionDesignValidatorTest {

    static final RequirementsSpec SPEC = new RequirementsSpec("x", List.of(), List.of(),
            List.of(new RequirementsSpec.Role("vendedor", "d")),
            List.of(new RequirementsSpec.UserStory("HU-01", "vendedor", "q", "p", List.of("c"), List.of(),
                    List.of(new RequirementsSpec.Scenario("ESC-01", "a", List.of("d"), List.of("c"), List.of("e")),
                            new RequirementsSpec.Scenario("ESC-02", "b", List.of("d"), List.of("c"), List.of("e"))))),
            List.of(new RequirementsSpec.Increment(1, "MVP", List.of("HU-01"))), "APP");

    static AppDesign app(List<Screen> screens, List<Flow> flows, List<ScenarioScreen> map) {
        return new AppDesign(screens, flows, map, List.of("hipótesis"));
    }

    static final Screen P1 = new Screen("PANT-01", "Clientes", "listar", "vendedor", List.of("empty"));

    @Test void aValidAppDesignPasses() {
        assertEquals(List.of(), InteractionDesignValidator.validateApp(app(List.of(P1),
                List.of(new Flow("HU-01", List.of("PANT-01"), List.of("abrir"))),
                List.of(new ScenarioScreen("ESC-01", "PANT-01"), new ScenarioScreen("ESC-02", "PANT-01"))), SPEC));
    }

    @Test void everyScenarioMustBeAssigned() {
        var errors = InteractionDesignValidator.validateApp(app(List.of(P1),
                List.of(new Flow("HU-01", List.of("PANT-01"), List.of("abrir"))),
                List.of(new ScenarioScreen("ESC-01", "PANT-01"))), SPEC);
        assertTrue(errors.stream().anyMatch(e -> e.contains("ESC-02")), errors.toString());
    }

    @Test void unknownIdsScreensAndRolesAreRejected() {
        var errors = InteractionDesignValidator.validateApp(app(
                List.of(new Screen("P-1", "x", "y", "admin", List.of("raro"))),
                List.of(new Flow("HU-09", List.of("PANT-07"), List.of())),
                List.of(new ScenarioScreen("ESC-01", "PANT-07"), new ScenarioScreen("ESC-02", "PANT-07"),
                        new ScenarioScreen("ESC-99", "PANT-07"))), SPEC);
        var all = String.join("\n", errors);
        assertTrue(all.contains("P-1"), all);       // formato PANT-NN
        assertTrue(all.contains("admin"), all);     // rol inexistente
        assertTrue(all.contains("HU-01"), all);     // HU sin flujo
        assertTrue(all.contains("HU-09"), all);     // flujo de HU inexistente
        assertTrue(all.contains("PANT-07"), all);   // pantalla inexistente
        assertTrue(all.contains("ESC-99"), all);    // escenario inexistente
        assertTrue(all.contains("raro"), all);      // estado inválido
    }

    static GameDesign game(List<Control> controls, List<Rule> rules, List<Transition> transitions,
                           List<String> lose, List<ScenarioRule> map, Double speed) {
        return new GameDesign(List.of("START", "PLAYING", "GAME_OVER"), transitions,
                List.of(new Entity("pelota", "Pelota", List.of(new Property("velocidad", speed)))),
                controls, rules, List.of(), lose, List.of(new Property("velocidad_por_5_puntos", 1.1)), map,
                List.of("hipótesis"));
    }

    @Test void aValidGameDesignPasses() {
        assertEquals(List.of(), InteractionDesignValidator.validateGame(game(
                List.of(new Control("move_right", "Right", "mueve la paleta")),
                List.of(new Rule("REGLA-01", "move_right", "la paleta avanza"),
                        new Rule("REGLA-02", "la pelota pasa la paleta", "GAME_OVER")),
                List.of(new Transition("START", "PLAYING", "ui_accept"), new Transition("PLAYING", "GAME_OVER", "REGLA-02")),
                List.of("REGLA-02"),
                List.of(new ScenarioRule("ESC-01", "move_right"), new ScenarioRule("ESC-02", "REGLA-02")), 300.0), SPEC));
    }

    @Test void gameRulesAreCheckedInJava() {
        var errors = InteractionDesignValidator.validateGame(game(
                List.of(new Control("Move Right", "Right", "x"), new Control("jump", "Space", "salta")),
                List.of(new Rule("REGLA-01", "algo", "efecto")),
                List.of(new Transition("PLAYING", "PAUSA", "p")),
                List.of(), List.of(new ScenarioRule("ESC-01", "REGLA-05")), null), SPEC);
        var all = String.join("\n", errors);
        assertTrue(all.contains("Move Right"), all);  // formato de acción
        assertTrue(all.contains("jump"), all);        // control sin uso en reglas
        assertTrue(all.contains("PAUSA"), all);       // transición a estado inexistente
        assertTrue(all.contains("velocidad"), all);   // propiedad sin número
        assertTrue(all.contains("win"), all);         // sin win ni lose
        assertTrue(all.contains("REGLA-05"), all);    // destino inexistente
        assertTrue(all.contains("ESC-02"), all);      // escenario sin asignar
    }

    @Test void capsKeepTheDesignAtMvpSize() {
        var many = java.util.stream.IntStream.rangeClosed(1, 16)
                .mapToObj(i -> new Screen("PANT-%02d".formatted(i), "s", "p", "vendedor", List.of())).toList();
        var errors = InteractionDesignValidator.validateApp(app(many,
                List.of(new Flow("HU-01", List.of("PANT-01"), List.of())),
                List.of(new ScenarioScreen("ESC-01", "PANT-01"), new ScenarioScreen("ESC-02", "PANT-01"))), SPEC);
        assertTrue(errors.stream().anyMatch(e -> e.contains("15")), errors.toString());
    }
}
```

- [ ] **Step 2: Correr y ver que falla**

Run: `cd app && mvn test -Dtest=InteractionDesignValidatorTest` → FAIL (no compila).

- [ ] **Step 3: Implementar el modelo**

`InteractionDesign.java` (records públicos anidados; listas null → `List.of()` en el constructor compacto, igual que `RequirementsSpec`; `@JsonFormat(with = ACCEPT_SINGLE_VALUE_AS_ARRAY)` en las `List<String>`):

```java
package com.aicompany.core.agent.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;
import java.util.Map;

/** Spec 2026-10-14 Kael §1.3: el diseño de interacción (apps) o de juego que Kael escribe antes del plan de Neo. */
public record InteractionDesign(String experience, AppDesign app, GameDesign game) {

    public record Screen(String id, String name, String purpose, String role,
                         @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> states) {
        public Screen { states = states == null ? List.of() : states; }
    }

    public record Flow(String story, @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> screens,
                       @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> actions) {
        public Flow { screens = screens == null ? List.of() : screens; actions = actions == null ? List.of() : actions; }
    }

    public record ScenarioScreen(String scenario, String screen) { }

    public record AppDesign(List<Screen> screens, List<Flow> flows, List<ScenarioScreen> scenarioScreens,
                            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> frictionRisks) {
        public AppDesign {
            screens = screens == null ? List.of() : screens;
            flows = flows == null ? List.of() : flows;
            scenarioScreens = scenarioScreens == null ? List.of() : scenarioScreens;
            frictionRisks = frictionRisks == null ? List.of() : frictionRisks;
        }

        public static final Map<String, Object> SCHEMA = Map.of("type", "object",
                "properties", Map.of(
                        "screens", array(Map.of("type", "object", "properties", Map.of("id", str(), "name", str(),
                                "purpose", str(), "role", str(), "states", strings()),
                                "required", List.of("id", "name", "purpose", "role", "states"))),
                        "flows", array(Map.of("type", "object", "properties", Map.of("story", str(),
                                "screens", strings(), "actions", strings()), "required", List.of("story", "screens", "actions"))),
                        "scenarioScreens", array(Map.of("type", "object", "properties", Map.of("scenario", str(),
                                "screen", str()), "required", List.of("scenario", "screen"))),
                        "frictionRisks", strings()),
                "required", List.of("screens", "flows", "scenarioScreens", "frictionRisks"));
    }

    public record Transition(String from, String to, String trigger) { }
    public record Property(String name, Double value) { }
    public record Entity(String id, String name, List<Property> properties) {
        public Entity { properties = properties == null ? List.of() : properties; }
    }
    public record Control(String action, String key, String effect) { }
    public record Rule(String id, String condition, String effect) { }
    public record ScenarioRule(String scenario, String target) { }

    public record GameDesign(@JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> states,
                             List<Transition> transitions, List<Entity> entities, List<Control> controls, List<Rule> rules,
                             @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> win,
                             @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> lose,
                             List<Property> difficulty, List<ScenarioRule> scenarioRules,
                             @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<String> balanceHypotheses) {
        public GameDesign {
            states = states == null ? List.of() : states;
            transitions = transitions == null ? List.of() : transitions;
            entities = entities == null ? List.of() : entities;
            controls = controls == null ? List.of() : controls;
            rules = rules == null ? List.of() : rules;
            win = win == null ? List.of() : win;
            lose = lose == null ? List.of() : lose;
            difficulty = difficulty == null ? List.of() : difficulty;
            scenarioRules = scenarioRules == null ? List.of() : scenarioRules;
            balanceHypotheses = balanceHypotheses == null ? List.of() : balanceHypotheses;
        }

        private static Map<String, Object> property() {
            return Map.of("type", "object", "properties", Map.of("name", str(), "value", Map.of("type", "number")),
                    "required", List.of("name", "value"));
        }

        public static final Map<String, Object> SCHEMA = Map.of("type", "object",
                "properties", Map.of(
                        "states", strings(),
                        "transitions", array(Map.of("type", "object", "properties", Map.of("from", str(), "to", str(),
                                "trigger", str()), "required", List.of("from", "to", "trigger"))),
                        "entities", array(Map.of("type", "object", "properties", Map.of("id", str(), "name", str(),
                                "properties", array(property())), "required", List.of("id", "name", "properties"))),
                        "controls", array(Map.of("type", "object", "properties", Map.of("action", str(), "key", str(),
                                "effect", str()), "required", List.of("action", "key", "effect"))),
                        "rules", array(Map.of("type", "object", "properties", Map.of("id", str(), "condition", str(),
                                "effect", str()), "required", List.of("id", "condition", "effect"))),
                        "win", strings(), "lose", strings(),
                        "difficulty", array(property()),
                        "scenarioRules", array(Map.of("type", "object", "properties", Map.of("scenario", str(),
                                "target", str()), "required", List.of("scenario", "target"))),
                        "balanceHypotheses", strings()),
                "required", List.of("states", "transitions", "entities", "controls", "rules", "win", "lose",
                        "difficulty", "scenarioRules", "balanceHypotheses"));
    }

    static Map<String, Object> str() { return Map.of("type", "string"); }
    static Map<String, Object> strings() { return Map.of("type", "array", "items", str()); }
    static Map<String, Object> array(Map<String, Object> items) { return Map.of("type", "array", "items", items); }
}
```

(`GameDesign.SCHEMA` tiene 10 pares en `properties`: es el máximo de `Map.of`; si se agrega un campo, usar `Map.ofEntries`.)

- [ ] **Step 4: Implementar el validador**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign.*;
import com.aicompany.core.agent.model.RequirementsSpec;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Spec 2026-10-14 Kael §1.3: reglas de Java sobre el diseño de Kael (lista vacía = válido). */
public final class InteractionDesignValidator {

    public static final int MAX_SCREENS = 15;
    public static final int MAX_RULES = 20;
    public static final int MAX_CONTROLS = 8;
    private static final Pattern PANT = Pattern.compile("PANT-\\d{2}");
    private static final Pattern REGLA = Pattern.compile("REGLA-\\d{2}");
    private static final Pattern ACTION = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Set<String> STATES = Set.of("empty", "loading", "error");

    private InteractionDesignValidator() {
    }

    public static List<String> validateApp(AppDesign design, RequirementsSpec spec) {
        if (design == null) {
            return List.of("El diseño de la app está vacío.");
        }
        var errors = new ArrayList<String>();
        var roles = spec.rolesOrEmpty().stream().map(r -> r.name().strip()).collect(Collectors.toSet());
        var screens = new LinkedHashSet<String>();
        if (design.screens().isEmpty()) {
            errors.add("screens debe tener al menos una pantalla PANT-01.");
        }
        if (design.screens().size() > MAX_SCREENS) {
            errors.add("Hay " + design.screens().size() + " pantallas: máximo " + MAX_SCREENS + " (diseña el MVP).");
        }
        for (var s : design.screens()) {
            if (s.id() == null || !PANT.matcher(s.id()).matches()) {
                errors.add("El id de pantalla \"" + s.id() + "\" no tiene el formato PANT-01.");
            } else if (!screens.add(s.id())) {
                errors.add("La pantalla " + s.id() + " está repetida.");
            }
            if (s.role() == null || !roles.contains(s.role().strip())) {
                errors.add(s.id() + ": el rol \"" + s.role() + "\" no está en los roles de Aria " + roles + ".");
            }
            s.states().stream().filter(st -> !STATES.contains(st)).forEach(st ->
                    errors.add(s.id() + ": el estado \"" + st + "\" no es empty, loading ni error."));
        }
        var stories = spec.userStoriesOrEmpty().stream().map(RequirementsSpec.UserStory::id).toList();
        var withFlow = new HashSet<String>();
        for (var f : design.flows()) {
            if (!stories.contains(f.story())) {
                errors.add("Hay un flujo para " + f.story() + ", que no existe en las HU " + stories + ".");
            }
            withFlow.add(f.story());
            f.screens().stream().filter(p -> !screens.contains(p)).forEach(p ->
                    errors.add("El flujo de " + f.story() + " usa " + p + ", que no está en screens."));
        }
        stories.stream().filter(id -> !withFlow.contains(id)).forEach(id ->
                errors.add(id + " no tiene flujo: agrega uno en flows con story \"" + id + "\"."));
        var targets = new LinkedHashMap<String, String>();
        design.scenarioScreens().forEach(m -> targets.put(m.scenario(), m.screen()));
        checkScenarios(spec, targets, screens::contains, "screen", errors);
        if (design.frictionRisks().stream().allMatch(r -> r == null || r.isBlank())) {
            errors.add("frictionRisks necesita al menos un riesgo de fricción con su hipótesis y mitigación.");
        }
        return errors;
    }

    public static List<String> validateGame(GameDesign design, RequirementsSpec spec) {
        if (design == null) {
            return List.of("El diseño del juego está vacío.");
        }
        var errors = new ArrayList<String>();
        var states = new HashSet<>(design.states());
        for (var t : design.transitions()) {
            for (var end : new String[]{t.from(), t.to()}) {
                if (!states.contains(end)) {
                    errors.add("La transición " + t.from() + " → " + t.to() + " usa el estado \"" + end
                            + "\", que no está en states " + design.states() + ".");
                }
            }
        }
        for (var e : design.entities()) {
            e.properties().stream().filter(p -> p.value() == null).forEach(p -> errors.add(e.id() + ": la propiedad \""
                    + p.name() + "\" necesita un número en value (p. ej. 300 para una velocidad en píxeles/segundo)."));
        }
        design.difficulty().stream().filter(p -> p.value() == null).forEach(p ->
                errors.add("difficulty: \"" + p.name() + "\" necesita un número en value."));
        if (design.controls().size() > MAX_CONTROLS) {
            errors.add("Hay " + design.controls().size() + " controles: máximo " + MAX_CONTROLS + ".");
        }
        if (design.rules().size() > MAX_RULES) {
            errors.add("Hay " + design.rules().size() + " reglas: máximo " + MAX_RULES + ".");
        }
        var rules = new LinkedHashSet<String>();
        for (var r : design.rules()) {
            if (r.id() == null || !REGLA.matcher(r.id()).matches()) {
                errors.add("El id de regla \"" + r.id() + "\" no tiene el formato REGLA-01.");
            } else if (!rules.add(r.id())) {
                errors.add("La regla " + r.id() + " está repetida.");
            }
        }
        var ruleText = design.rules().stream().map(r -> r.condition() + " " + r.effect()).collect(Collectors.joining(" "));
        var actions = new LinkedHashSet<String>();
        for (var c : design.controls()) {
            if (c.action() == null || !ACTION.matcher(c.action()).matches()) {
                errors.add("La acción \"" + c.action() + "\" debe ser un nombre de acción de Godot en minúsculas con "
                        + "guiones bajos (p. ej. move_left).");
                continue;
            }
            actions.add(c.action());
            if (!ruleText.contains(c.action())) {
                errors.add("El control " + c.action() + " no se usa en ninguna regla: nómbralo en la condition de la "
                        + "regla que dispara, o quítalo.");
            }
        }
        if (design.win().isEmpty() && design.lose().isEmpty()) {
            errors.add("Define al menos una condición de win o de lose (REGLA-NN).");
        }
        for (var ref : concat(design.win(), design.lose())) {
            if (!rules.contains(ref)) {
                errors.add("win/lose usa " + ref + ", que no está en rules.");
            }
        }
        var targets = new LinkedHashMap<String, String>();
        design.scenarioRules().forEach(m -> targets.put(m.scenario(), m.target()));
        checkScenarios(spec, targets, t -> rules.contains(t) || actions.contains(t), "target (REGLA-NN o una action)",
                errors);
        if (design.balanceHypotheses().stream().allMatch(h -> h == null || h.isBlank())) {
            errors.add("balanceHypotheses necesita al menos una hipótesis de balance.");
        }
        return errors;
    }

    /** Todo escenario del spec asignado una vez, a un destino existente; ningún escenario inventado. */
    private static void checkScenarios(RequirementsSpec spec, Map<String, String> targets,
                                       java.util.function.Predicate<String> exists, String what, List<String> errors) {
        var known = spec.userStoriesOrEmpty().stream().flatMap(s -> s.scenariosOrEmpty().stream())
                .map(RequirementsSpec.Scenario::id).toList();
        targets.forEach((scenario, target) -> {
            if (!known.contains(scenario)) {
                errors.add("El escenario " + scenario + " no existe en las HU de Aria " + known + ".");
            } else if (target == null || !exists.test(target)) {
                errors.add(scenario + ": el " + what + " \"" + target + "\" no existe en el diseño.");
            }
        });
        known.stream().filter(id -> !targets.containsKey(id)).forEach(id ->
                errors.add(id + " no está asignado: cada escenario de Aria va a un " + what + "."));
    }

    private static List<String> concat(List<String> a, List<String> b) {
        var out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
```

- [ ] **Step 5: Correr y ver que pasa**

Run: `cd app && mvn test -Dtest=InteractionDesignValidatorTest` → PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src && git commit -m "Kael: modelo y validador del diseño de interacción (apps) y de juego

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Versiones del diseño en Neo4j y texto para prompts/chat

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/InteractionDesignMemoryService.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/InteractionDesignText.java`
- Modify: `app/src/main/java/com/aicompany/core/service/RequirementsMemoryService.java` (`releaseDecision`)
- Test: `app/src/test/java/com/aicompany/core/agent/validation/InteractionDesignTextTest.java`

**Interfaces:**
- Produces:
  - `InteractionDesignMemoryService.Version(int version, String designJson, String status, int specVersion, String instruction)`.
  - `int saveDraft(String missionId, String designJson, int specVersion, String instruction)` (pasa a `SUPERSEDED` el DRAFT anterior).
  - `Optional<Version> latest(String missionId)`, `Optional<Version> approved(String missionId)`.
  - `void approve(String missionId, int version, String by)`, `void reject(String missionId, int version)`, `void supersedeDraft(String missionId)`.
  - `void setPendingAdjustment(String missionId, int version, String adjustment)`, `Optional<String> pendingAdjustment(String missionId)` (sobre el DRAFT).
  - `RequirementsMemoryService.releaseDecision(String missionId, int version)` → `REMOVE r.decisionClaimedAt, r.pendingAdjustment`.
  - `InteractionDesignText.incrementBlock(InteractionDesign, RequirementsSpec, int increment) → String`; `summary(InteractionDesign) → String`; `scenarioTargets(InteractionDesign) → Map<String,String>` (ESC → PANT/REGLA/acción).

- [ ] **Step 1: Tests que fallan (texto)**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.InteractionDesign.*;
import com.aicompany.core.agent.model.RequirementsSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InteractionDesignTextTest {

    static final RequirementsSpec SPEC = new RequirementsSpec("x", List.of(), List.of(),
            List.of(new RequirementsSpec.Role("vendedor", "d")),
            List.of(new RequirementsSpec.UserStory("HU-01", "vendedor", "q", "p", List.of("c"), List.of(),
                            List.of(new RequirementsSpec.Scenario("ESC-01", "a", List.of("d"), List.of("c"), List.of("e")))),
                    new RequirementsSpec.UserStory("HU-02", "vendedor", "q2", "p", List.of("c"), List.of(),
                            List.of(new RequirementsSpec.Scenario("ESC-02", "b", List.of("d"), List.of("c"), List.of("e"))))),
            List.of(new RequirementsSpec.Increment(1, "MVP", List.of("HU-01")),
                    new RequirementsSpec.Increment(2, "más", List.of("HU-02"))), "APP");

    @Test void theAppBlockOnlyCarriesTheScreensOfTheIncrement() {
        var design = new InteractionDesign("APP", new AppDesign(
                List.of(new Screen("PANT-01", "Lista", "ver", "vendedor", List.of()),
                        new Screen("PANT-02", "Detalle", "ver", "vendedor", List.of())),
                List.of(new Flow("HU-01", List.of("PANT-01"), List.of("abrir")),
                        new Flow("HU-02", List.of("PANT-02"), List.of("tocar"))),
                List.of(new ScenarioScreen("ESC-01", "PANT-01"), new ScenarioScreen("ESC-02", "PANT-02")),
                List.of("r")), null);
        var block = InteractionDesignText.incrementBlock(design, SPEC, 1);
        assertTrue(block.contains("PANT-01") && block.contains("Key('PANT-01')"), block);
        assertFalse(block.contains("PANT-02"), block);
    }

    @Test void theGameBlockCarriesControlsEntitiesAndTheRulesOfTheIncrement() {
        var design = new InteractionDesign("GAME", null, new GameDesign(List.of("PLAYING", "GAME_OVER"),
                List.of(new Transition("PLAYING", "GAME_OVER", "REGLA-02")),
                List.of(new Entity("pelota", "Pelota", List.of(new Property("velocidad", 300.0)))),
                List.of(new Control("move_right", "Right", "mueve")),
                List.of(new Rule("REGLA-01", "move_right", "avanza"), new Rule("REGLA-02", "pasa", "pierde"),
                        new Rule("REGLA-03", "otra", "x")),
                List.of(), List.of("REGLA-02"), List.of(), List.of(new ScenarioRule("ESC-01", "REGLA-01"),
                new ScenarioRule("ESC-02", "REGLA-03")), List.of("h")));
        var block = InteractionDesignText.incrementBlock(design, SPEC, 1);
        assertTrue(block.contains("move_right") && block.contains("300") && block.contains("REGLA-01")
                && block.contains("REGLA-02"), block);   // REGLA-02 por ser de lose
        assertFalse(block.contains("REGLA-03"), block);  // es del incremento 2
    }
}
```

- [ ] **Step 2: Correr y ver que falla** — `cd app && mvn test -Dtest=InteractionDesignTextTest` → FAIL.

- [ ] **Step 3: Implementar `InteractionDesignText`**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.RequirementsSpec;

import java.util.*;
import java.util.stream.Collectors;

/** Spec 2026-10-14 Kael §1.4: el diseño aprobado como texto (Java) para los prompts y el chat. */
public final class InteractionDesignText {

    private InteractionDesignText() {
    }

    public static Map<String, String> scenarioTargets(InteractionDesign design) {
        var out = new LinkedHashMap<String, String>();
        if (design.app() != null) {
            design.app().scenarioScreens().forEach(m -> out.put(m.scenario(), m.screen()));
        }
        if (design.game() != null) {
            design.game().scenarioRules().forEach(m -> out.put(m.scenario(), m.target()));
        }
        return out;
    }

    static Set<String> scenariosOf(RequirementsSpec spec, int increment) {
        return spec.storiesOf(increment).stream().flatMap(s -> s.scenariosOrEmpty().stream())
                .map(RequirementsSpec.Scenario::id).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Pantallas de las HU del incremento (por sus flujos), en orden. */
    public static Set<String> screensOf(InteractionDesign design, RequirementsSpec spec, int increment) {
        var stories = spec.storiesOf(increment).stream().map(RequirementsSpec.UserStory::id).toList();
        return design.app() == null ? Set.of() : design.app().flows().stream()
                .filter(f -> stories.contains(f.story())).flatMap(f -> f.screens().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static String incrementBlock(InteractionDesign design, RequirementsSpec spec, int increment) {
        var scenarios = scenariosOf(spec, increment);
        var targets = scenarioTargets(design);
        if (design.app() != null) {
            var stories = spec.storiesOf(increment).stream().map(RequirementsSpec.UserStory::id).toList();
            var screens = screensOf(design, spec, increment);
            return """
                    DISEÑO DE INTERACCIÓN APROBADO (Kael): INCREMENTO %d
                    Pantallas (cada una lleva Key('PANT-NN') en su widget raíz; Forjai lo verifica):
                    %s
                    Flujos:
                    %s
                    Escenarios → pantalla: %s
                    """.formatted(increment,
                    design.app().screens().stream().filter(s -> screens.contains(s.id()))
                            .map(s -> "- " + s.id() + " " + s.name() + " (" + s.role() + "): " + s.purpose()
                                    + (s.states().isEmpty() ? "" : " — estados: " + String.join(", ", s.states()))
                                    + " → Key('" + s.id() + "')")
                            .collect(Collectors.joining("\n")),
                    design.app().flows().stream().filter(f -> stories.contains(f.story()))
                            .map(f -> "- " + f.story() + ": " + String.join(" → ", f.screens())
                                    + (f.actions().isEmpty() ? "" : " (" + String.join("; ", f.actions()) + ")"))
                            .collect(Collectors.joining("\n")),
                    scenarios.stream().map(id -> id + "→" + targets.get(id)).collect(Collectors.joining(", ")));
        }
        var game = design.game();
        var ruleIds = new LinkedHashSet<String>();
        scenarios.forEach(id -> Optional.ofNullable(targets.get(id)).filter(t -> t.startsWith("REGLA-")).ifPresent(ruleIds::add));
        ruleIds.addAll(game.win());
        ruleIds.addAll(game.lose());
        return """
                DISEÑO DE JUEGO APROBADO (Kael): INCREMENTO %d
                Estados: %s
                Transiciones: %s
                Entidades: %s
                Controles (acciones de input de Godot; decláralas en [input] de game/project.godot):
                %s
                Reglas:
                %s
                Gana: %s · Pierde: %s
                Dificultad: %s
                Escenarios → regla o control: %s
                Sin assets externos: dibuja con nodos (ColorRect, Polygon2D, Label) o en _Draw().
                """.formatted(increment, String.join(", ", game.states()),
                game.transitions().stream().map(t -> t.from() + "→" + t.to() + " (" + t.trigger() + ")")
                        .collect(Collectors.joining(", ")),
                game.entities().stream().map(e -> e.id() + " " + e.properties().stream()
                        .map(p -> p.name() + "=" + num(p.value())).collect(Collectors.joining(", ", "{", "}")))
                        .collect(Collectors.joining("; ")),
                game.controls().stream().map(c -> "- " + c.action() + " [" + c.key() + "]: " + c.effect())
                        .collect(Collectors.joining("\n")),
                game.rules().stream().filter(r -> ruleIds.contains(r.id()))
                        .map(r -> "- " + r.id() + ": SI " + r.condition() + " ENTONCES " + r.effect())
                        .collect(Collectors.joining("\n")),
                String.join(", ", game.win()), String.join(", ", game.lose()),
                game.difficulty().stream().map(p -> p.name() + "=" + num(p.value())).collect(Collectors.joining(", ")),
                scenarios.stream().map(id -> id + "→" + targets.get(id)).collect(Collectors.joining(", ")));
    }

    public static String summary(InteractionDesign design) {
        if (design.app() != null) {
            return "Pantallas: " + design.app().screens().stream().map(s -> s.id() + " " + s.name())
                    .collect(Collectors.joining(", ")) + ". Flujos: " + design.app().flows().stream()
                    .map(f -> f.story() + " " + String.join("→", f.screens())).collect(Collectors.joining("; "))
                    + ". Riesgos (hipótesis): " + String.join("; ", design.app().frictionRisks()) + ".";
        }
        var g = design.game();
        return "Loop: " + String.join("→", g.states()) + ". Controles: " + g.controls().stream()
                .map(c -> c.action() + " [" + c.key() + "]").collect(Collectors.joining(", ")) + ". Reglas: "
                + g.rules().stream().map(r -> r.id() + " " + r.effect()).collect(Collectors.joining("; "))
                + ". Pierde: " + String.join(", ", g.lose()) + ". Balance (hipótesis): "
                + String.join("; ", g.balanceHypotheses()) + ".";
    }

    private static String num(Double v) {
        return v == null ? "?" : v == Math.rint(v) ? String.valueOf(v.longValue()) : String.valueOf(v);
    }
}
```

- [ ] **Step 4: Implementar `InteractionDesignMemoryService`**

Mismo estilo que `RequirementsMemoryService` (Driver plano, `executeWrite`):

```java
package com.aicompany.core.service;

import org.neo4j.driver.Driver;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Spec 2026-10-14 Kael §1.3: versiones del diseño de Kael ((:Mission)-[:HAS_INTERACTION_DESIGN]->). */
@Service
public class InteractionDesignMemoryService {

    public record Version(int version, String designJson, String status, int specVersion, String instruction) {
    }

    private final Driver driver;

    public InteractionDesignMemoryService(Driver driver) {
        this.driver = driver;
    }

    public int saveDraft(String missionId, String designJson, int specVersion, String instruction) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> {
                var next = tx.run("MATCH (m:Mission {id:$id}) OPTIONAL MATCH (m)-[:HAS_INTERACTION_DESIGN]->(d) "
                        + "RETURN coalesce(max(d.version), 0) + 1 AS v", Map.of("id", missionId)).single().get("v").asInt();
                tx.run("MATCH (:Mission {id:$id})-[:HAS_INTERACTION_DESIGN]->(d:InteractionDesignVersion {status:'DRAFT'}) "
                        + "SET d.status = 'SUPERSEDED'", Map.of("id", missionId));
                tx.run("MATCH (m:Mission {id:$id}) CREATE (m)-[:HAS_INTERACTION_DESIGN]->(:InteractionDesignVersion "
                                + "{version:$v, designJson:$json, status:'DRAFT', specVersion:$spec, instruction:$i, "
                                + "createdAt:datetime()})",
                        Map.of("id", missionId, "v", next, "json", designJson, "spec", specVersion,
                                "i", instruction == null ? "" : instruction));
                return next;
            });
        }
    }

    public Optional<Version> latest(String missionId) {
        return query(missionId, "");
    }

    public Optional<Version> approved(String missionId) {
        return query(missionId, " AND d.status = 'APPROVED'");
    }

    private Optional<Version> query(String missionId, String filter) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_INTERACTION_DESIGN]->(d:InteractionDesignVersion) "
                            + "WHERE true" + filter + " RETURN d.version AS v, d.designJson AS j, d.status AS s, "
                            + "d.specVersion AS sv, d.instruction AS i ORDER BY d.version DESC LIMIT 1",
                    Map.of("id", missionId)).list(r -> new Version(r.get("v").asInt(), r.get("j").asString(),
                    r.get("s").asString(), r.get("sv").asInt(), r.get("i").isNull() ? "" : r.get("i").asString()))
                    .stream().findFirst();
        }
    }

    public void approve(String missionId, int version, String by) {
        write("MATCH (:Mission {id:$id})-[:HAS_INTERACTION_DESIGN]->(d:InteractionDesignVersion {version:$v}) "
                + "SET d.status='APPROVED', d.approvedBy=$by, d.approvedAt=datetime() REMOVE d.pendingAdjustment",
                Map.of("id", missionId, "v", version, "by", by));
    }

    public void reject(String missionId, int version) {
        write("MATCH (:Mission {id:$id})-[:HAS_INTERACTION_DESIGN]->(d:InteractionDesignVersion {version:$v}) "
                + "SET d.status='REJECTED'", Map.of("id", missionId, "v", version));
    }

    /** Spec §1.2: un ajuste a Aria deja colgando el diseño anterior. */
    public void supersedeDraft(String missionId) {
        write("MATCH (:Mission {id:$id})-[:HAS_INTERACTION_DESIGN]->(d:InteractionDesignVersion {status:'DRAFT'}) "
                + "SET d.status='SUPERSEDED'", Map.of("id", missionId));
    }

    public void setPendingAdjustment(String missionId, int version, String adjustment) {
        var params = new HashMap<String, Object>();
        params.put("id", missionId);
        params.put("v", version);
        params.put("a", adjustment);
        write("MATCH (:Mission {id:$id})-[:HAS_INTERACTION_DESIGN]->(d:InteractionDesignVersion {version:$v}) "
                + "SET d.pendingAdjustment=$a", params);
    }

    public Optional<String> pendingAdjustment(String missionId) {
        try (var session = driver.session()) {
            return session.run("MATCH (:Mission {id:$id})-[:HAS_INTERACTION_DESIGN]->(d:InteractionDesignVersion "
                            + "{status:'DRAFT'}) WHERE d.pendingAdjustment IS NOT NULL RETURN d.pendingAdjustment AS a "
                            + "ORDER BY d.version DESC LIMIT 1", Map.of("id", missionId))
                    .list(r -> r.get("a").asString()).stream().findFirst();
        }
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

`RequirementsMemoryService`:

```java
/** Spec 2026-10-14 Kael (Review Focus 2): tras un ajuste a Kael, la versión de HU vuelve a poder decidirse. */
public void releaseDecision(String missionId, int version) {
    write("MATCH (:Mission {id:$id})-[:HAS_REQUIREMENTS]->(r:RequirementsVersion {version:$v}) "
            + "REMOVE r.decisionClaimedAt, r.pendingAdjustment", Map.of("id", missionId, "v", version));
}
```

(Los `*MemoryService` no tienen test directo — convención del repo; se cubren mockeados en Tasks 5-6.)

- [ ] **Step 5: Correr y ver que pasa** — `cd app && mvn test -Dtest=InteractionDesignTextTest` → PASS; `mvn -q -DskipTests package` compila.

- [ ] **Step 6: Commit**

```bash
git add app/src && git commit -m "Kael: versiones del diseño en Neo4j y su texto para prompts y chat

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `InteractionDesignWriter` (la tarea de Kael)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/InteractionDesignWriter.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java` (dos métodos junto a `writeRequirements`)
- Test: `app/src/test/java/com/aicompany/core/service/InteractionDesignWriterTest.java`

**Interfaces:**
- Consumes: Task 2 (`InteractionDesign`, validador), Task 3 (`InteractionDesignMemoryService`).
- Produces:
  - `CeoService.writeAppDesign(String agentId, String prompt, String agentPrompt, String model) → InteractionDesign.AppDesign` (kind `"APP_DESIGN"`); `writeGameDesign(...) → InteractionDesign.GameDesign` (kind `"GAME_DESIGN"`).
  - `InteractionDesignWriter.KAEL = "interaction-design"`; `record Written(int version, InteractionDesign design)`.
  - `Written write(String missionId, RequirementsSpec spec, int specVersion, Optional<InteractionDesign> previous, String adjustment)`; lanza `IllegalStateException("Kael está apagado: …")` si `AgentAvailability.isEnabled(KAEL)` es false; lanza `IllegalStateException` si `spec.experienceOrNull()` no es `APP`/`GAME`.
  - Tarea `<misión>-INTERACTION` (`-V<n>` si n > 1), action `INTERACTION_DESIGN`, kind `DESIGN`.

- [ ] **Step 1: Tests que fallan**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.InteractionDesign.*;
import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.event.CompanyEventPublisher;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InteractionDesignWriterTest {

    private final CeoService ceo = mock(CeoService.class);
    private final MissionMemoryService missions = mock(MissionMemoryService.class);
    private final InteractionDesignMemoryService designs = mock(InteractionDesignMemoryService.class);
    private final CompanyMemoryService company = mock(CompanyMemoryService.class);
    private final PromptMemoryService prompts = mock(PromptMemoryService.class);
    private final AgentAvailability availability = mock(AgentAvailability.class);
    private final InteractionDesignWriter writer = new InteractionDesignWriter(ceo, missions, designs, company, prompts,
            availability, mock(CompanyEventPublisher.class), JsonMapper.builder().build(), "qwen3:8b");

    static final RequirementsSpec SPEC = new RequirementsSpec("CRM", List.of(), List.of(),
            List.of(new RequirementsSpec.Role("vendedor", "d")),
            List.of(new RequirementsSpec.UserStory("HU-01", "vendedor", "q", "p", List.of("c"), List.of(),
                    List.of(new RequirementsSpec.Scenario("ESC-01", "a", List.of("d"), List.of("c"), List.of("e"))))),
            List.of(new RequirementsSpec.Increment(1, "MVP", List.of("HU-01"))), "APP");

    static final AppDesign VALID = new AppDesign(List.of(new Screen("PANT-01", "Lista", "ver", "vendedor", List.of())),
            List.of(new Flow("HU-01", List.of("PANT-01"), List.of())), List.of(new ScenarioScreen("ESC-01", "PANT-01")),
            List.of("r"));
    static final AppDesign INVALID = new AppDesign(List.of(), List.of(), List.of(), List.of());

    {
        when(availability.isEnabled("interaction-design")).thenReturn(true);
        when(company.agentModel(eq("interaction-design"), anyString())).thenReturn("nvidia:x/y");
        when(prompts.activePrompt("interaction-design")).thenReturn("");
        when(designs.saveDraft(anyString(), anyString(), anyInt(), any())).thenReturn(1);
    }

    @Test void anInvalidDesignIsRetriedWithTheCorrection() {
        when(ceo.writeAppDesign(anyString(), anyString(), anyString(), anyString())).thenReturn(INVALID, VALID);
        var written = writer.write("M-1", SPEC, 3, Optional.empty(), null);
        assertEquals(1, written.version());
        verify(ceo).writeAppDesign(eq("interaction-design"), contains("CORRECCIÓN DEL INTENTO ANTERIOR"), anyString(), anyString());
        verify(designs).saveDraft(eq("M-1"), anyString(), eq(3), isNull());
        verify(missions).createTask("M-1-INTERACTION", "M-1", "interaction-design", "INTERACTION_DESIGN", "DESIGN");
    }

    @Test void threeInvalidDesignsFail() {
        when(ceo.writeAppDesign(anyString(), anyString(), anyString(), anyString())).thenReturn(INVALID);
        assertThrows(IllegalStateException.class, () -> writer.write("M-1", SPEC, 1, Optional.empty(), null));
        verify(designs, never()).saveDraft(anyString(), anyString(), anyInt(), any());
    }

    @Test void kaelOffFailsWithoutCallingTheModel() {
        when(availability.isEnabled("interaction-design")).thenReturn(false);
        var ex = assertThrows(IllegalStateException.class, () -> writer.write("M-1", SPEC, 1, Optional.empty(), null));
        assertTrue(ex.getMessage().contains("Kael está apagado"));
        verifyNoInteractions(ceo);
    }

    @Test void anAdjustmentSendsThePreviousDesignAndUsesAVersionedTask() {
        when(designs.latest("M-1")).thenReturn(Optional.of(new InteractionDesignMemoryService.Version(1, "{}", "DRAFT", 1, "")));
        when(ceo.writeAppDesign(anyString(), anyString(), anyString(), anyString())).thenReturn(VALID);
        writer.write("M-1", SPEC, 1, Optional.of(new InteractionDesign("APP", VALID, null)), "lista con buscador");
        verify(ceo).writeAppDesign(anyString(), argThat(p -> p.contains("lista con buscador") && p.contains("PANT-01")),
                anyString(), anyString());
        verify(missions).createTask("M-1-INTERACTION-V2", "M-1", "interaction-design", "INTERACTION_DESIGN", "DESIGN");
    }
}
```

- [ ] **Step 2: Correr y ver que falla** — `cd app && mvn test -Dtest=InteractionDesignWriterTest` → FAIL.

- [ ] **Step 3: `CeoService`** (junto a `writeRequirements`, línea ~1256):

```java
/** Spec 2026-10-14 Kael §1.3: diseño de interacción de una app. format sin tools. */
public com.aicompany.core.agent.model.InteractionDesign.AppDesign writeAppDesign(String agentId, String prompt,
                                                                                String agentPrompt, String model) {
    return callStructured("APP_DESIGN", agentId, prompt, agentPrompt, model,
            com.aicompany.core.agent.model.InteractionDesign.AppDesign.SCHEMA,
            com.aicompany.core.agent.model.InteractionDesign.AppDesign.class);
}

/** Spec 2026-10-14 Kael §1.3: diseño de un juego. format sin tools. */
public com.aicompany.core.agent.model.InteractionDesign.GameDesign writeGameDesign(String agentId, String prompt,
                                                                                  String agentPrompt, String model) {
    return callStructured("GAME_DESIGN", agentId, prompt, agentPrompt, model,
            com.aicompany.core.agent.model.InteractionDesign.GameDesign.SCHEMA,
            com.aicompany.core.agent.model.InteractionDesign.GameDesign.class);
}
```

- [ ] **Step 4: `InteractionDesignWriter`**

Misma estructura que `RequirementsWriter` (copiar el bucle de 3 intentos con `CORRECCIÓN DEL INTENTO ANTERIOR` y "Así entendió Java…"). Diferencias:

```java
@Service
public class InteractionDesignWriter {
    public static final String KAEL = "interaction-design";
    static final int MAX_ATTEMPTS = 3;
    public record Written(int version, InteractionDesign design) { }

    // constructor: CeoService ceo, MissionMemoryService missions, InteractionDesignMemoryService designs,
    // CompanyMemoryService company, PromptMemoryService prompts, AgentAvailability availability,
    // CompanyEventPublisher events, JsonMapper json, @Value("${ollama.agent-model}") String defaultModel

    public Written write(String missionId, RequirementsSpec spec, int specVersion, Optional<InteractionDesign> previous,
                         String adjustment) {
        var experience = spec.experienceOrNull();
        if (experience != RequirementsSpec.Experience.APP && experience != RequirementsSpec.Experience.GAME) {
            throw new IllegalStateException("MISSION " + missionId + " no necesita diseño de Kael (experiencia "
                    + experience + ").");
        }
        if (!availability.isEnabled(KAEL)) {
            throw new IllegalStateException("Kael está apagado: la misión " + missionId + " es " + experience
                    + " y necesita su diseño. Enciéndelo en el Command Center.");
        }
        var nextVersion = designs.latest(missionId).map(v -> v.version() + 1).orElse(1);
        var taskId = missionId + "-INTERACTION" + (nextVersion > 1 ? "-V" + nextVersion : "");
        missions.createTask(taskId, missionId, KAEL, "INTERACTION_DESIGN", "DESIGN");
        missions.updateTask(taskId, "RUNNING", "Kael está diseñando la " + (experience == RequirementsSpec.Experience.GAME
                ? "mecánica del juego." : "interacción de la app."));
        missions.setAgentStatus(KAEL, "WORKING");
        try {
            // bucle idéntico a RequirementsWriter: por intento,
            //   APP  → var app = ceo.writeAppDesign(KAEL, prompt, agentPrompt, model);
            //          errors = InteractionDesignValidator.validateApp(app, spec); design = new InteractionDesign("APP", app, null)
            //   GAME → var game = ceo.writeGameDesign(...); errors = validateGame(game, spec);
            //          design = new InteractionDesign("GAME", null, game)
            // válido → var designJson = json.writeValueAsString(design);
            //          var version = designs.saveDraft(missionId, designJson, specVersion, adjustment);
            //          missions.updateTask(taskId, "COMPLETED", designJson); return new Written(version, design);
            // agotado → updateTask FAILED con "Kael no produjo un diseño válido después de 3 intentos:\n" + feedback; throw
        } finally {
            missions.setAgentStatus(KAEL, "IDLE");
        }
    }
}
```

Prompt base (Java, armado como en Aria; `RequirementsText.incrementBlock` de **todos** los incrementos concatenados como contexto):

```java
private String prompt(RequirementsSpec spec, RequirementsSpec.Experience experience,
                      Optional<InteractionDesign> previous, String adjustment) {
    var allStories = spec.incrementNumbers().stream().map(n -> RequirementsText.incrementBlock(spec, n))
            .collect(java.util.stream.Collectors.joining("\n"));
    var rules = experience == RequirementsSpec.Experience.APP ? """
            Diseña la INTERACCIÓN de la app (no el código):
            - screens: PANT-01, PANT-02… (máximo 15) con name, purpose, role (un rol de las HU) y states (solo
              "empty", "loading", "error" cuando apliquen).
            - flows: uno por cada HU (story: "HU-NN"), con la secuencia de pantallas y las acciones del usuario.
            - scenarioScreens: CADA escenario ESC-NN de las HU → la pantalla donde ocurre.
            - frictionRisks: riesgos de fricción o engagement, cada uno como hipótesis + mitigación.
            Responde SOLO este JSON: {"screens":[{"id":"PANT-01","name":"...","purpose":"...","role":"...","states":["empty"]}],
             "flows":[{"story":"HU-01","screens":["PANT-01"],"actions":["..."]}],
             "scenarioScreens":[{"scenario":"ESC-01","screen":"PANT-01"}],"frictionRisks":["..."]}
            """ : """
            Diseña la MECÁNICA del juego 2D (no el código). Sin assets externos: todo se dibuja con formas y texto.
            - states del loop (p. ej. START, PLAYING, GAME_OVER) y transitions {from,to,trigger}.
            - entities con properties numéricas (value es un NÚMERO: velocidad en px/s, tamaño en px, vidas).
            - controls: action (nombre de acción de Godot en minúsculas, p. ej. move_left), key y effect (máximo 8).
            - rules: REGLA-01… con condition y effect (máximo 20); cada control aparece en la condition de una regla.
            - win/lose: ids de reglas. difficulty: escalado con números.
            - scenarioRules: CADA escenario ESC-NN → la REGLA-NN o la action que lo cumple.
            - balanceHypotheses: hipótesis de balance (no afirmes que algo es divertido).
            Responde SOLO este JSON: {"states":["START"],"transitions":[{"from":"START","to":"PLAYING","trigger":"..."}],
             "entities":[{"id":"...","name":"...","properties":[{"name":"...","value":1}]}],
             "controls":[{"action":"move_left","key":"Left","effect":"..."}],
             "rules":[{"id":"REGLA-01","condition":"...","effect":"..."}],"win":[],"lose":["REGLA-02"],
             "difficulty":[{"name":"...","value":1.1}],"scenarioRules":[{"scenario":"ESC-01","target":"REGLA-01"}],
             "balanceHypotheses":["..."]}
            """;
    var base = "Eres Kael, Interactive Logic & Product Designer del Development Group de Forjai.\n\n"
            + "HISTORIAS DE USUARIO DE ARIA (todas las del producto):\n" + allStories + "\n" + rules;
    if (previous.isEmpty()) {
        return base;
    }
    return base + "\nDISEÑO ANTERIOR (conserva lo que no contradiga el ajuste ni las HU actuales):\n"
            + json.writeValueAsString(previous.get()) + "\n\nAJUSTE DEL FUNDADOR:\n" + (adjustment == null ? "" : adjustment);
}
```

- [ ] **Step 5: Correr y ver que pasa** — `cd app && mvn test -Dtest=InteractionDesignWriterTest` → PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src && git commit -m "Kael escribe el diseño de interacción o de juego; Java lo valida con hasta 3 intentos

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Flujo de la misión — Aria → Kael → aprobación conjunta, retome

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java` (bloque Aria, líneas ~222-235, ~430-485, ~600-625, ~826-857)
- Modify: `app/src/main/java/com/aicompany/core/recovery/ResumePoint.java`, `MissionSnapshot.java`, `MissionRecoveryService.java` (`snapshot`, ~línea 405)
- Test: `app/src/test/java/com/aicompany/core/service/MissionExecutorTeamTest.java`, `app/src/test/java/com/aicompany/core/recovery/ResumePointTest.java`

**Interfaces:**
- Consumes: Task 4 `InteractionDesignWriter.write(...)`, Task 3 memory service.
- Produces:
  - `MissionExecutor.setInteractionDesign(InteractionDesignWriter, InteractionDesignMemoryService)` (`@Autowired(required=false)`).
  - `MissionExecutor.adjustDesignAsync(String missionId, String adjustment) → CompletableFuture<Void>`.
  - `ResumePoint.Stage.DESIGN` y `Stage.DESIGN_ADJUSTMENT`.
  - `MissionSnapshot` con 3 campos nuevos al final: `boolean designNeeded`, `boolean designForLatestSpec`, `String pendingDesignAdjustment`; los constructores anteriores delegan con `false, false, null`.
  - Bloque de diseño en la instrucción de Neo/equipo: `withIncrement(spec, design, increment, instruction)` (sobrecarga con `InteractionDesign design` nullable).

- [ ] **Step 1: Tests que fallan (ResumePoint)**

```java
@Test void aDraftThatNeedsADesignWithoutOneGoesToKael() {
    var s = new MissionSnapshot("M-1", "TEAM-DEVELOPMENT", 0, null, false, "DRAFT", false, null, false, List.of(), null,
            true, false, null);
    assertEquals(ResumePoint.Stage.DESIGN, ResumePoint.of(s).stage());
}
@Test void aClaimedDesignAdjustmentIsRepeated() {
    var s = new MissionSnapshot("M-1", "TEAM-DEVELOPMENT", 0, null, false, "DRAFT", false, null, false, List.of(), null,
            true, true, "más grande la paleta");
    assertEquals(ResumePoint.Stage.DESIGN_ADJUSTMENT, ResumePoint.of(s).stage());
}
@Test void aDraftWithItsDesignAwaitsApproval() {
    var s = new MissionSnapshot("M-1", "TEAM-DEVELOPMENT", 0, null, false, "DRAFT", false, null, false, List.of(), null,
            true, true, null);
    assertEquals(ResumePoint.Stage.AWAIT_APPROVAL, ResumePoint.of(s).stage());
}
// Review Focus 3: un spec sin experience (o NONE) no necesita a Kael.
@Test void aLegacyDraftAwaitsApprovalWithoutKael() {
    assertEquals(ResumePoint.Stage.AWAIT_APPROVAL, ResumePoint.of(dev(0, null, false, "DRAFT", false, null, false,
            List.of())).stage());
}
```

- [ ] **Step 2: Tests que fallan (executor)** en `MissionExecutorTeamTest`, junto a `withAria()`:

```java
private final InteractionDesignWriter designWriter = mock(InteractionDesignWriter.class);
private final InteractionDesignMemoryService designMemory = mock(InteractionDesignMemoryService.class);

static final com.aicompany.core.agent.model.RequirementsSpec GAME_SPEC = /* SPEC con experience "GAME" (constructor de 7 args) */;
static final com.aicompany.core.agent.model.InteractionDesign DESIGN = new com.aicompany.core.agent.model.InteractionDesign(
        "GAME", null, new com.aicompany.core.agent.model.InteractionDesign.GameDesign(List.of("PLAYING"), List.of(),
        List.of(), List.of(new com.aicompany.core.agent.model.InteractionDesign.Control("move_right", "Right", "x")),
        List.of(new com.aicompany.core.agent.model.InteractionDesign.Rule("REGLA-01", "move_right", "y")),
        List.of(), List.of("REGLA-01"), List.of(), List.of(), List.of("h")));

private void withKael() throws Exception {
    withAria();
    executor.setInteractionDesign(designWriter, designMemory);
    when(writer.write(eq("M-9"), eq("Un CRM"), eq(Optional.empty()), isNull()))
            .thenReturn(new RequirementsWriter.Written(1, GAME_SPEC));
    when(designWriter.write(eq("M-9"), eq(GAME_SPEC), eq(1), eq(Optional.empty()), isNull()))
            .thenReturn(new InteractionDesignWriter.Written(1, DESIGN));
}

@Test
void aGameMissionStopsAfterAriaAndKaelWaitingForBoth() throws Exception {
    withKael();
    when(companyPolicyService.activeValue(PolicyKey.SPEC_APPROVAL_REQUIRED)).thenReturn(1.0);
    executor.executeAsync("M-9", "Un CRM").get();
    var order = inOrder(writer, designWriter, memory);
    order.verify(writer).write(any(), any(), any(), any());
    order.verify(designWriter).write(eq("M-9"), eq(GAME_SPEC), eq(1), eq(Optional.empty()), isNull());
    order.verify(memory).updateMission(eq("M-9"), eq(MissionStatus.AWAITING_SPEC_APPROVAL), anyInt(), anyString(),
            argThat(m -> m.contains("Kael")));
    verify(planner, never()).plan(any(), any(), any(), any());
}

@Test
void withThePolicyOffBothAreApprovedAndNeoGetsTheDesign() throws Exception {
    withKael();
    when(companyPolicyService.activeValue(PolicyKey.SPEC_APPROVAL_REQUIRED)).thenReturn(0.0);
    when(designMemory.approved("M-9")).thenReturn(Optional.of(new InteractionDesignMemoryService.Version(1,
            JsonMapper.builder().build().writeValueAsString(DESIGN), "APPROVED", 1, "")));
    when(requirements.approved("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1,
            JsonMapper.builder().build().writeValueAsString(GAME_SPEC), "APPROVED", "", null)));
    when(planner.plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), anyString(), any())).thenReturn(planned("TEAM-DEVELOPMENT"));
    executor.executeAsync("M-9", "Un CRM").get();
    verify(designMemory).approve("M-9", 1, "policy");
    verify(planner).plan(eq("M-9"), eq("TEAM-DEVELOPMENT"), argThat(i -> i.contains("DISEÑO DE JUEGO APROBADO")
            && i.contains("TIPO DE EXPERIENCIA: GAME")), any());
}

// Review Focus 3: un spec APP/GAME no existe en misiones viejas; sin experience, Kael no corre.
@Test
void aSpecWithoutExperienceNeverCallsKael() throws Exception {
    withAria();
    executor.setInteractionDesign(designWriter, designMemory);
    when(companyPolicyService.activeValue(PolicyKey.SPEC_APPROVAL_REQUIRED)).thenReturn(1.0);
    executor.executeAsync("M-9", "Un CRM").get();
    verifyNoInteractions(designWriter);
}

@Test
void adjustingKaelKeepsTheStoriesAndReleasesTheDecision() throws Exception {
    withKael();
    when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1,
            JsonMapper.builder().build().writeValueAsString(GAME_SPEC), "DRAFT", "", null)));
    when(designMemory.latest("M-9")).thenReturn(Optional.of(new InteractionDesignMemoryService.Version(1,
            JsonMapper.builder().build().writeValueAsString(DESIGN), "DRAFT", 1, "")));
    when(designWriter.write(eq("M-9"), eq(GAME_SPEC), eq(1), any(), eq("paleta más grande")))
            .thenReturn(new InteractionDesignWriter.Written(2, DESIGN));
    executor.adjustDesignAsync("M-9", "paleta más grande").get();
    verify(writer, never()).write(any(), any(), any(), any());
    verify(requirements).releaseDecision("M-9", 1);
    verify(memory, atLeastOnce()).updateMission(eq("M-9"), eq(MissionStatus.AWAITING_SPEC_APPROVAL), anyInt(), anyString(), anyString());
}
```

- [ ] **Step 3: Correr y ver que fallan**

Run: `cd app && mvn test -Dtest=ResumePointTest,MissionExecutorTeamTest` → FAIL (no compila).

- [ ] **Step 4: `MissionSnapshot` y `ResumePoint`**

`MissionSnapshot`: agregar `boolean designNeeded, boolean designForLatestSpec, String pendingDesignAdjustment` al final del canónico; el constructor de 11 args delega con `false, false, null` y el de 10 sigue delegando en el de 11.

`ResumePoint.Stage`: agregar `DESIGN, DESIGN_ADJUSTMENT`. En `of`, dentro de `if (!s.requirementsApproved())`, después del chequeo de `pendingAdjustment()` de Aria:

```java
if ("DRAFT".equals(s.latestRequirementsStatus()) && s.designNeeded()) {
    if (s.pendingDesignAdjustment() != null) {
        return new ResumePoint(Stage.DESIGN_ADJUSTMENT, s.round(), kind, increment);
    }
    if (!s.designForLatestSpec()) {
        return new ResumePoint(Stage.DESIGN, s.round(), kind, increment);
    }
}
```

`MissionRecoveryService.snapshot`: inyectar `InteractionDesignMemoryService designs` (opcional, por setter `@Autowired(required=false)`; null → `false,false,null`) y `JsonMapper` (ya existe en la clase o agregarlo). Calcular:

```java
var latestSpec = latest.map(v -> json.readValue(v.specJson(), RequirementsSpec.class)).orElse(null);
var experience = latestSpec == null ? null : latestSpec.experienceOrNull();
var designNeeded = experience == RequirementsSpec.Experience.APP || experience == RequirementsSpec.Experience.GAME;
var latestDesign = designs == null ? Optional.<InteractionDesignMemoryService.Version>empty() : designs.latest(missionId);
var designForLatest = latestDesign.filter(d -> "DRAFT".equals(d.status())
        && latest.map(v -> v.version() == d.specVersion()).orElse(false)).isPresent();
var pendingDesign = designs == null ? null : designs.pendingAdjustment(missionId).orElse(null);
```

- [ ] **Step 5: `MissionExecutor`**

Campos y setter junto a `setRequirements`:

```java
private InteractionDesignWriter designWriter;
private InteractionDesignMemoryService designMemory;

/** Spec 2026-10-14 Kael: opcional (sin él, las misiones siguen solo con Aria). */
@org.springframework.beans.factory.annotation.Autowired(required = false)
public void setInteractionDesign(InteractionDesignWriter designWriter, InteractionDesignMemoryService designMemory) {
    this.designWriter = designWriter;
    this.designMemory = designMemory;
}

private boolean needsDesign(com.aicompany.core.agent.model.RequirementsSpec spec) {
    var e = spec.experienceOrNull();
    return designWriter != null && designMemory != null
            && (e == com.aicompany.core.agent.model.RequirementsSpec.Experience.APP
            || e == com.aicompany.core.agent.model.RequirementsSpec.Experience.GAME);
}

/** Kael diseña sobre el borrador de Aria; null si la misión no lo necesita. */
private InteractionDesignWriter.Written design(String missionId, RequirementsWriter.Written written,
                                               java.util.Optional<com.aicompany.core.agent.model.InteractionDesign> previous,
                                               String adjustment) {
    if (!needsDesign(written.spec())) {
        return null;
    }
    advanceMission(missionId, MissionStatus.PLANNING, 6, "Diseño (Kael)",
            "Kael está diseñando " + (written.spec().experienceOrNull()
                    == com.aicompany.core.agent.model.RequirementsSpec.Experience.GAME ? "la mecánica del juego." : "la interacción."));
    var designed = designWriter.write(missionId, written.spec(), written.version(), previous, adjustment);
    events.publish("EMPRESA_INTERACTION_DESIGN_READY", missionId, null, InteractionDesignWriter.KAEL,
            Map.of("version", designed.version(), "specVersion", written.version()));
    return designed;
}
```

En `executeTeamMission`, rama `ariaApplies`: después de `var written = requirementsWriter.write(...)`:

```java
var designed = design(missionId, written, java.util.Optional.empty(), null);
if (companyPolicyService.activeValue(PolicyKey.SPEC_APPROVAL_REQUIRED) >= 1.0) {
    awaitApproval(missionId, written, designed);
    return;
}
publishReady(missionId, written);
requirementsMemory.approve(missionId, written.version(), "policy");
if (designed != null) {
    designMemory.approve(missionId, designed.version(), "policy");
}
events.publish("EMPRESA_REQUIREMENTS_APPROVED", missionId, null, "policy",
        designed == null ? Map.of("version", written.version(), "approvedBy", "policy")
                : Map.of("version", written.version(), "approvedBy", "policy", "interactionDesignVersion", designed.version()));
planAndRunIncrement(missionId, instruction, teamId, mode, written.spec(), 1, 0);
```

`awaitApproval(missionId, written)` pasa a `awaitApproval(missionId, written, designed)` (designed nullable); el texto agrega, si hay diseño: `" Kael diseñó " + (GAME ? "el juego" : "la interacción") + " (versión " + v + "): " + InteractionDesignText.summary(design)` y el título del paso pasa a `"Requisitos y diseño listos (Aria + Kael)"`. Las llamadas existentes sin diseño pasan `null`.

`withIncrement`: nueva sobrecarga que antepone el bloque del diseño aprobado:

```java
private String withIncrement(String missionId, com.aicompany.core.agent.model.RequirementsSpec spec, int increment,
                             String instruction) {
    var design = approvedDesign(missionId);
    return design == null ? withIncrement(spec, increment, instruction)
            : com.aicompany.core.agent.validation.RequirementsText.incrementBlock(spec, increment)
              + com.aicompany.core.agent.validation.InteractionDesignText.incrementBlock(design, spec, increment)
              + "\nPEDIDO ORIGINAL DEL FUNDADOR: " + instruction;
}

private com.aicompany.core.agent.model.InteractionDesign approvedDesign(String missionId) {
    if (designMemory == null) {
        return null;
    }
    return designMemory.approved(missionId).map(v -> jsonMapper.readValue(v.designJson(),
            com.aicompany.core.agent.model.InteractionDesign.class)).orElse(null);
}
```

Reemplazar **todas** las llamadas `withIncrement(spec, increment, instruction)` dentro de `MissionExecutor` (en `planAndRunIncrement`, `resumeRound`, `buildIncrementAsync` y rondas de evidencia: `grep -n "withIncrement(" MissionExecutor.java`) por `withIncrement(missionId, spec, increment, instruction)`. El `static withIncrement(spec, increment, instruction)` queda para los casos sin misión.

Ajuste a Aria (`adjustRequirementsAsync` y `case ADJUSTMENT` de `resumeInternal`): tras `rewriteRequirements` y el evento:

```java
if (designMemory != null) {
    designMemory.supersedeDraft(missionId);
}
var previousDesign = designMemory == null ? java.util.Optional.<com.aicompany.core.agent.model.InteractionDesign>empty()
        : designMemory.latest(missionId).map(v -> jsonMapper.readValue(v.designJson(),
                com.aicompany.core.agent.model.InteractionDesign.class));
awaitApproval(missionId, written, design(missionId, written, previousDesign,
        "Aria cambió las historias de usuario: rehaz el diseño sobre las HU actuales."));
```

Ajuste a Kael (nuevo):

```java
/** Spec 2026-10-14 Kael §1.2: Kael rehace solo el diseño (mismas HU) y vuelve a esperar. */
public CompletableFuture<Void> adjustDesignAsync(String missionId, String adjustment) {
    advanceMission(missionId, MissionStatus.PLANNING, 6, "Diseño (Kael)", "Kael ajusta el diseño: " + adjustment);
    return submit(missionId, "design adjustment", () -> redesign(missionId, adjustment));
}

private void redesign(String missionId, String adjustment) {
    var latest = requirementsMemory.latest(missionId).orElseThrow();
    var spec = jsonMapper.readValue(latest.specJson(), com.aicompany.core.agent.model.RequirementsSpec.class);
    var written = new RequirementsWriter.Written(latest.version(), spec);
    var previous = designMemory.latest(missionId).map(v -> jsonMapper.readValue(v.designJson(),
            com.aicompany.core.agent.model.InteractionDesign.class));
    var designed = designWriter.write(missionId, spec, latest.version(), previous, adjustment);
    requirementsMemory.releaseDecision(missionId, latest.version());
    events.publish("EMPRESA_INTERACTION_DESIGN_ADJUSTED", missionId, null, "human",
            Map.of("version", designed.version(), "instruction", adjustment));
    awaitApproval(missionId, written, designed);
}
```

`resumeInternal`: agregar

```java
case DESIGN -> {
    var latest = requirementsMemory.latest(missionId).orElseThrow();
    var written = new RequirementsWriter.Written(latest.version(), jsonMapper.readValue(latest.specJson(),
            com.aicompany.core.agent.model.RequirementsSpec.class));
    awaitApproval(missionId, written, design(missionId, written, java.util.Optional.empty(), null));
}
case DESIGN_ADJUSTMENT -> redesign(missionId, designMemory.pendingAdjustment(missionId).orElseThrow());
```

y en `case AWAIT_APPROVAL` pasar el diseño DRAFT si existe (`designMemory.latest` con `status DRAFT` y `specVersion == latest.version()`, armado como `InteractionDesignWriter.Written`), o `null`.

- [ ] **Step 6: Correr y ver que pasan**

Run: `cd app && mvn test -Dtest=ResumePointTest,MissionExecutorTeamTest,MissionRecoveryServiceTest,MissionExecutorTest` → PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src && git commit -m "Misión del Development Group: Kael diseña tras Aria y el fundador aprueba ambos; retome en la etapa de Kael

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Decisión conjunta, ajuste dirigido y API

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/RequirementsDecisionCommand.java`
- Create: `app/src/main/java/com/aicompany/core/model/InteractionDesignView.java`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionService.java` (`decideRequirements`, `requireAgentsOn`, nuevo `interactionDesign`)
- Modify: `app/src/main/java/com/aicompany/core/controller/MissionController.java`
- Modify: `app/src/main/java/com/aicompany/core/service/AgentAvailability.java` (constante `INTERACTION_DESIGN = "interaction-design"`)
- Test: `app/src/test/java/com/aicompany/core/service/MissionServiceRequirementsTest.java`

**Interfaces:**
- Produces:
  - `RequirementsDecisionCommand(RequirementsDecision decision, String instruction, Target target)`; `enum Target { ARIA, KAEL }`; constructor de 2 args → `Target.ARIA`; `targetOrAria()`.
  - `InteractionDesignView(String missionId, Integer version, String status, Integer specVersion, Integer approvedVersion, InteractionDesign design, String summary)`.
  - `MissionService.interactionDesign(String missionId) → Optional<InteractionDesignView>`; `MissionService.setInteractionDesign(InteractionDesignMemoryService)` (setter opcional).
  - `GET /api/company/missions/{id}/interaction-design`.

- [ ] **Step 1: Tests que fallan**

```java
private final InteractionDesignMemoryService designs = mock(InteractionDesignMemoryService.class);
{ service.setInteractionDesign(designs); }

private void awaitingWithDesign(int specVersion, int designSpecVersion) {
    when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_SPEC_APPROVAL)));
    when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(specVersion, "{}", "DRAFT", "", null)));
    when(designs.latest("M-9")).thenReturn(Optional.of(new InteractionDesignMemoryService.Version(4, "{}", "DRAFT", designSpecVersion, "")));
}

@Test
void approvingApprovesTheStoriesAndTheDesignTogether() {
    awaitingWithDesign(2, 2);
    service.decideRequirements("M-9", new RequirementsDecisionCommand(
            RequirementsDecisionCommand.RequirementsDecision.APPROVE, null));
    verify(requirements).approve("M-9", 2, "founder");
    verify(designs).approve("M-9", 4, "founder");
    verify(events).publish(eq("EMPRESA_REQUIREMENTS_APPROVED"), eq("M-9"), isNull(), eq("human"),
            argThat(m -> Integer.valueOf(4).equals(m.get("interactionDesignVersion"))));
}

// Review Focus 4: un diseño hecho sobre otra versión de HU no se aprueba.
@Test
void aDesignForOtherStoriesCannotBeApproved() {
    awaitingWithDesign(3, 2);
    assertThrows(IllegalStateException.class, () -> service.decideRequirements("M-9", new RequirementsDecisionCommand(
            RequirementsDecisionCommand.RequirementsDecision.APPROVE, null)));
    verify(requirements, never()).approve(anyString(), anyInt(), anyString());
}

@Test
void anAdjustmentForKaelGoesOnlyToKael() {
    awaitingWithDesign(2, 2);
    service.decideRequirements("M-9", new RequirementsDecisionCommand(
            RequirementsDecisionCommand.RequirementsDecision.ADJUST, "paleta más grande",
            RequirementsDecisionCommand.Target.KAEL));
    verify(requirements).claimDecision("M-9", 2, null);
    verify(designs).setPendingAdjustment("M-9", 4, "paleta más grande");
    verify(executor).adjustDesignAsync("M-9", "paleta más grande");
    verify(executor, never()).adjustRequirementsAsync(anyString(), anyString());
}

@Test
void kaelCannotBeAdjustedWithoutADesign() {
    when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_SPEC_APPROVAL)));
    when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(1, "{}", "DRAFT", "", null)));
    when(designs.latest("M-9")).thenReturn(Optional.empty());
    assertThrows(IllegalStateException.class, () -> service.decideRequirements("M-9", new RequirementsDecisionCommand(
            RequirementsDecisionCommand.RequirementsDecision.ADJUST, "x", RequirementsDecisionCommand.Target.KAEL)));
}

@Test
void rejectingRejectsTheDesignToo() {
    awaitingWithDesign(2, 2);
    service.decideRequirements("M-9", new RequirementsDecisionCommand(
            RequirementsDecisionCommand.RequirementsDecision.REJECT, null));
    verify(designs).reject("M-9", 4);
}

// Review Focus 3: una misión sin diseño (spec viejo o NONE) se aprueba como siempre.
@Test
void approvingWithoutDesignWorksAsBefore() {
    when(memory.find("M-9")).thenReturn(Optional.of(missionIn(MissionStatus.AWAITING_SPEC_APPROVAL)));
    when(requirements.latest("M-9")).thenReturn(Optional.of(new RequirementsMemoryService.Version(2, "{}", "DRAFT", "", null)));
    when(designs.latest("M-9")).thenReturn(Optional.empty());
    service.decideRequirements("M-9", new RequirementsDecisionCommand(
            RequirementsDecisionCommand.RequirementsDecision.APPROVE, null));
    verify(requirements).approve("M-9", 2, "founder");
    verify(designs, never()).approve(anyString(), anyInt(), anyString());
}
```

- [ ] **Step 2: Correr y ver que fallan** — `cd app && mvn test -Dtest=MissionServiceRequirementsTest` → FAIL.

- [ ] **Step 3: Implementar**

`RequirementsDecisionCommand`:

```java
public record RequirementsDecisionCommand(RequirementsDecision decision, String instruction, Target target) {
    public enum RequirementsDecision { APPROVE, ADJUST, REJECT }
    /** Spec 2026-10-14 Kael §3: a quién va un ADJUST (default Aria). */
    public enum Target { ARIA, KAEL }
    public RequirementsDecisionCommand(RequirementsDecision decision, String instruction) {
        this(decision, instruction, Target.ARIA);
    }
    public Target targetOrAria() {
        return target == null ? Target.ARIA : target;
    }
}
```

`MissionService.decideRequirements`, después del chequeo `!"DRAFT".equals(version.status())`:

```java
// Spec 2026-10-14 Kael §1.3: el diseño DRAFT vigente, solo si se hizo sobre esta versión de HU.
var draftDesign = designs == null ? Optional.<InteractionDesignMemoryService.Version>empty()
        : designs.latest(missionId).filter(d -> "DRAFT".equals(d.status()));
if (draftDesign.isPresent() && draftDesign.get().specVersion() != version.version()) {
    throw new IllegalStateException("El diseño de Kael de " + missionId + " es de la versión "
            + draftDesign.get().specVersion() + " de las HU y la vigente es la " + version.version()
            + ": espera a que Kael termine el diseño nuevo.");
}
var toKael = command.decision() == RequirementsDecisionCommand.RequirementsDecision.ADJUST
        && command.targetOrAria() == RequirementsDecisionCommand.Target.KAEL;
if (toKael && draftDesign.isEmpty()) {
    throw new IllegalStateException(missionId + " no tiene un diseño de Kael para ajustar.");
}
```

`requireAgentsOn(mission.teamId(), what)` → pasar además `draftDesign.isPresent()` para exigir Kael: agregar parámetro `boolean needsKael` a `requireAgentsOn(teamId, what, needsAria)` (sobrecarga) y `if (needsKael) required.add(AgentAvailability.INTERACTION_DESIGN);`.

`claimDecision`: el ajuste se guarda en la versión de HU solo si va a Aria:

```java
var adjustment = command.decision() == RequirementsDecisionCommand.RequirementsDecision.ADJUST
        ? command.instruction().strip() : null;
if (!requirementsMemory.claimDecision(missionId, version.version(), toKael ? null : adjustment)) { ...igual... }
```

`switch`:

```java
case APPROVE -> {
    requirementsMemory.approve(missionId, version.version(), "founder");
    draftDesign.ifPresent(d -> designs.approve(missionId, d.version(), "founder"));
    events.publish("EMPRESA_REQUIREMENTS_APPROVED", missionId, null, "human", draftDesign
            .<Map<String, Object>>map(d -> Map.of("version", version.version(), "interactionDesignVersion", d.version()))
            .orElse(Map.of("version", version.version())));
    executor.continueAfterApprovalAsync(missionId);
}
case ADJUST -> {
    if (toKael) {
        designs.setPendingAdjustment(missionId, draftDesign.get().version(), adjustment);
        executor.adjustDesignAsync(missionId, adjustment);
    } else {
        executor.adjustRequirementsAsync(missionId, adjustment);
    }
}
case REJECT -> {
    requirementsMemory.reject(missionId, version.version());
    draftDesign.ifPresent(d -> designs.reject(missionId, d.version()));
    // …resto igual…
}
```

`interactionDesign(missionId)`: `designs.latest` → `InteractionDesignView` con `approvedVersion` de `designs.approved`, el JSON leído como `InteractionDesign` y `summary` de `InteractionDesignText.summary`; `Optional.empty()` si no hay versión o `designs == null`.

`MissionController`:

```java
/** Spec 2026-10-14 Kael §3: diseño de Kael de la misión (404 = sin diseño). */
@GetMapping("/{missionId}/interaction-design")
public ResponseEntity<InteractionDesignView> interactionDesign(@PathVariable String missionId) {
    return missionService.interactionDesign(missionId).map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
}
```

- [ ] **Step 4: Correr y ver que pasan** — `cd app && mvn test -Dtest=MissionServiceRequirementsTest,MissionServiceTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "Aprobación conjunta de HU y diseño, ajuste dirigido a Aria o Kael y endpoint del diseño

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Neo respeta el tipo de experiencia

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/ExperienceRule.java`
- Modify: `app/src/main/java/com/aicompany/core/service/TeamWorkPlanner.java` (línea ~197, junto a `RequestedComponents.errors`)
- Test: `app/src/test/java/com/aicompany/core/agent/validation/ExperienceRuleTest.java`

**Interfaces:**
- Consumes: `RequirementsText.EXPERIENCE_LINE` (Task 1); la instrucción que recibe Neo ya trae `TIPO DE EXPERIENCIA: X` (Tasks 1 y 5).
- Produces: `ExperienceRule.declared(String instruction) → Optional<RequirementsSpec.Experience>`; `ExperienceRule.errors(String instruction, TeamPlan plan, TeamSnapshot team) → List<String>`.

- [ ] **Step 1: Tests que fallan**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.agent.model.TeamPlan.PlannedTask;
import com.aicompany.core.model.TeamMemberInfo;
import com.aicompany.core.model.TeamSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExperienceRuleTest {

    static final TeamSnapshot TEAM = new TeamSnapshot("TEAM-DEVELOPMENT", "DG", "ACTIVE", "engineering", List.of(
            member("interaction-design", "GAME_DEV"), member("qa", "QA"), member("frontend-ui", "UI_UX"),
            member("backend", "BACKEND")));

    static TeamMemberInfo member(String id, String role) {
        // usar el constructor real de TeamMemberInfo (agentId, name, roleCode, capabilities, …) del repo
        return TeamPlanValidatorTest.member(id, role);
    }

    static TeamPlan plan(String profile, String... agents) {
        var tasks = java.util.Arrays.stream(agents).map(a -> new PlannedTask(a, "WORK", "HACER", "o",
                List.of("x"), List.of(), List.of())).toList();
        return new TeamPlan("s", "t", "e", tasks, List.of(), profile, List.of(), List.of(), null, List.of(profile));
    }

    @Test void aGameNeedsGodotKaelAndVera() {
        var ins = "TIPO DE EXPERIENCIA: GAME\nPEDIDO: un pong";
        assertEquals(List.of(), ExperienceRule.errors(ins, plan("GODOT_DOTNET_GAME", "interaction-design", "qa"), TEAM));
        var errors = String.join("\n", ExperienceRule.errors(ins, plan("FLUTTER_WEB_APP", "frontend-ui"), TEAM));
        assertTrue(errors.contains("GODOT_DOTNET_GAME") && errors.contains("interaction-design") && errors.contains("qa"), errors);
    }

    @Test void anAppNeedsFlutterAndMila() {
        var ins = "TIPO DE EXPERIENCIA: APP\n";
        assertFalse(ExperienceRule.errors(ins, plan("DOTNET_APP", "backend"), TEAM).isEmpty());
        assertEquals(List.of(), ExperienceRule.errors(ins, plan("FLUTTER_WEB_APP", "frontend-ui"), TEAM));
    }

    @Test void noneForbidsInterfaces() {
        assertFalse(ExperienceRule.errors("TIPO DE EXPERIENCIA: NONE", plan("FLUTTER_WEB_APP", "frontend-ui"), TEAM).isEmpty());
    }

    @Test void withoutTheLineNothingIsRequired() {
        assertEquals(List.of(), ExperienceRule.errors("PEDIDO: un CRM", plan("DOTNET_APP", "backend"), TEAM));
        assertTrue(ExperienceRule.declared("PEDIDO: un CRM").isEmpty());
    }
}
```

(Si `TeamPlanValidatorTest` no expone un helper `member`, crear el `TeamMemberInfo` con su constructor real; ver `app/src/main/java/com/aicompany/core/model/TeamMemberInfo.java`.)

Y el Review Focus 1 en `app/src/test/java/com/aicompany/core/service/TeamWorkPlannerInstructionRulesTest.java` (mismo paquete que `TeamWorkPlanner`, porque `instructionRules` es package-private; reusar `ExperienceRuleTest.TEAM` y `ExperienceRuleTest.plan` haciéndolos `public static`):

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.validation.ExperienceRuleTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeamWorkPlannerInstructionRulesTest {

    // Review Focus 1: "pantalla" en un juego no exige Flutter cuando Aria declaró GAME.
    @Test void aDeclaredGameSkipsTheRequestedComponentsHeuristic() {
        var ins = "TIPO DE EXPERIENCIA: GAME\nPEDIDO ORIGINAL DEL FUNDADOR: un pong con pantalla de game over";
        assertEquals(List.of(), TeamWorkPlanner.instructionRules(ins,
                ExperienceRuleTest.plan("GODOT_DOTNET_GAME", "interaction-design", "qa"), ExperienceRuleTest.TEAM));
    }

    @Test void withoutTheLineTheHeuristicStillRuns() {
        assertFalse(TeamWorkPlanner.instructionRules("una app para inventario",
                ExperienceRuleTest.plan("DOTNET_APP", "backend"), ExperienceRuleTest.TEAM).isEmpty());
    }
}
```

(`ExperienceRuleTest` y sus miembros `TEAM`/`plan` pasan a `public`.)

- [ ] **Step 2: Correr y ver que falla** — `cd app && mvn test -Dtest=ExperienceRuleTest,TeamWorkPlannerInstructionRulesTest` → FAIL.

- [ ] **Step 3: Implementar**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.RequirementsSpec.Experience;
import com.aicompany.core.agent.model.TeamPlan;
import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.TeamSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Spec 2026-10-14 Kael §1.4: el tipo de experiencia que aprobó el fundador manda sobre el plan de Neo. */
public final class ExperienceRule {

    private static final Pattern LINE = Pattern.compile("(?m)^" + Pattern.quote(RequirementsText.EXPERIENCE_LINE)
            + "(APP|GAME|NONE)\\s*$");

    private ExperienceRule() {
    }

    public static Optional<Experience> declared(String instruction) {
        if (instruction == null) {
            return Optional.empty();
        }
        var m = LINE.matcher(instruction);
        return m.find() ? Experience.of(m.group(1)) : Optional.empty();
    }

    public static List<String> errors(String instruction, TeamPlan plan, TeamSnapshot team) {
        var experience = declared(instruction);
        if (experience.isEmpty() || plan == null) {
            return List.of();
        }
        var agents = plan.tasksOrEmpty().stream().filter(Objects::nonNull).map(TeamPlan.PlannedTask::agentId).toList();
        var errors = new ArrayList<String>();
        var hasGame = plan.hasComponent(StackProfile.GODOT_DOTNET_GAME);
        var hasApp = plan.hasComponent(StackProfile.FLUTTER_WEB_APP);
        switch (experience.get()) {
            case GAME -> {
                if (!hasGame) errors.add("El fundador aprobó un JUEGO: usa stackProfile GODOT_DOTNET_GAME.");
                requireRole(team, agents, "GAME_DEV", "el juego lo programa", errors);
                requireRole(team, agents, "QA", "las partidas y los tests los escribe", errors);
            }
            case APP -> {
                if (!hasApp) errors.add("El fundador aprobó una APP con pantallas: incluye FLUTTER_WEB_APP en components.");
                requireRole(team, agents, "UI_UX", "las pantallas las programa", errors);
            }
            case NONE -> {
                if (hasApp || hasGame) errors.add("El fundador aprobó un producto SIN interfaz (solo API o servicio): "
                        + "quita FLUTTER_WEB_APP y GODOT_DOTNET_GAME.");
            }
        }
        return errors;
    }

    private static void requireRole(TeamSnapshot team, List<String> agents, String role, String why, List<String> errors) {
        var member = team.members().stream().filter(m -> role.equals(m.roleCode())).findFirst();
        if (member.isPresent() && !agents.contains(member.get().agentId())) {
            errors.add("Falta " + member.get().agentId() + " (" + role + "): " + why + " " + member.get().agentId() + ".");
        }
    }
}
```

`TeamWorkPlanner`: extraer una función estática y usarla donde hoy se suma `RequestedComponents.errors`:

```java
/** Spec 2026-10-14 Kael (Review Focus 1): con el tipo declarado por Aria manda ExperienceRule, no la heurística. */
static List<String> instructionRules(String instruction, TeamPlan plan, TeamSnapshot team) {
    return com.aicompany.core.agent.validation.ExperienceRule.declared(instruction).isPresent()
            ? com.aicompany.core.agent.validation.ExperienceRule.errors(instruction, plan, team)
            : com.aicompany.core.agent.validation.RequestedComponents.errors(instruction, plan);
}
```

y en `plan(...)`: `errors.addAll(instructionRules(instruction, plan, team));` en lugar de la línea de `RequestedComponents`.

**Nota**: con `GAME_DEV` todavía en fase 3 el validador rechazaría a Kael; `ExperienceRuleTest` no pasa por el validador, así que no depende de Task 8.

- [ ] **Step 4: Correr y ver que pasa** — `cd app && mvn test -Dtest=ExperienceRuleTest,RequestedComponentsTest,TeamPlanValidatorTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "Neo respeta el tipo de experiencia aprobado (juego, app o sin interfaz)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Habilitar Godot y a Kael en fase 1; rutas de Vera y de Forjai en `game/`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/StackProfile.java` (`phase()`, `describe()` del perfil Godot si menciona fase)
- Modify: `app/src/main/java/com/aicompany/core/model/RoleLayerCatalog.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/TeamPlanResolver.java` (después de `addMigrations`)
- Test: `app/src/test/java/com/aicompany/core/model/RoleLayerCatalogTest.java`, `StackProfileTest.java`, `app/src/test/java/com/aicompany/core/agent/validation/TeamPlanResolverTest.java`, `TeamPlanValidatorTest.java`

**Interfaces:**
- Produces: `StackProfile.GODOT_DOTNET_GAME.enabledNow() == true`; `RoleLayerCatalog` `GAME_DEV` = primaria `GAME`, respaldo `DOMAIN, APPLICATION`, fase 1; `StackProfile.PLAYTHROUGHS_ROOT = "game/playthroughs"`, `StackProfile.FORJAI_GAME_ROOT = "game/forjai"`; Vera recibe `game/playthroughs` en `ownedPaths` cuando el plan tiene `GODOT_DOTNET_GAME`.

- [ ] **Step 1: Tests que fallan**

`RoleLayerCatalogTest`:

```java
@Test void kaelWritesTheGameAndTheRulesWithoutIris() {
    var roles = new java.util.LinkedHashMap<String, String>();
    roles.put("interaction-design", "GAME_DEV");
    roles.put("qa", "QA");
    var layers = RoleLayerCatalog.assign(roles, StackProfile.GODOT_DOTNET_GAME.layers());
    assertEquals(List.of(Layer.DOMAIN, Layer.APPLICATION, Layer.GAME), layers.get("interaction-design"));
    assertEquals(List.of(Layer.TESTS), layers.get("qa"));
}
@Test void withIrisKaelOnlyWritesTheGame() {
    var roles = new java.util.LinkedHashMap<String, String>();
    roles.put("interaction-design", "GAME_DEV");
    roles.put("backend", "BACKEND");
    var layers = RoleLayerCatalog.assign(roles, StackProfile.GODOT_DOTNET_GAME.layers());
    assertEquals(List.of(Layer.GAME), layers.get("interaction-design"));
    assertEquals(List.of(Layer.DOMAIN, Layer.APPLICATION), layers.get("backend"));
}
@Test void kaelIsEnabledNow() {
    assertTrue(RoleLayerCatalog.enabledNow("GAME_DEV"));
    assertFalse(RoleLayerCatalog.enabledNow("SPECIALIST_3D"));
    assertFalse(RoleLayerCatalog.describe().contains("Kael/GAME_DEV, Orion"), RoleLayerCatalog.describe());
}
```

`StackProfileTest`: `assertTrue(StackProfile.GODOT_DOTNET_GAME.enabledNow());` y `assertTrue(StackProfile.enabledUsages().contains("Juegos Godot con C#"))` (usar el nombre real del método estático que hoy filtra por `enabledNow`, línea ~342).

`TeamPlanResolverTest` (seguir el armado de equipo y plan de los tests existentes de la clase):

```java
@Test void veraOwnsThePlaythroughsInAGame() {
    // plan GODOT_DOTNET_GAME con interaction-design y qa, un contexto "Pong"
    var resolution = /* resolver.resolve(plan, team) como en los otros tests */;
    var vera = resolution.plan().workTasks().stream().filter(t -> t.agentId().equals("qa")).findFirst().orElseThrow();
    assertTrue(vera.ownedPathsOrEmpty().contains("game/playthroughs"), vera.ownedPathsOrEmpty().toString());
    var kael = resolution.plan().workTasks().stream().filter(t -> t.agentId().equals("interaction-design")).findFirst().orElseThrow();
    assertTrue(kael.ownedPathsOrEmpty().contains("game"));
    assertEquals(List.of(), resolution.errors());
}
```

`TeamPlanValidatorTest`: un plan Godot con Kael y Vera ya no da "no habilitado" (buscar el test que hoy espera el rechazo por fase 3 y cambiar su expectativa; si existe un test "kaelIsNotEnabledYet", reemplazarlo por `aGodotPlanWithKaelIsValid`).

- [ ] **Step 2: Correr y ver que fallan** — `cd app && mvn test -Dtest=RoleLayerCatalogTest,StackProfileTest,TeamPlanResolverTest,TeamPlanValidatorTest` → FAIL.

- [ ] **Step 3: Implementar**

`StackProfile.phase()`: `return 1;` (todos los perfiles en fase 1). Agregar:

```java
/** Spec 2026-10-14 Kael §2.1: dentro de game/, las partidas son de Vera y el runner es de Forjai. */
public static final String PLAYTHROUGHS_ROOT = "game/playthroughs";
public static final String FORJAI_GAME_ROOT = "game/forjai";
```

Revisar `describe()`/`executionContract()` del perfil Godot (línea ~322) y quitar cualquier mención a "fase 3".

`RoleLayerCatalog`:

```java
"GAME_DEV", new RoleLayers(List.of(Layer.GAME), List.of(Layer.DOMAIN, Layer.APPLICATION), 1, true),
```

y en `describe()`: reemplazar el tramo de Kael por
`"Kael/GAME_DEV → GAME (todo game/, incluidos project.godot y la escena principal; y DOMAIN/APPLICATION si no participa Iris), solo en GODOT_DOTNET_GAME; Vera/QA también escribe las partidas simuladas en game/playthroughs; "`
y dejar `"Orion/SPECIALIST_3D, Luna/CREATIVE y Andrea/DEVOPS todavía no están habilitados…"`.

`TeamPlanResolver.resolve`, después de `addMigrations(plan, membersById, resolved);`:

```java
addPlaythroughs(plan, membersById, resolved);
```

```java
/** Spec 2026-10-14 Kael §2.3: en un juego, Vera escribe además las partidas simuladas. */
private static void addPlaythroughs(TeamPlan plan, Map<String, TeamMemberInfo> membersById, List<PlannedTask> resolved) {
    if (!plan.hasComponent(StackProfile.GODOT_DOTNET_GAME)) {
        return;
    }
    var prefix = plan.resolvedComponents().stream().filter(c -> c.profile() == StackProfile.GODOT_DOTNET_GAME)
            .findFirst().map(c -> c.prefix(StackProfile.PLAYTHROUGHS_ROOT)).orElse(StackProfile.PLAYTHROUGHS_ROOT);
    for (int i = 0; i < resolved.size(); i++) {
        var task = resolved.get(i);
        var member = membersById.get(task.agentId());
        if (member != null && "QA".equals(member.roleCode()) && TeamPlan.KIND_WORK.equals(task.kind())) {
            var paths = new ArrayList<>(task.ownedPathsOrEmpty());
            if (!paths.contains(prefix)) {
                paths.add(prefix);
            }
            resolved.set(i, withKindAndPaths(task, task.kind(), task.action(), paths));
        }
    }
}
```

(Usar los nombres reales: `withKindAndPaths`, `ownedPathsOrEmpty`, `AppComponent.prefix` ya existen en la clase/modelo; ver `addMigrations` como referencia de estilo.)

- [ ] **Step 4: Correr y ver que pasan** — mismo comando → PASS. Luego `cd app && mvn test` completo → PASS (ajustar tests que esperaban Godot/Kael deshabilitados).

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "Godot y Kael (GAME_DEV) habilitados en fase 1; Vera escribe las partidas en game/playthroughs

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Gates de juego

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/GameAssetGate.java`, `GameMainSceneGate.java`, `InjectableRandomGate.java`, `ReservedGamePathsGate.java`
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (`generate`, línea ~1672)
- Test: `app/src/test/java/com/aicompany/core/agent/validation/GameGatesTest.java`

**Interfaces:**
- Produces:
  - `GameAssetGate.check(List<GeneratedFile>) → List<String>`.
  - `GameMainSceneGate.check(List<GeneratedFile> returned, Map<String,String> existingGameFiles) → List<String>` (solo si entre `returned` + `existing` hay un `game/project.godot`).
  - `InjectableRandomGate.check(List<GeneratedFile>, List<String> domainAndApplicationRoots) → List<String>`.
  - `ReservedGamePathsGate.check(List<GeneratedFile>, boolean isQa) → List<String>`.

- [ ] **Step 1: Tests que fallan**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GameGatesTest {

    @Test void externalAssetsAreRejectedAndShapesAreFine() {
        assertFalse(GameAssetGate.check(List.of(new GeneratedFile("game/Main.tscn",
                "[ext_resource type=\"Texture2D\" path=\"res://paleta.png\" id=\"2\"]"))).isEmpty());
        assertFalse(GameAssetGate.check(List.of(new GeneratedFile("game/Ball.cs",
                "var s = GD.Load<AudioStream>(\"res://pong.wav\");"))).isEmpty());
        assertEquals(List.of(), GameAssetGate.check(List.of(new GeneratedFile("game/Main.tscn",
                "[ext_resource type=\"Script\" path=\"res://Main.cs\" id=\"1\"]\n[node name=\"Paleta\" type=\"ColorRect\"]"))));
        assertEquals(List.of(), GameAssetGate.check(List.of(new GeneratedFile("src/Pong.Domain/Score.cs", "// sprite.png"))));
    }

    @Test void theMainSceneMustBeDeclaredAndExist() {
        var godot = new GeneratedFile("game/project.godot", "[application]\nrun/main_scene=\"res://Main.tscn\"\n");
        assertFalse(GameMainSceneGate.check(List.of(godot), Map.of()).isEmpty());                       // falta Main.tscn
        assertEquals(List.of(), GameMainSceneGate.check(List.of(godot, new GeneratedFile("game/Main.tscn", "x")), Map.of()));
        assertEquals(List.of(), GameMainSceneGate.check(List.of(godot), Map.of("game/Main.tscn", "x"))); // ya commiteada
        assertFalse(GameMainSceneGate.check(List.of(new GeneratedFile("game/project.godot", "[application]\n")), Map.of()).isEmpty());
        assertEquals(List.of(), GameMainSceneGate.check(List.of(new GeneratedFile("game/Ball.cs", "x")), Map.of()));
    }

    @Test void randomnessIsInjectedInTheRules() {
        var roots = List.of("src/Pong.Domain", "src/Pong.Application");
        assertFalse(InjectableRandomGate.check(List.of(new GeneratedFile("src/Pong.Domain/Ball.cs",
                "var r = new Random();")), roots).isEmpty());
        assertFalse(InjectableRandomGate.check(List.of(new GeneratedFile("src/Pong.Application/Serve.cs",
                "var a = GD.Randf();")), roots).isEmpty());
        assertEquals(List.of(), InjectableRandomGate.check(List.of(new GeneratedFile("src/Pong.Domain/Ball.cs",
                "public Ball(IRandom random) { _random = random; }")), roots));
        assertEquals(List.of(), InjectableRandomGate.check(List.of(new GeneratedFile("game/Main.cs",
                "var r = new Random();")), roots)); // la escena puede crear la semilla
    }

    // Review Focus 5
    @Test void reservedPathsInsideGameAreProtected() {
        var playthrough = new GeneratedFile("game/playthroughs/ESC_01_mueve.cs", "x");
        var runner = new GeneratedFile("game/forjai/PlaythroughRunner.cs", "x");
        assertFalse(ReservedGamePathsGate.check(List.of(playthrough), false).isEmpty());
        assertEquals(List.of(), ReservedGamePathsGate.check(List.of(playthrough), true));
        assertFalse(ReservedGamePathsGate.check(List.of(runner), true).isEmpty());
        assertFalse(ReservedGamePathsGate.check(List.of(runner), false).isEmpty());
    }
}
```

- [ ] **Step 2: Correr y ver que falla** — `cd app && mvn test -Dtest=GameGatesTest` → FAIL.

- [ ] **Step 3: Implementar**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Spec 2026-10-14 Kael §2.2: el juego no usa assets externos (no hay arte ni audio en esta fase). */
public final class GameAssetGate {

    private static final Pattern ASSET = Pattern.compile(
            "(?i)[\\w./-]+\\.(png|jpe?g|svg|wav|ogg|mp3|ttf|otf|glb|gltf)\\b");

    private GameAssetGate() {
    }

    public static List<String> check(List<DevelopmentResult.GeneratedFile> files) {
        var errors = new ArrayList<String>();
        for (var f : files.stream().filter(Objects::nonNull).toList()) {
            var path = f.path() == null ? "" : f.path();
            if (!path.startsWith("game/") || !(path.endsWith(".tscn") || path.endsWith(".tres") || path.endsWith(".cs"))) {
                continue;
            }
            var m = ASSET.matcher(f.content() == null ? "" : f.content());
            if (m.find()) {
                errors.add(path + " usa el asset externo \"" + m.group() + "\": no hay arte ni audio; dibuja con nodos "
                        + "(ColorRect, Polygon2D, Label) o en _Draw().");
            }
        }
        return errors;
    }
}
```

```java
/** Spec 2026-10-14 Kael §2.2: project.godot declara la escena principal y esa escena existe. */
public final class GameMainSceneGate {
    private static final Pattern MAIN = Pattern.compile("(?m)^run/main_scene=\"res://([^\"]+)\"");

    public static List<String> check(List<DevelopmentResult.GeneratedFile> returned, Map<String, String> existing) {
        var godot = returned.stream().filter(Objects::nonNull).filter(f -> "game/project.godot".equals(f.path()))
                .findFirst();
        if (godot.isEmpty()) {
            return List.of();
        }
        var m = MAIN.matcher(godot.get().content() == null ? "" : godot.get().content());
        if (!m.find()) {
            return List.of("game/project.godot debe declarar run/main_scene=\"res://<Escena>.tscn\" en [application].");
        }
        var scene = "game/" + m.group(1);
        var exists = existing.containsKey(scene) || returned.stream().filter(Objects::nonNull)
                .anyMatch(f -> scene.equals(f.path()));
        return exists ? List.of() : List.of("run/main_scene apunta a " + scene + ", que no existe: entrégala.");
    }
}
```

```java
/** Spec 2026-10-14 Kael §2.2: azar con semilla inyectable en las reglas (la partida simulada debe ser determinista). */
public final class InjectableRandomGate {
    private static final Pattern RANDOM = Pattern.compile("new\\s+Random\\s*\\(|Random\\.Shared|GD\\.Rand(i|f|Range)\\b");

    public static List<String> check(List<DevelopmentResult.GeneratedFile> files, List<String> rulesRoots) {
        var errors = new ArrayList<String>();
        for (var f : files.stream().filter(Objects::nonNull).toList()) {
            if (f.path() == null || rulesRoots.stream().noneMatch(r -> OwnedPaths.covers(r, f.path()))) {
                continue;
            }
            var m = RANDOM.matcher(f.content() == null ? "" : f.content());
            if (m.find()) {
                errors.add(f.path() + " usa \"" + m.group().strip() + "\": en domain/application el azar entra como "
                        + "dependencia (una interfaz con semilla, p. ej. IRandom), nunca se crea ahí.");
            }
        }
        return errors;
    }
}
```

```java
/** Spec 2026-10-14 Kael §2.1 (Review Focus 5): game/forjai es de Forjai; game/playthroughs es de Vera. */
public final class ReservedGamePathsGate {
    public static List<String> check(List<DevelopmentResult.GeneratedFile> files, boolean isQa) {
        var errors = new ArrayList<String>();
        for (var f : files.stream().filter(Objects::nonNull).toList()) {
            var path = f.path() == null ? "" : OwnedPaths.normalize(f.path());
            if (OwnedPaths.covers(StackProfile.FORJAI_GAME_ROOT, path)) {
                errors.add(path + " es del runner de Forjai: no lo escribas.");
            } else if (!isQa && OwnedPaths.covers(StackProfile.PLAYTHROUGHS_ROOT, path)) {
                errors.add(path + " es una partida simulada de Vera (QA): no la escribas.");
            }
        }
        return errors;
    }
}
```

(Imports: `java.util.*`, `java.util.regex.Pattern`, `com.aicompany.core.model.StackProfile`; constructores privados como en los demás gates.)

`DevelopmentTeamStrategy.generate`, tras `if (context.plan().databaseOrNull() != null) {...}`:

```java
if (context.plan().hasComponent(StackProfile.GODOT_DOTNET_GAME)) {
    checks.add(gameChecks(context, agentId, headSha));
}
```

```java
/** Spec 2026-10-14 Kael §2.2: assets, escena principal, azar inyectable y rutas reservadas, con reintento. */
private java.util.function.Function<DevelopmentResult, List<String>> gameChecks(TeamMissionContext context, String agentId,
                                                                                String headSha) {
    var rulesRoots = new ArrayList<String>();
    for (var ctx : context.plan().contextNames()) {
        StackProfile.GODOT_DOTNET_GAME.resolveRoot(ctx, StackProfile.Layer.DOMAIN).ifPresent(rulesRoots::add);
        StackProfile.GODOT_DOTNET_GAME.resolveRoot(ctx, StackProfile.Layer.APPLICATION).ifPresent(rulesRoots::add);
    }
    var existing = new java.util.LinkedHashMap<String, String>();
    try {
        if (headSha != null) {
            for (var path : workspace.filesAtCommit(context.missionId(), headSha)) {
                if (path.startsWith("game/") && path.endsWith(".tscn")) {
                    existing.put(path, "");
                }
            }
        }
    } catch (Exception ex) {
        existing.clear();
    }
    var qa = isQa(context, agentId);
    return result -> {
        var errors = new ArrayList<String>();
        errors.addAll(com.aicompany.core.agent.validation.GameAssetGate.check(result.files()));
        errors.addAll(com.aicompany.core.agent.validation.GameMainSceneGate.check(result.files(), existing));
        errors.addAll(com.aicompany.core.agent.validation.InjectableRandomGate.check(result.files(), rulesRoots));
        errors.addAll(com.aicompany.core.agent.validation.ReservedGamePathsGate.check(result.files(), qa));
        return errors;
    };
}
```

- [ ] **Step 4: Correr y ver que pasa** — `cd app && mvn test -Dtest=GameGatesTest,DevelopmentTeamStrategyTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "Gates de juego: sin assets externos, escena principal, azar inyectable y rutas reservadas

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Runner de partidas en el scaffold y partidas como tests

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/GameRunnerFiles.java`
- Modify: `app/src/main/java/com/aicompany/core/model/ProjectScaffold.java` (bloque `GODOT_DOTNET_GAME`)
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/TestInventory.java`
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (`qaScenarioRule`, línea ~1655)
- Test: `app/src/test/java/com/aicompany/core/model/ProjectScaffoldTest.java`, `app/src/test/java/com/aicompany/core/agent/validation/TestInventoryTest.java` (crear si no existe), `DevelopmentTeamStrategyTest.java`

**Interfaces:**
- Produces: `GameRunnerFiles.files() → List<GeneratedFile>` (`game/forjai/Playthrough.cs`, `game/forjai/PlaythroughRunner.cs`, `game/forjai/Runner.tscn`); `TestInventory.of(...)` reconoce `class ESC_NN_x : Playthrough` en `game/playthroughs/*.cs`; `DevelopmentTeamStrategy.qaScenarioRule(List<Scenario>, boolean game)`.

- [ ] **Step 1: Tests que fallan**

```java
// ProjectScaffoldTest
@Test void aGameScaffoldIncludesTheForjaiRunner() {
    var paths = ProjectScaffold.generate(StackProfile.GODOT_DOTNET_GAME, List.of("Pong")).stream()
            .map(GeneratedFile::path).toList();
    assertTrue(paths.containsAll(List.of("game/forjai/Playthrough.cs", "game/forjai/PlaythroughRunner.cs",
            "game/forjai/Runner.tscn")), paths.toString());
    assertFalse(ProjectScaffold.generate(StackProfile.DOTNET_APP, List.of("Clientes")).stream()
            .anyMatch(f -> f.path().startsWith("game/forjai")));
}
```

```java
// TestInventoryTest
@Test void playthroughsCountAsTests() {
    var tests = TestInventory.of(Map.of("game/playthroughs/ESC_02_rebota.cs",
            "public sealed class ESC_02_rebota : Playthrough {\n public override IEnumerable<Step> Steps(Node main) { yield break; } }",
            "game/Main.cs", "public partial class Main : Node2D { }"));
    assertEquals(Map.of("ESC_02_rebota", false), tests);
}
```

```java
// DevelopmentTeamStrategyTest
@Test void inAGameVeraAlsoWritesPlaythroughs() {
    var rule = DevelopmentTeamStrategy.qaScenarioRule(List.of(new ScenarioCoverage.Scenario("ESC-01", "mueve", 1)), true);
    assertTrue(rule.contains("game/playthroughs/") && rule.contains(": Playthrough") && rule.contains("Press("), rule);
}
```

- [ ] **Step 2: Correr y ver que fallan** — `cd app && mvn test -Dtest=ProjectScaffoldTest,TestInventoryTest,DevelopmentTeamStrategyTest` → FAIL.

- [ ] **Step 3: `GameRunnerFiles` (contenido exacto; el mismo que usó el spike de Task 0)**

```java
package com.aicompany.core.model;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;

import java.util.List;

/**
 * Spec 2026-10-14 Kael §2.3: el runner fijo de partidas simuladas (Forjai, nunca un agente). Vera escribe subclases de
 * Playthrough en game/playthroughs; el paso test del sandbox las corre en Godot headless sobre la escena principal real.
 */
public final class GameRunnerFiles {

    private GameRunnerFiles() {
    }

    static final String PLAYTHROUGH = """
            using System;
            using System.Collections.Generic;
            using Godot;

            namespace Forjai.Playthroughs;

            /// <summary>Partida simulada de un escenario: el nombre de la clase lleva el id (ESC_03_descripcion).</summary>
            public abstract class Playthrough
            {
                /// <param name="main">La instancia nueva de la escena principal (run/main_scene), ya en el árbol.</param>
                public abstract IEnumerable<Step> Steps(Node main);

                /// <summary>Mantiene apretada la acción de input durante frames (a 60 fps fijos).</summary>
                protected static Step Press(string action, int frames) => new(StepKind.Press, action, frames, null, null);

                protected static Step Wait(int frames) => new(StepKind.Wait, null, frames, null, null);

                protected static Step Assert(Func<bool> check, string description) =>
                    new(StepKind.Assert, null, 0, check, description);
            }

            public enum StepKind { Press, Wait, Assert }

            public sealed record Step(StepKind Kind, string? Action, int Frames, Func<bool>? Check, string? Description);
            """;

    static final String RUNNER = """
            using System;
            using System.Collections.Generic;
            using System.Linq;
            using System.Text.Json;
            using System.Threading.Tasks;
            using Godot;

            namespace Forjai.Playthroughs;

            /// <summary>Forjai: corre cada Playthrough sobre una escena principal nueva y escribe los resultados.</summary>
            public partial class PlaythroughRunner : Node
            {
                private const int MaxFrames = 3600;

                private sealed record Result(string name, string outcome, string failedStep, string message);

                private readonly List<Result> _results = new();

                public override async void _Ready()
                {
                    GD.Seed(0);
                    var mainPath = (string)ProjectSettings.GetSetting("application/run/main_scene");
                    var types = typeof(PlaythroughRunner).Assembly.GetTypes()
                        .Where(t => t.IsSubclassOf(typeof(Playthrough)) && !t.IsAbstract)
                        .OrderBy(t => t.Name, StringComparer.Ordinal);
                    foreach (var type in types)
                    {
                        _results.Add(await Run(type, mainPath));
                    }
                    var output = OS.GetCmdlineUserArgs().FirstOrDefault(a => a.StartsWith("--out="))?.Substring(6)
                                 ?? "/work/.forjai/playthroughs.json";
                    System.IO.File.WriteAllText(output, JsonSerializer.Serialize(_results));
                    GetTree().Quit(_results.All(r => r.outcome == "PASSED") ? 0 : 1);
                }

                private async Task<Result> Run(Type type, string mainPath)
                {
                    Node? main = null;
                    var step = 0;
                    var frames = 0;
                    try
                    {
                        main = GD.Load<PackedScene>(mainPath).Instantiate();
                        AddChild(main);
                        await NextFrame();
                        var playthrough = (Playthrough)Activator.CreateInstance(type)!;
                        foreach (var s in playthrough.Steps(main))
                        {
                            step++;
                            switch (s.Kind)
                            {
                                case StepKind.Press:
                                    if (!InputMap.HasAction(s.Action))
                                    {
                                        return Fail(type, step, "la acción \"" + s.Action + "\" no está en [input] de project.godot");
                                    }
                                    Input.ParseInputEvent(new InputEventAction { Action = s.Action!, Pressed = true });
                                    for (var i = 0; i < s.Frames; i++, frames++) await NextFrame();
                                    Input.ParseInputEvent(new InputEventAction { Action = s.Action!, Pressed = false });
                                    break;
                                case StepKind.Wait:
                                    for (var i = 0; i < s.Frames; i++, frames++) await NextFrame();
                                    break;
                                case StepKind.Assert:
                                    if (!s.Check!())
                                    {
                                        return Fail(type, step, "falló la aserción: " + s.Description);
                                    }
                                    break;
                            }
                            if (frames > MaxFrames)
                            {
                                return Fail(type, step, "superó el tope de " + MaxFrames + " frames");
                            }
                        }
                        return new Result(type.Name, "PASSED", "", "");
                    }
                    catch (Exception ex)
                    {
                        return Fail(type, step, ex.GetType().Name + ": " + ex.Message);
                    }
                    finally
                    {
                        foreach (var action in InputMap.GetActions()) Input.ActionRelease(action);
                        main?.QueueFree();
                        await NextFrame();
                    }
                }

                private static Result Fail(Type type, int step, string message) =>
                    new(type.Name, "FAILED", "paso " + step, message);

                private SignalAwaiter NextFrame() => ToSignal(GetTree(), SceneTree.SignalName.PhysicsFrame);
            }
            """;

    static final String RUNNER_SCENE = """
            [gd_scene load_steps=2 format=3]

            [ext_resource type="Script" path="res://forjai/PlaythroughRunner.cs" id="1"]

            [node name="PlaythroughRunner" type="Node"]
            script = ExtResource("1")
            """;

    public static List<GeneratedFile> files() {
        return List.of(new GeneratedFile("game/forjai/Playthrough.cs", PLAYTHROUGH),
                new GeneratedFile("game/forjai/PlaythroughRunner.cs", RUNNER),
                new GeneratedFile("game/forjai/Runner.tscn", RUNNER_SCENE));
    }
}
```

(Si el spike de Task 0 mostró que hace falta otro ajuste — p. ej. `ProcessFrame` en vez de `PhysicsFrame` — aplicar acá lo que funcionó.)

`ProjectScaffold.generate`, dentro de `if (profile == StackProfile.GODOT_DOTNET_GAME) {...}`, después del `Game.csproj`:

```java
files.addAll(GameRunnerFiles.files());
```

y `solution(files)` debe seguir recibiendo solo `.csproj`: cambiar a `files.add(solution(files.stream().filter(f -> f.path().endsWith(".csproj")).toList()));`.

- [ ] **Step 4: `TestInventory`**

```java
private static final Pattern PLAYTHROUGH = Pattern.compile("\\bclass\\s+(\\w+)\\s*:\\s*(?:Forjai\\.Playthroughs\\.)?Playthrough\\b");

public static boolean isTestFile(String path) {
    return (TestTriageValidator.isTestFile(path) && (path.endsWith(".cs") || path.endsWith(".dart")))
            || isPlaythrough(path);
}

/** Spec 2026-10-14 Kael §2.3: las partidas simuladas de Vera cuentan como tests (el nombre de la clase lleva el ESC). */
static boolean isPlaythrough(String path) {
    var p = OwnedPaths.normalize(path);
    return p.endsWith(".cs") && (p.startsWith(StackProfile.PLAYTHROUGHS_ROOT + "/")
            || p.contains("/" + StackProfile.PLAYTHROUGHS_ROOT + "/"));
}
```

y en `of(...)`: `if (isPlaythrough(path)) { var m = PLAYTHROUGH.matcher(content); while (m.find()) tests.put(m.group(1), false); return; }` antes del `if (path.endsWith(".cs"))`.

- [ ] **Step 5: Regla de Vera**

`qaScenarioRule(scenarios)` pasa a `qaScenarioRule(scenarios, false)`; la nueva firma agrega para juegos:

```java
static String qaScenarioRule(List<ScenarioCoverage.Scenario> scenarios, boolean game) {
    if (scenarios.isEmpty()) {
        return "";
    }
    var base = /* texto actual */;
    if (!game) {
        return base;
    }
    return base + """
            PARTIDAS SIMULADAS (juego): además de los tests xUnit de las reglas, escribe en game/playthroughs/ una clase
            por escenario de interacción (los asignados a un control en el diseño de Kael), con el id en el NOMBRE:
              using System.Collections.Generic; using Godot; using Forjai.Playthroughs;
              public sealed class ESC_03_descripcion : Playthrough {
                  public override IEnumerable<Step> Steps(Node main) {
                      yield return Press("move_right", frames: 30);   // solo acciones del diseño de Kael
                      yield return Wait(frames: 10);
                      yield return Assert(() => ((Main)main).Score == 1, "el puntaje es 1");
                  }
              }
            main es la escena principal real recién cargada (60 fps fijos, semilla 0). No escribas en game/forjai/.
            """;
}
```

En `buildWorkPrompt`: `qaScenarioRule(scenarios(context.missionId()), context.plan().hasComponent(StackProfile.GODOT_DOTNET_GAME))`. Actualizar también el mensaje de `ScenarioCoverage.sourceErrors` agregando `"; Godot: clase ESC_03_descripcion : Playthrough en game/playthroughs/"`.

- [ ] **Step 6: Correr y ver que pasan** — `cd app && mvn test -Dtest=ProjectScaffoldTest,TestInventoryTest,DevelopmentTeamStrategyTest,ScenarioCoverageTest,TestSuiteGrowthGateTest` → PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src && git commit -m "Runner de partidas simuladas en el scaffold Godot; las partidas de Vera cuentan como tests

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Sandbox — correr las partidas y sumar sus resultados

**Files:**
- Modify: `sandbox/images/godot-dotnet-game/run.sh`
- Modify: `sandbox/build-images.sh` (tag `:2` para godot)
- Modify: `sandbox-runner/src/main/java/com/forjai/sandbox/ExecutionProfile.java`
- Modify: `sandbox-runner/src/main/java/com/forjai/sandbox/TestReportParser.java`
- Test: `sandbox-runner/src/test/java/com/forjai/sandbox/TestReportParserTest.java`, `ExecutionProfileTest.java`

**Interfaces:**
- Produces: `TestReportParser.parse(Path)` suma `"[juego] " + name` por cada partida de `.forjai/playthroughs.json` (PASSED cuenta en `passed`, FAILED en `failed`); JSON ausente o corrupto → 0 partidas sin excepción. Imagen `localhost/forjai-sandbox/godot-dotnet-game:2`.

- [ ] **Step 1: Tests que fallan** (seguir el estilo de `TestReportParserTest`, que escribe archivos en un `@TempDir`):

```java
@Test void playthroughsAreAddedToTheTrxTests(@TempDir Path dir) throws Exception {
    Files.createDirectories(dir.resolve(".forjai"));
    Files.writeString(dir.resolve(".forjai/results.trx"), """
            <TestRun><Results><UnitTestResult testName="Pong.Tests.ScoreTests.ESC_02_suma" outcome="Passed" /></Results>
            <ResultSummary><Counters total="1" passed="1" failed="0" /></ResultSummary></TestRun>""");
    Files.writeString(dir.resolve(".forjai/playthroughs.json"),
            "[{\"name\":\"ESC_01_mueve\",\"outcome\":\"PASSED\",\"failedStep\":\"\",\"message\":\"\"},"
            + "{\"name\":\"ESC_03_pierde\",\"outcome\":\"FAILED\",\"failedStep\":\"paso 2\",\"message\":\"falló la aserción: game over\"}]");
    var counts = TestReportParser.parse(dir);
    assertEquals(2, counts.passed());
    assertEquals(1, counts.failed());
    assertTrue(counts.tests().contains(new SandboxResult.TestCase("[juego] ESC_01_mueve", "PASSED")));
    assertTrue(counts.tests().contains(new SandboxResult.TestCase("[juego] ESC_03_pierde", "FAILED")));
}

@Test void aMissingOrCorruptPlaythroughReportAddsNothing(@TempDir Path dir) throws Exception {
    Files.createDirectories(dir.resolve(".forjai"));
    Files.writeString(dir.resolve(".forjai/results.trx"), """
            <TestRun><Results><UnitTestResult testName="A.B.C" outcome="Passed" /></Results>
            <ResultSummary><Counters total="1" passed="1" failed="0" /></ResultSummary></TestRun>""");
    assertEquals(1, TestReportParser.parse(dir).tests().size());
    Files.writeString(dir.resolve(".forjai/playthroughs.json"), "[{\"name\":");
    var counts = TestReportParser.parse(dir);
    assertEquals(1, counts.tests().size());
    assertEquals(1, counts.passed());
}
```

`ExecutionProfileTest`: `assertEquals("localhost/forjai-sandbox/godot-dotnet-game:2", ExecutionProfile.GODOT_DOTNET_GAME.image());`

- [ ] **Step 2: Correr y ver que fallan** — `cd sandbox-runner && mvn test -Dtest=TestReportParserTest,ExecutionProfileTest` → FAIL.

- [ ] **Step 3: Implementar el parser**

Renombrar el cuerpo actual de `parse` a `private static TestCounts parseReports(Path workDir)` y:

```java
private static final Pattern PLAYTHROUGH = Pattern.compile(
        "\\{\"name\":\"((?:[^\"\\\\]|\\\\.)*)\",\"outcome\":\"(PASSED|FAILED)\"");

public static TestCounts parse(Path workDir) {
    var base = parseReports(workDir);
    var games = playthroughs(workDir);
    if (games.isEmpty()) {
        return base;
    }
    var tests = new ArrayList<>(base.tests());
    tests.addAll(games);
    var passed = (int) games.stream().filter(t -> "PASSED".equals(t.outcome())).count();
    return new TestCounts(base.passed() + passed, base.failed() + games.size() - passed, tests);
}

/** Spec 2026-10-14 Kael §2.3: partidas simuladas del runner de Forjai; ausente o corrupto = ninguna. */
private static List<SandboxResult.TestCase> playthroughs(Path workDir) {
    try {
        var file = workDir.resolve(".forjai/playthroughs.json");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        var content = Files.readString(file).strip();
        if (!content.startsWith("[") || !content.endsWith("]")) {
            return List.of();
        }
        var out = new ArrayList<SandboxResult.TestCase>();
        var m = PLAYTHROUGH.matcher(content);
        while (m.find()) {
            out.add(new SandboxResult.TestCase("[juego] " + m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"),
                    m.group(2)));
        }
        return out;
    } catch (IOException | RuntimeException ex) {
        return List.of();
    }
}
```

`ExecutionProfile`: `GODOT_DOTNET_GAME("localhost/forjai-sandbox/godot-dotnet-game:2", true)`.

- [ ] **Step 4: `run.sh` (paso test)**

Reemplazar la línea `test)` por:

```bash
  test)
    make_sln
    rm -f /work/.forjai/playthroughs.json
    set +e
    checked dotnet test "$SLN" --no-build -c Debug --logger "trx;LogFileName=results.trx" --results-directory /work/.forjai
    rc=$?
    play=0
    # Spec 2026-10-14 Kael §2.3: las partidas simuladas de Vera sobre la escena principal real.
    if [ -n "$(find /work/game/playthroughs -name '*.cs' -print -quit 2>/dev/null)" ]; then
      godot --headless --path /work/game --import > /tmp/import.log 2>&1
      godot --headless --fixed-fps 60 --path /work/game res://forjai/Runner.tscn -- --out=/work/.forjai/playthroughs.json \
        > /tmp/play.log 2>&1
      play=$?
      cat /tmp/play.log
    fi
    set -e
    [ "$rc" -eq 0 ] && [ "$play" -eq 0 ] ;;
```

(Si el spike mostró que `--import` no hace falta o no existe en 4.3, quitar esa línea.) `build-images.sh`: el tag de godot pasa a `:2` (las otras imágenes siguen en `:1`):

```bash
for profile in dotnet-app flutter-web-app egress-proxy; do
  echo "== $profile"; podman build -t "localhost/forjai-sandbox/$profile:1" "$profile"
done
echo "== godot-dotnet-game"; podman build -t "localhost/forjai-sandbox/godot-dotnet-game:2" godot-dotnet-game
```

- [ ] **Step 5: Correr tests y construir la imagen**

Run: `cd sandbox-runner && mvn test` → PASS.
Run: `cd sandbox/images && podman build -t localhost/forjai-sandbox/godot-dotnet-game:2 godot-dotnet-game` → OK.
Verificar con el proyecto del spike (Task 0) copiado a un directorio de trabajo: `podman run --rm --network=none -v $S:/work:Z localhost/forjai-sandbox/godot-dotnet-game:2 /forjai/run.sh build && … run.sh test` → rc 0 y `.forjai/playthroughs.json` con la partida.

- [ ] **Step 6: Commit**

```bash
git add sandbox sandbox-runner && git commit -m "Sandbox Godot: el paso test corre las partidas simuladas y el runner suma sus resultados

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: Chequeo `DESIGN_CONFORMANCE`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/DesignConformance.java`
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (los dos sitios que agregan `ScenarioCoverage.check`, líneas ~488-500 y ~707-716; setter del diseño)
- Test: `app/src/test/java/com/aicompany/core/agent/validation/DesignConformanceTest.java`

**Interfaces:**
- Consumes: `InteractionDesign`, `InteractionDesignText.screensOf`, `scenarioTargets`; tests con nombre del sandbox.
- Produces: `DesignConformance.CHECK = "DESIGN_CONFORMANCE"`; `DesignConformance.check(InteractionDesign design, RequirementsSpec spec, int upToIncrement, Map<String,String> headFiles, List<SandboxResult.TestCase> tests) → StaticCheck`; `DevelopmentTeamStrategy.setInteractionDesign(InteractionDesignMemoryService)`.

- [ ] **Step 1: Tests que fallan**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.InteractionDesign.*;
import com.aicompany.core.model.SandboxResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DesignConformanceTest {

    static final InteractionDesign APP = new InteractionDesign("APP", new AppDesign(
            List.of(new Screen("PANT-01", "Lista", "ver", "vendedor", List.of())),
            List.of(new Flow("HU-01", List.of("PANT-01"), List.of())),
            List.of(new ScenarioScreen("ESC-01", "PANT-01")), List.of("r")), null);

    @Test void everyScreenNeedsItsKeyInTheApp() {
        var spec = InteractionDesignTextTest.SPEC;
        var fail = DesignConformance.check(APP, spec, 1, Map.of("lib/main.dart", "void main() {}"), List.of());
        assertFalse(fail.passed(), fail.detail());
        assertTrue(fail.detail().contains("PANT-01"));
        var pass = DesignConformance.check(APP, spec, 1,
                Map.of("app/lib/clientes/presentation/lista.dart", "return Scaffold(key: const Key('PANT-01'));"), List.of());
        assertTrue(pass.passed(), pass.detail());
    }

    static final InteractionDesign GAME = new InteractionDesign("GAME", null, new GameDesign(List.of("PLAYING"),
            List.of(), List.of(), List.of(new Control("move_right", "Right", "x")),
            List.of(new Rule("REGLA-01", "move_right", "avanza")), List.of(), List.of("REGLA-01"), List.of(),
            List.of(new ScenarioRule("ESC-01", "move_right")), List.of("h")));

    @Test void gameActionsMustBeDeclaredAndInteractionScenariosNeedAPassingPlaythrough() {
        var spec = InteractionDesignTextTest.SPEC;
        var godot = "[application]\nrun/main_scene=\"res://Main.tscn\"\n[input]\nmove_right={\n\"deadzone\": 0.5\n}\n";
        var playthrough = "yield return Press(\"move_right\", frames: 10);";
        var pass = DesignConformance.check(GAME, spec, 1, Map.of("game/project.godot", godot,
                "game/playthroughs/ESC_01_mueve.cs", playthrough),
                List.of(new SandboxResult.TestCase("[juego] ESC_01_mueve", "PASSED")));
        assertTrue(pass.passed(), pass.detail());

        var undeclared = DesignConformance.check(GAME, spec, 1, Map.of("game/project.godot", "[input]\n",
                "game/playthroughs/ESC_01_mueve.cs", playthrough),
                List.of(new SandboxResult.TestCase("[juego] ESC_01_mueve", "PASSED")));
        assertTrue(undeclared.detail().contains("move_right") && !undeclared.passed());

        var foreignAction = DesignConformance.check(GAME, spec, 1, Map.of("game/project.godot", godot,
                "game/playthroughs/ESC_01_mueve.cs", "Press(\"jump\", frames: 1)"),
                List.of(new SandboxResult.TestCase("[juego] ESC_01_mueve", "PASSED")));
        assertTrue(foreignAction.detail().contains("jump") && !foreignAction.passed());

        var noPlaythrough = DesignConformance.check(GAME, spec, 1, Map.of("game/project.godot", godot),
                List.of(new SandboxResult.TestCase("Pong.Tests.ESC_01_mueve", "PASSED")));
        assertTrue(noPlaythrough.detail().contains("ESC-01") && !noPlaythrough.passed());
    }
}
```

(Ajustar `passed()`/`detail()` a los accesores reales de `StaticCheck`; ver `app/src/main/java/com/aicompany/core/model/StaticCheck.java`.)

- [ ] **Step 2: Correr y ver que falla** — `cd app && mvn test -Dtest=DesignConformanceTest` → FAIL.

- [ ] **Step 3: Implementar**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.InteractionDesign;
import com.aicompany.core.agent.model.RequirementsSpec;
import com.aicompany.core.model.SandboxResult;
import com.aicompany.core.model.StaticCheck;

import java.util.*;
import java.util.regex.Pattern;

/** Spec 2026-10-14 Kael §1.4: el código sigue el diseño aprobado (Java, sin modelo). */
public final class DesignConformance {

    public static final String CHECK = "DESIGN_CONFORMANCE";
    private static final Pattern PRESS = Pattern.compile("Press\\(\\s*\"([^\"]+)\"");
    private static final Pattern INPUT_ACTION = Pattern.compile("^([A-Za-z0-9_]+)=");

    private DesignConformance() {
    }

    public static StaticCheck check(InteractionDesign design, RequirementsSpec spec, int upTo,
                                    Map<String, String> headFiles, List<SandboxResult.TestCase> tests) {
        var problems = new ArrayList<String>();
        if (design.app() != null) {
            var dart = headFiles.entrySet().stream().filter(e -> e.getKey().endsWith(".dart")
                    && (e.getKey().startsWith("lib/") || e.getKey().contains("/lib/"))).map(Map.Entry::getValue).toList();
            for (int n = 1; n <= upTo; n++) {
                for (var screen : InteractionDesignText.screensOf(design, spec, n)) {
                    var key = Pattern.compile("Key\\(\\s*['\"]" + Pattern.quote(screen) + "['\"]\\s*\\)");
                    if (dart.stream().noneMatch(c -> c != null && key.matcher(c).find())) {
                        problems.add(screen + " sin Key('" + screen + "') en la app");
                    }
                }
            }
        }
        if (design.game() != null) {
            var declared = inputActions(headFiles.entrySet().stream()
                    .filter(e -> e.getKey().endsWith("game/project.godot")).map(Map.Entry::getValue).findFirst().orElse(""));
            var designed = design.game().controls().stream().map(InteractionDesign.Control::action).toList();
            designed.stream().filter(a -> !declared.contains(a)).forEach(a ->
                    problems.add("la acción " + a + " no está en [input] de game/project.godot"));
            headFiles.forEach((path, content) -> {
                if (path.contains("game/playthroughs/") && content != null) {
                    var m = PRESS.matcher(content);
                    while (m.find()) {
                        if (!designed.contains(m.group(1))) {
                            problems.add(path + " usa la acción " + m.group(1) + ", que no está en el diseño " + designed);
                        }
                    }
                }
            });
            var passingGames = tests.stream().filter(t -> t.name().startsWith("[juego] ") && "PASSED".equals(t.outcome()))
                    .flatMap(t -> ScenarioIds.inTestName(t.name()).stream()).collect(java.util.stream.Collectors.toSet());
            var scenarios = new LinkedHashSet<String>();
            for (int n = 1; n <= upTo; n++) {
                scenarios.addAll(InteractionDesignText.scenariosOf(spec, n));
            }
            var targets = InteractionDesignText.scenarioTargets(design);
            scenarios.stream().filter(id -> designed.contains(targets.get(id)) && !passingGames.contains(id))
                    .forEach(id -> problems.add(id + " es de interacción (" + targets.get(id)
                            + ") y no tiene una partida simulada aprobada"));
        }
        return problems.isEmpty()
                ? StaticCheck.pass(CHECK, "El código sigue el diseño aprobado de Kael.", null, List.of())
                : StaticCheck.fail(CHECK, String.join("; ", problems) + ".", null, List.of());
    }

    static Set<String> inputActions(String projectGodot) {
        var actions = new LinkedHashSet<String>();
        var inInput = false;
        for (var line : projectGodot.split("\\R")) {
            var t = line.strip();
            if (t.startsWith("[")) {
                inInput = t.equals("[input]");
                continue;
            }
            var m = INPUT_ACTION.matcher(t);
            if (inInput && m.find()) {
                actions.add(m.group(1));
            }
        }
        return actions;
    }
}
```

(`InteractionDesignText.scenariosOf` es package-private en Task 3: subirlo a `public`.)

`DevelopmentTeamStrategy`: setter opcional `setInteractionDesign(InteractionDesignMemoryService designs)` y un método

```java
/** Spec 2026-10-14 Kael §1.4: con diseño aprobado, el chequeo DESIGN_CONFORMANCE junto a la cobertura. */
private Optional<StaticCheck> designConformance(String missionId, String headSha, List<SandboxResult.TestCase> tests) {
    if (designs == null || requirementsMemory == null || headSha == null) {
        return Optional.empty();
    }
    var approved = designs.approved(missionId);
    var spec = requirementsMemory.approved(missionId);
    if (approved.isEmpty() || spec.isEmpty()) {
        return Optional.empty();
    }
    var design = jsonMapper.readValue(approved.get().designJson(), InteractionDesign.class);
    var requirements = jsonMapper.readValue(spec.get().specJson(), RequirementsSpec.class);
    var files = new LinkedHashMap<String, String>();
    for (var path : workspace.filesAtCommit(missionId, headSha)) {
        if (path.endsWith(".dart") || path.endsWith("project.godot") || path.contains("game/playthroughs/")) {
            files.put(path, workspace.readFileAtCommit(missionId, headSha, path));
        }
    }
    return Optional.of(DesignConformance.check(design, requirements,
            Math.max(1, requirementsMemory.currentIncrement(missionId)), files, tests));
}
```

En los dos sitios que hoy hacen `checks.add(ScenarioCoverage.check(rows))`, agregar a continuación:

```java
designConformance(missionId, headSha, ScenarioCoverage.componentTests(sandboxResult)).ifPresent(checks::add);
```

`StaticValidationStatus.compute` ya marca `FAILED` con cualquier chequeo en `FAIL`; la corrección automática (misiones durables) toma el detalle como error persistente. Verificar en `CorrectionRequest`/el armado de la ronda de corrección (`grep -n "SCENARIO_COVERAGE" app/src/main/java -r`) que un chequeo en `FAIL` que no es de compilación se reparte al dueño del archivo; si `SCENARIO_COVERAGE` tiene un ruteo explícito a Vera, agregar `DESIGN_CONFORMANCE` con ruteo: pantallas → dueño de `PRESENTATION` (Mila), acciones de `project.godot` → dueño de `GAME` (Kael), partidas → Vera.

- [ ] **Step 4: Correr y ver que pasa** — `cd app && mvn test -Dtest=DesignConformanceTest,DevelopmentTeamStrategyTest` → PASS; `mvn test` completo → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "DESIGN_CONFORMANCE: pantallas con su Key, acciones declaradas y partidas por escenario de interacción

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Chat

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java` (`handleRequirements` ~1134; patrones ~1122; status ~2093; `requirementsAdjustment` ~484)
- Test: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `MissionService.interactionDesign`, `RequirementsDecisionCommand.Target.KAEL`.
- Produces: frases "muéstrame el diseño de MISSION-X", "para MISSION-X, Kael: …"; la aprobación de HU responde "Requisitos y diseño de X aprobados" cuando hay diseño; el status lista "HU + diseño".

- [ ] **Step 1: Tests que fallan** (copiar el armado de los tests de requisitos existentes en `ChatIntentRouterTest`; buscar `decideRequirements` en la clase de test):

```java
@Test void showsTheDesignWithoutCallingAModel() {
    when(missionService.interactionDesign("MISSION-7")).thenReturn(Optional.of(new InteractionDesignView("MISSION-7", 2,
            "DRAFT", 1, null, null, "Loop: START→PLAYING")));
    var reply = router.route("muéstrame el diseño de MISSION-7");
    assertTrue(reply.contains("Loop: START→PLAYING") && reply.contains("versión 2"), reply);
    verifyNoInteractions(ceoService);
}

@Test void anAdjustmentForKaelIsRoutedToKael() {
    router.route("para MISSION-7, Kael: la paleta más grande");
    verify(missionService).decideRequirements("MISSION-7", new RequirementsDecisionCommand(
            RequirementsDecisionCommand.RequirementsDecision.ADJUST, "la paleta más grande",
            RequirementsDecisionCommand.Target.KAEL));
}

@Test void anAdjustmentWithoutKaelStillGoesToAria() {
    router.route("para MISSION-7: sin reportes");
    verify(missionService).decideRequirements("MISSION-7", new RequirementsDecisionCommand(
            RequirementsDecisionCommand.RequirementsDecision.ADJUST, "sin reportes"));
}
```

(Usar el método de entrada real del router — `route`, `handle` o el que usan los tests de requisitos — y los mocks ya presentes en la clase.)

- [ ] **Step 2: Correr y ver que fallan** — `cd app && mvn test -Dtest=ChatIntentRouterTest` → FAIL.

- [ ] **Step 3: Implementar**

Patrones (junto a `SHOW_STORIES`):

```java
private static final Pattern SHOW_DESIGN = Pattern.compile(
        "(?i)\\b(?:muestrame|mostrame|ver|dame|cual es)\\s+(?:\\S+\\s+){0,2}?dise(?:n|ñ)o\\b.*?(" + MISSION_REF + ")");
private static final Pattern ADJUST_KAEL = Pattern.compile(
        "(?i)^\\s*para\\s+(" + MISSION_REF + ")\\s*,\\s*kael\\s*:\\s*(.+)$", Pattern.DOTALL);
```

En `handleRequirements`, **antes** de `SHOW_STORIES` (la frase "muéstrame el diseño" no tiene "hu/historias/requisitos", pero va primero por claridad):

```java
var showDesign = SHOW_DESIGN.matcher(normalized);
if (showDesign.find()) {
    var missionId = showDesign.group(1).toUpperCase(Locale.ROOT);
    return missionService.interactionDesign(missionId)
            .map(v -> "Diseño de Kael de " + missionId + " (versión " + v.version() + ", " + v.status() + "): " + v.summary())
            .orElse(missionId + " no tiene diseño de Kael.");
}
var adjustKael = ADJUST_KAEL.matcher(message);
if (adjustKael.find()) {
    var missionId = adjustKael.group(1).toUpperCase(Locale.ROOT);
    missionService.decideRequirements(missionId, new RequirementsDecisionCommand(
            RequirementsDecisionCommand.RequirementsDecision.ADJUST, adjustKael.group(2).strip(),
            RequirementsDecisionCommand.Target.KAEL));
    return "Le pasé el ajuste a Kael; te aviso cuando el diseño de " + missionId + " esté listo.";
}
```

Verificar que la regla que decide si un mensaje entra a `handleRequirements` (buscar dónde se llama, línea ~491 usa `REQUIREMENTS_WORD` y `requirementsAdjustment`) también deja pasar `SHOW_DESIGN` y `ADJUST_KAEL`; agregar ambas condiciones ahí. `ADJUST_STORIES` (`para MISSION-X: …`) no matchea `para MISSION-X, Kael: …` porque exige `:` justo tras el id — confirmarlo con el test `anAdjustmentWithoutKaelStillGoesToAria`.

Aprobación: el texto pasa a `"Requisitos" + (missionService.interactionDesign(missionId).map(v -> "DRAFT".equals(v.status()) || "APPROVED".equals(v.status())).orElse(false) ? " y diseño" : "") + " de " + missionId + " aprobados: Neo planifica el incremento 1."` (consultar **antes** de decidir, para saber si había diseño).

Status (~2093, `awaitingSpec`): agregar `" (HU + diseño de Kael)"` a la línea de cada misión con `interactionDesign` presente; y donde se lista la cobertura de misiones `AWAITING_INVESTOR`, agregar el detalle del chequeo `DESIGN_CONFORMANCE` si existe en la última validación (mismo origen que `SCENARIO_COVERAGE`; buscar cómo se lee ese chequeo en el status y repetirlo con `DesignConformance.CHECK`).

- [ ] **Step 4: Correr y ver que pasan** — `cd app && mvn test -Dtest=ChatIntentRouterTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "Chat: ver el diseño de Kael, ajustarlo y verlo en el status

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: Command Center — sección "Diseño (Kael)"

**Files:**
- Modify: `app/frontend/src/api/types.ts`, `app/frontend/src/api/client.ts`, `app/frontend/src/pages/MissionDetailPage.tsx`

**Interfaces:**
- Consumes: `GET /api/company/missions/{id}/interaction-design`; `POST …/requirements/decision` con `target`.

- [ ] **Step 1: Tipos** (en `types.ts`, junto a los de requisitos, línea ~600):

```ts
// Spec 2026-10-14 Kael: diseño de interacción (InteractionDesign / InteractionDesignView en Java).
export interface DesignScreen { id: string; name: string; purpose: string; role: string; states: string[] }
export interface DesignFlow { story: string; screens: string[]; actions: string[] }
export interface AppDesign {
  screens: DesignScreen[]
  flows: DesignFlow[]
  scenarioScreens: { scenario: string; screen: string }[]
  frictionRisks: string[]
}
export interface DesignProperty { name: string; value: number | null }
export interface GameDesign {
  states: string[]
  transitions: { from: string; to: string; trigger: string }[]
  entities: { id: string; name: string; properties: DesignProperty[] }[]
  controls: { action: string; key: string; effect: string }[]
  rules: { id: string; condition: string; effect: string }[]
  win: string[]
  lose: string[]
  difficulty: DesignProperty[]
  scenarioRules: { scenario: string; target: string }[]
  balanceHypotheses: string[]
}
export interface InteractionDesign { experience: 'APP' | 'GAME'; app: AppDesign | null; game: GameDesign | null }
export interface InteractionDesignView {
  missionId: string
  version: number | null
  status: string | null
  specVersion: number | null
  approvedVersion: number | null
  design: InteractionDesign | null
  summary: string
}
export type AdjustTarget = 'ARIA' | 'KAEL'
```

Agregar `experience?: 'APP' | 'GAME' | 'NONE' | null` al tipo del spec de requisitos.

- [ ] **Step 2: Cliente** (`client.ts`):

```ts
interactionDesign: (missionId: string) =>
  request<InteractionDesignView>(`/api/company/missions/${missionId}/interaction-design`),
```

y `decideRequirements(missionId, decision, instruction?, target: AdjustTarget = 'ARIA')` enviando `{ decision, instruction, target }`.

- [ ] **Step 3: Pantalla** (`MissionDetailPage.tsx`)

1. En `MissionRequirements`, mostrar `Tipo: {spec.experience}` bajo la versión cuando exista.
2. Nuevo componente `MissionDesign({ missionId })` con `useQuery(['interactionDesign', missionId], …, { retry: false, refetchInterval: 10_000 })`; si no hay `view?.design` devuelve `null`. Muestra `<h2>Diseño (Kael)</h2>`, `Versión {version} — {status}`; para `app`: lista de pantallas (`PANT-NN nombre (rol): propósito — estados`), flujos (`HU-NN: PANT-01 → PANT-02`) y riesgos como "Hipótesis"; para `game`: estados/transiciones, controles (`action [key]: effect`), entidades con sus números, reglas (`REGLA-NN: SI … ENTONCES …`), gana/pierde, dificultad e hipótesis de balance.
3. Renderizarlo justo debajo de `<MissionRequirements …/>`.
4. En el bloque de botones de `AWAITING_SPEC_APPROVAL`, agregar un `<select>` "Ajuste para" con `Aria`/`Kael` (estado `target`, default `ARIA`; `Kael` solo si hay diseño — leer la misma query `['interactionDesign', missionId]`) y pasar `target` en `decide.mutate('ADJUST')`. El placeholder del textarea pasa a `Qué debe cambiar {target === 'KAEL' ? 'Kael' : 'Aria'}`. Al decidir, invalidar también `['interactionDesign', missionId]`.

- [ ] **Step 4: Verificar** — `cd app/frontend && npm run lint && npm run build` → sin errores.

- [ ] **Step 5: Commit**

```bash
git add app/frontend && git commit -m "Command Center: diseño de Kael en Missions y ajuste dirigido a Aria o Kael

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 15: Prompt de Kael v3, modelo y documentación

**Files:**
- Modify: `docs/ARCHITECTURE.md` (Aria, Development Group, Sandbox, Chat, Command Center), `docs/EVENTS.md`, `docs/FALTANTES.md`, `docs/HISTORY.md`

- [ ] **Step 1: Docs**

- `ARCHITECTURE.md`: en "Aria v1" agregar `experience`; nuevo párrafo **"Kael: diseño de interacción y de juego"** tras Aria (flujo, tarea `-INTERACTION`, validador, versiones, aprobación conjunta, ajuste dirigido, retome `DESIGN`/`DESIGN_ADJUSTMENT`, `ExperienceRule`, `DESIGN_CONFORMANCE`); en "Development Group" cambiar la lista de habilitados (Kael/`GAME_DEV` habilitado, `GODOT_DOTNET_GAME` en fase 1, respaldo de Domain/Application, `game/playthroughs` de Vera, `game/forjai` de Forjai, los 4 gates); en "Sandbox" el paso `test` de Godot con partidas, imagen `:2`, `[juego]` en los nombres.
- `EVENTS.md`: `EMPRESA_INTERACTION_DESIGN_READY` (`version`, `specVersion`), `EMPRESA_INTERACTION_DESIGN_ADJUSTED` (`version`, `instruction`); `EMPRESA_REQUIREMENTS_APPROVED` con `interactionDesignVersion` opcional.
- `FALTANTES.md`: marcar `[x]` "Habilitar a Kael" con la rama `kael-hibrido`; agregar filas: assets con Orion/Luna; Game UI de Mila (HUD/menús en Godot); exportar el juego para que lo pruebe el fundador; juegos 3D; juego + backend en una misión; balance/engagement con datos reales (Gael); prompts activos vacíos de Aria, Orion y Andrea; prompt de Mila dice React/Vue y genera Flutter.
- `HISTORY.md`: sección "Kael híbrido (2026-10-14)" con las 7 decisiones del fundador del spec y el resultado del spike (Task 0).

- [ ] **Step 2: Commit de docs**

```bash
git add docs && git commit -m "Docs: Kael híbrido (diseño de apps y juegos, juegos 2D con partidas simuladas)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 3: Desplegar (solo con OK del fundador y sin misiones vivas — memoria "no reiniciar con misión viva")**

1. Consultar misiones vivas en un paso aparte: `curl -s localhost:8081/api/company/missions | python3 -c "import json,sys;print([m['missionId'] for m in json.load(sys.stdin) if m['status'] in ('PLANNING','DELEGATING','WAITING_AGENT_RESULTS','EVALUATING','CONSOLIDATING')])"` → debe ser `[]`.
2. `bash sandbox/build-images.sh` (crea `godot-dotnet-game:2`).
3. `docker compose build && docker compose up -d`.
4. Prompt de Kael v3 por la API de prompts (`PUT /api/company/agents/interaction-design/prompt` o el endpoint real que usa el Command Center para versionar; ver `AgentController`), con el texto de v2 más:

```
En el Development Group además programas juegos 2D en Godot 4.3 con C#:
- escribes la capa GAME (game/: project.godot, la escena principal y sus scripts) siguiendo tu diseño aprobado;
- sin assets externos: dibujas con nodos (ColorRect, Polygon2D, Label) o en _Draw();
- declaras en [input] de project.godot cada acción de tus controles;
- lees el input SOLO con las acciones de [input] (Input.IsActionPressed / GetAxis), nunca con teclas directas;
- no usas GetTree().Quit(), ChangeSceneToFile ni ReloadCurrentScene, ni rutas /root/…: el reinicio tras game over se hace dentro de la escena;
- expones en el script de la escena principal, como propiedades públicas, el estado que se verifica (puntaje, estado del loop);
- el azar entra a las reglas como dependencia con semilla (nunca new Random() en domain/application);
- no escribes en game/playthroughs (Vera) ni en game/forjai (Forjai).
```

5. Modelo de Kael: `PUT /api/company/agents/interaction-design/model` con el mismo `model`, `paidFallbackModel` y `fallbackModel` que Iris (`curl -s localhost:8081/api/company/agents | jq '.[] | select(.id=="backend")'`).
6. `GET /actuator/health` → `UP`.

---

### Task 16: Prueba en vivo — app (CRM)

- [ ] **Step 1**: Por chat: "crea una misión del equipo TEAM-DEVELOPMENT: un CRM simple para registrar clientes y verlos en una lista" (la frase real que crea misiones de equipo; ver `HISTORY.md` de Aria v1).
- [ ] **Step 2**: Esperar `AWAITING_SPEC_APPROVAL`; verificar en Missions "Requisitos (Aria)" con `Tipo: APP` y "Diseño (Kael)" con pantallas y flujos; "muéstrame el diseño de MISSION-X" en el chat.
- [ ] **Step 3**: Probar "para MISSION-X, Kael: <ajuste>" → nueva versión del diseño, mismas HU; luego "apruebo las HU de MISSION-X".
- [ ] **Step 4**: Esperar `AWAITING_INVESTOR`: validación `VERIFIED`, `SCENARIO_COVERAGE` 100 %, `DESIGN_CONFORMANCE` en PASS. Si falla, diagnosticar con `superpowers:systematic-debugging` antes de tocar código.
- [ ] **Step 5**: Registrar en `HISTORY.md` (misión, costo `GET …/model-cost`, rondas, resultado) y commit `Docs: prueba en vivo de Kael en una app`.

### Task 17: Prueba en vivo — juego (Pong)

- [ ] **Step 1**: Por chat, al Development Group: "un Pong de un jugador contra la pared: la paleta se mueve con las flechas, la pelota rebota y el puntaje sube en cada rebote; si la pelota pasa, game over".
- [ ] **Step 2**: Verificar `Tipo: GAME`, diseño con loop/controles/reglas/números; aprobar HU + diseño.
- [ ] **Step 3**: Verificar el plan de Neo: `GODOT_DOTNET_GAME`, Kael con `GAME` (+ Domain/Application si no está Iris), Vera con `game/playthroughs`.
- [ ] **Step 4**: Esperar `AWAITING_INVESTOR` → `VERIFIED`, `SCENARIO_COVERAGE` 100 % (con tests `[juego] ESC_…` PASSED), `DESIGN_CONFORMANCE` PASS. En el workspace (`git -c safe.directory='*' log` en `~/forjai-products/<misión>`): commits de Forjai (scaffold con `game/forjai/`), Kael, Vera.
- [ ] **Step 5**: Registrar en `HISTORY.md` (misión, costo, rondas, partidas) y commit `Docs: prueba en vivo de Kael en un juego 2D`. Actualizar la memoria de proyecto del estado de la rama.
