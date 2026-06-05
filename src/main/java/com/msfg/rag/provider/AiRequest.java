package com.msfg.rag.provider;

/**
 * Provider-agnostic request to a chat model.
 *
 * @param prompt      the fully built prompt (see PromptBuilderService)
 * @param temperature 0..1, keep low for guideline answers
 * @param maxTokens   completion budget
 */
public record AiRequest(String prompt, double temperature, int maxTokens) {

    public static AiRequest forGuidelineAnswer(String prompt) {
        return new AiRequest(prompt, 0.2, 1500);
    }
}
