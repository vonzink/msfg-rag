package com.msfg.rag.service.ingestion;

import com.msfg.rag.domain.DocumentChunk;
import com.msfg.rag.domain.MortgageDocument;
import com.msfg.rag.domain.SourceType;
import com.msfg.rag.service.sync.Sha256;
import com.msfg.rag.repository.DocumentChunkRepository;
import com.msfg.rag.repository.MortgageDocumentRepository;
import com.msfg.rag.service.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full ingestion pipeline: store file -> extract text -> chunk -> embed -> persist.
 * This is what turns an uploaded guideline PDF into searchable knowledge.
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    /** OpenAI embedding batch limit is generous; 64 keeps request sizes sane. */
    private static final int EMBEDDING_BATCH_SIZE = 64;

    private final StorageService storageService;
    private final TextExtractionService textExtractionService;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final MortgageDocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;

    public DocumentIngestionService(StorageService storageService,
                                    TextExtractionService textExtractionService,
                                    ChunkingService chunkingService,
                                    EmbeddingService embeddingService,
                                    MortgageDocumentRepository documentRepository,
                                    DocumentChunkRepository chunkRepository) {
        this.storageService = storageService;
        this.textExtractionService = textExtractionService;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
    }

    /**
     * Ingests a new guideline document end to end.
     */
    @Transactional
    public MortgageDocument ingest(String fileName,
                                   byte[] fileBytes,
                                   String title,
                                   String sourceName,
                                   SourceType sourceType,
                                   String documentVersion,
                                   LocalDate effectiveDate,
                                   LocalDate expirationDate) {

        String storageKey = storageService.store(fileName, new ByteArrayInputStream(fileBytes));

        MortgageDocument document = new MortgageDocument();
        document.setTitle(title);
        document.setSourceName(sourceName);
        document.setSourceType(sourceType);
        document.setFileName(fileName);
        document.setS3Key(storageKey);
        document.setDocumentVersion(documentVersion);
        document.setContentSha256(Sha256.hex(fileBytes));
        document.setEffectiveDate(effectiveDate);
        document.setExpirationDate(expirationDate);
        document = documentRepository.save(document);

        int chunkCount = extractChunkAndEmbed(document, new ByteArrayInputStream(fileBytes));
        log.info("Ingested document '{}' ({}): {} chunks", title, fileName, chunkCount);
        return document;
    }

    /**
     * Re-runs extraction, chunking, and embedding for an existing document,
     * e.g. after chunking settings change or an embedding model upgrade.
     */
    @Transactional
    public int reindex(UUID documentId) {
        MortgageDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        chunkRepository.deleteByDocumentId(documentId);
        try (InputStream content = storageService.retrieve(document.getS3Key())) {
            int chunkCount = extractChunkAndEmbed(document, content);
            log.info("Reindexed document '{}': {} chunks", document.getTitle(), chunkCount);
            return chunkCount;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read stored document for reindex: " + documentId, e);
        }
    }

    /**
     * Hard-deletes a document: its chunks, its stored file, and the row.
     * Chunk + row removals are transactional; the file delete runs last so a
     * storage error does not block the DB cleanup, and a null storage key
     * (a document with no stored file) is skipped.
     */
    @Transactional
    public void delete(UUID documentId) {
        MortgageDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        chunkRepository.deleteByDocumentId(documentId);
        String storageKey = document.getS3Key();
        if (storageKey != null) {
            storageService.delete(storageKey);
        }
        documentRepository.delete(document);
        log.info("Deleted document '{}' ({})", document.getTitle(), document.getFileName());
    }

    private int extractChunkAndEmbed(MortgageDocument document, InputStream content) {
        String text = textExtractionService.extract(content, document.getFileName());
        if (text.isBlank()) {
            throw new IllegalStateException(
                    "No text could be extracted from " + document.getFileName()
                    + " — is it a scanned image PDF? OCR is not supported yet.");
        }

        List<TextChunk> textChunks = chunkingService.chunk(text);
        List<DocumentChunk> entities = new ArrayList<>(textChunks.size());

        // Embed in batches to limit API request size.
        for (int start = 0; start < textChunks.size(); start += EMBEDDING_BATCH_SIZE) {
            List<TextChunk> batch = textChunks.subList(
                    start, Math.min(start + EMBEDDING_BATCH_SIZE, textChunks.size()));
            List<float[]> embeddings = embeddingService.embedBatch(
                    batch.stream().map(TextChunk::content).toList());

            for (int i = 0; i < batch.size(); i++) {
                TextChunk tc = batch.get(i);
                DocumentChunk entity = new DocumentChunk();
                entity.setDocument(document);
                entity.setChunkIndex(tc.index());
                entity.setContent(tc.content());
                entity.setTokenCount(tc.tokenCount());
                entity.setEmbedding(embeddings.get(i));

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source_name", document.getSourceName());
                metadata.put("source_type", document.getSourceType().name());
                metadata.put("document_name", document.getFileName());
                if (tc.heading() != null) {
                    metadata.put("section", tc.heading());
                }
                if (document.getEffectiveDate() != null) {
                    metadata.put("effective_date", document.getEffectiveDate().toString());
                }
                if (document.getDocumentVersion() != null) {
                    metadata.put("version", document.getDocumentVersion());
                }
                entity.setMetadata(metadata);
                entities.add(entity);
            }
        }

        chunkRepository.saveAll(entities);
        return entities.size();
    }
}
