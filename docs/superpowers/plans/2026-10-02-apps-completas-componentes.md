# Apps completas con varios componentes (B1) — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que una misión del Development Group construya una aplicación completa (API .NET en `backend/` + app Flutter en `app/` + migraciones PostgreSQL en la raíz) en un solo repositorio, con el contrato de rutas generado por Java y cada componente verificado en el sandbox.

**Architecture:** un record `Component(profile, dir)` adapta las piezas existentes, que siguen trabajando sobre rutas relativas a su perfil: Java quita el prefijo `dir/` antes de llamar a `StackProfile`, `ProfileStructureChecker`, `DddLayerChecker`, `ProjectScaffold` y `MissingUsingFixer`, y lo vuelve a poner en los resultados. Con un solo componente `dir` es `""` y todo funciona exactamente como hoy. Lo nuevo es puro y determinista: `HttpRouteExtractor`, `ApiRoutesGenerator`, `ApiContractGate`, `PortabilityChecker`, `ComponentSandbox`.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Jackson 3 (`tools.jackson.*`), JUnit 5 + Mockito, Neo4j (sin cambios de esquema), `sandbox-runner` (Spring Boot, Podman), React + TS (Command Center), Flutter 3.24 / .NET 8 en las imágenes del sandbox.

**Spec:** `docs/superpowers/specs/2026-10-02-apps-completas-componentes-design.md`

## Global Constraints

- Rama `apps-completas-b1`. Suites verdes al final de cada tarea: `cd app && mvn test`; las tareas que tocan `sandbox-runner` además `cd sandbox-runner && mvn test`; la que toca el frontend `cd app/frontend && npm run lint && npm run build`.
- Jackson 3: `tools.jackson.*`, nunca `com.fasterxml.jackson.*`. Sin `@Async`. Errores HTTP: `IllegalArgumentException`/`IllegalStateException` sin manejo nuevo.
- Determinismo sobre LLM: carpetas, reparto, rutas y verificación los decide Java; el modelo nunca elige carpetas.
- Carpetas fijas: `DOTNET_APP` → `backend`, `FLUTTER_WEB_APP` → `app`, `GODOT_DOTNET_GAME` → `game-client`; solo con ≥2 componentes. Un componente → `dir = ""` (raíz, comportamiento actual intacto).
- Comunes en la raíz con ≥2 componentes: `db/postgres/migrations`, `.env.example`, `README.md`, `.gitignore`, `docs`.
- Orden fijo de componentes (Java): `DOTNET_APP`, `GODOT_DOTNET_GAME`, `FLUTTER_WEB_APP`. El primero es el **principal**: su formato de nombre de contexto es el que se valida (`Clientes`), y Java deriva el de los demás (`clientes`).
- Archivo de rutas: `app/lib/api/api_routes.dart`, autor "Forjai", tarea `<misión>-API-ROUTES`. Nadie más lo escribe.
- Paquete Flutter preaprobado: `http` **1.2.2** (`PUB:http@1.2.2`), precargado en la imagen.
- Imports prohibidos en todo componente Flutter: `dart:html`, `dart:js`, `dart:js_util`, `dart:js_interop`, `package:web/`.
- Comentarios y textos para el modelo en español, como el resto del código. Cada regla nueva nacida de una verificación lleva su comentario "Verificado en vivo (…)" o "Spec 2026-10-02 B1 §n".

## Review Focus

1. **Una misión de un solo componente debe quedar idéntica a hoy** (rutas sin prefijo, mismos pasos y evidencias del sandbox, mismos chequeos). Cada tarea que toca un flujo existente lleva un test "un componente = sin cambios".
2. **Neo devuelve los componentes en otro orden o repite `stackProfile` dentro de `components`** (`["FLUTTER_WEB_APP","DOTNET_APP"]` con `stackProfile:"FLUTTER_WEB_APP"`): Java debe ordenar y deduplicar sin error, y el backend debe generarse antes que la app. Test en la Task 1.
3. **Rutas con `MapGroup` en una variable definida en otro archivo o encadenada** (`var api = app.MapGroup("/api"); var v1 = api.MapGroup("/v1"); v1.MapGet("/clientes/{id:guid}", …)`): la ruta debe salir `/api/v1/clientes/{id}`. Test en la Task 6.
4. **Errores del compilador de la app** (`lib/clientes/presentation/x.dart:3:5: Error: …` dentro del paso `app/build`): deben volver a Mila con la ruta `app/lib/...`, nunca perderse por falta de prefijo. Test en la Task 10.
5. **El sandbox-runner cae en el segundo componente**: la misión queda `UNVALIDATED` con el motivo, nunca `VERIFIED` por haber pasado el primero. Test en la Task 10.

---

## Mapa de archivos

Nuevos (company-core, `app/src/main/java/com/aicompany/core/`):
- `model/Component.java` — componente (perfil + carpeta), prefijo/quitar prefijo, nombres de contexto por perfil, orden y carpetas fijas.
- `model/ComponentScaffold.java` — `.csproj`/`.sln` por componente con prefijo y proyectos esperados.
- `model/HttpRoute.java` — `(method, template)`.
- `agent/validation/HttpRouteExtractor.java` — rutas minimal API desde C#.
- `agent/validation/ApiRoutesGenerator.java` — contenido de `api_routes.dart`.
- `agent/validation/ApiContractGate.java` — rutas escritas a mano / localhost en Dart.
- `agent/validation/PortabilityChecker.java` — imports solo-web.
- `agent/validation/ComponentStructureChecker.java` — `ENTRY_FILES`, `PROFILE_STRUCTURE`, `DDD_LAYERS`, `PORTABILITY`, `API_ROUTES_CONSISTENT` por componente.
- `service/ComponentSandbox.java` — combinación de resultados del sandbox por componente y errores del compilador con prefijo.

Modificados: `agent/model/TeamPlan.java`, `agent/model/TeamPlanSchema.java`, `service/TeamWorkPlanner.java`, `service/MissionExecutor.java`, `agent/validation/TeamPlanValidator.java`, `agent/validation/TeamPlanResolver.java`, `model/RoleLayerCatalog.java`, `model/StackProfile.java`, `model/BaselineDependencies.java`, `service/StaticWorkspaceValidator.java`, `service/SandboxRunnerClient.java`, `service/DevelopmentTeamStrategy.java`, `service/ChatIntentRouter.java`; `sandbox-runner/.../JobController.java`, `VerifyJobRunner.java`; `sandbox/images/flutter-web-app/Dockerfile`, `sandbox/build-images.sh`; `app/frontend/src/pages/MissionDetailPage.tsx`; `CLAUDE.md`, `docs/HISTORY.md`.

---

### Task 1: `Component` y `TeamPlan.components`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/Component.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/model/TeamPlan.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/model/TeamPlanSchema.java`
- Modify: `app/src/main/java/com/aicompany/core/service/TeamWorkPlanner.java:270-281,314-315`
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java:288`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/TeamPlanResolver.java:104-105`
- Test: `app/src/test/java/com/aicompany/core/model/ComponentTest.java`

**Interfaces:**
- Produces:
  - `record Component(StackProfile profile, String dir)` con `static List<Component> of(List<StackProfile> profiles)`, `String prefix(String path)`, `Optional<String> strip(String path)`, `boolean owns(String path)`, `List<String> contexts(List<String> names)`, `boolean isRoot()`, `static final List<String> COMMON_ROOT_PATHS`, `static String dirOf(StackProfile)`.
  - `TeamPlan` gana el componente de record `List<String> components` (último parámetro) y los métodos `List<StackProfile> componentProfiles()`, `List<Component> resolvedComponents()`, `boolean hasComponent(StackProfile)`; `profile()` pasa a ser el principal (primero de `resolvedComponents()`).

- [ ] **Step 1: Escribir el test que falla**

```java
package com.aicompany.core.model;

import com.aicompany.core.agent.model.TeamPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ComponentTest {

    @Test
    void oneComponentLivesAtTheRootLikeToday() {
        var components = Component.of(List.of(StackProfile.FLUTTER_WEB_APP));
        assertEquals(List.of(new Component(StackProfile.FLUTTER_WEB_APP, "")), components);
        assertEquals("lib/main.dart", components.get(0).prefix("lib/main.dart"));
        assertEquals(Optional.of("lib/main.dart"), components.get(0).strip("lib/main.dart"));
    }

    // Spec 2026-10-02 B1 §1: carpetas fijas por perfil y orden fijo (backend antes que app), sin importar el de Neo.
    @Test
    void severalComponentsGetFixedDirsInFixedOrder() {
        var components = Component.of(List.of(StackProfile.FLUTTER_WEB_APP, StackProfile.DOTNET_APP,
                StackProfile.FLUTTER_WEB_APP));
        assertEquals(List.of(new Component(StackProfile.DOTNET_APP, "backend"),
                new Component(StackProfile.FLUTTER_WEB_APP, "app")), components);
        var app = components.get(1);
        assertEquals("app/lib/main.dart", app.prefix("lib/main.dart"));
        assertEquals(Optional.of("lib/main.dart"), app.strip("app/lib/main.dart"));
        assertEquals(Optional.empty(), app.strip("backend/src/X.Api/Program.cs"));
        assertEquals(Optional.empty(), app.strip("application/x.dart"));
        assertTrue(app.owns("app"));
    }

    @Test
    void contextNamesFollowEachProfileConvention() {
        var backend = new Component(StackProfile.DOTNET_APP, "backend");
        var app = new Component(StackProfile.FLUTTER_WEB_APP, "app");
        assertEquals(List.of("GestionClientes"), backend.contexts(List.of("GestionClientes")));
        assertEquals(List.of("gestion_clientes"), app.contexts(List.of("GestionClientes")));
        assertEquals(List.of("GestionClientes"), backend.contexts(List.of("gestion_clientes")));
    }

    // Review Focus 2: Neo repite stackProfile dentro de components o los manda desordenados.
    @Test
    void planComponentsAreDeduplicatedAndFallBackToStackProfile() {
        var multi = plan("FLUTTER_WEB_APP", List.of("FLUTTER_WEB_APP", "DOTNET_APP"));
        assertEquals(List.of(StackProfile.DOTNET_APP, StackProfile.FLUTTER_WEB_APP), multi.componentProfiles());
        assertEquals(StackProfile.DOTNET_APP, multi.profile().orElseThrow());
        assertTrue(multi.hasComponent(StackProfile.FLUTTER_WEB_APP));

        var legacy = plan("DOTNET_APP", null);
        assertEquals(List.of(new Component(StackProfile.DOTNET_APP, "")), legacy.resolvedComponents());
        assertEquals(StackProfile.DOTNET_APP, legacy.profile().orElseThrow());
    }

    private static TeamPlan plan(String stackProfile, List<String> components) {
        return new TeamPlan("App", null, null, List.of(), List.of(), stackProfile, List.of(), List.of(), null,
                components);
    }
}
```

- [ ] **Step 2: Correr el test y verificar que falla**

Run: `cd app && mvn -q test -Dtest=ComponentTest`
Expected: FAIL de compilación (`Component` no existe; `TeamPlan` no tiene 10 parámetros).

- [ ] **Step 3: Crear `Component`**

```java
package com.aicompany.core.model;

import com.aicompany.core.agent.validation.OwnedPaths;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Spec 2026-10-02 B1 §1: una parte de la aplicación (perfil + carpeta). Las piezas existentes siguen trabajando con
 * rutas relativas a su perfil: Java quita {@code dir/} antes de llamarlas y lo vuelve a poner en los resultados.
 * Con un solo componente {@code dir} es "" y todo queda como antes. La carpeta la fija Java, nunca el modelo.
 */
public record Component(StackProfile profile, String dir) {

    /** Orden fijo: el backend se genera antes que la app que lo consume. */
    private static final List<StackProfile> ORDER =
            List.of(StackProfile.DOTNET_APP, StackProfile.GODOT_DOTNET_GAME, StackProfile.FLUTTER_WEB_APP);

    private static final Map<StackProfile, String> DIRS = Map.of(
            StackProfile.DOTNET_APP, "backend",
            StackProfile.FLUTTER_WEB_APP, "app",
            StackProfile.GODOT_DOTNET_GAME, "game-client");

    /** Con ≥2 componentes, lo que vive en la raíz del repositorio (compartido por todos). */
    public static final List<String> COMMON_ROOT_PATHS =
            List.of("db/postgres/migrations", ".env.example", "README.md", ".gitignore", "docs");

    public static String dirOf(StackProfile profile) {
        return DIRS.get(profile);
    }

    public static List<Component> of(List<StackProfile> profiles) {
        var distinct = new LinkedHashSet<>(profiles);
        var ordered = ORDER.stream().filter(distinct::contains).toList();
        if (ordered.size() <= 1) {
            return ordered.stream().map(p -> new Component(p, "")).toList();
        }
        return ordered.stream().map(p -> new Component(p, DIRS.get(p))).toList();
    }

    public boolean isRoot() {
        return dir.isEmpty();
    }

    public String prefix(String path) {
        return isRoot() ? path : dir + "/" + path;
    }

    /** La ruta relativa al componente, o vacío si la ruta no es de este componente. */
    public Optional<String> strip(String path) {
        var normalized = OwnedPaths.normalize(path);
        if (isRoot()) {
            return Optional.of(normalized);
        }
        return normalized.startsWith(dir + "/") ? Optional.of(normalized.substring(dir.length() + 1)) : Optional.empty();
    }

    public boolean owns(String path) {
        var normalized = OwnedPaths.normalize(path);
        return isRoot() || normalized.equals(dir) || normalized.startsWith(dir + "/");
    }

    /** Los nombres del plan en la convención de este perfil (PascalCase en C#, snake_case en Dart). */
    public List<String> contexts(List<String> names) {
        return names.stream().map(this::contextName).toList();
    }

    private String contextName(String name) {
        if (name == null || profile.isValidContextName(name)) {
            return name;
        }
        if (profile.ecosystem() == StackProfile.Ecosystem.PUB) {
            return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
        }
        return Arrays.stream(name.split("_")).filter(s -> !s.isEmpty())
                .map(s -> Character.toUpperCase(s.charAt(0)) + s.substring(1))
                .collect(Collectors.joining());
    }
}
```

- [ ] **Step 4: Extender `TeamPlan`**

En `TeamPlan.java`, agregar `List<String> components` como **último** componente del record (después de `DatabaseNeed database`), y que el constructor de 9 parámetros que hoy es el canónico pase a delegar:

```java
public record TeamPlan(
        String summary,
        String techStack,
        String entryPoint,
        List<PlannedTask> tasks,
        List<ParticipationConflict> participationConflicts,
        String stackProfile,
        List<BoundedContext> boundedContexts,
        List<GlossaryTerm> ubiquitousLanguage,
        DatabaseNeed database,
        List<String> components
) {

    /** Planes de un solo perfil (todos los anteriores a la spec 2026-10-02 B1). */
    public TeamPlan(String summary, String techStack, String entryPoint, List<PlannedTask> tasks,
                    List<ParticipationConflict> participationConflicts, String stackProfile,
                    List<BoundedContext> boundedContexts, List<GlossaryTerm> ubiquitousLanguage, DatabaseNeed database) {
        this(summary, techStack, entryPoint, tasks, participationConflicts, stackProfile, boundedContexts,
                ubiquitousLanguage, database, null);
    }
```

Los constructores de 4, 5 y 8 parámetros que hoy llaman a `this(..., null)` con 9 argumentos siguen compilando sin cambios (delegan al de 9). Reemplazar `profile()` y agregar los métodos nuevos:

```java
    /** Spec 2026-10-02 B1 §1: los perfiles de la misión, sin repetidos y en el orden fijo de Component. */
    public List<com.aicompany.core.model.StackProfile> componentProfiles() {
        var ids = new java.util.ArrayList<String>();
        if (components != null) {
            components.stream().filter(Objects::nonNull).forEach(ids::add);
        }
        if (stackProfile != null && !stackProfile.isBlank()) {
            ids.add(stackProfile);
        }
        var profiles = ids.stream().map(com.aicompany.core.model.StackProfile::parse)
                .flatMap(Optional::stream).toList();
        return com.aicompany.core.model.Component.of(profiles).stream()
                .map(com.aicompany.core.model.Component::profile).toList();
    }

    public List<com.aicompany.core.model.Component> resolvedComponents() {
        return com.aicompany.core.model.Component.of(componentProfiles());
    }

    public boolean hasComponent(com.aicompany.core.model.StackProfile profile) {
        return componentProfiles().contains(profile);
    }

    /** El componente principal: define el formato de los nombres de contexto. */
    public Optional<com.aicompany.core.model.StackProfile> profile() {
        return componentProfiles().stream().findFirst();
    }
```

- [ ] **Step 5: Propagar `components` donde se copia un plan**

En los tres lugares que hoy construyen un `TeamPlan` copiando otro, pasar `plan.components()` como décimo argumento:
- `TeamWorkPlanner.normalizeActions` (línea ~314): `new TeamPlan(summary, plan.techStack(), plan.entryPoint(), tasks, plan.participationConflicts(), plan.stackProfile(), plan.boundedContexts(), plan.ubiquitousLanguage(), plan.database(), plan.components())`.
- `MissionExecutor` línea ~288 (`var withRequest = new TeamPlan(...)`): agregar `, plan.components()` al final de la lista de argumentos.
- `TeamPlanResolver.resolve` (línea ~104): `new TeamPlan(plan.summary(), plan.techStack(), plan.entryPoint(), resolved, List.of(), plan.stackProfile(), plan.boundedContexts(), plan.ubiquitousLanguage(), plan.database(), plan.components())`.

Verificar que no quede otro: `grep -rn "plan.database())" app/src/main/java` → ninguna coincidencia que construya un `TeamPlan`.

- [ ] **Step 6: Schema y prompt de Neo**

En `TeamPlanSchema.SCHEMA`, dentro de `properties`, agregar (sin tocar `required`):

```java
                    // Spec 2026-10-02 B1: partes de la aplicación (opcional; sin él, solo stackProfile).
                    "components", Map.of("type", "array", "items", Map.of("type", "string")),
```

`Map.of` admite hasta 10 pares: hoy `properties` tiene 6, queda en 7.

En `TeamWorkPlanner`, reemplazar el bloque de `stackProfile` del prompt de desarrollo (líneas ~273-275) por:

```java
                - stackProfile: el perfil principal de la aplicación, EXACTAMENTE uno de este catálogo (no existen otros):
                %s
                - components: TODAS las partes que necesita el producto, con ids del mismo catálogo. Una app con pantallas
                  y datos guardados en un servidor es ["DOTNET_APP", "FLUTTER_WEB_APP"] (API .NET + app Flutter, que
                  sirve igual para web, Android e iOS); solo una API es ["DOTNET_APP"]; solo una app sin servidor es
                  ["FLUTTER_WEB_APP"]. Forjai decide las carpetas (backend/, app/): no las pongas en ninguna ruta.
                - boundedContexts: los bounded contexts del producto, cada uno con name y description. Con DOTNET_APP en
                  components, el name va en PascalCase (p. ej. Clientes); si no, en el formato del perfil. El name
                  define las rutas de sus capas. Para un producto chico, UN solo contexto.
```

y borrar la línea vieja de `boundedContexts` que queda duplicada.

- [ ] **Step 7: Correr los tests**

Run: `cd app && mvn -q test -Dtest='ComponentTest,TeamPlanResolverTest,TeamPlanValidatorTest,TeamWorkPlannerTest'`
Expected: PASS (los existentes siguen pasando: con un solo perfil `profile()` devuelve lo mismo que antes).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/Component.java app/src/main/java/com/aicompany/core/agent/model/TeamPlan.java app/src/main/java/com/aicompany/core/agent/model/TeamPlanSchema.java app/src/main/java/com/aicompany/core/service/TeamWorkPlanner.java app/src/main/java/com/aicompany/core/service/MissionExecutor.java app/src/main/java/com/aicompany/core/agent/validation/TeamPlanResolver.java app/src/test/java/com/aicompany/core/model/ComponentTest.java
git commit -m "Apps completas: componentes del plan con carpetas fijas por perfil

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Reparto por componente (`RoleLayerCatalog` + `TeamPlanResolver`)

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/RoleLayerCatalog.java`
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/TeamPlanResolver.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/TeamPlanResolverTest.java`

**Interfaces:**
- Consumes: `TeamPlan.resolvedComponents()`, `Component.prefix/contexts`.
- Produces: `RoleLayerCatalog.writesIn(String roleCode, StackProfile.Ecosystem ecosystem)`; `TeamPlanResolver.resolve` con rutas prefijadas por componente. Con un componente, salida idéntica a la actual.

- [ ] **Step 1: Tests que fallan** (agregar a `TeamPlanResolverTest`)

```java
    private static TeamPlan fullstack(List<PlannedTask> tasks) {
        return new TeamPlan("App", null, null, tasks, List.of(), "DOTNET_APP",
                List.of(new TeamPlan.BoundedContext("Clientes", "desc")),
                List.of(new TeamPlan.GlossaryTerm("a", "b"), new TeamPlan.GlossaryTerm("c", "d"),
                        new TeamPlan.GlossaryTerm("e", "f")), null, List.of("DOTNET_APP", "FLUTTER_WEB_APP"));
    }

    // Spec 2026-10-02 B1 §1: reparto por componente y capa; Iris nunca en app/, Mila nunca en backend/.
    @Test
    void fullstackSplitsLayersByComponent() {
        var result = resolver.resolve(fullstack(List.of(t("backend", "WORK", "api"), t("devops", "WORK", "persistence"),
                t("frontend-ui", "WORK", "flutter"), t("qa", "WORK", "tests"))), DEVELOPMENT);

        assertEquals(List.of(), result.errors());
        assertEquals(List.of("backend/src/Clientes.Domain", "backend/src/Clientes.Application", "backend/src/Clientes.Api"),
                of(result.plan(), "backend").get(0).ownedPaths());
        assertEquals(List.of("backend/src/Clientes.Infrastructure"), of(result.plan(), "devops").get(0).ownedPaths());
        assertEquals(List.of("app/lib/clientes/domain", "app/lib/clientes/application", "app/lib/clientes/infrastructure",
                        "app/lib/clientes/presentation", "app/pubspec.yaml", "app/lib/main.dart", "app/web"),
                of(result.plan(), "frontend-ui").get(0).ownedPaths());
        assertEquals(List.of("backend/tests/Clientes.Tests", "app/test/clientes"),
                of(result.plan(), "qa").stream().filter(x -> "WORK".equals(x.kind())).findFirst().orElseThrow().ownedPaths());
        assertEquals(1, of(result.plan(), "qa").stream().filter(x -> "VALIDATION".equals(x.kind())).count());
        assertEquals(List.of("DOTNET_APP", "FLUTTER_WEB_APP"), result.plan().components());
    }

    @Test
    void fullstackWithoutBackendOwnerIsAnError() {
        var result = resolver.resolve(fullstack(List.of(t("frontend-ui", "WORK", "flutter"), t("qa", "WORK", "tests"))),
                DEVELOPMENT);
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("DOMAIN") && e.contains("backend")),
                result.errors().toString());
    }
```

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn -q test -Dtest=TeamPlanResolverTest`
Expected: FAIL (`ownedPaths` sin prefijo; Iris recibe capas de Flutter).

- [ ] **Step 3: `RoleLayerCatalog.writesIn`**

```java
    /**
     * Spec 2026-10-02 B1 §1: en qué ecosistemas escribe cada rol cuando la misión tiene varios componentes (con uno
     * solo se mantiene el reparto de siempre, incluido el respaldo de Mila en las capas internas).
     */
    private static final Map<String, List<StackProfile.Ecosystem>> ECOSYSTEMS = Map.of(
            "BACKEND", List.of(StackProfile.Ecosystem.NUGET),
            "DATA_ARCHITECT", List.of(StackProfile.Ecosystem.NUGET),
            "GAME_DEV", List.of(StackProfile.Ecosystem.NUGET),
            "UI_UX", List.of(StackProfile.Ecosystem.PUB),
            "QA", List.of(StackProfile.Ecosystem.NUGET, StackProfile.Ecosystem.PUB));

    public static boolean writesIn(String roleCode, StackProfile.Ecosystem ecosystem) {
        return ECOSYSTEMS.getOrDefault(roleCode, List.of()).contains(ecosystem);
    }
```

(Agregar `import com.aicompany.core.model.StackProfile;` no hace falta: misma package. `Map` ya está importado.)

- [ ] **Step 4: Reescribir el núcleo de `TeamPlanResolver.resolve`**

Reemplazar desde `var layersByAgent = RoleLayerCatalog.assign(...)` hasta el `return` por un recorrido por componente. Código completo del tramo:

```java
        var components = plan.resolvedComponents();
        var multi = components.size() > 1;
        var pathsByAgent = new LinkedHashMap<String, List<String>>();
        merged.forEach(task -> pathsByAgent.put(task.agentId(), new ArrayList<>()));
        var errorsBefore = errors.size();

        for (var component : components) {
            var componentRoles = new LinkedHashMap<String, String>();
            roleCodeByAgent.forEach((agent, role) -> {
                if (!multi || RoleLayerCatalog.writesIn(role, component.profile().ecosystem())) {
                    componentRoles.put(agent, role);
                }
            });
            var layersByAgent = RoleLayerCatalog.assign(componentRoles, component.profile().layers());
            var componentContexts = component.contexts(contexts);
            var ownerByLayerRoot = new LinkedHashMap<String, String>();

            for (var entry : layersByAgent.entrySet()) {
                for (var layer : entry.getValue()) {
                    for (var root : roots(component.profile(), componentContexts, layer)) {
                        var prefixed = component.prefix(root);
                        pathsByAgent.get(entry.getKey()).add(prefixed);
                        ownerByLayerRoot.put(prefixed, entry.getKey());
                    }
                }
            }

            for (var context : componentContexts) {
                component.profile().resolveRoot(context, Layer.DOMAIN).map(component::prefix)
                        .filter(root -> !ownerByLayerRoot.containsKey(root))
                        .ifPresent(root -> errors.add("La capa DOMAIN del contexto " + context + " no tiene dueño"
                                + (component.isRoot() ? "" : " en " + component.dir() + "/ (" + component.profile().name() + ")")
                                + ": incluye a un miembro que escriba domain (BACKEND o UI_UX)."));
            }

            addEntryFiles(component, componentContexts, ownerByLayerRoot, pathsByAgent, errors);
        }

        var resolved = new ArrayList<PlannedTask>();
        for (var task : merged) {
            var member = membersById.get(task.agentId());
            var role = member == null ? Optional.<RoleLayerCatalog.RoleLayers>empty()
                    : RoleLayerCatalog.of(member.roleCode());
            var writesCode = role.isPresent() && role.get().writesCode() && RoleLayerCatalog.enabledNow(member.roleCode());
            if (!writesCode) {
                resolved.add(task);
                continue;
            }
            var paths = pathsByAgent.get(task.agentId());
            if (paths.isEmpty()) {
                errors.add(task.agentId() + " (" + member.roleCode() + ") no tiene capas en "
                        + components.stream().map(c -> c.profile().name()).toList()
                        + ": otro miembro elegido ya las cubre o el perfil no las tiene. Quítalo del plan.");
            }
            resolved.add(withKindAndPaths(task, TeamPlan.KIND_WORK, task.action(), paths));
            if ("QA".equals(member.roleCode())) {
                resolved.add(new PlannedTask(task.agentId(), TeamPlan.KIND_VALIDATION, REVIEW_ACTION,
                        "Revisar el código commiteado y los resultados reales del sandbox.",
                        task.requiredCapabilities(), List.of(), List.of()));
            }
        }

        addMigrations(plan, membersById, resolved);

        return new Resolution(new TeamPlan(plan.summary(), plan.techStack(), plan.entryPoint(), resolved,
                List.of(), plan.stackProfile(), plan.boundedContexts(), plan.ubiquitousLanguage(), plan.database(),
                plan.components()), errors);
```

Al principio de `resolve`, el `if (profile.isEmpty())` sigue igual (sin perfiles válidos no se resuelve nada; lo rechaza el validador). Borrar la variable `errorsBefore` si no se usa. Cambiar `addEntryFiles` para trabajar por componente y sobre el mapa de rutas:

```java
    private static void addEntryFiles(Component component, List<String> contexts,
                                      Map<String, String> ownerByLayerRoot, Map<String, List<String>> pathsByAgent,
                                      List<String> errors) {

        var entryFiles = component.profile().leaderOwnedPaths().stream().map(component::prefix).toList();
        if (entryFiles.isEmpty()) {
            return;
        }

        String owner = null;
        for (var layer : List.of(Layer.PRESENTATION, Layer.API, Layer.GAME)) {
            for (var root : roots(component.profile(), contexts, layer)) {
                var prefixed = component.prefix(root);
                if (owner == null && ownerByLayerRoot.containsKey(prefixed)) {
                    owner = ownerByLayerRoot.get(prefixed);
                }
            }
        }

        if (owner == null) {
            errors.add("Los archivos de entrada " + entryFiles + " de " + component.profile().name() + " necesitan un "
                    + "dueño de la capa PRESENTATION, API o GAME: incluye a ese miembro en el plan.");
            return;
        }

        var paths = pathsByAgent.get(owner);
        entryFiles.stream().filter(file -> !paths.contains(file)).forEach(paths::add);
    }
```

Importar `com.aicompany.core.model.Component`. Las migraciones y `.env.example` (`addMigrations`) quedan en la raíz: con varios componentes ya son comunes, no se prefijan.

- [ ] **Step 5: Correr los tests**

Run: `cd app && mvn -q test -Dtest=TeamPlanResolverTest`
Expected: PASS, incluidos los tests viejos de un solo perfil (`flutterWithOnlyUiAndQaGivesUiTheFallbackLayers` y el resto quedan iguales).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/RoleLayerCatalog.java app/src/main/java/com/aicompany/core/agent/validation/TeamPlanResolver.java app/src/test/java/com/aicompany/core/agent/validation/TeamPlanResolverTest.java
git commit -m "Apps completas: reparto de capas por componente y rol

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Validación del plan con componentes

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/TeamPlanValidator.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/TeamPlanValidatorTest.java`

**Interfaces:**
- Consumes: `TeamPlan.components()`, `TeamPlan.resolvedComponents()`, `Component.strip/contexts/COMMON_ROOT_PATHS`.
- Produces: errores nuevos con textos exactos (los usa Neo para corregir): `"components: \"X\" no es un perfil del catálogo"`, `"components: \"X\" está repetido"`, `"El stack X todavía no está habilitado"`.

- [ ] **Step 1: Tests que fallan** (en `TeamPlanValidatorTest`, usando sus helpers existentes para armar un plan de desarrollo válido; si el test tiene un helper `devPlan(...)`, construir el plan con el constructor de 10 argumentos)

```java
    // Spec 2026-10-02 B1 §1.
    @Test
    void rejectsUnknownOrRepeatedComponents() {
        var plan = withComponents(validDevelopmentPlan(), List.of("DOTNET_APP", "REACT_APP", "DOTNET_APP"));
        var errors = validator.validate(plan, DEVELOPMENT_TEAM, TeamExecutionMode.DEVELOPMENT);
        assertTrue(errors.contains("components: \"REACT_APP\" no es un perfil del catálogo "
                + java.util.Arrays.toString(StackProfile.values()) + "."), errors.toString());
        assertTrue(errors.contains("components: \"DOTNET_APP\" está repetido."), errors.toString());
    }

    @Test
    void ownedPathsMayLiveInAnyComponentOrTheCommonRoot() {
        // las rutas las calcula el resolver; acá alcanza con verificar que backend/... y app/... son estructura válida
        var errors = validator.validate(resolvedFullstack(), DEVELOPMENT_TEAM, TeamExecutionMode.DEVELOPMENT);
        assertTrue(errors.stream().noneMatch(e -> e.contains("fuera de la estructura")), errors.toString());
    }

    @Test
    void aDatabaseNeedsADotnetComponentNotTheMainProfile() {
        var errors = validator.validate(withDatabase(resolvedFullstack()), DEVELOPMENT_TEAM, TeamExecutionMode.DEVELOPMENT);
        assertTrue(errors.stream().noneMatch(e -> e.contains("necesita un backend")), errors.toString());
    }
```

con los helpers (en el mismo test):

```java
    private static TeamPlan withComponents(TeamPlan p, List<String> components) {
        return new TeamPlan(p.summary(), p.techStack(), p.entryPoint(), p.tasks(), p.participationConflicts(),
                p.stackProfile(), p.boundedContexts(), p.ubiquitousLanguage(), p.database(), components);
    }

    /** stackProfile FLUTTER_WEB_APP a propósito: la base exige un componente .NET, no que sea el principal de Neo. */
    private static TeamPlan withDatabase(TeamPlan p) {
        return new TeamPlan(p.summary(), p.techStack(), p.entryPoint(), p.tasks(), p.participationConflicts(),
                "FLUTTER_WEB_APP", p.boundedContexts(), p.ubiquitousLanguage(),
                new TeamPlan.DatabaseNeed("POSTGRESQL", ""), p.components());
    }

    /** Plan fullstack ya resuelto (como lo deja TeamPlanResolver). */
    private TeamPlan resolvedFullstack() {
        var plan = new TeamPlan("App", null, null, List.of(
                new PlannedTask("backend", "WORK", "API", "API", List.of("api"), List.of()),
                new PlannedTask("devops", "WORK", "DATA", "Datos", List.of("persistence"), List.of()),
                new PlannedTask("frontend-ui", "WORK", "APP", "App", List.of("flutter"), List.of()),
                new PlannedTask("qa", "WORK", "TESTS", "Tests", List.of("tests"), List.of())),
                List.of(), "DOTNET_APP", List.of(new TeamPlan.BoundedContext("Clientes", "desc")),
                List.of(new TeamPlan.GlossaryTerm("a", "b"), new TeamPlan.GlossaryTerm("c", "d"),
                        new TeamPlan.GlossaryTerm("e", "f")), null, List.of("DOTNET_APP", "FLUTTER_WEB_APP"));
        return new TeamPlanResolver().resolve(plan, TeamPlanResolverTest.DEVELOPMENT).plan();
    }
```

Si `TeamPlanValidatorTest` no tiene `validDevelopmentPlan()` ni `DEVELOPMENT_TEAM`, usar `resolvedFullstack()` para los tres tests y `TeamPlanResolverTest.DEVELOPMENT` como equipo (los miembros de ese snapshot tienen las capabilities `api`, `persistence`, `flutter`, `tests`).

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn -q test -Dtest=TeamPlanValidatorTest`
Expected: FAIL (`backend/...` fuera de la estructura; base de datos exige `stackProfile` DOTNET_APP; componentes sin validar).

- [ ] **Step 3: Implementar en `validateDevelopmentRules`**

Reemplazar desde `var profile = plan.profile();` hasta el final del método por:

```java
        validateComponents(plan, errors);

        var profile = plan.profile();
        if (profile.isEmpty()) {
            errors.add("stackProfile debe ser uno de los perfiles del catálogo: "
                    + java.util.Arrays.toString(StackProfile.values()) + " (recibido: \"" + plan.stackProfile() + "\").");
            return;
        }

        for (var component : plan.resolvedComponents()) {
            if (!component.profile().enabledNow()) {
                errors.add("El stack " + component.profile().name() + " todavía no está habilitado (fase "
                        + component.profile().phase() + "). Stacks disponibles: " + java.util.Arrays.stream(StackProfile.values())
                        .filter(StackProfile::enabledNow).map(StackProfile::name).toList() + ".");
            }
        }

        validateContextsAndGlossary(plan, profile.get(), errors);

        var contexts = plan.contextNames();
        var components = plan.resolvedComponents();
        for (var task : work) {
            for (var path : task.ownedPathsOrEmpty()) {
                if (path != null && OwnedPaths.isSafe(path) && !withinStructure(components, contexts, path)) {
                    errors.add("El ownedPath \"" + path + "\" de " + task.agentId() + " está fuera de la estructura "
                            + "de " + components.stream().map(c -> c.profile().name()).toList() + ". Rutas permitidas: "
                            + allowedRoots(components, contexts) + " y los archivos de entrada de cada perfil.");
                }
            }
        }
    }

    /** Spec 2026-10-02 B1 §1: ids del catálogo y sin repetidos (el orden lo fija Java). */
    private static void validateComponents(TeamPlan plan, List<String> errors) {
        if (plan.components() == null) {
            return;
        }
        var seen = new HashSet<String>();
        for (var id : plan.components()) {
            if (StackProfile.parse(id).isEmpty()) {
                errors.add("components: \"" + id + "\" no es un perfil del catálogo "
                        + java.util.Arrays.toString(StackProfile.values()) + ".");
            } else if (!seen.add(id.strip())) {
                errors.add("components: \"" + id + "\" está repetido.");
            }
        }
    }

    static boolean withinStructure(List<com.aicompany.core.model.Component> components, List<String> contexts,
                                   String path) {
        var multi = components.size() > 1;
        if (multi && com.aicompany.core.model.Component.COMMON_ROOT_PATHS.stream()
                .anyMatch(root -> OwnedPaths.covers(root, OwnedPaths.normalize(path)))) {
            return true;
        }
        return components.stream().anyMatch(c -> c.strip(path)
                .map(relative -> c.profile().isWithinStructure(relative, c.contexts(contexts))).orElse(false));
    }

    private static List<String> allowedRoots(List<com.aicompany.core.model.Component> components, List<String> contexts) {
        var roots = new ArrayList<String>();
        for (var c : components) {
            c.profile().allowedRoots(c.contexts(contexts)).stream().map(c::prefix).forEach(roots::add);
        }
        if (components.size() > 1) {
            roots.addAll(com.aicompany.core.model.Component.COMMON_ROOT_PATHS);
        }
        return roots;
    }
```

En `validateDatabase`, reemplazar la condición `if (!"DOTNET_APP".equals(plan.stackProfile()))` por `if (!plan.hasComponent(StackProfile.DOTNET_APP))` y el texto por `"Una base de datos necesita un backend: incluye DOTNET_APP en components."`.

Nota: el texto viejo `"Stacks disponibles: FLUTTER_WEB_APP, DOTNET_APP."` ahora sale de `enabledNow()` (cierra el pendiente m-3 de `docs/FALTANTES.md`; marcarlo `[x]` en la Task 13).

- [ ] **Step 4: Correr los tests**

Run: `cd app && mvn -q test -Dtest='TeamPlanValidatorTest,TeamPlanResolverTest'`
Expected: PASS. Si algún test viejo comparaba el texto exacto de "Stacks disponibles", actualizarlo a `[DOTNET_APP, FLUTTER_WEB_APP]`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/TeamPlanValidator.java app/src/test/java/com/aicompany/core/agent/validation/TeamPlanValidatorTest.java
git commit -m "Apps completas: el validador acepta varios componentes y la raíz común

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Scaffold, proyectos y paquetes por componente

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/ComponentScaffold.java`
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (`expectedProjects` l.117, `existingScaffold` l.802-813, `commitProjectScaffold` l.815-830, `impliedPackages`/`commitRequestedPackages`/`projectsOf` l.455-553, `dependencyRefs` l.443-450, bloque de dependencias l.207-224, `buildWorkPromptBase` l.1006-1015)
- Test: `app/src/test/java/com/aicompany/core/model/ComponentScaffoldTest.java`, `app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java`

**Interfaces:**
- Consumes: `Component`, `ProjectScaffold.generate(profile, contexts, packagesByProject)`, `StackProfile.projectFiles`.
- Produces:
  - `ComponentScaffold.generate(List<Component>, List<String> contexts, Map<String, List<DependencyRef>> packagesByPrefixedProject) → List<GeneratedFile>` (rutas prefijadas).
  - `ComponentScaffold.projectFiles(List<Component>, List<String> contexts) → List<String>` (prefijadas).
  - `DevelopmentTeamStrategy.impliedPackages(List<Component>, List<String>, Map<String,String>)` (reemplaza la firma con `StackProfile`).
  - `DevelopmentTeamStrategy.dependencyRefs(List<Component>, List<PackageRequest>, Function<String,String> readFile)`.

- [ ] **Step 1: Test que falla de `ComponentScaffold`**

```java
package com.aicompany.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentScaffoldTest {

    @Test
    void oneComponentIsTheSameScaffoldAsToday() {
        var root = List.of(new Component(StackProfile.DOTNET_APP, ""));
        assertEquals(ProjectScaffold.generate(StackProfile.DOTNET_APP, List.of("Clientes")),
                ComponentScaffold.generate(root, List.of("Clientes"), Map.of()));
    }

    // Spec 2026-10-02 B1 §3: los .csproj y la .sln van dentro de backend/ con referencias relativas intactas.
    @Test
    void dotnetProjectsArePrefixedAndFlutterHasNone() {
        var components = Component.of(List.of(StackProfile.DOTNET_APP, StackProfile.FLUTTER_WEB_APP));
        var files = ComponentScaffold.generate(components, List.of("Clientes"),
                Map.of("backend/src/Clientes.Infrastructure/Clientes.Infrastructure.csproj",
                        List.of(new DependencyRef("NUGET", "Npgsql", "8.0.5"))));
        var paths = files.stream().map(f -> f.path()).toList();
        assertTrue(paths.contains("backend/src/Clientes.Api/Clientes.Api.csproj"), paths.toString());
        assertTrue(paths.contains("backend/Solution.sln"), paths.toString());
        assertTrue(paths.stream().allMatch(p -> p.startsWith("backend/")), paths.toString());
        var infra = files.stream().filter(f -> f.path().endsWith("Clientes.Infrastructure.csproj")).findFirst().orElseThrow();
        assertTrue(infra.content().contains("Include=\"Npgsql\" Version=\"8.0.5\""), infra.content());
        assertTrue(infra.content().contains("../Clientes.Domain/Clientes.Domain.csproj"), infra.content());
        assertEquals(List.of("backend/src/Clientes.Domain/Clientes.Domain.csproj",
                        "backend/src/Clientes.Application/Clientes.Application.csproj",
                        "backend/src/Clientes.Infrastructure/Clientes.Infrastructure.csproj",
                        "backend/src/Clientes.Api/Clientes.Api.csproj",
                        "backend/tests/Clientes.Tests/Clientes.Tests.csproj"),
                ComponentScaffold.projectFiles(components, List.of("Clientes")));
    }
}
```

- [ ] **Step 2: Correr y ver que falla**

Run: `cd app && mvn -q test -Dtest=ComponentScaffoldTest`
Expected: FAIL (no existe `ComponentScaffold`).

- [ ] **Step 3: Crear `ComponentScaffold`**

```java
package com.aicompany.core.model;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Spec 2026-10-02 B1 §3: ProjectScaffold por componente, con las rutas bajo la carpeta de cada uno. */
public final class ComponentScaffold {

    private ComponentScaffold() {
    }

    public static List<GeneratedFile> generate(List<Component> components, List<String> contexts,
                                               Map<String, List<DependencyRef>> packagesByPrefixedProject) {
        var files = new ArrayList<GeneratedFile>();
        for (var component : components) {
            var packages = new LinkedHashMap<String, List<DependencyRef>>();
            packagesByPrefixedProject.forEach((project, refs) ->
                    component.strip(project).filter(p -> component.owns(project)).ifPresent(p -> packages.put(p, refs)));
            for (var file : ProjectScaffold.generate(component.profile(), component.contexts(contexts), packages)) {
                files.add(new GeneratedFile(component.prefix(file.path()), file.content()));
            }
        }
        return files;
    }

    public static List<String> projectFiles(List<Component> components, List<String> contexts) {
        return components.stream()
                .flatMap(c -> c.profile().projectFiles(c.contexts(contexts)).stream().map(c::prefix))
                .toList();
    }
}
```

- [ ] **Step 4: Usarlo en `DevelopmentTeamStrategy`**

1. Línea 117: `var expectedProjects = ComponentScaffold.projectFiles(plan.resolvedComponents(), plan.contextNames());`
2. `existingScaffold` y `commitProjectScaffold`: reemplazar `plan.profile().map(p -> ProjectScaffold.generate(p, plan.contextNames())).orElse(List.of())` por `ComponentScaffold.generate(plan.resolvedComponents(), plan.contextNames(), Map.of())`. El mensaje del commit: `"Proyectos .csproj generados por Forjai (reglas DDD de " + plan.componentProfiles() + ")"`.
3. `buildWorkPromptBase`: `requiredFiles` pasa a `plan.resolvedComponents().stream().flatMap(c -> c.profile().leaderOwnedPaths().stream().map(c::prefix))`, y `projects` a `ComponentScaffold.projectFiles(plan.resolvedComponents(), plan.contextNames())`.
4. `impliedPackages` por componente (reemplaza la versión actual; el test existente `npgsqlIsAddedToTheProjectsWhoseCodeUsesIt` se adapta en el Step 5):

```java
    static Map<String, List<DependencyRef>> impliedPackages(List<Component> components, List<String> contexts,
                                                            Map<String, String> sources) {
        var implied = new LinkedHashMap<String, List<DependencyRef>>();
        for (var component : components) {
            if (component.profile().ecosystem() != StackProfile.Ecosystem.NUGET) {
                continue;
            }
            for (var project : component.profile().projectFiles(component.contexts(contexts))) {
                var dir = component.prefix(project.substring(0, project.lastIndexOf('/') + 1));
                var uses = sources.entrySet().stream().anyMatch(e -> e.getKey().startsWith(dir)
                        && e.getKey().endsWith(".cs") && e.getValue() != null && USES_NPGSQL.matcher(e.getValue()).find());
                if (uses) {
                    implied.put(component.prefix(project), List.of(NPGSQL));
                }
            }
        }
        return implied;
    }
```

   y en el llamador (l.209-211): `var implied = impliedPackages(plan.resolvedComponents(), plan.contextNames(), plan.hasComponent(StackProfile.DOTNET_APP) || plan.hasComponent(StackProfile.GODOT_DOTNET_GAME) ? csharpSources(missionId, generationHead) : Map.of());` (borrar `nugetProfile`).

5. `projectsOf` y `commitRequestedPackages`:

```java
    static List<String> projectsOf(List<Component> components, List<String> contexts, List<String> ownedPaths) {
        return ComponentScaffold.projectFiles(components, contexts).stream()
                .filter(project -> OwnedPaths.coveredByAny(ownedPaths, project))
                .toList();
    }
```

   En `commitRequestedPackages`: la guarda pasa a `if (plan.resolvedComponents().stream().noneMatch(c -> c.profile().ecosystem() == StackProfile.Ecosystem.NUGET) || (packagesByTask.isEmpty() && implied.isEmpty())) return null;`; `projectsOf(plan.resolvedComponents(), plan.contextNames(), ...)`; y los archivos: `ComponentScaffold.generate(plan.resolvedComponents(), plan.contextNames(), byProject).stream().filter(f -> byProject.containsKey(f.path())).toList()`.

6. Dependencias (l.216-224) y `dependencyRefs`:

```java
    /** Parte 3 + B1: NuGet pedidos si hay un componente .NET; pub de cada pubspec.yaml de un componente Flutter. */
    static List<DependencyRef> dependencyRefs(List<Component> components,
                                              List<DevelopmentResult.PackageRequest> requested,
                                              java.util.function.Function<String, String> readFile) {
        var refs = new ArrayList<DependencyRef>();
        for (var component : components) {
            if (component.profile().ecosystem() == StackProfile.Ecosystem.NUGET) {
                requested.stream().map(p -> new DependencyRef("NUGET", p.name(), p.version()))
                        .filter(r -> !refs.contains(r)).forEach(refs::add);
            } else {
                var pubspec = readFile.apply(component.prefix("pubspec.yaml"));
                if (pubspec != null) {
                    DependencyManifest.pubspec(pubspec).deps().stream().filter(r -> !refs.contains(r)).forEach(refs::add);
                }
            }
        }
        return refs;
    }
```

   y en el flujo: 

```java
        var lastSha = committed.get(committed.size() - 1).commitSha();
        var requestedRefs = dependencyRefs(plan.resolvedComponents(),
                packagesByTask.values().stream().flatMap(List::stream).toList(),
                path -> readOrNull(missionId, lastSha, path));
```

   (borrar `profileForDeps` y `pubspec`).

- [ ] **Step 5: Adaptar los tests existentes de la estrategia**

En `DevelopmentTeamStrategyTest`:
- `flutterDependenciesComeFromThePubspec`:

```java
    @Test
    void flutterDependenciesComeFromThePubspec() {
        var flutter = List.of(new Component(StackProfile.FLUTTER_WEB_APP, ""));
        var refs = DevelopmentTeamStrategy.dependencyRefs(flutter, List.of(),
                path -> "pubspec.yaml".equals(path) ? "dependencies:\n  flutter:\n    sdk: flutter\n  equatable: 2.0.5\n" : null);
        assertEquals(List.of(new DependencyRef("PUB", "equatable", "2.0.5")), refs);
        assertEquals(List.of(new DependencyRef("NUGET", "A", "1.0.0")), DevelopmentTeamStrategy.dependencyRefs(
                List.of(new Component(StackProfile.DOTNET_APP, "")),
                List.of(new DevelopmentResult.PackageRequest("A", "1.0.0")), path -> null));
    }

    // Spec 2026-10-02 B1 §3: en una app completa, NuGet del backend y pub de app/pubspec.yaml en la misma misión.
    @Test
    void fullstackDependenciesComeFromBothEcosystems() {
        var components = Component.of(List.of(StackProfile.DOTNET_APP, StackProfile.FLUTTER_WEB_APP));
        var refs = DevelopmentTeamStrategy.dependencyRefs(components,
                List.of(new DevelopmentResult.PackageRequest("A", "1.0.0")),
                path -> "app/pubspec.yaml".equals(path) ? "dependencies:\n  http: 1.2.2\n" : null);
        assertEquals(List.of(new DependencyRef("NUGET", "A", "1.0.0"), new DependencyRef("PUB", "http", "1.2.2")), refs);
    }
```

- `requestedPackagesGoToEveryProjectOfTheAgentsLayers`: `DevelopmentTeamStrategy.projectsOf(List.of(new Component(StackProfile.DOTNET_APP, "")), List.of("Tareas"), ...)`.
- `npgsqlIsAddedToTheProjectsWhoseCodeUsesIt`: primer argumento `List.of(new Component(StackProfile.DOTNET_APP, ""))`; el chequeo de Flutter pasa a `List.of(new Component(StackProfile.FLUTTER_WEB_APP, ""))`. Agregar:

```java
    @Test
    void npgsqlGoesToTheBackendProjectOfAFullstackApp() {
        var implied = DevelopmentTeamStrategy.impliedPackages(
                Component.of(List.of(StackProfile.DOTNET_APP, StackProfile.FLUTTER_WEB_APP)), List.of("Clientes"),
                Map.of("backend/src/Clientes.Infrastructure/Repo.cs", "using Npgsql;"));
        assertEquals(Map.of("backend/src/Clientes.Infrastructure/Clientes.Infrastructure.csproj",
                List.of(DevelopmentTeamStrategy.NPGSQL)), implied);
    }
```

Importar `com.aicompany.core.model.Component`.

- [ ] **Step 6: Correr los tests**

Run: `cd app && mvn -q test -Dtest='ComponentScaffoldTest,DevelopmentTeamStrategyTest,ProjectScaffoldTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/ComponentScaffold.java app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java app/src/test/java/com/aicompany/core/model/ComponentScaffoldTest.java app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java
git commit -m "Apps completas: scaffold, proyectos y dependencias por componente

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Chequeos deterministas por componente y portabilidad

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/PortabilityChecker.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/ComponentStructureChecker.java`
- Modify: `app/src/main/java/com/aicompany/core/service/StaticWorkspaceValidator.java`
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (las 4 llamadas a `staticValidator.validate`)
- Test: `app/src/test/java/com/aicompany/core/agent/validation/PortabilityCheckerTest.java`, `ComponentStructureCheckerTest.java`; ajustar `DevelopmentTeamStrategyTest`

**Interfaces:**
- Consumes: `ProfileStructureChecker.check(profile, contexts, files)`, `DddLayerChecker.check/toCheck`, `Component`.
- Produces:
  - `PortabilityChecker.check(Map<String,String> dartByPath) → StaticCheck` (nombre `PORTABILITY`).
  - `ComponentStructureChecker.check(List<Component>, List<String> contexts, List<String> files, Map<String,String> contents, List<String> forjaiFiles) → List<StaticCheck>`: devuelve `ENTRY_FILES`, `PROFILE_STRUCTURE`, `DDD_LAYERS` y, si hay Flutter, `PORTABILITY` (uno de cada, combinando componentes).
  - `StaticWorkspaceValidator.validate(String missionId, List<CommittedWork> work, List<Component> components, List<String> contexts, List<String> allowedPaths)`; la firma vieja con `StackProfile` delega con `profile == null ? List.of() : List.of(new Component(profile, ""))`.

- [ ] **Step 1: Tests que fallan**

```java
package com.aicompany.core.agent.validation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PortabilityCheckerTest {

    // Spec 2026-10-02 B1 §1: el mismo código sirve para web, Android e iOS solo sin librerías solo-web.
    @Test
    void webOnlyImportsAreRejectedInAnyLayer() {
        var check = PortabilityChecker.check(Map.of(
                "lib/clientes/presentation/pantalla.dart", "import 'dart:html';\nimport 'package:flutter/material.dart';",
                "lib/main.dart", "import 'package:web/web.dart' as web;",
                "lib/clientes/domain/cliente.dart", "class Cliente {}"));
        assertFalse(check.passed());
        assertEquals("PORTABILITY", check.check());
        assertTrue(check.detail().contains("lib/clientes/presentation/pantalla.dart → dart:html"), check.detail());
        assertTrue(check.detail().contains("lib/main.dart → package:web/web.dart"), check.detail());
    }

    @Test
    void portableCodePasses() {
        assertTrue(PortabilityChecker.check(Map.of("lib/main.dart",
                "import 'package:flutter/material.dart';\nimport 'package:http/http.dart' as http;")).passed());
    }
}
```

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.model.Component;
import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.StaticCheck;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentStructureCheckerTest {

    private static final List<Component> FULLSTACK =
            Component.of(List.of(StackProfile.DOTNET_APP, StackProfile.FLUTTER_WEB_APP));

    private static final List<String> FILES = List.of(
            "backend/Solution.sln",
            "backend/src/Clientes.Domain/Clientes.Domain.csproj", "backend/src/Clientes.Domain/Cliente.cs",
            "backend/src/Clientes.Application/Clientes.Application.csproj",
            "backend/src/Clientes.Infrastructure/Clientes.Infrastructure.csproj",
            "backend/src/Clientes.Api/Clientes.Api.csproj", "backend/src/Clientes.Api/Program.cs",
            "backend/tests/Clientes.Tests/Clientes.Tests.csproj",
            "app/pubspec.yaml", "app/lib/main.dart", "app/lib/clientes/domain/cliente.dart",
            "app/lib/api/api_routes.dart",
            "db/postgres/migrations/V1__clientes.sql", ".env.example");

    private static StaticCheck named(List<StaticCheck> checks, String name) {
        return checks.stream().filter(c -> c.check().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void fullstackRepositoryPassesStructureChecks() {
        var checks = ComponentStructureChecker.check(FULLSTACK, List.of("Clientes"), FILES, Map.of(
                "backend/src/Clientes.Domain/Cliente.cs", "namespace Clientes.Domain; public class Cliente {}",
                "app/pubspec.yaml", "name: app\n",
                "app/lib/main.dart", "import 'package:flutter/material.dart';",
                "app/lib/clientes/domain/cliente.dart", "class Cliente {}"),
                List.of("app/lib/api/api_routes.dart"));
        assertTrue(named(checks, "ENTRY_FILES").passed(), named(checks, "ENTRY_FILES").detail());
        assertTrue(named(checks, "PROFILE_STRUCTURE").passed(), named(checks, "PROFILE_STRUCTURE").detail());
        assertTrue(named(checks, "DDD_LAYERS").passed(), named(checks, "DDD_LAYERS").detail());
        assertTrue(named(checks, "PORTABILITY").passed());
    }

    @Test
    void aFileOutsideEveryComponentFailsWithItsRealPath() {
        var files = new java.util.ArrayList<>(FILES);
        files.add("frontend/index.html");
        var structure = named(ComponentStructureChecker.check(FULLSTACK, List.of("Clientes"), files, Map.of(),
                List.of("app/lib/api/api_routes.dart")), "PROFILE_STRUCTURE");
        assertFalse(structure.passed());
        assertTrue(structure.paths().contains("frontend/index.html"), structure.paths().toString());
    }

    @Test
    void domainViolationReportsThePrefixedFile() {
        var checks = ComponentStructureChecker.check(FULLSTACK, List.of("Clientes"), FILES, Map.of(
                "backend/src/Clientes.Domain/Cliente.cs", "using Microsoft.AspNetCore.Http;\nnamespace Clientes.Domain;"),
                List.of());
        var ddd = named(checks, "DDD_LAYERS");
        assertFalse(ddd.passed());
        assertTrue(ddd.paths().contains("backend/src/Clientes.Domain/Cliente.cs"), ddd.paths().toString());
    }

    @Test
    void oneComponentGivesTheSameChecksAsToday() {
        var files = List.of("pubspec.yaml", "lib/main.dart", "lib/saludo/domain/saludo.dart");
        var root = List.of(new Component(StackProfile.FLUTTER_WEB_APP, ""));
        var checks = ComponentStructureChecker.check(root, List.of("saludo"), files,
                Map.of("lib/main.dart", "void main() {}"), List.of());
        assertEquals(ProfileStructureChecker.check(StackProfile.FLUTTER_WEB_APP, List.of("saludo"), files),
                checks.subList(0, 2));
    }
}
```

(Si `StaticCheck` no expone `paths()`/`detail()`/`check()`/`passed()` con esos nombres, usar los accesores reales del record: hoy se construye con `StaticCheck.pass(check, detail, sha, paths)`.)

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn -q test -Dtest='PortabilityCheckerTest,ComponentStructureCheckerTest'`
Expected: FAIL (clases inexistentes).

- [ ] **Step 3: `PortabilityChecker`**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.model.StaticCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Spec 2026-10-02 B1 §1: un componente Flutter sirve igual para web, Android e iOS solo si ninguna capa importa
 * librerías que existen únicamente en el navegador. Compilar en web no lo detecta: lo detecta Java.
 */
public final class PortabilityChecker {

    static final List<String> WEB_ONLY = List.of("dart:html", "dart:js", "dart:js_util", "dart:js_interop", "package:web/");

    private static final Pattern IMPORT = Pattern.compile("^\\s*(?:import|export)\\s+['\"]([^'\"]+)['\"]", Pattern.MULTILINE);

    private PortabilityChecker() {
    }

    public static StaticCheck check(Map<String, String> dartByPath) {
        var found = new ArrayList<String>();
        var files = new ArrayList<String>();
        dartByPath.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var matcher = IMPORT.matcher(entry.getValue() == null ? "" : entry.getValue());
            while (matcher.find()) {
                var uri = matcher.group(1);
                if (WEB_ONLY.stream().anyMatch(w -> uri.equals(w) || (w.endsWith("/") && uri.startsWith(w)))) {
                    found.add(entry.getKey() + " → " + uri);
                    if (!files.contains(entry.getKey())) {
                        files.add(entry.getKey());
                    }
                }
            }
        });
        return found.isEmpty()
                ? StaticCheck.pass("PORTABILITY", "Sin librerías solo-web (" + dartByPath.size() + " archivo(s) Dart)",
                        null, List.of())
                : StaticCheck.fail("PORTABILITY", "Librerías solo-web (rompen Android/iOS): " + String.join("; ", found),
                        null, files);
    }
}
```

- [ ] **Step 4: `ComponentStructureChecker`**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.model.Component;
import com.aicompany.core.model.StackProfile;
import com.aicompany.core.model.StaticCheck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spec 2026-10-02 B1 §3: los chequeos de estructura y capas de cada componente, con las piezas de siempre sobre rutas
 * relativas al componente y los resultados con la ruta real. {@code forjaiFiles}: archivos que escribe Java (p. ej.
 * api_routes.dart), fuera de la estructura de los agentes pero permitidos.
 */
public final class ComponentStructureChecker {

    private ComponentStructureChecker() {
    }

    public static List<StaticCheck> check(List<Component> components, List<String> contexts, List<String> files,
                                          Map<String, String> contents, List<String> forjaiFiles) {

        var multi = components.size() > 1;
        var entry = new ArrayList<StaticCheck>();
        var structure = new ArrayList<StaticCheck>();
        var ddd = new ArrayList<StaticCheck>();
        var portability = new ArrayList<StaticCheck>();
        var outsideAll = new ArrayList<String>();

        for (var file : files) {
            var common = multi && Component.COMMON_ROOT_PATHS.stream()
                    .anyMatch(root -> OwnedPaths.covers(root, OwnedPaths.normalize(file)));
            if (!common && !forjaiFiles.contains(file) && components.stream().noneMatch(c -> c.owns(file))) {
                outsideAll.add(file);
            }
        }

        for (var component : components) {
            var ctx = component.contexts(contexts);
            var own = files.stream().filter(component::owns).filter(f -> !forjaiFiles.contains(f))
                    .map(f -> component.strip(f).orElseThrow()).toList();
            if (!multi) {
                // Un componente: exactamente los mismos chequeos que antes de B1.
                own = files.stream().filter(f -> !forjaiFiles.contains(f)).toList();
            }
            var checks = ProfileStructureChecker.check(component.profile(), ctx, own);
            entry.add(prefixed(checks.get(0), component));
            structure.add(prefixed(checks.get(1), component));

            var relativeContents = new LinkedHashMap<String, String>();
            contents.forEach((path, content) -> component.strip(path).filter(p -> component.owns(path))
                    .ifPresent(p -> relativeContents.put(p, content)));
            var analyzed = (int) relativeContents.keySet().stream()
                    .filter(p -> p.endsWith(component.profile().sourceExtension())).count();
            ddd.add(prefixed(DddLayerChecker.toCheck(
                    DddLayerChecker.check(component.profile(), ctx, relativeContents), analyzed), component));

            if (component.profile() == StackProfile.FLUTTER_WEB_APP) {
                var dart = new LinkedHashMap<String, String>();
                relativeContents.forEach((p, c) -> {
                    if (p.endsWith(".dart") && !forjaiFiles.contains(component.prefix(p))) {
                        dart.put(p, c);
                    }
                });
                portability.add(prefixed(PortabilityChecker.check(dart), component));
            }
        }

        if (!outsideAll.isEmpty()) {
            structure.add(StaticCheck.fail("PROFILE_STRUCTURE", "Archivos fuera de " + components.stream()
                    .map(c -> c.isRoot() ? c.profile().name() : c.dir() + "/").toList()
                    + " y de la raíz común " + Component.COMMON_ROOT_PATHS + ": " + outsideAll, null, outsideAll));
        }

        var result = new ArrayList<StaticCheck>();
        result.add(merge("ENTRY_FILES", entry));
        result.add(merge("PROFILE_STRUCTURE", structure));
        result.add(merge("DDD_LAYERS", ddd));
        if (!portability.isEmpty()) {
            result.add(merge("PORTABILITY", portability));
        }
        return result;
    }

    /** Las rutas del resultado, con el prefijo del componente (sin cambios en la raíz). */
    private static StaticCheck prefixed(StaticCheck check, Component component) {
        if (component.isRoot()) {
            return check;
        }
        var paths = check.paths() == null ? List.<String>of() : check.paths().stream().map(component::prefix).toList();
        var detail = component.dir() + "/: " + check.detail();
        return check.passed()
                ? StaticCheck.pass(check.check(), detail, check.sha(), paths)
                : StaticCheck.fail(check.check(), detail, check.sha(), paths);
    }

    /** Un solo chequeo por nombre: pasa si pasan todos; si no, junta los detalles y rutas de los que fallaron. */
    private static StaticCheck merge(String name, List<StaticCheck> checks) {
        if (checks.size() == 1) {
            return checks.get(0);
        }
        var failed = checks.stream().filter(c -> !c.passed()).toList();
        if (failed.isEmpty()) {
            return StaticCheck.pass(name, String.join(" | ", checks.stream().map(StaticCheck::detail).toList()),
                    null, List.of());
        }
        var paths = failed.stream().flatMap(c -> c.paths() == null ? java.util.stream.Stream.<String>empty()
                : c.paths().stream()).distinct().toList();
        return StaticCheck.fail(name, String.join(" | ", failed.stream().map(StaticCheck::detail).toList()), null, paths);
    }
}
```

Verificar los accesores reales de `StaticCheck` (`check()`, `status()`, `detail()`, `sha()`, `paths()`, `passed()`) con `grep -n "record StaticCheck" -A12 app/src/main/java/com/aicompany/core/model/StaticCheck.java` y ajustar los nombres si difieren.

- [ ] **Step 5: `StaticWorkspaceValidator`**

Agregar la sobrecarga nueva y hacer que la vieja delegue:

```java
    public List<StaticCheck> validate(
            String missionId, List<CommittedWork> work, StackProfile profile, List<String> contexts,
            List<String> allowedPaths) {
        return validate(missionId, work, profile == null ? List.of() : List.of(new Component(profile, "")),
                contexts, allowedPaths, List.of());
    }

    /** Spec 2026-10-02 B1 §3: chequeos por componente. forjaiFiles = archivos que escribe Java (api_routes.dart). */
    public List<StaticCheck> validate(
            String missionId, List<CommittedWork> work, List<Component> components, List<String> contexts,
            List<String> allowedPaths, List<String> forjaiFiles) {
        // cuerpo actual de validate(...), cambiando la llamada final:
        //   validateHead(dir, components, contexts, allowedPaths, forjaiFiles, checks);
    }
```

y en `validateHead`, reemplazar desde `if (profile == null)` hasta el final por:

```java
        if (components.isEmpty()) {
            checks.add(StaticCheck.fail("STACK_PROFILE", "El plan no declaró un stackProfile del catálogo.",
                    null, List.of()));
            return;
        }

        var contents = new LinkedHashMap<String, String>();
        try {
            for (var path : paths) {
                var source = components.stream().anyMatch(c -> c.owns(path)
                        && (path.endsWith(c.profile().sourceExtension()) || path.equals(c.prefix("pubspec.yaml"))));
                if (source) {
                    contents.put(path, git.run(dir, "show", "HEAD:" + path));
                }
            }
        } catch (IOException ex) {
            checks.add(StaticCheck.fail("DDD_LAYERS", "No se pudo leer el código para el chequeo DDD: "
                    + ex.getMessage(), null, List.of()));
            return;
        }

        checks.addAll(ComponentStructureChecker.check(components, contexts, paths, contents, forjaiFiles));
```

(`validateHead` recibe `List<Component> components` y `List<String> forjaiFiles` en lugar de `StackProfile profile`.)

- [ ] **Step 6: Llamadas desde `DevelopmentTeamStrategy`**

Reemplazar `var profile = plan.profile().orElse(null);` (l.232) por `var components = plan.resolvedComponents();` y `var forjaiFiles = new ArrayList<String>();` (la Task 9 le agrega `api_routes.dart`). Las 4 llamadas pasan a `staticValidator.validate(missionId, committed, components, plan.contextNames(), allowedPaths, forjaiFiles)`. La guarda `|| profile == null` (l.246) pasa a `|| components.isEmpty()`. (La llamada al sandbox y a `commitMissingUsings` que usan `profile` las cambia la Task 10; mientras tanto usar `components.get(0).profile()` en esas dos líneas para que compile.)

En `DevelopmentTeamStrategyTest`, cambiar los stubs `validator.validate(eq("M-1"), anyList(), eq(StackProfile.GODOT_DOTNET_GAME), eq(List.of("Combate")), anyList())` por `validator.validate(eq("M-1"), anyList(), eq(List.of(new Component(StackProfile.GODOT_DOTNET_GAME, ""))), eq(List.of("Combate")), anyList(), anyList())` (las dos ocurrencias: `grep -n "eq(StackProfile\." app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java`). Hacer lo mismo con cualquier otro stub o `verify` de `validator.validate` en ese archivo.

- [ ] **Step 7: Correr los tests**

Run: `cd app && mvn -q test -Dtest='PortabilityCheckerTest,ComponentStructureCheckerTest,StaticWorkspaceValidatorTest,DevelopmentTeamStrategyTest,ProfileStructureCheckerTest'`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/PortabilityChecker.java app/src/main/java/com/aicompany/core/agent/validation/ComponentStructureChecker.java app/src/main/java/com/aicompany/core/service/StaticWorkspaceValidator.java app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java app/src/test/java/com/aicompany/core/agent/validation/PortabilityCheckerTest.java app/src/test/java/com/aicompany/core/agent/validation/ComponentStructureCheckerTest.java app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java
git commit -m "Apps completas: chequeos de estructura, capas y portabilidad por componente

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: `HttpRouteExtractor`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/HttpRoute.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/HttpRouteExtractor.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/HttpRouteExtractorTest.java`

**Interfaces:**
- Produces: `record HttpRoute(String method, String template)`; `HttpRouteExtractor.extract(Map<String,String> csharpByPath) → List<HttpRoute>` (sin duplicados, ordenadas por `template` y luego `method`).

- [ ] **Step 1: Test que falla**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.model.HttpRoute;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HttpRouteExtractorTest {

    @Test
    void readsTheFiveMinimalApiVerbs() {
        var routes = HttpRouteExtractor.extract(Map.of("src/X.Api/Program.cs", """
                var app = builder.Build();
                app.MapGet("/health", () => Results.Ok());
                app.MapPost("/clientes", (Crear c) => Results.Created());
                app.MapPut("/clientes/{id}", (Guid id) => Results.Ok());
                app.MapPatch("clientes/{id}/estado", (Guid id) => Results.Ok());
                app.MapDelete("/clientes/{id:guid}", (Guid id) => Results.NoContent());
                """));
        assertEquals(List.of(new HttpRoute("POST", "/clientes"), new HttpRoute("DELETE", "/clientes/{id}"),
                new HttpRoute("PUT", "/clientes/{id}"), new HttpRoute("PATCH", "/clientes/{id}/estado"),
                new HttpRoute("GET", "/health")), routes);
    }

    // Review Focus 3: grupos en variables, encadenados y definidos en otro archivo.
    @Test
    void resolvesGroupsInVariablesChainsAndOtherFiles() {
        var routes = HttpRouteExtractor.extract(Map.of(
                "src/X.Api/Program.cs", """
                        var api = app.MapGroup("/api");
                        RouteGroupBuilder v1 = api.MapGroup("v1/");
                        v1.MapGet("/clientes/{id:guid}", (Guid id) => Results.Ok());
                        app.MapGroup("/admin").MapGroup("/reportes").MapGet("", () => Results.Ok());
                        """,
                "src/X.Api/Endpoints.cs", """
                        public static class Endpoints {
                            public static void Map(IEndpointRouteBuilder routes) {
                                routes.MapPost("/oportunidades", () => Results.Ok());
                                v1.MapDelete("/clientes/{id?}", () => Results.Ok());
                            }
                        }
                        """));
        assertTrue(routes.contains(new HttpRoute("GET", "/api/v1/clientes/{id}")), routes.toString());
        assertTrue(routes.contains(new HttpRoute("GET", "/admin/reportes")), routes.toString());
        assertTrue(routes.contains(new HttpRoute("POST", "/oportunidades")), routes.toString());
        assertTrue(routes.contains(new HttpRoute("DELETE", "/api/v1/clientes/{id}")), routes.toString());
    }

    @Test
    void noLiteralRoutesMeansAnEmptyList() {
        assertEquals(List.of(), HttpRouteExtractor.extract(Map.of("src/X.Api/Program.cs",
                "app.MapControllers(); var ruta = \"/x\"; app.MapGet(ruta, () => 1);")));
    }
}
```

- [ ] **Step 2: Correr y ver que falla**

Run: `cd app && mvn -q test -Dtest=HttpRouteExtractorTest`
Expected: FAIL (clases inexistentes).

- [ ] **Step 3: Implementar**

```java
package com.aicompany.core.model;

/** Spec 2026-10-02 B1 §2: una ruta real del backend (método HTTP + plantilla normalizada). */
public record HttpRoute(String method, String template) {
}
```

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.model.HttpRoute;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spec 2026-10-02 B1 §2: rutas minimal API reales del backend, leídas por Java (nunca declaradas por un modelo).
 * Solo rutas literales: MapGet/MapPost/MapPut/MapPatch/MapDelete("…"), con prefijos de MapGroup("…") encadenados o
 * guardados en variables (también en otros archivos). Una variable sin MapGroup conocido cuenta como raíz.
 */
public final class HttpRouteExtractor {

    private static final String GROUPS = "((?:\\s*\\.\\s*MapGroup\\(\\s*\"[^\"]*\"\\s*\\))*)";

    private static final Pattern GROUP_ASSIGNMENT = Pattern.compile(
            "(?:var|RouteGroupBuilder)\\s+([A-Za-z_]\\w*)\\s*=\\s*([A-Za-z_]\\w*)" + GROUPS.replace("*)", "+)") + "\\s*;");

    private static final Pattern MAP_CALL = Pattern.compile(
            "([A-Za-z_]\\w*)" + GROUPS + "\\s*\\.\\s*Map(Get|Post|Put|Patch|Delete)\\(\\s*\"([^\"]*)\"");

    private static final Pattern GROUP_LITERAL = Pattern.compile("MapGroup\\(\\s*\"([^\"]*)\"\\s*\\)");

    private HttpRouteExtractor() {
    }

    public static List<HttpRoute> extract(Map<String, String> csharpByPath) {

        var sources = csharpByPath.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue).filter(Objects::nonNull).toList();

        // Variable → (variable base, prefijos propios). Se resuelve después, porque puede venir de otro archivo.
        var groups = new HashMap<String, String[]>();
        for (var source : sources) {
            var matcher = GROUP_ASSIGNMENT.matcher(source);
            while (matcher.find()) {
                groups.put(matcher.group(1), new String[]{matcher.group(2), literalGroups(matcher.group(3))});
            }
        }

        var routes = new LinkedHashSet<HttpRoute>();
        for (var source : sources) {
            var matcher = MAP_CALL.matcher(source);
            while (matcher.find()) {
                var prefix = prefixOf(matcher.group(1), groups, 0) + literalGroups(matcher.group(2));
                routes.add(new HttpRoute(matcher.group(3).toUpperCase(java.util.Locale.ROOT),
                        normalize(prefix + "/" + matcher.group(4))));
            }
        }

        var sorted = new ArrayList<>(routes);
        sorted.sort(Comparator.comparing(HttpRoute::template).thenComparing(HttpRoute::method));
        return sorted;
    }

    private static String prefixOf(String variable, Map<String, String[]> groups, int depth) {
        var group = groups.get(variable);
        if (group == null || depth > 10) {
            return "";
        }
        return prefixOf(group[0], groups, depth + 1) + group[1];
    }

    private static String literalGroups(String chain) {
        if (chain == null || chain.isBlank()) {
            return "";
        }
        var out = new StringBuilder();
        Matcher matcher = GROUP_LITERAL.matcher(chain);
        while (matcher.find()) {
            out.append('/').append(matcher.group(1));
        }
        return out.toString();
    }

    /** "/" inicial, sin "//" ni "/" final, y {id:guid} / {id?} / {*resto} → {id} / {resto}. */
    static String normalize(String template) {
        var path = template.replaceAll("\\{\\*?([A-Za-z_]\\w*)[^}]*}", "{$1}").replaceAll("/+", "/");
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }
}
```

Nota sobre `GROUP_ASSIGNMENT`: `GROUPS.replace("*)", "+)")` exige al menos un `MapGroup` en la asignación. Si el `replace` resulta frágil al implementarlo, escribir la constante explícita `"((?:\\s*\\.\\s*MapGroup\\(\\s*\"[^\"]*\"\\s*\\))+)"`.

- [ ] **Step 4: Correr los tests**

Run: `cd app && mvn -q test -Dtest=HttpRouteExtractorTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/HttpRoute.java app/src/main/java/com/aicompany/core/agent/validation/HttpRouteExtractor.java app/src/test/java/com/aicompany/core/agent/validation/HttpRouteExtractorTest.java
git commit -m "Apps completas: Java lee las rutas minimal API del backend

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: `ApiRoutesGenerator` y `ApiContractGate`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/ApiRoutesGenerator.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/ApiContractGate.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/ApiRoutesGeneratorTest.java`, `ApiContractGateTest.java`

**Interfaces:**
- Consumes: `HttpRoute`.
- Produces:
  - `ApiRoutesGenerator.PATH = "lib/api/api_routes.dart"` (relativo al componente Flutter); `ApiRoutesGenerator.generate(List<HttpRoute>) → String`.
  - `ApiContractGate.check(List<GeneratedFile> files, List<HttpRoute> routes) → List<String>` (errores; vacío = pasa).

- [ ] **Step 1: Tests que fallan**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.model.HttpRoute;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApiRoutesGeneratorTest {

    @Test
    void oneMemberPerRouteWithStableNamesAndParameters() {
        var dart = ApiRoutesGenerator.generate(List.of(
                new HttpRoute("POST", "/clientes"), new HttpRoute("GET", "/clientes"),
                new HttpRoute("GET", "/clientes/{id}"), new HttpRoute("DELETE", "/clientes/{id}"),
                new HttpRoute("PUT", "/clientes/{clienteId}/oportunidades/{id}"),
                new HttpRoute("GET", "/health")));
        assertTrue(dart.contains("static const ApiRoute crearClientes = ApiRoute('POST', '/clientes');"), dart);
        assertTrue(dart.contains("static const ApiRoute listarClientes = ApiRoute('GET', '/clientes');"), dart);
        assertTrue(dart.contains("static ApiRoute obtenerClientes(String id) => "
                + "ApiRoute('GET', '/clientes/${Uri.encodeComponent(id)}');"), dart);
        assertTrue(dart.contains("static ApiRoute eliminarClientes(String id) =>"), dart);
        assertTrue(dart.contains("static ApiRoute actualizarClientesOportunidades(String clienteId, String id) =>"), dart);
        assertTrue(dart.contains("static const ApiRoute listarHealth = ApiRoute('GET', '/health');"), dart);
        assertTrue(dart.startsWith("// Generado por Forjai"), dart);
    }

    @Test
    void collidingNamesGetANumericSuffixInRouteOrder() {
        var dart = ApiRoutesGenerator.generate(List.of(
                new HttpRoute("GET", "/clientes-vip"), new HttpRoute("GET", "/clientes_vip")));
        assertTrue(dart.contains("listarClientesVip ="), dart);
        assertTrue(dart.contains("listarClientesVip2 ="), dart);
    }

    @Test
    void sameRoutesAlwaysGiveTheSameFile() {
        var routes = List.of(new HttpRoute("GET", "/a"), new HttpRoute("POST", "/a"));
        assertEquals(ApiRoutesGenerator.generate(routes), ApiRoutesGenerator.generate(List.copyOf(routes)));
    }
}
```

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.model.HttpRoute;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApiContractGateTest {

    private static final List<HttpRoute> ROUTES = List.of(new HttpRoute("GET", "/clientes/{id}"),
            new HttpRoute("POST", "/oportunidades"));

    @Test
    void handWrittenBackendRouteIsRejected() {
        var errors = ApiContractGate.check(List.of(new GeneratedFile("app/lib/clientes/infrastructure/repo.dart",
                "final r = await http.get(Uri.parse('$base/clientes/$id'));\nfinal s = \"/oportunidades\";")), ROUTES);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("app/lib/clientes/infrastructure/repo.dart"), errors.get(0));
        assertTrue(errors.get(0).contains("/oportunidades"), errors.get(0));
        assertTrue(errors.get(0).contains("ApiRoutes"), errors.get(0));
    }

    @Test
    void localhostIsRejected() {
        var errors = ApiContractGate.check(List.of(new GeneratedFile("app/lib/main.dart",
                "const base = 'http://localhost:5080';")), ROUTES);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("API_BASE_URL"), errors.get(0));
    }

    @Test
    void usingApiRoutesAndAssetsPasses() {
        assertEquals(List.of(), ApiContractGate.check(List.of(
                new GeneratedFile("app/lib/main.dart", "final r = ApiRoutes.obtenerClientes(id); Image.asset('/img/logo.png');"),
                new GeneratedFile("app/lib/api/api_routes.dart", "ApiRoute('GET', '/clientes/$id')"),
                new GeneratedFile("app/pubspec.yaml", "name: app")), ROUTES));
    }
}
```

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn -q test -Dtest='ApiRoutesGeneratorTest,ApiContractGateTest'`
Expected: FAIL (clases inexistentes).

- [ ] **Step 3: `ApiRoutesGenerator`**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.model.HttpRoute;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Spec 2026-10-02 B1 §2: el contrato de rutas que usa la app Flutter, escrito por Java desde las rutas reales del
 * backend. Si el backend cambia una ruta, este archivo cambia y la app deja de compilar: el compilador hace cumplir
 * el contrato. Determinista: mismas rutas → mismo archivo.
 */
public final class ApiRoutesGenerator {

    public static final String PATH = "lib/api/api_routes.dart";

    private static final Pattern PARAM = Pattern.compile("\\{([A-Za-z_]\\w*)}");
    private static final Set<String> DART_RESERVED = Set.of("class", "default", "new", "this", "is", "in", "var",
            "final", "const", "null", "true", "false", "void", "return", "switch", "case", "do", "if", "else", "for",
            "while", "with", "super", "try", "catch", "throw", "enum", "extends", "assert", "break", "continue");

    private ApiRoutesGenerator() {
    }

    public static String generate(List<HttpRoute> routes) {
        var out = new StringBuilder("""
                // Generado por Forjai desde las rutas reales de backend/. No editar: se regenera en cada cambio de la API.
                class ApiRoute {
                  final String method;
                  final String path;
                  const ApiRoute(this.method, this.path);
                }

                class ApiRoutes {
                """);
        var used = new HashSet<String>();
        for (var route : routes) {
            var name = unique(baseName(route), used);
            var params = new ArrayList<String>();
            var matcher = PARAM.matcher(route.template());
            while (matcher.find()) {
                params.add(unique(dartIdentifier(matcher.group(1)), new HashSet<>(params)));
            }
            if (params.isEmpty()) {
                out.append("  static const ApiRoute ").append(name).append(" = ApiRoute('").append(route.method())
                        .append("', '").append(route.template()).append("');\n");
            } else {
                var path = route.template();
                for (var param : params) {
                    path = path.replaceFirst("\\{[A-Za-z_]\\w*}",
                            java.util.regex.Matcher.quoteReplacement("${Uri.encodeComponent(" + param + ")}"));
                }
                out.append("  static ApiRoute ").append(name).append("(")
                        .append(params.stream().map(p -> "String " + p).collect(Collectors.joining(", ")))
                        .append(") => ApiRoute('").append(route.method()).append("', '").append(path).append("');\n");
            }
        }
        return out.append("}\n").toString();
    }

    private static String baseName(HttpRoute route) {
        var endsWithParam = route.template().endsWith("}");
        var verb = switch (route.method()) {
            case "GET" -> endsWithParam ? "obtener" : "listar";
            case "POST" -> "crear";
            case "PUT" -> "actualizar";
            case "PATCH" -> "modificar";
            case "DELETE" -> "eliminar";
            default -> route.method().toLowerCase(Locale.ROOT);
        };
        var literal = new StringBuilder();
        for (var segment : route.template().split("/")) {
            if (segment.isEmpty() || segment.startsWith("{")) {
                continue;
            }
            for (var word : segment.split("[^A-Za-z0-9]+")) {
                if (!word.isEmpty()) {
                    literal.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
                }
            }
        }
        return verb + (literal.isEmpty() ? "Raiz" : literal.toString());
    }

    private static String dartIdentifier(String name) {
        var id = name.replaceAll("[^A-Za-z0-9_]", "_");
        if (Character.isDigit(id.charAt(0))) {
            id = "p" + id;
        }
        return DART_RESERVED.contains(id) ? id + "Value" : id;
    }

    private static String unique(String name, Set<String> used) {
        var candidate = name;
        for (int i = 2; used.contains(candidate); i++) {
            candidate = name + i;
        }
        used.add(candidate);
        return candidate;
    }
}
```

- [ ] **Step 4: `ApiContractGate`**

```java
package com.aicompany.core.agent.validation;

import com.aicompany.core.agent.model.DevelopmentResult.GeneratedFile;
import com.aicompany.core.model.HttpRoute;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Spec 2026-10-02 B1 §2: la app llama a la API solo a través de ApiRoutes (generado por Java) y con la URL base de
 * API_BASE_URL. Rechaza, con reintento, un string que empiece por una ruta del backend o que apunte a localhost.
 */
public final class ApiContractGate {

    private static final Pattern STRING = Pattern.compile("(['\"])(.*?)\\1");
    private static final Pattern LOCALHOST = Pattern.compile("https?://(localhost|127\\.0\\.0\\.1)");

    private ApiContractGate() {
    }

    public static List<String> check(List<GeneratedFile> files, List<HttpRoute> routes) {

        var firstSegments = new LinkedHashSet<String>();
        for (var route : routes) {
            var segments = route.template().split("/");
            if (segments.length > 1 && !segments[1].isEmpty() && !segments[1].startsWith("{")) {
                firstSegments.add(segments[1].toLowerCase(Locale.ROOT));
            }
        }

        var errors = new ArrayList<String>();
        for (var file : files == null ? List.<GeneratedFile>of() : files) {
            if (file == null || file.path() == null || !file.path().endsWith(".dart")
                    || file.path().endsWith(ApiRoutesGenerator.PATH)) {
                continue;
            }
            var content = Objects.requireNonNullElse(file.content(), "");
            if (LOCALHOST.matcher(content).find()) {
                errors.add(file.path() + ": no escribas la URL de la API (localhost); usa "
                        + "const String.fromEnvironment('API_BASE_URL') como base y ApiRoutes para las rutas.");
                continue;
            }
            var found = new LinkedHashSet<String>();
            var matcher = STRING.matcher(content);
            while (matcher.find()) {
                var literal = matcher.group(2).replaceAll("^\\$\\{?\\w+}?", "");
                if (literal.startsWith("/") && literal.length() > 1) {
                    var first = literal.substring(1).split("[/?$]")[0].toLowerCase(Locale.ROOT);
                    if (firstSegments.contains(first)) {
                        found.add(literal);
                    }
                }
            }
            if (!found.isEmpty()) {
                errors.add(file.path() + ": rutas de la API escritas a mano " + found + ". Usa ApiRoutes (de "
                        + "lib/api/api_routes.dart, generado por Forjai desde el backend), p. ej. "
                        + "ApiRoutes.obtenerClientes(id).path, nunca el texto de la ruta.");
            }
        }
        return errors;
    }
}
```

- [ ] **Step 5: Correr los tests**

Run: `cd app && mvn -q test -Dtest='ApiRoutesGeneratorTest,ApiContractGateTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/agent/validation/ApiRoutesGenerator.java app/src/main/java/com/aicompany/core/agent/validation/ApiContractGate.java app/src/test/java/com/aicompany/core/agent/validation/ApiRoutesGeneratorTest.java app/src/test/java/com/aicompany/core/agent/validation/ApiContractGateTest.java
git commit -m "Apps completas: contrato de rutas en Dart y gate de rutas a mano

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: `sandbox-runner` con `componentDir`

**Files:**
- Modify: `sandbox-runner/src/main/java/com/forjai/sandbox/JobController.java`
- Modify: `sandbox-runner/src/main/java/com/forjai/sandbox/VerifyJobRunner.java`
- Test: `sandbox-runner/src/test/java/com/forjai/sandbox/JobControllerTest.java`, `VerifyJobRunnerTest.java`

**Interfaces:**
- Produces: `JobRequest` gana `String componentDir` (después de `stackProfile`); `VerifyJobRunner.verify(String missionId, String commitSha, ExecutionProfile profile, String componentDir)`; la de 3 argumentos delega con `""`. `VerifyJobRunner.COMPONENT_DIRS = Set.of("", "backend", "app", "game-client")`.

- [ ] **Step 1: Tests que fallan**

En `VerifyJobRunnerTest` (que ya usa un `ProcessExecutor` falso; seguir su patrón para capturar los comandos):

```java
    // Spec 2026-10-02 B1 §3: solo se extrae la carpeta del componente pedido.
    @Test
    void componentDirArchivesOnlyThatTree() {
        // armar runner + executor falso como en los tests existentes; commit válido de 40 hex
        runner.verify("M-1", "a".repeat(40), ExecutionProfile.DOTNET_APP, "backend");
        assertTrue(executor.commands().stream().anyMatch(c -> c.contains("a".repeat(40) + ":backend")),
                executor.commands().toString());
    }

    @Test
    void unknownComponentDirFailsTheCheckout() {
        var result = runner.verify("M-1", "a".repeat(40), ExecutionProfile.DOTNET_APP, "../etc");
        assertEquals("FAIL", result.overall());
        assertTrue(result.steps().get(0).outputTail().contains("componentDir"), result.steps().get(0).outputTail());
    }
```

(Si el executor falso del test no guarda los comandos, agregarle una lista `commands()` que registre cada `List<String>` recibido.)

En `JobControllerTest`:

```java
    @Test
    void verifyRejectsAComponentDirOutsideTheCatalog() {
        var response = controller.run(TOKEN, new JobController.JobRequest("VERIFY", "M-1", "a".repeat(40),
                "DOTNET_APP", "src", null));
        assertEquals(400, response.getStatusCode().value());
    }
```

y ajustar las construcciones existentes de `JobRequest` agregando `null` como `componentDir` (quinto argumento).

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd sandbox-runner && mvn -q test`
Expected: FAIL de compilación (`componentDir` no existe).

- [ ] **Step 3: Implementar**

`JobController`:

```java
    public record JobRequest(String jobType, String missionId, String commitSha, String stackProfile,
                             String componentDir, DependencyRequest dependencies) {
    }
```

y antes del `return ResponseEntity.ok(runner.verify(...))`:

```java
        var componentDir = request.componentDir() == null ? "" : request.componentDir();
        if (!VerifyJobRunner.COMPONENT_DIRS.contains(componentDir)) {
            return ResponseEntity.badRequest().body(Map.of("error", "componentDir desconocido: " + componentDir));
        }

        return ResponseEntity.ok(runner.verify(request.missionId(), request.commitSha(), profile.get(), componentDir));
```

`VerifyJobRunner`:

```java
    /** Spec 2026-10-02 B1 §3: carpetas de componente admitidas (lista fija, como los perfiles). */
    static final java.util.Set<String> COMPONENT_DIRS = java.util.Set.of("", "backend", "app", "game-client");

    public SandboxResult verify(String missionId, String commitSha, ExecutionProfile profile) {
        return verify(missionId, commitSha, profile, "");
    }

    public SandboxResult verify(String missionId, String commitSha, ExecutionProfile profile, String componentDir) {
        // cuerpo actual, con checkout(missionId, commitSha, componentDir, jobDir)
    }
```

En `checkout`, agregar el parámetro `String componentDir` y:

```java
        if (!repo.startsWith(productsRoot) || !commitSha.matches("[0-9a-f]{40}")
                || !COMPONENT_DIRS.contains(componentDir == null ? "" : componentDir)) {
            return new SandboxResult.StepResult("checkout", SandboxResult.FAIL, -1, 0,
                    "missionId, commitSha o componentDir inválidos.", 0, 0);
        }

        var treeish = componentDir == null || componentDir.isEmpty() ? commitSha : commitSha + ":" + componentDir;
        var archive = executor.run(List.of("git", "-c", "safe.directory=*", "-C", repo.toString(), "archive",
                "--format=tar", "-o", tar.toString(), treeish), jobDir, 60);
```

- [ ] **Step 4: Correr los tests**

Run: `cd sandbox-runner && mvn -q test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sandbox-runner/src
git commit -m "sandbox-runner: VERIFY de una carpeta de componente (lista fija)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Paquete `http` precargado y contratos de los perfiles

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/BaselineDependencies.java`
- Modify: `app/src/main/java/com/aicompany/core/model/StackProfile.java` (`executionContract` de `FLUTTER_WEB_APP`, constante nueva `FULLSTACK_API_RULE`)
- Modify: `sandbox/images/flutter-web-app/Dockerfile`
- Modify: `sandbox/build-images.sh`
- Test: `app/src/test/java/com/aicompany/core/model/BaselineDependenciesTest.java`, `StackProfileTest.java`

**Interfaces:**
- Produces: `StackProfile.FULLSTACK_API_RULE` (texto para Iris con app en la misión) y `StackProfile.FULLSTACK_APP_RULE` (texto para Mila), ambos `public static final String`.

- [ ] **Step 1: Tests que fallan**

En `BaselineDependenciesTest`:

```java
    // Spec 2026-10-02 B1 §2: la app llama a la API con http, precargado en la imagen de Flutter.
    @Test
    void httpIsPreapprovedForFlutter() {
        assertTrue(BaselineDependencies.contains(new DependencyRef("PUB", "http", "1.2.2")));
    }
```

En `StackProfileTest`:

```java
    @Test
    void flutterContractOffersHttpAndForbidsWebOnlyLibraries() {
        var contract = StackProfile.FLUTTER_WEB_APP.executionContract();
        assertTrue(contract.contains("http 1.2.2"), contract);
        assertTrue(contract.contains("dart:html"), contract);
        assertTrue(StackProfile.FULLSTACK_API_RULE.contains("MapGet"), StackProfile.FULLSTACK_API_RULE);
        assertTrue(StackProfile.FULLSTACK_API_RULE.contains("CORS_ORIGINS"), StackProfile.FULLSTACK_API_RULE);
        assertTrue(StackProfile.FULLSTACK_APP_RULE.contains("API_BASE_URL"), StackProfile.FULLSTACK_APP_RULE);
    }
```

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn -q test -Dtest='BaselineDependenciesTest,StackProfileTest'`
Expected: FAIL.

- [ ] **Step 3: Implementar**

`BaselineDependencies.IDS`: agregar `"PUB:http@1.2.2"` con el comentario `// Spec 2026-10-02 B1 §2: la app llama a la API (precargado en sandbox/images/flutter-web-app).`

`StackProfile`: en el texto de `FLUTTER_WEB_APP`, después de `flutter_lints 4.0.0 (dev).` agregar `"http 1.2.2 está disponible para llamar a una API (pídelo en pubspec.yaml como http: 1.2.2). "`, y al final del texto agregar `" El código sirve igual para web, Android e iOS: nunca importes dart:html, dart:js, dart:js_util, dart:js_interop ni package:web (Forjai rechaza el repositorio)."`. Agregar las constantes:

```java
    /** Spec 2026-10-02 B1 §2: el backend de una app completa (Iris), para que Java lea sus rutas. */
    public static final String FULLSTACK_API_RULE = "APP COMPLETA: una app Flutter consume esta API. Los endpoints "
            + "van como minimal APIs con la ruta LITERAL en el código (app.MapGet(\"/clientes/{id}\", …), MapPost, MapPut, "
            + "MapPatch, MapDelete; grupos con MapGroup(\"/…\")), nunca controllers ni rutas en variables: Forjai lee "
            + "esas rutas y genera el contrato de la app. Habilita CORS para los orígenes de la variable de entorno "
            + "CORS_ORIGINS (lista separada por comas); /health responde sin CORS ni base.";

    /** Spec 2026-10-02 B1 §2: la app de una app completa (Mila). */
    public static final String FULLSTACK_APP_RULE = "APP COMPLETA: la app consume la API del backend. Usa el paquete "
            + "http y SOLO las rutas de ApiRoutes (lib/api/api_routes.dart, generado por Forjai desde el backend; "
            + "impórtalo con package:<nombre de pubspec>/api/api_routes.dart y no lo escribas). La URL base sale de "
            + "const String.fromEnvironment('API_BASE_URL'); nunca escribas rutas de la API ni localhost a mano. Los "
            + "campos JSON siguen los nombres en camelCase de los tipos públicos del backend (contrato de abajo).";
```

`sandbox/images/flutter-web-app/Dockerfile`: en el `RUN` de la semilla, reemplazar `cd /opt/seed && flutter pub get` por `cd /opt/seed && flutter pub add http:1.2.2 && flutter pub get` (sigue dentro de la misma capa, antes del `chown`).

`sandbox/build-images.sh`: reemplazar el bloque que siembra `~/forjai-deps/pub` solo si no existe por una copia sin pisar lo existente, para que las versiones nuevas de la imagen lleguen también a una caché ya sembrada:

```bash
mkdir -p ~/forjai-deps/pub
# Sin pisar lo ya aprobado: agrega a la caché lo que trae la imagen (p. ej. http 1.2.2, spec 2026-10-02 B1).
podman run --rm --userns=keep-id -v ~/forjai-deps/pub:/seed:z localhost/forjai-sandbox/flutter-web-app:1 \
  bash -c 'cp -an /opt/pub-cache/. /seed/'
```

- [ ] **Step 4: Correr los tests**

Run: `cd app && mvn -q test -Dtest='BaselineDependenciesTest,StackProfileTest'`
Expected: PASS.

- [ ] **Step 5: Construir la imagen y comprobar `http` sin red**

Run:
```bash
cd /home/alex/Documentos/projectos/empresa && podman build -t localhost/forjai-sandbox/flutter-web-app:1 sandbox/images/flutter-web-app && bash sandbox/build-images.sh 2>&1 | tail -3
ls ~/forjai-deps/pub/hosted/pub.dev | grep '^http-1.2.2$'
```
Expected: `http-1.2.2` listado. (`build-images.sh` reconstruye todas las imágenes; si tarda demasiado, correr solo el bloque de siembra.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aicompany/core/model/BaselineDependencies.java app/src/main/java/com/aicompany/core/model/StackProfile.java app/src/test/java/com/aicompany/core/model/BaselineDependenciesTest.java app/src/test/java/com/aicompany/core/model/StackProfileTest.java sandbox/images/flutter-web-app/Dockerfile sandbox/build-images.sh
git commit -m "Apps completas: http 1.2.2 precargado y contratos de API y app

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Sandbox por componente en `company-core`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/ComponentSandbox.java`
- Modify: `app/src/main/java/com/aicompany/core/service/SandboxRunnerClient.java`
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (llamada a `sandbox.verify`, `compileErrors`, `compileErrorsByOwner`, `commitMissingUsings`, `sandboxSummary`, `sandboxEvidence`)
- Test: `app/src/test/java/com/aicompany/core/service/ComponentSandboxTest.java`

**Interfaces:**
- Consumes: `Component`, `SandboxResult`, `CompilerErrorParser`, `MissingUsingFixer.fix(profile, contexts, files, errors)`.
- Produces:
  - `SandboxRunnerClient.verify(String missionId, String commitSha, String stackProfile, String componentDir)`; la de 3 argumentos delega con `""` y manda `componentDir` solo si no es vacío.
  - `ComponentSandbox.combine(List<Component>, List<Optional<SandboxResult>>) → Optional<SandboxResult>`: vacío si falta alguno; pasos con prefijo `dir/` (sin prefijo con un componente); `overall = PASS` solo si cada componente pasó con ≥1 test.
  - `ComponentSandbox.stepName(String) → String` (`"backend/build"` → `"build"`), `ComponentSandbox.dirOf(String) → String` (`"backend/build"` → `"backend"`, `"build"` → `""`).
  - `ComponentSandbox.compileErrors(SandboxResult) → List<CompilerError>` con las rutas prefijadas por la carpeta del paso fallido.

- [ ] **Step 1: Tests que fallan**

```java
package com.aicompany.core.service;

import com.aicompany.core.model.Component;
import com.aicompany.core.model.SandboxResult;
import com.aicompany.core.model.SandboxResult.StepResult;
import com.aicompany.core.model.StackProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ComponentSandboxTest {

    private static final List<Component> FULLSTACK =
            Component.of(List.of(StackProfile.DOTNET_APP, StackProfile.FLUTTER_WEB_APP));

    private static SandboxResult ok(int tests) {
        return new SandboxResult("PASS", List.of(new StepResult("build", "PASS", 0, 1000, "", 0, 0),
                new StepResult("test", "PASS", 0, 1000, "", tests, 0)));
    }

    @Test
    void oneComponentIsReturnedUnchanged() {
        var root = List.of(new Component(StackProfile.DOTNET_APP, ""));
        assertEquals(Optional.of(ok(3)), ComponentSandbox.combine(root, List.of(Optional.of(ok(3)))));
    }

    @Test
    void stepsArePrefixedAndPassOnlyWithTestsInEveryComponent() {
        var combined = ComponentSandbox.combine(FULLSTACK, List.of(Optional.of(ok(4)), Optional.of(ok(2)))).orElseThrow();
        assertEquals("PASS", combined.overall());
        assertEquals(List.of("backend/build", "backend/test", "app/build", "app/test"),
                combined.stepsOrEmpty().stream().map(StepResult::name).toList());
        assertEquals(6, combined.testsPassed());

        var noAppTests = ComponentSandbox.combine(FULLSTACK, List.of(Optional.of(ok(4)), Optional.of(ok(0)))).orElseThrow();
        assertEquals("FAIL", noAppTests.overall());
        assertFalse(noAppTests.passed());
    }

    // Review Focus 5: el runner cae en el segundo componente → sin resultado (UNVALIDATED), nunca PASS.
    @Test
    void aMissingComponentResultMeansNoResult() {
        assertEquals(Optional.empty(), ComponentSandbox.combine(FULLSTACK, List.of(Optional.of(ok(4)), Optional.empty())));
    }

    // Review Focus 4: errores de la app vuelven con la ruta real.
    @Test
    void compileErrorsGetTheComponentPrefix() {
        var result = new SandboxResult("FAIL", List.of(
                new StepResult("backend/build", "PASS", 0, 1, "", 0, 0),
                new StepResult("app/build", "FAIL", 1, 1,
                        "lib/clientes/presentation/x.dart:3:5: Error: Undefined name 'ApiRoutes'.", 0, 0)));
        var errors = ComponentSandbox.compileErrors(result);
        assertEquals(1, errors.size());
        assertEquals("app/lib/clientes/presentation/x.dart", errors.get(0).path());
        assertEquals("build", ComponentSandbox.stepName("app/build"));
        assertEquals("app", ComponentSandbox.dirOf("app/build"));
        assertEquals("", ComponentSandbox.dirOf("build"));
    }
}
```

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn -q test -Dtest=ComponentSandboxTest`
Expected: FAIL (no existe `ComponentSandbox`).

- [ ] **Step 3: `ComponentSandbox`**

```java
package com.aicompany.core.service;

import com.aicompany.core.agent.validation.CompilerErrorParser;
import com.aicompany.core.model.Component;
import com.aicompany.core.model.SandboxResult;
import com.aicompany.core.model.SandboxResult.StepResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Spec 2026-10-02 B1 §3: un VERIFY por componente, combinado. Pasa solo si cada componente pasó con ≥1 test aprobado;
 * si falta el resultado de alguno (runner caído), no hay resultado: la misión queda UNVALIDATED, nunca VERIFIED.
 */
public final class ComponentSandbox {

    private ComponentSandbox() {
    }

    public static Optional<SandboxResult> combine(List<Component> components, List<Optional<SandboxResult>> results) {
        if (results.stream().anyMatch(Optional::isEmpty)) {
            return Optional.empty();
        }
        if (components.size() == 1) {
            return results.get(0);
        }
        var steps = new ArrayList<StepResult>();
        var passed = true;
        for (int i = 0; i < components.size(); i++) {
            var component = components.get(i);
            var result = results.get(i).orElseThrow();
            passed &= result.passed() && result.testsPassed() > 0;
            for (var step : result.stepsOrEmpty()) {
                steps.add(new StepResult(component.prefix(step.name()), step.status(), step.exitCode(),
                        step.durationMs(), step.outputTail(), step.testsPassed(), step.testsFailed()));
            }
        }
        return Optional.of(new SandboxResult(passed ? "PASS" : "FAIL", steps));
    }

    public static String stepName(String name) {
        return name == null ? "" : name.substring(name.lastIndexOf('/') + 1);
    }

    public static String dirOf(String name) {
        return name == null || !name.contains("/") ? "" : name.substring(0, name.lastIndexOf('/'));
    }

    /** Errores del compilador del primer paso fallido (restore/build), con la ruta real en el repositorio. */
    public static List<CompilerErrorParser.CompilerError> compileErrors(SandboxResult result) {
        var failed = result.stepsOrEmpty().stream()
                .filter(step -> "FAIL".equals(step.status()) || "TIMEOUT".equals(step.status()))
                .findFirst();
        if (failed.isEmpty() || !List.of("restore", "build").contains(stepName(failed.get().name()))) {
            return List.of();
        }
        var dir = dirOf(failed.get().name());
        return CompilerErrorParser.parse(failed.get().outputTail()).stream()
                .map(e -> dir.isEmpty() ? e : new CompilerErrorParser.CompilerError(dir + "/" + e.path(), e.line(),
                        e.column(), e.code(), e.message()))
                .toList();
    }
}
```

- [ ] **Step 4: `SandboxRunnerClient`**

```java
    public Optional<SandboxResult> verify(String missionId, String commitSha, String stackProfile) {
        return verify(missionId, commitSha, stackProfile, "");
    }

    /** Spec 2026-10-02 B1 §3: VERIFY de la carpeta de un componente ("" = raíz). */
    public Optional<SandboxResult> verify(String missionId, String commitSha, String stackProfile, String componentDir) {
        try {
            lastError = null;
            var body = new java.util.LinkedHashMap<String, Object>(Map.of("jobType", "VERIFY", "missionId", missionId,
                    "commitSha", commitSha, "stackProfile", stackProfile));
            if (componentDir != null && !componentDir.isEmpty()) {
                body.put("componentDir", componentDir);
            }
            var result = client.post().uri("/jobs")
                    .header("X-Sandbox-Token", token == null ? "" : token)
                    .body(body)
                    .retrieve()
                    .body(SandboxResult.class);
            // ... resto igual que el método actual
```

(Mover el cuerpo actual del método de 3 argumentos al de 4, cambiando solo el `body`.)

- [ ] **Step 5: `DevelopmentTeamStrategy`**

1. Reemplazar `var verified = sandbox.verify(missionId, headSha, profile.name());` por:

```java
            var verified = verifyComponents(missionId, headSha, components);
```

con el método:

```java
    /** Spec 2026-10-02 B1 §3: un VERIFY por componente, siempre todos (una ronda de corrección recibe todos los errores). */
    private Optional<SandboxResult> verifyComponents(String missionId, String headSha, List<Component> components) {
        if (components.size() == 1) {
            return sandbox.verify(missionId, headSha, components.get(0).profile().name());
        }
        var results = new ArrayList<Optional<SandboxResult>>();
        String error = null;
        for (var component : components) {
            var result = sandbox.verify(missionId, headSha, component.profile().name(), component.dir());
            if (result.isEmpty() && error == null) {
                error = component.dir() + "/: " + sandbox.lastError();
            }
            results.add(result);
        }
        lastSandboxError = error;
        return ComponentSandbox.combine(components, results);
    }
```

   y `sandboxError = verified.isEmpty() ? (components.size() == 1 ? sandbox.lastError() : lastSandboxError) : null;` (campo `private String lastSandboxError;` en la clase; importar `java.util.Optional`).

2. `compileErrors(SandboxResult)` (l.598) pasa a `return ComponentSandbox.compileErrors(result);`. En `compileErrorsByOwner`, la condición del paso pasa a `!List.of("restore", "build").contains(ComponentSandbox.stepName(failed.get().name()))`.

3. `commitMissingUsings(missionId, headSha, components, contexts, result)`: por cada componente NuGet, quitar el prefijo a archivos y errores, llamar a `MissingUsingFixer.fix(component.profile(), component.contexts(contexts), stripped, strippedErrors)` y volver a poner el prefijo en las claves del resultado:

```java
            var fixes = new LinkedHashMap<String, String>();
            for (var component : components) {
                if (component.profile().ecosystem() != StackProfile.Ecosystem.NUGET) {
                    continue;
                }
                var own = new LinkedHashMap<String, String>();
                files.forEach((path, content) -> component.strip(path).filter(p -> component.owns(path))
                        .ifPresent(p -> own.put(p, content)));
                var ownErrors = errors.stream().filter(e -> component.owns(e.path()))
                        .map(e -> new CompilerErrorParser.CompilerError(component.strip(e.path()).orElseThrow(),
                                e.line(), e.column(), e.code(), e.message())).toList();
                MissingUsingFixer.fix(component.profile(), component.contexts(contexts), own, ownErrors)
                        .forEach((path, content) -> fixes.put(component.prefix(path), content));
            }
```

4. `sandboxSummary`: el `switch` usa `ComponentSandbox.stepName(s.name())` y la etiqueta lleva delante `ComponentSandbox.dirOf(s.name())` + `" "` cuando no es vacío (`"backend Build PASS (10 s) · app Tests PASS 6/6 (3 s)"`); `"test".equals(...)` también con `stepName`.

5. `sandboxEvidence`: sin cambios de código (el `name` ya trae `backend/build`, y queda `sandbox:<misión>@<sha>/backend/build`).

6. Los dos usos provisionales de `components.get(0).profile()` de la Task 5 desaparecen.

- [ ] **Step 6: Test de integración de la estrategia** (en `DevelopmentTeamStrategyTest`)

```java
    // Review Focus 5: con dos componentes, si el runner cae en app/ la misión no queda VERIFIED.
    @Test
    void fullstackWithoutTheSecondSandboxResultIsUnvalidated() throws Exception {
        // contexto con components [DOTNET_APP, FLUTTER_WEB_APP] y tareas backend/frontend-ui/qa ya resueltas;
        // stubs: generate/commit como stubHappyPath pero con rutas backend/... y app/...; validator.validate(...) PASS;
        when(sandbox.verify(eq("M-1"), anyString(), eq("DOTNET_APP"), eq("backend"))).thenReturn(Optional.of(
                new SandboxResult("PASS", List.of(new SandboxResult.StepResult("test", "PASS", 0, 1, "", 3, 0)))));
        when(sandbox.verify(eq("M-1"), anyString(), eq("FLUTTER_WEB_APP"), eq("app"))).thenReturn(Optional.empty());
        when(sandbox.lastError()).thenReturn("sandbox-runner no disponible: timeout");

        var result = strategy.execute(fullstackContext(), progress);

        verify(memory).recordStaticValidation(anyString(), eq("UNVALIDATED"), anyString());
        assertTrue(result.verifiableState().contains("app/: sandbox-runner no disponible"), result.verifiableState());
    }
```

   Armar `fullstackContext()` con el equipo de `context()` más `backend` (`BACKEND`), y un `TeamPlan` de 10 argumentos con `components = List.of("DOTNET_APP", "FLUTTER_WEB_APP")`, contexto `Clientes` y tareas con `ownedPaths` ya resueltos (`backend/src/Clientes.Domain`, `backend/src/Clientes.Application`, `backend/src/Clientes.Api` para `backend`; `app/lib/clientes/presentation`, `app/pubspec.yaml`, `app/lib/main.dart` para `frontend-ui`; `qa` con VALIDATION). Si el nombre del método que graba el estado no es `recordStaticValidation(taskId, status, json)`, usar el real (`grep -n "recordStaticValidation" app/src/main/java/com/aicompany/core/service/MissionMemoryService.java`).

- [ ] **Step 7: Correr los tests**

Run: `cd app && mvn -q test -Dtest='ComponentSandboxTest,DevelopmentTeamStrategyTest,SandboxRunnerClientTest'`
Expected: PASS (los tests viejos siguen usando la `verify` de 3 argumentos con un componente).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/ComponentSandbox.java app/src/main/java/com/aicompany/core/service/SandboxRunnerClient.java app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java app/src/test/java/com/aicompany/core/service/ComponentSandboxTest.java app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java
git commit -m "Apps completas: sandbox por componente y errores con la ruta real

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Orden, contrato de rutas y gates en la generación

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (`orderByLayer`, bucle de generación l.141-200, `generate` l.927-937, `buildWorkPrompt`, ciclo de corrección l.292-348, `withoutFindingsOn` l.399)
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/ComponentStructureChecker.java` (chequeo `API_ROUTES_CONSISTENT`)
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java`, `ComponentStructureCheckerTest.java`

**Interfaces:**
- Consumes: `HttpRouteExtractor.extract`, `ApiRoutesGenerator.generate/PATH`, `ApiContractGate.check`, `StackProfile.FULLSTACK_API_RULE/FULLSTACK_APP_RULE`, `PublicApiExtractor.extract`.
- Produces: `DevelopmentTeamStrategy.orderTasks(List<PlannedTask>, TeamPlan)` (package-private static, testeable); commit `<misión>-API-ROUTES`; chequeo `API_ROUTES_CONSISTENT`.

- [ ] **Step 1: Tests que fallan**

En `DevelopmentTeamStrategyTest`:

```java
    // Spec 2026-10-02 B1 §2 + Review Focus 2: backend antes que app, tests al final, aunque Neo los liste al revés.
    @Test
    void fullstackTasksRunBackendThenAppThenTests() {
        var plan = fullstackPlan();
        var ordered = DevelopmentTeamStrategy.orderTasks(List.of(task(plan, "qa"), task(plan, "frontend-ui"),
                task(plan, "devops"), task(plan, "backend")), plan);
        assertEquals(List.of("backend", "devops", "frontend-ui", "qa"),
                ordered.stream().map(PlannedTask::agentId).toList());
    }

    @Test
    void apiRoutesAreCommittedByForjaiBeforeTheApp() throws Exception {
        // stubs del camino feliz fullstack (Step 6 de la Task 10) + filesAtCommit/readFileAtCommit devolviendo
        // backend/src/Clientes.Api/Program.cs con app.MapGet("/clientes/{id}", ...)
        strategy.execute(fullstackContext(), progress);
        InOrder inOrder = inOrder(workspace);
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-BACKEND"), eq("backend"), anyString(), any());
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-API-ROUTES"), eq("forjai"), eq("Forjai"),
                argThat(r -> r.files().get(0).path().equals("app/lib/api/api_routes.dart")
                        && r.files().get(0).content().contains("obtenerClientes(String id)")));
        inOrder.verify(workspace).commitAgentWork(eq("M-1"), eq("M-1-FRONTEND-UI"), eq("frontend-ui"), anyString(), any());
    }
```

(`fullstackPlan()` devuelve el plan de `fullstackContext()`; `task(plan, id)` la `PlannedTask` WORK de ese agente.)

En `ComponentStructureCheckerTest`:

```java
    @Test
    void apiRoutesMustMatchTheBackendOfTheSameCommit() {
        var program = "app.MapGet(\"/clientes/{id}\", (Guid id) => Results.Ok());";
        var stale = ApiRoutesGenerator.generate(List.of(new com.aicompany.core.model.HttpRoute("GET", "/clientes")));
        var checks = ComponentStructureChecker.check(FULLSTACK, List.of("Clientes"), FILES, Map.of(
                "backend/src/Clientes.Api/Program.cs", program, "app/lib/api/api_routes.dart", stale),
                List.of("app/lib/api/api_routes.dart"));
        assertFalse(named(checks, "API_ROUTES_CONSISTENT").passed());

        var fresh = ApiRoutesGenerator.generate(HttpRouteExtractor.extract(Map.of("Program.cs", program)));
        var ok = ComponentStructureChecker.check(FULLSTACK, List.of("Clientes"), FILES, Map.of(
                "backend/src/Clientes.Api/Program.cs", program, "app/lib/api/api_routes.dart", fresh),
                List.of("app/lib/api/api_routes.dart"));
        assertTrue(named(ok, "API_ROUTES_CONSISTENT").passed(), named(ok, "API_ROUTES_CONSISTENT").detail());
    }
```

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn -q test -Dtest='DevelopmentTeamStrategyTest,ComponentStructureCheckerTest'`
Expected: FAIL.

- [ ] **Step 3: `API_ROUTES_CONSISTENT` en `ComponentStructureChecker`**

Al final de `check`, antes del `return`, si hay un componente `DOTNET_APP` y uno `FLUTTER_WEB_APP`:

```java
        var backend = components.stream().filter(c -> c.profile() == StackProfile.DOTNET_APP).findFirst();
        var app = components.stream().filter(c -> c.profile() == StackProfile.FLUTTER_WEB_APP).findFirst();
        if (backend.isPresent() && app.isPresent() && multi) {
            result.add(apiRoutesCheck(backend.get(), app.get(), contents));
        }
```

```java
    /** Spec 2026-10-02 B1 §3: api_routes.dart del commit = lo que sale del backend de ese mismo commit. */
    static StaticCheck apiRoutesCheck(Component backend, Component app, Map<String, String> contents) {
        var apiSources = new LinkedHashMap<String, String>();
        contents.forEach((path, content) -> {
            if (backend.owns(path) && path.endsWith(".cs") && path.contains(".Api/")) {
                apiSources.put(path, content);
            }
        });
        var routesPath = app.prefix(ApiRoutesGenerator.PATH);
        var expected = ApiRoutesGenerator.generate(HttpRouteExtractor.extract(apiSources));
        var actual = contents.get(routesPath);
        return expected.equals(actual)
                ? StaticCheck.pass("API_ROUTES_CONSISTENT", routesPath + " coincide con las rutas del backend", null, List.of())
                : StaticCheck.fail("API_ROUTES_CONSISTENT", routesPath + (actual == null ? " no existe" : " no coincide")
                        + " con las rutas del backend del mismo commit", null, List.of(routesPath));
    }
```

`StaticWorkspaceValidator.validateHead` (Task 5) ya lee los `.cs` del backend; agregar a la condición de lectura `|| forjaiFiles.contains(path)` para que lea también `app/lib/api/api_routes.dart`.

- [ ] **Step 4: Orden de tareas**

Reemplazar `orderByLayer` por:

```java
    /**
     * Spec 2026-10-02 B1 §2: por componente (backend antes que app, en el orden fijo de Component) y dentro de cada uno
     * por capa; los tests siempre al final. Con un componente es el orden por capas de siempre.
     */
    static List<PlannedTask> orderTasks(List<PlannedTask> work, TeamPlan plan) {
        var components = plan.resolvedComponents();
        if (components.isEmpty()) {
            return work;
        }
        return work.stream()
                .sorted(java.util.Comparator.comparingInt(task -> task.ownedPathsOrEmpty().stream()
                        .map(path -> rank(components, plan.contextNames(), path))
                        .min(Integer::compare).orElse(Integer.MAX_VALUE)))
                .toList();
    }

    private static int rank(List<Component> components, List<String> contexts, String path) {
        for (int i = 0; i < components.size(); i++) {
            var component = components.get(i);
            if (!component.owns(path) || (components.size() > 1 && component.isRoot())) {
                continue;
            }
            var layer = component.strip(path)
                    .flatMap(p -> component.profile().locate(p, component.contexts(contexts)))
                    .map(location -> layerRank(location.layer())).orElse(3);
            return layer == 4 ? 1_000 : i * 10 + layer;
        }
        return 500; // raíz común (migraciones, .env.example): no define el orden
    }
```

y cambiar `var ordered = orderByLayer(work, plan);` por `var ordered = orderTasks(work, plan);`.

- [ ] **Step 5: Commit de rutas antes de la app y en cada corrección del backend**

Agregar a la clase:

```java
    static final String API_ROUTES_TASK_SUFFIX = "-API-ROUTES";

    /**
     * Spec 2026-10-02 B1 §2: Forjai commitea app/lib/api/api_routes.dart desde las rutas reales del backend en HEAD.
     * null si la misión no es app + API o si el archivo ya está igual.
     */
    private CommittedWork commitApiRoutes(TeamMissionContext context, String headSha) {
        var plan = context.plan();
        var backend = plan.resolvedComponents().stream().filter(c -> c.profile() == StackProfile.DOTNET_APP).findFirst();
        var app = plan.resolvedComponents().stream().filter(c -> c.profile() == StackProfile.FLUTTER_WEB_APP).findFirst();
        if (backend.isEmpty() || app.isEmpty() || plan.resolvedComponents().size() < 2 || headSha == null) {
            return null;
        }
        var missionId = context.missionId();
        try {
            var sources = new LinkedHashMap<String, String>();
            for (var path : workspace.filesAtCommit(missionId, headSha)) {
                if (backend.get().owns(path) && path.endsWith(".cs") && path.contains(".Api/")) {
                    sources.put(path, workspace.readFileAtCommit(missionId, headSha, path));
                }
            }
            var path = app.get().prefix(ApiRoutesGenerator.PATH);
            var content = ApiRoutesGenerator.generate(HttpRouteExtractor.extract(sources));
            if (content.equals(readOrNull(missionId, headSha, path))) {
                return null;
            }
            var id = missionId + API_ROUTES_TASK_SUFFIX;
            var record = workspace.commitAgentWork(missionId, id, "forjai", "Forjai", new DevelopmentResult(
                    "Forjai generó el contrato de rutas de la app desde " + HttpRouteExtractor.extract(sources).size()
                            + " ruta(s) del backend", List.of(new DevelopmentResult.GeneratedFile(path, content))));
            return new CommittedWork(id, "forjai", record.sha(), record.files());
        } catch (Exception ex) {
            log.warn("MISSION {} - could not commit API routes: {}", missionId, ex.getMessage());
            return null;
        }
    }
```

En el bucle de generación, antes de `progress.advance(...)` de cada tarea:

```java
            // Spec 2026-10-02 B1 §2: el contrato de rutas se escribe antes de la primera tarea de la app.
            if (!routesCommitted && touchesFlutter(task, plan)) {
                var routes = commitApiRoutes(context, generationHead);
                if (routes != null) {
                    committed.add(routes);
                    generationHead = routes.commitSha();
                }
                routesCommitted = true;
            }
```

con `var routesCommitted = false;` antes del bucle, `forjaiFiles.add(app.prefix(ApiRoutesGenerator.PATH))` cuando la misión es app + API (la lista `forjaiFiles` de la Task 5 se crea ahora antes del bucle), y:

```java
    private static boolean touchesFlutter(PlannedTask task, TeamPlan plan) {
        return plan.resolvedComponents().size() > 1 && plan.resolvedComponents().stream()
                .filter(c -> c.profile() == StackProfile.FLUTTER_WEB_APP)
                .anyMatch(c -> task.ownedPathsOrEmpty().stream().anyMatch(c::owns));
    }
```

`allowedPaths` (l.229) suma `forjaiFiles`. En el ciclo de corrección, después de `repairRounds++;` y antes del `staticValidator.validate`:

```java
            var routes = commitApiRoutes(context, headSha);
            if (routes != null) {
                committed.add(routes);
                headSha = routes.commitSha();
            }
```

`withoutFindingsOn(review, ...)` recibe la lista de archivos del scaffold más `forjaiFiles`.

- [ ] **Step 6: Prompts y gates de Iris y Mila**

`buildWorkPrompt` pasa a `buildWorkPromptBase(context, task) + databaseBlock(context) + fullstackBlock(context, task)`:

```java
    /** Spec 2026-10-02 B1 §2: reglas de app completa para el dueño de la API y para la dueña de la app. */
    private String fullstackBlock(TeamMissionContext context, PlannedTask task) {
        var plan = context.plan();
        if (plan.resolvedComponents().size() < 2 || !plan.hasComponent(StackProfile.FLUTTER_WEB_APP)
                || !plan.hasComponent(StackProfile.DOTNET_APP)) {
            return "";
        }
        if (touchesFlutter(task, plan)) {
            return "\n\n" + StackProfile.FULLSTACK_APP_RULE + "\n";
        }
        var ownsApi = task.ownedPathsOrEmpty().stream().anyMatch(p -> p.startsWith("backend/") && p.contains(".Api"));
        return ownsApi ? "\n\n" + StackProfile.FULLSTACK_API_RULE + "\n" : "";
    }
```

El contrato público del backend ya le llega a Mila: `existingCode` → `repositoryCode` → `renderWithContract`, que antepone el contrato de `PublicApiExtractor` de todo el repositorio (incluye `backend/` y `api_routes.dart`). No hace falta otro bloque.

`generate(...)` (l.927): construir los chequeos extra combinados y usar la variante con `extraChecks` cuando haya alguno:

```java
    private DevelopmentResult generate(String id, String missionId, String agentId, String prompt, List<String> owned,
                                       List<String> expectedProjects, List<String> requiredPaths,
                                       TeamMissionContext context, String headSha) {
        var checks = new ArrayList<java.util.function.Function<DevelopmentResult, List<String>>>();
        if (context.plan().databaseOrNull() != null) {
            checks.add(databaseChecks(context, headSha));
        }
        var api = apiChecks(context, owned, headSha);
        if (api != null) {
            checks.add(api);
        }
        if (checks.isEmpty()) {
            return requiredPaths == null
                    ? runtime.generate(id, missionId, agentId, prompt, owned, expectedProjects).join()
                    : runtime.generate(id, missionId, agentId, prompt, owned, expectedProjects, requiredPaths).join();
        }
        return runtime.generate(id, missionId, agentId, prompt, owned, expectedProjects,
                requiredPaths == null ? owned : requiredPaths,
                result -> checks.stream().flatMap(c -> c.apply(result).stream()).toList()).join();
    }

    /**
     * Spec 2026-10-02 B1 §2: la dueña de la app no escribe rutas de la API a mano (ApiContractGate); el dueño de la API
     * deja al menos una ruta literal que Java pueda leer. null si no aplica.
     */
    private java.util.function.Function<DevelopmentResult, List<String>> apiChecks(
            TeamMissionContext context, List<String> owned, String headSha) {
        var plan = context.plan();
        if (plan.resolvedComponents().size() < 2 || !plan.hasComponent(StackProfile.FLUTTER_WEB_APP)
                || !plan.hasComponent(StackProfile.DOTNET_APP)) {
            return null;
        }
        var task = new PlannedTask("", "WORK", "X", "x", List.of(), owned);
        if (touchesFlutter(task, plan)) {
            var routes = HttpRouteExtractor.extract(backendApiSources(context.missionId(), headSha));
            return result -> ApiContractGate.check(result.files(), routes);
        }
        if (owned.stream().anyMatch(p -> p.startsWith("backend/") && p.contains(".Api"))) {
            return result -> {
                var sources = backendApiSources(context.missionId(), headSha);
                (result.files() == null ? List.<DevelopmentResult.GeneratedFile>of() : result.files()).stream()
                        .filter(f -> f != null && f.path() != null && f.path().endsWith(".cs") && f.path().contains(".Api/"))
                        .forEach(f -> sources.put(f.path(), f.content()));
                return HttpRouteExtractor.extract(sources).isEmpty()
                        ? List.of("No encontré endpoints minimal API con ruta literal (app.MapGet(\"/…\"), MapPost, …) "
                                + "en backend/src/<Ctx>.Api: la app Flutter los necesita y Forjai los lee del código.")
                        : List.of();
            };
        }
        return null;
    }

    private Map<String, String> backendApiSources(String missionId, String headSha) {
        var sources = new LinkedHashMap<String, String>();
        if (headSha == null) {
            return sources;
        }
        try {
            for (var path : workspace.filesAtCommit(missionId, headSha)) {
                if (path.startsWith("backend/") && path.endsWith(".cs") && path.contains(".Api/")) {
                    sources.put(path, workspace.readFileAtCommit(missionId, headSha, path));
                }
            }
        } catch (Exception ex) {
            log.warn("MISSION {} - could not read backend API sources: {}", missionId, ex.getMessage());
        }
        return sources;
    }
```

Importar `HttpRouteExtractor`, `ApiRoutesGenerator`, `ApiContractGate`, `Component`.

- [ ] **Step 7: Correr los tests**

Run: `cd app && mvn -q test -Dtest='DevelopmentTeamStrategyTest,ComponentStructureCheckerTest'`
Expected: PASS (los tests de un componente no cambian: sin app + API no hay rutas ni gates).

- [ ] **Step 8: Suite completa**

Run: `cd app && mvn -q test`
Expected: BUILD SUCCESS.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java app/src/main/java/com/aicompany/core/agent/validation/ComponentStructureChecker.java app/src/main/java/com/aicompany/core/service/StaticWorkspaceValidator.java app/src/test/java
git commit -m "Apps completas: backend antes que app, contrato de rutas de Forjai y gates de API

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: Estado verificable, chat y pantalla

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (`resultsForCeo` l.1147, `verifiableState` l.1201)
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java` (`formatTeamExecution` l.962-986)
- Modify: `app/frontend/src/pages/MissionDetailPage.tsx:196-205`
- Test: `app/src/test/java/com/aicompany/core/service/DevelopmentTeamStrategyTest.java`, `ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `DevelopmentTeamStrategy.sandboxSummary(SandboxResult, String)` (ya con prefijos, Task 10).
- Produces: textos fijos: `"Componentes: "`, la advertencia `FULLSTACK_JSON_WARNING`.

- [ ] **Step 1: Tests que fallan**

En `DevelopmentTeamStrategyTest`:

```java
    @Test
    void fullstackVerifiableStateListsComponentsRoutesAndTheJsonWarning() throws Exception {
        // camino feliz fullstack (Task 11) con ambos VERIFY en PASS
        var result = strategy.execute(fullstackContext(), progress);
        assertTrue(result.verifiableState().contains("Componentes: backend/ (DOTNET_APP), app/ (FLUTTER_WEB_APP)"),
                result.verifiableState());
        assertTrue(result.verifiableState().contains("Contrato de rutas: 1 ruta(s)"), result.verifiableState());
        assertTrue(result.verifiableState().contains(DevelopmentTeamStrategy.FULLSTACK_JSON_WARNING), result.verifiableState());
    }
```

En `ChatIntentRouterTest`, siguiendo el patrón de un test existente de "¿cómo va MISSION-X?" con una misión de equipo: agregar una tarea `VALIDATION` con `sandboxResult` = JSON de un `SandboxResult` con pasos `backend/test` (3 PASS) y `app/test` (2 PASS), y comprobar que la respuesta contiene `"backend Tests PASS 3/3"` y `"app Tests PASS 2/2"` y **no** contiene `"esta fase no ejecuta código"`.

- [ ] **Step 2: Correr y ver que fallan**

Run: `cd app && mvn -q test -Dtest='DevelopmentTeamStrategyTest,ChatIntentRouterTest'`
Expected: FAIL.

- [ ] **Step 3: Implementar**

En `DevelopmentTeamStrategy`:

```java
    static final String FULLSTACK_JSON_WARNING = "Los nombres de los campos JSON entre la app y la API no se verifican "
            + "hasta la entrega de ambientes (B2).";

    private static String componentsLine(TeamPlan plan) {
        var components = plan.resolvedComponents();
        if (components.size() < 2) {
            return "Perfil: " + plan.stackProfile();
        }
        return "Componentes: " + components.stream().map(c -> c.dir() + "/ (" + c.profile().name() + ")")
                .collect(Collectors.joining(", "));
    }
```

y en `resultsForCeo` y `verifiableState` reemplazar `"Perfil: " + context.plan().stackProfile()` (o el `.append(" | Perfil: ")...`) por `componentsLine(context.plan())`. En `verifiableState`, si la misión es app + API, agregar después de la línea del sandbox:

```java
        var routesPath = context.plan().resolvedComponents().stream()
                .filter(c -> c.profile() == StackProfile.FLUTTER_WEB_APP && !c.isRoot())
                .map(c -> c.prefix(ApiRoutesGenerator.PATH)).findFirst();
        if (routesPath.isPresent() && context.plan().hasComponent(StackProfile.DOTNET_APP)) {
            var routes = HttpRouteExtractor.extract(backendApiSources(context.missionId(), headShaOf(committed)));
            out.append("Contrato de rutas: ").append(routes.size()).append(" ruta(s) en ").append(routesPath.get())
                    .append("\n").append(FULLSTACK_JSON_WARNING).append("\n");
        }
```

(`headShaOf(committed)` = `committed.get(committed.size() - 1).commitSha()`; `verifiableState` deja de ser `static` si lo era, porque usa `backendApiSources`.)

En `ChatIntentRouter.formatTeamExecution`, reemplazar el bloque de `validationStatus` por:

```java
        tasks.stream()
                .filter(t -> t.validationStatus() != null)
                .findFirst()
                .ifPresent(t -> {
                    out.append(" Validación: ").append(t.validationStatus()).append(".");
                    sandboxOf(t).ifPresent(s -> out.append(" Sandbox: ")
                            .append(DevelopmentTeamStrategy.sandboxSummary(s, null)).append("."));
                });
```

```java
    private static java.util.Optional<com.aicompany.core.model.SandboxResult> sandboxOf(AgentTask task) {
        if (task.sandboxResult() == null || task.sandboxResult().isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(JSON.readValue(task.sandboxResult(), com.aicompany.core.model.SandboxResult.class));
        } catch (Exception ex) {
            return java.util.Optional.empty();
        }
    }
```

(`JSON` es el `JsonMapper` estático que ya existe en la línea ~546; `sandboxSummary` es package-private y `ChatIntentRouter` está en el mismo paquete `service`.)

En `MissionDetailPage.tsx`, línea ~200: reemplazar `step.name === 'test'` por `(step.name === 'test' || step.name.endsWith('/test'))`. Los nombres con prefijo (`backend/build`) se muestran tal cual, agrupados por el orden en que llegan (ya vienen por componente).

- [ ] **Step 4: Correr los tests y el lint**

Run: `cd app && mvn -q test -Dtest='DevelopmentTeamStrategyTest,ChatIntentRouterTest' && cd frontend && npm run lint && npm run build`
Expected: PASS; lint sin errores nuevos (el aviso preexistente de `SettingsPage.tsx:201` puede seguir).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java app/frontend/src/pages/MissionDetailPage.tsx app/src/test/java
git commit -m "Apps completas: componentes y contrato en el estado verificable, el chat y Missions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Despliegue, prueba en vivo y documentación

**Files:**
- Modify: `CLAUDE.md` (sección "Misiones por equipo" y "Sandbox")
- Modify: `docs/HISTORY.md`, `docs/FALTANTES.md`

- [ ] **Step 1: Suites completas**

Run:
```bash
cd /home/alex/Documentos/projectos/empresa/app && mvn -q test && cd ../sandbox-runner && mvn -q test && cd ../app/frontend && npm run lint && npm run build
```
Expected: todo verde.

- [ ] **Step 2: Comprobar que no hay misiones vivas (paso aparte) y redesplegar**

Run: `curl -s localhost:8081/api/company/missions | python3 -c "import json,sys; print([(m['missionId'],m['status']) for m in json.load(sys.stdin) if m['status'] not in ('COMPLETED','CANCELLED','FAILED','AWAITING_INVESTOR')])"`
Expected: `[]`. Si hay alguna, parar y avisar al fundador.

Run: `cd /home/alex/Documentos/projectos/empresa && docker compose build && docker compose up -d && until curl -s localhost:8081/actuator/health | grep -q UP; do sleep 5; done; echo UP`
Expected: `UP` (se reconstruyen `company-core` y `sandbox-runner`).

- [ ] **Step 3: Prueba en vivo — tres apps de dominios distintos**

Lanzar, de a una, por el chat o por la API (entorno TEST, equipo `TEAM-DEVELOPMENT`, base `prueba`), pedidos escritos como los escribiría el fundador y sin pistas técnicas:
1. "Un CRM simple para registrar clientes y sus oportunidades de venta, con pantallas para verlos y cargarlos."
2. "Una app para llevar el inventario de una tienda: productos, existencias y alertas de stock bajo."
3. "Una agenda de reservas para un consultorio: ver horarios libres y reservar."

Para cada una registrar: componentes que eligió Neo, commits por agente, commit `-API-ROUTES` y su número de rutas, pasos `backend/*` y `app/*`, rondas de corrección y estado final. Éxito = `VERIFIED` con los dos componentes en PASS y `API_ROUTES_CONSISTENT` en PASS. Un fallo propio de un dominio se registra como bug del sistema (no se parchea para ese caso) y se corrige en el código general antes de repetir.

- [ ] **Step 4: Regresión de un componente**

Lanzar "Una API .NET para registrar tareas pendientes" (sin app). Éxito = `VERIFIED`, rutas sin prefijo `backend/`, sin commit `-API-ROUTES`, mismos pasos del sandbox que antes de B1.

- [ ] **Step 5: Documentación**

- `docs/HISTORY.md`: sección "Apps completas (B1)" con las decisiones del fundador (spec), los bugs reales encontrados en la prueba y el resultado de cada misión (ids, componentes, rutas, pasos, rondas, estado).
- `CLAUDE.md`: en "Misiones por equipo", un párrafo **Apps completas (B1)** con: `components` del plan y carpetas fijas (`backend/`, `app/`, raíz común), reparto por componente (`RoleLayerCatalog.writesIn`), contrato de rutas (`HttpRouteExtractor` → `app/lib/api/api_routes.dart`, tarea `-API-ROUTES`, `ApiContractGate`, `API_ROUTES_CONSISTENT`), `PORTABILITY`, `http 1.2.2` preaprobado, y en "Sandbox" el `componentDir` y `ComponentSandbox` (≥1 test por componente).
- `docs/FALTANTES.md`: marcar `[x]` "Varios stacks en una misión" con el PR, y el pendiente m-3 ("Stacks disponibles" escrito a mano).

- [ ] **Step 6: Commit y PR**

```bash
git add CLAUDE.md docs/HISTORY.md docs/FALTANTES.md
git commit -m "Docs: apps completas con varios componentes (B1)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push -u origin apps-completas-b1
gh pr create --title "Apps completas: varios componentes en una misión (B1)" --body "$(cat <<'EOF'
## Resumen
- El plan de Neo declara components; Java fija backend/ y app/ y reparte por componente.
- Contrato de rutas generado por Java (api_routes.dart) y gate de rutas a mano.
- Sandbox por componente (componentDir), ≥1 test por componente.
- Portabilidad Flutter (web/Android/iOS) y http 1.2.2 preaprobado.

## Prueba en vivo
Ver docs/HISTORY.md (tres apps de dominios distintos + regresión de un componente).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

(El PR solo con autorización del fundador; si no la dio, dejar la rama empujada y avisar.)
