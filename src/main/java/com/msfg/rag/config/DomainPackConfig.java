package com.msfg.rag.config;

import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.pack.DomainPackLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Loads the domain pack named by brain.pack at boot. Any pack problem aborts
 * startup (DomainPackLoader fails fast) — a brain must never run with a
 * missing or partial compliance layer. brain.slug must match the pack's slug
 * so a deployment can't accidentally point at another company's pack.
 */
@Configuration
public class DomainPackConfig {

    @Bean
    public DomainPack domainPack(@Value("${brain.pack:packs/msfg-mortgage}") String packDir,
                                 @Value("${brain.slug:mortgage}") String slug) {
        DomainPack pack = new DomainPackLoader().load(Path.of(packDir));
        if (!pack.slug().equals(slug)) {
            throw new IllegalStateException("brain.slug is '" + slug + "' but pack '" + packDir
                    + "' declares slug '" + pack.slug() + "' — deployment/pack mismatch");
        }
        return pack;
    }
}
