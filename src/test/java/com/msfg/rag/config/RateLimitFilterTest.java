package com.msfg.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitFilterTest {

    private final RagProperties props = new RagProperties(
            new RagProperties.Routing("anthropic", "openai"),
            new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24),
            new RagProperties.Chunking(1000, 1200, 150),
            new RagProperties.Storage("./data/documents"),
            new RagProperties.Admin("k"),
            new RagProperties.RateLimit(10));

    @Test
    void limitsTheSlugAskPathOnly() {
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage");

        assertFalse(filter.shouldNotFilter(get("/api/ai/mortgage/ask")),
                "the ask path must be rate limited");
        assertTrue(filter.shouldNotFilter(get("/api/ai/documents")),
                "admin endpoints are not rate limited by this filter");
    }

    @Test
    void percentEncodedAskPathIsStillRateLimited() {
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage");
        assertFalse(filter.shouldNotFilter(get("/api/ai/mortgage/%61sk")),
                "encoded ask path must not bypass rate limiting");
    }

    @Test
    void followsTheConfiguredSlug() {
        RateLimitFilter filter = new RateLimitFilter(props, "roofing");

        assertFalse(filter.shouldNotFilter(get("/api/ai/roofing/ask")));
        assertTrue(filter.shouldNotFilter(get("/api/ai/mortgage/ask")),
                "the old path is not limited when the slug changes");
    }

    private MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }
}
