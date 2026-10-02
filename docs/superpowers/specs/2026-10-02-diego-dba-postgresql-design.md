# Diego como DBA: conexiones y PostgreSQL de punta a punta — diseño

**Fecha**: 2026-10-02
**Estado**: diseño aprobado por el fundador en conversación (partes 1 a 4); pendiente de revisión de este spec.
**Contexto**: el Development Group es un equipo híbrido (decisión del fundador, 2026-10-02): separar roles por "fases"
fue un error y se corrige rol por rol, empezando por Diego (`devops`, `DATA_ARCHITECT`). Diego debe saber modelar
bases en PostgreSQL, MongoDB y Firebase y conectarse a bases en AWS. Este spec cubre las piezas 1 (conexiones) y 2
(PostgreSQL de punta a punta); MongoDB/DocumentDB y Firestore siguen el mismo patrón en specs propios.

## Decisiones del fundador (2026-10-02)

1. **Nada de bases de datos en el sandbox.** Los tests unitarios mockean la base (fakes en memoria de los
   repositorios); el sandbox sigue sin red.
2. **Siempre el fundador entrega las credenciales** de un servidor real; entregarlas autoriza a Diego a crear la base
   ahí. Si una misión las necesita y no las tiene, se piden.
3. **El usuario de base de datos que se entrega ya tiene los permisos adecuados**: Forjai no agrega un filtro propio
   de operaciones; lo que ese usuario no puede hacer lo rechaza el servidor y queda como error.
4. **Reparto**: Diego diseña la base (migraciones) y escribe la persistencia; Iris registra la conexión en el arranque
   de la API; Andrea (despliegue, spec futuro) inyecta los valores reales. El puente es un contrato de variables de
   entorno definido por Java.
5. **Migraciones en SQL versionado** (`V<n>__<descripcion>.sql`), aplicadas por Java con un historial como Flyway, sin
   agregar Flyway.

## 1. Conexiones de base de datos

- Nodo `(:DatabaseConnection {id, name, engine, host, port, database, username, cipherText, tls, caCertPem,
  environment, lastCheckedAt, lastCheckResult, createdAt, updatedAt})`. `engine` ∈ `POSTGRESQL` (MongoDB y Firestore,
  specs siguientes). `tls` ∈ `DISABLE | REQUIRE | VERIFY_FULL` (`VERIFY_FULL` exige `caCertPem`, p. ej. el bundle de
  AWS RDS). `environment` ∈ `TEST | PRODUCTION`. `name` único, formato `[a-z0-9][a-z0-9-]{1,40}`.
- La clave se cifra con `SecretCipher` (AES-256-GCM, llave `FORJAI_SECRETS_KEY`), igual que `ApiKeyService`. Sin la
  llave configurada no se puede guardar una conexión (error claro).
- **Prueba antes de guardar**: Java abre una conexión real (driver JDBC de PostgreSQL, TLS según la conexión, timeout de
  conexión 10 s) y hace `SELECT 1` contra `database`; si la base todavía no existe, contra la base de mantenimiento
  `postgres`. Si falla, no se guarda y se devuelve el mensaje del servidor. "Probar" también existe como acción aparte.
- API (`DatabaseConnectionController`, `/api/company/databases`): `GET` (lista sin claves: últimos 4 caracteres),
  `POST` (crear), `PUT /{id}` (editar; clave vacía = conservar), `POST /{id}/test`, `DELETE /{id}` (rechazado si una
  misión no terminada la usa). Eventos `EMPRESA_DATABASE_CONNECTION_SAVED|DELETED` (sin datos sensibles).
- **Solo el fundador** crea, edita o borra conexiones; ningún agente tiene herramienta para leerlas. En el chat no se
  cargan claves (el historial se envía al modelo): un mensaje que parece contener una clave o cadena de conexión no se
  graba tal cual (Java reemplaza el valor por `[clave omitida]` en `ConversationMemoryService`) y la respuesta pide
  cargarla en Settings.

### Contrato de variables

Por cada conexión Java genera el contrato con el prefijo `DB_<NOMBRE>_` (nombre en mayúsculas, `-` → `_`):
`HOST`, `PORT`, `NAME`, `USER`, `PASSWORD`, `SSLMODE`. Ejemplo para `citas-dev-aws`: `DB_CITAS_DEV_AWS_HOST`, …
Es lo único que Neo, Diego, Iris y Vera ven de una conexión, junto con su nombre, motor, entorno y modo TLS.

### Conexiones de una misión

- `(:Mission)-[:USES_DATABASE]->(:DatabaseConnection)`. Se asocian al iniciar la misión: formulario de Missions
  (selector múltiple) y chat ("… usa la base <nombre>", nombre exacto). Inmutable como `teamId`, salvo agregar una
  conexión más tarde para aplicar el esquema (§3).

## 2. El trabajo de Diego en una misión

1. **Plan de Neo**: `TeamPlan` gana `database` (`{engine, connectionName}` o ausente). Neo recibe la lista de conexiones
   de la misión (nombre, motor, entorno). `TeamPlanValidator` exige, si hay `database`: Diego (`DATA_ARCHITECT`) en el
   plan; perfil `DOTNET_APP`; `engine` = `POSTGRESQL`; `connectionName` vacío o igual a una conexión de la misión de ese
   motor. Si el producto necesita base y la misión no tiene conexión, `connectionName` queda vacío y la misión sigue
   (ver §3, "si falta").
2. **Rutas de Diego** (las calcula Java): INFRASTRUCTURE (`src/<Ctx>.Infrastructure`) y `db/postgres/migrations`. El
   perfil `DOTNET_APP` admite `db/postgres/migrations` como raíz cuando el plan declara `database`.
3. **Qué escribe cada uno** (va en sus prompts, armado por Java con el contrato de variables):
   - Diego: `db/postgres/migrations/V1__<descripcion>.sql`, … (tablas, claves, restricciones, índices) y los
     repositorios en infraestructura con **Npgsql** (`NpgsqlDataSource`), leyendo solo las variables del contrato.
   - Iris: interfaces de repositorios en domain/application, casos de uso y el arranque de la API que registra la
     conexión desde el contrato, **conecta al primer uso** y deja `/health` sin depender de la base.
   - Vera: tests con fakes en memoria escritos a mano de esas interfaces (sin librerías de mocks).
   - La entrega incluye `.env.example` con los nombres del contrato y sin valores (dueño: Iris, archivo de entrada).
4. **Chequeos deterministas** (Java, reintento con el motivo al dueño del archivo):
   - `MigrationFilesGate`: nombre `V<n>__<descripcion>.sql` (`n` entero ≥ 1, descripción `[a-z0-9_]+`), versiones
     consecutivas desde 1 sin huecos ni repetidas, archivos no vacíos, sin metacomandos de `psql` (líneas que empiezan
     con `\`).
   - `SecretLiteralGate` (todos los archivos, incluido `appsettings*.json`): ninguna cadena con `Password=`/`Pwd=` con
     valor, ninguna URI `postgres(ql)?://<usuario>:<clave>@`, ningún host ni usuario de una conexión real de la misión.
   - `DatabaseContractGate`: el código no lee variables `DB_*` fuera del contrato; hay migraciones solo si el plan
     declaró `database`.
   - En una ronda de evidencia, una migración ya aplicada en el servidor (según su historial) no se puede editar:
     el cambio va en `V<n+1>`.
5. **Npgsql** (versión exacta) entra en `BaselineDependencies` y se precarga en `sandbox/images/dotnet-app`
   (decisión: pieza central del rol; su licencia, PostgreSQL License, no está en la lista automática de
   `LicenseClassifier`). Se agrega también `PostgreSQL` a `LicenseClassifier` como licencia permisiva.
6. **Diego**: `RoleLayerCatalog` sin cambios de capas; capabilities suman `postgresql-ddl` y `sql-migrations`.

## 3. Crear la base en el servidor del fundador

- **Cuándo**: automáticamente al final de la misión si el código quedó `VERIFIED` y el plan tiene `database` con
  `connectionName`; y a pedido del fundador (chat "aplica el esquema de MISSION-X", botón en Missions,
  `POST /api/company/missions/{id}/database/apply`), p. ej. después de cargar la conexión. Nunca si el código no está
  `VERIFIED`.
- **Quién**: `DatabaseSchemaApplier` en `company-core` (driver JDBC `org.postgresql:postgresql`), nunca el sandbox ni
  el modelo. La clave se descifra solo en memoria; nunca va a logs, evidencias, eventos ni al chat.
- **Pasos**:
  1. Conecta con TLS según la conexión (`sslmode=require` o `verify-full` con el CA cargado); timeout de conexión 10 s.
  2. Si `database` no existe: se conecta a `postgres` y ejecuta `CREATE DATABASE "<database>"`. Si existe, la usa.
  3. Crea si falta `forjai_schema_history (version int primary key, description text, checksum char(64),
     applied_at timestamptz, mission_id text, commit_sha text)`.
  4. Lee las migraciones del commit verificado (`DevelopmentWorkspaceService`), calcula SHA-256 de cada una y compara con
     el historial: una aplicada con checksum distinto → no aplica nada y reporta "V<n> se editó después de aplicarse".
  5. Aplica en orden las pendientes, cada una en su transacción con su fila de historial; timeout de 5 min por
     migración. Si una falla, se revierte esa y se detiene; las anteriores quedan.
- **Registro**: `AgentTask` "DATABASE_APPLY" (actor `forjai`) con el resultado por migración o el error exacto del
  servidor; `Evidence` por migración (`database:<conexión>@<commit>/V<n>`); eventos
  `EMPRESA_DATABASE_SCHEMA_APPLIED|FAILED` (`{missionId, connectionName, applied, failedVersion?, error?}`).
- **Si falla** (sintaxis, permisos, red): el `validationStatus` del código no cambia; la misión llega al fundador con
  "Esquema no aplicado: <error>" en el estado verificable. "Pedir más evidencia" lo vuelve a intentar con una migración
  nueva de Diego. (En el bloque 7 este error entra al ciclo de corrección.)
- **Si falta la conexión**: la misión llega con el pendiente "Falta la conexión PostgreSQL para crear la base: cárgala
  en Bases de datos y escribe 'aplica el esquema de MISSION-X'".

## 4. Command Center y chat

- **Settings → "Bases de datos"**: lista (nombre, motor, host, base, usuario, entorno, TLS, `****1234`, última prueba);
  agregar, editar, probar, eliminar.
- **Missions**: el formulario asocia conexiones; el detalle de una misión de desarrollo muestra "Base de datos"
  (conexión, migraciones con estado aplicada/pendiente/error, botón "Aplicar esquema"). `api/types.ts` y
  `SpaController` se actualizan.
- **Chat** (Java, antes de cualquier modelo): "¿qué bases de datos hay?" (sin claves); asociación al iniciar misión;
  "aplica el esquema de MISSION-X" (gobernanza); "¿cómo va la base de MISSION-X?". Una clave pegada en el chat no se
  graba y la respuesta pide cargarla en Settings.

## Errores

- Sin `FORJAI_SECRETS_KEY`: no se puede guardar ni usar una conexión (mensaje claro).
- Conexión que no responde al aplicar: error registrado, misión con pendiente; no se reintenta sola.
- Una conexión borrada después de usarse: el historial de la misión conserva su nombre; aplicar de nuevo exige
  asociar otra.

## Testing

- Unitarios: cifrado y enmascarado; contrato de variables (nombres, caracteres); `MigrationFilesGate`,
  `SecretLiteralGate`, `DatabaseContractGate`; validador del plan con `database`; `DatabaseSchemaApplier` con un
  `DataSource` simulado (orden, historial, checksum editado, fallo a mitad, base inexistente, TLS); chat (listar,
  asociar, aplicar, clave pegada).
- Integración real del aplicador en `mvn test` contra un PostgreSQL efímero (Testcontainers con Podman), solo para el
  test; nunca en el sandbox de las misiones. Se salta con aviso si no hay Podman disponible.
- En vivo (registrar en `docs/HISTORY.md`): el fundador carga una conexión real (servidor propio o AWS RDS) y se lanza
  "una API .NET de citas para psicólogos con PostgreSQL usando la base <nombre>" → `VERIFIED`, tablas creadas en el
  servidor y `forjai_schema_history` con las versiones.

## Fuera de alcance

MongoDB y AWS DocumentDB; Firebase/Firestore; despliegue con inyección de variables (Andrea); bases para apps Flutter
sin backend; reintento automático del esquema dentro del ciclo de corrección (bloque 7).
