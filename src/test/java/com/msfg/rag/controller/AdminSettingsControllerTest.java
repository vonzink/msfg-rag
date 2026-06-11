package com.msfg.rag.controller;

import com.msfg.rag.service.ai.ModelRouterService;
import com.msfg.rag.service.ai.RuntimeSettings;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSettingsControllerTest {

    private final RuntimeSettings settings = mock(RuntimeSettings.class);
    private final ModelRouterService router = mock(ModelRouterService.class);
    private final AdminSettingsController controller =
            new AdminSettingsController(settings, router);

    @Test
    void getReturnsEffectiveValuesAndOverrides() {
        when(settings.answerProvider()).thenReturn("anthropic");
        when(settings.answerModel()).thenReturn(null);
        when(settings.utilityProvider()).thenReturn("anthropic");
        when(settings.utilityModel()).thenReturn(null);
        when(settings.confidenceThreshold()).thenReturn(0.35);
        when(settings.topK()).thenReturn(8);
        when(settings.rerankEnabled()).thenReturn(true);
        when(settings.overrides()).thenReturn(Map.of());

        Map<String, Object> body = controller.get();

        @SuppressWarnings("unchecked")
        Map<String, Object> effective = (Map<String, Object>) body.get("effective");
        assertEquals("anthropic", effective.get("answer.provider"));
        assertEquals(8, effective.get("retrieval.top-k"));
        assertEquals(Map.of(), body.get("overrides"));
    }

    @Test
    void putValidatesProviderAgainstRegistry() {
        when(router.providerNames()).thenReturn(Set.of("anthropic", "openai"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("answer.provider", "gemini")));
        verify(settings, never()).put(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void putRejectsUnknownKeysAndBadNumbers() {
        when(router.providerNames()).thenReturn(Set.of("anthropic", "openai"));
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("nope.key", "x")));
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("retrieval.top-k", "0")));
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("retrieval.confidence-threshold", "1.5")));
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(Map.of("rerank.enabled", "maybe")));
    }

    @Test
    void putWritesValidEntriesAndBlankClearsTheOverride() {
        when(router.providerNames()).thenReturn(Set.of("anthropic", "openai"));

        controller.put(new java.util.LinkedHashMap<>(Map.of(
                "answer.model", "claude-haiku-4-5",
                "retrieval.top-k", "12")));
        verify(settings).put("answer.model", "claude-haiku-4-5", "admin-api");
        verify(settings).put("retrieval.top-k", "12", "admin-api");

        controller.put(Map.of("answer.model", ""));
        verify(settings).clear("answer.model");
    }
}
