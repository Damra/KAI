// ── Pipeline Types (matches backend pipeline models) ──

export type TaskStatus =
  | "CREATED" | "PLANNED" | "READY" | "IN_PROGRESS" | "PR_OPENED"
  | "REVIEWING" | "CHANGES_REQUESTED" | "APPROVED" | "MERGED"
  | "TESTING" | "TEST_PASSED" | "TEST_FAILED" | "BUG_CREATED" | "DEPLOYED";

export type TaskCategory = "DESIGN" | "BACKEND" | "FRONTEND" | "DEVOPS" | "TESTING" | "DOCUMENTATION";
export type TaskPriority = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW";
export type ProjectStatus = "ACTIVE" | "PAUSED" | "COMPLETED" | "ARCHIVED";

export interface Project {
  id: number;
  name: string;
  description: string;
  repoUrl: string;
  status: ProjectStatus;
  createdAt: string;
  updatedAt: string;
}

export interface Epic {
  id: number;
  projectId: number;
  title: string;
  description: string;
  order: number;
}

export interface Feature {
  id: number;
  epicId: number;
  title: string;
  description: string;
  acceptanceCriteria: string;
}

export interface DevTask {
  id: number;
  featureId: number;
  title: string;
  description: string;
  category: TaskCategory;
  priority: TaskPriority;
  status: TaskStatus;
  assignedAgent: string;
  branchName: string;
  prUrl: string;
  dependsOn: number[];
  estimatedComplexity: number;
  createdAt: string;
  updatedAt: string;
}

export interface TaskTransition {
  id: number;
  taskId: number;
  fromStatus: string;
  toStatus: string;
  reason: string;
  triggeredBy: string;
  createdAt: string;
}

export interface ProjectStatus_Response {
  projectId: number;
  projectName: string;
  totalTasks: number;
  tasksByStatus: Record<string, number>;
  epics: Epic[];
  features: Feature[];
  tasks: DevTask[];
}

export interface TaskOutput {
  id: number;
  taskId: number;
  outputType: string;  // CODE_GENERATION, REVIEW, TEST_RESULT
  content: string;
  agent: string;       // CODE_WRITER, REVIEWER
  createdAt: string;
}

export interface TaskDetail {
  task: DevTask;
  history: TaskTransition[];
  outputs: TaskOutput[];
}

// Status column groupings for Kanban board
export const STATUS_COLUMNS: { label: string; statuses: TaskStatus[] }[] = [
  { label: "Backlog", statuses: ["CREATED", "PLANNED"] },
  { label: "Ready", statuses: ["READY"] },
  { label: "In Progress", statuses: ["IN_PROGRESS"] },
  { label: "Review", statuses: ["PR_OPENED", "REVIEWING", "CHANGES_REQUESTED"] },
  { label: "Done", statuses: ["APPROVED", "MERGED", "TESTING", "TEST_PASSED", "DEPLOYED"] },
  { label: "Failed", statuses: ["TEST_FAILED", "BUG_CREATED"] },
];

// Color mappings
export const STATUS_COLORS: Record<TaskStatus, string> = {
  CREATED: "bg-gray-500",
  PLANNED: "bg-blue-500",
  READY: "bg-cyan-500",
  IN_PROGRESS: "bg-yellow-500",
  PR_OPENED: "bg-orange-500",
  REVIEWING: "bg-purple-500",
  CHANGES_REQUESTED: "bg-red-400",
  APPROVED: "bg-green-400",
  MERGED: "bg-green-500",
  TESTING: "bg-indigo-500",
  TEST_PASSED: "bg-green-600",
  TEST_FAILED: "bg-red-600",
  BUG_CREATED: "bg-red-500",
  DEPLOYED: "bg-emerald-600",
};

export const PRIORITY_COLORS: Record<TaskPriority, string> = {
  CRITICAL: "text-red-400 bg-red-400/10",
  HIGH: "text-orange-400 bg-orange-400/10",
  MEDIUM: "text-yellow-400 bg-yellow-400/10",
  LOW: "text-gray-400 bg-gray-400/10",
};

export const CATEGORY_COLORS: Record<TaskCategory, string> = {
  DESIGN: "text-pink-400 bg-pink-400/10",
  BACKEND: "text-blue-400 bg-blue-400/10",
  FRONTEND: "text-green-400 bg-green-400/10",
  DEVOPS: "text-orange-400 bg-orange-400/10",
  TESTING: "text-purple-400 bg-purple-400/10",
  DOCUMENTATION: "text-gray-400 bg-gray-400/10",
};
