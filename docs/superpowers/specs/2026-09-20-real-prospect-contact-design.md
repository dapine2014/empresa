# Contacto real por email a prospectos (Sub-proyecto D de "Company Chat completo") — diseño

**Fecha**: 2026-09-20
**Estado**: aprobado por el usuario en brainstorming, pendiente de plan de implementación.

## Contexto y objetivo

Última pieza del orden aprobado para "Company Chat completo" (A → B → E → C → D). Decisiones ya tomadas por el usuario al principio de esta sesión, antes de este spec: **"Contacta al primer prospecto"** debe ejecutar de verdad (opción "Ejecutar de verdad (enviar)", no una simulación), y el canal es **email, reusando `AlertMailService`**.

Auditando el código real antes de diseñar: hoy no existe ningún dato de contacto real en ningún lado del modelo. `AgentResult.CustomerCandidate` (lo que reporta un agente sobre un prospecto) solo tiene `name`/`description`/`source`/`sourceType`/`confidence` — `source` es la URL donde se identificó la empresa, nunca un email. Y `AlertMailService.send(subject, body, critical)` manda siempre al `alertEmail` del fundador (alertas internas) — no hay forma de mandarle un correo a un destinatario externo arbitrario. `ChatIntentRouter.formatCustomerReferenceAnswer` ("contactalo") hoy siempre responde "No tengo un dato de contacto directo... registrado".

Alcance acordado con el usuario en brainstorming (3 preguntas explícitas):
1. **El email real lo busca y reporta el agente** — se agrega un campo opcional `contactEmail` a `CustomerCandidate`, poblado si el agente lo encuentra públicamente durante su investigación (nunca inventado). Sin ese dato, el prospecto simplemente no es contactable todavía — no se agrega un paso separado de registro manual ni se le pide el email al fundador en el chat.
2. **"contactalo" ejecuta el envío real de inmediato**, sin paso de confirmación intermedio — el comando del fundador ES la aprobación, mismo criterio ya usado para "las dos misiones están aprobadas".
3. **El contenido del email es una plantilla determinista en Java**, con los datos reales insertados — nunca redactado por el CEO. Mismo criterio anti-alucinación de todo el proyecto, evitando el riesgo (distinto al de una respuesta interna de chat) de que el CEO invente algo hacia un desconocido externo real.

**Ajuste posterior del usuario**: no siempre hay un email visible de una persona/contacto puntual en una empresa — un email general/de ventas de la compañía (`info@`, `ventas@`, `sales@`, `contacto@`, etc.) es un dato de contacto igual de válido. El agente no debe descartar un candidato solo por no encontrar el email de una persona con nombre.

## Decisiones de diseño

### 1. `AgentResult.CustomerCandidate` gana `contactEmail` (opcional)

```java
public record CustomerCandidate(
        String name,
        String description,
        String source,
        String sourceType,
        double confidence,
        String contactEmail
) {
    // Constructor de compatibilidad de 5 args (sin contactEmail, queda null)
    // -- evita tocar los 3 `new AgentResult.CustomerCandidate(...)` ya
    // existentes en AgentResultValidatorTest/MissionExecutorTest.
    public CustomerCandidate(String name, String description, String source, String sourceType, double confidence) {
        this(name, description, source, sourceType, confidence, null);
    }
}
```

`AgentResultSchema.CUSTOMER_CANDIDATE_ITEM_SCHEMA` gana `"contactEmail", Map.of("type", "string")` en `properties` — **sin** `minLength` (puede venir vacío) y **sin** agregarlo a `required` (a diferencia de `name`/`description`/`source`/`sourceType`/`confidence`, que sí lo están): un agente puede legítimamente no encontrar ningún email público.

`AgentRuntime.buildPrompt` (el bloque de reglas para `customerCandidates`, ya existente) gana una instrucción nueva: buscar, junto con cada candidato, un email de contacto público real y reportarlo en `contactEmail` — **explícitamente válido tanto un email general/de ventas de la empresa** (`info@`/`ventas@`/`sales@`/`contacto@`, encontrado en la página de contacto, el pie de página del sitio, o su perfil) **como el de una persona puntual** si lo hay — no hace falta que sea de un individuo nombrado. Nunca inventado; si no encontró ninguno de los dos, dejar el campo vacío, nunca inventar uno con formato plausible.

### 2. Persistencia: `Customer.contactEmail` + nuevo status `CONTACTADO`

`OpportunityMemoryService.recordCandidate` persiste `c.contactEmail` en el `ON CREATE SET` del nodo `Customer` (junto a `name`/`status`/`confidence`, ya existentes). Los 4 métodos de lectura que ya devuelven `LeadResponse` (`listLeads`, `discardLead`, `listCandidatesForMission`, `findCandidatesByIds`) agregan `c.contactEmail AS contactEmail` a su `RETURN` y lo pasan al constructor.

`LeadResponse` gana el campo `contactEmail` (13º campo), con un constructor de compatibilidad de 12 args (sin `contactEmail`, queda `null`) para no tocar los ~14 call-sites de test que ya lo construyen con la firma actual.

Nuevo `OpportunityMemoryService.markContacted(String leadId)` — mismo patrón compare-and-swap de `markConverted` (`MATCH (c:Customer {id:$id, status:'LEAD'}) SET c.status='CONTACTADO'`). Al pasar a `CONTACTADO`, el prospecto sale automáticamente de `listLeads()`/`listCandidatesForMission` (ambos ya filtran estrictamente `status='LEAD'`, mismo criterio que ya excluye `CONVERTIDO`/`DESCARTADO`) — evita contactar dos veces por accidente sin necesitar ninguna guarda nueva.

### 3. `AlertMailService.sendToExternal` — envío real a un destinatario arbitrario

```java
public synchronized boolean sendToExternal(String to, String subject, String body) {
    // mismo bloque systemEmail/mailPassword que send(), pero:
    // - helper.setTo(to) en vez de memory.alertEmail()
    // - devuelve boolean (true = enviado, false = no se pudo) en vez de
    //   nunca lanzar y nunca informar el resultado -- una alerta interna
    //   fallida no debe tumbar nada (por eso send() nunca lanza), pero acá
    //   el chat le tiene que decir la verdad al fundador sobre si el
    //   correo a un prospecto real salió o no.
}
```

Reusa `AlertEmailTemplate.html(subject, body, critical=false)` para el HTML (documentado en `CLAUDE.md`: ese template ya se diseñó pensando en "a futuro escribirle a un cliente real").

### 4. Contenido determinista: `ProspectOutreachEmailTemplate`

Clase nueva, package-private, mismo patrón que `AlertEmailTemplate` (función pura, testeable sin JavaMail):

```java
final class ProspectOutreachEmailTemplate {
    static String subject(LeadResponse candidate) { ... } // fijo, ej. "Oportunidad de colaboración con Forjai"
    static String body(LeadResponse candidate) { ... }     // texto fijo con candidate.name()/description()/source() insertados
}
```

Redactado de forma genérica a propósito (nunca asume que se dirige a una persona con nombre — funciona igual de bien si el destinatario real es un buzón general de ventas): saludo neutro ("Hola equipo de {empresa}", no "Estimado/a {nombre}"). Nunca pasa por el CEO — se arma 100% en Java con datos ya reales del `LeadResponse` (el mismo objeto que ya usa `formatCandidate`).

### 5. Disparo: `CompanyTools.contactProspect(LeadResponse candidate)`

Nuevo método en `CompanyTools` (no en `ChatIntentRouter` — mismo criterio ya documentado en `CLAUDE.md`: la desambiguación de "contactalo" contra el foco conversacional se queda en `ChatIntentRouter` porque necesita objetos crudos, pero la acción real con efecto secundario es del tipo que ya vive en `CompanyTools`). `CompanyTools` gana dos dependencias nuevas de constructor: `AlertMailService`, `CompanyEventPublisher`.

```java
public String contactProspect(LeadResponse candidate) {

    if (candidate.contactEmail() == null || candidate.contactEmail().isBlank()) {
        return "No tengo un dato de contacto directo (teléfono/email) registrado para "
                + "este prospecto, solo la fuente donde se identificó.";
    }

    var sent = alertMailService.sendToExternal(
            candidate.contactEmail(),
            ProspectOutreachEmailTemplate.subject(candidate),
            ProspectOutreachEmailTemplate.body(candidate)
    );

    if (!sent) {
        return "Intenté contactar a " + candidate.contactEmail() + " pero no se pudo enviar "
                + "el correo real (revisá la configuración de correo del sistema en Settings).";
    }

    opportunityMemory.markContacted(candidate.id());

    events.publish(
            "EMPRESA_PROSPECT_CONTACTED",
            candidate.missionId(), null, "ceo",
            Map.of("leadId", candidate.id(), "recipientEmail", candidate.contactEmail())
    );

    return "Listo, le mandé un correo real a " + candidate.contactEmail() + ".";
}
```

`ChatIntentRouter.formatCustomerReferenceAnswer` (que hoy siempre devuelve el texto fijo de "no tengo contacto") pasa a delegar en `companyTools.contactProspect(candidate)` para armar esa parte de la respuesta — la desambiguación (cuál candidato, si aclarar "te muestro el de mayor probabilidad") no cambia.

### 6. Auditoría: evento Kafka nuevo

`EMPRESA_PROSPECT_CONTACTED` (`data: {leadId, recipientEmail}`, `missionId` en el envelope) — se agrega a `docs/EVENTS.md` con el mismo formato que los demás eventos del catálogo.

## Testing

- `AgentResultValidatorTest`/`MissionExecutorTest`: sin cambios (sus `new CustomerCandidate(...)` de 5 args siguen compilando con el constructor de compatibilidad).
- Tests nuevos en `AlertMailServiceTest` para `sendToExternal`: envía al destinatario dado (no a `alertEmail`), devuelve `true` en éxito, devuelve `false` (no lanza) si la cuenta del sistema no está configurada, devuelve `false` si `mailSender.send` falla — mismo patrón de mocking que los 3 tests ya existentes de `send`.
- Tests nuevos en `CompanyToolsTest` para `contactProspect`: candidato sin `contactEmail` → mensaje determinista de "no tengo contacto", nunca llama a `alertMailService`; candidato con `contactEmail` (probado tanto con un email de persona como con uno genérico tipo `ventas@empresa.com`, para confirmar que el código no distingue entre ambos) y envío exitoso → llama a `sendToExternal` con el destinatario/asunto/cuerpo reales, llama a `markContacted`, publica el evento, devuelve el mensaje de éxito; envío fallido → mensaje de fallo, **no** llama a `markContacted` ni publica el evento (un email que no salió no debe marcarse como contactado).
- Test nuevo en `ChatIntentRouterTest`: "contactalo" contra un foco `CUSTOMER` cuyo candidato SÍ tiene `contactEmail` real llama a `companyTools.contactProspect(...)` (verificado con `verify`, sin necesidad de re-probar la lógica interna de `contactProspect`, ya cubierta en `CompanyToolsTest`).
- `ProspectOutreachEmailTemplateTest` nuevo (función pura, mismo patrón que `AlertEmailTemplateTest`): el asunto/cuerpo contienen los datos reales del candidato (nombre, descripción, fuente), nunca placeholders, y el saludo es genérico (no asume un nombre de persona).
- Sin test directo para `OpportunityMemoryService.recordCandidate`/`markContacted`/las 4 lecturas actualizadas (integración Neo4j pura, mismo criterio ya establecido para el resto de los `*MemoryService`) — verificado en vivo.

## Fuera de alcance de esta ronda (documentado, no descartado)

- Registrar/editar manualmente el email de un prospecto desde el chat o el Command Center web — si el agente no lo encontró, el prospecto no es contactable en esta ronda.
- Mostrar el `contactEmail` en `getProspects()`/`formatCandidate` (el fundador no necesita verlo, solo que "contactalo" funcione cuando existe).
- Seguimiento automático (recordatorio, segundo email si no hay respuesta) — un solo envío por "contactalo".
- Canales de contacto distintos a email (teléfono, formularios web, redes sociales).
- Paso de confirmación/preview antes de enviar — decidido explícitamente en contra (ver decisión 2 más arriba).
