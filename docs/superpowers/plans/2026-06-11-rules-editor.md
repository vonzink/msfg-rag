# Phase 4.5 — Editable Rules (Hard / Guidance) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The brain's answer policy becomes two owner-editable text blocks — **hard rules** (no wiggle room) and **guidance** (strong recommendations) — seeded from the pack, revised from a new dashboard Rules screen with an append-only revision history, live within ~10 s, always revertible to the pack default. Approved design + mockup locked in conversation 2026-06-11.

**Architecture:** `prompt.yaml` splits into a locked 5-placeholder skeleton + `hard-rules` + `guidance` default texts (DomainPack gains both fields). New `brain_rule_revisions` table (V6, append-only; null content = revert-to-pack marker) read through a cached `RulesService` (effective text = latest revision, else pack default). `PromptBuilderService` assembles skeleton.formatted(hard, guidance, context, question, disclaimer). `AdminRulesController` exposes GET state / PUT revision / revert / history / assembled-prompt preview, inside the existing admin gate + CORS. Dashboard gains the Rules screen per the mockup.

**DELIBERATE PROMPT CHANGE (the one behavioral delta):** restructuring "Rules: 1–9" into two labeled sections cannot be byte-identical to the old prompt. The new default assembly is a reviewed, semantically-equivalent restructure — every old rule sentence is preserved verbatim, only the section headers and numbering change. The new assembled default is byte-locked by an updated golden test, and live behavior is re-verified in E2E (grounded PMI answer with citations; guardrail refusals unchanged — those live in classifier/validator, untouched here).

**Tech Stack:** Java 21 / Spring Boot 3.5, Flyway V6, JPA, JUnit+Mockito, Testcontainers; React/Vite dashboard. Spec refs: platform design §4 (pack), §8 (dashboard) — this phase amends both per the approved design.

**Prerequisites:** Branch `feat/rules-editor` (off main `e854474`). Java suite green (214), dashboard gates green.

**Safety rules for every worker:** FIRST command `git branch --show-current` must print `feat/rules-editor` — else STOP/BLOCKED. Never `git checkout <sha>` / `git reset` / `git rebase`. Reviewers read-only. Don't touch `scripts/`. Java tasks: full `./gradlew test` before commit; dashboard tasks: `npm run check && npm test -- --run && npm run build` in `dashboard/`.

---

## File map

| File | Task | Role |
|---|---|---|
| M `packs/msfg-mortgage/prompt.yaml` | 1 | skeleton + hard-rules + guidance |
| M `src/main/java/com/msfg/rag/pack/DomainPack.java`, `DomainPackLoader.java` | 1 | two new fields + validation |
| M `src/test/resources/packs/test-pack/prompt.yaml`, loader tests, `MsfgGoldenPackTest` | 1 | fixtures + golden lock |
| C `src/main/resources/db/migration/V6__create_brain_rule_revisions.sql` | 2 | revisions table |
| C `src/main/java/com/msfg/rag/domain/RuleRevision.java`, `repository/RuleRevisionRepository.java` (+ `@DataJpaTest`) | 2 | entity/repo |
| C `src/main/java/com/msfg/rag/service/ai/RulesService.java` (+ test) | 3 | cached effective rules + revisions |
| M `src/main/java/com/msfg/rag/service/ai/PromptBuilderService.java` (+ tests, golden assembly test) | 4 | 5-placeholder assembly |
| C `src/main/java/com/msfg/rag/controller/AdminRulesController.java` (+ test) | 5 | rules API |
| M `dashboard/src/{App.tsx, types.ts, styles.css}`; C `dashboard/src/screens/Rules.tsx` | 6 | Rules screen |
| — | 7 | live E2E |

Rule keys (the ONLY two): `rules.hard`, `rules.guidance`.

---

### Task 1: Pack restructure — skeleton + two rule blocks

- [ ] **1.1** Rewrite `packs/msfg-mortgage/prompt.yaml` EXACTLY as:
```yaml
template: |
  You are an AI mortgage education assistant for Mountain State Financial Group.

  You must answer ONLY using the approved source context provided below.

  Hard rules — follow these without exception:
  %s

  Guidance — strong recommendations:
  %s

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
hard-rules: |-
  1. Do not answer from general knowledge.
  2. Do not invent mortgage guidelines.
  3. Do not provide loan approval, legal advice, tax advice, or underwriting decisions.
  4. If the source context does not answer the question, say you cannot find enough information.
  5. Include citations from the provided source context. The "citations"
     array is REQUIRED and must contain at least one entry whenever
     source context is provided above. Cite every [Source N] you relied
     on to write the answer. NEVER return an empty "citations" array
     when source context is present — if you used the sources to answer,
     you must list them.
  6. In citations, copy source_name, document_name, section, page_number, and
     effective_date EXACTLY as given in the source context metadata. If a field is
     not present for a source, set it to null. NEVER invent page numbers, section
     names, or dates.
  7. Pay attention to which loan program each source covers (FHA, VA, conventional).
     If the question is about one program, do not answer using a different
     program's guideline. If no source covers the right program, say you cannot
     find enough information.
guidance: |-
  1. Use careful wording such as "may," "generally," and "subject to full loan review."
  2. Keep the answer clear and borrower-friendly.
```
(Every sentence from the old rules 1–9 is preserved verbatim; old 5 and 7 moved to guidance; numbering re-sequenced per block; `|-` strips trailing newline so `%s` substitution controls spacing.)

- [ ] **1.2** `DomainPack`: add components `String hardRules, String guidance` AFTER `promptTemplate` (update the canonical constructor order everywhere; compact constructor untouched — strings). `DomainPackLoader`: `PromptFile(String template, String hardRules, String guidance)` (kebab-case maps `hard-rules`); assembly passes both; validation updates:
  - template now requires exactly FIVE `%s` → `split("%s", -1).length == 6`, message `"template (needs exactly 5 %s placeholders)"`.
  - new requires: `prompt.yaml` `hard-rules` and `guidance` non-blank.
- [ ] **1.3** Update `src/test/resources/packs/test-pack/prompt.yaml`:
```yaml
template: |
  Hard: %s
  Soft: %s
  Context: %s
  Question: %s
  Disclaimer: %s
hard-rules: |-
  H1
guidance: |-
  G1
```
Update `DomainPackLoaderTest`: happy-path asserts `pack.hardRules()=="H1"`, `pack.guidance()=="G1"`, template equals the new 5-slot text; `templateWithoutThreePlaceholdersFailsBoot` renamed/updated to five-placeholder expectation (write `template: only %s here` still fails); add `blankHardRulesFailsBoot` (write prompt.yaml with template but `hard-rules: ""` → PackValidationException mentioning hard-rules).
- [ ] **1.4** `MsfgGoldenPackTest`: replace `promptTemplateIsByteIdenticalToLegacyConstant` with THREE locks — `skeletonIsByteExact` (new template literal), `defaultHardRulesAreByteExact`, `defaultGuidanceIsByteExact` (literals copied from 1.1, as Java text blocks). Every old rule sentence must appear verbatim inside hard/guidance literals (that IS the equivalence review — reviewer checks it).
- [ ] **1.5** TDD order: update tests first (golden + loader) → run → fail → apply pack + model + loader changes → green. NOTE: `PromptBuilderService` still calls `template.formatted(3 args)` and will now THROW at runtime (5 slots) — but nothing constructs prompts in unit tests except `PromptBuilderServiceTest`, which WILL fail. To keep the suite green within this task, apply the MINIMAL bridge in `PromptBuilderService.build`: `template.formatted(pack.hardRules(), pack.guidance(), formatContext(chunks), question, disclaimer)` (pack-only, no service yet — Task 4 swaps in RulesService). Update `PromptBuilderServiceTest.requiresNonEmptyCitationsWhenSourcesProvided` (contains-check still passes — text preserved) and any template-shape assertions. Full suite green → commit:
```bash
git add packs/msfg-mortgage/prompt.yaml src/main/java/com/msfg/rag/pack/ src/main/java/com/msfg/rag/service/ai/PromptBuilderService.java src/test/resources/packs/test-pack/prompt.yaml src/test/java/com/msfg/rag/pack/ src/test/java/com/msfg/rag/service/ai/PromptBuilderServiceTest.java
git commit -m "Split the pack prompt into skeleton plus hard rules and guidance"
```

---

### Task 2: brain_rule_revisions (V6) + entity + repo

- [ ] **2.1** `V6__create_brain_rule_revisions.sql`:
```sql
-- Append-only revisions of the owner-editable rule blocks. The effective
-- text is the newest row per key; NULL content means "use the pack default"
-- (an explicit, attributable revert). Pack defaults are revision zero,
-- implicit and immutable.
CREATE TABLE brain_rule_revisions (
    id          UUID         PRIMARY KEY,
    rule_key    VARCHAR(32)  NOT NULL,
    content     TEXT,
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(100) NOT NULL
);
CREATE INDEX idx_rule_revisions_key_created ON brain_rule_revisions (rule_key, created_at DESC);
```
- [ ] **2.2** `RuleRevision` entity (`@Id @GeneratedValue UUID id`, `ruleKey`, nullable `content` (columnDefinition text), `createdAt` via `@PrePersist`, `createdBy`; ctor `(ruleKey, content, createdBy)`; getters; follow `BrainSetting` style). `RuleRevisionRepository extends JpaRepository<RuleRevision, UUID>` with:
```java
    Optional<RuleRevision> findFirstByRuleKeyOrderByCreatedAtDescIdDesc(String ruleKey);
    List<RuleRevision> findTop20ByRuleKeyOrderByCreatedAtDescIdDesc(String ruleKey);
```
(IdDesc tiebreak for same-timestamp rows.)
- [ ] **2.3** `@DataJpaTest` (mirror `BrainSettingRepositoryTest` scaffolding) — `RuleRevisionRepositoryTest`:
```java
    @Test
    void latestRevisionWinsAndHistoryIsNewestFirst() {
        repository.saveAndFlush(new RuleRevision("rules.hard", "v1", "test"));
        repository.saveAndFlush(new RuleRevision("rules.hard", "v2", "test"));
        repository.saveAndFlush(new RuleRevision("rules.guidance", "g1", "test"));

        assertEquals("v2", repository
                .findFirstByRuleKeyOrderByCreatedAtDescIdDesc("rules.hard").orElseThrow().getContent());
        assertEquals(2, repository.findTop20ByRuleKeyOrderByCreatedAtDescIdDesc("rules.hard").size());
    }

    @Test
    void nullContentRevisionIsAllowedAsRevertMarker() {
        repository.saveAndFlush(new RuleRevision("rules.hard", null, "test"));
        assertNull(repository
                .findFirstByRuleKeyOrderByCreatedAtDescIdDesc("rules.hard").orElseThrow().getContent());
    }
```
- [ ] **2.4** TDD (test → compile fail → implement → green); full suite; commit `git add src/main/resources/db/migration/V6__create_brain_rule_revisions.sql src/main/java/com/msfg/rag/domain/RuleRevision.java src/main/java/com/msfg/rag/repository/RuleRevisionRepository.java src/test/java/com/msfg/rag/repository/RuleRevisionRepositoryTest.java && git commit -m "Append-only rule revisions table (V6)"`.

---

### Task 3: RulesService

- [ ] **3.1** Failing tests `RulesServiceTest` (Mockito; mock repo; `TestPacks.msfg()` for defaults):
  1. `effectiveFallsBackToPackDefaults` — empty repo → `effectiveHard()` equals pack `hardRules()`, `effectiveGuidance()` equals pack `guidance()`; `state()` reports source "pack".
  2. `latestRevisionOverridesPack` — repo returns revision "CUSTOM HARD" for rules.hard → effectiveHard()=="CUSTOM HARD", source "custom".
  3. `nullContentRevisionRevertsToPack` — latest revision content null → effective == pack default, source "pack" (revertedAt info preserved in state).
  4. `saveAppendsAndInvalidates` — save("rules.hard","X","admin-api") → repo.save with matching fields; subsequent effective re-reads repo (verify two findFirst calls around an invalidate, mirroring RuntimeSettingsTest cache test).
  5. `cachesWithinTtl` — two effective calls → one repo read.
  6. `rejectsUnknownKeyAndBlankContent` — save("nope", ...) and save("rules.hard", "  ") → IllegalArgumentException, repo never written.
  7. `revertAppendsNullRevision` — revert("rules.hard","admin-api") → repo.save with null content.
- [ ] **3.2** Implement `RulesService` (`com.msfg.rag.service.ai`, `@Service`): pattern-copy `RuntimeSettings` (volatile cache of the two latest revisions, 10s TTL with the `Long.MIN_VALUE` sentinel guard — copy the FIXED condition from RuntimeSettings, not the plan-doc original), `KEYS = Set.of("rules.hard","rules.guidance")`, content cap 20_000 chars on save (IllegalArgumentException beyond), `state()` returning per-key record `RuleState(String key, String content, String source, java.time.OffsetDateTime updatedAt, String updatedBy)` where content = effective text; history(key) maps Top20; `@Transactional` on save/revert.
- [ ] **3.3** TDD; full suite; commit `git add src/main/java/com/msfg/rag/service/ai/RulesService.java src/test/java/com/msfg/rag/service/ai/RulesServiceTest.java && git commit -m "RulesService: cached effective rules with append-only revisions"`.

---

### Task 4: PromptBuilderService assembly via RulesService

- [ ] **4.1** Failing tests first. `PromptBuilderServiceTest`: construct with `new PromptBuilderService(TestPacks.msfg(), rulesService)` where `rulesService` is a Mockito mock stubbed `effectiveHard() -> TestPacks.msfg().hardRules()`, `effectiveGuidance() -> ...guidance()`; existing assertions stay; ADD `customHardRulesReachThePrompt`: stub effectiveHard() -> "ONLY ANSWER IN HAIKU." → build(...) contains that sentinel. ADD golden assembly test `defaultAssemblyIsByteExact` in `MsfgGoldenPackTest` (or new `PromptAssemblyGoldenTest`): with pack defaults, `build("Q", List.of(oneChunk))` equals a literal expected string (construct the full expected prompt literal — skeleton with default hard/guidance + the formatted context of the one fixture chunk + "Q" + disclaimer; this byte-locks the e2e default prompt).
- [ ] **4.2** Implement: `PromptBuilderService(DomainPack pack, RulesService rules)`; fields template/disclaimer/rules; `build` = `template.formatted(rules.effectiveHard(), rules.effectiveGuidance(), formatContext(chunks), question, disclaimer)`. `AskServiceTest` unaffected (mocks PromptBuilderService).
- [ ] **4.3** TDD; full suite; commit `git add src/main/java/com/msfg/rag/service/ai/PromptBuilderService.java src/test/java/com/msfg/rag/service/ai/PromptBuilderServiceTest.java src/test/java/com/msfg/rag/pack/ && git commit -m "Prompt assembly reads live hard rules and guidance"`.

---

### Task 5: AdminRulesController

Endpoints (admin-gated; CORS already covers `/api/ai/admin/**`):
- `GET /api/ai/admin/rules` → `{hard: RuleState, guidance: RuleState}`
- `PUT /api/ai/admin/rules/{key}` body `{"content": "..."}` → save → fresh GET shape
- `POST /api/ai/admin/rules/{key}/revert` → revert → fresh GET shape
- `GET /api/ai/admin/rules/{key}/history` → list of `{revision: n, createdAt, createdBy, reverted: bool, content}` newest-first (revision number = count - index)
- `GET /api/ai/admin/rules/preview` → `{prompt: "..."}` — `promptBuilderService.build("<your question here>", List.of())` with a placeholder question and empty context (shows the assembled skeleton+rules; context section shows the no-sources placeholder of formatContext).

- [ ] **5.1** Failing unit tests (Mockito on RulesService + PromptBuilderService): get maps both states; put validates key via service (service throws → propagate; controller test verifies pass-through + body unwrap, blank content → IllegalArgumentException BEFORE service); revert calls service; history maps; preview returns build output. 5 tests.
- [ ] **5.2** Implement (plain controller, records for request/response shapes nested in controller). Key path-var validated against the two known keys (IllegalArgumentException otherwise → 400).
- [ ] **5.3** TDD; full suite; commit `git add src/main/java/com/msfg/rag/controller/AdminRulesController.java src/test/java/com/msfg/rag/controller/AdminRulesControllerTest.java && git commit -m "Admin rules API: state, revise, revert, history, prompt preview"`.

---

### Task 6: Dashboard Rules screen

- [ ] **6.1** `dashboard/src/types.ts` — add:
```ts
export interface RuleState {
  key: string; content: string; source: "pack" | "custom";
  updatedAt: string | null; updatedBy: string | null;
}
export interface RulesResponse { hard: RuleState; guidance: RuleState }
export interface RuleRevisionDto {
  revision: number; createdAt: string; createdBy: string;
  reverted: boolean; content: string | null;
}
```
- [ ] **6.2** `dashboard/src/App.tsx`: add `<NavLink to="/rules">Rules</NavLink>` after Settings; route `<Route path="/rules" element={<Rules />} />`; import. `dashboard/src/styles.css`: add `.rulebox { width:100%; min-height:170px; font-family: var(--mono, ui-monospace, monospace); font-size:12.5px; line-height:1.6; padding:10px 12px; border:1px solid var(--border); border-radius:8px; resize: vertical; }` and `.hist-row { display:flex; gap:10px; align-items:center; font-size:12.5px; padding:6px 0; border-bottom:1px solid var(--border); }` (match existing token names used in styles.css — read it first).
- [ ] **6.3** `dashboard/src/screens/Rules.tsx` — complete component (mirror the mockup): loads `RulesResponse`; per tier: textarea bound to draft (initialized from state content, dirty tracking), badges (`must` red-ish/`should` blue + `pack default`/`custom` pill), Save-as-new-revision (PUT, disabled unless dirty), Revert-to-pack (POST revert, only when source==custom), history toggle per tier (fetch on open; each row shows rev/time/author + Restore button = PUT with that revision's content; reverted rows labeled "revert to pack"), and a Preview button fetching `/preview` into a `<pre>` block in a card. Errors via ErrorNote; "live within ~10 s" note in header. Write the full component in the implementer's own structure — the contracts above + the mockup are binding, the JSX composition is theirs (this is the one screen the plan doesn't dictate line-by-line; the reviewer holds it to the mockup + contracts).
- [ ] **6.4** Gates (`npm run check`, `npm test -- --run`, `npm run build`); commit `git add dashboard/src && git commit -m "Rules screen: two-tier editor with revisions, revert, and prompt preview"`.

---

### Task 7: Live E2E

- [ ] **7.1** `./gradlew cleanTest test` → green (report totals; expect ~225+). Dashboard gates green.
- [ ] **7.2** Boot :8090 (never 8080; source .env; log → /tmp/p45_boot.log; confirm V6 applied).
- [ ] **7.3** With key: GET rules → both source "pack", content equals pack defaults. GET preview → contains "Hard rules — follow these without exception:" and the default rule 1 text.
- [ ] **7.4** PUT `rules.hard` with the pack default text PLUS sentinel line `99. E2E SENTINEL — refer all pet insurance questions to a human.` → 200 source "custom". GET preview → contains the sentinel. Wait 11 s → POST an ask ("What is PMI?") → still grounded+cited (sanity that a rules edit doesn't break the pipeline).
- [ ] **7.5** GET history → 1 revision, createdBy admin-api. POST revert → source back to "pack"; preview no longer contains sentinel; history now 2 entries (newest marked reverted).
- [ ] **7.6** No-key on all rules endpoints → 401. Kill app; ports freed; `git status --short` clean of source changes.
- [ ] **7.7** Report + VERDICT; human note: re-run the dashboard and try the Rules screen end-to-end (edit → preview → revert).

---

## Plan self-review (done at write time)

- **Approved-design coverage:** two tiers seeded from today's rules (T1 split preserves every sentence verbatim — equivalence is reviewer-checked); easy editing + revisions + revert + history (T2–T5); live ~10 s (RulesService cache mirrors RuntimeSettings incl. the FIXED sentinel condition); locked skeleton (JSON contract/context/citations/disclaimer placement not editable — only rule text flows through the two placeholders); Rules screen per mockup incl. preview (T5 preview + T6); posture control documented in the conversation maps to hard/guidance text — no code gate removed (classifier/validator/retrieval untouched).
- **Deliberate prompt change** is declared up front, byte-locked at the new baseline (golden pack tests + assembly golden test), and E2E-smoked.
- **Placeholders:** Task 6.3 deliberately specifies contracts + mockup rather than line-by-line JSX (stated explicitly); everything else carries complete code or exact text.
- **Type consistency:** DomainPack gains `hardRules()/guidance()` used in T1 bridge, T3 defaults, T4 assembly; `RuleState` shape identical in T3 service record, T5 GET, T6 types.ts; revision DTO field names consistent (`revision/createdAt/createdBy/reverted/content`); 5-placeholder order (hard, guidance, context, question, disclaimer) identical in pack template, loader validation message, T4 formatted call, and the golden assembly literal.
