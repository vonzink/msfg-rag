package com.msfg.rag.seed;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.LinkRef;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.repository.BrainPageGuideRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds brain_page_guides from the optional pack file on first boot, once, only
 * when the table is empty. No pack file -> DomainPack.pageGuides() is empty ->
 * nothing is seeded -> the table stays empty and the system behaves exactly as
 * before this feature existed. Idempotent: a non-empty table is left untouched.
 * Pack seeds carry NO source-link ids (links are attached later via the dashboard).
 */
@Component
public class PageGuideSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PageGuideSeeder.class);
    private static final String SEEDED_BY = "pack-seed";

    private final BrainPageGuideRepository repository;
    private final DomainPack pack;

    public PageGuideSeeder(BrainPageGuideRepository repository, DomainPack pack) {
        this.repository = repository;
        this.pack = pack;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (pack.pageGuides().isEmpty()) {
            return;
        }
        if (repository.count() > 0) {
            return;
        }
        for (DomainPack.PageGuide seed : pack.pageGuides()) {
            repository.save(new BrainPageGuide(
                    seed.route(),
                    seed.title(),
                    seed.purpose(),
                    Surface.valueOf(seed.surface()),
                    seed.userIntents(),
                    seed.allowedGuidance(),
                    seed.internalLinks().stream()
                            .map(l -> new LinkRef(l.label(), l.url()))
                            .toList(),
                    List.of(),
                    seed.topics(),
                    SEEDED_BY));
        }
        log.info("Seeded {} page guide(s) from pack into brain_page_guides", pack.pageGuides().size());
    }
}
