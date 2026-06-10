package com.msfg.rag.controller;

import com.msfg.rag.dto.AskRequest;
import com.msfg.rag.dto.AskResponse;
import com.msfg.rag.service.AskService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public website endpoint. Rate limiting is applied by RateLimitFilter.
 */
@RestController
@RequestMapping("/api/ai/${brain.slug:mortgage}")
public class AskController {

    private final AskService askService;

    public AskController(AskService askService) {
        this.askService = askService;
    }

    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request) {
        return ResponseEntity.ok(askService.ask(request));
    }
}
