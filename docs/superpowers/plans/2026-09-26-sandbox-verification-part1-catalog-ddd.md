# Sandbox de verificación — Parte 1: catálogo de stacks, contrato DDD y chequeos de capas — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Toda misión de Engineering declara un perfil de stack del catálogo, sus bounded contexts y su lenguaje ubicuo, y la capa 1 de validación verifica de forma determinista la estructura DDD del perfil, sus archivos de entrada y el sentido de las dependencias entre capas (C# y Dart).

**Architecture:** `StackProfile` (enum, catálogo fijo en código) es dueño de la parte estructural de cada perfil. `TeamPlan` gana `stackProfile`, `boundedContexts` y `ubiquitousLanguage`, exigidos por `TeamPlanValidator`. Dos verificadores puros y deterministas, `ProfileStructureChecker` y `DddLayerChecker`, se integran en `StaticWorkspaceValidator` y reemplazan al chequeo `ENTRY_POINT`. No hay sandbox todavía: esto es la parte 1 de 3 (parte 2: `sandbox-runner` + `VERIFY`; parte 3: dependencias gobernadas).

**Tech Stack:** Java 21, Spring Boot 4.1.1, Jackson 3 (`tools.jackson.*`), JUnit 5 + Mockito, `git` real vía `GitCommandRunner`.

**Spec:** `docs/superpowers/specs/2026-09-26-sandbox-verification-design.md` (secciones 1 y 4, parte estructural)

## Global Constraints

- Rama: `sandbox-verification`. Cada commit termina con `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Maven desde `app/`: `mvn test -Dtest=Clase`; suite completa `mvn test` (baseline 321 tests en verde).
- Jackson `tools.jackson.*`, nunca `com.fasterxml.jackson.*`. Sin `@Async`. Nunca `format`+`tools` en la misma llamada.
- Perfiles de la primera ola, ids exactos: `DOTNET_APP`, `GODOT_DOTNET_GAME`, `FLUTTER_WEB_APP`. Catálogo fijo en código; el modelo nunca lo amplía.
- Nombres de bounded context: `[A-Z][A-Za-z0-9]*` en `DOTNET_APP`/`GODOT_DOTNET_GAME`; `[a-z][a-z0-9_]*` en `FLUTTER_WEB_APP`.
- Glosario (`ubiquitousLanguage`): al menos 3 términos con definición.
- Frameworks prohibidos en la capa `domain`: `Godot`, `Microsoft.AspNetCore` (perfiles .NET); `package:flutter/`, `dart:ui`, `dart:html` (Flutter).
- Reglas de capas: `domain` solo depende de su propio `domain`; `application` de su `domain` y de `application` de cualquier contexto; `infrastructure`/`api`/`presentation` de `domain`/`application` de su contexto, de su misma capa, y de `application` de otros contextos; `game` (compartido) de `domain`/`application` de cualquier contexto y de `game`; `tests` sin restricción; los archivos de entrada (composition root, p. ej. `lib/main.dart`) están exentos.
- `StaticCheck` nuevos: `STACK_PROFILE`, `ENTRY_FILES`, `PROFILE_STRUCTURE`, `DDD_LAYERS`. Reemplazan a `ENTRY_POINT`. Cualquier `FAIL` hace fallar la validación (regla existente de `StaticValidationStatus`).
- Los equipos de análisis (Creative, Marketing) no cambian de comportamiento: dejan `stackProfile` vacío y listas vacías.
- `*MemoryService` sin test directo (convención del proyecto).

## Review Focus

- `using` de C# con alias, `static` o `global` (`using D = Combate.Domain;`, `global using static Godot.GD;`) debe detectarse igual que un `using` simple — test en Task 5.
- Import relativo de Dart con `../` que cruza al `domain` de otro contexto debe marcarse como violación — test en Task 6.
- `lib/main.dart` (composition root) importa todas las capas y no debe marcarse como violación — test en Task 6.
- Un contexto declarado en minúsculas en un perfil .NET (`combate`) produce rutas que no coinciden con `src/Combate.Domain`: el validador debe rechazar el nombre con una corrección explícita — test en Task 2.
- Un `ownedPath` padre de la estructura (`src`) cubre cualquier cosa: debe rechazarse por estar fuera de la estructura del perfil — test en Task 2.

---

## File Structure

- Create `app/src/main/java/com/aicompany/core/model/StackProfile.java` — catálogo estructural (capas, raíces, archivos de entrada, frameworks prohibidos, ubicación de un archivo).
- Modify `app/src/main/java/com/aicompany/core/agent/model/TeamPlan.java` + `TeamPlanSchema.java` — campos DDD.
- Modify `app/src/main/java/com/aicompany/core/agent/validation/TeamPlanValidator.java` — reglas DDD/perfil; se eliminan las reglas de `techStack`/`entryPoint`.
- Modify `app/src/main/java/com/aicompany/core/service/TeamWorkPlanner.java` — prompt con el catálogo; `normalizeActions` conserva los campos nuevos.
- Create `app/src/main/java/com/aicompany/core/agent/validation/ProfileStructureChecker.java` — `ENTRY_FILES`, `PROFILE_STRUCTURE`.
- Create `app/src/main/java/com/aicompany/core/agent/validation/DddLayerChecker.java` — `DDD_LAYERS` (C# y Dart).
- Modify `app/src/main/java/com/aicompany/core/service/StaticWorkspaceValidator.java` — integra los checkers, nueva firma.
- Modify `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` — prompts y reportes con perfil/contextos/glosario; nueva llamada al validador.
- Docs: `CLAUDE.md`, `docs/HISTORY.md`.

---

### Task 1: Catálogo `StackProfile`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/StackProfile.java`
- Test: `app/src/test/java/com/aicompany/core/model/StackProfileTest.java`

**Interfaces:**
- Consumes: `OwnedPaths.normalize(String)`, `OwnedPaths.covers(String owned, String path)` (existentes, `com.aicompany.core.agent.validation`).
- Produces: `enum StackProfile {DOTNET_APP, GODOT_DOTNET_GAME, FLUTTER_WEB_APP}` con tipos anidados `enum Ecosystem {NUGET, PUB}`, `enum Layer {DOMAIN, APPLICATION, INFRASTRUCTURE, API, PRESENTATION, GAME, TESTS}` (`boolean isOuter()`), `record Location(String context, Layer layer)`; métodos `static Optional<StackProfile> parse(String)`, `String usage()`, `Ecosystem ecosystem()`, `String sourceExtension()`, `List<String> forbiddenDomainDependencies()`, `boolean isValidContextName(String)`, `String contextNameRule()`, `List<String> allowedRoots(List<String> contexts)`, `boolean isEntryFile(String path)`, `List<String> missingEntryFiles(Collection<String> files)`, `boolean isWithinStructure(String path, List<String> contexts)`, `Optional<Location> locate(String path, List<String> contexts)`, `String describe()`, `static String describeAll()`.

- [ ] **Step 1: Escribir el test (falla: la clase no existe)**

```java
package com.aicompany.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class StackProfileTest {

    @Test
    void parsesOnlyCatalogIds() {
        assertEquals(Optional.of(StackProfile.GODOT_DOTNET_GAME), StackProfile.parse("GODOT_DOTNET_GAME"));
        assertEquals(Optional.empty(), StackProfile.parse("UNITY_GAME"));
        assertEquals(Optional.empty(), StackProfile.parse(""));
        assertEquals(Optional.empty(), StackProfile.parse(null));
    }

    @Test
    void contextNameRulesDependOnTheLanguage() {
        assertTrue(StackProfile.DOTNET_APP.isValidContextName("Combate"));
        assertFalse(StackProfile.DOTNET_APP.isValidContextName("combate"));
        assertTrue(StackProfile.FLUTTER_WEB_APP.isValidContextName("combate_naval"));
        assertFalse(StackProfile.FLUTTER_WEB_APP.isValidContextName("Combate"));
    }

    @Test
    void locatesFilesByContextAndLayer() {
        var contexts = List.of("Combate", "Inventario");
        assertEquals(Optional.of(new StackProfile.Location("Combate", StackProfile.Layer.DOMAIN)),
                StackProfile.GODOT_DOTNET_GAME.locate("src/Combate.Domain/Unidad.cs", contexts));
        assertEquals(Optional.of(new StackProfile.Location(null, StackProfile.Layer.GAME)),
                StackProfile.GODOT_DOTNET_GAME.locate("game/Main.cs", contexts));
        assertEquals(Optional.of(new StackProfile.Location("pedidos", StackProfile.Layer.PRESENTATION)),
                StackProfile.FLUTTER_WEB_APP.locate("lib/pedidos/presentation/home.dart", List.of("pedidos")));
        assertEquals(Optional.empty(), StackProfile.DOTNET_APP.locate("utils/Helper.cs", contexts));
    }

    @Test
    void structureAcceptsLayerRootsEntryFilesAndExtrasOnly() {
        var contexts = List.of("Combate");
        var godot = StackProfile.GODOT_DOTNET_GAME;
        assertTrue(godot.isWithinStructure("src/Combate.Domain/Unidad.cs", contexts));
        assertTrue(godot.isWithinStructure("Juego.sln", contexts));
        assertTrue(godot.isWithinStructure("game/project.godot", contexts));
        assertTrue(godot.isWithinStructure("README.md", contexts));
        assertFalse(godot.isWithinStructure("src", contexts));
        assertFalse(godot.isWithinStructure("src/Otro.Domain/X.cs", contexts));
        assertFalse(godot.isWithinStructure("utils/Helper.cs", contexts));
    }

    @Test
    void reportsMissingEntryFiles() {
        assertEquals(List.of(), StackProfile.GODOT_DOTNET_GAME.missingEntryFiles(List.of("game/project.godot", "Juego.sln")));
        assertEquals(List.of("game/project.godot"),
                StackProfile.GODOT_DOTNET_GAME.missingEntryFiles(List.of("Juego.sln")));
        assertEquals(List.of("pubspec.yaml", "lib/main.dart"), StackProfile.FLUTTER_WEB_APP.missingEntryFiles(List.of()));
    }

    @Test
    void theCatalogDescriptionShowsEveryProfileAndItsStructure() {
        var all = StackProfile.describeAll();
        assertTrue(all.contains("DOTNET_APP") && all.contains("GODOT_DOTNET_GAME") && all.contains("FLUTTER_WEB_APP"));
        assertTrue(all.contains("src/<Ctx>.Domain"));
        assertTrue(all.contains("lib/<ctx>/domain"));
    }
}
```

- [ ] **Step 2: Correr el test para confirmar que falla**

Run: `cd app && mvn test -Dtest=StackProfileTest`
Expected: FAIL de compilación (`StackProfile` no existe).

- [ ] **Step 3: Implementar `StackProfile`**

```java
package com.aicompany.core.model;

import com.aicompany.core.agent.validation.OwnedPaths;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Catálogo fijo de perfiles de stack (spec 2026-09-26 §1): parte ESTRUCTURAL
 * de cada perfil — capas DDD por bounded context, archivos de entrada,
 * frameworks prohibidos en domain. La parte de ejecución (imagen, comandos)
 * vive en sandbox-runner. Agregar un perfil es un cambio de código.
 */
public enum StackProfile {

    DOTNET_APP(
            "APIs y servicios .NET (ASP.NET)",
            Ecosystem.NUGET, ".cs", "[A-Z][A-Za-z0-9]*", "PascalCase, p. ej. Pedidos",
            List.of(
                    new LayerRoot(Layer.DOMAIN, "src/{ctx}.Domain"),
                    new LayerRoot(Layer.APPLICATION, "src/{ctx}.Application"),
                    new LayerRoot(Layer.INFRASTRUCTURE, "src/{ctx}.Infrastructure"),
                    new LayerRoot(Layer.API, "src/{ctx}.Api"),
                    new LayerRoot(Layer.TESTS, "tests/{ctx}.Tests")),
            List.of(),
            List.of(new EntryFile("[A-Za-z0-9]+\\.sln", "<Producto>.sln")),
            List.of("README.md", ".gitignore", "docs", "Directory.Build.props"),
            List.of("Microsoft.AspNetCore", "Godot")),

    GODOT_DOTNET_GAME(
            "Juegos Godot con C#",
            Ecosystem.NUGET, ".cs", "[A-Z][A-Za-z0-9]*", "PascalCase, p. ej. Combate",
            List.of(
                    new LayerRoot(Layer.DOMAIN, "src/{ctx}.Domain"),
                    new LayerRoot(Layer.APPLICATION, "src/{ctx}.Application"),
                    new LayerRoot(Layer.TESTS, "tests/{ctx}.Tests")),
            List.of(new LayerRoot(Layer.GAME, "game")),
            List.of(new EntryFile("game/project\\.godot", "game/project.godot"),
                    new EntryFile("[A-Za-z0-9]+\\.sln", "<Producto>.sln")),
            List.of("README.md", ".gitignore", "docs", "Directory.Build.props"),
            List.of("Godot", "Microsoft.AspNetCore")),

    FLUTTER_WEB_APP(
            "Apps Flutter (web en esta ola)",
            Ecosystem.PUB, ".dart", "[a-z][a-z0-9_]*", "snake_case, p. ej. pedidos",
            List.of(
                    new LayerRoot(Layer.DOMAIN, "lib/{ctx}/domain"),
                    new LayerRoot(Layer.APPLICATION, "lib/{ctx}/application"),
                    new LayerRoot(Layer.INFRASTRUCTURE, "lib/{ctx}/infrastructure"),
                    new LayerRoot(Layer.PRESENTATION, "lib/{ctx}/presentation"),
                    new LayerRoot(Layer.TESTS, "test/{ctx}")),
            List.of(),
            List.of(new EntryFile("pubspec\\.yaml", "pubspec.yaml"),
                    new EntryFile("lib/main\\.dart", "lib/main.dart")),
            List.of("README.md", ".gitignore", "docs", "analysis_options.yaml", "web"),
            List.of("package:flutter/", "dart:ui", "dart:html"));

    public enum Ecosystem { NUGET, PUB }

    public enum Layer {
        DOMAIN, APPLICATION, INFRASTRUCTURE, API, PRESENTATION, GAME, TESTS;

        public boolean isOuter() {
            return this == INFRASTRUCTURE || this == API || this == PRESENTATION || this == GAME;
        }
    }

    /** {@code context == null} para capas compartidas sin contexto (p. ej. {@code game/}). */
    public record Location(String context, Layer layer) {
    }

    private record LayerRoot(Layer layer, String template) {
        String resolve(String context) {
            return template.replace("{ctx}", context);
        }
    }

    private record EntryFile(String regex, String display) {
        boolean matches(String path) {
            return Pattern.matches(regex, path);
        }
    }

    private final String usage;
    private final Ecosystem ecosystem;
    private final String sourceExtension;
    private final Pattern contextName;
    private final String contextNameRule;
    private final List<LayerRoot> contextRoots;
    private final List<LayerRoot> sharedRoots;
    private final List<EntryFile> entryFiles;
    private final List<String> extras;
    private final List<String> forbiddenDomainDependencies;

    StackProfile(String usage, Ecosystem ecosystem, String sourceExtension, String contextName,
                 String contextNameRule, List<LayerRoot> contextRoots, List<LayerRoot> sharedRoots,
                 List<EntryFile> entryFiles, List<String> extras, List<String> forbiddenDomainDependencies) {
        this.usage = usage;
        this.ecosystem = ecosystem;
        this.sourceExtension = sourceExtension;
        this.contextName = Pattern.compile(contextName);
        this.contextNameRule = contextNameRule;
        this.contextRoots = contextRoots;
        this.sharedRoots = sharedRoots;
        this.entryFiles = entryFiles;
        this.extras = extras;
        this.forbiddenDomainDependencies = forbiddenDomainDependencies;
    }

    public static Optional<StackProfile> parse(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(p -> p.name().equals(id.strip())).findFirst();
    }

    public String usage() {
        return usage;
    }

    public Ecosystem ecosystem() {
        return ecosystem;
    }

    public String sourceExtension() {
        return sourceExtension;
    }

    public List<String> forbiddenDomainDependencies() {
        return forbiddenDomainDependencies;
    }

    public boolean isValidContextName(String name) {
        return name != null && contextName.matcher(name).matches();
    }

    public String contextNameRule() {
        return contextNameRule;
    }

    public List<String> allowedRoots(List<String> contexts) {
        var roots = new ArrayList<String>();
        for (var context : contexts) {
            contextRoots.forEach(root -> roots.add(root.resolve(context)));
        }
        sharedRoots.forEach(root -> roots.add(root.template()));
        roots.addAll(extras);
        return roots;
    }

    public boolean isEntryFile(String path) {
        var normalized = OwnedPaths.normalize(path);
        return entryFiles.stream().anyMatch(e -> e.matches(normalized));
    }

    public List<String> missingEntryFiles(Collection<String> files) {
        var normalized = files.stream().map(OwnedPaths::normalize).toList();
        return entryFiles.stream()
                .filter(e -> normalized.stream().noneMatch(e::matches))
                .map(EntryFile::display)
                .toList();
    }

    public boolean isWithinStructure(String path, List<String> contexts) {
        var normalized = OwnedPaths.normalize(path);
        return isEntryFile(normalized)
                || allowedRoots(contexts).stream().anyMatch(root -> OwnedPaths.covers(root, normalized));
    }

    public Optional<Location> locate(String path, List<String> contexts) {
        var normalized = OwnedPaths.normalize(path);
        for (var context : contexts) {
            for (var root : contextRoots) {
                if (OwnedPaths.covers(root.resolve(context), normalized)) {
                    return Optional.of(new Location(context, root.layer()));
                }
            }
        }
        for (var root : sharedRoots) {
            if (OwnedPaths.covers(root.template(), normalized)) {
                return Optional.of(new Location(null, root.layer()));
            }
        }
        return Optional.empty();
    }

    public String describe() {
        var placeholder = ecosystem == Ecosystem.PUB ? "<ctx>" : "<Ctx>";
        var layers = contextRoots.stream()
                .map(r -> r.layer().name().toLowerCase() + "=" + r.template().replace("{ctx}", placeholder))
                .collect(Collectors.joining(", "));
        var shared = sharedRoots.isEmpty() ? "" : " | compartido: " + sharedRoots.stream()
                .map(r -> r.layer().name().toLowerCase() + "=" + r.template())
                .collect(Collectors.joining(", "));
        var entries = entryFiles.stream().map(EntryFile::display).collect(Collectors.joining(", "));
        return "- " + name() + " (" + usage + "): por contexto " + layers + shared
                + " | archivos de entrada: " + entries
                + " | nombres de contexto: " + contextNameRule
                + " | prohibido en domain: " + forbiddenDomainDependencies;
    }

    public static String describeAll() {
        return Arrays.stream(values()).map(StackProfile::describe).collect(Collectors.joining("\n"));
    }
}
```

- [ ] **Step 4: Correr el test**

Run: `cd app && mvn test -Dtest=StackProfileTest` → PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/StackProfile.java app/src/test/java/com/aicompany/core/model/StackProfileTest.java
git commit -m "Catálogo StackProfile: estructura DDD de los perfiles de la primera ola" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `TeamPlan` con perfil, contextos y glosario + reglas del validador

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/model/TeamPlan.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/model/TeamPlanSchema.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/TeamPlanValidator.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/TeamPlanValidatorTest.java` (reescritura del fixture de desarrollo)

**Interfaces:**
- Consumes: `StackProfile` (Task 1).
- Produces: `TeamPlan` canónico de 8 componentes `(String summary, String techStack, String entryPoint, List<PlannedTask> tasks, List<ParticipationConflict> participationConflicts, String stackProfile, List<BoundedContext> boundedContexts, List<GlossaryTerm> ubiquitousLanguage)`; se conservan los constructores de 4 y 5 argumentos (campos nuevos en `null`/`List.of()`); `record BoundedContext(String name, String description)`, `record GlossaryTerm(String term, String definition)`; `boundedContextsOrEmpty()`, `ubiquitousLanguageOrEmpty()`, `contextNames() -> List<String>`, `profile() -> Optional<StackProfile>`.

- [ ] **Step 1: Reescribir el fixture de desarrollo de `TeamPlanValidatorTest` y agregar los tests nuevos**

En `TeamPlanValidatorTest.java`, reemplazar `validTasks()` y `plan(...)` por:

```java
    private static List<PlannedTask> validTasks() {
        return new ArrayList<>(List.of(
                new PlannedTask("engineering", "WORK", "ARCHITECTURE", "Solución y capa application",
                        List.of("arquitectura backend"), List.of("Juego.sln", "src/Combate.Application")),
                new PlannedTask("frontend-ui", "WORK", "GAME_UI", "Escenas y HUD en Godot",
                        List.of("Game UI"), List.of("game")),
                new PlannedTask("backend", "WORK", "DOMAIN_MODEL", "Modelo de dominio del combate",
                        List.of("lógica de negocio"), List.of("src/Combate.Domain")),
                new PlannedTask("devops", "WORK", "TESTS_INFRA", "Proyecto de tests",
                        List.of("infraestructura"), List.of("tests/Combate.Tests")),
                new PlannedTask("qa", "VALIDATION", "STATIC_REVIEW", "Revisar el código",
                        List.of("QA"), List.of())
        ));
    }

    private static final List<TeamPlan.BoundedContext> CONTEXTS =
            List.of(new TeamPlan.BoundedContext("Combate", "Reglas del combate por turnos"));

    private static final List<TeamPlan.GlossaryTerm> GLOSSARY = List.of(
            new TeamPlan.GlossaryTerm("Unidad", "Personaje que participa en un combate"),
            new TeamPlan.GlossaryTerm("Turno", "Momento en que una unidad actúa"),
            new TeamPlan.GlossaryTerm("Daño", "Puntos que resta un ataque a la vida de una unidad"));

    private static TeamPlan plan(List<PlannedTask> tasks) {
        return plan("GODOT_DOTNET_GAME", CONTEXTS, GLOSSARY, tasks);
    }

    private static TeamPlan plan(String profile, List<TeamPlan.BoundedContext> contexts,
                                 List<TeamPlan.GlossaryTerm> glossary, List<PlannedTask> tasks) {
        return new TeamPlan("Juego de combate por turnos", null, null, tasks, List.of(), profile, contexts, glossary);
    }
```

Borrar los tests `entryPointMustBeInsideSomeWorkOwnedPaths` y `theEntryPointErrorListsTheWorkOwnedPathsAndHowToFixIt` (el `entryPoint` lo fija ahora el perfil; spec §1). En los tests que usan rutas `web/...` como datos, reemplazarlas por rutas de la estructura:

- `rejectsOverlappingOwnedPaths`: `tasks.set(2, new PlannedTask("backend", "WORK", "DOMAIN_MODEL", "Dominio", List.of("lógica de negocio"), List.of("src/Combate.Application")));` y assert `e.contains("se solapan")`.
- `rejectsAnInventedCapability`: `List.of("game")` como `ownedPaths`.
- `individualRealCapabilitiesPass` y `aConcatenatedCapabilityListIsRejectedWithASpecificCorrection`: `List.of("game")`.
- `theSamePathTwiceInOneTaskIsRejected`: `List.of("game/hud.cs", "game/hud.cs")` y assert `e.contains("game/hud.cs")`.
- `theSamePathInTwoTasksIsRejected`: tarea 0 con `List.of("Juego.sln", "README.md")`, tarea 3 con `List.of("tests/Combate.Tests", "README.md")`; assert `e.contains("README.md") && e.contains("se solapan")`.
- `globsInOwnedPathsAreRejected`: `List.of("tests/*.cs")`; assert `e.contains("tests/*.cs")`.
- `rejectsUnsafeOwnedPaths`: `List.of("../infra")` (sin cambios).
- `rejectsTwoTasksForTheSameAgent`: la tarea extra con `List.of("src/Combate.Infrastructure")`.
- `rejectsActionsThatAreNotUpperSnakeCase`: ownedPaths `List.of("Juego.sln")`.
- `requiresExactlyOneValidationTask` / `validationMustGoToAMemberWithQaCapability`: la tarea WORK de `qa` con `List.of("tests/Combate.Tests")` y la de `devops` quitada o con `List.of("README.md")` para no solapar.

Agregar al final de la clase:

```java
    @Test
    void rejectsAProfileOutsideTheCatalog() {
        var errors = validateDev(plan("UNITY_GAME", CONTEXTS, GLOSSARY, validTasks()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("stackProfile") && e.contains("GODOT_DOTNET_GAME")), errors.toString());
    }

    @Test
    void requiresAtLeastOneBoundedContext() {
        var errors = validateDev(plan("GODOT_DOTNET_GAME", List.of(), GLOSSARY, validTasks()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("boundedContexts")), errors.toString());
    }

    // Review Focus: en .NET el nombre define las rutas src/<Ctx>.Domain; "combate" no coincide.
    @Test
    void rejectsAContextNameThatDoesNotFollowTheProfileRule() {
        var errors = validateDev(plan("GODOT_DOTNET_GAME",
                List.of(new TeamPlan.BoundedContext("combate", "x")), GLOSSARY, validTasks()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("combate") && e.contains("PascalCase")), errors.toString());
    }

    @Test
    void requiresAGlossaryOfAtLeastThreeTerms() {
        var errors = validateDev(plan("GODOT_DOTNET_GAME", CONTEXTS, GLOSSARY.subList(0, 2), validTasks()));
        assertTrue(errors.stream().anyMatch(e -> e.contains("ubiquitousLanguage")), errors.toString());
    }

    // Review Focus: "src" es padre de toda la estructura; tiene que estar DENTRO de ella.
    @Test
    void rejectsOwnedPathsOutsideTheProfileStructure() {
        var tasks = validTasks();
        tasks.set(3, new PlannedTask("devops", "WORK", "TESTS_INFRA", "Infra", List.of("infraestructura"), List.of("src")));
        var errors = validateDev(plan(tasks));
        assertTrue(errors.stream().anyMatch(e -> e.contains("\"src\"") && e.contains("estructura")), errors.toString());
    }

    @Test
    void analysisTeamsStillNeedNoProfile() {
        var marketing = new TeamSnapshot("TEAM-MARKETING-GROWTH", "Marketing & Growth", "ACTIVE", "growth-content", List.of(
                member("growth-content", "Kira", List.of("SEO"))));
        var onlyKira = new TeamPlan("Plan", "", "", List.of(
                new PlannedTask("growth-content", "WORK", "SEO_PLAN", "Plan SEO", List.of("SEO"), List.of())));
        assertEquals(List.of(), validator.validate(onlyKira, marketing, TeamExecutionMode.ANALYSIS));
    }
```

- [ ] **Step 2: Correr el test para confirmar que falla**

Run: `cd app && mvn test -Dtest=TeamPlanValidatorTest`
Expected: FAIL de compilación (constructor de 8 argumentos, `BoundedContext`, `GlossaryTerm` no existen).

- [ ] **Step 3: `TeamPlan`**

Reemplazar la cabecera del record y los constructores por:

```java
public record TeamPlan(
        String summary,
        String techStack,
        String entryPoint,
        List<PlannedTask> tasks,
        List<ParticipationConflict> participationConflicts,
        String stackProfile,
        List<BoundedContext> boundedContexts,
        List<GlossaryTerm> ubiquitousLanguage
) {

    /** Planes sin conflictos ni campos DDD (equipos de análisis y planes previos). */
    public TeamPlan(String summary, String techStack, String entryPoint, List<PlannedTask> tasks) {
        this(summary, techStack, entryPoint, tasks, List.of(), null, List.of(), List.of());
    }

    public TeamPlan(String summary, String techStack, String entryPoint, List<PlannedTask> tasks,
                    List<ParticipationConflict> participationConflicts) {
        this(summary, techStack, entryPoint, tasks, participationConflicts, null, List.of(), List.of());
    }

    /** Bounded context DDD del producto (spec 2026-09-26 §1). El nombre define las rutas. */
    public record BoundedContext(String name, String description) {
    }

    /** Término del lenguaje ubicuo con su definición. */
    public record GlossaryTerm(String term, String definition) {
    }

    public List<BoundedContext> boundedContextsOrEmpty() {
        return boundedContexts == null ? List.of() : boundedContexts;
    }

    public List<GlossaryTerm> ubiquitousLanguageOrEmpty() {
        return ubiquitousLanguage == null ? List.of() : ubiquitousLanguage;
    }

    public List<String> contextNames() {
        return boundedContextsOrEmpty().stream()
                .filter(java.util.Objects::nonNull)
                .map(BoundedContext::name)
                .toList();
    }

    public Optional<com.aicompany.core.model.StackProfile> profile() {
        return com.aicompany.core.model.StackProfile.parse(stackProfile);
    }
```

(El resto del record — `ParticipationConflict`, `KIND_*`, `PlannedTask`, `tasksOrEmpty`, `workTasks`, `validationTask`, `participationConflictsOrEmpty` — sin cambios.)

- [ ] **Step 4: `TeamPlanSchema`**

Reemplazar `SCHEMA` por (se quitan `techStack`/`entryPoint`):

```java
    private static final Map<String, Object> CONTEXT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "name", Map.of("type", "string", "minLength", 1),
                    "description", Map.of("type", "string", "minLength", 1)),
            "required", List.of("name", "description"),
            "additionalProperties", false);

    private static final Map<String, Object> TERM_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "term", Map.of("type", "string", "minLength", 1),
                    "definition", Map.of("type", "string", "minLength", 1)),
            "required", List.of("term", "definition"),
            "additionalProperties", false);

    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "summary", Map.of("type", "string", "minLength", 1),
                    "stackProfile", Map.of("type", "string"),
                    "boundedContexts", Map.of("type", "array", "items", CONTEXT_SCHEMA),
                    "ubiquitousLanguage", Map.of("type", "array", "items", TERM_SCHEMA),
                    "tasks", Map.of("type", "array", "items", TASK_SCHEMA, "minItems", 1),
                    "participationConflicts", PARTICIPATION_CONFLICTS_SCHEMA
            ),
            "required", List.of("summary", "stackProfile", "boundedContexts", "ubiquitousLanguage", "tasks"),
            "additionalProperties", false
    );
```

Extraer el `Map.of(...)` de `participationConflicts` que ya existe inline a la constante `PARTICIPATION_CONFLICTS_SCHEMA` (mismo contenido) antes de `SCHEMA`, y actualizar el javadoc de la clase: "stackProfile vacío y listas vacías en equipos de análisis".

- [ ] **Step 5: `TeamPlanValidator.validateDevelopmentRules`**

1. Borrar los dos bloques `if (plan.techStack() ...)` y `if (plan.entryPoint() ...)` que exigen esos campos, y el bloque final `if (plan.entryPoint() != null && ... ownedByAgent ...)`.
2. Agregar al final del método:

```java
        var profile = plan.profile();

        if (profile.isEmpty()) {
            errors.add("stackProfile debe ser uno de los perfiles del catálogo: "
                    + java.util.Arrays.toString(StackProfile.values()) + " (recibido: \"" + plan.stackProfile() + "\").");
            return;
        }

        validateContextsAndGlossary(plan, profile.get(), errors);

        var contexts = plan.contextNames();
        for (var task : work) {
            for (var path : task.ownedPathsOrEmpty()) {
                if (path != null && OwnedPaths.isSafe(path) && !profile.get().isWithinStructure(path, contexts)) {
                    errors.add("El ownedPath \"" + path + "\" de " + task.agentId() + " está fuera de la estructura "
                            + "de " + profile.get().name() + ". Rutas permitidas: " + profile.get().allowedRoots(contexts)
                            + " y los archivos de entrada del perfil.");
                }
            }
        }
    }

    private static void validateContextsAndGlossary(TeamPlan plan, StackProfile profile, List<String> errors) {

        if (plan.boundedContextsOrEmpty().isEmpty()) {
            errors.add("boundedContexts debe declarar al menos un bounded context del producto (DDD).");
        }

        var seen = new HashSet<String>();
        for (var context : plan.boundedContextsOrEmpty()) {
            if (context == null || !profile.isValidContextName(context.name())) {
                errors.add("El bounded context \"" + (context == null ? null : context.name()) + "\" no cumple el formato de "
                        + profile.name() + ": " + profile.contextNameRule() + ". El nombre define las rutas de sus capas.");
            } else if (!seen.add(context.name())) {
                errors.add("El bounded context \"" + context.name() + "\" está repetido.");
            }
            if (context != null && (context.description() == null || context.description().isBlank())) {
                errors.add("El bounded context \"" + context.name() + "\" necesita una descripción.");
            }
        }

        var terms = plan.ubiquitousLanguageOrEmpty().stream()
                .filter(t -> t != null && t.term() != null && !t.term().isBlank()
                        && t.definition() != null && !t.definition().isBlank())
                .count();
        if (terms < 3) {
            errors.add("ubiquitousLanguage debe tener al menos 3 términos del dominio con su definición (hay " + terms + ").");
        }
    }
```

Nota: `return;` dentro de `validateDevelopmentRules` corta solo ese método; las demás reglas ya corrieron antes. Import: `com.aicompany.core.model.StackProfile`.

- [ ] **Step 6: Correr los tests**

Run: `cd app && mvn test -Dtest=TeamPlanValidatorTest` → PASS.
Run: `cd app && mvn test` → BUILD SUCCESS (si `TeamWorkPlanner.normalizeActions` no compila, se arregla en Task 3; en ese caso correr la suite recién al final de Task 3 y dejarlo anotado).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/model/TeamPlan.java app/src/main/java/com/aicompany/core/agent/model/TeamPlanSchema.java \
        app/src/main/java/com/aicompany/core/agent/validation/TeamPlanValidator.java \
        app/src/test/java/com/aicompany/core/agent/validation/TeamPlanValidatorTest.java
git commit -m "Plan de Engineering: stackProfile del catálogo, bounded contexts, lenguaje ubicuo y rutas dentro de la estructura" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Prompt del planner con el catálogo

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/TeamWorkPlanner.java`
- Test: `app/src/test/java/com/aicompany/core/service/TeamWorkPlannerTest.java`

**Interfaces:**
- Consumes: `StackProfile.describeAll()` (Task 1); `TeamPlan` de 8 componentes (Task 2).
- Produces: comportamiento (prompt de desarrollo con el catálogo; `normalizeActions` conserva los campos DDD).

- [ ] **Step 1: Escribir los tests (fallan)**

Agregar a `TeamWorkPlannerTest`:

```java
    private static TeamSnapshot engineering() {
        return new TeamSnapshot("TEAM-ENGINEERING", "Engineering Team", "ACTIVE", "engineering", List.of(
                new TeamMemberInfo("engineering", "Neo", "Arquitecto", "CLOUD_ARCHITECT_LEAD_BACKEND",
                        List.of("arquitectura backend"), "qwen3:8b"),
                new TeamMemberInfo("qa", "Vera", "QA", "QA_CLOUD_PERFORMANCE_ENGINEER", List.of("QA"), "qwen3:8b")));
    }

    private static TeamPlan dddPlan() {
        return new TeamPlan("Juego", null, null, List.of(
                new PlannedTask("engineering", "WORK", "DOMAIN_MODEL", "Dominio", List.of("arquitectura backend"),
                        List.of("Juego.sln", "src/Combate.Domain")),
                new PlannedTask("qa", "VALIDATION", "STATIC_REVIEW", "Revisar", List.of("QA"), List.of())),
                List.of(), "GODOT_DOTNET_GAME",
                List.of(new TeamPlan.BoundedContext("Combate", "Combate por turnos")),
                List.of(new TeamPlan.GlossaryTerm("Unidad", "Personaje"),
                        new TeamPlan.GlossaryTerm("Turno", "Momento de acción"),
                        new TeamPlan.GlossaryTerm("Daño", "Vida que resta un ataque")));
    }

    @Test
    void theDevelopmentPromptPresentsTheStackCatalogAndDddRules() {
        when(teamMemory.snapshot("TEAM-ENGINEERING")).thenReturn(engineering());
        var prompt = ArgumentCaptor.forClass(String.class);
        when(ceoService.planTeamWork(eq("engineering"), prompt.capture(), anyString(), anyString())).thenReturn(dddPlan());

        var result = planner.plan("M-1", "TEAM-ENGINEERING", "Crear un juego", TeamExecutionMode.DEVELOPMENT);

        assertTrue(prompt.getValue().contains("GODOT_DOTNET_GAME"));
        assertTrue(prompt.getValue().contains("src/<Ctx>.Domain"));
        assertTrue(prompt.getValue().contains("boundedContexts"));
        assertTrue(prompt.getValue().contains("ubiquitousLanguage"));
        assertEquals("GODOT_DOTNET_GAME", result.plan().stackProfile());
    }

    @Test
    void normalizingActionsKeepsTheDddFields() {
        var normalized = TeamWorkPlanner.normalizeActions(dddPlan());
        assertEquals("GODOT_DOTNET_GAME", normalized.stackProfile());
        assertEquals(List.of("Combate"), normalized.contextNames());
        assertEquals(3, normalized.ubiquitousLanguageOrEmpty().size());
    }
```

(Import `com.aicompany.core.model.TeamMemberInfo` si falta.)

- [ ] **Step 2: Correr para confirmar que fallan**

Run: `cd app && mvn test -Dtest=TeamWorkPlannerTest` → FAIL (prompt sin catálogo; `normalizeActions` pierde los campos o no compila).

- [ ] **Step 3: Implementar**

1. En `normalizeActions`, reemplazar el `return new TeamPlan(...)` por:

```java
        return new TeamPlan(plan.summary(), plan.techStack(), plan.entryPoint(), tasks, plan.participationConflicts(),
                plan.stackProfile(), plan.boundedContexts(), plan.ubiquitousLanguage());
```

2. En `buildPrompt`, bloque `ANALYSIS`: reemplazar `- techStack y entryPoint: déjalos como "" y ownedPaths como [].` por:

```
                    - stackProfile: "" ; boundedContexts: [] ; ubiquitousLanguage: [] ; ownedPaths: [].
```

3. En el bloque de desarrollo, reemplazar las líneas de `techStack:` y `entryPoint:` (y su continuación) por:

```java
                - Metodología obligatoria: DDD.
                - stackProfile: elige EXACTAMENTE uno de estos perfiles del catálogo (no existen otros):
                %s
                - boundedContexts: los bounded contexts del producto, cada uno con name (en el formato del perfil) y
                  description. El name define las rutas de sus capas.
                - ubiquitousLanguage: al menos 3 términos del dominio, cada uno con term y definition.
                - ownedPaths de cada tarea WORK: carpetas o archivos DENTRO de la estructura del perfil elegido para
                  alguno de tus contextos (p. ej. src/Combate.Domain), o sus archivos de entrada. Reparte el trabajo por
                  contexto y capa. Una carpeta padre como "src" no se acepta.
                - Reglas de capas: domain no depende de nada fuera de su domain ni de frameworks; application solo de
                  domain; infrastructure/api/presentation/game dependen de application y domain.
                """.formatted(StackProfile.describeAll());
```

(El text block de desarrollo pasa a terminar en `.formatted(StackProfile.describeAll())`; si ya terminaba sin `.formatted`, agregarlo. Import `com.aicompany.core.model.StackProfile`.)

- [ ] **Step 4: Correr los tests**

Run: `cd app && mvn test -Dtest=TeamWorkPlannerTest` → PASS.
Run: `cd app && mvn test` → BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/TeamWorkPlanner.java app/src/test/java/com/aicompany/core/service/TeamWorkPlannerTest.java
git commit -m "Planner: Neo elige un perfil del catálogo y declara contextos y lenguaje ubicuo (DDD)" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `ProfileStructureChecker` (`ENTRY_FILES`, `PROFILE_STRUCTURE`)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/ProfileStructureChecker.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/ProfileStructureCheckerTest.java`

**Interfaces:**
- Consumes: `StackProfile.missingEntryFiles`, `isWithinStructure` (Task 1); `StaticCheck.pass/fail` (existente).
- Produces: `static List<StaticCheck> ProfileStructureChecker.check(StackProfile profile, List<String> contexts, List<String> files)` → siempre 2 checks: `ENTRY_FILES` y `PROFILE_STRUCTURE`.

- [ ] **Step 1: Test (falla)**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.StaticCheck;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProfileStructureCheckerTest {

    private static StaticCheck find(List<StaticCheck> checks, String name) {
        return checks.stream().filter(c -> c.check().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aWellFormedGodotRepositoryPasses() {
        var checks = ProfileStructureChecker.check(StackProfile.GODOT_DOTNET_GAME, List.of("Combate"), List.of(
                "Juego.sln", "game/project.godot", "game/Main.cs", "src/Combate.Domain/Unidad.cs", "README.md"));
        assertTrue(checks.stream().allMatch(StaticCheck::passed), checks.toString());
    }

    @Test
    void missingEntryFilesFail() {
        var checks = ProfileStructureChecker.check(StackProfile.FLUTTER_WEB_APP, List.of("pedidos"),
                List.of("lib/pedidos/domain/pedido.dart"));
        var entry = find(checks, "ENTRY_FILES");
        assertFalse(entry.passed());
        assertTrue(entry.detail().contains("pubspec.yaml"));
    }

    @Test
    void filesOutsideTheStructureFailAndAreListed() {
        var checks = ProfileStructureChecker.check(StackProfile.DOTNET_APP, List.of("Pedidos"),
                List.of("Tienda.sln", "src/Pedidos.Domain/Pedido.cs", "utils/Helper.cs"));
        var structure = find(checks, "PROFILE_STRUCTURE");
        assertFalse(structure.passed());
        assertEquals(List.of("utils/Helper.cs"), structure.paths());
    }
}
```

- [ ] **Step 2: Correr** — `cd app && mvn test -Dtest=ProfileStructureCheckerTest` → FAIL de compilación.

- [ ] **Step 3: Implementar**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.StaticCheck;

import java.util.List;

/** Estructura del perfil (spec 2026-09-26 §1): archivos de entrada y ningún archivo fuera de la estructura DDD. */
public final class ProfileStructureChecker {

    private ProfileStructureChecker() {
    }

    public static List<StaticCheck> check(StackProfile profile, List<String> contexts, List<String> files) {

        var missing = profile.missingEntryFiles(files);
        var entry = missing.isEmpty()
                ? StaticCheck.pass("ENTRY_FILES", "Archivos de entrada de " + profile.name() + " presentes", null, List.of())
                : StaticCheck.fail("ENTRY_FILES", "Faltan archivos de entrada de " + profile.name() + ": " + missing,
                        null, missing);

        var outside = files.stream().filter(f -> !profile.isWithinStructure(f, contexts)).toList();
        var structure = outside.isEmpty()
                ? StaticCheck.pass("PROFILE_STRUCTURE", files.size() + " archivo(s) dentro de la estructura de "
                        + profile.name() + " para " + contexts, null, List.of())
                : StaticCheck.fail("PROFILE_STRUCTURE", "Archivos fuera de la estructura de " + profile.name()
                        + " (contextos " + contexts + "): " + outside, null, outside);

        return List.of(entry, structure);
    }
}
```

- [ ] **Step 4: Correr** — PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/ProfileStructureChecker.java app/src/test/java/com/aicompany/core/agent/validation/ProfileStructureCheckerTest.java
git commit -m "ProfileStructureChecker: archivos de entrada y estructura DDD del perfil" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: `DddLayerChecker` — reglas de capas y C#

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/DddLayerChecker.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/DddLayerCheckerCSharpTest.java`

**Interfaces:**
- Consumes: `StackProfile.locate`, `isEntryFile`, `sourceExtension`, `forbiddenDomainDependencies`, `ecosystem`, `Layer.isOuter()` (Task 1).
- Produces: `record DddLayerChecker.Violation(String file, String dependency, String reason)`; `static List<Violation> check(StackProfile profile, List<String> contexts, Map<String, String> contentsByPath)`; `static StaticCheck toCheck(List<Violation> violations, int analyzedFiles)` (check `DDD_LAYERS`).

- [ ] **Step 1: Test (falla)**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.model.StackProfile;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DddLayerCheckerCSharpTest {

    private static final List<String> CONTEXTS = List.of("Combate", "Inventario");

    private static List<DddLayerChecker.Violation> check(Map<String, String> files) {
        return DddLayerChecker.check(StackProfile.GODOT_DOTNET_GAME, CONTEXTS, files);
    }

    @Test
    void aCleanLayeredRepositoryHasNoViolations() {
        var files = new LinkedHashMap<String, String>();
        files.put("src/Combate.Domain/Unidad.cs", "using System;\nnamespace Combate.Domain;\npublic class Unidad {}");
        files.put("src/Combate.Application/AtacarHandler.cs", "using Combate.Domain;\nusing Inventario.Application;\nnamespace Combate.Application;");
        files.put("game/Main.cs", "using Godot;\nusing Combate.Application;\nusing Combate.Domain;\npublic partial class Main : Node {}");
        files.put("tests/Combate.Tests/UnidadTests.cs", "using Combate.Domain;\nusing Xunit;");
        assertEquals(List.of(), check(files));
    }

    @Test
    void domainImportingAFrameworkIsAViolation() {
        var violations = check(Map.of("src/Combate.Domain/Unidad.cs", "using Godot;\nnamespace Combate.Domain;"));
        assertEquals(1, violations.size());
        assertEquals("Godot", violations.get(0).dependency());
    }

    @Test
    void domainImportingApplicationIsAViolation() {
        var violations = check(Map.of("src/Combate.Domain/Unidad.cs", "using Combate.Application;"));
        assertEquals(1, violations.size());
    }

    @Test
    void domainUsingAnotherContextsDomainIsAViolation() {
        var violations = check(Map.of("src/Combate.Domain/Unidad.cs", "using Inventario.Domain;"));
        assertEquals(1, violations.size());
    }

    @Test
    void applicationUsingAnotherContextsDomainIsAViolation() {
        var violations = check(Map.of("src/Combate.Application/X.cs", "using Inventario.Domain.Items;"));
        assertEquals(1, violations.size());
    }

    // Review Focus: alias, static y global using se detectan igual que un using simple.
    @Test
    void aliasStaticAndGlobalUsingsAreDetected() {
        var violations = check(Map.of("src/Combate.Domain/Unidad.cs", String.join("\n",
                "using G = Godot;",
                "global using static Godot.GD;",
                "using Microsoft.AspNetCore.Mvc;")));
        assertEquals(3, violations.size(), violations.toString());
    }

    @Test
    void theCheckSummarizesViolations() {
        var violations = check(Map.of("src/Combate.Domain/Unidad.cs", "using Godot;"));
        var check = DddLayerChecker.toCheck(violations, 1);
        assertFalse(check.passed());
        assertTrue(check.detail().contains("src/Combate.Domain/Unidad.cs"));
        assertTrue(DddLayerChecker.toCheck(List.of(), 4).passed());
    }
}
```

- [ ] **Step 2: Correr** — `cd app && mvn test -Dtest=DddLayerCheckerCSharpTest` → FAIL de compilación.

- [ ] **Step 3: Implementar**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.StackProfile.Layer;
import com.aicompany.core.model.StackProfile.Location;
import com.aicompany.core.model.StaticCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Chequeo determinista de capas DDD (spec 2026-09-26 §1), escrito en Java y
 * sin herramientas externas por lenguaje. Ubica cada archivo en (contexto,
 * capa) por su ruta y cada dependencia por su namespace (C#) o import (Dart).
 * Límite conocido: en C# no ve nombres totalmente calificados sin `using`.
 */
public final class DddLayerChecker {

    public record Violation(String file, String dependency, String reason) {
    }

    private record Dependency(String raw, Optional<Location> target, boolean forbidden) {
    }

    private static final Pattern CS_USING = Pattern.compile(
            "^\\s*(?:global\\s+)?using\\s+(?:static\\s+)?(?:[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*)?([A-Za-z_][A-Za-z0-9_.]*)\\s*;",
            Pattern.MULTILINE);

    private static final Map<String, Layer> CS_LAYER_NAMES = Map.of(
            "Domain", Layer.DOMAIN, "Application", Layer.APPLICATION, "Infrastructure", Layer.INFRASTRUCTURE,
            "Api", Layer.API, "Tests", Layer.TESTS);

    private DddLayerChecker() {
    }

    public static List<Violation> check(StackProfile profile, List<String> contexts, Map<String, String> contentsByPath) {

        var violations = new ArrayList<Violation>();

        for (var entry : contentsByPath.entrySet()) {

            var path = entry.getKey();

            if (!path.endsWith(profile.sourceExtension()) || profile.isEntryFile(path)) {
                continue;
            }

            var source = profile.locate(path, contexts);
            if (source.isEmpty() || source.get().layer() == Layer.TESTS) {
                continue;
            }

            for (var dependency : dependencies(profile, contexts, path, entry.getValue(), contentsByPath)) {
                var reason = violation(source.get(), dependency);
                if (reason != null) {
                    violations.add(new Violation(path, dependency.raw(), reason));
                }
            }
        }

        return violations;
    }

    public static StaticCheck toCheck(List<Violation> violations, int analyzedFiles) {

        if (violations.isEmpty()) {
            return StaticCheck.pass("DDD_LAYERS", "Sin violaciones de capas DDD (" + analyzedFiles + " archivo(s) analizados)",
                    null, List.of());
        }

        var detail = violations.stream().limit(10)
                .map(v -> v.file() + " → " + v.dependency() + ": " + v.reason())
                .collect(Collectors.joining("; "));
        var files = violations.stream().map(Violation::file).distinct().toList();

        return StaticCheck.fail("DDD_LAYERS", violations.size() + " violación(es) de capas DDD: " + detail, null, files);
    }

    private static String violation(Location source, Dependency dependency) {

        if (dependency.forbidden()) {
            return source.layer() == Layer.DOMAIN ? "domain no puede depender de frameworks" : null;
        }

        if (dependency.target().isEmpty()) {
            return null;
        }

        var target = dependency.target().get();
        var sameContext = Objects.equals(source.context(), target.context());

        return switch (source.layer()) {
            case DOMAIN -> target.layer() == Layer.DOMAIN && sameContext
                    ? null : "domain solo puede depender de su propio domain";
            case APPLICATION -> (target.layer() == Layer.DOMAIN && sameContext) || target.layer() == Layer.APPLICATION
                    ? null : "application solo puede depender de su domain y de application de otros contextos";
            case GAME -> target.layer() == Layer.DOMAIN || target.layer() == Layer.APPLICATION || target.layer() == Layer.GAME
                    ? null : "game solo puede depender de domain y application";
            case INFRASTRUCTURE, API, PRESENTATION -> {
                if (sameContext) {
                    yield target.layer() == Layer.DOMAIN || target.layer() == Layer.APPLICATION || target.layer() == source.layer()
                            ? null : "una capa externa solo puede depender de domain/application de su contexto";
                }
                yield target.layer() == Layer.APPLICATION
                        ? null : "entre contextos solo se permite usar la capa application";
            }
            case TESTS -> null;
        };
    }

    private static List<Dependency> dependencies(
            StackProfile profile, List<String> contexts, String path, String content, Map<String, String> all) {
        return profile.ecosystem() == StackProfile.Ecosystem.NUGET
                ? csharpDependencies(profile, contexts, content)
                : DartImports.dependencies(profile, contexts, path, content, all);
    }

    private static List<Dependency> csharpDependencies(StackProfile profile, List<String> contexts, String content) {

        var result = new ArrayList<Dependency>();
        var matcher = CS_USING.matcher(content == null ? "" : content);

        while (matcher.find()) {
            var namespace = matcher.group(1);
            var forbidden = profile.forbiddenDomainDependencies().stream()
                    .anyMatch(f -> namespace.equals(f) || namespace.startsWith(f + "."));
            result.add(new Dependency(namespace, csharpTarget(namespace, contexts), forbidden));
        }

        return result;
    }

    private static Optional<Location> csharpTarget(String namespace, List<String> contexts) {
        for (var context : contexts) {
            for (var layer : CS_LAYER_NAMES.entrySet()) {
                var prefix = context + "." + layer.getKey();
                if (namespace.equals(prefix) || namespace.startsWith(prefix + ".")) {
                    return Optional.of(new Location(context, layer.getValue()));
                }
            }
        }
        return Optional.empty();
    }

    /** Dart: se completa en Task 6. */
    static final class DartImports {
        static List<Dependency> dependencies(
                StackProfile profile, List<String> contexts, String path, String content, Map<String, String> all) {
            return List.of();
        }
    }
}
```

- [ ] **Step 4: Correr** — `cd app && mvn test -Dtest=DddLayerCheckerCSharpTest` → PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/DddLayerChecker.java app/src/test/java/com/aicompany/core/agent/validation/DddLayerCheckerCSharpTest.java
git commit -m "DddLayerChecker: reglas de capas DDD y análisis de using en C#" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: `DddLayerChecker` — Dart

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/DddLayerChecker.java` (clase anidada `DartImports`)
- Test: `app/src/test/java/com/aicompany/core/agent/validation/DddLayerCheckerDartTest.java`

**Interfaces:**
- Consumes: `DddLayerChecker.check` (Task 5); `OwnedPaths.normalize` (existente).
- Produces: comportamiento para `FLUTTER_WEB_APP` (mismo `check`).

- [ ] **Step 1: Test (falla)**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.model.StackProfile;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DddLayerCheckerDartTest {

    private static final List<String> CONTEXTS = List.of("pedidos", "catalogo");

    private static Map<String, String> repo(String path, String content) {
        var files = new LinkedHashMap<String, String>();
        files.put("pubspec.yaml", "name: tienda\nenvironment:\n  sdk: '>=3.0.0 <4.0.0'\n");
        files.put(path, content);
        return files;
    }

    private static List<DddLayerChecker.Violation> check(Map<String, String> files) {
        return DddLayerChecker.check(StackProfile.FLUTTER_WEB_APP, CONTEXTS, files);
    }

    @Test
    void domainImportingFlutterIsAViolation() {
        var violations = check(repo("lib/pedidos/domain/pedido.dart", "import 'package:flutter/material.dart';"));
        assertEquals(1, violations.size());
    }

    @Test
    void domainImportingDartUiIsAViolation() {
        assertEquals(1, check(repo("lib/pedidos/domain/pedido.dart", "import 'dart:ui';")).size());
    }

    @Test
    void packageImportsOfTheAppAreClassifiedByPath() {
        var violations = check(repo("lib/pedidos/domain/pedido.dart",
                "import 'package:tienda/pedidos/infrastructure/api.dart';"));
        assertEquals(1, violations.size());
    }

    // Review Focus: un import relativo con ../ que cruza al domain de otro contexto.
    @Test
    void relativeImportsCrossingIntoAnotherContextsDomainAreViolations() {
        var violations = check(repo("lib/pedidos/application/crear_pedido.dart",
                "import '../../catalogo/domain/producto.dart';"));
        assertEquals(1, violations.size());
        assertEquals("../../catalogo/domain/producto.dart", violations.get(0).dependency());
    }

    @Test
    void allowedDependenciesPass() {
        var files = repo("lib/pedidos/presentation/pedidos_page.dart", String.join("\n",
                "import 'package:flutter/material.dart';",
                "import '../application/crear_pedido.dart';",
                "import 'package:tienda/pedidos/domain/pedido.dart';",
                "import 'package:tienda/catalogo/application/buscar_productos.dart';"));
        files.put("lib/pedidos/domain/pedido.dart", "import 'linea_pedido.dart';\nimport 'package:equatable/equatable.dart';");
        assertEquals(List.of(), check(files));
    }

    // Review Focus: lib/main.dart es el composition root y está exento.
    @Test
    void theCompositionRootIsExempt() {
        var files = repo("lib/main.dart", String.join("\n",
                "import 'package:flutter/material.dart';",
                "import 'pedidos/infrastructure/api.dart';",
                "import 'pedidos/domain/pedido.dart';"));
        assertEquals(List.of(), check(files));
    }
}
```

- [ ] **Step 2: Correr** — `cd app && mvn test -Dtest=DddLayerCheckerDartTest` → FAIL (`DartImports` devuelve lista vacía: los 4 tests de violación fallan).

- [ ] **Step 3: Implementar `DartImports`**

Reemplazar la clase anidada `DartImports` por:

```java
    /** Dart: `import`/`export`; `package:<app>/...` → `lib/...`; relativos resueltos contra el archivo. */
    static final class DartImports {

        private static final Pattern DART_IMPORT = Pattern.compile(
                "^\\s*(?:import|export)\\s+['\"]([^'\"]+)['\"]", Pattern.MULTILINE);

        private static final Pattern PUBSPEC_NAME = Pattern.compile("^name:\\s*([a-z0-9_]+)\\s*$", Pattern.MULTILINE);

        static List<Dependency> dependencies(
                StackProfile profile, List<String> contexts, String path, String content, Map<String, String> all) {

            var appPackage = appPackage(all);
            var result = new ArrayList<Dependency>();
            var matcher = DART_IMPORT.matcher(content == null ? "" : content);

            while (matcher.find()) {
                var uri = matcher.group(1);
                var forbidden = profile.forbiddenDomainDependencies().stream().anyMatch(uri::startsWith);
                var targetPath = resolve(uri, path, appPackage);
                var target = targetPath == null ? Optional.<Location>empty() : profile.locate(targetPath, contexts);
                result.add(new Dependency(uri, target, forbidden));
            }

            return result;
        }

        private static String appPackage(Map<String, String> all) {
            var pubspec = all.get("pubspec.yaml");
            if (pubspec == null) {
                return null;
            }
            var matcher = PUBSPEC_NAME.matcher(pubspec);
            return matcher.find() ? matcher.group(1) : null;
        }

        private static String resolve(String uri, String fromPath, String appPackage) {

            if (appPackage != null && uri.startsWith("package:" + appPackage + "/")) {
                return "lib/" + uri.substring(("package:" + appPackage + "/").length());
            }

            if (uri.contains(":")) {
                return null;
            }

            var base = fromPath.contains("/") ? fromPath.substring(0, fromPath.lastIndexOf('/')) : "";
            var segments = new ArrayList<String>(base.isEmpty() ? List.of() : List.of(base.split("/")));

            for (var segment : uri.split("/")) {
                if (segment.equals("..")) {
                    if (!segments.isEmpty()) {
                        segments.remove(segments.size() - 1);
                    }
                } else if (!segment.equals(".") && !segment.isEmpty()) {
                    segments.add(segment);
                }
            }

            return OwnedPaths.normalize(String.join("/", segments));
        }
    }
```

- [ ] **Step 4: Correr** — `cd app && mvn test -Dtest='DddLayerCheckerDartTest,DddLayerCheckerCSharpTest'` → PASS (13 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/DddLayerChecker.java app/src/test/java/com/aicompany/core/agent/validation/DddLayerCheckerDartTest.java
git commit -m "DddLayerChecker: imports de Dart (package de la app, relativos y frameworks)" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Integrar los checkers en `StaticWorkspaceValidator`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/StaticWorkspaceValidator.java`
- Test: `app/src/test/java/com/aicompany/core/service/StaticWorkspaceValidatorTest.java` (fixture a la estructura Godot)

**Interfaces:**
- Consumes: `ProfileStructureChecker.check` (Task 4); `DddLayerChecker.check/toCheck` (Tasks 5-6); `StackProfile` (Task 1).
- Produces: nueva firma `List<StaticCheck> validate(String missionId, List<CommittedWork> work, StackProfile profile, List<String> contexts, List<String> allowedPaths)` (reemplaza a la que recibía `entryPoint`). Checks nuevos: `STACK_PROFILE` (solo si `profile == null`), `ENTRY_FILES`, `PROFILE_STRUCTURE`, `DDD_LAYERS`. Desaparece `ENTRY_POINT`.

- [ ] **Step 1: Adaptar el test (falla por la firma nueva)**

En `StaticWorkspaceValidatorTest`, reemplazar el `setUp` y `ALLOWED` por un repo Godot real:

```java
    private static final List<String> ALLOWED = List.of("Juego.sln", "game", "src/Combate.Domain", "src/Combate.Application");
    private static final List<String> CONTEXTS = List.of("Combate");
    private static final StackProfile PROFILE = StackProfile.GODOT_DOTNET_GAME;

    @BeforeEach
    void setUp() throws Exception {
        workspace = new DevelopmentWorkspaceService(tempRoot.toString(), git);
        validator = new StaticWorkspaceValidator(workspace, git);

        var neo = workspace.commitAgentWork("M-1", "M-1-ENGINEERING", "engineering", "Neo",
                new DevelopmentResult("base", List.of(
                        new GeneratedFile("Juego.sln", "Microsoft Visual Studio Solution File"),
                        new GeneratedFile("game/project.godot", "config_version=5"),
                        new GeneratedFile("game/Main.cs", "using Godot;\nusing Combate.Application;"))));
        work.add(new CommittedWork("M-1-ENGINEERING", "engineering", neo.sha(), neo.files()));

        var iris = workspace.commitAgentWork("M-1", "M-1-BACKEND", "backend", "Iris",
                new DevelopmentResult("dominio", List.of(
                        new GeneratedFile("src/Combate.Domain/Unidad.cs", "namespace Combate.Domain;\npublic class Unidad {}"))));
        work.add(new CommittedWork("M-1-BACKEND", "backend", iris.sha(), iris.files()));
    }

    private List<StaticCheck> validate(List<CommittedWork> items, List<String> allowed) {
        return validator.validate("M-1", items, PROFILE, CONTEXTS, allowed);
    }
```

Reemplazar cada `validator.validate("M-1", <work>, "<entryPoint>", <allowed>)` por `validate(<work>, <allowed>)`; borrar `failsWhenTheEntryPointIsMissing` y ajustar `failsWhenFilesAreOutsideTheOwnedPaths` a `validate(work, List.of("Juego.sln"))`; en `failsWhenTheRepositoryContainsASymlink` crear el symlink en `game/link.cs`; en `failsWhenThereIsNoRepository` usar `validator.validate("M-SIN-REPO", work, PROFILE, CONTEXTS, ALLOWED)`. Agregar (import `com.aicompany.core.model.StackProfile`):

```java
    @Test
    void aDomainFileImportingGodotFailsDddLayers() throws Exception {
        var bad = workspace.commitAgentWork("M-1", "M-1-DEVOPS", "devops", "Diego",
                new DevelopmentResult("mal", List.of(
                        new GeneratedFile("src/Combate.Domain/Mala.cs", "using Godot;\nnamespace Combate.Domain;"))));
        var items = new ArrayList<>(work);
        items.add(new CommittedWork("M-1-DEVOPS", "devops", bad.sha(), bad.files()));

        var ddd = find(validate(items, ALLOWED), "DDD_LAYERS");
        assertFalse(ddd.passed());
        assertTrue(ddd.detail().contains("src/Combate.Domain/Mala.cs"));
    }

    @Test
    void aMissingProjectGodotFailsEntryFiles() throws Exception {
        git.run(workspace.missionWorkspace("M-1"), "rm", "-q", "game/project.godot");
        git.run(workspace.missionWorkspace("M-1"), "-c", "user.name=t", "-c", "user.email=t@t",
                "-c", "commit.gpgsign=false", "commit", "-q", "-m", "rm");
        assertFalse(find(validate(work, ALLOWED), "ENTRY_FILES").passed());
    }

    @Test
    void aPlanWithoutProfileFailsStackProfile() {
        var checks = validator.validate("M-1", work, null, CONTEXTS, ALLOWED);
        assertFalse(find(checks, "STACK_PROFILE").passed());
    }

    @Test
    void theHappyPathIncludesTheNewChecks() {
        var names = validate(work, ALLOWED).stream().map(StaticCheck::check).toList();
        assertTrue(names.containsAll(List.of("ENTRY_FILES", "PROFILE_STRUCTURE", "DDD_LAYERS")), names.toString());
    }
```

- [ ] **Step 2: Correr** — `cd app && mvn test -Dtest=StaticWorkspaceValidatorTest` → FAIL de compilación (firma).

- [ ] **Step 3: Implementar**

1. Firma pública:

```java
    public List<StaticCheck> validate(
            String missionId, List<CommittedWork> work, StackProfile profile, List<String> contexts,
            List<String> allowedPaths) {
```

y la llamada interna `validateHead(dir, profile, contexts, allowedPaths, checks);`.

2. En `validateHead`, cambiar la firma a `(Path dir, StackProfile profile, List<String> contexts, List<String> allowedPaths, List<StaticCheck> checks)`, acumular las rutas del `ls-tree` en `var paths = new ArrayList<String>();` (dentro del bucle existente, `paths.add(path);`), y reemplazar todo el bloque desde `if (entryPoint == null || entryPoint.isBlank()) {` hasta el final del método por:

```java
        if (profile == null) {
            checks.add(StaticCheck.fail("STACK_PROFILE", "El plan no declaró un stackProfile del catálogo.",
                    null, List.of()));
            return;
        }

        checks.addAll(ProfileStructureChecker.check(profile, contexts, paths));

        var contents = new LinkedHashMap<String, String>();
        try {
            for (var path : paths) {
                if (path.endsWith(profile.sourceExtension()) || path.equals("pubspec.yaml")) {
                    contents.put(path, git.run(dir, "show", "HEAD:" + path));
                }
            }
        } catch (IOException ex) {
            checks.add(StaticCheck.fail("DDD_LAYERS", "No se pudo leer el código para el chequeo DDD: "
                    + ex.getMessage(), null, List.of()));
            return;
        }

        var analyzed = (int) contents.keySet().stream().filter(p -> p.endsWith(profile.sourceExtension())).count();
        checks.add(DddLayerChecker.toCheck(DddLayerChecker.check(profile, contexts, contents), analyzed));
```

Imports: `com.aicompany.core.agent.validation.DddLayerChecker`, `com.aicompany.core.agent.validation.ProfileStructureChecker`, `com.aicompany.core.model.StackProfile`, `java.util.LinkedHashMap`.

- [ ] **Step 4: Correr** — `cd app && mvn test -Dtest=StaticWorkspaceValidatorTest` → PASS. (Compilará `DevelopmentTeamStrategy` con error hasta Task 8; si Maven no compila el módulo, completar Task 8 Step 3.1 antes de correr y anotarlo.)

- [ ] **Step 5: Commit** (junto con Task 8 si hubo que tocar `DevelopmentTeamStrategy` para compilar)

```bash
git add app/src/main/java/com/aicompany/core/service/StaticWorkspaceValidator.java app/src/test/java/com/aicompany/core/service/StaticWorkspaceValidatorTest.java
git commit -m "StaticWorkspaceValidator: estructura del perfil, archivos de entrada y capas DDD en la capa 1" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: `DevelopmentTeamStrategy` — prompts, reportes y nueva llamada al validador

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java`
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java`

**Interfaces:**
- Consumes: `TeamPlan.profile()`, `contextNames()`, `ubiquitousLanguageOrEmpty()` (Task 2); `StaticWorkspaceValidator.validate(missionId, work, profile, contexts, allowedPaths)` (Task 7); `StackProfile.describe()` (Task 1).
- Produces: comportamiento (prompts de desarrollo y revisión con perfil, contextos, glosario y reglas DDD; reporte al CEO y estado verificable con perfil y contextos).

- [ ] **Step 1: Adaptar el test y agregar aserciones (fallan)**

En `DevelopmentTeamStrategyTest`, reemplazar la construcción del plan dentro de `context()` por:

```java
        var plan = new TeamPlan("Juego de combate", null, null, List.of(
                new PlannedTask("engineering", "WORK", "ARCHITECTURE", "Base", List.of("arquitectura backend"), List.of("Juego.sln", "game")),
                new PlannedTask("frontend-ui", "WORK", "GAME_UI", "HUD", List.of("Game UI"), List.of("src/Combate.Application")),
                new PlannedTask("qa", "VALIDATION", "STATIC_REVIEW", "Revisar", List.of("QA"), List.of())),
                List.of(), "GODOT_DOTNET_GAME",
                List.of(new TeamPlan.BoundedContext("Combate", "Combate por turnos")),
                List.of(new TeamPlan.GlossaryTerm("Unidad", "Personaje"),
                        new TeamPlan.GlossaryTerm("Turno", "Momento de acción"),
                        new TeamPlan.GlossaryTerm("Daño", "Vida que resta un ataque")));
```

En `stubHappyPath`, cambiar el stub del validador a:

```java
        when(validator.validate(eq("M-1"), anyList(), eq(StackProfile.GODOT_DOTNET_GAME), eq(List.of("Combate")), anyList()))
                .thenReturn(List.of(StaticCheck.pass("DDD_LAYERS", "ok", null, List.of())));
```

(y en los stubs de `commitAgentWork`/`generate` usar los `ownedPaths` nuevos si algún test los compara). Agregar:

```java
    @Test
    void workAndReviewPromptsCarryTheProfileContextsAndGlossary() throws Exception {
        stubHappyPath();
        var workPrompt = ArgumentCaptor.forClass(String.class);
        when(runtime.generate(eq("M-1-FRONTEND-UI"), anyString(), anyString(), workPrompt.capture(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(dev("src/Combate.Application/Atacar.cs")));
        var reviewPrompt = ArgumentCaptor.forClass(String.class);
        when(runtime.review(anyString(), anyString(), anyString(), reviewPrompt.capture(), anyMap()))
                .thenReturn(CompletableFuture.completedFuture(cleanReview()));

        var result = (TeamExecutionResult.Development) strategy.execute(context(), progress);

        assertTrue(workPrompt.getValue().contains("GODOT_DOTNET_GAME"));
        assertTrue(workPrompt.getValue().contains("Combate"));
        assertTrue(workPrompt.getValue().contains("Unidad: Personaje"));
        assertTrue(reviewPrompt.getValue().contains("lenguaje ubicuo"));
        assertTrue(reviewPrompt.getValue().contains("anémico"));
        assertTrue(result.resultsForCeo().contains("GODOT_DOTNET_GAME"));
    }
```

(Imports: `org.mockito.ArgumentCaptor`, `com.aicompany.core.model.StackProfile`.)

- [ ] **Step 2: Correr** — `cd app && mvn test -Dtest=DevelopmentTeamStrategyTest` → FAIL (firma del validador y prompts sin perfil).

- [ ] **Step 3: Implementar**

1. Llamada al validador (reemplaza la línea `var checks = staticValidator.validate(missionId, committed, plan.entryPoint(), allowedPaths);`):

```java
        var profile = plan.profile().orElse(null);
        var checks = staticValidator.validate(missionId, committed, profile, plan.contextNames(), allowedPaths);
```

2. Agregar el helper:

```java
    private static String dddContext(TeamPlan plan) {
        var profile = plan.profile().map(StackProfile::describe).orElse("- (sin perfil)");
        var contexts = plan.boundedContextsOrEmpty().stream()
                .map(c -> "- " + c.name() + ": " + c.description())
                .collect(Collectors.joining("\n"));
        var glossary = plan.ubiquitousLanguageOrEmpty().stream()
                .map(t -> "- " + t.term() + ": " + t.definition())
                .collect(Collectors.joining("\n"));
        return """
                PERFIL DE STACK (metodología DDD obligatoria):
                %s
                BOUNDED CONTEXTS:
                %s
                LENGUAJE UBICUO (usa estos términos en el código):
                %s
                REGLAS DE CAPAS: domain no depende de nada fuera de su domain ni de frameworks; application solo de
                domain; infrastructure/api/presentation/game dependen de application y domain.
                """.formatted(profile, contexts, glossary);
    }
```

3. En `buildWorkPrompt`: reemplazar las dos líneas `Tecnología: %s` y `Punto de entrada: %s` por una línea `%s` y, en los argumentos de `.formatted(...)`, reemplazar `plan.techStack(), plan.entryPoint()` por `dddContext(plan)`.

4. En `buildReviewPrompt`: reemplazar la línea `Tecnología: %s | Punto de entrada: %s` por `%s` (argumentos: `dddContext(context.plan())` en lugar de `context.plan().techStack(), context.plan().entryPoint()`), y agregar a "QUÉ DEBES HACER" las líneas:

```
                - Revisión DDD: ¿el código usa el lenguaje ubicuo del glosario? ¿Hay entidades, value objects y
                  agregados con sentido? ¿El dominio es anémico (solo datos, sin reglas)? Repórtalo en findings.
```

5. En `resultsForCeo`: reemplazar el `append` de `" | Tecnología: "` y `" | Punto de entrada: "` por:

```java
                .append(" | Perfil: ").append(context.plan().stackProfile())
                .append(" | Bounded contexts: ").append(context.plan().contextNames()).append("\n\n");
```

6. En `verifiableState`: después de la línea `Workspace:` agregar `out.append("Perfil: ").append(context.plan().stackProfile()).append(" | Bounded contexts: ").append(context.plan().contextNames()).append("\n");`.

Imports: `com.aicompany.core.model.StackProfile`, `com.aicompany.core.agent.model.TeamPlan` (ya existe), `java.util.stream.Collectors` (ya existe).

- [ ] **Step 4: Correr** — `cd app && mvn test -Dtest='DevelopmentTeamStrategyTest,StaticWorkspaceValidatorTest'` → PASS; `cd app && mvn test` → BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java
git commit -m "Desarrollo: prompts, revisión de Vera y reportes con perfil, bounded contexts y lenguaje ubicuo" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Documentación y verificación en vivo

**Files:**
- Modify: `CLAUDE.md`, `docs/HISTORY.md`

- [ ] **Step 1: `CLAUDE.md`** — en "Misiones por equipo", después del párrafo de Engineering, agregar:

```markdown
**DDD y perfiles de stack** (spec `2026-09-26-sandbox-verification-design.md`, parte 1): el plan de Engineering declara `stackProfile` (catálogo fijo `StackProfile`: `DOTNET_APP`, `GODOT_DOTNET_GAME`, `FLUTTER_WEB_APP`), `boundedContexts` y `ubiquitousLanguage` (≥3 términos); los `ownedPaths` tienen que caer dentro de la estructura DDD del perfil. La capa 1 suma `ENTRY_FILES`, `PROFILE_STRUCTURE` y `DDD_LAYERS` (`DddLayerChecker`, Java, lee `using` de C# e `import` de Dart; `domain` no depende de frameworks ni de otras capas). El sandbox de build/test/arranque es la parte 2.
```

- [ ] **Step 2: Suite** — `cd app && mvn test` → BUILD SUCCESS, 0 failures.

- [ ] **Step 3: Verificación en vivo**

1. Confirmar que no hay misiones en curso (misma query Cypher de siempre, `running = 0`), luego `docker compose build && docker compose up -d` y health `UP`.
2. Lanzar:

```bash
curl -s -X POST localhost:8081/api/company/missions -H 'Content-Type: application/json' -d '{
  "missionId": "MISSION-DDD-VERIFY-1",
  "instruction": "Crear un juego 2D de combate por turnos en Godot con C#, pequeño y completo como MVP.",
  "environment": "TEST",
  "teamId": "TEAM-ENGINEERING"
}'
```

3. Al terminar, registrar: `stackProfile`, `boundedContexts` y glosario del plan (resultado de la tarea `-PLAN`), intentos de plan y motivos de rechazo, archivos por commit, y el resultado de `ENTRY_FILES`/`PROFILE_STRUCTURE`/`DDD_LAYERS` (`GET /missions/{id}/details`, tarea de validación → `staticChecks`).
4. **Prueba de control**: en una copia del workspace de esa misión (nunca en el original), agregar un archivo `src/<Ctx>.Domain/Mala.cs` con `using Godot;`, commitearlo y correr el chequeo con un test de integración temporal o comprobarlo con `DddLayerCheckerCSharpTest`; documentar que `DDD_LAYERS` falla.

- [ ] **Step 4: `docs/HISTORY.md`** — entrada "DDD y perfiles de stack (sandbox parte 1)" con las decisiones (perfil del catálogo, contextos, glosario, reglas de capas, checker en Java sin herramientas externas), bugs reales encontrados en la implementación/verificación, y la salida real del Step 3.

- [ ] **Step 5: Commit**

```bash
git add CLAUDE.md docs/HISTORY.md
git commit -m "Documentar DDD y perfiles de stack; registrar verificación en vivo" \
           -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Self-review

- **Cobertura del spec (parte estructural)**: §1 catálogo → Task 1; contrato del plan (`stackProfile`, `boundedContexts`, `ubiquitousLanguage`, rutas dentro de la estructura, fin de `entryPoint`) → Tasks 2-3; `DddLayerChecker` C#/Dart → Tasks 5-6; `ENTRY_FILES`/`PROFILE_STRUCTURE` → Task 4; integración en capa 1 → Task 7; revisión DDD de Vera y prompts → Task 8; verificación en vivo → Task 9. §2 (sandbox-runner), §3 (dependencias) y la parte de ejecución de §4 (`VERIFIED`, `SandboxResult`, eventos, frontend) quedan para las partes 2 y 3, por diseño.
- **Consistencia de tipos**: `StackProfile.locate/isWithinStructure/missingEntryFiles/describe/describeAll` (Task 1) usados con esas firmas en Tasks 2-8; `TeamPlan` de 8 componentes (Task 2) en Tasks 3 y 8; `StaticWorkspaceValidator.validate(missionId, work, profile, contexts, allowedPaths)` (Task 7) en Task 8; `DddLayerChecker.check/toCheck` (Task 5) en Task 7.
- **Orden de compilación**: Tasks 2, 7 y 8 cambian firmas consumidas por otros archivos; cada una indica qué completar antes de correr la suite si el módulo no compila.
