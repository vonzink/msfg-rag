package com.msfg.rag.pack;

import com.msfg.rag.service.ai.QuestionCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DomainPackLoaderTest {

    private static final Path TEST_PACK = Path.of("src/test/resources/packs/test-pack");

    private final DomainPackLoader loader = new DomainPackLoader();

    @TempDir
    Path tempDir;

    /** Copies the valid fixture pack, then lets a test break one file. */
    private Path packCopy() throws IOException {
        for (String f : List.of("pack.yaml", "prompt.yaml", "guardrails.yaml",
                "classifier.yaml", "retrieval.yaml")) {
            Files.copy(TEST_PACK.resolve(f), tempDir.resolve(f));
        }
        return tempDir;
    }

    @Test
    void loadsAllFiveFilesIntoOnePack() {
        DomainPack pack = loader.load(TEST_PACK);

        assertEquals("testco", pack.slug());
        assertEquals("Test Company", pack.companyName());
        assertEquals("Educational only.", pack.disclaimer());
        assertEquals("Context: %s\nQuestion: %s\nDisclaimer: %s\n", pack.promptTemplate());

        assertEquals(List.of("you are approved"), pack.guardrails().prohibitedPhrases());
        assertEquals("you are eligible", pack.guardrails().eligiblePhrase());
        assertEquals("No source.", pack.guardrails().cannedAnswers().noSource());
        assertEquals("Escalate.", pack.guardrails().cannedAnswers().escalation());
        assertEquals("No legal.", pack.guardrails().cannedAnswers().legal());
        assertEquals("No tax.", pack.guardrails().cannedAnswers().tax());
        assertEquals("No rates.", pack.guardrails().cannedAnswers().liveRates());
        assertEquals("No fraud.", pack.guardrails().cannedAnswers().fraud());

        assertEquals(2, pack.classifierRules().size());
        assertEquals(QuestionCategory.FRAUD, pack.classifierRules().get(0).category());
        assertEquals(List.of("\\bfake\\b"), pack.classifierRules().get(0).patterns());

        assertEquals(Map.of("pmi", "private mortgage insurance"), pack.acronymExpansions());
        assertEquals(2, pack.programRules().size());
        assertEquals("VA", pack.programRules().get(1).program());
        assertEquals(List.of("\\bva\\b"), pack.programRules().get(1).wordPatterns());

        // Fix 3: null word-patterns branch — FHA has no word-patterns key in YAML
        assertEquals(List.of("fha"), pack.programRules().get(0).keywords());
        assertEquals(List.of(), pack.programRules().get(0).wordPatterns());
    }

    @Test
    void nullYamlDocumentFailsNamingTheFile() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("pack.yaml"), "---\n");
        var ex = assertThrows(
                DomainPackLoader.PackValidationException.class, () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("pack.yaml"), ex.getMessage());
    }

    @Test
    void missingFileFailsNamingTheFile() throws IOException {
        Path dir = packCopy();
        Files.delete(dir.resolve("guardrails.yaml"));
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("guardrails.yaml"), ex.getMessage());
    }

    @Test
    void emptyProhibitedPhrasesFailsBoot() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("guardrails.yaml"), """
                prohibited-phrases: []
                eligible-phrase: you are eligible
                canned-answers:
                  no-source: a
                  escalation: b
                  legal: c
                  tax: d
                  live-rates: e
                  fraud: f
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("prohibited-phrases"), ex.getMessage());
    }

    @Test
    void blankCannedAnswerFailsBoot() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("guardrails.yaml"), """
                prohibited-phrases:
                  - you are approved
                eligible-phrase: you are eligible
                canned-answers:
                  no-source: ""
                  escalation: b
                  legal: c
                  tax: d
                  live-rates: e
                  fraud: f
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("no-source"), ex.getMessage());
    }

    @Test
    void templateWithoutThreePlaceholdersFailsBoot() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("prompt.yaml"), "template: only %s here\n");
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("template"), ex.getMessage());
    }

    @Test
    void invalidRegexFailsBoot() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("classifier.yaml"), """
                rules:
                  - category: FRAUD
                    patterns:
                      - '([unclosed'
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("classifier.yaml"), ex.getMessage());
    }

    @Test
    void invalidSlugFailsBoot() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("pack.yaml"), """
                slug: "Not A Slug!"
                company-name: Test Company
                disclaimer: Educational only.
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("slug"), ex.getMessage());
    }

    // Locks the loader's strict mode: a typo'd key must fail, never be ignored.
    @Test
    void unknownYamlKeyFailsNamingTheFile() throws IOException {
        Path dir = packCopy();
        Files.writeString(dir.resolve("guardrails.yaml"), """
                prohibited-phrases:
                  - you are approved
                eligible-phrases: oops-typo
                canned-answers:
                  no-source: a
                  escalation: b
                  legal: c
                  tax: d
                  live-rates: e
                  fraud: f
                """);
        var ex = assertThrows(DomainPackLoader.PackValidationException.class,
                () -> loader.load(dir));
        assertTrue(ex.getMessage().contains("guardrails.yaml"), ex.getMessage());
    }
}
