package com.msfg.rag.config;

import com.msfg.rag.provider.OpenAiCompatibleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optional OpenAI-dialect providers. Each bean exists only when its API key
 * is configured — drop a key into .env, restart, and the provider appears in
 * the dashboard. No key, no bean, not selectable.
 */
@Configuration
public class ExtraProvidersConfig {

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${brain.providers.deepseek.api-key:}')")
    public OpenAiCompatibleProvider deepSeekProvider(
            @Value("${brain.providers.deepseek.base-url}") String baseUrl,
            @Value("${brain.providers.deepseek.api-key}") String apiKey,
            @Value("${brain.providers.deepseek.model}") String model) {
        return new OpenAiCompatibleProvider("deepseek", baseUrl, apiKey, model);
    }

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${brain.providers.gemini.api-key:}')")
    public OpenAiCompatibleProvider geminiProvider(
            @Value("${brain.providers.gemini.base-url}") String baseUrl,
            @Value("${brain.providers.gemini.api-key}") String apiKey,
            @Value("${brain.providers.gemini.model}") String model) {
        return new OpenAiCompatibleProvider("gemini", baseUrl, apiKey, model);
    }

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${brain.providers.grok.api-key:}')")
    public OpenAiCompatibleProvider grokProvider(
            @Value("${brain.providers.grok.base-url}") String baseUrl,
            @Value("${brain.providers.grok.api-key}") String apiKey,
            @Value("${brain.providers.grok.model}") String model) {
        return new OpenAiCompatibleProvider("grok", baseUrl, apiKey, model);
    }
}
