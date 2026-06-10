# Phase ① — Domain Pack Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every company-specific constant out of the Java services into a versioned YAML domain pack (`packs/msfg-mortgage/`), loaded fail-fast at boot, with MSFG behavior preserved exactly — plus the `brain_*` table rename and the slug-driven endpoint that ride along per the spec.

**Architecture:** A `DomainPack` record (immutable bean) is loaded by `DomainPackLoader` (Jackson YAML, kebab-case) from the directory named by the `brain.pack` property. Five services (`PromptBuilderService`, `AnswerValidationService`, `QuestionClassifierService`, `AskService`, `RetrievalService`) inject it instead of holding constants. A golden-pack test asserts the MSFG pack reproduces today's exact strings, and the existing suite keeps passing — proof the extraction changed nothing.

**Tech Stack:** Java 21 / Spring Boot 3.5, Jackson `jackson-dataformat-yaml` (version from Boot BOM), Flyway, JUnit 5. Spec: `docs/superpowers/specs/2026-06-10-rag-brain-platform-design.md` (§4, §7, §11, §12-①).

**Prerequisites:** Work on branch `fix/program-comparisons-and-refusal-coherence` (or `main` after it's merged — it contains the refusal fix, cross-program fix, and the spec). Run all commands from the repo root `~/MSFG/msfg-rag`. Full suite must be green before starting: `./gradlew test --console=plain`.

**Conventions for every task:** run the named test first and watch it fail for the stated reason; write the minimal code; re-run to green; run the full suite before each commit (`./gradlew test --console=plain` → `BUILD SUCCESSFUL`); commit with the given message. Java class names `MortgageDocument`/`DocumentChunk` are NOT renamed in this phase — only their tables (spec §7).

---

## File map (created → C, modified → M)

| File | Task | Role |
|---|---|---|
| C `build.gradle.kts` (one line) | 1 | YAML dependency |
| C `src/main/java/com/msfg/rag/pack/DomainPack.java` | 1 | Immutable pack model (records) |
| C `src/main/java/com/msfg/rag/pack/DomainPackLoader.java` | 1–2 | YAML → `DomainPack`, fail-fast validation |
| C `src/test/resources/packs/test-pack/*.yaml` | 1 | Tiny fixture pack for loader tests |
| C `src/test/java/com/msfg/rag/pack/DomainPackLoaderTest.java` | 1–2 | Loader happy-path + validation tests |
| C `packs/msfg-mortgage/{pack,prompt,guardrails,classifier,retrieval}.yaml` | 3 | The real MSFG pack |
| C `src/test/java/com/msfg/rag/pack/TestPacks.java` | 3 | Test helper: loads the real MSFG pack |
| C `src/test/java/com/msfg/rag/pack/MsfgGoldenPackTest.java` | 3 | Golden regression lock (literal strings) |
| C `src/main/java/com/msfg/rag/config/DomainPackConfig.java` | 4 | `@Bean DomainPack` from `brain.pack`; slug match check |
| M `src/main/resources/application.yml` | 4 | `brain.pack` / `brain.slug` properties |
| M `PromptBuilderService` / `AskService` / their tests | 5 | Template + disclaimer from pack |
| M `AnswerValidationService` + tests (and `AskServiceTest` ctor) | 6 | Phrases from pack |
| M `QuestionClassifierService` + test | 7 | Rules compiled from pack |
| M `AskService` + `AskServiceTest` | 8 | Canned answers from pack; statics deleted |
| M `RetrievalService` + test | 9 | Acronyms + program rules from pack |
| C `src/main/resources/db/migration/V3__rename_to_brain_tables.sql`; M entities + `DocumentChunkRepository` | 10 | Table rename |
| M `AskController`, `RateLimitFilter`; C `RateLimitFilterTest` | 11 | Slug-driven path |

---

### Task 1: DomainPack model + loader happy path

**Files:**
- Modify: `build.gradle.kts` (dependencies block)
- Create: `src/main/java/com/msfg/rag/pack/DomainPack.java`
- Create: `src/main/java/com/msfg/rag/pack/DomainPackLoader.java`
- Create: `src/test/resources/packs/test-pack/pack.yaml`, `prompt.yaml`, `guardrails.yaml`, `classifier.yaml`, `retrieval.yaml`
- Create: `src/test/java/com/msfg/rag/pack/DomainPackLoaderTest.java`

- [ ] **Step 1.1: Add the YAML dependency** — in `build.gradle.kts`, under the `// --- Spring core ---` group add:

```kotlin
    // --- Domain pack loading (YAML -> records) ---
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
```

- [ ] **Step 1.2: Create the fixture pack** (small, NOT the MSFG content — loader mechanics only).

`src/test/resources/packs/test-pack/pack.yaml`:
```yaml
slug: testco
company-name: Test Company
disclaimer: Educational only.
```

`src/test/resources/packs/test-pack/prompt.yaml`:
```yaml
template: |
  Context: %s
  Question: %s
  Disclaimer: %s
```

`src/test/resources/packs/test-pack/guardrails.yaml`:
```yaml
prohibited-phrases:
  - you are approved
eligible-phrase: you are eligible
canned-answers:
  no-source: No source.
  escalation: Escalate.
  legal: No legal.
  tax: No tax.
  live-rates: No rates.
  fraud: No fraud.
```

`src/test/resources/packs/test-pack/classifier.yaml`:
```yaml
rules:
  - category: FRAUD
    patterns:
      - '\bfake\b'
  - category: LEGAL
    patterns:
      - '\blawsuit\b'
```

`src/test/resources/packs/test-pack/retrieval.yaml`:
```yaml
acronyms:
  pmi: private mortgage insurance
programs:
  - program: FHA
    contains:
      - fha
    word-patterns: []
  - program: VA
    contains:
      - veteran
    word-patterns:
      - '\bva\b'
```

- [ ] **Step 1.3: Write the failing test**

`src/test/java/com/msfg/rag/pack/DomainPackLoaderTest.java`:
```java
package com.msfg.rag.pack;

import com.msfg.rag.service.ai.QuestionCategory;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DomainPackLoaderTest {

    private static final Path TEST_PACK = Path.of("src/test/resources/packs/test-pack");

    private final DomainPackLoader loader = new DomainPackLoader();

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
    }
}
```

- [ ] **Step 1.4: Run it — must fail to compile** (classes don't exist):

Run: `./gradlew test --tests "com.msfg.rag.pack.DomainPackLoaderTest" --console=plain`
Expected: `BUILD FAILED` — `error: package com.msfg.rag.pack does not exist` / `cannot find symbol`

- [ ] **Step 1.5: Create the model**

`src/main/java/com/msfg/rag/pack/DomainPack.java`:
```java
package com.msfg.rag.pack;

import com.msfg.rag.service.ai.QuestionCategory;

import java.util.List;
import java.util.Map;

/**
 * Everything company-specific about one brain, loaded from a pack directory
 * at boot (see DomainPackLoader). Immutable; services inject this instead of
 * holding their own constants. Spec: docs/superpowers/specs/
 * 2026-06-10-rag-brain-platform-design.md §4.
 */
public record DomainPack(
        String slug,
        String companyName,
        String disclaimer,
        String promptTemplate,
        Guardrails guardrails,
        List<ClassifierRule> classifierRules,
        Map<String, String> acronymExpansions,
        List<ProgramRule> programRules
) {

    public record Guardrails(
            List<String> prohibitedPhrases,
            String eligiblePhrase,
            CannedAnswers cannedAnswers
    ) {}

    /** The six fixed refusal/escalation texts the pipeline can return. */
    public record CannedAnswers(
            String noSource,
            String escalation,
            String legal,
            String tax,
            String liveRates,
            String fraud
    ) {}

    /** One classifier category with its regex patterns; list order = check order. */
    public record ClassifierRule(QuestionCategory category, List<String> patterns) {}

    /**
     * Program detection for program-aware ranking: substring matches plus
     * word-boundary regex patterns (e.g. "\\bva\\b" so "available" never
     * matches VA). List order = priority order.
     */
    public record ProgramRule(String program, List<String> contains, List<String> wordPatterns) {}
}
```

`src/main/java/com/msfg/rag/pack/DomainPackLoader.java`:
```java
package com.msfg.rag.pack;

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
            .build();

    // Intermediate per-file shapes (kebab-case keys map to these components).
    private record PackFile(String slug, String companyName, String disclaimer) {}
    private record PromptFile(String template) {}
    private record GuardrailsFile(List<String> prohibitedPhrases, String eligiblePhrase,
                                  DomainPack.CannedAnswers cannedAnswers) {}
    private record ClassifierFile(List<ClassifierRuleFile> rules) {}
    private record ClassifierRuleFile(QuestionCategory category, List<String> patterns) {}
    private record RetrievalFile(Map<String, String> acronyms, List<ProgramFile> programs) {}
    private record ProgramFile(String program, List<String> contains, List<String> wordPatterns) {}

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
                                p.contains() == null ? List.of() : p.contains(),
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
            return yaml.readValue(file.toFile(), type);
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
```

- [ ] **Step 1.6: Run to green**

Run: `./gradlew test --tests "com.msfg.rag.pack.DomainPackLoaderTest" --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 1.7: Full suite green, then commit**

```bash
git add build.gradle.kts src/main/java/com/msfg/rag/pack/ src/test/java/com/msfg/rag/pack/ src/test/resources/packs/
git commit -m "Add DomainPack model and YAML loader (happy path)"
```

---

### Task 2: Loader fail-fast validation

**Files:**
- Modify: `src/main/java/com/msfg/rag/pack/DomainPackLoader.java`
- Modify: `src/test/java/com/msfg/rag/pack/DomainPackLoaderTest.java`

- [ ] **Step 2.1: Add failing validation tests** (append inside `DomainPackLoaderTest`; add imports `java.nio.file.Files`, `java.io.IOException`, `org.junit.jupiter.api.io.TempDir`, and `static org.junit.jupiter.api.Assertions.assertThrows` / `assertTrue`):

```java
    @org.junit.jupiter.api.io.TempDir
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
```

- [ ] **Step 2.2: Run — the six new tests fail** (no validation yet):

Run: `./gradlew test --tests "com.msfg.rag.pack.DomainPackLoaderTest" --console=plain`
Expected: `BUILD FAILED`, the new tests fail with `Expected PackValidationException to be thrown` (except `missingFileFailsNamingTheFile`, which already passes from Task 1 — that's fine).

- [ ] **Step 2.3: Implement validation** — in `DomainPackLoader`, replace `return pack; // validation added in Task 2` with `validate(packDir, pack); return pack;` and add (plus `import java.util.regex.Pattern;` and `import java.util.regex.PatternSyntaxException;`):

```java
    private static final Pattern SLUG = Pattern.compile("[a-z0-9-]+");

    private void validate(Path dir, DomainPack p) {
        require(dir, "pack.yaml", "slug", p.slug() != null && SLUG.matcher(p.slug()).matches());
        require(dir, "pack.yaml", "company-name", notBlank(p.companyName()));
        require(dir, "pack.yaml", "disclaimer", notBlank(p.disclaimer()));

        require(dir, "prompt.yaml", "template (needs exactly 3 %s placeholders)",
                p.promptTemplate() != null && p.promptTemplate().split("%s", -1).length == 4);

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
        for (DomainPack.ClassifierRule rule : p.classifierRules()) {
            require(dir, "classifier.yaml", "rules.category", rule.category() != null);
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
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new PackValidationException("domain pack " + dir + ": " + file
                        + ": invalid regex \"" + pattern + "\": " + e.getDescription());
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
```

- [ ] **Step 2.4: Run to green**

Run: `./gradlew test --tests "com.msfg.rag.pack.DomainPackLoaderTest" --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2.5: Full suite green, then commit**

```bash
git add src/main/java/com/msfg/rag/pack/DomainPackLoader.java src/test/java/com/msfg/rag/pack/DomainPackLoaderTest.java
git commit -m "Fail-fast validation for domain pack loading"
```

---

### Task 3: The MSFG pack + golden-pack regression lock

The five YAML files below are **verbatim copies of today's constants**. Transcribe exactly — the golden test compares against literal strings. (In folded scalars `>-`, each newline becomes one space; in `|` block scalars, content is byte-for-byte with a single trailing newline.)

**Files:**
- Create: `packs/msfg-mortgage/pack.yaml`, `prompt.yaml`, `guardrails.yaml`, `classifier.yaml`, `retrieval.yaml`
- Create: `src/test/java/com/msfg/rag/pack/TestPacks.java`
- Create: `src/test/java/com/msfg/rag/pack/MsfgGoldenPackTest.java`

- [ ] **Step 3.1: Write `packs/msfg-mortgage/pack.yaml`**

```yaml
slug: mortgage
company-name: Mountain State Financial Group
disclaimer: >-
  This answer is for general mortgage education only and is not a loan approval,
  underwriting decision, legal advice, or tax advice.
```

- [ ] **Step 3.2: Write `packs/msfg-mortgage/prompt.yaml`** — the template is today's `PromptBuilderService.TEMPLATE` text block, byte-for-byte:

```yaml
template: |
  You are an AI mortgage education assistant for Mountain State Financial Group.

  You must answer ONLY using the approved source context provided below.

  Rules:
  1. Do not answer from general knowledge.
  2. Do not invent mortgage guidelines.
  3. Do not provide loan approval, legal advice, tax advice, or underwriting decisions.
  4. If the source context does not answer the question, say you cannot find enough information.
  5. Use careful wording such as "may," "generally," and "subject to full loan review."
  6. Include citations from the provided source context. The "citations"
     array is REQUIRED and must contain at least one entry whenever
     source context is provided above. Cite every [Source N] you relied
     on to write the answer. NEVER return an empty "citations" array
     when source context is present — if you used the sources to answer,
     you must list them.
  7. Keep the answer clear and borrower-friendly.
  8. In citations, copy source_name, document_name, section, page_number, and
     effective_date EXACTLY as given in the source context metadata. If a field is
     not present for a source, set it to null. NEVER invent page numbers, section
     names, or dates.
  9. Pay attention to which loan program each source covers (FHA, VA, conventional).
     If the question is about one program, do not answer using a different
     program's guideline. If no source covers the right program, say you cannot
     find enough information.

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
```

- [ ] **Step 3.3: Write `packs/msfg-mortgage/guardrails.yaml`** — phrases and texts from `AnswerValidationService.PROHIBITED_PHRASES` and the six `AskService` constants:

```yaml
prohibited-phrases:
  - you qualify
  - you are approved
  - you're approved
  - you will be approved
  - guaranteed
  - the underwriter must accept
  - the underwriter will accept
  - this will close
  - this loan will close
  - 'legal advice:'
  - as your lawyer
  - as your tax advisor
eligible-phrase: you are eligible
canned-answers:
  no-source: >-
    I could not find enough information in the approved mortgage guidelines to answer
    that confidently. Please contact a licensed loan officer for review.
  escalation: >-
    This question depends on your full loan file and should be reviewed by a licensed
    loan officer. I can explain the general guideline, but I cannot determine approval
    or eligibility here.
  legal: >-
    I can't provide legal advice. For legal questions about your mortgage or lender,
    please consult a licensed attorney. I'm happy to explain general mortgage
    guidelines if that helps.
  tax: >-
    I can't provide tax advice. A licensed tax professional can review your specific
    situation. I'm happy to explain general mortgage guidelines if that helps.
  live-rates: >-
    I don't have access to live rate data, and rates depend on your full loan scenario.
    A licensed loan officer at Mountain State Financial Group can provide a current,
    personalized quote.
  fraud: >-
    I can't help with that. Misrepresenting income, debts, or documents on a mortgage
    application is fraud. If you have questions about what must be disclosed, a
    licensed loan officer can walk you through the requirements.
```

- [ ] **Step 3.4: Write `packs/msfg-mortgage/classifier.yaml`** — regexes from `QuestionClassifierService.buildRules()`, same order (FRAUD first — order is the check order). Single-quoted YAML: a literal `'` inside is written `''`:

```yaml
rules:
  - category: FRAUD
    patterns:
      - '\b(hide|hiding|conceal)\b.*\b(income|debt|loan|liabilit|asset)'
      - '\b(fake|falsif|forge|doctor|alter)\w*\b.*\b(document|paystub|pay stub|w-?2|bank statement|tax return)'
      - '\b(lie|lying)\b.*\b(lender|application|underwriter|loan)'
      - '\bnot (tell|report|disclose)\b.*\b(lender|debt|income|loan)'
      - '\bwithout (the )?(lender|bank) (knowing|finding out)'
  - category: ELIGIBILITY
    patterns:
      - '\b(do|would|will|can|could) i (pre-?)?(qualify|get (pre-?)?approved)\b'
      - '\b(am i|are we) (eligible|approved|qualified)\b'
      - '\bwill (i|we) (be )?(approved|denied|turned down)\b'
      - '\b(approve|deny) (me|my loan|my application)\b'
      - '\bhow much (house|home|mortgage|loan) (can|could) (i|we) (afford|get|qualify)\b'
  - category: LEGAL
    patterns:
      - '\b(sue|suing|lawsuit|litigation)\b'
      - '\b(is (it|this) legal|illegal)\b'
      - '\b(lawyer|attorney)\b.*\b(need|should|hire)\b'
      - '\b(need|should|hire)\b.*\b(lawyer|attorney)\b'
      - '\bbreach of contract\b'
  - category: TAX
    patterns:
      - '\b(should|how do|how should) (i|we) file\b.*\btax'
      - '\btax (strategy|advice|loophole)\b'
      - '\b(write|writing) off\b.*\b(mortgage|interest|points)\b'
      - '\b(deduct|deduction)\b.*\b(should|can) i\b'
      - '\bclaim\b.*\bon (my|our) tax(es)?\b'
  - category: LIVE_RATES
    patterns:
      - '\bwhat(''s| is| are)? (the |your |today)?\w* ?rates? (can i get|today|right now|currently)\b'
      - '\b(current|today''?s?) (interest )?rates?\b'
      - '\bquote me\b'
      - '\brate (quote|lock)\b.*\b(today|now|get)\b'
      - '\bwhat rate\b.*\b(get|offer|give)\b'
```

- [ ] **Step 3.5: Write `packs/msfg-mortgage/retrieval.yaml`** — from `RetrievalService.ACRONYM_EXPANSIONS` and `detectPrograms`:

```yaml
acronyms:
  pmi: private mortgage insurance
  mip: mortgage insurance premium
  dti: debt-to-income
  ltv: loan-to-value
  cltv: combined loan-to-value
  piti: principal interest taxes insurance
  arm: adjustable-rate mortgage
  heloc: home equity line of credit
  hoa: homeowners association
  apr: annual percentage rate
  aus: automated underwriting system
  fha: Federal Housing Administration
  va: Veterans Affairs
  usda: United States Department of Agriculture
programs:
  - program: FHA
    contains:
      - fha
      - hud
      - "4000.1"
    word-patterns: []
  - program: VA
    contains:
      - veteran
    word-patterns:
      - '\bva\b'
  - program: USDA
    contains:
      - usda
      - rural development
    word-patterns: []
  - program: CONVENTIONAL
    contains:
      - conventional
      - fannie
      - freddie
      - conforming
    word-patterns: []
```

- [ ] **Step 3.6: Write the test helper + the failing golden test**

`src/test/java/com/msfg/rag/pack/TestPacks.java`:
```java
package com.msfg.rag.pack;

import java.nio.file.Path;

/** Loads the real MSFG pack for tests (working dir = repo root under Gradle). */
public final class TestPacks {

    private static final DomainPack MSFG = new DomainPackLoader().load(Path.of("packs/msfg-mortgage"));

    private TestPacks() {}

    public static DomainPack msfg() {
        return MSFG;
    }
}
```

`src/test/java/com/msfg/rag/pack/MsfgGoldenPackTest.java` — the literals here are the **frozen current behavior**; if one of these fails, the pack transcription is wrong, not the test:
```java
package com.msfg.rag.pack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden regression lock: the MSFG pack must reproduce the exact strings that
 * were hardcoded in the services before extraction. Do not "fix" these
 * literals to match a changed pack — they ARE the spec of current behavior.
 */
class MsfgGoldenPackTest {

    private final DomainPack pack = TestPacks.msfg();

    @Test
    void identity() {
        assertEquals("mortgage", pack.slug());
        assertEquals("Mountain State Financial Group", pack.companyName());
        assertEquals("This answer is for general mortgage education only and is not a loan "
                + "approval, underwriting decision, legal advice, or tax advice.",
                pack.disclaimer());
    }

    @Test
    void promptTemplateIsByteIdenticalToLegacyConstant() {
        String expected = """
                You are an AI mortgage education assistant for Mountain State Financial Group.

                You must answer ONLY using the approved source context provided below.

                Rules:
                1. Do not answer from general knowledge.
                2. Do not invent mortgage guidelines.
                3. Do not provide loan approval, legal advice, tax advice, or underwriting decisions.
                4. If the source context does not answer the question, say you cannot find enough information.
                5. Use careful wording such as "may," "generally," and "subject to full loan review."
                6. Include citations from the provided source context. The "citations"
                   array is REQUIRED and must contain at least one entry whenever
                   source context is provided above. Cite every [Source N] you relied
                   on to write the answer. NEVER return an empty "citations" array
                   when source context is present — if you used the sources to answer,
                   you must list them.
                7. Keep the answer clear and borrower-friendly.
                8. In citations, copy source_name, document_name, section, page_number, and
                   effective_date EXACTLY as given in the source context metadata. If a field is
                   not present for a source, set it to null. NEVER invent page numbers, section
                   names, or dates.
                9. Pay attention to which loan program each source covers (FHA, VA, conventional).
                   If the question is about one program, do not answer using a different
                   program's guideline. If no source covers the right program, say you cannot
                   find enough information.

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
        assertEquals(expected, pack.promptTemplate());
    }

    @Test
    void guardrailsMatchLegacyConstants() {
        assertEquals(List.of(
                "you qualify",
                "you are approved",
                "you're approved",
                "you will be approved",
                "guaranteed",
                "the underwriter must accept",
                "the underwriter will accept",
                "this will close",
                "this loan will close",
                "legal advice:",
                "as your lawyer",
                "as your tax advisor"), pack.guardrails().prohibitedPhrases());
        assertEquals("you are eligible", pack.guardrails().eligiblePhrase());

        var canned = pack.guardrails().cannedAnswers();
        assertEquals("I could not find enough information in the approved mortgage guidelines "
                + "to answer that confidently. Please contact a licensed loan officer for review.",
                canned.noSource());
        assertEquals("This question depends on your full loan file and should be reviewed by a "
                + "licensed loan officer. I can explain the general guideline, but I cannot "
                + "determine approval or eligibility here.", canned.escalation());
        assertEquals("I can't provide legal advice. For legal questions about your mortgage or "
                + "lender, please consult a licensed attorney. I'm happy to explain general "
                + "mortgage guidelines if that helps.", canned.legal());
        assertEquals("I can't provide tax advice. A licensed tax professional can review your "
                + "specific situation. I'm happy to explain general mortgage guidelines if "
                + "that helps.", canned.tax());
        assertEquals("I don't have access to live rate data, and rates depend on your full loan "
                + "scenario. A licensed loan officer at Mountain State Financial Group can "
                + "provide a current, personalized quote.", canned.liveRates());
        assertEquals("I can't help with that. Misrepresenting income, debts, or documents on a "
                + "mortgage application is fraud. If you have questions about what must be "
                + "disclosed, a licensed loan officer can walk you through the requirements.",
                canned.fraud());
    }

    @Test
    void classifierAndRetrievalShapesMatchLegacy() {
        assertEquals(5, pack.classifierRules().size());
        assertEquals("FRAUD", pack.classifierRules().get(0).category().name());
        assertEquals(5, pack.classifierRules().get(0).patterns().size());

        assertEquals(14, pack.acronymExpansions().size());
        assertEquals("private mortgage insurance", pack.acronymExpansions().get("pmi"));

        assertEquals(4, pack.programRules().size());
        assertEquals(List.of("FHA", "VA", "USDA", "CONVENTIONAL"),
                pack.programRules().stream().map(DomainPack.ProgramRule::program).toList());
        assertTrue(pack.programRules().get(1).wordPatterns().contains("\\bva\\b"));
    }
}
```

- [ ] **Step 3.7: Run — fails until the YAML transcription is exact** (typically `assertEquals` diffs pointing at the divergent character):

Run: `./gradlew test --tests "com.msfg.rag.pack.MsfgGoldenPackTest" --console=plain`
Expected: first run may FAIL with a string diff if any YAML line was mistranscribed; fix the YAML (never the literals) until: `BUILD SUCCESSFUL`

- [ ] **Step 3.8: Full suite green, then commit**

```bash
git add packs/ src/test/java/com/msfg/rag/pack/
git commit -m "Add MSFG domain pack and golden regression lock"
```

---

### Task 4: Spring wiring — pack bean + slug match

**Files:**
- Create: `src/main/java/com/msfg/rag/config/DomainPackConfig.java`
- Create: `src/test/java/com/msfg/rag/config/DomainPackConfigTest.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 4.1: Write the failing test**

`src/test/java/com/msfg/rag/config/DomainPackConfigTest.java`:
```java
package com.msfg.rag.config;

import com.msfg.rag.pack.DomainPack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainPackConfigTest {

    private final DomainPackConfig config = new DomainPackConfig();

    @Test
    void loadsPackFromPathAndAcceptsMatchingSlug() {
        DomainPack pack = config.domainPack("packs/msfg-mortgage", "mortgage");
        assertEquals("mortgage", pack.slug());
    }

    @Test
    void rejectsSlugMismatchAtBoot() {
        var ex = assertThrows(IllegalStateException.class,
                () -> config.domainPack("packs/msfg-mortgage", "roofing"));
        assertTrue(ex.getMessage().contains("mortgage"), ex.getMessage());
        assertTrue(ex.getMessage().contains("roofing"), ex.getMessage());
    }
}
```

- [ ] **Step 4.2: Run to verify it fails to compile** (`DomainPackConfig` missing):

Run: `./gradlew test --tests "com.msfg.rag.config.DomainPackConfigTest" --console=plain`
Expected: `BUILD FAILED` — `cannot find symbol: class DomainPackConfig`

- [ ] **Step 4.3: Implement**

`src/main/java/com/msfg/rag/config/DomainPackConfig.java`:
```java
package com.msfg.rag.config;

import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.pack.DomainPackLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Loads the domain pack named by brain.pack at boot. Any pack problem aborts
 * startup (DomainPackLoader fails fast) — a brain must never run with a
 * missing or partial compliance layer. brain.slug must match the pack's slug
 * so a deployment can't accidentally point at another company's pack.
 */
@Configuration
public class DomainPackConfig {

    @Bean
    public DomainPack domainPack(@Value("${brain.pack:packs/msfg-mortgage}") String packDir,
                                 @Value("${brain.slug:mortgage}") String slug) {
        DomainPack pack = new DomainPackLoader().load(Path.of(packDir));
        if (!pack.slug().equals(slug)) {
            throw new IllegalStateException("brain.slug is '" + slug + "' but pack '" + packDir
                    + "' declares slug '" + pack.slug() + "' — deployment/pack mismatch");
        }
        return pack;
    }
}
```

- [ ] **Step 4.4: Add the properties** — in `src/main/resources/application.yml`, directly under the `server:` block (top level, same indent as `server:`), add:

```yaml
# Which domain pack this deployment runs (see packs/ and the platform spec).
brain:
  pack: ${BRAIN_PACK:packs/msfg-mortgage}
  slug: ${BRAIN_SLUG:mortgage}
```

- [ ] **Step 4.5: Run to green, full suite, commit**

Run: `./gradlew test --tests "com.msfg.rag.config.DomainPackConfigTest" --console=plain` → `BUILD SUCCESSFUL`, then `./gradlew test --console=plain` → `BUILD SUCCESSFUL`

```bash
git add src/main/java/com/msfg/rag/config/DomainPackConfig.java src/test/java/com/msfg/rag/config/DomainPackConfigTest.java src/main/resources/application.yml
git commit -m "Wire DomainPack bean from brain.pack with slug match check"
```

> Note: packaged deployments (jar) must set `BRAIN_PACK` to a path that exists on disk relative to the process working directory, or absolute. `bootRun` and Gradle tests run from the repo root, so the default works in dev.

---

### Task 5: PromptBuilderService + disclaimer from pack

**Files:**
- Modify: `src/main/java/com/msfg/rag/service/ai/PromptBuilderService.java`
- Modify: `src/main/java/com/msfg/rag/service/AskService.java` (3 `PromptBuilderService.DISCLAIMER` references)
- Modify: `src/test/java/com/msfg/rag/service/ai/PromptBuilderServiceTest.java`
- Modify: `src/test/java/com/msfg/rag/service/AskServiceTest.java` (stub `disclaimer()`)

- [ ] **Step 5.1: Update `PromptBuilderServiceTest` first** — change construction and the disclaimer assertion:

```java
// replace:  private final PromptBuilderService promptBuilder = new PromptBuilderService();
private final PromptBuilderService promptBuilder = new PromptBuilderService(TestPacks.msfg());
```
(add `import com.msfg.rag.pack.TestPacks;`), and in `includesDisclaimer()`:
```java
// replace:  assertTrue(prompt.contains(PromptBuilderService.DISCLAIMER));
assertTrue(prompt.contains(TestPacks.msfg().disclaimer()));
```

- [ ] **Step 5.2: Run — fails to compile** (no such constructor):

Run: `./gradlew test --tests "com.msfg.rag.service.ai.PromptBuilderServiceTest" --console=plain`
Expected: `BUILD FAILED` — `constructor PromptBuilderService in class PromptBuilderService cannot be applied to given types`

- [ ] **Step 5.3: Refactor `PromptBuilderService`** — delete the `DISCLAIMER` and `TEMPLATE` constants, add:

```java
package com.msfg.rag.service.ai;

import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.service.retrieval.RetrievedChunk;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Builds the final prompt sent to the LLM from the domain pack's locked
 * template. The rules in the template are compliance-critical: the model must
 * answer only from the supplied source context, never from general knowledge.
 */
@Service
public class PromptBuilderService {

    private final String template;
    private final String disclaimer;

    public PromptBuilderService(DomainPack pack) {
        this.template = pack.promptTemplate();
        this.disclaimer = pack.disclaimer();
    }

    /** The pack's public disclaimer, appended to every website response. */
    public String disclaimer() {
        return disclaimer;
    }

    public String build(String question, List<RetrievedChunk> chunks) {
        return template.formatted(formatContext(chunks), question, disclaimer);
    }
    // formatContext(...) stays exactly as it is today
}
```

In `AskService`, replace all three `PromptBuilderService.DISCLAIMER` references (in `refuse(...)` and the final `AskResponse`) with `promptBuilderService.disclaimer()`.

In `AskServiceTest.askServiceReturning(...)`, after `when(promptBuilder.build(...)...)` add:
```java
        when(promptBuilder.disclaimer()).thenReturn("d");
```

- [ ] **Step 5.4: Run to green, full suite, commit**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL`

```bash
git add src/main/java/com/msfg/rag/service/ai/PromptBuilderService.java src/main/java/com/msfg/rag/service/AskService.java src/test/java/com/msfg/rag/service/ai/PromptBuilderServiceTest.java src/test/java/com/msfg/rag/service/AskServiceTest.java
git commit -m "PromptBuilderService reads template and disclaimer from domain pack"
```

---

### Task 6: AnswerValidationService from pack

**Files:**
- Modify: `src/main/java/com/msfg/rag/service/ai/AnswerValidationService.java`
- Modify: `src/test/java/com/msfg/rag/service/ai/AnswerValidationServiceTest.java`
- Modify: `src/test/java/com/msfg/rag/service/AskServiceTest.java` (construction site)

- [ ] **Step 6.1: Update both tests' construction first**

`AnswerValidationServiceTest`: `new AnswerValidationService()` → `new AnswerValidationService(TestPacks.msfg())` (+ import `com.msfg.rag.pack.TestPacks`).
`AskServiceTest.askServiceReturning(...)`: `new AnswerValidationService()` → `new AnswerValidationService(TestPacks.msfg())` (+ same import).

- [ ] **Step 6.2: Run — fails to compile** (no such constructor):

Run: `./gradlew test --tests "com.msfg.rag.service.ai.AnswerValidationServiceTest" --console=plain`
Expected: `BUILD FAILED` — constructor mismatch

- [ ] **Step 6.3: Refactor** — delete `PROHIBITED_PHRASES` and `ELIGIBLE_PHRASE` constants; add fields + constructor (validation logic body unchanged, but reading the fields):

```java
    private final List<String> prohibitedPhrases;
    private final String eligiblePhrase;

    public AnswerValidationService(com.msfg.rag.pack.DomainPack pack) {
        this.prohibitedPhrases = pack.guardrails().prohibitedPhrases();
        this.eligiblePhrase = pack.guardrails().eligiblePhrase();
    }
```
and in `validate(...)`: `for (String phrase : prohibitedPhrases)` and `if (lower.contains(eligiblePhrase) && !isQuoted(answer.answer(), eligiblePhrase))`.

- [ ] **Step 6.4: Run to green, full suite, commit**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL`

```bash
git add src/main/java/com/msfg/rag/service/ai/AnswerValidationService.java src/test/java/com/msfg/rag/service/ai/AnswerValidationServiceTest.java src/test/java/com/msfg/rag/service/AskServiceTest.java
git commit -m "AnswerValidationService reads guardrail phrases from domain pack"
```

---

### Task 7: QuestionClassifierService from pack

**Files:**
- Modify: `src/main/java/com/msfg/rag/service/ai/QuestionClassifierService.java`
- Modify: `src/test/java/com/msfg/rag/service/ai/QuestionClassifierServiceTest.java`

- [ ] **Step 7.1: Update the test's construction** — `new QuestionClassifierService()` → `new QuestionClassifierService(TestPacks.msfg())` (+ import `com.msfg.rag.pack.TestPacks`).

- [ ] **Step 7.2: Run — fails to compile:**

Run: `./gradlew test --tests "com.msfg.rag.service.ai.QuestionClassifierServiceTest" --console=plain`
Expected: `BUILD FAILED` — constructor mismatch

- [ ] **Step 7.3: Refactor** — delete `buildRules()` and the static `RULES`; compile from the pack (rule order in the YAML list is the check order, preserved by `LinkedHashMap`):

```java
    private final Map<QuestionCategory, List<Pattern>> rules;

    public QuestionClassifierService(com.msfg.rag.pack.DomainPack pack) {
        Map<QuestionCategory, List<Pattern>> compiled = new LinkedHashMap<>();
        for (var rule : pack.classifierRules()) {
            compiled.put(rule.category(),
                    rule.patterns().stream().map(Pattern::compile).toList());
        }
        this.rules = compiled;
    }
```
and in `classify(...)`: iterate `rules.entrySet()` instead of `RULES.entrySet()`.

- [ ] **Step 7.4: Run to green, full suite, commit**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL` (the classifier test class includes ordering cases like fraud-before-eligibility — they prove order survived the move)

```bash
git add src/main/java/com/msfg/rag/service/ai/QuestionClassifierService.java src/test/java/com/msfg/rag/service/ai/QuestionClassifierServiceTest.java
git commit -m "QuestionClassifierService compiles rules from domain pack"
```

---

### Task 8: AskService canned answers from pack

**Files:**
- Modify: `src/main/java/com/msfg/rag/service/AskService.java`
- Modify: `src/test/java/com/msfg/rag/service/AskServiceTest.java`

- [ ] **Step 8.1: Update the test first.** In `askServiceReturning(...)`, the constructor gains a `DomainPack` as its first argument:

```java
        return new AskService(TestPacks.msfg(), classifier, retrieval, promptBuilder, router,
                new AnswerValidationService(TestPacks.msfg()), audit,
                conversations, messages, sources, new ObjectMapper());
```
and the refusal assertion switches from the deleted static to the pack:
```java
// replace:  assertEquals(AskService.NO_SOURCE_ANSWER, response.answer(), ...)
assertEquals(TestPacks.msfg().guardrails().cannedAnswers().noSource(), response.answer(),
        "a refusal must return the canned refusal text, never the model's raw refusal");
```

- [ ] **Step 8.2: Run — fails to compile:**

Run: `./gradlew test --tests "com.msfg.rag.service.AskServiceTest" --console=plain`
Expected: `BUILD FAILED` — constructor mismatch

- [ ] **Step 8.3: Refactor `AskService`** — delete the six `public static final String` answer constants; add `private final DomainPack.CannedAnswers canned;` and a `DomainPack pack` first constructor parameter setting `this.canned = pack.guardrails().cannedAnswers();`. Replace every usage:

| Old | New |
|---|---|
| `NO_SOURCE_ANSWER` | `canned.noSource()` |
| `ESCALATION_ANSWER` | `canned.escalation()` |
| `LEGAL_ANSWER` | `canned.legal()` |
| `TAX_ANSWER` | `canned.tax()` |
| `LIVE_RATES_ANSWER` | `canned.liveRates()` |
| `FRAUD_ANSWER` | `canned.fraud()` |

(`categoryAnswer(...)` switch, the step-2 insufficient-evidence refusal, the 4a refusal branch, and the step-4 unparseable refusal all update; import `com.msfg.rag.pack.DomainPack`.)

- [ ] **Step 8.4: Run to green, full suite, commit**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL`

```bash
git add src/main/java/com/msfg/rag/service/AskService.java src/test/java/com/msfg/rag/service/AskServiceTest.java
git commit -m "AskService canned answers come from domain pack"
```

---

### Task 9: RetrievalService acronyms + programs from pack

**Files:**
- Modify: `src/main/java/com/msfg/rag/service/retrieval/RetrievalService.java`
- Modify: `src/test/java/com/msfg/rag/service/retrieval/RetrievalServiceTest.java`

- [ ] **Step 9.1: Update the static call sites in the test.** `expandQuery` and `detectPrograms` become parameterized statics; tests pass the MSFG pack data explicitly (add imports `com.msfg.rag.pack.TestPacks`):

```java
// every RetrievalService.expandQuery("...") becomes:
RetrievalService.expandQuery("...", TestPacks.msfg().acronymExpansions())

// every RetrievalService.detectPrograms("...") becomes:
RetrievalService.detectPrograms("...", RetrievalService.compilePrograms(TestPacks.msfg().programRules()))
```
(`programScoreFactor` tests are unchanged — that method has no constants.)

- [ ] **Step 9.2: Run — fails to compile:**

Run: `./gradlew test --tests "com.msfg.rag.service.retrieval.RetrievalServiceTest" --console=plain`
Expected: `BUILD FAILED` — method signature mismatches

- [ ] **Step 9.3: Refactor `RetrievalService`:**
  - Delete the `ACRONYM_EXPANSIONS` constant. `STOPWORDS` **stays in code** (language-level, not company-level — spec §4).
  - Add fields + constructor param (`DomainPack pack` appended last) :
    ```java
    private final Map<String, String> acronyms;
    private final List<CompiledProgram> programs;
    // in the constructor body:
    this.acronyms = pack.acronymExpansions();
    this.programs = compilePrograms(pack.programRules());
    ```
  - New compiled shape + helper (package-private for tests):
    ```java
    /** A program rule with its word-boundary regexes precompiled. */
    record CompiledProgram(String name, List<String> contains, List<java.util.regex.Pattern> patterns) {}

    static List<CompiledProgram> compilePrograms(List<com.msfg.rag.pack.DomainPack.ProgramRule> rules) {
        return rules.stream().map(r -> new CompiledProgram(
                r.program(),
                r.contains(),
                r.wordPatterns().stream().map(java.util.regex.Pattern::compile).toList()))
                .toList();
    }
    ```
  - Re-signature the two statics, preserving behavior exactly:
    ```java
    static String expandQuery(String question, Map<String, String> acronyms) {
        // identical body, but ACRONYM_EXPANSIONS.get(token) -> acronyms.get(token)
    }

    static java.util.Set<String> detectPrograms(String text, List<CompiledProgram> programs) {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        if (text == null) {
            return found;
        }
        String lower = text.toLowerCase(java.util.Locale.US);
        for (CompiledProgram program : programs) {
            boolean hit = program.contains().stream().anyMatch(lower::contains)
                    || program.patterns().stream().anyMatch(p -> p.matcher(lower).find());
            if (hit) {
                found.add(program.name());
            }
        }
        return found;
    }
    ```
  - Instance call sites: `expandQuery(question)` → `expandQuery(question, acronyms)`; `detectPrograms(question)` → `detectPrograms(question, programs)`; private `detectProgram(text)` → `detectPrograms(text, programs).stream().findFirst().orElse(null)`.

- [ ] **Step 9.4: Run to green, full suite, commit**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL` (the existing comparison/single-program/no-program tests prove detection behavior is unchanged)

```bash
git add src/main/java/com/msfg/rag/service/retrieval/RetrievalService.java src/test/java/com/msfg/rag/service/retrieval/RetrievalServiceTest.java
git commit -m "RetrievalService acronym and program rules come from domain pack"
```

---

### Task 10: Flyway V3 — rename tables to brain_*

Only three Java files reference the old names (verified by grep): the two entities' `@Table` and the two native queries in `DocumentChunkRepository`. `ALTER TABLE … RENAME` keeps indexes, FKs, and the `content_tsv` generated column attached (their old names remain — cosmetic, acceptable).

**Files:**
- Create: `src/main/resources/db/migration/V3__rename_to_brain_tables.sql`
- Modify: `src/main/java/com/msfg/rag/domain/MortgageDocument.java` (`@Table`)
- Modify: `src/main/java/com/msfg/rag/domain/DocumentChunk.java` (`@Table`)
- Modify: `src/main/java/com/msfg/rag/repository/DocumentChunkRepository.java` (both queries)

- [ ] **Step 10.1: Write the migration**

`src/main/resources/db/migration/V3__rename_to_brain_tables.sql`:
```sql
-- Platform rename (spec §7): the brain is company-agnostic, table names follow.
-- Rename keeps all indexes, constraints, and generated columns attached.
ALTER TABLE mortgage_document_chunks RENAME TO brain_document_chunks;
ALTER TABLE mortgage_documents RENAME TO brain_documents;
```

- [ ] **Step 10.2: Update the three Java files**
  - `MortgageDocument`: `@Table(name = "mortgage_documents")` → `@Table(name = "brain_documents")`
  - `DocumentChunk`: `@Table(name = "mortgage_document_chunks")` → `@Table(name = "brain_document_chunks")`
  - `DocumentChunkRepository`: in BOTH queries, `FROM mortgage_document_chunks c` → `FROM brain_document_chunks c` and `JOIN mortgage_documents d` → `JOIN brain_documents d`

- [ ] **Step 10.3: Run the Testcontainers integration test** (it runs V1→V3 on a fresh Postgres and exercises both native queries — this IS the failing/passing check for the rename):

Run: `./gradlew test --tests "com.msfg.rag.repository.HybridSearchIntegrationTest" --console=plain`
Expected: `BUILD SUCCESSFUL` (if it fails with `relation "mortgage_document_chunks" does not exist`, a query was missed; with `relation "brain_..." does not exist`, the migration file name/location is wrong)

- [ ] **Step 10.4: Confirm no stragglers**

Run: `grep -rn "mortgage_document" src/main src/test`
Expected: no matches

- [ ] **Step 10.5: Full suite green, then commit**

```bash
git add src/main/resources/db/migration/V3__rename_to_brain_tables.sql src/main/java/com/msfg/rag/domain/ src/main/java/com/msfg/rag/repository/DocumentChunkRepository.java
git commit -m "Rename mortgage_* tables to brain_* (platform template)"
```

> The dev database applies V3 on next boot; the 1,990 existing chunks are untouched (rename is metadata-only). Java class names intentionally unchanged this phase.

---

### Task 11: Slug-driven public endpoint

**Files:**
- Modify: `src/main/java/com/msfg/rag/controller/AskController.java`
- Modify: `src/main/java/com/msfg/rag/config/RateLimitFilter.java`
- Create: `src/test/java/com/msfg/rag/config/RateLimitFilterTest.java`

- [ ] **Step 11.1: Write the failing test**

`src/test/java/com/msfg/rag/config/RateLimitFilterTest.java`:
```java
package com.msfg.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitFilterTest {

    private final RagProperties props = new RagProperties(
            new RagProperties.Routing("anthropic", "openai"),
            new RagProperties.Retrieval(8, 3, 0.35, 0.65, 0.35, true, 24),
            new RagProperties.Chunking(1000, 1200, 150),
            new RagProperties.Storage("./data/documents"),
            new RagProperties.Admin("k"),
            new RagProperties.RateLimit(10));

    @Test
    void limitsTheSlugAskPathOnly() {
        RateLimitFilter filter = new RateLimitFilter(props, "mortgage");

        assertFalse(filter.shouldNotFilter(get("/api/ai/mortgage/ask")),
                "the ask path must be rate limited");
        assertTrue(filter.shouldNotFilter(get("/api/ai/documents")),
                "admin endpoints are not rate limited by this filter");
    }

    @Test
    void followsTheConfiguredSlug() {
        RateLimitFilter filter = new RateLimitFilter(props, "roofing");

        assertFalse(filter.shouldNotFilter(get("/api/ai/roofing/ask")));
        assertTrue(filter.shouldNotFilter(get("/api/ai/mortgage/ask")),
                "the old path is not limited when the slug changes");
    }

    private MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRequestURI(uri);
        return request;
    }
}
```

- [ ] **Step 11.2: Run — fails to compile** (no two-arg constructor):

Run: `./gradlew test --tests "com.msfg.rag.config.RateLimitFilterTest" --console=plain`
Expected: `BUILD FAILED` — constructor mismatch

- [ ] **Step 11.3: Implement**

`RateLimitFilter` — slug-aware path (constructor + `shouldNotFilter` visibility loosened to public for the test):
```java
    private final String askPath;

    public RateLimitFilter(RagProperties properties,
                           @org.springframework.beans.factory.annotation.Value("${brain.slug:mortgage}") String slug) {
        this.requestsPerMinute = properties.rateLimit().requestsPerMinute();
        this.askPath = "/api/ai/" + slug + "/ask";
    }

    @Override
    public boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals(askPath);
    }
```

`AskController` — mapping becomes property-driven:
```java
@RestController
@RequestMapping("/api/ai/${brain.slug:mortgage}")
public class AskController {
```

- [ ] **Step 11.4: Run to green, full suite, commit**

Run: `./gradlew test --console=plain` → `BUILD SUCCESSFUL`

```bash
git add src/main/java/com/msfg/rag/controller/AskController.java src/main/java/com/msfg/rag/config/RateLimitFilter.java src/test/java/com/msfg/rag/config/RateLimitFilterTest.java
git commit -m "Slug-driven ask endpoint and rate-limit path (default unchanged)"
```

---

### Task 12: End-to-end verification

- [ ] **Step 12.1: Full clean suite**

Run: `./gradlew cleanTest test --console=plain`
Expected: `BUILD SUCCESSFUL`, zero failures

- [ ] **Step 12.2: Boot smoke on the alt port** (8080 is often held by a stale instance — never test against it):

```bash
set -a && source .env && set +a
./gradlew bootRun --args='--server.port=8090' &
# wait for {"status":"UP"}:
curl -s http://localhost:8090/actuator/health
```
Expected: app boots (proves V3 migrated the dev DB and the pack loaded). Then:
```bash
curl -s -X POST http://localhost:8090/api/ai/mortgage/ask -H 'Content-Type: application/json' \
  -d '{"sessionId":"phase1-smoke","question":"What is PMI?"}'
```
Expected: JSON answer with non-empty `citations` (or a coherent canned refusal with empty citations), `disclaimer` exactly the pack's disclaimer text. Also verify the guardrail path:
```bash
curl -s -X POST http://localhost:8090/api/ai/mortgage/ask -H 'Content-Type: application/json' \
  -d '{"sessionId":"phase1-smoke","question":"Will I be approved for a mortgage?"}'
```
Expected: the escalation canned text (classifier → ELIGIBILITY), `humanEscalationRequired: true`. Kill the bootRun process afterwards (`lsof -ti:8090 | xargs kill`).

- [ ] **Step 12.3: Pack fail-fast proof** (manual, no commit):

```bash
BRAIN_PACK=packs/does-not-exist ./gradlew bootRun --args='--server.port=8090'
```
Expected: startup ABORTS with `PackValidationException: domain pack packs/does-not-exist: missing required file pack.yaml`. (Ctrl-C if it somehow lingers.)

- [ ] **Step 12.4: Final commit if anything was touched during verification; otherwise done.** Phase ① complete: the only company-specific content remaining in `src/main/java` should be defaults in property placeholders (`mortgage` slugs). Verify with:

```bash
grep -rn "Mountain State\|you qualify\|private mortgage insurance" src/main/java || echo CLEAN
```
Expected: `CLEAN`

---

## Plan self-review (done at write time)

- **Spec coverage (§4, §7, §11, §12-①):** pack files/fields → Tasks 1–3; fail-fast + slug match → Tasks 2, 4; five services consume pack → Tasks 5–9; golden-pack regression → Task 3; table rename → Task 10; slug endpoint → Task 11; "no company constants in code" definition-of-done → Task 12.4. Settings table/runtime routing is **Phase ②, intentionally absent here**.
- **Placeholders:** none — every step carries the code or exact command. The two "body unchanged" notes (Tasks 5, 9) refer to code shown verbatim earlier in this conversation's committed files, and the surrounding diff is specified precisely.
- **Type consistency:** accessors used everywhere are `slug() companyName() disclaimer() promptTemplate() guardrails().prohibitedPhrases() guardrails().eligiblePhrase() guardrails().cannedAnswers().{noSource,escalation,legal,tax,liveRates,fraud}() classifierRules() acronymExpansions() programRules()`; helper `compilePrograms(...)`; record `CompiledProgram`. Constructor orders: `AskService(pack, classifier, retrieval, promptBuilder, router, validator, audit, conversations, messages, sources, objectMapper)`; `RetrievalService(..., pack)` appended last.
