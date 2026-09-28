package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.CatalogProduct;
import com.aicompany.core.model.CatalogStatus;
import com.aicompany.core.model.ProductCommand;
import com.aicompany.core.model.ProductEvidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec catálogo (2026-09-28): transiciones, reglas de actor e historial, todo en Java. */
class ProductServiceTest {

    private final ProductMemoryService memory = mock(ProductMemoryService.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final ProductService service = new ProductService(memory, events);

    {
        when(memory.history(anyString())).thenReturn(List.of());
        when(memory.evidence(any())).thenReturn(new ProductEvidence(false, false));
    }

    private static CatalogProduct product(CatalogStatus status, double price, double cost) {
        return new CatalogProduct("P1", "Landing", "Landing para pymes", "SOFTWARE", "Pymes", price, false, cost, null,
                List.of("WORLDWIDE"), List.of("en", "es"), status, null, "human", Instant.now(), Instant.now(),
                List.of("MISSION-1"), List.of("MISSION-2"));
    }

    private static CatalogProduct withPause(CatalogStatus before) {
        var p = product(CatalogStatus.PAUSED, 120, 10);
        return new CatalogProduct(p.id(), p.name(), p.description(), p.kind(), p.targetCustomer(), p.priceUsd(),
                p.priceOnRequest(), p.estimatedCostUsd(), p.delivery(), p.markets(), p.languages(), CatalogStatus.PAUSED,
                before, p.createdBy(), p.createdAt(), p.updatedAt(), p.validatedBy(), p.builtBy());
    }

    private static CatalogProduct named(String name) {
        var p = product(CatalogStatus.IDEA, 0, 0);
        return new CatalogProduct(name.toUpperCase().replace(' ', '-'), name, p.description(), p.kind(), p.targetCustomer(),
                0, false, 0, null, p.markets(), p.languages(), CatalogStatus.IDEA, null, "human", p.createdAt(),
                p.updatedAt(), List.of(), List.of());
    }

    @Test
    void createDefaultsToIdeaWorldwideAndRecordsTheEvent() {
        var view = service.create(new ProductCommand("Landing", "Landing para pymes", "SOFTWARE", null, null, null, null,
                null, null, null, null), "human");

        assertEquals(CatalogStatus.IDEA, view.product().status());
        assertEquals(List.of("WORLDWIDE"), view.product().markets());
        assertEquals(List.of("en", "es"), view.product().languages());
        verify(memory).create(any());
        verify(events).publish(eq("EMPRESA_PRODUCT_CREATED"), isNull(), isNull(), eq("human"), anyMap());
    }

    @Test
    void aProductNeedsAName() {
        assertThrows(IllegalArgumentException.class, () -> service.create(new ProductCommand(" ", null, null, null, null,
                null, null, null, null, null, null), "human"));
    }

    @Test
    void readyIsRejectedWithTheMissingRequirements() {
        when(memory.find("P1")).thenReturn(Optional.of(product(CatalogStatus.IN_CONSTRUCTION, 0, 0)));

        var ex = assertThrows(IllegalArgumentException.class,
                () -> service.changeStatus("P1", CatalogStatus.READY_TO_SELL, "listo", "engineering"));

        assertTrue(ex.getMessage().contains("evidencia web"), ex.getMessage());
        verify(memory, never()).save(any());
    }

    @Test
    void anAgentCanMarkReadyWhenEverythingIsMet() {
        when(memory.find("P1")).thenReturn(Optional.of(product(CatalogStatus.IN_CONSTRUCTION, 120, 10)));
        when(memory.evidence(any())).thenReturn(new ProductEvidence(true, true));

        var view = service.changeStatus("P1", CatalogStatus.READY_TO_SELL, "Construcción verificada", "system");

        assertEquals(CatalogStatus.READY_TO_SELL, view.product().status());
        verify(memory).addChange(eq("P1"), argThat(c -> "status".equals(c.field()) && "system".equals(c.actor())));
        verify(events).publish(eq("EMPRESA_PRODUCT_STATUS_CHANGED"), isNull(), isNull(), eq("system"), anyMap());
    }

    @Test
    void onlyTheFounderPausesOrRetires() {
        when(memory.find("P1")).thenReturn(Optional.of(product(CatalogStatus.READY_TO_SELL, 120, 10)));

        assertThrows(IllegalArgumentException.class, () -> service.changeStatus("P1", CatalogStatus.PAUSED, "x", "sales"));
        assertThrows(IllegalArgumentException.class, () -> service.changeStatus("P1", CatalogStatus.RETIRED, "x", "system"));

        var paused = service.changeStatus("P1", CatalogStatus.PAUSED, "Sin capacidad", "human");
        assertEquals(CatalogStatus.PAUSED, paused.product().status());
        assertEquals(CatalogStatus.READY_TO_SELL, paused.product().statusBeforePause());
    }

    @Test
    void resumingGoesBackToTheStateBeforeThePause() {
        when(memory.find("P1")).thenReturn(Optional.of(withPause(CatalogStatus.READY_TO_SELL)));
        when(memory.evidence(any())).thenReturn(new ProductEvidence(true, true));

        assertEquals(CatalogStatus.READY_TO_SELL, service.changeStatus("P1", null, "Reanudar", "human").product().status());
        assertThrows(IllegalArgumentException.class, () -> service.changeStatus("P1", null, "x", "product"));
    }

    @Test
    void aPausedProductDoesNotMoveExceptByTheFounder() {
        when(memory.find("P1")).thenReturn(Optional.of(withPause(CatalogStatus.IN_CONSTRUCTION)));

        assertThrows(IllegalArgumentException.class,
                () -> service.changeStatus("P1", CatalogStatus.READY_TO_SELL, "x", "system"));
    }

    @Test
    void editingAReadyProductBelowCostSendsItBackToConstruction() {
        when(memory.find("P1")).thenReturn(Optional.of(product(CatalogStatus.READY_TO_SELL, 120, 10)));
        when(memory.evidence(any())).thenReturn(new ProductEvidence(true, true));

        var view = service.update("P1", new ProductCommand(null, null, null, null, 5.0, null, null, null, null, null,
                "rebaja"), "human");

        assertEquals(CatalogStatus.IN_CONSTRUCTION, view.product().status());
        verify(memory).addChange(eq("P1"), argThat(c -> "priceUsd".equals(c.field()) && "5.0".equals(c.to())));
        verify(memory).addChange(eq("P1"), argThat(c -> "status".equals(c.field()) && c.reason().contains("margen")));
    }

    @Test
    void aRetiredProductCanOnlyBeReactivatedToIdeaByTheFounder() {
        when(memory.find("P1")).thenReturn(Optional.of(product(CatalogStatus.RETIRED, 120, 10)));

        assertThrows(IllegalArgumentException.class,
                () -> service.changeStatus("P1", CatalogStatus.READY_TO_SELL, "x", "human"));
        assertThrows(IllegalArgumentException.class, () -> service.changeStatus("P1", CatalogStatus.IDEA, "x", "product"));
        assertEquals(CatalogStatus.IDEA, service.changeStatus("P1", CatalogStatus.IDEA, "Reactivar", "human").product().status());
    }

    @Test
    void findByNameIgnoresCaseAndAccents() {
        when(memory.all()).thenReturn(List.of(named("Asesoría Contable"), named("Landing")));

        assertEquals("Asesoría Contable", service.findByName("asesoria").get(0).name());
    }
}
