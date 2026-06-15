package com.msfg.rag.controller;

import com.msfg.rag.domain.VocabularyRevision;
import com.msfg.rag.service.retrieval.VocabularyService;
import com.msfg.rag.service.retrieval.VocabularyService.VocabState;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminVocabularyControllerTest {

    private final VocabularyService vocabularyService = mock(VocabularyService.class);
    private final AdminVocabularyController controller =
            new AdminVocabularyController(vocabularyService);

    // ── GET /api/ai/admin/vocabulary ─────────────────────────────────────────

    @Test
    void getStateDelegatesToService() {
        OffsetDateTime now = OffsetDateTime.now();
        VocabState state = new VocabState("duplex => 2-unit", "custom", now, "admin-api", 1);
        when(vocabularyService.state()).thenReturn(state);

        VocabState result = controller.getState();

        assertEquals(state, result);
        verify(vocabularyService).state();
    }

    // ── PUT /api/ai/admin/vocabulary ─────────────────────────────────────────

    @Test
    void putHappyPathSavesAndReturnsRefreshedState() {
        OffsetDateTime now = OffsetDateTime.now();
        VocabState savedState = new VocabState("duplex => 2-unit", "custom", now, "admin-api", 1);
        when(vocabularyService.state()).thenReturn(savedState);

        VocabState result = controller.put(new AdminVocabularyController.ContentBody("duplex => 2-unit"));

        verify(vocabularyService).save("duplex => 2-unit", "admin-api");
        assertEquals(savedState, result);
    }

    @Test
    void putWithBlankContentThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(new AdminVocabularyController.ContentBody("")));
        verify(vocabularyService, never()).save(any(), any());
    }

    @Test
    void putWithNullContentThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(new AdminVocabularyController.ContentBody(null)));
        verify(vocabularyService, never()).save(any(), any());
    }

    @Test
    void putWithNullBodyThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.put(null));
        verify(vocabularyService, never()).save(any(), any());
    }

    // ── POST /api/ai/admin/vocabulary/revert ─────────────────────────────────

    @Test
    void revertDelegatesToServiceAndReturnsRefreshedState() {
        OffsetDateTime now = OffsetDateTime.now();
        VocabState packState = new VocabState("duplex => 2-unit", "pack", now, "admin-api", 1);
        when(vocabularyService.state()).thenReturn(packState);

        VocabState result = controller.revert();

        verify(vocabularyService).revert("admin-api");
        assertEquals(packState, result);
    }

    // ── GET /api/ai/admin/vocabulary/history ─────────────────────────────────

    @Test
    void historyMapsRevisionNumberAndRevertedFlag() {
        OffsetDateTime t1 = OffsetDateTime.now().minusDays(2);
        OffsetDateTime t2 = OffsetDateTime.now().minusDays(1);
        OffsetDateTime t3 = OffsetDateTime.now();

        // Returned newest-first
        VocabularyRevision rev3 = makeRevision("Third content", t3, "carol");
        VocabularyRevision rev2 = makeRevision(null, t2, "bob");      // revert marker
        VocabularyRevision rev1 = makeRevision("First content", t1, "alice");

        when(vocabularyService.history()).thenReturn(List.of(rev3, rev2, rev1));

        List<Map<String, Object>> history = controller.history();

        assertEquals(3, history.size());

        // Newest (index 0) gets revision = list.size() = 3
        assertEquals(3,       history.get(0).get("revision"));
        assertEquals(t3,      history.get(0).get("createdAt"));
        assertEquals("carol", history.get(0).get("createdBy"));
        assertEquals(false,   history.get(0).get("reverted"));
        assertEquals("Third content", history.get(0).get("content"));

        // Middle (index 1) is a revert marker
        assertEquals(2,     history.get(1).get("revision"));
        assertEquals(true,  history.get(1).get("reverted"));
        assertNull(history.get(1).get("content"), "revert marker content should be null");

        // Oldest (index 2) gets revision = 1
        assertEquals(1,       history.get(2).get("revision"));
        assertEquals(false,   history.get(2).get("reverted"));
        assertEquals("First content", history.get(2).get("content"));
    }

    // ── GET /api/ai/admin/vocabulary/preview ─────────────────────────────────

    @Test
    void previewReturnsExpandedPhrase() {
        when(vocabularyService.previewExpansion("owner occupied duplex"))
                .thenReturn("owner occupied duplex principal residence 2-unit");

        Map<String, String> result = controller.preview("owner occupied duplex");

        assertEquals("owner occupied duplex", result.get("original"));
        assertTrue(result.get("expanded").contains("principal residence"), result.toString());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private VocabularyRevision makeRevision(String content, OffsetDateTime at, String by) {
        VocabularyRevision r = new VocabularyRevision(content, by);
        // inject createdAt via reflection (no public setter)
        try {
            var f = VocabularyRevision.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(r, at);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return r;
    }
}
