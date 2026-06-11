import { useCallback, useEffect, useState } from "react";
import { api } from "../api";
import { ErrorNote, Pill } from "../components";
import { RuleRevisionDto, RulesResponse, RuleState } from "../types";

// ── per-tier editor ───────────────────────────────────────────────────────────

interface TierProps {
  tier: "hard" | "guidance";
  state: RuleState;
  onSaved: (fresh: RulesResponse) => void;
}

function TierCard({ tier, state, onSaved }: TierProps) {
  const fullKey = tier === "hard" ? "rules.hard" : "rules.guidance";
  const [draft, setDraft]         = useState(state.content);
  const [saving, setSaving]       = useState(false);
  const [reverting, setReverting] = useState(false);
  const [error, setError]         = useState<string | null>(null);
  const [histOpen, setHistOpen]   = useState(false);
  const [history, setHistory]     = useState<RuleRevisionDto[] | null>(null);
  const [histError, setHistError] = useState<string | null>(null);

  // keep draft in sync if parent refreshes
  useEffect(() => { setDraft(state.content); }, [state.content]);

  const dirty = draft !== state.content;

  async function save() {
    setSaving(true);
    setError(null);
    try {
      const fresh = await api.put<RulesResponse>(
        `/api/ai/admin/rules/${fullKey}`, { content: draft });
      onSaved(fresh);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  }

  async function revert() {
    setReverting(true);
    setError(null);
    try {
      const fresh = await api.post<RulesResponse>(
        `/api/ai/admin/rules/${fullKey}/revert`, {});
      onSaved(fresh);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setReverting(false);
    }
  }

  async function toggleHistory() {
    if (histOpen) { setHistOpen(false); return; }
    setHistOpen(true);
    if (history !== null) return; // already fetched
    setHistError(null);
    try {
      const rows = await api.get<RuleRevisionDto[]>(
        `/api/ai/admin/rules/${fullKey}/history`);
      setHistory(rows);
    } catch (e) {
      setHistError((e as Error).message);
    }
  }

  function restoreRevision(content: string | null) {
    if (content === null) return;
    setDraft(content);
  }

  const title   = tier === "hard"
    ? "Hard rules — no wiggle room"
    : "Strong recommendations — guidance";
  const rulePill = tier === "hard"
    ? <Pill tone="amber">must</Pill>
    : <Pill tone="blue">should</Pill>;
  const srcPill = state.source === "custom"
    ? <Pill tone="amber">custom</Pill>
    : <Pill tone="gray">pack default</Pill>;

  return (
    <div className="card" style={{ marginBottom: 16 }}>
      {/* Card header */}
      <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 12 }}>
        <span style={{ fontWeight: 600, fontSize: 14 }}>{title}</span>
        {rulePill}
        {srcPill}
        {state.updatedBy && (
          <span className="muted" style={{ marginLeft: "auto" }}>
            last by {state.updatedBy}
            {state.updatedAt ? ` · ${new Date(state.updatedAt).toLocaleString()}` : ""}
          </span>
        )}
      </div>

      {/* Textarea */}
      <textarea
        className="rulebox"
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
      />

      <ErrorNote message={error} />

      {/* Action row */}
      <div style={{ display: "flex", gap: 8, marginTop: 10, flexWrap: "wrap" }}>
        <button
          className="btn-primary"
          disabled={!dirty || !draft.trim() || saving}
          onClick={save}
        >
          {saving ? "Saving…" : "Save as new revision"}
        </button>

        {state.source === "custom" && (
          <button disabled={reverting} onClick={revert}>
            {reverting ? "Reverting…" : "Revert to pack default"}
          </button>
        )}

        <button onClick={toggleHistory}>
          {histOpen ? "Hide history" : "History"}
        </button>
      </div>

      {/* History panel */}
      {histOpen && (
        <div style={{ marginTop: 12 }}>
          <ErrorNote message={histError} />
          {history === null && !histError && (
            <p className="muted">Loading…</p>
          )}
          {history !== null && history.length === 0 && (
            <p className="muted">No revisions yet.</p>
          )}
          {history !== null && history.map((rev) => (
            <div className="hist-row" key={rev.revision}>
              <span className="muted" style={{ minWidth: 60 }}>rev {rev.revision}</span>
              <span className="muted">{new Date(rev.createdAt).toLocaleString()}</span>
              <span className="muted">{rev.createdBy}</span>
              {rev.reverted && <Pill tone="gray">revert to pack</Pill>}
              <button
                style={{ marginLeft: "auto", height: 28, padding: "0 10px", fontSize: 12 }}
                disabled={rev.content === null}
                onClick={() => restoreRevision(rev.content)}
              >
                Restore
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── main screen ───────────────────────────────────────────────────────────────

export default function Rules() {
  const [rules, setRules]         = useState<RulesResponse | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [preview, setPreview]     = useState<string | null>(null);
  const [prevError, setPrevError] = useState<string | null>(null);
  const [prevLoading, setPrevLoading] = useState(false);

  const load = useCallback(async () => {
    setLoadError(null);
    try {
      const data = await api.get<RulesResponse>("/api/ai/admin/rules");
      setRules(data);
    } catch (e) {
      setLoadError((e as Error).message);
    }
  }, []);

  useEffect(() => { load(); }, [load]);

  function handleSaved(fresh: RulesResponse) {
    setRules(fresh);
  }

  async function fetchPreview() {
    setPrevLoading(true);
    setPrevError(null);
    setPreview(null);
    try {
      const data = await api.get<{ prompt: string }>("/api/ai/admin/rules/preview");
      setPreview(data.prompt);
    } catch (e) {
      setPrevError((e as Error).message);
    } finally {
      setPrevLoading(false);
    }
  }

  return (
    <>
      <header className="screen-head">
        <h1>Rules</h1>
        <span className="muted">live within ~10 s</span>
        <div className="actions">
          <button onClick={fetchPreview} disabled={prevLoading}>
            {prevLoading ? "Loading preview…" : "Preview full prompt"}
          </button>
        </div>
      </header>

      <ErrorNote message={loadError} />

      {rules === null && !loadError && (
        <p className="muted">Loading…</p>
      )}

      {rules !== null && (
        <>
          <TierCard tier="hard"     state={rules.hard}     onSaved={handleSaved} />
          <TierCard tier="guidance" state={rules.guidance} onSaved={handleSaved} />
        </>
      )}

      {/* Preview panel */}
      {(preview !== null || prevError) && (
        <div className="card" style={{ marginTop: 8 }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 10 }}>
            <span style={{ fontWeight: 600, fontSize: 14 }}>Full prompt preview</span>
            <button
              style={{ height: 28, padding: "0 10px", fontSize: 12 }}
              onClick={() => { setPreview(null); setPrevError(null); }}
            >
              Close
            </button>
          </div>
          <ErrorNote message={prevError} />
          {preview !== null && (
            <pre style={{
              fontFamily: "ui-monospace, monospace",
              fontSize: 12,
              lineHeight: 1.6,
              whiteSpace: "pre-wrap",
              wordBreak: "break-word",
              margin: 0,
            }}>
              {preview}
            </pre>
          )}
        </div>
      )}
    </>
  );
}
