package com.aicompany.core.controller;

import com.aicompany.core.service.AutonomyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Spec modo automático (2026-09-29): interruptores del Dashboard y conteos de "esperando tu decisión". */
@RestController
@RequestMapping("/api/company/autonomy")
public class AutonomyController {

    private final AutonomyService service;

    public AutonomyController(AutonomyService service) {
        this.service = service;
    }

    @GetMapping
    public AutonomyService.AutonomyView view() {
        return service.view();
    }

    @PutMapping
    public AutonomyService.AutonomyView update(@RequestBody AutonomyService.AutonomyCommand command) {
        return service.update(command, "el Dashboard");
    }
}
