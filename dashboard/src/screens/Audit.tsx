import { Fragment, useCallback, useEffect, useState } from "react";
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
            <Fragment key={row.id}>
              <tr className="clickable" onClick={() => toggle(row.id)}>
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
                <tr className="detail-row">
                  <td colSpan={5}>
                    {open[row.id].answer && <p>{open[row.id].answer}</p>}
                    <p className="muted">
                      {open[row.id].sources.length} source chunk{open[row.id].sources.length === 1 ? "" : "s"} retrieved
                      {open[row.id].rewrittenQuestion ? ` · rewritten: ${open[row.id].rewrittenQuestion}` : ""}
                    </p>
                  </td>
                </tr>
              )}
            </Fragment>
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
