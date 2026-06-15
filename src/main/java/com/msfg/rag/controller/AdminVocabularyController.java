package com.msfg.rag.controller;

import com.msfg.rag.domain.VocabularyRevision;
import com.msfg.rag.service.retrieval.VocabularyService;
import com.msfg.rag.service.retrieval.VocabularyService.VocabState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for the editable retrieval vocabulary (borrower/broker
 * synonyms). Protected by AdminApiKeyFilter (the /api/ai/admin prefix is gated).
 * Sibling to AdminRulesController; this layer affects retrieval, not the prompt.
 */
@RestController
@RequestMapping("/api/ai/admin/vocabulary")
public class AdminVocabularyController {

    public record ContentBody(String content) {}

    private static final String UPDATED_BY = "admin-api";

    private final VocabularyService vocabulary;

    public AdminVocabularyController(VocabularyService vocabulary) {
        this.vocabulary = vocabulary;
    }

    @GetMapping
    public VocabState getState() {
        return vocabulary.state();
    }

    @PutMapping
    public VocabState put(@RequestBody ContentBody body) {
        if (body == null || body.content() == null || body.content().isBlank()) {
            throw new IllegalArgumentException("content must be non-blank");
        }
        vocabulary.save(body.content(), UPDATED_BY);
        return vocabulary.state();
    }

    @PostMapping("/revert")
    public VocabState revert() {
        vocabulary.revert(UPDATED_BY);
        return vocabulary.state();
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history() {
        List<VocabularyRevision> revisions = vocabulary.history();
        int total = revisions.size();
        List<Map<String, Object>> result = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            VocabularyRevision rev = revisions.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("revision", total - i);
            entry.put("createdAt", rev.getCreatedAt());
            entry.put("createdBy", rev.getCreatedBy());
            entry.put("reverted", rev.getContent() == null);
            entry.put("content", rev.getContent());
            result.add(entry);
        }
        return result;
    }

    @GetMapping("/preview")
    public Map<String, String> preview(@RequestParam("q") String q) {
        return Map.of("original", q, "expanded", vocabulary.previewExpansion(q));
    }
}
