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
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Routes requests to the per-purpose provider resolved from RuntimeSettings,
 * falling back to the configured fallback provider if the primary call fails.
 * Providers are discovered automatically — any AiModelProvider bean is routable,
 * so new adapters (Gemini, DeepSeek, Groq) plug in with zero router changes.
 *
 * CRITICAL invariant: the fallback provider always receives request.withModel(null)
 * — a primary's model name must never be sent to a different provider.
 */
@Service
public class ModelRouterService {

    private static final Logger log = LoggerFactory.getLogger(ModelRouterService.class);

    private final Map<String, AiModelProvider> providers;
    private final RagProperties.Routing routing;
    private final RuntimeSettings settings;

    public ModelRouterService(List<AiModelProvider> providerBeans, RagProperties properties,
                              RuntimeSettings settings) {
        this.providers = providerBeans.stream()
                .collect(Collectors.toMap(AiModelProvider::getProviderName, Function.identity()));
        this.routing = properties.routing();
        this.settings = settings;

        if (!providers.containsKey(routing.defaultProvider())) {
            throw new IllegalStateException(
                    "Default AI provider '" + routing.defaultProvider() + "' is not registered. "
                    + "Available: " + providers.keySet());
        }
        String fallback = routing.fallbackProvider();
        if (fallback != null && !fallback.isBlank() && !providers.containsKey(fallback)) {
            throw new IllegalStateException(
                    "Fallback AI provider '" + fallback + "' is not registered. "
                    + "Available: " + providers.keySet());
        }
    }

    /**
     * @return the response plus whether the fallback provider had to be used
     */
    public RoutedResponse generate(AiRequest request) {
        boolean utility = request.purpose() == AiRequest.Purpose.UTILITY;
        String configuredProvider = utility ? settings.utilityProvider() : settings.answerProvider();
        String model = utility ? settings.utilityModel() : settings.answerModel();

        AiModelProvider primary = providers.get(configuredProvider);
        if (primary == null) {
            log.warn("Configured {} provider '{}' is not registered; using default '{}'",
                    utility ? "utility" : "answer", configuredProvider, routing.defaultProvider());
            primary = providers.get(routing.defaultProvider());
        }

        try {
            return new RoutedResponse(primary.generate(request.withModel(model)), false);
        } catch (Exception primaryFailure) {
            log.error("Primary AI provider '{}' failed: {}",
                    primary.getProviderName(), primaryFailure.getMessage());

            AiModelProvider fallback = providers.get(routing.fallbackProvider());
            if (fallback == null || fallback == primary) {
                throw primaryFailure;
            }
            log.warn("Falling back to provider '{}'", fallback.getProviderName());
            // The fallback always runs its own default model — a primary's
            // model name must never be sent to a different provider.
            return new RoutedResponse(fallback.generate(request.withModel(null)), true);
        }
    }

    public Set<String> providerNames() {
        return java.util.Set.copyOf(providers.keySet());
    }

    public record RoutedResponse(AiResponse response, boolean fallbackUsed) {
    }
}
