# Contrato, épicas y juegos por niveles (2026-10-16) — entrega A

Reemplaza a `2026-10-16-juegos-por-niveles-design.md` (y a su plan): la Parte 1 de ese spec (niveles dentro del spec de
Aria) se reemplaza por el contrato y las épicas; sus Partes 2 y 3 (Kael por nivel, escenas de Forjai, contrato con Vera)
se conservan con los cambios de abajo. Para no confundirlo con el contrato del cliente, el «contrato con Vera» se llama
aquí **ficha de la escena**.

## Por qué

El Pong 3 (`MISSION-KAEL-PONG-1792113455`) quedó `BLOCKED` tras 20 correcciones: `Main.tscn` sin script en el nodo raíz
(9 partidas caídas en el paso 0) y partidas de Vera con miembros que Kael no expone (`Main.Pelota`). Además faltaba el
documento de origen: Aria salta del mensaje del fundador directo a las HU.

**Decisiones del fundador (2026-10-16):**
- Un juego es igual que una aplicación (solo suma animación y sonido): en un juego los módulos se cambian por escenas o
  niveles y todo funciona de la misma forma.
- Faltaba el **contrato**: el PO lo levanta con el cliente (hoy el fundador); es la solicitud del cliente — qué quiere y
  los módulos o niveles que pidió — sin mucho detalle. **Del contrato salen las épicas, las HU y todos los documentos que
  ingeniería necesita después** (diseño de Kael, plan de Neo).
- **Épicas opcionales**: agrupan las HU que tocan el mismo módulo (p. ej. Inventarios en un software contable) o el
  mismo nivel o escena; una aplicación pequeña no las necesita. **Aria propone si lleva épicas y el fundador lo aprueba**
  con el contrato.
- Un nivel que se puede entregar solo va en su propio incremento («permite corregir errores tempranos»).
- Primero esta entrega A; después la entrega B (refinar HU tras cada entrega y ajustar lo construido).

## Flujo

```
1. CONTRATO      Aria, desde el pedido                         → el fundador aprueba o ajusta
2. HU (+épicas)  Aria, desde el contrato aprobado              ┐
3. DISEÑO        Kael (apps y juegos) + revisión conjunta      ┘→ el fundador aprueba o ajusta (como hoy)
4. PLAN          Neo por incremento                            → construcción, sandbox, verificación (sin cambios)
```

Vale para todo el Development Group (APP, GAME y NONE). Las misiones sin contrato (anteriores a esto) siguen como hoy.

## Parte 1: el contrato

Nuevo record `ClientContract` (paquete `agent.model`):

- `summary`: qué se pide, 2-3 frases.
- `experience`: `APP | GAME | NONE` (se decide aquí; el spec de Aria la hereda y Java exige que coincida).
- `items`: `[{id, kind, name, description}]` — lo que pidió el cliente, una línea cada uno.
  - `id`: `MOD-01`, `MOD-02`… únicos y en orden (en un juego, el orden de los niveles).
  - `kind`: `MODULE` o `SYSTEM` en APP/NONE; `LEVEL`, `SCENE` o `SYSTEM` en GAME. `SYSTEM` = sistema base que usan
    varios (controles y puntaje, usuarios, guardado). `SCENE` = escena que no es nivel (menú, game over).
  - Máximo 10.
- `outOfScope`: lo que queda fuera.
- `useEpics` (boolean) y `epicsReason` (por qué sí o por qué no).

**Escritura:** `ContractWriter` (como `RequirementsWriter`: instrucciones fijas en Java, 3 intentos con la corrección,
cada contrato válido es una versión DRAFT en Neo4j `(:Mission)-[:HAS_CONTRACT]->(:ContractVersion)`).
`ContractValidator` (Java): campos obligatorios, ids `MOD-NN` en orden, `kind` válido para la `experience`, tope de 10,
`epicsReason` no vacía; en GAME al menos un `LEVEL`, y con 2 o más `LEVEL`/`SCENE`, `useEpics` debe ser `true`.

**Aprobación:** la misión queda en `AWAITING_SPEC_APPROVAL` (el mismo estado de hoy; la etapa dice «Contrato listo
(Aria)»). Los mismos comandos y botones deciden lo que está en DRAFT: si es el contrato, `APPROVE` lo aprueba y arranca
el paso 2; `ADJUST` («para MISSION-X: el nivel 3 tiene dos pelotas») hace que Aria reescriba el contrato y vuelve a
esperar; `REJECT` cancela la misión. Con la policy `SPEC_APPROVAL_REQUIRED` en 0, el contrato se aprueba por policy como
hoy las HU. Doble clic: el mismo claim atómico que los requisitos.

**Qué ve el fundador** (mensaje de la etapa, detalle de la misión, chat «muéstrame el contrato de MISSION-X»):

```
Contrato de MISSION-X (versión 1, juego): Un Pong de 3 niveles…
MOD-01 [Sistema] Controles y puntaje — …
MOD-02 [Nivel] Nivel 1 — pelota normal hasta 50 puntos
MOD-03 [Nivel] Nivel 2 — la pelota aumenta de velocidad
MOD-04 [Nivel] Nivel 3 — dos pelotas
Fuera de alcance: … · Épicas: sí (un nivel por épica).
```

## Parte 2: HU y épicas desde el contrato

- Aria escribe las HU con el **contrato aprobado** en el prompt (en vez del pedido suelto; el pedido original va como
  referencia). Mantiene todo lo de hoy (roles, escenarios `ESC-NN`, incrementos, supuestos).
- `UserStory` suma `contractItem` (`MOD-NN`): el ítem del contrato al que pertenece.
- **Las épicas no las escribe el modelo**: con `useEpics`, cada ítem del contrato es una épica y sus HU son las que lo
  referencian (Java las agrupa). Sin `useEpics`, las HU se muestran sin agrupar.
- `RequirementsSpecValidator`, con contrato aprobado: cada HU tiene un `contractItem` existente; **cada ítem del contrato
  tiene al menos una HU** (nada pedido queda sin HU); `experience` igual a la del contrato; en GAME, todas las HU de un
  `LEVEL` o `SCENE` van en el mismo incremento (un nivel no se parte). Sin contrato aprobado (misiones viejas), nada de
  esto aplica.
- Instrucciones a Aria (fijas en Java): un nivel, escena o módulo por incremento cuando se puede usar o jugar solo; si
  no, agrúpalos y explica por qué en el `goal`; el incremento 1 funciona de punta a punta (en un juego, jugable).
- `RequirementsText.summary` (aprobación, detalle, chat) e `incrementBlock` (lo que ven Neo y los agentes) agrupan las HU
  por épica cuando `useEpics`: «Incremento 2 (goal): Épica MOD-03 Nivel 2 — HU-04, HU-05».
- El ajuste de las HU (`para MISSION-X: …` en la segunda parada) no cambia el contrato; para cambiar el contrato en esa
  parada, se rechaza y se vuelve a pedir (cambiar un contrato aprobado es de la entrega B).

## Parte 3: Kael diseña cada nivel y declara las escenas (solo GAME)

- `GameDesign` suma:
  - `scenes`: `[{id, name, script, purpose}]` — `ESCENA-01`…; `script` = clase C# y archivo
    (`^[A-Z][A-Za-z0-9]*$`, distinto de `Playthrough`, `PlaythroughRunner`, `Step`, `StepKind`); `ESCENA-01` es la
    escena principal (`run/main_scene`); máximo 8.
  - `levels`: `[{item, scene, parameters, advanceRule}]` — uno por cada ítem `LEVEL` o `SCENE` del contrato: la escena
    donde ocurre; en un `LEVEL`, sus parámetros numéricos (`[{name, value}]`, p. ej. `velocidad_pelota=200`,
    `pelotas=1`, `puntaje_meta=50`) y la regla `REGLA-NN` que pasa al siguiente nivel (vacía solo en el último).
- `InteractionDesignValidator.validateGame`, con contrato: cada `LEVEL`/`SCENE` del contrato tiene exactamente un
  `levels` y no hay de más; escenas con ids únicos, `script` válido y único; cada `levels.scene` existe; `advanceRule`
  existe en `rules` (obligatoria salvo en el último `LEVEL`).
- `InteractionDesignText` (aprobación, detalle, chat y bloque por incremento para Kael, Iris y Vera) muestra escenas y
  niveles con sus parámetros.
- La revisión conjunta acepta `MOD-NN` como referencia de requisitos y `ESCENA-NN` como referencia de diseño.
- En APP no cambia nada: Kael sigue diseñando pantallas.

## Parte 4: escenas de Forjai y ficha de la escena para Vera (solo GAME con escenas)

Igual que la Parte 3 del spec reemplazado:

- **Esqueletos:** al planificar cada incremento, Forjai commitea (commit propio `<missionId>-SCENES`, `-R<n>` en las
  rondas) un `game/<script>.tscn` por cada escena que usan los niveles del incremento y de los anteriores, si no existe:

  ```
  [gd_scene load_steps=2 format=3]

  [ext_resource type="Script" path="res://<script>.cs" id="1"]

  [node name="<script>" type="Node2D"]
  script = ExtResource("1")
  ```

  Esos `.tscn` quedan reservados para Forjai (`ReservedGamePathsGate`).
- **Kael** escribe `game/<script>.cs` como `public partial class <script> : Node2D` y crea por código en `_Ready` todos
  los nodos que usa. `GameSceneScriptsGate` (Java): cada escena del incremento tiene su script con esa clase, y la
  escena donde ocurre un nivel expone `public int Level { get; }` y `public void StartLevel(int level)`.
  `GameMainSceneGate` compara `run/main_scene` con el `script` de `ESCENA-01`.
- **Runner** (`GameRunnerFiles`, fijo): `Playthrough` suma `public virtual string Scene => null` (null = principal) y el
  paso `Do(Action<Node>)` para preparar el estado (`Do(main => ((Main)main).StartLevel(2))`), así una partida del nivel 2
  no juega el nivel 1 entero. Compatible con las partidas existentes. Se verifica a mano en el sandbox de Godot.
- **Ficha de la escena:** en un juego, la tarea de QA (Vera) va siempre al final del incremento. Antes de pedirle sus
  partidas, Java extrae sin modelo los miembros públicos de cada script de escena commiteado (propiedades, campos y
  métodos con su tipo) y se los pasa como «FICHA DE LAS ESCENAS: solo puedes usar estos miembros». En una ronda de
  corrección se recalcula desde el último commit.
- **Chequeo antes del sandbox:** `PlaythroughContractGate` (sobre `game/playthroughs/`) reconoce `(X)main`, `main as X`
  y sus variables; `X` debe ser una escena del diseño, cada miembro usado debe estar en la ficha de `X` o en la lista
  fija de lo heredado de Godot (`Position`, `GetNode`…), y `Scene` debe nombrar una escena. Un error vuelve a Vera con el
  motivo exacto («`Main.Pelota` no existe; existen: Paleta, Puntaje, Level, StartLevel…») sin gastar una corrida del
  sandbox. Lo que no reconoce lo juzga el compilador.

## Compatibilidad

- Misión sin contrato (creada antes de esto): HU, diseño y gates como hoy.
- Juego sin `scenes` en el diseño: Forjai no genera esqueletos ni aplica los gates nuevos.
- Recuperación tras reinicio (`ResumePoint`): etapas nuevas `CONTRACT` (escribir el contrato) y `CONTRACT_ADJUSTMENT`;
  `AWAIT_APPROVAL` cubre también el contrato en DRAFT; con el contrato aprobado y sin HU, se retoma en `REQUIREMENTS`
  sin repetir el contrato.

## Pruebas

- `ContractValidator`: ids, kinds por experience, tope, GAME sin `LEVEL`, GAME con varios niveles sin épicas.
- Aprobar, ajustar y rechazar el contrato (y el doble clic); policy en 0; retome en cada etapa nueva.
- `RequirementsSpecValidator` con contrato: HU sin ítem o con ítem inexistente; ítem sin HU; experience distinta; nivel
  partido entre incrementos; sin contrato, sin cambios.
- Textos: contrato, HU agrupadas por épica y sin épicas; chat «muéstrame el contrato de MISSION-X».
- `InteractionDesignValidator`, esqueletos, gates, extractor de la ficha, `PlaythroughContractGate` y runner (como en el
  spec reemplazado). Frontend: lint + build (contrato en el detalle de la misión; épicas en las HU; escenas y niveles en
  el diseño).
- **En vivo** (con OK del fundador, sin misiones vivas): el Pong de 3 niveles. Aria propone el contrato, el fundador lo
  aprueba o ajusta, luego HU + diseño; el incremento 1 debe terminar `VERIFIED`; luego «construye el incremento 2».

## Fuera de alcance (entrega B y otros)

- Refinar HU tras cada entrega, ajustar lo construido y cambiar un contrato aprobado (entrega B).
- Encadenar incrementos solos; validar con un cliente real; calidad de sintaxis C# del modelo; assets y sonido.
