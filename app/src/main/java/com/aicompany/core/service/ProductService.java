package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.CatalogProduct;
import com.aicompany.core.model.CatalogStatus;
import com.aicompany.core.model.ProductChange;
import com.aicompany.core.model.ProductCommand;
import com.aicompany.core.model.ProductView;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Catálogo de productos y servicios (spec 2026-09-28). Decisión del fundador: los agentes pueden llevar un producto hasta
 * "listo para vender" (si Java verifica los requisitos); solo el fundador ("human") pausa, reanuda, retira y reactiva.
 * Cada cambio queda en un historial inmutable.
 */
@Service
public class ProductService {

    public static final String FOUNDER = "human";
    private static final Set<CatalogStatus> WORKING = Set.of(CatalogStatus.IDEA, CatalogStatus.IN_CONSTRUCTION,
            CatalogStatus.READY_TO_SELL);

    private final ProductMemoryService memory;
    private final CompanyEventPublisher events;

    public ProductService(ProductMemoryService memory, CompanyEventPublisher events) {
        this.memory = memory;
        this.events = events;
    }

    public List<ProductView> list() {
        return memory.all().stream().map(this::view).toList();
    }

    public Optional<ProductView> view(String id) {
        return memory.find(id).map(this::view);
    }

    public List<CatalogProduct> findByName(String text) {
        var key = normalize(text);
        return memory.all().stream().filter(p -> normalize(p.name()).contains(key) || p.id().equalsIgnoreCase(text.strip()))
                .toList();
    }

    public ProductView create(ProductCommand c, String actor) {
        if (c.name() == null || c.name().isBlank()) {
            throw new IllegalArgumentException("El producto necesita un nombre.");
        }
        var now = Instant.now();
        var product = new CatalogProduct("PRODUCT-" + UUID.randomUUID(), c.name().strip(), text(c.description()),
                kind(c.kind()), text(c.targetCustomer()), amount(c.priceUsd(), "precio"), Boolean.TRUE.equals(c.priceOnRequest()),
                amount(c.estimatedCostUsd(), "costo estimado"), text(c.delivery()), listOr(c.markets(), List.of("WORLDWIDE")),
                listOr(c.languages(), List.of("en", "es")), CatalogStatus.IDEA, null, actor, now, now, List.of(), List.of());
        memory.create(product);
        memory.addChange(product.id(), new ProductChange(actor, "status", null, CatalogStatus.IDEA.name(),
                c.reason() == null || c.reason().isBlank() ? "Producto creado" : c.reason(), now));
        events.publish("EMPRESA_PRODUCT_CREATED", null, null, actor,
                Map.of("productId", product.id(), "name", product.name(), "kind", product.kind()));
        return view(product);
    }

    public ProductView update(String id, ProductCommand c, String actor) {
        var before = require(id);
        var after = new CatalogProduct(before.id(),
                c.name() == null || c.name().isBlank() ? before.name() : c.name().strip(),
                c.description() == null ? before.description() : c.description().strip(),
                c.kind() == null ? before.kind() : kind(c.kind()),
                c.targetCustomer() == null ? before.targetCustomer() : c.targetCustomer().strip(),
                c.priceUsd() == null ? before.priceUsd() : amount(c.priceUsd(), "precio"),
                c.priceOnRequest() == null ? before.priceOnRequest() : c.priceOnRequest(),
                c.estimatedCostUsd() == null ? before.estimatedCostUsd() : amount(c.estimatedCostUsd(), "costo estimado"),
                c.delivery() == null ? before.delivery() : c.delivery().strip(),
                c.markets() == null || c.markets().isEmpty() ? before.markets() : c.markets(),
                c.languages() == null || c.languages().isEmpty() ? before.languages() : c.languages(),
                before.status(), before.statusBeforePause(), before.createdBy(), before.createdAt(), Instant.now(),
                before.validatedBy(), before.builtBy());
        var now = Instant.now();
        var reason = c.reason() == null || c.reason().isBlank() ? "Edición" : c.reason();
        record(before, after, actor, reason, now);
        after = demoteIfNoLongerReady(after, actor, now);
        memory.save(after);
        events.publish("EMPRESA_PRODUCT_UPDATED", null, null, actor, Map.of("productId", id));
        return view(after);
    }

    public ProductView linkMissions(String id, List<String> validatedBy, List<String> builtBy, String actor) {
        var before = require(id);
        var all = new ArrayList<String>();
        if (validatedBy != null) all.addAll(validatedBy);
        if (builtBy != null) all.addAll(builtBy);
        for (var missionId : all) {
            if (!memory.missionExists(missionId)) {
                throw new IllegalArgumentException("No existe la misión " + missionId + ".");
            }
        }
        memory.link(id, validatedBy == null ? before.validatedBy() : validatedBy,
                builtBy == null ? before.builtBy() : builtBy);
        var now = Instant.now();
        memory.addChange(id, new ProductChange(actor, "missions", String.valueOf(before.validatedBy()) + " / "
                + before.builtBy(), validatedBy + " / " + builtBy, "Misiones asociadas", now));
        var after = require(id);
        var demoted = demoteIfNoLongerReady(after, actor, now);
        if (demoted != after) {
            memory.save(demoted);
        }
        events.publish("EMPRESA_PRODUCT_UPDATED", null, null, actor, Map.of("productId", id));
        return view(demoted);
    }

    /** to == null significa reanudar un producto pausado. */
    public ProductView changeStatus(String id, CatalogStatus to, String reason, String actor) {
        var p = require(id);
        var from = p.status();
        var founder = FOUNDER.equals(actor);
        var founderOnly = to == null || to == CatalogStatus.PAUSED || to == CatalogStatus.RETIRED
                || from == CatalogStatus.RETIRED || from == CatalogStatus.PAUSED;
        if (founderOnly && !founder) {
            throw new IllegalArgumentException("Solo el fundador puede pausar, reanudar, retirar o reactivar un producto.");
        }
        CatalogStatus target;
        if (to == null) {
            if (from != CatalogStatus.PAUSED) {
                throw new IllegalArgumentException("Solo se reanuda un producto pausado (estado actual: " + from + ").");
            }
            target = p.statusBeforePause() == null ? CatalogStatus.IDEA : p.statusBeforePause();
        } else if (from == CatalogStatus.RETIRED && to != CatalogStatus.IDEA) {
            throw new IllegalArgumentException("Un producto retirado solo se reactiva como idea.");
        } else if (from == CatalogStatus.PAUSED && to != CatalogStatus.RETIRED) {
            throw new IllegalArgumentException("Un producto pausado solo se reanuda o se retira.");
        } else {
            target = to;
        }
        if (target == CatalogStatus.READY_TO_SELL) {
            var missing = ProductReadiness.missing(p, memory.evidence(p));
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("No puede pasar a listo para vender: " + String.join(" ", missing));
            }
        }
        var beforePause = target == CatalogStatus.PAUSED ? from : null;
        var now = Instant.now();
        var after = withStatus(p, target, beforePause, now);
        memory.save(after);
        memory.addChange(id, new ProductChange(actor, "status", from.name(), target.name(),
                reason == null || reason.isBlank() ? "Cambio de estado" : reason, now));
        events.publish("EMPRESA_PRODUCT_STATUS_CHANGED", null, null, actor,
                Map.of("productId", id, "from", from.name(), "to", target.name()));
        return view(after);
    }

    private CatalogProduct demoteIfNoLongerReady(CatalogProduct p, String actor, Instant now) {
        if (p.status() != CatalogStatus.READY_TO_SELL) {
            return p;
        }
        var missing = ProductReadiness.missing(p, memory.evidence(p));
        if (missing.isEmpty()) {
            return p;
        }
        memory.addChange(p.id(), new ProductChange(actor, "status", CatalogStatus.READY_TO_SELL.name(),
                CatalogStatus.IN_CONSTRUCTION.name(), "Deja de cumplir: " + String.join(" ", missing), now));
        events.publish("EMPRESA_PRODUCT_STATUS_CHANGED", null, null, actor, Map.of("productId", p.id(),
                "from", CatalogStatus.READY_TO_SELL.name(), "to", CatalogStatus.IN_CONSTRUCTION.name()));
        return withStatus(p, CatalogStatus.IN_CONSTRUCTION, null, now);
    }

    private void record(CatalogProduct before, CatalogProduct after, String actor, String reason, Instant now) {
        change(before.id(), "name", before.name(), after.name(), actor, reason, now);
        change(before.id(), "description", before.description(), after.description(), actor, reason, now);
        change(before.id(), "kind", before.kind(), after.kind(), actor, reason, now);
        change(before.id(), "targetCustomer", before.targetCustomer(), after.targetCustomer(), actor, reason, now);
        change(before.id(), "priceUsd", before.priceUsd(), after.priceUsd(), actor, reason, now);
        change(before.id(), "priceOnRequest", before.priceOnRequest(), after.priceOnRequest(), actor, reason, now);
        change(before.id(), "estimatedCostUsd", before.estimatedCostUsd(), after.estimatedCostUsd(), actor, reason, now);
        change(before.id(), "delivery", before.delivery(), after.delivery(), actor, reason, now);
        change(before.id(), "markets", before.markets(), after.markets(), actor, reason, now);
        change(before.id(), "languages", before.languages(), after.languages(), actor, reason, now);
    }

    private void change(String id, String field, Object from, Object to, String actor, String reason, Instant now) {
        if (!Objects.equals(from, to)) {
            memory.addChange(id, new ProductChange(actor, field, from == null ? null : String.valueOf(from),
                    to == null ? null : String.valueOf(to), reason, now));
        }
    }

    private ProductView view(CatalogProduct p) {
        var missing = WORKING.contains(p.status()) ? ProductReadiness.missing(p, memory.evidence(p)) : List.<String>of();
        return new ProductView(p, missing, memory.history(p.id()));
    }

    private CatalogProduct require(String id) {
        return memory.find(id).orElseThrow(() -> new IllegalArgumentException("No existe el producto " + id + "."));
    }

    private static CatalogProduct withStatus(CatalogProduct p, CatalogStatus status, CatalogStatus beforePause, Instant now) {
        return new CatalogProduct(p.id(), p.name(), p.description(), p.kind(), p.targetCustomer(), p.priceUsd(),
                p.priceOnRequest(), p.estimatedCostUsd(), p.delivery(), p.markets(), p.languages(), status, beforePause,
                p.createdBy(), p.createdAt(), now, p.validatedBy(), p.builtBy());
    }

    private static String kind(String kind) {
        if (kind == null || kind.isBlank()) {
            return "SOFTWARE";
        }
        var k = kind.strip().toUpperCase(Locale.ROOT);
        if (!k.equals("SOFTWARE") && !k.equals("SERVICE")) {
            throw new IllegalArgumentException("El tipo debe ser SOFTWARE o SERVICE.");
        }
        return k;
    }

    private static double amount(Double value, String label) {
        if (value == null) {
            return 0;
        }
        if (value < 0) {
            throw new IllegalArgumentException("El " + label + " no puede ser negativo.");
        }
        return value;
    }

    private static String text(String value) {
        return value == null ? null : value.strip();
    }

    private static List<String> listOr(List<String> value, List<String> fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).strip();
    }
}
