package com.msfg.rag.repository;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.LinkRef;
import com.msfg.rag.domain.Surface;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainPageGuideRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired
    private BrainPageGuideRepository repository;

    private BrainPageGuide guide(String title, String route, boolean active) {
        BrainPageGuide g = new BrainPageGuide(
                route, title, "Help users with " + title, Surface.BOTH,
                List.of("understand fha", "fha down payment"),
                List.of("explain the 3.5% minimum down payment"),
                List.of(new LinkRef("FHA loans", "/loans/fha"), new LinkRef("Apply", "/apply")),
                List.of(UUID.randomUUID(), UUID.randomUUID()),
                List.of("fha", "government"),
                "tester");
        g.setActive(active);
        return g;
    }

    @Test
    void savesAndReadsBackJsonbLists() {
        BrainPageGuide created = guide("FHA Loans", "/loans/fha", true);
        List<UUID> ids = created.getSourceLinkIds();
        BrainPageGuide saved = repository.saveAndFlush(created);

        BrainPageGuide found = repository.findById(saved.getId()).orElseThrow();
        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
        assertEquals("tester", found.getCreatedBy());
        assertEquals("/loans/fha", found.getRoute());
        assertEquals("FHA Loans", found.getTitle());
        assertEquals(Surface.BOTH, found.getSurface());
        assertEquals(List.of("understand fha", "fha down payment"), found.getUserIntents());
        assertEquals(List.of("explain the 3.5% minimum down payment"), found.getAllowedGuidance());
        assertEquals(List.of("fha", "government"), found.getTopics());
        // List<LinkRef> jsonb round-trip — records compare by value
        assertEquals(List.of(new LinkRef("FHA loans", "/loans/fha"), new LinkRef("Apply", "/apply")),
                found.getInternalLinks());
        // List<UUID> jsonb round-trip
        assertEquals(ids, found.getSourceLinkIds());
        assertTrue(found.isActive());
    }

    @Test
    void savesNullRoute() {
        BrainPageGuide saved = repository.saveAndFlush(guide("Topic-only guide", null, true));

        BrainPageGuide found = repository.findById(saved.getId()).orElseThrow();
        assertNull(found.getRoute());
    }

    @Test
    void findByActiveTrueExcludesInactive() {
        repository.saveAndFlush(guide("active-one", "/a", true));
        repository.saveAndFlush(guide("inactive-one", "/b", false));

        List<BrainPageGuide> active = repository.findByActiveTrueOrderByCreatedAtDescIdDesc();
        assertEquals(1, active.size());
        assertEquals("active-one", active.get(0).getTitle());
    }

    @Test
    void countByActiveTrueCountsOnlyActive() {
        repository.saveAndFlush(guide("a", "/a", true));
        repository.saveAndFlush(guide("b", "/b", true));
        repository.saveAndFlush(guide("c", "/c", false));

        assertEquals(2L, repository.countByActiveTrue());
    }

    @Test
    void findAllOrderedReturnsNewestFirst() {
        repository.saveAndFlush(guide("first", "/1", true));
        repository.saveAndFlush(guide("second", "/2", true));

        List<BrainPageGuide> all = repository.findAllByOrderByCreatedAtDescIdDesc();
        assertEquals(2, all.size());
        assertEquals("second", all.get(0).getTitle());
    }
}
