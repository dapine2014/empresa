package com.aicompany.core.service;

import com.aicompany.core.agent.model.AgentResult;
import com.aicompany.core.model.CatalogProduct;
import com.aicompany.core.model.CatalogStatus;
import com.aicompany.core.model.ProductView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec catálogo §6 A (2026-09-28): automatizaciones deterministas, en Java, sin decisión del modelo. */
class ProductAutomationTest {

    private final ProductService service = mock(ProductService.class);
    private final ProductMemoryService memory = mock(ProductMemoryService.class);
    private final ProductAutomation automation = new ProductAutomation(service, memory);

    private static AgentResult luna(String recommendation) {
        return new AgentResult("product", "OFFER_DESIGN", "NOT_VALIDATED", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), recommendation, 0.6);
    }

    private static ProductView view(String id, CatalogStatus status, List<String> missing) {
        return new ProductView(new CatalogProduct(id, "Landing", "d", "SOFTWARE", "Pymes", 120, false, 10, null,
                List.of("WORLDWIDE"), List.of("en", "es"), status, null, "product", Instant.now(), Instant.now(),
                List.of("MISSION-1"), List.of("MISSION-2")), missing, List.of());
    }

    @Test
    void aDiscoveryWithAnOfferCreatesAnIdeaLinkedToTheMission() {
        when(memory.ideaFromMission("MISSION-1")).thenReturn(Optional.empty());
        when(service.create(any(), eq("product"))).thenReturn(view("P1", CatalogStatus.IDEA, List.of()));

        automation.ideaFromDiscovery("MISSION-1", luna("Vender configuración de Google Business Profile a pymes. "
                + "Precio de 150 USD por proyecto."));

        verify(service).create(argThat(c -> c.name().equals("Vender configuración de Google Business Profile a pymes")
                && c.description().contains("150 USD")), eq("product"));
        verify(memory).markSourceMission("P1", "MISSION-1");
        verify(service).linkMissions("P1", List.of("MISSION-1"), null, "product");
    }

    @Test
    void anEvidenceRoundUpdatesTheSameIdeaInsteadOfDuplicatingIt() {
        when(memory.ideaFromMission("MISSION-1")).thenReturn(Optional.of("P1"));

        automation.ideaFromDiscovery("MISSION-1", luna("Oferta revisada con precios reales."));

        verify(service, never()).create(any(), any());
        verify(service).update(eq("P1"), argThat(c -> c.description().contains("precios reales")), eq("product"));
    }

    @Test
    void withoutAnOfferNothingIsCreated() {
        automation.ideaFromDiscovery("MISSION-1", luna("  "));
        automation.ideaFromDiscovery("MISSION-1", null);

        verifyNoInteractions(service);
    }

    @Test
    void aVerifiedBuildMovesAnIdeaToReadyWhenEverythingIsMet() {
        when(memory.productsBuiltBy("MISSION-2")).thenReturn(List.of("P1"));
        when(service.view("P1")).thenReturn(Optional.of(view("P1", CatalogStatus.IDEA, List.of())),
                Optional.of(view("P1", CatalogStatus.IN_CONSTRUCTION, List.of())));

        automation.buildFinished("MISSION-2");

        verify(service).changeStatus(eq("P1"), eq(CatalogStatus.IN_CONSTRUCTION), anyString(), eq("system"));
        verify(service).changeStatus(eq("P1"), eq(CatalogStatus.READY_TO_SELL), anyString(), eq("system"));
    }

    @Test
    void withMissingRequirementsItStaysInConstructionAndSaysWhatIsMissing() {
        when(memory.productsBuiltBy("MISSION-2")).thenReturn(List.of("P1"));
        when(service.view("P1")).thenReturn(Optional.of(view("P1", CatalogStatus.IN_CONSTRUCTION,
                List.of("Falta el precio (o marcarlo a cotizar)."))));

        automation.buildFinished("MISSION-2");

        verify(service, never()).changeStatus(eq("P1"), eq(CatalogStatus.READY_TO_SELL), anyString(), anyString());
        verify(memory).addChange(eq("P1"), argThat(c -> "system".equals(c.actor()) && c.reason().contains("Falta el precio")));
    }

    @Test
    void aPausedOrRetiredProductNeverMovesOnItsOwn() {
        when(memory.productsBuiltBy("MISSION-2")).thenReturn(List.of("P1", "P2"));
        when(service.view("P1")).thenReturn(Optional.of(view("P1", CatalogStatus.PAUSED, List.of())));
        when(service.view("P2")).thenReturn(Optional.of(view("P2", CatalogStatus.RETIRED, List.of())));

        automation.buildFinished("MISSION-2");

        verify(service, never()).changeStatus(any(), any(), any(), any());
    }
}
