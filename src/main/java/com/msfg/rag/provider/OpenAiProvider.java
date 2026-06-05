package com.msfg.rag.provider;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OpenAI adapter — configured as the fallback provider.
 */
@Component
public class OpenAiProvider implements AiModelProvider {

    private final OpenAiChatModel chatModel;
    private final String modelName;

    public OpenAiProvider(OpenAiChatModel chatModel,
                          @Value("${spring.ai.openai.chat.options.model}") String modelName) {
        this.chatModel = chatModel;
        this.modelName = modelName;
    }

    @Override
    public AiResponse generate(AiRequest request) {
        Prompt prompt = new Prompt(request.prompt(), OpenAiChatOptions.builder()
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
        return "openai";
    }

    @Override
    public String getModelName() {
        return modelName;
    }
}
