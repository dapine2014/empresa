package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.model.CatalogStatus;
import com.aicompany.core.model.ProductChange;
import com.aicompany.core.model.ProductCommand;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Automatizaciones del catálogo (spec 2026-09-28 §6 A, decisión del fundador: los agentes pueden llevar un producto hasta
 * "listo para vender"). Todo lo decide Java con datos reales: la oferta de Luna crea una idea; una construcción terminada
 * intenta el paso a "listo" y, si falta algo, lo anota. Un producto pausado o retirado nunca se mueve solo.
 */
@Service
public class ProductAutomation {

    static final String PRODUCT_AGENT = "product";
    static final String SYSTEM = "system";
    private static final int MAX_NAME = 80;

    private final ProductService service;
    private final ProductMemoryService memory;

    public ProductAutomation(ProductService service, ProductMemoryService memory) {
        this.service = service;
        this.memory = memory;
    }

    public void ideaFromDiscovery(String missionId, AgentResult productResult) {
        if (productResult == null || productResult.recommendation() == null || productResult.recommendation().isBlank()) {
            return;
        }
        var offer = productResult.recommendation().strip();
        var existing = memory.ideaFromMission(missionId);
        if (existing.isPresent()) {
            service.update(existing.get(), new ProductCommand(null, offer, null, null, null, null, null, null, null, null,
                    "Oferta revisada en una nueva ronda de " + missionId), PRODUCT_AGENT);
            return;
        }
        var created = service.create(new ProductCommand(nameFrom(offer), offer, null, null, null, null, null, null, null,
                null, "Oferta propuesta por Luna en " + missionId), PRODUCT_AGENT);
        var id = created.product().id();
        memory.markSourceMission(id, missionId);
        service.linkMissions(id, List.of(missionId), null, PRODUCT_AGENT);
    }

    public void buildFinished(String missionId) {
        for (var id : memory.productsBuiltBy(missionId)) {
            var current = service.view(id).orElse(null);
            if (current == null) {
                continue;
            }
            var status = current.product().status();
            if (status == CatalogStatus.PAUSED || status == CatalogStatus.RETIRED || status == CatalogStatus.READY_TO_SELL) {
                continue;
            }
            if (status == CatalogStatus.IDEA) {
                service.changeStatus(id, CatalogStatus.IN_CONSTRUCTION, "Construcción terminada en " + missionId, SYSTEM);
                current = service.view(id).orElse(current);
            }
            if (current.missing().isEmpty()) {
                service.changeStatus(id, CatalogStatus.READY_TO_SELL,
                        "Cumple los requisitos tras la construcción de " + missionId, SYSTEM);
            } else {
                memory.addChange(id, new ProductChange(SYSTEM, "readiness", null, null,
                        "Construcción terminada en " + missionId + "; para venderse falta: "
                                + String.join(" ", current.missing()), Instant.now()));
            }
        }
    }

    /** Primera oración de la oferta, máximo 80 caracteres (determinista). */
    static String nameFrom(String offer) {
        var sentence = offer.split("(?<=[.!?])\\s+|\\n", 2)[0].replaceAll("[.!?]+$", "").strip();
        return sentence.length() <= MAX_NAME ? sentence : sentence.substring(0, MAX_NAME - 1).strip() + "…";
    }
}
