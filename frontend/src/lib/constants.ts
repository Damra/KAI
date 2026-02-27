// API uses relative URLs — Vite proxy in dev, nginx proxy in prod
export const API_BASE = "/api/v1";
export const WS_BASE = `${window.location.protocol === "https:" ? "wss:" : "ws:"}//${window.location.host}/ws`;

export const API_KEY = "dev-key";
