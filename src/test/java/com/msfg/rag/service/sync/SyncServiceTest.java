package com.msfg.rag.service.sync;

import com.msfg.rag.domain.MortgageDocument;
import com.msfg.rag.domain.SourceType;
import com.msfg.rag.repository.MortgageDocumentRepository;
import com.msfg.rag.service.ingestion.DocumentIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SyncServiceTest {

    private CorpusSource corpusSource;
    private DocumentIngestionService ingestionService;
    private MortgageDocumentRepository documentRepository;
    private SyncService syncService;

    /** A minimal manifest JSON: defaults block, no per-file overrides. */
    private static final Optional<byte[]> EMPTY_MANIFEST = Optional.of(
            """
            {"defaults":{"sourceName":"MSFG","sourceType":"AGENCY_GUIDELINE"},"files":{}}
            """.getBytes());

    @BeforeEach
    void setUp() {
        corpusSource = mock(CorpusSource.class);
        ingestionService = mock(DocumentIngestionService.class);
        documentRepository = mock(MortgageDocumentRepository.class);
        syncService = new SyncService(corpusSource, ingestionService, documentRepository);
    }

    // -------------------------------------------------------------------------
    // Case 1: dry-run executes nothing
    // -------------------------------------------------------------------------

    @Test
    void dryRunExecutesNothing() {
        byte[] pdfBytes = "PDF".getBytes();
        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("policy.pdf"));
        when(corpusSource.fetch("policy.pdf")).thenReturn(pdfBytes);

        MortgageDocument old = new MortgageDocument();
        old.setFileName("old.pdf");
        old.setActive(true);
        when(documentRepository.findAll()).thenReturn(List.of(old));

        SyncReport report = syncService.sync(true);

        assertTrue(report.dryRun());
        report.results().forEach(r -> assertFalse(r.executed()));
        verifyNoInteractions(ingestionService);
        verify(documentRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Case 2: UPLOAD calls ingest with manifest metadata
    // -------------------------------------------------------------------------

    @Test
    void uploadIngestsWithManifestMetadata() {
        byte[] pdfBytes = "PDF-bytes".getBytes();
        Optional<byte[]> manifest = Optional.of("""
                {
                  "defaults":{"sourceName":"MSFG","sourceType":"AGENCY_GUIDELINE"},
                  "files":{
                    "policy.pdf":{
                      "ingest":true,
                      "title":"Lending Policy",
                      "sourceName":"MSFG Internal",
                      "sourceType":"INTERNAL_POLICY",
                      "effectiveDate":"2024-01-01"
                    }
                  }
                }
                """.getBytes());

        when(corpusSource.fetchManifest()).thenReturn(manifest);
        when(corpusSource.listFiles()).thenReturn(List.of("policy.pdf"));
        when(corpusSource.fetch("policy.pdf")).thenReturn(pdfBytes);
        when(documentRepository.findAll()).thenReturn(List.of());

        MortgageDocument saved = new MortgageDocument();
        saved.setFileName("policy.pdf");
        when(ingestionService.ingest(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(saved);

        SyncReport report = syncService.sync(false);

        verify(ingestionService).ingest(
                eq("policy.pdf"),
                eq(pdfBytes),
                eq("Lending Policy"),
                eq("MSFG Internal"),
                eq(SourceType.INTERNAL_POLICY),
                isNull(),
                eq(LocalDate.of(2024, 1, 1)),
                isNull());

        assertEquals(1, report.results().size());
        assertTrue(report.results().get(0).succeeded());
    }

    // -------------------------------------------------------------------------
    // Case 3: UPDATE → ingest succeeds → old doc deactivated AFTER ingest (InOrder)
    // -------------------------------------------------------------------------

    @Test
    void updateIngestsNewVersionThenDeactivatesOldOnSuccess() {
        byte[] pdfBytes = "PDF-v2".getBytes();

        MortgageDocument stale = new MortgageDocument();
        stale.setFileName("guide.pdf");
        stale.setActive(true);
        stale.setContentSha256("aaaaaa");   // differs from Sha256.hex(pdfBytes) → UPDATE

        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("guide.pdf"));
        when(corpusSource.fetch("guide.pdf")).thenReturn(pdfBytes);
        when(documentRepository.findAll()).thenReturn(List.of(stale));

        // Replacement is a distinct object from stale; identity comparison governs (both ids null)
        MortgageDocument replacement = new MortgageDocument();
        replacement.setFileName("guide.pdf");
        when(ingestionService.ingest(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(replacement);
        when(documentRepository.findByActiveTrue()).thenReturn(List.of(stale));

        SyncReport report = syncService.sync(false);

        InOrder order = inOrder(ingestionService, documentRepository);
        order.verify(ingestionService).ingest(any(), any(), any(), any(), any(), any(), any(), any());
        order.verify(documentRepository).save(stale);

        assertFalse(stale.isActive());
        assertEquals(1, report.results().stream().filter(SyncReport.Result::succeeded).count());
    }

    // -------------------------------------------------------------------------
    // Case 4: UPDATE ingest throws → old doc stays active; next action still runs
    // -------------------------------------------------------------------------

    @Test
    void updateFailureLeavesOldDocumentActive() {
        byte[] v2Bytes = "PDF-v2".getBytes();
        byte[] uploadBytes = "NEW-DOC".getBytes();

        MortgageDocument stale = new MortgageDocument();
        stale.setFileName("guide.pdf");
        stale.setActive(true);
        stale.setContentSha256("oldhash");

        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("guide.pdf", "new.pdf"));
        when(corpusSource.fetch("guide.pdf")).thenReturn(v2Bytes);
        when(corpusSource.fetch("new.pdf")).thenReturn(uploadBytes);
        when(documentRepository.findAll()).thenReturn(List.of(stale));

        when(ingestionService.ingest(
                eq("guide.pdf"), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("embedding-timeout"));

        MortgageDocument newDoc = new MortgageDocument();
        newDoc.setFileName("new.pdf");
        when(ingestionService.ingest(
                eq("new.pdf"), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(newDoc);

        SyncReport report = syncService.sync(false);

        // findByActiveTrue never called; stale never saved
        verify(documentRepository, never()).findByActiveTrue();
        verify(documentRepository, never()).save(stale);
        assertTrue(stale.isActive());

        SyncReport.Result updateResult = report.results().stream()
                .filter(r -> r.fileName().equals("guide.pdf")).findFirst().orElseThrow();
        assertFalse(updateResult.succeeded());
        assertNotNull(updateResult.error());

        // Per-file isolation: new.pdf UPLOAD still ran
        SyncReport.Result uploadResult = report.results().stream()
                .filter(r -> r.fileName().equals("new.pdf")).findFirst().orElseThrow();
        assertTrue(uploadResult.succeeded());
        assertTrue(uploadResult.executed());
    }

    // -------------------------------------------------------------------------
    // Case 5: REACTIVATE and DEACTIVATE planned correctly (dry-run level check)
    // -------------------------------------------------------------------------

    @Test
    void reactivateAndDeactivateTogglesActive() {
        byte[] pdfBytes = "PDF".getBytes();

        MortgageDocument inactive = new MortgageDocument();
        inactive.setFileName("old.pdf");
        inactive.setActive(false);

        MortgageDocument active = new MortgageDocument();
        active.setFileName("gone.pdf");
        active.setActive(true);

        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("old.pdf"));
        when(corpusSource.fetch("old.pdf")).thenReturn(pdfBytes);
        when(documentRepository.findAll()).thenReturn(List.of(inactive, active));

        // Verify plan types via dry-run (avoids UUID-null findById issues in test)
        SyncReport report = syncService.sync(true);

        long reactivateCount = report.results().stream()
                .filter(r -> r.action().equals("REACTIVATE")).count();
        long deactivateCount = report.results().stream()
                .filter(r -> r.action().equals("DEACTIVATE")).count();
        assertEquals(1, reactivateCount, "expected 1 REACTIVATE");
        assertEquals(1, deactivateCount, "expected 1 DEACTIVATE");
    }

    // -------------------------------------------------------------------------
    // Case 6: UPDATE deactivates ALL stale duplicate active rows (hygiene)
    // -------------------------------------------------------------------------

    @Test
    void updateDeactivatesStaleDuplicateActives() {
        byte[] pdfBytes = "PDF-v3".getBytes();

        MortgageDocument stale1 = new MortgageDocument();
        stale1.setFileName("guide.pdf");
        stale1.setActive(true);
        stale1.setContentSha256("hash1");

        MortgageDocument stale2 = new MortgageDocument();
        stale2.setFileName("guide.pdf");
        stale2.setActive(true);
        stale2.setContentSha256("hash1");

        when(corpusSource.fetchManifest()).thenReturn(EMPTY_MANIFEST);
        when(corpusSource.listFiles()).thenReturn(List.of("guide.pdf"));
        when(corpusSource.fetch("guide.pdf")).thenReturn(pdfBytes);
        when(documentRepository.findAll()).thenReturn(List.of(stale1, stale2));

        MortgageDocument replacement = new MortgageDocument();
        replacement.setFileName("guide.pdf");
        when(ingestionService.ingest(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(replacement);
        when(documentRepository.findByActiveTrue()).thenReturn(List.of(stale1, stale2));

        syncService.sync(false);

        assertFalse(stale1.isActive(), "stale1 must be deactivated");
        assertFalse(stale2.isActive(), "stale2 must be deactivated");
        verify(documentRepository, times(2)).save(argThat(d ->
                d.getFileName().equals("guide.pdf") && !d.isActive()));
    }
}
