package com.aicompany.core.config;

import com.aicompany.core.service.CompanyMemoryService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CompanyMemoryInitializer {
    private final CompanyMemoryService memory;

    public CompanyMemoryInitializer(CompanyMemoryService memory) {
        this.memory = memory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterReady() {
        memory.initialize();
    }
}
