import { useState, useRef, useEffect, useCallback } from 'react'
import { Send, Trash2, Copy, Check, Paperclip, X, ChevronDown, Globe, Code2, Presentation, Ghost, Monitor, Square } from 'lucide-react'
import { useApp } from '../contexts/AppContext'
import MessageBubble from './MessageBubble'

// ── Attachment pill ────────────────────────────────────────────────────────────
function AttachmentPill({ att, onRemove }) {
  const icons = { image: '🖼️', text: '📄' }
  return (
    <div className="flex items-center gap-1.5 bg-surface-600 border border-surface-400 rounded-lg px-2 py-1 text-xs text-gray-300 max-w-[160px]">
      <span className="flex-shrink-0">{icons[att.type] || '📎'}</span>
      <span className="truncate">{att.fileName}</span>
      <span className="text-gray-600 flex-shrink-0">{att.sizeKB}KB</span>
      <button onClick={onRemove} className="flex-shrink-0 hover:text-red-400 transition-colors ml-0.5">
        <X size={10} />
      </button>
    </div>
  )
}

// ── Workspace switcher dropdown ────────────────────────────────────────────────
function WorkspaceSwitcher() {
  const { state, switchWorkspace } = useApp()
  const [open, setOpen] = useState(false)
  const ref = useRef(null)

  const workspaces = state.workspaces || []
  const active = workspaces.find(w => w.id === state.activeWorkspaceId)

  useEffect(() => {
    const handler = (e) => { if (ref.current && !ref.current.contains(e.target)) setOpen(false) }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

  if (!workspaces.length) return null

  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => setOpen(p => !p)}
        className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-surface-700 border border-surface-500 hover:border-accent/50 text-xs text-gray-400 hover:text-gray-200 transition-colors"
        title="Switch context workspace"
      >
        <Globe size={11} />
        <span className="max-w-[120px] truncate">{active?.name || 'No Context'}</span>
        <ChevronDown size={10} className={`transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>

      {open && (
        <div className="absolute bottom-full mb-1 left-0 bg-surface-800 border border-surface-500 rounded-xl shadow-2xl overflow-hidden z-50 min-w-[200px]">
          <div className="px-3 py-2 border-b border-surface-600">
            <p className="text-xs font-semibold text-gray-400 uppercase tracking-wide">Context</p>
          </div>
          {workspaces.map(ws => (
            <button
              key={ws.id}
              onClick={() => { switchWorkspace(ws.id); setOpen(false) }}
              className={`w-full text-left px-3 py-2.5 text-sm hover:bg-surface-600 transition-colors flex items-center justify-between gap-2 ${
                ws.id === state.activeWorkspaceId ? 'text-white bg-accent/10' : 'text-gray-300'
              }`}
            >
              <span>{ws.name}</span>
              {ws.id === state.activeWorkspaceId && (
                <span className="w-1.5 h-1.5 rounded-full bg-accent flex-shrink-0" />
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

// ── Input bar ─────────────────────────────────────────────────────────────────
function InputBar({ conversationId, isStreaming }) {
  const { sendMessage, state } = useApp()
  const [value, setValue] = useState('')
  const [attachments, setAttachments] = useState([])
  const [attLoading, setAttLoading] = useState(false)
  const textareaRef = useRef(null)

  const canSubmit = (value.trim() || attachments.length > 0) && !isStreaming

  const handleSubmit = useCallback(() => {
    if (!canSubmit) return
    sendMessage(conversationId, value.trim(), attachments)
    setValue('')
    setAttachments([])
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto'
    }
  }, [value, attachments, canSubmit, conversationId, sendMessage])

  const handleKeyDown = useCallback((e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit()
    }
  }, [handleSubmit])

  const handleInput = useCallback((e) => {
    setValue(e.target.value)
    const ta = e.target
    ta.style.height = 'auto'
    ta.style.height = Math.min(ta.scrollHeight, 200) + 'px'
  }, [])

  const handleAttach = useCallback(async () => {
    setAttLoading(true)
    try {
      const picked = await window.electron.pickAttachments()
      if (picked?.length) {
        setAttachments(prev => [...prev, ...picked])
      }
    } catch (e) {
      console.error('Attachment error:', e)
    } finally {
      setAttLoading(false)
    }
  }, [])

  const activeWs = (state.workspaces || []).find(w => w.id === state.activeWorkspaceId)

  return (
    <div className="border-t border-surface-600 p-4">
      {/* Attachment pills */}
      {attachments.length > 0 && (
        <div className="flex flex-wrap gap-1.5 mb-2">
          {attachments.map((att, i) => (
            <AttachmentPill
              key={i}
              att={att}
              onRemove={() => setAttachments(prev => prev.filter((_, j) => j !== i))}
            />
          ))}
        </div>
      )}

      <div className="flex items-end gap-2 bg-surface-700 border border-surface-500 rounded-2xl px-4 py-3 focus-within:border-accent/60 transition-colors">
        {/* Attach button */}
        <button
          onClick={handleAttach}
          disabled={isStreaming || attLoading}
          className="p-1.5 rounded-lg hover:bg-surface-600 text-gray-500 hover:text-gray-300 disabled:opacity-40 transition-colors flex-shrink-0 mb-0.5"
          title="Attach file (PDF, DOCX, XLSX, images, code...)"
        >
          <Paperclip size={15} className={attLoading ? 'animate-spin' : ''} />
        </button>

        <textarea
          ref={textareaRef}
          value={value}
          onInput={handleInput}
          onKeyDown={handleKeyDown}
          placeholder={isStreaming ? 'Claude is thinking...' : 'Message Claude... (Enter to send, Shift+Enter for newline)'}
          disabled={isStreaming}
          rows={1}
          className="flex-1 bg-transparent resize-none outline-none text-sm text-gray-100 placeholder-gray-600 leading-relaxed max-h-48"
          style={{ scrollbarWidth: 'none' }}
        />
        <button
          onClick={handleSubmit}
          disabled={!canSubmit}
          className="p-2 rounded-xl bg-accent hover:bg-accent-hover disabled:opacity-30 disabled:cursor-not-allowed transition-all flex-shrink-0 mb-0.5"
        >
          <Send size={15} className="text-white" />
        </button>
      </div>

      {/* Footer row: workspace switcher + model info */}
      <div className="flex items-center justify-between mt-2 px-1">
        <WorkspaceSwitcher />
        <p className="text-xs text-gray-600">
          Vortex · {state.settings?.model?.split('/').pop() || 'claude-sonnet-4-6'}
          {activeWs && activeWs.id !== 'none' && (
            <span className="ml-1.5 text-accent/70">· {activeWs.name}</span>
          )}
        </p>
      </div>
    </div>
  )
}

function EmptyState({ conversationTitle }) {
  return (
    <div className="flex-1 flex flex-col items-center justify-center px-8 pb-16">
      <div className="w-14 h-14 rounded-2xl bg-gradient-to-br from-accent to-red-900 flex items-center justify-center mb-6 shadow-lg">
        <span className="text-white text-2xl font-bold">V</span>
      </div>
      <h1 className="text-2xl font-semibold text-gray-100 mb-2">Vortex Cowork</h1>
      <p className="text-gray-500 text-center max-w-sm text-sm leading-relaxed mb-8">
        Claude via your Vortex API gateway. Ask anything — I can read and write files in your workspace, run bash commands, and use connected MCP tools.
      </p>

      <div className="grid grid-cols-2 gap-3 w-full max-w-lg">
        {[
          { icon: '📁', title: 'Explore workspace', desc: 'List and read your project files' },
          { icon: '🐍', title: 'Write Python', desc: 'Generate and run scripts' },
          { icon: '🗄️', title: 'Oracle PL/SQL', desc: 'Help with queries and procedures' },
          { icon: '📊', title: 'Data analysis', desc: 'Analyze CSV, Excel, or SQL data' },
        ].map(item => (
          <div key={item.title} className="bg-surface-700 border border-surface-500 rounded-xl p-3 text-left">
            <div className="text-xl mb-1">{item.icon}</div>
            <p className="text-sm font-medium text-gray-200">{item.title}</p>
            <p className="text-xs text-gray-500 mt-0.5">{item.desc}</p>
          </div>
        ))}
      </div>
    </div>
  )
}

export default function ChatPanel({ conversation }) {
  const { clearConversation, dispatch, state, setComputerControl } = useApp()
  const messagesEndRef = useRef(null)
  const [copied, setCopied] = useState(false)

  // Auto scroll to bottom when messages change
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [conversation.messages, conversation.streamingContent, conversation.toolEvents])

  const handleCopyLast = useCallback(() => {
    const lastMsg = [...conversation.messages].reverse().find(m => m.role === 'assistant')
    if (!lastMsg) return
    navigator.clipboard.writeText(lastMsg.content)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }, [conversation.messages])

  const hasMessages = conversation.messages.length > 0 || conversation.streaming

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="h-12 flex items-center justify-between px-6 border-b border-surface-600 flex-shrink-0" style={{ paddingTop: '2px' }}>
        <div className="flex items-center gap-2 pt-6">
          <h2 className="text-sm font-medium text-gray-300 truncate max-w-xs">
            {conversation.title || 'New Chat'}
          </h2>
          {conversation.streaming && (
            <span className="flex items-center gap-1 text-xs text-accent">
              <span className="w-1.5 h-1.5 rounded-full bg-accent animate-pulse" />
              thinking
            </span>
          )}
        </div>

        <div className="flex items-center gap-1 pt-6">
          {/* Ephemeral session toggle */}
          <button
            onClick={() => dispatch({ type: 'TOGGLE_CONVERSATION_EPHEMERAL', id: conversation.id })}
            className={`flex items-center gap-1.5 px-2 py-1 rounded-lg text-xs transition-colors ${
              conversation.ephemeral
                ? 'bg-accent/20 text-accent'
                : 'hover:bg-surface-600 text-gray-500 hover:text-gray-300'
            }`}
            title={conversation.ephemeral
              ? 'Ephemeral — this chat will not be saved. Click to make it persistent.'
              : 'Persistent — this chat is saved. Click to make it ephemeral (not saved).'}
          >
            <Ghost size={13} />
            {conversation.ephemeral && <span>ephemeral</span>}
          </button>

          {/* Computer control toggle */}
          <button
            onClick={() => setComputerControl(conversation.id, !conversation.computerControlEnabled)}
            className={`flex items-center gap-1.5 px-2 py-1 rounded-lg text-xs transition-colors ${
              conversation.computerControlEnabled
                ? 'bg-red-500/20 text-red-400'
                : 'hover:bg-surface-600 text-gray-500 hover:text-gray-300'
            }`}
            title={conversation.computerControlEnabled
              ? 'Claude can control your screen. Click to turn off.'
              : 'Give Claude screen control (mouse, keyboard, screenshots) for this conversation.'}
          >
            <Monitor size={13} />
            {conversation.computerControlEnabled && <span>controlling</span>}
          </button>

          {/* Presentation panel toggle */}
          {state.presentations?.length > 0 && (
            <button
              onClick={() => dispatch({ type: 'PRESENTATION_PANEL_TOGGLE' })}
              className={`flex items-center gap-1.5 px-2 py-1 rounded-lg text-xs transition-colors ${
                state.presentationPanelOpen
                  ? 'bg-accent/20 text-accent'
                  : 'hover:bg-surface-600 text-gray-500 hover:text-gray-300'
              }`}
              title="Toggle presentation panel"
            >
              <Presentation size={13} />
              <span>{state.presentations.length}</span>
            </button>
          )}

          {/* Code panel toggle */}
          {state.codeArtifacts?.length > 0 && (
            <button
              onClick={() => dispatch({ type: 'CODE_PANEL_TOGGLE' })}
              className={`flex items-center gap-1.5 px-2 py-1 rounded-lg text-xs transition-colors ${
                state.codePanelOpen
                  ? 'bg-accent/20 text-accent'
                  : 'hover:bg-surface-600 text-gray-500 hover:text-gray-300'
              }`}
              title="Toggle code panel"
            >
              <Code2 size={13} />
              <span>{state.codeArtifacts.length}</span>
            </button>
          )}
          {hasMessages && (
            <>
              <button
                onClick={handleCopyLast}
                className="p-1.5 rounded-lg hover:bg-surface-600 text-gray-500 hover:text-gray-300 transition-colors"
                title="Copy last response"
              >
                {copied ? <Check size={14} className="text-green-400" /> : <Copy size={14} />}
              </button>
              <button
                onClick={() => clearConversation(conversation.id)}
                className="p-1.5 rounded-lg hover:bg-surface-600 text-gray-500 hover:text-red-400 transition-colors"
                title="Clear conversation"
              >
                <Trash2 size={14} />
              </button>
            </>
          )}
        </div>
      </div>

      {/* Computer control banner */}
      {conversation.computerControlEnabled && (
        <div className="flex items-center justify-between gap-3 px-4 py-2 bg-red-500/15 border-b border-red-500/30 flex-shrink-0">
          <div className="flex items-center gap-2 text-xs text-red-300">
            <span className="w-1.5 h-1.5 rounded-full bg-red-400 animate-pulse flex-shrink-0" />
            Claude is controlling your screen
          </div>
          <button
            onClick={() => setComputerControl(conversation.id, false)}
            className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-red-500/20 hover:bg-red-500/30 text-red-300 text-xs transition-colors"
          >
            <Square size={11} />
            Stop
          </button>
        </div>
      )}

      {/* Messages */}
      <div className="flex-1 overflow-y-auto">
        {!hasMessages ? (
          <EmptyState conversationTitle={conversation.title} />
        ) : (
          <div className="max-w-3xl mx-auto px-6 py-6">
            {conversation.messages.map(msg => (
              <MessageBubble key={msg.id} message={msg} />
            ))}

            {/* Streaming message */}
            {conversation.streaming && (
              <MessageBubble
                isStreaming={true}
                streamingContent={conversation.streamingContent}
                toolEvents={conversation.toolEvents}
              />
            )}

            <div ref={messagesEndRef} />
          </div>
        )}
      </div>

      {/* Input */}
      <div className="max-w-3xl mx-auto w-full px-0">
        <InputBar
          conversationId={conversation.id}
          isStreaming={conversation.streaming}
        />
      </div>
    </div>
  )
}
