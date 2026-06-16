package com.msfg.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Public website question request.
 */
public record AskRequest(
        UUID conversationId,           // optional: continue an existing conversation

        @NotBlank(message = "sessionId is required")
        @Size(max = 255)
        String sessionId,

        @NotBlank(message = "question is required")
        @Size(max = 2000, message = "question must be 2000 characters or fewer")
        String question,

        @Size(max = 50)
        String loanType,               // optional context, e.g. "conventional"

        @Size(max = 2)
        String state,                  // optional, e.g. "CO"

        @Size(max = 200)
        String pageRoute,              // optional: page the visitor is on, e.g. "/loan-options"

        @Size(max = 20)
        String surface                 // optional audience: "PUBLIC" | "INTERNAL" | "BOTH"
                                       // (bound as String; valueOf'd in-service for a clean 400)
) {
}
