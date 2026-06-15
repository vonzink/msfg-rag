import { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import { ErrorNote, Pill } from "../components";
import { VocabRevisionDto, VocabState } from "../types";

export default function Vocabulary() {
  const [state, setState] = useState<VocabState | null>(null);
  const [draft, setDraft] = useState("");
  const [loadError, setLoadError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [reverting, setReverting] = useState(false);

  const [histOpen, setHistOpen] = useState(false);
  const [history, setHistory] = useState<VocabRevisionDto[] | null>(null);
  const [histError, setHistError] = useState<string | null>(null);

  const [phrase, setPhrase] = useState("owner occupied duplex");
  const [expanded, setExpanded] = useState<string | null>(null);
  const [prevError, setPrevError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoadError(null);
    try {
      const data = await api.get<VocabState>("/api/ai/admin/vocabulary");
      setState(data);
      setDraft(data.content);
    } catch (e) {
      setLoadError((e as Error).message);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  const dirty = state !== null && draft !== state.content;

  async function save() {
    setSaving(true); setError(null);
    try {
      const fresh = await api.put<VocabState>("/api/ai/admin/vocabulary", { content: draft });
      setState(fresh); setDraft(fresh.content);
    } catch (e) { setError((e as Error).message); }
    finally { setSaving(false); }
  }

  async function revert() {
    setReverting(true); setError(null);
    try {
      const fresh = await api.post<VocabState>("/api/ai/admin/vocabulary/revert", {});
      setState(fresh); setDraft(fresh.content); setHistory(null);
    } catch (e) { setError((e as Error).message); }
    finally { setReverting(false); }
  }

  async function toggleHistory() {
    if (histOpen) { setHistOpen(false); return; }
    setHistOpen(true);
    if (history !== null) return;
    setHistError(null);
    try {
      setHistory(await api.get<VocabRevisionDto[]>("/api/ai/admin/vocabulary/history"));
    } catch (e) { setHistError((e as Error).message); }
  }

  async function testPhrase() {
    setPrevError(null); setExpanded(null);
    try {
      const data = await api.get<{ original: string; expanded: string }>(
        `/api/ai/admin/vocabulary/preview?q=${encodeURIComponent(phrase)}`);
      setExpanded(data.expanded);
    } catch (e) { setPrevError((e as Error).message); }
  }

  return (
    <>
      <header className="screen-head">
        <h1>Vocabulary</h1>
        <span className="muted">borrower words → guideline words · live within ~10 s</span>
      </header>

      <p className="muted" style={{ marginBottom: 12 }}>
        Translates the words people use ("duplex", "owner occupied") into the words the
        guidelines use ("2-unit", "principal residence") so retrieval finds the right row.
        One <code>term =&gt; expansion</code> per line. This is search-time only — it never
        changes what the model is allowed to say.
      </p>

      <ErrorNote message={loadError} />
      {state === null && !loadError && <p className="muted">Loading…</p>}

      {state !== null && (
        <div className="card" style={{ marginBottom: 16 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 12 }}>
            <span style={{ fontWeight: 600, fontSize: 14 }}>Synonym list</span>
            {state.source === "custom"
              ? <Pill tone="amber">custom</Pill>
              : <Pill tone="gray">pack default</Pill>}
            <Pill tone="gray">{state.entries} terms</Pill>
            {state.updatedBy && (
              <span className="muted" style={{ marginLeft: "auto" }}>
                last by {state.updatedBy}
                {state.updatedAt ? ` · ${new Date(state.updatedAt).toLocaleString()}` : ""}
              </span>
            )}
          </div>

          <textarea className="rulebox" value={draft} onChange={(e) => setDraft(e.target.value)} />
          <ErrorNote message={error} />

          <div style={{ display: "flex", gap: 8, marginTop: 10, flexWrap: "wrap" }}>
            <button className="btn-primary" disabled={!dirty || !draft.trim() || saving} onClick={save}>
              {saving ? "Saving…" : "Save as new revision"}
            </button>
            {state.source === "custom" && (
              <button disabled={reverting} onClick={revert}>
                {reverting ? "Reverting…" : "Revert to pack default"}
              </button>
            )}
            <button onClick={toggleHistory}>{histOpen ? "Hide history" : "History"}</button>
          </div>

          {histOpen && (
            <div style={{ marginTop: 12 }}>
              <ErrorNote message={histError} />
              {history === null && !histError && <p className="muted">Loading…</p>}
              {history !== null && history.length === 0 && <p className="muted">No revisions yet.</p>}
              {history !== null && history.map((rev) => (
                <div className="hist-row" key={rev.revision}>
                  <span className="muted" style={{ minWidth: 60 }}>rev {rev.revision}</span>
                  <span className="muted">{new Date(rev.createdAt).toLocaleString()}</span>
                  <span className="muted">{rev.createdBy}</span>
                  {rev.reverted && <Pill tone="gray">revert to pack</Pill>}
                  <button
                    style={{ marginLeft: "auto", height: 28, padding: "0 10px", fontSize: 12 }}
                    disabled={rev.content === null}
                    onClick={() => rev.content !== null && setDraft(rev.content)}>
                    Restore
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      <div className="card">
        <div style={{ fontWeight: 600, fontSize: 14, marginBottom: 10 }}>Test a phrase</div>
        <div className="ask-bar">
          <input value={phrase} onChange={(e) => setPhrase(e.target.value)}
                 onKeyDown={(e) => e.key === "Enter" && testPhrase()} />
          <button className="btn-primary" onClick={testPhrase} disabled={!phrase.trim()}>Expand</button>
        </div>
        <ErrorNote message={prevError} />
        {expanded !== null && (
          <p className="answer" style={{ marginTop: 10 }}>
            <span className="muted">searches as: </span>{expanded}
          </p>
        )}
      </div>
    </>
  );
}
