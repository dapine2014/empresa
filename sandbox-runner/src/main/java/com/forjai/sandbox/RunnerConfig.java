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
            @Value("${sandbox.deps-root}") String depsRoot,
            ProcessExecutor executor) {
        return new VerifyJobRunner(Path.of(productsRoot).toAbsolutePath().normalize(),
                Path.of(workRoot).toAbsolutePath().normalize(),
                new PodmanCommandBuilder(podmanUrl, Path.of(depsRoot).toAbsolutePath().normalize()), executor);
    }

    @Bean
    DependencyFetcher dependencyFetcher(
            @Value("${sandbox.deps-root}") String depsRoot,
            @Value("${sandbox.podman-url}") String podmanUrl,
            @Value("${sandbox.fetch-network:forjai-fetch}") String network,
            @Value("${sandbox.proxy-image:localhost/forjai-sandbox/egress-proxy:1}") String proxyImage,
            ProcessExecutor executor) {
        return new DependencyFetcher(Path.of(depsRoot).toAbsolutePath().normalize(), podmanUrl, network, proxyImage, executor);
    }

    @Bean
    DependencyPromoter dependencyPromoter(@Value("${sandbox.deps-root}") String depsRoot) {
        return new DependencyPromoter(Path.of(depsRoot).toAbsolutePath().normalize());
    }
}
