import { useState } from "react";
import { Plus, X } from "lucide-react";

interface Props {
  onSubmit: (name: string, description: string, repoUrl?: string) => Promise<void>;
  onCancel: () => void;
}

export function ProjectForm({ onSubmit, onCancel }: Props) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [repoUrl, setRepoUrl] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim() || !description.trim()) return;

    try {
      setSubmitting(true);
      await onSubmit(name.trim(), description.trim(), repoUrl.trim() || undefined);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
      <form
        onSubmit={handleSubmit}
        className="bg-[#141414] border border-[#2a2a2a] rounded-xl w-full max-w-lg p-6 space-y-4"
      >
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold text-gray-100">New Project</h2>
          <button type="button" onClick={onCancel} className="p-1 text-gray-400 hover:text-white">
            <X size={20} />
          </button>
        </div>

        <div>
          <label className="block text-sm text-gray-400 mb-1">Project Name</label>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g., TODO App"
            className="w-full bg-[#1a1a1a] border border-[#2a2a2a] rounded-lg px-3 py-2 text-sm text-gray-200 placeholder-gray-600 focus:outline-none focus:border-blue-500"
            required
          />
        </div>

        <div>
          <label className="block text-sm text-gray-400 mb-1">Description</label>
          <textarea
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Describe the project in detail. The AI will use this to generate tasks..."
            rows={5}
            className="w-full bg-[#1a1a1a] border border-[#2a2a2a] rounded-lg px-3 py-2 text-sm text-gray-200 placeholder-gray-600 focus:outline-none focus:border-blue-500 resize-none"
            required
          />
        </div>

        <div>
          <label className="block text-sm text-gray-400 mb-1">Repository URL (optional)</label>
          <input
            type="text"
            value={repoUrl}
            onChange={(e) => setRepoUrl(e.target.value)}
            placeholder="https://github.com/user/repo"
            className="w-full bg-[#1a1a1a] border border-[#2a2a2a] rounded-lg px-3 py-2 text-sm text-gray-200 placeholder-gray-600 focus:outline-none focus:border-blue-500"
          />
        </div>

        <div className="flex justify-end gap-2 pt-2">
          <button
            type="button"
            onClick={onCancel}
            className="px-4 py-2 text-sm text-gray-400 hover:text-white transition-colors"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={submitting || !name.trim() || !description.trim()}
            className="flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-500 disabled:bg-blue-600/50 disabled:cursor-not-allowed rounded-lg text-sm text-white transition-colors"
          >
            <Plus size={16} />
            {submitting ? "Creating..." : "Create Project"}
          </button>
        </div>
      </form>
    </div>
  );
}
