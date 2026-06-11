package com.msfg.rag.provider;

/**
 * Provider-agnostic request to a chat model.
 *
 * @param prompt      the fully built prompt
 * @param temperature 0..1, keep low for guideline answers
 * @param maxTokens   completion budget
 * @param purpose     which routing lane this call uses (ANSWER = customer-facing
 *                    answers; UTILITY = internal plumbing like reranking)
 * @param model       router-populated model override; null = provider default.
 *                    Callers never set this directly — use the factories.
 */
public record AiRequest(String prompt, double temperature, int maxTokens,
                        Purpose purpose, String model) {

    public enum Purpose { ANSWER, UTILITY }

    public static AiRequest forGuidelineAnswer(String prompt) {
        return new AiRequest(prompt, 0.2, 1500, Purpose.ANSWER, null);
    }

    public static AiRequest forUtility(String prompt, double temperature, int maxTokens) {
        return new AiRequest(prompt, temperature, maxTokens, Purpose.UTILITY, null);
    }

    public AiRequest withModel(String model) {
        return new AiRequest(prompt, temperature, maxTokens, purpose, model);
    }
}
