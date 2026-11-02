# Front con diseño visual: Mila y Orion (etapa 1, apps)

Fecha: 2026-11-01. Decisiones del fundador en la conversación de ese día. Las marcadas **[decisión del builder]** las tomó Claude y el fundador puede revisarlas.

## Intención

Hoy el aspecto visual de las apps no lo diseña nadie. Kael diseña la estructura (pantallas, flujos, escenario por pantalla), Mila programa y decide los colores sobre la marcha, y cada proyecto sale con el estilo que le toque. El fundador lo resumió así: «problema de muchos, problema de nadie; una cosa es que alguien diga qué colores, otra que alguien lo haga, y hoy los de front no lo hacen».

Hay una segunda razón: en front solo trabaja Mila. Si falla o está ocupada, nadie más sabe Flutter.

**Rol del front en Forjai (definición del fundador):**
- **Apps:** diseña aplicaciones visualmente amigables y fáciles de usar, y las construye con Flutter, para que una sola app sirva en cualquier dispositivo.
- **Juegos:** crea y diseña personajes y ambientes 2D y 3D (Godot `Skeleton2D`, `Polygon2D`, `AnimationPlayer`, y Blender para 3D).

**Quién es front y quién es back (fundador, 2026-11-01):** los front son **solo dos, Mila y Orion**. Kael **no** es front: es back junto con Iris, porque los dos programan en C# (Kael en juegos Godot y, como cambio aparte, también en .NET; Iris en .NET y en las reglas de los juegos Godot). Kael sigue diseñando la estructura de las apps (pantallas y flujos) y la mecánica de los juegos.

| Agente | Área | Lenguaje | Rol |
|---|---|---|---|
| Iris | Back | C# (.NET y Godot) | Backend |
| Kael | Back | C# (Godot y .NET) | Juegos |
| Diego | Back | SQL (PostgreSQL) | Datos |
| Mila | Front | Dart (Flutter) + Blender | Interfaz |
| Orion | Front | Dart (Flutter) + Blender | Interfaz |

Esta etapa cubre **solo apps**. Las etapas de juegos se describen al final y quedan fuera.

## Metas

1. **Mila y Orion son dos front intercambiables.** Los dos saben Flutter, pueden trabajar juntos en un proyecto o cada uno en uno distinto, y Neo los asigna como asigna a los demás.
2. **El front diseña el aspecto antes de construir.** Entrega un *sistema visual* (paleta, tipografía, tamaños, estilo de componentes) y una *maqueta* por cada pantalla de Kael, en HTML, CSS y SVG escritos a mano.
3. **El fundador ve y aprueba las maquetas** como imágenes, junto con las HU de Aria y el diseño de Kael.
4. **Java garantiza que se aplique lo aprobado.** Genera el tema de Flutter desde el sistema visual, rechaza colores y estilos escritos a mano y compara cada pantalla construida con su maqueta.
5. **Se ahorran tokens.** El modelo nunca recibe imágenes: las correcciones de diseño son texto corto que arma Java, y se manda solo el archivo de la pantalla afectada.

## No metas

- Juegos (personajes con esqueleto, Blender, Godot 3D): son las etapas 2 y 3.
- Una herramienta de diseño gráfica (Figma, Penpot). Se descartó porque el agente no puede manejar una interfaz gráfica, y el HTML, CSS y SVG a mano son texto que sí escribe.
- Habilitar ya que el PO decida las diferencias de diseño. Por ahora decide el fundador. Esta etapa deja lista la política y el punto de decisión para pasarle la tarea al PO cuando se activen los demás modelos (ver paso 15).
- Cambios a Kael (que también programe .NET) y a Iris (que pueda cubrir la escena de un juego). Se pidieron en la misma conversación, pero son un cambio aparte.

## Anti-metas (lo que sería un fracaso aunque funcione)

- Que el aspecto lo vuelvan a decidir varios. El responsable del sistema visual es **el front asignado a la misión**. Si hay dos front, lo escribe uno solo **[decisión del builder: el primero que Neo nombre; el otro lo recibe aprobado]**.
- Que la app final se aparte en silencio de la maqueta aprobada.
- Que el ciclo de corrección de diseño gaste tokens sin límite. Son 2 rondas como máximo y después decide el fundador.
- Que se le pida al modelo revisar su propio diseño. Toda comparación la hace Java (convención del proyecto: determinismo sobre LLM).

## Flujo

### Antes de construir (aprobación junto con las HU)

1. Aria escribe el contrato y las HU, y Kael diseña las pantallas y los flujos (`AppDesign`), como hoy.
2. **El front escribe el sistema visual y una maqueta por pantalla.**
   - **Sistema visual:** un JSON validado por Java con colores con nombre (`primary`, `onPrimary`, `surface`, `error`…), tipografías y escala de tamaños, espaciados, radios de borde y estilos de componentes (botón, campo, tarjeta, barra).
   - **Maqueta:** un HTML por pantalla de Kael. Usa **solo** variables CSS (`var(--color-primary)`), que **Java genera** desde el sistema visual. Los SVG de íconos, logo e ilustraciones van como archivos aparte.
   - Cada elemento relevante lleva `data-id="<id>"`, y ese mismo id es la `Key` del widget en Flutter.
3. **Java valida, sin modelo:**
   - que cada pantalla de `AppDesign` tenga su maqueta;
   - que la maqueta no tenga colores literales (`#…`, `rgb(`) fuera de `:root`, que lo escribe Java;
   - que los pares texto/fondo del sistema visual cumplan contraste WCAG AA (4,5:1 en texto normal);
   - que los SVG no tengan scripts ni referencias externas.

   Si algo falla, vuelve al front como `CORRECCIÓN DEL INTENTO ANTERIOR`, como los otros writers.
4. **El sandbox renderiza cada maqueta a PNG** con el Chrome sin pantalla que ya trae la imagen `flutter-web-app`. Lo hace en un paso nuevo del catálogo, sin red, y también devuelve las cajas (`getBoundingClientRect`) y el color de fondo de cada `data-id`. Son dos tamaños: teléfono (390×844) y escritorio (1280×800) **[decisión del builder: tamaños de referencia]**.
5. La misión espera en `AWAITING_SPEC_APPROVAL`. El fundador ve en el Command Center las HU, el diseño de Kael, el sistema visual (muestras de color y tipografía) y las imágenes de las maquetas. Aprueba o pide ajustes, igual que hoy.

### Al construir

6. **Java escribe `lib/theme/app_theme.dart`** (`ThemeData` y constantes `AppTheme.*`) desde el sistema visual aprobado y lo commitea con el scaffold. Los SVG aprobados se copian a `assets/` y se declaran en `pubspec.yaml`. `flutter_svg` entra en las dependencias base **[decisión del builder: versión exacta a fijar al implementar]**.
7. El front construye las pantallas en PRESENTATION. Un **gate nuevo** rechaza, en los archivos de presentación, `Color(0x…)`, `Colors.<x>`, `TextStyle(` con `fontSize` o `color` literales, y `fontFamily:`. Todo tiene que salir de `AppTheme` o de `Theme.of(context)`. Va con reintento y motivo, como los otros gates.
8. **Vista previa por pantalla** **[decisión del builder]**. El front entrega, por cada pantalla de Kael, una vista previa con datos de ejemplo y el mismo estado de la maqueta (lleno, vacío o error). Va en `lib/<ctx>/presentation/previews/<pantalla>_preview.dart` y se registra en una tabla `screenId → widget` que Java valida. La app web abre una vista previa con `?preview=<screenId>`. Con esto Java sabe qué archivo corresponde a cada pantalla, sin conocer los constructores reales.
9. **Captura de la app real** **[decisión del builder]**. No se usan goldens de `flutter test`, porque usan la fuente Ahem (texto en bloques) y su propio rasterizador, y nunca se parecerían a la maqueta. El paso nuevo del sandbox abre la **build web** en el mismo Chrome sin pantalla que renderiza las maquetas: carga cada `?preview=<screenId>` en los dos tamaños y guarda el PNG y las cajas de cada `Key`. Las cajas se exponen con `Semantics(identifier: <Key>)`, que Flutter web escribe en el DOM. Como Flutter dibuja los mismos pixeles en Android, iOS y web, esta captura vale para los tres. Al no ser tests, no van en `test/`, no son de Vera y no cuentan para `TestInventory` ni para `TestSuiteGrowthGate`. Los PNG se guardan en `.forjai/`, fuera del repo del producto.
10. **Fuentes** **[decisión del builder]**. El sistema visual solo puede elegir de un catálogo cerrado de fuentes que trae la imagen del sandbox (archivos `.ttf` libres). Java las copia a `assets/fonts/`, las declara en `pubspec.yaml` y las referencia en el `@font-face` de la maqueta. Así los dos renders usan exactamente los mismos archivos.

### Comparación de diseño (después de `test`, antes de dar el incremento por VERIFIED)

11. Java compara, para cada pantalla y tamaño:
   - **similitud global** (SSIM) entre el PNG de la maqueta y el golden de Flutter;
   - **por elemento** (`data-id` = `Key`): posición, tamaño y color dominante, con tolerancia **[decisión del builder: ±8 px de posición y tamaño, y el color debe ser el mismo token]**, además de los elementos que faltan o que sobran.
12. **Umbral:** SSIM ≥ 0,90 y ningún elemento faltante. Es el valor de ejemplo de la conversación y se edita en el Command Center. **[decisión del builder]** Se calibra con la primera misión real antes de darlo por bueno: si una pantalla correcta a ojo da menos, se ajusta y se registra en HISTORY.
13. **Si no alcanza el umbral**, Java le manda al front solo:
    - el archivo de esa pantalla;
    - `app_theme.dart`;
    - una corrección corta generada por Java (elementos que no coinciden, valores de la maqueta contra los de Flutter, y «lo demás coincide: no lo cambies»).

    Así no se reenvía el contexto completo del proyecto. Ejemplo de la conversación: «btn-ingresar: maqueta y=520 alto=48 color=primary; Flutter y=580 alto=40 color=#6750A4, que no está en la paleta».
14. **Máximo 2 rondas.** Si la pantalla sigue sin alcanzar el umbral, la misión queda esperando al fundador. El Command Center muestra la maqueta y la captura lado a lado, con las diferencias marcadas. El fundador puede:
    - **aceptar** la pantalla como está (queda registrado);
    - **pedir otra ronda** con un comentario, que se agrega a la corrección.

    El chat responde «¿cómo va el diseño de MISSION-X?» con el puntaje por pantalla, sin modelo.

    **[decisión del builder]** sobre cómo encaja en el ciclo de la misión:
    - **Estado:** la misión pasa a `BLOCKED`, que ya significa «un freno de la corrección automática: decide el fundador». El motivo es `DESIGN_MISMATCH` y lleva la lista de pantallas.
    - **No es una falla:** una diferencia de diseño no es `FAILED` ni entra al ciclo de «corregir sin preguntar», porque la decisión es del fundador. El código y los tests pueden estar en verde.
    - **Cuándo queda VERIFIED:** solo cuando todas las pantallas alcanzan el umbral o el fundador las aceptó. `StaticValidationStatus.compute` suma esa condición.
    - **Decisión por pantalla:** «aceptar» vale para esa pantalla. Cuando no queda ninguna pendiente, el incremento sigue solo.
    - **Otra ronda:** «pedir otra ronda» da **una** ronda más con el comentario del fundador, no reinicia el contador de 2. Si tampoco alcanza el umbral, vuelve a esperar al fundador.
    - **Reinicios:** `MissionRecoveryService` deja la misión en `BLOCKED` como cualquier otro freno.

15. **Quién decide es temporal** (decisión del fundador, 2026-11-01). Que decida el fundador es solo mientras Forjai trabaja con el Development Group. Cuando se activen los demás modelos, decidirá el **PO** y el fundador dejará de intervenir en esta tarea.
    - **Política editable:** quién decide es una política versionada en `CompanyPolicyService`, por ejemplo `designMismatchDecider` con los valores `FOUNDER` (el valor inicial) o `PRODUCT_OWNER`. Se edita en el Command Center y se consulta en el chat («¿quién decide las diferencias de diseño?»). Java la resuelve, no un modelo.
    - **Un solo punto de decisión:** aceptar o pedir otra ronda pasa siempre por la misma operación, con un `decidedBy` que guarda quién decidió y por qué, y queda en la bitácora de la misión. Lo que cambia según la política es solo **quién** la invoca: el fundador desde el Command Center, o el PO.
    - **Con `PRODUCT_OWNER`:** la misión no queda en `BLOCKED` esperando al fundador. El PO recibe la corrección de Java (el texto por elemento, sin imágenes) más las HU y los escenarios de la pantalla, y responde con `format` sin `tools`: aceptar (con el motivo) u otra ronda (con el comentario). Java valida la respuesta y la aplica por la misma operación. El fundador puede ver la decisión en la bitácora y anularla.
    - **Alcance de esta etapa:** se construyen la política y el punto de decisión. El camino del PO queda **sin habilitar** hasta que el fundador active los demás modelos: el valor `PRODUCT_OWNER` se rechaza al guardar, con un mensaje claro, hasta entonces **[decisión del builder]**.

## Alcance por incremento **[decisión del builder]**

- **Sistema visual:** uno por proyecto. Se escribe y se aprueba en el primer incremento con interfaz. En un incremento posterior el front puede proponer cambios, pero solo como una versión nueva que el fundador aprueba junto con las HU de ese incremento. Al aprobarla, Java regenera `app_theme.dart`.
- **Maquetas:** solo las pantallas que el incremento en curso crea o modifica. Las de incrementos anteriores ya aprobadas se conservan y no se vuelven a comparar, salvo que el incremento toque esa pantalla.
- **Misiones existentes:** las que ya están en curso o verificadas sin sistema visual quedan fuera del gate de colores y de la comparación. Las reglas nuevas solo aplican a misiones que tengan un sistema visual aprobado.

## Mila y Orion intercambiables

- **Rol de Orion** **[decisión del builder]**: pasa a tener el **mismo rol `UI_UX`** que Mila, en vez de `SPECIALIST_3D` con las capas copiadas. Así `ROLE_PRIORITY`, `TeamPlanResolver` y `TeamPlanValidator` lo tratan como a Mila sin casos especiales. Se habilita en `DevelopmentPhase.CURRENT`. Sus capacidades en `TeamMemoryService` suman Flutter, diseño visual, maquetas HTML/CSS/SVG y Blender (esto último para la etapa 3). Su modelo es `bedrock:global.moonshotai.kimi-k3`, el mismo del 2026-10-29; la semilla de `TeamMemoryService` no lo pisa porque manda Neo4j.
- **Juntos en un proyecto:** Neo reparte por contexto o por pantallas, y `TeamPlanValidator` ya exige rutas sin solapar. **[decisión del builder]** El primer front que Neo nombra es dueño de los archivos de entrada (`lib/main.dart`, `pubspec.yaml`) y del sistema visual. El segundo recibe ambos ya hechos y solo escribe las pantallas y vistas previas de sus contextos. Si el segundo necesita un cambio en la entrada (una ruta, un asset), lo pide en su resultado y Java lo aplica, igual que hoy con las dependencias.
- **En proyectos distintos:** cada uno toma una misión. El orquestador ya corre 2 misiones a la vez.
- Los dos usan el mismo modelo que los demás agentes de código (Bedrock kimi-k3, decisión del 2026-10-29).

## Restricciones

- Las convenciones de `CLAUDE.md`, en particular: nunca `format` y `tools` en la misma llamada (el writer del sistema visual y las maquetas usa `format` sin `tools`); Jackson 3; nada de `@Async`; eventos `EMPRESA_*`; todo editable en el Command Center y consultable en el chat.
- **El sandbox no tiene red.** Las maquetas no pueden cargar fuentes ni imágenes externas. Las tipografías se limitan a las que trae la imagen o a archivos empaquetados **[decisión del builder: en la etapa 1, solo fuentes ya presentes en la imagen o empaquetadas en ella]**.
- `company-core` nunca ejecuta el HTML: lo renderiza `sandbox-runner` con un perfil del catálogo.
- El costo por misión sigue tope en `MAX_MISSION_COST_USD`.

## Qué queda para el builder

- Los nombres exactos de clases, del paso del catálogo y de los eventos.
- Cómo se implementa SSIM en Java (librería o código propio) y el formato exacto de las cajas.
- La forma del JSON del sistema visual, siempre que cubra lo listado en el paso 2.
- Cómo se muestran en la UI las muestras de color y las imágenes.

## Etapas siguientes (fuera de este documento)

2. **Juegos 2D:** el front crea personajes y ambientes con `Skeleton2D`, `Polygon2D` y `AnimationPlayer` en Godot. No necesita imagen nueva. Falta decidir la frontera con Kael (la propuesta es: Kael, reglas y escenas; el front, personajes, ambientes y animaciones).
3. **Juegos 3D con Blender:** scripts `bpy` sin pantalla que exportan `.glb`, una imagen nueva del sandbox y un stack Godot 3D.
