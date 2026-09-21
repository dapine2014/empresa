package com.aicompany.core.config;

import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.EngineeringTeamMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CompanyMemoryInitializer {
    private final CompanyMemoryService memory;
    private final MissionMemoryService missionMemory;
    private final EngineeringTeamMemoryService engineeringTeamMemory;

    public CompanyMemoryInitializer(
            CompanyMemoryService memory,
            MissionMemoryService missionMemory,
            EngineeringTeamMemoryService engineeringTeamMemory) {

        this.memory = memory;
        this.missionMemory = missionMemory;
        this.engineeringTeamMemory = engineeringTeamMemory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterReady() {
        memory.initialize();
        missionMemory.backfillMissionEnvironment();
        engineeringTeamMemory.ensureEngineeringTeam();
    }
}
