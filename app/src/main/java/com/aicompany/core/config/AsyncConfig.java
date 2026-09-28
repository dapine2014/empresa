package com.aicompany.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
// Spec salud de modelos (2026-09-28): ModelHealthService prueba cada 30 s los modelos caídos.
@org.springframework.scheduling.annotation.EnableScheduling
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
