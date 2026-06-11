package com.msfg.rag.controller;

import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.repository.DocumentChunkRepository;
import com.msfg.rag.repository.MortgageDocumentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Brain identity + corpus counts for the dashboard shell and corpus screen. */
@RestController
@RequestMapping("/api/ai/admin/stats")
public class AdminStatsController {

    public record BrainDto(String companyName, String slug) {}
    public record CorpusDto(long activeDocuments, long totalDocuments, long chunks) {}
    public record StatsDto(BrainDto brain, CorpusDto corpus) {}

    private final DomainPack pack;
    private final MortgageDocumentRepository documents;
    private final DocumentChunkRepository chunks;

    public AdminStatsController(DomainPack pack,
                                MortgageDocumentRepository documents,
                                DocumentChunkRepository chunks) {
        this.pack = pack;
        this.documents = documents;
        this.chunks = chunks;
    }

    @GetMapping
    public StatsDto stats() {
        return new StatsDto(
                new BrainDto(pack.companyName(), pack.slug()),
                new CorpusDto(documents.countByActiveTrue(), documents.count(), chunks.count()));
    }
}
