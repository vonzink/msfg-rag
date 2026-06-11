package com.msfg.rag.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Protects admin endpoints with a static API key (X-Admin-Api-Key header).
 * Interim measure for local/MVP — replace with Cognito JWT auth at deployment.
 */
@Component
public class AdminApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Admin-Api-Key";

    private final String adminApiKey;

    public AdminApiKeyFilter(RagProperties properties) {
        this.adminApiKey = properties.admin().apiKey();
    }

    @Override
    public boolean shouldNotFilter(HttpServletRequest request) {
        // Only admin surfaces are gated; /ask and conversation reads are public.
        String uri = request.getRequestURI();
        return !(uri.startsWith("/api/ai/documents") || uri.startsWith("/api/ai/admin"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String provided = request.getHeader(HEADER);
        if (provided == null || !constantTimeEquals(provided, adminApiKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Missing or invalid admin API key\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** Constant-time comparison to avoid timing attacks on the key. */
    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
