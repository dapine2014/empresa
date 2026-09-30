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
