# Proyecto B (subproyecto 2a): sandbox de verificación, catálogo de stacks, DDD y dependencias gobernadas — diseño

**Fecha**: 2026-09-26
**Estado**: aprobado por el usuario en brainstorming (4 secciones), pendiente de revisión del spec escrito y del plan de implementación.

## Contexto y objetivo

El subproyecto 1 de Proyecto B (`2026-09-21-development-generation-design.md`)
dejó a Engineering generando código real, con un commit por agente y revisión
estática de Vera. Las verificaciones en vivo (`MISSION-TEAM-VERIFY-2` a `-8`,
ver `docs/HISTORY.md`) mostraron el límite: el código son esqueletos y **nadie
sabe si compila, pasa tests o arranca**. La severidad la decide la opinión de un
modelo, y Vera la subestimó varias veces.

Objetivo del fundador: que el equipo sea **100% funcional con verificación
real**. Este subproyecto (2a) aporta la verificación objetiva. Criterio de
"funcional" acordado: **compila, pasa sus tests y arranca**, comprobado por
ejecución real y aislada.

Descomposición acordada:

| Subproyecto | Contenido |
|---|---|
| **2a (este)** | Sandbox de build/test/arranque, catálogo de stacks (primera ola), DDD obligatorio, dependencias gobernadas |
| 2b | Navegación web para Engineering (buscar y leer documentación y APIs reales durante la generación) |
| 3 | Ciclo de corrección (los errores reales vuelven al agente dueño para otra ronda) |

## Decisiones acordadas en brainstorming

1. **Criterio de funcional (A)**: compila, pasa sus tests y arranca.
2. **Dependencias (catálogo que crece)**: descarga aislada solo hacia los
   registros, chequeos deterministas y ejecución siempre sin red.
3. **Aprobación de dependencias (A)**: automática por política si pasa todos los
   chequeos; si falla alguno, queda pendiente del fundador (🔴).
4. **Metodología DDD obligatoria para todo proyecto (A)**: el plan declara
   bounded contexts y lenguaje ubicuo; capas y dependencias verificadas de forma
   determinista; Vera revisa la calidad del modelo de dominio.
5. **Primera ola de stacks**: C# (.NET para apps y Godot .NET para juegos) y
   Dart/Flutter (apps; primera ola solo web). JavaScript/TypeScript, Python,
   Java y Kotlin quedan para olas posteriores, cuando un producto real los necesite.
6. **Ejecución (enfoque 1)**: un servicio `sandbox-runner` aparte, con Podman sin
   root; `company-core` nunca accede a un motor de contenedores.

## 1. Catálogo de stacks, contrato DDD del plan y chequeo de capas

### Catálogo de perfiles (`StackProfile`)

Catálogo fijo en código (mismo criterio que `TeamMemoryService.TEAMS`: agregar
un perfil es un cambio de código, nunca un dato que el modelo pueda inventar).
Primera ola:

| Perfil | Uso | Estructura DDD por bounded context (`<Ctx>`) | Archivos de entrada obligatorios |
|---|---|---|---|
| `DOTNET_APP` | APIs/servicios ASP.NET | `src/<Ctx>.Domain`, `src/<Ctx>.Application`, `src/<Ctx>.Infrastructure`, `src/<Ctx>.Api`, `tests/<Ctx>.Tests` | `<Producto>.sln` |
| `GODOT_DOTNET_GAME` | Juegos Godot con C# | `src/<Ctx>.Domain`, `src/<Ctx>.Application` (bibliotecas .NET puras), `game/` (proyecto Godot = capas `ui`/`infrastructure`), `tests/<Ctx>.Tests` | `game/project.godot`, `<Producto>.sln` |
| `FLUTTER_WEB_APP` | Apps con interfaz (web en esta ola) | `lib/<ctx>/domain`, `lib/<ctx>/application`, `lib/<ctx>/infrastructure`, `lib/<ctx>/presentation`, `test/` | `pubspec.yaml`, `lib/main.dart` |

Cada perfil define además, con valores fijos que nunca decide el modelo: la
imagen de sandbox, el ecosistema de dependencias (`NUGET` | `PUB`), los comandos
de restauración, build, test y prueba de arranque, las extensiones de código
fuente que se analizan (`.cs` | `.dart`) y los frameworks prohibidos en la capa
`domain` (`Godot`, `Microsoft.AspNetCore` | `package:flutter/`).

### Contrato del plan (`TeamPlan`)

Campos nuevos, exigidos por `TeamPlanValidator` en la estrategia de desarrollo:

- `stackProfile`: uno de los perfiles del catálogo; cualquier otro valor se
  rechaza. Reemplaza al `techStack` de texto libre.
- `boundedContexts`: lista no vacía de `{name, description}`. El `name` es un
  identificador (`[A-Z][A-Za-z0-9]*` en .NET, `[a-z][a-z0-9_]*` en Flutter) y
  determina las rutas.
- `ubiquitousLanguage`: al menos 3 `{term, definition}`.
- Cada `ownedPath` de una tarea `WORK` tiene que caer dentro de la estructura del
  perfil para algún contexto declarado, o en un archivo de entrada obligatorio
  del perfil. Una ruta fuera de la estructura se rechaza con corrección.
- `entryPoint` deja de declararlo el líder: los archivos de entrada los fija el
  perfil y los verifica la capa 1.

El prompt de `TeamWorkPlanner` presenta el catálogo de perfiles con su
estructura y exige elegir uno, declarar contextos y glosario, y repartir las
tareas por contexto y capa.

### Revisión 2026-09-26: Java decide lo mecánico, el líder decide lo de producto

Verificado en vivo (`MISSION-DDD-VERIFY-1` a `-3`): con `qwen3:8b`, el líder no
lograba un plan válido en 3 ni en 5 intentos cuando tenía que calcular carpetas
exclusivas, asignar la única tarea `VALIDATION` y repartir archivos de entrada.
Decisión del fundador (opción B):

- El líder decide **perfil, bounded contexts, glosario** y, por miembro,
  `objective`, `requiredCapabilities` y **`assignments`**: una lista de
  `{context, layer}` con las capas del perfil que trabaja ese miembro (`game`
  sin contexto en Godot).
- **`TeamPlanResolver`** (Java, determinista) completa el plan antes de validarlo:
  - `ownedPaths` = la raíz de cada asignación, calculada desde el catálogo;
  - la tarea del primer miembro con la capability `QA` pasa a `VALIDATION` (sin
    rutas) y todas las demás a `WORK`, sin importar lo que haya escrito el modelo;
  - el líder recibe los archivos de entrada y extras de arranque del perfil
    (`Solution.sln`; `game/project.godot` si nadie tiene `game`; en Flutter
    `pubspec.yaml`, `lib/main.dart` y `web`), salvo los que ya cubra otra raíz.
- El resolver devuelve errores **accionables** (se reintentan con el plan
  anterior): capa o contexto inexistente, misma capa de un contexto asignada a
  dos miembros (nombrando a ambos), miembro de trabajo sin asignaciones, y capa
  `domain` de un contexto sin dueño.
- `TeamPlanValidator` sigue corriendo después, sobre el plan ya resuelto.

### Revisión 2 (2026-09-26): reparto de capas determinista por `roleCode`

Verificado en vivo (`MISSION-DDD-VERIFY-4` y `-5`): aun eligiendo solo capas
(`assignments`), `qwen3:8b` no convergía (oscilaciones y capas inexistentes).
Decisión del fundador (opción 1): **las capas las asigna Java según el
`roleCode` de cada miembro** (catálogo fijo en código, `RoleLayerCatalog`); el
líder decide solo lo de producto: perfil, bounded contexts, glosario y el
objetivo de cada miembro.

| `roleCode` | Capas (las que tenga el perfil, en todos los contextos) |
|---|---|
| `DEV_BACKEND_INTEGRATIONS` | `DOMAIN`, `APPLICATION` |
| `FRONTEND_GAME_UI_SPECIALIST` | `GAME`, `PRESENTATION`, `API` |
| `CLOUD_DB_SRE_DEVOPS` | `INFRASTRUCTURE`, `TESTS` |
| `CLOUD_ARCHITECT_LEAD_BACKEND` (líder) | archivos de entrada del perfil |
| miembro con capability `QA` | tarea `VALIDATION` |

Si un miembro tiene un `roleCode` fuera del catálogo, se usan sus `assignments`
(revisión 1) como respaldo. `TeamPlanResolver` además une en una sola las tareas
repetidas de un mismo agente (es una corrección mecánica: rutas y tipo los
calcula Java igual). Las `requiredCapabilities` y los objetivos siguen siendo
del líder.

### Chequeo de capas DDD (`DddLayerChecker`, Java, determinista)

Se suma a `StaticWorkspaceValidator` (capa 1), antes del sandbox. Sin
herramientas externas por lenguaje.

- Clasifica cada archivo fuente en `(contexto, capa)` según su ruta y el perfil.
- Extrae dependencias: en C#, las directivas `using` (el namespace refleja la
  ruta, `<Ctx>.Domain`, etc.); en Dart, los `import` (`package:<app>/<ctx>/<capa>/…`
  o rutas relativas resueltas a una ruta del repo).
- Reglas:
  - `domain` solo depende de su propio `domain`; nunca de frameworks prohibidos
    ni de otras capas.
  - `application` depende solo de `domain` (propio) y de `application` de otros
    contextos.
  - `infrastructure`, `api`, `presentation` y `game` pueden depender de
    `application` y `domain` de su contexto.
  - Entre contextos, solo a través de su capa `application`; nunca tocando el
    `domain` de otro contexto.
- Cada violación es un `StaticCheck` `DDD_LAYERS` en `FAIL`, con el archivo y la
  dependencia prohibida. Cualquier violación hace fallar la validación.

Chequeos de estructura adicionales (también en capa 1): `PROFILE_STRUCTURE`
(ningún archivo fuera de la estructura del perfil) y `ENTRY_FILES` (existen los
archivos de entrada obligatorios del perfil).

### Revisión de Vera

A su revisión estática suma lo que no se puede verificar con reglas: uso del
lenguaje ubicuo del glosario en el código, existencia y sentido de entidades,
value objects y agregados, y dominio anémico. Sigue sujeta a los gates
existentes (`RepositoryEvidenceGate`, `ForbiddenClaimsGuard`,
`MissingFileClaimGate`) y ahora **no puede contradecir los resultados del
sandbox** (ver sección 4).

## 2. Servicio `sandbox-runner`

- Módulo nuevo en el repo (`sandbox-runner/`, Java/Spring Boot), con su propio
  `Dockerfile` y servicio en `docker-compose.yml`. **Sin puerto publicado al
  host**: solo accesible dentro de `ai-company-net`. `company-core` se autentica
  con un token compartido (`SANDBOX_RUNNER_TOKEN`).
- Habla con **Podman sin root** a través del socket de usuario
  (`/run/user/<uid>/podman/podman.sock`), montado solo en este servicio. Aunque
  el código escape del contenedor, queda con permisos del usuario, no de root.

### API

`POST /jobs` con `{jobType, missionId, commitSha, stackProfile, packages?}`,
**síncrona** (el llamador ya espera en su propio hilo; el cliente de
`company-core` usa timeout de 30 min). `jobType`:

- `VERIFY`: restaurar sin red, build, tests, prueba de arranque.
- `DEPENDENCY_FETCH`: descargar paquetes faltantes (sección 3).

El runner **no acepta comandos**: la imagen y los pasos salen del catálogo según
el `stackProfile`. Un perfil desconocido se rechaza.

Reparto del catálogo: `company-core` es dueño de la parte **estructural** de
cada perfil (estructura DDD, archivos de entrada, frameworks prohibidos,
ecosistema, extensiones); el runner es dueño de la parte **de ejecución**
(imagen, comandos, timeouts, prueba de arranque). Lo único compartido es el id
del perfil (`DOTNET_APP`, `GODOT_DOTNET_GAME`, `FLUTTER_WEB_APP`). Si el runner
recibe un id que no conoce, responde con error y la misión queda `UNVALIDATED`
(nunca se ejecuta nada por defecto).

### Ejecución de `VERIFY`

1. Extrae el código exacto del commit (`git archive <sha>`) desde el workspace
   de la misión, montado en solo lectura, a un directorio temporal. Nunca toca
   el repo original.
2. Monta ese directorio en `/work` y la caché aprobada del ecosistema en solo
   lectura.
3. Corre los pasos del perfil en orden; un paso fallido detiene el resto
   (restantes en `SKIPPED`).
4. Borra contenedor y directorio temporal.

### Aislamiento de cada contenedor de `VERIFY`

`--network=none`, usuario no root, `--read-only` con `/tmp` en memoria,
`--cap-drop=ALL`, `--security-opt=no-new-privileges`, `--memory=4g`,
`--cpus=4`, `--pids-limit=512`, `--rm`. Timeout por paso: build 10 min,
tests 10 min, arranque 3 min; al vencer, `TIMEOUT` (cuenta como fallo).

### Prueba de arranque por perfil

- `DOTNET_APP`: arranca `src/*.Api` y consulta `GET /health` hasta 60 s; pasa
  con HTTP 200 (el perfil exige exponer `/health`).
- `GODOT_DOTNET_GAME`: compila la parte C# y corre
  `godot --headless --path game --quit-after 300`; pasa con código 0 y sin
  `SCRIPT ERROR`, `ERROR:` ni excepciones no manejadas en la salida.
- `FLUTTER_WEB_APP`: `flutter build web`, sirve el resultado dentro del
  contenedor y lo carga con Chromium sin interfaz; pasa si no hay errores en la
  consola y la app de Flutter está presente en el DOM.

### Resultado

`SandboxResult{overall, steps[{name, status: PASS|FAIL|TIMEOUT|SKIPPED,
exitCode, durationMs, outputTail, testsPassed, testsFailed}]}`. `outputTail`:
últimas líneas de salida, tope 20 KB. Conteo de tests desde reportes
estructurados (TRX de `dotnet test`, JSON de `flutter test`), nunca desde texto
libre.

### Imágenes

`sandbox/images/<perfil>/Dockerfile` con toolchain fijado a versión exacta:
.NET SDK (`DOTNET_APP`); .NET SDK + Godot .NET + plantillas de exportación
(`GODOT_DOTNET_GAME`); Flutter SDK + Chromium (`FLUTTER_WEB_APP`). Se construyen
con un script (`sandbox/build-images.sh`) una vez y al actualizar versiones.

## 3. Dependencias gobernadas

### Detección (determinista, en `company-core`)

- Lee los manifiestos: `PackageReference` de cada `.csproj`;
  `dependencies`/`dev_dependencies` de `pubspec.yaml`.
- **Solo versiones exactas.** Rangos, comodines o `latest` se rechazan durante
  la generación como error corregible (reintento en `DevelopmentRuntime`).
- Compara con el catálogo aprobado. Si falta alguno → `DEPENDENCY_FETCH`.

### Descarga aislada (`DEPENDENCY_FETCH`)

- Contenedor en una red Podman **interna** cuya única salida es un **proxy con
  lista blanca**: `api.nuget.org`, `pub.dev`, `storage.googleapis.com`,
  `api.osv.dev`.
- Solo corre la restauración del gestor (`dotnet restore` / `dart pub get`)
  sobre un proyecto mínimo generado con los paquetes fijados. No compila ni
  ejecuta código de los paquetes.
- Resuelve las **dependencias transitivas**; cada una pasa los mismos chequeos.

### Chequeos por paquete

- **Vulnerabilidades**: OSV (`api.osv.dev`), mismo criterio para NuGet y pub.
- **Licencia** en lista blanca: MIT, Apache-2.0, BSD-2-Clause, BSD-3-Clause,
  ISC, Zlib. NuGet: expresión de licencia del `.nuspec`. Pub: identificación del
  texto de `LICENSE`; si no se reconoce, no se permite.
- **Código que se ejecuta al compilar**: NuGet con `.targets`/`.props` en
  `build/`/`buildTransitive/` o `tools/*.ps1`; pub con `hook/` (assets nativos).

### Política

- **Aprobación automática** (`approvedBy='policy'`) si no hay vulnerabilidades
  HIGH/CRITICAL, la licencia está permitida y no hay código en el build. El
  paquete entra a la caché compartida (`~/forjai-deps/nuget`,
  `~/forjai-deps/pub`).
- Si falla cualquier chequeo → `PENDING_APPROVAL` (🔴) con los motivos exactos.

### Si quedan paquetes pendientes

`VERIFY` no corre (pasos `SKIPPED` con el motivo), `validationStatus` queda
`UNVALIDATED` y la misión termina en `AWAITING_INVESTOR` con la lista. Aprobar
deja los paquetes disponibles para próximas misiones; re-verificar la misma
misión es del subproyecto 3.

### Persistencia y API

`(:Dependency {ecosystem, name, version, license, status: APPROVED |
PENDING_APPROVAL | REJECTED, approvedBy, reasons, requestedByAgent, missionId,
createdAt, decidedAt})`. Endpoints: `GET /api/company/dependencies`,
`PUT /api/company/dependencies/{id}/approve`,
`PUT /api/company/dependencies/{id}/reject`. Sin pantalla en esta ola.

## 4. Integración, estado final, evidencia y verificación

### Orden en `DevelopmentTeamStrategy`

1. Commits por agente (sin cambios).
2. Capa 1 ampliada: chequeos existentes + `PROFILE_STRUCTURE`, `ENTRY_FILES`,
   `DDD_LAYERS`.
3. Dependencias: detección y, si faltan, `DEPENDENCY_FETCH` + política.
4. `VERIFY` en el sandbox.
5. Revisión de Vera, con el `SandboxResult` como contexto y la regla de no
   contradecirlo.
6. Consolidación de Alex + bloque "Estado verificable" generado por Java.

### Estado final (`StaticValidationStatus` gana `VERIFIED`)

- `FAILED`: cualquier chequeo determinista en `FAIL`; cualquier paso del sandbox
  en `FAIL`/`TIMEOUT`; algún hallazgo `BLOCKER` o `MAJOR`; o **0 tests
  ejecutados** (pasar cero tests no cuenta como "pasa sus tests").
- `UNVALIDATED`: el sandbox no pudo correr (dependencias pendientes o runner no
  disponible) o la revisión de Vera no se completó.
- `VERIFIED`: todo en PASS, al menos un test pasado, sin `BLOCKER` ni `MAJOR`.
- `STATICALLY_VALIDATED` queda solo para misiones anteriores.
- `ProductStatus.QA` pasa a ser alcanzable: tarea `VALIDATION` en `VERIFIED`.

### Evidencia y persistencia

`SandboxResult` completo en la tarea de validación (propiedad `sandboxResult`).
Cada paso como `Evidence` `INTERNAL` con
`source = sandbox:<missionId>@<sha>/<paso>`.

### Estado verificable

Agrega las líneas reales del sandbox (p. ej. `Build PASS (48 s) · Tests PASS
12/12 · Arranque PASS`). Con `VERIFIED`, la frase fija pasa a ser: *"Compiló,
pasaron N tests y arrancó en el sandbox. No garantiza que el producto esté
completo ni que no tenga errores fuera de lo probado."* Si no, muestra el paso
que falló y las últimas líneas de su error.

### Fallas de infraestructura

Runner no disponible → `UNVALIDATED` con el motivo; la misión sigue. Nunca se da
por verificado algo que no corrió.

### Eventos nuevos

`EMPRESA_SANDBOX_VERIFICATION_COMPLETED`, `EMPRESA_DEPENDENCY_REQUESTED`,
`EMPRESA_DEPENDENCY_APPROVED`, `EMPRESA_DEPENDENCY_REJECTED` (documentar en
`docs/EVENTS.md`).

### Frontend

`MissionDetailPage` muestra los pasos del sandbox con estado, duración y el final
de la salida cuando fallan.

## Testing

Unitarios:
- Catálogo de perfiles.
- `TeamPlanValidator` con `stackProfile`, `boundedContexts`,
  `ubiquitousLanguage` y rutas dentro de la estructura del perfil.
- `DddLayerChecker` con repos de ejemplo en C# y Dart, cada uno con una
  violación deliberada (`domain` importando `Godot` o `package:flutter/`;
  `domain` de un contexto usando el `domain` de otro).
- Lectura de manifiestos y versiones exactas.
- Política de dependencias (vulnerabilidad, licencia, código en el build).
- Armado de los argumentos de Podman: siempre `--network=none`,
  `--cap-drop=ALL`, `no-new-privileges` y los límites.
- Cálculo del estado final, incluido "0 tests → FAILED".

**Verificación en vivo** (obligatoria): una misión real por perfil, registrando
en `docs/HISTORY.md` si llega a `VERIFIED` o qué falla de verdad; y una prueba
de control con una violación de capas y un test roto introducidos a mano, que
debe terminar en `FAILED`.

## Preparación del fundador (una vez)

- `systemctl --user enable --now podman.socket`
- Construir las imágenes: `sandbox/build-images.sh` (varios GB, tarda).
- Crear `~/forjai-deps`.

## Fuera de alcance

- Ciclo de corrección (subproyecto 3) y re-verificación de una misión.
- Navegación web para Engineering (subproyecto 2b).
- Builds Android/iOS y escritorio nativo.
- Stacks fuera de la primera ola (JS/TS, Python, Java, Kotlin, Spring Boot).
- Pantalla de aprobación de dependencias.
- Cualquier despliegue o publicación (sigue siendo 🔴).
