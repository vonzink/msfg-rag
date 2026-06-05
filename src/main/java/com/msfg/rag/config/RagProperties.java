package com.msfg.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for everything under msfg.rag.* in application.yml.
 * Keeps tuning knobs (retrieval weights, chunk sizes, thresholds) in one place.
 */
@ConfigurationProperties(prefix = "msfg.rag")
public record RagProperties(
        Routing routing,
        Retrieval retrieval,
        Chunking chunking,
        Storage storage,
        Admin admin,
        RateLimit rateLimit
) {

    public record Routing(
            String defaultProvider,
            String fallbackProvider
    ) {}

    public record Retrieval(
            int topK,
            int minResults,
            double confidenceThreshold,
            double vectorWeight,
            double keywordWeight
    ) {}

    public record Chunking(
            int targetTokens,
            int maxTokens,
            int overlapTokens
    ) {}

    public record Storage(
            String path
    ) {}

    public record Admin(
            String apiKey
    ) {}

    public record RateLimit(
            int requestsPerMinute
    ) {}
}
