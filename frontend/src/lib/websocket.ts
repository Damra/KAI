import { StreamEvent } from "../types/api";
import { WS_BASE, API_KEY } from "./constants";

export type WSStatus = "connecting" | "connected" | "disconnected" | "error";

interface WSManagerOptions {
  sessionId: string;
  onEvent: (event: StreamEvent) => void;
  onStatusChange: (status: WSStatus) => void;
}

export class WSManager {
  private ws: WebSocket | null = null;
  private options: WSManagerOptions;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectAttempts = 0;
  private maxReconnectAttempts = 5;
  private manualClose = false;

  constructor(options: WSManagerOptions) {
    this.options = options;
  }

  connect() {
    this.manualClose = false;
    this.options.onStatusChange("connecting");

    const url = `${WS_BASE}/agent?token=${encodeURIComponent(API_KEY)}&session=${encodeURIComponent(this.options.sessionId)}`;
    this.ws = new WebSocket(url);

    this.ws.onopen = () => {
      this.reconnectAttempts = 0;
      this.options.onStatusChange("connected");
    };

    this.ws.onmessage = (e) => {
      try {
        const event = JSON.parse(e.data) as StreamEvent;
        this.options.onEvent(event);
      } catch {
        console.error("Failed to parse WS message:", e.data);
      }
    };

    this.ws.onclose = () => {
      if (!this.manualClose) {
        this.options.onStatusChange("disconnected");
        this.scheduleReconnect();
      }
    };

    this.ws.onerror = () => {
      this.options.onStatusChange("error");
    };
  }

  send(data: string) {
    console.log("[WS] send called, readyState:", this.ws?.readyState, "OPEN:", WebSocket.OPEN);
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(data);
      console.log("[WS] message sent:", data.slice(0, 100));
    } else {
      console.warn("[WS] cannot send, socket not open");
    }
  }

  disconnect() {
    this.manualClose = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.ws?.close();
    this.ws = null;
    this.options.onStatusChange("disconnected");
  }

  private scheduleReconnect() {
    if (this.reconnectAttempts >= this.maxReconnectAttempts) return;
    const delay = Math.min(1000 * 2 ** this.reconnectAttempts, 15000);
    this.reconnectAttempts++;
    this.reconnectTimer = setTimeout(() => this.connect(), delay);
  }

  updateSessionId(sessionId: string) {
    this.options.sessionId = sessionId;
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.disconnect();
      this.connect();
    }
  }
}
