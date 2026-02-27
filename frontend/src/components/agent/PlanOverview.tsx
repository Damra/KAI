import type { ReactNode } from "react";
import type { PlanStep } from "../../types/api";
import { Circle, Loader2, CheckCircle2, XCircle, MinusCircle } from "lucide-react";

interface Props {
  steps: PlanStep[];
}

const statusIcon: Record<string, ReactNode> = {
  PENDING: <Circle size={14} className="text-gray-500" />,
  RUNNING: <Loader2 size={14} className="text-blue-400 animate-spin" />,
  COMPLETED: <CheckCircle2 size={14} className="text-green-400" />,
  FAILED: <XCircle size={14} className="text-red-400" />,
  SKIPPED: <MinusCircle size={14} className="text-gray-600" />,
};

const statusColor: Record<string, string> = {
  PENDING: "border-gray-700",
  RUNNING: "border-blue-500",
  COMPLETED: "border-green-500",
  FAILED: "border-red-500",
  SKIPPED: "border-gray-700",
};

export function PlanOverview({ steps }: Props) {
  return (
    <div className="rounded-xl border border-surface-border bg-surface/50 p-4">
      <h3 className="mb-3 text-xs font-semibold uppercase tracking-wider text-gray-400">
        Execution Plan
      </h3>
      <div className="space-y-0">
        {steps.map((step, i) => (
          <div key={step.stepId} className="flex items-start gap-3">
            {/* Timeline line + dot */}
            <div className="flex flex-col items-center">
              <div className="mt-0.5">{statusIcon[step.status]}</div>
              {i < steps.length - 1 && (
                <div className={`mt-1 h-6 w-px border-l-2 ${statusColor[step.status]}`} />
              )}
            </div>
            {/* Step description */}
            <p
              className={`pb-4 text-xs ${
                step.status === "RUNNING"
                  ? "text-blue-300 font-medium"
                  : step.status === "COMPLETED"
                    ? "text-green-300"
                    : step.status === "FAILED"
                      ? "text-red-300"
                      : "text-gray-400"
              }`}
            >
              {step.description}
            </p>
          </div>
        ))}
      </div>
    </div>
  );
}
