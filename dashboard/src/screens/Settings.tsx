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
  const configuredProviders = data.providers.filter((p) => p.configured).map((p) => p.name);

  function providerOptions(selectKey: string) {
    const current = value(selectKey);
    const options = [...configuredProviders];
    if (current && !options.includes(current)) options.unshift(current);
    return options;
  }

  return (
    <>
      <header className="screen-head">
        <h1>Settings</h1>
        <span className="muted">changes go live within ~10 s, no restart</span>
      </header>
      {data.providers.length > 0 && (
        <div className="chips">
          {data.providers.map((p) => (
            <Pill key={p.name} tone={p.configured ? "green" : "gray"}>
              {p.configured ? `${p.name} ✓` : `${p.name} — no key`}
            </Pill>
          ))}
          <span className="muted">To activate a provider, add its API key to .env and restart the brain (see RUNBOOK).</span>
        </div>
      )}
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
                {providerOptions(f.key).map((p) => <option key={p}>{p}</option>)}
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
