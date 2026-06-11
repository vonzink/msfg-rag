package com.msfg.rag.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client token-bucket rate limiting on the public ask endpoint.
 * Keyed by client IP (X-Forwarded-For aware for when Nginx fronts the app).
 *
 * In-memory buckets are fine for a single instance; move to a Redis-backed
 * bucket4j store if we scale horizontally.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_TRACKED_CLIENTS = 100_000;
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int requestsPerMinute;
    private final String askPath;

    public RateLimitFilter(RagProperties properties,
                           @Value("${brain.slug:mortgage}") String slug) {
        this.requestsPerMinute = properties.rateLimit().requestsPerMinute();
        this.askPath = "/api/ai/" + slug + "/ask";
    }

    @Override
    public boolean shouldNotFilter(HttpServletRequest request) {
        // Preflights must not consume rate budget — they carry no application
        // payload and never reach the handler; the real POST still gets limited.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // Decoded path — percent-encoding must not bypass rate limiting.
        return !PATH_HELPER.getPathWithinApplication(request).equals(askPath);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Basic abuse guard: drop the map if it grows unreasonably.
        if (buckets.size() > MAX_TRACKED_CLIENTS) {
            buckets.clear();
        }

        Bucket bucket = buckets.computeIfAbsent(clientKey(request), key ->
                Bucket.builder()
                        .addLimit(Bandwidth.builder()
                                .capacity(requestsPerMinute)
                                .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                                .build())
                        .build());

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"error\":\"Too many requests. Please wait a moment and try again.\"}");
        }
    }

    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
