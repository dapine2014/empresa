package com.aicompany.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "missionOrchestratorExecutor")
    public Executor missionOrchestratorExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(25);
        executor.setThreadNamePrefix("mission-orchestrator-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "agentTaskExecutor")
    public Executor agentTaskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("agent-task-");
        executor.initialize();
        return executor;
    }
}
