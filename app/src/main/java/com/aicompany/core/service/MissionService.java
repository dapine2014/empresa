package com.aicompany.core.service;

import com.aicompany.core.event.CompanyEventPublisher;
import com.aicompany.core.model.MissionResponse;
import com.aicompany.core.model.MissionStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class MissionService {

    private static final Logger log = LoggerFactory.getLogger(MissionService.class);

    private final MissionMemoryService memory;
    private final MissionExecutor executor;
    private final CompanyEventPublisher events;

    public MissionService(MissionMemoryService memory, MissionExecutor executor, CompanyEventPublisher events) {
        this.memory = memory;
        this.executor = executor;
        this.events = events;
    }

    public MissionResponse start(String missionId, String instruction) {
        memory.ensureMission(missionId, instruction);

        executor.executeAsync(missionId, instruction)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        log.error("MISSION {} - async future failed", missionId, error);
                    } else {
                        log.info("MISSION {} - async orchestration finished", missionId);
                    }
                });

        return memory.find(missionId).orElseThrow();
    }

    public Optional<MissionResponse> status(String missionId) {
        return memory.find(missionId);
    }

    public Optional<MissionStatusResponse> details(String missionId) {
        return memory.find(missionId)
                .map(mission -> new MissionStatusResponse(
                        mission,
                        memory.tasks(missionId)
                ));
    }
}
