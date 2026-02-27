import { useState, useCallback, useRef } from "react";
import type { ChatMessage, StreamEvent, CodeArtifact, PlanStep } from "../types/api";

export function useChat(sessionId: string) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isProcessing, setIsProcessing] = useState(false);
  const currentAssistantId = useRef<string | null>(null);

  const sendMessage = useCallback(
    (text: string, wsSend: (data: string) => void) => {
      // Add user message
      const userMsg: ChatMessage = {
        id: crypto.randomUUID(),
        role: "user",
        content: text,
        timestamp: Date.now(),
        events: [],
        artifacts: [],
        planSteps: [],
        isStreaming: false,
      };

      // Prepare assistant placeholder
      const assistantId = crypto.randomUUID();
      currentAssistantId.current = assistantId;
      const assistantMsg: ChatMessage = {
        id: assistantId,
        role: "assistant",
        content: "",
        timestamp: Date.now(),
        events: [],
        artifacts: [],
        planSteps: [],
        isStreaming: true,
      };

      setMessages((prev) => [...prev, userMsg, assistantMsg]);
      setIsProcessing(true);

      // Send via WebSocket
      wsSend(JSON.stringify({ message: text, sessionId }));
    },
    [sessionId]
  );

  const handleEvent = useCallback((event: StreamEvent) => {
    const assistantId = currentAssistantId.current;
    if (!assistantId) return;

    setMessages((prev) =>
      prev.map((msg) => {
        if (msg.id !== assistantId) return msg;

        const newEvents = [...msg.events, event];
        let content = msg.content;
        const artifacts = [...msg.artifacts];
        const planSteps = [...msg.planSteps];
        let isStreaming = msg.isStreaming;

        switch (event.type) {
          case "thinking":
            // Accumulate thinking text
            break;
          case "code_generated":
            artifacts.push(event.artifact as CodeArtifact);
            break;
          case "plan_update": {
            const existing = planSteps.findIndex((s) => s.stepId === event.stepId);
            const step: PlanStep = {
              stepId: event.stepId,
              status: event.status,
              description: event.description,
            };
            if (existing >= 0) {
              planSteps[existing] = step;
            } else {
              planSteps.push(step);
            }
            break;
          }
          case "done":
            content = event.answer;
            isStreaming = false;
            setIsProcessing(false);
            currentAssistantId.current = null;
            break;
          case "error":
            content = content || `Error: ${event.message}`;
            if (!event.recoverable) {
              isStreaming = false;
              setIsProcessing(false);
              currentAssistantId.current = null;
            }
            break;
        }

        return { ...msg, content, events: newEvents, artifacts, planSteps, isStreaming };
      })
    );
  }, []);

  const clearMessages = useCallback(() => {
    setMessages([]);
    setIsProcessing(false);
    currentAssistantId.current = null;
  }, []);

  return { messages, isProcessing, sendMessage, handleEvent, clearMessages };
}
