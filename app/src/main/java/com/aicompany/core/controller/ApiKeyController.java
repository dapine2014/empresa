package com.aicompany.core.controller;

import com.aicompany.core.service.ApiKeyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Keys de modelos editables desde Settings (2026-09-29): la lista solo muestra una pista (últimos 4 caracteres), el
 * origen y quién usa cada proveedor; nunca devuelve una key.
 */
@RestController
@RequestMapping("/api/company/api-keys")
public class ApiKeyController {

    private final ApiKeyService service;

    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    @GetMapping
    public List<ApiKeyService.ApiKeyStatus> list() {
        return service.list();
    }

    @PutMapping("/{provider}")
    public ApiKeyService.ApiKeyStatus update(@PathVariable("provider") String provider, @RequestBody Map<String, String> body) {
        return service.update(provider, body.get("apiKey"), "human");
    }
}
