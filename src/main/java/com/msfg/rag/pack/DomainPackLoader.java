package com.msfg.rag.pack;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.msfg.rag.service.ai.QuestionCategory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Reads the five YAML files of a domain pack directory into a DomainPack.
 * Throws PackValidationException naming the exact file (and field) on any
 * problem — the application must fail to boot rather than run with a partial
 * compliance layer.
 */
public class DomainPackLoader {

    private final ObjectMapper yaml = YAMLMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    // Intermediate per-file shapes (kebab-case keys map to these components).
    private record PackFile(String slug, String companyName, String disclaimer) {}
    private record PromptFile(String template) {}
    private record GuardrailsFile(List<String> prohibitedPhrases, String eligiblePhrase,
                                  DomainPack.CannedAnswers cannedAnswers) {}
    private record ClassifierFile(List<ClassifierRuleFile> rules) {}
    private record ClassifierRuleFile(QuestionCategory category, List<String> patterns) {}
    private record RetrievalFile(Map<String, String> acronyms, List<ProgramFile> programs) {}
    private record ProgramFile(String program, List<String> keywords, List<String> wordPatterns) {}

    public DomainPack load(Path packDir) {
        PackFile packFile = read(packDir, "pack.yaml", PackFile.class);
        PromptFile promptFile = read(packDir, "prompt.yaml", PromptFile.class);
        GuardrailsFile guardrailsFile = read(packDir, "guardrails.yaml", GuardrailsFile.class);
        ClassifierFile classifierFile = read(packDir, "classifier.yaml", ClassifierFile.class);
        RetrievalFile retrievalFile = read(packDir, "retrieval.yaml", RetrievalFile.class);

        DomainPack pack = new DomainPack(
                packFile.slug(),
                packFile.companyName(),
                packFile.disclaimer(),
                promptFile.template(),
                new DomainPack.Guardrails(
                        guardrailsFile.prohibitedPhrases(),
                        guardrailsFile.eligiblePhrase(),
                        guardrailsFile.cannedAnswers()),
                classifierFile.rules() == null ? null : classifierFile.rules().stream()
                        .map(r -> new DomainPack.ClassifierRule(r.category(), r.patterns()))
                        .toList(),
                retrievalFile.acronyms(),
                retrievalFile.programs() == null ? null : retrievalFile.programs().stream()
                        .map(p -> new DomainPack.ProgramRule(
                                p.program(),
                                p.keywords() == null ? List.of() : p.keywords(),
                                p.wordPatterns() == null ? List.of() : p.wordPatterns()))
                        .toList());

        return pack; // validation added in Task 2
    }

    private <T> T read(Path packDir, String fileName, Class<T> type) {
        Path file = packDir.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            throw new PackValidationException(
                    "domain pack " + packDir + ": missing required file " + fileName);
        }
        try {
            T parsed = yaml.readValue(file.toFile(), type);
            if (parsed == null) {
                throw new PackValidationException(
                        "domain pack " + packDir + ": " + fileName + ": file is empty");
            }
            return parsed;
        } catch (IOException e) {
            throw new PackValidationException(
                    "domain pack " + packDir + ": " + fileName + ": " + e.getMessage(), e);
        }
    }

    /** Boot-blocking pack problem. */
    public static class PackValidationException extends RuntimeException {
        public PackValidationException(String message) { super(message); }
        public PackValidationException(String message, Throwable cause) { super(message, cause); }
    }
}
