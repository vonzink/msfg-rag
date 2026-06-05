package com.msfg.rag.provider;

/**
 * Contract for all AI chat providers (per rag.md).
 * Implementations wrap Spring AI ChatModels, so adding a provider
 * (Gemini, DeepSeek, Groq, Bedrock) is one small adapter class plus config.
 */
public interface AiModelProvider {

    AiResponse generate(AiRequest request);

    /** Stable lowercase identifier used in routing config, e.g. "anthropic". */
    String getProviderName();

    String getModelName();
}
