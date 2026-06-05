package com.msfg.rag.provider;

/**
 * Provider-agnostic response from a chat model.
 */
public record AiResponse(
        String content,
        String providerName,
        String modelName,
        Integer promptTokens,
        Integer completionTokens
) {
}
