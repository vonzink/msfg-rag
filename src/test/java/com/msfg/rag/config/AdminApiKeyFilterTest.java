package com.msfg.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminApiKeyFilterTest {

    private final AdminApiKeyFilter filter = new AdminApiKeyFilter(new RagProperties(
            new RagProperties.Routing("anthropic", "openai"),
            new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24),
            new RagProperties.Chunking(1000, 1200, 150),
            new RagProperties.Storage("./data/documents"),
            new RagProperties.Admin("k"),
            new RagProperties.RateLimit(10)));

    @Test
    void gatesDocumentsAndAdminSurfaces() {
        assertFalse(filter.shouldNotFilter(get("/api/ai/documents")));
        assertFalse(filter.shouldNotFilter(get("/api/ai/admin/settings")));
        assertTrue(filter.shouldNotFilter(get("/api/ai/mortgage/ask")));
        assertTrue(filter.shouldNotFilter(get("/api/ai/conversations/abc")));
    }

    private MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }
}
