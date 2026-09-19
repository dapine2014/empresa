# Puente LEAD → cliente real — diseño

**Fecha**: 2026-09-17
**Estado**: aprobado por el usuario en brainstorming, pendiente de plan de implementación.

## Contexto y objetivo

Forjai tiene hoy dos pipelines de `Customer` que comparten el label pero nunca se
hablan entre sí:

- **Automático (🟢)**: `OpportunityMemoryService.recordCandidate` crea
  `Customer {status:'LEAD'}` cuando un agente identifica un candidato real
  durante una misión (`(:Opportunity)-[:HAS_CANDIDATE]->(:Customer)`).
- **Humano (🔴)**: `CustomerController`/`CustomerService`/`CustomerMemoryService`
  registran un `Customer` real cuando el fundador cierra una venta de verdad
  (`(:Mission)-[:HAS_CUSTOMER]->(:Customer)`), con su propia evidencia validada.

Investigación en el código real (no solo lectura de comentarios) confirmó que
esta separación va más allá de lo documentado: **no existe ningún camino, API,
comando de chat ni pantalla que le permita al fundador ver los LEADs que
generaron los agentes**, ni ningún Cypher que transicione `Customer.status`
desde `'LEAD'` a otra cosa — el estado LEAD/PROSPECT/CUSTOMER/PAYING_CUSTOMER
mencionado en comentarios (`OpportunityMemoryService.java:28`) es un concepto
documentado, no una máquina de estados implementada. Si un humano registra a
mano el cliente real que salió de un LEAD, ese LEAD sigue contando para
siempre como "prospecto abierto" en `countCustomersAndProspects()` — doble
conteo sin reconciliación.

El objetivo de esta feature es cerrar exactamente ese hueco: darle al fundador
una forma de **ver** los LEADs, **descartar** los que no sirven, y **convertir**
uno en cliente real sin duplicar el conteo — sin automatizar el contacto ni el
cierre de venta en sí (eso sigue siendo 100% del fundador, nivel 🔴 de
`empresa.md` §5).

Este documento es el resultado de una sesión de `superpowers:brainstorming`
(path arquitectural) — cada decisión de abajo fue presentada como alternativas
A/B al usuario y aprobada explícitamente antes de escribir este spec.

## Decisiones de diseño

### 1. Tres estados para `Customer`, sin estados intermedios de contacto

`Customer.status` pasa a admitir: `LEAD` (ya existe, sin cambios), `NULL`
(cliente real sin lead de origen, ya existe, sin cambios), y dos nuevos:
`CONVERTIDO` y `DESCARTADO`. Se descartó explícitamente un modelo con estados
intermedios de seguimiento de contacto (`CONTACTADO`/`INTERESADO`/etc.) — más
visibilidad de embudo, pero más superficie de API/estado para una primera
ronda que ya tiene su alcance bien definido; se puede agregar después si hace
falta.

Un LEAD descartado gana dos propiedades nuevas en el mismo nodo:
`discardReason` (string) y `discardedAt` (datetime).

### 2. Conversión: nodo nuevo + relación `CONVERTED_FROM`, no reutilización in-place

Al convertir un LEAD en cliente real, **no se reutiliza el nodo del LEAD** —
se crea un `Customer` real nuevo por el camino humano de siempre (mismo
`CustomerMemoryService.registerCustomer`, misma validación de evidencia), el
nodo del LEAD pasa de `status='LEAD'` a `status='CONVERTIDO'` (sin borrarse ni
fusionarse con el nuevo), y se enlaza el cliente nuevo al LEAD con
`(:Customer {id:customerId})-[:CONVERTED_FROM]->(:Customer {id:leadId})`. Se
decidió así explícitamente para **no tocar**
`CustomerMemoryService.registerCustomer` (código ya probado, con su propio
`customerId` elegido por el humano) — la alternativa (reutilizar el nodo del
LEAD in-place) hubiera exigido cambiar cómo ese método identifica el nodo a
escribir, un cambio más invasivo para el mismo resultado observable. Mismo
criterio que ya usa `OpportunityMemoryService` para no tocar el flujo humano
existente (ver su javadoc de clase).

### 3. `OpportunityMemoryService` gana el ciclo de vida completo del LEAD

Nuevos métodos en `OpportunityMemoryService` (ya es dueño de la creación de
`Customer{status:'LEAD'}`, así que también es dueño de su ciclo de vida — no
se crea una clase nueva para esto):

- `listLeads() -> List<LeadResponse>`: todos los `Customer` con
  `status='LEAD'` (los `CONVERTIDO`/`DESCARTADO` ya no son accionables, no se
  listan acá — quedan en el grafo para auditoría vía Cypher directo si hace
  falta, no vía esta API). Cada `LeadResponse` trae `id`, `name`,
  `description`, `source`, `sourceType`, `missionId`, `opportunityId`,
  `createdAt`.
- `discardLead(String leadId, String reason)`: exige que el LEAD exista y
  esté en `status='LEAD'` (`IllegalArgumentException` si no existe,
  `IllegalStateException` si ya no está en `LEAD` — mismo patrón sin manejo
  fino de errores HTTP del resto del proyecto, cae a 500 default). Setea
  `status='DESCARTADO'`, `discardReason`, `discardedAt`.
- `markConverted(String leadId)`: mismas validaciones que `discardLead`
  (debe existir y estar en `LEAD`). Setea `status='CONVERTIDO'`. Es un método
  interno, llamado por `CustomerService.registerCustomer` cuando el comando
  trae un `leadId` — no tiene su propio endpoint REST.

### 4. `CustomerCommand` gana `leadId` opcional

Campo nuevo `leadId` (nullable, sin `@NotBlank` — a diferencia de
`customerId`, que sigue siendo obligatorio). `CustomerService.registerCustomer`:
si `leadId` viene informado, primero llama a
`opportunityMemory.markConverted(leadId)` (falla rápido si el lead no es
válido, antes de tocar nada del flujo de registro real) y, tras crear el
`Customer` real de siempre, agrega la relación
`(:Customer {id:customerId})-[:CONVERTED_FROM]->(:Customer {id:leadId})`. Sin
`leadId`, el comportamiento es **exactamente el de hoy** — retrocompatible,
ningún llamador existente necesita cambiar.

### 5. API: controller nuevo `LeadController`

- `GET /api/company/leads` → `List<LeadResponse>` (sin paginación, mismo
  criterio que `GET /missions` — el volumen de LEADs activos hoy es bajo).
- `POST /api/company/leads/{leadId}/discard` (`DiscardLeadCommand{reason}`) →
  aplica `discardLead`, devuelve el `LeadResponse` actualizado.

No cuelga de `/missions/{missionId}/...` como `CustomerController` porque un
LEAD puede venir de cualquier misión y el fundador quiere verlos todos juntos,
no misión por misión.

### 6. Chat: `QueryIntent.LEADS`

Nuevo intent determinista (keywords `"leads"`/`"prospectos"`/`"a quién
contacto"`), formateado 100% en Java (`formatLeads()`, mismo nivel de detalle
que `formatOpportunities`) — nunca delegado a Ollama, mismo criterio que el
resto de `ChatIntentRouter`. Se agrega `LEADS` al enum `topic` de la
herramienta `query_company_memory` en `CeoService`, mismo patrón que los
topics existentes.

### 7. Sin eventos Kafka nuevos

Verificado en el código: el flujo humano completo (`CustomerController` →
`CustomerService` → `CustomerMemoryService`) **no publica ningún evento a
Kafka** hoy, a diferencia del flujo de misiones. Esta feature mantiene esa
misma convención — ni `discardLead` ni la conversión vía `markConverted`
publican nada nuevo. No se inventa un patrón de eventos que el resto de ese
flujo humano tampoco tiene.

### 8. El conteo agregado (`countCustomersAndProspects`) no necesita cambios

Verificado: esa consulta cuenta "prospectos" por `status='LEAD'` y "clientes"
por `status IS NULL`. Un LEAD que pasa a `CONVERTIDO` o `DESCARTADO` sale solo
del conteo de prospectos (ya no matchea `status='LEAD'`), y el `Customer`
real nuevo (sin `status`, como cualquier cliente real de hoy) ya entra en el
conteo de clientes sin ningún cambio de código. El doble conteo que existía
antes de esta feature queda resuelto como efecto secundario del diseño, no
por un fix aparte.

### 9. Sin frontend nuevo por ahora

Mismo criterio que la feature de rondas de evidencia (`docs/superpowers/specs/
2026-09-16-evidence-rounds-design.md`): API REST + chat alcanza para esta
ronda; una pantalla `LeadsPage.tsx` en el Command Center queda como posible
seguimiento, no bloqueante para que la feature sea usable.

## Testing

- `OpportunityMemoryServiceTest` — no existe hoy (es un `*MemoryService`,
  integración Neo4j, mismo criterio de "sin test directo" ya establecido en
  el proyecto para toda esa familia de clases).
- `CustomerServiceTest`: nuevo caso con `leadId` presente y válido (llama a
  `markConverted`, crea el cliente, agrega `CONVERTED_FROM`); nuevo caso con
  `leadId` apuntando a un lead ya `CONVERTIDO`/`DESCARTADO` (falla, no crea
  nada); casos existentes sin `leadId` deben seguir pasando sin cambios
  (retrocompatibilidad).
- `LeadControllerTest` (nuevo, mismo estilo que `CompanyControllerTest`): los
  dos endpoints nuevos.
- `ChatIntentRouterTest`: ruteo nuevo a `LEADS`, nueva rama en el test del
  callback de `query_company_memory`.

## Fuera de alcance de esta ronda (documentado, no descartado)

- **Estados intermedios de contacto** (`CONTACTADO`/`INTERESADO`/etc.): se
  evaluó y se descartó por ahora (ver decisión 1) — sin caso de uso real hoy
  que lo exija, agregar estados sin uso sería especular.
- **Frontend (`LeadsPage.tsx`)**: fuera de alcance de esta ronda (ver
  decisión 9), mismo patrón que la feature anterior.
- **Editar/reabrir un LEAD descartado**: inmutable una vez `DESCARTADO`,
  mismo criterio de inmutabilidad que el resto de los registros financieros
  del proyecto (`Customer`/`Transaction`/`Decision`/`Expense`). Si se
  descartó por error, no hay corrección automática — queda para una ronda
  aparte si se vuelve un problema real.
- **Notificación/alerta cuando aparece un LEAD nuevo**: no exigido — el
  fundador ya puede consultarlos vía `GET /leads` o el chat cuando quiera;
  agregar una alerta por correo sería especular sin un caso de uso real que
  lo pida.
