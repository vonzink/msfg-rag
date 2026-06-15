# Phase 1a — Corpus Document CRUD (add / edit / delete) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the admin dashboard full document CRUD — upload a new document, edit a document's metadata, and hard-delete a document (file + chunks + row) — on top of the existing view/sync/reindex/activate Corpus screen.

**Architecture:** Insert, don't rewrite. The backend already has `POST /api/ai/documents/upload` (add) and list/reindex/activate. We add two endpoints (`PATCH` edit, `DELETE` hard-delete) and one service method (`DocumentIngestionService.delete`), then extend the existing `Corpus.tsx` screen with an upload form, a per-row edit modal, and a per-row delete-with-confirm. Not-found is signalled with `IllegalArgumentException` → HTTP 400 to match the existing `setActive`/`reindex` convention and the `GlobalExceptionHandler`.

**Tech Stack:** Java 21 / Spring Boot 3.5, JUnit 5 + Mockito (controller tests are plain constructor + mocks — no MockMvc). React 18 + Vite 5 + TS, Vitest for the API client.

**Prerequisites / coordination:**
- Repo root today: `/Users/zacharyzink/MSFG/msfg-rag` (relocation to `/Users/zacharyzink/rag-brain` is Phase 0, not done — build here; it rides along).
- A parallel agent is executing the Phase 4.7 **Vocabulary** plan on branch `feat/phase4.7-vocabulary-layer`. **Do NOT build on that branch.** Branch off `main`: `git checkout main && git checkout -b feat/phase1a-corpus-crud`. The only files this plan shares with the vocab work are `dashboard/src/api.ts` and `dashboard/src/types.ts` (both append-only additions — trivial merge). Everything else is disjoint.
- Suite green before starting: `./gradlew test --console=plain` and `cd dashboard && npm test`.
- Local runs use `--server.port=8090`. Never port 8080.

**Out of scope (Phase 1b, separate plan):** assigning/changing the corpus folder/S3 location at runtime + reindex-all. **Also note:** editing a document's metadata updates the `brain_documents` row but does NOT rewrite the metadata already embedded in its existing `brain_document_chunks`; those refresh on **Reindex** (existing button). The edit modal will say so.

---

## File structure

| File | Action | Responsibility |
|---|---|---|
| `src/main/java/com/msfg/rag/service/ingestion/DocumentIngestionService.java` | modify | add `delete(UUID)` — remove chunks + file + row |
| `src/test/java/com/msfg/rag/service/ingestion/DocumentIngestionServiceTest.java` | create | unit-test `delete` |
| `src/main/java/com/msfg/rag/dto/DocumentUpdateRequest.java` | create | request body for metadata edit |
| `src/main/java/com/msfg/rag/controller/DocumentAdminController.java` | modify | add `PATCH /{id}` and `DELETE /{id}` |
| `src/test/java/com/msfg/rag/controller/DocumentAdminControllerCrudTest.java` | create | unit-test the two new endpoints |
| `dashboard/src/api.ts` | modify | add `patch` + `del` helpers |
| `dashboard/src/api.test.ts` | modify | test `patch` + `del` |
| `dashboard/src/types.ts` | modify | add `DocumentUpdate` |
| `dashboard/src/screens/Corpus.tsx` | modify | add upload form, edit modal, delete-with-confirm |

---

### Task 1: `DocumentIngestionService.delete(UUID)` — hard delete

**Files:**
- Modify: `src/main/java/com/msfg/rag/service/ingestion/DocumentIngestionService.java`
- Test: `src/test/java/com/msfg/rag/service/ingestion/DocumentIngestionServiceTest.java` (create)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/msfg/rag/service/ingestion/DocumentIngestionServiceTest.java`:

```java
package com.msfg.rag.service.ingestion;

import com.msfg.rag.domain.MortgageDocument;
import com.msfg.rag.domain.SourceType;
import com.msfg.rag.repository.DocumentChunkRepository;
import com.msfg.rag.repository.MortgageDocumentRepository;
import com.msfg.rag.service.storage.StorageService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIngestionServiceTest {

    private final StorageService storageService = mock(StorageService.class);
    private final MortgageDocumentRepository documentRepository = mock(MortgageDocumentRepository.class);
    private final DocumentChunkRepository chunkRepository = mock(DocumentChunkRepository.class);

    private final DocumentIngestionService service = new DocumentIngestionService(
            storageService,
            mock(TextExtractionService.class),
            mock(ChunkingService.class),
            mock(EmbeddingService.class),
            documentRepository,
            chunkRepository);

    private MortgageDocument doc(UUID id, String key) {
        MortgageDocument d = new MortgageDocument();
        d.setTitle("HUD 4000.1");
        d.setSourceName("HUD");
        d.setSourceType(SourceType.INTERNAL_POLICY);
        d.setFileName("hud.pdf");
        d.setS3Key(key);
        return d;
    }

    @Test
    void deleteRemovesChunksThenFileThenRow() {
        UUID id = UUID.randomUUID();
        MortgageDocument d = doc(id, "abc_hud.pdf");
        when(documentRepository.findById(id)).thenReturn(Optional.of(d));

        service.delete(id);

        var order = inOrder(chunkRepository, storageService, documentRepository);
        order.verify(chunkRepository).deleteByDocumentId(id);
        order.verify(storageService).delete("abc_hud.pdf");
        order.verify(documentRepository).delete(d);
    }

    @Test
    void deleteThrowsWhenDocumentMissing() {
        UUID id = UUID.randomUUID();
        when(documentRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.delete(id));
        verify(chunkRepository, org.mockito.Mockito.never()).deleteByDocumentId(any());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.msfg.rag.service.ingestion.DocumentIngestionServiceTest' --console=plain`
Expected: FAIL — `delete(UUID)` method does not exist (compile error).

- [ ] **Step 3: Implement `delete` in `DocumentIngestionService.java`**

Add this method directly after `reindex(...)` (around line 108). It reuses the existing `documentRepository`, `chunkRepository`, and `storageService` fields:

```java
    /**
     * Hard-deletes a document: its chunks, its stored file, and the row.
     * Chunk + row removals are transactional; the file delete is idempotent
     * ({@code deleteIfExists}) and runs last so a missing file never blocks
     * the DB cleanup.
     */
    @Transactional
    public void delete(UUID documentId) {
        MortgageDocument document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        chunkRepository.deleteByDocumentId(documentId);
        String storageKey = document.getS3Key();
        documentRepository.delete(document);
        if (storageKey != null) {
            storageService.delete(storageKey);
        }
        log.info("Deleted document '{}' ({})", document.getTitle(), document.getFileName());
    }
```

(`UUID`, `MortgageDocument`, `@Transactional`, and `log` are already imported/declared in this file.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.msfg.rag.service.ingestion.DocumentIngestionServiceTest' --console=plain`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/msfg/rag/service/ingestion/DocumentIngestionService.java \
        src/test/java/com/msfg/rag/service/ingestion/DocumentIngestionServiceTest.java
git commit -m "feat(corpus): hard-delete service method (chunks + file + row)"
```

---

### Task 2: `DELETE /api/ai/documents/{id}` endpoint

**Files:**
- Modify: `src/main/java/com/msfg/rag/controller/DocumentAdminController.java`
- Test: `src/test/java/com/msfg/rag/controller/DocumentAdminControllerCrudTest.java` (create)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/msfg/rag/controller/DocumentAdminControllerCrudTest.java`:

```java
package com.msfg.rag.controller;

import com.msfg.rag.repository.MortgageDocumentRepository;
import com.msfg.rag.service.ingestion.DocumentIngestionService;
import com.msfg.rag.service.retrieval.RetrievalService;
import com.msfg.rag.service.sync.SyncService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DocumentAdminControllerCrudTest {

    private final DocumentIngestionService ingestionService = mock(DocumentIngestionService.class);
    private final MortgageDocumentRepository documentRepository = mock(MortgageDocumentRepository.class);
    private final DocumentAdminController controller = new DocumentAdminController(
            ingestionService,
            documentRepository,
            mock(RetrievalService.class),
            mock(SyncService.class));

    @Test
    void deleteDelegatesToIngestionServiceAndReturnsOk() {
        UUID id = UUID.randomUUID();

        var response = controller.delete(id);

        verify(ingestionService).delete(id);
        assertEquals(200, response.getStatusCode().value());
        assertTrue(Boolean.TRUE.equals(response.getBody().get("deleted")));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'com.msfg.rag.controller.DocumentAdminControllerCrudTest' --console=plain`
Expected: FAIL — `controller.delete(UUID)` does not exist (compile error).

- [ ] **Step 3: Implement the endpoint in `DocumentAdminController.java`**

Add these imports if not already present (the file already imports `DeleteMapping`? it does not — add it; `PatchMapping` is added in Task 3):

```java
import org.springframework.web.bind.annotation.DeleteMapping;
```

Add this method after `deactivate(...)` (around line 102), before the `sync` method:

```java
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable UUID id) {
        ingestionService.delete(id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }
```

(`ResponseEntity`, `Map`, `PathVariable`, `UUID` are already imported.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'com.msfg.rag.controller.DocumentAdminControllerCrudTest' --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/msfg/rag/controller/DocumentAdminController.java \
        src/test/java/com/msfg/rag/controller/DocumentAdminControllerCrudTest.java
git commit -m "feat(corpus): DELETE /api/ai/documents/{id} hard-delete endpoint"
```

---

### Task 3: `PATCH /api/ai/documents/{id}` — edit metadata

**Files:**
- Create: `src/main/java/com/msfg/rag/dto/DocumentUpdateRequest.java`
- Modify: `src/main/java/com/msfg/rag/controller/DocumentAdminController.java`
- Test: `src/test/java/com/msfg/rag/controller/DocumentAdminControllerCrudTest.java` (add cases)

- [ ] **Step 1: Write the failing tests** — add these methods to `DocumentAdminControllerCrudTest`:

```java
    @org.junit.jupiter.api.Test
    void updateAppliesMetadataAndSaves() {
        UUID id = UUID.randomUUID();
        com.msfg.rag.domain.MortgageDocument doc = new com.msfg.rag.domain.MortgageDocument();
        doc.setTitle("old");
        doc.setSourceName("old");
        doc.setSourceType(com.msfg.rag.domain.SourceType.EXTERNAL_VENDOR);
        doc.setFileName("f.pdf");
        org.mockito.Mockito.when(documentRepository.findById(id))
                .thenReturn(java.util.Optional.of(doc));
        org.mockito.Mockito.when(documentRepository.save(doc)).thenReturn(doc);

        var req = new com.msfg.rag.dto.DocumentUpdateRequest(
                "New Title", "HUD", "INTERNAL_POLICY", "v2",
                java.time.LocalDate.parse("2026-01-01"), null);

        var dto = controller.update(id, req).getBody();

        assertEquals("New Title", doc.getTitle());
        assertEquals("HUD", doc.getSourceName());
        assertEquals(com.msfg.rag.domain.SourceType.INTERNAL_POLICY, doc.getSourceType());
        assertEquals("v2", doc.getDocumentVersion());
        assertEquals(java.time.LocalDate.parse("2026-01-01"), doc.getEffectiveDate());
        assertEquals("New Title", dto.title());
        verify(documentRepository).save(doc);
    }

    @org.junit.jupiter.api.Test
    void updateRejectsBlankTitle() {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.when(documentRepository.findById(id))
                .thenReturn(java.util.Optional.of(new com.msfg.rag.domain.MortgageDocument()));
        var req = new com.msfg.rag.dto.DocumentUpdateRequest(
                "   ", "HUD", "INTERNAL_POLICY", null, null, null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.update(id, req));
    }

    @org.junit.jupiter.api.Test
    void updateRejectsUnknownSourceType() {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.when(documentRepository.findById(id))
                .thenReturn(java.util.Optional.of(new com.msfg.rag.domain.MortgageDocument()));
        var req = new com.msfg.rag.dto.DocumentUpdateRequest(
                "T", "HUD", "NOPE", null, null, null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.update(id, req));
    }

    @org.junit.jupiter.api.Test
    void updateThrowsWhenDocumentMissing() {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.when(documentRepository.findById(id))
                .thenReturn(java.util.Optional.empty());
        var req = new com.msfg.rag.dto.DocumentUpdateRequest(
                "T", "HUD", "INTERNAL_POLICY", null, null, null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> controller.update(id, req));
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests 'com.msfg.rag.controller.DocumentAdminControllerCrudTest' --console=plain`
Expected: FAIL — `DocumentUpdateRequest` and `controller.update(...)` do not exist (compile error).

- [ ] **Step 3: Create the request DTO** `src/main/java/com/msfg/rag/dto/DocumentUpdateRequest.java`:

```java
package com.msfg.rag.dto;

import java.time.LocalDate;

/**
 * Editable metadata for an existing document. The file itself is never
 * changed here — re-upload + reindex handles content changes.
 * sourceType is a string so an unknown value yields a clean 400 via
 * SourceType.valueOf(...) rather than a Jackson 500.
 */
public record DocumentUpdateRequest(
        String title,
        String sourceName,
        String sourceType,
        String documentVersion,
        LocalDate effectiveDate,
        LocalDate expirationDate
) {}
```

- [ ] **Step 4: Implement the endpoint** in `DocumentAdminController.java`.

Add imports:

```java
import com.msfg.rag.dto.DocumentUpdateRequest;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
```

Add this method right after the `delete(...)` method from Task 2:

```java
    @PatchMapping("/{id}")
    public ResponseEntity<DocumentDto> update(@PathVariable UUID id,
                                              @RequestBody DocumentUpdateRequest req) {
        MortgageDocument document = documentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + id));

        if (req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (req.sourceName() == null || req.sourceName().isBlank()) {
            throw new IllegalArgumentException("sourceName is required");
        }
        // Throws IllegalArgumentException (→ 400) on an unknown enum value.
        SourceType type = SourceType.valueOf(req.sourceType());

        document.setTitle(req.title().strip());
        document.setSourceName(req.sourceName().strip());
        document.setSourceType(type);
        document.setDocumentVersion(req.documentVersion());
        document.setEffectiveDate(req.effectiveDate());
        document.setExpirationDate(req.expirationDate());

        return ResponseEntity.ok(DocumentDto.from(documentRepository.save(document)));
    }
```

(`MortgageDocument`, `SourceType`, `DocumentDto`, `ResponseEntity`, `PathVariable`, `UUID` are already imported.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'com.msfg.rag.controller.DocumentAdminControllerCrudTest' --console=plain`
Expected: PASS (all 5 methods).

- [ ] **Step 6: Run the full backend suite (no regressions)**

Run: `./gradlew test --console=plain`
Expected: PASS (green, including the existing golden-pack and sync tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/msfg/rag/dto/DocumentUpdateRequest.java \
        src/main/java/com/msfg/rag/controller/DocumentAdminController.java \
        src/test/java/com/msfg/rag/controller/DocumentAdminControllerCrudTest.java
git commit -m "feat(corpus): PATCH /api/ai/documents/{id} edit metadata endpoint"
```

---

### Task 4: Frontend API client — `patch` + `del`

**Files:**
- Modify: `dashboard/src/api.ts`
- Test: `dashboard/src/api.test.ts`

- [ ] **Step 1: Write the failing tests** — add to `dashboard/src/api.test.ts` inside the `describe("api client", ...)` block:

```ts
  it("sends a PATCH with body and the admin key", async () => {
    adminKey.set("k");
    const fetchMock = fetchReturning(200, { id: "1", title: "New" });
    vi.stubGlobal("fetch", fetchMock);

    await api.patch("/api/ai/documents/1", { title: "New" });

    const init = (fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect(init.method).toBe("PATCH");
    expect(init.body).toBe(JSON.stringify({ title: "New" }));
    expect(new Headers(init.headers).get("X-Admin-Api-Key")).toBe("k");
  });

  it("sends a DELETE with the admin key", async () => {
    adminKey.set("k");
    const fetchMock = fetchReturning(200, { deleted: true });
    vi.stubGlobal("fetch", fetchMock);

    await api.del("/api/ai/documents/1");

    const init = (fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1];
    expect(init.method).toBe("DELETE");
    expect(new Headers(init.headers).get("X-Admin-Api-Key")).toBe("k");
  });
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd dashboard && npm test -- --run api.test.ts`
Expected: FAIL — `api.patch` / `api.del` are not functions.

- [ ] **Step 3: Implement** — in `dashboard/src/api.ts`, add two methods to the exported `api` object, after `put` and before `upload`:

```ts
  patch: <T>(path: string, body: unknown) =>
    request<T>(path, { method: "PATCH", body: JSON.stringify(body) }),
  del: <T = void>(path: string) =>
    request<T>(path, { method: "DELETE" }),
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd dashboard && npm test -- --run api.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add dashboard/src/api.ts dashboard/src/api.test.ts
git commit -m "feat(corpus): api client patch + del helpers"
```

---

### Task 5: Corpus screen — Add-document upload form

**Files:**
- Modify: `dashboard/src/screens/Corpus.tsx`

This task adds a collapsible "Add document" form that posts multipart to the existing
`POST /api/ai/documents/upload` via `api.upload`. No backend change.

- [ ] **Step 1: Add upload state + handler.** In `Corpus.tsx`, inside the component, after the existing `const [error, setError] = useState<string | null>(null);` line, add:

```tsx
  const [showAdd, setShowAdd] = useState(false);
  const [addBusy, setAddBusy] = useState(false);
  const [addFile, setAddFile] = useState<File | null>(null);
  const [addTitle, setAddTitle] = useState("");
  const [addSourceName, setAddSourceName] = useState("");
  const [addSourceType, setAddSourceType] = useState("INTERNAL_POLICY");
  const [addEffectiveDate, setAddEffectiveDate] = useState("");

  async function submitAdd(e: React.FormEvent) {
    e.preventDefault();
    if (!addFile || !addTitle.trim() || !addSourceName.trim()) return;
    setAddBusy(true);
    setError(null);
    try {
      const form = new FormData();
      form.append("file", addFile);
      form.append("title", addTitle.trim());
      form.append("sourceName", addSourceName.trim());
      form.append("sourceType", addSourceType);
      if (addEffectiveDate) form.append("effectiveDate", addEffectiveDate);
      await api.upload("/api/ai/documents/upload", form);
      setShowAdd(false);
      setAddFile(null); setAddTitle(""); setAddSourceName(""); setAddEffectiveDate("");
      reload(); onCorpusChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setAddBusy(false);
    }
  }
```

Add the React import at the top if not present (the file currently imports hooks from `"react"`); change the first import line to also bring in the `React` namespace used by `React.FormEvent`:

```tsx
import React, { useCallback, useEffect, useState } from "react";
```

- [ ] **Step 2: Add the "Add document" button to the header.** In the `<div className="actions">` block, add a button before the Dry-run button:

```tsx
          <button onClick={() => setShowAdd((v) => !v)} disabled={busy !== null}>
            {showAdd ? "Cancel" : "Add document"}
          </button>
```

- [ ] **Step 3: Render the form.** Immediately after `<ErrorNote message={error} />`, add:

```tsx
      {showAdd && (
        <form className="card" onSubmit={submitAdd} style={{ display: "grid", gap: 8, marginBottom: 12 }}>
          <input type="file" required
                 onChange={(e) => setAddFile(e.target.files?.[0] ?? null)} />
          <input placeholder="Title" value={addTitle}
                 onChange={(e) => setAddTitle(e.target.value)} required />
          <input placeholder="Source name (e.g. HUD)" value={addSourceName}
                 onChange={(e) => setAddSourceName(e.target.value)} required />
          <select value={addSourceType} onChange={(e) => setAddSourceType(e.target.value)}>
            <option value="INTERNAL_POLICY">internal policy</option>
            <option value="EXTERNAL_VENDOR">external vendor</option>
          </select>
          <input type="date" value={addEffectiveDate}
                 onChange={(e) => setAddEffectiveDate(e.target.value)} />
          <button className="btn-primary" type="submit"
                  disabled={addBusy || !addFile || !addTitle.trim() || !addSourceName.trim()}>
            {addBusy ? "Uploading…" : "Upload & ingest"}
          </button>
        </form>
      )}
```

- [ ] **Step 4: Build to verify it type-checks**

Run: `cd dashboard && npm run build`
Expected: builds clean (tsc + vite), no type errors.

- [ ] **Step 5: Commit**

```bash
git add dashboard/src/screens/Corpus.tsx
git commit -m "feat(corpus): add-document upload form on Corpus screen"
```

---

### Task 6: Corpus screen — Edit-metadata modal

**Files:**
- Modify: `dashboard/src/types.ts`, `dashboard/src/screens/Corpus.tsx`

- [ ] **Step 1: Add the request type** to `dashboard/src/types.ts` (append at end of file):

```ts
export interface DocumentUpdate {
  title: string; sourceName: string; sourceType: string;
  documentVersion: string | null;
  effectiveDate: string | null; expirationDate: string | null;
}
```

- [ ] **Step 2: Add edit state + handlers** in `Corpus.tsx`, after the add-form state from Task 5:

```tsx
  const [editing, setEditing] = useState<DocumentDto | null>(null);
  const [editBusy, setEditBusy] = useState(false);
  const [editForm, setEditForm] = useState<DocumentUpdate>({
    title: "", sourceName: "", sourceType: "INTERNAL_POLICY",
    documentVersion: null, effectiveDate: null, expirationDate: null,
  });

  function openEdit(d: DocumentDto) {
    setEditing(d);
    setEditForm({
      title: d.title, sourceName: d.sourceName, sourceType: d.sourceType,
      documentVersion: d.documentVersion, effectiveDate: d.effectiveDate,
      expirationDate: d.expirationDate,
    });
  }

  async function submitEdit(e: React.FormEvent) {
    e.preventDefault();
    if (!editing || !editForm.title.trim() || !editForm.sourceName.trim()) return;
    setEditBusy(true);
    setError(null);
    try {
      await api.patch(`/api/ai/documents/${editing.id}`, editForm);
      setEditing(null);
      reload(); onCorpusChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setEditBusy(false);
    }
  }
```

Update the import from `../types` to include `DocumentUpdate`:

```tsx
import { DocumentDto, DocumentUpdate, Stats, SyncReport } from "../types";
```

- [ ] **Step 3: Add an "Edit" button** to each row's `.row-actions` cell, before the Reindex button:

```tsx
                <button onClick={() => openEdit(d)}>Edit</button>
```

- [ ] **Step 4: Render the edit modal.** Add just before the closing `</>` of the returned JSX:

```tsx
      {editing && (
        <div className="modal-overlay" onClick={() => setEditing(null)}>
          <form className="card" onClick={(e) => e.stopPropagation()} onSubmit={submitEdit}
                style={{ display: "grid", gap: 8, maxWidth: 460, margin: "10vh auto" }}>
            <h3 style={{ margin: 0 }}>Edit document</h3>
            <input placeholder="Title" value={editForm.title}
                   onChange={(e) => setEditForm({ ...editForm, title: e.target.value })} required />
            <input placeholder="Source name" value={editForm.sourceName}
                   onChange={(e) => setEditForm({ ...editForm, sourceName: e.target.value })} required />
            <select value={editForm.sourceType}
                    onChange={(e) => setEditForm({ ...editForm, sourceType: e.target.value })}>
              <option value="INTERNAL_POLICY">internal policy</option>
              <option value="EXTERNAL_VENDOR">external vendor</option>
            </select>
            <input placeholder="Version" value={editForm.documentVersion ?? ""}
                   onChange={(e) => setEditForm({ ...editForm, documentVersion: e.target.value || null })} />
            <input type="date" value={editForm.effectiveDate ?? ""}
                   onChange={(e) => setEditForm({ ...editForm, effectiveDate: e.target.value || null })} />
            <input type="date" value={editForm.expirationDate ?? ""}
                   onChange={(e) => setEditForm({ ...editForm, expirationDate: e.target.value || null })} />
            <p className="muted" style={{ fontSize: 12 }}>
              Updates the document record. Existing search chunks keep their old metadata
              until you <strong>Reindex</strong> this document.
            </p>
            <div style={{ display: "flex", gap: 8 }}>
              <button className="btn-primary" type="submit"
                      disabled={editBusy || !editForm.title.trim() || !editForm.sourceName.trim()}>
                {editBusy ? "Saving…" : "Save"}
              </button>
              <button type="button" onClick={() => setEditing(null)}>Cancel</button>
            </div>
          </form>
        </div>
      )}
```

- [ ] **Step 5: Build to verify it type-checks**

Run: `cd dashboard && npm run build`
Expected: builds clean.

- [ ] **Step 6: Commit**

```bash
git add dashboard/src/types.ts dashboard/src/screens/Corpus.tsx
git commit -m "feat(corpus): edit-metadata modal on Corpus screen"
```

---

### Task 7: Corpus screen — Delete with confirm

**Files:**
- Modify: `dashboard/src/screens/Corpus.tsx`

- [ ] **Step 1: Add the delete handler** in `Corpus.tsx`, after `submitEdit`:

```tsx
  async function remove(d: DocumentDto) {
    if (!window.confirm(`Delete "${d.title}"? This removes the file, its search chunks, and the record. This cannot be undone.`)) {
      return;
    }
    setBusy(d.id);
    setError(null);
    try {
      await api.del(`/api/ai/documents/${d.id}`);
      reload(); onCorpusChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  }
```

- [ ] **Step 2: Add a "Delete" button** to each row's `.row-actions` cell, after the Activate/Deactivate button:

```tsx
                <button className="danger" onClick={() => remove(d)} disabled={busy === d.id}>
                  Delete
                </button>
```

- [ ] **Step 3: Build to verify it type-checks**

Run: `cd dashboard && npm run build`
Expected: builds clean.

- [ ] **Step 4: Commit**

```bash
git add dashboard/src/screens/Corpus.tsx
git commit -m "feat(corpus): delete-with-confirm on Corpus screen"
```

---

### Task 8: End-to-end verification

**Files:** none (verification only).

- [ ] **Step 1: Full backend suite green**

Run: `./gradlew clean test --console=plain` (Docker up for any Testcontainers tests).
Expected: green.

- [ ] **Step 2: Frontend tests + build green**

Run: `cd dashboard && npm test -- --run && npm run build`
Expected: all vitest pass; build clean.

- [ ] **Step 3: Live smoke** (optional but recommended). Boot the brain + dashboard:

Run: `~/MSFG/msfg-rag/start.sh` (Docker Desktop running). Wait for `Started MsfgRagApplication`, dashboard at http://localhost:5173. Unlock with the admin key from `.env` (`ADMIN_API_KEY`). On **Corpus**:
  - **Add:** click "Add document", choose a small `.md`/`.txt`, fill Title + Source name, Upload & ingest → row appears, chunk count rises.
  - **Edit:** click "Edit" on that row, change the Title + effective date, Save → row reflects the new title.
  - **Delete:** click "Delete", confirm → row disappears; All-docs / Chunks stats drop. Confirm the file is gone from the corpus folder (`ls ./data/documents`).

- [ ] **Step 4: Report.** Summarize: add/edit/delete working end-to-end; full suite + dashboard build green; no regressions to existing view/sync/reindex/activate.

---

## Self-review (completed by plan author)

- **Spec coverage (§7.1 Corpus management):** "view" (exists), **add** (Task 5 UI → existing upload API), **edit** (Task 3 PATCH + Task 6 modal), **delete** (Task 1 service + Task 2 DELETE + Task 7 UI). "Assign location" is explicitly deferred to Phase 1b with rationale. ✓
- **Placeholders:** none — every step has complete code or an exact command + expected result. ✓
- **Type/signature consistency:** `DocumentIngestionService.delete(UUID)`, `DocumentAdminController.delete(UUID)` → `ResponseEntity<Map<String,Object>>`, `update(UUID, DocumentUpdateRequest)` → `ResponseEntity<DocumentDto>`, `DocumentUpdateRequest(title, sourceName, sourceType:String, documentVersion, effectiveDate, expirationDate)`, frontend `api.patch`/`api.del`, `DocumentUpdate` type — all consistent across backend tasks, the DTO, and the frontend. `SourceType` values used: `INTERNAL_POLICY`, `EXTERNAL_VENDOR` (match the enum). ✓
- **Convention match:** not-found → `IllegalArgumentException` → 400 (matches existing `setActive`/`reindex`); controller tests are plain constructor + Mockito (matches `DocumentAdminControllerSyncTest`); api tests stub `fetch` (matches `api.test.ts`). ✓
- **Known risks flagged for execution:** (1) editing metadata does not rewrite existing chunk metadata until reindex — surfaced in the edit modal copy and the scope note; (2) `.danger`/`.modal-overlay` CSS classes are used — if absent in `styles.css`, the buttons/modal still function (unstyled); a follow-up can add styling. (3) Build on `feat/phase1a-corpus-crud` off `main`, not the vocab branch.
