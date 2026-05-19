package com.shichangwei.offerdecision;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.retrieval")
public record RetrievalProperties(
    boolean enabled,
    String provider,
    String endpoint,
    String apiKey,
    int requestTimeoutSeconds,
    int maxResults,
    int minQualityScore,
    int cacheTtlMinutes,
    int maxFreshnessAgeDays,
    String tavilyTopic,
    String tavilySearchDepth,
    String tavilyTimeRange,
    String tavilyCountry) {}
