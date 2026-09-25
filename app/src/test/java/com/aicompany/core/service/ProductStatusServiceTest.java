package com.aicompany.core.service;

import com.aicompany.core.model.AgentTask;
import com.aicompany.core.model.PolicyKey;
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
    private final CompanyPolicyService companyPolicyService = defaultCompanyPolicyService();

    private static CompanyPolicyService defaultCompanyPolicyService() {
        var mock = mock(CompanyPolicyService.class);
        when(mock.activeValue(PolicyKey.SEED_CAPITAL_USD)).thenReturn(50.0);
        return mock;
    }

    private final ProductStatusService service =
            new ProductStatusService(missionMemory, customerMemory, companyPolicyService);

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
        // isBusinessSuccess short-circuita resolve() antes de que
        // isMonetizing (que llama transactionCount) se evalúe -- no hace
        // falta stubear transactionCount para este caso.

        assertEquals(ProductStatus.BUSINESS_SUCCESS, service.resolve("MISSION-1"));
    }

    @Test
    void doesNotReachBusinessSuccessWhenNetProfitExactlyEqualsSeedCapital() {
        // Boundary del pedido original: la regla real es netProfit >
        // seedCapital (estrictamente mayor), no >=. Capital semilla es
        // 50.0 en el fixture de AppProperties de esta clase de test.
        when(missionMemory.tasks("MISSION-1")).thenReturn(List.of());
        when(customerMemory.totalRevenueAndCost("MISSION-1")).thenReturn(new double[]{50.0, 0.0});
        when(customerMemory.transactionCount("MISSION-1")).thenReturn(0L);

        assertEquals(ProductStatus.DISCOVERY, service.resolve("MISSION-1"));
    }

    private static AgentTask devTask(String kind, String status, String commitSha) {
        return new AgentTask("MISSION-9-BACKEND", "MISSION-9", "backend", "GAME_LOGIC", status, "{}",
                Instant.parse("2026-09-24T00:00:00Z"), kind, "/data/forjai-products/MISSION-9", commitSha,
                List.of("web/game/main.js"), null, null);
    }

    @Test
    void committedWorkTaskMeansDevelopment() {
        when(missionMemory.tasks("MISSION-9")).thenReturn(List.of(devTask("WORK", "COMPLETED", "a".repeat(40))));
        when(customerMemory.totalRevenueAndCost("MISSION-9")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-9")).thenReturn(0L);

        assertEquals(ProductStatus.DEVELOPMENT, service.resolve("MISSION-9"));
    }

    @Test
    void workTaskWithoutCommitIsNotDevelopment() {
        when(missionMemory.tasks("MISSION-9")).thenReturn(List.of(devTask("WORK", "FAILED", null)));
        when(customerMemory.totalRevenueAndCost("MISSION-9")).thenReturn(new double[]{0.0, 0.0});
        when(customerMemory.transactionCount("MISSION-9")).thenReturn(0L);

        assertEquals(ProductStatus.DISCOVERY, service.resolve("MISSION-9"));
    }
}
