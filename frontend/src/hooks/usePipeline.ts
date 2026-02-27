import { useState, useCallback, useEffect } from "react";
import type { Project, ProjectStatus_Response, TaskDetail } from "../types/pipeline";
import * as api from "../lib/pipeline-api";

export function usePipeline() {
  const [projects, setProjects] = useState<Project[]>([]);
  const [activeProjectId, setActiveProjectId] = useState<number | null>(null);
  const [projectStatus, setProjectStatus] = useState<ProjectStatus_Response | null>(null);
  const [selectedTask, setSelectedTask] = useState<TaskDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Load projects on mount
  useEffect(() => {
    loadProjects();
  }, []);

  // Load project status when active project changes
  useEffect(() => {
    if (activeProjectId !== null) {
      loadProjectStatus(activeProjectId);
    } else {
      setProjectStatus(null);
    }
  }, [activeProjectId]);

  const loadProjects = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.listProjects();
      setProjects(data);
      if (data.length > 0 && activeProjectId === null) {
        setActiveProjectId(data[0].id);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load projects");
    } finally {
      setLoading(false);
    }
  }, [activeProjectId]);

  const loadProjectStatus = useCallback(async (projectId: number) => {
    try {
      setLoading(true);
      setError(null);
      const data = await api.getProjectStatus(projectId);
      setProjectStatus(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load project status");
    } finally {
      setLoading(false);
    }
  }, []);

  const createProject = useCallback(async (name: string, description: string, repoUrl?: string) => {
    try {
      setLoading(true);
      setError(null);
      const project = await api.createProject(name, description, repoUrl);
      setProjects(prev => [project, ...prev]);
      setActiveProjectId(project.id);
      return project;
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to create project");
      throw e;
    } finally {
      setLoading(false);
    }
  }, []);

  const analyzeProject = useCallback(async (projectId: number) => {
    try {
      setLoading(true);
      setError(null);
      await api.analyzeProject(projectId);
      await loadProjectStatus(projectId);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Analysis failed");
      throw e;
    } finally {
      setLoading(false);
    }
  }, [loadProjectStatus]);

  const executePipeline = useCallback(async (projectId: number) => {
    try {
      setLoading(true);
      setError(null);
      const result = await api.executePipeline(projectId);
      await loadProjectStatus(projectId);
      return result;
    } catch (e) {
      setError(e instanceof Error ? e.message : "Execution failed");
      throw e;
    } finally {
      setLoading(false);
    }
  }, [loadProjectStatus]);

  const loadTaskDetail = useCallback(async (taskId: number) => {
    try {
      const detail = await api.getTaskDetail(taskId);
      setSelectedTask(detail);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to load task detail");
    }
  }, []);

  const transitionTask = useCallback(async (taskId: number, status: string, reason?: string) => {
    try {
      setError(null);
      await api.transitionTask(taskId, status, reason);
      if (activeProjectId !== null) {
        await loadProjectStatus(activeProjectId);
      }
      if (selectedTask && selectedTask.task.id === taskId) {
        await loadTaskDetail(taskId);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : "Transition failed");
      throw e;
    }
  }, [activeProjectId, loadProjectStatus, selectedTask, loadTaskDetail]);

  const refresh = useCallback(async () => {
    await loadProjects();
    if (activeProjectId !== null) {
      await loadProjectStatus(activeProjectId);
    }
  }, [loadProjects, activeProjectId, loadProjectStatus]);

  const silentRefresh = useCallback(async () => {
    try {
      const data = await api.listProjects();
      setProjects(data);
      if (activeProjectId !== null) {
        const status = await api.getProjectStatus(activeProjectId);
        setProjectStatus(status);
      }
    } catch {
      // Silent refresh — don't set error or loading state
    }
  }, [activeProjectId]);

  return {
    projects,
    activeProjectId,
    setActiveProjectId,
    projectStatus,
    selectedTask,
    setSelectedTask,
    loading,
    error,
    createProject,
    analyzeProject,
    executePipeline,
    loadTaskDetail,
    transitionTask,
    refresh,
    silentRefresh,
  };
}
