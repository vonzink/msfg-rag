import { ReactNode } from "react";

export function Pill({ tone, children }: { tone: "green" | "amber" | "gray" | "blue" | "purple"; children: ReactNode }) {
  return <span className={`pill ${tone}`}>{children}</span>;
}

export function Stat({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="stat">
      <span className="stat-label">{label}</span>
      <span className="stat-value">{value}</span>
    </div>
  );
}

export function ErrorNote({ message }: { message: string | null }) {
  return message ? <p className="error-note">{message}</p> : null;
}

export function outcomeTone(escalated: boolean): "green" | "amber" {
  return escalated ? "amber" : "green";
}
