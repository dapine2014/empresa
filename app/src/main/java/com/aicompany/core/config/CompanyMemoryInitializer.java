package com.aicompany.core.config;

import com.aicompany.core.service.CompanyMemoryService;
import com.aicompany.core.service.CompanyPolicyService;
import com.aicompany.core.service.MissionMemoryService;
import com.aicompany.core.service.PromptMemoryService;
import com.aicompany.core.service.TeamMemoryService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class CompanyMemoryInitializer {
    private final CompanyMemoryService memory;
    private final MissionMemoryService missionMemory;
    private final TeamMemoryService teamMemory;
    private final PromptMemoryService promptMemory;
    private final CompanyPolicyService companyPolicyService;

    public CompanyMemoryInitializer(
            CompanyMemoryService memory,
            MissionMemoryService missionMemory,
            TeamMemoryService teamMemory,
            PromptMemoryService promptMemory,
            CompanyPolicyService companyPolicyService) {

        this.memory = memory;
        this.missionMemory = missionMemory;
        this.teamMemory = teamMemory;
        this.promptMemory = promptMemory;
        this.companyPolicyService = companyPolicyService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeAfterReady() {
        memory.initialize();
        memory.ensureLocalFallbacks();
        // Decisión del fundador (2026-10-04): kimi gratis en NVIDIA; si cae, kimi en Bedrock (solo se siembra si falta).
        memory.ensurePaidFallbacks("nvidia:moonshotai/kimi-k3", "bedrock:global.moonshotai.kimi-k3");
        missionMemory.backfillMissionEnvironment();
        teamMemory.ensureAllTeams();
        promptMemory.ensureDefaultPrompts();
        companyPolicyService.ensureDefaultPolicies();
    }
}
