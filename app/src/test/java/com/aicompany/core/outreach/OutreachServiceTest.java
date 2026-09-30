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
        verify(ceo, times(2)).draftOutreach(anyString(), anyString(), contains("nombre exacto"), anyString());
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
