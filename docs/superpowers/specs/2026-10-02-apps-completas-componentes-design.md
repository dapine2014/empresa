# Apps completas: varios componentes en una misión (B1) — diseño

**Fecha**: 2026-10-02
**Estado**: diseño aprobado por el fundador en conversación (secciones 1 a 4); pendiente de revisión de este spec.
**Etapa**: capa B ("apps completas") del Development Group, entrega 1 de 3. B2 = ambientes (DEV/QA/UAT), promoción
por Git y GitHub; B3 = producción en AWS. Cada una tiene su propio spec.

## Objetivo

Que el Development Group pueda construir **cualquier aplicación completa** — frontend Flutter + API .NET + base
PostgreSQL — en una sola misión y un solo repositorio, verificada en el sandbox. Nada del diseño depende de un dominio
concreto.

Hoy una misión tiene un único `stackProfile`: el repositorio es solo .NET o solo Flutter, y nada verifica que una app
hable con una API.

## Decisiones del fundador (2026-10-02)

1. **Enfoque de componentes** (opción B, frente a un perfil combinado fijo o una misión por componente): el plan
   declara una lista de componentes con los perfiles existentes; sirve para cualquier combinación sin perfiles nuevos.
2. **Entrega en dos tiempos**: B1 verifica cada componente por separado más el contrato de rutas; la prueba contra
   bases reales y de punta a punta llega con los ambientes (B2).
3. **Flutter es portable**: el mismo código sirve para web, Android e iOS. El sandbox verifica web; el código no puede
   usar librerías solo-web. Compilar para el celular queda del lado del fundador.
4. Los tests unitarios siguen con fakes en memoria; probar contra la base es otra capa (B2), no un reemplazo.

## 1. Componentes, carpetas y reparto

**Plan de Neo**: `TeamPlan` gana `components: List<String>` (ids de `StackProfile`). Compatibilidad: si viene solo
`stackProfile`, o `components` con un único perfil, el repositorio se arma en la raíz como hoy (misiones y
repositorios existentes intactos). `TeamPlan.components()` devuelve siempre la lista efectiva (la de `components`, o
`[stackProfile]`).

**`TeamPlanValidator`** rechaza (con la lista exacta de errores y reintento, como hoy): componentes repetidos, ids fuera
del catálogo, perfiles no habilitados (`StackProfile.enabledNow`), lista vacía sin `stackProfile`.

**Carpetas** (`Component`, nuevo record `profile` + `dir`), fijadas por Java y solo con ≥2 componentes:

| Perfil | `dir` |
|---|---|
| `DOTNET_APP` | `backend` |
| `FLUTTER_WEB_APP` | `app` |
| `GODOT_DOTNET_GAME` | `game-client` (reservado; fase 3) |

Todas las rutas del perfil (capas, archivos de entrada, extras, `.csproj`) se resuelven bajo `dir/`. Comunes en la
raíz: `db/postgres/migrations`, `.env.example`, `README.md`, `.gitignore`, `docs`.

**Bounded contexts**: los mismos para todos los componentes. Java convierte el nombre a la convención de cada perfil
(`Clientes` en C#, `clientes` en Dart); el validador sigue exigiendo el formato del perfil principal (el primero de la
lista) y Java deriva los demás.

**Reparto por componente y capa** (`RoleLayerCatalog.assign` recibe el componente):

| Rol | Componente .NET | Componente Flutter |
|---|---|---|
| `BACKEND` (Iris) | DOMAIN, APPLICATION, API (+ INFRASTRUCTURE sin `DATA_ARCHITECT`) | — |
| `DATA_ARCHITECT` (Diego) | INFRASTRUCTURE + migraciones | — |
| `UI_UX` (Mila) | — | todas las capas y los archivos de entrada |
| `QA` (Vera) | TESTS | TESTS |

Cada rol tiene los ecosistemas en los que puede escribir (`BACKEND`/`DATA_ARCHITECT`: NUGET; `UI_UX`: PUB; `QA`:
ambos). Vera sigue con exactamente una WORK (tests de todos los componentes) y la `VALIDATION`. Un agente sigue teniendo
una sola tarea; sus `ownedPaths` abarcan todos sus componentes. El validador exige dueño de DOMAIN y de los archivos de
entrada **por componente** (p. ej. rechaza `backend/` sin Iris). Con un único componente el reparto es el de hoy
(incluido el respaldo de Mila en DOMAIN/APPLICATION/INFRASTRUCTURE de una app Flutter sola).

**Portabilidad**: en un componente Flutter, `dart:html`, `dart:js`, `dart:js_interop` y `package:web/` quedan
prohibidos en todas las capas (hoy solo en domain). Lo verifica `DddLayerChecker` (chequeo `PORTABILITY`).

## 2. Orden de generación y contrato de rutas

**Orden** (secuencial, como hoy): backend (Iris → Diego) → **rutas (Forjai)** → app (Mila) → tests (Vera) → revisión.
Con un solo componente no hay paso de rutas.

**Contrato .NET con app**: si la misión tiene componente Flutter, el contrato de `DOTNET_APP` exige *minimal APIs* con
rutas literales (`MapGet/MapPost/MapPut/MapPatch/MapDelete("…")`, `MapGroup("…")`), sin controllers, y CORS para los
orígenes de la variable `CORS_ORIGINS`.

**`HttpRouteExtractor`** (Java, puro): de los `.cs` de la capa API del componente .NET extrae `(método, plantilla)`,
resolviendo prefijos de `MapGroup` asignados a variables (también encadenados). Plantillas normalizadas (`/` inicial,
sin `/` final, restricciones `{id:guid}` → `{id}`). Cero rutas con app Flutter en la misión → la tarea de Iris
reintenta con ese motivo.

**`api_routes.dart`**: Forjai commitea `app/lib/api/api_routes.dart` (autor "Forjai", trailer `Forjai-Task:
<misión>-API-ROUTES`):

```dart
class ApiRoute { final String method; final String path; const ApiRoute(this.method, this.path); }
class ApiRoutes {
  static const ApiRoute crearClientes = ApiRoute('POST', '/clientes');
  static ApiRoute obtenerClientes(String id) => ApiRoute('GET', '/clientes/$id');
}
```

Nombre de cada miembro: verbo según el método (`listar`/`obtener` para GET con o sin parámetro final, `crear`,
`actualizar`, `modificar`, `eliminar`) + segmentos literales en camelCase; colisiones → sufijo numérico estable por
orden de ruta. Parámetros de la plantilla → argumentos `String`. El archivo es de Forjai (los findings de Vera sobre él
no cuentan; nadie más puede escribirlo).

**Lo que recibe Mila**: el contenido de `api_routes.dart`, el contrato público del backend (`PublicApiExtractor`, para
los tipos de pedido y respuesta y sus campos) y la regla: URL base de `const String.fromEnvironment('API_BASE_URL')`,
llamadas con el paquete `http` usando solo `ApiRoutes`.

**`ApiContractGate`** (Java, reintento) sobre los `.dart` de Mila fuera de `api_routes.dart`: rechaza un literal string
que empiece por `/` y cuyo primer segmento sea el primer segmento de alguna ruta del backend, y cualquier literal
`http://` o `https://` hacia `localhost`/`127.0.0.1`.

**Paquete `http`**: versión exacta compatible con Flutter 3.24 / Dart 3.5 (se fija al construir la imagen), agregado a
`BaselineDependencies` y precargado en `sandbox/images/flutter-web-app` (licencia BSD-3, permitida).

## 3. Sandbox, chequeos, dependencias y corrección

**`sandbox-runner`**: `JobRequest` gana `componentDir` opcional, validado contra una lista fija (`backend`, `app`,
`game-client`, vacío = raíz); cualquier otro valor → 400. `VerifyJobRunner` extrae solo ese árbol
(`git archive <sha>:<dir>`) y corre los pasos del perfil sobre él. Imágenes y `run.sh` sin cambios (salvo `http` en
Flutter).

**`company-core`**: `SandboxRunnerClient.verify` por componente, en el orden de la lista, **siempre todos** aunque
falle uno. `SandboxResult` combinado: pasos con prefijo (`backend/build`, `app/test`), `testsPassed` sumados y
`overall = PASS` solo si pasan todos los pasos y **cada componente tiene ≥1 test aprobado**. `Evidence` por paso:
`sandbox:<misión>@<sha>/<dir>/<paso>`. Un componente: pasos y evidencias sin prefijo, como hoy.

**Chequeos deterministas** (`StaticWorkspaceValidator`): `ENTRY_FILES`, `PROFILE_STRUCTURE` y `DDD_LAYERS` por
componente con las rutas bajo `dir/`; `PORTABILITY` en Flutter; `API_ROUTES_CONSISTENT`: `api_routes.dart` del commit
coincide con lo que `HttpRouteExtractor` saca del backend de ese mismo commit. Un archivo fuera de los `dir` y de los
comunes de la raíz se rechaza como hoy.

**Dependencias**: NuGet pedidos (y Npgsql implícito, `impliedPackages`) → `.csproj` bajo `backend/`; pub → de
`app/pubspec.yaml`. Ambas listas pasan por `DependencyService` con su ecosistema; si queda algo `PENDING_APPROVAL`, no
corre ningún `VERIFY` y la misión queda `UNVALIDATED` con la lista.

**Ciclo de corrección**: las rutas de `CompilerErrorParser` son relativas al componente; Java les antepone `dir/`
antes de buscar al dueño. `MissingUsingFixer` y las 2 rondas de modelo, como hoy. Si una corrección toca la capa API
del backend, Java regenera y commitea `api_routes.dart` antes de re-verificar (un cambio de ruta aparece como error de
compilación en `app/` y vuelve a Mila).

**Validación**: `StaticValidationStatus.compute(checks, review, sandbox)` sin cambios de reglas, sobre el resultado
combinado. `DatabaseApplyService` sigue leyendo `db/postgres/migrations` de la raíz.

## 4. Chat, Command Center y estado verificable

- Arranque de misión sin cambios: el fundador describe la app; Neo decide los componentes.
- "¿Cómo va MISSION-X?": por componente, dueños, commit y pasos del sandbox
  (`backend: build PASS · 14 tests PASS · arranque PASS`).
- Estado verificable (Java): una línea por componente, número de rutas del contrato y la advertencia fija "los nombres
  de campos JSON entre app y API no se verifican hasta la entrega de ambientes (B2)".
- Missions: detalle con pasos agrupados por componente y las rutas del contrato. `api/types.ts` actualizado. Sin
  pantallas nuevas.

## Errores

- Plan con componentes inválidos → replanifica (hasta 5 intentos en desarrollo, como hoy), después `FAILED`.
- Sin rutas extraíbles con app en la misión → reintento de Iris; agotado → la tarea falla como cualquier otra.
- `sandbox-runner` caído en un componente → ese componente `NOT_RUN`; la misión queda `UNVALIDATED` (nunca `VERIFIED`).

## Testing

Unitarios (JUnit + Mockito):
- `TeamPlan`/`TeamPlanValidator`: componentes repetidos, fuera de catálogo o no habilitados; compatibilidad con
  `stackProfile` único; dueño de DOMAIN y entrada por componente; `BACKEND` nunca en Flutter, `UI_UX` nunca en .NET.
- Resolución de rutas con `dir/` y nombres de contexto por perfil.
- `HttpRouteExtractor`: los cinco `Map*`, `MapGroup` anidado y encadenado, restricciones de parámetros, cero rutas.
- Generación de `api_routes.dart`: nombres estables, colisiones, parámetros.
- `ApiContractGate` y `PORTABILITY`.
- Corrección: prefijo de rutas de error y regeneración de rutas tras corregir la API.
- `sandbox-runner`: `componentDir` válido/ inválido y extracción de solo esa carpeta.
- `SandboxResult` combinado y `StaticValidationStatus` (≥1 test por componente).

**Prueba en vivo** (`docs/HISTORY.md`): al menos tres pedidos de dominios distintos escritos en lenguaje del fundador
(p. ej. un CRM simple de clientes y oportunidades, el inventario de una tienda, una agenda de reservas), cada uno
`VERIFIED` con sus dos componentes y `api_routes.dart` coherente; más una misión de un solo componente para comprobar
que no cambió. Un fallo propio de un dominio se registra como bug del sistema, nunca se parchea para ese caso.

## Fuera de alcance

- **B2**: conexiones por ambiente (DEV/QA/UAT/PROD), Diego por ambiente (crea base, migra, verifica, integridad en la
  base con `SchemaIntegrityGate`), DEV en el sandbox con red solo a la base DEV y tests de integración, ramas
  `develop → qa → uat`, despliegue en Docker de QA y UAT, GitHub (repo por producto, push, PR `uat → master` por Neo),
  verificación de campos JSON y pruebas de punta a punta.
- **B3**: producción en AWS.
- Compilar para Android/iOS.
- Godot con servidor (fase 3) y los planes del motor (capa A).
