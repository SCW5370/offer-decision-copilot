package com.shichangwei.offerdecision;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.live-research")
public record LiveResearchProperties(
    boolean enabled,
    String provider,
    String model,
    String apiKey,
    String baseUrl,
    String formulaUri,
    int connectTimeoutSeconds,
    int requestTimeoutSeconds,
    int researchTimeoutSeconds,
    String structuringProvider,
    String structuringModel,
    String structuringApiKey,
    String structuringBaseUrl) {}
