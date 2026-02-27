import { useState } from "react";
import { usePipeline } from "../../hooks/usePipeline";
import { ProjectForm } from "./ProjectForm";
import { TaskBoard } from "./TaskBoard";
import { TaskDetail } from "./TaskDetail";
import {
  Plus,
  Play,
  RefreshCw,
  Sparkles,
  ChevronDown,
  AlertCircle,
  Loader2,
} from "lucide-react";
import type { DevTask } from "../../types/pipeline";

export function PipelineView() {
  const {
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
  } = usePipeline();

  const [showProjectForm, setShowProjectForm] = useState(false);
  const [showProjectSelect, setShowProjectSelect] = useState(false);

  const activeProject = projects.find((p) => p.id === activeProjectId);
  const tasks = projectStatus?.tasks ?? [];

  const handleCreateProject = async (name: string, description: string, repoUrl?: string) => {
    await createProject(name, description, repoUrl);
    setShowProjectForm(false);
  };

  const handleTaskClick = (task: DevTask) => {
    loadTaskDetail(task.id);
  };

  const handleTransition = async (taskId: number, status: string, reason?: string) => {
    await transitionTask(taskId, status, reason);
  };

  return (
    <div className="flex flex-col h-full">
      {/* Toolbar */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-[#1e1e1e]">
        <div className="flex items-center gap-3">
          {/* Project selector */}
          <div className="relative">
            <button
              onClick={() => setShowProjectSelect(!showProjectSelect)}
              className="flex items-center gap-2 px-3 py-1.5 bg-[#1a1a1a] border border-[#2a2a2a] rounded-lg text-sm text-gray-200 hover:border-[#3a3a3a] transition-colors"
            >
              {activeProject ? activeProject.name : "Select Project"}
              <ChevronDown size={14} />
            </button>

            {showProjectSelect && (
              <>
                <div className="fixed inset-0 z-10" onClick={() => setShowProjectSelect(false)} />
                <div className="absolute top-full mt-1 left-0 z-20 bg-[#1a1a1a] border border-[#2a2a2a] rounded-lg shadow-xl min-w-[200px]">
                  {projects.map((p) => (
                    <button
                      key={p.id}
                      onClick={() => {
                        setActiveProjectId(p.id);
                        setShowProjectSelect(false);
                      }}
                      className={`w-full text-left px-3 py-2 text-sm hover:bg-[#252525] first:rounded-t-lg last:rounded-b-lg ${
                        p.id === activeProjectId ? "text-blue-400" : "text-gray-300"
                      }`}
                    >
                      {p.name}
                    </button>
                  ))}
                  {projects.length === 0 && (
                    <div className="px-3 py-2 text-sm text-gray-600">No projects yet</div>
                  )}
                </div>
              </>
            )}
          </div>

          <button
            onClick={() => setShowProjectForm(true)}
            className="flex items-center gap-1 px-3 py-1.5 bg-blue-600 hover:bg-blue-500 rounded-lg text-sm text-white transition-colors"
          >
            <Plus size={14} />
            New Project
          </button>
        </div>

        <div className="flex items-center gap-2">
          {/* Stats */}
          {projectStatus && (
            <div className="flex items-center gap-3 mr-4 text-xs text-gray-500">
              <span>Tasks: {projectStatus.totalTasks}</span>
              {Object.entries(projectStatus.tasksByStatus).map(([status, count]) => (
                <span key={status}>
                  {status}: {count}
                </span>
              ))}
            </div>
          )}

          {/* Action buttons */}
          {activeProjectId && (
            <>
              <button
                onClick={() => analyzeProject(activeProjectId)}
                disabled={loading}
                className="flex items-center gap-1 px-3 py-1.5 bg-purple-600 hover:bg-purple-500 disabled:bg-purple-600/50 rounded-lg text-sm text-white transition-colors"
                title="Analyze project and generate tasks"
              >
                <Sparkles size={14} />
                Analyze
              </button>
              <button
                onClick={() => executePipeline(activeProjectId)}
                disabled={loading}
                className="flex items-center gap-1 px-3 py-1.5 bg-green-600 hover:bg-green-500 disabled:bg-green-600/50 rounded-lg text-sm text-white transition-colors"
                title="Execute next batch of ready tasks"
              >
                <Play size={14} />
                Execute
              </button>
            </>
          )}

          <button
            onClick={refresh}
            disabled={loading}
            className="p-1.5 text-gray-400 hover:text-white transition-colors"
            title="Refresh"
          >
            <RefreshCw size={16} className={loading ? "animate-spin" : ""} />
          </button>
        </div>
      </div>

      {/* Error banner */}
      {error && (
        <div className="flex items-center gap-2 px-4 py-2 bg-red-500/10 border-b border-red-500/20 text-sm text-red-400">
          <AlertCircle size={14} />
          {error}
        </div>
      )}

      {/* Loading indicator */}
      {loading && (
        <div className="flex items-center gap-2 px-4 py-2 bg-blue-500/10 border-b border-blue-500/20 text-sm text-blue-400">
          <Loader2 size={14} className="animate-spin" />
          Loading...
        </div>
      )}

      {/* Main content */}
      <div className="flex-1 overflow-hidden p-4">
        {!activeProjectId ? (
          <div className="flex flex-col items-center justify-center h-full text-gray-600">
            <Sparkles size={48} className="mb-4 text-gray-700" />
            <p className="text-lg mb-2">No project selected</p>
            <p className="text-sm">Create a project to start the AI pipeline</p>
            <button
              onClick={() => setShowProjectForm(true)}
              className="mt-4 flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-500 rounded-lg text-sm text-white transition-colors"
            >
              <Plus size={16} />
              Create Project
            </button>
          </div>
        ) : tasks.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-gray-600">
            <Sparkles size={48} className="mb-4 text-gray-700" />
            <p className="text-lg mb-2">No tasks yet</p>
            <p className="text-sm mb-4">
              Click "Analyze" to let the AI decompose your project into tasks
            </p>
            <button
              onClick={() => analyzeProject(activeProjectId)}
              disabled={loading}
              className="flex items-center gap-2 px-4 py-2 bg-purple-600 hover:bg-purple-500 rounded-lg text-sm text-white transition-colors"
            >
              <Sparkles size={16} />
              Analyze Project
            </button>
          </div>
        ) : (
          <TaskBoard tasks={tasks} onTaskClick={handleTaskClick} />
        )}
      </div>

      {/* Modals */}
      {showProjectForm && (
        <ProjectForm
          onSubmit={handleCreateProject}
          onCancel={() => setShowProjectForm(false)}
        />
      )}

      {selectedTask && (
        <TaskDetail
          detail={selectedTask}
          onClose={() => setSelectedTask(null)}
          onTransition={handleTransition}
        />
      )}
    </div>
  );
}
