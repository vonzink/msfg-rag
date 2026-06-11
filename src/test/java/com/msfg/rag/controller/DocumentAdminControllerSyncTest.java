package com.msfg.rag.controller;

import com.msfg.rag.repository.MortgageDocumentRepository;
import com.msfg.rag.service.ingestion.DocumentIngestionService;
import com.msfg.rag.service.retrieval.RetrievalService;
import com.msfg.rag.service.sync.SyncReport;
import com.msfg.rag.service.sync.SyncService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentAdminControllerSyncTest {

    private final SyncService syncService = mock(SyncService.class);
    private final DocumentAdminController controller = new DocumentAdminController(
            mock(DocumentIngestionService.class),
            mock(MortgageDocumentRepository.class),
            mock(RetrievalService.class),
            syncService);

    @Test
    void syncPassesDryRunFlagThrough() {
        SyncReport report = new SyncReport(true, Map.of("skip", 1), List.of());
        when(syncService.sync(true)).thenReturn(report);

        assertEquals(report, controller.sync(true));
        verify(syncService).sync(true);
    }

    @Test
    void syncDefaultsToExecute() {
        SyncReport report = new SyncReport(false, Map.of(), List.of());
        when(syncService.sync(false)).thenReturn(report);

        assertEquals(report, controller.sync(false));
    }
}
