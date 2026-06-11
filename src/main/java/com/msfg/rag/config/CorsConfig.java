package com.msfg.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the public website chat widget and the internal ops dashboard.
 * The ask and conversation endpoints serve the public widget; admin surfaces
 * are exposed for the dashboard and remain key-gated at the filter layer.
 *
 * Allowed origins come from CORS_ALLOWED_ORIGINS (comma-separated), e.g.
 * "https://msfg.us,https://www.msfg.us". Localhost defaults support
 * local website development.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;
    private final String slug;

    public CorsConfig(@Value("${msfg.rag.cors.allowed-origins}") String allowedOrigins,
                      @Value("${brain.slug:mortgage}") String slug) {
        this.allowedOrigins = allowedOrigins.split(",");
        this.slug = slug;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/ai/" + slug + "/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("POST", "OPTIONS")
                .allowedHeaders("Content-Type")
                .maxAge(3600);

        registry.addMapping("/api/ai/conversations/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "OPTIONS")
                .allowedHeaders("Content-Type", "X-Session-Id")
                .maxAge(3600);

        registry.addMapping("/api/ai/admin/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "PUT", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "X-Admin-Api-Key")
                .maxAge(3600);

        registry.addMapping("/api/ai/documents/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "X-Admin-Api-Key")
                .maxAge(3600);
    }
}
