package com.aicompany.core.config;

import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.ConversationMemoryService;
import com.aicompany.core.service.MissionMemoryService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CompanyMemoryInitializer {
    private final CompanyMemoryService memory;
    private final MissionMemoryService missionMemory;
    private final ConversationMemoryService conversationMemory;

    public CompanyMemoryInitializer(
            CompanyMemoryService memory,
            MissionMemoryService missionMemory,
            ConversationMemoryService conversationMemory) {
        this.memory = memory;
        this.missionMemory = missionMemory;
        this.conversationMemory = conversationMemory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterReady() {
        memory.initialize();
        missionMemory.backfillMissionEnvironment();
        conversationMemory.migrateMessagesToChats();
    }
}
