package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.FinanceCorrectionCommand;
import com.aicompany.core.model.FinanceCustomer;
import com.aicompany.core.model.FinanceCustomerCommand;
import com.aicompany.core.model.FinanceExpenseCommand;
import com.aicompany.core.model.FinanceSaleCommand;
import com.aicompany.core.model.FinanceSummary;
import com.aicompany.core.model.PolicyKey;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Finanzas (spec 2026-09-27): clientes, ventas, gastos y correcciones que registra solo el fundador (🔴). Nada se
 * edita ni se borra; un error se corrige con un asiento. Costos, ganancias y balance los calcula FinanceCalculator.
 */
@Service
public class FinanceService {

    private final FinanceMemoryService memory;
    private final CompanyPolicyService policies;
    private final CompanyEventPublisher events;
    private final ProductMemoryService products;

    public FinanceService(FinanceMemoryService memory, CompanyPolicyService policies, CompanyEventPublisher events,
                          ProductMemoryService products) {
        this.memory = memory;
        this.policies = policies;
        this.events = events;
        this.products = products;
    }

    /** Ingresos, costos y ganancias de un producto del catálogo (spec 2026-09-28). */
    public FinanceSummary summaryForProduct(String productId) {
        return FinanceCalculator.summarize(memory.movements(), policies.activeValue(PolicyKey.SEED_CAPITAL_USD), null,
                productId);
    }

    public FinanceSummary summary(String missionIdOrNull) {
        return FinanceCalculator.summarize(memory.movements(), policies.activeValue(PolicyKey.SEED_CAPITAL_USD),
                missionIdOrNull == null || missionIdOrNull.isBlank() ? null : missionIdOrNull);
    }

    public List<FinanceCustomer> customers() {
        return memory.customers();
    }

    public FinanceCustomer registerCustomer(FinanceCustomerCommand c) {
        require(c.name(), "El nombre del cliente es obligatorio.");
        validateEvidence(c.evidenceDescription(), c.evidenceLink());
        environmentFor(c.missionId(), null);
        var id = "CUSTOMER-" + UUID.randomUUID();
        memory.createCustomer(id, c.name().strip(), c.contact(), blankToNull(c.missionId()), c.evidenceDescription().strip(),
                blankToNull(c.evidenceLink()));
        events.publish("EMPRESA_CUSTOMER_REGISTERED", blankToNull(c.missionId()), null, "human",
                Map.of("customerId", id, "name", c.name().strip()));
        return new FinanceCustomer(id, c.name().strip(), c.contact(), blankToNull(c.missionId()), Instant.now());
    }

    public String registerSale(FinanceSaleCommand c) {
        require(c.description(), "La descripción de la venta es obligatoria.");
        if (c.revenueUsd() < 0 || c.costUsd() < 0) {
            throw new IllegalArgumentException("Ingreso y costo no pueden ser negativos.");
        }
        validateEvidence(c.evidenceDescription(), c.evidenceLink());
        var kind = memory.customerKind(c.customerId())
                .orElseThrow(() -> new IllegalArgumentException("No existe el cliente " + c.customerId() + "."));
        if ("LEAD".equals(kind)) {
            throw new IllegalArgumentException("El cliente " + c.customerId() + " es un prospecto de los agentes, no un "
                    + "cliente: regístralo como cliente cuando compre.");
        }
        if (c.productId() != null && !c.productId().isBlank() && products.find(c.productId()).isEmpty()) {
            throw new IllegalArgumentException("No existe el producto " + c.productId() + ".");
        }
        var environment = environmentFor(c.missionId(), c.environment());
        var id = "SALE-" + UUID.randomUUID();
        memory.createSale(id, c, environment);
        events.publish("EMPRESA_SALE_RECORDED", blankToNull(c.missionId()), null, "human",
                Map.of("saleId", id, "revenueUsd", c.revenueUsd(), "costUsd", c.costUsd(), "environment", environment));
        return id;
    }

    public String registerExpense(FinanceExpenseCommand c) {
        require(c.description(), "La descripción del gasto es obligatoria.");
        if (c.amountUsd() <= 0) {
            throw new IllegalArgumentException("El monto del gasto debe ser mayor a 0.");
        }
        validateEvidence(c.evidenceDescription(), c.evidenceLink());
        var environment = environmentFor(c.missionId(), c.environment());
        var id = "EXPENSE-" + UUID.randomUUID();
        memory.createExpense(id, c, environment);
        events.publish("EMPRESA_EXPENSE_RECORDED", blankToNull(c.missionId()), null, "human",
                Map.of("expenseId", id, "amountUsd", c.amountUsd(), "environment", environment));
        return id;
    }

    public String registerCorrection(FinanceCorrectionCommand c) {
        require(c.reason(), "El motivo de la corrección es obligatorio.");
        validateEvidence(c.evidenceDescription(), c.evidenceLink());
        var kind = memory.movementKind(c.targetId())
                .orElseThrow(() -> new IllegalArgumentException("No existe el movimiento " + c.targetId() + "."));
        if ("CORRECTION".equals(kind)) {
            throw new IllegalArgumentException("Una corrección no se corrige: corrige otra vez el movimiento original.");
        }
        if ("EXPENSE".equals(kind) && c.revenueAdjustmentUsd() != 0) {
            throw new IllegalArgumentException("Un gasto solo admite ajuste de costo.");
        }
        if (c.revenueAdjustmentUsd() == 0 && c.costAdjustmentUsd() == 0) {
            throw new IllegalArgumentException("La corrección no cambia nada: indica un ajuste de ingreso o de costo.");
        }
        var id = "CORRECTION-" + UUID.randomUUID();
        memory.createCorrection(id, c);
        events.publish("EMPRESA_CORRECTION_RECORDED", null, null, "human", Map.of("correctionId", id,
                "targetId", c.targetId(), "revenueAdjustmentUsd", c.revenueAdjustmentUsd(),
                "costAdjustmentUsd", c.costAdjustmentUsd(), "reason", c.reason().strip()));
        return id;
    }

    private String environmentFor(String missionId, String requested) {
        if (missionId == null || missionId.isBlank()) {
            return requested == null || requested.isBlank() ? "PRODUCTION" : requested.strip().toUpperCase(Locale.ROOT);
        }
        return memory.missionEnvironment(missionId)
                .orElseThrow(() -> new IllegalArgumentException("No existe la misión " + missionId + "."));
    }

    private static void validateEvidence(String description, String link) {
        require(description, "La evidencia necesita una descripción (p. ej. \"factura #123\").");
        if (link != null && !link.isBlank() && !link.strip().matches("(?i)^https?://\\S+$")) {
            throw new IllegalArgumentException("El comprobante debe ser un link http(s).");
        }
    }

    private static void require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
