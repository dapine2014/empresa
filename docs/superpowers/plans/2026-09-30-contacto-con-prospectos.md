# Contacto con prospectos — plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cada prospecto nuevo con email recibe un borrador de correo redactado por Sofía y verificado por Java; el fundador lo aprueba (o edita/descarta) y Forjai lo envía con tope diario, registra la respuesta y puede convertirlo en cliente real de Finanzas.

**Architecture:** Paquete nuevo `com.aicompany.core.outreach`: validador puro (`OutreachDraftValidator`), memoria Neo4j (`OutreachMemoryService`: borradores, intentos, bajas, estado de contacto) y orquestación (`OutreachService`). El envío reusa el SMTP de alertas (`AlertMailService.sendToExternal`). `ProspectingService` pide los borradores al terminar cada corrida. Se expone por `OutreachController`, `AutonomyService` (conteo), el chat, Prospectos y Settings.

**Tech Stack:** Java 21, Spring Boot 4.1.1, JavaMail (`JavaMailSenderImpl`), Neo4j driver plano, JUnit 5 + Mockito; React + TS.

**Spec:** `docs/superpowers/specs/2026-09-30-contacto-con-prospectos-design.md`

## Global Constraints

- Contactar es 🔴: **nada se envía sin aprobación del fundador** (draft `APPROVED`).
- El estado del contacto vive en `Customer.outreachStatus` (`DRAFTED | CONTACT_IN_PROGRESS | CONTACTED | INTERESTED | NOT_INTERESTED | OPTED_OUT | CONVERTED`; ausente = sin contactar). `Customer.status` **sigue siendo `'LEAD'`** en el prospecto (Finanzas trata como cliente a todo `Customer` cuyo `status` no es `'LEAD'`: cambiarlo habilitaría ventas a un prospecto). Esto implementa los estados del spec §4-§5 sin tocar la semántica de Finanzas.
- Validación del borrador en Java (`OutreachDraftValidator`); hasta 3 intentos con `CORRECCIÓN DEL INTENTO ANTERIOR`. La línea de baja y la firma las agrega Java si faltan.
- Línea de baja fija: `Si no te interesa, responde «no» y no volveremos a escribirte.`
- Envío: SMTP de alertas (`Company.systemEmail`/`mailPassword`), `Reply-To` = `Company.alertEmail`, texto plano (sin el HTML de alertas), nunca lanza (`ExternalMailResult`).
- Tope `MAX_OUTREACH_PER_DAY` (policy, default 10, > 0) sobre envíos `SENT` del día UTC; lo aprobado que excede sale en el chequeo horario siguiente.
- Doble envío imposible: CAS sobre `outreachStatus IN ['DRAFTED'] → 'CONTACT_IN_PROGRESS'`.
- Bajas: `(:OptOut {value})` por email y por dominio; nunca se redacta ni envía a una baja; la búsqueda no vuelve a proponer esos dominios.
- Firma: `Company.outreachSignature` (default `Forjai — forjai.com`), editable por `GET|PUT /api/company/outreach/settings`.
- Eventos `EMPRESA_OUTREACH_DRAFTED|APPROVED|SENT|FAILED`, `EMPRESA_PROSPECT_RESPONDED|CONVERTED`.
- Errores: `IllegalArgumentException` → 500 con mensaje. Redactar nunca tumba la corrida de búsqueda.
- Nunca `format` y `tools` juntos. Jackson 3. `api/types.ts` a mano.

## Review Focus

- Un borrador con el precio escrito `39.00` o `US$ 39` cuando la ficha dice 39 debe pasar; `US$49` debe fallar (test en Task 2: `priceFormatsAreAcceptedButOtherAmountsAreNot`).
- Aprobar dos veces el mismo borrador (doble clic / chat + pantalla) envía un solo correo (test en Task 5: `approvingTwiceSendsOnce`).
- Un prospecto que pidió baja no recibe borrador aunque la búsqueda lo traiga otra vez con otro producto (test en Task 5: `optedOutEmailsGetNoDraft`).
- Sin cuenta SMTP configurada, aprobar devuelve el motivo y el borrador queda `APPROVED` (test en Task 5: `withoutSmtpTheDraftStaysApprovedWithTheReason`).
- "aprueba los correos" no debe caer en "aprueba la estrategia" ni en decisiones de misión (test en Task 8: `outreachCommandsDoNotCollideWithStrategies`).

---

### Task 1: Policy `MAX_OUTREACH_PER_DAY`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/model/PolicyKey.java`, `app/src/main/java/com/aicompany/core/service/CompanyPolicyService.java`
- Test: `app/src/test/java/com/aicompany/core/service/CompanyPolicyDefaultsTest.java`

**Interfaces:**
- Produces: `PolicyKey.MAX_OUTREACH_PER_DAY` (default 10).

- [ ] **Step 1: Test que falla**

```java
    // Spec contacto con prospectos §3: tope diario de envíos, 10 por defecto.
    @Test
    void outreachIsCappedAtTenPerDayByDefault() {
        var defaults = CompanyPolicyService.defaults(new AppProperties("Forjai", 50, 60));

        assertEquals(10.0, defaults.get(PolicyKey.MAX_OUTREACH_PER_DAY));
        assertThrows(IllegalArgumentException.class, () -> CompanyPolicyService.validateValue(PolicyKey.MAX_OUTREACH_PER_DAY, 0));
    }
```

- [ ] **Step 2: Ver que falla** — Run: `cd app && mvn -q test -Dtest=CompanyPolicyDefaultsTest` → compilación falla.
- [ ] **Step 3: Implementar** — `PolicyKey`: `MAX_PROSPECTS_PER_DAY,` + `MAX_OUTREACH_PER_DAY`. `defaults`: `map.put(PolicyKey.MAX_OUTREACH_PER_DAY, 10.0);`.
- [ ] **Step 4: Ver que pasa** — mismo comando → PASS.
- [ ] **Step 5: Commit** — `git commit -m "Policy MAX_OUTREACH_PER_DAY (10 por defecto)"`.

---

### Task 2: `OutreachDraftValidator`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/outreach/OutreachDraft.java`
- Create: `app/src/main/java/com/aicompany/core/outreach/OutreachDraftValidator.java`
- Test: `app/src/test/java/com/aicompany/core/outreach/OutreachDraftValidatorTest.java`

**Interfaces:**
- Produces:
  - `record OutreachDraft(String subject, String body)`
  - `OutreachDraftValidator.OPT_OUT_LINE` (String)
  - `record OutreachDraftValidator.Result(OutreachDraft draft, List<String> problems)` (`draft` = corregido con baja y firma si no hay problemas)
  - `static Result validate(OutreachDraft draft, String productName, Double priceUsd, boolean priceOnRequest, String signature, Set<String> allowedUrls, String replyTo)`

- [ ] **Step 1: Test que falla**

```java
package com.aicompany.core.outreach;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Spec contacto con prospectos §1: Java verifica el borrador de Sofía. */
class OutreachDraftValidatorTest {

    private static OutreachDraftValidator.Result check(String subject, String body, Double price, boolean onRequest) {
        return OutreachDraftValidator.validate(new OutreachDraft(subject, body), "Email Signature Generator", price,
                onRequest, "Forjai — forjai.com", Set.of("https://forjai.com"), "founder@forjai.com");
    }

    @Test
    void aGoodDraftGetsTheOptOutAndSignatureAppended() {
        var r = check("Firmas para su despacho", "Hola equipo de Acme: Email Signature Generator crea firmas por US$39.",
                39.0, false);

        assertTrue(r.problems().isEmpty(), r.problems().toString());
        assertTrue(r.draft().body().contains(OutreachDraftValidator.OPT_OUT_LINE));
        assertTrue(r.draft().body().endsWith("Forjai — forjai.com"));
    }

    @Test
    void theProductNameIsRequired() {
        var r = check("Hola", "Tenemos una herramienta de firmas por US$39.", 39.0, false);

        assertTrue(r.problems().stream().anyMatch(p -> p.contains("nombre")), r.problems().toString());
    }

    @Test
    void priceFormatsAreAcceptedButOtherAmountsAreNot() {
        assertTrue(check("A", "Email Signature Generator cuesta 39.00 USD.", 39.0, false).problems().isEmpty());
        assertTrue(check("A", "Email Signature Generator cuesta US$ 39.", 39.0, false).problems().isEmpty());
        var wrong = check("A", "Email Signature Generator cuesta US$49.", 39.0, false);
        assertTrue(wrong.problems().stream().anyMatch(p -> p.contains("precio")), wrong.problems().toString());
        var missing = check("A", "Email Signature Generator es genial.", 39.0, false);
        assertTrue(missing.problems().stream().anyMatch(p -> p.contains("precio")), missing.problems().toString());
    }

    @Test
    void aPriceOnRequestProductMustNotQuoteAmounts() {
        assertTrue(check("A", "Email Signature Generator: le cotizamos a medida.", null, true).problems().isEmpty());
        assertFalse(check("A", "Email Signature Generator por $20.", null, true).problems().isEmpty());
    }

    @Test
    void foreignUrlsAndEmailsAreRejected() {
        var url = check("A", "Email Signature Generator por US$39. Mira https://otro.com/x", 39.0, false);
        var email = check("A", "Email Signature Generator por US$39. Escribe a ventas@otro.com", 39.0, false);

        assertTrue(url.problems().stream().anyMatch(p -> p.contains("URL")), url.problems().toString());
        assertTrue(email.problems().stream().anyMatch(p -> p.contains("email")), email.problems().toString());
        assertTrue(check("A", "Email Signature Generator por US$39. Más en https://forjai.com y founder@forjai.com",
                39.0, false).problems().isEmpty());
    }

    @Test
    void lengthLimits() {
        assertFalse(check("", "Email Signature Generator por US$39.", 39.0, false).problems().isEmpty());
        assertFalse(check("A", "Email Signature Generator por US$39. " + "x".repeat(1200), 39.0, false).problems().isEmpty());
    }
}
```

- [ ] **Step 2: Ver que falla** — `cd app && mvn -q test -Dtest=OutreachDraftValidatorTest` → compilación falla.
- [ ] **Step 3: Implementar**

`OutreachDraft.java`:

```java
package com.aicompany.core.outreach;

/** Asunto y cuerpo de un correo a un prospecto. */
public record OutreachDraft(String subject, String body) {
}
```

`OutreachDraftValidator.java`:

```java
package com.aicompany.core.outreach;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Spec contacto con prospectos §1 (función pura): el borrador de Sofía nombra el producto exacto, cita solo el precio de
 * la ficha, no trae URLs ni emails ajenos y respeta los largos. La línea de baja y la firma las agrega Java.
 */
public final class OutreachDraftValidator {

    public static final String OPT_OUT_LINE = "Si no te interesa, responde «no» y no volveremos a escribirte.";
    static final int MAX_SUBJECT = 120;
    static final int MAX_BODY = 1200;

    public record Result(OutreachDraft draft, List<String> problems) {
    }

    private static final Pattern AMOUNT = Pattern.compile(
            "(?:US\\$|\\$)\\s?(\\d+(?:[.,]\\d{1,2})?)|(\\d+(?:[.,]\\d{1,2})?)\\s?(?:USD|US\\$|dólares|dolares)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("https?://[^\\s)>\\]]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");

    private OutreachDraftValidator() {
    }

    public static Result validate(OutreachDraft draft, String productName, Double priceUsd, boolean priceOnRequest,
                                  String signature, Set<String> allowedUrls, String replyTo) {
        var problems = new ArrayList<String>();
        var subject = draft == null || draft.subject() == null ? "" : draft.subject().strip();
        var body = draft == null || draft.body() == null ? "" : draft.body().strip();
        if (subject.isEmpty() || subject.length() > MAX_SUBJECT) {
            problems.add("El asunto debe tener entre 1 y " + MAX_SUBJECT + " caracteres.");
        }
        if (body.isEmpty() || body.length() > MAX_BODY) {
            problems.add("El cuerpo debe tener entre 1 y " + MAX_BODY + " caracteres (sin contar baja y firma).");
        }
        if (!body.toLowerCase(Locale.ROOT).contains(productName.toLowerCase(Locale.ROOT))) {
            problems.add("Debe mencionar el nombre exacto del producto: \"" + productName + "\".");
        }
        var amounts = AMOUNT.matcher(subject + "\n" + body).results()
                .map(m -> new BigDecimal((m.group(1) != null ? m.group(1) : m.group(2)).replace(',', '.')))
                .toList();
        if (priceOnRequest || priceUsd == null) {
            if (!amounts.isEmpty()) {
                problems.add("El producto es a cotizar: no menciones montos.");
            }
        } else {
            var price = BigDecimal.valueOf(priceUsd);
            if (amounts.isEmpty()) {
                problems.add("Debe mencionar el precio exacto: US$" + price.stripTrailingZeros().toPlainString() + ".");
            } else if (amounts.stream().anyMatch(a -> a.compareTo(price) != 0)) {
                problems.add("El único monto permitido es el precio de la ficha: US$"
                        + price.stripTrailingZeros().toPlainString() + ".");
            }
        }
        URL.matcher(body).results().map(m -> m.group().replaceAll("[.,;]+$", ""))
                .filter(u -> allowedUrls.stream().noneMatch(u::startsWith))
                .findFirst().ifPresent(u -> problems.add("No incluyas URLs ajenas (" + u + ")."));
        EMAIL.matcher(body).results().map(m -> m.group())
                .filter(e -> !e.equalsIgnoreCase(replyTo))
                .findFirst().ifPresent(e -> problems.add("No incluyas otro email que no sea el de respuesta (" + e + ")."));
        if (!problems.isEmpty()) {
            return new Result(null, problems);
        }
        var full = body;
        if (!full.contains(OPT_OUT_LINE)) {
            full += "\n\n" + OPT_OUT_LINE;
        }
        if (!full.strip().endsWith(signature.strip())) {
            full += "\n\n" + signature.strip();
        }
        return new Result(new OutreachDraft(subject, full), List.of());
    }
}
```

- [ ] **Step 4: Ver que pasa** — mismo comando → PASS (6 tests).
- [ ] **Step 5: Commit** — `git commit -m "Contacto: OutreachDraftValidator (producto, precio, URLs y emails ajenos, largos)"`.

---

### Task 3: `AlertMailService.sendToExternal`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/AlertMailService.java`
- Test: `app/src/test/java/com/aicompany/core/service/AlertMailServiceExternalTest.java`

**Interfaces:**
- Produces: `record AlertMailService.ExternalMailResult(boolean accepted, String errorMessage)`; `synchronized ExternalMailResult sendToExternal(String to, String subject, String body)` (From = `systemEmail`, Reply-To = `alertEmail`, texto plano).

- [ ] **Step 1: Test que falla**

```java
package com.aicompany.core.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Spec contacto con prospectos §3: envío real a un prospecto, sin ocultar el resultado. */
class AlertMailServiceExternalTest {

    private final JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
    private final CompanyMemoryService memory = mock(CompanyMemoryService.class);
    private final AlertMailService service = new AlertMailService(sender, memory);

    {
        when(sender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        when(memory.alertEmail()).thenReturn("founder@forjai.com");
    }

    @Test
    void withoutSmtpNothingIsSentAndTheReasonIsReturned() {
        when(memory.systemEmail()).thenReturn("");
        when(memory.mailPassword()).thenReturn("");

        var result = service.sendToExternal("hola@acme.com", "Asunto", "Cuerpo");

        assertFalse(result.accepted());
        assertTrue(result.errorMessage().contains("Settings"), result.errorMessage());
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void itSendsPlainTextToTheProspectWithReplyToTheFounder() throws Exception {
        when(memory.systemEmail()).thenReturn("forjai@gmail.com");
        when(memory.mailPassword()).thenReturn("app-pass");
        var captor = ArgumentCaptor.forClass(MimeMessage.class);

        var result = service.sendToExternal("hola@acme.com", "Asunto", "Cuerpo del correo");

        assertTrue(result.accepted());
        verify(sender).send(captor.capture());
        var message = captor.getValue();
        assertEquals("hola@acme.com", message.getAllRecipients()[0].toString());
        assertEquals("founder@forjai.com", message.getReplyTo()[0].toString());
        assertEquals("Asunto", message.getSubject());
    }

    @Test
    void anSmtpFailureIsReportedNotThrown() {
        when(memory.systemEmail()).thenReturn("forjai@gmail.com");
        when(memory.mailPassword()).thenReturn("app-pass");
        doThrow(new MailSendException("535 Authentication failed")).when(sender).send(any(MimeMessage.class));

        var result = service.sendToExternal("hola@acme.com", "Asunto", "Cuerpo");

        assertFalse(result.accepted());
        assertTrue(result.errorMessage().contains("535"), result.errorMessage());
    }
}
```

- [ ] **Step 2: Ver que falla** — `cd app && mvn -q test -Dtest=AlertMailServiceExternalTest` → compilación falla.
- [ ] **Step 3: Implementar** (después de `send`):

```java
    /** Spec contacto con prospectos §3: resultado real de un envío externo (SMTP genérico: sin id de proveedor). */
    public record ExternalMailResult(boolean accepted, String errorMessage) {
    }

    /**
     * Envío a un prospecto (🔴, solo tras aprobación del fundador): mismo SMTP que las alertas, Reply-To al fundador y
     * texto plano (no debe verse como una alerta interna). Nunca lanza: devuelve la verdad para que el fundador la vea.
     */
    public synchronized ExternalMailResult sendToExternal(String to, String subject, String body) {
        try {
            var systemEmail = memory.systemEmail();
            var password = memory.mailPassword();
            if (systemEmail == null || systemEmail.isBlank() || password == null || password.isBlank()) {
                return new ExternalMailResult(false,
                        "El correo propio del sistema no está configurado (Settings del Command Center).");
            }
            mailSender.setUsername(systemEmail);
            mailSender.setPassword(password);
            var mimeMessage = mailSender.createMimeMessage();
            var helper = new MimeMessageHelper(mimeMessage, false, "UTF-8");
            helper.setFrom(systemEmail);
            helper.setTo(to);
            helper.setReplyTo(memory.alertEmail());
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(mimeMessage);
            return new ExternalMailResult(true, null);
        } catch (Exception ex) {
            log.warn("No se pudo enviar el correo a {}: {}", to, ex.getMessage());
            return new ExternalMailResult(false, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }
```

- [ ] **Step 4: Ver que pasa** — mismo comando → PASS (3 tests).
- [ ] **Step 5: Commit** — `git commit -m "AlertMailService.sendToExternal: envío real a un prospecto con resultado verdadero"`.

---

### Task 4: `CeoService.draftOutreach`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/CeoService.java` (junto a `proposeProspectingStrategy`, schema junto a `STRATEGY_SCHEMA`)
- Test: `app/src/test/java/com/aicompany/core/service/CeoServiceProspectingTest.java`

**Interfaces:**
- Consumes: `OutreachDraft` (Task 2).
- Produces: `CeoService.draftOutreach(String productSheet, String prospectText, String correction, String model)` → `OutreachDraft` (`format` = `{subject, body}`, sin `tools`, agente `sales`).

- [ ] **Step 1: Test que falla** (agregar a `CeoServiceProspectingTest`)

```java
    @Test
    void sofiaDraftsAnOutreachEmailWithoutTools() {
        var prompt = org.mockito.ArgumentCaptor.forClass(List.class);
        when(remote.complete(anyString(), prompt.capture(), isNull(), eq(true), anyInt()))
                .thenReturn(new OpenAiCompatibleClient.RemoteReply("{\"subject\":\"Firmas\",\"body\":\"Hola\"}", List.of()));

        var draft = ceoService.draftOutreach("Producto: Email Signature Generator", "Acme Studio: publican mucho",
                "Debe mencionar el precio", "nvidia-discovery:m");

        assertEquals("Firmas", draft.subject());
        assertTrue(prompt.getValue().toString().contains("CORRECCIÓN DEL INTENTO ANTERIOR"));
        verify(remote, never()).complete(anyString(), anyList(), isNotNull(), anyBoolean(), anyInt());
    }
```

- [ ] **Step 2: Ver que falla** — `cd app && mvn -q test -Dtest=CeoServiceProspectingTest` → compilación falla.
- [ ] **Step 3: Implementar**

```java
    private static final Map<String, Object> OUTREACH_SCHEMA = Map.of("type", "object",
            "properties", Map.of("subject", Map.of("type", "string"), "body", Map.of("type", "string")),
            "required", List.of("subject", "body"));

    /**
     * Spec contacto con prospectos §1: Sofía redacta el primer correo a un prospecto. Java lo verifica después
     * (OutreachDraftValidator) y el fundador lo aprueba antes de enviarlo (🔴).
     */
    public com.aicompany.core.outreach.OutreachDraft draftOutreach(String productSheet, String prospectText,
                                                                   String correction, String model) {
        var prompt = """
                Escribe el primer correo de Forjai a este prospecto para ofrecerle el producto. Breve (máximo 150
                palabras), cordial, en el idioma del prospecto si se deduce de sus datos (si no, en inglés), sin
                exagerar ni prometer nada que no esté en la descripción del producto. Menciona el nombre exacto del
                producto y, si la ficha tiene precio, ese precio exacto en US$ (ningún otro monto). No incluyas links ni
                emails, ni la firma, ni una línea para darse de baja: Forjai los agrega.
                PRODUCTO (ficha):
                %s
                PROSPECTO:
                %s
                FORMATO: {"subject": "<asunto>", "body": "<cuerpo>"}
                """.formatted(productSheet, prospectText)
                + (correction == null || correction.isBlank() ? ""
                        : "\nCORRECCIÓN DEL INTENTO ANTERIOR: " + correction);
        return callStructured("OUTREACH_DRAFT", "sales", prompt, null, model, OUTREACH_SCHEMA,
                com.aicompany.core.outreach.OutreachDraft.class);
    }
```

- [ ] **Step 4: Ver que pasa** — mismo comando → PASS.
- [ ] **Step 5: Commit** — `git commit -m "CeoService.draftOutreach: Sofía redacta el correo al prospecto"`.

---

### Task 5: `OutreachMemoryService` y `OutreachService`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/outreach/ContactDraft.java`
- Create: `app/src/main/java/com/aicompany/core/outreach/OutreachMemoryService.java`
- Create: `app/src/main/java/com/aicompany/core/outreach/OutreachService.java`
- Test: `app/src/test/java/com/aicompany/core/outreach/OutreachServiceTest.java`

**Interfaces:**
- Consumes: Tasks 1-4; `ProspectingMemoryService.prospects()` (`Prospect` con `id, productId, productName, name, url, contactEmail, …, fitReason`); `ProductService.view(String)` → `Optional<ProductView>`; `FinanceService.registerCustomer(FinanceCustomerCommand)` → `FinanceCustomer(id,…)`; `CompanyMemoryService.agentModel/alertEmail`; `CompanyPolicyService.activeValue`; `AlertMailService.sendToExternal/send`; `CompanyEventPublisher.publish`.
- Produces:
  - `record ContactDraft(String id, String prospectId, String prospectName, String productId, String to, String subject, String body, String status, String error, Instant createdAt, Instant sentAt)` (status `PENDING_APPROVAL | APPROVED | SENT | DISCARDED`)
  - `OutreachMemoryService`: `saveDraft(ContactDraft)`, `Optional<ContactDraft> draft(String id)`, `List<ContactDraft> drafts(String status)`, `List<ContactDraft> allDrafts(int limit)`, `void setDraftStatus(String id, String status, String error, Instant sentAt)`, `void editDraft(String id, String subject, String body)`, `Optional<String> outreachStatus(String prospectId)`, `void setOutreachStatus(String prospectId, String status)`, `boolean claimForContact(String prospectId)`, `String recordAttempt(String prospectId, String draftId, String to, String subject)`, `void finishAttempt(String attemptId, boolean sent, String error)`, `int sentOn(LocalDate)`, `boolean optedOut(String email)`, `void optOut(String email)`, `Set<String> optedOutDomains()`, `String signature()`, `void setSignature(String)`, `void linkConverted(String prospectId, String customerId)`
  - `OutreachService`: `int draftFor(String runId, String productId)`, `ContactDraft approve(String draftId)`, `List<ContactDraft> approveAll()`, `ContactDraft discard(String draftId)`, `ContactDraft edit(String draftId, String subject, String body)`, `@Scheduled sendDue()`, `void respond(String prospectId, String response)`, `String convert(String prospectId)`, `String signature()`, `void setSignature(String)`

- [ ] **Step 1: Test que falla**

```java
package com.aicompany.core.outreach;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.CatalogProduct;
import com.aicompany.core.model.CatalogStatus;
import com.aicompany.core.model.FinanceCustomer;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.ProductView;
import com.aicompany.core.prospecting.Prospect;
import com.aicompany.core.prospecting.ProspectingMemoryService;
import com.aicompany.core.service.AlertMailService;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.CompanyPolicyService;
import com.aicompany.core.service.FinanceService;
import com.aicompany.core.service.ProductService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec contacto con prospectos: borradores, aprobación (🔴), envío con tope y conversión. */
class OutreachServiceTest {

    private final OutreachMemoryService memory = mock(OutreachMemoryService.class);
    private final ProspectingMemoryService prospecting = mock(ProspectingMemoryService.class);
    private final ProductService products = mock(ProductService.class);
    private final CeoService ceo = mock(CeoService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final CompanyPolicyService policies = mock(CompanyPolicyService.class);
    private final AlertMailService mail = mock(AlertMailService.class);
    private final FinanceService finance = mock(FinanceService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final OutreachService service = new OutreachService(memory, prospecting, products, ceo, companyMemory,
            policies, mail, finance, events, "qwen3:8b");

    {
        when(policies.activeValue(PolicyKey.MAX_OUTREACH_PER_DAY)).thenReturn(10.0);
        when(companyMemory.agentModel(anyString(), anyString())).thenReturn("nvidia-discovery:m");
        when(companyMemory.alertEmail()).thenReturn("founder@forjai.com");
        when(memory.signature()).thenReturn("Forjai — forjai.com");
        when(memory.optedOutDomains()).thenReturn(Set.of());
        when(memory.outreachStatus(anyString())).thenReturn(Optional.empty());
        when(products.view("P1")).thenReturn(Optional.of(new ProductView(product(), List.of(), List.of())));
    }

    private static CatalogProduct product() {
        return new CatalogProduct("P1", "Email Signature Generator", "Firmas HTML", "SOFTWARE", "Abogados", 39, false, 1,
                null, List.of("WORLDWIDE"), List.of("en"), CatalogStatus.READY_TO_SELL, null, "product", Instant.now(),
                Instant.now(), List.of(), List.of());
    }

    private static Prospect prospect(String id, String email) {
        return new Prospect(id, "P1", "Email Signature Generator", "Acme Legal", "https://acme.com", email,
                email == null ? null : "https://acme.com/c", email == null ? "https://acme.com/form" : null,
                "Despacho de abogados", "BASE-DIRECTORIES", Instant.now());
    }

    private static ContactDraft draft(String status) {
        return new ContactDraft("D1", "C1", "Acme Legal", "P1", "hola@acme.com", "Asunto",
                "Email Signature Generator por US$39.", status, null, Instant.now(), null);
    }

    @Test
    void draftsAreOnlyForProspectsWithEmailAndAreValidatedByJava() {
        when(prospecting.prospectsOfRun("RUN-1")).thenReturn(List.of(prospect("C1", "hola@acme.com"), prospect("C2", null)));
        when(ceo.draftOutreach(anyString(), anyString(), any(), anyString()))
                .thenReturn(new OutreachDraft("Firmas", "Hola Acme: Email Signature Generator por US$39."));

        var count = service.draftFor("RUN-1", "P1");

        assertEquals(1, count);
        verify(memory).saveDraft(argThat(d -> "C1".equals(d.prospectId()) && "PENDING_APPROVAL".equals(d.status())
                && d.body().contains(OutreachDraftValidator.OPT_OUT_LINE)));
        verify(memory).setOutreachStatus("C1", "DRAFTED");
        verify(events).publish(eq("EMPRESA_OUTREACH_DRAFTED"), isNull(), isNull(), eq("sales"), anyMap());
    }

    @Test
    void anInvalidDraftIsAskedAgainWithTheCorrectionUpToThreeTimes() {
        when(prospecting.prospectsOfRun("RUN-1")).thenReturn(List.of(prospect("C1", "hola@acme.com")));
        when(ceo.draftOutreach(anyString(), anyString(), any(), anyString()))
                .thenReturn(new OutreachDraft("Firmas", "Hola, algo genial por US$49."));

        assertEquals(0, service.draftFor("RUN-1", "P1"));
        verify(ceo, times(3)).draftOutreach(anyString(), anyString(), any(), anyString());
        verify(ceo).draftOutreach(anyString(), anyString(), contains("nombre exacto"), anyString());
        verify(memory, never()).saveDraft(any());
    }

    @Test
    void optedOutEmailsGetNoDraft() {
        when(prospecting.prospectsOfRun("RUN-1")).thenReturn(List.of(prospect("C1", "hola@acme.com")));
        when(memory.optedOut("hola@acme.com")).thenReturn(true);

        assertEquals(0, service.draftFor("RUN-1", "P1"));
        verifyNoInteractions(ceo);
    }

    @Test
    void approvingSendsAndMarksContacted() {
        when(memory.draft("D1")).thenReturn(Optional.of(draft("PENDING_APPROVAL")));
        when(memory.claimForContact("C1")).thenReturn(true);
        when(memory.recordAttempt(anyString(), anyString(), anyString(), anyString())).thenReturn("A1");
        when(mail.sendToExternal("hola@acme.com", "Asunto", "Email Signature Generator por US$39."))
                .thenReturn(new AlertMailService.ExternalMailResult(true, null));

        service.approve("D1");

        verify(memory).finishAttempt("A1", true, null);
        verify(memory).setDraftStatus(eq("D1"), eq("SENT"), isNull(), any());
        verify(memory).setOutreachStatus("C1", "CONTACTED");
        verify(events).publish(eq("EMPRESA_OUTREACH_SENT"), isNull(), isNull(), eq("human"), anyMap());
    }

    @Test
    void approvingTwiceSendsOnce() {
        when(memory.draft("D1")).thenReturn(Optional.of(draft("PENDING_APPROVAL")));
        when(memory.claimForContact("C1")).thenReturn(true, false);
        when(memory.recordAttempt(anyString(), anyString(), anyString(), anyString())).thenReturn("A1");
        when(mail.sendToExternal(anyString(), anyString(), anyString())).thenReturn(new AlertMailService.ExternalMailResult(true, null));

        service.approve("D1");
        service.approve("D1");

        verify(mail, times(1)).sendToExternal(anyString(), anyString(), anyString());
    }

    @Test
    void theDailyCapLeavesTheDraftApprovedForLater() {
        when(policies.activeValue(PolicyKey.MAX_OUTREACH_PER_DAY)).thenReturn(1.0);
        when(memory.sentOn(any())).thenReturn(1);
        when(memory.draft("D1")).thenReturn(Optional.of(draft("PENDING_APPROVAL")));

        var result = service.approve("D1");

        assertEquals("APPROVED", result.status());
        verify(memory).setDraftStatus(eq("D1"), eq("APPROVED"), contains("tope diario"), isNull());
        verifyNoInteractions(mail);
    }

    @Test
    void withoutSmtpTheDraftStaysApprovedWithTheReason() {
        when(memory.draft("D1")).thenReturn(Optional.of(draft("PENDING_APPROVAL")));
        when(memory.claimForContact("C1")).thenReturn(true);
        when(memory.recordAttempt(anyString(), anyString(), anyString(), anyString())).thenReturn("A1");
        when(mail.sendToExternal(anyString(), anyString(), anyString()))
                .thenReturn(new AlertMailService.ExternalMailResult(false, "El correo propio del sistema no está configurado"));

        var result = service.approve("D1");

        assertEquals("APPROVED", result.status());
        assertTrue(result.error().contains("no está configurado"));
        verify(memory).finishAttempt("A1", false, "El correo propio del sistema no está configurado");
        verify(memory).setOutreachStatus("C1", "DRAFTED");
        verify(events).publish(eq("EMPRESA_OUTREACH_FAILED"), isNull(), isNull(), eq("human"), anyMap());
    }

    @Test
    void anOptOutBeforeSendingBlocksTheSend() {
        when(memory.draft("D1")).thenReturn(Optional.of(draft("APPROVED")));
        when(memory.optedOut("hola@acme.com")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.approve("D1"));
        verifyNoInteractions(mail);
    }

    @Test
    void discardingReturnsTheProspectToLead() {
        when(memory.draft("D1")).thenReturn(Optional.of(draft("PENDING_APPROVAL")));

        service.discard("D1");

        verify(memory).setDraftStatus(eq("D1"), eq("DISCARDED"), isNull(), isNull());
        verify(memory).setOutreachStatus("C1", null);
    }

    @Test
    void anEditIsValidatedAgain() {
        when(memory.draft("D1")).thenReturn(Optional.of(draft("PENDING_APPROVAL")));

        assertThrows(IllegalArgumentException.class, () -> service.edit("D1", "Asunto", "Sin el producto"));
        service.edit("D1", "Asunto", "Email Signature Generator, US$39.");

        verify(memory).editDraft(eq("D1"), eq("Asunto"), contains(OutreachDraftValidator.OPT_OUT_LINE));
    }

    @Test
    void anOptOutResponseBlocksEmailAndDomain() {
        when(memory.outreachStatus("C1")).thenReturn(Optional.of("CONTACTED"));
        when(prospecting.prospect("C1")).thenReturn(Optional.of(prospect("C1", "hola@acme.com")));

        service.respond("C1", "OPTED_OUT");

        verify(memory).setOutreachStatus("C1", "OPTED_OUT");
        verify(memory).optOut("hola@acme.com");
        verify(memory).optOut("acme.com");
        verify(events).publish(eq("EMPRESA_PROSPECT_RESPONDED"), isNull(), isNull(), eq("human"), anyMap());
    }

    @Test
    void respondingForAProspectNotContactedIsRejected() {
        when(memory.outreachStatus("C1")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.respond("C1", "INTERESTED"));
    }

    @Test
    void convertingCreatesAFinanceCustomerOnce() {
        when(prospecting.prospect("C1")).thenReturn(Optional.of(prospect("C1", "hola@acme.com")));
        when(memory.outreachStatus("C1")).thenReturn(Optional.of("INTERESTED"), Optional.of("CONVERTED"));
        when(finance.registerCustomer(any())).thenReturn(new FinanceCustomer("CUSTOMER-1", "Acme Legal", "hola@acme.com", null, Instant.now()));

        assertEquals("CUSTOMER-1", service.convert("C1"));
        assertThrows(IllegalArgumentException.class, () -> service.convert("C1"));

        verify(finance, times(1)).registerCustomer(argThat(c -> "Acme Legal".equals(c.name())
                && "hola@acme.com".equals(c.contact()) && c.evidenceDescription().contains("Email Signature Generator")));
        verify(memory).linkConverted("C1", "CUSTOMER-1");
        verify(memory).setOutreachStatus("C1", "CONVERTED");
    }

    @Test
    void sendDueSendsApprovedDraftsWithinTheCap() {
        when(memory.drafts("APPROVED")).thenReturn(List.of(draft("APPROVED")));
        when(memory.draft("D1")).thenReturn(Optional.of(draft("APPROVED")));
        when(memory.claimForContact("C1")).thenReturn(true);
        when(memory.recordAttempt(anyString(), anyString(), anyString(), anyString())).thenReturn("A1");
        when(mail.sendToExternal(anyString(), anyString(), anyString())).thenReturn(new AlertMailService.ExternalMailResult(true, null));

        service.sendDue();

        verify(mail).sendToExternal("hola@acme.com", "Asunto", "Email Signature Generator por US$39.");
    }
}
```

Nota: `ProspectingMemoryService` gana `prospectsOfRun(String runId)` y `prospect(String id)` en esta tarea (ver Step 3).

- [ ] **Step 2: Ver que falla** — `cd app && mvn -q test -Dtest=OutreachServiceTest` → compilación falla.
- [ ] **Step 3: Implementar**

`ProspectingMemoryService` (agregar):

```java
    public List<Prospect> prospectsOfRun(String runId) {
        return prospectsWhere("c.prospectingRunId = $runId", Map.of("runId", runId));
    }

    public Optional<Prospect> prospect(String id) {
        return prospectsWhere("c.id = $id", Map.of("id", id)).stream().findFirst();
    }
```

y refactorizar `prospects()` a `return prospectsWhere("true", Map.of());` con:

```java
    private List<Prospect> prospectsWhere(String condition, Map<String, Object> params) {
        return read("MATCH (p:Product)-[:HAS_PROSPECT]->(c:Customer) WHERE " + condition
                + " RETURN p.id AS pid, p.name AS pname, c ORDER BY c.foundAt DESC", params, rec -> {
                    var c = rec.get("c");
                    return new Prospect(c.get("id").asString(), rec.get("pid").asString(), rec.get("pname").asString(null),
                            c.get("name").asString(), c.get("url").asString(null), c.get("contactEmail").asString(null),
                            c.get("contactEmailSource").asString(null), c.get("contactFormUrl").asString(null),
                            c.get("fitReason").asString(null), c.get("strategy").asString(null),
                            Instant.parse(c.get("foundAt").asString()));
                });
    }
```

`ContactDraft.java`:

```java
package com.aicompany.core.outreach;

import java.time.Instant;

/** Correo a un prospecto: PENDING_APPROVAL → APPROVED → SENT, o DISCARDED. */
public record ContactDraft(String id, String prospectId, String prospectName, String productId, String to, String subject,
                           String body, String status, String error, Instant createdAt, Instant sentAt) {
}
```

`OutreachMemoryService.java`:

```java
package com.aicompany.core.outreach;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Spec contacto con prospectos: (:Customer)-[:HAS_DRAFT]->(:ContactDraft), (:Customer)-[:HAS_CONTACT_ATTEMPT]->
 * (:ContactAttempt), (:OptOut {value}) y Customer.outreachStatus. Customer.status sigue 'LEAD' (Finanzas).
 */
@Service
public class OutreachMemoryService {

    static final String DEFAULT_SIGNATURE = "Forjai — forjai.com";

    private final Driver driver;

    public OutreachMemoryService(Driver driver) {
        this.driver = driver;
    }

    public void saveDraft(ContactDraft d) {
        var props = new HashMap<String, Object>();
        props.put("prospectId", d.prospectId());
        props.put("prospectName", d.prospectName());
        props.put("productId", d.productId());
        props.put("to", d.to());
        props.put("subject", d.subject());
        props.put("body", d.body());
        props.put("status", d.status());
        props.put("error", d.error());
        props.put("createdAt", d.createdAt().toString());
        write("MATCH (c:Customer {id:$prospectId}) MERGE (d:ContactDraft {id:$id}) SET d += $props "
                + "MERGE (c)-[:HAS_DRAFT]->(d)", Map.of("prospectId", d.prospectId(), "id", d.id(), "props", props));
    }

    public Optional<ContactDraft> draft(String id) {
        return read("MATCH (d:ContactDraft {id:$id}) RETURN d", Map.of("id", id), OutreachMemoryService::draft)
                .stream().findFirst();
    }

    public List<ContactDraft> drafts(String status) {
        return read("MATCH (d:ContactDraft {status:$status}) RETURN d ORDER BY d.createdAt", Map.of("status", status),
                OutreachMemoryService::draft);
    }

    public List<ContactDraft> allDrafts(int limit) {
        return read("MATCH (d:ContactDraft) RETURN d ORDER BY d.createdAt DESC LIMIT $limit", Map.of("limit", limit),
                OutreachMemoryService::draft);
    }

    public void setDraftStatus(String id, String status, String error, Instant sentAt) {
        var params = new HashMap<String, Object>();
        params.put("id", id);
        params.put("status", status);
        params.put("error", error);
        params.put("sentAt", sentAt == null ? null : sentAt.toString());
        write("MATCH (d:ContactDraft {id:$id}) SET d.status=$status, d.error=$error, "
                + "d.sentAt=coalesce($sentAt, d.sentAt)", params);
    }

    public void editDraft(String id, String subject, String body) {
        write("MATCH (d:ContactDraft {id:$id}) SET d.subject=$subject, d.body=$body, d.editedByFounder=true",
                Map.of("id", id, "subject", subject, "body", body));
    }

    public Optional<String> outreachStatus(String prospectId) {
        return read("MATCH (c:Customer {id:$id}) RETURN c.outreachStatus AS s", Map.of("id", prospectId),
                rec -> rec.get("s").asString(null)).stream().filter(s -> s != null).findFirst();
    }

    public void setOutreachStatus(String prospectId, String status) {
        var params = new HashMap<String, Object>();
        params.put("id", prospectId);
        params.put("status", status);
        write("MATCH (c:Customer {id:$id}) SET c.outreachStatus=$status", params);
    }

    /** CAS: solo un envío por prospecto (DRAFTED → CONTACT_IN_PROGRESS). */
    public boolean claimForContact(String prospectId) {
        try (var session = driver.session()) {
            return session.executeWrite(tx -> !tx.run("MATCH (c:Customer {id:$id}) WHERE c.outreachStatus = 'DRAFTED' "
                    + "SET c.outreachStatus = 'CONTACT_IN_PROGRESS' RETURN c.id", Map.of("id", prospectId)).list().isEmpty());
        }
    }

    public String recordAttempt(String prospectId, String draftId, String to, String subject) {
        var id = "ATTEMPT-" + UUID.randomUUID();
        write("MATCH (c:Customer {id:$prospectId}) CREATE (a:ContactAttempt {id:$id, draftId:$draftId, channel:'EMAIL', "
                        + "destination:$to, subject:$subject, status:'PENDING', requestedBy:'human', initiatedAt:$now}) "
                        + "MERGE (c)-[:HAS_CONTACT_ATTEMPT]->(a)",
                Map.of("prospectId", prospectId, "id", id, "draftId", draftId, "to", to, "subject", subject,
                        "now", Instant.now().toString()));
        return id;
    }

    public void finishAttempt(String attemptId, boolean sent, String error) {
        var params = new HashMap<String, Object>();
        params.put("id", attemptId);
        params.put("status", sent ? "SENT" : "FAILED");
        params.put("error", error);
        params.put("now", Instant.now().toString());
        write("MATCH (a:ContactAttempt {id:$id}) SET a.status=$status, a.errorMessage=$error, "
                + "a.sentAt = CASE WHEN $status = 'SENT' THEN $now ELSE null END", params);
    }

    public int sentOn(LocalDate day) {
        return read("MATCH (a:ContactAttempt {status:'SENT'}) WHERE a.sentAt STARTS WITH $day RETURN count(a) AS n",
                Map.of("day", day.toString()), rec -> rec.get("n").asInt()).get(0);
    }

    public boolean optedOut(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        var value = email.strip().toLowerCase(Locale.ROOT);
        var domain = value.substring(value.indexOf('@') + 1);
        return !read("MATCH (o:OptOut) WHERE o.value IN [$email, $domain] RETURN o LIMIT 1",
                Map.of("email", value, "domain", domain), rec -> rec).isEmpty();
    }

    public void optOut(String value) {
        write("MERGE (o:OptOut {value:$value}) ON CREATE SET o.createdAt=$now",
                Map.of("value", value.strip().toLowerCase(Locale.ROOT), "now", Instant.now().toString()));
    }

    public Set<String> optedOutDomains() {
        return new HashSet<>(read("MATCH (o:OptOut) WHERE NOT o.value CONTAINS '@' RETURN o.value AS v", Map.of(),
                rec -> rec.get("v").asString()));
    }

    public String signature() {
        return read("MATCH (c:Company {id:'AI-COMPANY'}) RETURN coalesce(c.outreachSignature, $d) AS s",
                Map.of("d", DEFAULT_SIGNATURE), rec -> rec.get("s").asString()).stream().findFirst().orElse(DEFAULT_SIGNATURE);
    }

    public void setSignature(String signature) {
        write("MATCH (c:Company {id:'AI-COMPANY'}) SET c.outreachSignature=$s", Map.of("s", signature));
    }

    public void linkConverted(String prospectId, String customerId) {
        write("MATCH (p:Customer {id:$pid}), (c:Customer {id:$cid}) MERGE (p)-[:CONVERTED_TO]->(c)",
                Map.of("pid", prospectId, "cid", customerId));
    }

    private static ContactDraft draft(Record rec) {
        var d = rec.get("d");
        return new ContactDraft(d.get("id").asString(), d.get("prospectId").asString(), d.get("prospectName").asString(null),
                d.get("productId").asString(null), d.get("to").asString(), d.get("subject").asString(),
                d.get("body").asString(), d.get("status").asString(), d.get("error").asString(null),
                Instant.parse(d.get("createdAt").asString()),
                d.get("sentAt").isNull() ? null : Instant.parse(d.get("sentAt").asString()));
    }

    private void write(String cypher, Map<String, Object> params) {
        try (var session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, params);
                return null;
            });
        }
    }

    private <T> List<T> read(String cypher, Map<String, Object> params, Function<Record, T> map) {
        try (var session = driver.session()) {
            return session.run(cypher, params).list(map::apply);
        }
    }
}
```

`OutreachService.java`:

```java
package com.aicompany.core.outreach;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.CatalogProduct;
import com.aicompany.core.model.FinanceCustomerCommand;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.prospecting.Prospect;
import com.aicompany.core.prospecting.ProspectValidator;
import com.aicompany.core.prospecting.ProspectingMemoryService;
import com.aicompany.core.service.AlertMailService;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.CompanyPolicyService;
import com.aicompany.core.service.FinanceService;
import com.aicompany.core.service.ProductService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Spec contacto con prospectos: Sofía redacta (🟢), el fundador aprueba (🔴), Java envía con tope diario y registra.
 * Customer.status nunca cambia (sigue 'LEAD'): el estado del contacto vive en Customer.outreachStatus.
 */
@Service
public class OutreachService {

    private static final Logger log = LoggerFactory.getLogger(OutreachService.class);
    static final int DRAFT_ATTEMPTS = 3;
    static final Set<String> RESPONSES = Set.of("INTERESTED", "NOT_INTERESTED", "OPTED_OUT");

    private final OutreachMemoryService memory;
    private final ProspectingMemoryService prospecting;
    private final ProductService products;
    private final CeoService ceo;
    private final CompanyMemoryService companyMemory;
    private final CompanyPolicyService policies;
    private final AlertMailService mail;
    private final FinanceService finance;
    private final CompanyEventPublisher events;
    private final String defaultModel;

    public OutreachService(OutreachMemoryService memory, ProspectingMemoryService prospecting, ProductService products,
                           CeoService ceo, CompanyMemoryService companyMemory, CompanyPolicyService policies,
                           AlertMailService mail, FinanceService finance, CompanyEventPublisher events,
                           @Value("${ollama.agent-model}") String defaultModel) {
        this.memory = memory;
        this.prospecting = prospecting;
        this.products = products;
        this.ceo = ceo;
        this.companyMemory = companyMemory;
        this.policies = policies;
        this.mail = mail;
        this.finance = finance;
        this.events = events;
        this.defaultModel = defaultModel;
    }

    /** Un borrador por prospecto nuevo con email de esa corrida. Nunca lanza: un fallo deja el prospecto sin borrador. */
    public int draftFor(String runId, String productId) {
        var product = products.view(productId).map(v -> v.product()).orElse(null);
        if (product == null) {
            return 0;
        }
        var count = 0;
        for (var p : prospecting.prospectsOfRun(runId)) {
            if (p.contactEmail() == null || p.contactEmail().isBlank() || memory.optedOut(p.contactEmail())
                    || memory.outreachStatus(p.id()).isPresent()) {
                continue;
            }
            try {
                var draft = draft(product, p);
                if (draft == null) {
                    continue;
                }
                memory.saveDraft(new ContactDraft("DRAFT-" + UUID.randomUUID(), p.id(), p.name(), productId,
                        p.contactEmail().strip(), draft.subject(), draft.body(), "PENDING_APPROVAL", null, Instant.now(), null));
                memory.setOutreachStatus(p.id(), "DRAFTED");
                events.publish("EMPRESA_OUTREACH_DRAFTED", null, null, "sales", Map.of("prospectId", p.id()));
                count++;
            } catch (Exception ex) {
                log.warn("OUTREACH draft for {} failed: {}", p.id(), ex.getMessage());
            }
        }
        if (count > 0) {
            mail.send("Forjai: " + count + " correos a prospectos por aprobar",
                    "Sofía redactó " + count + " correos para prospectos de " + product.name()
                            + ". Revísalos y apruébalos en la pantalla Prospectos (nada se envía sin tu aprobación).", false);
        }
        return count;
    }

    private OutreachDraft draft(CatalogProduct product, Prospect p) {
        String correction = null;
        for (int attempt = 0; attempt < DRAFT_ATTEMPTS; attempt++) {
            var raw = ceo.draftOutreach(sheet(product), p.name() + " (" + p.url() + "): " + p.fitReason(), correction,
                    companyMemory.agentModel("sales", defaultModel));
            var result = check(raw, product);
            if (result.problems().isEmpty()) {
                return result.draft();
            }
            correction = String.join(" ", result.problems());
        }
        log.info("OUTREACH draft for {} discarded after {} attempts: {}", p.id(), DRAFT_ATTEMPTS, correction);
        return null;
    }

    private OutreachDraftValidator.Result check(OutreachDraft draft, CatalogProduct product) {
        return OutreachDraftValidator.validate(draft, product.name(),
                product.priceOnRequest() ? null : product.priceUsd(), product.priceOnRequest(), memory.signature(),
                Set.of("https://forjai.com"), companyMemory.alertEmail());
    }

    public synchronized ContactDraft approve(String draftId) {
        var d = memory.draft(draftId).orElseThrow(() -> new IllegalArgumentException("No existe el correo " + draftId + "."));
        if (!"PENDING_APPROVAL".equals(d.status()) && !"APPROVED".equals(d.status())) {
            throw new IllegalArgumentException("El correo a " + d.prospectName() + " ya está " + d.status() + ".");
        }
        if (memory.optedOut(d.to())) {
            throw new IllegalArgumentException(d.to() + " pidió no recibir más correos: no se envía.");
        }
        if (!"APPROVED".equals(d.status())) {
            events.publish("EMPRESA_OUTREACH_APPROVED", null, null, "human", Map.of("draftId", draftId));
        }
        var cap = (int) policies.activeValue(PolicyKey.MAX_OUTREACH_PER_DAY);
        if (memory.sentOn(LocalDate.now(ZoneOffset.UTC)) >= cap) {
            var reason = "Aprobado; se envía mañana: se alcanzó el tope diario de " + cap + " correos.";
            memory.setDraftStatus(draftId, "APPROVED", reason, null);
            return withStatus(d, "APPROVED", reason, null);
        }
        if (!memory.claimForContact(d.prospectId())) {
            return memory.draft(draftId).orElse(d);
        }
        var attemptId = memory.recordAttempt(d.prospectId(), draftId, d.to(), d.subject());
        var result = mail.sendToExternal(d.to(), d.subject(), d.body());
        memory.finishAttempt(attemptId, result.accepted(), result.errorMessage());
        if (result.accepted()) {
            var now = Instant.now();
            memory.setDraftStatus(draftId, "SENT", null, now);
            memory.setOutreachStatus(d.prospectId(), "CONTACTED");
            events.publish("EMPRESA_OUTREACH_SENT", null, null, "human", Map.of("draftId", draftId, "attemptId", attemptId));
            return withStatus(d, "SENT", null, now);
        }
        memory.setDraftStatus(draftId, "APPROVED", result.errorMessage(), null);
        memory.setOutreachStatus(d.prospectId(), "DRAFTED");
        events.publish("EMPRESA_OUTREACH_FAILED", null, null, "human",
                Map.of("draftId", draftId, "error", String.valueOf(result.errorMessage())));
        return withStatus(d, "APPROVED", result.errorMessage(), null);
    }

    public List<ContactDraft> approveAll() {
        var out = new ArrayList<ContactDraft>();
        for (var d : memory.drafts("PENDING_APPROVAL")) {
            out.add(approve(d.id()));
        }
        return out;
    }

    /** Lo aprobado que quedó por el tope o por un fallo se envía en el chequeo horario siguiente. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 240_000)
    public void sendDue() {
        for (var d : memory.drafts("APPROVED")) {
            try {
                var result = approve(d.id());
                if ("APPROVED".equals(result.status())) {
                    return;
                }
            } catch (Exception ex) {
                log.warn("OUTREACH send of {} failed: {}", d.id(), ex.getMessage());
            }
        }
    }

    public ContactDraft discard(String draftId) {
        var d = memory.draft(draftId).orElseThrow(() -> new IllegalArgumentException("No existe el correo " + draftId + "."));
        if ("SENT".equals(d.status())) {
            throw new IllegalArgumentException("El correo a " + d.prospectName() + " ya se envió.");
        }
        memory.setDraftStatus(draftId, "DISCARDED", null, null);
        memory.setOutreachStatus(d.prospectId(), null);
        return withStatus(d, "DISCARDED", null, null);
    }

    public ContactDraft edit(String draftId, String subject, String body) {
        var d = memory.draft(draftId).orElseThrow(() -> new IllegalArgumentException("No existe el correo " + draftId + "."));
        if (!"PENDING_APPROVAL".equals(d.status()) && !"APPROVED".equals(d.status())) {
            throw new IllegalArgumentException("El correo a " + d.prospectName() + " ya no se puede editar.");
        }
        var product = products.view(d.productId()).map(v -> v.product())
                .orElseThrow(() -> new IllegalArgumentException("El producto del correo ya no existe."));
        var result = check(new OutreachDraft(subject, body), product);
        if (!result.problems().isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", result.problems()));
        }
        memory.editDraft(draftId, result.draft().subject(), result.draft().body());
        return new ContactDraft(d.id(), d.prospectId(), d.prospectName(), d.productId(), d.to(), result.draft().subject(),
                result.draft().body(), d.status(), d.error(), d.createdAt(), d.sentAt());
    }

    public void respond(String prospectId, String response) {
        if (!RESPONSES.contains(response)) {
            throw new IllegalArgumentException("Respuesta inválida: " + response + " (INTERESTED, NOT_INTERESTED, OPTED_OUT).");
        }
        var status = memory.outreachStatus(prospectId).orElse(null);
        if (status == null || "DRAFTED".equals(status) || "CONTACT_IN_PROGRESS".equals(status) || "CONVERTED".equals(status)) {
            throw new IllegalArgumentException("Solo se registra la respuesta de un prospecto ya contactado.");
        }
        memory.setOutreachStatus(prospectId, response);
        if ("OPTED_OUT".equals(response)) {
            var p = prospecting.prospect(prospectId).orElse(null);
            if (p != null && p.contactEmail() != null) {
                memory.optOut(p.contactEmail());
                var domain = ProspectValidator.domain("https://" + p.contactEmail().substring(p.contactEmail().indexOf('@') + 1));
                if (domain != null) {
                    memory.optOut(domain);
                }
            }
        }
        events.publish("EMPRESA_PROSPECT_RESPONDED", null, null, "human", Map.of("prospectId", prospectId, "response", response));
    }

    public String convert(String prospectId) {
        var p = prospecting.prospect(prospectId)
                .orElseThrow(() -> new IllegalArgumentException("No existe el prospecto " + prospectId + "."));
        var status = memory.outreachStatus(prospectId).orElse(null);
        if ("CONVERTED".equals(status)) {
            throw new IllegalArgumentException(p.name() + " ya es cliente.");
        }
        if ("OPTED_OUT".equals(status)) {
            throw new IllegalArgumentException(p.name() + " pidió no recibir más correos.");
        }
        var customer = finance.registerCustomer(new FinanceCustomerCommand(p.name(),
                p.contactEmail() != null ? p.contactEmail() : p.contactFormUrl(), null,
                "Prospecto convertido por el fundador; producto: " + p.productName(), p.url()));
        memory.linkConverted(prospectId, customer.id());
        memory.setOutreachStatus(prospectId, "CONVERTED");
        events.publish("EMPRESA_PROSPECT_CONVERTED", null, null, "human",
                Map.of("prospectId", prospectId, "customerId", customer.id()));
        return customer.id();
    }

    public String signature() {
        return memory.signature();
    }

    public void setSignature(String signature) {
        if (signature == null || signature.isBlank() || signature.length() > 200) {
            throw new IllegalArgumentException("La firma debe tener entre 1 y 200 caracteres.");
        }
        memory.setSignature(signature.strip());
    }

    private static ContactDraft withStatus(ContactDraft d, String status, String error, Instant sentAt) {
        return new ContactDraft(d.id(), d.prospectId(), d.prospectName(), d.productId(), d.to(), d.subject(), d.body(),
                status, error, d.createdAt(), sentAt);
    }

    private static String sheet(CatalogProduct p) {
        return "Nombre: " + p.name() + "\nDescripción: " + p.description() + "\nCliente objetivo: " + p.targetCustomer()
                + "\nPrecio: " + (p.priceOnRequest() ? "a cotizar" : "US$" + p.priceUsd());
    }
}
```

Nota sobre `approvingTwiceSendsOnce`: la segunda llamada encuentra `claimForContact` en `false` y devuelve el draft sin enviar. Nota sobre `approvingSendsAndMarksContacted`: el mock de `claimForContact("C1")` es `true` (el prospecto está `DRAFTED`).

- [ ] **Step 4: Ver que pasan** — `cd app && mvn -q test -Dtest=OutreachServiceTest` → PASS (14 tests).
- [ ] **Step 5: Commit** — `git add app/src/main/java/com/aicompany/core/outreach app/src/main/java/com/aicompany/core/prospecting/ProspectingMemoryService.java app/src/test/java/com/aicompany/core/outreach && git commit -m "Contacto: borradores, aprobación, envío con tope, respuestas y conversión a cliente"`.

---

### Task 6: Enganche con la búsqueda y bajas en `knownDomains`

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/prospecting/ProspectingService.java`
- Test: `app/src/test/java/com/aicompany/core/prospecting/ProspectingServiceTest.java`

**Interfaces:**
- Consumes: `OutreachService.draftFor(String runId, String productId)`, `OutreachMemoryService.optedOutDomains()` (Task 5).
- Produces: `ProspectingService` constructor gana `OutreachService outreach` y `OutreachMemoryService outreachMemory` (al final).

- [ ] **Step 1: Test que falla**

Constructor del test: agregar mocks
`private final com.aicompany.core.outreach.OutreachService outreach = mock(com.aicompany.core.outreach.OutreachService.class);`
`private final com.aicompany.core.outreach.OutreachMemoryService outreachMemory = mock(com.aicompany.core.outreach.OutreachMemoryService.class);`
y pasarlos al final de `new ProspectingService(...)`; en el inicializador `when(outreachMemory.optedOutDomains()).thenReturn(Set.of());`.

```java
    // Spec contacto con prospectos §1: al terminar la corrida se piden los borradores; una baja nunca vuelve.
    @Test
    void aCompletedRunWithProspectsAsksForDrafts() {
        catalog(product("P1", CatalogStatus.READY_TO_SELL, "Creadores", List.of("WORLDWIDE"), List.of("en")));
        when(ceo.searchProspects(anyString(), any(), any(), any(), anyString())).thenReturn(new ProspectBatch(List.of(acme())));

        var run = service.runNow();

        verify(outreach).draftFor(run.id(), "P1");
    }

    @Test
    void aDraftingFailureNeverBreaksTheRun() {
        catalog(product("P1", CatalogStatus.READY_TO_SELL, "Creadores", List.of("WORLDWIDE"), List.of("en")));
        when(ceo.searchProspects(anyString(), any(), any(), any(), anyString())).thenReturn(new ProspectBatch(List.of(acme())));
        when(outreach.draftFor(anyString(), anyString())).thenThrow(new IllegalStateException("modelo caído"));

        assertEquals("COMPLETED", service.runNow().status());
    }

    @Test
    void optedOutDomainsAreTreatedAsKnown() {
        catalog(product("P1", CatalogStatus.READY_TO_SELL, "Creadores", List.of("WORLDWIDE"), List.of("en")));
        when(outreachMemory.optedOutDomains()).thenReturn(Set.of("acme.com"));
        when(ceo.searchProspects(anyString(), any(), any(), any(), anyString())).thenReturn(new ProspectBatch(List.of(acme())));

        var run = service.runNow();

        assertEquals(0, run.valid());
        assertTrue(run.rejections().get(0).contains("repetido"), run.rejections().toString());
    }
```

- [ ] **Step 2: Ver que falla** — `cd app && mvn -q test -Dtest=ProspectingServiceTest` → compilación falla.
- [ ] **Step 3: Implementar** — constructor + campos; en `runNow`:
  - `memory.knownDomains(product.id())` → `union(memory.knownDomains(product.id()), outreachMemory.optedOutDomains())` (nuevo `HashSet`).
  - Después de `memory.saveRun(run)` y antes del evento:

```java
            if (!saved.isEmpty()) {
                try {
                    outreach.draftFor(id, product.id());
                } catch (Exception ex) {
                    log.warn("PROSPECTING run {}: drafting failed: {}", id, ex.getMessage());
                }
            }
```

- [ ] **Step 4: Ver que pasan** — mismo comando → PASS.
- [ ] **Step 5: Commit** — `git commit -m "Prospectos: borradores al terminar la corrida y bajas fuera de la búsqueda"`.

---

### Task 7: `OutreachController` y conteo en `AutonomyService`

**Files:**
- Create: `app/src/main/java/com/aicompany/core/controller/OutreachController.java`
- Modify: `app/src/main/java/com/aicompany/core/service/AutonomyService.java`
- Modify: `app/src/main/java/com/aicompany/core/prospecting/Prospect.java` + `ProspectingMemoryService.prospectsWhere` (campo `outreachStatus`)
- Test: `app/src/test/java/com/aicompany/core/controller/OutreachControllerTest.java`, `AutonomyServiceTest.java`, `AutonomyControllerTest.java`, `ChatIntentRouterTest.java` (constructores de `Waiting`), tests que construyen `Prospect`.

**Interfaces:**
- Produces:
  - `GET /api/company/outreach/drafts?status=` → `List<ContactDraft>` (sin status: últimas 50); `PUT /drafts/{id}` body `{subject, body}`; `POST /drafts/{id}/approve`; `POST /drafts/{id}/discard`; `POST /drafts/approve-all`; `POST /prospects/{id}/response` body `{response}`; `POST /prospects/{id}/convert` → `{customerId}`; `GET|PUT /settings` `{signature}`.
  - `AutonomyService.Waiting(int orchestratorMissions, int pendingDependencies, int pendingStrategies, int pendingDrafts)`; constructor gana `OutreachMemoryService`.
  - `Prospect` gana `String outreachStatus` como **último** campo.

- [ ] **Step 1: Tests que fallan**

`OutreachControllerTest.java`:

```java
package com.aicompany.core.controller;

import com.aicompany.core.outreach.OutreachMemoryService;
import com.aicompany.core.outreach.OutreachService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutreachControllerTest {

    private final OutreachService service = mock(OutreachService.class);
    private final OutreachMemoryService memory = mock(OutreachMemoryService.class);
    private final OutreachController controller = new OutreachController(service, memory);

    @Test
    void draftsByStatusOrLatest() {
        controller.drafts("PENDING_APPROVAL");
        controller.drafts(null);

        verify(memory).drafts("PENDING_APPROVAL");
        verify(memory).allDrafts(50);
    }

    @Test
    void actionsDelegate() {
        controller.approve("D1");
        controller.discard("D2");
        controller.approveAll();
        controller.edit("D3", new OutreachController.DraftEdit("A", "B"));
        controller.respond("C1", new OutreachController.ResponseCommand("INTERESTED"));
        when(service.convert("C2")).thenReturn("CUSTOMER-1");

        assertEquals(Map.of("customerId", "CUSTOMER-1"), controller.convert("C2"));
        verify(service).approve("D1");
        verify(service).discard("D2");
        verify(service).approveAll();
        verify(service).edit("D3", "A", "B");
        verify(service).respond("C1", "INTERESTED");
    }

    @Test
    void signatureSettings() {
        when(service.signature()).thenReturn("Forjai");

        assertEquals(Map.of("signature", "Forjai"), controller.settings());
        controller.updateSettings(Map.of("signature", "Alex — Forjai"));
        verify(service).setSignature("Alex — Forjai");
    }
}
```

`AutonomyServiceTest`: constructor con `outreachMemory = mock(com.aicompany.core.outreach.OutreachMemoryService.class)` al final; test nuevo:

```java
    @Test
    void draftsWaitingForApprovalAreCounted() {
        productsOn(true, "seed");
        clientsOn(true);
        when(outreachMemory.drafts("PENDING_APPROVAL")).thenReturn(List.of(
                new com.aicompany.core.outreach.ContactDraft("D1", "C1", "Acme", "P1", "a@acme.com", "s", "b",
                        "PENDING_APPROVAL", null, Instant.now(), null)));

        assertEquals(1, service.view().waiting().pendingDrafts());
    }
```

`Waiting(0, 0, 0)` → `Waiting(0, 0, 0, 0)` y `Waiting(3, 1, 0)` → `Waiting(3, 1, 0, 0)` en `AutonomyControllerTest` y `ChatIntentRouterTest`. Donde los tests construyen `new com.aicompany.core.prospecting.Prospect(...)` o `new Prospect(...)` (en `ChatIntentRouterTest.prospect(...)` y `OutreachServiceTest.prospect(...)`), agregar `null` como último argumento.

- [ ] **Step 2: Ver que fallan** — `cd app && mvn -q test -Dtest=OutreachControllerTest,AutonomyServiceTest` → compilación falla.
- [ ] **Step 3: Implementar**

`OutreachController.java`:

```java
package com.aicompany.core.controller;

import com.aicompany.core.outreach.ContactDraft;
import com.aicompany.core.outreach.OutreachMemoryService;
import com.aicompany.core.outreach.OutreachService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Spec contacto con prospectos: todo acá es acción del fundador (🔴), salvo leer. */
@RestController
@RequestMapping("/api/company/outreach")
public class OutreachController {

    public record DraftEdit(String subject, String body) {
    }

    public record ResponseCommand(String response) {
    }

    private final OutreachService service;
    private final OutreachMemoryService memory;

    public OutreachController(OutreachService service, OutreachMemoryService memory) {
        this.service = service;
        this.memory = memory;
    }

    @GetMapping("/drafts")
    public List<ContactDraft> drafts(@RequestParam(value = "status", required = false) String status) {
        return status == null || status.isBlank() ? memory.allDrafts(50) : memory.drafts(status);
    }

    @PutMapping("/drafts/{id}")
    public ContactDraft edit(@PathVariable("id") String id, @RequestBody DraftEdit edit) {
        return service.edit(id, edit.subject(), edit.body());
    }

    @PostMapping("/drafts/{id}/approve")
    public ContactDraft approve(@PathVariable("id") String id) {
        return service.approve(id);
    }

    @PostMapping("/drafts/{id}/discard")
    public ContactDraft discard(@PathVariable("id") String id) {
        return service.discard(id);
    }

    @PostMapping("/drafts/approve-all")
    public List<ContactDraft> approveAll() {
        return service.approveAll();
    }

    @PostMapping("/prospects/{id}/response")
    public void respond(@PathVariable("id") String id, @RequestBody ResponseCommand command) {
        service.respond(id, command.response());
    }

    @PostMapping("/prospects/{id}/convert")
    public Map<String, String> convert(@PathVariable("id") String id) {
        return Map.of("customerId", service.convert(id));
    }

    @GetMapping("/settings")
    public Map<String, String> settings() {
        return Map.of("signature", service.signature());
    }

    @PutMapping("/settings")
    public Map<String, String> updateSettings(@RequestBody Map<String, String> body) {
        service.setSignature(body.get("signature"));
        return settings();
    }
}
```

`AutonomyService`: `Waiting` gana `int pendingDrafts`; constructor gana `OutreachMemoryService outreachMemory` (último); en `view()` el cuarto argumento de `Waiting` es `outreachMemory.drafts("PENDING_APPROVAL").size()`.

`Prospect`: agregar `String outreachStatus` al final; en `ProspectingMemoryService.prospectsWhere` pasar `c.get("outreachStatus").asString(null)` como último argumento.

- [ ] **Step 4: Ver que pasan** — `cd app && mvn -q test -Dtest=OutreachControllerTest,AutonomyServiceTest,AutonomyControllerTest,ChatIntentRouterTest,OutreachServiceTest,ProspectingServiceTest` → PASS.
- [ ] **Step 5: Commit** — `git commit -m "API de contacto con prospectos y correos por aprobar en el modo automático"`.

---

### Task 8: Chat

**Files:**
- Modify: `app/src/main/java/com/aicompany/core/service/ChatIntentRouter.java`
- Test: `app/src/test/java/com/aicompany/core/service/ChatIntentRouterTest.java`

**Interfaces:**
- Consumes: `OutreachService` (approve, approveAll, discard, respond, convert), `OutreachMemoryService.drafts/allDrafts`, `ProspectingMemoryService.prospects()` (con `outreachStatus`).
- Produces: nada.

- [ ] **Step 1: Tests que fallan**

Mocks + constructor (al final): `outreachService = mock(com.aicompany.core.outreach.OutreachService.class)`, `outreachMemory = mock(com.aicompany.core.outreach.OutreachMemoryService.class)`.

```java
    // Spec contacto con prospectos §5: el fundador decide los contactos desde el chat, en Java.
    private static com.aicompany.core.outreach.ContactDraft pendingDraft(String id, String name) {
        return new com.aicompany.core.outreach.ContactDraft(id, "C-" + id, name, "P1", "hola@" + id + ".com", "Asunto",
                "Cuerpo", "PENDING_APPROVAL", null, Instant.now(), null);
    }

    @Test
    void theDraftsQueryListsWhatWaitsForApproval() {
        when(outreachMemory.drafts("PENDING_APPROVAL")).thenReturn(List.of(pendingDraft("D1", "Acme Legal")));

        var response = router.route("¿qué correos hay por aprobar?");

        assertTrue(response.contains("Acme Legal"), response);
        assertTrue(response.contains("hola@D1.com"), response);
        verifyNoInteractions(ceoService);
    }

    @Test
    void approveAllDrafts() {
        when(outreachService.approveAll()).thenReturn(List.of(new com.aicompany.core.outreach.ContactDraft("D1", "C1",
                "Acme Legal", "P1", "a@acme.com", "s", "b", "SENT", null, Instant.now(), Instant.now())));

        var response = router.route("aprueba los correos");

        verify(outreachService).approveAll();
        assertTrue(response.contains("1 enviado"), response);
    }

    @Test
    void approveOneDraftByProspectName() {
        when(outreachMemory.drafts("PENDING_APPROVAL")).thenReturn(List.of(pendingDraft("D1", "Acme Legal"),
                pendingDraft("D2", "Beta Law")));
        when(outreachService.approve("D2")).thenReturn(pendingDraft("D2", "Beta Law"));

        router.route("aprueba el correo a Beta Law");

        verify(outreachService).approve("D2");
    }

    @Test
    void outreachCommandsDoNotCollideWithStrategies() {
        router.route("aprueba los correos");

        verify(strategyService, never()).approve(anyString());
        verify(missionService, never()).recordDecision(anyString(), any());
    }

    @Test
    void aResponseAndAConversionByName() {
        when(prospectingMemory.prospects()).thenReturn(List.of(new com.aicompany.core.prospecting.Prospect("C1", "P1",
                "Pack", "Acme Legal", "https://acme.com", "a@acme.com", "https://acme.com/c", null, "x",
                "BASE-DIRECTORIES", Instant.now(), "CONTACTED")));
        when(outreachService.convert("C1")).thenReturn("CUSTOMER-1");

        router.route("Acme Legal respondió interesado");
        var response = router.route("convierte a Acme Legal en cliente");

        verify(outreachService).respond("C1", "INTERESTED");
        assertTrue(response.contains("CUSTOMER-1"), response);
    }
```

- [ ] **Step 2: Ver que fallan** — `cd app && mvn -q test -Dtest=ChatIntentRouterTest` → compilación falla (constructor).
- [ ] **Step 3: Implementar**

1. Campos/constructor (al final): `com.aicompany.core.outreach.OutreachService outreachService`, `com.aicompany.core.outreach.OutreachMemoryService outreachMemory`.
2. Patrones junto a `STRATEGY_COMMAND`:

```java
    /** Spec contacto con prospectos §5: decisiones del fundador sobre correos y prospectos (🔴). */
    private static final Pattern OUTREACH_APPROVE_ALL = Pattern.compile(
            "^\\s*(?:@\\S+[\\s,]+)*(?:por favor\\s+)?aprueba\\s+(?:todos\\s+)?los\\s+correos\\s*[.!]?\\s*$");
    private static final Pattern OUTREACH_ONE = Pattern.compile(
            "^\\s*(?:@\\S+[\\s,]+)*(?:por favor\\s+)?(aprueba|descarta)\\s+el\\s+correo\\s+(?:a|para|de)\\s+(.+?)\\s*[.!]?\\s*$");
    private static final Pattern PROSPECT_RESPONSE = Pattern.compile(
            "^\\s*(.+?)\\s+respondio\\s+(interesado|no interesado|pidio baja|que no)\\s*[.!]?\\s*$");
    private static final Pattern PROSPECT_CONVERT = Pattern.compile(
            "^\\s*(?:@\\S+[\\s,]+)*(?:por favor\\s+)?convierte\\s+a\\s+(.+?)\\s+en\\s+cliente\\s*[.!]?\\s*$");
```

3. `isGovernance`: agregar los cuatro (`matcher(normalize(message)).matches()`).
4. `resolve`, después del bloque de `strategyCommand`:

```java
        var normalizedMessage = normalize(message);
        if (OUTREACH_APPROVE_ALL.matcher(normalizedMessage).matches()) {
            return handleApproveAllDrafts();
        }
        var outreachOne = OUTREACH_ONE.matcher(normalizedMessage);
        if (outreachOne.matches()) {
            return handleOneDraft("aprueba".equals(outreachOne.group(1)), outreachOne.group(2));
        }
        var prospectResponse = PROSPECT_RESPONSE.matcher(normalizedMessage);
        if (prospectResponse.matches()) {
            return handleProspectResponse(prospectResponse.group(1), prospectResponse.group(2));
        }
        var prospectConvert = PROSPECT_CONVERT.matcher(normalizedMessage);
        if (prospectConvert.matches()) {
            return handleProspectConvert(prospectConvert.group(1));
        }
```

5. Query: `QueryIntent` gana `OUTREACH_DRAFTS, OUTREACH_STATUS`; `detectQuery`, antes del bloque de prospectos:

```java
        // Spec contacto con prospectos (2026-09-30).
        if (normalized.matches(".*\\bcorreos?\\b.*\\b(aprobar|pendientes?)\\b.*")) {
            return new QueryMatch(QueryIntent.OUTREACH_DRAFTS, null);
        }
        if (normalized.contains("a quien contactamos") || normalized.contains("quien respondio")) {
            return new QueryMatch(QueryIntent.OUTREACH_STATUS, null);
        }
```

   Switch: `case "OUTREACH_DRAFTS" -> formatDrafts(); case "OUTREACH_STATUS" -> formatOutreachStatus();`.
6. Métodos:

```java
    private String handleApproveAllDrafts() {
        var results = outreachService.approveAll();
        if (results.isEmpty()) {
            return "No hay correos por aprobar.";
        }
        var sent = results.stream().filter(d -> "SENT".equals(d.status())).count();
        var queued = results.stream().filter(d -> "APPROVED".equals(d.status())).toList();
        return "Correos aprobados: " + sent + " enviado(s)"
                + (queued.isEmpty() ? "." : "; " + queued.size() + " quedan para después: "
                        + queued.stream().map(d -> d.prospectName() + " (" + d.error() + ")").collect(Collectors.joining("; ")) + ".");
    }

    private String handleOneDraft(boolean approve, String name) {
        var wanted = com.aicompany.core.prospecting.ProspectValidator.normalize(name);
        var pending = outreachMemory.drafts("PENDING_APPROVAL");
        var exact = pending.stream().filter(d -> com.aicompany.core.prospecting.ProspectValidator.normalize(d.prospectName()).equals(wanted)).toList();
        var matches = exact.isEmpty()
                ? pending.stream().filter(d -> com.aicompany.core.prospecting.ProspectValidator.normalize(d.prospectName()).contains(wanted)).toList()
                : exact;
        if (matches.size() != 1) {
            return matches.isEmpty() ? "No hay un correo por aprobar para \"" + name + "\"."
                    : "Coinciden varios: " + matches.stream().map(d -> d.prospectName()).collect(Collectors.joining(", "))
                            + ". Escribe el nombre exacto.";
        }
        var draft = matches.get(0);
        if (!approve) {
            outreachService.discard(draft.id());
            return "Correo a " + draft.prospectName() + " descartado.";
        }
        var result = outreachService.approve(draft.id());
        return "SENT".equals(result.status()) ? "Correo a " + draft.prospectName() + " enviado a " + draft.to() + "."
                : "Correo a " + draft.prospectName() + " aprobado; queda pendiente: " + result.error();
    }

    private Optional<com.aicompany.core.prospecting.Prospect> prospectByName(String name) {
        var wanted = com.aicompany.core.prospecting.ProspectValidator.normalize(name);
        var all = prospectingMemory.prospects();
        var exact = all.stream().filter(p -> com.aicompany.core.prospecting.ProspectValidator.normalize(p.name()).equals(wanted)).toList();
        var matches = exact.isEmpty()
                ? all.stream().filter(p -> com.aicompany.core.prospecting.ProspectValidator.normalize(p.name()).contains(wanted)).toList()
                : exact;
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private String handleProspectResponse(String name, String answer) {
        var prospect = prospectByName(name);
        if (prospect.isEmpty()) {
            return "No encontré un único prospecto que se llame \"" + name + "\".";
        }
        var response = switch (answer) {
            case "interesado" -> "INTERESTED";
            case "no interesado", "que no" -> "NOT_INTERESTED";
            default -> "OPTED_OUT";
        };
        outreachService.respond(prospect.get().id(), response);
        return "Anotado: " + prospect.get().name() + " → " + answer
                + ("OPTED_OUT".equals(response) ? " (no se le vuelve a escribir, ni a su dominio)." : ".");
    }

    private String handleProspectConvert(String name) {
        var prospect = prospectByName(name);
        if (prospect.isEmpty()) {
            return "No encontré un único prospecto que se llame \"" + name + "\".";
        }
        var customerId = outreachService.convert(prospect.get().id());
        return prospect.get().name() + " ya es cliente real (" + customerId + "): puedes registrarle ventas en Finanzas.";
    }

    private String formatDrafts() {
        var drafts = outreachMemory.drafts("PENDING_APPROVAL");
        if (drafts.isEmpty()) {
            return "No hay correos por aprobar.";
        }
        return "Correos por aprobar (" + drafts.size() + "; nada se envía sin tu aprobación — \"aprueba los correos\" o "
                + "\"aprueba el correo a X\"):\n" + drafts.stream()
                .map(d -> "- " + d.prospectName() + " <" + d.to() + ">: " + d.subject())
                .collect(Collectors.joining("\n"));
    }

    private String formatOutreachStatus() {
        var contacted = prospectingMemory.prospects().stream()
                .filter(p -> p.outreachStatus() != null && !"DRAFTED".equals(p.outreachStatus())).toList();
        if (contacted.isEmpty()) {
            return "Todavía no contactamos a ningún prospecto.";
        }
        return "Prospectos contactados:\n" + contacted.stream()
                .map(p -> "- " + p.name() + " (" + p.productName() + "): " + p.outreachStatus())
                .collect(Collectors.joining("\n"));
    }
```

(Si `Optional` no está importado en el archivo, agregar `import java.util.Optional;`.)

- [ ] **Step 4: Ver que pasan** — `cd app && mvn -q test -Dtest=ChatIntentRouterTest` → PASS.
- [ ] **Step 5: Suite completa** — `cd app && mvn test 2>&1 | grep -E "Tests run:.*Skipped: 0$|BUILD" | tail -2` → `BUILD SUCCESS`.
- [ ] **Step 6: Commit** — `git commit -m "Chat: correos por aprobar, aprobar/descartar, respuestas y conversión"`.

---

### Task 9: Frontend

**Files:**
- Modify: `app/frontend/src/api/types.ts`, `app/frontend/src/api/client.ts`, `app/frontend/src/pages/ProspectsPage.tsx`, `app/frontend/src/components/AutonomyPanel.tsx`, `app/frontend/src/pages/SettingsPage.tsx`

- [ ] **Step 1: Tipos y cliente**

`types.ts`: `Prospect` gana `outreachStatus: string | null`; `AutonomyView.waiting` gana `pendingDrafts: number`; nuevo:

```ts
// Contacto con prospectos (spec 2026-09-30): reflejo a mano de ContactDraft.
export interface ContactDraft {
  id: string
  prospectId: string
  prospectName: string | null
  productId: string | null
  to: string
  subject: string
  body: string
  status: 'PENDING_APPROVAL' | 'APPROVED' | 'SENT' | 'DISCARDED'
  error: string | null
  createdAt: string
  sentAt: string | null
}
```

`client.ts`:

```ts
  outreachDrafts: (status?: string) =>
    request<ContactDraft[]>(`/api/company/outreach/drafts${status ? `?status=${status}` : ''}`),
  editDraft: (id: string, subject: string, body: string) =>
    request<ContactDraft>(`/api/company/outreach/drafts/${encodeURIComponent(id)}`, {
      method: 'PUT',
      body: JSON.stringify({ subject, body }),
    }),
  approveDraft: (id: string) => request<ContactDraft>(`/api/company/outreach/drafts/${encodeURIComponent(id)}/approve`, { method: 'POST' }),
  discardDraft: (id: string) => request<ContactDraft>(`/api/company/outreach/drafts/${encodeURIComponent(id)}/discard`, { method: 'POST' }),
  approveAllDrafts: () => request<ContactDraft[]>('/api/company/outreach/drafts/approve-all', { method: 'POST' }),
  prospectResponse: (id: string, response: 'INTERESTED' | 'NOT_INTERESTED' | 'OPTED_OUT') =>
    request<unknown>(`/api/company/outreach/prospects/${encodeURIComponent(id)}/response`, {
      method: 'POST',
      body: JSON.stringify({ response }),
    }),
  convertProspect: (id: string) =>
    request<{ customerId: string }>(`/api/company/outreach/prospects/${encodeURIComponent(id)}/convert`, { method: 'POST' }),
  outreachSettings: () => request<{ signature: string }>('/api/company/outreach/settings'),
  updateOutreachSettings: (signature: string) =>
    request<{ signature: string }>('/api/company/outreach/settings', { method: 'PUT', body: JSON.stringify({ signature }) }),
```

- [ ] **Step 2: Prospectos**

En `ProspectsPage.tsx`, antes de "Por producto", un componente `DraftsSection` (en el mismo archivo):

```tsx
function DraftsSection() {
  const queryClient = useQueryClient()
  const [feedback, setFeedback] = useState<string | null>(null)
  const [editing, setEditing] = useState<Record<string, { subject: string; body: string }>>({})
  const drafts = useQuery({ queryKey: ['drafts'], queryFn: () => api.outreachDrafts('PENDING_APPROVAL'), refetchInterval: 30_000 })
  const done = (message: string) => {
    setFeedback(message)
    for (const key of ['drafts', 'prospects', 'autonomy']) void queryClient.invalidateQueries({ queryKey: [key] })
  }
  const act = useMutation({
    mutationFn: async ({ id, action }: { id: string; action: 'approve' | 'discard' | 'save' }) => {
      if (action === 'approve') return api.approveDraft(id)
      if (action === 'discard') return api.discardDraft(id)
      const e = editing[id]
      return api.editDraft(id, e.subject, e.body)
    },
    onSuccess: (d) =>
      done(d.status === 'SENT' ? `Enviado a ${d.to}.` : d.status === 'APPROVED' ? `Aprobado; pendiente: ${d.error}` : 'Listo.'),
    onError: (e: Error) => setFeedback(e.message),
  })
  const all = useMutation({
    mutationFn: api.approveAllDrafts,
    onSuccess: (list) => done(`${list.filter((d) => d.status === 'SENT').length} enviados de ${list.length}.`),
    onError: (e: Error) => setFeedback(e.message),
  })
  const list = drafts.data ?? []
  return (
    <>
      <h2>Correos por aprobar ({list.length})</h2>
      <p className="hint">Nada se envía sin tu aprobación. Las respuestas llegan a tu correo.</p>
      {feedback && <p className={act.isError || all.isError ? 'error' : 'feedback'}>{feedback}</p>}
      {list.length > 0 && (
        <button disabled={all.isPending} onClick={() => all.mutate()}>
          Aprobar todos
        </button>
      )}
      {list.map((d) => {
        const e = editing[d.id]
        return (
          <div key={d.id} className="card">
            <strong>{d.prospectName}</strong> &lt;{d.to}&gt;
            {e ? (
              <>
                <input value={e.subject} onChange={(x) => setEditing({ ...editing, [d.id]: { ...e, subject: x.target.value } })} />
                <textarea rows={8} value={e.body} onChange={(x) => setEditing({ ...editing, [d.id]: { ...e, body: x.target.value } })} />
                <button disabled={act.isPending} onClick={() => act.mutate({ id: d.id, action: 'save' })}>Guardar</button>
              </>
            ) : (
              <>
                <p><strong>{d.subject}</strong></p>
                <pre className="draft-body">{d.body}</pre>
                <button onClick={() => setEditing({ ...editing, [d.id]: { subject: d.subject, body: d.body } })}>Editar</button>
              </>
            )}{' '}
            <button disabled={act.isPending} onClick={() => act.mutate({ id: d.id, action: 'approve' })}>Aprobar y enviar</button>{' '}
            <button className="danger" disabled={act.isPending} onClick={() => act.mutate({ id: d.id, action: 'discard' })}>
              Descartar
            </button>
          </div>
        )
      })}
    </>
  )
}
```

`<DraftsSection />` debajo del párrafo "Contactar prospectos…". En cada prospecto de la lista, agregar el estado y acciones:

```tsx
                {' · '}
                <span className="hint">{OUTREACH_LABELS[p.outreachStatus ?? ''] ?? 'sin contactar'}</span>
                {p.outreachStatus === 'CONTACTED' && (
                  <>
                    {' '}
                    <button onClick={() => respond.mutate({ id: p.id, r: 'INTERESTED' })}>Interesado</button>{' '}
                    <button onClick={() => respond.mutate({ id: p.id, r: 'NOT_INTERESTED' })}>No interesado</button>{' '}
                    <button onClick={() => respond.mutate({ id: p.id, r: 'OPTED_OUT' })}>Pidió baja</button>
                  </>
                )}
                {(p.outreachStatus === 'CONTACTED' || p.outreachStatus === 'INTERESTED') && (
                  <>
                    {' '}
                    <button onClick={() => convert.mutate(p.id)}>Convertir en cliente</button>
                  </>
                )}
                {!p.contactEmail && p.contactFormUrl && <span className="hint"> · contactar a mano</span>}
```

con, dentro de `ProspectsPage`:

```tsx
  const respond = useMutation({
    mutationFn: ({ id, r }: { id: string; r: 'INTERESTED' | 'NOT_INTERESTED' | 'OPTED_OUT' }) => api.prospectResponse(id, r),
    onSuccess: () => { setFeedback('Respuesta anotada.'); refresh() },
    onError: (e: Error) => setFeedback(e.message),
  })
  const convert = useMutation({
    mutationFn: (id: string) => api.convertProspect(id),
    onSuccess: (r) => { setFeedback(`Ahora es cliente (${r.customerId}): regístrale ventas en Finanzas.`); refresh() },
    onError: (e: Error) => setFeedback(e.message),
  })
```

y la constante a nivel de módulo:

```tsx
const OUTREACH_LABELS: Record<string, string> = {
  DRAFTED: 'borrador',
  CONTACT_IN_PROGRESS: 'enviando',
  CONTACTED: 'contactado',
  INTERESTED: 'interesado',
  NOT_INTERESTED: 'no interesado',
  OPTED_OUT: 'baja',
  CONVERTED: 'cliente',
}
```

`index.css`: `.draft-body { white-space: pre-wrap; font-family: inherit; }`.

- [ ] **Step 3: Dashboard y Settings**

`AutonomyPanel.tsx` "Esperando tu decisión": agregar `· <Link to="/prospectos">{view.waiting.pendingDrafts} correos por aprobar</Link>`.

`SettingsPage.tsx`: `POLICY_LABELS.MAX_OUTREACH_PER_DAY = 'Correos a prospectos por día (máximo)'`; sección nueva "Firma de los correos a prospectos" con un `input` (valor de `api.outreachSettings`) y botón Guardar (`api.updateOutreachSettings`), mostrando el error del backend si lo hay.

- [ ] **Step 4: Build y lint** — `cd app/frontend && npm run build && npm run lint` → build OK; sin hallazgos nuevos.
- [ ] **Step 5: Commit** — `git commit -m "Command Center: correos por aprobar, respuestas, conversión y firma"`.

---

### Task 10: Documentación, despliegue y verificación en vivo

- [ ] **Step 1: Documentación**
  - `CLAUDE.md`: bullet nuevo "**Contacto con prospectos**" después del de búsqueda de prospectos, resumiendo: borradores de Sofía verificados por `OutreachDraftValidator` (producto y precio exactos, sin URLs/emails ajenos, baja y firma agregadas por Java, 3 intentos), aprobación del fundador (🔴, Prospectos/chat), envío por el SMTP de alertas con `Reply-To` al fundador y tope `MAX_OUTREACH_PER_DAY`, CAS contra doble envío, `ContactAttempt`, `Customer.outreachStatus` (el `status` sigue `'LEAD'` por Finanzas), bajas `(:OptOut)` por email y dominio (también excluidas de la búsqueda), conversión a cliente de Finanzas (`CONVERTED_TO`), `/api/company/outreach/**`. En "Command Center web", Prospectos suma "correos por aprobar".
  - `docs/EVENTS.md`: línea con `EMPRESA_OUTREACH_DRAFTED|APPROVED|SENT|FAILED`, `EMPRESA_PROSPECT_RESPONDED|CONVERTED` y sus `data`.
  - `docs/HISTORY.md`: sección "Contacto con prospectos (subproyecto 6)" con las 4 decisiones del fundador y la decisión técnica de `outreachStatus`.
  - Commit `"Documentar el contacto con prospectos"`.
- [ ] **Step 2: Suite, build** — `cd app && mvn test` → `BUILD SUCCESS`; `cd frontend && npm run build`.
- [ ] **Step 3: Desplegar** — solo sin misiones en curso ni agentes `WORKING`.
- [ ] **Step 4: Verificar en vivo** — `GET /outreach/drafts` → `[]`; `GET /outreach/settings` → firma default; `POST /outreach/drafts/NO-EXISTE/approve` → 500 con motivo; `GET /autonomy` → `pendingDrafts`. Un envío real solo con autorización explícita del fundador (con un producto listo y, si lo prefiere, a una dirección suya). Registrar en `docs/HISTORY.md`.
