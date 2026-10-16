# Juegos por niveles y escenas (2026-10-16)

## Por qué

El Pong 3 (`MISSION-KAEL-PONG-1792113455`) quedó `BLOCKED` tras 20 correcciones. Causas medidas en sus 16 rondas:

1. `Main.tscn` lo escribió Kael a mano y el nodo raíz no tenía el script: con el build verde, las 9 partidas cayeron en el paso 0 (`NullReferenceException`).
2. No había contrato entre Kael y Vera: las partidas usaban miembros de `Main` que no existían o cambiaban de nombre entre rondas (`Main.Pelota`, `FinalScoreLabel`, `EstadoJuego`).
3. Errores de sintaxis C# del modelo (todo lo escribió nemotron-3-super): fuera de alcance de este spec.

**Decisiones del fundador (2026-10-16):** un juego se parte en **escenas o niveles, cada uno con su HU acordada antes de construir**; el PO (Aria) propone la división y **la decide con el cliente (hoy, el fundador)**; cuando un nivel o escena se puede entregar solo, va en **su propio incremento** («tener avances del juego permite corregir errores tempranos»); si no, se agrupa. Ejemplo del fundador, Pong: nivel 1 pelota normal hasta 50 puntos; nivel 2 la pelota aumenta de velocidad; nivel 3 dos pelotas. Las tres partes de abajo van en una sola entrega.

## Parte 1: Aria parte el juego en niveles o escenas

Solo con `experience: GAME` (apps y `NONE` no cambian).

- `RequirementsSpec` suma `levels`: `[{id, kind, name, description, userStoryIds}]`.
  - `id`: `NIVEL-01`, `NIVEL-02`… en orden (el orden es el del juego).
  - `kind`: `LEVEL` (un nivel jugable) o `SCENE` (una escena que no es nivel: menú, game over, créditos).
  - `description`: qué tiene de distinto (p. ej. «la pelota aumenta de velocidad»).
  - `userStoryIds`: sus HU; **cada HU pertenece a exactamente un nivel o escena**.
- En un juego, cada incremento lista `levelIds` (niveles completos) en vez de `userStoryIds`; Java calcula `userStoryIds` del incremento como la unión de las HU de sus niveles, así que todo lo que hoy usa los incrementos (Neo, cobertura de escenarios, «construye el incremento N») sigue igual.
- Instrucciones a Aria (fijas en Java): un nivel o escena por incremento cuando se puede jugar o mostrar solo; si no, agrúpalos y explica por qué en el `goal` del incremento; el incremento 1 debe ser jugable de punta a punta; un nivel nunca se parte entre incrementos.
- `RequirementsSpecValidator` (Java, 3 intentos con la corrección, como hoy): en `GAME`, `levels` no vacío y con ids `NIVEL-NN` únicos y en orden; `kind` válido; cada HU en exactamente un nivel; cada nivel en exactamente un incremento; incrementos sin niveles o con un nivel inexistente → error; tope de 10 niveles; los topes de escenarios por incremento siguen.
- **Aprobación con el cliente (el fundador):** el mensaje de `AWAITING_SPEC_APPROVAL`, el detalle de la misión y el chat («muéstrame las historias de MISSION-X») muestran la división: «Incremento 1 (goal): NIVEL-01 Nivel 1 — HU-01, HU-02 · Incremento 2 (goal): NIVEL-02 …». El fundador aprueba o ajusta con lo que ya existe («para MISSION-X: el nivel 3 va en su propio incremento»); Aria rehace y vuelve a esperar. Nada se construye sin esa aprobación.
- `RequirementsText.incrementBlock` (lo que ven Neo y los agentes) suma los niveles del incremento con su descripción.

## Parte 2: Kael diseña por nivel y declara las escenas

- `GameDesign` suma:
  - `scenes`: `[{id, name, script, purpose}]` — `ESCENA-01`, `ESCENA-02`…; `script` es el nombre de la clase C# y del archivo (`^[A-Z][A-Za-z0-9]*$`, p. ej. `Main`, `Menu`); `ESCENA-01` es la escena principal (`run/main_scene`).
  - `levels`: `[{level, scene, parameters, advanceRule}]` — por cada nivel de Aria: la escena donde ocurre, sus parámetros numéricos (`[{name, value}]`, p. ej. `velocidad_pelota=200`, `pelotas=1`, `puntaje_meta=50`) y la regla (`REGLA-NN`) que pasa al siguiente nivel (vacía solo en el último).
- `InteractionDesignValidator.validateGame` suma: cada nivel de Aria tiene exactamente un `levels` en el diseño y no hay de más; escenas con ids únicos, `script` válido y único; cada `levels.scene` existe; `advanceRule` existe en `rules` (obligatoria salvo en el último nivel); tope de 8 escenas.
- `InteractionDesignText.incrementBlock` (lo que ven Kael, Iris y Vera por incremento) suma los niveles del incremento con sus parámetros y escenas.
- La revisión conjunta (Neo + Aria) revisa también niveles y escenas: `JointReviewValidator` acepta `NIVEL-NN` como referencia de requisitos y `ESCENA-NN`/`NIVEL-NN` del diseño como referencia de diseño.

## Parte 3: escenas válidas generadas por Forjai y contrato con Vera

**3.1 Esqueletos de escena.** Al planificar cada incremento, Forjai commitea (como hoy el scaffold) un `game/<script>.tscn` válido por cada escena que usan los niveles de ese incremento y de los anteriores, si no existe:

```
[gd_scene load_steps=2 format=3]

[ext_resource type="Script" path="res://<script>.cs" id="1"]

[node name="<script>" type="Node2D"]
script = ExtResource("1")
```

- Esos `.tscn` quedan reservados (`ReservedGamePathsGate`: «game/Main.tscn es de Forjai: no lo escribas»).
- Kael escribe `game/<script>.cs` como `public partial class <script> : Node2D` y **crea por código en `_Ready` todos los nodos que usa** (`AddChild`); nunca espera hijos en la escena. Gate nuevo `GameSceneScriptsGate` (Java): cada escena del incremento tiene su script con esa clase; `project.godot` declara `run/main_scene="res://<script de ESCENA-01>.tscn"` (`GameMainSceneGate` lo compara con el diseño).
- **Niveles en el runner:** cada escena con niveles expone `public int Level { get; }` y `public void StartLevel(int level)` (lo exige `GameSceneScriptsGate`). El runner de Forjai (`GameRunnerFiles`, fijo) suma a `Playthrough`:
  - `public virtual string Scene => null` (null = escena principal; si no, el `script` de otra escena);
  - el paso `Do(Action<Node> action)` para preparar el estado, p. ej. `Do(main => ((Main)main).StartLevel(2))`.
  Así una partida del nivel 2 no tiene que jugar el nivel 1 entero (tope de 3.600 frames).

**3.2 Contrato de escena para Vera.** Vera genera después de Kael en el incremento: hoy `DevelopmentTeamStrategy.orderTasks` los empata (las rutas de Vera en `game/playthroughs` caen en la capa `GAME`, igual que las de Kael) y el orden queda librado al plan de Neo; en juegos, la tarea de QA pasa a ir siempre al final. Antes de pedirle sus partidas, Java lee los scripts de escena commiteados y extrae, sin modelo, los miembros públicos de cada clase (propiedades, campos y métodos con su tipo), y se los pasa como «CONTRATO DE LAS ESCENAS: solo puedes usar estos miembros». En una ronda de corrección el contrato se recalcula desde el último commit.

**3.3 Chequeo antes del sandbox.** Gate nuevo `PlaythroughContractGate` (Java, sobre los archivos de Vera en `game/playthroughs/`): reconoce `(X)main`, `main as X` y las variables asignadas con ellos; `X` debe ser una escena del diseño; cada `variable.Miembro` o `((X)main).Miembro` debe estar en el contrato de `X`; `Scene` debe nombrar una escena del diseño. Un error vuelve a Vera con el motivo exacto («`Main.Pelota` no existe; existen: Paleta, Puntaje, Level, StartLevel…») antes de gastar una corrida del sandbox. Lo que el gate no reconoce (otros patrones) lo sigue juzgando el compilador en el sandbox.

## Compatibilidad

- Un spec `GAME` ya aprobado sin `levels` (misiones viejas) sigue funcionando como hoy: sin `scenes` en el diseño, Forjai no genera esqueletos ni aplica los gates nuevos.
- El cambio del runner es compatible: `Scene` y `Do` son opcionales; las partidas existentes compilan igual.

## Qué ve el fundador

- La división por niveles y su incremento en la aprobación (mensaje, detalle de la misión, chat) y los ajustes de siempre.
- En el diseño de Kael (detalle y «muéstrame el diseño de MISSION-X»): escenas y niveles con sus parámetros.
- En el reporte de verificación, las partidas por nivel.

## Pruebas

- `RequirementsSpecValidator`: niveles válidos; HU sin nivel o en dos; nivel sin incremento o en dos; ids desordenados; `kind` inválido; tope; Java calcula `userStoryIds` del incremento; apps y `NONE` sin cambios.
- `InteractionDesignValidator`: nivel de Aria sin diseño o de más; escena inexistente; `script` inválido o repetido; `advanceRule` inexistente o faltante (salvo el último).
- `ProjectScaffold`: un `.tscn` válido por escena del incremento y de los anteriores, sin pisar uno existente; sin `scenes`, nada.
- `ReservedGamePathsGate`, `GameSceneScriptsGate`, `GameMainSceneGate` (contra el diseño).
- Extractor del contrato: propiedades, campos, métodos, `partial`, comentarios y strings que no confunden.
- `PlaythroughContractGate`: miembro inexistente, válido, alias por variable, `as`, escena inexistente en `Scene`.
- Runner (`GameRunnerFiles`, verificado en el sandbox como en Kael híbrido): `Scene` carga otra escena y `Do` prepara el nivel.
- Textos de aprobación y chat; frontend lint + build.
- **En vivo** (con OK del fundador): el Pong con los 3 niveles del fundador. Aria debe proponer la división, el fundador la aprueba o ajusta, y el incremento 1 debe terminar `VERIFIED`; luego «construye el incremento 2».

## Fuera de alcance

- Validar con un cliente real (hoy el cliente es el fundador).
- Encadenar los incrementos automáticamente (pendiente aparte).
- La calidad de sintaxis C# del modelo (causa 3).
- Assets, sonido y pantallas elaboradas (siguen fuera, como en Kael híbrido).
