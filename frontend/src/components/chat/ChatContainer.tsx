import { useEffect, useRef } from "react";
import type { ChatMessage as ChatMessageType } from "../../types/api";
import { ChatMessage } from "./ChatMessage";
import { Bot } from "lucide-react";

interface Props {
  messages: ChatMessageType[];
}

export function ChatContainer({ messages }: Props) {
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  if (messages.length === 0) {
    return (
      <div className="flex flex-1 items-center justify-center">
        <div className="text-center text-gray-500">
          <Bot size={48} className="mx-auto mb-4 text-gray-600" />
          <h2 className="text-xl font-medium text-gray-300">KAI Agent</h2>
          <p className="mt-2 text-sm">Multi-agent AI code generation system</p>
          <p className="mt-1 text-xs text-gray-600">Send a message to start</p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex-1 overflow-y-auto px-4 py-6">
      <div className="mx-auto max-w-3xl space-y-6">
        {messages.map((msg) => (
          <ChatMessage key={msg.id} message={msg} />
        ))}
        <div ref={bottomRef} />
      </div>
    </div>
  );
}
