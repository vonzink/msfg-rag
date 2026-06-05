package com.msfg.rag.service.ai;

import com.msfg.rag.config.RagProperties;
import com.msfg.rag.provider.AiModelProvider;
import com.msfg.rag.provider.AiRequest;
import com.msfg.rag.provider.AiResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRouterServiceTest {

    private static final AiRequest REQUEST = AiRequest.forGuidelineAnswer("test prompt");

    private RagProperties properties(String defaultProvider, String fallbackProvider) {
        return new RagProperties(
                new RagProperties.Routing(defaultProvider, fallbackProvider),
                new RagProperties.Retrieval(8, 3, 0.55, 0.65, 0.35),
                new RagProperties.Chunking(1000, 1200, 150),
                new RagProperties.Storage("./data/test"),
                new RagProperties.Admin("test-key"),
                new RagProperties.RateLimit(10));
    }

    @Test
    void routesToDefaultProvider() {
        var router = new ModelRouterService(
                List.of(workingProvider("anthropic"), workingProvider("openai")),
                properties("anthropic", "openai"));

        var routed = router.generate(REQUEST);

        assertEquals("anthropic", routed.response().providerName());
        assertFalse(routed.fallbackUsed());
    }

    @Test
    void fallsBackWhenPrimaryFails() {
        var router = new ModelRouterService(
                List.of(failingProvider("anthropic"), workingProvider("openai")),
                properties("anthropic", "openai"));

        var routed = router.generate(REQUEST);

        assertEquals("openai", routed.response().providerName());
        assertTrue(routed.fallbackUsed());
    }

    @Test
    void throwsWhenPrimaryFailsAndNoFallbackConfigured() {
        var router = new ModelRouterService(
                List.of(failingProvider("anthropic")),
                properties("anthropic", "anthropic"));

        assertThrows(RuntimeException.class, () -> router.generate(REQUEST));
    }

    @Test
    void rejectsUnknownDefaultProviderAtStartup() {
        assertThrows(IllegalStateException.class, () -> new ModelRouterService(
                List.of(workingProvider("openai")),
                properties("anthropic", "openai")));
    }

    // ------------------------------------------------------------------

    private AiModelProvider workingProvider(String name) {
        return new AiModelProvider() {
            @Override
            public AiResponse generate(AiRequest request) {
                return new AiResponse("{\"answer\":\"ok\"}", name, name + "-model", 100, 50);
            }

            @Override
            public String getProviderName() {
                return name;
            }

            @Override
            public String getModelName() {
                return name + "-model";
            }
        };
    }

    private AiModelProvider failingProvider(String name) {
        return new AiModelProvider() {
            @Override
            public AiResponse generate(AiRequest request) {
                throw new RuntimeException(name + " API unavailable");
            }

            @Override
            public String getProviderName() {
                return name;
            }

            @Override
            public String getModelName() {
                return name + "-model";
            }
        };
    }
}
