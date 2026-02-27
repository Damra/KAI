import { API_BASE, API_KEY } from "./constants";

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${API_KEY}`,
      ...init?.headers,
    },
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`API ${res.status}: ${body}`);
  }
  return res.json();
}

export async function healthCheck(): Promise<{ status: string; service: string; version: string }> {
  return apiFetch("/health");
}

export async function postAgent(message: string, sessionId?: string) {
  return apiFetch("/agent", {
    method: "POST",
    body: JSON.stringify({ message, sessionId }),
  });
}
