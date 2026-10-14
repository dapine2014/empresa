# Kael híbrido: diseña apps y juegos, programa juegos 2D — diseño

**Fecha**: 2026-10-14
**Estado**: diseño aprobado por el fundador en conversación; pendiente de revisión de este spec.
**Base**: Development Group fase 1 (`2026-10-01-development-group-fase1-design.md`), Aria v1
(`2026-10-03-aria-v1-design.md`), QA casos de uso (`2026-10-05-qa-casos-de-uso-design.md`), misiones durables
(`2026-10-05-misiones-durables-design.md`), sandbox (`2026-09-26-sandbox-verification-design.md`). Rama `kael-hibrido`
(sobre `diego-ambientes`).

## Objetivo

`docs/FALTANTES.md` marca a Kael como "siguiente rol a corregir": el equipo es híbrido (decisión del fundador
2026-10-02, apps y juegos) y los roles bloqueados por "fases" se corrigen uno por uno. Hoy Kael tiene dos identidades
que no coinciden:

- **Prompt** (v2, 2026-09-23): *Interactive Logic & Product Designer* — en software diseña UX, flujos, interacción,
  gamificación, engagement y retención; en videojuegos, game loop, mecánicas, reglas, progresión, dificultad, balance y
  economía interna. No asume que una mecánica funciona sin hipótesis ni evidencia.
- **Pipeline**: `roleCode` `GAME_DEV` (capabilities `godot`, `csharp`, `gameplay`, `game-loop`, `physics`), capa
  `GAME`, fase 3 → excluido de todo plan. En apps no tiene ninguna función.

**Éxito**: (1) en una app, Kael diseña pantallas y flujos que el fundador aprueba junto con las HU de Aria, Mila los
implementa y Java verifica que cada pantalla existe; (2) un juego 2D simple pedido por chat (Pong de un jugador contra
la pared) termina `VERIFIED`, diseñado y programado por Kael, con tests de reglas y **partidas simuladas** sobre la
escena real, sin que ningún modelo revise el resultado.

## Decisiones del fundador (2026-10-14)

1. Kael es híbrido: **diseña en apps y en juegos, y programa juegos**.
2. El juego objetivo es **2D simple y jugable, sin assets externos** (formas, colores y texto generados).
3. Evidencia de "jugable" = **reglas (tests de dominio por escenario) + partida simulada** en el sandbox sobre la
   escena real; el fundador no necesita jugarlo para aprobar.
4. Partidas simuladas escritas por **Vera**, ejecutadas por un **runner fijo de Forjai** dentro de Godot (enfoque A;
   descartados: simular contra la capa Application sin Godot, e inputs estructurados por Aria inyectados por Java).
5. Kael tiene **respaldo de Domain y Application** en juegos (un juego no necesita a Iris) y usa **kimi-k3** como los
   demás que programan.
6. El diseño de Kael se aprueba **junto con las HU de Aria**, en la misma parada `AWAITING_SPEC_APPROVAL`.
7. **Aria declara el tipo de experiencia** (`APP`, `GAME`, `NONE`) en su spec y el fundador lo aprueba con las HU;
   Kael solo participa en `APP`/`GAME`.

## Parte 1 — Kael diseñador (apps y juegos)

### 1.1 Tipo de experiencia (Aria)

- `RequirementsSpec` agrega `experience`: `APP` (producto con interfaz de usuario), `GAME` (juego) o `NONE` (solo API o
  servicio). `RequirementsSpecValidator` lo exige con uno de los tres valores (reintento con corrección, como el resto).
- Una misión con requisitos anteriores a este campo (`experience` ausente) sigue como hoy: sin Kael y sin las reglas de
  la tabla de 1.4.
- El prompt de la tarea de Aria (armado en Java) explica los tres valores y pide elegir `NONE` solo si el pedido no
  tiene ninguna interfaz.

### 1.2 Flujo de la misión

En una misión de `TEAM-DEVELOPMENT` con `experience` `APP` o `GAME`:

```
Aria escribe las HU (RequirementsVersion DRAFT)
  → Kael diseña sobre ese borrador (InteractionDesignVersion DRAFT, specVersion = la de Aria)
  → AWAITING_SPEC_APPROVAL: el fundador ve HU + diseño juntos
  → APPROVE → ambos APPROVED → Neo planifica el incremento 1
  → ADJUST a Aria ("para MISSION-X: …")       → HU nuevas → Kael rediseña → vuelve a esperar
  → ADJUST a Kael ("para MISSION-X, Kael: …") → solo diseño nuevo sobre las mismas HU → vuelve a esperar
  → REJECT → CANCELLED
```

- Con `NONE` Kael no participa: todo sigue como hoy.
- Un ajuste a Aria **siempre** hace rediseñar a Kael: el diseño referencia ids de HU y escenarios, y uno viejo quedaría
  colgando. El diseño anterior pasa a `SUPERSEDED`.
- Con la policy `SPEC_APPROVAL_REQUIRED = 0`, Kael diseña igual y la misión pasa directo (`approvedBy: policy`), como
  Aria.
- Con `experience` `APP`/`GAME` y Kael apagado (`Agent.enabled = false`), la misión no continúa tras Aria: queda
  `FAILED` con el motivo "Kael está apagado" (mismo criterio que Aria apagada). Aprobar y ajustar exigen además a Kael
  encendido cuando la misión es `APP`/`GAME`.
- **Misiones durables**: la tarea `<misión>-INTERACTION[-V<n>]` es un punto de retome nuevo
  (`ResumePoint.Stage`): un reinicio con el spec de Aria en `DRAFT` y sin diseño para esa versión retoma en Kael, nunca
  repite a Aria.

### 1.3 El diseño (contenido, validación y versiones)

**Tarea**: `<misión>-INTERACTION` (`-V<n>` en los ajustes), agente `interaction-design`. `InteractionDesignWriter`
llama al modelo con `format` sin `tools` (como `RequirementsWriter`), con el spec completo de Aria y el tipo de
experiencia. El JSON Schema lo arma Java según el tipo.

**`APP` → `InteractionDesign`**:
- `screens`: `PANT-NN` con `name`, `purpose`, `role` (rol del spec de Aria) y `states` (subconjunto de `empty`,
  `loading`, `error`).
- `flows`: uno por HU (`story: HU-NN`), con la secuencia de `PANT-NN` y la acción que lleva de una a otra.
- `scenarioScreens`: cada `ESC-NN` → la `PANT-NN` donde ocurre.
- `frictionRisks`: riesgos de fricción o engagement con hipótesis y mitigación (texto; Java solo exige ≥ 1, no vacío).

**`GAME` → `GameDesign`**:
- `loop`: estados (`START`, `PLAYING`, `GAME_OVER`, …) y transiciones `{from, to, trigger}`.
- `entities`: `id`, `name` y `properties` (nombre → **número**: velocidad, tamaño, vidas…).
- `controls`: `action` (nombre de acción de input de Godot, `[a-z_]+`), `key` y `effect`.
- `rules`: `REGLA-NN` con `condition` y `effect`; los valores se toman de `entities`.
- `win` / `lose`: condiciones que referencian `REGLA-NN`.
- `difficulty`: escalado con números (p. ej. +10 % de velocidad de la pelota cada 5 puntos).
- `scenarioRules`: cada `ESC-NN` → la `REGLA-NN` o el control que lo cumple.
- `balanceHypotheses`: como `frictionRisks`.

**`InteractionDesignValidator`** (Java, puro; 3 intentos con `CORRECCIÓN DEL INTENTO ANTERIOR`; agotados → `FAILED`
con los motivos, Neo no corre):
- ids con el formato exacto y únicos;
- **todo `ESC-NN` del spec está asignado** (en `scenarioScreens` o `scenarioRules`) y no hay asignaciones a ids
  inexistentes;
- `APP`: cada HU tiene un flujo; los flujos y asignaciones solo usan pantallas existentes; cada `role` existe en el
  spec;
- `GAME`: cada control se usa en alguna regla; las transiciones del loop van entre estados existentes; las
  propiedades de las entidades son números; existe al menos una condición `win` o `lose`;
- topes: 15 pantallas, 20 reglas, 8 controles (el diseño no puede crecer más que el MVP).

**Versiones** (`InteractionDesignMemoryService`, Cypher a mano):
`(:Mission)-[:HAS_INTERACTION_DESIGN]->(:InteractionDesignVersion {version, designJson, status, specVersion,
createdAt})`, `status` `DRAFT|APPROVED|SUPERSEDED|REJECTED`. `specVersion` ata cada diseño a la versión de HU sobre la
que se hizo. La decisión conjunta reclama **ambas** versiones con un CAS (extiende
`RequirementsMemoryService.claimDecision`): Java exige que el diseño en `DRAFT` tenga `specVersion` = la versión de HU
en `DRAFT`; si no, la decisión se rechaza (`IllegalStateException`).

**Prompt de Kael**: nueva versión (v3) por `AgentPromptService` que conserva su identidad de diseñador y agrega la de
programador de juegos 2D (parte 2): sin assets externos, azar inyectable, escena principal declarada.

### 1.4 Cómo el diseño guía el código

**Bloque en los prompts**: `InteractionDesignText.incrementBlock` (como `RequirementsText.incrementBlock`) agrega la
parte del diseño que tocan las HU del incremento en curso a la instrucción de Neo y de cada agente que programa:
- Mila: pantallas y flujos; **cada pantalla lleva `Key('PANT-NN')` en su widget raíz**.
- Kael (juego): su diseño completo del incremento como especificación de la capa `GAME`.
- Iris (si participa en un juego): `entities` y `rules` para Domain/Application.
- Vera: el mapa escenario → pantalla/regla; en apps sus tests de Flutter usan `find.byKey(Key('PANT-NN'))`; en juegos
  sus partidas usan las acciones de `controls`.

**Neo respeta el tipo** (`TeamPlanValidator`, reintento con el motivo):

| `experience` | El plan debe… |
|---|---|
| `GAME` | usar `GODOT_DOTNET_GAME` e incluir a Kael y a Vera |
| `APP` | tener un componente `FLUTTER_WEB_APP` e incluir a Mila |
| `NONE` | no tener componentes `FLUTTER_WEB_APP` ni `GODOT_DOTNET_GAME` |

**Incrementos**: Kael diseña una vez sobre todo el spec; cada incremento recibe solo la parte del diseño de sus HU. Un
incremento no vuelve a llamar a Kael.

**Chequeo `DESIGN_CONFORMANCE`** (Java, tras el sandbox, junto a `SCENARIO_COVERAGE`; solo en misiones con diseño
aprobado):
- `APP`: cada `PANT-NN` de las HU del incremento aparece como `Key('PANT-NN')` en algún `.dart` bajo `lib/`.
- `GAME`: cada `action` de `controls` está declarada en la sección `[input]` de `game/project.godot`, y las partidas
  simuladas (parte 2) solo usan acciones del diseño.
- `FAIL` → validación `FAILED` y corrección automática: el faltante vuelve al dueño del archivo (Mila o Kael) con la
  pantalla o acción exacta.
- Lo que no es verificable sin un modelo (balance, fricción, engagement) queda en el reporte como **hipótesis**, nunca
  como hecho.

## Parte 2 — Kael programador de juegos 2D

### 2.1 Habilitación y reparto

- `StackProfile.phase()`: `GODOT_DOTNET_GAME` pasa a fase 1 (`enabledNow`). `DevelopmentPhase.CURRENT` sigue en 1;
  Orion (`SPECIALIST_3D`), Luna (`CREATIVE`) y Andrea (`DEVOPS`) no cambian.
- `RoleLayerCatalog`: `GAME_DEV` pasa a fase 1, primaria `GAME`, **respaldo `DOMAIN` y `APPLICATION`**. Se mantiene
  `ROLE_PRIORITY`: si participa Iris, ella escribe las reglas y Kael la escena. `describe()` deja de decir "Kael
  todavía no habilitado".
- Vera conserva `TESTS` (`tests/{ctx}.Tests`) y suma la raíz compartida `game/playthroughs/` (`sharedRoots` del perfil
  con `Layer.TESTS`).
- `game/forjai/` es de Forjai: queda fuera de todo `ownedPaths` (un archivo de un agente ahí se descarta y se anota,
  como hoy los de Forjai).
- `StackProfile.describe()`/`enabledUsages()` ofrecen "Juegos Godot con C#" a Neo y a Aria sin tocar sus prompts.
- Modelo de Kael: `nvidia:moonshotai/kimi-k3` con el mismo `paidFallbackModel` y `fallbackModel` que Iris — cambio de
  datos por `PUT /api/company/agents/interaction-design/model`, sin código.

### 2.2 Gates deterministas nuevos (reintento con el motivo)

- **`GameAssetGate`**: rechaza en `game/` referencias a assets externos (`.png`, `.jpg`, `.svg`, `.wav`, `.ogg`,
  `.mp3`, `.ttf`, `.otf`, `.glb`, `.gltf`) en `.tscn`, `.tres` y `.cs`. Se dibuja con nodos (`ColorRect`,
  `Polygon2D`, `Label`) o en `_Draw()`.
- **`GameMainSceneGate`**: `game/project.godot` declara `run/main_scene` y ese `.tscn` existe en el workspace (o en la
  misma entrega).
- **`InjectableRandomGate`**: en Domain y Application prohíbe `new Random(`, `Random.Shared`, `GD.Randi`, `GD.Randf`,
  `GD.RandRange`; el azar entra como dependencia con semilla. Sin esto la partida simulada no es determinista.

### 2.3 Partida simulada

**Runner fijo** (`ProjectScaffold`, commit de Forjai, solo en `GODOT_DOTNET_GAME`):
- `game/forjai/Playthrough.cs`: contrato para Vera.
- `game/forjai/PlaythroughRunner.cs` + `game/forjai/Runner.tscn`: ejecutor.

Contrato (mínimo, para que un modelo no lo malinterprete):

```csharp
public sealed class ESC_03_puntaje_sube_al_rebotar : Playthrough {
    public override IEnumerable<Step> Steps(Node main) {
        yield return Press("move_right", frames: 30);
        yield return Wait(frames: 10);
        yield return Assert(() => main.GetNode<Main>(".").Score == 1, "el puntaje es 1");
    }
}
```

El id `ESC_NN` va en el nombre de la clase, como en xUnit. Vera ve la API pública de la escena de Kael
(`PublicApiExtractor`).

**Ejecución** — dentro del paso `test` de la imagen `godot-dotnet-game` (`run.sh`), después de `dotnet test`:
`godot --headless --fixed-fps 60 --path game res://forjai/Runner.tscn`. El runner:
1. fija `GD.Seed(0)`;
2. encuentra por reflexión las subclases de `Playthrough`;
3. por cada una instancia **una escena principal nueva** (la de `run/main_scene`), la agrega al árbol, ejecuta los
   pasos frame a frame inyectando inputs con `Input.ParseInputEvent` (`InputEventAction`) y evalúa las aserciones;
4. tope de 3.600 frames por partida; una excepción, una aserción falsa o el tope → `FAILED` de esa partida sin cortar
   las demás;
5. escribe `/work/.forjai/playthroughs.json`: `[{name, outcome PASSED|FAILED, failedStep, message}]` y sale.

El paso `test` falla si falla `dotnet test` o alguna partida. El paso `smoke` queda igual (la escena arranca 300 frames
sin errores).

**Resultados**: `TestReportParser` (sandbox-runner) suma las partidas de `playthroughs.json` a los tests del TRX
(`StepResult.tests`); un JSON ausente o corrupto cuenta 0 partidas sin romper el paso (lo frena `SCENARIO_COVERAGE`:
sin partidas no hay cobertura de los escenarios de interacción). `company-core`
los cuenta en `SCENARIO_COVERAGE` y `TEST_SUITE_GROWTH` sin cambios, y `ScenarioCoverage.componentTests` las marca
`[juego]`.

**Corrección**: una partida fallida vuelve por el ciclo de corrección existente — una aserción falsa al dueño de la
escena (Kael), una partida que no compila al dueño del archivo (Vera, por `CompilerErrorParser`).

**Riesgo a verificar primero (spike)**: que Godot 4.3 .NET headless cargue los assemblies que compiló `dotnet build`
(sin `--build-solutions`, sin red) y procese `Input.ParseInputEvent` sin ventana. Si falla, se para y se replantea con
el fundador antes de tocar `company-core`.

## 3. Lo que ve el fundador

**Chat** (`ChatIntentRouter`, reglas deterministas antes del CEO):
- "muéstrame el diseño de MISSION-X" → pantallas y flujos, o loop, reglas y controles (Java, sin modelo).
- "apruebo las HU de MISSION-X" → aprueba HU y diseño juntos (misma frase de hoy).
- "para MISSION-X, Kael: …" → ajuste solo a Kael (antes de la regla de ajuste a Aria).
- "dame un status" → en `AWAITING_SPEC_APPROVAL` muestra "HU + diseño"; en `AWAITING_INVESTOR`, `DESIGN_CONFORMANCE`
  junto a la cobertura.

**Command Center** (Missions): sección "Diseño (Kael)" junto a "Requisitos (Aria)", con versión y estado; los botones
de aprobar/ajustar/rechazar son los mismos, con un selector de destino del ajuste (Aria o Kael). Tipos nuevos en
`api/types.ts`; sin rutas nuevas.

**API**: `GET /api/company/missions/{id}/interaction-design`; `POST …/requirements/decision` acepta
`target: ARIA|KAEL` en `ADJUST` (default `ARIA`).

## 4. Errores

- Diseño inválido 3 veces → `FAILED` con los motivos; Neo no corre.
- Modelo de Kael caído → respaldo pago y suplente local (`ModelHealthService`, existente).
- Decisión con diseño de otra versión de HU, o fuera de `AWAITING_SPEC_APPROVAL` → `IllegalStateException`.
- `ADJUST` con `target: KAEL` en una misión `NONE` o sin diseño → `IllegalStateException`.

## 5. Eventos nuevos

`EMPRESA_INTERACTION_DESIGN_READY`, `EMPRESA_INTERACTION_DESIGN_ADJUSTED`. La aprobación y el rechazo siguen saliendo
como `EMPRESA_REQUIREMENTS_APPROVED`/`_REJECTED` (decisión conjunta), con `interactionDesignVersion` en el payload. Se
agregan a `docs/EVENTS.md`.

## 6. Orden de construcción

1. **Spike Godot headless** (assemblies + `Input.ParseInputEvent`) en la imagen actual. Throwaway; si falla, se para.
2. **Parte 1 en apps** — prueba en vivo: el CRM de clientes con `experience: APP` termina `VERIFIED` con
   `DESIGN_CONFORMANCE` en PASS.
3. **Parte 2 + parte 1 en juegos** — prueba en vivo: Pong de un jugador contra la pared ("la paleta se mueve con las
   flechas, la pelota rebota y el puntaje sube en cada rebote; si la pelota pasa, game over"). Aria declara `GAME`,
   Kael diseña, el fundador aprueba HU + diseño, Kael programa, Vera escribe tests y partidas → `VERIFIED` con
   `SCENARIO_COVERAGE` 100 % (≥ 1 partida `PASSED` por escenario de interacción) y `DESIGN_CONFORMANCE` en PASS.

Costo y rondas de cada prueba en `docs/HISTORY.md`.

## 7. Fuera de alcance (a `docs/FALTANTES.md`)

- Assets (sprites, sonido) con Orion y Luna.
- Game UI de Mila (HUD y menús en Godot).
- Exportar el juego para que el fundador lo juegue.
- Juegos 3D; juego + backend en una misión.
- Verificar balance o engagement con datos reales (Gael, telemetría).
- Prompts activos vacíos de Aria, Orion y Andrea (v1, 2026-10-01); el prompt de Mila dice React/Vue y genera Flutter.

## 8. Pruebas

Unitarios (JUnit + Mockito), `company-core`:
- `RequirementsSpecValidator`: `experience` ausente, inválido y válido.
- `InteractionDesignValidator`: cada regla de `APP` y de `GAME` (escenario sin asignar, asignación a id inexistente,
  HU sin flujo, pantalla inexistente, rol inexistente, control sin uso, transición a estado inexistente, propiedad no
  numérica, sin win/lose, topes).
- `InteractionDesignWriter` con modelo simulado (reintento con corrección).
- Flujo: Aria → Kael → `AWAITING_SPEC_APPROVAL`; `APPROVE` conjunto; `ADJUST` a Aria (rediseño) y a Kael (solo
  diseño); `REJECT`; policy en 0; `NONE` sin Kael; Kael apagado; CAS con versiones desalineadas; retome tras reinicio
  en la etapa de Kael.
- `TeamPlanValidator` contra `experience` (las tres filas).
- `RoleLayerCatalog`: Kael con `GAME` y con respaldo `DOMAIN`/`APPLICATION` sin Iris; con Iris, solo `GAME`.
- `GameAssetGate`, `GameMainSceneGate`, `InjectableRandomGate`: un rechazo y un caso válido cada uno.
- `ProjectScaffold`: genera `game/forjai/` en `GODOT_DOTNET_GAME`; un archivo de un agente en `game/forjai/` se
  descarta.
- `DESIGN_CONFORMANCE` en `APP` y `GAME` (PASS y FAIL).
- Chat: las reglas nuevas sin llamar a un modelo; endpoint del diseño.

`sandbox-runner`:
- `TestReportParser`: TRX + `playthroughs.json`; JSON ausente o corrupto (0 partidas, sin romper el paso).

Frontend: `npm run lint && npm run build`.
