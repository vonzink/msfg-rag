package com.msfg.rag.repository;

import com.msfg.rag.domain.VocabularyRevision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class VocabularyRevisionRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired
    private VocabularyRevisionRepository repository;

    @Test
    void savesAndReadsLatestByCreatedAt() {
        repository.saveAndFlush(new VocabularyRevision("duplex => 2-unit", "tester"));
        repository.saveAndFlush(new VocabularyRevision("duplex => 2-unit 2-4 units", "tester2"));

        VocabularyRevision latest = repository.findFirstByOrderByCreatedAtDescIdDesc().orElseThrow();
        assertEquals("duplex => 2-unit 2-4 units", latest.getContent());
        assertEquals("tester2", latest.getCreatedBy());
        assertNotNull(latest.getCreatedAt());
    }

    @Test
    void nullContentRevertMarkerPersists() {
        repository.saveAndFlush(new VocabularyRevision(null, "tester"));
        assertNull(repository.findFirstByOrderByCreatedAtDescIdDesc().orElseThrow().getContent());
    }

    @Test
    void historyReturnsNewestFirst() {
        repository.saveAndFlush(new VocabularyRevision("a => 1", "t"));
        repository.saveAndFlush(new VocabularyRevision("b => 2", "t"));
        var rows = repository.findTop20ByOrderByCreatedAtDescIdDesc();
        assertEquals("b => 2", rows.get(0).getContent());
    }
}
