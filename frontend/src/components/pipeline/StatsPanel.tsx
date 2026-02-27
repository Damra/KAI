import { useMemo } from "react";
import type { ProjectStatus_Response, DevTask, TaskCategory, TaskPriority } from "../../types/pipeline";
import {
  STATUS_COLUMNS,
  CATEGORY_COLORS,
  PRIORITY_COLORS,
} from "../../types/pipeline";
import { Activity, BarChart3, Layers, AlertTriangle, Bot, Clock } from "lucide-react";

interface StatsPanelProps {
  projectStatus: ProjectStatus_Response;
}

// Column color for status breakdown bars
const COLUMN_COLORS: Record<string, string> = {
  Backlog: "bg-gray-500",
  Ready: "bg-cyan-500",
  "In Progress": "bg-yellow-500",
  Review: "bg-purple-500",
  Done: "bg-emerald-500",
  Failed: "bg-red-500",
};

function relativeTime(dateStr: string): string {
  const now = Date.now();
  const then = new Date(dateStr).getTime();
  const diffSec = Math.floor((now - then) / 1000);
  if (diffSec < 60) return "just now";
  const diffMin = Math.floor(diffSec / 60);
  if (diffMin < 60) return `${diffMin}m ago`;
  const diffHr = Math.floor(diffMin / 60);
  if (diffHr < 24) return `${diffHr}h ago`;
  const diffDay = Math.floor(diffHr / 24);
  return `${diffDay}d ago`;
}

// SVG progress ring
function ProgressRing({ percent, size = 48, stroke = 4 }: { percent: number; size?: number; stroke?: number }) {
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - (percent / 100) * circumference;
  return (
    <svg width={size} height={size} className="transform -rotate-90">
      <circle cx={size / 2} cy={size / 2} r={radius} fill="none" stroke="#1e1e1e" strokeWidth={stroke} />
      <circle
        cx={size / 2}
        cy={size / 2}
        r={radius}
        fill="none"
        stroke="#10b981"
        strokeWidth={stroke}
        strokeDasharray={circumference}
        strokeDashoffset={offset}
        strokeLinecap="round"
        className="transition-all duration-500"
      />
    </svg>
  );
}

export function StatsPanel({ projectStatus }: StatsPanelProps) {
  const tasks = projectStatus.tasks;

  const stats = useMemo(() => {
    const doneStatuses = new Set(["APPROVED", "MERGED", "TESTING", "TEST_PASSED", "DEPLOYED"]);
    const deployed = tasks.filter((t) => doneStatuses.has(t.status)).length;
    const total = tasks.length;
    const percent = total > 0 ? Math.round((deployed / total) * 100) : 0;

    // Status column counts
    const columnCounts: { label: string; count: number }[] = STATUS_COLUMNS.map((col) => ({
      label: col.label,
      count: tasks.filter((t) => col.statuses.includes(t.status)).length,
    }));

    // Category distribution
    const categoryMap = new Map<TaskCategory, number>();
    tasks.forEach((t) => categoryMap.set(t.category, (categoryMap.get(t.category) ?? 0) + 1));

    // Priority distribution
    const priorityMap = new Map<TaskPriority, number>();
    tasks.forEach((t) => priorityMap.set(t.priority, (priorityMap.get(t.priority) ?? 0) + 1));

    // Active agents
    const activeAgents: { agent: string; title: string }[] = tasks
      .filter((t) => t.assignedAgent && t.assignedAgent.length > 0)
      .map((t) => ({ agent: t.assignedAgent, title: t.title }));

    // Recent activity (last 5 updated tasks)
    const recent: DevTask[] = [...tasks]
      .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
      .slice(0, 5);

    return { deployed, total, percent, columnCounts, categoryMap, priorityMap, activeAgents, recent };
  }, [tasks]);

  return (
    <div className="grid grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-3 mb-4">
      {/* Progress */}
      <div className="bg-[#141414] border border-[#1e1e1e] rounded-xl p-3 flex items-center gap-3">
        <ProgressRing percent={stats.percent} />
        <div>
          <div className="text-xs text-gray-500 mb-0.5">Progress</div>
          <div className="text-lg font-semibold text-gray-100">
            {stats.deployed}/{stats.total}
          </div>
          <div className="text-xs text-emerald-400">{stats.percent}% done</div>
        </div>
      </div>

      {/* Status Breakdown */}
      <div className="bg-[#141414] border border-[#1e1e1e] rounded-xl p-3">
        <div className="flex items-center gap-1.5 mb-2">
          <BarChart3 size={12} className="text-gray-500" />
          <span className="text-xs text-gray-500">Status</span>
        </div>
        <div className="space-y-1.5">
          {stats.columnCounts.map((col) => (
            <div key={col.label} className="flex items-center gap-2">
              <div className={`w-2 h-2 rounded-full ${COLUMN_COLORS[col.label] ?? "bg-gray-500"}`} />
              <span className="text-xs text-gray-400 flex-1 truncate">{col.label}</span>
              <span className="text-xs font-medium text-gray-300">{col.count}</span>
            </div>
          ))}
        </div>
      </div>

      {/* Category Distribution */}
      <div className="bg-[#141414] border border-[#1e1e1e] rounded-xl p-3">
        <div className="flex items-center gap-1.5 mb-2">
          <Layers size={12} className="text-gray-500" />
          <span className="text-xs text-gray-500">Categories</span>
        </div>
        <div className="flex flex-wrap gap-1.5">
          {Array.from(stats.categoryMap.entries()).map(([cat, count]) => (
            <span key={cat} className={`px-2 py-0.5 rounded-full text-xs font-medium ${CATEGORY_COLORS[cat]}`}>
              {cat} {count}
            </span>
          ))}
          {stats.categoryMap.size === 0 && <span className="text-xs text-gray-600">No tasks</span>}
        </div>
      </div>

      {/* Priority Overview */}
      <div className="bg-[#141414] border border-[#1e1e1e] rounded-xl p-3">
        <div className="flex items-center gap-1.5 mb-2">
          <AlertTriangle size={12} className="text-gray-500" />
          <span className="text-xs text-gray-500">Priority</span>
        </div>
        <div className="flex flex-wrap gap-1.5">
          {(["CRITICAL", "HIGH", "MEDIUM", "LOW"] as TaskPriority[]).map((p) => {
            const count = stats.priorityMap.get(p) ?? 0;
            if (count === 0) return null;
            return (
              <span key={p} className={`px-2 py-0.5 rounded-full text-xs font-medium ${PRIORITY_COLORS[p]}`}>
                {p} {count}
              </span>
            );
          })}
          {stats.priorityMap.size === 0 && <span className="text-xs text-gray-600">No tasks</span>}
        </div>
      </div>

      {/* Active Agents */}
      <div className="bg-[#141414] border border-[#1e1e1e] rounded-xl p-3">
        <div className="flex items-center gap-1.5 mb-2">
          <Bot size={12} className="text-gray-500" />
          <span className="text-xs text-gray-500">Active Agents</span>
        </div>
        <div className="space-y-1">
          {stats.activeAgents.length > 0 ? (
            stats.activeAgents.slice(0, 4).map((a, i) => (
              <div key={i} className="text-xs truncate">
                <span className="text-cyan-400 font-medium">{a.agent}</span>
                <span className="text-gray-600 mx-1">&rarr;</span>
                <span className="text-gray-400">{a.title}</span>
              </div>
            ))
          ) : (
            <span className="text-xs text-gray-600">No agents active</span>
          )}
          {stats.activeAgents.length > 4 && (
            <span className="text-xs text-gray-600">+{stats.activeAgents.length - 4} more</span>
          )}
        </div>
      </div>

      {/* Recent Activity */}
      <div className="bg-[#141414] border border-[#1e1e1e] rounded-xl p-3">
        <div className="flex items-center gap-1.5 mb-2">
          <Clock size={12} className="text-gray-500" />
          <span className="text-xs text-gray-500">Recent</span>
        </div>
        <div className="space-y-1">
          {stats.recent.length > 0 ? (
            stats.recent.map((t) => (
              <div key={t.id} className="flex items-center gap-1.5 text-xs">
                <Activity size={8} className="text-gray-600 shrink-0" />
                <span className="text-gray-400 truncate flex-1">{t.title}</span>
                <span className="text-gray-600 shrink-0">{relativeTime(t.updatedAt)}</span>
              </div>
            ))
          ) : (
            <span className="text-xs text-gray-600">No activity</span>
          )}
        </div>
      </div>
    </div>
  );
}
