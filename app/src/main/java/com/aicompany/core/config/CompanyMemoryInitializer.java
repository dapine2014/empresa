package com.aicompany.core.config;

import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import com.aicompany.core.service.TeamMemoryService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CompanyMemoryInitializer {
    private final CompanyMemoryService memory;
    private final MissionMemoryService missionMemory;
    private final TeamMemoryService teamMemory;

    public CompanyMemoryInitializer(
            CompanyMemoryService memory,
            MissionMemoryService missionMemory,
            TeamMemoryService teamMemory) {

        this.memory = memory;
        this.missionMemory = missionMemory;
        this.teamMemory = teamMemory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterReady() {
        memory.initialize();
        missionMemory.backfillMissionEnvironment();
        teamMemory.ensureAllTeams();
    }
}
