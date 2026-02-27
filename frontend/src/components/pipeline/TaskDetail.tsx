import type { TaskDetail as TaskDetailType } from "../../types/pipeline";
import { STATUS_COLORS, PRIORITY_COLORS, CATEGORY_COLORS } from "../../types/pipeline";
import { X, GitBranch, ExternalLink, Clock, ArrowRight } from "lucide-react";

interface Props {
  detail: TaskDetailType;
  onClose: () => void;
  onTransition: (taskId: number, status: string, reason?: string) => void;
}

// Valid next transitions from each status
const NEXT_TRANSITIONS: Record<string, string[]> = {
  CREATED: ["PLANNED"],
  PLANNED: ["READY"],
  READY: ["IN_PROGRESS"],
  IN_PROGRESS: ["PR_OPENED"],
  PR_OPENED: ["REVIEWING"],
  REVIEWING: ["APPROVED", "CHANGES_REQUESTED"],
  CHANGES_REQUESTED: ["IN_PROGRESS"],
  APPROVED: ["MERGED"],
  MERGED: ["TESTING"],
  TESTING: ["TEST_PASSED", "TEST_FAILED"],
  TEST_PASSED: ["DEPLOYED"],
  TEST_FAILED: ["BUG_CREATED"],
  BUG_CREATED: ["CREATED"],
};

export function TaskDetail({ detail, onClose, onTransition }: Props) {
  const { task, history } = detail;
  const statusColor = STATUS_COLORS[task.status] || "bg-gray-500";
  const nextStatuses = NEXT_TRANSITIONS[task.status] || [];

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
      <div className="bg-[#141414] border border-[#2a2a2a] rounded-xl w-full max-w-2xl max-h-[80vh] overflow-hidden flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between p-4 border-b border-[#2a2a2a]">
          <div className="flex items-center gap-3">
            <div className={`w-3 h-3 rounded-full ${statusColor}`} />
            <h2 className="text-lg font-semibold text-gray-100">{task.title}</h2>
            <span className="text-sm text-gray-500">#{task.id}</span>
          </div>
          <button onClick={onClose} className="p-1 text-gray-400 hover:text-white">
            <X size={20} />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto p-4 space-y-4">
          {/* Description */}
          <div>
            <h3 className="text-sm font-medium text-gray-400 mb-1">Description</h3>
            <p className="text-sm text-gray-200">{task.description || "No description"}</p>
          </div>

          {/* Metadata grid */}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <span className="text-xs text-gray-500">Category</span>
              <div className={`text-sm mt-0.5 px-2 py-0.5 rounded inline-block ${CATEGORY_COLORS[task.category]}`}>
                {task.category}
              </div>
            </div>
            <div>
              <span className="text-xs text-gray-500">Priority</span>
              <div className={`text-sm mt-0.5 px-2 py-0.5 rounded inline-block ${PRIORITY_COLORS[task.priority]}`}>
                {task.priority}
              </div>
            </div>
            <div>
              <span className="text-xs text-gray-500">Status</span>
              <div className="text-sm text-gray-200 mt-0.5">{task.status}</div>
            </div>
            <div>
              <span className="text-xs text-gray-500">Complexity</span>
              <div className="text-sm text-gray-200 mt-0.5">{task.estimatedComplexity}/5</div>
            </div>
            {task.assignedAgent && (
              <div>
                <span className="text-xs text-gray-500">Agent</span>
                <div className="text-sm text-gray-200 mt-0.5">{task.assignedAgent}</div>
              </div>
            )}
          </div>

          {/* Branch / PR */}
          {(task.branchName || task.prUrl) && (
            <div className="space-y-1">
              {task.branchName && (
                <div className="flex items-center gap-2 text-sm text-gray-300">
                  <GitBranch size={14} className="text-gray-500" />
                  <code className="bg-[#1a1a1a] px-2 py-0.5 rounded text-xs">{task.branchName}</code>
                </div>
              )}
              {task.prUrl && (
                <a
                  href={task.prUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="flex items-center gap-2 text-sm text-blue-400 hover:text-blue-300"
                >
                  <ExternalLink size={14} />
                  {task.prUrl}
                </a>
              )}
            </div>
          )}

          {/* Dependencies */}
          {task.dependsOn.length > 0 && (
            <div>
              <h3 className="text-sm font-medium text-gray-400 mb-1">Dependencies</h3>
              <div className="flex gap-1">
                {task.dependsOn.map((depId) => (
                  <span key={depId} className="text-xs px-2 py-0.5 bg-[#1a1a1a] rounded text-gray-400">
                    #{depId}
                  </span>
                ))}
              </div>
            </div>
          )}

          {/* Transition buttons */}
          {nextStatuses.length > 0 && (
            <div>
              <h3 className="text-sm font-medium text-gray-400 mb-2">Transition To</h3>
              <div className="flex gap-2">
                {nextStatuses.map((status) => (
                  <button
                    key={status}
                    onClick={() => onTransition(task.id, status)}
                    className="flex items-center gap-1 px-3 py-1.5 bg-[#1a1a1a] border border-[#3a3a3a] rounded-lg text-sm text-gray-200 hover:bg-[#252525] transition-colors"
                  >
                    <ArrowRight size={12} />
                    {status}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* History */}
          {history.length > 0 && (
            <div>
              <h3 className="text-sm font-medium text-gray-400 mb-2">History</h3>
              <div className="space-y-2">
                {history.map((t) => (
                  <div key={t.id} className="flex items-center gap-2 text-xs">
                    <Clock size={12} className="text-gray-600 shrink-0" />
                    <span className="text-gray-500">
                      {new Date(t.createdAt).toLocaleString()}
                    </span>
                    <span className="text-gray-400">{t.fromStatus}</span>
                    <ArrowRight size={10} className="text-gray-600" />
                    <span className="text-gray-200">{t.toStatus}</span>
                    {t.reason && (
                      <span className="text-gray-600 truncate">— {t.reason}</span>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
