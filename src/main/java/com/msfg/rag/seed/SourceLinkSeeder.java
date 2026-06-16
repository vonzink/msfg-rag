package com.msfg.rag.seed;

import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.repository.BrainSourceLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds brain_source_links from the optional pack file on first boot, once, only
 * when the table is empty. No pack file -> DomainPack.sourceLinks() is empty ->
 * nothing is seeded -> the table stays empty and the system behaves exactly as
 * before this feature existed. Idempotent: a non-empty table is left untouched.
 */
@Component
public class SourceLinkSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SourceLinkSeeder.class);
    private static final String SEEDED_BY = "pack-seed";

    private final BrainSourceLinkRepository repository;
    private final DomainPack pack;

    public SourceLinkSeeder(BrainSourceLinkRepository repository, DomainPack pack) {
        this.repository = repository;
        this.pack = pack;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (pack.sourceLinks().isEmpty()) {
            return;
        }
        if (repository.count() > 0) {
            return;
        }
        for (DomainPack.SourceLink seed : pack.sourceLinks()) {
            repository.save(new BrainSourceLink(
                    seed.name(),
                    seed.url(),
                    seed.domain(),
                    LinkAuthority.valueOf(seed.authority()),
                    seed.topics(),
                    seed.freshnessRequired(),
                    seed.allowedUse(),
                    seed.doNotUseFor(),
                    Surface.valueOf(seed.surface()),
                    SEEDED_BY));
        }
        log.info("Seeded {} source link(s) from pack into brain_source_links", pack.sourceLinks().size());
    }
}
