import { AlertTriangle, Check, X } from 'lucide-react'
import { useApp } from '../contexts/AppContext'

function formatArgs(args) {
  if (!args || Object.keys(args).length === 0) return null
  try {
    return JSON.stringify(args, null, 2)
  } catch {
    return String(args)
  }
}

function formatToolName(name) {
  if (name.startsWith('mcp__')) {
    const parts = name.split('__')
    return `${parts[1]}: ${parts.slice(2).join('.')}`
  }
  return name.replace(/_/g, ' ')
}

export default function ToolConfirmModal() {
  const { state, respondToApproval } = useApp()
  const request = state.pendingApprovals[0]

  if (!request) return null

  const formattedArgs = formatArgs(request.args)
  const remaining = state.pendingApprovals.length - 1

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/70 backdrop-blur-sm">
      <div className="bg-surface-800 border border-amber-500/40 rounded-2xl w-full max-w-lg shadow-2xl">
        <div className="flex items-center gap-2 px-6 py-4 border-b border-surface-600">
          <AlertTriangle size={16} className="text-amber-400 flex-shrink-0" />
          <h2 className="text-sm font-semibold text-gray-100">Claude wants to run a tool</h2>
        </div>

        <div className="px-6 py-4 space-y-3">
          <p className="text-sm font-mono text-gray-200">{formatToolName(request.toolName)}</p>
          {formattedArgs && (
            <pre className="text-xs font-mono text-gray-400 bg-surface-900 border border-surface-600 rounded-lg p-3 whitespace-pre-wrap break-all max-h-56 overflow-auto">
              {formattedArgs}
            </pre>
          )}
          <p className="text-xs text-gray-500">
            This action was flagged as higher-risk (shell command, git write, SSH, screen
            control, or access outside your workspace). You can turn this check off in
            Settings → Features.
          </p>
          {remaining > 0 && (
            <p className="text-xs text-gray-600">{remaining} more waiting after this one</p>
          )}
        </div>

        <div className="px-6 py-4 border-t border-surface-600 flex items-center justify-end gap-2">
          <button
            onClick={() => respondToApproval(request.toolCallId, false)}
            className="flex items-center gap-1.5 px-4 py-2 rounded-xl text-sm text-gray-300 hover:bg-surface-600 transition-colors"
          >
            <X size={14} />
            Deny
          </button>
          <button
            onClick={() => respondToApproval(request.toolCallId, true)}
            className="flex items-center gap-1.5 px-4 py-2 bg-accent hover:bg-accent-hover text-white text-sm rounded-xl transition-colors"
          >
            <Check size={14} />
            Approve
          </button>
        </div>
      </div>
    </div>
  )
}
