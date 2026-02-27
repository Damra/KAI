import type { DevTask } from "../../types/pipeline";
import { STATUS_COLUMNS } from "../../types/pipeline";
import { TaskCard } from "./TaskCard";

interface Props {
  tasks: DevTask[];
  onTaskClick: (task: DevTask) => void;
}

export function TaskBoard({ tasks, onTaskClick }: Props) {
  return (
    <div className="flex gap-3 overflow-x-auto pb-4 h-full">
      {STATUS_COLUMNS.map((column) => {
        const columnTasks = tasks.filter((t) => column.statuses.includes(t.status));
        return (
          <div
            key={column.label}
            className="flex-shrink-0 w-72 bg-[#111111] border border-[#1e1e1e] rounded-xl flex flex-col"
          >
            {/* Column header */}
            <div className="flex items-center justify-between px-3 py-2 border-b border-[#1e1e1e]">
              <span className="text-sm font-medium text-gray-300">{column.label}</span>
              <span className="text-xs text-gray-600 bg-[#1a1a1a] px-1.5 py-0.5 rounded">
                {columnTasks.length}
              </span>
            </div>

            {/* Task list */}
            <div className="flex-1 overflow-y-auto p-2 space-y-2">
              {columnTasks.length === 0 ? (
                <div className="text-xs text-gray-700 text-center py-8">
                  No tasks
                </div>
              ) : (
                columnTasks.map((task) => (
                  <TaskCard key={task.id} task={task} onClick={onTaskClick} />
                ))
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
