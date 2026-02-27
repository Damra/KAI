import { useState, useCallback } from "react";
import type { Session } from "../types/api";

const STORAGE_KEY = "kai-sessions";

function loadSessions(): Session[] {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || "[]");
  } catch {
    return [];
  }
}

function saveSessions(sessions: Session[]) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions));
}

export function useSession() {
  const [sessions, setSessions] = useState<Session[]>(loadSessions);
  const [activeSessionId, setActiveSessionId] = useState<string>(() => {
    const existing = loadSessions();
    if (existing.length > 0) return existing[0].id;
    const id = crypto.randomUUID();
    const initial: Session = { id, title: "New Chat", createdAt: Date.now(), lastMessageAt: Date.now() };
    saveSessions([initial]);
    return id;
  });

  const createSession = useCallback(() => {
    const id = crypto.randomUUID();
    const session: Session = { id, title: "New Chat", createdAt: Date.now(), lastMessageAt: Date.now() };
    setSessions((prev) => {
      const next = [session, ...prev];
      saveSessions(next);
      return next;
    });
    setActiveSessionId(id);
    return id;
  }, []);

  const updateSessionTitle = useCallback((id: string, title: string) => {
    setSessions((prev) => {
      const next = prev.map((s) => (s.id === id ? { ...s, title, lastMessageAt: Date.now() } : s));
      saveSessions(next);
      return next;
    });
  }, []);

  const deleteSession = useCallback(
    (id: string) => {
      setSessions((prev) => {
        const next = prev.filter((s) => s.id !== id);
        saveSessions(next);
        if (id === activeSessionId && next.length > 0) {
          setActiveSessionId(next[0].id);
        }
        return next;
      });
    },
    [activeSessionId]
  );

  return { sessions, activeSessionId, setActiveSessionId, createSession, updateSessionTitle, deleteSession };
}
