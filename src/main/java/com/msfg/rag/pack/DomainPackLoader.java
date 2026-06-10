package com.msfg.rag.pack;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.msfg.rag.service.ai.QuestionCategory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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

        // Element-level checks BEFORE assembly: List.copyOf/Map.copyOf in the
        // record constructors reject null elements with a bare NPE, which
        // would bypass the file+field error contract.
        requireElementsNotBlank(packDir, "guardrails.yaml", "prohibited-phrases",
                guardrailsFile.prohibitedPhrases());
        requireLowercase(packDir, "guardrails.yaml", "prohibited-phrases",
                guardrailsFile.prohibitedPhrases());
        requireLowercase(packDir, "guardrails.yaml", "eligible-phrase",
                guardrailsFile.eligiblePhrase() == null ? null
                        : List.of(guardrailsFile.eligiblePhrase()));
        if (classifierFile.rules() != null) {
            for (ClassifierRuleFile rule : classifierFile.rules()) {
                requireElementsNotBlank(packDir, "classifier.yaml", "rules.patterns", rule.patterns());
            }
        }
        if (retrievalFile.acronyms() != null) {
            for (Map.Entry<String, String> entry : retrievalFile.acronyms().entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()
                        || entry.getValue() == null || entry.getValue().isBlank()) {
                    throw new PackValidationException("domain pack " + packDir
                            + ": retrieval.yaml: invalid or empty acronyms entry \"" + entry.getKey() + "\"");
                }
                // Acronym keys are matched against lowercased text — must be lowercase.
                if (!entry.getKey().equals(entry.getKey().toLowerCase(java.util.Locale.US))) {
                    throw new PackValidationException("domain pack " + packDir
                            + ": retrieval.yaml: entry must be lowercase: \"" + entry.getKey() + "\"");
                }
            }
        }
        if (retrievalFile.programs() != null) {
            for (ProgramFile program : retrievalFile.programs()) {
                requireElementsNotBlank(packDir, "retrieval.yaml", "programs.keywords", program.keywords());
                requireElementsNotBlank(packDir, "retrieval.yaml", "programs.word-patterns", program.wordPatterns());
                requireLowercase(packDir, "retrieval.yaml", "programs.keywords", program.keywords());
            }
        }

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

        validate(packDir, pack);
        return pack;
    }

    private static final Pattern SLUG = Pattern.compile("[a-z0-9-]+");

    private void validate(Path dir, DomainPack p) {
        require(dir, "pack.yaml", "slug (must match [a-z0-9-]+)", p.slug() != null && SLUG.matcher(p.slug()).matches());
        require(dir, "pack.yaml", "company-name", notBlank(p.companyName()));
        require(dir, "pack.yaml", "disclaimer", notBlank(p.disclaimer()));

        require(dir, "prompt.yaml", "template (needs exactly 3 %s placeholders)",
                p.promptTemplate() != null && p.promptTemplate().split("%s", -1).length == 4);
        try {
            p.promptTemplate().formatted("", "", "");
        } catch (IllegalFormatException e) {
            throw new PackValidationException("domain pack " + dir
                    + ": prompt.yaml: template is not a valid format string: " + e.getMessage());
        }

        require(dir, "guardrails.yaml", "prohibited-phrases",
                p.guardrails() != null && p.guardrails().prohibitedPhrases() != null
                        && !p.guardrails().prohibitedPhrases().isEmpty());
        require(dir, "guardrails.yaml", "eligible-phrase", notBlank(p.guardrails().eligiblePhrase()));
        DomainPack.CannedAnswers c = p.guardrails().cannedAnswers();
        require(dir, "guardrails.yaml", "canned-answers", c != null);
        require(dir, "guardrails.yaml", "canned-answers.no-source", notBlank(c.noSource()));
        require(dir, "guardrails.yaml", "canned-answers.escalation", notBlank(c.escalation()));
        require(dir, "guardrails.yaml", "canned-answers.legal", notBlank(c.legal()));
        require(dir, "guardrails.yaml", "canned-answers.tax", notBlank(c.tax()));
        require(dir, "guardrails.yaml", "canned-answers.live-rates", notBlank(c.liveRates()));
        require(dir, "guardrails.yaml", "canned-answers.fraud", notBlank(c.fraud()));

        require(dir, "classifier.yaml", "rules",
                p.classifierRules() != null && !p.classifierRules().isEmpty());
        EnumSet<QuestionCategory> seen = EnumSet.noneOf(QuestionCategory.class);
        for (DomainPack.ClassifierRule rule : p.classifierRules()) {
            require(dir, "classifier.yaml", "rules.category", rule.category() != null);
            if (!seen.add(rule.category())) {
                throw new PackValidationException("domain pack " + dir
                        + ": classifier.yaml: duplicate category " + rule.category());
            }
            require(dir, "classifier.yaml", "rules.patterns",
                    rule.patterns() != null && !rule.patterns().isEmpty());
            compileAll(dir, "classifier.yaml", rule.patterns());
        }

        require(dir, "retrieval.yaml", "acronyms",
                p.acronymExpansions() != null && !p.acronymExpansions().isEmpty());
        require(dir, "retrieval.yaml", "programs",
                p.programRules() != null && !p.programRules().isEmpty());
        for (DomainPack.ProgramRule rule : p.programRules()) {
            require(dir, "retrieval.yaml", "programs.program", notBlank(rule.program()));
            compileAll(dir, "retrieval.yaml", rule.wordPatterns());
        }
    }

    private void compileAll(Path dir, String file, List<String> patterns) {
        for (String pattern : patterns) {
            require(dir, file, "pattern", notBlank(pattern));
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new PackValidationException("domain pack " + dir + ": " + file
                        + ": invalid regex \"" + pattern + "\": " + e.getDescription());
            }
        }
    }

    /** Null list is fine here — list-level nullness/emptiness is checked in validate(). */
    private void requireElementsNotBlank(Path dir, String file, String field, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new PackValidationException(
                        "domain pack " + dir + ": " + file + ": blank entry in " + field);
            }
        }
    }

    /**
     * Entries that are matched against lowercased text at runtime must themselves
     * be lowercase — a mixed-case entry is a silently dead rule.
     * Null list is fine — emptiness/nullness is checked elsewhere.
     */
    private void requireLowercase(Path dir, String file, String field, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            if (value != null && !value.equals(value.toLowerCase(java.util.Locale.US))) {
                throw new PackValidationException("domain pack " + dir + ": " + file
                        + ": entry must be lowercase: \"" + value + "\"");
            }
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private void require(Path dir, String file, String field, boolean ok) {
        if (!ok) {
            throw new PackValidationException(
                    "domain pack " + dir + ": " + file + ": invalid or empty " + field);
        }
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
