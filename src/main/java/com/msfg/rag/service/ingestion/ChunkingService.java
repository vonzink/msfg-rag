package com.msfg.rag.service.ingestion;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.msfg.rag.config.RagProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Recursive, paragraph-aware chunker.
 *
 * Strategy (per rag.md):
 * - split on paragraph boundaries first, never mid-sentence when avoidable
 * - target ~1000 tokens per chunk, hard max 1200
 * - carry ~150 tokens of overlap between consecutive chunks so guideline
 *   rules that span a boundary are retrievable from either side
 * - track the nearest heading so each chunk can cite its section
 */
@Service
public class ChunkingService {

    // Matches markdown headings and ALL-CAPS / numbered section lines
    // commonly produced by Tika from guideline PDFs.
    private static final Pattern HEADING = Pattern.compile(
            "^(#{1,6}\\s+.+|[A-Z][A-Z0-9 ,/&-]{8,}|\\d+(\\.\\d+)*\\s+[A-Z].{3,80})$");

    private final Encoding encoding = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    private final int targetTokens;
    private final int maxTokens;
    private final int overlapTokens;

    public ChunkingService(RagProperties properties) {
        this.targetTokens = properties.chunking().targetTokens();
        this.maxTokens = properties.chunking().maxTokens();
        this.overlapTokens = properties.chunking().overlapTokens();
    }

    public List<TextChunk> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<TextChunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentHeading = null;
        String chunkHeading = null;
        int index = 0;

        for (String paragraph : text.split("\n\n")) {
            String para = paragraph.strip();
            if (para.isEmpty()) {
                continue;
            }
            if (HEADING.matcher(para).matches()) {
                currentHeading = para.replaceFirst("^#{1,6}\\s+", "").strip();
            }

            int paraTokens = countTokens(para);
            int currentTokens = countTokens(current.toString());

            // Oversized single paragraph (often a table): split by sentences.
            if (paraTokens > maxTokens) {
                if (!current.isEmpty()) {
                    chunks.add(new TextChunk(index++, current.toString().strip(),
                            currentTokens, chunkHeading));
                    current = new StringBuilder(overlapTail(current.toString()));
                }
                for (String piece : splitOversized(para)) {
                    chunks.add(new TextChunk(index++, piece, countTokens(piece), currentHeading));
                }
                chunkHeading = currentHeading;
                continue;
            }

            if (currentTokens + paraTokens > targetTokens && !current.isEmpty()) {
                chunks.add(new TextChunk(index++, current.toString().strip(),
                        currentTokens, chunkHeading));
                current = new StringBuilder(overlapTail(current.toString()));
                chunkHeading = currentHeading;
            }

            if (current.isEmpty()) {
                chunkHeading = currentHeading;
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(para);
        }

        if (!current.toString().isBlank()) {
            String content = current.toString().strip();
            chunks.add(new TextChunk(index, content, countTokens(content), chunkHeading));
        }
        return chunks;
    }

    public int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /** Last ~overlapTokens worth of text, cut at a sentence boundary when possible. */
    private String overlapTail(String text) {
        var tokens = encoding.encode(text);
        if (tokens.size() <= overlapTokens) {
            return text;
        }
        var tailTokens = new com.knuddels.jtokkit.api.IntArrayList(overlapTokens);
        for (int i = tokens.size() - overlapTokens; i < tokens.size(); i++) {
            tailTokens.add(tokens.get(i));
        }
        String tail = encoding.decode(tailTokens);
        int sentenceStart = tail.indexOf(". ");
        return sentenceStart >= 0 ? tail.substring(sentenceStart + 2) : tail;
    }

    /** Sentence-level split for paragraphs that exceed the hard max (e.g. big tables). */
    private List<String> splitOversized(String paragraph) {
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        for (String sentence : paragraph.split("(?<=[.!?])\\s+|\n")) {
            if (countTokens(piece.toString()) + countTokens(sentence) > targetTokens
                    && !piece.isEmpty()) {
                pieces.add(piece.toString().strip());
                piece = new StringBuilder();
            }
            if (!piece.isEmpty()) {
                piece.append(' ');
            }
            piece.append(sentence);
        }
        if (!piece.toString().isBlank()) {
            pieces.add(piece.toString().strip());
        }
        return pieces;
    }
}
