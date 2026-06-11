package com.msfg.rag.service.ai;

import com.msfg.rag.config.RagProperties;
import com.msfg.rag.provider.AiModelProvider;
import com.msfg.rag.provider.AiRequest;
import com.msfg.rag.provider.AiResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelRouterServiceTest {

    private static final AiRequest REQUEST = AiRequest.forGuidelineAnswer("test prompt");

    private RagProperties properties(String defaultProvider, String fallbackProvider) {
        return new RagProperties(
                new RagProperties.Routing(defaultProvider, fallbackProvider),
                new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24),
                new RagProperties.Chunking(1000, 1200, 150),
                new RagProperties.Storage("./data/test"),
                new RagProperties.Admin("test-key"),
                new RagProperties.RateLimit(10));
    }

    /** Default RuntimeSettings mock: answer=anthropic (no model), utility same. */
    private RuntimeSettings defaultSettings(String defaultProvider) {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn(defaultProvider);
        when(s.answerModel()).thenReturn(null);
        when(s.utilityProvider()).thenReturn(defaultProvider);
        when(s.utilityModel()).thenReturn(null);
        return s;
    }

    // ------------------------------------------------------------------
    // Existing tests — updated to pass RuntimeSettings mock

    @Test
    void routesToDefaultProvider() {
        var router = new ModelRouterService(
                List.of(capturingProvider("anthropic"), capturingProvider("openai")),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"));

        var routed = router.generate(REQUEST);

        assertEquals("anthropic", routed.response().providerName());
        assertFalse(routed.fallbackUsed());
    }

    @Test
    void fallsBackWhenPrimaryFails() {
        var router = new ModelRouterService(
                List.of(failingProvider("anthropic"), capturingProvider("openai")),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"));

        var routed = router.generate(REQUEST);

        assertEquals("openai", routed.response().providerName());
        assertTrue(routed.fallbackUsed());
    }

    @Test
    void throwsWhenPrimaryFailsAndNoFallbackConfigured() {
        var router = new ModelRouterService(
                List.of(failingProvider("anthropic")),
                properties("anthropic", "anthropic"),
                defaultSettings("anthropic"));

        assertThrows(RuntimeException.class, () -> router.generate(REQUEST));
    }

    @Test
    void rejectsUnknownDefaultProviderAtStartup() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("anthropic");
        when(s.answerModel()).thenReturn(null);
        when(s.utilityProvider()).thenReturn("anthropic");
        when(s.utilityModel()).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> new ModelRouterService(
                List.of(capturingProvider("openai")),
                properties("anthropic", "openai"),
                s));
    }

    @Test
    void rejectsUnknownFallbackProviderAtStartup() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("anthropic");
        when(s.answerModel()).thenReturn(null);
        when(s.utilityProvider()).thenReturn("anthropic");
        when(s.utilityModel()).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> new ModelRouterService(
                List.of(capturingProvider("anthropic")),
                properties("anthropic", "bogus"),
                s));
    }

    // ------------------------------------------------------------------
    // New tests (Task 4.1)

    @Test
    void answerPurposeUsesAnswerSettings() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("openai");
        when(s.answerModel()).thenReturn("gpt-x");
        when(s.utilityProvider()).thenReturn("anthropic");
        when(s.utilityModel()).thenReturn(null);

        CapturingProvider openai = capturingProvider("openai");
        var router = new ModelRouterService(
                List.of(openai, capturingProvider("anthropic")),
                properties("openai", "anthropic"),
                s);

        router.generate(AiRequest.forGuidelineAnswer("test"));

        assertEquals("gpt-x", openai.lastRequest().model());
    }

    @Test
    void utilityPurposeUsesUtilitySettings() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("openai");
        when(s.answerModel()).thenReturn("gpt-x");
        when(s.utilityProvider()).thenReturn("anthropic");
        when(s.utilityModel()).thenReturn(null);

        CapturingProvider anthropic = capturingProvider("anthropic");
        var router = new ModelRouterService(
                List.of(capturingProvider("openai"), anthropic),
                properties("openai", "anthropic"),
                s);

        router.generate(AiRequest.forUtility("rerank prompt", 0.0, 800));

        assertTrue(anthropic.wasCalled(), "anthropic (utility provider) must have been called");
        assertNull(anthropic.lastRequest().model());
    }

    @Test
    void unknownConfiguredProviderFallsBackToDefaultProvider() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("gemini"); // not registered
        when(s.answerModel()).thenReturn(null);
        when(s.utilityProvider()).thenReturn("gemini");
        when(s.utilityModel()).thenReturn(null);

        CapturingProvider anthropic = capturingProvider("anthropic");
        var router = new ModelRouterService(
                List.of(anthropic, capturingProvider("openai")),
                properties("anthropic", "openai"),
                s);

        var routed = router.generate(AiRequest.forGuidelineAnswer("test"));

        // should fall back to routing.defaultProvider() = anthropic
        assertEquals("anthropic", routed.response().providerName());
        assertFalse(routed.fallbackUsed());
        assertTrue(anthropic.wasCalled());
    }

    @Test
    void fallbackProviderReceivesNoModelOverride() {
        RuntimeSettings s = mock(RuntimeSettings.class);
        when(s.answerProvider()).thenReturn("anthropic");
        when(s.answerModel()).thenReturn("claude-opus-4-5");
        when(s.utilityProvider()).thenReturn("anthropic");
        when(s.utilityModel()).thenReturn(null);

        CapturingProvider openai = capturingProvider("openai");
        var router = new ModelRouterService(
                List.of(failingProvider("anthropic"), openai),
                properties("anthropic", "openai"),
                s);

        var routed = router.generate(AiRequest.forGuidelineAnswer("test"));

        assertTrue(routed.fallbackUsed());
        // fallback must NOT receive the primary's model override
        assertNull(openai.lastRequest().model(),
                "Fallback provider must receive model=null, not the primary's model name");
    }

    @Test
    void providerNames() {
        var router = new ModelRouterService(
                List.of(capturingProvider("anthropic"), capturingProvider("openai")),
                properties("anthropic", "openai"),
                defaultSettings("anthropic"));

        assertEquals(Set.of("anthropic", "openai"), router.providerNames());
    }

    // ------------------------------------------------------------------

    /** A provider that records the last AiRequest it received. */
    private CapturingProvider capturingProvider(String name) {
        return new CapturingProvider(name);
    }

    private static class CapturingProvider implements AiModelProvider {
        private final String name;
        private AiRequest lastRequest;

        CapturingProvider(String name) {
            this.name = name;
        }

        @Override
        public AiResponse generate(AiRequest request) {
            this.lastRequest = request;
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

        AiRequest lastRequest() {
            return lastRequest;
        }

        boolean wasCalled() {
            return lastRequest != null;
        }
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
