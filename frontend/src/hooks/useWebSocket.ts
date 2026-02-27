import { useEffect, useRef, useState, useCallback } from "react";
import { WSManager, type WSStatus } from "../lib/websocket";
import type { StreamEvent } from "../types/api";

export function useWebSocket(
  sessionId: string,
  onEvent: (event: StreamEvent) => void
) {
  const [status, setStatus] = useState<WSStatus>("disconnected");
  const managerRef = useRef<WSManager | null>(null);
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;

  useEffect(() => {
    const mgr = new WSManager({
      sessionId,
      onEvent: (e) => onEventRef.current(e),
      onStatusChange: setStatus,
    });
    mgr.connect();
    managerRef.current = mgr;

    return () => {
      mgr.disconnect();
      managerRef.current = null;
    };
  }, [sessionId]);

  const send = useCallback((data: string) => {
    managerRef.current?.send(data);
  }, []);

  return { status, send };
}
