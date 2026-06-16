package com.msfg.rag.repository;

import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainSourceLinkRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired
    private BrainSourceLinkRepository repository;

    private BrainSourceLink link(String name, boolean active) {
        BrainSourceLink l = new BrainSourceLink(
                name, "https://example.com/" + name, "example.com", LinkAuthority.PRIMARY,
                List.of("conventional", "appraisal"), true,
                List.of("cite guidelines"), List.of("legal advice"),
                Surface.BOTH, "tester");
        l.setActive(active);
        return l;
    }

    @Test
    void savesAndReadsBackJsonbLists() {
        BrainSourceLink saved = repository.saveAndFlush(link("fannie", true));

        BrainSourceLink found = repository.findById(saved.getId()).orElseThrow();
        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
        assertEquals("tester", found.getCreatedBy());
        assertEquals(List.of("conventional", "appraisal"), found.getTopics());
        assertEquals(List.of("cite guidelines"), found.getAllowedUse());
        assertEquals(List.of("legal advice"), found.getDoNotUseFor());
        assertEquals(LinkAuthority.PRIMARY, found.getAuthority());
        assertEquals(Surface.BOTH, found.getSurface());
        assertTrue(found.isFreshnessRequired());
        assertTrue(found.isActive());
    }

    @Test
    void findByActiveTrueExcludesInactive() {
        repository.saveAndFlush(link("active-one", true));
        repository.saveAndFlush(link("inactive-one", false));

        List<BrainSourceLink> active = repository.findByActiveTrueOrderByCreatedAtDescIdDesc();
        assertEquals(1, active.size());
        assertEquals("active-one", active.get(0).getName());
    }

    @Test
    void countByActiveTrueCountsOnlyActive() {
        repository.saveAndFlush(link("a", true));
        repository.saveAndFlush(link("b", true));
        repository.saveAndFlush(link("c", false));

        assertEquals(2L, repository.countByActiveTrue());
    }

    @Test
    void findAllOrderedReturnsNewestFirst() {
        repository.saveAndFlush(link("first", true));
        repository.saveAndFlush(link("second", true));

        List<BrainSourceLink> all = repository.findAllByOrderByCreatedAtDescIdDesc();
        assertEquals(2, all.size());
        assertEquals("second", all.get(0).getName());
    }
}
