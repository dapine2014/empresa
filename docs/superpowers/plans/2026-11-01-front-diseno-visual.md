# Front con diseño visual (etapa 1, apps) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mila y Orion pasan a ser dos front intercambiables. El front diseña el sistema visual y maquetas HTML/CSS/SVG que el fundador aprueba. Java genera el tema de Flutter, compara la app con la maqueta (2 rondas de corrección en texto) y deja la decisión de las diferencias en una política: hoy el fundador, después el PO.

**Architecture:** Hay un writer nuevo (`VisualDesignWriter`, con el patrón de `InteractionDesignWriter`) entre el diseño de Kael y la aprobación. Las validaciones y la comparación son Java puro, en `agent/validation`. `sandbox-runner` suma el job `DESIGN_CAPTURE`, que renderiza maquetas y vistas previas de la build web con el Chrome de la imagen `flutter-web-app`. `DevelopmentTeamStrategy` corre la comparación después del sandbox y antes de dar el incremento por VERIFIED. Si no converge, usa `MissionExecutor.block` con el motivo `DESIGN_MISMATCH`.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Jackson 3 (`tools.jackson.*`), Neo4j (Cypher a mano), JUnit 5 y Mockito, Podman, Flutter 3.41.9 web, Chrome sin pantalla, React con TS y react-query.

**Spec:** `docs/superpowers/specs/2026-11-01-front-diseno-visual-design.md`

## Global Constraints

- Nunca `format` y `tools` en la misma llamada. El writer usa `format` sin `tools`.
- Jackson 3: `tools.jackson.*`, nunca `com.fasterxml.jackson.*`.
- Sin `@Async`: lo asíncrono va por los executors existentes (`MissionExecutor.*Async`).
- Errores HTTP: `IllegalArgumentException` e `IllegalStateException` dan 500. No se agrega manejo nuevo.
- Eventos `EMPRESA_*` por `CompanyEventPublisher`. Las transiciones de misión solo las hace `MissionExecutor.advanceMission`.
- Todo lo configurable se edita en el Command Center, y toda feature se puede consultar en el chat (con respuesta de Java).
- `company-core` nunca ejecuta HTML ni código generado: lo hace `sandbox-runner`, sin red, con perfiles y pasos fijos.
- El modelo nunca recibe imágenes. Las correcciones de diseño son texto que arma Java.
- Umbral inicial: **SSIM ≥ 0,90** y ningún elemento faltante. Tolerancia por elemento: **±8 px** de posición y tamaño, y el color debe ser el **mismo token**.
- Tamaños de referencia: **teléfono 390×844** y **escritorio 1280×800**.
- Contraste mínimo **WCAG AA 4,5:1** para texto normal.
- **Máximo 2 rondas** automáticas por pantalla. «Otra ronda» del decisor da **1** ronda más y no reinicia el contador.
- La política `DESIGN_MISMATCH_DECIDER` vale `0` (FOUNDER), que es el valor inicial, o `1` (PRODUCT_OWNER). El valor `1` se rechaza al guardar hasta que se habilite.
- Las misiones sin sistema visual aprobado no pasan por el gate de colores ni por la comparación.
- Las capturas no son tests: no van en `test/`, no son de Vera y no cuentan en `TestInventory` ni en `TestSuiteGrowthGate`.

## Review Focus

1. **Mila y Orion juntos en una misión con un solo contexto.** El segundo front no tiene capas. Se espera el error claro de hoy («Quítalo del plan»), no una misión que se cuelga (prueba en Task 2).
2. **Maqueta con color literal dentro de un `style=""` en línea o en un SVG embebido.** Debe rechazarse igual que en `<style>` (prueba en Task 4).
3. **Pantalla de Kael sin vista previa en un incremento que no la toca.** No debe exigirse ni compararse (prueba en Task 9).
4. **Reinicio de company-core con la misión en `BLOCKED` por `DESIGN_MISMATCH`.** Debe seguir `BLOCKED` con sus pantallas pendientes, y las decisiones hechas antes del reinicio se conservan (prueba en Task 12).
5. **El fundador decide «aceptar» dos veces la misma pantalla (doble clic).** La segunda no crea otra decisión ni reanuda dos veces (prueba en Task 12).

---

### Task 1: Orion es un front `UI_UX`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/TeamMemoryService.java:94-95`
- Modify: `app/src/main/java/com/aicompany/core/model/RoleLayerCatalog.java` (`describe()`)
- Test: `app/src/test/java/com/aicompany/core/service/TeamMemoryServiceTest.java`, `app/src/test/java/com/aicompany/core/model/RoleLayerCatalogTest.java`

**Interfaces:**
- Produces: `specialist-3d` con `roleCode = "UI_UX"`. `ensureAllTeams` ya re-escribe `roleCode` y `capabilities` en cada arranque, así que la migración es automática.

- [ ] **Step 1: Tests que fallan**
  - `TeamMemoryServiceTest.orionIsUiUxWithFlutterAndBlender`: la `RoleDefinition` de `specialist-3d` tiene `roleCode` `"UI_UX"` y sus capabilities incluyen `"flutter"`, `"dart"`, `"visual-design"`, `"html-css-svg-mockups"` y `"blender"`.
  - `RoleLayerCatalogTest.describeNamesMilaAndOrionAsFront`: `describe()` contiene `"Mila/UI_UX y Orion/UI_UX"` y ya no contiene `"Orion/SPECIALIST_3D"`.
- [ ] **Step 2:** Correr `cd app && mvn test -Dtest='TeamMemoryServiceTest,RoleLayerCatalogTest'`. Debe dar FAIL.
- [ ] **Step 3: Implementar.** Cambiar la `RoleDefinition` de `specialist-3d` a `"UI_UX"`, con las capabilities de Mila más `"visual-design"`, `"html-css-svg-mockups"`, `"blender"`, `"3d-modeling"` y `"rigging"`. Agregar `"visual-design"` y `"html-css-svg-mockups"` también a Mila. En `describe()`, Mila y Orion quedan como front intercambiables; Luna y Andrea siguen como no habilitados. Las capabilities nuevas tienen que pasar el chequeo de atomicidad del catálogo (línea 57).
- [ ] **Step 4:** Volver a correr los tests. Deben dar PASS. Correr también `mvn test`, porque otros tests pueden fijar el catálogo; ajustar los que afirmen `SPECIALIST_3D` para Orion.
- [ ] **Step 5:** Commit: `feat(dev-group): Orion pasa a front UI_UX junto a Mila`.

### Task 2: Dos agentes del mismo rol se reparten por contexto

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/agent/validation/TeamPlanResolver.java:60-80`
- Modify: `app/src/main/java/com/aicompany/core/model/RoleLayerCatalog.java` (método nuevo)
- Test: `app/src/test/java/com/aicompany/core/agent/validation/TeamPlanResolverTest.java`

**Interfaces:**
- Produces: `RoleLayerCatalog.splitByContext(LinkedHashMap<String,String> roleCodeByAgent, Map<String, List<String>> contextsByAgent, List<String> contexts) -> Map<String, List<String>>`. Devuelve los contextos que le tocan a cada agente que **comparte rol** con otro elegido. Los agentes con rol único no aparecen en el mapa: tienen todos los contextos, como hoy.

Regla: un agente que comparte rol se queda con los contextos que Neo le puso en `assignments` (`LayerAssignment.context`). Los contextos que Neo no asignó se reparten en orden, por turnos, entre los agentes de ese rol en el orden del plan. Un contexto tiene un solo dueño por rol. Los archivos de entrada (`addEntryFiles`) son del **primer** agente del rol en el plan.

- [ ] **Step 1: Tests que fallan**
  - `milaAndOrionSplitTwoContexts`: con contextos `[pedidos, clientes]`, Mila y Orion `UI_UX` y sin `assignments`, Mila es dueña de `lib/pedidos/presentation` y de `lib/main.dart`, y Orion de `lib/clientes/presentation`. No hay errores.
  - `neoAssignmentWins`: Orion tiene `assignments=[{context:"pedidos", layer:"PRESENTATION"}]`. Orion es dueño de `lib/pedidos/presentation` y Mila de `lib/clientes/presentation`.
  - `sameRoleSingleContextKeepsClearError` (Review Focus 1): con un solo contexto, la tarea de Orion da el error que contiene `"no tiene capas"` y `"Quítalo del plan"`.
- [ ] **Step 2:** `mvn test -Dtest=TeamPlanResolverTest`. Debe dar FAIL en los tres.
- [ ] **Step 3: Implementar.** Agregar `splitByContext`. En `TeamPlanResolver`, para cada capa asignada a un rol compartido, generar las raíces solo con los contextos de ese agente (en `roots(...)`), y dar las capas de respaldo y las primarias del rol a cada agente en sus contextos. `RoleLayerCatalog.assign` hoy asigna una capa a un solo agente; para los roles compartidos, todos los agentes del rol reciben la capa y el filtro por contexto evita el solape.
- [ ] **Step 4:** `mvn test -Dtest='TeamPlanResolverTest,TeamPlanValidatorTest'`. Debe dar PASS.
- [ ] **Step 5:** Commit: `feat(dev-group): agentes del mismo rol se reparten los contextos`.

### Task 3: Sistema visual y su validación

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/model/VisualSystem.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/VisualSystemValidator.java`
- Create: `app/src/main/java/com/aicompany/core/model/FontCatalog.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/VisualSystemValidatorTest.java`

**Interfaces:**
- Produces:
  - `record VisualSystem(List<ColorToken> colors, Typography typography, Map<String, Double> spacing, Map<String, Double> radii, List<ComponentStyle> components)`
  - `record ColorToken(String name, String hex)`
  - `record Typography(String fontFamily, Map<String, Double> sizes)`, con claves `display`, `title`, `body` y `label`
  - `record ComponentStyle(String component, Map<String, String> tokens)`, con `component` ∈ {`button`, `field`, `card`, `appBar`} y valores que son **nombres** de token
  - `VisualSystemValidator.validate(VisualSystem vs) -> List<String>`
  - `FontCatalog.FAMILIES` (`List<String>`) y `FontCatalog.files(String family) -> List<String>` (rutas `.ttf` dentro de la imagen del sandbox)

Colores obligatorios: `primary`, `onPrimary`, `surface`, `onSurface`, `error`, `onError`. Pares de contraste: `onPrimary`/`primary`, `onSurface`/`surface`, `onError`/`error`. El contraste se calcula con la luminancia relativa de WCAG 2.1. Catálogo de fuentes inicial: `Roboto`, `Noto Sans`, `Noto Serif`.

- [ ] **Step 1: Tests que fallan**
  - `acceptsCompleteSystem`: un sistema válido → `[]`.
  - `rejectsMissingRequiredColor`: sin `onError` → error que contiene `"onError"`.
  - `rejectsLowContrast`: `primary=#777777` y `onPrimary=#888888` → error que contiene `"contraste"` y `"4,5"`.
  - `rejectsFontOutsideCatalog`: `fontFamily="Comic Sans"` → error que nombra las fuentes del catálogo.
  - `rejectsComponentTokenThatDoesNotExist`: `button.background="brand"` sin token `brand` → error.
  - `rejectsInvalidHex`: `"#12345"` → error.
- [ ] **Step 2:** `mvn test -Dtest=VisualSystemValidatorTest`. Debe dar FAIL.
- [ ] **Step 3:** Implementar los records, el validador y `FontCatalog`. Los mensajes en español van como `CORRECCIÓN`.
- [ ] **Step 4:** Volver a correr. Debe dar PASS.
- [ ] **Step 5:** Commit: `feat(diseño): sistema visual del front con validación de contraste`.

### Task 4: Maquetas, variables CSS generadas por Java y su validación

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/model/Mockups.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/MockupValidator.java`
- Create: `app/src/main/java/com/aicompany/core/model/CssTokens.java`
- Test: `app/src/test/java/com/aicompany/core/agent/validation/MockupValidatorTest.java`, `app/src/test/java/com/aicompany/core/model/CssTokensTest.java`

**Interfaces:**
- Consumes: `VisualSystem` (Task 3) y `InteractionDesign.AppDesign` / `Screen` (existentes).
- Produces:
  - `record Mockups(List<Mockup> screens, List<SvgAsset> svgs)`
  - `record Mockup(String screen, String html)`, con `screen` = `PANT-NN`
  - `record SvgAsset(String name, String svg)`, con `name` = `[a-z0-9_]+`
  - `MockupValidator.validate(Mockups m, AppDesign app, Set<String> screensInIncrement, VisualSystem vs) -> List<String>`
  - `CssTokens.root(VisualSystem vs) -> String`: devuelve el bloque `:root{--color-primary:#…;…}` y los `@font-face` que apuntan a `fonts/<archivo>.ttf`
  - `CssTokens.page(Mockup m, VisualSystem vs) -> String`: el HTML final, con el `<style>` de Java inyectado al principio del `<head>`

Reglas del validador:
- Cada pantalla de `screensInIncrement` tiene su maqueta.
- No hay color literal (`#hex`, `rgb(`, `rgba(`, `hsl(`) en ningún lugar del HTML del front: ni `<style>`, ni `style=""`, ni atributos `fill`/`stroke` de un `<svg>` embebido. Solo se permite `var(--color-…)` con un token existente.
- No hay `<script>`, `http:`, `https:` ni `url(` hacia afuera, ni en los HTML ni en los SVG.
- Cada maqueta tiene al menos un `data-id`, y los `data-id` son únicos dentro de la pantalla.

- [ ] **Step 1: Tests que fallan**
  - `requiresMockupForEachIncrementScreen`
  - `rejectsLiteralColorInStyleTag`
  - `rejectsLiteralColorInInlineStyleAndSvgFill` (Review Focus 2)
  - `rejectsUnknownTokenVar`
  - `rejectsScriptAndExternalUrl`
  - `rejectsDuplicateDataId`
  - `CssTokensTest.rootHasEveryColorAndFontFace`: `root(vs)` contiene `--color-primary:#2F6FED` y `@font-face{font-family:"Roboto"`.
- [ ] **Step 2:** `mvn test -Dtest='MockupValidatorTest,CssTokensTest'`. Debe dar FAIL.
- [ ] **Step 3:** Implementar con regex sobre el texto. No hace falta un parser HTML completo: los modelos escriben HTML simple y el rechazo es conservador.
- [ ] **Step 4:** Volver a correr. Debe dar PASS.
- [ ] **Step 5:** Commit: `feat(diseño): maquetas HTML/CSS/SVG con tokens de Java`.

### Task 5: `VisualDesignWriter` y su memoria

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/VisualDesignWriter.java`
- Create: `app/src/main/java/com/aicompany/core/service/VisualDesignMemoryService.java`
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java` (método nuevo junto a `writeAppDesign`, línea ~1272)
- Modify: `app/src/main/java/com/aicompany/core/config/CompanyMemoryInitializer.java` (constraint de `VisualDesignVersion`)
- Test: `app/src/test/java/com/aicompany/core/service/VisualDesignWriterTest.java`

**Interfaces:**
- Consumes: `VisualSystemValidator`, `MockupValidator` y `CssTokens` (Tasks 3 y 4).
- Produces:
  - `record VisualDesign(VisualSystem system, Mockups mockups)` en `agent/model`
  - `VisualDesignWriter.write(String missionId, String frontAgentId, RequirementsSpec spec, AppDesign app, int increment, Optional<VisualDesign> previous, String adjustment) -> Written(int version, VisualDesign design)`
  - `CeoService.writeVisualDesign(String agentId, String prompt, String agentPrompt, String model) -> VisualDesign`, con `format` = JSON Schema de `VisualDesign` y **sin** `tools`
  - `VisualDesignMemoryService`: `saveDraft(missionId, json, specVersion, designVersion, instruction) -> int`, `latest(missionId)`, `approved(missionId)`, `approve(missionId, version, by)`, `supersedeDraft(missionId)`. Usa los nodos `(:Mission)-[:HAS_VISUAL_DESIGN]->(:VisualDesignVersion {version, designJson, status, specVersion, interactionDesignVersion, instruction})`

Copia el patrón de `InteractionDesignWriter`: `MAX_ATTEMPTS = 3`, `PaidRetry`, `CORRECCIÓN DEL INTENTO ANTERIOR` y «Así entendió Java», tarea `<misión>-VISUAL` (`-V<n>` en los ajustes), estado del agente WORKING/IDLE y error si el agente está apagado. Si ya hay un sistema visual **aprobado** en un incremento anterior, el prompt lo trae como base y pide maquetas solo para las pantallas del incremento. Un cambio de sistema visual genera una versión nueva que el fundador aprueba.

- [ ] **Step 1: Tests que fallan** (Mockito sobre `CeoService` y la memoria)
  - `acceptsValidDesignFirstAttempt`: guarda el DRAFT v1 y la tarea queda `COMPLETED`.
  - `retriesWithCorrectionWhenValidatorRejects`: el segundo prompt contiene `"CORRECCIÓN DEL INTENTO ANTERIOR"` y el error de contraste.
  - `failsAfterMaxAttempts`: lanza `IllegalStateException` con `"después de 3 intentos"`.
  - `asksMockupsOnlyForIncrementScreens`: el prompt nombra `PANT-02` (del incremento) y no `PANT-01` (aprobada antes).
  - `throwsWhenFrontAgentDisabled`
- [ ] **Step 2:** `mvn test -Dtest=VisualDesignWriterTest`. Debe dar FAIL.
- [ ] **Step 3:** Implementar. El prompt pide el JSON y explica en español las reglas de las Tasks 3 y 4: tokens, `data-id` igual a la `Key` de Flutter, sin colores literales y sin red.
- [ ] **Step 4:** Volver a correr. Debe dar PASS.
- [ ] **Step 5:** Commit: `feat(diseño): el front escribe sistema visual y maquetas`.

### Task 6: Job `DESIGN_CAPTURE` en `sandbox-runner` e imagen `flutter-web-app:3`

**Files:**
- Modify: `sandbox-runner/src/main/java/com/forjai/sandbox/JobController.java` (jobType `DESIGN_CAPTURE`)
- Modify: `sandbox-runner/src/main/java/com/forjai/sandbox/ExecutionProfile.java` (`CAPTURE_STEP`, imagen `:3`)
- Modify: `sandbox-runner/src/main/java/com/forjai/sandbox/VerifyJobRunner.java` (método `capture`)
- Modify: `sandbox/images/flutter-web-app/Dockerfile`, `sandbox/images/flutter-web-app/run.sh`
- Create: `sandbox/images/flutter-web-app/capture.py`
- Modify: `sandbox/build-images.sh` (tag `:3`)
- Test: `sandbox-runner/src/test/java/com/forjai/sandbox/JobControllerTest.java`, `VerifyJobRunnerTest.java`

**Interfaces:**
- Produces:
  - Request: `JobRequest` con `jobType="DESIGN_CAPTURE"`, `stackProfile="FLUTTER_WEB_APP"`, `missionId`, `commitSha` y `increment`. El campo nuevo `captureMode` vale `MOCKUPS` o `APP`; cualquier otro valor da 400.
  - Response: `SandboxResult` con `stagingDir = "diseno-<increment>-<mode>-<jobId>"` bajo el staging de la misión.
  - Contenido del staging: `<screen>-<phone|desktop>.png` y `<screen>-<phone|desktop>.json`, con `[{id, x, y, w, h, color}]` (color de fondo dominante en hex).
- `MOCKUPS`: Java commitea las maquetas en `.forjai/mockups/<PANT-NN>.html`, fuera de las capas del front, y `capture.py` las abre en `file://`.
- `APP`: `run.sh capture-app` hace el `flutter build web` de siempre, sirve `build/web` en `127.0.0.1` y abre `/?preview=<PANT-NN>` en los dos tamaños. Las cajas salen de los elementos con atributo `flt-semantics-identifier` (Flutter web, `Semantics(identifier:)`).
- El Dockerfile copia las fuentes de `FontCatalog` (Roboto, Noto Sans, Noto Serif) a `/forjai/fonts/` y `capture.py`. No necesita nada nuevo porque Chrome ya está. Las capturas usan Chrome DevTools en modo headless con `--window-size` y un script que lee `getBoundingClientRect`, ejecutado con Python estándar sin dependencias.

- [ ] **Step 1: Tests que fallan**
  - `JobControllerTest.designCaptureRejectsUnknownMode`: `captureMode="X"` → 400.
  - `JobControllerTest.designCaptureRunsCaptureStep`
  - `VerifyJobRunnerTest.captureCopiesPngAndJsonToStaging`: con el `ProcessExecutor` fake que escribe `.forjai/design/PANT-01-phone.png` y su `.json`, el staging los contiene y nada más.
  - `VerifyJobRunnerTest.captureRejectsTraversalInMissionId`
- [ ] **Step 2:** `cd sandbox-runner && mvn test`. Debe dar FAIL.
- [ ] **Step 3:** Implementar, con un timeout de 600 s.
- [ ] **Step 4:** `mvn test`, que debe dar PASS. Después `bash sandbox/build-images.sh` y una corrida manual con `podman run … flutter-web-app:3 /forjai/run.sh capture-mockups` sobre una maqueta de ejemplo, que debe producir el PNG y un JSON con cajas.
- [ ] **Step 5:** Commit: `feat(sandbox): job DESIGN_CAPTURE para maquetas y vistas previas`.

### Task 7: Aprobación junto con las HU

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/MissionExecutor.java` (después de `design(...)`, ~línea 290, y en `awaitApproval`)
- Modify: `app/src/main/java/com/aicompany/core/service/MissionService.java:340-380` (`decideRequirements`)
- Modify: `app/src/main/java/com/aicompany/core/service/RequirementsMemoryService.java` (`approveWithDesign` aprueba también el `VisualDesignVersion` en la misma transacción)
- Modify: `app/src/main/java/com/aicompany/core/service/SandboxRunnerClient.java` (`designCapture`)
- Create: `app/src/main/java/com/aicompany/core/service/DesignCaptureService.java`
- Modify: `app/src/main/java/com/aicompany/core/controller/MissionController.java` (`GET /{missionId}/visual-design`, `GET /{missionId}/design-images/{name}`)
- Test: `app/src/test/java/com/aicompany/core/service/MissionExecutorVisualDesignTest.java`, `DesignCaptureServiceTest.java`

**Interfaces:**
- Consumes: `VisualDesignWriter.write` (Task 5) y el job `DESIGN_CAPTURE` (Task 6).
- Produces:
  - `SandboxRunnerClient.designCapture(String missionId, String commitSha, int increment, String mode) -> Optional<SandboxResult>`
  - `DesignCaptureService.captureMockups(String missionId, int increment, VisualDesign d) -> Captures` y `captureApp(String missionId, String headSha, int increment, Set<String> screens) -> Captures`
  - `record Captures(Map<String, Path> pngByScreenSize, Map<String, List<Box>> boxesByScreenSize)`, con la clave `"<PANT-NN>-<phone|desktop>"`
  - `record Box(String id, int x, int y, int w, int h, String color)`
  - `VisualDesignView` para la UI, con `system`, `screens` y `imageUrls`, más la línea correspondiente en `types.ts` (en la Task 14)

Flujo: experiencia `APP` → Kael → **front**: Mila, o Orion si Mila está apagada. Neo todavía no planificó en este punto, así que el autor del sistema visual es el front por defecto; la spec decía «el primero que Neo nombre», y esto lo ajusta → `captureMockups` → `awaitApproval`, cuyo mensaje suma «el front diseñó el sistema visual y N maquetas». Un `ADJUST` con `target="VISUAL"` (campo nuevo y opcional en `RequirementsDecisionCommand`) va a `VisualDesignWriter`. Si Aria cambia las HU, el visual queda `SUPERSEDED` como el de Kael.

- [ ] **Step 1: Tests que fallan**
  - `appExperienceRunsFrontAfterKaelBeforeApproval`
  - `gameExperienceSkipsVisualDesign`
  - `approveApprovesVisualInSameTransaction`
  - `adjustVisualGoesToFrontWriter`
  - `captureFailureLeavesApprovalWithWarning`: si el sandbox no responde, se pide la aprobación con el aviso «sin imágenes: <error>» y la misión no queda FAILED.
- [ ] **Step 2:** `mvn test -Dtest='MissionExecutorVisualDesignTest,DesignCaptureServiceTest'`. Debe dar FAIL.
- [ ] **Step 3:** Implementar. Los PNG se sirven desde el staging, solo con nombres `PANT-\d{2}-(phone|desktop)\.png`; cualquier otro nombre da 404.
- [ ] **Step 4:** Volver a correr. Debe dar PASS.
- [ ] **Step 5:** Commit: `feat(diseño): sistema visual y maquetas se aprueban con las HU`.

### Task 8: Scaffold con tema, fuentes, SVG y host de vistas previas

**Files:**
- Create: `app/src/main/java/com/aicompany/core/model/FlutterThemeFiles.java`
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java:1530-1560` (scaffold)
- Modify: `app/src/main/java/com/aicompany/core/model/BaselineDependencies.java` (`PUB:flutter_svg@<versión exacta que resuelva sin red>`)
- Test: `app/src/test/java/com/aicompany/core/model/FlutterThemeFilesTest.java`

**Interfaces:**
- Consumes: `VisualDesign` aprobado.
- Produces: `FlutterThemeFiles.files(VisualDesign d, List<String> previewScreens) -> Map<String, String>` (ruta → contenido), con:
  - `lib/theme/app_theme.dart`: la clase `AppTheme` con `static const Color primary = Color(0xFF2F6FED);` por token, `static ThemeData get theme` y los `TextStyle` de la escala;
  - `lib/forjai/preview.dart`: la función `Widget forjaiPreviewOr(Widget app)`, que con `Uri.base.queryParameters['preview']` devuelve la vista previa registrada y si no, la app;
  - `lib/forjai/previews.dart`: la tabla `screenId → builder`, generada importando `lib/<ctx>/presentation/previews/pant_NN_preview.dart` (clase `PantNNPreview`);
  - `assets/svg/<name>.svg`, `assets/fonts/<archivo>.ttf` (copiados desde la imagen en el paso `restore`, no desde Java) y el bloque `flutter: assets/fonts` que Java mezcla en `pubspec.yaml`.

`lib/forjai/` y `lib/theme/` son de Forjai: se agregan a las rutas reservadas para que ningún agente las escriba, igual que `game/forjai/`. Cuando el sistema visual cambia en un incremento, `app_theme.dart` se regenera en el scaffold de esa ronda.

- [ ] **Step 1: Tests que fallan**
  - `themeHasEveryToken`
  - `previewRegistryImportsEachScreen`
  - `pubspecDeclaresFontsAndSvgs`
  - `regeneratesThemeWhenVisualVersionChanges`
- [ ] **Step 2:** `mvn test -Dtest=FlutterThemeFilesTest`. Debe dar FAIL.
- [ ] **Step 3:** Implementar e integrar en el scaffold solo si existe un `VisualDesign` aprobado.
- [ ] **Step 4:** Volver a correr y luego `mvn test`. Debe dar PASS.
- [ ] **Step 5:** Commit: `feat(diseño): Java genera el tema Flutter y el host de vistas previas`.

### Task 9: Gates del front (colores literales y vistas previas)

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/ThemeLiteralGate.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/PreviewGate.java`
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java` (sumar a los checks del agente, junto a los gates de juego, ~línea 2320)
- Test: `ThemeLiteralGateTest.java`, `PreviewGateTest.java` en `app/src/test/java/com/aicompany/core/agent/validation/`

**Interfaces:**
- Produces:
  - `ThemeLiteralGate.check(List<GeneratedFile> files) -> List<String>`: aplica solo a `lib/**/presentation/**.dart` y rechaza `Color(0x`, `Colors.` (salvo `Colors.transparent`), `fontFamily:` y `TextStyle(` con `fontSize:` o `color:` literales. Ignora los comentarios Dart.
  - `PreviewGate.check(List<GeneratedFile> files, Set<String> screensInIncrement, Map<String,String> existing) -> List<String>`: cada pantalla del incremento tiene `pant_NN_preview.dart` con `class PantNNPreview` y una `Key('PANT-NN')` raíz, y `lib/main.dart` llama a `forjaiPreviewOr(`.
- Los dos gates solo corren si la misión tiene un `VisualDesign` aprobado.

- [ ] **Step 1: Tests que fallan**
  - `rejectsColorLiteralInPresentation`
  - `allowsColorInDomainFile`
  - `ignoresCommentedLiteral`
  - `rejectsTextStyleWithLiteralFontSize`
  - `requiresPreviewForIncrementScreen`
  - `doesNotRequirePreviewForScreenOutsideIncrement` (Review Focus 3)
  - `requiresMainToCallForjaiPreviewOr`
- [ ] **Step 2:** `mvn test -Dtest='ThemeLiteralGateTest,PreviewGateTest'`. Debe dar FAIL.
- [ ] **Step 3:** Implementar e integrar, con reintento y el motivo al dueño del archivo, como los otros gates.
- [ ] **Step 4:** Volver a correr. Debe dar PASS.
- [ ] **Step 5:** Commit: `feat(diseño): gates de tema y vistas previas para el front`.

### Task 10: Comparación de diseño (SSIM y por elemento) y texto de corrección

**Files:**
- Create: `app/src/main/java/com/aicompany/core/agent/validation/Ssim.java`
- Create: `app/src/main/java/com/aicompany/core/agent/validation/DesignComparison.java`
- Test: `SsimTest.java`, `DesignComparisonTest.java`

**Interfaces:**
- Consumes: `Box` y `Captures` (Task 7), y `VisualSystem` para traducir un hex a su token.
- Produces:
  - `Ssim.score(BufferedImage a, BufferedImage b) -> double`: escala de grises y ventanas de 8×8 con las constantes estándar `C1 = (0,01·255)²` y `C2 = (0,03·255)²`. Si los tamaños difieren, escala `b` al tamaño de `a`.
  - `DesignComparison.compare(String screen, String size, BufferedImage mockup, List<Box> mockupBoxes, BufferedImage app, List<Box> appBoxes, VisualSystem vs, double threshold) -> ScreenResult`
  - `record ScreenResult(String screen, String size, double ssim, List<ElementDiff> diffs, List<String> missing, boolean passed)`
  - `record ElementDiff(String id, Box mockup, Box app, String reason)`
  - `DesignComparison.correction(List<ScreenResult> failed) -> String`: el texto de la spec, que empieza por `"CORRECCIÓN DE DISEÑO — "` y termina cada pantalla con `"Lo demás coincide: no lo cambies."`. Si el color de la app no es de la paleta, dice «que no está en la paleta. Usa AppTheme.<token>».
- `passed` = `ssim ≥ threshold` y `missing` vacío. Un `ElementDiff` sale si la posición o el tamaño difieren en más de 8 px, o si el token del color es distinto.

- [ ] **Step 1: Tests que fallan**
  - `identicalImagesScoreOne`
  - `differentImagesScoreBelowThreshold`
  - `flagsButtonMovedAndOffPalette`: la maqueta tiene `btn-ingresar` en y=520, alto 48 y color primary; la app en y=580, alto 40 y `#6750A4`. El texto contiene `"btn-ingresar"`, `"y=520"`, `"y=580"` y `"no está en la paleta"`.
  - `flagsMissingElement`: `link-olvide` → `missing`.
  - `withinToleranceNoDiff`: 6 px → sin diferencia.
- [ ] **Step 2:** `mvn test -Dtest='SsimTest,DesignComparisonTest'`. Debe dar FAIL.
- [ ] **Step 3:** Implementar con `javax.imageio` y `BufferedImage`, sin dependencias nuevas.
- [ ] **Step 4:** Volver a correr. Debe dar PASS.
- [ ] **Step 5:** Commit: `feat(diseño): comparación determinista maqueta vs app`.

### Task 11: Ciclo de diseño en la estrategia de desarrollo

**Files:**
- Create: `app/src/main/java/com/aicompany/core/service/DesignReviewMemoryService.java`
- Create: `app/src/main/java/com/aicompany/core/service/DesignReviewLoop.java`
- Modify: `app/src/main/java/com/aicompany/core/service/DevelopmentTeamStrategy.java:579-595` (después del sandbox y antes de `recordVerifiedCommit`)
- Modify: `app/src/main/java/com/aicompany/core/model/StaticValidationStatus.java` (sobrecarga con `boolean designAccepted`)
- Test: `DesignReviewLoopTest.java`, `app/src/test/java/com/aicompany/core/model/StaticValidationStatusTest.java`

**Interfaces:**
- Consumes: `DesignCaptureService.captureApp` (Task 7), `DesignComparison` (Task 10) y el agente front dueño de la pantalla (`ownedPaths`).
- Produces:
  - `DesignReviewMemoryService`, con los nodos `(:Mission)-[:HAS_DESIGN_REVIEW]->(:DesignScreenReview {increment, screen, size, ssim, status, rounds, extraRounds, decidedBy, decisionNote, updatedAt})`. `status` ∈ `PENDING`, `PASSED`, `AWAITING_DECISION`, `ACCEPTED`. Métodos: `save(ScreenResult, increment, rounds)`, `pending(missionId, increment)`, `all(missionId)`.
  - `DesignReviewLoop.run(TeamMissionContext ctx, String headSha, int increment) -> Outcome(boolean accepted, List<String> awaiting, String headSha)`

Ciclo:
1. Captura de la app y comparación.
2. Por cada pantalla que falla con `rounds < 2 + extraRounds`, una llamada al front **solo** con el archivo de la pantalla, `app_theme.dart` y `correction(...)`. Es el mismo contrato `DevelopmentResult`, con los mismos gates de la Task 9, commit `<misión>-DESIGN-R<n>` y otra pasada por el sandbox (build y capture).
3. Si sigue fallando, la pantalla queda `AWAITING_DECISION` y `Outcome.accepted = false`.

En `DevelopmentTeamStrategy`:
- Con `accepted = false`: `status` no puede ser `VERIFIED`. La misión se frena con `MissionExecutor.block(missionId, "DESIGN_MISMATCH: " + pantallas)`.
- No se marca `FAILED` ni corre la corrección automática.
- Las misiones sin `VisualDesign` aprobado saltan el ciclo: `designAccepted = true`.

- [ ] **Step 1: Tests que fallan**
  - `passingScreensVerifyWithoutModelCall`
  - `failingScreenGetsTwoRoundsThenAwaitsDecision`: el front recibe exactamente 2 llamadas, el prompt de cada una tiene menos de 8.000 caracteres e incluye `"CORRECCIÓN DE DISEÑO"`, y el estado final es `AWAITING_DECISION`.
  - `correctionPromptContainsOnlyThatScreenFile`
  - `designMismatchBlocksNotFails`
  - `noVisualDesignSkipsLoop`
  - `StaticValidationStatusTest.notVerifiedWhileDesignNotAccepted`
- [ ] **Step 2:** `mvn test -Dtest='DesignReviewLoopTest,StaticValidationStatusTest'`. Debe dar FAIL.
- [ ] **Step 3:** Implementar. El umbral sale de `PolicyKey.DESIGN_SIMILARITY_THRESHOLD` (Task 12).
- [ ] **Step 4:** Volver a correr y luego `mvn test`. Debe dar PASS.
- [ ] **Step 5:** Commit: `feat(diseño): ciclo de corrección de diseño con 2 rondas y freno al decisor`.

### Task 12: Punto de decisión y políticas

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/PolicyKey.java` (`DESIGN_MISMATCH_DECIDER`, `DESIGN_SIMILARITY_THRESHOLD`)
- Modify: `app/src/main/java/com/aicompany/core/service/CompanyPolicyService.java:60-70, 175-185` (valores por defecto `0.0` y `0.90`; validación)
- Create: `app/src/main/java/com/aicompany/core/service/DesignDecisionService.java`
- Modify: `app/src/main/java/com/aicompany/core/controller/MissionController.java` (`GET /{missionId}/design-review`, `POST /{missionId}/design-review/decision`)
- Modify: `app/src/main/java/com/aicompany/core/recovery/MissionRecoveryService.java` (una BLOCKED por `DESIGN_MISMATCH` no se reanuda sola)
- Test: `DesignDecisionServiceTest.java`, `CompanyPolicyServiceTest.java`, `MissionRecoveryServiceTest.java`

**Interfaces:**
- Produces:
  - `enum DesignDecision { ACCEPT, ANOTHER_ROUND }`
  - `DesignDecisionService.decide(String missionId, String screen, DesignDecision d, String note, String decidedBy) -> DesignReviewView`. Es la **única** operación de decisión.
  - Request: `DesignDecisionCommand(String screen, DesignDecision decision, String note)`. El controller pasa `decidedBy = "founder"`.
  - `DesignDecisionService.decider() -> String` (`"FOUNDER"` o `"PRODUCT_OWNER"`), que resuelve Java desde la política.

Reglas:
- `decide` solo procede con la misión en `BLOCKED` y la pantalla en `AWAITING_DECISION`. Hace un *claim* atómico en Cypher: `SET status = 'DECIDING' WHERE status = 'AWAITING_DECISION'`. Si no lo consigue, lanza `IllegalStateException("… ya tiene una decisión en curso")`.
- `ACCEPT` deja la pantalla `ACCEPTED` con `decidedBy` y la nota.
- `ANOTHER_ROUND` suma `extraRounds += 1`, guarda la nota (que se agrega a la corrección) y la pantalla vuelve a `PENDING`.
- Cuando no queda ninguna pantalla en `AWAITING_DECISION`, se reanuda la misión: una ronda de corrección por el camino de `CorrectionRound`/`resume` existente, que entra al ciclo de la Task 11.
- Cada decisión publica `EMPRESA_DESIGN_DECISION` con `{screen, decision, decidedBy}` y queda en `MissionEventLog`.
- En `CompanyPolicyService`, crear una versión de `DESIGN_MISMATCH_DECIDER` con un valor distinto de `0` lanza `IllegalArgumentException("El PO decidirá las diferencias de diseño cuando se activen los demás modelos; por ahora solo FOUNDER (0).")`. `DESIGN_SIMILARITY_THRESHOLD` va de 0,5 a 1,0.

- [ ] **Step 1: Tests que fallan**
  - `acceptLastPendingResumesMission`
  - `anotherRoundAddsOneExtraRoundAndResumes`
  - `doubleAcceptIsRejected` (Review Focus 5): la segunda llamada lanza `IllegalStateException` y `resume` se llama una sola vez.
  - `decideRejectedWhenMissionNotBlocked`
  - `CompanyPolicyServiceTest.productOwnerDeciderRejectedForNow`
  - `CompanyPolicyServiceTest.thresholdOutOfRangeRejected`
  - `MissionRecoveryServiceTest.designMismatchStaysBlockedAfterRestart` (Review Focus 4): las pantallas `ACCEPTED` siguen `ACCEPTED`.
- [ ] **Step 2:** `mvn test -Dtest='DesignDecisionServiceTest,CompanyPolicyServiceTest,MissionRecoveryServiceTest'`. Debe dar FAIL.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** Volver a correr. Debe dar PASS.
- [ ] **Step 5:** Commit: `feat(diseño): punto único de decisión y política del decisor (fundador por ahora)`.

### Task 13: Chat

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java` (en el bloque de consultas deterministas, cerca de la bitácora, ~línea 369)
- Test: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `DesignReviewMemoryService.all` (Task 11), `DesignDecisionService.decider()` (Task 12) y `VisualDesignMemoryService.approved` (Task 5).

Reglas, todas respondidas por Java y sin modelo:
- `(?i)c[oó]mo va el dise[nñ]o de (MISSION-[\w-]+)` → una línea por pantalla y tamaño, con `PANT-NN (teléfono): 0,93 ✔`, `0,82 — esperando tu decisión` o `aceptada por founder`.
- `(?i)qui[eé]n decide las diferencias de dise[nñ]o` → «Hoy decide el fundador. Cuando se activen los demás modelos, decidirá el PO (política DESIGN_MISMATCH_DECIDER).»
- `(?i)sistema visual de (MISSION-[\w-]+)` → la paleta con nombres y hex, más la tipografía.

- [ ] **Step 1: Tests que fallan.** Uno por regla, verificando con Mockito que no se llamó a `CeoService`.
- [ ] **Step 2:** `mvn test -Dtest=ChatIntentRouterTest`. Debe dar FAIL.
- [ ] **Step 3:** Implementar las reglas antes del fallback al CEO.
- [ ] **Step 4:** Volver a correr. Debe dar PASS.
- [ ] **Step 5:** Commit: `feat(chat): estado del diseño y quién decide, respondidos por Java`.

### Task 14: Command Center

**Files:**
- Modify: `app/frontend/src/api/types.ts`, `app/frontend/src/api/client.ts`
- Modify: `app/frontend/src/pages/MissionDetailPage.tsx`
- Modify: `app/frontend/src/pages/SettingsPage.tsx` (las dos políticas)
- Modify: `app/frontend/src/statusColor.ts` (`AWAITING_DECISION`, `ACCEPTED` y `PASSED`)

**Interfaces:**
- Consumes: `GET /visual-design`, `GET /design-images/{name}`, `GET /design-review` y `POST /design-review/decision` (Tasks 7 y 12), y las políticas por el endpoint de políticas existente.

Contenido de las pantallas:
- **Bloque «Sistema visual y maquetas»** en la aprobación de requisitos: muestras de color (nombre y hex), muestra de tipografía, la imagen de cada maqueta en teléfono y escritorio, y un botón «Ajustar diseño visual» que manda `ADJUST` con `target="VISUAL"`.
- **Bloque «Revisión de diseño»:** por pantalla, la maqueta y la app lado a lado, el puntaje, la lista de diferencias, y los botones «Aceptar» y «Otra ronda» (con nota). Los botones solo aparecen si el decisor es FOUNDER y la pantalla está `AWAITING_DECISION`, y se deshabilitan mientras la petición está en curso.
- **Settings:** el umbral es editable. El decisor se muestra como «Fundador (temporal; luego el PO)»; la opción PO queda visible pero deshabilitada, con el texto de la política.

No hay rutas nuevas, así que `SpaController` no cambia.

- [ ] **Step 1:** Implementar los tipos, el cliente y las pantallas.
- [ ] **Step 2:** Correr `cd app/frontend && npm run lint && npm run build`. Deben terminar sin errores nuevos; el aviso preexistente de `SettingsPage.tsx:201` se tolera.
- [ ] **Step 3:** Commit: `feat(command-center): sistema visual, maquetas y revisión de diseño`.

### Task 15: Documentación y verificación en vivo

**Files:**
- Modify: `docs/ARCHITECTURE.md` (sección del Development Group y una sección nueva «Diseño visual del front»), `docs/HISTORY.md`, `docs/FALTANTES.md`

- [ ] **Step 1:** Documentar el comportamiento vigente en ARCHITECTURE: roles, flujo, job, gates, ciclo, políticas y chat.
- [ ] **Step 2:** Verificación en vivo. Antes de desplegar, confirmar en un paso aparte que no haya misiones vivas. Desplegar `company-core` y `sandbox-runner` juntos con la imagen `flutter-web-app:3`. Correr una misión `APP` chica (por ejemplo «login y lista de tareas») y verificar:
  1. aparece el sistema visual con sus maquetas en la aprobación;
  2. `app_theme.dart` queda en el scaffold;
  3. un color literal se rechaza;
  4. sale el puntaje por pantalla;
  5. si alguna pantalla no alcanza, se frena en `BLOCKED` y «Aceptar» la reanuda;
  6. el chat responde las tres preguntas.
- [ ] **Step 3: Calibrar el umbral.** Si una pantalla que a ojo coincide da menos de 0,90, se ajusta `DESIGN_SIMILARITY_THRESHOLD` desde Settings y se registra el valor y el porqué en HISTORY.
- [ ] **Step 4:** Registrar en HISTORY las decisiones del fundador del 2026-11-01, los bugs encontrados y la verificación. En FALTANTES, marcar lo cerrado y dejar abiertos: habilitar el PO como decisor, que el segundo front pida cambios en los archivos de entrada y Java los aplique (spec, «Mila y Orion intercambiables»; fuera de este plan), la etapa 2 (esqueleto 2D) y la etapa 3 (Blender y Godot 3D), y Kael .NET / Iris escena.
- [ ] **Step 5:** Commit: `docs: diseño visual del front (etapa 1) y verificación en vivo`.
