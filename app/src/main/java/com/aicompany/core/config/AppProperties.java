package com.aicompany.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "company")
public record AppProperties(
        String name,
        double seedCapitalUsd,
        int challengeDays,
        int maxEvidenceRounds) {}
