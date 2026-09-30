package com.aicompany.core.prospecting;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.evidence.WebPageFetcher;
import com.aicompany.core.model.CatalogProduct;
import com.aicompany.core.model.CatalogStatus;
import com.aicompany.core.model.PolicyKey;
import com.aicompany.core.model.ProductView;
import com.aicompany.core.service.AlertMailService;
import com.aicompany.core.service.CeoService;
import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.CompanyPolicyService;
import com.aicompany.core.service.ProductService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Spec búsqueda de prospectos §1: la corrida diaria. */
class ProspectingServiceTest {

    private final ProspectingMemoryService memory = mock(ProspectingMemoryService.class);
    private final ProductService products = mock(ProductService.class);
    private final CeoService ceo = mock(CeoService.class);
    private final CompanyMemoryService companyMemory = mock(CompanyMemoryService.class);
    private final CompanyPolicyService policies = mock(CompanyPolicyService.class);
    private final WebPageFetcher fetcher = mock(WebPageFetcher.class);
    private final CompanyEventPublisher events = mock(CompanyEventPublisher.class);
    private final AlertMailService mail = mock(AlertMailService.class);
    private final ProspectingService service = new ProspectingService(memory, products, ceo, companyMemory, policies,
            fetcher, events, mail, "qwen3:8b");
    private final List<ProspectingRun> saved = new ArrayList<>();

    {
        when(policies.activeValue(PolicyKey.PROSPECTING_ENABLED)).thenReturn(1.0);
        when(policies.activeValue(PolicyKey.MAX_PROSPECTS_PER_DAY)).thenReturn(10.0);
        when(companyMemory.agentModel(anyString(), anyString())).thenReturn("nvidia-discovery:m");
        when(memory.stats()).thenReturn(List.of());
        when(memory.strategies()).thenReturn(List.of());
        when(memory.knownDomains(anyString())).thenReturn(Set.of());
        doAnswer(inv -> saved.add(inv.getArgument(0))).when(memory).saveRun(any());
        when(fetcher.fetchFollowingRedirects("https://acme.com", 3)).thenReturn("Acme Studio");
        when(fetcher.fetchFollowingRedirects("https://acme.com/c", 3)).thenReturn("hola@acme.com");
    }

    private static CatalogProduct product(String id, CatalogStatus status, String target, List<String> markets,
                                          List<String> languages) {
        return new CatalogProduct(id, "Pack " + id, "desc", "SERVICE", target, 39, false, 5, "48 h", markets, languages,
                status, null, "product", Instant.now(), Instant.now(), List.of(), List.of());
    }

    private void catalog(CatalogProduct... ps) {
        when(products.list()).thenReturn(java.util.Arrays.stream(ps).map(p -> new ProductView(p, List.of(), List.of())).toList());
    }

    private static ProspectCandidate acme() {
        return new ProspectCandidate("Acme Studio", "https://acme.com", "hola@acme.com", "", "https://acme.com/c",
                "Publican mucho contenido");
    }

    private ProspectingRun lastRun() {
        return saved.get(saved.size() - 1);
    }

    @Test
    void itDoesNothingWhenOff() {
        when(policies.activeValue(PolicyKey.PROSPECTING_ENABLED)).thenReturn(0.0);
        catalog(product("P1", CatalogStatus.READY_TO_SELL, "Creadores", List.of("WORLDWIDE"), List.of("en")));

        assertTrue(service.runIfDue().isEmpty());
        verifyNoInteractions(ceo);
    }

    @Test
    void itDoesNothingWithoutProductsReadyToSell() {
        catalog(product("P1", CatalogStatus.IN_CONSTRUCTION, "Creadores", List.of("WORLDWIDE"), List.of("en")));

        assertThrows(IllegalArgumentException.class, service::runNow);
        verifyNoInteractions(ceo);
    }

    @Test
    void itRunsOnlyOncePerDay() {
        when(memory.ranOn(any())).thenReturn(true);
        catalog(product("P1", CatalogStatus.READY_TO_SELL, "Creadores", List.of("WORLDWIDE"), List.of("en")));

        assertTrue(service.runIfDue().isEmpty());
        verifyNoInteractions(ceo);
    }

    @Test
    void aValidProspectIsSavedAndTheRunCompletes() {
        catalog(product("P1", CatalogStatus.READY_TO_SELL, "Creadores", List.of("WORLDWIDE"), List.of("en")));
        when(ceo.searchProspects(anyString(), any(), any(), any(), anyString()))
                .thenReturn(new ProspectBatch(List.of(acme())));

        var run = service.runNow();

        assertEquals("COMPLETED", run.status());
        assertEquals(1, run.found());
        assertEquals(1, run.valid());
        assertEquals("BASE-DIRECTORIES", run.strategyId());
        verify(memory).saveProspect(eq("P1"), eq(acme()), eq("acme.com"), eq("BASE-DIRECTORIES"), eq(run.id()));
        verify(events).publish(eq("EMPRESA_PROSPECTING_RUN_COMPLETED"), isNull(), isNull(), eq("sales"), anyMap());
        verify(mail).send(contains("prospectos"), contains("Acme Studio"), eq(false));
    }

    @Test
    void searchScopeComesFromTheSheet() {
        catalog(product("P1", CatalogStatus.READY_TO_SELL, "Creadores", List.of("US"), List.of()));
        when(ceo.searchProspects(anyString(), any(), any(), any(), anyString())).thenReturn(new ProspectBatch(List.of()));

        service.runNow();

        verify(ceo).searchProspects(contains("Creadores"), any(), eq("US"), eq("en"), eq("nvidia-discovery:m"));

        catalog(product("P2", CatalogStatus.READY_TO_SELL, "Creadores", List.of("WORLDWIDE"), List.of("es")));
        service.runNow();

        verify(ceo).searchProspects(anyString(), any(), isNull(), eq("es"), anyString());
    }

    @Test
    void anIncompleteSheetFailsWithoutCallingTheModel() {
        catalog(product("P1", CatalogStatus.READY_TO_SELL, " ", List.of("WORLDWIDE"), List.of("en")));

        var run = service.runNow();

        assertEquals("FAILED", run.status());
        assertTrue(run.error().contains("cliente objetivo"), run.error());
        verifyNoInteractions(ceo);
    }

    @Test
    void theDailyLimitCapsWhatIsSaved() {
        when(policies.activeValue(PolicyKey.MAX_PROSPECTS_PER_DAY)).thenReturn(1.0);
        when(memory.prospectsOn(any())).thenReturn(1);
        catalog(product("P1", CatalogStatus.READY_TO_SELL, "Creadores", List.of("WORLDWIDE"), List.of("en")));
        when(ceo.searchProspects(anyString(), any(), any(), any(), anyString()))
                .thenReturn(new ProspectBatch(List.of(acme())));

        var run = service.runNow();

        assertEquals(0, run.valid());
        assertTrue(run.rejections().stream().anyMatch(r -> r.contains("límite")), run.rejections().toString());
        verify(memory, never()).saveProspect(anyString(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void aModelFailureLeavesTheRunFailed() {
        catalog(product("P1", CatalogStatus.READY_TO_SELL, "Creadores", List.of("WORLDWIDE"), List.of("en")));
        when(ceo.searchProspects(anyString(), any(), any(), any(), anyString()))
                .thenThrow(new IllegalStateException("Modelo remoto no responde"));

        var run = service.runNow();

        assertEquals("FAILED", run.status());
        assertTrue(run.error().contains("no responde"));
        verify(events).publish(eq("EMPRESA_PROSPECTING_RUN_FAILED"), isNull(), isNull(), eq("sales"), anyMap());
    }

    @Test
    void productsRotateByLongestWithoutARun() {
        catalog(product("P1", CatalogStatus.READY_TO_SELL, "Creadores", List.of("WORLDWIDE"), List.of("en")),
                product("P2", CatalogStatus.READY_TO_SELL, "Agencias", List.of("WORLDWIDE"), List.of("en")));
        when(memory.stats()).thenReturn(List.of(new RunStat("P1", "BASE-DIRECTORIES", 2, Instant.parse("2026-09-29T08:00:00Z"))));
        when(ceo.searchProspects(anyString(), any(), any(), any(), anyString())).thenReturn(new ProspectBatch(List.of()));

        var run = service.runNow();

        assertEquals("P2", run.productId());
    }

    @Test
    void approvedStrategiesJoinTheCatalogButPendingOnesDoNot() {
        when(memory.strategies()).thenReturn(List.of(
                new StoredStrategy("S1", "Podcasts", "d", "h", "APPROVED", "growth-content", Instant.now(), Instant.now()),
                new StoredStrategy("S2", "Eventos", "d", "h", "PENDING_APPROVAL", "growth-content", Instant.now(), null)));

        var ids = service.options().stream().map(StrategyOption::id).toList();

        assertTrue(ids.contains("S1"));
        assertFalse(ids.contains("S2"));
        assertEquals(5, ids.size());
    }
}
