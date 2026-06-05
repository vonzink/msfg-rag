package com.msfg.rag.service.ai;

import com.msfg.rag.service.retrieval.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Builds the final prompt sent to the LLM, following the locked template
 * in rag.md. The rules here are compliance-critical: the model must answer
 * only from the supplied source context, never from general knowledge.
 */
@Service
public class PromptBuilderService {

    public static final String DISCLAIMER =
            "This answer is for general mortgage education only and is not a loan approval, "
            + "underwriting decision, legal advice, or tax advice.";

    private static final String TEMPLATE = """
            You are an AI mortgage education assistant for Mountain State Financial Group.

            You must answer ONLY using the approved source context provided below.

            Rules:
            1. Do not answer from general knowledge.
            2. Do not invent mortgage guidelines.
            3. Do not provide loan approval, legal advice, tax advice, or underwriting decisions.
            4. If the source context does not answer the question, say you cannot find enough information.
            5. Use careful wording such as "may," "generally," and "subject to full loan review."
            6. Include citations from the provided source context.
            7. Keep the answer clear and borrower-friendly.

            Approved Source Context:
            %s

            User Question:
            %s

            Return ONLY valid JSON in exactly this format, with no other text before or after it:

            {
              "answer": "...",
              "citations": [
                {
                  "source_name": "...",
                  "document_name": "...",
                  "section": "...",
                  "page_number": "...",
                  "effective_date": "..."
                }
              ],
              "confidence": 0.0,
              "human_escalation_required": false,
              "disclaimer": "%s"
            }
            """;

    public String build(String question, List<RetrievedChunk> chunks) {
        return TEMPLATE.formatted(formatContext(chunks), question, DISCLAIMER);
    }

    /**
     * Each chunk is labeled [Source N] with its citation metadata so the model
     * can attribute statements to specific sources.
     */
    private String formatContext(List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return "(no source context found)";
        }
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (RetrievedChunk chunk : chunks) {
            sb.append("[Source ").append(n++).append("]\n");
            sb.append("source_name: ").append(chunk.sourceName()).append('\n');
            sb.append("document_name: ").append(chunk.documentName()).append('\n');
            if (chunk.section() != null) {
                sb.append("section: ").append(chunk.section()).append('\n');
            }
            if (chunk.pageNumber() != null) {
                sb.append("page_number: ").append(chunk.pageNumber()).append('\n');
            }
            if (chunk.effectiveDate() != null) {
                sb.append("effective_date: ").append(chunk.effectiveDate()).append('\n');
            }
            sb.append("content:\n").append(chunk.content()).append("\n\n");
        }
        return sb.toString().strip();
    }
}
