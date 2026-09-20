package com.aicompany.core.service;

import com.aicompany.core.config.AppProperties;
import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.ProductStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductStatusServiceTest {

    private final MissionMemoryService missionMemory = mock(MissionMemoryService.class);
    private final CustomerMemoryService customerMemory = mock(CustomerMemoryService.class);
    private final AppProperties appProperties = new AppProperties("Forjai", 50.0, 60);

    private final ProductStatusService service =
            new ProductStatusService(missionMemory, customerMemory, appProperties);

    private static AgentTask task(String agentId, String action, String status) {
        return new AgentTask("TASK-1", "MISSION-1", agentId, action, status, "{}", Instant.now());
    }

    @Test
    void defaultsToDiscoveryWithNoRealSignal() {
        when(missionMemory.tasks("MISSION-1")).thenReturn(List.of(
                task("sales", "MARKET_DISCOVERY", "COMPLETED")
        ));
        when(customerMemory.totalRevenueAndCost("MISSION-1")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-1")).thenReturn(0L);

        assertEquals(ProductStatus.DISCOVERY, service.resolve("MISSION-1"));
    }

    @Test
    void movesToDesignOnceOfferDesignIsCompleted() {
        when(missionMemory.tasks("MISSION-1")).thenReturn(List.of(
                task("product", "OFFER_DESIGN", "COMPLETED"),
                task("engineering", "DELIVERY_FEASIBILITY", "COMPLETED")
        ));
        when(customerMemory.totalRevenueAndCost("MISSION-1")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-1")).thenReturn(0L);

        assertEquals(ProductStatus.DESIGN, service.resolve("MISSION-1"));
    }

    @Test
    void deliveryFeasibilityAloneNeverMeansDesignOrDevelopment() {
        // Regla dura del pedido original: DELIVERY_FEASIBILITY (estudio de
        // factibilidad de engineering durante el discovery) no implica ni
        // DESIGN ni DEVELOPMENT.
        when(missionMemory.tasks("MISSION-1")).thenReturn(List.of(
                task("engineering", "DELIVERY_FEASIBILITY", "COMPLETED")
        ));
        when(customerMemory.totalRevenueAndCost("MISSION-1")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-1")).thenReturn(0L);

        assertEquals(ProductStatus.DISCOVERY, service.resolve("MISSION-1"));
    }

    @Test
    void qualityRiskReviewNeverCountsAsRealQa() {
        // Regla dura del pedido original: QUALITY_RISK_REVIEW (discovery de
        // qa) nunca implica ProductStatus.QA real.
        when(missionMemory.tasks("MISSION-1")).thenReturn(List.of(
                task("qa", "QUALITY_RISK_REVIEW", "COMPLETED")
        ));
        when(customerMemory.totalRevenueAndCost("MISSION-1")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-1")).thenReturn(0L);

        assertEquals(ProductStatus.DISCOVERY, service.resolve("MISSION-1"));
    }

    @Test
    void movesToMonetizingWithARealTransactionEvenWithZeroNetProfit() {
        when(missionMemory.tasks("MISSION-1")).thenReturn(List.of());
        when(customerMemory.totalRevenueAndCost("MISSION-1")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-1")).thenReturn(1L);

        assertEquals(ProductStatus.MONETIZING, service.resolve("MISSION-1"));
    }

    @Test
    void movesToBusinessSuccessWhenNetProfitExceedsSeedCapital() {
        when(missionMemory.tasks("MISSION-1")).thenReturn(List.of());
        when(customerMemory.totalRevenueAndCost("MISSION-1")).thenReturn(new double[]{200.0, 50.0});
        when(customerMemory.transactionCount("MISSION-1")).thenReturn(1L);

        assertEquals(ProductStatus.BUSINESS_SUCCESS, service.resolve("MISSION-1"));
    }
}
