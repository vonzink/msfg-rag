package com.msfg.rag.service.ai;

import com.msfg.rag.config.RagProperties;
import com.msfg.rag.provider.AiModelProvider;
import com.msfg.rag.provider.AiRequest;
import com.msfg.rag.provider.AiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Routes requests to the configured default provider, falling back to the
 * configured fallback provider if the primary call fails. Providers are
 * discovered automatically — any AiModelProvider bean is routable, so new
 * adapters (Gemini, DeepSeek, Groq) plug in with zero router changes.
 */
@Service
public class ModelRouterService {

    private static final Logger log = LoggerFactory.getLogger(ModelRouterService.class);

    private final Map<String, AiModelProvider> providers;
    private final RagProperties.Routing routing;

    public ModelRouterService(List<AiModelProvider> providerBeans, RagProperties properties) {
        this.providers = providerBeans.stream()
                .collect(Collectors.toMap(AiModelProvider::getProviderName, Function.identity()));
        this.routing = properties.routing();

        if (!providers.containsKey(routing.defaultProvider())) {
            throw new IllegalStateException(
                    "Default AI provider '" + routing.defaultProvider() + "' is not registered. "
                    + "Available: " + providers.keySet());
        }
    }

    /**
     * @return the response plus whether the fallback provider had to be used
     */
    public RoutedResponse generate(AiRequest request) {
        AiModelProvider primary = providers.get(routing.defaultProvider());
        try {
            return new RoutedResponse(primary.generate(request), false);
        } catch (Exception primaryFailure) {
            log.error("Primary AI provider '{}' failed: {}",
                    primary.getProviderName(), primaryFailure.getMessage());

            AiModelProvider fallback = providers.get(routing.fallbackProvider());
            if (fallback == null || fallback == primary) {
                throw primaryFailure;
            }
            log.warn("Falling back to provider '{}'", fallback.getProviderName());
            return new RoutedResponse(fallback.generate(request), true);
        }
    }

    public record RoutedResponse(AiResponse response, boolean fallbackUsed) {
    }
}
