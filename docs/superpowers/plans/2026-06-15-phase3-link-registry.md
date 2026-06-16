# Phase 3 — Link / Source Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a curatable Link/Source Registry (`brain_source_links`) — table, JPA entity, repository, cached CRUD service, admin REST CRUD, optional pack seed file with first-boot seeding, and a dashboard screen — with zero changes to the ask pipeline and exact backward compatibility (empty table + no pack file = today's behavior).

**Architecture:** A full-CRUD collection layer (spec §6.1, §6.5 collections pattern) mirroring the corpus document CRUD shape (`MortgageDocument`/`DocumentAdminController`) for the mutable entity + verbs, combined with the `VocabularyService` 10s-cache idiom (`Long.MIN_VALUE` sentinel checked *before* subtraction, `invalidate()` on every write) for a `activeLinks()` read snapshot that Phase 6 will later consume. jsonb list columns use Hibernate `@JdbcTypeCode(SqlTypes.JSON)` on typed `List<String>` fields (the repo's only jsonb idiom — there is no String-JSON/ObjectMapper convert pattern). Pack seeding is fully optional via a new `readOptional()` loader helper + a new positional `DomainPack` component defaulting to empty, plus a first-boot `ApplicationRunner` that seeds only when the table is empty.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring Data JPA + Hibernate, PostgreSQL + pgvector, Flyway (forward-only, next is V8), Testcontainers (`pgvector/pgvector:pg16`), JUnit 5 + Mockito, React 18 + Vite 5 + TS dashboard (HashRouter), Vitest. Build file: `build.gradle.kts` (no pom).

---

## Backward-compatibility contract (HARD RULE — verify every task preserves it)

- **Empty `brain_source_links` table + no `source-links.yaml` pack file = today's behavior, exactly.** The ask pipeline (`AskService`/`RetrievalService`/`PromptBuilderService`) is **NOT touched** in Phase 3.
- `activeLinks()` on `SourceLinkService` is the integration seam for Phase 6. It is read by **nothing** in Phase 3. Keep it; do not wire it anywhere.
- The new `DomainPack.sourceLinks()` component **defaults to `List.of()`** when the pack file is absent or its `links:` key is null/absent. No pack file → empty component → first-boot seeder seeds nothing → empty table.
- `DomainPackLoader.validate()` gets **NO** `require(... non-empty)` assertion for source links (that would make the optional file mandatory and break every pack that lacks it).
- The full backend suite (`./gradlew test`) + `MsfgGoldenPackTest` golden lock + the dashboard build/tests must stay green. Final task (14) is the explicit no-regression gate.

---

## File Structure

### Backend — created

| Path | Responsibility |
|---|---|
| `src/main/resources/db/migration/V8__create_brain_source_links.sql` | Flyway migration: `brain_source_links` table + indexes. |
| `src/main/java/com/msfg/rag/domain/LinkAuthority.java` | Plain enum `{ PRIMARY, SECONDARY, BACKGROUND }`. |
| `src/main/java/com/msfg/rag/domain/Surface.java` | Plain enum `{ PUBLIC, INTERNAL, BOTH }` (reused Phase 4/5). |
| `src/main/java/com/msfg/rag/domain/BrainSourceLink.java` | Mutable full-CRUD JPA entity; jsonb `List<String>` fields; created/updated audit. |
| `src/main/java/com/msfg/rag/repository/BrainSourceLinkRepository.java` | `JpaRepository<BrainSourceLink, UUID>` + derived finders. |
| `src/main/java/com/msfg/rag/dto/SourceLinkDto.java` | Response DTO (snake_case JSON keys) + `from(entity)` factory. |
| `src/main/java/com/msfg/rag/dto/SourceLinkRequest.java` | Create/update body; enum fields as `String`. |
| `src/main/java/com/msfg/rag/service/retrieval/SourceLinkService.java` | Cached CRUD service (CRUD + `activeLinks()` snapshot + `invalidate()`). |
| `src/main/java/com/msfg/rag/controller/AdminSourceLinkController.java` | Admin REST CRUD under `/api/ai/admin/source-links`. |
| `src/main/java/com/msfg/rag/seed/SourceLinkSeeder.java` | First-boot `ApplicationRunner`: seed from pack when table empty. |
| `packs/msfg-mortgage/source-links.yaml` | Optional pack seed: real mortgage authority links. |

### Backend — modified

| Path | Change |
|---|---|
| `src/main/java/com/msfg/rag/pack/DomainPack.java` | Add positional `List<SourceLink> sourceLinks` component (defaults to empty) + nested `SourceLink` record. Update Javadoc. |
| `src/main/java/com/msfg/rag/pack/DomainPackLoader.java` | Add `readOptional()` helper, `SourceLinksFile`/`SourceLinkFile` records, optional read in `load()`, append the mapped arg to `new DomainPack(...)`. |
| `src/test/java/com/msfg/rag/pack/MsfgGoldenPackTest.java` | Add `sourceLinksMatchSeed()` locking the seeded list literal. |

### Backend — tests created

| Path | Responsibility |
|---|---|
| `src/test/java/com/msfg/rag/repository/BrainSourceLinkRepositoryTest.java` | Testcontainers `@DataJpaTest` repo test. |
| `src/test/java/com/msfg/rag/service/retrieval/SourceLinkServiceTest.java` | Service CRUD + cache + invalidate (mocked repo). |
| `src/test/java/com/msfg/rag/controller/AdminSourceLinkControllerTest.java` | POJO controller test (Mockito `verify` + `assertThrows`). |
| `src/test/java/com/msfg/rag/seed/SourceLinkSeederTest.java` | Pure-Mockito seeder unit test (no Spring/Docker); mocks repo + `DomainPack`. |

### Dashboard — created / modified

| Path | Change |
|---|---|
| `dashboard/src/screens/SourceLinks.tsx` | New row-CRUD screen (table + create form + edit modal + delete-confirm + activate/deactivate). |
| `dashboard/src/types.ts` | Add `SourceLinkDto` + `SourceLinkRequest` interfaces. |
| `dashboard/src/App.tsx` | Register screen: import + `<NavLink>` + `<Route>`. |

---

## Conventions for every backend task

- **Test commands** run via the context-mode shell wrapper (raw `./gradlew` is redirected by a hook):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests '<FQN>'")`.
  Docker must be up for Testcontainers tests. Full suite: `./gradlew test`.
- **Controllers** throw `IllegalArgumentException` for ALL bad input / not-found. The existing `GlobalExceptionHandler` (`@RestControllerAdvice`) maps it to HTTP 400 `{"error": msg}`. Do **NOT** add try/catch or a per-controller `@ExceptionHandler`.
- **Controller tests are POJO** (`new Controller(mock)` + Mockito `verify`/`when` + `assertThrows`) — NOT MockMvc. Keep test classes in the **same package** as the class under test for package-private access.
- **Repo tests** mirror `VocabularyRevisionRepositoryTest` exactly: `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = Replace.NONE)` + `@Testcontainers` + `pgvector/pgvector:pg16`.
- **jsonb fields**: `@JdbcTypeCode(SqlTypes.JSON)` on typed `List<String>`; NOT-NULL jsonb initialized in Java (`= new ArrayList<>()`) AND `DEFAULT '[]'::jsonb` in DDL.
- **Commit** after each green task with the exact commands shown.

---

## Task 1: V8 migration — `brain_source_links` table

**Files:**
- Create: `src/main/resources/db/migration/V8__create_brain_source_links.sql`

This is pure SQL (Flyway). There is no isolated unit test for DDL; it is exercised by the repo test in Task 4 (which boots Flyway against a Testcontainers Postgres). The verification step here is a no-op build to confirm the file is syntactically loadable later. Convention notes (verified against V6/V7): newer `brain_*` tables use **`id UUID PRIMARY KEY` with NO `DEFAULT gen_random_uuid()`** (the JPA app supplies the UUID via `@GeneratedValue`), TIMESTAMPTZ audit columns, VARCHAR(100) `created_by`/`updated_by`, a leading header comment, and `idx_<tableNoun>_<cols>` index naming. jsonb NOT-NULL columns get `DEFAULT '[]'::jsonb`.

- [ ] **Write the migration file** (complete, copy-paste-ready):

```sql
-- Editable registry of external source/links the brain is allowed to cite or
-- surface (the trust layer, spec §6.1). Full-CRUD collection: create / list /
-- get / update / delete (hard) / activate / deactivate; one row per link.
-- Seeded from the optional pack file source-links.yaml on first boot
-- (idempotent — only when the table is empty). An empty table plus no pack file
-- reproduces today's behavior exactly: nothing in this table is read by the ask
-- pipeline in Phase 3.
CREATE TABLE brain_source_links (
    id                 UUID         PRIMARY KEY,
    name               VARCHAR(500) NOT NULL,
    url                VARCHAR(2000) NOT NULL,
    domain             VARCHAR(255),
    authority          VARCHAR(50)  NOT NULL,                  -- PRIMARY | SECONDARY | BACKGROUND
    topics             JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- string[]
    freshness_required BOOLEAN      NOT NULL DEFAULT FALSE,
    allowed_use        JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- string[]
    do_not_use_for     JSONB        NOT NULL DEFAULT '[]'::jsonb,   -- string[]
    surface            VARCHAR(50)  NOT NULL,                  -- PUBLIC | INTERNAL | BOTH
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL,
    created_by         VARCHAR(100) NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    updated_by         VARCHAR(100) NOT NULL
);
CREATE INDEX idx_source_links_active ON brain_source_links (is_active);
CREATE INDEX idx_source_links_created ON brain_source_links (created_at DESC, id DESC);
```

- [ ] **Verify it compiles into the build** (no test yet; just confirm the project still builds with the new resource present):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew compileJava")`
  Expected: `BUILD SUCCESSFUL`. (Flyway validation happens when the repo test boots in Task 4.)

- [ ] **Commit:**
```
git add src/main/resources/db/migration/V8__create_brain_source_links.sql
git commit -m "feat(links): V8 migration for brain_source_links table

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Enums — `LinkAuthority` + `Surface`

**Files:**
- Create: `src/main/java/com/msfg/rag/domain/LinkAuthority.java`
- Create: `src/main/java/com/msfg/rag/domain/Surface.java`

Plain Java enums, UPPER_SNAKE constants, no JPA annotations (mirrors `SourceType.java` style). These are tested transitively (entity + service + DTO tests use them); there is no standalone enum unit test in this repo, so verification is a compile.

- [ ] **Write `LinkAuthority.java`:**

```java
package com.msfg.rag.domain;

/**
 * Trust tier of an external source link (spec §6.1, §6.4). Stored as the bare
 * enum name (UPPER_SNAKE) via @Enumerated(EnumType.STRING). Only the three
 * external tiers live on the link row; company-rule and page-guide tiers come
 * from elsewhere (Phase 7).
 */
public enum LinkAuthority {
    PRIMARY,      // authoritative source: agency selling/servicing guides, HUD handbook
    SECONDARY,    // approved supporting source
    BACKGROUND    // general background / context only
}
```

- [ ] **Write `Surface.java`:**

```java
package com.msfg.rag.domain;

/**
 * Audience a record applies to (spec §6.1 D4). Reused by page guides in a later
 * phase. Stored as the bare enum name via @Enumerated(EnumType.STRING).
 */
public enum Surface {
    PUBLIC,     // borrower-facing only
    INTERNAL,   // staff-facing only
    BOTH        // both audiences
}
```

- [ ] **Verify compile:**
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew compileJava")`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/domain/LinkAuthority.java src/main/java/com/msfg/rag/domain/Surface.java
git commit -m "feat(links): LinkAuthority and Surface domain enums

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `BrainSourceLink` entity

**Files:**
- Create: `src/main/java/com/msfg/rag/domain/BrainSourceLink.java`

Mutable full-CRUD entity combining `MortgageDocument`'s shape (`@PrePersist`/`@PreUpdate` for `created_at`/`updated_at`, `is_active` column with `active` field + `isActive()`/`setActive()`, bare `@Id @GeneratedValue`) with `created_by`/`updated_by` VARCHAR(100) (mirroring `VocabularyRevision`/`RuleRevision`). jsonb `List<String>` fields use `@JdbcTypeCode(SqlTypes.JSON)` (mirrors `DocumentChunk.metadata`). `created_at` is `updatable=false` with getter only; `created_by` getter only. A `protected` no-arg ctor for JPA + a `public` ctor taking the editable fields + `createdBy`. The entity is exercised by the repo test (Task 4); compile-verify here.

- [ ] **Write `BrainSourceLink.java`:**

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
 * A curated external source/link the brain is allowed to cite or surface (the
 * trust layer, spec §6.1). Full-CRUD mutable row: editable name/url/domain/
 * authority/topics/freshness/allowed-use/do-not-use-for/surface plus a soft
 * is_active flag. created_at/created_by are immutable; updated_at/updated_by are
 * touched on every write. jsonb list columns are mapped on typed List&lt;String&gt;
 * fields via @JdbcTypeCode(SqlTypes.JSON) (the repo's only jsonb idiom).
 */
@Entity
@Table(name = "brain_source_links")
public class BrainSourceLink {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, length = 500)
    private String name;

    @Column(nullable = false, length = 2000)
    private String url;

    @Column(length = 255)
    private String domain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private LinkAuthority authority;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> topics = new ArrayList<>();

    @Column(name = "freshness_required", nullable = false)
    private boolean freshnessRequired = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_use", nullable = false, columnDefinition = "jsonb")
    private List<String> allowedUse = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "do_not_use_for", nullable = false, columnDefinition = "jsonb")
    private List<String> doNotUseFor = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Surface surface;

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

    protected BrainSourceLink() {
    }

    public BrainSourceLink(String name, String url, String domain, LinkAuthority authority,
                           List<String> topics, boolean freshnessRequired, List<String> allowedUse,
                           List<String> doNotUseFor, Surface surface, String createdBy) {
        this.name = name;
        this.url = url;
        this.domain = domain;
        this.authority = authority;
        this.topics = topics == null ? new ArrayList<>() : new ArrayList<>(topics);
        this.freshnessRequired = freshnessRequired;
        this.allowedUse = allowedUse == null ? new ArrayList<>() : new ArrayList<>(allowedUse);
        this.doNotUseFor = doNotUseFor == null ? new ArrayList<>() : new ArrayList<>(doNotUseFor);
        this.surface = surface;
        this.createdBy = createdBy;
        this.updatedBy = createdBy;
    }

    // --- getters / setters ---

    public UUID getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public LinkAuthority getAuthority() { return authority; }
    public void setAuthority(LinkAuthority authority) { this.authority = authority; }

    public List<String> getTopics() { return topics; }
    public void setTopics(List<String> topics) { this.topics = topics; }

    public boolean isFreshnessRequired() { return freshnessRequired; }
    public void setFreshnessRequired(boolean freshnessRequired) { this.freshnessRequired = freshnessRequired; }

    public List<String> getAllowedUse() { return allowedUse; }
    public void setAllowedUse(List<String> allowedUse) { this.allowedUse = allowedUse; }

    public List<String> getDoNotUseFor() { return doNotUseFor; }
    public void setDoNotUseFor(List<String> doNotUseFor) { this.doNotUseFor = doNotUseFor; }

    public Surface getSurface() { return surface; }
    public void setSurface(Surface surface) { this.surface = surface; }

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
git add src/main/java/com/msfg/rag/domain/BrainSourceLink.java
git commit -m "feat(links): BrainSourceLink mutable JPA entity

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Repository + Testcontainers repo test

**Files:**
- Create: `src/main/java/com/msfg/rag/repository/BrainSourceLinkRepository.java`
- Test: `src/test/java/com/msfg/rag/repository/BrainSourceLinkRepositoryTest.java`

Derived finders bind to the Java property `active` (NOT `isActive`). Include the deterministic-tiebreaker ordered finders and `countByActiveTrue()` (returns `long`, used by the seeder). Add `@Repository` (consistent with `BrainSettingRepository`).

- [ ] **Write the failing test** (`BrainSourceLinkRepositoryTest.java`). It constructs the entity via its public ctor, `saveAndFlush`es, and asserts via derived finders + jsonb round-trip:

```java
package com.msfg.rag.repository;

import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BrainSourceLinkRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired
    private BrainSourceLinkRepository repository;

    private BrainSourceLink link(String name, boolean active) {
        BrainSourceLink l = new BrainSourceLink(
                name, "https://example.com/" + name, "example.com", LinkAuthority.PRIMARY,
                List.of("conventional", "appraisal"), true,
                List.of("cite guidelines"), List.of("legal advice"),
                Surface.BOTH, "tester");
        l.setActive(active);
        return l;
    }

    @Test
    void savesAndReadsBackJsonbLists() {
        BrainSourceLink saved = repository.saveAndFlush(link("fannie", true));

        BrainSourceLink found = repository.findById(saved.getId()).orElseThrow();
        assertNotNull(found.getCreatedAt());
        assertNotNull(found.getUpdatedAt());
        assertEquals("tester", found.getCreatedBy());
        assertEquals(List.of("conventional", "appraisal"), found.getTopics());
        assertEquals(List.of("cite guidelines"), found.getAllowedUse());
        assertEquals(List.of("legal advice"), found.getDoNotUseFor());
        assertEquals(LinkAuthority.PRIMARY, found.getAuthority());
        assertEquals(Surface.BOTH, found.getSurface());
        assertTrue(found.isFreshnessRequired());
        assertTrue(found.isActive());
    }

    @Test
    void findByActiveTrueExcludesInactive() {
        repository.saveAndFlush(link("active-one", true));
        repository.saveAndFlush(link("inactive-one", false));

        List<BrainSourceLink> active = repository.findByActiveTrueOrderByCreatedAtDescIdDesc();
        assertEquals(1, active.size());
        assertEquals("active-one", active.get(0).getName());
    }

    @Test
    void countByActiveTrueCountsOnlyActive() {
        repository.saveAndFlush(link("a", true));
        repository.saveAndFlush(link("b", true));
        repository.saveAndFlush(link("c", false));

        assertEquals(2L, repository.countByActiveTrue());
    }

    @Test
    void findAllOrderedReturnsNewestFirst() {
        repository.saveAndFlush(link("first", true));
        repository.saveAndFlush(link("second", true));

        List<BrainSourceLink> all = repository.findAllByOrderByCreatedAtDescIdDesc();
        assertEquals(2, all.size());
        assertEquals("second", all.get(0).getName());
    }
}
```

- [ ] **Run it — expected FAIL:** `BrainSourceLinkRepository` does not exist yet → compilation failure.
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.repository.BrainSourceLinkRepositoryTest'")`
  Expected: compile error — `cannot find symbol: class BrainSourceLinkRepository` (test does not run).

- [ ] **Write the repository** (`BrainSourceLinkRepository.java`):

```java
package com.msfg.rag.repository;

import com.msfg.rag.domain.BrainSourceLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BrainSourceLinkRepository extends JpaRepository<BrainSourceLink, UUID> {

    List<BrainSourceLink> findAllByOrderByCreatedAtDescIdDesc();

    List<BrainSourceLink> findByActiveTrueOrderByCreatedAtDescIdDesc();

    long countByActiveTrue();
}
```

- [ ] **Run it — expected PASS** (Docker must be up):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.repository.BrainSourceLinkRepositoryTest'")`
  Expected: `BUILD SUCCESSFUL`, 4 tests passing.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/repository/BrainSourceLinkRepository.java src/test/java/com/msfg/rag/repository/BrainSourceLinkRepositoryTest.java
git commit -m "feat(links): BrainSourceLinkRepository + Testcontainers repo test

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: DTOs — `SourceLinkDto` + `SourceLinkRequest`

**Files:**
- Create: `src/main/java/com/msfg/rag/dto/SourceLinkDto.java`
- Create: `src/main/java/com/msfg/rag/dto/SourceLinkRequest.java`

`SourceLinkDto` is the response record with snake_case JSON keys (mirrors `CitationDto`'s `@JsonProperty` style); boolean exposed as `active`; enums exposed as `.name()` strings; a static `from(BrainSourceLink)` factory. `SourceLinkRequest` is the create/update body: `authority`/`surface` typed as `String` (converted via `valueOf` for a clean 400), list fields as `List<String>`. NO bean-validation annotations (manual validation in the service, mirroring `DocumentUpdateRequest`). These are exercised by the service + controller tests; compile-verify here.

- [ ] **Write `SourceLinkDto.java`:**

```java
package com.msfg.rag.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.msfg.rag.domain.BrainSourceLink;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admin/dashboard view of a source link. snake_case wire keys mirror CitationDto;
 * enums are exposed as their .name() string; the boolean is exposed as "active".
 */
public record SourceLinkDto(
        UUID id,
        String name,
        String url,
        String domain,
        String authority,
        List<String> topics,
        @JsonProperty("freshness_required") boolean freshnessRequired,
        @JsonProperty("allowed_use") List<String> allowedUse,
        @JsonProperty("do_not_use_for") List<String> doNotUseFor,
        String surface,
        boolean active,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("created_by") String createdBy,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("updated_by") String updatedBy
) {

    public static SourceLinkDto from(BrainSourceLink l) {
        return new SourceLinkDto(
                l.getId(),
                l.getName(),
                l.getUrl(),
                l.getDomain(),
                l.getAuthority().name(),
                l.getTopics(),
                l.isFreshnessRequired(),
                l.getAllowedUse(),
                l.getDoNotUseFor(),
                l.getSurface().name(),
                l.isActive(),
                l.getCreatedAt(),
                l.getCreatedBy(),
                l.getUpdatedAt(),
                l.getUpdatedBy()
        );
    }
}
```

- [ ] **Write `SourceLinkRequest.java`:**

```java
package com.msfg.rag.dto;

import java.util.List;

/**
 * Create/update body for a source link. authority and surface are Strings (not
 * enums) so an unknown value yields a clean 400 via valueOf(...) in the service
 * rather than a Jackson 500. List fields default-empty in the service. No
 * jakarta.validation annotations — the service validates manually.
 */
public record SourceLinkRequest(
        String name,
        String url,
        String domain,
        String authority,
        List<String> topics,
        boolean freshnessRequired,
        List<String> allowedUse,
        List<String> doNotUseFor,
        String surface
) {}
```

- [ ] **Verify compile:**
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew compileJava")`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/dto/SourceLinkDto.java src/main/java/com/msfg/rag/dto/SourceLinkRequest.java
git commit -m "feat(links): SourceLinkDto and SourceLinkRequest DTOs

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: `SourceLinkService` — cached CRUD + invalidate + tests

**Files:**
- Create: `src/main/java/com/msfg/rag/service/retrieval/SourceLinkService.java`
- Test: `src/test/java/com/msfg/rag/service/retrieval/SourceLinkServiceTest.java`

Package `com.msfg.rag.service.retrieval` (matches `VocabularyService`). Full CRUD: `list()`, `get(id)`, `create(SourceLinkRequest, createdBy)`, `update(id, SourceLinkRequest, updatedBy)`, `delete(id)` (HARD delete, `@Transactional`), `setActive(id, boolean, updatedBy)`. PLUS `activeLinks()` — a cached read snapshot mirroring `VocabularyService`'s cache EXACTLY: 10s `System.nanoTime()` TTL, `Long.MIN_VALUE` sentinel checked **before** the `now - cachedAt > TTL` subtraction, `volatile` fields, `invalidate()` resets the sentinel. `invalidate()` is called on EVERY write path. `get`/`update`/`setActive`/`delete` use `findById().orElseThrow(() -> new IllegalArgumentException("source link not found: " + id))`. Text fields normalized with `.strip()`; blank-but-non-null inputs rejected via `isBlank()`. Enum `String → enum` via `valueOf` (→ 400 on bad value). `activeLinks()` is read by nothing in Phase 3 — keep it as the Phase 6 seam.

- [ ] **Write the failing test** (`SourceLinkServiceTest.java`) — mocked repo; covers create/get/update/setActive/delete validation + invalidate + the cache (sentinel + TTL + invalidate refresh):

```java
package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.dto.SourceLinkRequest;
import com.msfg.rag.repository.BrainSourceLinkRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SourceLinkServiceTest {

    private final BrainSourceLinkRepository repo = mock(BrainSourceLinkRepository.class);
    private final SourceLinkService service = new SourceLinkService(repo);

    private SourceLinkRequest validRequest() {
        return new SourceLinkRequest(
                "  Fannie Mae Selling Guide  ", "https://selling-guide.fanniemae.com", "fanniemae.com",
                "PRIMARY", List.of("conventional"), true,
                List.of("cite guideline sections"), List.of("legal advice"), "BOTH");
    }

    @Test
    void createStripsAndPersistsAndInvalidates() {
        when(repo.save(any(BrainSourceLink.class))).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.create(validRequest(), "admin-api");

        assertEquals("Fannie Mae Selling Guide", dto.name());        // stripped
        assertEquals("PRIMARY", dto.authority());
        assertEquals("BOTH", dto.surface());
        assertTrue(dto.freshnessRequired());
        verify(repo).save(any(BrainSourceLink.class));
    }

    @Test
    void createRejectsBlankName() {
        SourceLinkRequest req = new SourceLinkRequest(
                "   ", "https://x.com", null, "PRIMARY", List.of(), false, List.of(), List.of(), "BOTH");
        assertThrows(IllegalArgumentException.class, () -> service.create(req, "admin-api"));
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsBlankUrl() {
        SourceLinkRequest req = new SourceLinkRequest(
                "Name", "  ", null, "PRIMARY", List.of(), false, List.of(), List.of(), "BOTH");
        assertThrows(IllegalArgumentException.class, () -> service.create(req, "admin-api"));
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsUnknownAuthority() {
        SourceLinkRequest req = new SourceLinkRequest(
                "Name", "https://x.com", null, "NOPE", List.of(), false, List.of(), List.of(), "BOTH");
        assertThrows(IllegalArgumentException.class, () -> service.create(req, "admin-api"));
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsUnknownSurface() {
        SourceLinkRequest req = new SourceLinkRequest(
                "Name", "https://x.com", null, "PRIMARY", List.of(), false, List.of(), List.of(), "SIDEWAYS");
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
        BrainSourceLink existing = new BrainSourceLink(
                "old", "https://old.com", "old.com", LinkAuthority.BACKGROUND,
                List.of("x"), false, List.of(), List.of(), Surface.PUBLIC, "seed");
        when(repo.findById(id)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);

        var dto = service.update(id, validRequest(), "admin-api");

        assertEquals("Fannie Mae Selling Guide", existing.getName());
        assertEquals("https://selling-guide.fanniemae.com", existing.getUrl());
        assertEquals(LinkAuthority.PRIMARY, existing.getAuthority());
        assertEquals(Surface.BOTH, existing.getSurface());
        assertEquals("admin-api", existing.getUpdatedBy());
        assertEquals("Fannie Mae Selling Guide", dto.name());
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
        BrainSourceLink existing = new BrainSourceLink(
                "n", "https://n.com", null, LinkAuthority.PRIMARY,
                List.of(), false, List.of(), List.of(), Surface.BOTH, "seed");
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
        BrainSourceLink existing = new BrainSourceLink(
                "n", "https://n.com", null, LinkAuthority.PRIMARY,
                List.of(), false, List.of(), List.of(), Surface.BOTH, "seed");
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
    void activeLinksCachesFirstReadAndReusesUntilInvalidated() {
        BrainSourceLink a = new BrainSourceLink(
                "a", "https://a.com", null, LinkAuthority.PRIMARY,
                List.of(), false, List.of(), List.of(), Surface.BOTH, "seed");
        when(repo.findByActiveTrueOrderByCreatedAtDescIdDesc()).thenReturn(List.of(a));

        List<BrainSourceLink> first = service.activeLinks();
        List<BrainSourceLink> second = service.activeLinks();

        assertEquals(1, first.size());
        assertSame(first, second);                                  // served from cache
        verify(repo, times(1)).findByActiveTrueOrderByCreatedAtDescIdDesc();

        service.invalidate();
        service.activeLinks();
        verify(repo, times(2)).findByActiveTrueOrderByCreatedAtDescIdDesc();  // reloaded after invalidate
    }
}
```

- [ ] **Run it — expected FAIL:** `SourceLinkService` does not exist → compile error.
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.service.retrieval.SourceLinkServiceTest'")`
  Expected: compile error — `cannot find symbol: class SourceLinkService`.

- [ ] **Write the service** (`SourceLinkService.java`):

```java
package com.msfg.rag.service.retrieval;

import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.dto.SourceLinkDto;
import com.msfg.rag.dto.SourceLinkRequest;
import com.msfg.rag.repository.BrainSourceLinkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Full-CRUD service over the source-link registry, plus a short-cached
 * activeLinks() read snapshot for the (Phase 6) retrieval seam. The cache mirrors
 * VocabularyService exactly: 10s nanoTime TTL with a Long.MIN_VALUE sentinel
 * tested BEFORE the subtraction (so a fresh process never computes
 * now - Long.MIN_VALUE), volatile fields, invalidate() on every write. Nothing
 * in Phase 3 reads activeLinks() — it is the integration point for later phases.
 */
@Service
public class SourceLinkService {

    private static final long CACHE_TTL_NANOS = 10_000_000_000L; // ~10 s

    private final BrainSourceLinkRepository repo;

    private volatile List<BrainSourceLink> cache = List.of();
    private volatile long cachedAtNanos = Long.MIN_VALUE;

    public SourceLinkService(BrainSourceLinkRepository repo) {
        this.repo = repo;
    }

    public List<SourceLinkDto> list() {
        return repo.findAllByOrderByCreatedAtDescIdDesc().stream()
                .map(SourceLinkDto::from)
                .toList();
    }

    public SourceLinkDto get(UUID id) {
        return SourceLinkDto.from(find(id));
    }

    @Transactional
    public SourceLinkDto create(SourceLinkRequest req, String createdBy) {
        String name = required(req.name(), "name");
        String url = required(req.url(), "url");
        LinkAuthority authority = authority(req.authority());
        Surface surface = surface(req.surface());

        BrainSourceLink link = new BrainSourceLink(
                name, url, strip(req.domain()), authority,
                cleanList(req.topics()), req.freshnessRequired(),
                cleanList(req.allowedUse()), cleanList(req.doNotUseFor()),
                surface, createdBy);

        SourceLinkDto dto = SourceLinkDto.from(repo.save(link));
        invalidate();
        return dto;
    }

    @Transactional
    public SourceLinkDto update(UUID id, SourceLinkRequest req, String updatedBy) {
        BrainSourceLink link = find(id);
        link.setName(required(req.name(), "name"));
        link.setUrl(required(req.url(), "url"));
        link.setDomain(strip(req.domain()));
        link.setAuthority(authority(req.authority()));
        link.setTopics(cleanList(req.topics()));
        link.setFreshnessRequired(req.freshnessRequired());
        link.setAllowedUse(cleanList(req.allowedUse()));
        link.setDoNotUseFor(cleanList(req.doNotUseFor()));
        link.setSurface(surface(req.surface()));
        link.setUpdatedBy(updatedBy);

        SourceLinkDto dto = SourceLinkDto.from(repo.save(link));
        invalidate();
        return dto;
    }

    @Transactional
    public SourceLinkDto setActive(UUID id, boolean active, String updatedBy) {
        BrainSourceLink link = find(id);
        link.setActive(active);
        link.setUpdatedBy(updatedBy);
        SourceLinkDto dto = SourceLinkDto.from(repo.save(link));
        invalidate();
        return dto;
    }

    @Transactional
    public void delete(UUID id) {
        BrainSourceLink link = find(id);
        repo.delete(link);
        invalidate();
    }

    /** Cached snapshot of active links. Phase 6 retrieval seam — unused in Phase 3. */
    public List<BrainSourceLink> activeLinks() {
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

    private BrainSourceLink find(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("source link not found: " + id));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.strip();
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static LinkAuthority authority(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("authority is required");
        }
        return LinkAuthority.valueOf(value.strip());
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
}
```

- [ ] **Run it — expected PASS:**
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.service.retrieval.SourceLinkServiceTest'")`
  Expected: `BUILD SUCCESSFUL`, 12 tests passing.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/service/retrieval/SourceLinkService.java src/test/java/com/msfg/rag/service/retrieval/SourceLinkServiceTest.java
git commit -m "feat(links): SourceLinkService cached CRUD + service test

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: `AdminSourceLinkController` + POJO controller test

**Files:**
- Create: `src/main/java/com/msfg/rag/controller/AdminSourceLinkController.java`
- Test: `src/test/java/com/msfg/rag/controller/AdminSourceLinkControllerTest.java`

Class-level `@RequestMapping("/api/ai/admin/source-links")` (admin-gated by `AdminApiKeyFilter`). Endpoints: `GET ""` list, `GET "/{id}"` get, `POST ""` create, `PATCH "/{id}"` update, `DELETE "/{id}"` delete, `POST "/{id}/activate"`, `POST "/{id}/deactivate"` (both delegating to `service.setActive`). `UPDATED_BY = "admin-api"` constant threaded through create/update/setActive. Controller delegates validation/conversion to the service (which already throws `IllegalArgumentException` → 400 via `GlobalExceptionHandler`); it adds a null-body guard. Delete returns `{"deleted": true, "id": id}` (mirrors `DocumentAdminController.delete`). No literal-vs-`/{id}` ambiguity (activate/deactivate are under `/{id}/...`).

- [ ] **Write the failing test** (`AdminSourceLinkControllerTest.java`) — POJO, mocked service:

```java
package com.msfg.rag.controller;

import com.msfg.rag.dto.SourceLinkDto;
import com.msfg.rag.dto.SourceLinkRequest;
import com.msfg.rag.service.retrieval.SourceLinkService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSourceLinkControllerTest {

    private final SourceLinkService service = mock(SourceLinkService.class);
    private final AdminSourceLinkController controller = new AdminSourceLinkController(service);

    private SourceLinkDto dto(UUID id, boolean active) {
        return new SourceLinkDto(id, "Fannie Mae Selling Guide",
                "https://selling-guide.fanniemae.com", "fanniemae.com", "PRIMARY",
                List.of("conventional"), true, List.of("cite"), List.of("legal advice"),
                "BOTH", active, OffsetDateTime.now(), "admin-api", OffsetDateTime.now(), "admin-api");
    }

    private SourceLinkRequest req() {
        return new SourceLinkRequest("Fannie Mae Selling Guide",
                "https://selling-guide.fanniemae.com", "fanniemae.com", "PRIMARY",
                List.of("conventional"), true, List.of("cite"), List.of("legal advice"), "BOTH");
    }

    @Test
    void listDelegates() {
        UUID id = UUID.randomUUID();
        when(service.list()).thenReturn(List.of(dto(id, true)));

        List<SourceLinkDto> result = controller.list();

        assertEquals(1, result.size());
        verify(service).list();
    }

    @Test
    void getDelegates() {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenReturn(dto(id, true));

        SourceLinkDto result = controller.get(id);

        assertEquals(id, result.id());
        verify(service).get(id);
    }

    @Test
    void createDelegatesWithAdminAttribution() {
        UUID id = UUID.randomUUID();
        SourceLinkRequest body = req();
        when(service.create(body, "admin-api")).thenReturn(dto(id, true));

        SourceLinkDto result = controller.create(body);

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
        SourceLinkRequest body = req();
        when(service.update(id, body, "admin-api")).thenReturn(dto(id, true));

        SourceLinkDto result = controller.update(id, body);

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

        SourceLinkDto result = controller.activate(id);

        assertTrue(result.active());
        verify(service).setActive(id, true, "admin-api");
    }

    @Test
    void deactivateDelegates() {
        UUID id = UUID.randomUUID();
        when(service.setActive(id, false, "admin-api")).thenReturn(dto(id, false));

        SourceLinkDto result = controller.deactivate(id);

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

- [ ] **Run it — expected FAIL:** `AdminSourceLinkController` does not exist → compile error.
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.controller.AdminSourceLinkControllerTest'")`
  Expected: compile error — `cannot find symbol: class AdminSourceLinkController`.

- [ ] **Write the controller** (`AdminSourceLinkController.java`):

```java
package com.msfg.rag.controller;

import com.msfg.rag.dto.SourceLinkDto;
import com.msfg.rag.dto.SourceLinkRequest;
import com.msfg.rag.service.retrieval.SourceLinkService;
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
 * Admin CRUD for the source-link registry. Gated by AdminApiKeyFilter (the
 * /api/ai/admin prefix). All validation / not-found / bad-enum cases throw
 * IllegalArgumentException, mapped to HTTP 400 by GlobalExceptionHandler — no
 * try/catch or per-controller @ExceptionHandler here.
 */
@RestController
@RequestMapping("/api/ai/admin/source-links")
public class AdminSourceLinkController {

    private static final String UPDATED_BY = "admin-api";

    private final SourceLinkService service;

    public AdminSourceLinkController(SourceLinkService service) {
        this.service = service;
    }

    @GetMapping
    public List<SourceLinkDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public SourceLinkDto get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public SourceLinkDto create(@RequestBody SourceLinkRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return service.create(body, UPDATED_BY);
    }

    @PatchMapping("/{id}")
    public SourceLinkDto update(@PathVariable UUID id, @RequestBody SourceLinkRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        return service.update(id, body, UPDATED_BY);
    }

    @PostMapping("/{id}/activate")
    public SourceLinkDto activate(@PathVariable UUID id) {
        return service.setActive(id, true, UPDATED_BY);
    }

    @PostMapping("/{id}/deactivate")
    public SourceLinkDto deactivate(@PathVariable UUID id) {
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
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.controller.AdminSourceLinkControllerTest'")`
  Expected: `BUILD SUCCESSFUL`, 9 tests passing.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/controller/AdminSourceLinkController.java src/test/java/com/msfg/rag/controller/AdminSourceLinkControllerTest.java
git commit -m "feat(links): AdminSourceLinkController CRUD + POJO controller test

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: `source-links.yaml` pack seed

**Files:**
- Create: `packs/msfg-mortgage/source-links.yaml`

A small but real seed of mortgage authority links mapped to the schema. The loader's YAML mapper is `KEBAB_CASE` + `FAIL_ON_UNKNOWN_PROPERTIES` ON, so **every key must match the `SourceLinkFile` record component 1:1** (kebab-case). `authority`/`surface` are deserialized into the intermediate record as **String** (the loader maps them to the `DomainPack.SourceLink` record, also String) — use the exact enum NAMES so the seeder's `valueOf` succeeds. Topics / allowed-use / do-not-use-for are string lists. (This file is created here; the loader records that consume it land in Task 9. Until Task 9, this file is inert — the loader ignores files it does not read.)

- [ ] **Write `source-links.yaml`:**

```yaml
# Optional pack seed for the source-link registry (spec §6.1). Loaded by
# DomainPackLoader.readOptional and seeded into brain_source_links on first boot
# only when the table is empty. Removing this file reproduces today's behavior
# (empty registry). Keys are kebab-case; authority/surface use the exact enum
# names PRIMARY|SECONDARY|BACKGROUND and PUBLIC|INTERNAL|BOTH.
links:
  - name: "Fannie Mae Selling Guide"
    url: "https://selling-guide.fanniemae.com/"
    domain: "fanniemae.com"
    authority: PRIMARY
    topics:
      - conventional
      - conforming
      - underwriting
      - appraisal
    freshness-required: true
    allowed-use:
      - "cite conventional underwriting and eligibility requirements"
    do-not-use-for:
      - "FHA, VA, or USDA program rules"
    surface: BOTH

  - name: "Freddie Mac Single-Family Seller/Servicer Guide"
    url: "https://guide.freddiemac.com/"
    domain: "freddiemac.com"
    authority: PRIMARY
    topics:
      - conventional
      - conforming
      - servicing
    freshness-required: true
    allowed-use:
      - "cite conventional seller/servicer requirements"
    do-not-use-for:
      - "government loan program rules"
    surface: BOTH

  - name: "HUD Handbook 4000.1 (FHA Single Family Housing Policy Handbook)"
    url: "https://www.hud.gov/program_offices/housing/sfh/handbook_4000-1"
    domain: "hud.gov"
    authority: PRIMARY
    topics:
      - fha
      - government
      - underwriting
    freshness-required: true
    allowed-use:
      - "cite FHA origination and underwriting policy"
    do-not-use-for:
      - "conventional or VA program rules"
    surface: BOTH

  - name: "VA Lender's Handbook (M26-7)"
    url: "https://www.benefits.va.gov/warms/pam26_7.asp"
    domain: "benefits.va.gov"
    authority: PRIMARY
    topics:
      - va
      - government
      - eligibility
    freshness-required: true
    allowed-use:
      - "cite VA loan eligibility and underwriting requirements"
    do-not-use-for:
      - "conventional or FHA program rules"
    surface: BOTH

  - name: "USDA Single Family Housing Guaranteed Loan Program Handbook (HB-1-3555)"
    url: "https://www.rd.usda.gov/resources/directives/handbooks"
    domain: "rd.usda.gov"
    authority: PRIMARY
    topics:
      - usda
      - rural-development
      - government
    freshness-required: true
    allowed-use:
      - "cite USDA guaranteed loan program requirements"
    do-not-use-for:
      - "conventional, FHA, or VA program rules"
    surface: BOTH
```

- [ ] **Verify YAML is parseable** (standalone sanity; the loader integration is tested in Task 9):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"python3 -c \"import yaml,sys; d=yaml.safe_load(open('packs/msfg-mortgage/source-links.yaml')); print('links:', len(d['links'])); assert len(d['links'])==5\"")`
  Expected: `links: 5`.

- [ ] **Commit:**
```
git add packs/msfg-mortgage/source-links.yaml
git commit -m "feat(links): seed source-links.yaml with agency authority links

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: `DomainPackLoader` `readOptional()` + `SourceLinksFile` + `DomainPack` component (golden-test-driven)

**Files:**
- Modify: `src/main/java/com/msfg/rag/pack/DomainPack.java`
- Modify: `src/main/java/com/msfg/rag/pack/DomainPackLoader.java`
- Test: `src/test/java/com/msfg/rag/pack/MsfgGoldenPackTest.java`

`DomainPack` is a POSITIONAL 10-arg record; add `sourceLinks` as the 11th component (defaults to `List.of()` when null — NOT null-preserving, unlike the other components) plus a nested `SourceLink` record. The loader gets a `readOptional()` helper (returns null when the file is absent — `read()` throws and cannot be reused), `SourceLinksFile`/`SourceLinkFile` intermediate records, an optional read in `load()`, and the new positional arg in the `new DomainPack(...)` call. NO `require(... non-empty)` in `validate()` for source links (would make the optional file mandatory). Update the `DomainPackLoader` Javadoc ("five required + one optional").

This task is **golden-test-driven** (red→green): the new behavior is locked by a `sourceLinksMatchSeed()` golden assertion that is written BEFORE the loader reads the YAML. Sequence: (1) shift the record shape so `PACK.sourceLinks()` compiles but the loader passes a hardcoded empty `List.of()` (the unmapped/red state); (2) write the golden assertion in `MsfgGoldenPackTest` and run it — it FAILS (`expected 5, was 0`) because `source-links.yaml` from Task 8 is not yet read; (3) implement `readOptional()` + `SourceLinksFile`/`SourceLinkFile` + the mapped `new DomainPack(...)` arg to populate the component — the assertion turns green. (`source-links.yaml` already exists from Task 8, so the assertion has real data to lock the moment the loader reads it.)

- [ ] **Edit `DomainPack.java` — add the component to the record header.** Replace:

```java
        Map<String, String> acronymExpansions,
        List<ProgramRule> programRules
) {

    public DomainPack {
        classifierRules = classifierRules == null ? null : List.copyOf(classifierRules);
        acronymExpansions = acronymExpansions == null ? null : Map.copyOf(acronymExpansions);
        programRules = programRules == null ? null : List.copyOf(programRules);
    }
```

with:

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

- [ ] **Edit `DomainPack.java` — add the nested `SourceLink` record** before the final closing brace (after the `ProgramRule` record):

```java
    /**
     * One optional source/link registry seed entry from source-links.yaml.
     * authority/surface are the enum NAMES (PRIMARY|SECONDARY|BACKGROUND,
     * PUBLIC|INTERNAL|BOTH); the first-boot seeder converts them via valueOf.
     */
    public record SourceLink(
            String name,
            String url,
            String domain,
            String authority,
            List<String> topics,
            boolean freshnessRequired,
            List<String> allowedUse,
            List<String> doNotUseFor,
            String surface
    ) {
        public SourceLink {
            topics = topics == null ? List.of() : List.copyOf(topics);
            allowedUse = allowedUse == null ? List.of() : List.copyOf(allowedUse);
            doNotUseFor = doNotUseFor == null ? List.of() : List.copyOf(doNotUseFor);
        }
    }
```

- [ ] **Edit `DomainPack.java` — update the class Javadoc** to mention the optional source links. Change the opening Javadoc sentence to note: `... loaded from a pack directory at boot (five required files plus an optional source-links.yaml; see DomainPackLoader).` (Append " Source links default to an empty list when the optional file is absent." after the existing immutability sentence.)

- [ ] **Edit `DomainPackLoader.java` — append a temporary EMPTY arg to the `new DomainPack(...)` call (the red state).** This makes the new 11-arg record compile while the loader does NOT yet read the YAML — so `PACK.sourceLinks()` is empty, which the golden assertion below will catch. The current call ends with the `programRules` mapping `.toList())`. Change the closing `)` of that mapping into `,` and append `List.of()` as the new final argument (showing the tail of the call):

```java
                retrievalFile.programs() == null ? null : retrievalFile.programs().stream()
                        .map(p -> new DomainPack.ProgramRule(
                                p.program(),
                                p.keywords() == null ? List.of() : p.keywords(),
                                p.wordPatterns() == null ? List.of() : p.wordPatterns()))
                        .toList(),
                List.of());   // TEMPORARY: source links unmapped — replaced below to turn the golden test green
```

- [ ] **Write the failing golden assertion** (`MsfgGoldenPackTest.sourceLinksMatchSeed`). Add inside the `MsfgGoldenPackTest` class (e.g. after `retrievalRulesMatchLegacy`). `PACK` is `TestPacks.msfg()`; the expected values mirror `source-links.yaml` (Task 8) exactly — a 2-way golden sync. This assertion is written BEFORE the loader reads the YAML, so it locks the literal first:

```java
    @Test
    void sourceLinksMatchSeed() {
        List<DomainPack.SourceLink> links = PACK.sourceLinks();
        assertEquals(5, links.size());

        DomainPack.SourceLink fannie = links.get(0);
        assertEquals("Fannie Mae Selling Guide", fannie.name());
        assertEquals("https://selling-guide.fanniemae.com/", fannie.url());
        assertEquals("fanniemae.com", fannie.domain());
        assertEquals("PRIMARY", fannie.authority());
        assertEquals(List.of("conventional", "conforming", "underwriting", "appraisal"), fannie.topics());
        assertTrue(fannie.freshnessRequired());
        assertEquals(List.of("cite conventional underwriting and eligibility requirements"), fannie.allowedUse());
        assertEquals(List.of("FHA, VA, or USDA program rules"), fannie.doNotUseFor());
        assertEquals("BOTH", fannie.surface());

        assertEquals("Freddie Mac Single-Family Seller/Servicer Guide", links.get(1).name());
        assertEquals("freddiemac.com", links.get(1).domain());
        assertEquals("PRIMARY", links.get(1).authority());

        assertEquals("HUD Handbook 4000.1 (FHA Single Family Housing Policy Handbook)", links.get(2).name());
        assertEquals("hud.gov", links.get(2).domain());

        assertEquals("VA Lender's Handbook (M26-7)", links.get(3).name());
        assertEquals("benefits.va.gov", links.get(3).domain());

        assertEquals("USDA Single Family Housing Guaranteed Loan Program Handbook (HB-1-3555)", links.get(4).name());
        assertEquals("rd.usda.gov", links.get(4).domain());

        // every seeded link is PRIMARY / BOTH and freshness-required
        for (DomainPack.SourceLink link : links) {
            assertEquals("PRIMARY", link.authority());
            assertEquals("BOTH", link.surface());
            assertTrue(link.freshnessRequired());
        }
    }
```

  (If `assertTrue` is not already statically imported in the file, add `import static org.junit.jupiter.api.Assertions.assertTrue;` — the existing file uses `import static org.junit.jupiter.api.Assertions.*;` per the extraction, in which case no new import is needed. Verify and add only if missing.)

- [ ] **Run it — expected FAIL:** the loader still passes a hardcoded empty `List.of()`, so `PACK.sourceLinks()` is empty.
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.pack.MsfgGoldenPackTest'")`
  Expected: `sourceLinksMatchSeed` FAILS with `expected: <5> but was: <0>` (the assertion is locked before the loader reads the YAML). All other golden methods still PASS.

- [ ] **Edit `DomainPackLoader.java` — update the class Javadoc.** Replace `Reads the five YAML files of a domain pack directory into a DomainPack.` with `Reads the five required YAML files (plus an optional source-links.yaml) of a domain pack directory into a DomainPack.`

- [ ] **Edit `DomainPackLoader.java` — add the intermediate file records.** After the existing `ProgramFile` record line, add:

```java
    private record SourceLinksFile(List<SourceLinkFile> links) {}
    private record SourceLinkFile(String name, String url, String domain, String authority,
                                  List<String> topics, boolean freshnessRequired,
                                  List<String> allowedUse, List<String> doNotUseFor,
                                  String surface) {}
```

- [ ] **Edit `DomainPackLoader.java` — read the optional file in `load()`.** After the line `RetrievalFile retrievalFile = read(packDir, "retrieval.yaml", RetrievalFile.class);` add:

```java
        SourceLinksFile sourceLinksFile = readOptional(packDir, "source-links.yaml", SourceLinksFile.class);
```

- [ ] **Edit `DomainPackLoader.java` — replace the temporary `List.of()` with the mapped arg (the green state).** Replace the `List.of());   // TEMPORARY: ...` line added in the red step with the mapped stream so the constructor reads (showing the tail of the call):

```java
                retrievalFile.programs() == null ? null : retrievalFile.programs().stream()
                        .map(p -> new DomainPack.ProgramRule(
                                p.program(),
                                p.keywords() == null ? List.of() : p.keywords(),
                                p.wordPatterns() == null ? List.of() : p.wordPatterns()))
                        .toList(),
                (sourceLinksFile == null || sourceLinksFile.links() == null) ? List.of()
                        : sourceLinksFile.links().stream()
                                .map(s -> new DomainPack.SourceLink(
                                        s.name(), s.url(), s.domain(), s.authority(),
                                        s.topics(), s.freshnessRequired(),
                                        s.allowedUse(), s.doNotUseFor(), s.surface()))
                                .toList());
```

- [ ] **Edit `DomainPackLoader.java` — add the `readOptional()` helper** next to `read(...)` (mirrors `read()` exactly but returns null when the file is absent, instead of throwing):

```java
    /** Optional pack file: a missing file returns null (caller defaults to empty). */
    private <T> T readOptional(Path packDir, String fileName, Class<T> type) {
        Path file = packDir.resolve(fileName);
        if (!Files.isRegularFile(file)) {
            return null;
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
```

- [ ] **Do NOT add any `require(...)` for source links in `validate()`.** (The optional file must stay optional; the existing per-pack tests for packs without the file must keep booting.)

- [ ] **Run it — expected PASS:** the loader now reads `source-links.yaml` and populates the component, so `sourceLinksMatchSeed` (and every existing golden method) passes.
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.pack.MsfgGoldenPackTest'")`
  Expected: `BUILD SUCCESSFUL`, all golden methods (existing + `sourceLinksMatchSeed`) passing. If `sourceLinksMatchSeed` fails on a value mismatch, FIX `packs/msfg-mortgage/source-links.yaml` to match this literal (never weaken the literal), then re-run.

- [ ] **Run the rest of the pack suite + compile — expected PASS** (no regression from the record-shape change):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests 'com.msfg.rag.pack.*'")`
  Expected: `BUILD SUCCESSFUL`. If a pack-shape assertion fails, it is because a test hand-constructs `new DomainPack(...)` positionally — there is exactly one such site to watch (none in `MsfgGoldenPackTest`, which uses `TestPacks.msfg()` + `new PromptBuilderService(PACK, ...)`); fix any positional `new DomainPack(...)` in test code by appending `List.of()` as the 11th arg. Then confirm the whole tree compiles:
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew compileJava compileTestJava")`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/pack/DomainPack.java src/main/java/com/msfg/rag/pack/DomainPackLoader.java src/test/java/com/msfg/rag/pack/MsfgGoldenPackTest.java
git commit -m "feat(links): optional source-links pack component + readOptional loader, golden-locked

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: First-boot seeder

**Files:**
- Test: `src/test/java/com/msfg/rag/seed/SourceLinkSeederTest.java`
- Create: `src/main/java/com/msfg/rag/seed/SourceLinkSeeder.java`

An `ApplicationRunner` bean that, IF `brain_source_links` is empty (`repo.count() == 0`) AND the `DomainPack` has source links, inserts them. Idempotent — only seeds when count is zero. No pack file → `DomainPack.sourceLinks()` is empty → nothing seeded → empty table → today's behavior. Converts `DomainPack.SourceLink` (String authority/surface) → entity via `valueOf`. Wrapped `@Transactional`. There is no existing seeder pattern in the repo (confirmed: no `ApplicationRunner`/`ApplicationReadyEvent` bean exists) — this is the first. The seeder's branch logic (seed-when-empty / skip-when-non-empty / skip-when-pack-empty and the String→enum `valueOf` mapping) is locked by a pure-Mockito unit test below — NO Spring, NO Docker, NO Testcontainers (both ctor args, `BrainSourceLinkRepository` and `DomainPack`, are mockable). The live wiring is additionally exercised by Task 14's boot.

- [ ] **Write the failing test** (`SourceLinkSeederTest.java`) — pure Mockito, same package `com.msfg.rag.seed` for package-private access. Mocks the repository and `DomainPack` (stubbing only `pack.sourceLinks()`, the only method the seeder reads); captures the saved entity to assert the `valueOf` enum mapping:

```java
package com.msfg.rag.seed;

import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.repository.BrainSourceLinkRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SourceLinkSeederTest {

    private final BrainSourceLinkRepository repository = mock(BrainSourceLinkRepository.class);
    private final DomainPack pack = mock(DomainPack.class);
    private final SourceLinkSeeder seeder = new SourceLinkSeeder(repository, pack);

    private DomainPack.SourceLink seed() {
        return new DomainPack.SourceLink(
                "Fannie Mae Selling Guide", "https://selling-guide.fanniemae.com/", "fanniemae.com",
                "PRIMARY", List.of("conventional"), true,
                List.of("cite guideline sections"), List.of("legal advice"), "BOTH");
    }

    @Test
    void seedsWhenEmptyAndPackHasLinks() {
        when(pack.sourceLinks()).thenReturn(List.of(seed()));
        when(repository.count()).thenReturn(0L);

        seeder.run(null);

        ArgumentCaptor<BrainSourceLink> captor = ArgumentCaptor.forClass(BrainSourceLink.class);
        verify(repository).save(captor.capture());
        BrainSourceLink saved = captor.getValue();
        assertEquals("Fannie Mae Selling Guide", saved.getName());
        assertEquals(LinkAuthority.PRIMARY, saved.getAuthority());   // String -> enum via valueOf
        assertEquals(Surface.BOTH, saved.getSurface());              // String -> enum via valueOf
    }

    @Test
    void skipsWhenTableNonEmpty() {
        when(pack.sourceLinks()).thenReturn(List.of(seed()));
        when(repository.count()).thenReturn(1L);

        seeder.run(null);

        verify(repository, never()).save(any());
    }

    @Test
    void skipsWhenPackEmpty() {
        when(pack.sourceLinks()).thenReturn(List.of());

        seeder.run(null);

        verify(repository, never()).save(any());
    }
}
```

- [ ] **Run it — expected FAIL:** `SourceLinkSeeder` does not exist yet → compile error.
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests '*SourceLinkSeederTest'")`
  Expected: compile error — `cannot find symbol: class SourceLinkSeeder` (test does not run).

- [ ] **Write `SourceLinkSeeder.java`:**

```java
package com.msfg.rag.seed;

import com.msfg.rag.domain.BrainSourceLink;
import com.msfg.rag.domain.LinkAuthority;
import com.msfg.rag.domain.Surface;
import com.msfg.rag.pack.DomainPack;
import com.msfg.rag.repository.BrainSourceLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds brain_source_links from the optional pack file on first boot, once, only
 * when the table is empty. No pack file -> DomainPack.sourceLinks() is empty ->
 * nothing is seeded -> the table stays empty and the system behaves exactly as
 * before this feature existed. Idempotent: a non-empty table is left untouched.
 */
@Component
public class SourceLinkSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SourceLinkSeeder.class);
    private static final String SEEDED_BY = "pack-seed";

    private final BrainSourceLinkRepository repository;
    private final DomainPack pack;

    public SourceLinkSeeder(BrainSourceLinkRepository repository, DomainPack pack) {
        this.repository = repository;
        this.pack = pack;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (pack.sourceLinks().isEmpty()) {
            return;
        }
        if (repository.count() > 0) {
            return;
        }
        for (DomainPack.SourceLink seed : pack.sourceLinks()) {
            repository.save(new BrainSourceLink(
                    seed.name(),
                    seed.url(),
                    seed.domain(),
                    LinkAuthority.valueOf(seed.authority()),
                    seed.topics(),
                    seed.freshnessRequired(),
                    seed.allowedUse(),
                    seed.doNotUseFor(),
                    Surface.valueOf(seed.surface()),
                    SEEDED_BY));
        }
        log.info("Seeded {} source link(s) from pack into brain_source_links", pack.sourceLinks().size());
    }
}
```

- [ ] **Run the seeder test — expected PASS** (pure Mockito; no Docker needed):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test --tests '*SourceLinkSeederTest'")`
  Expected: `BUILD SUCCESSFUL`, 3 tests passing.

- [ ] **Verify compile + pack suite still green** (the seeder is wired into the context; ensure no startup wiring breaks the existing `@SpringBootTest`/context tests):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew compileJava && ./gradlew test --tests 'com.msfg.rag.pack.*'")`
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Commit:**
```
git add src/main/java/com/msfg/rag/seed/SourceLinkSeeder.java src/test/java/com/msfg/rag/seed/SourceLinkSeederTest.java
git commit -m "feat(links): first-boot seeder for brain_source_links + seeder unit test

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: Dashboard types

**Files:**
- Modify: `dashboard/src/types.ts`

Add `SourceLinkDto` (matches the backend response wire shape — snake_case keys for `freshness_required`/`allowed_use`/`do_not_use_for`/`created_at`/`created_by`/`updated_at`/`updated_by`; camelCase for the rest as serialized) and `SourceLinkRequest` (the create/update body — omits id/active/timestamps; `freshnessRequired` is camelCase as the backend record field name binds on input). Mirror the existing `DocumentDto`/`DocumentUpdate` split style.

> Note on JSON key casing: the response DTO (`SourceLinkDto.java`) uses `@JsonProperty` snake_case on the multi-word fields, so the dashboard interface uses those snake_case keys on **read**. The request record (`SourceLinkRequest.java`) has no `@JsonProperty`, so Jackson binds its camelCase Java names on **write** — the dashboard sends camelCase (`freshnessRequired`, `allowedUse`, `doNotUseFor`). The two shapes are intentionally different; keep them distinct.

- [ ] **Append to `types.ts`:**

```typescript
export interface SourceLinkDto {
  id: string;
  name: string;
  url: string;
  domain: string | null;
  authority: string;
  topics: string[];
  freshness_required: boolean;
  allowed_use: string[];
  do_not_use_for: string[];
  surface: string;
  active: boolean;
  created_at: string | null;
  created_by: string | null;
  updated_at: string | null;
  updated_by: string | null;
}

export interface SourceLinkRequest {
  name: string;
  url: string;
  domain: string | null;
  authority: string;
  topics: string[];
  freshnessRequired: boolean;
  allowedUse: string[];
  doNotUseFor: string[];
  surface: string;
}
```

- [ ] **Verify the dashboard type-checks AND builds** (`npm run build` is Vite/esbuild and does NOT type-check, so run `npm run check` (`tsc --noEmit`, per `dashboard/package.json`) alongside it — a snake_case-read / camelCase-write field-name typo only fails the `tsc` gate):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"cd dashboard && npm run check && npm run build")`
  Expected: `tsc --noEmit` exits 0 (no type errors from the new interfaces); build succeeds (`✓ built` / module count).

- [ ] **Commit:**
```
git add dashboard/src/types.ts
git commit -m "feat(links): dashboard types for source links

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: `SourceLinks.tsx` dashboard screen

**Files:**
- Create: `dashboard/src/screens/SourceLinks.tsx`

Mirrors `Corpus.tsx` row-CRUD: a `tbl` table with `Pill`/`row-actions`, single-string `busy` state, a collapsible create form, a per-row edit MODAL (`.card` inside `.modal-overlay` with `onClick={()=>setEditing(null)}` on the overlay + `onClick={e=>e.stopPropagation()}` on the inner box), delete-with-confirm, activate/deactivate. Reuses the existing `api` methods (`get`/`post`/`patch`/`del`). Endpoints under `/api/ai/admin/source-links`. Reuses existing `Pill` tones only (green/amber/gray/blue/purple). Nullable `domain` round-trips with `value={x ?? ""}` + `onChange` writing `e.target.value || null`. No `onCorpusChanged` prop (no parent stats callback) — only `reload()` after mutations. List/comma string fields (`topics`, `allowedUse`, `doNotUseFor`) are edited as comma-separated text and split/join on the boundary.

- [ ] **Write `SourceLinks.tsx`:**

```tsx
import React, { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import { SourceLinkDto, SourceLinkRequest } from "../types";
import { ErrorNote, Pill } from "../components";

const AUTHORITIES = ["PRIMARY", "SECONDARY", "BACKGROUND"];
const SURFACES = ["BOTH", "PUBLIC", "INTERNAL"];

function toList(text: string): string[] {
  return text.split(",").map((s) => s.trim()).filter((s) => s.length > 0);
}

function emptyForm(): SourceLinkRequest {
  return {
    name: "",
    url: "",
    domain: null,
    authority: "PRIMARY",
    topics: [],
    freshnessRequired: false,
    allowedUse: [],
    doNotUseFor: [],
    surface: "BOTH",
  };
}

function authorityTone(authority: string): "blue" | "purple" | "gray" {
  if (authority === "PRIMARY") return "blue";
  if (authority === "SECONDARY") return "purple";
  return "gray";
}

export default function SourceLinks() {
  const [links, setLinks] = useState<SourceLinkDto[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // create form
  const [showAdd, setShowAdd] = useState(false);
  const [addBusy, setAddBusy] = useState(false);
  const [addForm, setAddForm] = useState<SourceLinkRequest>(emptyForm());
  const [addTopics, setAddTopics] = useState("");
  const [addAllowed, setAddAllowed] = useState("");
  const [addDoNot, setAddDoNot] = useState("");

  // edit modal
  const [editing, setEditing] = useState<SourceLinkDto | null>(null);
  const [editBusy, setEditBusy] = useState(false);
  const [editForm, setEditForm] = useState<SourceLinkRequest>(emptyForm());
  const [editTopics, setEditTopics] = useState("");
  const [editAllowed, setEditAllowed] = useState("");
  const [editDoNot, setEditDoNot] = useState("");

  const reload = useCallback(() => {
    api.get<SourceLinkDto[]>("/api/ai/admin/source-links")
      .then(setLinks)
      .catch((e) => setError((e as Error).message));
  }, []);

  useEffect(reload, [reload]);

  async function submitAdd(e: React.FormEvent) {
    e.preventDefault();
    if (!addForm.name.trim() || !addForm.url.trim()) return;
    setAddBusy(true);
    setError(null);
    try {
      const body: SourceLinkRequest = {
        ...addForm,
        name: addForm.name.trim(),
        url: addForm.url.trim(),
        topics: toList(addTopics),
        allowedUse: toList(addAllowed),
        doNotUseFor: toList(addDoNot),
      };
      await api.post("/api/ai/admin/source-links", body);
      setShowAdd(false);
      setAddForm(emptyForm());
      setAddTopics(""); setAddAllowed(""); setAddDoNot("");
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setAddBusy(false);
    }
  }

  function openEdit(l: SourceLinkDto) {
    setEditing(l);
    setEditForm({
      name: l.name,
      url: l.url,
      domain: l.domain,
      authority: l.authority,
      topics: l.topics,
      freshnessRequired: l.freshness_required,
      allowedUse: l.allowed_use,
      doNotUseFor: l.do_not_use_for,
      surface: l.surface,
    });
    setEditTopics(l.topics.join(", "));
    setEditAllowed(l.allowed_use.join(", "));
    setEditDoNot(l.do_not_use_for.join(", "));
  }

  async function submitEdit(e: React.FormEvent) {
    e.preventDefault();
    if (!editing || !editForm.name.trim() || !editForm.url.trim()) return;
    setEditBusy(true);
    setError(null);
    try {
      const body: SourceLinkRequest = {
        ...editForm,
        name: editForm.name.trim(),
        url: editForm.url.trim(),
        topics: toList(editTopics),
        allowedUse: toList(editAllowed),
        doNotUseFor: toList(editDoNot),
      };
      await api.patch(`/api/ai/admin/source-links/${editing.id}`, body);
      setEditing(null);
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setEditBusy(false);
    }
  }

  async function remove(l: SourceLinkDto) {
    if (!window.confirm(`Delete "${l.name}"? This permanently removes the registry entry. This cannot be undone.`)) {
      return;
    }
    setBusy(l.id);
    setError(null);
    try {
      await api.del(`/api/ai/admin/source-links/${l.id}`);
      reload();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  async function setActive(l: SourceLinkDto, active: boolean) {
    setError(null);
    try {
      await api.post(`/api/ai/admin/source-links/${l.id}/${active ? "activate" : "deactivate"}`);
      reload();
    } catch (e) {
      setError((e as Error).message);
    }
  }

  return (
    <>
      <header className="screen-head">
        <h1>Source links</h1>
        <div className="actions">
          <button onClick={() => setShowAdd((v) => !v)} disabled={busy !== null}>
            {showAdd ? "Cancel" : "Add source link"}
          </button>
        </div>
      </header>
      <ErrorNote message={error} />
      {showAdd && (
        <form className="card" onSubmit={submitAdd} style={{ display: "grid", gap: 8, marginBottom: 12 }}>
          <input placeholder="Name (e.g. Fannie Mae Selling Guide)" value={addForm.name}
                 onChange={(e) => setAddForm({ ...addForm, name: e.target.value })} required />
          <input placeholder="URL" value={addForm.url}
                 onChange={(e) => setAddForm({ ...addForm, url: e.target.value })} required />
          <input placeholder="Domain (e.g. fanniemae.com)" value={addForm.domain ?? ""}
                 onChange={(e) => setAddForm({ ...addForm, domain: e.target.value || null })} />
          <select value={addForm.authority}
                  onChange={(e) => setAddForm({ ...addForm, authority: e.target.value })}>
            {AUTHORITIES.map((a) => <option key={a} value={a}>{a.toLowerCase()}</option>)}
          </select>
          <select value={addForm.surface}
                  onChange={(e) => setAddForm({ ...addForm, surface: e.target.value })}>
            {SURFACES.map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
          </select>
          <input placeholder="Topics (comma-separated)" value={addTopics}
                 onChange={(e) => setAddTopics(e.target.value)} />
          <input placeholder="Allowed use (comma-separated)" value={addAllowed}
                 onChange={(e) => setAddAllowed(e.target.value)} />
          <input placeholder="Do not use for (comma-separated)" value={addDoNot}
                 onChange={(e) => setAddDoNot(e.target.value)} />
          <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <input type="checkbox" checked={addForm.freshnessRequired}
                   onChange={(e) => setAddForm({ ...addForm, freshnessRequired: e.target.checked })} />
            Freshness required
          </label>
          <button className="btn-primary" type="submit"
                  disabled={addBusy || !addForm.name.trim() || !addForm.url.trim()}>
            {addBusy ? "Saving…" : "Create source link"}
          </button>
        </form>
      )}
      <table className="tbl">
        <thead>
          <tr><th>Name</th><th>Authority</th><th>Surface</th><th>Status</th><th></th></tr>
        </thead>
        <tbody>
          {links.map((l) => (
            <tr key={l.id}>
              <td title={l.url}>{l.name}</td>
              <td><Pill tone={authorityTone(l.authority)}>{l.authority.toLowerCase()}</Pill></td>
              <td><Pill tone="gray">{l.surface.toLowerCase()}</Pill></td>
              <td><Pill tone={l.active ? "green" : "gray"}>{l.active ? "active" : "inactive"}</Pill></td>
              <td className="row-actions">
                <button onClick={() => openEdit(l)}>Edit</button>
                <button onClick={() => setActive(l, !l.active)}>
                  {l.active ? "Deactivate" : "Activate"}
                </button>
                <button className="danger" onClick={() => remove(l)} disabled={busy === l.id}>
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
                style={{ display: "grid", gap: 8, maxWidth: 460, margin: "10vh auto" }}>
            <h3 style={{ margin: 0 }}>Edit source link</h3>
            <input placeholder="Name" value={editForm.name}
                   onChange={(e) => setEditForm({ ...editForm, name: e.target.value })} required />
            <input placeholder="URL" value={editForm.url}
                   onChange={(e) => setEditForm({ ...editForm, url: e.target.value })} required />
            <input placeholder="Domain" value={editForm.domain ?? ""}
                   onChange={(e) => setEditForm({ ...editForm, domain: e.target.value || null })} />
            <select value={editForm.authority}
                    onChange={(e) => setEditForm({ ...editForm, authority: e.target.value })}>
              {AUTHORITIES.map((a) => <option key={a} value={a}>{a.toLowerCase()}</option>)}
            </select>
            <select value={editForm.surface}
                    onChange={(e) => setEditForm({ ...editForm, surface: e.target.value })}>
              {SURFACES.map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
            </select>
            <input placeholder="Topics (comma-separated)" value={editTopics}
                   onChange={(e) => setEditTopics(e.target.value)} />
            <input placeholder="Allowed use (comma-separated)" value={editAllowed}
                   onChange={(e) => setEditAllowed(e.target.value)} />
            <input placeholder="Do not use for (comma-separated)" value={editDoNot}
                   onChange={(e) => setEditDoNot(e.target.value)} />
            <label style={{ display: "flex", gap: 8, alignItems: "center" }}>
              <input type="checkbox" checked={editForm.freshnessRequired}
                     onChange={(e) => setEditForm({ ...editForm, freshnessRequired: e.target.checked })} />
              Freshness required
            </label>
            <div style={{ display: "flex", gap: 8 }}>
              <button className="btn-primary" type="submit"
                      disabled={editBusy || !editForm.name.trim() || !editForm.url.trim()}>
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
  Expected: `tsc --noEmit` exits 0; build succeeds. (An unused-import or unused-component warning is acceptable only if both `tsc` and the build still exit 0.)

- [ ] **Commit:**
```
git add dashboard/src/screens/SourceLinks.tsx
git commit -m "feat(links): SourceLinks dashboard screen

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: Register the screen in `App.tsx`

**Files:**
- Modify: `dashboard/src/App.tsx`

Three edits: import, `<NavLink>`, `<Route>`. Place "Source links" near Vocabulary (knowledge-adjacent). HashRouter (already used). No props for this screen.

- [ ] **Edit 1 — import.** After `import Vocabulary from "./screens/Vocabulary";` add:

```tsx
import SourceLinks from "./screens/SourceLinks";
```

- [ ] **Edit 2 — NavLink.** Inside `<nav className="nav">`, after `<NavLink to="/vocabulary">Vocabulary</NavLink>` add:

```tsx
            <NavLink to="/source-links">Source links</NavLink>
```

- [ ] **Edit 3 — Route.** Inside `<Routes>`, after `<Route path="/vocabulary" element={<Vocabulary />} />` add:

```tsx
            <Route path="/source-links" element={<SourceLinks />} />
```

- [ ] **Verify type-check + build + dashboard tests** (`npm run build` is Vite/esbuild and does NOT type-check, so run `npm run check` (`tsc --noEmit`, per `dashboard/package.json`) alongside it):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"cd dashboard && npm run check && npm run build && npx vitest run")`
  Expected: `tsc --noEmit` exits 0; build succeeds; `vitest run` passes (existing 5/5; `npx vitest run`, NOT `npm run test` which is watch-mode and hangs).

- [ ] **Commit:**
```
git add dashboard/src/App.tsx
git commit -m "feat(links): register Source links screen in dashboard nav

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 14: Full-suite + dashboard + golden no-regression verification

**Files:** none (verification only).

The hard backward-compatibility gate. Confirm the whole backend suite is green (including the golden lock and any `@SpringBootTest` context tests that now boot the seeder), the dashboard builds and tests pass, and — manually — that a fresh boot with the pack file seeds 5 links while removing the pack file leaves the table empty (today's behavior).

- [ ] **Backend full suite:**
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"./gradlew test")`
  Expected: `BUILD SUCCESSFUL`. The new tests (`BrainSourceLinkRepositoryTest`, `SourceLinkServiceTest`, `AdminSourceLinkControllerTest`, `MsfgGoldenPackTest.sourceLinksMatchSeed`, `SourceLinkSeederTest`) all pass; no existing test regresses.

- [ ] **Dashboard type-check + build + tests** (`npm run build` is Vite/esbuild and does NOT type-check, so run `npm run check` (`tsc --noEmit`, per `dashboard/package.json`) alongside it):
  `mcp__plugin_context-mode_context-mode__ctx_execute(language:"shell", code:"cd dashboard && npm run check && npm run build && npx vitest run")`
  Expected: `tsc --noEmit` exits 0; build succeeds; vitest passes.

- [ ] **Backward-compat smoke (manual, optional but recommended).** Boot the brain on :8090 from this branch (`~/MSFG/msfg-rag/start.sh`, NEVER port 8080). With `packs/msfg-mortgage/source-links.yaml` present and an empty table, the seeder logs `Seeded 5 source link(s)...`; `GET /api/ai/admin/source-links` (with `X-Admin-Api-Key`) returns 5 active links. Temporarily renaming the pack file away and pointing at a fresh DB → empty table, no seeding, `GET` returns `[]` — identical to pre-feature behavior. The ask pipeline (`/api/ai/mortgage/ask`) is unchanged in both cases.

- [ ] **Confirm branch is mergeable** (do NOT merge here — that is the user's call):
  `git log --oneline main..feat/phase3-link-registry` shows the 13 feature commits; `git merge-base --is-ancestor main feat/phase3-link-registry` exits 0.

- [ ] **Final commit (if any verification-only artifacts changed — normally none):** skip if nothing changed.

---

## Self-Review

**Spec coverage (spec §6.1, §6.4, §6.5; locked decisions):**
- Table `brain_source_links` with exact §6.1 columns (`name`, `url`, `domain`, `authority`, `topics`, `freshness_required`, `allowed_use`, `do_not_use_for`, `surface`, `active`, `created_at/by`, `updated_at/by`) — Task 1. ✓
- `id UUID PRIMARY KEY` with NO `DEFAULT gen_random_uuid()` matching the V6/V7 brain_* convention (entity supplies UUID via `@GeneratedValue`) — Task 1. ✓ (V6/V7 verified: no default; entities use bare `@Id @GeneratedValue` and work.)
- jsonb via `@JdbcTypeCode(SqlTypes.JSON)` on typed `List<String>`; NOT-NULL jsonb initialized in Java + `DEFAULT '[]'::jsonb` in DDL; NO String-JSON/ObjectMapper idiom — Tasks 1, 3. ✓ (The handoff §5 claim of "String JSON + ObjectMapper" is contradicted by the extraction grep and the locked decision; the plan follows the locked decision/extraction.)
- Enums `LinkAuthority {PRIMARY,SECONDARY,BACKGROUND}` + `Surface {PUBLIC,INTERNAL,BOTH}`, plain, `@Enumerated(STRING) @Column(length=50)` — Tasks 2, 3. ✓
- Mutable entity combining `MortgageDocument` shape + `created_by`/`updated_by`; protected + public ctors; `created_at` `updatable=false` getter-only; `created_by` getter-only — Task 3. ✓
- Repository with `findAllByOrderByCreatedAtDescIdDesc`, `findByActiveTrueOrderByCreatedAtDescIdDesc`, `countByActiveTrue`, `@Repository`, derived finders bind to `active` — Task 4. ✓
- Service: full CRUD + cached `activeLinks()` mirroring VocabularyService EXACTLY (10s TTL, `Long.MIN_VALUE` sentinel checked before subtraction, volatile, `invalidate()` on every write), `findById().orElseThrow(...)`, `.strip()`, `isBlank()` rejection, `@Transactional delete` (hard), `valueOf` for enums — Task 6. ✓
- DTOs: `SourceLinkDto` (snake_case `@JsonProperty`, `active`, `from()` factory) + `SourceLinkRequest` (String enums, `List<String>`, manual validation, no `@Valid`) — Task 5. ✓
- Controller `/api/ai/admin/source-links`, all verbs delegating, shared setActive path, `UPDATED_BY="admin-api"`, `IllegalArgumentException` → existing global 400, no try/catch — Task 7. ✓
- Pack: `source-links.yaml` real seed (Fannie/Freddie/HUD/VA/USDA), kebab-case keys 1:1 with `SourceLinkFile`, enum names exact — Task 8. ✓
- Loader: `readOptional()` (null on absent), `SourceLinksFile`/`SourceLinkFile`, positional `DomainPack` component shift, compact-ctor default `List.of()` (not null-preserving), no `require(...)` non-empty, Javadoc updated — Task 9. ✓
- Golden test: new `sourceLinksMatchSeed()` literal written FIRST (red→green: fails on the loader's temporary empty `List.of()`, greens once the loader reads the YAML), existing counts intact, no positional `new DomainPack` in the golden test — Task 9. ✓
- First-boot seeder: `ApplicationRunner`, seeds only when `count()==0` AND pack has links, idempotent, empty pack → no seed → today's behavior; locked by a pure-Mockito `SourceLinkSeederTest` (failing-test-first, no Spring/Docker) — Task 10. ✓
- Dashboard: `SourceLinks.tsx` (Corpus row-CRUD: tbl/Pill/row-actions/busy/collapsible create/edit modal with overlay+stopPropagation/delete-confirm/activate-deactivate), reuses existing `api` methods, existing `Pill` tones only, nullable round-trip, no `onCorpusChanged`, only `reload()` — Task 12. Types — Task 11. App.tsx three edits — Task 13. Every dashboard verify gates on `npm run check` (tsc --noEmit) alongside `npm run build`. ✓
- No-regression gate (full suite + golden + dashboard) — Task 14; backward-compat contract stated up top and re-verified. ✓

**Placeholder scan:** Every code step shows complete, copy-paste-ready code. No "TBD" / "add validation" / "similar to Task N" / "handle edge cases" placeholders. Exact gradle/npm commands with expected output on every step. ✓

**Type-name consistency across tasks:** `BrainSourceLink`, `LinkAuthority`, `Surface`, `BrainSourceLinkRepository`, `SourceLinkService`, `SourceLinkDto`, `SourceLinkRequest`, `AdminSourceLinkController`, `SourceLinkSeeder`, `DomainPack.SourceLink`, `SourceLinksFile`/`SourceLinkFile` — used identically in every task (entity ctor arg order matches across entity, repo test, service, service test, seeder; DTO field order matches across DTO, controller test, dashboard interface). Endpoint base `/api/ai/admin/source-links` identical in controller, controller test, and dashboard. Enum names `PRIMARY/SECONDARY/BACKGROUND`, `PUBLIC/INTERNAL/BOTH` identical in enum, YAML, golden test, dashboard. ✓

**Judgment calls flagged:** (1) Handoff §5 says jsonb-as-String/ObjectMapper — contradicted by extraction grep + locked decision; followed locked decision (`@JdbcTypeCode`). (2) `id` DEFAULT: matched V6/V7 (no `DEFAULT gen_random_uuid()`), per the locked "match V6/V7" instruction. (3) Golden test "add the literal": the golden test never positionally constructs `DomainPack` and did not assert source links before, so a NEW `sourceLinksMatchSeed()` test method is the faithful way to "add the literal" without weakening existing asserts; it is written FIRST (Task 9, red→green) so the loader change is driven by a failing assertion rather than locked after the fact. (4) Seeder has no dedicated Spring-context test (no such pattern exists in-repo and it would be heavyweight); its branch logic + enum mapping are instead locked by a pure-Mockito `SourceLinkSeederTest` (Task 10, failing-test-first), and the live wiring is covered by Task 14's boot.
