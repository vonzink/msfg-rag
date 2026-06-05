package com.msfg.rag.provider;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Anthropic Claude adapter — the default provider.
 */
@Component
public class AnthropicProvider implements AiModelProvider {

    private final AnthropicChatModel chatModel;
    private final String modelName;

    public AnthropicProvider(AnthropicChatModel chatModel,
                             @Value("${spring.ai.anthropic.chat.options.model}") String modelName) {
        this.chatModel = chatModel;
        this.modelName = modelName;
    }

    @Override
    public AiResponse generate(AiRequest request) {
        Prompt prompt = new Prompt(request.prompt(), AnthropicChatOptions.builder()
                .model(modelName)
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

        return new AiResponse(
                response.getResult().getOutput().getText(),
                getProviderName(),
                modelName,
                promptTokens,
                completionTokens
        );
    }

    @Override
    public String getProviderName() {
        return "anthropic";
    }

    @Override
    public String getModelName() {
        return modelName;
    }
}
