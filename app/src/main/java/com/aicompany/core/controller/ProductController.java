package com.aicompany.core.controller;

import com.aicompany.core.model.CatalogStatus;
import com.aicompany.core.model.FinanceSummary;
import com.aicompany.core.model.ProductCommand;
import com.aicompany.core.model.ProductMissionsCommand;
import com.aicompany.core.model.ProductStatusCommand;
import com.aicompany.core.model.ProductView;
import com.aicompany.core.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/** Catálogo (spec 2026-09-28). El Command Center actúa como el fundador ("human"). */
@RestController
@RequestMapping("/api/company/products")
public class ProductController {

    private final ProductService products;

    public ProductController(ProductService products) {
        this.products = products;
    }

    @GetMapping
    public List<ProductView> list() {
        return products.list();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductView> view(@PathVariable String id) {
        return products.view(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ProductView create(@RequestBody ProductCommand command) {
        return products.create(command, ProductService.FOUNDER);
    }

    @PutMapping("/{id}")
    public ProductView update(@PathVariable String id, @RequestBody ProductCommand command) {
        return products.update(id, command, ProductService.FOUNDER);
    }

    @PutMapping("/{id}/status")
    public ProductView changeStatus(@PathVariable String id, @RequestBody ProductStatusCommand command) {
        var status = command.status() == null || command.status().isBlank()
                ? null : CatalogStatus.valueOf(command.status().strip().toUpperCase(Locale.ROOT));
        return products.changeStatus(id, status, command.reason(), ProductService.FOUNDER);
    }

    @PutMapping("/{id}/missions")
    public ProductView missions(@PathVariable String id, @RequestBody ProductMissionsCommand command) {
        return products.linkMissions(id, command.validatedBy(), command.builtBy(), ProductService.FOUNDER);
    }
}
