package com.forjai.sandbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class RunnerConfig {

    @Bean
    VerifyJobRunner verifyJobRunner(
            @Value("${sandbox.products-root}") String productsRoot,
            @Value("${sandbox.work-root}") String workRoot,
            @Value("${sandbox.podman-url}") String podmanUrl,
            ProcessExecutor executor) {
        return new VerifyJobRunner(Path.of(productsRoot).toAbsolutePath().normalize(),
                Path.of(workRoot).toAbsolutePath().normalize(), new PodmanCommandBuilder(podmanUrl), executor);
    }
}
