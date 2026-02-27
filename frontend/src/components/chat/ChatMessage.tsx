import type { ChatMessage as ChatMessageType } from "../../types/api";
import { StreamingMessage } from "./StreamingMessage";
import { CodeArtifact } from "../code/CodeArtifact";
import { PlanOverview } from "../agent/PlanOverview";
import { ToolCallCard } from "../agent/ToolCallCard";
import { ThinkingIndicator } from "../agent/ThinkingIndicator";
import { User, Bot } from "lucide-react";

interface Props {
  message: ChatMessageType;
}

export function ChatMessage({ message }: Props) {
  const isUser = message.role === "user";

  // Extract tool calls and thinking events
  const toolCalls = message.events.filter((e) => e.type === "tool_call");
  const thinkingEvents = message.events.filter((e) => e.type === "thinking");
  const isThinking = message.isStreaming && message.content === "" && thinkingEvents.length > 0;

  return (
    <div className={`flex gap-3 ${isUser ? "justify-end" : ""}`}>
      {!isUser && (
        <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-brand/20 text-brand">
          <Bot size={16} />
        </div>
      )}

      <div className={`max-w-[85%] space-y-3 ${isUser ? "order-first" : ""}`}>
        {/* User message bubble */}
        {isUser && (
          <div className="rounded-2xl rounded-tr-md bg-brand px-4 py-2.5 text-sm text-white">
            {message.content}
          </div>
        )}

        {/* Assistant content */}
        {!isUser && (
          <>
            {/* Thinking indicator */}
            {isThinking && (
              <ThinkingIndicator
                thought={thinkingEvents.length > 0 ? (thinkingEvents[thinkingEvents.length - 1] as { type: "thinking"; thought: string }).thought : ""}
              />
            )}

            {/* Tool calls */}
            {toolCalls.length > 0 && (
              <div className="space-y-2">
                {toolCalls.map((tc, i) => {
                  const t = tc as { type: "tool_call"; tool: string; input: string };
                  const result = message.events.find(
                    (e) => e.type === "tool_result" && (e as { tool: string }).tool === t.tool
                  );
                  return (
                    <ToolCallCard
                      key={i}
                      tool={t.tool}
                      input={t.input}
                      output={result ? (result as { output: string }).output : undefined}
                      success={result ? (result as { success: boolean }).success : undefined}
                    />
                  );
                })}
              </div>
            )}

            {/* Plan steps timeline */}
            {message.planSteps.length > 0 && <PlanOverview steps={message.planSteps} />}

            {/* Main content */}
            {message.content && (
              <StreamingMessage content={message.content} isStreaming={message.isStreaming} />
            )}

            {/* Code artifacts */}
            {message.artifacts.map((artifact, i) => (
              <CodeArtifact key={i} artifact={artifact} />
            ))}
          </>
        )}
      </div>

      {isUser && (
        <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-gray-700 text-gray-300">
          <User size={16} />
        </div>
      )}
    </div>
  );
}
