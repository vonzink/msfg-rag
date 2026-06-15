import React, { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import { DocumentDto, DocumentUpdate, Stats, SyncReport } from "../types";
import { ErrorNote, Pill, Stat } from "../components";

export default function Corpus({ stats, onCorpusChanged }:
    { stats: Stats | null; onCorpusChanged: () => void }) {
  const [docs, setDocs] = useState<DocumentDto[]>([]);
  const [report, setReport] = useState<SyncReport | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [showAdd, setShowAdd] = useState(false);
  const [addBusy, setAddBusy] = useState(false);
  const [addFile, setAddFile] = useState<File | null>(null);
  const [addTitle, setAddTitle] = useState("");
  const [addSourceName, setAddSourceName] = useState("");
  const [addSourceType, setAddSourceType] = useState("AGENCY_GUIDELINE");
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

  const [editing, setEditing] = useState<DocumentDto | null>(null);
  const [editBusy, setEditBusy] = useState(false);
  const [editForm, setEditForm] = useState<DocumentUpdate>({
    title: "", sourceName: "", sourceType: "AGENCY_GUIDELINE",
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
          <button onClick={() => setShowAdd((v) => !v)} disabled={busy !== null}>
            {showAdd ? "Cancel" : "Add document"}
          </button>
          <button onClick={() => runSync(true)} disabled={busy !== null}>
            {busy === "dry" ? "Planning…" : "Dry run"}
          </button>
          <button className="btn-primary" onClick={() => runSync(false)} disabled={busy !== null}>
            {busy === "sync" ? "Syncing…" : "Sync now"}
          </button>
        </div>
      </header>
      <ErrorNote message={error} />
      {showAdd && (
        <form className="card" onSubmit={submitAdd} style={{ display: "grid", gap: 8, marginBottom: 12 }}>
          <input type="file" required
                 onChange={(e) => setAddFile(e.target.files?.[0] ?? null)} />
          <input placeholder="Title" value={addTitle}
                 onChange={(e) => setAddTitle(e.target.value)} required />
          <input placeholder="Source name (e.g. HUD)" value={addSourceName}
                 onChange={(e) => setAddSourceName(e.target.value)} required />
          <select value={addSourceType} onChange={(e) => setAddSourceType(e.target.value)}>
            <option value="AGENCY_GUIDELINE">agency guideline</option>
            <option value="INTERNAL_POLICY">internal policy</option>
            <option value="INVESTOR_OVERLAY">investor overlay</option>
            <option value="EDUCATIONAL">educational</option>
          </select>
          <input type="date" value={addEffectiveDate}
                 onChange={(e) => setAddEffectiveDate(e.target.value)} />
          <button className="btn-primary" type="submit"
                  disabled={addBusy || !addFile || !addTitle.trim() || !addSourceName.trim()}>
            {addBusy ? "Uploading…" : "Upload & ingest"}
          </button>
        </form>
      )}
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
                <button onClick={() => openEdit(d)}>Edit</button>
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
              <option value="AGENCY_GUIDELINE">agency guideline</option>
              <option value="INTERNAL_POLICY">internal policy</option>
              <option value="INVESTOR_OVERLAY">investor overlay</option>
              <option value="EDUCATIONAL">educational</option>
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
    </>
  );
}
