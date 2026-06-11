# Phase ④ — Dashboard v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The internal ops console approved in the design mockup: four screens (Corpus, Settings, Test console, Audit) as a React + Vite + TypeScript app in `dashboard/`, talking to the existing admin API with an admin-key gate — plus the two backend pieces the mockup implies: a read-only audit API and a brain/corpus stats endpoint, with CORS opened for the admin surfaces.

**Design lock:** the four-screen mockup approved in conversation on 2026-06-10 is the visual contract — sidebar shell branded from the domain pack, corpus screen leading with the sync diff, two-lane model settings with override badges, test console with full-ask and retrieval-only modes, audit table with expandable detail rows.

**Architecture:** Backend first (Tasks 1–2, strict TDD): `AdminAuditController` (list + detail over `ai_audit_logs`, filtered/paged via one JPQL query), `AdminStatsController` (brain identity from `DomainPack` + corpus counts), CORS mappings for `/api/ai/admin/**` and `/api/ai/documents/**`. Frontend (Tasks 3–7): hand-rolled CSS mirroring the mockup (no UI framework), plain hooks + a small `api.ts` (key in sessionStorage, header injection, 401 → gate), react-router with four routes. Vitest covers the api client logic; screen markup is gated by `tsc` + build + the live walkthrough (Task 8). Dev mode proxies `/api` to :8090 (no CORS needed locally); production uses the new CORS mappings.

**Tech Stack:** Java 21 / Spring Boot 3.5 (backend additions) · React 18 + Vite 5 + TypeScript 5 + react-router 6 + Vitest 2 (`dashboard/`). Node v24 / npm 11 confirmed on this machine. Spec: platform design §8.

**Prerequisites:** Branch `feat/phase4-dashboard` (off main `05c6437`). Java suite green (206 tests).

**Safety rules for every worker:** FIRST command `git branch --show-current` must print `feat/phase4-dashboard` — else STOP/BLOCKED. Never `git checkout <sha>` / `git reset` / `git rebase`. Reviewers read-only. Don't touch `scripts/`. Java tasks: full `./gradlew test` before commit. Frontend tasks: `npm run check && npm test -- --run && npm run build` (in `dashboard/`) before commit.

---

## File map

| File | Task | Role |
|---|---|---|
| C `src/main/java/com/msfg/rag/dto/AuditLogListDto.java`, `AuditLogDetailDto.java`, `AuditPageDto.java` | 1 | audit read DTOs |
| M `src/main/java/com/msfg/rag/repository/AuditLogRepository.java` | 1 | filtered search query |
| C `src/main/java/com/msfg/rag/controller/AdminAuditController.java` (+ unit test, + repo `@DataJpaTest`) | 1 | GET list/detail |
| C `src/main/java/com/msfg/rag/controller/AdminStatsController.java` (+ test) | 2 | brain + corpus stats |
| M `src/main/java/com/msfg/rag/repository/MortgageDocumentRepository.java` | 2 | `countByActiveTrue()` |
| M `src/main/java/com/msfg/rag/config/CorsConfig.java` (+ M `CorsConfigTest`) | 2 | admin/documents CORS |
| C `dashboard/package.json`, `tsconfig.json`, `vite.config.ts`, `index.html` | 3 | scaffold |
| C `dashboard/src/{main.tsx, styles.css, api.ts, types.ts, components.tsx, App.tsx}` (+ `api.test.ts`) | 3 | plumbing, gate, shell |
| C `dashboard/src/screens/Corpus.tsx` | 4 | corpus screen |
| C `dashboard/src/screens/Settings.tsx` | 5 | settings screen |
| C `dashboard/src/screens/TestConsole.tsx` | 6 | test console |
| C `dashboard/src/screens/Audit.tsx` | 7 | audit screen |
| — | 8 | E2E verification |

---

### Task 1: Audit read API (backend, strict TDD)

**Endpoints:** `GET /api/ai/admin/audit?page=0&size=20&escalatedOnly=false&q=` → newest-first page; `GET /api/ai/admin/audit/{id}` → detail with answer + retrieved sources. Both inside the existing admin gate (`/api/ai/admin` prefix already filtered). List DTO deliberately EXCLUDES `finalPrompt`/`finalAnswer`/`retrievedContext` (heavy); detail includes answer + sources but never the prompt (prompt stays DB-only).

- [ ] **1.1** DTOs:

`src/main/java/com/msfg/rag/dto/AuditLogListDto.java`:
```java
package com.msfg.rag.dto;

import com.msfg.rag.domain.AuditLog;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One audit row in the dashboard table — intentionally light. */
public record AuditLogListDto(
        UUID id,
        OffsetDateTime createdAt,
        String question,
        Double confidence,
        String modelProvider,
        String modelName,
        boolean fallbackUsed,
        boolean escalated
) {
    public static AuditLogListDto from(AuditLog log) {
        return new AuditLogListDto(log.getId(), log.getCreatedAt(), log.getUserQuestion(),
                log.getConfidenceScore(), log.getModelProvider(), log.getModelName(),
                log.isFallbackUsed(), log.isHumanEscalationRequired());
    }
}
```

`src/main/java/com/msfg/rag/dto/AuditLogDetailDto.java`:
```java
package com.msfg.rag.dto;

import com.msfg.rag.domain.AuditLog;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Expanded audit row: adds the final answer and the retrieved sources. */
public record AuditLogDetailDto(
        UUID id,
        OffsetDateTime createdAt,
        String question,
        String rewrittenQuestion,
        String answer,
        Double confidence,
        String modelProvider,
        String modelName,
        boolean fallbackUsed,
        boolean escalated,
        List<Map<String, Object>> sources
) {
    public static AuditLogDetailDto from(AuditLog log) {
        return new AuditLogDetailDto(log.getId(), log.getCreatedAt(), log.getUserQuestion(),
                log.getRewrittenQuestion(), log.getFinalAnswer(), log.getConfidenceScore(),
                log.getModelProvider(), log.getModelName(), log.isFallbackUsed(),
                log.isHumanEscalationRequired(),
                log.getRetrievedContext() == null ? List.of() : log.getRetrievedContext());
    }
}
```

`src/main/java/com/msfg/rag/dto/AuditPageDto.java`:
```java
package com.msfg.rag.dto;

import java.util.List;

/** Stable page envelope (Spring's Page serialization is not a public contract). */
public record AuditPageDto(List<AuditLogListDto> items, int page, int size, long total) {
}
```

- [ ] **1.2** Repository query — add to `AuditLogRepository`:
```java
    /**
     * Dashboard search: newest first, optionally escalated-only, optionally
     * question-substring (case-insensitive). Null/blank q means no filter.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT a FROM AuditLog a
            WHERE (:escalatedOnly = false OR a.humanEscalationRequired = true)
              AND (:q IS NULL OR LOWER(a.userQuestion) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY a.createdAt DESC
            """)
    Page<AuditLog> search(@org.springframework.data.repository.query.Param("escalatedOnly") boolean escalatedOnly,
                          @org.springframework.data.repository.query.Param("q") String q,
                          Pageable pageable);
```
(convert the fully-qualified annotations to imports.)

- [ ] **1.3** TDD the repository query FIRST (it's the risky part — JPQL null-param semantics on Postgres). Create `src/test/java/com/msfg/rag/repository/AuditLogRepositoryTest.java` mirroring `BrainSettingRepositoryTest`'s Testcontainers scaffolding exactly; body:
```java
    @Autowired
    AuditLogRepository repository;

    private AuditLog log(String question, boolean escalated) {
        AuditLog entry = new AuditLog();
        entry.setUserQuestion(question);
        entry.setFallbackUsed(false);
        entry.setHumanEscalationRequired(escalated);
        return entry;
    }

    @Test
    void searchFiltersEscalationAndQuestionSubstring() {
        repository.save(log("What is PMI?", false));
        repository.save(log("Will I be approved?", true));
        repository.save(log("What is an FHA loan?", false));

        assertEquals(3, repository.search(false, null, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, repository.search(true, null, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(2, repository.search(false, "what is", PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, repository.search(false, "fha", PageRequest.of(0, 10)).getTotalElements());
        assertEquals(0, repository.search(true, "pmi", PageRequest.of(0, 10)).getTotalElements());
    }
```
(imports: `org.springframework.data.domain.PageRequest`, JUnit asserts.) Run targeted → compile failure → implement 1.2 → green. If Hibernate rejects `:q IS NULL` typing, the sanctioned fallback is two query methods (with/without q) selected in the controller — report which path was needed.

- [ ] **1.4** Controller TDD. `src/test/java/com/msfg/rag/controller/AdminAuditControllerTest.java` (plain Mockito):
```java
package com.msfg.rag.controller;

import com.msfg.rag.domain.AuditLog;
import com.msfg.rag.dto.AuditPageDto;
import com.msfg.rag.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuditControllerTest {

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final AdminAuditController controller = new AdminAuditController(repository);

    private AuditLog entry(String question) {
        AuditLog log = new AuditLog();
        log.setUserQuestion(question);
        return log;
    }

    @Test
    void listMapsPageAndClampsSize() {
        when(repository.search(eq(false), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of(entry("What is PMI?")),
                        PageRequest.of(0, 20), 1));

        AuditPageDto page = controller.list(0, 500, false, null);

        assertEquals(1, page.items().size());
        assertEquals("What is PMI?", page.items().get(0).question());
        assertEquals(1, page.total());
        verify(repository).search(eq(false), eq(null),
                eq(PageRequest.of(0, 100)));  // size clamped to 100
    }

    @Test
    void blankQueryBecomesNull() {
        when(repository.search(eq(true), eq(null), any()))
                .thenReturn(new PageImpl<>(List.of()));
        controller.list(0, 20, true, "   ");
        verify(repository).search(eq(true), eq(null), eq(PageRequest.of(0, 20)));
    }

    @Test
    void detailThrowsOnUnknownId() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> controller.detail(id));
    }
}
```
Run → compile failure. Implement `AdminAuditController`:
```java
package com.msfg.rag.controller;

import com.msfg.rag.domain.AuditLog;
import com.msfg.rag.dto.AuditLogDetailDto;
import com.msfg.rag.dto.AuditLogListDto;
import com.msfg.rag.dto.AuditPageDto;
import com.msfg.rag.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only audit trail for the dashboard. List rows are light; the detail
 * view adds the answer and retrieved sources. The final prompt is never
 * exposed over HTTP — it stays in the database for offline review.
 */
@RestController
@RequestMapping("/api/ai/admin/audit")
public class AdminAuditController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditLogRepository repository;

    public AdminAuditController(AuditLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public AuditPageDto list(@RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size,
                             @RequestParam(defaultValue = "false") boolean escalatedOnly,
                             @RequestParam(required = false) String q) {
        int clampedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        String query = q == null || q.isBlank() ? null : q.strip();
        Page<AuditLog> result = repository.search(escalatedOnly, query,
                PageRequest.of(Math.max(0, page), clampedSize));
        return new AuditPageDto(
                result.getContent().stream().map(AuditLogListDto::from).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @GetMapping("/{id}")
    public AuditLogDetailDto detail(@PathVariable UUID id) {
        return repository.findById(id).map(AuditLogDetailDto::from)
                .orElseThrow(() -> new IllegalArgumentException("Audit entry not found: " + id));
    }
}
```
- [ ] **1.5** Both test classes green → full suite green → commit:
```bash
git add src/main/java/com/msfg/rag/dto/AuditLogListDto.java src/main/java/com/msfg/rag/dto/AuditLogDetailDto.java src/main/java/com/msfg/rag/dto/AuditPageDto.java src/main/java/com/msfg/rag/repository/AuditLogRepository.java src/main/java/com/msfg/rag/controller/AdminAuditController.java src/test/java/com/msfg/rag/repository/AuditLogRepositoryTest.java src/test/java/com/msfg/rag/controller/AdminAuditControllerTest.java
git commit -m "Read-only audit API for the dashboard"
```

---

### Task 2: Stats endpoint + admin CORS (backend, strict TDD)

- [ ] **2.1** Add `long countByActiveTrue();` to `MortgageDocumentRepository`.
- [ ] **2.2** Controller TDD — `src/test/java/com/msfg/rag/controller/AdminStatsControllerTest.java`:
```java
package com.msfg.rag.controller;

import com.msfg.rag.pack.TestPacks;
import com.msfg.rag.repository.DocumentChunkRepository;
import com.msfg.rag.repository.MortgageDocumentRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminStatsControllerTest {

    @Test
    void statsCarryBrainIdentityAndCorpusCounts() {
        MortgageDocumentRepository docs = mock(MortgageDocumentRepository.class);
        DocumentChunkRepository chunks = mock(DocumentChunkRepository.class);
        when(docs.count()).thenReturn(13L);
        when(docs.countByActiveTrue()).thenReturn(9L);
        when(chunks.count()).thenReturn(1990L);

        AdminStatsController controller =
                new AdminStatsController(TestPacks.msfg(), docs, chunks);
        AdminStatsController.StatsDto stats = controller.stats();

        assertEquals("Mountain State Financial Group", stats.brain().companyName());
        assertEquals("mortgage", stats.brain().slug());
        assertEquals(9L, stats.corpus().activeDocuments());
        assertEquals(13L, stats.corpus().totalDocuments());
        assertEquals(1990L, stats.corpus().chunks());
    }
}
```
Run → compile failure. Implement `AdminStatsController`:
```java
package com.msfg.rag.controller;

import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.repository.DocumentChunkRepository;
import com.msfg.rag.repository.MortgageDocumentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Brain identity + corpus counts for the dashboard shell and corpus screen. */
@RestController
@RequestMapping("/api/ai/admin/stats")
public class AdminStatsController {

    public record BrainDto(String companyName, String slug) {}
    public record CorpusDto(long activeDocuments, long totalDocuments, long chunks) {}
    public record StatsDto(BrainDto brain, CorpusDto corpus) {}

    private final DomainPack pack;
    private final MortgageDocumentRepository documents;
    private final DocumentChunkRepository chunks;

    public AdminStatsController(DomainPack pack,
                                MortgageDocumentRepository documents,
                                DocumentChunkRepository chunks) {
        this.pack = pack;
        this.documents = documents;
        this.chunks = chunks;
    }

    @GetMapping
    public StatsDto stats() {
        return new StatsDto(
                new BrainDto(pack.companyName(), pack.slug()),
                new CorpusDto(documents.countByActiveTrue(), documents.count(), chunks.count()));
    }
}
```
- [ ] **2.3** CORS TDD — add to `CorsConfigTest`:
```java
    @Test
    void exposesAdminSurfacesForTheDashboard() {
        Map<String, CorsConfiguration> mappings = register("mortgage");

        assertTrue(mappings.containsKey("/api/ai/admin/**"),
                "dashboard origins must reach the admin API");
        assertTrue(mappings.containsKey("/api/ai/documents/**"),
                "dashboard origins must reach the documents API");
        assertTrue(mappings.get("/api/ai/admin/**").getAllowedHeaders()
                .contains("X-Admin-Api-Key"), "the admin key header must be allowed");
    }
```
Run → fails. Implement in `CorsConfig.addCorsMappings` (after the existing two mappings; update the class javadoc's "admin endpoints are not" sentence to say admin surfaces are exposed for the dashboard and remain key-gated):
```java
        registry.addMapping("/api/ai/admin/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "PUT", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "X-Admin-Api-Key")
                .maxAge(3600);

        registry.addMapping("/api/ai/documents/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type", "X-Admin-Api-Key")
                .maxAge(3600);
```
- [ ] **2.4** Green → full suite → commit:
```bash
git add src/main/java/com/msfg/rag/controller/AdminStatsController.java src/test/java/com/msfg/rag/controller/AdminStatsControllerTest.java src/main/java/com/msfg/rag/repository/MortgageDocumentRepository.java src/main/java/com/msfg/rag/config/CorsConfig.java src/test/java/com/msfg/rag/config/CorsConfigTest.java
git commit -m "Stats endpoint and admin CORS for the dashboard"
```

---

### Task 3: Frontend scaffold, api client, gate, shell

All files below are CREATED exactly as given. After creating them: `cd dashboard && npm install` (writes `package-lock.json` — commit it too).

- [ ] **3.1** `dashboard/package.json`:
```json
{
  "name": "rag-brain-dashboard",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "check": "tsc --noEmit",
    "test": "vitest",
    "preview": "vite preview"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "react-router-dom": "^6.30.0"
  },
  "devDependencies": {
    "@types/react": "^18.3.12",
    "@types/react-dom": "^18.3.1",
    "@vitejs/plugin-react": "^4.3.4",
    "typescript": "~5.6.3",
    "vite": "^5.4.11",
    "vitest": "^2.1.8"
  }
}
```
(If npm cannot resolve a pinned minor, bump to the nearest available and report versions used.)

- [ ] **3.2** `dashboard/tsconfig.json`:
```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "skipLibCheck": true,
    "types": ["vite/client"]
  },
  "include": ["src"]
}
```

- [ ] **3.3** `dashboard/vite.config.ts`:
```ts
/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { "/api": "http://localhost:8090" },
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
```

- [ ] **3.4** `dashboard/index.html`:
```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>RAG brain dashboard</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **3.5** TDD the api client. `dashboard/src/api.test.ts` FIRST:
```ts
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, adminKey, AuthError } from "./api";

const store = new Map<string, string>();

beforeEach(() => {
  vi.stubGlobal("sessionStorage", {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  });
  store.clear();
});

afterEach(() => vi.unstubAllGlobals());

function fetchReturning(status: number, body: unknown) {
  return vi.fn(async () => new Response(JSON.stringify(body), { status }));
}

describe("api client", () => {
  it("sends the admin key header on every request", async () => {
    adminKey.set("secret-key");
    const fetchMock = fetchReturning(200, { ok: true });
    vi.stubGlobal("fetch", fetchMock);

    await api.get("/api/ai/admin/stats");

    const init = fetchMock.mock.calls[0]![1] as RequestInit;
    expect(new Headers(init.headers).get("X-Admin-Api-Key")).toBe("secret-key");
  });

  it("clears the key and throws AuthError on 401", async () => {
    adminKey.set("bad-key");
    vi.stubGlobal("fetch", fetchReturning(401, { error: "nope" }));

    await expect(api.get("/api/ai/admin/stats")).rejects.toBeInstanceOf(AuthError);
    expect(adminKey.get()).toBeNull();
  });

  it("throws the server error message on non-401 failures", async () => {
    adminKey.set("k");
    vi.stubGlobal("fetch", fetchReturning(400, { error: "retrieval.top-k must be between 1 and 50" }));

    await expect(api.put("/api/ai/admin/settings", {})).rejects.toThrow(
      "retrieval.top-k must be between 1 and 50");
  });
});
```
Run `npm test -- --run` → fails (module missing). Implement `dashboard/src/api.ts`:
```ts
const KEY_STORAGE = "rag-brain-admin-key";

export class AuthError extends Error {
  constructor() {
    super("Admin key missing or rejected");
  }
}

export const adminKey = {
  get: () => sessionStorage.getItem(KEY_STORAGE),
  set: (key: string) => sessionStorage.setItem(KEY_STORAGE, key),
  clear: () => sessionStorage.removeItem(KEY_STORAGE),
};

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("X-Admin-Api-Key", adminKey.get() ?? "");
  if (init.body) headers.set("Content-Type", "application/json");

  const response = await fetch(path, { ...init, headers });
  if (response.status === 401) {
    adminKey.clear();
    throw new AuthError();
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null) as { error?: string } | null;
    throw new Error(body?.error ?? `HTTP ${response.status}`);
  }
  return response.json() as Promise<T>;
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) }),
  put: <T>(path: string, body: unknown) =>
    request<T>(path, { method: "PUT", body: JSON.stringify(body) }),
  upload: <T>(path: string, form: FormData) =>
    request<T>(path, { method: "POST", body: form }),
};
```
NOTE: `upload` passes FormData — the `if (init.body)` JSON header guard must NOT fire for FormData; adjust: `if (init.body && !(init.body instanceof FormData)) headers.set("Content-Type", "application/json");` — include this in the implementation, and the browser sets the multipart boundary itself.

- [ ] **3.6** `dashboard/src/types.ts` (server contracts, matching the Java DTOs exactly):
```ts
export interface Stats {
  brain: { companyName: string; slug: string };
  corpus: { activeDocuments: number; totalDocuments: number; chunks: number };
}

export interface DocumentDto {
  id: string; title: string; sourceName: string; sourceType: string;
  fileName: string; documentVersion: string | null;
  effectiveDate: string | null; expirationDate: string | null; active: boolean;
}

export interface SyncResult {
  fileName: string; action: string; reason: string | null;
  executed: boolean; succeeded: boolean; error: string | null;
}
export interface SyncReport { dryRun: boolean; summary: Record<string, number>; results: SyncResult[] }

export interface SettingsResponse {
  effective: Record<string, string | number | boolean | null>;
  overrides: Record<string, string>;
}

export interface Citation {
  source_name: string | null; document_name: string | null;
  section: string | null; page_number: string | null; effective_date: string | null;
}
export interface AskResponse {
  conversationId: string; answer: string; citations: Citation[];
  confidence: number; humanEscalationRequired: boolean; disclaimer: string;
}

export interface RetrievedChunk {
  content: string; sourceName: string; documentName: string;
  section: string | null; pageNumber: number | null; combinedScore: number;
}
export interface RetrievalResult { chunks: RetrievedChunk[]; confidence: number; sufficientEvidence: boolean }

export interface AuditRow {
  id: string; createdAt: string; question: string; confidence: number | null;
  modelProvider: string | null; modelName: string | null;
  fallbackUsed: boolean; escalated: boolean;
}
export interface AuditPage { items: AuditRow[]; page: number; size: number; total: number }
export interface AuditDetail extends AuditRow {
  rewrittenQuestion: string | null; answer: string | null;
  sources: Record<string, unknown>[];
}
```

- [ ] **3.7** `dashboard/src/styles.css` — the mockup's look, hand-rolled (sidebar shell, cards, pills, table, form controls). Write it in full following the approved mockup: CSS custom properties for light theme (`--bg`, `--surface`, `--border: rgba(0,0,0,.12)`, `--text`, `--muted`, `--accent: #0F6E56`), classes `.shell` (grid `188px 1fr`, min-height 100vh), `.sidebar`, `.nav a` / `.nav a.active`, `.content` (padding 24px, max-width 980px), `.cards` (auto-fit grid), `.card`, `.stat`, `.pill` (+ `.pill.green/.amber/.gray/.blue/.purple` matching the mockup's badge colors), `.tbl` (full-width, 13px, bottom-borders), `.row-actions button` (small), `.btn-primary`, form input/select/button base styles (36px height, 1px borders, 8px radius), `.diff-line` (mono action + reason), `.gate` (centered card for the key screen). Keep it under ~150 lines; clarity over cleverness.

- [ ] **3.8** `dashboard/src/components.tsx`:
```tsx
import { ReactNode } from "react";

export function Pill({ tone, children }: { tone: "green" | "amber" | "gray" | "blue" | "purple"; children: ReactNode }) {
  return <span className={`pill ${tone}`}>{children}</span>;
}

export function Stat({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="stat">
      <span className="stat-label">{label}</span>
      <span className="stat-value">{value}</span>
    </div>
  );
}

export function ErrorNote({ message }: { message: string | null }) {
  return message ? <p className="error-note">{message}</p> : null;
}

export function outcomeTone(escalated: boolean): "green" | "amber" {
  return escalated ? "amber" : "green";
}
```

- [ ] **3.9** `dashboard/src/App.tsx` — gate + shell + routes:
```tsx
import { useEffect, useState } from "react";
import { HashRouter, NavLink, Navigate, Route, Routes } from "react-router-dom";
import { AuthError, adminKey, api } from "./api";
import { Stats } from "./types";
import Corpus from "./screens/Corpus";
import Settings from "./screens/Settings";
import TestConsole from "./screens/TestConsole";
import Audit from "./screens/Audit";

function KeyGate({ onUnlocked }: { onUnlocked: () => void }) {
  const [key, setKey] = useState("");
  const [error, setError] = useState<string | null>(null);

  async function unlock() {
    adminKey.set(key.trim());
    try {
      await api.get<Stats>("/api/ai/admin/stats");
      onUnlocked();
    } catch (e) {
      setError(e instanceof AuthError ? "Key rejected" : (e as Error).message);
    }
  }

  return (
    <div className="gate">
      <div className="card">
        <h1>RAG brain dashboard</h1>
        <p className="muted">Enter the admin API key for this brain.</p>
        <input type="password" value={key} onChange={(e) => setKey(e.target.value)}
               onKeyDown={(e) => e.key === "Enter" && unlock()} placeholder="admin API key" />
        <button className="btn-primary" onClick={unlock} disabled={!key.trim()}>Unlock</button>
        {error && <p className="error-note">{error}</p>}
      </div>
    </div>
  );
}

export default function App() {
  const [unlocked, setUnlocked] = useState(!!adminKey.get());
  const [stats, setStats] = useState<Stats | null>(null);

  useEffect(() => {
    if (!unlocked) return;
    api.get<Stats>("/api/ai/admin/stats")
      .then(setStats)
      .catch((e) => { if (e instanceof AuthError) setUnlocked(false); });
  }, [unlocked]);

  if (!unlocked) return <KeyGate onUnlocked={() => setUnlocked(true)} />;

  return (
    <HashRouter>
      <div className="shell">
        <aside className="sidebar">
          <div className="brand">
            <strong>{stats?.brain.companyName ?? "RAG brain"}</strong>
            <span className="muted">slug: {stats?.brain.slug ?? "…"}</span>
          </div>
          <nav className="nav">
            <NavLink to="/corpus">Corpus</NavLink>
            <NavLink to="/settings">Settings</NavLink>
            <NavLink to="/console">Test console</NavLink>
            <NavLink to="/audit">Audit</NavLink>
          </nav>
          <button className="signout" onClick={() => { adminKey.clear(); setUnlocked(false); }}>
            Lock dashboard
          </button>
        </aside>
        <main className="content">
          <Routes>
            <Route path="/corpus" element={<Corpus stats={stats} onCorpusChanged={() =>
              api.get<Stats>("/api/ai/admin/stats").then(setStats).catch(() => undefined)} />} />
            <Route path="/settings" element={<Settings />} />
            <Route path="/console" element={<TestConsole slug={stats?.brain.slug ?? "mortgage"} />} />
            <Route path="/audit" element={<Audit />} />
            <Route path="*" element={<Navigate to="/corpus" replace />} />
          </Routes>
        </main>
      </div>
    </HashRouter>
  );
}
```

- [ ] **3.10** `dashboard/src/main.tsx`:
```tsx
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import App from "./App";
import "./styles.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
```

- [ ] **3.11** For this task only, create PLACEHOLDER screen files so the build compiles (each replaced by its own task): `dashboard/src/screens/{Corpus,Settings,TestConsole,Audit}.tsx`, each shaped like:
```tsx
export default function Corpus(_props: { stats?: unknown; onCorpusChanged?: () => void }) {
  return <h1>Corpus</h1>;
}
```
(matching each route's props: Settings/Audit take no props; TestConsole takes `{ slug: string }`; Corpus as in App.tsx. Keep prop types real so App compiles strictly.)

- [ ] **3.12** Gates: `npm install` → `npm run check` → `npm test -- --run` (3/3) → `npm run build` → all green. Java suite untouched. Commit:
```bash
git add dashboard/
git commit -m "Dashboard scaffold: api client with key gate, shell, routes"
```

---

### Task 4: Corpus screen

- [ ] **4.1** Replace `dashboard/src/screens/Corpus.tsx` entirely:
```tsx
import { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import { DocumentDto, Stats, SyncReport } from "../types";
import { ErrorNote, Pill, Stat } from "../components";

export default function Corpus({ stats, onCorpusChanged }:
    { stats: Stats | null; onCorpusChanged: () => void }) {
  const [docs, setDocs] = useState<DocumentDto[]>([]);
  const [report, setReport] = useState<SyncReport | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = useCallback(() => {
    api.get<DocumentDto[]>("/api/ai/documents").then(setDocs).catch((e) => setError(e.message));
  }, []);

  useEffect(reload, [reload]);

  async function runSync(dryRun: boolean) {
    setBusy(dryRun ? "dry" : "sync");
    setError(null);
    try {
      setReport(await api.post<SyncReport>(`/api/ai/documents/sync?dryRun=${dryRun}`));
      if (!dryRun) { reload(); onCorpusChanged(); }
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(null); }
  }

  async function setActive(doc: DocumentDto, active: boolean) {
    setError(null);
    try {
      await api.post(`/api/ai/documents/${doc.id}/${active ? "activate" : "deactivate"}`);
      reload(); onCorpusChanged();
    } catch (e) { setError((e as Error).message); }
  }

  async function reindex(doc: DocumentDto) {
    setBusy(doc.id);
    setError(null);
    try { await api.post(`/api/ai/documents/${doc.id}/reindex`); reload(); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(null); }
  }

  const summaryTone = (k: string) =>
    k === "upload" || k === "update" ? "green" : k === "deactivate" ? "amber" : "gray";

  return (
    <>
      <header className="screen-head">
        <h1>Corpus</h1>
        <div className="actions">
          <button onClick={() => runSync(true)} disabled={busy !== null}>
            {busy === "dry" ? "Planning…" : "Dry run"}
          </button>
          <button className="btn-primary" onClick={() => runSync(false)} disabled={busy !== null}>
            {busy === "sync" ? "Syncing…" : "Sync now"}
          </button>
        </div>
      </header>
      <ErrorNote message={error} />
      <div className="cards">
        <Stat label="Active docs" value={stats?.corpus.activeDocuments ?? "…"} />
        <Stat label="All docs" value={stats?.corpus.totalDocuments ?? "…"} />
        <Stat label="Chunks" value={stats?.corpus.chunks.toLocaleString() ?? "…"} />
      </div>
      {report && (
        <div className="card sync-report">
          <div className="sync-summary">
            <strong>{report.dryRun ? "Dry run plan" : "Sync finished"}</strong>
            {Object.entries(report.summary).map(([k, v]) => (
              <Pill key={k} tone={summaryTone(k)}>{v} {k}</Pill>
            ))}
          </div>
          {report.results.filter((r) => r.action !== "SKIP" || r.error || report.dryRun).map((r) => (
            <div key={`${r.action}-${r.fileName}`} className="diff-line">
              <code>{r.action.toLowerCase()}</code>
              <span>{r.fileName}{r.reason ? ` — ${r.reason}` : ""}{r.error ? ` — FAILED: ${r.error}` : ""}</span>
            </div>
          ))}
        </div>
      )}
      <table className="tbl">
        <thead>
          <tr><th>Document</th><th>Source type</th><th>Effective</th><th>Status</th><th></th></tr>
        </thead>
        <tbody>
          {docs.map((d) => (
            <tr key={d.id}>
              <td title={d.fileName}>{d.title}</td>
              <td><Pill tone={d.sourceType === "INTERNAL_POLICY" ? "purple" : "blue"}>
                {d.sourceType.replaceAll("_", " ").toLowerCase()}</Pill></td>
              <td>{d.effectiveDate ?? "—"}</td>
              <td><Pill tone={d.active ? "green" : "gray"}>{d.active ? "active" : "inactive"}</Pill></td>
              <td className="row-actions">
                <button onClick={() => reindex(d)} disabled={busy === d.id}>
                  {busy === d.id ? "Reindexing…" : "Reindex"}
                </button>
                <button onClick={() => setActive(d, !d.active)}>
                  {d.active ? "Deactivate" : "Activate"}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
```
(Upload stays CLI/S3-driven in v1 — the mockup's upload button is deferred; syncing from S3 is the canonical path. Note this in the commit body.)
- [ ] **4.2** Gates: `npm run check`, `npm test -- --run`, `npm run build` → green. Commit:
```bash
git add dashboard/src/screens/Corpus.tsx
git commit -m "Corpus screen: stats, sync with diff, document management

Upload is deferred to keep S3 as the canonical corpus path; documents
arrive via sync, with activate/deactivate/reindex handled here."
```

---

### Task 5: Settings screen

- [ ] **5.1** Replace `dashboard/src/screens/Settings.tsx` entirely:
```tsx
import { useEffect, useState } from "react";
import { api } from "../api";
import { SettingsResponse } from "../types";
import { ErrorNote, Pill } from "../components";

const FIELDS = [
  { key: "answer.provider", label: "Answer provider", kind: "select" },
  { key: "answer.model", label: "Answer model", kind: "text" },
  { key: "utility.provider", label: "Utility provider", kind: "select" },
  { key: "utility.model", label: "Utility model", kind: "text" },
  { key: "retrieval.confidence-threshold", label: "Confidence threshold (0–1)", kind: "text" },
  { key: "retrieval.top-k", label: "Top-K chunks", kind: "text" },
  { key: "rerank.enabled", label: "LLM reranking", kind: "toggle" },
] as const;

const PROVIDERS = ["anthropic", "openai"];

export default function Settings() {
  const [data, setData] = useState<SettingsResponse | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const load = () => api.get<SettingsResponse>("/api/ai/admin/settings")
    .then((d) => { setData(d); setDraft({}); }).catch((e) => setError(e.message));

  useEffect(() => { load(); }, []);

  async function save() {
    setError(null); setSaved(false);
    try {
      setData(await api.put<SettingsResponse>("/api/ai/admin/settings", draft));
      setDraft({}); setSaved(true);
    } catch (e) { setError((e as Error).message); }
  }

  async function clearOverride(key: string) {
    setError(null);
    try { setData(await api.put<SettingsResponse>("/api/ai/admin/settings", { [key]: "" })); }
    catch (e) { setError((e as Error).message); }
  }

  if (!data) return <h1>Settings</h1>;
  const effective = (key: string) => String(data.effective[key] ?? "");
  const value = (key: string) => draft[key] ?? effective(key);
  const overridden = Object.keys(data.overrides);

  return (
    <>
      <header className="screen-head">
        <h1>Settings</h1>
        <span className="muted">changes go live within ~10 s, no restart</span>
      </header>
      <ErrorNote message={error} />
      <div className="card">
        {FIELDS.map((f) => (
          <div key={f.key} className="setting-row">
            <label>{f.label}
              {data.overrides[f.key] !== undefined && <Pill tone="amber">override</Pill>}
            </label>
            {f.kind === "select" ? (
              <select value={value(f.key)}
                      onChange={(e) => setDraft({ ...draft, [f.key]: e.target.value })}>
                {PROVIDERS.map((p) => <option key={p}>{p}</option>)}
              </select>
            ) : f.kind === "toggle" ? (
              <select value={value(f.key)}
                      onChange={(e) => setDraft({ ...draft, [f.key]: e.target.value })}>
                <option value="true">enabled</option>
                <option value="false">disabled</option>
              </select>
            ) : (
              <input value={value(f.key)}
                     placeholder={f.key.endsWith(".model") ? "blank = provider default" : ""}
                     onChange={(e) => setDraft({ ...draft, [f.key]: e.target.value })} />
            )}
            {data.overrides[f.key] !== undefined && (
              <button onClick={() => clearOverride(f.key)}>Reset</button>
            )}
          </div>
        ))}
        <p className="muted">Blank model means the provider's own default. A model name never crosses providers.</p>
        <div className="setting-row">
          <button className="btn-primary" onClick={save} disabled={Object.keys(draft).length === 0}>
            Save changes
          </button>
          {overridden.length > 0 && (
            <span className="muted">{overridden.length} override{overridden.length > 1 ? "s" : ""} active</span>
          )}
          {saved && <Pill tone="green">saved</Pill>}
        </div>
      </div>
    </>
  );
}
```
- [ ] **5.2** Gates green; commit `git add dashboard/src/screens/Settings.tsx && git commit -m "Settings screen: two-lane models, retrieval knobs, override reset"`.

---

### Task 6: Test console

- [ ] **6.1** Replace `dashboard/src/screens/TestConsole.tsx` entirely:
```tsx
import { useState } from "react";
import { api } from "../api";
import { AskResponse, RetrievalResult } from "../types";
import { ErrorNote, Pill } from "../components";

export default function TestConsole({ slug }: { slug: string }) {
  const [mode, setMode] = useState<"ask" | "retrieval">("ask");
  const [question, setQuestion] = useState("What is PMI?");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [answer, setAnswer] = useState<AskResponse | null>(null);
  const [retrieval, setRetrieval] = useState<RetrievalResult | null>(null);
  const [elapsed, setElapsed] = useState<number | null>(null);
  const [sessionId] = useState(() => `dashboard-${crypto.randomUUID()}`);

  async function run() {
    setBusy(true); setError(null); setAnswer(null); setRetrieval(null);
    const start = performance.now();
    try {
      if (mode === "ask") {
        setAnswer(await api.post<AskResponse>(`/api/ai/${slug}/ask`, { sessionId, question }));
      } else {
        setRetrieval(await api.get<RetrievalResult>(
          `/api/ai/documents/test-retrieval?question=${encodeURIComponent(question)}`));
      }
      setElapsed((performance.now() - start) / 1000);
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <>
      <header className="screen-head">
        <h1>Test console</h1>
        <div className="mode-toggle">
          <button className={mode === "ask" ? "on" : ""} onClick={() => setMode("ask")}>Full ask</button>
          <button className={mode === "retrieval" ? "on" : ""} onClick={() => setMode("retrieval")}>Retrieval only</button>
        </div>
      </header>
      <div className="ask-bar">
        <input value={question} onChange={(e) => setQuestion(e.target.value)}
               onKeyDown={(e) => e.key === "Enter" && !busy && run()} />
        <button className="btn-primary" onClick={run} disabled={busy || !question.trim()}>
          {busy ? "Working…" : mode === "ask" ? "Ask" : "Retrieve"}
        </button>
      </div>
      <ErrorNote message={error} />
      {answer && (
        <div className="card">
          <div className="chips">
            <Pill tone={answer.humanEscalationRequired ? "amber" : "green"}>
              {answer.humanEscalationRequired ? "escalated" : "grounded"}</Pill>
            <Pill tone="gray">confidence {answer.confidence.toFixed(2)}</Pill>
            <Pill tone="gray">{answer.citations.length} citations</Pill>
            {elapsed !== null && <Pill tone="gray">{elapsed.toFixed(1)} s</Pill>}
          </div>
          <p className="answer">{answer.answer}</p>
          {answer.citations.length > 0 && (
            <ul className="citations">
              {answer.citations.map((c, i) => (
                <li key={i}>{[c.source_name, c.section, c.page_number ? `p. ${c.page_number}` : null]
                  .filter(Boolean).join(" — ")}</li>
              ))}
            </ul>
          )}
          <p className="muted">{answer.disclaimer}</p>
        </div>
      )}
      {retrieval && (
        <div className="card">
          <div className="chips">
            <Pill tone={retrieval.sufficientEvidence ? "green" : "amber"}>
              {retrieval.sufficientEvidence ? "sufficient evidence" : "insufficient evidence"}</Pill>
            <Pill tone="gray">confidence {retrieval.confidence.toFixed(2)}</Pill>
            <Pill tone="gray">{retrieval.chunks.length} chunks</Pill>
            {elapsed !== null && <Pill tone="gray">{elapsed.toFixed(1)} s</Pill>}
          </div>
          {retrieval.chunks.map((chunk, i) => (
            <div key={i} className="chunk">
              <div className="chunk-head">
                <strong>{chunk.sourceName}</strong>
                <span className="muted">{[chunk.section, chunk.pageNumber ? `p. ${chunk.pageNumber}` : null]
                  .filter(Boolean).join(" — ")}</span>
                <Pill tone="gray">{chunk.combinedScore.toFixed(2)}</Pill>
              </div>
              <p>{chunk.content.length > 280 ? `${chunk.content.slice(0, 280)}…` : chunk.content}</p>
            </div>
          ))}
        </div>
      )}
    </>
  );
}
```
NOTE: full-ask hits the PUBLIC slug endpoint — it is rate-limited (10/min) and does NOT need the admin key (the api client sends it anyway; harmless). Dev-proxy makes it same-origin; in production the dashboard origin must be in CORS_ALLOWED_ORIGINS (it is for localhost dev).
- [ ] **6.2** Gates green; commit `git add dashboard/src/screens/TestConsole.tsx && git commit -m "Test console: full ask and retrieval-only modes"`.

---

### Task 7: Audit screen

- [ ] **7.1** Replace `dashboard/src/screens/Audit.tsx` entirely:
```tsx
import { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import { AuditDetail, AuditPage } from "../types";
import { ErrorNote, Pill } from "../components";

export default function Audit() {
  const [page, setPage] = useState(0);
  const [escalatedOnly, setEscalatedOnly] = useState(false);
  const [q, setQ] = useState("");
  const [data, setData] = useState<AuditPage | null>(null);
  const [open, setOpen] = useState<Record<string, AuditDetail>>({});
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    const params = new URLSearchParams({ page: String(page), size: "20",
      escalatedOnly: String(escalatedOnly) });
    if (q.trim()) params.set("q", q.trim());
    api.get<AuditPage>(`/api/ai/admin/audit?${params}`)
      .then(setData).catch((e) => setError(e.message));
  }, [page, escalatedOnly, q]);

  useEffect(load, [load]);

  async function toggle(id: string) {
    if (open[id]) {
      setOpen(({ [id]: _gone, ...rest }) => rest);
      return;
    }
    try {
      const detail = await api.get<AuditDetail>(`/api/ai/admin/audit/${id}`);
      setOpen((current) => ({ ...current, [id]: detail }));
    } catch (e) { setError((e as Error).message); }
  }

  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1;

  return (
    <>
      <header className="screen-head">
        <h1>Audit</h1>
        <div className="actions">
          <label className="check"><input type="checkbox" checked={escalatedOnly}
            onChange={(e) => { setPage(0); setEscalatedOnly(e.target.checked); }} />escalated only</label>
          <input placeholder="search questions…" value={q}
                 onChange={(e) => { setPage(0); setQ(e.target.value); }} />
        </div>
      </header>
      <ErrorNote message={error} />
      <table className="tbl">
        <thead>
          <tr><th>Time</th><th>Question</th><th>Conf.</th><th>Model</th><th>Outcome</th></tr>
        </thead>
        <tbody>
          {data?.items.map((row) => (
            <>
              <tr key={row.id} className="clickable" onClick={() => toggle(row.id)}>
                <td>{new Date(row.createdAt).toLocaleString()}</td>
                <td>{row.question}</td>
                <td>{row.confidence == null ? "—" : row.confidence.toFixed(2)}</td>
                <td><code>{row.modelName ?? "classifier"}</code></td>
                <td>
                  <Pill tone={row.escalated ? "amber" : "green"}>
                    {row.escalated ? "escalated" : "grounded"}</Pill>
                  {row.fallbackUsed && <Pill tone="purple">fallback</Pill>}
                </td>
              </tr>
              {open[row.id] && (
                <tr key={`${row.id}-detail`} className="detail-row">
                  <td colSpan={5}>
                    {open[row.id].answer && <p>{open[row.id].answer}</p>}
                    <p className="muted">
                      {open[row.id].sources.length} source chunk{open[row.id].sources.length === 1 ? "" : "s"} retrieved
                      {open[row.id].rewrittenQuestion ? ` · rewritten: ${open[row.id].rewrittenQuestion}` : ""}
                    </p>
                  </td>
                </tr>
              )}
            </>
          ))}
        </tbody>
      </table>
      <div className="pager">
        <button disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</button>
        <span className="muted">page {page + 1} of {pages}</span>
        <button disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Older</button>
      </div>
    </>
  );
}
```
- [ ] **7.2** Gates green; commit `git add dashboard/src/screens/Audit.tsx && git commit -m "Audit screen: filters, paging, expandable detail rows"`.

---

### Task 8: E2E verification

- [ ] **8.1** Java: `./gradlew cleanTest test --console=plain` → green; report exact totals (expect ~212).
- [ ] **8.2** Frontend: in `dashboard/` — `npm run check` (0 errors), `npm test -- --run` (3/3), `npm run build` (dist/ produced; report bundle size).
- [ ] **8.3** Boot API on :8090 (NEVER 8080; `set -a && source .env && set +a && ./gradlew bootRun --args='--server.port=8090' &`, poll health). Verify new endpoints with the admin key:
  - `GET /api/ai/admin/stats` → brain {companyName: "Mountain State Financial Group", slug: "mortgage"} + real counts.
  - `GET /api/ai/admin/audit?size=5` → 200, items present (the dev DB has audit rows), newest first.
  - `GET /api/ai/admin/audit?escalatedOnly=true&size=5` → 200, all items escalated=true.
  - Take one id from the list → `GET /api/ai/admin/audit/{id}` → 200 with answer/sources; confirm response contains NO `finalPrompt` field.
  - No key → both → 401. Encoded path `/api/ai/%61dmin/audit` no key → 401.
  - CORS preflight: `curl -s -i -X OPTIONS http://localhost:8090/api/ai/admin/stats -H "Origin: http://localhost:5173" -H "Access-Control-Request-Method: GET" -H "Access-Control-Request-Headers: X-Admin-Api-Key"` → 200 with `Access-Control-Allow-Origin: http://localhost:5173`.
- [ ] **8.4** Dashboard against the live API: `cd dashboard && npm run dev &` (port 5173, proxies /api → 8090). Then via curl THROUGH the proxy: `curl -s http://localhost:5173/api/ai/admin/stats -H "X-Admin-Api-Key: $ADMIN_API_KEY"` → same stats JSON (proves proxy wiring). Kill the dev server.
- [ ] **8.5** Kill the API; ports freed; `git status --short` → only expected untracked (`scripts/`); no stray changes.
- [ ] **8.6** Report results 8.1–8.5 + VERDICT. Note for the human: a click-through of the four screens in a browser (`cd dashboard && npm run dev`, open http://localhost:5173, unlock with ADMIN_API_KEY from .env) is the final acceptance step only a human can do.

---

## Plan self-review (done at write time)

- **Spec §8 coverage:** four screens per the locked mockup → Tasks 4–7; admin-key gate held in browser session → 3.5/3.9; static build, API base via dev proxy (configurable in prod via same-origin deploy or CORS) → 3.3 + 2.3; analytics-ready shape (router + typed api client) → 3; audit endpoint the mockup implied → 1; brain-branded shell from pack → 2 + 3.9. Upload deferred with rationale recorded in the Task 4 commit (S3 sync is canonical) — a deliberate, documented mockup deviation.
- **Placeholders:** Task 3.7 (styles.css) specifies the complete class inventory and constraints rather than 150 lines of CSS — the mockup is the visual contract and the class names are enumerated; every other code step is complete. Task 3.11 placeholders are explicit, typed, and each is replaced by a dedicated task.
- **Type consistency:** `types.ts` mirrors the Java DTOs defined in Tasks 1–2 (AuditPageDto items/page/size/total; StatsDto brain/corpus; SyncReport dryRun/summary/results; SettingsResponse effective/overrides; CitationDto snake_case keys per @JsonProperty). Screen props match App.tsx routes (Corpus stats+onCorpusChanged, TestConsole slug). `api.upload` exists for future use but no screen calls it in v1 — remove if the reviewer prefers YAGNI strictness (sanctioned either way).
- **Known risks accepted:** full-ask in the console consumes rate-limit budget (10/min — fine for an ops console); audit search is ILIKE over text (no index — table is small; revisit with pg_trgm if it grows); React 18 `<>` fragment keys in Audit rows use explicit keys on both `<tr>` elements.
