package com.msfg.rag.service.ingestion;

import com.msfg.rag.domain.MortgageDocument;
import com.msfg.rag.domain.SourceType;
import com.msfg.rag.repository.DocumentChunkRepository;
import com.msfg.rag.repository.MortgageDocumentRepository;
import com.msfg.rag.service.storage.StorageService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIngestionServiceTest {

    private final StorageService storageService = mock(StorageService.class);
    private final MortgageDocumentRepository documentRepository = mock(MortgageDocumentRepository.class);
    private final DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);

    private final DocumentIngestionService service = new DocumentIngestionService(
            storageService,
            mock(TextExtractionService.class),
            mock(ChunkingService.class),
            mock(EmbeddingService.class),
            documentRepository,
            chunkRepository);

    private MortgageDocument doc(UUID id, String key) {
        MortgageDocument d = new MortgageDocument();
        d.setTitle("HUD 4000.1");
        d.setSourceName("HUD");
        d.setSourceType(SourceType.INTERNAL_POLICY);
        d.setFileName("hud.pdf");
        d.setS3Key(key);
        return d;
    }

    @Test
    void deleteRemovesChunksThenFileThenRow() {
        UUID id = UUID.randomUUID();
        MortgageDocument d = doc(id, "abc_hud.pdf");
        when(documentRepository.findById(id)).thenReturn(Optional.of(d));

        service.delete(id);

        var order = inOrder(chunkRepository, storageService, documentRepository);
        order.verify(chunkRepository).deleteByDocumentId(id);
        order.verify(storageService).delete("abc_hud.pdf");
        order.verify(documentRepository).delete(d);
    }

    @Test
    void deleteThrowsWhenDocumentMissing() {
        UUID id = UUID.randomUUID();
        when(documentRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.delete(id));
        verify(chunkRepository, never()).deleteByDocumentId(any());
    }

    @Test
    void deleteSkipsStorageWhenS3KeyIsNull() {
        UUID id = UUID.randomUUID();
        MortgageDocument d = doc(id, null);
        when(documentRepository.findById(id)).thenReturn(Optional.of(d));

        service.delete(id);

        verify(chunkRepository).deleteByDocumentId(id);
        verify(documentRepository).delete(d);
        verify(storageService, never()).delete(any());
    }
}
