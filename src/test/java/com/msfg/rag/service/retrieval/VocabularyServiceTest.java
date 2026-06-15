package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.VocabularyRevision;
import com.msfg.rag.pack.TestPacks;
import com.msfg.rag.repository.VocabularyRevisionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VocabularyServiceTest {

    private VocabularyService service(VocabularyRevisionRepository repo) {
        return new VocabularyService(repo, TestPacks.msfg());
    }

    @Test
    void effectiveSynonymsFallsBackToPackWhenNoRevision() {
        VocabularyRevisionRepository repo = mock(VocabularyRevisionRepository.class);
        when(repo.findFirstByOrderByCreatedAtDescIdDesc()).thenReturn(Optional.empty());
        // pack seed (Task 3) includes "duplex"
        assertTrue(service(repo).effectiveSynonyms().containsKey("duplex"));
        assertEquals(TestPacks.msfg().acronymExpansions(), service(repo).effectiveSynonyms());
    }

    @Test
    void customRevisionFullyReplacesPackSet() {
        VocabularyRevisionRepository repo = mock(VocabularyRevisionRepository.class);
        when(repo.findFirstByOrderByCreatedAtDescIdDesc())
                .thenReturn(Optional.of(new VocabularyRevision("widget => gadget", "t")));
        var eff = service(repo).effectiveSynonyms();
        assertEquals("gadget", eff.get("widget"));
        assertEquals(1, eff.size()); // pack defaults replaced, not merged
    }

    @Test
    void nullContentRevisionRevertsToPack() {
        VocabularyRevisionRepository repo = mock(VocabularyRevisionRepository.class);
        when(repo.findFirstByOrderByCreatedAtDescIdDesc())
                .thenReturn(Optional.of(new VocabularyRevision(null, "t")));
        assertEquals(TestPacks.msfg().acronymExpansions(), service(repo).effectiveSynonyms());
    }

    @Test
    void effectiveTextSerializesPackDefaultWhenNoRevision() {
        VocabularyRevisionRepository repo = mock(VocabularyRevisionRepository.class);
        when(repo.findFirstByOrderByCreatedAtDescIdDesc()).thenReturn(Optional.empty());
        assertEquals(VocabularyText.serialize(TestPacks.msfg().acronymExpansions()),
                service(repo).effectiveText());
    }

    @Test
    void saveValidatesAndInvalidatesCache() {
        VocabularyRevisionRepository repo = mock(VocabularyRevisionRepository.class);
        when(repo.findFirstByOrderByCreatedAtDescIdDesc()).thenReturn(Optional.empty());
        VocabularyService s = service(repo);
        s.effectiveText();                       // warms cache
        s.save("duplex => 2-unit", "admin-api"); // valid
        verify(repo).save(any(VocabularyRevision.class));
        // cache invalidated -> next read hits the repo again (2 reads total)
        s.effectiveText();
        verify(repo, times(2)).findFirstByOrderByCreatedAtDescIdDesc();
    }

    @Test
    void saveRejectsMalformedContent() {
        VocabularyRevisionRepository repo = mock(VocabularyRevisionRepository.class);
        assertThrows(IllegalArgumentException.class, () -> service(repo).save("no arrow", "admin-api"));
    }

    @Test
    void revertPersistsNullMarker() {
        VocabularyRevisionRepository repo = mock(VocabularyRevisionRepository.class);
        when(repo.findFirstByOrderByCreatedAtDescIdDesc()).thenReturn(Optional.empty());
        service(repo).revert("admin-api");
        verify(repo).save(any(VocabularyRevision.class));
    }

    @Test
    void previewExpansionAppliesEffectiveSynonyms() {
        VocabularyRevisionRepository repo = mock(VocabularyRevisionRepository.class);
        when(repo.findFirstByOrderByCreatedAtDescIdDesc()).thenReturn(Optional.empty());
        String expanded = service(repo).previewExpansion("owner occupied duplex");
        assertTrue(expanded.contains("principal residence"), expanded);
        assertTrue(expanded.contains("2-unit"), expanded);
    }

    @Test
    void stateReportsSourceAndEntryCount() {
        VocabularyRevisionRepository repo = mock(VocabularyRevisionRepository.class);
        when(repo.findFirstByOrderByCreatedAtDescIdDesc()).thenReturn(Optional.empty());
        VocabularyService.VocabState st = service(repo).state();
        assertEquals("pack", st.source());
        assertEquals(TestPacks.msfg().acronymExpansions().size(), st.entries());
    }

    @Test
    void historyDelegatesToRepo() {
        VocabularyRevisionRepository repo = mock(VocabularyRevisionRepository.class);
        when(repo.findTop20ByOrderByCreatedAtDescIdDesc())
                .thenReturn(List.of(new VocabularyRevision("a => 1", "t")));
        assertEquals(1, service(repo).history().size());
    }
}
