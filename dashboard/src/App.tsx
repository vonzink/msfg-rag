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
