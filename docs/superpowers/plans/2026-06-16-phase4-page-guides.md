# Phase 4 — Page Guides Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a curatable Page-Guide registry (`brain_page_guides`) — table, JPA entity, repository, cached CRUD service, admin REST CRUD, optional pack seed file with first-boot seeding, and a dashboard screen — with zero changes to the ask pipeline and exact backward compatibility (empty table + no pack file = today's behavior). This is a near-mirror of the just-merged Phase 3 Link Registry; the Phase 3 source files are the authoritative template.

**Architecture:** A full-CRUD collection layer (spec §6.1, §6.5 collections pattern) mirroring the Phase 3 `BrainSourceLink`/`SourceLinkService`/`AdminSourceLinkController` shape for the mutable entity + verbs, combined with the `VocabularyService`/`SourceLinkService` 10s-cache idiom (`Long.MIN_VALUE` sentinel checked *before* subtraction, `invalidate()` on every write) for an `activePageGuides()` read snapshot that a later phase will consume. jsonb list columns use Hibernate `@JdbcTypeCode(SqlTypes.JSON)` on typed `List<String>` / `List<LinkRef>` / `List<UUID>` fields (the repo's only jsonb idiom — `BrainSourceLink` uses `List<String>`, `AuditLog` uses `List<Map<String,Object>>`; there is NO String-JSON/ObjectMapper convert pattern in the merged code). Pack seeding is fully optional via the existing `readOptional()` loader helper + a new positional `DomainPack` component defaulting to empty, plus a first-boot `ApplicationRunner` that seeds only when the table is empty.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA + Hibernate, PostgreSQL + pgvector, Flyway (forward-only, next is V9), Testcontainers (`pgvector/pgvector:pg16` + `@AutoConfigureTestDatabase(replace = NONE)`), JUnit 5 + Mockito, React 18 + Vite 5 + TS dashboard (HashRouter), Vitest. Build file: `build.gradle.kts` (no pom).

---

## Backward-compatibility contract (HARD RULE — verify every task preserves it)

- **Empty `brain_page_guides` table + no `page-guides.yaml` pack file = today's behavior, exactly.** The ask pipeline (`AskService`/`RetrievalService`/`PromptBuilderService`) is **NOT touched** in Phase 4.
- `activePageGuides()` on `PageGuideService` is the integration seam for a later phase. It is read by **nothing** in Phase 4. Keep it; do not wire it anywhere.
- The new `DomainPack.pageGuides()` component **defaults to `List.of()`** when the pack file is absent or its `guides:` key is null/absent. No pack file → empty component → first-boot seeder seeds nothing → empty table.
- `DomainPackLoader.validate()` gets **NO** `require(... non-empty)` assertion for page guides (that would make the optional file mandatory and break every pack that lacks it).
- The full backend suite (`./gradlew test`) + `MsfgGoldenPackTest` golden lock (including the existing `sourceLinksMatchSeed`) + the dashboard build/tests must stay green. Final task (15) is the explicit no-regression gate.
- **OMIT the `embedding vector` column** (spec §6.1 lists it optional). Semantic page-matching is deferred to a later phase; no pgvector column/wiring is added now.

---

## File Structure

### Backend — created

| Path | Responsibility |
|---|---|
| `src/main/resources/db/migration/V9__create_brain_page_guides.sql` | Flyway migration: `brain_page_guides` table + indexes. |
| `src/main/java/com/msfg/rag/domain/LinkRef.java` | Value record `LinkRef(String label, String url)` for the `internal_links` jsonb list-of-objects. |
| `src/main/java/com/msfg/rag/domain/BrainPageGuide.java` | Mutable full-CRUD JPA entity; jsonb `List<String>`/`List<LinkRef>`/`List<UUID>` fields; created/updated audit. Reuses existing `Surface` enum. |
| `src/main/java/com/msfg/rag/repository/BrainPageGuideRepository.java` | `JpaRepository<BrainPageGuide, UUID>` + derived finders. |
| `src/main/java/com/msfg/rag/dto/PageGuideDto.java` | Response DTO (snake_case JSON keys) + `from(entity)` factory. |
| `src/main/java/com/msfg/rag/dto/PageGuideRequest.java` | Create/update body; surface as `String`; sourceLinkIds as `List<String>`; internalLinks as `List<LinkRefRequest>`. |
| `src/main/java/com/msfg/rag/service/retrieval/PageGuideService.java` | Cached CRUD service (CRUD + `activePageGuides()` snapshot + `invalidate()`). |
| `src/main/java/com/msfg/rag/controller/AdminPageGuideController.java` | Admin REST CRUD under `/api/ai/admin/page-guides`. |
| `src/main/java/com/msfg/rag/seed/PageGuideSeeder.java` | First-boot `ApplicationRunner`: seed from pack when table empty. |
| `packs/msfg-mortgage/page-guides.yaml` | Optional pack seed: real mortgage page guides. |

### Backend — modified

| Path | Change |
|---|---|
| `src/main/java/com/msfg/rag/pack/DomainPack.java` | Add positional `List<PageGuide> pageGuides` component (12th, defaults to empty) + nested `PageGuide` + `InternalLink` records. Update Javadoc. |
| `src/main/java/com/msfg/rag/pack/DomainPackLoader.java` | Add `PageGuidesFile`/`PageGuideFile`/`InternalLinkFile` records, second `readOptional()` read in `load()`, append the mapped arg to `new DomainPack(...)`. Update Javadoc. |
| `src/test/java/com/msfg/rag/pack/MsfgGoldenPackTest.java` | Add `pageGuidesMatchSeed()` locking the seeded list literal. |

### Backend — tests created

| Path | Responsibility |
|---|---|
| `src/test/java/com/msfg/rag/repository/BrainPageGuideRepositoryTest.java` | Testcontainers `@DataJpaTest` repo test (includes `List<LinkRef>` jsonb round-trip). |
| `src/test/java/com/msfg/rag/service/retrieval/PageGuideServiceTest.java` | Service CRUD + cache + invalidate (mocked repo). |
| `src/test/java/com/msfg/rag/controller/AdminPageGuideControllerTest.java` | POJO controller test (Mockito `verify` + `assertThrows`). |
| `src/test/java/com/msfg/rag/seed/PageGuideSeederTest.java` | Pure-Mockito seeder unit test (no Spring/Docker); mocks repo + `DomainPack`. |

### Dashboard — created / modified

| Path | Change |
|---|---|
| `dashboard/src/screens/PageGuides.tsx` | New row-CRUD screen (table + create form + edit modal + internal-links repeater + source-link multi-select + delete-confirm + activate/deactivate). |
| `dashboard/src/types.ts` | Add `LinkRef`, `PageGuideDto`, `PageGuideRequest` interfaces. |
| `dashboard/src/App.tsx` | Register screen: import + `<NavLink>` + `<Route>`. |

---

## Conventions for every backend task

- **Test commands** run via the context-mode shell wrapper (raw `./gradlew` is redirected by a hook):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests '<FQN>'")`.
  Docker must be up for Testcontainers tests. Full suite: `./gradlew test`.
- **Controllers** throw `IllegalArgumentException` for ALL bad input / not-found. The existing `GlobalExceptionHandler` (`@RestControllerAdvice`) maps it to HTTP 400 `{"error": msg}`. Do **NOT** add try/catch or a per-controller `@ExceptionHandler`.
- **Controller tests are POJO** (`new Controller(mock)` + Mockito `verify`/`when` + `assertThrows`) — NOT MockMvc. Keep test classes in the **same package** as the class under test for package-private access.
- **Repo tests** mirror `BrainSourceLinkRepositoryTest` exactly: `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = Replace.NONE)` + `@Testcontainers` + `pgvector/pgvector:pg16`.
- **jsonb fields**: `@JdbcTypeCode(SqlTypes.JSON)` on typed `List<...>`; NOT-NULL jsonb initialized in Java (`= new ArrayList<>()`) AND `DEFAULT '[]'::jsonb` in DDL. `List<LinkRef>` (list of a Java record) and `List<UUID>` serialize through Hibernate's Jackson serializer the same way `List<String>` (Phase 3) and `List<Map<String,Object>>` (`AuditLog.retrievedContext`) do — verified by the Task 4 round-trip test.
- **Commit** after each green task with the exact commands shown.

---

## Task 1: V9 migration — `brain_page_guides` table

**Files:**
- Create: `src/main/resources/db/migration/V9__create_brain_page_guides.sql`

This is pure SQL (Flyway). There is no isolated unit test for DDL; it is exercised by the repo test in Task 4 (which boots Flyway against a Testcontainers Postgres). The verification step here is a no-op compile to confirm the file is present. Convention notes (copied verbatim from V8): newer `brain_*` tables use **`id UUID PRIMARY KEY` with NO `DEFAULT gen_random_uuid()`** (the JPA app supplies the UUID via `@GeneratedValue`), TIMESTAMPTZ audit columns, VARCHAR(100) NOT NULL `created_by`/`updated_by`, a leading header comment, and `idx_<tableNoun>_<cols>` index naming. jsonb NOT-NULL columns get `DEFAULT '[]'::jsonb`. `route` is NULLABLE (null → topic-matched only, per spec §6.1). **No `embedding vector` column** (deferred). `source_link_ids` is plain stored UUID[] (jsonb), NOT a relational FK to `brain_source_links` (spec §6.1 D5 + handoff §4).

- [ ] **Write the migration file** (complete, copy-paste-ready):

```sql
-- Editable registry of page guides — "where the user should go" (spec §6.1).
-- Route-keyed (route may be null for topic-matched-only guides) and topic-
-- matchable. Full-CRUD collection: create / list / get / update / delete (hard) /
-- activate / deactivate; one row per guide. Seeded from the optional pack file
-- page-guides.yaml on first boot (idempotent — only when the table is empty). An
-- empty table plus no pack file reproduces today's behavior exactly: nothing in
-- this table is read by the ask pipeline in Phase 4. The optional `embedding`
-- column from spec §6.1 is intentionally omitted (semantic page-matching is a
-- later phase). source_link_ids holds plain UUIDs referencing brain_source_links
-- by value — NOT a relational FK (spec §6.1 D5).
CREATE TABLE brain_page_guides (
    id                UUID         PRIMARY KEY,
    route             VARCHAR(500),                            -- null → topic-matched only
    title             VARCHAR(500) NOT NULL,
    purpose           TEXT         NOT NULL,
    surface           VARCHAR(50)  NOT NULL,                   -- PUBLIC | INTERNAL | BOTH
    user_intents      JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- string[]
    allowed_guidance  JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- string[]
    internal_links    JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- [{label,url}]
    source_link_ids   JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- UUID[] (by value, not FK)
    topics            JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- string[]
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(100) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(100) NOT NULL
);
CREATE INDEX idx_page_guides_active ON brain_page_guides (is_active);
CREATE INDEX idx_page_guides_created ON brain_page_guides (created_at DESC, id DESC);
```

- [ ] **Verify it compiles into the build** (no test yet; just confirm the project still builds with the new resource present):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew compileJava")`
  Expected: `BUILD SUCCESSFUL`. (Flyway validation happens when the repo test boots in Task 4.)

- [ ] **Commit:**
```
git add src/main/resources/db/migration/V9__create_brain_page_guides.sql
git commit -m "feat(guides): V9 migration for brain_page_guides table

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `LinkRef` value record

**Files:**
- Create: `src/main/java/com/msfg/rag/domain/LinkRef.java`

A tiny immutable value record for the `internal_links` jsonb list-of-objects (`[{label,url}]`). Stored inside the entity as `List<LinkRef>` via `@JdbcTypeCode(SqlTypes.JSON)` — Hibernate's Jackson serializer handles a `List` of records the same way it handles `List<String>` (Phase 3) and `List<Map>` (`AuditLog`). No JPA annotations on the record itself. This is exercised transitively (entity round-trip in Task 4, DTO/service in later tasks); verification here is a compile.

- [ ] **Write `LinkRef.java`:**

```java
package com.msfg.rag.domain;

/**
 * One inline internal link on a page guide (spec §6.1, internal_links
 * [{label,url}]). Stored inside BrainPageGuide as a jsonb List&lt;LinkRef&gt; via
 * @JdbcTypeCode(SqlTypes.JSON) — Hibernate's Jackson serializer round-trips a
 * List of this record the same way it round-trips List&lt;String&gt; and the
 * AuditLog's List&lt;Map&gt;. Internal links are inline (NOT registry rows);
 * external sources are referenced by id via source_link_ids instead.
 */
public record LinkRef(String label, String url) {
}
```

- [ ] **Verify compile:**
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew compileJava")`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/domain/LinkRef.java
git commit -m "feat(guides): LinkRef value record for internal links

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `BrainPageGuide` entity

**Files:**
- Create: `src/main/java/com/msfg/rag/domain/BrainPageGuide.java`

Mutable full-CRUD entity mirroring `BrainSourceLink` exactly: bare `@Id @GeneratedValue`, `@PrePersist`/`@PreUpdate` for `created_at`/`updated_at`, `is_active` column with `active` field + `isActive()`/`setActive()`, `created_by`/`updated_by` VARCHAR(100) NOT NULL, `created_at` `updatable=false` getter-only, `created_by` getter-only, a `protected` no-arg ctor for JPA + a `public` ctor taking the editable fields + `createdBy`. NOT-NULL jsonb lists initialized to `new ArrayList<>()` and defensively copied in the ctor. Reuses the existing `Surface` enum (`@Enumerated(EnumType.STRING)`). jsonb fields: `userIntents`/`allowedGuidance`/`topics` are `List<String>`, `internalLinks` is `List<LinkRef>`, `sourceLinkIds` is `List<UUID>`. `route` is nullable (no `nullable=false`, no length cap beyond 500). The entity is exercised by the repo test (Task 4); compile-verify here.

- [ ] **Write `BrainPageGuide.java`:**

```java
package com.msfg.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A curated page guide — "where the user should go" (spec §6.1). Full-CRUD
 * mutable row: editable route (nullable → topic-matched only)/title/purpose/
 * surface/user-intents/allowed-guidance/internal-links/source-link-ids/topics
 * plus a soft is_active flag. created_at/created_by are immutable; updated_at/
 * updated_by are touched on every write. jsonb list columns are mapped on typed
 * List fields via @JdbcTypeCode(SqlTypes.JSON): List&lt;String&gt; for intents/
 * guidance/topics, List&lt;LinkRef&gt; for inline internal links, List&lt;UUID&gt;
 * for the by-value references into brain_source_links (NOT a relational FK).
 */
@Entity
@Table(name = "brain_page_guides")
public class BrainPageGuide {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(length = 500)
    private String route;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Surface surface;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "user_intents", nullable = false, columnDefinition = "jsonb")
    private List<String> userIntents = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_guidance", nullable = false, columnDefinition = "jsonb")
    private List<String> allowedGuidance = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "internal_links", nullable = false, columnDefinition = "jsonb")
    private List<LinkRef> internalLinks = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_link_ids", nullable = false, columnDefinition = "jsonb")
    private List<UUID> sourceLinkIds = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> topics = new ArrayList<>();

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    protected BrainPageGuide() {
    }

    public BrainPageGuide(String route, String title, String purpose, Surface surface,
                          List<String> userIntents, List<String> allowedGuidance,
                          List<LinkRef> internalLinks, List<UUID> sourceLinkIds,
                          List<String> topics, String createdBy) {
        this.route = route;
        this.title = title;
        this.purpose = purpose;
        this.surface = surface;
        this.userIntents = userIntents == null ? new ArrayList<>() : new ArrayList<>(userIntents);
        this.allowedGuidance = allowedGuidance == null ? new ArrayList<>() : new ArrayList<>(allowedGuidance);
        this.internalLinks = internalLinks == null ? new ArrayList<>() : new ArrayList<>(internalLinks);
        this.sourceLinkIds = sourceLinkIds == null ? new ArrayList<>() : new ArrayList<>(sourceLinkIds);
        this.topics = topics == null ? new ArrayList<>() : new ArrayList<>(topics);
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
    }

    // --- getters / setters ---

    public UUID getId() { return id; }

    public String getRoute() { return route; }
    public void setRoute(String route) { this.route = route; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }

    public Surface getSurface() { return surface; }
    public void setSurface(Surface surface) { this.surface = surface; }

    public List<String> getUserIntents() { return userIntents; }
    public void setUserIntents(List<String> userIntents) { this.userIntents = userIntents; }

    public List<String> getAllowedGuidance() { return allowedGuidance; }
    public void setAllowedGuidance(List<String> allowedGuidance) { this.allowedGuidance = allowedGuidance; }

    public List<LinkRef> getInternalLinks() { return internalLinks; }
    public void setInternalLinks(List<LinkRef> internalLinks) { this.internalLinks = internalLinks; }

    public List<UUID> getSourceLinkIds() { return sourceLinkIds; }
    public void setSourceLinkIds(List<UUID> sourceLinkIds) { this.sourceLinkIds = sourceLinkIds; }

    public List<String> getTopics() { return topics; }
    public void setTopics(List<String> topics) { this.topics = topics; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
```

- [ ] **Verify compile:**
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew compileJava")`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/domain/BrainPageGuide.java
git commit -m "feat(guides): BrainPageGuide mutable JPA entity

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Repository + Testcontainers repo test

**Files:**
- Create: `src/main/java/com/msfg/rag/repository/BrainPageGuideRepository.java`
- Test: `src/test/java/com/msfg/rag/repository/BrainPageGuideRepositoryTest.java`

Derived finders bind to the Java property `active` (NOT `isActive`). Include the deterministic-tiebreaker ordered finders and `countByActiveTrue()` (returns `long`, used by the seeder). Add `@Repository` (consistent with `BrainSourceLinkRepository`). The repo test ALSO locks the `List<LinkRef>` and `List<UUID>` jsonb round-trip (the only new-vs-Phase-3 jsonb shapes): save a guide with 2 internal links + 2 source-link ids + a null route, read back, assert equal.

- [ ] **Write the failing test** (`BrainPageGuideRepositoryTest.java`). It constructs the entity via its public ctor, `saveAndFlush`es, and asserts via derived finders + jsonb round-trip:

```java
package com.msfg.rag.repository;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.LinkRef;
import com.msfg.rag.domain.Surface;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainPageGuideRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired
    private BrainPageGuideRepository repository;

    private BrainPageGuide guide(String title, String route, boolean active) {
        BrainPageGuide g = new BrainPageGuide(
                route, title, "Help users with " + title, Surface.BOTH,
                List.of("understand fha", "fha down payment"),
                List.of("explain the 3.5% minimum down payment"),
                List.of(new LinkRef("FHA loans", "/loans/fha"), new LinkRef("Apply", "/apply")),
                List.of(UUID.randomUUID(), UUID.randomUUID()),
                List.of("fha", "government"),
                "tester");
        g.setActive(active);
        return g;
    }

    @Test
    void savesAndReadsBackJsonbLists() {
        BrainPageGuide created = guide("FHA Loans", "/loans/fha", true);
        List<UUID> ids = created.getSourceLinkIds();
        BrainPageGuide saved = repository.saveAndFlush(created);

        BrainPageGuide found = repository.findById(saved.getId()).orElseThrow();
        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
        assertEquals("tester", found.getCreatedBy());
        assertEquals("/loans/fha", found.getRoute());
        assertEquals("FHA Loans", found.getTitle());
        assertEquals(Surface.BOTH, found.getSurface());
        assertEquals(List.of("understand fha", "fha down payment"), found.getUserIntents());
        assertEquals(List.of("explain the 3.5% minimum down payment"), found.getAllowedGuidance());
        assertEquals(List.of("fha", "government"), found.getTopics());
        // List<LinkRef> jsonb round-trip — records compare by value
        assertEquals(List.of(new LinkRef("FHA loans", "/loans/fha"), new LinkRef("Apply", "/apply")),
                found.getInternalLinks());
        // List<UUID> jsonb round-trip
        assertEquals(ids, found.getSourceLinkIds());
        assertTrue(found.isActive());
    }

    @Test
    void savesNullRoute() {
        BrainPageGuide saved = repository.saveAndFlush(guide("Topic-only guide", null, true));

        BrainPageGuide found = repository.findById(saved.getId()).orElseThrow();
        assertNull(found.getRoute());
    }

    @Test
    void findByActiveTrueExcludesInactive() {
        repository.saveAndFlush(guide("active-one", "/a", true));
        repository.saveAndFlush(guide("inactive-one", "/b", false));

        List<BrainPageGuide> active = repository.findByActiveTrueOrderByCreatedAtDescIdDesc();
        assertEquals(1, active.size());
        assertEquals("active-one", active.get(0).getTitle());
    }

    @Test
    void countByActiveTrueCountsOnlyActive() {
        repository.saveAndFlush(guide("a", "/a", true));
        repository.saveAndFlush(guide("b", "/b", true));
        repository.saveAndFlush(guide("c", "/c", false));

        assertEquals(2L, repository.countByActiveTrue());
    }

    @Test
    void findAllOrderedReturnsNewestFirst() {
        repository.saveAndFlush(guide("first", "/1", true));
        repository.saveAndFlush(guide("second", "/2", true));

        List<BrainPageGuide> all = repository.findAllByOrderByCreatedAtDescIdDesc();
        assertEquals(2, all.size());
        assertEquals("second", all.get(0).getTitle());
    }
}
```

- [ ] **Run it — expected FAIL:** `BrainPageGuideRepository` does not exist yet → compilation failure.
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.repository.BrainPageGuideRepositoryTest'")`
  Expected: compile error — `cannot find symbol: class BrainPageGuideRepository` (test does not run).

- [ ] **Write the repository** (`BrainPageGuideRepository.java`):

```java
package com.msfg.rag.repository;

import com.msfg.rag.domain.BrainPageGuide;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BrainPageGuideRepository extends JpaRepository<BrainPageGuide, UUID> {

    List<BrainPageGuide> findAllByOrderByCreatedAtDescIdDesc();

    List<BrainPageGuide> findByActiveTrueOrderByCreatedAtDescIdDesc();

    long countByActiveTrue();
}
```

- [ ] **Run it — expected PASS** (Docker must be up):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.repository.BrainPageGuideRepositoryTest'")`
  Expected: `BUILD SUCCESSFUL`, 5 tests passing. If the `List<LinkRef>` round-trip fails (e.g. Jackson cannot map the record), it is a real bug to fix in the entity/`LinkRef` — do NOT weaken the assertion.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/repository/BrainPageGuideRepository.java src/test/java/com/msfg/rag/repository/BrainPageGuideRepositoryTest.java
git commit -m "feat(guides): BrainPageGuideRepository + Testcontainers repo test

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: DTOs — `PageGuideDto` + `PageGuideRequest`

**Files:**
- Create: `src/main/java/com/msfg/rag/dto/PageGuideDto.java`
- Create: `src/main/java/com/msfg/rag/dto/PageGuideRequest.java`

`PageGuideDto` is the response record with snake_case JSON keys (mirrors `SourceLinkDto`'s `@JsonProperty` style); boolean exposed as `active`; `surface` exposed as `.name()` string; `internal_links` exposed as the `List<LinkRef>` (Jackson serializes each record to `{"label":...,"url":...}`); `source_link_ids` exposed as `List<String>` (UUIDs `.toString()`-ed for the wire); a static `from(BrainPageGuide)` factory. `PageGuideRequest` is the create/update body: `surface` typed as `String` (converted via `valueOf` for a clean 400), `sourceLinkIds` as `List<String>` (converted via `UUID.fromString` in the service — bad UUID → `IllegalArgumentException` → 400), `internalLinks` as a `List<LinkRefRequest>` (a nested `{label,url}` record on the request), and `userIntents`/`allowedGuidance`/`topics` as `List<String>`. `route` is a nullable `String`. NO bean-validation annotations (manual validation in the service, mirroring `SourceLinkRequest`). These are exercised by the service + controller tests; compile-verify here.

- [ ] **Write `PageGuideDto.java`:**

```java
package com.msfg.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.LinkRef;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admin/dashboard view of a page guide. snake_case wire keys mirror SourceLinkDto;
 * surface is exposed as its .name() string; the boolean is exposed as "active";
 * internal_links serialize each LinkRef record to {"label":...,"url":...};
 * source_link_ids are stringified UUIDs.
 */
public record PageGuideDto(
        UUID id,
        String route,
        String title,
        String purpose,
        String surface,
        @JsonProperty("user_intents") List<String> userIntents,
        @JsonProperty("allowed_guidance") List<String> allowedGuidance,
        @JsonProperty("internal_links") List<LinkRef> internalLinks,
        @JsonProperty("source_link_ids") List<String> sourceLinkIds,
        List<String> topics,
        boolean active,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("created_by") String createdBy,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("updated_by") String updatedBy
) {

    public static PageGuideDto from(BrainPageGuide g) {
        return new PageGuideDto(
                g.getId(),
                g.getRoute(),
                g.getTitle(),
                g.getPurpose(),
                g.getSurface().name(),
                g.getUserIntents(),
                g.getAllowedGuidance(),
                g.getInternalLinks(),
                g.getSourceLinkIds().stream().map(UUID::toString).toList(),
                g.getTopics(),
                g.isActive(),
                g.getCreatedAt(),
                g.getCreatedBy(),
                g.getUpdatedAt(),
                g.getUpdatedBy()
        );
    }
}
```

- [ ] **Write `PageGuideRequest.java`:**

```java
package com.msfg.rag.dto;

import java.util.List;

/**
 * Create/update body for a page guide. surface is a String (not the enum) so an
 * unknown value yields a clean 400 via valueOf(...) in the service rather than a
 * Jackson 500. sourceLinkIds are Strings converted with UUID.fromString in the
 * service (a malformed UUID → IllegalArgumentException → 400). internalLinks is a
 * list of the nested LinkRefRequest {label,url}. route is nullable. List fields
 * default-empty in the service. No jakarta.validation annotations — the service
 * validates manually.
 */
public record PageGuideRequest(
        String route,
        String title,
        String purpose,
        String surface,
        List<String> userIntents,
        List<String> allowedGuidance,
        List<LinkRefRequest> internalLinks,
        List<String> sourceLinkIds,
        List<String> topics
) {

    /** Inline {label,url} pair on a create/update body. */
    public record LinkRefRequest(String label, String url) {}
}
```

- [ ] **Verify compile:**
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew compileJava")`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/dto/PageGuideDto.java src/main/java/com/msfg/rag/dto/PageGuideRequest.java
git commit -m "feat(guides): PageGuideDto and PageGuideRequest DTOs

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: `PageGuideService` — cached CRUD + invalidate + tests

**Files:**
- Create: `src/main/java/com/msfg/rag/service/retrieval/PageGuideService.java`
- Test: `src/test/java/com/msfg/rag/service/retrieval/PageGuideServiceTest.java`

Package `com.msfg.rag.service.retrieval` (matches `SourceLinkService`). Full CRUD: `list()`, `get(id)`, `create(PageGuideRequest, createdBy)`, `update(id, PageGuideRequest, updatedBy)`, `delete(id)` (HARD delete, `@Transactional`), `setActive(id, boolean, updatedBy)`. PLUS `activePageGuides()` — a cached read snapshot mirroring `SourceLinkService`'s cache EXACTLY: 10s `System.nanoTime()` TTL, `Long.MIN_VALUE` sentinel checked **before** the `now - cachedAt > TTL` subtraction, `volatile` fields, `invalidate()` resets the sentinel. `invalidate()` is called on EVERY write path. `get`/`update`/`setActive`/`delete` use `findById().orElseThrow(() -> new IllegalArgumentException("page guide not found: " + id))`. `title` and `purpose` normalized with `.strip()`; blank-but-non-null inputs rejected via `isBlank()`. `route` is optional: null → null, otherwise `.strip()`. `surface` String→enum via `valueOf` (→ 400 on bad value). `sourceLinkIds` String→`UUID` via `UUID.fromString` (→ `IllegalArgumentException` → 400 on a malformed UUID). `internalLinks` mapped from `LinkRefRequest` to `LinkRef` (blank label AND blank url rows dropped; otherwise label/url stripped, a blank field kept as empty string). `activePageGuides()` is read by nothing in Phase 4 — keep it as the later-phase seam.

- [ ] **Write the failing test** (`PageGuideServiceTest.java`) — mocked repo; covers create/get/update/setActive/delete validation + invalidate + the cache (sentinel + TTL + invalidate refresh) + the UUID conversion + the internal-link mapping:

```java
package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.LinkRef;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.dto.PageGuideRequest;
import com.msfg.rag.repository.BrainPageGuideRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageGuideServiceTest {

    private final BrainPageGuideRepository repo = mock(BrainPageGuideRepository.class);
    private final PageGuideService service = new PageGuideService(repo);

    private final UUID linkId = UUID.randomUUID();

    private PageGuideRequest validRequest() {
        return new PageGuideRequest(
                "  /loans/fha  ", "  FHA Loans  ", "  Help users understand FHA.  ", "BOTH",
                List.of("understand fha"), List.of("explain the 3.5% down payment"),
                List.of(new PageGuideRequest.LinkRefRequest("FHA loans", "/loans/fha")),
                List.of(linkId.toString()), List.of("fha"));
    }

    @Test
    void createStripsAndPersistsAndInvalidates() {
        when(repo.save(any(BrainPageGuide.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.create(validRequest(), "admin-api");

        assertEquals("/loans/fha", dto.route());                  // stripped
        assertEquals("FHA Loans", dto.title());                   // stripped
        assertEquals("Help users understand FHA.", dto.purpose()); // stripped
        assertEquals("BOTH", dto.surface());
        assertEquals(List.of(new LinkRef("FHA loans", "/loans/fha")), dto.internalLinks());
        assertEquals(List.of(linkId.toString()), dto.sourceLinkIds());  // UUID round-tripped
        verify(repo).save(any(BrainPageGuide.class));
    }

    @Test
    void createAcceptsNullRoute() {
        when(repo.save(any(BrainPageGuide.class))).thenAnswer(inv -> inv.getArgument(0));
        PageGuideRequest req = new PageGuideRequest(
                null, "Topic guide", "Purpose", "BOTH",
                List.of(), List.of(), List.of(), List.of(), List.of());

        var dto = service.create(req, "admin-api");

        assertNull(dto.route());
        verify(repo).save(any(BrainPageGuide.class));
    }

    @Test
    void createRejectsBlankTitle() {
        PageGuideRequest req = new PageGuideRequest(
                "/x", "   ", "Purpose", "BOTH", List.of(), List.of(), List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> service.create(req, "admin-api"));
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsBlankPurpose() {
        PageGuideRequest req = new PageGuideRequest(
                "/x", "Title", "  ", "BOTH", List.of(), List.of(), List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> service.create(req, "admin-api"));
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsUnknownSurface() {
        PageGuideRequest req = new PageGuideRequest(
                "/x", "Title", "Purpose", "SIDEWAYS", List.of(), List.of(), List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> service.create(req, "admin-api"));
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsMalformedSourceLinkId() {
        PageGuideRequest req = new PageGuideRequest(
                "/x", "Title", "Purpose", "BOTH",
                List.of(), List.of(), List.of(), List.of("not-a-uuid"), List.of());
        assertThrows(IllegalArgumentException.class, () -> service.create(req, "admin-api"));
        verify(repo, never()).save(any());
    }

    @Test
    void getThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.get(id));
    }

    @Test
    void updateAppliesFieldsAndInvalidates() {
        UUID id = UUID.randomUUID();
        BrainPageGuide existing = new BrainPageGuide(
                "/old", "old", "old purpose", Surface.PUBLIC,
                List.of("x"), List.of(), List.of(), List.of(), List.of("old"), "seed");
        when(repo.findById(id)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);

        var dto = service.update(id, validRequest(), "admin-api");

        assertEquals("/loans/fha", existing.getRoute());
        assertEquals("FHA Loans", existing.getTitle());
        assertEquals("Help users understand FHA.", existing.getPurpose());
        assertEquals(Surface.BOTH, existing.getSurface());
        assertEquals(List.of(new LinkRef("FHA loans", "/loans/fha")), existing.getInternalLinks());
        assertEquals(List.of(linkId), existing.getSourceLinkIds());
        assertEquals("admin-api", existing.getUpdatedBy());
        assertEquals("FHA Loans", dto.title());
        verify(repo).save(existing);
    }

    @Test
    void updateThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.update(id, validRequest(), "admin-api"));
    }

    @Test
    void setActiveTogglesAndSaves() {
        UUID id = UUID.randomUUID();
        BrainPageGuide existing = new BrainPageGuide(
                "/n", "n", "p", Surface.BOTH,
                List.of(), List.of(), List.of(), List.of(), List.of(), "seed");
        when(repo.findById(id)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);

        var dto = service.setActive(id, false, "admin-api");

        assertEquals(false, dto.active());
        assertEquals("admin-api", existing.getUpdatedBy());
        verify(repo).save(existing);
    }

    @Test
    void deleteDelegatesToRepository() {
        UUID id = UUID.randomUUID();
        BrainPageGuide existing = new BrainPageGuide(
                "/n", "n", "p", Surface.BOTH,
                List.of(), List.of(), List.of(), List.of(), List.of(), "seed");
        when(repo.findById(id)).thenReturn(Optional.of(existing));

        service.delete(id);

        verify(repo).delete(existing);
    }

    @Test
    void deleteThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.delete(id));
        verify(repo, never()).delete(any());
    }

    @Test
    void activePageGuidesCachesFirstReadAndReusesUntilInvalidated() {
        BrainPageGuide a = new BrainPageGuide(
                "/a", "a", "p", Surface.BOTH,
                List.of(), List.of(), List.of(), List.of(), List.of(), "seed");
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(a));

        List<BrainPageGuide> first = service.activePageGuides();
        List<BrainPageGuide> second = service.activePageGuides();

        assertEquals(1, first.size());
        assertSame(first, second);                                  // served from cache
        verify(repo, times(1)).findByActiveTrueOrderByCreatedAtDescIdDesc();

        service.invalidate();
        service.activePageGuides();
        verify(repo, times(2)).findByActiveTrueOrderByCreatedAtDescIdDesc();  // reloaded after invalidate
    }
}
```

- [ ] **Run it — expected FAIL:** `PageGuideService` does not exist → compile error.
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.service.retrieval.PageGuideServiceTest'")`
  Expected: compile error — `cannot find symbol: class PageGuideService`.

- [ ] **Write the service** (`PageGuideService.java`):

```java
package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.LinkRef;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.dto.PageGuideDto;
import com.msfg.rag.dto.PageGuideRequest;
import com.msfg.rag.repository.BrainPageGuideRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Full-CRUD service over the page-guide registry, plus a short-cached
 * activePageGuides() read snapshot for the later retrieval/routing seam. The
 * cache mirrors SourceLinkService exactly: 10s nanoTime TTL with a Long.MIN_VALUE
 * sentinel tested BEFORE the subtraction (so a fresh process never computes
 * now - Long.MIN_VALUE), volatile fields, invalidate() on every write. Nothing in
 * Phase 4 reads activePageGuides() — it is the integration point for later phases.
 */
@Service
public class PageGuideService {

    private static final long CACHE_TTL_NANOS = 10_000_000_000L; // ~10 s

    private final BrainPageGuideRepository repo;

    private volatile List<BrainPageGuide> cache = List.of();
    private volatile long cachedAtNanos = Long.MIN_VALUE;

    public PageGuideService(BrainPageGuideRepository repo) {
        this.repo = repo;
    }

    public List<PageGuideDto> list() {
        return repo.findAllByOrderByCreatedAtDescIdDesc().stream()
                .map(PageGuideDto::from)
                .toList();
    }

    public PageGuideDto get(UUID id) {
        return PageGuideDto.from(find(id));
    }

    @Transactional
    public PageGuideDto create(PageGuideRequest req, String createdBy) {
        String title = required(req.title(), "title");
        String purpose = required(req.purpose(), "purpose");
        Surface surface = surface(req.surface());

        BrainPageGuide guide = new BrainPageGuide(
                route(req.route()), title, purpose, surface,
                cleanList(req.userIntents()), cleanList(req.allowedGuidance()),
                links(req.internalLinks()), ids(req.sourceLinkIds()),
                cleanList(req.topics()), createdBy);

        PageGuideDto dto = PageGuideDto.from(repo.save(guide));
        invalidate();
        return dto;
    }

    @Transactional
    public PageGuideDto update(UUID id, PageGuideRequest req, String updatedBy) {
        BrainPageGuide guide = find(id);
        guide.setRoute(route(req.route()));
        guide.setTitle(required(req.title(), "title"));
        guide.setPurpose(required(req.purpose(), "purpose"));
        guide.setSurface(surface(req.surface()));
        guide.setUserIntents(cleanList(req.userIntents()));
        guide.setAllowedGuidance(cleanList(req.allowedGuidance()));
        guide.setInternalLinks(links(req.internalLinks()));
        guide.setSourceLinkIds(ids(req.sourceLinkIds()));
        guide.setTopics(cleanList(req.topics()));
        guide.setUpdatedBy(updatedBy);

        PageGuideDto dto = PageGuideDto.from(repo.save(guide));
        invalidate();
        return dto;
    }

    @Transactional
    public PageGuideDto setActive(UUID id, boolean active, String updatedBy) {
        BrainPageGuide guide = find(id);
        guide.setActive(active);
        guide.setUpdatedBy(updatedBy);
        PageGuideDto dto = PageGuideDto.from(repo.save(guide));
        invalidate();
        return dto;
    }

    @Transactional
    public void delete(UUID id) {
        BrainPageGuide guide = find(id);
        repo.delete(guide);
        invalidate();
    }

    /** Cached snapshot of active page guides. Later-phase retrieval seam — unused in Phase 4. */
    public List<BrainPageGuide> activePageGuides() {
        long now = System.nanoTime();
        if (cachedAtNanos == Long.MIN_VALUE || now - cachedAtNanos > CACHE_TTL_NANOS) {
            cache = List.copyOf(repo.findByActiveTrueOrderByCreatedAtDescIdDesc());
            cachedAtNanos = now;
        }
        return cache;
    }

    public void invalidate() {
        cachedAtNanos = Long.MIN_VALUE;
    }

    // --- helpers ---

    private BrainPageGuide find(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("page guide not found: " + id));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    /** route is optional: null stays null; otherwise stripped (blank → null). */
    private static String route(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static Surface surface(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("surface is required");
        }
        return Surface.valueOf(value.strip());
    }

    private static List<String> cleanList(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        List<String> out = new ArrayList<>(values.size());
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v.strip());
            }
        }
        return out;
    }

    /** Map request {label,url} rows to LinkRef; drop rows where both fields are blank. */
    private static List<LinkRef> links(List<PageGuideRequest.LinkRefRequest> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        List<LinkRef> out = new ArrayList<>(values.size());
        for (PageGuideRequest.LinkRefRequest v : values) {
            if (v == null) {
                continue;
            }
            String label = v.label() == null ? "" : v.label().strip();
            String url = v.url() == null ? "" : v.url().strip();
            if (label.isEmpty() && url.isEmpty()) {
                continue;
            }
            out.add(new LinkRef(label, url));
        }
        return out;
    }

    /** Convert String ids to UUID (a malformed value throws IllegalArgumentException → 400). */
    private static List<UUID> ids(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        List<UUID> out = new ArrayList<>(values.size());
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(UUID.fromString(v.strip()));
            }
        }
        return out;
    }
}
```

- [ ] **Run it — expected PASS:**
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.service.retrieval.PageGuideServiceTest'")`
  Expected: `BUILD SUCCESSFUL`, 13 tests passing.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/service/retrieval/PageGuideService.java src/test/java/com/msfg/rag/service/retrieval/PageGuideServiceTest.java
git commit -m "feat(guides): PageGuideService cached CRUD + service test

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: `AdminPageGuideController` + POJO controller test

**Files:**
- Create: `src/main/java/com/msfg/rag/controller/AdminPageGuideController.java`
- Test: `src/test/java/com/msfg/rag/controller/AdminPageGuideControllerTest.java`

Class-level `@RequestMapping("/api/ai/admin/page-guides")` (admin-gated by `AdminApiKeyFilter`). Endpoints mirror `AdminSourceLinkController` verbs/structure EXACTLY: `GET ""` list, `GET "/{id}"` get, `POST ""` create, `PATCH "/{id}"` update, `DELETE "/{id}"` delete, `POST "/{id}/activate"`, `POST "/{id}/deactivate"` (both delegating to `service.setActive`). `UPDATED_BY = "admin-api"` constant threaded through create/update/setActive. Controller delegates validation/conversion to the service (which throws `IllegalArgumentException` → 400 via `GlobalExceptionHandler`); it adds a null-body guard on create/update. Delete returns `{"deleted": true, "id": id}`. No `@Valid`, no try/catch.

- [ ] **Write the failing test** (`AdminPageGuideControllerTest.java`) — POJO, mocked service:

```java
package com.msfg.rag.controller;

import com.msfg.rag.domain.LinkRef;
import com.msfg.rag.dto.PageGuideDto;
import com.msfg.rag.dto.PageGuideRequest;
import com.msfg.rag.service.retrieval.PageGuideService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminPageGuideControllerTest {

    private final PageGuideService service = mock(PageGuideService.class);
    private final AdminPageGuideController controller = new AdminPageGuideController(service);

    private PageGuideDto dto(UUID id, boolean active) {
        return new PageGuideDto(id, "/loans/fha", "FHA Loans", "Help users understand FHA.",
                "BOTH", List.of("understand fha"), List.of("explain the 3.5% down payment"),
                List.of(new LinkRef("FHA loans", "/loans/fha")), List.of(UUID.randomUUID().toString()),
                List.of("fha"), active, OffsetDateTime.now(), "admin-api", OffsetDateTime.now(), "admin-api");
    }

    private PageGuideRequest req() {
        return new PageGuideRequest("/loans/fha", "FHA Loans", "Help users understand FHA.", "BOTH",
                List.of("understand fha"), List.of("explain the 3.5% down payment"),
                List.of(new PageGuideRequest.LinkRefRequest("FHA loans", "/loans/fha")),
                List.of(UUID.randomUUID().toString()), List.of("fha"));
    }

    @Test
    void listDelegates() {
        UUID id = UUID.randomUUID();
        when(service.list()).thenReturn(List.of(dto(id, true)));

        List<PageGuideDto> result = controller.list();

        assertEquals(1, result.size());
        verify(service).list();
    }

    @Test
    void getDelegates() {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenReturn(dto(id, true));

        PageGuideDto result = controller.get(id);

        assertEquals(id, result.id());
        verify(service).get(id);
    }

    @Test
    void createDelegatesWithAdminAttribution() {
        UUID id = UUID.randomUUID();
        PageGuideRequest body = req();
        when(service.create(body, "admin-api")).thenReturn(dto(id, true));

        PageGuideDto result = controller.create(body);

        assertEquals(id, result.id());
        verify(service).create(body, "admin-api");
    }

    @Test
    void createRejectsNullBody() {
        assertThrows(IllegalArgumentException.class, () -> controller.create(null));
        verify(service, never()).create(any(), any());
    }

    @Test
    void updateDelegatesWithAdminAttribution() {
        UUID id = UUID.randomUUID();
        PageGuideRequest body = req();
        when(service.update(id, body, "admin-api")).thenReturn(dto(id, true));

        PageGuideDto result = controller.update(id, body);

        assertEquals(id, result.id());
        verify(service).update(id, body, "admin-api");
    }

    @Test
    void updateRejectsNullBody() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> controller.update(id, null));
        verify(service, never()).update(any(), any(), any());
    }

    @Test
    void activateDelegates() {
        UUID id = UUID.randomUUID();
        when(service.setActive(id, true, "admin-api")).thenReturn(dto(id, true));

        PageGuideDto result = controller.activate(id);

        assertTrue(result.active());
        verify(service).setActive(id, true, "admin-api");
    }

    @Test
    void deactivateDelegates() {
        UUID id = UUID.randomUUID();
        when(service.setActive(id, false, "admin-api")).thenReturn(dto(id, false));

        PageGuideDto result = controller.deactivate(id);

        assertEquals(false, result.active());
        verify(service).setActive(id, false, "admin-api");
    }

    @Test
    void deleteDelegatesAndReturnsOk() {
        UUID id = UUID.randomUUID();

        var response = controller.delete(id);

        verify(service).delete(id);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(Boolean.TRUE, response.getBody().get("deleted"));
        assertEquals(id, response.getBody().get("id"));
    }
}
```

- [ ] **Run it — expected FAIL:** `AdminPageGuideController` does not exist → compile error.
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.controller.AdminPageGuideControllerTest'")`
  Expected: compile error — `cannot find symbol: class AdminPageGuideController`.

- [ ] **Write the controller** (`AdminPageGuideController.java`):

```java
package com.msfg.rag.controller;

import com.msfg.rag.dto.PageGuideDto;
import com.msfg.rag.dto.PageGuideRequest;
import com.msfg.rag.service.retrieval.PageGuideService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin CRUD for the page-guide registry. Gated by AdminApiKeyFilter (the
 * /api/ai/admin prefix). All validation / not-found / bad-enum / bad-UUID cases
 * throw IllegalArgumentException, mapped to HTTP 400 by GlobalExceptionHandler —
 * no try/catch or per-controller @ExceptionHandler here.
 */
@RestController
@RequestMapping("/api/ai/admin/page-guides")
public class AdminPageGuideController {

    private static final String UPDATED_BY = "admin-api";

    private final PageGuideService service;

    public AdminPageGuideController(PageGuideService service) {
        this.service = service;
    }

    @GetMapping
    public List<PageGuideDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public PageGuideDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public PageGuideDto create(@RequestBody PageGuideRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return service.create(body, UPDATED_BY);
    }

    @PatchMapping("/{id}")
    public PageGuideDto update(@PathVariable UUID id, @RequestBody PageGuideRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return service.update(id, body, UPDATED_BY);
    }

    @PostMapping("/{id}/activate")
    public PageGuideDto activate(@PathVariable UUID id) {
        return service.setActive(id, true, UPDATED_BY);
    }

    @PostMapping("/{id}/deactivate")
    public PageGuideDto deactivate(@PathVariable UUID id) {
        return service.setActive(id, false, UPDATED_BY);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }
}
```

- [ ] **Run it — expected PASS:**
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.controller.AdminPageGuideControllerTest'")`
  Expected: `BUILD SUCCESSFUL`, 9 tests passing.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/controller/AdminPageGuideController.java src/test/java/com/msfg/rag/controller/AdminPageGuideControllerTest.java
git commit -m "feat(guides): AdminPageGuideController CRUD + POJO controller test

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: `page-guides.yaml` pack seed

**Files:**
- Create: `packs/msfg-mortgage/page-guides.yaml`

A small but real seed of mortgage page guides mapped to the schema. The loader's YAML mapper is `KEBAB_CASE` + `FAIL_ON_UNKNOWN_PROPERTIES` ON, so **every key must match the `PageGuideFile` record component 1:1** (kebab-case). `surface` is deserialized as **String** (the exact enum name `PUBLIC|INTERNAL|BOTH`) so the seeder's `valueOf` succeeds. `user-intents` / `allowed-guidance` / `topics` are string lists. `internal-links` is a list of `{label,url}` objects (mapped via `InternalLinkFile`). **`source-link-ids` is seeded EMPTY** (omit the key, or `[]`): the source links are seeded with freshly generated UUIDs at first boot, so a literal UUID in this YAML cannot reference a real seeded row — source links are attached to guides later via the dashboard multi-select. This limitation is intentional and documented here. (This file is created here; the loader records that consume it land in Task 9. Until Task 9, this file is inert — the loader ignores files it does not read.)

- [ ] **Write `page-guides.yaml`:**

```yaml
# Optional pack seed for the page-guide registry (spec §6.1). Loaded by
# DomainPackLoader.readOptional and seeded into brain_page_guides on first boot
# only when the table is empty. Removing this file reproduces today's behavior
# (empty registry). Keys are kebab-case; surface uses the exact enum names
# PUBLIC|INTERNAL|BOTH. internal-links are inline [{label,url}] objects.
#
# NOTE: source-link-ids is intentionally EMPTY for every seed guide. Source links
# are seeded into brain_source_links with freshly generated UUIDs at first boot,
# so a literal UUID here could not reference a real row. Attach source links to
# guides AFTER boot via the dashboard Page Guides screen (the source-link
# multi-select). This file therefore omits source-link-ids entirely (defaults to []).
guides:
  - route: "/loans/fha"
    title: "FHA Loans"
    purpose: "Explain FHA loan basics and direct the user toward FHA eligibility and next steps."
    surface: BOTH
    user-intents:
      - "understand FHA loans"
      - "FHA down payment"
      - "FHA credit requirements"
    allowed-guidance:
      - "explain the 3.5% minimum down payment for qualifying credit scores"
      - "explain that FHA loans require mortgage insurance (UFMIP and annual MIP)"
    internal-links:
      - label: "Start an FHA application"
        url: "/apply?program=fha"
      - label: "FHA eligibility checklist"
        url: "/loans/fha/eligibility"
    topics:
      - fha
      - government
      - down-payment

  - route: "/loans/conventional"
    title: "Conventional Loans"
    purpose: "Explain conventional (Fannie/Freddie) loan basics and direct the user toward conforming options."
    surface: BOTH
    user-intents:
      - "understand conventional loans"
      - "conforming loan limits"
      - "private mortgage insurance"
    allowed-guidance:
      - "explain that conventional loans follow Fannie Mae / Freddie Mac guidelines"
      - "explain that PMI may be required below 20% down and can typically be removed later"
    internal-links:
      - label: "Compare conventional vs FHA"
        url: "/loans/compare"
    topics:
      - conventional
      - conforming
      - pmi

  - route: "/loans/duplex"
    title: "2-4 Unit (Duplex) Properties"
    purpose: "Explain financing for 2-4 unit / duplex properties and point the user to multi-unit guidance."
    surface: BOTH
    user-intents:
      - "buy a duplex"
      - "2-unit property financing"
      - "rental income from a multi-unit home"
    allowed-guidance:
      - "explain that 2-4 unit properties are eligible under several programs with program-specific rules"
      - "explain that owner-occupied multi-unit properties may use the principal residence guidelines"
    internal-links:
      - label: "Multi-unit eligibility"
        url: "/loans/multi-unit"
    topics:
      - duplex
      - 2-unit
      - multi-unit
      - investment
```

- [ ] **Verify YAML is parseable** (standalone sanity; the loader integration is tested in Task 9):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"python3 -c \"import yaml; d=yaml.safe_load(open('packs/msfg-mortgage/page-guides.yaml')); print('guides:', len(d['guides'])); assert len(d['guides'])==3; assert all('source-link-ids' not in g for g in d['guides'])\"")`
  Expected: `guides: 3`.

- [ ] **Commit:**
```
git add packs/msfg-mortgage/page-guides.yaml
git commit -m "feat(guides): seed page-guides.yaml with FHA/conventional/duplex guides

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: `DomainPackLoader` second `readOptional()` + `PageGuidesFile` + `DomainPack` 12th component (golden-test-driven)

**Files:**
- Modify: `src/main/java/com/msfg/rag/pack/DomainPack.java`
- Modify: `src/main/java/com/msfg/rag/pack/DomainPackLoader.java`
- Test: `src/test/java/com/msfg/rag/pack/MsfgGoldenPackTest.java`

`DomainPack` is currently a POSITIONAL **11-arg** record (the 11th, `sourceLinks`, was added in Phase 3). Add `pageGuides` as the **12th** component (defaults to `List.of()` when null — NOT null-preserving, mirroring `sourceLinks`) plus a nested `PageGuide` record and a nested `InternalLink` record (for `[{label,url}]`). The loader gets `PageGuidesFile`/`PageGuideFile`/`InternalLinkFile` intermediate records, a second `readOptional()` read in `load()` (the helper already exists from Phase 3 — reuse it, do NOT re-add it), and the new positional arg in the `new DomainPack(...)` call. NO `require(... non-empty)` in `validate()` for page guides (would make the optional file mandatory). Update both Javadocs ("five required + two optional").

This task is **golden-test-driven** (red→green): the new behavior is locked by a `pageGuidesMatchSeed()` golden assertion that is written BEFORE the loader reads the YAML. Sequence: (1) shift the record shape so `PACK.pageGuides()` compiles but the loader passes a hardcoded empty `List.of()` (the unmapped/red state); (2) write the golden assertion in `MsfgGoldenPackTest` and run it — it FAILS (`expected 3, was 0`) because `page-guides.yaml` from Task 8 is not yet read; (3) implement the `PageGuidesFile`/`PageGuideFile`/`InternalLinkFile` records + the `readOptional` call + the mapped `new DomainPack(...)` arg to populate the component — the assertion turns green. (`page-guides.yaml` already exists from Task 8, so the assertion has real data to lock the moment the loader reads it.)

**There is exactly ONE positional `new DomainPack(...)` call site in the whole repo: in `DomainPackLoader.load()`** (confirmed by `grep -rn "new DomainPack(" src` — `MsfgGoldenPackTest` uses `TestPacks.msfg()`, not a positional ctor). Updating that one site for the 12th arg is sufficient.

- [ ] **Edit `DomainPack.java` — add the 12th component to the record header.** Replace:

```java
        Map<String, String> acronymExpansions,
        List<ProgramRule> programRules,
        List<SourceLink> sourceLinks
) {

    public DomainPack {
        classifierRules = classifierRules == null ? null : List.copyOf(classifierRules);
        acronymExpansions = acronymExpansions == null ? null : Map.copyOf(acronymExpansions);
        programRules = programRules == null ? null : List.copyOf(programRules);
        sourceLinks = sourceLinks == null ? List.of() : List.copyOf(sourceLinks);
    }
```

with:

```java
        Map<String, String> acronymExpansions,
        List<ProgramRule> programRules,
        List<SourceLink> sourceLinks,
        List<PageGuide> pageGuides
) {

    public DomainPack {
        classifierRules = classifierRules == null ? null : List.copyOf(classifierRules);
        acronymExpansions = acronymExpansions == null ? null : Map.copyOf(acronymExpansions);
        programRules = programRules == null ? null : List.copyOf(programRules);
        sourceLinks = sourceLinks == null ? List.of() : List.copyOf(sourceLinks);
        pageGuides = pageGuides == null ? List.of() : List.copyOf(pageGuides);
    }
```

- [ ] **Edit `DomainPack.java` — add the nested `PageGuide` + `InternalLink` records** before the final closing brace (after the `SourceLink` record):

```java
    /**
     * One optional page-guide registry seed entry from page-guides.yaml. surface
     * is the enum NAME (PUBLIC|INTERNAL|BOTH); the first-boot seeder converts it
     * via valueOf. route may be null (topic-matched only). sourceLinkIds is
     * always empty in pack seeds (links are attached later via the dashboard,
     * because seeded source-link UUIDs are not known at YAML-authoring time).
     */
    public record PageGuide(
            String route,
            String title,
            String purpose,
            String surface,
            List<String> userIntents,
            List<String> allowedGuidance,
            List<InternalLink> internalLinks,
            List<String> topics
    ) {
        public PageGuide {
            userIntents = userIntents == null ? List.of() : List.copyOf(userIntents);
            allowedGuidance = allowedGuidance == null ? List.of() : List.copyOf(allowedGuidance);
            internalLinks = internalLinks == null ? List.of() : List.copyOf(internalLinks);
            topics = topics == null ? List.of() : List.copyOf(topics);
        }
    }

    /** One inline internal link ({label,url}) on a page-guide seed. */
    public record InternalLink(String label, String url) {}
```

- [ ] **Edit `DomainPack.java` — update the class Javadoc.** Change the opening sentence to note the second optional file (e.g. `... loaded from a pack directory at boot (five required files plus optional source-links.yaml and page-guides.yaml; see DomainPackLoader).`) and append after the existing source-links sentence: `Page guides likewise default to an empty list when the optional file is absent.`

- [ ] **Edit `DomainPackLoader.java` — append a temporary EMPTY arg to the `new DomainPack(...)` call (the red state).** This makes the new 12-arg record compile while the loader does NOT yet read the YAML — so `PACK.pageGuides()` is empty, which the golden assertion below will catch. The current call ends with the `sourceLinks` mapping `.toList())`. Change the closing `)` of that mapping into `,` and append `List.of()` as the new final argument (showing the tail of the call):

```java
                (sourceLinksFile == null || sourceLinksFile.links() == null) ? List.of()
                        : sourceLinksFile.links().stream()
                                .map(s -> new DomainPack.SourceLink(
                                        s.name(), s.url(), s.domain(), s.authority(),
                                        s.topics(), s.freshnessRequired(),
                                        s.allowedUse(), s.doNotUseFor(), s.surface()))
                                .toList(),
                List.of());   // TEMPORARY: page guides unmapped — replaced below to turn the golden test green
```

- [ ] **Write the failing golden assertion** (`MsfgGoldenPackTest.pageGuidesMatchSeed`). Add inside the `MsfgGoldenPackTest` class (e.g. after `sourceLinksMatchSeed`). `PACK` is `TestPacks.msfg()`; the expected values mirror `page-guides.yaml` (Task 8) exactly — a 2-way golden sync. This assertion is written BEFORE the loader reads the YAML, so it locks the literal first. The file already uses `import static org.junit.jupiter.api.Assertions.*;`, so `assertEquals`/`assertTrue`/`assertNull` need no new imports:

```java
    @Test
    void pageGuidesMatchSeed() {
        List<DomainPack.PageGuide> guides = PACK.pageGuides();
        assertEquals(3, guides.size());

        DomainPack.PageGuide fha = guides.get(0);
        assertEquals("/loans/fha", fha.route());
        assertEquals("FHA Loans", fha.title());
        assertEquals(
                "Explain FHA loan basics and direct the user toward FHA eligibility and next steps.",
                fha.purpose());
        assertEquals("BOTH", fha.surface());
        assertEquals(List.of("understand FHA loans", "FHA down payment", "FHA credit requirements"),
                fha.userIntents());
        assertEquals(List.of(
                "explain the 3.5% minimum down payment for qualifying credit scores",
                "explain that FHA loans require mortgage insurance (UFMIP and annual MIP)"),
                fha.allowedGuidance());
        assertEquals(List.of(
                new DomainPack.InternalLink("Start an FHA application", "/apply?program=fha"),
                new DomainPack.InternalLink("FHA eligibility checklist", "/loans/fha/eligibility")),
                fha.internalLinks());
        assertEquals(List.of("fha", "government", "down-payment"), fha.topics());

        assertEquals("/loans/conventional", guides.get(1).route());
        assertEquals("Conventional Loans", guides.get(1).title());
        assertEquals("BOTH", guides.get(1).surface());

        assertEquals("/loans/duplex", guides.get(2).route());
        assertEquals("2-4 Unit (Duplex) Properties", guides.get(2).title());
        assertEquals(List.of("duplex", "2-unit", "multi-unit", "investment"), guides.get(2).topics());

        // every seeded guide must have a non-blank title and a non-blank purpose
        for (var guide : guides) {
            assertFalse(guide.title() == null || guide.title().isBlank(), "every page guide has a title");
            assertFalse(guide.purpose() == null || guide.purpose().isBlank(), "every page guide has a purpose");
        }
    }
```

- [ ] **Run it — expected FAIL:** the loader still passes a hardcoded empty `List.of()`, so `PACK.pageGuides()` is empty.
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.pack.MsfgGoldenPackTest'")`
  Expected: `pageGuidesMatchSeed` FAILS with `expected: <3> but was: <0>` (the assertion is locked before the loader reads the YAML). All other golden methods (including `sourceLinksMatchSeed`) still PASS.

- [ ] **Edit `DomainPackLoader.java` — update the class Javadoc.** Replace `Reads the five required YAML files (plus an optional source-links.yaml) of a domain pack directory into a DomainPack.` with `Reads the five required YAML files (plus optional source-links.yaml and page-guides.yaml) of a domain pack directory into a DomainPack.`

- [ ] **Edit `DomainPackLoader.java` — add the intermediate file records.** After the existing `SourceLinkFile` record (just before the `load(...)` method), add:

```java
    private record PageGuidesFile(List<PageGuideFile> guides) {}
    private record PageGuideFile(String route, String title, String purpose, String surface,
                                 List<String> userIntents, List<String> allowedGuidance,
                                 List<InternalLinkFile> internalLinks, List<String> topics) {}
    private record InternalLinkFile(String label, String url) {}
```

- [ ] **Edit `DomainPackLoader.java` — read the optional file in `load()`.** After the line `SourceLinksFile sourceLinksFile = readOptional(packDir, "source-links.yaml", SourceLinksFile.class);` add:

```java
        PageGuidesFile pageGuidesFile = readOptional(packDir, "page-guides.yaml", PageGuidesFile.class);
```

- [ ] **Edit `DomainPackLoader.java` — replace the temporary `List.of()` with the mapped arg (the green state).** Replace the `List.of());   // TEMPORARY: ...` line added in the red step with the mapped stream so the constructor reads (showing the tail of the call):

```java
                (sourceLinksFile == null || sourceLinksFile.links() == null) ? List.of()
                        : sourceLinksFile.links().stream()
                                .map(s -> new DomainPack.SourceLink(
                                        s.name(), s.url(), s.domain(), s.authority(),
                                        s.topics(), s.freshnessRequired(),
                                        s.allowedUse(), s.doNotUseFor(), s.surface()))
                                .toList(),
                (pageGuidesFile == null || pageGuidesFile.guides() == null) ? List.of()
                        : pageGuidesFile.guides().stream()
                                .map(g -> new DomainPack.PageGuide(
                                        g.route(), g.title(), g.purpose(), g.surface(),
                                        g.userIntents(), g.allowedGuidance(),
                                        g.internalLinks() == null ? List.of()
                                                : g.internalLinks().stream()
                                                        .map(l -> new DomainPack.InternalLink(l.label(), l.url()))
                                                        .toList(),
                                        g.topics()))
                                .toList());
```

- [ ] **Do NOT add any `require(...)` for page guides in `validate()`.** (The optional file must stay optional; the existing per-pack tests for packs without the file must keep booting.)

- [ ] **Run it — expected PASS:** the loader now reads `page-guides.yaml` and populates the component, so `pageGuidesMatchSeed` (and every existing golden method, including `sourceLinksMatchSeed`) passes.
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.pack.MsfgGoldenPackTest'")`
  Expected: `BUILD SUCCESSFUL`, all golden methods passing. If `pageGuidesMatchSeed` fails on a value mismatch, FIX `packs/msfg-mortgage/page-guides.yaml` to match this literal (never weaken the literal), then re-run.

- [ ] **Run the rest of the pack suite + compile — expected PASS** (no regression from the record-shape change):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.pack.*'")`
  Expected: `BUILD SUCCESSFUL`. If a pack-shape assertion fails, it is because a test hand-constructs `new DomainPack(...)` positionally — there is exactly one such site repo-wide (in `DomainPackLoader`, already updated). Then confirm the whole tree compiles:
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew compileJava compileTestJava")`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/pack/DomainPack.java src/main/java/com/msfg/rag/pack/DomainPackLoader.java src/test/java/com/msfg/rag/pack/MsfgGoldenPackTest.java
git commit -m "feat(guides): optional page-guides pack component (12th) + loader read, golden-locked

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: First-boot seeder

**Files:**
- Test: `src/test/java/com/msfg/rag/seed/PageGuideSeederTest.java`
- Create: `src/main/java/com/msfg/rag/seed/PageGuideSeeder.java`

An `ApplicationRunner` bean mirroring `SourceLinkSeeder`: IF `brain_page_guides` is empty (`repo.count() == 0`) AND the `DomainPack` has page guides, insert them. Idempotent — only seeds when count is zero. No pack file → `DomainPack.pageGuides()` is empty → nothing seeded → empty table → today's behavior. Converts `DomainPack.PageGuide` (String surface) → entity via `valueOf`; maps `DomainPack.InternalLink` → `LinkRef`; seeds `sourceLinkIds` as an empty list (pack seeds never carry ids). Wrapped `@Transactional`. The seeder's branch logic (seed-when-empty / skip-when-non-empty / skip-when-pack-empty and the String→enum `valueOf` + route conversion) is locked by a pure-Mockito unit test below — NO Spring, NO Docker, NO Testcontainers (both ctor args, `BrainPageGuideRepository` and `DomainPack`, are mockable). The live wiring is additionally exercised by Task 15's boot.

- [ ] **Write the failing test** (`PageGuideSeederTest.java`) — pure Mockito, same package `com.msfg.rag.seed` for package-private access. Mocks the repository and `DomainPack` (stubbing only `pack.pageGuides()`); captures the saved entity to assert the `valueOf` enum mapping + route + the internal-link mapping:

```java
package com.msfg.rag.seed;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.LinkRef;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.repository.BrainPageGuideRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageGuideSeederTest {

    private final BrainPageGuideRepository repository = mock(BrainPageGuideRepository.class);
    private final DomainPack pack = mock(DomainPack.class);
    private final PageGuideSeeder seeder = new PageGuideSeeder(repository, pack);

    private DomainPack.PageGuide seed() {
        return new DomainPack.PageGuide(
                "/loans/fha", "FHA Loans", "Help users understand FHA.", "BOTH",
                List.of("understand fha"), List.of("explain the 3.5% down payment"),
                List.of(new DomainPack.InternalLink("Apply", "/apply?program=fha")),
                List.of("fha"));
    }

    @Test
    void seedsWhenEmptyAndPackHasGuides() {
        when(pack.pageGuides()).thenReturn(List.of(seed()));
        when(repository.count()).thenReturn(0L);

        seeder.run(null);

        ArgumentCaptor<BrainPageGuide> captor = ArgumentCaptor.forClass(BrainPageGuide.class);
        verify(repository).save(captor.capture());
        BrainPageGuide saved = captor.getValue();
        assertEquals("FHA Loans", saved.getTitle());
        assertEquals("/loans/fha", saved.getRoute());                 // route preserved
        assertEquals(Surface.BOTH, saved.getSurface());               // String -> enum via valueOf
        assertEquals(List.of(new LinkRef("Apply", "/apply?program=fha")), saved.getInternalLinks());
        assertTrue(saved.getSourceLinkIds().isEmpty());               // pack seeds carry no ids
    }

    @Test
    void skipsWhenTableNonEmpty() {
        when(pack.pageGuides()).thenReturn(List.of(seed()));
        when(repository.count()).thenReturn(1L);

        seeder.run(null);

        verify(repository, never()).save(any());
    }

    @Test
    void skipsWhenPackEmpty() {
        when(pack.pageGuides()).thenReturn(List.of());

        seeder.run(null);

        verify(repository, never()).save(any());
    }
}
```

- [ ] **Run it — expected FAIL:** `PageGuideSeeder` does not exist yet → compile error.
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests '*PageGuideSeederTest'")`
  Expected: compile error — `cannot find symbol: class PageGuideSeeder` (test does not run).

- [ ] **Write `PageGuideSeeder.java`:**

```java
package com.msfg.rag.seed;

import com.msfg.rag.domain.BrainPageGuide;
import com.msfg.rag.domain.LinkRef;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.repository.BrainPageGuideRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds brain_page_guides from the optional pack file on first boot, once, only
 * when the table is empty. No pack file -> DomainPack.pageGuides() is empty ->
 * nothing is seeded -> the table stays empty and the system behaves exactly as
 * before this feature existed. Idempotent: a non-empty table is left untouched.
 * Pack seeds carry NO source-link ids (links are attached later via the dashboard).
 */
@Component
public class PageGuideSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PageGuideSeeder.class);
    private static final String SEEDED_BY = "pack-seed";

    private final BrainPageGuideRepository repository;
    private final DomainPack pack;

    public PageGuideSeeder(BrainPageGuideRepository repository, DomainPack pack) {
        this.repository = repository;
        this.pack = pack;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (pack.pageGuides().isEmpty()) {
            return;
        }
        if (repository.count() > 0) {
            return;
        }
        for (DomainPack.PageGuide seed : pack.pageGuides()) {
            repository.save(new BrainPageGuide(
                    seed.route(),
                    seed.title(),
                    seed.purpose(),
                    Surface.valueOf(seed.surface()),
                    seed.userIntents(),
                    seed.allowedGuidance(),
                    seed.internalLinks().stream()
                            .map(l -> new LinkRef(l.label(), l.url()))
                            .toList(),
                    List.of(),
                    seed.topics(),
                    SEEDED_BY));
        }
        log.info("Seeded {} page guide(s) from pack into brain_page_guides", pack.pageGuides().size());
    }
}
```

- [ ] **Run the seeder test — expected PASS** (pure Mockito; no Docker needed):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests '*PageGuideSeederTest'")`
  Expected: `BUILD SUCCESSFUL`, 3 tests passing.

- [ ] **Verify compile + pack suite still green** (V9 correctness is auto-verified by the Task 4 `@DataJpaTest` repo test, which boots Flyway V1–V9 against Testcontainers; the seeder's first-boot Postgres path is covered only by the Task 15 manual smoke — identical to the merged Phase 3 `SourceLinkSeeder`, which also has no integration test):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew compileJava && ./gradlew test --tests 'com.msfg.rag.pack.*'")`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/seed/PageGuideSeeder.java src/test/java/com/msfg/rag/seed/PageGuideSeederTest.java
git commit -m "feat(guides): first-boot seeder for brain_page_guides + seeder unit test

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: Dashboard types

**Files:**
- Modify: `dashboard/src/types.ts`

Add `LinkRef` (`{label,url}`), `PageGuideDto` (matches the backend response wire shape — snake_case keys for `user_intents`/`allowed_guidance`/`internal_links`/`source_link_ids`/`created_at`/`created_by`/`updated_at`/`updated_by`; camelCase for the rest as serialized; `internal_links` is `LinkRef[]`; `source_link_ids` is `string[]`), and `PageGuideRequest` (the create/update body — omits id/active/timestamps; `internalLinks`/`userIntents`/`allowedGuidance`/`sourceLinkIds`/`topics` are camelCase as the backend record field names bind on input; `route` is nullable). Mirror the existing `SourceLinkDto`/`SourceLinkRequest` split style.

> Note on JSON key casing (identical asymmetry to source links): the response DTO (`PageGuideDto.java`) uses `@JsonProperty` snake_case on the multi-word fields, so the dashboard interface uses those snake_case keys on **read**. The request record (`PageGuideRequest.java`) has no `@JsonProperty`, so Jackson binds its camelCase Java names on **write** — the dashboard sends camelCase (`userIntents`, `allowedGuidance`, `internalLinks`, `sourceLinkIds`, `topics`). The two shapes are intentionally different; keep them distinct.

- [ ] **Append to `types.ts`:**

```typescript
export interface LinkRef {
  label: string;
  url: string;
}

export interface PageGuideDto {
  id: string;
  route: string | null;
  title: string;
  purpose: string;
  surface: string;
  user_intents: string[];
  allowed_guidance: string[];
  internal_links: LinkRef[];
  source_link_ids: string[];
  topics: string[];
  active: boolean;
  created_at: string | null;
  created_by: string | null;
  updated_at: string | null;
  updated_by: string | null;
}

export interface PageGuideRequest {
  route: string | null;
  title: string;
  purpose: string;
  surface: string;
  userIntents: string[];
  allowedGuidance: string[];
  internalLinks: LinkRef[];
  sourceLinkIds: string[];
  topics: string[];
}
```

- [ ] **Verify the dashboard type-checks AND builds** (`npm run build` is Vite/esbuild and does NOT type-check, so run `npm run check` (`tsc --noEmit`, per `dashboard/package.json`) alongside it — a snake_case-read / camelCase-write field-name typo only fails the `tsc` gate):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"cd dashboard && npm run check && npm run build")`
  Expected: `tsc --noEmit` exits 0 with zero diagnostics; build exits 0. (`noUnusedLocals` is ON in `dashboard/tsconfig.json` — any unused import/local is a tsc ERROR; `npm run check` AND `npm run build` must both exit 0 with zero diagnostics.)

- [ ] **Commit:**
```
git add dashboard/src/types.ts
git commit -m "feat(guides): dashboard types for page guides

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: `PageGuides.tsx` dashboard screen

**Files:**
- Create: `dashboard/src/screens/PageGuides.tsx`

Mirrors `SourceLinks.tsx` row-CRUD: a `tbl` table with `Pill`/`row-actions`, single-string `busy` state, a collapsible create form, a per-row edit MODAL (`.card` inside `.modal-overlay` with `onClick={()=>setEditing(null)}` on the overlay + `onClick={e=>e.stopPropagation()}` on the inner box), delete-with-confirm, activate/deactivate. Reuses the existing `api` methods (`get`/`post`/`patch`/`del`) and existing `Pill` tones only. Endpoints under `/api/ai/admin/page-guides`. No `onCorpusChanged` prop — only `reload()` after mutations.

Page-guide-specific form details:
- `route` is optional text: round-trips with `value={x ?? ""}` + `onChange` writing `e.target.value || null`.
- `title` (required) + `purpose` (required `<textarea>`).
- `surface` `<select>` over `["BOTH","PUBLIC","INTERNAL"]`.
- `user_intents` / `allowed_guidance` / `topics` edited as comma-separated text (split/join on the boundary, same `toList` helper as `SourceLinks.tsx`).
- `internal_links` is a small **add/remove repeater** of `{label,url}` rows (a `LinkRef[]` in state; "Add link" button appends an empty row, each row has a remove button; blank rows are filtered out on submit).
- `source_link_ids` is a **multi-select** populated by fetching the source-links list (`GET /api/ai/admin/source-links`) on screen load: each `<option>` shows the link name and stores its id; selected ids round-trip as `sourceLinkIds`.

A shared, reusable `GuideFormFields` sub-component renders the create form body and the edit modal body so the repeater + multi-select logic is written once. `availableLinks` (the fetched source links) is passed down so the multi-select can label options.

- [ ] **Write `PageGuides.tsx`:**

```tsx
import React, { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import { LinkRef, PageGuideDto, PageGuideRequest, SourceLinkDto } from "../types";
import { ErrorNote, Pill } from "../components";

const SURFACES = ["BOTH", "PUBLIC", "INTERNAL"];

function toList(text: string): string[] {
  return text.split(",").map((s) => s.trim()).filter((s) => s.length > 0);
}

function cleanLinks(links: LinkRef[]): LinkRef[] {
  return links
    .map((l) => ({ label: l.label.trim(), url: l.url.trim() }))
    .filter((l) => l.label.length > 0 || l.url.length > 0);
}

function emptyForm(): PageGuideRequest {
  return {
    route: null,
    title: "",
    purpose: "",
    surface: "BOTH",
    userIntents: [],
    allowedGuidance: [],
    internalLinks: [],
    sourceLinkIds: [],
    topics: [],
  };
}

interface FormState {
  form: PageGuideRequest;
  setForm: (f: PageGuideRequest) => void;
  intents: string;
  setIntents: (s: string) => void;
  guidance: string;
  setGuidance: (s: string) => void;
  topics: string;
  setTopics: (s: string) => void;
  links: LinkRef[];
  setLinks: (l: LinkRef[]) => void;
  availableLinks: SourceLinkDto[];
}

function GuideFormFields(s: FormState) {
  return (
    <>
      <input placeholder="Route (optional, e.g. /loans/fha)" value={s.form.route ?? ""}
             onChange={(e) => s.setForm({ ...s.form, route: e.target.value || null })} />
      <input placeholder="Title (e.g. FHA Loans)" value={s.form.title}
             onChange={(e) => s.setForm({ ...s.form, title: e.target.value })} required />
      <textarea placeholder="Purpose (what this page/guide is for)" value={s.form.purpose}
                onChange={(e) => s.setForm({ ...s.form, purpose: e.target.value })} required rows={3} />
      <select value={s.form.surface}
              onChange={(e) => s.setForm({ ...s.form, surface: e.target.value })}>
        {SURFACES.map((x) => <option key={x} value={x}>{x.toLowerCase()}</option>)}
      </select>
      <input placeholder="User intents (comma-separated)" value={s.intents}
             onChange={(e) => s.setIntents(e.target.value)} />
      <input placeholder="Allowed guidance (comma-separated)" value={s.guidance}
             onChange={(e) => s.setGuidance(e.target.value)} />
      <input placeholder="Topics (comma-separated)" value={s.topics}
             onChange={(e) => s.setTopics(e.target.value)} />

      <div style={{ display: "grid", gap: 6 }}>
        <strong style={{ fontSize: 13 }}>Internal links</strong>
        {s.links.map((l, i) => (
          <div key={i} style={{ display: "flex", gap: 6 }}>
            <input placeholder="Label" value={l.label}
                   onChange={(e) => {
                     const next = [...s.links];
                     next[i] = { ...next[i], label: e.target.value };
                     s.setLinks(next);
                   }} />
            <input placeholder="URL (e.g. /apply)" value={l.url}
                   onChange={(e) => {
                     const next = [...s.links];
                     next[i] = { ...next[i], url: e.target.value };
                     s.setLinks(next);
                   }} />
            <button type="button" className="danger"
                    onClick={() => s.setLinks(s.links.filter((_, j) => j !== i))}>
              Remove
            </button>
          </div>
        ))}
        <button type="button" onClick={() => s.setLinks([...s.links, { label: "", url: "" }])}>
          Add link
        </button>
      </div>

      <div style={{ display: "grid", gap: 6 }}>
        <strong style={{ fontSize: 13 }}>Source links (registry)</strong>
        <select multiple value={s.form.sourceLinkIds} style={{ minHeight: 96 }}
                onChange={(e) =>
                  s.setForm({
                    ...s.form,
                    sourceLinkIds: Array.from(e.target.selectedOptions, (o) => o.value),
                  })}>
          {s.availableLinks.map((sl) => (
            <option key={sl.id} value={sl.id}>{sl.name}</option>
          ))}
        </select>
      </div>
    </>
  );
}

export default function PageGuides() {
  const [guides, setGuides] = useState<PageGuideDto[]>([]);
  const [availableLinks, setAvailableLinks] = useState<SourceLinkDto[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // create form
  const [showAdd, setShowAdd] = useState(false);
  const [addBusy, setAddBusy] = useState(false);
  const [addForm, setAddForm] = useState<PageGuideRequest>(emptyForm());
  const [addIntents, setAddIntents] = useState("");
  const [addGuidance, setAddGuidance] = useState("");
  const [addTopics, setAddTopics] = useState("");
  const [addLinks, setAddLinks] = useState<LinkRef[]>([]);

  // edit modal
  const [editing, setEditing] = useState<PageGuideDto | null>(null);
  const [editBusy, setEditBusy] = useState(false);
  const [editForm, setEditForm] = useState<PageGuideRequest>(emptyForm());
  const [editIntents, setEditIntents] = useState("");
  const [editGuidance, setEditGuidance] = useState("");
  const [editTopics, setEditTopics] = useState("");
  const [editLinks, setEditLinks] = useState<LinkRef[]>([]);

  const reload = useCallback(() => {
    api.get<PageGuideDto[]>("/api/ai/admin/page-guides")
      .then(setGuides)
      .catch((e) => setError((e as Error).message));
  }, []);

  useEffect(() => {
    reload();
    api.get<SourceLinkDto[]>("/api/ai/admin/source-links")
      .then(setAvailableLinks)
      .catch((e) => setError((e as Error).message));
  }, [reload]);

  async function submitAdd(e: React.FormEvent) {
    e.preventDefault();
    if (!addForm.title.trim() || !addForm.purpose.trim()) return;
    setAddBusy(true);
    setError(null);
    try {
      const body: PageGuideRequest = {
        ...addForm,
        title: addForm.title.trim(),
        purpose: addForm.purpose.trim(),
        userIntents: toList(addIntents),
        allowedGuidance: toList(addGuidance),
        topics: toList(addTopics),
        internalLinks: cleanLinks(addLinks),
      };
      await api.post("/api/ai/admin/page-guides", body);
      setShowAdd(false);
      setAddForm(emptyForm());
      setAddIntents(""); setAddGuidance(""); setAddTopics(""); setAddLinks([]);
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setAddBusy(false);
    }
  }

  function openEdit(g: PageGuideDto) {
    setEditing(g);
    setEditForm({
      route: g.route,
      title: g.title,
      purpose: g.purpose,
      surface: g.surface,
      userIntents: g.user_intents,
      allowedGuidance: g.allowed_guidance,
      internalLinks: g.internal_links,
      sourceLinkIds: g.source_link_ids,
      topics: g.topics,
    });
    setEditIntents(g.user_intents.join(", "));
    setEditGuidance(g.allowed_guidance.join(", "));
    setEditTopics(g.topics.join(", "));
    setEditLinks(g.internal_links.map((l) => ({ ...l })));
  }

  async function submitEdit(e: React.FormEvent) {
    e.preventDefault();
    if (!editing || !editForm.title.trim() || !editForm.purpose.trim()) return;
    setEditBusy(true);
    setError(null);
    try {
      const body: PageGuideRequest = {
        ...editForm,
        title: editForm.title.trim(),
        purpose: editForm.purpose.trim(),
        userIntents: toList(editIntents),
        allowedGuidance: toList(editGuidance),
        topics: toList(editTopics),
        internalLinks: cleanLinks(editLinks),
      };
      await api.patch(`/api/ai/admin/page-guides/${editing.id}`, body);
      setEditing(null);
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setEditBusy(false);
    }
  }

  async function remove(g: PageGuideDto) {
    if (!window.confirm(`Delete "${g.title}"? This permanently removes the page guide. This cannot be undone.`)) {
      return;
    }
    setBusy(g.id);
    setError(null);
    try {
      await api.del(`/api/ai/admin/page-guides/${g.id}`);
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  async function setActive(g: PageGuideDto, active: boolean) {
    setError(null);
    try {
      await api.post(`/api/ai/admin/page-guides/${g.id}/${active ? "activate" : "deactivate"}`);
      reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <header className="screen-head">
        <h1>Page guides</h1>
        <div className="actions">
          <button onClick={() => setShowAdd((v) => !v)} disabled={busy !== null}>
            {showAdd ? "Cancel" : "Add page guide"}
          </button>
        </div>
      </header>
      <ErrorNote message={error} />
      {showAdd && (
        <form className="card" onSubmit={submitAdd} style={{ display: "grid", gap: 8, marginBottom: 12 }}>
          <GuideFormFields
            form={addForm} setForm={setAddForm}
            intents={addIntents} setIntents={setAddIntents}
            guidance={addGuidance} setGuidance={setAddGuidance}
            topics={addTopics} setTopics={setAddTopics}
            links={addLinks} setLinks={setAddLinks}
            availableLinks={availableLinks} />
          <button className="btn-primary" type="submit"
                  disabled={addBusy || !addForm.title.trim() || !addForm.purpose.trim()}>
            {addBusy ? "Saving…" : "Create page guide"}
          </button>
        </form>
      )}
      <table className="tbl">
        <thead>
          <tr><th>Title</th><th>Route</th><th>Surface</th><th>Status</th><th></th></tr>
        </thead>
        <tbody>
          {guides.map((g) => (
            <tr key={g.id}>
              <td title={g.purpose}>{g.title}</td>
              <td>{g.route ?? <span className="muted">topic-matched</span>}</td>
              <td><Pill tone="gray">{g.surface.toLowerCase()}</Pill></td>
              <td><Pill tone={g.active ? "green" : "gray"}>{g.active ? "active" : "inactive"}</Pill></td>
              <td className="row-actions">
                <button onClick={() => openEdit(g)}>Edit</button>
                <button onClick={() => setActive(g, !g.active)}>
                  {g.active ? "Deactivate" : "Activate"}
                </button>
                <button className="danger" onClick={() => remove(g)} disabled={busy === g.id}>
                  Delete
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {editing && (
        <div className="modal-overlay" onClick={() => setEditing(null)}>
          <form className="card" onClick={(e) => e.stopPropagation()} onSubmit={submitEdit}
                style={{ display: "grid", gap: 8, maxWidth: 520, margin: "8vh auto" }}>
            <h3 style={{ margin: 0 }}>Edit page guide</h3>
            <GuideFormFields
              form={editForm} setForm={setEditForm}
              intents={editIntents} setIntents={setEditIntents}
              guidance={editGuidance} setGuidance={setEditGuidance}
              topics={editTopics} setTopics={setEditTopics}
              links={editLinks} setLinks={setEditLinks}
              availableLinks={availableLinks} />
            <div style={{ display: "flex", gap: 8 }}>
              <button className="btn-primary" type="submit"
                      disabled={editBusy || !editForm.title.trim() || !editForm.purpose.trim()}>
                {editBusy ? "Saving…" : "Save"}
              </button>
              <button type="button" onClick={() => setEditing(null)}>Cancel</button>
            </div>
          </form>
        </div>
      )}
    </>
  );
}
```

- [ ] **Verify the screen type-checks AND builds** (screen not yet routed, but must type-check — `npm run build` is Vite/esbuild and does NOT type-check, so run `npm run check` (`tsc --noEmit`, per `dashboard/package.json`) alongside it to catch a snake_case-read / camelCase-write field-name typo):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"cd dashboard && npm run check && npm run build")`
  Expected: `tsc --noEmit` exits 0 with zero diagnostics; build exits 0. (`noUnusedLocals` is ON in `dashboard/tsconfig.json` — any unused import/local is a tsc ERROR; `npm run check` AND `npm run build` must both exit 0 with zero diagnostics.)

- [ ] **Commit:**
```
git add dashboard/src/screens/PageGuides.tsx
git commit -m "feat(guides): PageGuides dashboard screen

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: Register the screen in `App.tsx`

**Files:**
- Modify: `dashboard/src/App.tsx`

Three edits: import, `<NavLink>`, `<Route>`. Place "Page guides" right after "Source links" (knowledge-adjacent). HashRouter (already used). No props for this screen.

- [ ] **Edit 1 — import.** After `import SourceLinks from "./screens/SourceLinks";` add:

```tsx
import PageGuides from "./screens/PageGuides";
```

- [ ] **Edit 2 — NavLink.** Inside `<nav className="nav">`, after `<NavLink to="/source-links">Source links</NavLink>` add:

```tsx
            <NavLink to="/page-guides">Page guides</NavLink>
```

- [ ] **Edit 3 — Route.** Inside `<Routes>`, after `<Route path="/source-links" element={<SourceLinks />} />` add:

```tsx
            <Route path="/page-guides" element={<PageGuides />} />
```

- [ ] **Verify type-check + build + dashboard tests** (`npm run build` is Vite/esbuild and does NOT type-check, so run `npm run check` (`tsc --noEmit`, per `dashboard/package.json`) alongside it):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"cd dashboard && npm run check && npm run build && npx vitest run")`
  Expected: `tsc --noEmit` exits 0 with zero diagnostics; build exits 0; `vitest run` passes. (`noUnusedLocals` is ON in `dashboard/tsconfig.json` — any unused import/local is a tsc ERROR; `npm run check` AND `npm run build` must both exit 0 with zero diagnostics.)

- [ ] **Commit:**
```
git add dashboard/src/App.tsx
git commit -m "feat(guides): register Page guides screen in dashboard nav

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 14: Backend full suite + golden no-regression checkpoint

**Files:** none (verification only).

Confirm the whole backend suite is green BEFORE the final dashboard/manual gate. This isolates any backend regression (the `DomainPack` 12th-component shift, the seeder wiring, the golden lock) from dashboard noise.

- [ ] **Backend full suite:**
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test")`
  Expected: `BUILD SUCCESSFUL`. The new tests (`BrainPageGuideRepositoryTest`, `PageGuideServiceTest`, `AdminPageGuideControllerTest`, `MsfgGoldenPackTest.pageGuidesMatchSeed`, `PageGuideSeederTest`) all pass; no existing test regresses — in particular `MsfgGoldenPackTest.sourceLinksMatchSeed` and all hard-locked counts (classifier rules = 5, acronyms = 30, programs = 4, source links = 5) stay intact.

- [ ] **No commit** (verification only).

---

## Task 15: Final dashboard + backward-compat verification

**Files:** none (verification only).

The hard backward-compatibility gate. Confirm the dashboard builds and tests pass, and — manually — that a fresh boot with the pack file seeds 3 guides while removing the pack file leaves the table empty (today's behavior).

- [ ] **Dashboard type-check + build + tests** (`npm run build` is Vite/esbuild and does NOT type-check, so run `npm run check` (`tsc --noEmit`, per `dashboard/package.json`) alongside it):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"cd dashboard && npm run check && npm run build && npx vitest run")`
  Expected: `tsc --noEmit` exits 0; build succeeds; vitest passes.

- [ ] **Backward-compat smoke (manual, optional but recommended).** Boot the brain on :8090 from this branch (`~/MSFG/msfg-rag/start.sh`, NEVER port 8080). With `packs/msfg-mortgage/page-guides.yaml` present and an empty table, the seeder logs `Seeded 3 page guide(s)...`; `GET /api/ai/admin/page-guides` (with `X-Admin-Api-Key`) returns 3 active guides, each with `source_link_ids: []`. In the dashboard Page Guides screen, the source-link multi-select is populated from the 5 seeded source links; selecting some + saving persists them as `source_link_ids` (verify the PATCH round-trips). Temporarily renaming the pack file away and pointing at a fresh DB → empty table, no seeding, `GET` returns `[]` — identical to pre-feature behavior. The ask pipeline (`/api/ai/mortgage/ask`) is unchanged in both cases.

- [ ] **Confirm branch is mergeable** (do NOT merge here — that is the user's call):
  `git log --oneline main..feat/phase4-page-guides` shows the feature commits; `git merge-base --is-ancestor main feat/phase4-page-guides` exits 0.

- [ ] **Final commit (if any verification-only artifacts changed — normally none):** skip if nothing changed.

---

## Self-Review

**Spec coverage (spec §6.1; handoff §4–§8; locked decisions):**
- Table `brain_page_guides` with exact §6.1 columns (`route` NULL, `title`, `purpose`, `surface`, `user_intents`, `allowed_guidance`, `internal_links`, `source_link_ids`, `topics`, `active`, `created_at/by`, `updated_at/by`) — Task 1. ✓
- `embedding vector` column OMITTED (spec lists it optional; deferred) — Task 1. ✓ (explicitly noted in DDL comment + backward-compat contract.)
- `id UUID PRIMARY KEY` with NO `DEFAULT gen_random_uuid()` matching V8's brain_* convention (entity supplies UUID via `@GeneratedValue`) — Task 1. ✓
- jsonb via `@JdbcTypeCode(SqlTypes.JSON)` on typed `List<String>`/`List<LinkRef>`/`List<UUID>`; NOT-NULL jsonb initialized in Java + `DEFAULT '[]'::jsonb` in DDL — Tasks 1, 3. ✓ (Handoff §5's "String JSON + ObjectMapper" claim is contradicted by the merged Phase 3 code + `AuditLog` + the locked decision; the plan follows the locked decision/Phase-3 template. The `List<LinkRef>` + `List<UUID>` round-trip is explicitly asserted in the Task 4 repo test.)
- Reuses existing `Surface` enum — no new enum — Tasks 3, 5, 6. ✓
- `LinkRef(String label, String url)` value record for `internal_links` — Task 2; round-trip asserted in Task 4. ✓
- `source_link_ids` as plain stored `List<UUID>` (NOT a relational FK), accepted as `List<String>` in the request and converted via `UUID.fromString` (bad → `IllegalArgumentException` → 400) — Tasks 1, 3, 5, 6 (test `createRejectsMalformedSourceLinkId`). ✓
- Mutable entity mirroring `BrainSourceLink` (protected + public ctors; `created_at` `updatable=false` getter-only; `created_by` getter-only; defensive copies; NOT-NULL lists init to `new ArrayList<>()`) — Task 3. ✓
- Repository with `findAllByOrderByCreatedAtDescIdDesc`, `findByActiveTrueOrderByCreatedAtDescIdDesc`, `countByActiveTrue`, `@Repository`, derived finders bind to `active` — Task 4. ✓
- Service: full CRUD + cached `activePageGuides()` mirroring `SourceLinkService` EXACTLY (10s TTL, `Long.MIN_VALUE` sentinel checked before subtraction, volatile, `invalidate()` on every write), `findById().orElseThrow(...)`, `.strip()`, `isBlank()` rejection of blank title/purpose, optional route, `@Transactional delete` (hard), `valueOf` for surface, `UUID.fromString` for ids — Task 6. `UPDATED_BY="admin-api"` threaded from controller. ✓
- DTOs: `PageGuideDto` (snake_case `@JsonProperty`, `active`, `internal_links` as `[{label,url}]`, `source_link_ids` as string UUIDs, `from()` factory) + `PageGuideRequest` (String surface, `List<String>` sourceLinkIds, nested `LinkRefRequest`, manual validation, no `@Valid`) — Task 5. ✓
- Controller `/api/ai/admin/page-guides`, all verbs mirroring `AdminSourceLinkController`, shared setActive path, `UPDATED_BY="admin-api"`, `IllegalArgumentException` → existing global 400, no try/catch, no `@Valid` — Task 7. ✓
- Pack: `page-guides.yaml` real seed (FHA / conventional / duplex-2-unit), kebab-case keys 1:1 with `PageGuideFile`/`InternalLinkFile`, surface name exact, `source-link-ids` seeded EMPTY (limitation documented) — Task 8. ✓
- Loader: reuses existing `readOptional()`, adds `PageGuidesFile`/`PageGuideFile`/`InternalLinkFile`, 12th positional `DomainPack` component shift, compact-ctor default `List.of()` (not null-preserving), no `require(...)` non-empty, both Javadocs updated, the single positional `new DomainPack(` call site updated — Task 9. ✓
- Golden test: new `pageGuidesMatchSeed()` literal written FIRST (red→green: fails on the loader's temporary empty `List.of()`, greens once the loader reads the YAML); existing `sourceLinksMatchSeed` + all hard-locked counts confirmed intact — Tasks 9, 14. ✓
- First-boot seeder: `ApplicationRunner`, seeds only when `count()==0` AND pack has guides, idempotent, empty pack → no seed → today's behavior; maps `InternalLink`→`LinkRef`, seeds `sourceLinkIds` empty; locked by a pure-Mockito `PageGuideSeederTest` (failing-test-first, surface/route conversion via `ArgumentCaptor`, skip-when-nonempty, skip-when-pack-empty) — Task 10. ✓
- Dashboard: `PageGuides.tsx` (SourceLinks row-CRUD: tbl/Pill/row-actions/busy/collapsible create/edit modal with overlay+stopPropagation/delete-confirm/activate-deactivate), reuses existing `api` methods + `Pill` tones only, nullable `route` round-trip, internal-links `{label,url}` repeater, source-link multi-select populated from `GET /api/ai/admin/source-links`, no `onCorpusChanged` — Task 12. Types (`LinkRef`/`PageGuideDto`/`PageGuideRequest`) — Task 11. App.tsx three edits — Task 13. Every dashboard verify gates on `npm run check` (tsc --noEmit) alongside `npm run build` + `npx vitest run`. ✓
- No-regression gate (backend full suite + golden + dashboard) — Tasks 14, 15; backward-compat contract stated up top and re-verified. ✓

**Placeholder scan:** Every code step shows complete, copy-paste-ready code. No "TBD" / "add validation" / "similar to Task N" / "handle edge cases" placeholders. Exact gradle/npm commands with expected output on every step. ✓

**Type-name consistency across tasks:** `BrainPageGuide`, `LinkRef`, `Surface` (reused), `BrainPageGuideRepository`, `PageGuideService`, `PageGuideDto`, `PageGuideRequest` (+ nested `LinkRefRequest`), `AdminPageGuideController`, `PageGuideSeeder`, `DomainPack.PageGuide`, `DomainPack.InternalLink`, `PageGuidesFile`/`PageGuideFile`/`InternalLinkFile` — used identically in every task. Entity ctor arg order (`route, title, purpose, surface, userIntents, allowedGuidance, internalLinks, sourceLinkIds, topics, createdBy`) matches across entity, repo test, service, service test, seeder. DTO field order matches across DTO, controller test, dashboard interface. Endpoint base `/api/ai/admin/page-guides` identical in controller, controller test, and dashboard. Surface names `PUBLIC/INTERNAL/BOTH` identical in YAML, golden test, dashboard. ✓

**Judgment calls flagged (for the author's review):**
1. **`List<LinkRef>` jsonb round-trip** — handoff §5 suggested String-JSON + ObjectMapper, but the merged Phase 3 code and `AuditLog.retrievedContext` (`List<Map<String,Object>>`) both use `@JdbcTypeCode(SqlTypes.JSON)` on typed fields, which Hibernate's Jackson serializer handles for a List of records too. The plan follows the Phase 3 template and explicitly **asserts the round-trip in the Task 4 repo test** (2 internal links + assert equal) rather than trusting it. If Jackson cannot deserialize the `LinkRef` record (e.g. a missing default ctor concern), the Task 4 test is the gate that catches it; records deserialize fine with Jackson's parameter-name/`@JsonCreator`-free constructor binding on the Spring Boot classpath, but this is the one spot worth watching at execution time.
2. **`source_link_ids` handling** — modeled as plain `List<UUID>` (no JPA association, no DB FK) per the locked decision + spec D5; the request takes `List<String>` and converts with `UUID.fromString` so a malformed id is a clean 400. **Pack seeds carry NO ids** (seeded source-link UUIDs are generated at seed time) — documented in the YAML header, the loader Javadoc, the seeder, and the golden test; ids are attached post-boot via the dashboard. Flagged because it is the one place the page-guide model is NOT a pure mirror of source links.
3. **Dashboard source-link multi-select** — uses a native `<select multiple>` populated by a second fetch (`GET /api/ai/admin/source-links`) on screen load, storing ids and labeling by `name`. A shared `GuideFormFields` sub-component renders both the create form and the edit modal so the repeater + multi-select are written once (Phase 3's `SourceLinks.tsx` duplicated its simpler fields inline; page guides have enough fields that a shared sub-component is cleaner — a minor, deliberate divergence from the literal Phase 3 structure, still using only existing CSS classes and `Pill` tones).
4. **3 seed guides vs Phase 3's 5 links** — chose 3 real guides (FHA / conventional / duplex-2-unit, the last tying into the borrower-vocabulary "duplex"/"2-unit" memory) to keep the golden literal tractable; the count is arbitrary and locked by `pageGuidesMatchSeed`.

**Spec §6.1 page-guide fields — coverage check (every field mapped to a task):**
`id` → Task 1/3 (✓), `route` (NULL) → 1/3/5/6 (✓), `title` → 1/3/5 (✓), `purpose` → 1/3/5 (✓), `surface` → 1/3/5/6 (✓), `user_intents` → 1/3/5 (✓), `allowed_guidance` → 1/3/5 (✓), `internal_links` `[{label,url}]` → 1/2/3/5 (✓), `source_link_ids` (UUID[]→links, by value) → 1/3/5/6 (✓), `topics` → 1/3/5 (✓), `active` → 1/3 (✓), `created_at/by, updated_at/by` → 1/3 (✓). **`embedding vector NULL`** — the ONLY §6.1 field NOT mapped to a task; intentionally deferred per the locked decision (spec marks it optional). No other §6.1 page-guide field is unmapped.
