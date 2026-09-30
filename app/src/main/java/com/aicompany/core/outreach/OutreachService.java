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
                    || memory.optedOutDomains().contains(ProspectValidator.domain(p.url()))
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
            // Revisión final: nunca "no hacer nada" en silencio (el fundador creería que se envió).
            throw new IllegalArgumentException("El prospecto " + d.prospectName() + " no está disponible para contactar"
                    + " (estado " + memory.outreachStatus(d.prospectId()).orElse("sin contactar") + ").");
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
        // Revisión final: solo el tope diario corta la cola; un correo que falla no atasca a los demás.
        var cap = (int) policies.activeValue(PolicyKey.MAX_OUTREACH_PER_DAY);
        for (var d : memory.drafts("APPROVED")) {
            if (memory.sentOn(LocalDate.now(ZoneOffset.UTC)) >= cap) {
                return;
            }
            try {
                approve(d.id());
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
            if (p != null) {
                // Revisión final: se bloquea el email y el dominio del SITIO del prospecto, nunca el del proveedor de
                // correo (una baja desde hola@gmail.com no puede bloquear a todo Gmail).
                if (p.contactEmail() != null) {
                    memory.optOut(p.contactEmail());
                }
                var domain = ProspectValidator.domain(p.url());
                if (domain != null) {
                    memory.optOut(domain);
                }
            }
        }
        events.publish("EMPRESA_PROSPECT_RESPONDED", null, null, "human", Map.of("prospectId", prospectId, "response", response));
    }

    /** synchronized: dos "convertir" a la vez (pantalla + chat, doble clic) crean un solo cliente real. */
    public synchronized String convert(String prospectId) {
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
