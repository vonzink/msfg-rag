# Phase 5 — Intent Router + pageRoute/surface on AskRequest

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the request seam (`pageRoute` + `surface` on `AskRequest`) and a deterministic, code-driven `IntentRouterService` that classifies each EDUCATIONAL question into an `Intent`. Wire the router into `AskService.ask()` so that the new fields are **accepted and validated** on the public API and an `Intent` is **computed + logged** as the seam Phase 6 will consume. **Behavior MUST be byte-identical to today** — `pageRoute`/`surface` are never read by retrieval, prompt, generation, validation, or the response in Phase 5; intent is computed and logged only.

**Architecture:** Smallest possible integration phase. Two trailing OPTIONAL record components on `AskRequest` (mirroring the existing optional `loanType`/`state`), validated by jakarta `@Size` only (NO `@NotNull` → absent JSON keys deserialize to `null` → today's behavior). `surface` is bound as a **`String`, not the `Surface` enum** — a bad enum value on the public body would otherwise throw a Jackson `InvalidFormatException` (wrapped `HttpMessageNotReadableException`), which `GlobalExceptionHandler` does NOT handle → 500. Keeping it a `String` and calling `Surface.valueOf(...)` in-service yields an `IllegalArgumentException` → clean 400. The router is a pure POJO `@Service` (NOT pack-driven — no `intent.yaml`, no `DomainPack`/golden change): a documented heuristic keyword seam returning a neutral `GUIDELINE_QUESTION` default. It is inserted into `ask()` **after** the compliance `classify()` EDUCATIONAL gate and **before** `retrieve(...)`, computed and logged, changing nothing downstream.

**Tech Stack:** Java 21, Spring Boot 3.5, JUnit 5 + Mockito. Build file: `build.gradle.kts` (no pom). Backend tests run via `./gradlew test`. Reused domain: `com.msfg.rag.domain.Surface { PUBLIC, INTERNAL, BOTH }` (already merged, Phase 3).

---

## Backward-compatibility contract (HARD RULE — verify every task preserves it)

- **`AskRequest` with `pageRoute`/`surface` absent (null) = today's behavior, exactly.** The `ask()` flow — `resolveConversation` → save user msg → `classify` → EDUCATIONAL gate → `retrieve(question)` → sufficiency gate → `build` → `generate` → `parseModelAnswer` → escalation/`ensureCitations` → `validate` → assemble `AskResponse` → persist → `auditLogService.record` → return — is **NOT reordered or altered**. The only new statement is computing + logging `Intent`.
- **`AskResponse`, `ModelAnswer`, `CitationDto`, `RetrievalResult`, `RetrievedChunk` are NOT touched.** No new AskResponse field; the two `new AskResponse(...)` construction sites and the `ensureCitations` all-args `ModelAnswer` reconstruction are unchanged.
- **The boot-locked prompt is NOT touched.** `packs/msfg-mortgage/prompt.yaml` (the 5-`%s` template), `PromptBuilderService.build(...)`, and `DomainPackLoader.validate()` are untouched → no boot risk, no golden change.
- **NO pack file, NO `DomainPack` shape change, NO golden test change.** The router is code-driven; `MsfgGoldenPackTest` and every pack/golden test must stay green untouched.
- **NO migration, NO DB change, NO `AuditLog`/telemetry persistence.** slf4j logging only (telemetry is Phase 9).
- **`QuestionCategory` is NOT extended.** Intent is a separate concept downstream of the compliance category gate; the `categoryAnswer` switch stays exhaustive and untouched.
- The full backend suite (`./gradlew test`) must stay green. The existing `AskServiceTest` scenarios must pass **unchanged in behavior** (only the positional construction sites get a mechanical `new IntentRouterService()` arg + two trailing `null`s on `AskRequest`). Final task (Task 5) is the explicit no-regression gate.

---

## File Structure

### Backend — created

| Path | Responsibility |
|---|---|
| `src/main/java/com/msfg/rag/service/ai/Intent.java` | Plain enum `{ GUIDELINE_QUESTION, PAGE_GUIDANCE, CALCULATION, EXTERNAL_REFERENCE }`. `GUIDELINE_QUESTION` is the neutral default. |
| `src/main/java/com/msfg/rag/service/ai/IntentRouterService.java` | `@Service`; deterministic, code-driven `Intent route(String question, String pageRoute, String surface)`. Validates `surface` via `Surface.valueOf`. |

### Backend — modified

| Path | Change |
|---|---|
| `src/main/java/com/msfg/rag/dto/AskRequest.java` | Append two trailing OPTIONAL record components: `@Size(max = 200) String pageRoute`, `@Size(max = 20) String surface`. |
| `src/main/java/com/msfg/rag/service/AskService.java` | Add `IntentRouterService` as the 12th constructor parameter; after the `classify` EDUCATIONAL gate and before `retrieve(...)`, compute `Intent intent = intentRouterService.route(...)` and `log.debug(...)` it. |

### Backend — tests created

| Path | Responsibility |
|---|---|
| `src/test/java/com/msfg/rag/service/ai/IntentRouterServiceTest.java` | Pure POJO test (`new IntentRouterService()`, no Spring): every branch, pageRoute-wins precedence, blank/null → `GUIDELINE_QUESTION`, bad surface → `assertThrows(IllegalArgumentException)`. |

### Backend — tests modified

| Path | Change |
|---|---|
| `src/test/java/com/msfg/rag/service/AskServiceTest.java` | Both positional `new AskService(...)` builders (lines 93 & 121) gain a `new IntentRouterService()` arg; the `AskRequest` construction (`pmiQuestion()`, line 127) gains two trailing `null`s; add two new tests: pageRoute-set answer-parity + bad-surface → `IllegalArgumentException`. |

### NOT touched (verify untouched at the end)

`dto/AskResponse.java`, `service/ai/ModelAnswer.java`, `dto/CitationDto.java`, `service/ai/QuestionCategory.java`, `service/ai/QuestionClassifierService.java`, `service/ai/PromptBuilderService.java`, `packs/msfg-mortgage/prompt.yaml`, `pack/DomainPack.java`, `pack/DomainPackLoader.java`, `controller/AskController.java`, `exception/GlobalExceptionHandler.java`, `domain/Surface.java`, `MsfgGoldenPackTest`, every migration.

---

## Conventions for every backend task

- **Test commands:** backend via `./gradlew test --tests "<FQN>"` (full suite: `./gradlew test`). The build is `build.gradle.kts`. NOTE: in this environment the raw `./gradlew` invocation is hook-redirected — when *executing*, run it through the context-mode shell wrapper: `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests '<FQN>'")`. This plan WRITES the literal `./gradlew test --tests "..."` command for each step.
- **Tests are JUnit 5 + Mockito.** `IntentRouterServiceTest` is a pure POJO test (`new IntentRouterService()`, no Spring context, no mocks needed). `AskServiceTest` mirrors the existing file: `TestPacks.msfg()` + positional `new AskService(...)` construction + Mockito-mocked collaborators.
- **Keep test classes in the same package** as the class under test for package-private access (`IntentRouterServiceTest` → `com.msfg.rag.service.ai`; `AskServiceTest` → `com.msfg.rag.service`).
- **400-vs-500 contract:** `IllegalArgumentException` is mapped to HTTP 400 `{"error": msg}` by the existing `GlobalExceptionHandler` (`@RestControllerAdvice`). Do NOT add try/catch or a new `@ExceptionHandler`. Surface validation throwing `IllegalArgumentException` IS the 400 path.
- **Commit** after each green task with the exact commands shown. End every commit message with the Co-Authored-By trailer.

---

## Verified anchors (do not re-research)

- **`AskService` constructor has exactly 11 positional parameters today** (AskService.java lines 59–69): `DomainPack pack, QuestionClassifierService, RetrievalService, PromptBuilderService, ModelRouterService, AnswerValidationService, AuditLogService, ConversationRepository, MessageRepository, AnswerSourceRepository, ObjectMapper`. After Phase 5 it has **12** (the new `IntentRouterService`).
- **`AskServiceTest` positional `new AskService(...)` builders are at lines 93 and 121** (the `return new AskService(TestPacks.msfg(), classifier, retrieval, promptBuilder, router, new AnswerValidationService(TestPacks.msfg()), audit, conversations, messages, sources, new ObjectMapper());` in `askServiceReturning` and `askServiceClassifying`).
- **`AskRequest` is constructed positionally at AskServiceTest.java line 127** (`pmiQuestion()`): `new AskRequest(null, "session-1", "What is PMI?", null, null)` — 5 args today, 7 after Phase 5.
- **The intent computation slots into `ask()` after the EDUCATIONAL gate (AskService.java lines 90–94) and before `retrievalService.retrieve(...)` (line 97).**
- **`AskRequest` today** (AskRequest.java): `record AskRequest(UUID conversationId, @NotBlank @Size(255) String sessionId, @NotBlank @Size(2000) String question, @Size(50) String loanType, @Size(2) String state)`.
- **`Surface`** (`com.msfg.rag.domain.Surface`): `PUBLIC, INTERNAL, BOTH`.

---

## Task 1: `Intent` enum

**Files:**
- Create: `src/main/java/com/msfg/rag/service/ai/Intent.java`

Plain Java enum, UPPER_SNAKE constants, no annotations (mirrors `QuestionCategory.java` style). `GUIDELINE_QUESTION` is the neutral default the router falls back to. There is no standalone enum unit test in this repo (enums are tested transitively); verification is a compile.

- [ ] **Write `Intent.java`** (complete, copy-paste-ready):

```java
package com.msfg.rag.service.ai;

/**
 * Heuristic routing intent for an EDUCATIONAL question, computed AFTER the
 * compliance {@link QuestionCategory} gate and BEFORE retrieval. This is a
 * separate concept from {@link QuestionCategory}: the category enum is the
 * compliance guardrail (it decides whether we answer at all); Intent is a
 * downstream hint about HOW to retrieve/answer, consumed by later phases.
 *
 * <p>Phase 5 only computes and logs the intent — nothing reads it yet, so the
 * pipeline behaves identically to today. {@code GUIDELINE_QUESTION} is the
 * neutral default returned for ordinary guideline questions and for any
 * null/blank input, preserving the classifier's proceed-by-default anchor.
 *
 * <p><b>Spec §7.3 reconciliation:</b> the spec lists five intent names:
 * {@code question_answering}, {@code page_guidance}, {@code external_reference},
 * {@code calculator}, and {@code handoff}. The Java enum uses deliberate
 * renames: {@code GUIDELINE_QUESTION} (was {@code question_answering}) and
 * {@code CALCULATION} (was {@code calculator}) are idiomatic UPPER_SNAKE Java
 * names. {@code handoff} is intentionally deferred — there is no consumer for
 * it until a later phase — so no {@code HANDOFF} constant is added here.
 */
public enum Intent {

    /** Ordinary guideline/education question — the neutral default. */
    GUIDELINE_QUESTION,

    /** The caller is on a specific page and wants page-scoped guidance. */
    PAGE_GUIDANCE,

    /** A numeric/calculation question (payment, DTI, LTV, amortization, rate). */
    CALCULATION,

    /** A request for an official/external source, link, or handbook reference. */
    EXTERNAL_REFERENCE
}
```

- [ ] **Verify compile:**
  `./gradlew compileJava`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/service/ai/Intent.java
git commit -m "feat(intent): Intent enum for the Phase 5 routing seam

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `IntentRouterService` + pure POJO test

**Files:**
- Create: `src/main/java/com/msfg/rag/service/ai/IntentRouterService.java`
- Test: `src/test/java/com/msfg/rag/service/ai/IntentRouterServiceTest.java`

A code-driven (NOT pack-driven) deterministic `@Service` with one public method `Intent route(String question, String pageRoute, String surface)`. No constructor dependencies — instantiable with `new IntentRouterService()` in tests. Deterministic rule order (documented in Javadoc):

1. **Validate `surface` first** (regardless of branch): if non-null and non-blank, `Surface.valueOf(surface.strip().toUpperCase(Locale.US))` — a bad value throws `IllegalArgumentException` (→ 400). A null/blank surface is fine (no validation). Validating up front keeps the 400 deterministic and independent of which intent branch wins.
2. **`pageRoute` wins:** if `pageRoute` is non-blank → `PAGE_GUIDANCE`.
3. **Null/blank question** → `GUIDELINE_QUESTION` (preserve the proceed-by-default anchor).
4. **Calculation cues** (case-insensitive `contains`): any of `calculate`, `payment`, `how much`, `monthly`, `dti`, `ltv`, `amortiz`, `rate`, `%` → `CALCULATION`.
5. **External-reference cues** (case-insensitive `contains`): any of `official`, `source`, `link`, `where can i find`, `guideline number`, `handbook` → `EXTERNAL_REFERENCE`.
6. Otherwise → `GUIDELINE_QUESTION`.

**Judgment-call / review note (state in the Javadoc):** the keyword sets are a small, deliberately-conservative heuristic seam, NOT the final intent taxonomy. They are code constants (not a pack file) by design for Phase 5. Calculation is checked before external-reference (a question like "how much is the rate per the handbook?" resolves to `CALCULATION` — acceptable for a heuristic seam since nothing consumes intent yet). Refine in a later phase once intent is actually consumed.

- [ ] **Write the failing test** (`IntentRouterServiceTest.java`) — pure POJO, no Spring, no mocks:

```java
package com.msfg.rag.service.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure unit test for the deterministic, code-driven intent router. No Spring,
 * no mocks — {@code new IntentRouterService()}.
 */
class IntentRouterServiceTest {

    private final IntentRouterService router = new IntentRouterService();

    // --- neutral default ------------------------------------------------

    @Test
    void plainGuidelineQuestionIsGuidelineQuestion() {
        assertEquals(Intent.GUIDELINE_QUESTION,
                router.route("What is PMI?", null, null));
    }

    @Test
    void nullQuestionIsGuidelineQuestion() {
        assertEquals(Intent.GUIDELINE_QUESTION, router.route(null, null, null));
    }

    @Test
    void blankQuestionIsGuidelineQuestion() {
        assertEquals(Intent.GUIDELINE_QUESTION, router.route("   ", null, null));
    }

    // --- pageRoute wins -------------------------------------------------

    @Test
    void nonBlankPageRouteWinsOverEverything() {
        // The question contains a calculation cue ("monthly") AND an
        // external-reference cue ("handbook"), yet pageRoute takes precedence.
        assertEquals(Intent.PAGE_GUIDANCE,
                router.route("what is my monthly payment per the handbook?",
                        "/loan-options", null));
    }

    @Test
    void blankPageRouteDoesNotWin() {
        assertEquals(Intent.GUIDELINE_QUESTION,
                router.route("What is PMI?", "   ", null));
    }

    // --- calculation cues -----------------------------------------------

    @Test
    void calculationCueIsCalculation() {
        assertEquals(Intent.CALCULATION,
                router.route("How much will my monthly payment be?", null, null));
    }

    @Test
    void calculationCueIsCaseInsensitive() {
        assertEquals(Intent.CALCULATION,
                router.route("Calculate my DTI", null, null));
    }

    @Test
    void percentSignIsCalculation() {
        assertEquals(Intent.CALCULATION,
                router.route("Is 3% down enough?", null, null));
    }

    // --- external-reference cues ----------------------------------------

    @Test
    void externalReferenceCueIsExternalReference() {
        assertEquals(Intent.EXTERNAL_REFERENCE,
                router.route("Where can I find the official source?", null, null));
    }

    @Test
    void handbookCueIsExternalReference() {
        assertEquals(Intent.EXTERNAL_REFERENCE,
                router.route("Give me the FHA handbook link", null, null));
    }

    // --- calculation precedence over external-reference -----------------

    @Test
    void calculationIsCheckedBeforeExternalReference() {
        // Contains both "rate" (calc) and "handbook" (external) — calc wins.
        assertEquals(Intent.CALCULATION,
                router.route("what rate does the handbook list?", null, null));
    }

    // --- surface validation ---------------------------------------------

    @Test
    void validSurfaceIsAcceptedAndDoesNotAlterIntent() {
        assertEquals(Intent.GUIDELINE_QUESTION,
                router.route("What is PMI?", null, "PUBLIC"));
    }

    @Test
    void validSurfaceIsCaseInsensitiveAndTrimmed() {
        assertEquals(Intent.GUIDELINE_QUESTION,
                router.route("What is PMI?", null, "  internal  "));
    }

    @Test
    void nullSurfaceIsAccepted() {
        assertEquals(Intent.GUIDELINE_QUESTION,
                router.route("What is PMI?", null, null));
    }

    @Test
    void blankSurfaceIsAccepted() {
        assertEquals(Intent.GUIDELINE_QUESTION,
                router.route("What is PMI?", null, "   "));
    }

    @Test
    void badSurfaceThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> router.route("What is PMI?", null, "SIDEWAYS"));
    }

    @Test
    void badSurfaceThrowsEvenWhenPageRouteWouldWin() {
        // Surface is validated up front, independent of the winning branch.
        assertThrows(IllegalArgumentException.class,
                () -> router.route("What is PMI?", "/loan-options", "NOPE"));
    }
}
```

- [ ] **Run it — expected FAIL:** `IntentRouterService` does not exist yet → compilation failure.
  `./gradlew test --tests "com.msfg.rag.service.ai.IntentRouterServiceTest"`
  Expected: compile error — `cannot find symbol: class IntentRouterService` (test does not run).

- [ ] **Write the service** (`IntentRouterService.java`) — complete, copy-paste-ready:

```java
package com.msfg.rag.service.ai;

import com.msfg.rag.domain.Surface;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic, code-driven intent router (Phase 5). Computes a heuristic
 * {@link Intent} for an EDUCATIONAL question AFTER the compliance
 * {@link QuestionCategory} gate and BEFORE retrieval. It NEVER replaces the
 * category gate and NEVER folds intent into {@link QuestionCategory}.
 *
 * <p><b>Phase 5 scope:</b> the result is computed and logged by the caller only;
 * nothing reads it yet, so the pipeline behaves identically to today. This is a
 * forward seam for Phase 6.
 *
 * <p><b>Not pack-driven on purpose:</b> the keyword sets below are code
 * constants, not an {@code intent.yaml}. They are a small, deliberately
 * conservative heuristic, NOT the final taxonomy — refine in a later phase once
 * intent is actually consumed.
 *
 * <p><b>Broad substring cues (intentional):</b> keywords like {@code rate},
 * {@code source}, and {@code link} are deliberately broad — they match
 * substrings such as "sepa<b>rate</b>", "re<b>source</b>ful", and
 * "<b>link</b>age". This is acceptable for Phase 5 because nothing consumes
 * intent yet; tighten to word-boundary or phrase matches when Phase 6 actually
 * reads intent routing results.
 *
 * <p><b>Surface parsing divergence (intentional):</b> {@code route()} parses
 * surface as {@code Surface.valueOf(surface.strip().toUpperCase(Locale.US))}
 * (lenient, public-facing — accepts {@code "public"} or {@code "Public"}),
 * whereas the admin {@code SourceLinkService}/{@code PageGuideService} parsers
 * use {@code Surface.valueOf(value.strip())} (case-sensitive). A shared
 * {@code Surface.parse(String)} helper could unify them later; not required
 * for Phase 5.
 *
 * <p><b>Deterministic rule order:</b>
 * <ol>
 *   <li>Validate {@code surface} first (regardless of which branch wins): if
 *       non-null and non-blank, {@code Surface.valueOf(surface.strip().toUpperCase())};
 *       a bad value throws {@link IllegalArgumentException} (mapped to HTTP 400 by
 *       the global handler). Null/blank surface is accepted.</li>
 *   <li>If {@code pageRoute} is non-blank → {@link Intent#PAGE_GUIDANCE}.</li>
 *   <li>If {@code question} is null/blank → {@link Intent#GUIDELINE_QUESTION}
 *       (preserves the classifier's proceed-by-default anchor).</li>
 *   <li>If the question contains any calculation cue → {@link Intent#CALCULATION}.</li>
 *   <li>If the question contains any external-reference cue →
 *       {@link Intent#EXTERNAL_REFERENCE}.</li>
 *   <li>Otherwise → {@link Intent#GUIDELINE_QUESTION}.</li>
 * </ol>
 * Calculation is intentionally checked before external-reference, so a question
 * carrying both cues resolves to {@code CALCULATION} (acceptable for a heuristic
 * seam that nothing consumes yet).
 */
@Service
public class IntentRouterService {

    /** Numeric / calculation cues (matched case-insensitively, substring). */
    private static final List<String> CALCULATION_CUES = List.of(
            "calculate", "payment", "how much", "monthly",
            "dti", "ltv", "amortiz", "rate", "%");

    /** Official / external-source cues (matched case-insensitively, substring). */
    private static final List<String> EXTERNAL_REFERENCE_CUES = List.of(
            "official", "source", "link", "where can i find",
            "guideline number", "handbook");

    /**
     * Computes the heuristic intent. Validates {@code surface} up front so a bad
     * value yields a clean 400 regardless of the winning branch.
     *
     * @param question  the user's question (may be null/blank)
     * @param pageRoute optional page route the caller is on (may be null/blank)
     * @param surface   optional audience string ("PUBLIC"/"INTERNAL"/"BOTH",
     *                  case-insensitive); a bad value throws IllegalArgumentException
     * @return the computed {@link Intent}; never null
     * @throws IllegalArgumentException if {@code surface} is non-blank and not a
     *                                  valid {@link Surface} name
     */
    public Intent route(String question, String pageRoute, String surface) {
        // 1. Validate surface first (independent of which branch wins).
        if (surface != null && !surface.isBlank()) {
            Surface.valueOf(surface.strip().toUpperCase(Locale.US));
        }

        // 2. pageRoute wins.
        if (pageRoute != null && !pageRoute.isBlank()) {
            return Intent.PAGE_GUIDANCE;
        }

        // 3. Proceed-by-default anchor for empty questions.
        if (question == null || question.isBlank()) {
            return Intent.GUIDELINE_QUESTION;
        }

        String normalized = question.toLowerCase(Locale.US).strip();

        // 4. Calculation cues (checked before external-reference by design).
        if (containsAny(normalized, CALCULATION_CUES)) {
            return Intent.CALCULATION;
        }

        // 5. External-reference cues.
        if (containsAny(normalized, EXTERNAL_REFERENCE_CUES)) {
            return Intent.EXTERNAL_REFERENCE;
        }

        // 6. Neutral default.
        return Intent.GUIDELINE_QUESTION;
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Run it — expected PASS:**
  `./gradlew test --tests "com.msfg.rag.service.ai.IntentRouterServiceTest"`
  Expected: `BUILD SUCCESSFUL`, 17 tests passing.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/service/ai/IntentRouterService.java src/test/java/com/msfg/rag/service/ai/IntentRouterServiceTest.java
git commit -m "feat(intent): deterministic IntentRouterService + POJO test

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `AskRequest` — append `pageRoute` + `surface`

**Files:**
- Modify: `src/main/java/com/msfg/rag/dto/AskRequest.java`
- Modify (compile-fix only): `src/test/java/com/msfg/rag/service/AskServiceTest.java` (the `pmiQuestion()` construction at line 127)

Append two trailing OPTIONAL record components mirroring the existing optional `loanType`/`state`: `@Size(max = 200) String pageRoute` and `@Size(max = 20) String surface`. **No `@NotNull`** → absent JSON keys deserialize to `null` → today's behavior. `surface` is a **`String`, not the `Surface` enum** (a bad enum on the body would 500; String + in-service `valueOf` gives a clean 400). Production builds `AskRequest` via Jackson `@RequestBody`, so trailing components are wire-compatible (absent keys → null). The ONLY positional construction site is the test (`pmiQuestion()`, line 127) — it must gain two trailing `null`s or the test suite won't compile.

- [ ] **Edit `AskRequest.java`** — append the two components after `state`:

```java
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
```

- [ ] **Fix the test construction site** in `AskServiceTest.java`. Change `pmiQuestion()` (currently `new AskRequest(null, "session-1", "What is PMI?", null, null)` at line 127) to add two trailing `null`s:

```java
    private AskRequest pmiQuestion() {
        return new AskRequest(null, "session-1", "What is PMI?", null, null, null, null);
    }
```

- [ ] **Verify compile** (the test compiles even though `AskService` does not yet take `IntentRouterService` — that wiring is Task 4; this step only proves `AskRequest` + the construction-site fix compile):
  `./gradlew compileTestJava`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Run the existing AskService suite — expected PASS (behavior unchanged):**
  `./gradlew test --tests "com.msfg.rag.service.AskServiceTest"`
  Expected: `BUILD SUCCESSFUL`, all existing tests green (the two new `null`s are inert).

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/dto/AskRequest.java src/test/java/com/msfg/rag/service/AskServiceTest.java
git commit -m "feat(intent): optional pageRoute + surface on AskRequest

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Wire `IntentRouterService` into `AskService` (ctor + compute/validate/log) + AskService tests

**Files:**
- Modify: `src/main/java/com/msfg/rag/service/AskService.java`
- Modify: `src/test/java/com/msfg/rag/service/AskServiceTest.java`

Add `IntentRouterService` as the **12th** constructor parameter (after `objectMapper`) and store it. In `ask()`, **after** the `classify` EDUCATIONAL gate (lines 90–94) and **before** `retrievalService.retrieve(...)` (line 97), compute `Intent intent = intentRouterService.route(request.question(), request.pageRoute(), request.surface());` and `log.info(...)` it with intent + pageRoute + surface. **Do not otherwise change the flow** — retrieve/prompt/generate/validate/assemble stay exactly as-is; intent is computed (which validates surface) and logged only. (`log.info` is required: `application.yml` sets `logging.level.com.msfg.rag: INFO`, so a `debug` line would be invisible in staging; the comparable citation-salvage log at `AskService.java:135` also uses `log.info`.)

**Surface-validation placement decision (locked, be consistent):** surface is validated **inside `route()`**, which is called only on the EDUCATIONAL/proceed path (after the category gate). Consequence: a bad surface on a *refused* (non-EDUCATIONAL) question is ignored — acceptable, since the request is already being refused and the new fields drive nothing. We deliberately do NOT hoist surface validation above the category gate, to avoid changing refusal semantics (a fraud/eligibility/legal/tax/rates question must still refuse identically regardless of surface). This keeps refusal behavior byte-identical to today.

We write the test changes first (TDD): a bad-surface test that fails because `IntentRouterService` is not yet wired, then the implementation.

- [ ] **Write/extend the failing tests** in `AskServiceTest.java`. Three mechanical edits + two new tests:

  1. Add the import (alongside the other `com.msfg.rag.service.ai.*` imports near the top):

```java
import com.msfg.rag.service.ai.IntentRouterService;
```

  2. In `askServiceReturning(...)`, change the `return new AskService(...)` at **line 93** to add `new IntentRouterService()` as the final argument:

```java
        return new AskService(TestPacks.msfg(), classifier, retrieval, promptBuilder, router,
                new AnswerValidationService(TestPacks.msfg()), audit,
                conversations, messages, sources, new ObjectMapper(),
                new IntentRouterService());
```

  3. In `askServiceClassifying(...)`, change the `return new AskService(...)` at **line 121** to add `new IntentRouterService()` as the final argument:

```java
        return new AskService(TestPacks.msfg(), classifier, retrieval, promptBuilder, router,
                new AnswerValidationService(TestPacks.msfg()), audit,
                conversations, messages, sources, new ObjectMapper(),
                new IntentRouterService());
```

  4. Add a small helper for a request carrying pageRoute/surface, and two new tests, at the end of the class (before the closing brace). The `pageRouteDoesNotChangeTheAnswer` test uses **one** `AskService` instance and calls `.ask(...)` twice on it, plus an `ArgumentCaptor` to prove retrieval always sees the raw question string regardless of pageRoute/surface. Imports needed: `org.mockito.ArgumentCaptor` and `org.mockito.Mockito.verify` (add alongside existing Mockito imports):

```java
import org.mockito.ArgumentCaptor;
```

```java
    /** A PMI question that also carries an optional pageRoute and surface. */
    private AskRequest pmiQuestionWith(String pageRoute, String surface) {
        return new AskRequest(null, "session-1", "What is PMI?", null, null, pageRoute, surface);
    }

    @Test
    void pageRouteDoesNotChangeTheAnswer() {
        // One AskService instance; intent is computed + logged only — must not
        // alter the produced answer or leak into the retrieval question string.
        String groundedJson = """
                {"answer":"PMI is private mortgage insurance that may be required on conventional loans.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";
        List<RetrievedChunk> chunks = List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1)),
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-2", 2, LocalDate.of(2026, 1, 1)));

        // Re-use a single service + a single stubbed retrieval mock for both calls.
        AskService service = askServiceReturning(groundedJson, chunks);

        AskResponse without  = service.ask(pmiQuestion());
        AskResponse withPage = service.ask(pmiQuestionWith("/loan-options", "PUBLIC"));

        assertEquals(without.answer(), withPage.answer(),
                "pageRoute/surface must not change the answer in Phase 5");
        assertEquals(without.citations().size(), withPage.citations().size());
        assertEquals(without.humanEscalationRequired(), withPage.humanEscalationRequired());
        assertEquals(without.confidence(), withPage.confidence());

        // Prove retrieval always receives the raw question string — pageRoute and
        // surface must never leak into the retrieval call.
        ArgumentCaptor<String> questionCaptor = ArgumentCaptor.forClass(String.class);
        verify(retrieval, atLeast(2)).retrieve(questionCaptor.capture());
        List<String> capturedQuestions = questionCaptor.getAllValues();
        // Every captured question must equal the original PMI question string.
        capturedQuestions.forEach(q ->
                assertEquals("What is PMI?", q,
                        "retrieve() must be called with the original question, not pageRoute/surface"));
    }

    @Test
    void badSurfaceThrowsIllegalArgumentException() {
        // A malformed surface on an EDUCATIONAL question surfaces as
        // IllegalArgumentException -> HTTP 400 via GlobalExceptionHandler.
        String groundedJson = """
                {"answer":"PMI is private mortgage insurance.",
                 "citations":[],
                 "confidence":0.85,
                 "human_escalation_required":false,
                 "disclaimer":"d"}""";
        AskService service = askServiceReturning(groundedJson, List.of(
                chunk("Fannie Mae Selling Guide", "selling-guide.pdf", "B7-1", 1, LocalDate.of(2026, 1, 1))));

        assertThrows(IllegalArgumentException.class,
                () -> service.ask(pmiQuestionWith(null, "SIDEWAYS")));
    }
```

  5. Add the following static imports if not already present (the existing file imports `assertEquals/assertFalse/assertNull/assertTrue` but NOT `assertThrows` or `atLeast`):

```java
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
```

- [ ] **Run it — expected FAIL:** `new AskService(...)` now passes 12 args but the constructor still takes 11 → compile error.
  `./gradlew test --tests "com.msfg.rag.service.AskServiceTest"`
  Expected: compile error — constructor `AskService(...)` cannot be applied to the given 12 arguments (test does not run).

- [ ] **Edit `AskService.java`** — add the field, the constructor parameter, the assignment, and the compute/log statement.

  1. Add the import (with the other `com.msfg.rag.service.ai.*` imports):

```java
import com.msfg.rag.service.ai.Intent;
import com.msfg.rag.service.ai.IntentRouterService;
```

  2. Add the field (after the `questionClassifierService` field, near line 48):

```java
    private final IntentRouterService intentRouterService;
```

  3. Add the constructor parameter (append after `ObjectMapper objectMapper`) and the assignment (append after `this.objectMapper = objectMapper;`). The full constructor becomes:

```java
    public AskService(DomainPack pack,
                      QuestionClassifierService questionClassifierService,
                      RetrievalService retrievalService,
                      PromptBuilderService promptBuilderService,
                      ModelRouterService modelRouterService,
                      AnswerValidationService answerValidationService,
                      AuditLogService auditLogService,
                      ConversationRepository conversationRepository,
                      MessageRepository messageRepository,
                      AnswerSourceRepository answerSourceRepository,
                      ObjectMapper objectMapper,
                      IntentRouterService intentRouterService) {
        this.canned = pack.guardrails().cannedAnswers();
        this.questionClassifierService = questionClassifierService;
        this.retrievalService = retrievalService;
        this.promptBuilderService = promptBuilderService;
        this.modelRouterService = modelRouterService;
        this.answerValidationService = answerValidationService;
        this.auditLogService = auditLogService;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.answerSourceRepository = answerSourceRepository;
        this.objectMapper = objectMapper;
        this.intentRouterService = intentRouterService;
    }
```

  4. Insert the compute/log statement in `ask()` between the EDUCATIONAL gate and the retrieve call. The existing block is:

```java
        QuestionCategory category = questionClassifierService.classify(request.question());
        if (category != QuestionCategory.EDUCATIONAL) {
            return refuse(conversation, request, RetrievalResult.empty(),
                    categoryAnswer(category, canned), null, "classified as " + category);
        }

        // 1. Retrieve approved source context.
        RetrievalResult retrieval = retrievalService.retrieve(request.question());
```

  Change it to (insert the intent block between the gate and the retrieve comment):

```java
        QuestionCategory category = questionClassifierService.classify(request.question());
        if (category != QuestionCategory.EDUCATIONAL) {
            return refuse(conversation, request, RetrievalResult.empty(),
                    categoryAnswer(category, canned), null, "classified as " + category);
        }

        // Routing seam (Phase 5): compute + log intent, validate surface — consumed by the Phase 6 planner.
        // This is a distinct post-EDUCATIONAL-gate step, not a sub-step of the compliance gate above.
        // A bad surface value throws IllegalArgumentException -> HTTP 400 via GlobalExceptionHandler.
        // Nothing else reads intent yet; the flow below is byte-identical to today.
        Intent intent = intentRouterService.route(
                request.question(), request.pageRoute(), request.surface());
        log.info("Routed question to intent {} (pageRoute={}, surface={})",
                intent, request.pageRoute(), request.surface());

        // 1. Retrieve approved source context.
        RetrievalResult retrieval = retrievalService.retrieve(request.question());
```

- [ ] **Run it — expected PASS:**
  `./gradlew test --tests "com.msfg.rag.service.AskServiceTest"`
  Expected: `BUILD SUCCESSFUL`, all existing tests + the two new tests green.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/service/AskService.java src/test/java/com/msfg/rag/service/AskServiceTest.java
git commit -m "feat(intent): wire IntentRouterService into AskService (compute + log only)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Full-suite no-regression verification

**Files:** none modified (verification gate).

Confirms (a) the existing `AskServiceTest` scenarios still pass unchanged, (b) the golden pack lock and every other test stay green untouched (no pack/golden/migration was changed), and (c) the app still wires (`IntentRouterService` is a no-arg `@Service`, so Spring constructs it and injects it into `AskService` with no extra config).

- [ ] **Run the full backend suite — expected PASS:**
  `./gradlew test`
  Expected: `BUILD SUCCESSFUL`. Specifically confirm green: `com.msfg.rag.service.AskServiceTest`, `com.msfg.rag.service.ai.IntentRouterServiceTest`, `com.msfg.rag.pack.MsfgGoldenPackTest`, and `com.msfg.rag.pack.DomainPackLoaderTest`.

- [ ] **Confirm the no-touch set is unchanged** (git should show NO modifications to these):
  `git status --porcelain src/main/java/com/msfg/rag/dto/AskResponse.java src/main/java/com/msfg/rag/service/ai/ModelAnswer.java src/main/java/com/msfg/rag/service/ai/QuestionCategory.java src/main/java/com/msfg/rag/service/ai/PromptBuilderService.java packs/msfg-mortgage/prompt.yaml src/main/java/com/msfg/rag/pack/DomainPack.java src/main/java/com/msfg/rag/pack/DomainPackLoader.java`
  Expected: empty output (no files listed) — none of the boot-locked / response / golden surfaces were touched.

- [ ] **Confirm no new migration was added:**
  `git status --porcelain src/main/resources/db/migration/`
  Expected: empty output.

- [ ] **Final commit (only if any tracked verification artifact changed — normally nothing to commit here).** If `git status` is clean, skip. Otherwise:
```
git add -A
git commit -m "test(intent): Phase 5 full-suite no-regression verification

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review (writing-plans)

**Spec coverage (every LOCKED DESIGN DECISION mapped to a task):**
- New enum `Intent` in `com.msfg.rag.service.ai` with the four UPPER_SNAKE values, `GUIDELINE_QUESTION` default → **Task 1**.
- `AskRequest` gains trailing OPTIONAL `@Size(max=200) String pageRoute` + `@Size(max=20) String surface` (String, NOT enum; no `@NotNull`); fixes the single positional construction site → **Task 3**.
- `IntentRouterService` `@Service`, code-driven, `Intent route(String, String, String)`, surface validated via `Surface.valueOf(strip().toUpperCase(Locale.US))`, pageRoute-wins precedence, calculation/external cue sets, null/blank → GUIDELINE_QUESTION, thoroughly unit-tested incl. bad-surface `assertThrows` → **Task 2**.
- `AskService` gains `IntentRouterService` as the 12th ctor param; computes + logs intent after the EDUCATIONAL gate and before retrieve; both positional test builders updated; backward-compat + bad-surface AskService tests added → **Task 4**.
- No `AuditLog`/telemetry persistence (slf4j only) → honored in Task 4 (`log.info`).
- No-regression gate + null pageRoute/surface = identical flow → **Task 5** + the `pageRouteDoesNotChangeTheAnswer` test in Task 4.

**Placeholder scan:** No `TODO`, `FIXME`, `...`, `<placeholder>`, or "implement here" in any code block. Every file is complete and copy-paste-ready.

**Type / signature consistency vs the live files:**
- `AskService` ctor arity verified = **11** today (AskService.java:59–69) → **12** after Task 4. Field/assignment/param all added consistently.
- `AskServiceTest` positional `new AskService(...)` builders verified at **lines 93 and 121**; `AskRequest` positional construction (`pmiQuestion()`) verified at **line 127** (5 args → 7). All three updated mechanically; new tests use the same `TestPacks.msfg()` + mock idiom as the existing file.
- `Surface` is `com.msfg.rag.domain.Surface { PUBLIC, INTERNAL, BOTH }` — reused, not redefined.
- `AskRequest` mirrors the verified existing shape; `loanType`/`state` optional-field pattern matched exactly.
- `assertThrows` static import is NOT in the existing `AskServiceTest` imports → Task 4 adds it. `assertEquals` IS already imported (used by new parity test).
- `Intent`/`IntentRouterService` live in `com.msfg.rag.service.ai` (same package as `QuestionCategory`/`QuestionClassifierService`); `AskService` adds explicit imports for both.

**Boot-lock / golden safety:** prompt.yaml, the 5-`%s` template, `PromptBuilderService.build`, `DomainPackLoader.validate`, `DomainPack`, and `MsfgGoldenPackTest` are untouched (Task 5 asserts this) — no boot or golden risk. No migration added.
