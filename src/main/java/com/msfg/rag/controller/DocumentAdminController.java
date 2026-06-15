package com.msfg.rag.controller;

import com.msfg.rag.domain.MortgageDocument;
import com.msfg.rag.domain.SourceType;
import com.msfg.rag.dto.DocumentDto;
import com.msfg.rag.repository.MortgageDocumentRepository;
import com.msfg.rag.service.ingestion.DocumentIngestionService;
import com.msfg.rag.service.retrieval.RetrievalResult;
import com.msfg.rag.service.retrieval.RetrievalService;
import com.msfg.rag.service.sync.SyncReport;
import com.msfg.rag.service.sync.SyncService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin endpoints for managing guideline documents and testing retrieval.
 * Protected by AdminApiKeyFilter (X-Admin-Api-Key header) until Cognito
 * is wired in at deployment.
 */
@RestController
@RequestMapping("/api/ai/documents")
public class DocumentAdminController {

    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("pdf", "docx", "txt", "md", "markdown", "html", "htm");

    private final DocumentIngestionService ingestionService;
    private final MortgageDocumentRepository documentRepository;
    private final RetrievalService retrievalService;
    private final SyncService syncService;

    public DocumentAdminController(DocumentIngestionService ingestionService,
                                   MortgageDocumentRepository documentRepository,
                                   RetrievalService retrievalService,
                                   SyncService syncService) {
        this.ingestionService = ingestionService;
        this.documentRepository = documentRepository;
        this.retrievalService = retrievalService;
        this.syncService = syncService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentDto> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam("sourceName") String sourceName,
            @RequestParam("sourceType") SourceType sourceType,
            @RequestParam(value = "documentVersion", required = false) String documentVersion,
            @RequestParam(value = "effectiveDate", required = false) LocalDate effectiveDate,
            @RequestParam(value = "expirationDate", required = false) LocalDate expirationDate)
            throws IOException {

        String fileName = file.getOriginalFilename() == null ? "upload" : file.getOriginalFilename();
        String extension = fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
                : "";
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException(
                    "Unsupported file type '" + extension + "'. Allowed: " + ALLOWED_EXTENSIONS);
        }

        MortgageDocument document = ingestionService.ingest(
                fileName, file.getBytes(), title, sourceName, sourceType,
                documentVersion, effectiveDate, expirationDate);

        return ResponseEntity.ok(DocumentDto.from(document));
    }

    @GetMapping
    public List<DocumentDto> list() {
        return documentRepository.findAll().stream().map(DocumentDto::from).toList();
    }

    @PostMapping("/{id}/reindex")
    public ResponseEntity<Map<String, Object>> reindex(@PathVariable UUID id) {
        int chunkCount = ingestionService.reindex(id);
        return ResponseEntity.ok(Map.of("documentId", id, "chunkCount", chunkCount));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<DocumentDto> activate(@PathVariable UUID id) {
        return setActive(id, true);
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<DocumentDto> deactivate(@PathVariable UUID id) {
        return setActive(id, false);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable UUID id) {
        ingestionService.delete(id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }

    /**
     * Sync the S3 corpus into the brain (dashboard "Sync now"). dryRun=true
     * returns the plan without changing anything.
     */
    @PostMapping("/sync")
    public SyncReport sync(@RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun) {
        return syncService.sync(dryRun);
    }

    /**
     * Admin retrieval test: see exactly which chunks would be retrieved for a
     * question, with scores, before any AI answer is generated.
     */
    @GetMapping("/test-retrieval")
    public RetrievalResult testRetrieval(@RequestParam("question") String question) {
        return retrievalService.retrieve(question);
    }

    private ResponseEntity<DocumentDto> setActive(UUID id, boolean active) {
        MortgageDocument document = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));
        document.setActive(active);
        return ResponseEntity.ok(DocumentDto.from(documentRepository.save(document)));
    }
}
