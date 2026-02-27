// ── Request Types ──

export interface AgentRequest {
  message: string;
  sessionId?: string;
  constraints?: string[];
  streaming?: boolean;
}

// ── Stream Event Types (matches backend StreamEvent sealed class) ──

export type StreamEvent =
  | { type: "thinking"; thought: string }
  | { type: "tool_call"; tool: string; input: string }
  | { type: "tool_result"; tool: string; output: string; success: boolean }
  | { type: "code_generated"; artifact: CodeArtifact }
  | { type: "plan_update"; stepId: string; status: StepStatus; description: string }
  | { type: "delegation"; from: string; to: string; reason: string }
  | { type: "done"; answer: string; metadata: AnswerMetadata | null }
  | { type: "error"; message: string; recoverable: boolean };

export interface CodeArtifact {
  filename: string;
  language: string;
  content: string;
  version: number;
}

export type StepStatus = "PENDING" | "RUNNING" | "COMPLETED" | "FAILED" | "SKIPPED";

export interface AnswerMetadata {
  totalSteps: number;
  agentsUsed: string[];
  verificationPassed: boolean;
}

// ── Chat Types ──

export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  timestamp: number;
  events: StreamEvent[];
  artifacts: CodeArtifact[];
  planSteps: PlanStep[];
  isStreaming: boolean;
}

export interface PlanStep {
  stepId: string;
  status: StepStatus;
  description: string;
}

export interface Session {
  id: string;
  title: string;
  createdAt: number;
  lastMessageAt: number;
}
