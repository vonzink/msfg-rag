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
}
