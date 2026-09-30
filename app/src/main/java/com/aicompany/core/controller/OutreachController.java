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
