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
