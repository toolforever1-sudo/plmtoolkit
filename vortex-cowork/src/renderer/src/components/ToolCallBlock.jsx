import { useState } from 'react'
import { ChevronDown, ChevronRight, Terminal, FileText, FolderOpen, Code, Zap, CheckCircle, AlertCircle, Loader, GitBranch, Server, Monitor, Camera, Brain } from 'lucide-react'

const TOOL_ICONS = {
  read_file: FileText,
  write_file: FileText,
  list_files: FolderOpen,
  run_bash: Terminal,
  git_status: GitBranch, git_diff: GitBranch, git_log: GitBranch, git_branch_list: GitBranch,
  git_add: GitBranch, git_checkout: GitBranch, git_commit: GitBranch, git_push: GitBranch, git_pull: GitBranch,
  ssh_exec: Server,
  computer_screenshot: Camera,
  computer_click: Monitor, computer_move: Monitor, computer_type: Monitor,
  computer_keypress: Monitor, computer_scroll: Monitor,
  remember: Brain, recall: Brain, list_memories: Brain, forget: Brain,
}

function getToolIcon(name) {
  const base = name.startsWith('mcp__') ? Zap : (TOOL_ICONS[name] || Code)
  return base
}

function formatToolName(name) {
  if (name.startsWith('mcp__')) {
    const parts = name.split('__')
    return `${parts[1]}: ${parts.slice(2).join('.')}`
  }
  return name.replace(/_/g, ' ')
}

function formatArgs(args) {
  if (!args || Object.keys(args).length === 0) return null
  try {
    return JSON.stringify(args, null, 2)
  } catch {
    return String(args)
  }
}

function formatResult(result) {
  if (!result) return null
  if (typeof result === 'string') return result
  try {
    return JSON.stringify(result, null, 2)
  } catch {
    return String(result)
  }
}

// A single tool call row
function ToolCallItem({ event, resultEvent }) {
  const [expanded, setExpanded] = useState(false)
  const Icon = getToolIcon(event.toolName)
  const isDone = !!resultEvent
  const isError = resultEvent?.result?.error

  return (
    <div className="border border-surface-500 rounded-lg overflow-hidden mb-2">
      {/* Header */}
      <button
        onClick={() => setExpanded(p => !p)}
        className="w-full flex items-center gap-2 px-3 py-2 hover:bg-surface-600 transition-colors text-left"
      >
        <Icon size={13} className="text-accent flex-shrink-0" />
        <span className="text-xs font-mono font-medium text-gray-200 flex-1">
          {formatToolName(event.toolName)}
        </span>
        {!isDone ? (
          <Loader size={12} className="text-accent animate-spin flex-shrink-0" />
        ) : isError ? (
          <AlertCircle size={12} className="text-red-400 flex-shrink-0" />
        ) : (
          <CheckCircle size={12} className="text-green-400 flex-shrink-0" />
        )}
        {expanded ? (
          <ChevronDown size={12} className="text-gray-500 flex-shrink-0" />
        ) : (
          <ChevronRight size={12} className="text-gray-500 flex-shrink-0" />
        )}
      </button>

      {/* Body */}
      {expanded && (
        <div className="tool-expand border-t border-surface-500">
          {event.args && Object.keys(event.args).length > 0 && (
            <div className="px-3 py-2 border-b border-surface-500">
              <p className="text-xs text-gray-500 mb-1 uppercase tracking-wide">Input</p>
              <pre className="text-xs font-mono text-gray-300 whitespace-pre-wrap break-all overflow-auto max-h-40">
                {formatArgs(event.args)}
              </pre>
            </div>
          )}
          {resultEvent && resultEvent.result?.dataUrl && (
            <div className="px-3 py-2">
              <p className="text-xs mb-1 uppercase tracking-wide text-gray-500">Output</p>
              <img
                src={resultEvent.result.dataUrl}
                alt="Tool result"
                className="max-w-full rounded-lg border border-surface-500"
              />
            </div>
          )}
          {resultEvent && !resultEvent.result?.dataUrl && (
            <div className="px-3 py-2">
              <p className={`text-xs mb-1 uppercase tracking-wide ${isError ? 'text-red-500' : 'text-gray-500'}`}>
                {isError ? 'Error' : 'Output'}
              </p>
              <pre className={`text-xs font-mono whitespace-pre-wrap break-all overflow-auto max-h-48 ${
                isError ? 'text-red-300' : 'text-gray-300'
              }`}>
                {formatResult(resultEvent.result)}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

// Renders all tool events for a given message
export default function ToolCallBlock({ toolEvents }) {
  if (!toolEvents || toolEvents.length === 0) return null

  // Pair executing events with their results
  const executingEvents = toolEvents.filter(e => e.type === 'chat:tool-executing')
  const resultEvents = toolEvents.filter(e => e.type === 'chat:tool-result')

  if (executingEvents.length === 0) return null

  return (
    <div className="mt-2 mb-3">
      <p className="text-xs text-gray-500 mb-2 flex items-center gap-1">
        <Zap size={10} />
        Tool calls ({executingEvents.length})
      </p>
      {executingEvents.map((ev) => {
        const result = resultEvents.find(r => r.toolId === ev.toolId)
        return (
          <ToolCallItem
            key={ev.toolId}
            event={ev}
            resultEvent={result}
          />
        )
      })}
    </div>
  )
}
