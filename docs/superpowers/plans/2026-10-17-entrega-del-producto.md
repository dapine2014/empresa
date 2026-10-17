# Plan de implementación: entrega del producto

> **Para agentes:** sub-skill OBLIGATORIA: superpowers:subagent-driven-development (recomendado) o superpowers:executing-plans,
> para implementar este plan tarea por tarea. Los pasos usan casillas (`- [ ]`) para el seguimiento.

**Objetivo:** cada incremento VERIFIED de una misión de app o juego deja `entregas-forja/<missionId>/incremento-N.zip`.
El zip trae los compilados, el código fuente, `ENTREGA.md` armado por Java, Docker en las apps y las migraciones de la
base.

**Arquitectura:**

- **sandbox-runner** suma un job `PACKAGE`. Hace checkout del commit, corre `restore` y un paso `package` con targets fijos,
  y deja la salida en un staging compartido.
- **company-core** (paquete nuevo `com.aicompany.core.delivery`):
  - calcula los targets: perfil + contrato + último incremento + clave de Android;
  - llama al runner y arma el zip con datos reales;
  - guarda un nodo `Delivery`, publica el evento y lo expone en la API, el chat y el Command Center.
- **El momento:** el empaquetado corre dentro de `DevelopmentTeamStrategy` apenas el incremento queda VERIFIED, antes de
  la consolidación del CEO.

**Stack técnico:** Spring Boot 4.1.1 y Java 21 (Jackson 3, `tools.jackson.*`), Neo4j con driver plano, sandbox-runner con
Podman, imágenes del sandbox (Godot 4.3 mono, Flutter, .NET 8) y React + TS en el Command Center.

**Spec:** `docs/superpowers/specs/2026-10-17-entrega-del-producto-design.md`

## Restricciones globales

- **Sin contraseñas en el zip.** Ninguna contraseña de base de datos ni de keystore entra al zip, a `ENTREGA.md`, al
  `.env.example`, a los logs, a los eventos ni a la API.
- **Un empaquetado que falla no cambia la validación.** El incremento sigue VERIFIED y no abre una corrección.
- **`company-core` nunca ejecuta código generado.** Solo el sandbox-runner exporta y compila.
- **El sandbox sigue sin red.** Todo lo que haga falta para exportar se instala al construir la imagen.
- **Los targets son una lista fija.** Nunca llega un comando libre al runner.
- **Determinismo.** `ENTREGA.md`, Docker y `.env.example` salen de plantillas Java fijas con datos reales. No se llama a
  ningún modelo.
- **Convenciones del proyecto:**
  - Jackson 3 (`tools.jackson.*`; las anotaciones siguen en `com.fasterxml.jackson.annotation`);
  - sin `@Async`;
  - eventos `EMPRESA_*` por `CompanyEventPublisher`;
  - en los controllers, `IllegalArgumentException`/`IllegalStateException` → 500, sin manejo fino.
- **Rutas:**
  - carpeta de entregas: `products.deliveries-root` = `${DELIVERIES_ROOT:${user.home}/entregas-forja}`;
  - staging del runner: `${DELIVERIES_ROOT}/.staging/<missionId>/incremento-<N>/`.
- **Nombres fijos de los archivos de juego:**
  - `juego/windows/Juego.exe`
  - `juego/macos/Juego-macos.zip`
  - `juego/linux/Juego.x86_64`
  - `juego/android/Juego-debug.apk`
  - `juego/android/Juego.aab`
- **Targets:** `windows`, `macos`, `linux`, `android-apk`, `android-aab` (juegos), `web` (Flutter), `app` (.NET).
- **Evento de éxito:** `EMPRESA_DELIVERY_PACKAGED` con `{increment, status, targets, path}`.
- **Evento de falla:** `EMPRESA_DELIVERY_FAILED` con `{increment, error}`.
- **Respuestas en español** en todos los textos visibles.

## Decisiones tomadas al planificar (desvíos del spec)

- **R1 — la clave de Android usa una sola contraseña.** Godot 4 firma con `GODOT_ANDROID_KEYSTORE_RELEASE_PASSWORD` como
  contraseña del keystore y de la clave a la vez. El nodo queda como `AndroidSigningKey {alias, keystoreCipher,
  passwordCipher, updatedAt}`.
  - **Costo si me equivoco:** un keystore con dos contraseñas distintas no sirve. Java lo rechaza al cargarlo, con un
    mensaje claro.
- **R2 — dónde vive la clave durante el job.** El spec decía el tmpfs del contenedor, pero el runner no puede escribir ahí
  antes de que el contenedor arranque. Va en `<jobDir>/.forjai/keystore/release.jks` y se borra antes de copiar la salida.
  Las variables con contraseña van por `--env-file`, en un archivo fuera del jobDir que se borra en el `finally`, y nunca
  como `-e` (visible en `ps`).
  - **Costo si me equivoco:** ninguno visible; el job dir completo se borra al terminar.
- **R3 — timeout.** El paso `package` dura 1200 s, así `restore` + `package` caben en el timeout de 30 min del cliente.
- **R4 — generar a pedido.** `POST …/deliveries/{n}` solo genera el incremento cuyo commit VERIFIED está guardado
  (`MissionMemoryService.verifiedCommit`), que es el incremento actual. Los incrementos anteriores no guardaron su commit.
  - **Costo si me equivoco:** no se puede regenerar el zip de un incremento viejo. El zip del último incluye todo.
- **R5 — la verificación humana no empaqueta sola.** `verifyHead` no dispara la entrega; para ese caso está el botón.
- **R6 — `WEB` en un contrato de juego no es un error del validador.** Solo se valida que cada plataforma pertenezca al
  enum. La limitación se explica en `ENTREGA.md`.

## Puntos a revisar (Review Focus)

1. **Falla un target.** Android falla y Windows, macOS y Linux pasan. El zip existe, queda `PARTIAL` y `ENTREGA.md` lista
   el motivo. Prueba en la Task 6 (`DeliveryServiceTest.aFailingTargetGivesAPartialDeliveryWithTheReason`).
2. **Se filtra una contraseña.** Ni el zip, ni el `.env.example`, ni el evento, ni el log del runner contienen la
   contraseña de base ni la del keystore. Pruebas en la Task 1 (`envFileNeverLandsInTheArgs`), la Task 3
   (`envExampleNeverCarriesThePassword`) y la Task 6 (`theEventNeverCarriesSecrets`).
3. **Reinicio a mitad del empaquetado.** No queda un zip a medias: se escribe a `.part` y se renombra. Una entrega en
   `PACKAGING` al arrancar se rehace. Pruebas en la Task 5 (`aFailedAssemblyLeavesNoZip`) y la Task 6
   (`recoveryRepackagesAStuckDelivery`).
4. **El último incremento sin clave de Android.** Lleva solo el `.apk` y la limitación lo dice. Prueba en la Task 2
   (`lastIncrementWithoutKeyHasNoAab`).
5. **Una app con migraciones y sin conexiones cargadas.** El compose levanta `postgres:16` y `LEEME.md` explica cómo
   aplicarlas. Prueba en la Task 3 (`composeAddsPostgresWhenThereAreMigrationsAndNoConnection`).

---

### Task 1: sandbox-runner, job `PACKAGE`

**Archivos:**
- Crear: `sandbox-runner/src/main/java/com/forjai/sandbox/PackageTarget.java`
- Crear: `sandbox-runner/src/main/java/com/forjai/sandbox/AndroidRelease.java`
- Modificar: `sandbox-runner/src/main/java/com/forjai/sandbox/ExecutionProfile.java` (constante `PACKAGE_STEP`)
- Modificar: `sandbox-runner/src/main/java/com/forjai/sandbox/PodmanCommandBuilder.java` (sobrecarga con `envFile`)
- Modificar: `sandbox-runner/src/main/java/com/forjai/sandbox/VerifyJobRunner.java` (método `pack`, constructor con
  `stagingRoot`)
- Modificar: `sandbox-runner/src/main/java/com/forjai/sandbox/JobController.java` (campos nuevos de `JobRequest`, rama
  `PACKAGE`)
- Modificar: `sandbox-runner/src/main/java/com/forjai/sandbox/RunnerConfig.java` y
  `sandbox-runner/src/main/resources/application.yml` (`sandbox.staging-root`)
- Tests: `PackageTargetTest.java`, `VerifyJobRunnerTest.java`, `PodmanCommandBuilderTest.java` y `JobControllerTest.java`

**Interfaces:**
- Produce el JSON de `POST /jobs` con `{"jobType":"PACKAGE","missionId","commitSha","stackProfile","increment":3,
  "targets":["windows","linux"],"androidRelease":{"keystoreBase64","alias","password"}}` (`androidRelease` es opcional).
- Responde un `SandboxResult` con los pasos `checkout`, `restore` y un `package:<target>` por cada target pedido.
- Produce los archivos en `<stagingRoot>/<missionId>/incremento-<N>/` (lo que había en `/work/.forjai/package/`, sin
  `status/` ni `keystore/`).
- Contrato con `run.sh package` (Task 7):
  - recibe `FORJAI_TARGETS` (csv) y, si hay clave, `GODOT_ANDROID_KEYSTORE_RELEASE_PATH`, `_USER` y `_PASSWORD`;
  - por cada target escribe `/work/.forjai/package/status/<target>` con `PASS` o `FAIL: <motivo>`.

- [ ] **Step 1: tests que fallan.** En `PackageTargetTest`:

```java
@Test void parsesOnlyKnownTargetsForTheProfile() {
    assertEquals(Optional.of(PackageTarget.WINDOWS), PackageTarget.parse("windows", ExecutionProfile.GODOT_DOTNET_GAME));
    assertTrue(PackageTarget.parse("web", ExecutionProfile.GODOT_DOTNET_GAME).isEmpty());   // juegos C#: sin web
    assertEquals(Optional.of(PackageTarget.WEB), PackageTarget.parse("web", ExecutionProfile.FLUTTER_WEB_APP));
    assertEquals(Optional.of(PackageTarget.APP), PackageTarget.parse("app", ExecutionProfile.DOTNET_APP));
    assertTrue(PackageTarget.parse("rm -rf /", ExecutionProfile.DOTNET_APP).isEmpty());
}
```

En `PodmanCommandBuilderTest`:

```java
@Test void envFileNeverLandsInTheArgs() {
    var args = new PodmanCommandBuilder("unix:///s", Path.of("/deps"))
            .run(ExecutionProfile.GODOT_DOTNET_GAME, ExecutionProfile.PACKAGE_STEP, Path.of("/w/job"), Path.of("/w/env-1"));
    assertTrue(args.containsAll(List.of("--env-file", "/w/env-1")));
    assertTrue(args.contains("--timeout=1200"));
    assertTrue(args.stream().noneMatch(a -> a.contains("PASSWORD")));
    assertEquals("package", args.get(args.size() - 1));
}
```

En `VerifyJobRunnerTest`, el fake de podman escribe los status y un archivo de salida en `/work`. `workDir` es el `-v`
`<dir>:/work:Z` de los args:

```java
@Test void packCopiesTheOutputAndReportsEachTarget() throws Exception {
    var staging = Files.createTempDirectory("staging");
    var runner = new VerifyJobRunner(products, work, new PodmanCommandBuilder("unix:///s", Path.of("/deps")), packFake(Map.of(
            "windows", "PASS", "android-apk", "FAIL: falta el SDK")), staging);
    var result = runner.pack("M-1", "a".repeat(40), ExecutionProfile.GODOT_DOTNET_GAME, 2,
            List.of(PackageTarget.WINDOWS, PackageTarget.ANDROID_APK), null);
    assertEquals(List.of("checkout", "restore", "package:windows", "package:android-apk"),
            result.steps().stream().map(SandboxResult.StepResult::name).toList());
    assertEquals("PASS", result.steps().get(2).status());
    assertEquals("FAIL", result.steps().get(3).status());
    assertTrue(result.steps().get(3).outputTail().contains("falta el SDK"));
    assertEquals("FAIL", result.overall());
    assertTrue(Files.exists(staging.resolve("M-1/incremento-2/juego/windows/Juego.exe")));
    assertFalse(Files.exists(staging.resolve("M-1/incremento-2/status")));
}

@Test void theReleaseKeyIsWrittenForTheJobAndNeverCopied() throws Exception {
    var staging = Files.createTempDirectory("staging");
    var seen = new java.util.concurrent.atomic.AtomicReference<String>();
    var runner = new VerifyJobRunner(products, work, new PodmanCommandBuilder("unix:///s", Path.of("/deps")), packFake(Map.of(
            "android-aab", "PASS"), (args, workDir) -> seen.set(readEnvFile(args))), staging);
    runner.pack("M-1", "a".repeat(40), ExecutionProfile.GODOT_DOTNET_GAME, 3, List.of(PackageTarget.ANDROID_AAB),
            new AndroidRelease(java.util.Base64.getEncoder().encodeToString("jks".getBytes()), "forjai", "s3cr3t"));
    assertTrue(seen.get().contains("GODOT_ANDROID_KEYSTORE_RELEASE_PATH=/work/.forjai/keystore/release.jks"));
    assertTrue(seen.get().contains("GODOT_ANDROID_KEYSTORE_RELEASE_USER=forjai"));
    assertTrue(seen.get().contains("FORJAI_TARGETS=android-aab"));
    assertFalse(Files.exists(staging.resolve("M-1/incremento-3/keystore")));
    try (var files = Files.list(work)) { assertEquals(0, files.count()); }   // ni job dir ni env file quedan
}
```

`packFake(statusByTarget[, hook])` es un helper del test. Para los comandos `git` y `tar` devuelve 0. En el paso
`restore` también devuelve 0. En el paso `package`:
1. toma el `workDir` del arg `-v <dir>:/work:Z`;
2. corre el hook, si hay, con los args y ese `workDir`;
3. escribe `.forjai/package/status/<t>` para cada entrada del mapa;
4. crea `.forjai/package/juego/windows/Juego.exe`;
5. devuelve 0.

`readEnvFile(args)` lee el archivo que sigue a `--env-file`.

En `JobControllerTest`:

```java
@Test void packageRejectsAnUnknownTarget() {
    var response = controller.run("secreto", new JobController.JobRequest("PACKAGE", "M-1", "a".repeat(40),
            "GODOT_DOTNET_GAME", null, null, 1, List.of("windows", "web"), null));
    assertEquals(400, response.getStatusCode().value());
    verifyNoInteractions(runner);
}
```

- [ ] **Step 2: correr y ver que fallan.** `cd sandbox-runner && mvn test`. Tienen que fallar por compilación: no existen
  `PackageTarget`, `PACKAGE_STEP` ni `pack`.

- [ ] **Step 3: implementar.**

```java
// PackageTarget.java
package com.forjai.sandbox;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/** Entrega del producto (spec 2026-10-17 §1): targets fijos del paso package; nunca un comando libre. */
public enum PackageTarget {
    WINDOWS("windows"), MACOS("macos"), LINUX("linux"), ANDROID_APK("android-apk"), ANDROID_AAB("android-aab"),
    WEB("web"), APP("app");

    private static final Set<PackageTarget> GAME = Set.of(WINDOWS, MACOS, LINUX, ANDROID_APK, ANDROID_AAB);
    private final String id;

    PackageTarget(String id) { this.id = id; }

    public String id() { return id; }

    public boolean allowedFor(ExecutionProfile profile) {
        return switch (profile) {
            case GODOT_DOTNET_GAME -> GAME.contains(this);
            case FLUTTER_WEB_APP -> this == WEB;
            case DOTNET_APP -> this == APP;
        };
    }

    public static Optional<PackageTarget> parse(String id, ExecutionProfile profile) {
        return Arrays.stream(values()).filter(t -> t.id.equals(id) && t.allowedFor(profile)).findFirst();
    }
}
```

```java
// AndroidRelease.java
package com.forjai.sandbox;

/** Clave de publicación de Android (solo en el último incremento). toString nunca expone el keystore ni la contraseña. */
public record AndroidRelease(String keystoreBase64, String alias, String password) {
    @Override public String toString() { return "AndroidRelease[" + alias + "]"; }
}
```

- **`ExecutionProfile`:** `public static final Step PACKAGE_STEP = new Step("package", 1200);`.
- **`PodmanCommandBuilder`:**
  - `run(profile, step, workDir)` delega en `run(profile, step, workDir, null)`.
  - La versión de 4 argumentos, si `envFile != null`, agrega `"--env-file", envFile.toString()` justo antes de la
    imagen. `--timeout` ya sale de `step.timeoutSeconds()`.
- **`VerifyJobRunner`:**
  - El constructor nuevo de 5 argumentos agrega `Path stagingRoot`. El de 4 queda y delega en él con
    `workRoot.resolve(".staging")`, para no romper los tests.
  - `pack(missionId, commitSha, profile, increment, targets, release)` hace, en orden:
    1. crea el jobDir;
    2. hace el `checkout(missionId, commitSha, "", jobDir)` de hoy;
    3. si falla, cada target queda en `SKIPPED`;
    4. si hay `release`, escribe `jobDir/.forjai/keystore/release.jks` con `Base64.getDecoder().decode(...)`;
    5. escribe el env file en `workRoot.resolve("env-" + UUID)`, con permisos `rw-------` si el FS lo soporta. Lleva
       `FORJAI_TARGETS=<ids csv>` y, si hay clave, las tres variables `GODOT_ANDROID_KEYSTORE_RELEASE_*` (la ruta
       `/work/.forjai/keystore/release.jks`, `USER=alias` y `PASSWORD=password`);
    6. corre `restore` con `profile.steps().get(0)`, sin env file; si falla, los targets quedan `SKIPPED`;
    7. corre `package` con `PACKAGE_STEP` y el env file;
    8. lee el estado de cada target en `jobDir/.forjai/package/status/<id>`:
       - archivo `PASS` → `PASS`;
       - `FAIL: x` → `FAIL`, con `x` en `outputTail`;
       - archivo ausente → `FAIL`, con el tail de la salida del paso `package`;
       - paso en timeout → `TIMEOUT` en todos;
    9. borra `.forjai/package/status` y `.forjai/keystore`;
    10. reemplaza `stagingRoot/<missionId>/incremento-<N>` (borra y crea) y copia ahí recursivamente
        `jobDir/.forjai/package`;
    11. en el `finally`, borra el jobDir y el env file.
  - `overall` = `PASS` si todos los pasos pasan; si no, `FAIL`.
  - La salida de cada `package:<t>` lleva `durationMs` del paso completo, `exitCode` del paso y 0 tests.
- **`JobController`:**
  - `JobRequest` suma `Integer increment, List<String> targets, AndroidRelease androidRelease`. Se actualizan los
    `new JobRequest(...)` de los tests existentes, con `null, null, null` al final.
  - La rama `PACKAGE` va antes del chequeo de `VERIFY`.
  - Valida el perfil, que `increment >= 1`, que `targets` no esté vacío y que cada target parsee con `PackageTarget.parse`.
    Si algo falla, responde 400 con `{"error": "target desconocido para <perfil>: <t>"}`.
  - Si pasa, responde `ok(runner.pack(...))`.
- **Config:**
  - `RunnerConfig`: `@Value("${sandbox.staging-root}") String stagingRoot`.
  - `application.yml`: `staging-root: ${SANDBOX_STAGING_ROOT:${user.home}/entregas-forja/.staging}`.

- [ ] **Step 4:** `cd sandbox-runner && mvn test`. Pasan todos los tests.
- [ ] **Step 5: commit.**

```bash
git add sandbox-runner && git commit -m "sandbox-runner: job PACKAGE con targets fijos, clave por env-file y staging de la salida

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: targets de la entrega y `delivery` en el contrato

**Archivos:**
- Modificar: `app/src/main/java/com/aicompany/core/agent/model/ClientContract.java`
- Modificar: `app/src/main/java/com/aicompany/core/agent/validation/ContractValidator.java`
- Modificar: `app/src/main/java/com/aicompany/core/service/ContractWriter.java` (prompt de Aria)
- Crear: `app/src/main/java/com/aicompany/core/delivery/DeliveryTargets.java`
- Tests: `app/src/test/java/com/aicompany/core/delivery/DeliveryTargetsTest.java`, `ContractValidatorTest.java` (el
  existente)

**Interfaces:**
- Produce:
  - `ClientContract.Delivery(List<String> platforms)` con `platformsOrEmpty()`, y `ClientContract.deliveryOrNull()`;
  - `ClientContract.PLATFORMS = List.of("WINDOWS","MACOS","LINUX","ANDROID","WEB","DOCKER")`;
  - `DeliveryTargets.Plan(List<String> targets, List<String> limitations)`;
  - `DeliveryTargets.plan(String stackProfile, ClientContract.Delivery delivery, boolean lastIncrement,
    boolean hasReleaseKey)`.

- [ ] **Step 1: tests que fallan.**

```java
class DeliveryTargetsTest {
    @Test void aGameGetsTheFourPlatformsAndExplainsWeb() {
        var plan = DeliveryTargets.plan("GODOT_DOTNET_GAME", null, false, false);
        assertEquals(List.of("windows", "macos", "linux", "android-apk"), plan.targets());
        assertTrue(plan.limitations().contains(DeliveryTargets.NO_WEB_CSHARP));
        assertTrue(plan.limitations().contains(DeliveryTargets.MACOS_UNSIGNED));
    }
    @Test void lastIncrementWithKeyAddsTheAab() {
        assertEquals(List.of("windows", "macos", "linux", "android-apk", "android-aab"),
                DeliveryTargets.plan("GODOT_DOTNET_GAME", null, true, true).targets());
    }
    @Test void lastIncrementWithoutKeyHasNoAab() {
        var plan = DeliveryTargets.plan("GODOT_DOTNET_GAME", null, true, false);
        assertFalse(plan.targets().contains("android-aab"));
        assertTrue(plan.limitations().contains(DeliveryTargets.NO_AAB_WITHOUT_KEY));
    }
    @Test void theContractNarrowsThePlatforms() {
        var plan = DeliveryTargets.plan("GODOT_DOTNET_GAME",
                new ClientContract.Delivery(List.of("WINDOWS", "WEB")), false, false);
        assertEquals(List.of("windows"), plan.targets());
        assertTrue(plan.limitations().contains(DeliveryTargets.NO_WEB_CSHARP));
    }
    @Test void appsGetTheirBuild() {
        assertEquals(List.of("web"), DeliveryTargets.plan("FLUTTER_WEB_APP", null, true, false).targets());
        assertEquals(List.of("app"), DeliveryTargets.plan("DOTNET_APP", null, true, false).targets());
    }
}
```

En `ContractValidatorTest`, que es el test existente, se agrega un ejemplo con plataformas válidas y otro con
`"PLAYSTATION"`. El error esperado: `delivery.platforms solo admite WINDOWS, MACOS, LINUX, ANDROID, WEB o DOCKER;
recibido: "PLAYSTATION".`

- [ ] **Step 2:** `cd app && mvn test -Dtest='DeliveryTargetsTest,ContractValidatorTest'`. Tienen que fallar.
- [ ] **Step 3: implementar.**
  - **`ClientContract`:**
    - Se suma el campo `Delivery delivery` como último componente del record.
    - Se agrega un constructor de 6 argumentos que delega con `null`, para no tocar los llamadores.
    - Se agrega `public record Delivery(List<String> platforms) { public List<String> platformsOrEmpty() {...} }` y
      `deliveryOrNull()`.
    - En `SCHEMA` se agrega la propiedad opcional `"delivery": {"type":"object","properties":{"platforms":{"type":
      "array","items":{"type":"string","enum": PLATFORMS}}}}`, sin agregarla a `required`.
  - **`ContractValidator`:** valida que cada plataforma esté en `PLATFORMS`, con el mensaje exacto de arriba. `WEB` en un
    juego no es un error (R6).
  - **Prompt de Aria (`ContractWriter.prompt`):**
    - Se agrega una regla: `- delivery (opcional): solo si el cliente pidió plataformas concretas, platforms con
      WINDOWS, MACOS, LINUX, ANDROID, WEB o DOCKER; si no las pidió, omite delivery.`
    - En el JSON de ejemplo va `"delivery": {"platforms": ["..."]}`, marcado como opcional.
  - **`DeliveryTargets`** (final, puro):

```java
package com.aicompany.core.delivery;

import com.aicompany.core.agent.model.ClientContract;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Entrega del producto (spec 2026-10-17 §1, §3): qué targets pide el runner y qué limitaciones dice ENTREGA.md. */
public final class DeliveryTargets {

    public static final String NO_WEB_CSHARP = "Sin versión Web: el juego está en C# y Godot (4.3 y la última estable, "
            + "4.7) no exporta C# a Web.";
    public static final String MACOS_UNSIGNED = "macOS sin firma de Apple: ábrelo con clic derecho → Abrir.";
    public static final String NO_AAB_WITHOUT_KEY = "Sin .aab para Google Play: falta la clave de publicación de Android "
            + "del proyecto (cárgala en la misión → Entregas → Clave de Android y genera la entrega de nuevo).";

    public record Plan(List<String> targets, List<String> limitations) { }

    private DeliveryTargets() { }

    public static Plan plan(String stackProfile, ClientContract.Delivery delivery, boolean lastIncrement,
                            boolean hasReleaseKey) {
        var asked = delivery == null ? List.<String>of() : delivery.platformsOrEmpty().stream()
                .map(p -> p.toUpperCase(Locale.ROOT)).toList();
        var targets = new ArrayList<String>();
        var limits = new ArrayList<String>();
        switch (stackProfile == null ? "" : stackProfile) {
            case "GODOT_DOTNET_GAME" -> {
                if (asked.isEmpty() || asked.contains("WINDOWS")) targets.add("windows");
                if (asked.isEmpty() || asked.contains("MACOS")) { targets.add("macos"); limits.add(MACOS_UNSIGNED); }
                if (asked.isEmpty() || asked.contains("LINUX")) targets.add("linux");
                if (asked.isEmpty() || asked.contains("ANDROID")) {
                    targets.add("android-apk");
                    if (lastIncrement && hasReleaseKey) targets.add("android-aab");
                    else if (lastIncrement) limits.add(NO_AAB_WITHOUT_KEY);
                }
                if (asked.isEmpty() || asked.contains("WEB")) limits.add(0, NO_WEB_CSHARP);
            }
            case "FLUTTER_WEB_APP" -> targets.add("web");
            case "DOTNET_APP" -> targets.add("app");
            default -> limits.add("El perfil " + stackProfile + " no tiene empaquetado.");
        }
        return new Plan(List.copyOf(targets), List.copyOf(limits));
    }
}
```

- [ ] **Step 4:** se corren los dos tests y después `cd app && mvn test` completo. Todo pasa.
- [ ] **Step 5: commit:** `"Entrega: targets por perfil, contrato y último incremento; delivery.platforms en el contrato"`,
  con la línea `Co-Authored-By`.

---

### Task 3: `ENTREGA.md`, Docker y `.env.example` (Java puro)

**Archivos:**
- Crear: `app/src/main/java/com/aicompany/core/delivery/DeliveryFacts.java`
- Crear: `app/src/main/java/com/aicompany/core/delivery/DeliveryDocument.java`
- Crear: `app/src/main/java/com/aicompany/core/delivery/DockerTemplates.java`
- Tests: `DeliveryDocumentTest.java`, `DockerTemplatesTest.java` (en `app/src/test/java/com/aicompany/core/delivery/`)

**Interfaces:**
- Produce:

```java
public record DeliveryFacts(
        String missionId, String productName, String clientSummary, String experience /*APP|GAME|NONE*/,
        String stackProfile, int increment, int totalIncrements, String verifiedAt, String commitSha,
        String repoPath, String zipPath,
        List<Item> contractItems, List<Story> stories, List<Scenario> scenarios,
        List<Control> controls, List<Level> levels, List<String> screens,
        int testsPassed, int testsTotal, int correctionRounds,
        List<TargetResult> targets, List<String> limitations,
        List<Database> databases, List<String> migrations, double costUsd, List<String> agents) {
    public record Item(String id, String kind, String name, String description) { }
    public record Story(String id, int increment, String text) { }
    public record Scenario(String id, String title, int increment, String status /*VERIFICADO|SIN TEST*/) { }
    public record Control(String action, String key, String effect) { }
    public record Level(String item, String name, String parameters, String advanceRule) { }
    public record TargetResult(String name, String status /*PASS|FAIL|TIMEOUT|SKIPPED*/, String reason) { }
    /** Nunca lleva contraseña. */
    public record Database(String name, String environment, String host, int port, String database, String username) { }
}
```

- `DeliveryDocument.render(DeliveryFacts f) -> String` (Markdown).
- `DockerTemplates`:
  - `dockerfile(String stackProfile) -> String`;
  - `compose(String stackProfile, List<DeliveryFacts.Database> dbs, boolean hasMigrations) -> String`;
  - `envExample(List<DeliveryFacts.Database> dbs, boolean ownPostgres) -> String`;
  - `databaseReadme(List<String> migrations, List<DeliveryFacts.Database> dbs, boolean ownPostgres) -> String`;
  - `envName(String connectionName) -> String`, que devuelve `DB_<NOMBRE>` en mayúsculas, con `[^A-Z0-9]` → `_`.

- [ ] **Step 1: tests que fallan.**

```java
class DeliveryDocumentTest {
    static DeliveryFacts game(List<DeliveryFacts.TargetResult> targets, List<String> limits) {
        return new DeliveryFacts("MISSION-PONG", "Pong de 3 niveles", "Pong 2D con tres niveles", "GAME",
                "GODOT_DOTNET_GAME", 3, 3, "2026-10-17T21:52:00Z", "dd0a0e0" + "0".repeat(33),
                "~/forjai-products/MISSION-PONG", "~/entregas-forja/MISSION-PONG/incremento-3.zip",
                List.of(new DeliveryFacts.Item("MOD-01", "LEVEL", "Nivel 1", "pelota normal hasta 50 puntos")),
                List.of(new DeliveryFacts.Story("HU-01", 1, "Como jugador quiero mover la paleta")),
                List.of(new DeliveryFacts.Scenario("ESC-01", "La pelota rebota", 1, "VERIFICADO")),
                List.of(new DeliveryFacts.Control("move_up", "W", "sube la paleta")),
                List.of(new DeliveryFacts.Level("MOD-01", "Nivel 1", "{velocidad=300}", "REGLA-03")), List.of(),
                47, 47, 6, targets, limits, List.of(), List.of(), 15.27, List.of("Iris", "Kael", "Vera"));
    }
    @Test void theGameDocumentHasEveryFixedSection() {
        var md = DeliveryDocument.render(game(List.of(new DeliveryFacts.TargetResult("windows", "PASS", null)),
                List.of(DeliveryTargets.NO_WEB_CSHARP)));
        for (var h : List.of("# Pong de 3 niveles — entrega del incremento 3 de 3", "## 1. Qué es", "## 2. Dónde está cada cosa",
                "## 3. Cómo correrlo", "## 4. Cómo se usa", "## 5. Qué se construyó", "## 6. Cómo se verificó",
                "## 7. Base de datos", "## 8. Limitaciones", "## 9. Costo y equipo")) {
            assertTrue(md.contains(h), h);
        }
        assertTrue(md.contains("juego/windows/Juego.exe"));
        assertTrue(md.contains("| move_up | W | sube la paleta |"));
        assertTrue(md.contains("47 de 47 tests"));
        assertTrue(md.contains("US$ 15.27"));
        assertTrue(md.contains(DeliveryTargets.NO_WEB_CSHARP));
        assertTrue(md.contains("git clone codigo-fuente.bundle"));
        assertTrue(md.contains("Sin base de datos."));
    }
    @Test void aFailedTargetIsListedWithItsReasonAndNotAsRunnable() {
        var md = DeliveryDocument.render(game(List.of(new DeliveryFacts.TargetResult("windows", "PASS", null),
                new DeliveryFacts.TargetResult("android-apk", "FAIL", "falta el SDK")), List.of()));
        assertTrue(md.contains("- android-apk: no se generó (falta el SDK)"));
        assertFalse(md.contains("Juego-debug.apk"));
    }
    @Test void missingDataSaysNotAvailable() {
        var f = game(List.of(), List.of());
        var md = DeliveryDocument.render(new DeliveryFacts(f.missionId(), null, null, "GAME", f.stackProfile(), 1, 0,
                null, f.commitSha(), f.repoPath(), f.zipPath(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), 0, 0, 0, List.of(), List.of(), List.of(), List.of(), 0, List.of()));
        assertTrue(md.contains("no disponible"));
        assertTrue(md.startsWith("# " + f.missionId() + " — entrega del incremento 1"));
    }
}

class DockerTemplatesTest {
    static final DeliveryFacts.Database CRM = new DeliveryFacts.Database("crm-test", "TEST", "db.local", 5432, "crm", "app");
    @Test void envExampleNeverCarriesThePassword() {
        var env = DockerTemplates.envExample(List.of(CRM), false);
        assertTrue(env.contains("DB_CRM_TEST_HOST=db.local"));
        assertTrue(env.contains("DB_CRM_TEST_PASSWORD=\n"));
        assertTrue(env.contains("Forjai → Settings → Bases de datos"));
    }
    @Test void composeAddsPostgresWhenThereAreMigrationsAndNoConnection() {
        var compose = DockerTemplates.compose("DOTNET_APP", List.of(), true);
        assertTrue(compose.contains("image: postgres:16"));
        assertTrue(DockerTemplates.envExample(List.of(), true).contains("cámbiala"));
        assertTrue(DockerTemplates.databaseReadme(List.of("V1__crear_clientes.sql"), List.of(), true)
                .contains("V1__crear_clientes.sql"));
    }
    @Test void dockerfilesCopyTheBuiltApp() {
        assertTrue(DockerTemplates.dockerfile("FLUTTER_WEB_APP").contains("FROM nginx:"));
        assertTrue(DockerTemplates.dockerfile("FLUTTER_WEB_APP").contains("COPY app/ /usr/share/nginx/html/"));
        assertTrue(DockerTemplates.dockerfile("DOTNET_APP").contains("FROM mcr.microsoft.com/dotnet/aspnet:8.0"));
    }
}
```

- [ ] **Step 2:** `mvn test -Dtest='DeliveryDocumentTest,DockerTemplatesTest'`. Tienen que fallar.
- [ ] **Step 3: implementar.**
  - **`DeliveryDocument`.** Arma el Markdown con un `StringBuilder`, en este orden:
    1. **Título:** `# <productName o missionId> — entrega del incremento N de M`. Si `totalIncrements` es 0, va sin
       «de M».
    2. **Qué es:** cliente, tipo, stack y fecha del VERIFIED.
    3. **Dónde está cada cosa:**
       - la ruta del zip, el repositorio y el commit;
       - un árbol del zip, armado con los targets en PASS (`juego/windows/Juego.exe`, `juego/macos/Juego-macos.zip`,
         `juego/linux/Juego.x86_64`, `juego/android/Juego-debug.apk`, `juego/android/Juego.aab`, `app/`, `docker/`,
         `base-de-datos/`), más `codigo-fuente/` y `codigo-fuente.bundle`;
       - la línea `Historial completo: git clone codigo-fuente.bundle proyecto`.
    4. **Cómo correrlo:**
       - un bloque fijo por target en PASS: Windows doble clic; macOS descomprimir y clic derecho → Abrir; Linux
         `chmod +x Juego.x86_64 && ./Juego.x86_64`; Android con orígenes desconocidos y `adb install Juego-debug.apk`;
         `.aab` a Google Play Console;
       - para apps: `cd docker && cp .env.example .env && docker compose up -d` y `http://localhost:8080`;
       - los targets que no están en PASS van como `- <target>: no se generó (<reason>)`.
    5. **Cómo se usa:**
       - juegos: tabla `| Acción | Tecla | Efecto |` y lista de niveles;
       - apps: lista de pantallas;
       - sin datos: `no disponible`.
    6. **Qué se construyó:** ítems del contrato, HU con su incremento y escenarios con su estado.
    7. **Cómo se verificó:** `<passed> de <total> tests`, escenarios verificados sobre el total, `<n> rondas de
       corrección` y el commit.
    8. **Base de datos:**
       - si hay migraciones o bases: las migraciones listadas, cada base sin contraseña, y la línea «La contraseña está
         en Forjai → Settings → Bases de datos.»;
       - si no hay ninguna: `Sin base de datos.`
    9. **Limitaciones:** las `limitations`, o «Ninguna.».
    10. **Costo y equipo:** `US$ %.2f` con `Locale.ROOT` y los agentes.
    - Cada valor null o vacío → `no disponible`.
  - **`DockerTemplates`.** Plantillas en text blocks:
    - Flutter: `FROM nginx:1.27-alpine`, `COPY app/ /usr/share/nginx/html/`, `EXPOSE 80`.
    - .NET: `FROM mcr.microsoft.com/dotnet/aspnet:8.0`, `WORKDIR /app`, `COPY app/ .`, `ENV ASPNETCORE_URLS=http://+:8080`,
      `EXPOSE 8080` y `ENTRYPOINT ["sh","-c","dotnet $(ls *.dll | head -1)"]`. El ENTRYPOINT es determinista: el
      packager deja una sola dll de entrada.
    - Compose:
      - servicio `app` con `build: {context: .., dockerfile: docker/Dockerfile}`, `env_file: .env` y puerto
        `8080:80` (Flutter) o `8080:8080` (.NET);
      - si `hasMigrations && dbs.isEmpty()`, servicio `db` con `postgres:16`, `POSTGRES_PASSWORD: ${DB_APP_PASSWORD}` y
        un volumen.
    - `envExample`:
      - por base: `DB_<N>_HOST`, `_PORT`, `_NAME`, `_USER` y `_PASSWORD=`, vacío;
      - comentario `# La contraseña está en Forjai → Settings → Bases de datos.`;
      - con `ownPostgres`: `DB_APP_*` apuntando a `db`, con `DB_APP_PASSWORD=cambia-esta-clave   # cámbiala`.
- [ ] **Step 4:** se corren los tests de la tarea y después `mvn test` completo. Todo pasa.
- [ ] **Step 5: commit:** `"Entrega: ENTREGA.md, Docker y .env.example armados por Java"` + `Co-Authored-By`.

---

### Task 4: clave de Android y llamada `PACKAGE` desde `company-core`

**Archivos:**
- Crear: `app/src/main/java/com/aicompany/core/delivery/AndroidSigningKeyMemoryService.java`
- Crear: `app/src/main/java/com/aicompany/core/delivery/AndroidSigningKeyService.java`
- Modificar: `app/src/main/java/com/aicompany/core/service/SandboxRunnerClient.java` (método `packageJob`)
- Modificar: `app/src/main/java/com/aicompany/core/service/MissionMemoryService.java` (el borrado de la misión suma
  `HAS_SIGNING_KEY|HAS_DELIVERY` a la lista de relaciones de la línea ~508)
- Tests: `AndroidSigningKeyServiceTest.java`, `SandboxRunnerClientTest.java` (el existente, o uno nuevo si no hay)

**Interfaces:**
- Produce:
  - `AndroidSigningKeyService.Status(boolean configured, String alias, String updatedAt)`;
  - `Status status(String missionId)`;
  - `Status save(String missionId, String keystoreBase64, String alias, String password)`;
  - `void delete(String missionId)`;
  - `Optional<Plain> plain(String missionId)`, con
    `record Plain(String keystoreBase64, String alias, String password) { toString sin secretos }`;
  - `SandboxRunnerClient.packageJob(String missionId, String commitSha, String stackProfile, int increment,
    List<String> targets, AndroidSigningKeyService.Plain release) -> Optional<SandboxResult>`. Nunca lanza;
    `lastError()` igual que hoy.
- Consume: `SecretCipher.encrypt/decrypt/available`.

- [ ] **Step 1: tests que fallan** (memoria mockeada y `SecretCipher` real, con una key de 32 bytes en base64):

```java
@Test void aValidKeystoreIsStoredEncryptedAndOnlyTheAliasComesBack() throws Exception {
    var ks = java.security.KeyStore.getInstance("PKCS12");
    ks.load(null, null);
    // keystore con un certificado autofirmado generado en el test con keytool (ProcessBuilder) en @TempDir
    var b64 = generateKeystore("forjai", "clave123");
    var status = service.save("M-1", b64, "forjai", "clave123");
    assertTrue(status.configured());
    assertEquals("forjai", status.alias());
    verify(memory).save(eq("M-1"), eq("forjai"), argThat(c -> !c.contains("clave123")), argThat(c -> !c.equals(b64)), any());
}
@Test void aWrongPasswordOrAliasIsRejected() {
    var b64 = generateKeystore("forjai", "clave123");
    assertThrows(IllegalArgumentException.class, () -> service.save("M-1", b64, "forjai", "otra"));
    assertThrows(IllegalArgumentException.class, () -> service.save("M-1", b64, "otro", "clave123"));
}
@Test void withoutTheSecretsKeyNothingIsSaved() { /* SecretCipher sin key → IllegalStateException con mensaje FORJAI_SECRETS_KEY */ }
```

`generateKeystore` corre `keytool -genkeypair -keystore <tmp>/k.p12 -storetype PKCS12 -alias <a> -storepass <p>
-keypass <p> -dname CN=Forjai -keyalg RSA -validity 10000` y devuelve el archivo en base64. Si no hay `keytool`, el test
usa `Assumptions.assumeTrue`.

Para `SandboxRunnerClient` se usa un `MockRestServiceServer`:
- espera un POST a `/jobs` con `jsonPath("$.jobType").value("PACKAGE")`, `$.targets[0]` = `windows` y
  `$.androidRelease.alias` = `forjai`, y responde un `SandboxResult`;
- con el server respondiendo 500 → `Optional.empty()` y `lastError()` no null.

- [ ] **Step 2:** correr los tests y ver que fallan.
- [ ] **Step 3: implementar.**
  - **`AndroidSigningKeyMemoryService`** (Cypher a mano):
    - `save(missionId, alias, passwordCipher, keystoreCipher, updatedAt)` →
      `MATCH (m:Mission {id:$id}) MERGE (m)-[:HAS_SIGNING_KEY]->(k:AndroidSigningKey) SET k.alias=$alias,
      k.passwordCipher=$p, k.keystoreCipher=$ks, k.updatedAt=$at`;
    - `find(missionId) -> Optional<Stored(alias, passwordCipher, keystoreCipher, updatedAt)>`;
    - `delete(missionId)` → `DETACH DELETE` del nodo.
  - **`AndroidSigningKeyService.save`:**
    1. exige `cipher.available()`; si no, `IllegalStateException("Falta FORJAI_SECRETS_KEY en .env: sin ella no se
       guardan claves.")`;
    2. decodifica el base64; si falla, `IllegalArgumentException("El keystore no es base64 válido.")`;
    3. abre el keystore con `KeyStore.getInstance("PKCS12")`; si falla, prueba `"JKS"`;
    4. si no abre o no contiene el alias, `IllegalArgumentException("El keystore no abre con esa contraseña o no tiene
       el alias <a> (Godot usa la misma contraseña para el keystore y la clave).")`;
    5. cifra ambos valores y guarda.
  - **`status`:** nunca descifra.
  - **`plain`:** descifra. Solo lo usa `DeliveryService`.
  - **`SandboxRunnerClient.packageJob`:** copia de `verify`, con cancelación incluida. El body suma
    `jobType=PACKAGE, increment, targets` y, si `release != null`, `androidRelease` con keystoreBase64, alias y password.
    El log de error nunca incluye el body.
  - **`MissionMemoryService`:** suma `HAS_SIGNING_KEY|HAS_DELIVERY` al patrón del borrado.
- [ ] **Step 4:** `mvn test` completo. Todo pasa.
- [ ] **Step 5: commit:** `"Entrega: clave de Android cifrada por misión y job PACKAGE en el cliente del sandbox"` +
  `Co-Authored-By`.

---

### Task 5: armado del zip y nodo `Delivery`

**Archivos:**
- Crear: `app/src/main/java/com/aicompany/core/delivery/DeliveryRecord.java`
- Crear: `app/src/main/java/com/aicompany/core/delivery/DeliveryMemoryService.java`
- Crear: `app/src/main/java/com/aicompany/core/delivery/DeliveryPackager.java`
- Modificar: `app/src/main/java/com/aicompany/core/service/CompanyMemoryService.java` (constraint `delivery_id` junto a
  `mission_event_id`, línea ~61)
- Modificar: `app/src/main/resources/application.yml` (`products.deliveries-root`)
- Tests: `DeliveryPackagerTest.java`

**Interfaces:**
- Produce:

```java
public record DeliveryRecord(String missionId, int increment, String status /*PACKAGING|READY|PARTIAL|FAILED*/,
                             String path, long sizeBytes, List<DeliveryFacts.TargetResult> targets, String error,
                             String createdAt) { }
```

- `DeliveryMemoryService`:
  - `markPackaging(String missionId, int increment)`;
  - `save(DeliveryRecord r)`, con `MERGE` por `id = missionId + "-INC-" + increment`;
  - `List<DeliveryRecord> list(String missionId)`, de mayor a menor incremento;
  - `Optional<DeliveryRecord> find(String missionId, int increment)`;
  - `List<DeliveryRecord> packaging()`: todas las entregas en `PACKAGING`.
  - `List<DeliveryRecord> recent(int limit)`: las últimas de todas las misiones, por `createdAt` descendente.
  - Los targets se guardan como `targetsJson` con Jackson 3.
- `DeliveryPackager` (constructor `(Path deliveriesRoot, GitCommandRunner git, Path productsRoot)`):
  - `Path assemble(Assembly a) throws IOException`, con
    `record Assembly(String missionId, int increment, String commitSha, String stackProfile, Path staging, String entregaMd,
    Map<String,String> extraFiles /*ruta en el zip → contenido*/, Map<String,String> migrations /*nombre → sql*/)`;
  - `Path stagingDir(String missionId, int increment)` → `deliveriesRoot/.staging/<missionId>/incremento-<N>`;
  - `Path zipPath(String missionId, int increment)` → `deliveriesRoot/<missionId>/incremento-<N>.zip`.

- [ ] **Step 1: tests que fallan.** Usan un repo git real en `@TempDir`, creado con el `GitCommandRunner` real: `init`, un
  archivo y un commit.

```java
@Test void theZipHasSourceBundleBuildsDocsAndMigrations() throws Exception {
    var staging = packager.stagingDir("M-1", 2);
    Files.createDirectories(staging.resolve("juego/linux"));
    Files.writeString(staging.resolve("juego/linux/Juego.x86_64"), "bin");
    var zip = packager.assemble(new DeliveryPackager.Assembly("M-1", 2, sha, "GODOT_DOTNET_GAME", staging, "# doc",
            Map.of(), Map.of("V1__crear.sql", "create table t(id int);")));
    assertEquals(packager.zipPath("M-1", 2), zip);
    var names = zipEntries(zip);
    assertTrue(names.containsAll(List.of("ENTREGA.md", "juego/linux/Juego.x86_64", "codigo-fuente/README.md",
            "codigo-fuente.bundle", "base-de-datos/migraciones/V1__crear.sql")));
    assertFalse(Files.exists(staging));                       // el staging se borra
    assertFalse(Files.exists(zip.resolveSibling("incremento-2.zip.part")));
}
@Test void aFailedAssemblyLeavesNoZip() throws Exception {
    assertThrows(IOException.class, () -> packager.assemble(new DeliveryPackager.Assembly("M-1", 1, "f".repeat(40),
            "GODOT_DOTNET_GAME", packager.stagingDir("M-1", 1), "# doc", Map.of(), Map.of())));   // commit inexistente
    assertFalse(Files.exists(packager.zipPath("M-1", 1)));
    try (var s = Files.list(packager.zipPath("M-1", 1).getParent())) { assertEquals(0, s.count()); }
}
@Test void regeneratingOverwritesTheZip() throws Exception { /* assemble dos veces → un solo zip, con el contenido nuevo */ }
@Test void aMissionIdWithPathTraversalIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> packager.zipPath("../x", 1));
}
```

- [ ] **Step 2:** correr los tests y ver que fallan.
- [ ] **Step 3: implementar `assemble`:**
  1. Valida `missionId` contra `^[A-Z0-9-]+$` y `commitSha` contra `[0-9a-f]{40}`; si no, `IllegalArgumentException`.
  2. Crea `deliveriesRoot/<missionId>/`.
  3. Crea un temporal `deliveriesRoot/.staging/<missionId>/src-<N>`.
  4. `git -C <repo> archive --format=tar -o <tmp>/src.tar <sha>` y lo extrae con `java.util` (sin tar externo): se lee
     el tar con un lector mínimo propio de ustar, o se usa `git archive --format=zip` y se copian sus entradas con prefijo
     `codigo-fuente/`. Se prefiere `--format=zip`.
  5. `git -C <repo> bundle create <tmp>/codigo-fuente.bundle --all`.
  6. Escribe `<zip>.part` con `ZipOutputStream`, en este orden:
     - `ENTREGA.md`;
     - las entradas del zip de git, con prefijo `codigo-fuente/`;
     - `codigo-fuente.bundle`;
     - todo `staging/**`, con su ruta relativa y respetando el bit ejecutable; ZIP no guarda permisos POSIX con
       `java.util.zip`, así que el `.x86_64` se documenta con `chmod +x`, que ya está en `ENTREGA.md`;
     - cada `extraFiles` (por ejemplo `docker/Dockerfile` y `base-de-datos/LEEME.md`);
     - cada migración, en `base-de-datos/migraciones/<nombre>`.
  7. `Files.move(part, zip, REPLACE_EXISTING, ATOMIC_MOVE)`.
  8. Borra el staging y el temporal.
  9. Ante cualquier `IOException`, borra el `.part` y el temporal y relanza.

  En `application.yml`, debajo de `workspace-root`: `deliveries-root: ${DELIVERIES_ROOT:${user.home}/entregas-forja}`.

  En `CompanyMemoryService`, junto a `mission_event_id`: `CREATE CONSTRAINT delivery_id IF NOT EXISTS FOR (d:Delivery)
  REQUIRE d.id IS UNIQUE`.
- [ ] **Step 4:** `mvn test` completo. Todo pasa.
- [ ] **Step 5: commit:** `"Entrega: armado atómico del zip (código, bundle, compilados, docs, migraciones) y nodo
  Delivery"` + `Co-Authored-By`.

---

### Task 6: `DeliveryService`, hook al quedar VERIFIED, recuperación y eventos

**Archivos:**
- Crear: `app/src/main/java/com/aicompany/core/delivery/DeliveryService.java`
- Crear: `app/src/main/java/com/aicompany/core/delivery/DeliveryFactsBuilder.java`, que lee spec, contrato, diseño, tests,
  bases y costo, y arma `DeliveryFacts`
- Modificar: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java`:
  - setter `setDeliveryService`, igual que `setDatabaseApply`;
  - llamada después de `memory.recordVerifiedCommit` en `execute` (línea ~596);
  - texto `Entrega:` en `verifiableState`.
- Modificar: `app/src/main/java/com/aicompany/core/recovery/MissionRecoveryService.java` (al arrancar, rehace las
  entregas en `PACKAGING`)
- Modificar: `app/src/main/java/com/aicompany/core/event/MissionEventText.java` (plantillas de los dos eventos)
- Modificar: `docs/EVENTS.md`
- Tests: `DeliveryServiceTest.java`, `MissionEventTextTest.java` (el existente) y `DevelopmentTeamStrategyTest` (un caso)

**Interfaces:**
- Consume: lo de las Tasks 2 a 5; `SandboxRunnerClient.packageJob`; `RequirementsMemoryService.approved/currentIncrement`;
  `ContractMemoryService.approved`; `InteractionDesignMemoryService.approved` (lo que usa `approvedGameScenes`);
  `DatabaseConnectionService.connectionsOf`; `ModelCostService.missionCost`; `DevelopmentWorkspaceService.filesAtCommit/
  readFileAtCommit`; `MissionMemoryService.verifiedCommit`.
- Produce:
  - `DeliveryRecord deliver(String missionId, int increment, String commitSha, String stackProfile, int correctionRounds,
    SandboxResult verifiedRun)`. Nunca lanza y siempre devuelve un record: `FAILED` con `error` si algo se rompe.
  - `DeliveryRecord deliverVerified(String missionId)`, para el botón y la recuperación. Usa `verifiedCommit`,
    `currentIncrement` y el `stackProfile` del último plan (`lastTeamPlanJson`). Sin commit verificado lanza
    `IllegalStateException("MISSION-X no tiene un incremento VERIFIED para entregar.")`.
  - `List<DeliveryRecord> list(String missionId)` y `List<DeliveryRecord> recent(int limit)`.
  - `void resumeStuck()`, que se llama desde la recuperación.

- [ ] **Step 1: tests que fallan** (todo mockeado salvo `DeliveryTargets`, `DeliveryDocument` y `DockerTemplates`):

```java
@Test void aFailingTargetGivesAPartialDeliveryWithTheReason() {
    when(runner.packageJob(any(), any(), any(), anyInt(), any(), any())).thenReturn(Optional.of(new SandboxResult("FAIL",
            List.of(step("checkout", "PASS", ""), step("restore", "PASS", ""), step("package:windows", "PASS", ""),
                    step("package:android-apk", "FAIL", "falta el SDK")))));
    var r = service.deliver("M-1", 2, SHA, "GODOT_DOTNET_GAME", 3, verifiedRun(47, 47));
    assertEquals("PARTIAL", r.status());
    verify(packager).assemble(argThat(a -> a.entregaMd().contains("- android-apk: no se generó (falta el SDK)")));
    verify(events).publish(eq("EMPRESA_DELIVERY_PACKAGED"), eq("M-1"), isNull(), eq("delivery"),
            argThat(m -> "PARTIAL".equals(m.get("status")) && Integer.valueOf(2).equals(m.get("increment"))));
}
@Test void theRunnerDownGivesAFailedDeliveryAndNeverThrows() {
    when(runner.packageJob(any(), any(), any(), anyInt(), any(), any())).thenReturn(Optional.empty());
    when(runner.lastError()).thenReturn("sandbox-runner no disponible: Connection refused");
    var r = service.deliver("M-1", 1, SHA, "GODOT_DOTNET_GAME", 0, verifiedRun(10, 10));
    assertEquals("FAILED", r.status());
    assertTrue(r.error().contains("Connection refused"));
    verify(events).publish(eq("EMPRESA_DELIVERY_FAILED"), eq("M-1"), isNull(), eq("delivery"), anyMap());
}
@Test void theEventNeverCarriesSecrets() {
    when(keys.plain("M-1")).thenReturn(Optional.of(new AndroidSigningKeyService.Plain("S0VZ", "forjai", "clave123")));
    // último incremento con clave
    service.deliver("M-1", 3, SHA, "GODOT_DOTNET_GAME", 0, verifiedRun(5, 5));
    verify(events).publish(anyString(), any(), any(), any(), argThat(m -> !m.toString().contains("clave123")
            && !m.toString().contains("S0VZ")));
    verify(runner).packageJob(any(), any(), any(), eq(3), argThat(t -> t.contains("android-aab")), notNull());
}
@Test void recoveryRepackagesAStuckDelivery() {
    when(deliveries.packaging()).thenReturn(List.of(new DeliveryRecord("M-1", 2, "PACKAGING", null, 0, List.of(), null, "t")));
    // verifiedCommit, currentIncrement=2 y el plan con stackProfile mockeados
    service.resumeStuck();
    verify(runner).packageJob(eq("M-1"), eq(SHA), eq("GODOT_DOTNET_GAME"), eq(2), any(), any());
}
```

En `MissionEventTextTest`, dos casos:
- `EMPRESA_DELIVERY_PACKAGED` `{increment:3, status:"PARTIAL", path:"~/entregas-forja/M/incremento-3.zip"}` →
  `Entrega del incremento 3 (PARTIAL): ~/entregas-forja/M/incremento-3.zip`;
- `EMPRESA_DELIVERY_FAILED` → `Entrega del incremento 3 falló: <error>`.

En `DevelopmentTeamStrategyTest`, un caso: con un `DeliveryService` mockeado y el estado VERIFIED, `deliver` se llama una
vez con el `headSha`, y el estado verificable contiene `Entrega: ` con la ruta.

- [ ] **Step 2:** correr los tests y ver que fallan.
- [ ] **Step 3: implementar.**
  - **`deliver`:**
    1. `deliveries.markPackaging`.
    2. `last` = `increment >= max(spec.incrementNumbers())`.
    3. `plan = DeliveryTargets.plan(stackProfile, contract.deliveryOrNull(), last, keys.status(m).configured())`.
    4. `release = last && plan.targets().contains("android-aab") ? keys.plain(m).orElse(null) : null`.
    5. `runner.packageJob(...)`.
    6. Los resultados por target salen de los steps `package:<t>`, con el motivo = `outputTail` recortado a 300
       caracteres y una línea.
    7. Arma los facts con `DeliveryFactsBuilder`: tests de `verifiedRun` (paso `test`), migraciones de
       `filesAtCommit` bajo `db/postgres/migrations/` y bases de `connectionsOf`, sin contraseña.
    8. `md = DeliveryDocument.render(facts)`.
    9. En apps, los `extraFiles` son `docker/Dockerfile`, `docker/docker-compose.yml` y `docker/.env.example`. Si hay
       migraciones o bases, también `docker/.env.example` (en juegos) y `base-de-datos/LEEME.md`.
    10. `packager.assemble(...)`.
    11. El estado sale de: todos PASS → `READY`; alguno PASS → `PARTIAL`; ninguno PASS → `FAILED`. Si es `FAILED`, igual
        se arma un zip con el código y la documentación, y `error` = «no se generó ningún compilado».
    12. Guarda y publica `EMPRESA_DELIVERY_PACKAGED` `{increment, status, targets: [ids], path}`.
    - Cualquier excepción → guarda `FAILED` con `error`, publica `EMPRESA_DELIVERY_FAILED` `{increment, error}` y
      loguea `DELIVERY_FAILED mission=… reason=…`.
  - **Hook en `DevelopmentTeamStrategy.execute`:** después de `memory.recordVerifiedCommit(missionId, headSha)`, si
    `deliveryService != null`:
    1. `progress.advance(MissionStatus.EVALUATING, 90, "Entrega", "Empaquetando el incremento " + inc + " para entregar.")`;
    2. `var delivery = deliveryService.deliver(missionId, inc, headSha, plan.stackProfile(), repairRounds, sandboxResult)`;
    3. `deliveryText = delivery.status() + " — " + (delivery.path() == null ? delivery.error() : delivery.path())`.

    El texto se agrega al estado verificable como `"\nEntrega: " + deliveryText`, igual que `databaseText`, así lo lee
    la consolidación del CEO.
  - **`MissionRecoveryService`:** al final de la recuperación de arranque, llama a `deliveryService.resumeStuck()`,
    envuelto en try/catch y con el bean opcional (`@Autowired(required=false)`).
  - **`docs/EVENTS.md`:** se documentan los dos eventos con su `data`.
- [ ] **Step 4:** `mvn test` completo. Todo pasa.
- [ ] **Step 5: commit:** `"Entrega: empaquetado al quedar VERIFIED, recuperación y eventos en la bitácora"` +
  `Co-Authored-By`.

---

### Task 7: imágenes del sandbox (`run.sh package`) y verificación en vivo del runner

**Archivos:**
- Modificar: `sandbox/images/godot-dotnet-game/Dockerfile`:
  - export templates de Godot 4.3 mono;
  - OpenJDK 17 y Android SDK (cmdline-tools, `platform-tools`, `build-tools;34.0.0`, `platforms;android-34`);
  - keystore de depuración;
  - runtime packs de NuGet para `win-x64`, `osx-x64`, `osx-arm64`, `linux-x64` y `android-arm64` en el feed offline.
- Crear: `sandbox/images/godot-dotnet-game/export_presets.cfg` (presets fijos: `Windows`, `macOS`, `Linux`, `AndroidApk`,
  `AndroidAab`)
- Modificar: `sandbox/images/godot-dotnet-game/run.sh` (caso `package`)
- Modificar: `sandbox/images/flutter-web-app/run.sh` y `sandbox/images/dotnet-app/run.sh` (caso `package`)
- Modificar: `sandbox/build-images.sh` (tag `godot-dotnet-game:3`)
- Modificar: `sandbox-runner/src/main/java/com/forjai/sandbox/ExecutionProfile.java` (`:3`) y su test

**Interfaces:**
- Consume el contrato de la Task 1: `FORJAI_TARGETS`, las variables `GODOT_ANDROID_KEYSTORE_RELEASE_*`, la salida en
  `/work/.forjai/package/` y `status/<t>` con `PASS` o `FAIL: <motivo>`.

- [ ] **Step 1: `run.sh` de juegos, caso `package`.** Un target que falla escribe su `FAIL` y sigue con el siguiente.

```bash
  package)
    OUT=/work/.forjai/package; mkdir -p "$OUT/status"
    status() { echo "$2" > "$OUT/status/$1"; }
    # Godot lee templates y editor settings de $HOME (tmpfs): enlaces a lo que trae la imagen.
    mkdir -p "$HOME/.local/share/godot/export_templates" "$HOME/.config/godot"
    ln -sfn /opt/godot-templates/4.3.stable.mono "$HOME/.local/share/godot/export_templates/4.3.stable.mono"
    cp /forjai/editor_settings-4.3.tres "$HOME/.config/godot/editor_settings-4.3.tres"
    cp /forjai/export_presets.cfg /work/game/export_presets.cfg
    make_sln; dotnet build "$SLN" --no-restore -c Release > /tmp/release.log 2>&1 || true
    IFS=',' read -ra TS <<< "${FORJAI_TARGETS:-}"
    for t in "${TS[@]}"; do
      case "$t" in
        windows)     dir=juego/windows; file=Juego.exe;         preset=Windows ;;
        macos)       dir=juego/macos;   file=Juego-macos.zip;   preset=macOS ;;
        linux)       dir=juego/linux;   file=Juego.x86_64;      preset=Linux ;;
        android-apk) dir=juego/android; file=Juego-debug.apk;   preset=AndroidApk ;;
        android-aab) dir=juego/android; file=Juego.aab;         preset=AndroidAab ;;
        *) status "$t" "FAIL: target desconocido"; continue ;;
      esac
      mkdir -p "$OUT/$dir"
      mode=--export-release; [ "$t" = android-apk ] && mode=--export-debug
      if timeout 600 godot --headless --path /work/game $mode "$preset" "$OUT/$dir/$file" > "/tmp/export-$t.log" 2>&1 \
         && [ -s "$OUT/$dir/$file" ]; then status "$t" PASS
      else status "$t" "FAIL: $(grep -iE 'error|failed' "/tmp/export-$t.log" | tail -3 | tr '\n' ' ' | cut -c1-280)"; fi
    done ;;
```

`editor_settings-4.3.tres` trae fijos `export/android/android_sdk_path`, `export/android/java_sdk_path`,
`export/android/debug_keystore`, `debug_keystore_user=androiddebugkey` y `debug_keystore_pass=android`. Se crea en la
imagen.

- [ ] **Step 2: Flutter y .NET, caso `package`.**
  - **Flutter:** `flutter build web --release --offline`, con un fallback sin `--offline` porque la red igual está
    cortada. Si pasa, `cp -r build/web "$OUT/web"` y `status web PASS`; si no, `FAIL` con el tail del log.
  - **.NET:** busca el `.csproj` ejecutable. Primero el que tenga `Sdk="Microsoft.NET.Sdk.Web"`; si no hay, el único con
    `<OutputType>Exe</OutputType>`. Si no encuentra ninguno, `FAIL: no hay proyecto ejecutable`. Si lo encuentra:
    `dotnet publish <csproj> -c Release --no-restore -o "$OUT/app"`.
- [ ] **Step 3: Dockerfile de juegos.** Todo con red, al construir la imagen:
  1. Descarga e instala las templates en `/opt/godot-templates/4.3.stable.mono` desde
     `Godot_v4.3-stable_mono_export_templates.tpz`, que es un zip con la carpeta `templates/`.
  2. `apt-get install -y openjdk-17-jdk-headless`.
  3. Instala las Android cmdline-tools en `/opt/android-sdk` y corre `sdkmanager --licenses` y la instalación de los
     paquetes.
  4. `keytool -genkeypair` del debug keystore en `/opt/android/debug.keystore` (alias `androiddebugkey`, pass `android`).
  5. Siembra los runtime packs: un proyecto dummy en `/opt/seed` con `dotnet publish -r <rid> --self-contained` para cada
     RID, que deja los paquetes en el feed offline existente (ver cómo se siembra hoy, después de la línea 9).
  6. Copia `export_presets.cfg` y `editor_settings-4.3.tres` a `/forjai/`.
- [ ] **Step 4: construir y verificar en vivo.** Es la única forma de probarlo: no hay tests unitarios de imágenes.
  1. `bash sandbox/build-images.sh` y anotar el tamaño de la imagen nueva.
  2. Redeploy solo de `sandbox-runner` (`docker compose build sandbox-runner && docker compose up -d sandbox-runner`).
     Sirve únicamente si no hay misiones en sandbox en ese momento: el controlador lo consulta antes.
  3. Llamada manual con el token de `.env`, sin mostrarlo: `curl -s -X POST localhost:8090/jobs -H "X-Sandbox-Token:
     $TOKEN" -d '{"jobType":"PACKAGE","missionId":"MISSION-PONG-NIVELES-1792199290","commitSha":"<dd0a0e0 completo>",
     "stackProfile":"GODOT_DOTNET_GAME","increment":3,"targets":["windows","macos","linux","android-apk"]}'`.
  4. Ver el resultado y los archivos en `~/entregas-forja/.staging/…`.
  5. Correr el `Juego.x86_64` exportado con `--headless --quit-after 120`.
  - **Criterio de éxito:** `windows`, `linux` y `macos` en PASS. Si `android-apk` falla, se investiga hasta 3 intentos
    con systematic-debugging. Si sigue fallando, queda documentado en `HISTORY.md` y en FALTANTES, porque la entrega
    tolera la falla y sale `PARTIAL`.
  - **`android-aab`:** necesita el build con Gradle, que sin red requiere la caché de Gradle precargada en la imagen. Si
    no sale en 2 intentos, se documenta como limitación y se cierra la tarea.
- [ ] **Step 5: commit:** `"Sandbox: paso package (Godot 4 plataformas, Flutter web, dotnet publish) e imagen de juegos
  v3"` + `Co-Authored-By`, con el resultado de la prueba en vivo en el cuerpo del mensaje.

---

### Task 8: API y chat

**Archivos:**
- Crear: `app/src/main/java/com/aicompany/core/delivery/DeliveryController.java`
- Modificar: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java` (regla nueva, junto a `MISSION_LOG`)
- Tests: `DeliveryControllerTest.java` y `ChatIntentRouterTest.java` (el existente)

**Interfaces:**
- **`GET /api/company/missions/{id}/deliveries`:** devuelve `List<DeliveryView>`, con
  `record DeliveryView(int increment, String status, String path, long sizeBytes, List<DeliveryFacts.TargetResult>
  targets, String error, String createdAt)`.
- **`GET /api/company/missions/{id}/deliveries/{n}/download`:** responde `ResponseEntity<Resource>` (`FileSystemResource`)
  con `Content-Disposition: attachment; filename="<id>-incremento-<n>.zip"` y `application/zip`. Si no existe el record o
  el archivo, 404.
- **`POST /api/company/missions/{id}/deliveries`:** llama a `deliverVerified(id)` y devuelve `DeliveryView`. Corre de
  forma síncrona, porque es un paso de minutos dentro del timeout de 30 min. Nadie bloquea en la UI: el botón muestra
  «empaquetando…».
- **`GET /api/company/missions/{id}/android-signing-key`:** devuelve `Status`.
- **`PUT /api/company/missions/{id}/android-signing-key`:** recibe `{keystoreBase64, alias, password}` y devuelve
  `Status`.
- **`DELETE /api/company/missions/{id}/android-signing-key`:** responde 204.

- [ ] **Step 1: tests que fallan.**
  - **Controller:**
    - el download devuelve 200, el header de attachment y los bytes del zip de un `@TempDir`;
    - el download de un incremento sin record devuelve 404;
    - el GET de la clave nunca serializa `password` ni `keystoreBase64` (se serializa con Jackson y se asserta);
    - el PUT delega en el servicio.
  - **Chat:**
    - `«entrega de MISSION-PONG-1»` → responde Java sin llamar a `ceoService`, con `incremento 3 (PARTIAL):
      ~/entregas-forja/...zip` y los targets que faltaron con su motivo;
    - `«¿dónde está MISSION-PONG-1?»` → la misma respuesta;
    - una misión sin entregas → `MISSION-X: sin entrega todavía. Si tiene un incremento VERIFIED, usa «Generar entrega» en
      la misión o escribe «genera la entrega de MISSION-X».`;
    - `«genera la entrega de MISSION-X»` → llama a `deliverVerified` y responde la ruta;
    - `«entregas»` solo → las últimas 5 de todas las misiones.
- [ ] **Step 2:** correr los tests y ver que fallan.
- [ ] **Step 3: implementar.**
  - **Patrones:**
    - `DELIVERY_QUERY = Pattern.compile("(entregas?|donde esta|descargar)( de| la)? mission-")`;
    - `DELIVERY_MAKE = Pattern.compile("genera(r)? (la )?entrega( de)? mission-")`;
    - `DELIVERY_LIST = Pattern.compile("^entregas$")`.
  - Se aplican sobre `bare`, igual que `MISSION_LOG`.
  - Van **antes** de `MISSION_LOG` y después de las menciones, para que «¿dónde está MISSION-X?» no caiga en el CEO.
  - `DeliveryService` se inyecta con un setter `@Autowired(required = false)`, como `setMissionEventLog`. Sin el bean,
    la regla no aplica.
  - Las rutas se muestran con `~` en lugar de `/data`. El path real del contenedor es `/data/entregas-forja/...`. Se
    agrega la propiedad `products.deliveries-host-path: ${DELIVERIES_HOST_PATH:~/entregas-forja}`, solo para mostrar, y
    la vista reemplaza el prefijo `deliveries-root` por ella.
- [ ] **Step 4:** `mvn test` completo. Todo pasa.
- [ ] **Step 5: commit:** `"Entrega: API (listar, descargar, generar, clave de Android) y consultas en el chat"` +
  `Co-Authored-By`.

---

### Task 9: Command Center

**Archivos:**
- Modificar: `app/frontend/src/api/types.ts` (`DeliveryTarget`, `DeliveryView`, `AndroidSigningKeyStatus`)
- Modificar: `app/frontend/src/api/client.ts` (`deliveries`, `generateDelivery`, `downloadDeliveryUrl`, `signingKey`,
  `saveSigningKey`, `deleteSigningKey`)
- Modificar: `app/frontend/src/pages/MissionDetailPage.tsx` (componente `DeliveriesSection`, al lado de la Bitácora)
- Modificar: `app/frontend/src/statusColor.ts` (`READY`, `PARTIAL`, `PACKAGING`; `FAILED` ya existe)

- [ ] **Step 1: tipos y cliente**, como espejo exacto de `DeliveryView` y `Status` de las Tasks 5, 6 y 8.
- [ ] **Step 2: `DeliveriesSection({ missionId, status })`:**
  - `useQuery(['deliveries', missionId])`, con polling cada 5 s mientras alguna entrega esté en `PACKAGING`.
  - Una fila por incremento:
    - estado con color;
    - ruta con un botón «copiar»;
    - tamaño en MB;
    - link **Descargar** (`<a href={downloadDeliveryUrl(id, n)} download>`);
    - los targets como chips: verde PASS, rojo FAIL con `title` = motivo;
    - el `error`, si hay.
  - Botón **Generar entrega**, visible si la misión tiene validación VERIFIED y el último incremento no tiene entrega, o
    para regenerar. Hace `useMutation` y muestra «Empaquetando… (puede tardar varios minutos)».
  - Sin entregas: «Sin entregas todavía».
- [ ] **Step 3: tarjeta «Clave de Android».** Solo aparece si la misión es un juego, es decir si alguna entrega tiene
  targets `android-*` o el plan es `GODOT_DOTNET_GAME`; se usa el dato que ya tenga la página.
  - Con clave configurada: muestra el alias, la fecha y el botón Borrar.
  - Si no: un formulario con archivo `.jks`/`.p12` (`FileReader` → base64), alias y contraseña, y el botón Guardar.
  - Al guardar se limpia el formulario y se muestra el error del servidor si lo hay.
  - Texto de ayuda: «Godot usa la misma contraseña para el keystore y la clave. Respáldala: sin ella no podrás
    actualizar la app en Google Play.»
- [ ] **Step 4:** `cd app/frontend && npm run lint && npm run build`. Los dos pasan.
- [ ] **Step 5: commit:** `"Command Center: entregas de la misión (descargar, generar) y clave de Android"` +
  `Co-Authored-By`.

---

### Task 10: compose, docs y verificación en vivo de punta a punta

**Archivos:**
- Modificar: `docker-compose.yml`:
  - `company-core`: `- ${HOME}/entregas-forja:/data/entregas-forja` y `DELIVERIES_ROOT=/data/entregas-forja`;
  - `sandbox-runner`: `- ${HOME}/entregas-forja/.staging:${HOME}/entregas-forja/.staging` y
    `SANDBOX_STAGING_ROOT=${HOME}/entregas-forja/.staging`.
  - Ojo: el runner escribe en `<staging-root>/<missionId>/incremento-N`, y company-core lo lee como
    `/data/entregas-forja/.staging/<missionId>/incremento-N`. Las dos rutas apuntan a la misma carpeta del host.
- Modificar: `docs/ARCHITECTURE.md` (sección nueva «Entrega del producto»), `docs/HISTORY.md` (decisiones del fundador
  del 2026-10-17 y resultado en vivo), `docs/FALTANTES.md` (cerrar «Exportar el juego para que el fundador lo juegue»;
  agregar Web con GDScript, firma de macOS, iOS y `.aab` si no salió) y `CLAUDE.md` (una línea en «Arquitectura en
  grande»: paquete `delivery` y `~/entregas-forja`).

- [ ] **Step 1:** compose y `mkdir -p ~/entregas-forja/.staging`. Es el host del fundador y es inocuo.
- [ ] **Step 2: docs.**
- [ ] **Step 3:** `cd app && mvn test`, `cd sandbox-runner && mvn test` y `cd app/frontend && npm run lint && npm run
  build`. Todo pasa.
- [ ] **Step 4: commit:** `"Docs y compose: entrega del producto"` + `Co-Authored-By`.
- [ ] **Step 5: verificación en vivo.** La hace el controlador, no un subagente: implica reiniciar y la regla «no
  reiniciar con misión viva» se consulta antes.
  1. Desplegar `company-core` y `sandbox-runner`.
  2. Generar la entrega del Pong (`POST /deliveries`).
  3. Revisar el zip: `unzip -l`, que `ENTREGA.md` sea coherente y que no haya ninguna contraseña (`grep -ri password` en
     el contenido extraído).
  4. Correr el Linux exportado.
  5. Preguntar en el chat «¿dónde está MISSION-PONG-NIVELES-1792199290?».
  6. Registrar todo en `HISTORY.md`.
