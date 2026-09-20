# Contacto real por email a prospectos (Sub-proyecto D de "Company Chat completo") — diseño

**Fecha**: 2026-09-20
**Estado**: aprobado por el usuario en brainstorming, pendiente de plan de implementación.

## Contexto y objetivo

Última pieza del orden aprobado para "Company Chat completo" (A → B → E → C → D). Decisiones ya tomadas por el usuario al principio de esta sesión: **"Contacta al primer prospecto"** debe ejecutar de verdad (opción "Ejecutar de verdad (enviar)"), y el canal es **email, reusando `AlertMailService`**.

Este documento pasó por 2 rondas: un diseño inicial simple (flag de status + boolean de éxito), y una versión "v2" mucho más ambiciosa que el usuario pegó después (entidad `ContactAttempt` de primera clase, idempotencia por hash, `providerMessageId`, un módulo de "Governance", secret manager, rate limiting, métricas de observabilidad, feed de actividad). Auditando esa v2 contra el código real se encontraron 3 problemas concretos:

1. **"Governance" (`mission.contactAllowed == true`) no existe** en ningún lado del código — sería una pieza nueva grande, no parte natural de esta ronda.
2. **La v2 exige "credenciales solo en secret manager, nunca en Neo4j"** — pero `AlertMailService` guarda `mailPassword` en Neo4j a propósito, una decisión de MVP ya tomada y documentada en `CLAUDE.md` ("Alertas por correo"). La v2 contradice una decisión de arquitectura ya aceptada, no la extiende.
3. **`providerMessageId`** asume un proveedor de email tipo API (SendGrid/Mailgun/etc.) que confirma la entrega con un identificador — pero `AlertMailService` manda por SMTP genérico (`spring-boot-starter-mail`/`JavaMailSenderImpl`, típicamente Gmail), que no da ese dato de forma confiable; `mailSender.send(...)` es `void`.

**Decisión del usuario**: recortar la v2 a su núcleo real para esta ronda — mantener la idea de `ContactAttempt` como entidad real (en vez de solo un flag de status, que era la limitación real que la v2 señalaba correctamente) y la reclamación por comparar-y-actualizar (CAS) para evitar doble envío, pero **sin** Governance, **sin** secret manager, **sin** `providerMessageId`/rate limiting/métricas de observabilidad/feed de actividad — todo eso documentado como fuera de alcance, no descartado, mismo criterio que cada ronda anterior de esta sesión.

## Decisiones de diseño

### 1. `AgentResult.CustomerCandidate` gana `contactEmail` + `contactEmailSource` (ambos opcionales, pero acoplados)

```java
public record CustomerCandidate(
        String name,
        String description,
        String source,
        String sourceType,
        double confidence,
        String contactEmail,
        String contactEmailSource
) {
    // Constructor de compatibilidad de 5 args (sin datos de contacto) --
    // evita tocar los 3 `new AgentResult.CustomerCandidate(...)` ya
    // existentes en AgentResultValidatorTest/MissionExecutorTest.
    public CustomerCandidate(String name, String description, String source, String sourceType, double confidence) {
        this(name, description, source, sourceType, confidence, null, null);
    }
}
```

`AgentRuntime.buildPrompt` (el bloque de reglas para `customerCandidates`, ya existente) gana una instrucción nueva: buscar un canal de contacto público real junto con cada candidato — **explícitamente válido tanto un email general/de ventas de la empresa** (`info@`/`ventas@`/`sales@`/`contacto@`, encontrado en la página de contacto o el pie de página del sitio) **como el de una persona puntual** si lo hay. `contactEmailSource` debe citar la página exacta donde se encontró (nunca deducido del dominio de la empresa — ej. inventar `nombre@empresa.com` sin haberlo visto publicado es justo el caso que se prohíbe). Si no encontró ninguno, dejar ambos campos vacíos.

`AgentResultValidator.validateCustomerCandidates` (ya existente, valida `confidence` en rango) gana una regla nueva: si `contactEmail` viene no vacío, `contactEmailSource` también debe venir no vacío — un email sin de dónde salió no es un dato real, es exactamente el "parece válido" que la v2 correctamente prohibía. Rechaza la tarea igual que cualquier otro error de validación, con reintento (mismo mecanismo ya existente).

### 2. Persistencia: `Customer.contactEmail`/`contactEmailSource`, y `ContactAttempt` como entidad real

`OpportunityMemoryService.recordCandidate` persiste `c.contactEmail`/`c.contactEmailSource` en el `ON CREATE SET` del nodo `Customer`. Los 4 métodos de lectura que devuelven `LeadResponse` (`listLeads`, `discardLead`, `listCandidatesForMission`, `findCandidatesByIds`) agregan ambos campos a su `RETURN`.

`LeadResponse` gana `contactEmail`/`contactEmailSource` (14 campos), con un constructor de compatibilidad de 12 args (ambos quedan `null`) para no tocar los ~14 call-sites de test que ya lo construyen con la firma actual.

**`ContactAttempt`, nodo nuevo** (la mejora real que rescata la v2 sobre solo cambiar `Customer.status`): un registro de auditoría de cada intento real de contacto, independiente del estado actual del prospecto.

```
(:Customer)-[:HAS_CONTACT_ATTEMPT]->(:ContactAttempt {
    id, prospectId, missionId, opportunityId,
    channel: "EMAIL", destination, subject,
    status: "PENDING" | "SENT" | "FAILED",
    requestedBy: "human",
    initiatedAt, sentAt, errorMessage
})
```

Nuevos métodos en `OpportunityMemoryService`:

- `boolean claimForContact(String leadId)` — CAS: `MATCH (c:Customer {id:$id, status:'LEAD'}) SET c.status='CONTACT_IN_PROGRESS' RETURN c.id`. Si no hay filas (el lead ya fue reclamado, contactado, convertido o descartado por otra operación concurrente o un "contactalo" repetido), devuelve `false` — **este es el mecanismo real de "no enviar dos veces"**, más fuerte que solo sacar el lead de `listLeads()` después: dos comandos "contactalo" casi simultáneos no pueden ganar ambos el CAS.
- `String recordContactAttempt(String leadId, String missionId, String opportunityId, String destination, String subject)` — crea el `ContactAttempt {status:'PENDING'}`, devuelve su id.
- `void markContactSent(String attemptId, String leadId)` — `ContactAttempt.status='SENT'` + `Customer.status='CONTACTADO'`.
- `void markContactFailed(String attemptId, String leadId, String errorMessage)` — `ContactAttempt.status='FAILED'` + **revierte `Customer.status='LEAD'`** (un envío que falló debe poder reintentarse después, no quedar atascado en `CONTACT_IN_PROGRESS` para siempre).

`CONTACTADO`/`CONTACT_IN_PROGRESS` salen de `listLeads()`/`listCandidatesForMission` automáticamente (ambos ya filtran estrictamente `status='LEAD'`).

### 3. `AlertMailService.sendToExternal` — envío real a un destinatario arbitrario

```java
public record ExternalMailResult(boolean accepted, String errorMessage) {}

public synchronized ExternalMailResult sendToExternal(String to, String subject, String body) {
    // mismo bloque systemEmail/mailPassword que send(), pero:
    // - helper.setTo(to) en vez de memory.alertEmail()
    // - devuelve ExternalMailResult (accepted + motivo real de fallo) en
    //   vez de nunca lanzar y nunca informar el resultado -- una alerta
    //   interna fallida no debe tumbar nada (por eso send() sigue como
    //   está), pero acá el chat le tiene que decir la verdad al fundador.
    // Sin providerMessageId: JavaMailSenderImpl.send(...) es void, SMTP
    // genérico no lo da -- no se inventa un dato que no existe.
}
```

Reusa `AlertEmailTemplate.html(subject, body, critical=false)` para el HTML. `send()` (alertas internas al fundador) no se toca.

### 4. Contenido determinista: `ProspectOutreachEmailTemplate`

Sin cambios respecto al diseño previo: clase nueva, package-private, mismo patrón que `AlertEmailTemplate` (función pura, testeable sin JavaMail). Saludo genérico ("Hola equipo de {empresa}", nunca asume un nombre de persona — funciona igual si el destinatario real es un buzón general de ventas). Nunca pasa por el CEO.

### 5. Disparo: `CompanyTools.contactProspect(LeadResponse candidate)`

`CompanyTools` gana dos dependencias nuevas de constructor: `AlertMailService`, `CompanyEventPublisher`.

```java
public String contactProspect(LeadResponse candidate) {

    if (candidate.contactEmail() == null || candidate.contactEmail().isBlank()) {
        return "No tengo un dato de contacto directo (teléfono/email) registrado para "
                + "este prospecto, solo la fuente donde se identificó.";
    }

    if (!opportunityMemory.claimForContact(candidate.id())) {
        return "Este prospecto ya tiene un contacto en progreso o ya fue contactado.";
    }

    var subject = ProspectOutreachEmailTemplate.subject(candidate);
    var body = ProspectOutreachEmailTemplate.body(candidate);

    var attemptId = opportunityMemory.recordContactAttempt(
            candidate.id(), candidate.missionId(), candidate.opportunityId(),
            candidate.contactEmail(), subject
    );

    var result = alertMailService.sendToExternal(candidate.contactEmail(), subject, body);

    if (!result.accepted()) {
        opportunityMemory.markContactFailed(attemptId, candidate.id(), result.errorMessage());
        events.publish("EMPRESA_PROSPECT_CONTACT_FAILED", candidate.missionId(), null, "ceo",
                Map.of("leadId", candidate.id(), "attemptId", attemptId, "reason", result.errorMessage()));
        return "Intenté enviar el correo, pero no se pudo (" + result.errorMessage() + "). "
                + "El intento quedó registrado y el prospecto sigue disponible para reintentar.";
    }

    opportunityMemory.markContactSent(attemptId, candidate.id());
    events.publish("EMPRESA_PROSPECT_CONTACTED", candidate.missionId(), null, "ceo",
            Map.of("leadId", candidate.id(), "attemptId", attemptId, "recipientEmail", candidate.contactEmail()));

    return "Listo, le mandé un correo real a " + candidate.contactEmail() + ". (ContactAttempt " + attemptId + ", estado: SENT)";
}
```

`ChatIntentRouter.formatCustomerReferenceAnswer` (hoy siempre devuelve el texto fijo de "no tengo contacto") delega en `companyTools.contactProspect(candidate)` para armar esa parte de la respuesta — la desambiguación (cuál candidato, si aclarar "te muestro el de mayor probabilidad") no cambia.

### 6. Auditoría: 2 eventos Kafka nuevos

`EMPRESA_PROSPECT_CONTACTED` (`data: {leadId, attemptId, recipientEmail}`) y `EMPRESA_PROSPECT_CONTACT_FAILED` (`data: {leadId, attemptId, reason}`), `missionId` en el envelope — se agregan a `docs/EVENTS.md`. Deliberadamente **no** `EMPRESA_PROSPECT_CONTACT_STARTED` (toda la operación es síncrona, dura milisegundos dentro del mismo turno de chat — un evento de "empezó" no aporta valor de auditoría que `CONTACTED`/`CONTACT_FAILED` no den ya).

## Testing

- `AgentResultValidatorTest`: caso nuevo, `contactEmail` presente sin `contactEmailSource` → inválido (mismo mecanismo de rechazo que el resto de las reglas de este validador).
- `AlertMailServiceTest`: tests nuevos para `sendToExternal` — envía al destinatario dado (no a `alertEmail`), `accepted=true` en éxito, `accepted=false` con `errorMessage` real si la cuenta del sistema no está configurada o si `mailSender.send` falla (nunca lanza).
- `CompanyToolsTest` para `contactProspect`: sin `contactEmail` → mensaje determinista, nunca toca `alertMailService`/`opportunityMemory.claimForContact`; `claimForContact` devuelve `false` (ya reclamado/contactado) → mensaje determinista de "ya tiene un contacto en progreso", nunca llama a `sendToExternal`; envío exitoso → `recordContactAttempt` + `sendToExternal` + `markContactSent` + evento `EMPRESA_PROSPECT_CONTACTED`, en ese orden; envío fallido → `markContactFailed` (revierte a `LEAD`) + evento `EMPRESA_PROSPECT_CONTACT_FAILED`, **nunca** `markContactSent`.
- `ChatIntentRouterTest`: "contactalo" contra un foco `CUSTOMER` cuyo candidato tiene `contactEmail` real llama a `companyTools.contactProspect(...)`.
- `ProspectOutreachEmailTemplateTest`: asunto/cuerpo con datos reales del candidato, saludo genérico, sin placeholders.
- Sin test directo para los métodos nuevos de `OpportunityMemoryService` (integración Neo4j pura, mismo criterio ya establecido) — verificado en vivo, incluyendo el caso de dos "contactalo" seguidos contra el mismo prospecto (el segundo debe fallar el CAS).

## Fuera de alcance de esta ronda (documentado, no descartado)

- **Governance / `mission.contactAllowed`**: no existe ningún módulo de gobernanza de misiones hoy; agregar uno es una iniciativa propia, no parte de esta ronda.
- **Secret manager para credenciales SMTP**: contradice la decisión de MVP ya tomada (`mailPassword` en Neo4j) — se mantiene como está.
- **`providerMessageId`, reconciliación de envíos a mitad de caída**: requiere un proveedor de email tipo API, no SMTP genérico.
- **Idempotency key por hash SHA-256**: el CAS sobre `Customer.status` ya resuelve el caso real (dos "contactalo" casi simultáneos) sin necesitar una clave adicional.
- **Rate limiting por misión/prospecto/destinatario**.
- **Métricas de observabilidad** (prospects contactados, latencia, tasa de rebote) — el proyecto ya tiene precedente de métricas Micrometer (`evidence.*`), se puede agregar cuando haya un pedido concreto.
- **Feed de actividad** (`ActivityMemoryService`) mostrando los intentos de contacto — extensión natural futura, no esencial para que "contactalo" funcione de verdad.
- **Pipeline de estados extendido** (`PROSPECT`, `RESPONDED`, `INTERESTED`, `NOT_INTERESTED`, `BOUNCED`) y **`NO_CONTACTABLE`**: requieren infraestructura de tracking de respuestas/rebotes que no existe; el estado se queda en `LEAD` (recuperable, se puede reintentar si más adelante se encuentra un email) hasta `CONTACTADO`.
- Registrar/editar manualmente el email de un prospecto desde el chat o el Command Center web.
- Mostrar `contactEmail`/`contactEmailSource` en `getProspects()`/`formatCandidate`.
- Seguimiento automático (recordatorio, segundo email).
- Canales de contacto distintos a email.
- Paso de confirmación/preview antes de enviar (decidido explícitamente en contra).
