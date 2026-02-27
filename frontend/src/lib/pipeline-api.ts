import { API_BASE, API_KEY } from "./constants";
import type { Project, DevTask, ProjectStatus_Response, TaskDetail } from "../types/pipeline";

async function pipelineFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}/pipeline${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${API_KEY}`,
      ...init?.headers,
    },
  });
  if (!res.ok) {
    const body = await res.text();
    throw new Error(`Pipeline API ${res.status}: ${body}`);
  }
  return res.json();
}

// ── Projects ──

export async function createProject(name: string, description: string, repoUrl = ""): Promise<Project> {
  return pipelineFetch("/projects", {
    method: "POST",
    body: JSON.stringify({ name, description, repoUrl }),
  });
}

export async function listProjects(): Promise<Project[]> {
  return pipelineFetch("/projects");
}

export async function getProjectStatus(projectId: number): Promise<ProjectStatus_Response> {
  return pipelineFetch(`/projects/${projectId}`);
}

// ── Analysis ──

export async function analyzeProject(projectId: number): Promise<unknown> {
  return pipelineFetch(`/projects/${projectId}/analyze`, { method: "POST" });
}

// ── Pipeline Execution ──

export async function executePipeline(projectId: number): Promise<{ executed: number; tasks: DevTask[] }> {
  return pipelineFetch(`/execute/${projectId}`, { method: "POST" });
}

// ── Tasks ──

export async function listTasks(status?: string): Promise<DevTask[]> {
  const params = status ? `?status=${status}` : "";
  return pipelineFetch(`/tasks${params}`);
}

export async function getTaskDetail(taskId: number): Promise<TaskDetail> {
  return pipelineFetch(`/tasks/${taskId}`);
}

export async function transitionTask(taskId: number, status: string, reason = ""): Promise<DevTask> {
  return pipelineFetch(`/tasks/${taskId}/transition`, {
    method: "POST",
    body: JSON.stringify({ status, reason }),
  });
}

// ── Status ──

export async function getPipelineStatus(projectId: number): Promise<ProjectStatus_Response> {
  return pipelineFetch(`/status/${projectId}`);
}
