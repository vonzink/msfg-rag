package com.msfg.rag.provider;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * Adapter for any provider that speaks the OpenAI chat-completions dialect
 * (DeepSeek, Grok/xAI, Gemini's compatibility endpoint). One instance per
 * provider, registered by ExtraProvidersConfig only when its API key is
 * configured — an unconfigured provider simply doesn't exist in the registry,
 * so it can't be selected from the dashboard.
 */
public class OpenAiCompatibleProvider implements AiModelProvider {

    private final String providerName;
    private final String defaultModel;
    private final OpenAiChatModel chatModel;

    public OpenAiCompatibleProvider(String providerName, String baseUrl,
                                    String apiKey, String defaultModel) {
        this.providerName = providerName;
        this.defaultModel = defaultModel;
        this.chatModel = OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build())
                .build();
    }

    @Override
    public AiResponse generate(AiRequest request) {
        String model = request.model() != null ? request.model() : defaultModel;
        Prompt prompt = new Prompt(request.prompt(), OpenAiChatOptions.builder()
                .model(model)
                .temperature(request.temperature())
                .maxTokens(request.maxTokens())
                .build());

        ChatResponse response = chatModel.call(prompt);

        Integer promptTokens = null;
        Integer completionTokens = null;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            promptTokens = response.getMetadata().getUsage().getPromptTokens();
            completionTokens = response.getMetadata().getUsage().getCompletionTokens();
        }

        return new AiResponse(response.getResult().getOutput().getText(),
                providerName, model, promptTokens, completionTokens);
    }

    @Override
    public String getProviderName() {
        return providerName;
    }

    @Override
    public String getModelName() {
        return defaultModel;
    }
}
