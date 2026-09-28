package com.aicompany.core.controller;

import com.aicompany.core.model.FinanceCorrectionCommand;
import com.aicompany.core.model.FinanceCustomer;
import com.aicompany.core.model.FinanceCustomerCommand;
import com.aicompany.core.model.FinanceExpenseCommand;
import com.aicompany.core.model.FinanceSaleCommand;
import com.aicompany.core.model.FinanceSummary;
import com.aicompany.core.service.FinanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Finanzas (spec 2026-09-27): canal exclusivo del fundador (🔴), ningún agente lo llama. */
@RestController
@RequestMapping("/api/company/finance")
public class FinanceController {

    private final FinanceService finance;

    public FinanceController(FinanceService finance) {
        this.finance = finance;
    }

    @GetMapping
    public FinanceSummary summary(@RequestParam(required = false) String missionId,
                                  @RequestParam(required = false) String productId) {
        return productId != null && !productId.isBlank() ? finance.summaryForProduct(productId) : finance.summary(missionId);
    }

    @GetMapping("/customers")
    public List<FinanceCustomer> customers() {
        return finance.customers();
    }

    @PostMapping("/customers")
    public FinanceCustomer customer(@RequestBody FinanceCustomerCommand command) {
        return finance.registerCustomer(command);
    }

    @PostMapping("/sales")
    public Map<String, String> sale(@RequestBody FinanceSaleCommand command) {
        return Map.of("id", finance.registerSale(command));
    }

    @PostMapping("/expenses")
    public Map<String, String> expense(@RequestBody FinanceExpenseCommand command) {
        return Map.of("id", finance.registerExpense(command));
    }

    @PostMapping("/corrections")
    public Map<String, String> correction(@RequestBody FinanceCorrectionCommand command) {
        return Map.of("id", finance.registerCorrection(command));
    }
}
