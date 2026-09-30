# Contacto con prospectos — diseño

**Fecha**: 2026-09-30
**Estado**: diseño aprobado por el fundador en conversación (partes 1 y 2); pendiente de revisión de este spec.
**Subproyecto 6 del ciclo de producto** (orden: 5 búsqueda de prospectos ✅ → 6 contacto (este) → 7 reventa). Porta, sin
mergear, las piezas útiles de `worktree-lead-conversion` y `worktree-prospect-chat-quality` (spec del 2026-09-20).

## Contexto y objetivo

La búsqueda diaria (subproyecto 3) guarda prospectos reales con contacto verificado (`Customer {status:'LEAD'}` +
`(:Product)-[:HAS_PROSPECT]`). Falta escribirles, sabiendo que contactar es 🔴 (decisión del fundador), y convertir en
cliente real al que compra. La rama del 20-sep implementó el envío uno por uno para candidatos de discovery sobre un
`master` del 17-sep; acá se rediseña sobre el `master` actual y por producto.

## Decisiones del fundador (2026-09-30)

1. **Lote diario aprobado**: Forjai prepara un borrador por prospecto nuevo; el fundador aprueba, edita o descarta cada
   uno (o todos). Solo lo aprobado se envía.
2. **Sofía redacta y Java verifica** (borrador personalizado; el fundador lo ve antes de enviar).
3. **Se envía desde la cuenta SMTP de alertas** (`Company.systemEmail`), con `Reply-To` = correo del fundador
   (`Company.alertEmail`) y un tope diario.
4. Sin seguimientos automáticos en esta entrega: un correo por prospecto.

## 1. Borradores (🟢, automático)

- Después de cada `ProspectingRun` COMPLETED, `OutreachService.draftFor(run)` redacta un borrador por cada prospecto nuevo
  de esa corrida **con email**. Los que solo tienen formulario no reciben borrador: se muestran como "contactar a mano".
- Sofía: `CeoService.draftOutreach(productSheet, prospect, signature, model)` (`format` = `{subject, body}`, sin `tools`),
  con la ficha del producto, el `fitReason` y el nombre del prospecto.
- **Validación Java (`OutreachDraftValidator`, función pura)**; si falla, se repite con `CORRECCIÓN DEL INTENTO ANTERIOR`
  hasta 3 intentos; si sigue fallando, no se guarda y queda anotado en la corrida:
  1. `subject` 1–120 caracteres; `body` ≤ 1.200 caracteres.
  2. Contiene el **nombre exacto** del producto (de la ficha).
  3. Si la ficha tiene precio (no "a cotizar"): contiene el precio exacto (`US$<precio>` o `<precio> USD`, admitiendo
     `39` y `39.00`); ningún otro monto con `US$`/`USD`/`$`.
  4. Contiene la **línea de baja** (texto fijo que Java agrega si falta: "Si no te interesa, responde «no» y no volveremos
     a escribirte.") — Java la agrega, no se rechaza por esto.
  5. Contiene la **firma** configurada (Java la agrega al final si falta).
  6. No contiene URLs distintas de las del producto/Forjai ni direcciones de email distintas de la de respuesta.
- Guardado: `(:ContactDraft {id, prospectId, productId, subject, body, status: PENDING_APPROVAL, createdAt, attempts})`
  y `(:Customer)-[:HAS_DRAFT]->(:ContactDraft)`; el prospecto pasa a `status:'DRAFTED'`.
- Correo al fundador cuando hay borradores nuevos ("N correos por aprobar").

## 2. Aprobación (🔴, fundador)

- `PUT /api/company/outreach/drafts/{id}` (editar `subject`/`body`: vuelve a pasar la validación de Java, salvo el límite
  de intentos), `POST /drafts/{id}/approve`, `POST /drafts/{id}/discard`, `POST /drafts/approve-all`.
- Aprobar = enviar (sección 3) respetando el tope diario; lo que exceda el tope queda `APPROVED` y sale al día siguiente
  (chequeo horario del mismo scheduler).
- Descartar = `ContactDraft.status = DISCARDED`, el prospecto vuelve a `LEAD` (no se vuelve a redactar solo).

## 3. Envío (Java)

- `AlertMailService.sendToExternal(to, subject, body, replyTo)` → `ExternalMailResult(accepted, errorMessage)` (portado
  de la rama del 20-sep: mismo SMTP que las alertas, nunca lanza, devuelve la verdad). `send()` de alertas no cambia.
- Doble envío imposible: `claimForContact` (CAS `status IN ['LEAD','DRAFTED'] → 'CONTACT_IN_PROGRESS'`); `ContactAttempt`
  por intento `(:Customer)-[:HAS_CONTACT_ATTEMPT]->(:ContactAttempt {id, draftId, channel:'EMAIL', destination, subject,
  status: PENDING|SENT|FAILED, requestedBy:'human', initiatedAt, sentAt, errorMessage})`.
- Éxito → prospecto `CONTACTADO`, draft `SENT`. Fallo → prospecto vuelve a `DRAFTED`, draft `APPROVED` con el motivo (se
  reintenta al día siguiente dentro del tope o el fundador lo descarta).
- Nunca se envía a un email o dominio con baja (`(:OptOut {value})`, ver sección 4) ni sin cuenta SMTP configurada
  (rechazo con motivo claro).
- Policy nueva `MAX_OUTREACH_PER_DAY` (default 10, > 0) — cuenta envíos `SENT` del día UTC.

## 4. Después del envío

- El fundador registra la respuesta (las respuestas llegan a su bandeja; Forjai no lee correo):
  `POST /api/company/outreach/prospects/{id}/response` con `INTERESTED | NOT_INTERESTED | OPTED_OUT` → prospecto
  `INTERESADO | NO_INTERESADO | BAJA`. `OPTED_OUT` crea `(:OptOut {value: email})` y `(:OptOut {value: dominio})`.
- **Convertir en cliente**: `POST /api/company/outreach/prospects/{id}/convert` crea el cliente real de Finanzas
  (`FinanceService`, mismo registro que un cliente del fundador, entorno `PRODUCTION`) con nombre, email y el producto;
  el prospecto queda `status:'CONVERTED'` con `(:Customer)-[:CONVERTED_TO]->(:Customer)`. Un LEAD sigue sin poder recibir
  ventas; el cliente convertido sí.
- La búsqueda (subproyecto 3) nunca vuelve a proponer un dominio con baja (se suma a `knownDomains`).

## 5. Control y visibilidad

- Sin interruptor nuevo: los borradores se generan cuando "Buscar clientes" corre; nada sale sin aprobación. Settings:
  `MAX_OUTREACH_PER_DAY` y la firma (`Company.outreachSignature`, default "Forjai — forjai.com" editable).
- **Dashboard**: "Esperando tu decisión" suma "N correos por aprobar"; fila Clientes suma "M enviados hoy".
- **Prospectos**: sección "Correos por aprobar" (editar, aprobar, descartar, aprobar todos); estado de cada prospecto
  (`LEAD`, `BORRADOR`, `CONTACTADO`, `INTERESADO`, `NO INTERESADO`, `BAJA`, `CLIENTE`) e historial de intentos; botones de
  respuesta y "Convertir en cliente".
- **Chat** (Java): consultas "¿qué correos hay por aprobar?", "¿a quién contactamos?", "¿quién respondió?"; gobernanza
  "aprueba los correos", "aprueba el correo a X", "descarta el correo a X", "X respondió interesado|no interesado|pidió
  baja", "convierte a X en cliente" (nombre exacto gana; varios parciales → lista sin cambiar).
- **Eventos**: `EMPRESA_OUTREACH_DRAFTED {draftId, prospectId}`, `EMPRESA_OUTREACH_APPROVED {draftId}`,
  `EMPRESA_OUTREACH_SENT {draftId, attemptId}`, `EMPRESA_OUTREACH_FAILED {draftId, error}`,
  `EMPRESA_PROSPECT_RESPONDED {prospectId, response}`, `EMPRESA_PROSPECT_CONVERTED {prospectId, customerId}`.
- Al quedar en `master`, las ramas `worktree-lead-conversion` y `worktree-prospect-chat-quality` quedan obsoletas (se
  propone borrarlas).

## Errores

- Convención del proyecto (`IllegalArgumentException` → 500 con mensaje). Aprobar un draft que no está
  `PENDING_APPROVAL`/`APPROVED`, responder por un prospecto no contactado o convertir uno ya convertido → rechazo con
  motivo.
- La redacción nunca tumba la corrida de búsqueda: un fallo del modelo deja el prospecto en `LEAD` con el motivo.
- Un envío fallido nunca oculta el resultado: el fundador ve el motivo real (SMTP).

## Testing

- `OutreachDraftValidatorTest`: nombre ausente, precio distinto u otro monto, URL/email ajenos, largo; baja y firma se
  agregan solas; producto "a cotizar" sin precio.
- `OutreachServiceTest`: borradores solo para prospectos con email; 3 intentos con corrección; aprobar envía y marca;
  tope diario deja `APPROVED` para mañana; fallo SMTP revierte; opt-out bloquea; sin cuenta SMTP → rechazo; doble
  aprobación simultánea envía una sola vez (CAS).
- Conversión: crea cliente de Finanzas y enlaza; no convierte dos veces.
- `ChatIntentRouterTest`: consultas y comandos nuevos, sin modelo; no chocan con estrategias ni productos.
- En vivo: con el primer producto listo, una búsqueda → borradores → el fundador aprueba uno enviado a **su propia
  dirección de prueba** si lo prefiere → `ContactAttempt SENT` y prospecto `CONTACTADO`.

## Fuera de alcance

- Seguimientos automáticos, lectura de la bandeja, contacto por formulario o redes, cuenta de envío separada, métricas
  de apertura.
