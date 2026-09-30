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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Spec búsqueda de prospectos §1: una corrida por día (≥ 08:00 UTC) para un producto READY_TO_SELL, con la estrategia que
 * elige Java; Sofía investiga y Java valida y guarda. Nunca propaga excepciones: la corrida queda FAILED con el motivo.
 */
@Service
public class ProspectingService {

    private static final Logger log = LoggerFactory.getLogger(ProspectingService.class);
    static final int RUN_HOUR_UTC = 8;

    private final ProspectingMemoryService memory;
    private final ProductService products;
    private final CeoService ceo;
    private final CompanyMemoryService companyMemory;
    private final CompanyPolicyService policies;
    private final WebPageFetcher fetcher;
    private final CompanyEventPublisher events;
    private final AlertMailService mail;
    private final String defaultModel;

    public ProspectingService(ProspectingMemoryService memory, ProductService products, CeoService ceo,
                              CompanyMemoryService companyMemory, CompanyPolicyService policies, WebPageFetcher fetcher,
                              CompanyEventPublisher events, AlertMailService mail,
                              @Value("${ollama.agent-model}") String defaultModel) {
        this.memory = memory;
        this.products = products;
        this.ceo = ceo;
        this.companyMemory = companyMemory;
        this.policies = policies;
        this.fetcher = fetcher;
        this.events = events;
        this.mail = mail;
        this.defaultModel = defaultModel;
    }

    public boolean enabled() {
        return policies.activeValue(PolicyKey.PROSPECTING_ENABLED) >= 1;
    }

    /** Catálogo base + estrategias aprobadas por el fundador. */
    public List<StrategyOption> options() {
        return Stream.concat(BaseStrategy.options().stream(), memory.strategies().stream()
                        .filter(s -> "APPROVED".equals(s.status()))
                        .map(s -> new StrategyOption(s.id(), s.name(), s.description(), s.searchHints())))
                .toList();
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 180_000)
    public synchronized Optional<ProspectingRun> runIfDue() {
        var now = Instant.now().atZone(ZoneOffset.UTC);
        if (!enabled() || now.getHour() < RUN_HOUR_UTC || memory.ranOn(now.toLocalDate()) || ready().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(runNow());
    }

    /** Acción del fundador ("correr ahora"): ignora el interruptor y la regla de una por día. */
    public synchronized ProspectingRun runNow() {
        var ready = ready();
        if (ready.isEmpty()) {
            throw new IllegalArgumentException("No hay productos listos para vender: no hay a quién buscarle clientes.");
        }
        var stats = memory.stats();
        var product = ready.stream().min(Comparator.comparing((CatalogProduct p) -> lastRun(stats, p.id()))
                .thenComparing(CatalogProduct::id)).orElseThrow();
        var strategy = StrategySelector.choose(options(), product.id(), stats);
        var id = "PROSPECTING-" + UUID.randomUUID();
        var started = Instant.now();
        try {
            if (product.targetCustomer() == null || product.targetCustomer().isBlank()) {
                return fail(id, product, strategy, started, "La ficha de " + product.name() + " no tiene cliente objetivo.");
            }
            var runsForProduct = (int) stats.stream().filter(s -> product.id().equals(s.productId())).count();
            var batch = ceo.searchProspects(sheet(product), strategy, country(product), language(product, runsForProduct),
                    companyMemory.agentModel("sales", defaultModel));
            var validation = new ProspectValidator(fetcher::fetch).validate(batch.prospects(), memory.knownDomains(product.id()));
            var rejections = new ArrayList<>(validation.rejections());
            var room = (int) policies.activeValue(PolicyKey.MAX_PROSPECTS_PER_DAY)
                    - memory.prospectsOn(LocalDate.now(ZoneOffset.UTC));
            var saved = new ArrayList<ProspectCandidate>();
            for (var c : validation.valid()) {
                if (saved.size() >= room) {
                    rejections.add(c.name() + ": límite diario de prospectos alcanzado");
                    continue;
                }
                memory.saveProspect(product.id(), c, ProspectValidator.domain(c.url()), strategy.id(), id);
                saved.add(c);
            }
            var run = new ProspectingRun(id, product.id(), strategy.id(), "COMPLETED", batch.prospects().size(),
                    saved.size(), rejections, null, started, Instant.now());
            memory.saveRun(run);
            events.publish("EMPRESA_PROSPECTING_RUN_COMPLETED", null, null, "sales", Map.of("runId", id,
                    "productId", product.id(), "strategy", strategy.id(), "found", run.found(), "valid", run.valid()));
            if (!saved.isEmpty()) {
                mail.send("Forjai: " + saved.size() + " prospectos nuevos para " + product.name(),
                        "Estrategia: " + strategy.name() + ".\n\n" + saved.stream()
                                .map(c -> "- " + c.name() + " (" + c.url() + "): " + c.fitReason())
                                .collect(Collectors.joining("\n"))
                                + "\n\nContactarlos sigue siendo decisión tuya. Los ves en la pantalla Prospectos.", false);
            }
            log.info("PROSPECTING run={} product={} strategy={} found={} valid={}", id, product.id(), strategy.id(),
                    run.found(), run.valid());
            return run;
        } catch (Exception ex) {
            log.error("PROSPECTING run {} failed", id, ex);
            return fail(id, product, strategy, started, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private ProspectingRun fail(String id, CatalogProduct product, StrategyOption strategy, Instant started, String error) {
        var run = new ProspectingRun(id, product.id(), strategy.id(), "FAILED", 0, 0, List.of(), error, started, Instant.now());
        memory.saveRun(run);
        events.publish("EMPRESA_PROSPECTING_RUN_FAILED", null, null, "sales",
                Map.of("runId", id, "productId", product.id(), "error", error));
        return run;
    }

    private List<CatalogProduct> ready() {
        return products.list().stream().map(ProductView::product)
                .filter(p -> p.status() == CatalogStatus.READY_TO_SELL).toList();
    }

    private static Instant lastRun(List<RunStat> stats, String productId) {
        return stats.stream().filter(s -> productId.equals(s.productId())).map(RunStat::startedAt)
                .max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
    }

    static String country(CatalogProduct p) {
        var markets = p.markets() == null ? List.<String>of() : p.markets();
        return markets.isEmpty() || markets.contains("WORLDWIDE") ? null : markets.get(0);
    }

    static String language(CatalogProduct p, int runsForProduct) {
        var languages = p.languages() == null ? List.<String>of() : p.languages();
        return languages.isEmpty() ? "en" : languages.get(runsForProduct % languages.size());
    }

    private static String sheet(CatalogProduct p) {
        return "Nombre: " + p.name() + "\nDescripción: " + p.description() + "\nTipo: " + p.kind()
                + "\nCliente objetivo: " + p.targetCustomer()
                + "\nPrecio: " + (p.priceOnRequest() ? "a cotizar" : "US$" + p.priceUsd())
                + "\nMercados: " + String.join(", ", p.markets() == null ? List.of() : p.markets())
                + "\nIdiomas: " + String.join(", ", p.languages() == null ? List.of() : p.languages())
                + (p.delivery() == null || p.delivery().isBlank() ? "" : "\nEntrega: " + p.delivery());
    }
}
