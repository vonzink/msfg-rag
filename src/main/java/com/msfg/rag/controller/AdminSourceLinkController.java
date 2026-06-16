package com.msfg.rag.controller;

import com.msfg.rag.dto.SourceLinkDto;
import com.msfg.rag.dto.SourceLinkRequest;
import com.msfg.rag.service.retrieval.SourceLinkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin CRUD for the source-link registry. Gated by AdminApiKeyFilter (the
 * /api/ai/admin prefix). All validation / not-found / bad-enum cases throw
 * IllegalArgumentException, mapped to HTTP 400 by GlobalExceptionHandler — no
 * try/catch or per-controller @ExceptionHandler here.
 */
@RestController
@RequestMapping("/api/ai/admin/source-links")
public class AdminSourceLinkController {

    private static final String UPDATED_BY = "admin-api";

    private final SourceLinkService service;

    public AdminSourceLinkController(SourceLinkService service) {
        this.service = service;
    }

    @GetMapping
    public List<SourceLinkDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public SourceLinkDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public SourceLinkDto create(@RequestBody SourceLinkRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return service.create(body, UPDATED_BY);
    }

    @PatchMapping("/{id}")
    public SourceLinkDto update(@PathVariable UUID id, @RequestBody SourceLinkRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return service.update(id, body, UPDATED_BY);
    }

    @PostMapping("/{id}/activate")
    public SourceLinkDto activate(@PathVariable UUID id) {
        return service.setActive(id, true, UPDATED_BY);
    }

    @PostMapping("/{id}/deactivate")
    public SourceLinkDto deactivate(@PathVariable UUID id) {
        return service.setActive(id, false, UPDATED_BY);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }
}
