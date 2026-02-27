import type { DevTask } from "../../types/pipeline";
import { STATUS_COLORS, PRIORITY_COLORS, CATEGORY_COLORS } from "../../types/pipeline";
import { GitBranch, ExternalLink } from "lucide-react";

interface Props {
  task: DevTask;
  onClick: (task: DevTask) => void;
}

export function TaskCard({ task, onClick }: Props) {
  const statusColor = STATUS_COLORS[task.status] || "bg-gray-500";
  const priorityColor = PRIORITY_COLORS[task.priority] || "text-gray-400";
  const categoryColor = CATEGORY_COLORS[task.category] || "text-gray-400";

  return (
    <div
      onClick={() => onClick(task)}
      className="bg-[#1a1a1a] border border-[#2a2a2a] rounded-lg p-3 cursor-pointer hover:border-[#3a3a3a] transition-colors"
    >
      {/* Header: status dot + title */}
      <div className="flex items-start gap-2 mb-2">
        <div className={`w-2 h-2 rounded-full mt-1.5 shrink-0 ${statusColor}`} />
        <span className="text-sm text-gray-200 font-medium leading-tight">
          {task.title}
        </span>
      </div>

      {/* Description preview */}
      {task.description && (
        <p className="text-xs text-gray-500 mb-2 line-clamp-2 pl-4">
          {task.description}
        </p>
      )}

      {/* Tags row */}
      <div className="flex flex-wrap gap-1.5 pl-4">
        <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${categoryColor}`}>
          {task.category}
        </span>
        <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${priorityColor}`}>
          {task.priority}
        </span>
        {task.estimatedComplexity > 0 && (
          <span className="text-[10px] px-1.5 py-0.5 rounded text-gray-400 bg-gray-400/10">
            C:{task.estimatedComplexity}
          </span>
        )}
      </div>

      {/* Branch / PR info */}
      {(task.branchName || task.prUrl) && (
        <div className="flex items-center gap-2 mt-2 pl-4">
          {task.branchName && (
            <span className="flex items-center gap-1 text-[10px] text-gray-500">
              <GitBranch size={10} />
              {task.branchName}
            </span>
          )}
          {task.prUrl && (
            <a
              href={task.prUrl}
              target="_blank"
              rel="noopener noreferrer"
              onClick={(e) => e.stopPropagation()}
              className="flex items-center gap-1 text-[10px] text-blue-400 hover:text-blue-300"
            >
              <ExternalLink size={10} />
              PR
            </a>
          )}
        </div>
      )}

      {/* Task ID */}
      <div className="mt-2 pl-4">
        <span className="text-[10px] text-gray-600">#{task.id}</span>
      </div>
    </div>
  );
}
