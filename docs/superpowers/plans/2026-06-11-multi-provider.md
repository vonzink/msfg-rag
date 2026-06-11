# Phase 4.6 — Multi-Provider Expansion (DeepSeek now; Gemini & Grok dormant) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three new selectable AI providers — `deepseek`, `gemini`, `grok` — each a thin OpenAI-compatible adapter that registers ONLY when its API key is present in the environment. The dashboard Settings screen shows every known provider with a configured/no-key status chip and offers only configured ones in the model dropdowns. Adding a future provider key = edit `.env`, restart, it appears. No router, settings, or validation changes — the registry was built for this.

**Architecture:** One generic `OpenAiCompatibleProvider` (name + base URL + key + default model; mirrors `OpenAiProvider`'s Spring AI usage with a hand-built `OpenAiApi`/`OpenAiChatModel` pointed at the vendor's OpenAI-compatible endpoint). An `ExtraProvidersConfig` declares three conditional beans via `@ConditionalOnExpression(hasText(...))` on the key property. `GET /api/ai/admin/settings` gains a `providers` block (`known[]` with name+configured; dropdowns use configured only). Settings screen reads it. `.env.example` + RUNBOOK document the three key slots.

**Endpoints (OpenAI-compatible dialects):** DeepSeek `https://api.deepseek.com` (default model `deepseek-chat`); Grok/xAI `https://api.x.ai/v1` (default `grok-3`); Gemini `https://generativelanguage.googleapis.com/v1beta/openai` (default `gemini-2.5-flash`). Default models are env-overridable (`*_MODEL`); base URLs env-overridable too. HONESTY CLAUSE: Gemini/Grok adapters ship wired-but-unverified until a real key exists (documented in RUNBOOK); DeepSeek is live-verified in E2E only if `DEEPSEEK_API_KEY` is present in `.env` at run time — otherwise its dormant state is verified like the others. Implementer for Task 1 should verify current default model names with a quick web search and adjust the constants if the vendors have moved on (report what was used).

**Tech Stack:** Java 21 / Spring Boot 3.5 / Spring AI 1.1.7 (existing `spring-ai-starter-model-openai` provides `OpenAiApi`/`OpenAiChatModel` — no new dependencies). React dashboard.

**Prerequisites:** Branch `feat/multi-provider` (off main `8b18ee9`+RUNBOOK). Suite green (235 Java; dashboard gates).

**Safety rules for every worker:** FIRST command `git branch --show-current` must print `feat/multi-provider` — else STOP/BLOCKED. Never checkout/reset/rebase. Reviewers read-only. Don't touch `scripts/`. NEVER print key values; only presence checks. Java: full `./gradlew test` before commit; dashboard: `npm run check && npm test -- --run && npm run build`.

---

## File map

| File | Task | Role |
|---|---|---|
| C `src/main/java/com/msfg/rag/provider/OpenAiCompatibleProvider.java` | 1 | generic adapter |
| C `src/main/java/com/msfg/rag/config/ExtraProvidersConfig.java` | 1 | 3 conditional beans |
| M `src/main/resources/application.yml` | 1 | `brain.providers.*` properties |
| C `src/test/java/com/msfg/rag/config/ExtraProvidersConfigTest.java` | 1 | conditional registration tests |
| M `AdminSettingsController` (+ test) | 2 | `providers` block in GET |
| M `dashboard/src/{types.ts, screens/Settings.tsx}` | 3 | dynamic dropdowns + status chips |
| M `.env.example`, `docs/RUNBOOK.md` | 4 | key slots + activation doc |
| — | 5 | E2E (dormant + live-if-key) |

Known-provider catalog (single source of truth, Task 2): `anthropic`, `openai`, `deepseek`, `gemini`, `grok`.

---

### Task 1: Generic adapter + conditional registration

- [ ] **1.1** `application.yml` — under the existing `brain:` block add:
```yaml
  providers:
    deepseek:
      api-key: ${DEEPSEEK_API_KEY:}
      base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com}
      model: ${DEEPSEEK_MODEL:deepseek-chat}
    gemini:
      api-key: ${GEMINI_API_KEY:}
      base-url: ${GEMINI_BASE_URL:https://generativelanguage.googleapis.com/v1beta/openai}
      model: ${GEMINI_MODEL:gemini-2.5-flash}
    grok:
      api-key: ${GROK_API_KEY:}
      base-url: ${GROK_BASE_URL:https://api.x.ai/v1}
      model: ${GROK_MODEL:grok-3}
```
(Default model names: verify current vendor names via web search before committing; adjust here AND in 4.1's `.env.example` comments if needed — report final values.)

- [ ] **1.2** READ `src/main/java/com/msfg/rag/provider/OpenAiProvider.java` first — the new adapter mirrors its generate/usage-extraction shape exactly. Then create `OpenAiCompatibleProvider.java`:
```java
package com.msfg.rag.provider;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * Adapter for any provider that speaks the OpenAI chat-completions dialect
 * (DeepSeek, Grok/xAI, Gemini's compatibility endpoint). One instance per
 * provider, registered by ExtraProvidersConfig only when its API key is
 * configured — an unconfigured provider simply doesn't exist in the registry,
 * so it can't be selected from the dashboard.
 */
public class OpenAiCompatibleProvider implements AiModelProvider {

    private final String providerName;
    private final String defaultModel;
    private final OpenAiChatModel chatModel;

    public OpenAiCompatibleProvider(String providerName, String baseUrl,
                                    String apiKey, String defaultModel) {
        this.providerName = providerName;
        this.defaultModel = defaultModel;
        this.chatModel = OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build())
                .build();
    }

    @Override
    public AiResponse generate(AiRequest request) {
        String model = request.model() != null ? request.model() : defaultModel;
        Prompt prompt = new Prompt(request.prompt(), OpenAiChatOptions.builder()
                .model(model)
                .temperature(request.temperature())
                .maxTokens(request.maxTokens())
                .build());

        ChatResponse response = chatModel.call(prompt);

        Integer promptTokens = null;
        Integer completionTokens = null;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            promptTokens = response.getMetadata().getUsage().getPromptTokens();
            completionTokens = response.getMetadata().getUsage().getCompletionTokens();
        }

        return new AiResponse(response.getResult().getOutput().getText(),
                providerName, model, promptTokens, completionTokens);
    }

    @Override
    public String getProviderName() {
        return providerName;
    }

    @Override
    public String getModelName() {
        return defaultModel;
    }
}
```
COMPILE-REALITY NOTE: the `OpenAiChatModel.builder()/OpenAiApi.builder()` surface is Spring AI 1.1.7 — if the builder names differ, mirror however the version actually constructs them (check the classes in the Gradle cache / use IDE-less javap if needed); the CONTRACT (one ChatModel per instance against a custom base URL + key) is what's binding. Report the exact construction used.

- [ ] **1.3** `ExtraProvidersConfig.java`:
```java
package com.msfg.rag.config;

import com.msfg.rag.provider.OpenAiCompatibleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optional OpenAI-dialect providers. Each bean exists only when its API key
 * is configured — drop a key into .env, restart, and the provider appears in
 * the dashboard. No key, no bean, not selectable.
 */
@Configuration
public class ExtraProvidersConfig {

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${brain.providers.deepseek.api-key:}')")
    public OpenAiCompatibleProvider deepSeekProvider(
            @Value("${brain.providers.deepseek.base-url}") String baseUrl,
            @Value("${brain.providers.deepseek.api-key}") String apiKey,
            @Value("${brain.providers.deepseek.model}") String model) {
        return new OpenAiCompatibleProvider("deepseek", baseUrl, apiKey, model);
    }

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${brain.providers.gemini.api-key:}')")
    public OpenAiCompatibleProvider geminiProvider(
            @Value("${brain.providers.gemini.base-url}") String baseUrl,
            @Value("${brain.providers.gemini.api-key}") String apiKey,
            @Value("${brain.providers.gemini.model}") String model) {
        return new OpenAiCompatibleProvider("gemini", baseUrl, apiKey, model);
    }

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${brain.providers.grok.api-key:}')")
    public OpenAiCompatibleProvider grokProvider(
            @Value("${brain.providers.grok.base-url}") String baseUrl,
            @Value("${brain.providers.grok.api-key}") String apiKey,
            @Value("${brain.providers.grok.model}") String model) {
        return new OpenAiCompatibleProvider("grok", baseUrl, apiKey, model);
    }
}
```

- [ ] **1.4** TDD with `ApplicationContextRunner` — `ExtraProvidersConfigTest` (plain JUnit, no Spring Boot test slice):
```java
package com.msfg.rag.config;

import com.msfg.rag.provider.OpenAiCompatibleProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtraProvidersConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ExtraProvidersConfig.class)
            .withPropertyValues(
                    "brain.providers.deepseek.base-url=https://api.deepseek.com",
                    "brain.providers.deepseek.model=deepseek-chat",
                    "brain.providers.gemini.base-url=https://example.test",
                    "brain.providers.gemini.model=gemini-x",
                    "brain.providers.grok.base-url=https://example.test",
                    "brain.providers.grok.model=grok-x");

    @Test
    void noKeysMeansNoExtraProviders() {
        runner.withPropertyValues(
                        "brain.providers.deepseek.api-key=",
                        "brain.providers.gemini.api-key=",
                        "brain.providers.grok.api-key=")
                .run(context -> assertEquals(0,
                        context.getBeansOfType(OpenAiCompatibleProvider.class).size()));
    }

    @Test
    void aKeyActivatesExactlyThatProvider() {
        runner.withPropertyValues(
                        "brain.providers.deepseek.api-key=sk-test",
                        "brain.providers.gemini.api-key=",
                        "brain.providers.grok.api-key=")
                .run(context -> {
                    var beans = context.getBeansOfType(OpenAiCompatibleProvider.class);
                    assertEquals(1, beans.size());
                    assertEquals("deepseek",
                            beans.values().iterator().next().getProviderName());
                });
    }

    @Test
    void modelOverrideFallsBackToInstanceDefault() {
        OpenAiCompatibleProvider provider =
                new OpenAiCompatibleProvider("deepseek", "https://api.deepseek.com", "sk-x", "deepseek-chat");
        assertEquals("deepseek", provider.getProviderName());
        assertEquals("deepseek-chat", provider.getModelName());
    }
}
```
Run → compile failure → implement 1.1–1.3 → green. (Bean construction builds an HTTP client but makes NO network call — same as the other providers; the context-runner tests prove it.)
- [ ] **1.5** Full suite green (boot contexts in existing integration tests see empty keys → no behavior change). Commit:
```bash
git add src/main/java/com/msfg/rag/provider/OpenAiCompatibleProvider.java src/main/java/com/msfg/rag/config/ExtraProvidersConfig.java src/main/resources/application.yml src/test/java/com/msfg/rag/config/ExtraProvidersConfigTest.java
git commit -m "Optional OpenAI-dialect providers: deepseek, gemini, grok (key-gated)"
```

---

### Task 2: Providers block in the settings API

- [ ] **2.1** Failing test additions to `AdminSettingsControllerTest`:
```java
    @Test
    void getListsKnownProvidersWithConfiguredFlags() {
        when(router.providerNames()).thenReturn(Set.of("anthropic", "openai", "deepseek"));
        stubAllSettings(); // reuse/extract the existing GET stubbing into a helper

        Map<String, Object> body = controller.get();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> known = (List<Map<String, Object>>) body.get("providers");
        assertEquals(List.of("anthropic", "openai", "deepseek", "gemini", "grok"),
                known.stream().map(p -> p.get("name")).toList());
        assertEquals(true, known.get(2).get("configured"));   // deepseek
        assertEquals(false, known.get(3).get("configured"));  // gemini
    }
```
(Extract the existing GET test's `when(settings...)` stubs into a private `stubAllSettings()` helper used by both tests.)
- [ ] **2.2** Implement in `AdminSettingsController`: constant `KNOWN_PROVIDERS = List.of("anthropic", "openai", "deepseek", "gemini", "grok")`; `get()` adds `"providers"` → list of `Map.of("name", n, "configured", router.providerNames().contains(n))` in catalog order. (Return shape: top-level keys now `effective`, `overrides`, `providers` — use a LinkedHashMap instead of Map.of for the envelope.)
- [ ] **2.3** Targeted green → full suite → commit `git add src/main/java/com/msfg/rag/controller/AdminSettingsController.java src/test/java/com/msfg/rag/controller/AdminSettingsControllerTest.java && git commit -m "Settings API lists known providers with configured flags"`.

---

### Task 3: Dynamic provider dropdowns + status chips

- [ ] **3.1** `dashboard/src/types.ts`: extend `SettingsResponse` with `providers: { name: string; configured: boolean }[]`.
- [ ] **3.2** `dashboard/src/screens/Settings.tsx`:
  - Delete the hardcoded `const PROVIDERS = ["anthropic", "openai"]`.
  - Provider `<select>` options come from `data.providers.filter(p => p.configured).map(p => p.name)`.
  - Add a status strip under the header (above the card): for each `data.providers` entry render a Pill — `green` "name ✓" when configured, `gray` "name — no key" when not — with a muted note: "To activate a provider, add its API key to .env and restart the brain (see RUNBOOK)."
  - Guard: if the currently-effective provider value is somehow not in the configured list (key removed after an override was saved), still render it as an extra option so the form reflects reality.
- [ ] **3.3** Gates → commit `git add dashboard/src/types.ts dashboard/src/screens/Settings.tsx && git commit -m "Settings screen: dynamic providers with configured-status chips"`.

---

### Task 4: Documentation slots

- [ ] **4.1** `.env.example` — after the existing model-routing block add (match final model names from Task 1):
```
# --- Optional extra AI providers (leave blank = provider hidden) ---
# Add a key and restart the brain; the provider then appears in the dashboard.
DEEPSEEK_API_KEY=
# DEEPSEEK_MODEL=deepseek-chat
GEMINI_API_KEY=
# GEMINI_MODEL=gemini-2.5-flash
GROK_API_KEY=
# GROK_MODEL=grok-3
```
- [ ] **4.2** `docs/RUNBOOK.md` — add a short "Adding an AI provider" section: edit `.env` (`open -e ~/MSFG/msfg-rag/.env`), paste the key after the matching `..._API_KEY=`, save, restart the brain (Ctrl+C + start command), then pick the provider on the Settings screen. Note: Gemini and Grok adapters are wired but unverified until first real key — test on the UTILITY lane (reranker) before trusting them with customer answers, and remember the data-handling consideration when routing borrower questions to any new vendor.
- [ ] **4.3** Commit `git add .env.example docs/RUNBOOK.md && git commit -m "Document the optional provider key slots"`.

---

### Task 5: E2E

- [ ] **5.1** `./gradlew cleanTest test` green (report totals; expect ~239); dashboard gates green.
- [ ] **5.2** Check key presence WITHOUT printing values: `grep -c '^DEEPSEEK_API_KEY=..*' /Users/zacharyzink/MSFG/msfg-rag/.env` (1 = present). Record which of the three keys exist.
- [ ] **5.3** Boot :8090 (kill any prior 8090 first; NEVER 8080). With admin key: `GET /api/ai/admin/settings` → `providers` lists all five in order; configured flags match 5.2 (anthropic/openai true; others per key presence).
- [ ] **5.4** DORMANT CHECK (for each absent key): `PUT {"utility.provider":"gemini"}` → **400** "Unknown provider" (registry-driven validation working).
- [ ] **5.5** LIVE CHECK (only if DEEPSEEK key present): `PUT {"utility.provider":"deepseek"}` → 200; wait 11 s; ask "What is PMI?" → coherent grounded answer (reranker now running on DeepSeek; the audit row's model for the ANSWER stays Claude). Then check `GET /api/ai/admin/audit?size=1`… the reranker's model isn't audited per-row — instead verify via a second settings GET that the override took, and that the ask succeeded. REVERT: `PUT {"utility.provider":""}` (clears override). If the live call FAILS (bad key/model name), report verbatim error and revert — that's a finding, not a blocker for the dormant architecture.
- [ ] **5.6** Kill app; ports freed; `git status --short` clean (scripts/ only). Report + VERDICT.

---

## Plan self-review (done at write time)

- **User ask coverage:** DeepSeek selectable now (key-gated) — T1; Gemini/Grok "spots" = wired dormant adapters + visible no-key chips + documented activation — T1/T3/T4; keys stay in `.env` only (no dashboard key editing, per the agreed security posture) — T4 documents the flow; provider status visibility — T2/T3.
- **No regressions by construction:** with all three keys absent, zero new beans exist; router/settings/validation are untouched code paths reading the same registry; existing tests prove the empty-key world.
- **Placeholders:** Task 1's two REALITY NOTES (builder surface, current model names) name their verification method and require reporting — deliberate research hooks, not TBDs. Task 3.2 is contract-level for JSX (established pattern from 4.5-T6).
- **Type consistency:** `providers` payload shape identical in T2 test/controller and T3 types.ts; catalog order fixed in one constant; provider names lowercase everywhere (matches getProviderName values).
