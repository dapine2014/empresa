# Aria v1: requisitos aprobados antes de construir — diseño

**Fecha**: 2026-10-03
**Estado**: diseño aprobado por el fundador en conversación (secciones 1 a 4); pendiente de revisión de este spec.
**Base**: spec `2026-10-01-development-group-fase1-design.md` §3 (Aria), adaptado para funcionar **sin** la cola de
misiones durables (plan 4), que todavía no existe. Rama `aria-v1` (sale de `apps-completas-b1`).

## Objetivo

Que toda misión del Development Group parta de requisitos explícitos que el fundador aprobó: Aria (Product Owner)
convierte el pedido en roles, historias de usuario con criterios y escenarios, supuestos, fuera de alcance e
incrementos (el 1 es el MVP); Neo y el equipo construyen **solo el incremento aprobado**.

Por qué ahora (verificado en vivo, 2026-10-03): con el mismo pedido de CRM, `nemotron-3-ultra` construyó 11 rutas,
librerías extra y pantallas no pedidas y no compiló; el alcance quedaba a criterio del modelo. Aria es además la base
del rediseño de QA decidido por el fundador (casos de uso → pruebas automatizadas), que va después.

## Decisiones del fundador (2026-10-03)

1. Aria v1 **sin cola**: la misión se pausa para aprobación; los incrementos siguientes se lanzan a mano. Encadenarlos
   solos llega con las misiones durables (Kafka).
2. El fundador revisa y aprueba en **Missions** (detalle completo con botones) y en el **chat** (resumen + comandos).
3. Aria lista los **roles** del producto (quién lo usa: comprador, vendedor, paciente, jugador, sistema externo…) y cada
   HU usa uno; sin perfiles con personalidad ni simulación (descartado MiroFish).
4. La aprobación de las HU es obligatoria mientras el equipo se entrena (`SPEC_APPROVAL_REQUIRED = 1`, ya decidido en
   el spec de septiembre).

## 1. Flujo y datos

Misión con `teamId = TEAM-DEVELOPMENT`:

`Aria (REQUIREMENTS_SPEC)` → **`AWAITING_SPEC_APPROVAL`** → aprobado → `Neo (plan del incremento 1)` → equipo por capas
→ sandbox → … → `AWAITING_INVESTOR`.

**Aria** (agente `product-owner`) corre en una tarea `<misión>-REQUIREMENTS` (`kind` `REQUIREMENTS`), con `format` =
`RequirementsSpecSchema` y **sin tools**. Recibe el pedido completo (en un ajuste, además el spec anterior y las
instrucciones del fundador).

**`RequirementsSpec`**:
- `summary`, `assumptions[]` (ambigüedades resueltas y cómo), `outOfScope[]`.
- `roles[]`: `name` y `description` (quién usa el producto).
- `userStories[]`: `id` (`HU-01`), `role` (uno de `roles`), `iWant`, `soThat`, `acceptanceCriteria[]`, `dependsOn[]`
  (ids de HU), `scenarios[]` con `id` (`ESC-01`), `title`, `given[]`, `when[]`, `then[]`.
- `increments[]`: `number` (1 = MVP), `goal`, `userStoryIds[]`.

**Persistencia** (Neo4j, Cypher a mano como el resto): `(:Mission)-[:HAS_REQUIREMENTS]->(:RequirementsVersion {version,
specJson, createdAt, instruction, status})`, una por versión (`DRAFT` → `APPROVED` | `SUPERSEDED` | `REJECTED`).
`Mission.approvedSpecVersion` dice qué versión se construye; `Mission.builtIncrement` el último incremento construido.

**Pausa**: `MissionStatus.AWAITING_SPEC_APPROVAL` (nuevo). Tras un spec válido, `MissionExecutor.advanceMission` deja la
misión en ese estado y **termina el hilo**; nada queda bloqueado. Al aprobar, Java reanuda la misión desde el plan de
Neo con el mismo mecanismo que las rondas de evidencia (`reexecuteAsync`-like). Sobrevive a un reinicio de
`company-core` mientras espera.

## 2. Validación y aprobación

**`RequirementsSpecValidator`** (Java, puro), hasta 3 intentos con `CORRECCIÓN DEL INTENTO ANTERIOR`:
- ≥1 rol con nombre único; ≥1 HU; el `role` de cada HU está en `roles`.
- Cada HU con ≥1 criterio y ≥1 escenario; `given`/`when`/`then` no vacíos.
- Ids con formato exacto (`HU-\d{2}`, `ESC-\d{2}`) y únicos en todo el spec.
- Cada HU en exactamente un incremento; incrementos numerados desde 1 sin huecos; cada incremento con ≤ 12 escenarios.
- `dependsOn` apunta a HU existentes, sin ciclos y nunca a una HU de un incremento posterior.

La cobertura "cada criterio del pedido tiene escenario" del spec de septiembre **no** aplica en v1: el pedido es texto
libre sin lista de criterios; la revisa el fundador al aprobar.

**Aprobación** (gobernanza, como las decisiones del inversionista):
- API: `GET /api/company/missions/{id}/requirements` (versión vigente y aprobada) y
  `POST /api/company/missions/{id}/requirements/decision` con `APPROVE` | `ADJUST` (+ `instruction`) | `REJECT`.
  Solo con la misión en `AWAITING_SPEC_APPROVAL`.
- `APPROVE` → la versión vigente pasa a `APPROVED`, se graba `approvedSpecVersion`, la misión sigue (incremento 1).
- `ADJUST` → Aria corre de nuevo con el spec anterior y la instrucción; versión nueva; mismo validador; vuelve a
  `AWAITING_SPEC_APPROVAL`. Sin límite de ajustes.
- `REJECT` → `CANCELLED`.
- Chat (`ChatIntentRouter`, Java, con la gobernanza antes de las menciones): "muéstrame las HU de MISSION-X" (resumen:
  roles, HU por incremento, supuestos y fuera de alcance), "apruebo las HU de MISSION-X", "rechaza las HU de MISSION-X",
  "para MISSION-X: <instrucción>" (= `ADJUST` si la misión espera aprobación). Al quedar el spec listo, el chat lo
  anuncia en el status.
- Correo al fundador al entrar en `AWAITING_SPEC_APPROVAL` (`AlertMailService`, igual que `AWAITING_INVESTOR`).
- Command Center → Missions: sección **"Requisitos (Aria)"** con roles, HU (criterios y escenarios), supuestos, fuera de
  alcance e incrementos; botones Aprobar / Ajustar (texto) / Rechazar en `AWAITING_SPEC_APPROVAL`; botón "Construir
  incremento N" cuando corresponde. `api/types.ts` actualizado.
- Policy nueva `SPEC_APPROVAL_REQUIRED` (0/1, default 1, como `ORCHESTRATOR_ENABLED`). Con 0, el spec válido pasa
  directo al plan de Neo (queda `APPROVED` por `policy`).

## 3. Construcción e incrementos

- Neo (`TeamWorkPlanner`) recibe **solo las HU del incremento a construir**, con sus escenarios y los roles; el resto del
  spec va como "fuera de este incremento". Los prompts de los agentes que escriben código y de Vera reciben el mismo
  bloque (armado por Java). Vera sigue escribiendo tests como hoy, ya viendo los escenarios (el rediseño de QA es
  posterior).
- **"Construye el incremento N de MISSION-X"** (chat) o botón: ronda nueva de la misma misión sobre el mismo repositorio
  con las HU del incremento N. Requisitos: spec aprobado, N existe, `builtIncrement = N - 1` (el anterior construido) y
  la misión detenida (`AWAITING_INVESTOR`/`COMPLETED`/`FAILED`). **No consume** `MAX_EVIDENCE_ROUNDS`. Al terminar,
  `builtIncrement = N`.
- Una misión del Development Group **sin** spec aprobado nunca pasa al plan de Neo (salvo `SPEC_APPROVAL_REQUIRED = 0`).
- Misiones viejas (sin requisitos) y rondas de evidencia siguen como hoy.
- **Fuera de v1**: HU nuevas después de aprobar (van al final del plan, spec de septiembre §11), incrementos encadenados
  solos y `DevelopmentRequest`/cola → misiones durables.

## 4. Errores

- Spec inválido 3 veces → `FAILED` con los motivos; Neo no corre.
- Modelo de Aria caído → su suplente local (`ModelHealthService`, existente).
- Decisión sobre una misión que no está en `AWAITING_SPEC_APPROVAL` → rechazo con el motivo (convención:
  `IllegalStateException`).
- Aria apagada (`Agent.enabled = false`) → una misión del Development Group no arranca (como hoy con el líder).

## Eventos nuevos

`EMPRESA_REQUIREMENTS_READY`, `EMPRESA_REQUIREMENTS_APPROVED`, `EMPRESA_REQUIREMENTS_ADJUSTED`,
`EMPRESA_REQUIREMENTS_REJECTED`, `EMPRESA_INCREMENT_STARTED`. Se agregan a `docs/EVENTS.md`.

## Testing

Unitarios (JUnit + Mockito):
- `RequirementsSpecValidator`: roles (vacío, repetido, HU con rol inexistente), ids, HU sin escenario, incrementos con
  huecos, > 12 escenarios, dependencias inexistentes, ciclos y hacia adelante.
- Flujo: tras Aria la misión queda en `AWAITING_SPEC_APPROVAL` sin llamar a Neo; `APPROVE` reanuda con solo el
  incremento 1; `ADJUST` crea versión nueva y vuelve a esperar; `REJECT` cancela; policy en 0 pasa directo.
- Incremento N: rechazos (spec sin aprobar, N inexistente, N-1 sin construir, misión corriendo) y ronda con las HU de N
  sin consumir rondas de evidencia.
- Chat: los cuatro comandos y la consulta, sin llamar a un modelo; endpoints del controller.

**Prueba en vivo** (`docs/HISTORY.md`):
1. CRM: Aria escribe roles e HU → el fundador las aprueba por chat → `VERIFIED` con solo el incremento 1.
2. Incremento 2 del mismo CRM.
3. Con el alcance acotado, repetir la comparación `nemotron-3-ultra` vs `kimi-k3` en el Development Group.
