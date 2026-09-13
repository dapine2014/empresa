package com.aicompany.core.controller;

import com.aicompany.core.model.CustomerCommand;
import com.aicompany.core.model.CustomerResponse;
import com.aicompany.core.model.MissionProfitResponse;
import com.aicompany.core.model.TransactionCommand;
import com.aicompany.core.model.TransactionResponse;
import com.aicompany.core.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/company/missions/{missionId}")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping("/customers")
    public ResponseEntity<CustomerResponse> registerCustomer(
            @PathVariable String missionId,
            @Valid @RequestBody CustomerCommand command) {

        return ResponseEntity.ok(
                customerService.registerCustomer(missionId, command)
        );
    }

    @PostMapping("/transactions")
    public ResponseEntity<TransactionResponse> registerTransaction(
            @PathVariable String missionId,
            @Valid @RequestBody TransactionCommand command) {

        return customerService.registerTransaction(missionId, command)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/net-profit")
    public ResponseEntity<MissionProfitResponse> netProfit(
            @PathVariable String missionId) {

        return ResponseEntity.ok(
                customerService.netProfit(missionId)
        );
    }
}
