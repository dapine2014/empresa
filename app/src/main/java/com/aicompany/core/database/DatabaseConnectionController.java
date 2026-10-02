package com.aicompany.core.database;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Settings → Bases de datos (spec 2026-10-02 §1). Ningún endpoint devuelve la clave. */
@RestController
@RequestMapping("/api/company/databases")
public class DatabaseConnectionController {

    private final DatabaseConnectionService service;

    public DatabaseConnectionController(DatabaseConnectionService service) {
        this.service = service;
    }

    @GetMapping
    public List<DatabaseConnection> list() {
        return service.list();
    }

    @PostMapping
    public DatabaseConnection create(@RequestBody DatabaseConnectionCommand command) {
        return service.create(command);
    }

    @PutMapping("/{id}")
    public DatabaseConnection update(@PathVariable("id") String id, @RequestBody DatabaseConnectionCommand command) {
        return service.update(id, command);
    }

    @PostMapping("/{id}/test")
    public Map<String, String> test(@PathVariable("id") String id) {
        return Map.of("result", service.test(id));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable("id") String id) {
        service.delete(id);
    }
}
