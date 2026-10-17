# Entrega del producto: zip con el compilado y la documentación del caso (2026-10-17)

## Por qué

El Pong de 3 niveles quedó VERIFIED y el fundador preguntó: «Forjai terminó, pero ¿dónde está el aplicativo?».

El producto existe solo como repositorio git local en `~/forjai-products/<missionId>/`, sin remoto. Lo que compila el
sandbox se descarta junto con el contenedor (`VerifyJobRunner` borra el job dir). Las copias jugables de los niveles 1, 2 y
3 se armaron a mano.

No hay documento de entrega. Lo más cercano es la consolidación del CEO, que es un informe de la misión y no un manual del
producto. Ya figuraba en FALTANTES: «Exportar el juego para que el fundador lo juegue» y «Entrega y DevOps (Andrea)».

Pedido del fundador: «debería entregar una documentación con todo: desde dónde descargar la aplicación, el repositorio…
toda la documentación del caso, tanto para aplicaciones como para juegos».

## Decisiones del fundador (2026-10-17)

1. **Dónde.** Manda lo que diga el contrato. Si el contrato no dice nada, se entrega un `.zip` dentro de la carpeta
   `entregas-forja/`.
   - **Aplicación:** el zip trae la aplicación, la documentación del caso, lo necesario para desplegarla en Docker y los
     scripts de la base de datos (o dónde está la base).
   - **Juego Godot:** el zip trae el código fuente y los ejecutables por plataforma, más los scripts de la base de datos
     si el juego tiene una.
2. **Web/HTML5 en juegos.** Ni Godot 4.3 ni la última estable (4.7.2, agosto de 2026) exportan C# a Web. Los juegos se
   entregan para Windows, macOS, Linux y Android. Web va solo si el contrato lo pide, y en ese caso el juego se programa
   en GDScript (esto queda fuera de este spec).
3. **Credenciales.** Ninguna contraseña entra al zip. Va un `.env.example` con dónde está cada base (host, puerto, nombre
   y usuario) y la contraseña vacía, más una nota: «la contraseña está en Forjai → Settings → Bases de datos».
4. **Cuándo.** Se genera una entrega en cada incremento VERIFIED: `incremento-1.zip`, `incremento-2.zip`, etc.
5. **Android.**
   - En todas las entregas va el `.apk` firmado con la clave de depuración de Forjai.
   - En el último incremento va además el `.aab`, pero solo si el fundador o el cliente cargó la clave de publicación del
     proyecto. Sin clave, el último incremento lleva solo el `.apk`.
6. **Quién escribe la documentación.** Java, con plantillas fijas por stack y datos reales. Ningún modelo la escribe:
   no inventa comandos ni rutas y no cuesta nada.

## Alcance

1. Un paso de empaquetado en el sandbox, que exporta o publica el commit VERIFIED.
2. El armado del zip y de `ENTREGA.md` en `company-core`.
3. La clave de publicación de Android por misión, cifrada.
4. API, Command Center, chat y evento.
5. Generar la entrega de un incremento que ya quedó VERIFIED, para las misiones anteriores a esta feature.

Fuera de alcance:

- **Web en juegos** (GDScript).
- **Subir el código a GitHub** u otro remoto.
- **Firma y notarización de macOS.** Exigen una cuenta de Apple Developer y una Mac.
- **iOS.**
- **Que Andrea redacte prosa** sobre la entrega. Se puede sumar después como texto marcado del agente.

## Parte 1 — Empaquetado en el sandbox

### Pedido nuevo al runner

Se agrega `jobType: "PACKAGE"` a `POST /jobs` (`JobController`), con el mismo token. El cuerpo trae:

- `missionId`, `commitSha` y `profile`;
- `increment`;
- `targets`: la lista de plataformas a generar (ver «Contrato»);
- `androidRelease`: opcional. Trae `{keystoreBase64, alias, storePassword, keyPassword}` y solo se manda en el último
  incremento cuando hay clave cargada.

Reglas:

- **Lo que no cambia de `VERIFY`.** Se hace el mismo `git archive` del commit a un job dir y el mismo aislamiento de
  `PodmanCommandBuilder`: sin red, cap-drop y userns.
- **Cómo corre.** Corre el paso `restore` y después un paso nuevo, `package`, con un timeout de 1800 s. Como `run.sh`
  recibe solo el nombre del paso, los targets llegan por la variable de entorno `FORJAI_TARGETS` (lista separada por
  comas, validada contra un enum en el runner). Nunca llegan comandos.
- **La clave de Android.**
  - El runner la escribe en el tmpfs del contenedor (`/tmp/release.jks`).
  - Le pasa la ruta y las contraseñas por las variables que Godot lee para firmar: `GODOT_ANDROID_KEYSTORE_RELEASE_PATH`,
    `GODOT_ANDROID_KEYSTORE_RELEASE_USER` y `GODOT_ANDROID_KEYSTORE_RELEASE_PASSWORD`.
  - Nunca se loguea: los args de podman se loguean con esos valores redactados.
- **La salida.** El runner copia `/work/.forjai/package/` a `<deliveries-root>/.staging/<missionId>/incremento-<N>/`. Esa
  carpeta se monta en el runner (rw) y en `company-core`.
- **La respuesta.** Una `SandboxResult` con un paso por target (`package:windows`, `package:linux`, …) en PASS o FAIL, con
  su log recortado como hoy.

### Lo que hace `run.sh package` por perfil

| Perfil | Targets | Qué hace |
|---|---|---|
| `GODOT_DOTNET_GAME` | `windows`, `macos`, `linux`, `android-apk`, `android-aab` | Compila en Release. Escribe un `export_presets.cfg` fijo, que viene en la imagen. Para cada target corre `godot --headless --path game --export-release <preset> <salida>`. Un target que falla no corta los demás. |
| `FLUTTER_WEB_APP` | `web` | `flutter build web --release` → `package/web/` |
| `DOTNET_APP` | `app` | `dotnet publish -c Release` del proyecto ejecutable (el que referencia `Microsoft.NET.Sdk.Web`, o el único `OutputType=Exe`) → `package/app/` |

La salida de los juegos:

- **Windows:** `juego/windows/<Nombre>.exe` más la carpeta `data_*` de .NET.
- **macOS:** `juego/macos/<Nombre>.zip` con el `.app` sin firmar.
- **Linux:** `juego/linux/<Nombre>.x86_64`.
- **Android:** `juego/android/<Nombre>-debug.apk` y, si se pidió, `<Nombre>.aab`.

### La imagen

`godot-dotnet-game` pasa a la versión 3 e incorpora:

- las export templates de Godot 4.3 mono;
- OpenJDK 17 y el Android SDK, con cmdline-tools, platform-tools, build-tools y la plataforma que pide Godot 4.3;
- una clave de depuración generada con `keytool` al construir la imagen;
- el `export_presets.cfg` fijo.

Todo se instala al construir la imagen (con red, en `build-images.sh`); el sandbox sigue sin red. `flutter-web-app` y
`dotnet-app` ya tienen lo que necesitan.

## Parte 2 — El zip (`company-core`)

### Dónde

`products.deliveries-root`, con la variable `DELIVERIES_ROOT` y el default `${user.home}/entregas-forja`. En compose:

- `company-core`: `${HOME}/entregas-forja:/data/entregas-forja`;
- `sandbox-runner`: `${HOME}/entregas-forja/.staging`, montado en la misma ruta.

### Contenido de `entregas-forja/<missionId>/incremento-<N>.zip`

```
ENTREGA.md
codigo-fuente/                 git archive del commit VERIFIED
codigo-fuente.bundle           git bundle con todo el historial (git clone codigo-fuente.bundle)
juego/<plataforma>/…           (juegos) lo que dejó el runner
app/…                          (apps) web compilada o app publicada
docker/Dockerfile              (apps) plantilla fija por stack: nginx sirve app/ (Flutter); aspnet corre app/ (.NET)
docker/docker-compose.yml      (apps) servicio de la app; si hay migraciones y ninguna conexión cargada, un postgres:16
docker/.env.example            (apps y juegos con base) variables DB_<NOMBRE>_* sin contraseña
base-de-datos/migraciones/     si el commit tiene db/postgres/migrations/V*__*.sql (MigrationSet)
base-de-datos/LEEME.md         cómo aplicarlas (psql en orden, o lo que hizo Diego por ambiente)
```

### Cómo se arma

- **`DeliveryPackager`.**
  - Arma el zip en un archivo temporal y lo renombra al final, así un zip a medio armar nunca queda visible.
  - Al terminar borra el staging.
  - Si se vuelve a generar el mismo incremento, el zip se sobrescribe.
- **El Dockerfile de la app** copia lo ya compilado (`app/`). No compila nada dentro de Docker, así funciona igual en un
  equipo sin el SDK.
- **`.env.example`.**
  - Por cada conexión de la misión (`DatabaseConnectionService`) lleva host, puerto, nombre y usuario reales, y la
    contraseña vacía.
  - Sin conexiones cargadas, apunta al postgres del compose, con una contraseña de ejemplo marcada «cámbiala».

### `ENTREGA.md` (`DeliveryDocument`, Java puro)

Las secciones son fijas y cada una sale de un dato real. Si el dato falta, la sección dice «no disponible» y nunca se
inventa:

1. **Qué es.** Nombre del producto (contrato), cliente, tipo (app o juego), stack, incremento N de M y fechas.
2. **Dónde está cada cosa.** La ruta del zip, el repositorio local (`~/forjai-products/<missionId>`), el commit y la
   estructura del zip.
3. **Cómo correrlo**, con texto fijo por target y por stack. Los requisitos y los comandos son exactos:
   - **Windows:** doble clic en el `.exe`.
   - **macOS:** descomprimir y abrir con clic derecho → Abrir, por la falta de firma.
   - **Linux:** `chmod +x` y ejecutar.
   - **Android:** activar «orígenes desconocidos» e instalar el `.apk`; el `.aab` se sube a Google Play Console.
   - **Apps:** `cd docker && cp .env.example .env && docker compose up -d` y la URL.
4. **Cómo se usa.**
   - **Juegos:** los controles (acción → tecla, del diseño de Kael), las escenas y los niveles con su meta.
   - **Apps:** las pantallas y los flujos por HU.
5. **Qué se construyó.** Los ítems del contrato, las HU del incremento y de los anteriores, y los escenarios ESC-NN con
   su estado.
6. **Cómo se verificó.** Tests del sandbox (OK y total), escenarios cubiertos, rondas de corrección y la fecha del
   VERIFIED.
7. **Base de datos.**
   - Las migraciones y el estado por ambiente (`DatabaseApplyService.status`).
   - Cómo configurar el `.env`.
   - Sin contraseñas.
8. **Limitaciones.**
   - Los targets que fallaron, con su motivo.
   - «Sin Web: el juego está en C# y Godot no exporta C# a Web».
   - «macOS sin firma de Apple».
   - «`.aab` no incluido: falta la clave de publicación del proyecto».
9. **Costo y equipo.** Costo de modelos de la misión (`ModelCostService`) y agentes con commits.

## Parte 3 — Cuándo se genera

- **Momento.** En `MissionExecutor`, cuando un incremento queda VERIFIED se agrega el paso «Entrega — empaquetando el
  incremento N» antes de `CONSOLIDATING`. Así la consolidación del CEO recibe la ruta del zip y la menciona. El paso no
  llama a ningún modelo.
- **Un empaquetado que falla no cambia nada de la misión.** Aplica a un target, a todos o al runner caído:
  - el incremento sigue VERIFIED;
  - no se abre una corrección;
  - la entrega queda `PARTIAL` (hay zip, pero faltan targets) o `FAILED` (sin zip, con el motivo);
  - se publica el evento y se avisa en la consolidación.
- **Durable.** Si `company-core` se reinicia durante el empaquetado, `MissionRecoveryService` lo vuelve a correr. Es
  idempotente.
- **Último incremento.** Es aquel cuyo número es el mayor de `RequirementsSpec.increments`. Solo en ese se agrega
  `android-aab`, y solo si hay clave cargada.

### Contrato

`ClientContract` agrega un campo opcional:

```
delivery: {platforms: [WINDOWS|MACOS|LINUX|ANDROID|WEB|DOCKER]}
```

- **Quién lo llena.** Aria lo completa solo si el cliente pide plataformas concretas. Java lo valida contra el enum.
- **Vacío o ausente.** Va lo de siempre: los 4 targets en juegos y Docker en apps.
- **`WEB` en un juego C#.** Queda en las Limitaciones de la entrega, porque la vía GDScript está fuera de alcance. El
  validador del contrato le avisa a Aria que hoy no es posible.

## Parte 4 — Clave de publicación de Android

- **Dónde se guarda.** En un nodo `(:Mission)-[:HAS_SIGNING_KEY]->(:AndroidSigningKey {alias, keystoreCipher,
  storePasswordCipher, keyPasswordCipher, updatedAt})`, cifrado con `SecretCipher`.
- **API.**
  - `PUT /api/company/missions/{id}/android-signing-key` recibe `{keystoreBase64, alias, storePassword, keyPassword}`.
  - Antes de guardar, Java valida que el keystore abra con esa contraseña y que tenga ese alias.
  - `GET` devuelve solo `{alias, updatedAt, configured}`.
  - `DELETE` la borra.
- **Seguridad.**
  - Nunca sale por la API, el chat ni los eventos.
  - Solo se descifra para mandarla al runner en el pedido `PACKAGE`.
  - Un mensaje de chat con un keystore o una contraseña se corta como hoy.

## Parte 5 — API, Command Center, chat y eventos

- **API.**
  - `GET /api/company/missions/{id}/deliveries` devuelve `DeliveryView(increment, status, path, sizeBytes, targets[{name,
    status, reason}], createdAt)`.
  - `GET …/deliveries/{n}/download` descarga el zip en streaming.
  - `POST …/deliveries/{n}` genera o regenera la entrega de un incremento VERIFIED. Para uno que no está VERIFIED,
    devuelve `IllegalStateException`.
- **Dónde se guarda el estado.** `(:Mission)-[:HAS_DELIVERY]->(:Delivery {increment, status, path, sizeBytes,
  targetsJson, createdAt})`. Al borrar la misión se borran también sus `Delivery` (el zip queda en disco) y su
  `AndroidSigningKey`.
- **MissionDetailPage.** Una sección «Entregas» con cada incremento: estado, ruta, tamaño, el botón **Descargar**, los
  targets con su motivo si fallaron y el botón **Generar entrega** en los incrementos VERIFIED sin zip. También la tarjeta
  «Clave de Android» (cargar o borrar), para juegos.
- **Chat**, respondido por Java antes del modelo:
  - «entrega de MISSION-X», «¿dónde está MISSION-X?» o «descargar MISSION-X» → la lista de entregas con su ruta y lo que
    faltó;
  - «entregas» sin misión → las últimas 5.
- **Eventos.**
  - `EMPRESA_DELIVERY_PACKAGED`, con `increment`, `status`, `targets` y `path`.
  - `EMPRESA_DELIVERY_FAILED`, con `increment` y `error`.
  - Se suman a `docs/EVENTS.md` y a las plantillas de `MissionEventText`, así quedan en la bitácora.
- **Tipos.** A mano en `api/types.ts`. No hay ruta nueva en `SpaController`.

## Compatibilidad

- **Misiones VERIFIED anteriores, como el Pong de hoy:** no tienen entrega hasta que se usa «Generar entrega». El chat
  dice «sin entrega: usa Generar entrega».
- **Imágenes del sandbox:** hay que reconstruirlas (`bash sandbox/build-images.sh`). La de juegos crece varios GB por el
  Android SDK y las templates.
- **Disco:** un zip de juego con 4 plataformas pesa del orden de cientos de MB. Se guardan todos, porque el fundador
  eligió una entrega por incremento. La sección muestra el tamaño de cada uno.

## Review Focus

1. **Falla un target y los demás no.** Android falla, por ejemplo por el SDK. El zip igual trae Windows, macOS y Linux,
   queda `PARTIAL`, y `ENTREGA.md` explica qué faltó.
2. **Una contraseña se filtra.** Ni el zip, ni `ENTREGA.md`, ni el `.env.example`, ni los logs del runner, ni los eventos
   contienen una contraseña de base o de keystore.
3. **Reinicio durante el empaquetado.** No queda un zip a medias visible, y la recuperación lo rehace.
4. **El último incremento sin clave.** Lleva solo el `.apk`, y las Limitaciones dicen por qué no va el `.aab`.
5. **Una app con migraciones y sin conexiones cargadas.** El compose levanta postgres, y `LEEME.md` explica cómo aplicar
   las migraciones y cómo apuntar a una base real.
