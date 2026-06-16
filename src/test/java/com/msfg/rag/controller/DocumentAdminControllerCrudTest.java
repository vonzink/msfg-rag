package com.msfg.rag.controller;

import com.msfg.rag.repository.MortgageDocumentRepository;
import com.msfg.rag.service.ingestion.DocumentIngestionService;
import com.msfg.rag.service.retrieval.RetrievalService;
import com.msfg.rag.service.sync.SyncService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DocumentAdminControllerCrudTest {

    private final DocumentIngestionService ingestionService = mock(DocumentIngestionService.class);
    private final MortgageDocumentRepository documentRepository = mock(MortgageDocumentRepository.class);
    private final DocumentAdminController controller = new DocumentAdminController(
            ingestionService,
            documentRepository,
            mock(RetrievalService.class),
            mock(SyncService.class));

    @Test
    void deleteDelegatesToIngestionServiceAndReturnsOk() {
        UUID id = UUID.randomUUID();

        var response = controller.delete(id);

        verify(ingestionService).delete(id);
        assertEquals(200, response.getStatusCode().value());
        assertTrue(Boolean.TRUE.equals(response.getBody().get("deleted")));
    }

    @org.junit.jupiter.api.Test
    void updateAppliesMetadataAndSaves() {
        UUID id = UUID.randomUUID();
        com.msfg.rag.domain.MortgageDocument doc = new com.msfg.rag.domain.MortgageDocument();
        doc.setTitle("old");
        doc.setSourceName("old");
        doc.setSourceType(com.msfg.rag.domain.SourceType.AGENCY_GUIDELINE);
        doc.setFileName("f.pdf");
        org.mockito.Mockito.when(documentRepository.findById(id))
                .thenReturn(java.util.Optional.of(doc));
        org.mockito.Mockito.when(documentRepository.save(doc)).thenReturn(doc);

        var req = new com.msfg.rag.dto.DocumentUpdateRequest(
                "New Title", "HUD", "INTERNAL_POLICY", "v2",
                java.time.LocalDate.parse("2026-01-01"), null);

        var dto = controller.update(id, req).getBody();

        assertEquals("New Title", doc.getTitle());
        assertEquals("HUD", doc.getSourceName());
        assertEquals(com.msfg.rag.domain.SourceType.INTERNAL_POLICY, doc.getSourceType());
        assertEquals("v2", doc.getDocumentVersion());
        assertEquals(java.time.LocalDate.parse("2026-01-01"), doc.getEffectiveDate());
        assertEquals("New Title", dto.title());
        verify(documentRepository).save(doc);
    }

    @org.junit.jupiter.api.Test
    void updateRejectsBlankTitle() {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.when(documentRepository.findById(id))
                .thenReturn(java.util.Optional.of(new com.msfg.rag.domain.MortgageDocument()));
        var req = new com.msfg.rag.dto.DocumentUpdateRequest(
                "   ", "HUD", "INTERNAL_POLICY", null, null, null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.update(id, req));
    }

    @org.junit.jupiter.api.Test
    void updateRejectsUnknownSourceType() {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.when(documentRepository.findById(id))
                .thenReturn(java.util.Optional.of(new com.msfg.rag.domain.MortgageDocument()));
        var req = new com.msfg.rag.dto.DocumentUpdateRequest(
                "T", "HUD", "NOPE", null, null, null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.update(id, req));
    }

    @org.junit.jupiter.api.Test
    void updateRejectsMissingSourceType() {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.when(documentRepository.findById(id))
                .thenReturn(java.util.Optional.of(new com.msfg.rag.domain.MortgageDocument()));
        var req = new com.msfg.rag.dto.DocumentUpdateRequest(
                "T", "HUD", null, null, null, null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.update(id, req));
    }

    @org.junit.jupiter.api.Test
    void updateThrowsWhenDocumentMissing() {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.when(documentRepository.findById(id))
                .thenReturn(java.util.Optional.empty());
        var req = new com.msfg.rag.dto.DocumentUpdateRequest(
                "T", "HUD", "INTERNAL_POLICY", null, null, null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.update(id, req));
    }
}
