import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, adminKey, AuthError } from "./api";

const store = new Map<string, string>();

beforeEach(() => {
  vi.stubGlobal("sessionStorage", {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  });
  store.clear();
});

afterEach(() => vi.unstubAllGlobals());

function fetchReturning(status: number, body: unknown) {
  return vi.fn(async () => new Response(JSON.stringify(body), { status }));
}

describe("api client", () => {
  it("sends the admin key header on every request", async () => {
    adminKey.set("secret-key");
    const fetchMock = fetchReturning(200, { ok: true });
    vi.stubGlobal("fetch", fetchMock);

    await api.get("/api/ai/admin/stats");

    const call = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    const init = call[1];
    expect(new Headers(init.headers).get("X-Admin-Api-Key")).toBe("secret-key");
  });

  it("clears the key and throws AuthError on 401", async () => {
    adminKey.set("bad-key");
    vi.stubGlobal("fetch", fetchReturning(401, { error: "nope" }));

    await expect(api.get("/api/ai/admin/stats")).rejects.toBeInstanceOf(AuthError);
    expect(adminKey.get()).toBeNull();
  });

  it("throws the server error message on non-401 failures", async () => {
    adminKey.set("k");
    vi.stubGlobal("fetch", fetchReturning(400, { error: "retrieval.top-k must be between 1 and 50" }));

    await expect(api.put("/api/ai/admin/settings", {})).rejects.toThrow(
      "retrieval.top-k must be between 1 and 50");
  });

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
});
