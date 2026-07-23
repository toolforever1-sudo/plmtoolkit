import { useState, useMemo, useCallback } from 'react'
import { X, Copy, Check, ChevronRight, Trash2, Download } from 'lucide-react'
import hljs from 'highlight.js'
import { useApp } from '../contexts/AppContext'
import { renderMarkdown } from '../lib/markdown'

function highlight(code, language) {
  if (language && hljs.getLanguage(language)) {
    try { return hljs.highlight(code, { language, ignoreIllegals: true }).value } catch (_) {}
  }
  try { return hljs.highlightAuto(code).value } catch (_) {}
  return code.replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function fileIcon(lang) {
  const map = {
    js: '🟨', jsx: '⚛️', ts: '🔷', tsx: '⚛️',
    py: '🐍', sql: '🗄️', json: '{ }', yaml: '📋',
    sh: '🐚', bash: '🐚', html: '🌐', css: '🎨',
    java: '☕', go: '🐹', rs: '🦀', rb: '💎',
  }
  return map[lang?.toLowerCase()] || '📄'
}

export default function CodePanel() {
  const { state, dispatch } = useApp()
  const [copiedId, setCopiedId] = useState(null)

  const { codeArtifacts, codePanelOpen, activeArtifactId } = state

  const active = useMemo(
    () => codeArtifacts.find(a => a.id === activeArtifactId) || codeArtifacts[codeArtifacts.length - 1],
    [codeArtifacts, activeArtifactId]
  )

  const isMarkdownPreview = active?.language === 'markdown' || active?.language === 'md'

  const highlighted = useMemo(
    () => active && !isMarkdownPreview ? highlight(active.code, active.language) : '',
    [active, isMarkdownPreview]
  )

  const renderedMarkdown = useMemo(
    () => active && isMarkdownPreview ? renderMarkdown(active.code) : '',
    [active, isMarkdownPreview]
  )

  const handleCopy = useCallback((artifact) => {
    navigator.clipboard.writeText(artifact.code)
    setCopiedId(artifact.id)
    setTimeout(() => setCopiedId(null), 2000)
  }, [])

  const handleDownload = useCallback((artifact) => {
    const ext = artifact.language && artifact.language !== 'text' ? `.${artifact.language}` : '.txt'
    const filename = artifact.label.includes('.') ? artifact.label : `${artifact.label}${ext}`
    const blob = new Blob([artifact.code], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
    URL.revokeObjectURL(url)
  }, [])

  if (!codePanelOpen || codeArtifacts.length === 0) return null

  return (
    <div className="flex flex-col border-l border-surface-600 bg-surface-900" style={{ width: '420px', minWidth: '320px', maxWidth: '600px' }}>
      {/* Panel header */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-surface-600 flex-shrink-0">
        <span className="text-xs font-semibold text-gray-400 uppercase tracking-wide">Code</span>
        <div className="flex items-center gap-1">
          <button
            onClick={() => dispatch({ type: 'CODE_ARTIFACTS_CLEAR' })}
            className="p-1 rounded hover:bg-surface-600 text-gray-600 hover:text-gray-400 transition-colors"
            title="Clear all"
          >
            <Trash2 size={13} />
          </button>
          <button
            onClick={() => dispatch({ type: 'CODE_PANEL_TOGGLE' })}
            className="p-1 rounded hover:bg-surface-600 text-gray-500 hover:text-gray-300 transition-colors"
            title="Close panel"
          >
            <X size={14} />
          </button>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex overflow-x-auto border-b border-surface-600 flex-shrink-0" style={{ scrollbarWidth: 'none' }}>
        {codeArtifacts.map((artifact) => (
          <button
            key={artifact.id}
            onClick={() => dispatch({ type: 'CODE_PANEL_SET_ACTIVE', id: artifact.id })}
            className={`flex items-center gap-1.5 px-3 py-2 text-xs whitespace-nowrap border-r border-surface-600 transition-colors flex-shrink-0 ${
              artifact.id === active?.id
                ? 'bg-surface-800 text-gray-100 border-b-2 border-b-accent -mb-px'
                : 'text-gray-500 hover:text-gray-300 hover:bg-surface-700'
            }`}
          >
            <span>{fileIcon(artifact.language)}</span>
            <span className="font-mono">{artifact.label}</span>
          </button>
        ))}
      </div>

      {/* Active file toolbar */}
      {active && (
        <div className="flex items-center justify-between px-3 py-1.5 bg-surface-800 border-b border-surface-600 flex-shrink-0">
          <span className="text-xs text-gray-500 font-mono">{active.label}</span>
          <div className="flex items-center gap-1">
            <button
              onClick={() => handleDownload(active)}
              className="flex items-center gap-1 px-2 py-0.5 rounded text-xs text-gray-500 hover:text-gray-300 hover:bg-surface-600 transition-colors"
              title="Download file"
            >
              <Download size={11} />
              <span>Save</span>
            </button>
            <button
              onClick={() => handleCopy(active)}
              className="flex items-center gap-1 px-2 py-0.5 rounded text-xs text-gray-500 hover:text-gray-300 hover:bg-surface-600 transition-colors"
            >
              {copiedId === active.id
                ? <><Check size={11} className="text-green-400" /><span className="text-green-400">Copied</span></>
                : <><Copy size={11} /><span>Copy</span></>
              }
            </button>
          </div>
        </div>
      )}

      {/* Code body */}
      <div className="flex-1 overflow-auto">
        {active && isMarkdownPreview && (
          <div
            className="prose-msg p-4"
            dangerouslySetInnerHTML={{ __html: renderedMarkdown }}
          />
        )}
        {active && !isMarkdownPreview && (
          <pre className="m-0 p-4 text-sm leading-relaxed font-mono min-h-full">
            <code
              className={`hljs language-${active.language || 'text'}`}
              dangerouslySetInnerHTML={{ __html: highlighted }}
            />
          </pre>
        )}
      </div>

      {/* Line count footer */}
      {active && (
        <div className="px-3 py-1.5 border-t border-surface-600 flex-shrink-0">
          <span className="text-xs text-gray-600">
            {active.code.split('\n').length} lines · {active.language || 'text'}
          </span>
        </div>
      )}
    </div>
  )
}
