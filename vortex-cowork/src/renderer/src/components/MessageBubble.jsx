import { useMemo, useState, useCallback } from 'react'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import { Copy, Check, ExternalLink } from 'lucide-react'
import { useApp } from '../contexts/AppContext'
import ToolCallBlock from './ToolCallBlock'

// ─── Markdown renderer (prose only — no fenced code blocks) ───────────────────
const md = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: false,
  breaks: true,
  highlight: () => '', // handled separately
})
// Disable fence rendering — we split those out manually
md.renderer.rules.fence = () => ''
md.renderer.rules.code_block = () => ''

function renderProse(content) {
  if (!content) return ''
  try { return md.render(content) } catch (e) {
    return `<p>${content.replace(/</g, '&lt;').replace(/>/g, '&gt;')}</p>`
  }
}

// ─── Code block component ─────────────────────────────────────────────────────
function CodeBlock({ language, code, label }) {
  const [copied, setCopied] = useState(false)
  const { dispatch } = useApp()

  const highlighted = useMemo(() => {
    if (language && hljs.getLanguage(language)) {
      try { return hljs.highlight(code, { language, ignoreIllegals: true }).value } catch (_) {}
    }
    try { return hljs.highlightAuto(code).value } catch (_) {}
    return code.replace(/</g, '&lt;').replace(/>/g, '&gt;')
  }, [language, code])

  const handleCopy = useCallback(() => {
    navigator.clipboard.writeText(code)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }, [code])

  const handleOpenPanel = useCallback(() => {
    // Dispatch a temporary artifact so the panel opens on this block
    const id = `inline-${Date.now()}`
    dispatch({
      type: 'CODE_PANEL_PUSH',
      artifact: { id, label: label || language || 'code', language: language || 'text', code }
    })
  }, [dispatch, label, language, code])

  const displayLang = label && label !== language ? label : (language || 'code')

  return (
    <div className="code-block-wrapper my-3 rounded-xl overflow-hidden border border-surface-500">
      {/* Header bar */}
      <div className="flex items-center justify-between px-3 py-1.5 bg-surface-700 border-b border-surface-500">
        <span className="text-xs font-mono text-gray-400">{displayLang}</span>
        <div className="flex items-center gap-1">
          <button
            onClick={handleOpenPanel}
            className="flex items-center gap-1 px-2 py-0.5 rounded text-xs text-gray-500 hover:text-gray-300 hover:bg-surface-600 transition-colors"
            title="Open in code panel"
          >
            <ExternalLink size={11} />
            <span>Panel</span>
          </button>
          <button
            onClick={handleCopy}
            className="flex items-center gap-1 px-2 py-0.5 rounded text-xs text-gray-500 hover:text-gray-300 hover:bg-surface-600 transition-colors"
            title="Copy code"
          >
            {copied
              ? <><Check size={11} className="text-green-400" /><span className="text-green-400">Copied</span></>
              : <><Copy size={11} /><span>Copy</span></>
            }
          </button>
        </div>
      </div>
      {/* Code body */}
      <pre className="m-0 p-4 bg-surface-900 overflow-x-auto text-sm leading-relaxed">
        <code
          className={`hljs language-${language || 'text'}`}
          dangerouslySetInnerHTML={{ __html: highlighted }}
        />
      </pre>
    </div>
  )
}

// ─── Content renderer: splits markdown into prose + code segments ─────────────
function ContentRenderer({ content, isStreaming }) {
  const segments = useMemo(() => {
    if (!content) return []
    const result = []
    const re = /```([^\n`]*)\n?([\s\S]*?)```/g
    let lastIndex = 0
    let match
    while ((match = re.exec(content)) !== null) {
      if (match.index > lastIndex) {
        result.push({ type: 'prose', content: content.slice(lastIndex, match.index) })
      }
      const rawLang = (match[1] || '').trim()
      const code = match[2] || ''
      // Try to derive filename from first comment line
      let label = rawLang
      const firstLine = code.split('\n')[0].trim()
      const fileMatch = firstLine.match(/(?:filename:|file:)\s*(\S+)/i)
        || firstLine.match(/^(?:\/\/|#)\s*([\w./\\-]+\.\w+)$/)
      if (fileMatch) label = fileMatch[1]
      result.push({ type: 'code', language: rawLang, label, code })
      lastIndex = match.index + match[0].length
    }
    if (lastIndex < content.length) {
      result.push({ type: 'prose', content: content.slice(lastIndex) })
    }
    return result
  }, [content])

  // During streaming, if we're mid-code-block just render as prose to avoid flicker
  const safeSegments = isStreaming
    ? [{ type: 'prose', content }]
    : segments

  return (
    <>
      {safeSegments.map((seg, i) =>
        seg.type === 'code' ? (
          <CodeBlock key={i} language={seg.language} label={seg.label} code={seg.code} />
        ) : seg.content?.trim() ? (
          <div
            key={i}
            className="prose-msg"
            dangerouslySetInnerHTML={{ __html: renderProse(seg.content) }}
          />
        ) : null
      )}
    </>
  )
}

// ─── User message ─────────────────────────────────────────────────────────────
function UserMessage({ message }) {
  const { content, attachments = [] } = message

  const fileIcon = (att) => {
    if (att.type === 'image') return '🖼️'
    const code = ['js','jsx','ts','tsx','py','sql','json','yaml','sh']
    if (code.includes(att.ext)) return '💻'
    if (['csv','xlsx','xls'].includes(att.ext)) return '📊'
    if (att.ext === 'pdf') return '📕'
    if (['docx','doc'].includes(att.ext)) return '📝'
    return '📄'
  }

  return (
    <div className="flex justify-end mb-4 msg-enter">
      <div className="max-w-[75%] space-y-2">
        {attachments.length > 0 && (
          <div className="flex flex-wrap gap-1.5 justify-end">
            {attachments.map((att, i) => (
              <div key={i} className="flex items-center gap-1.5 px-2.5 py-1 rounded-lg bg-surface-600 border border-surface-400 text-xs text-gray-300">
                <span>{fileIcon(att)}</span>
                <span className="font-medium truncate max-w-[120px]">{att.fileName}</span>
                <span className="text-gray-600">{att.sizeKB}KB</span>
              </div>
            ))}
          </div>
        )}
        {attachments.filter(a => a.type === 'image').map((att, i) => (
          <div key={`img-${i}`} className="flex justify-end">
            <img src={att.dataUrl} alt={att.fileName} className="max-w-full max-h-64 rounded-xl border border-surface-400 object-contain" />
          </div>
        ))}
        {content && (
          <div className="bg-accent/25 border border-accent/30 rounded-2xl rounded-tr-sm px-4 py-3">
            <p className="text-sm text-gray-100 whitespace-pre-wrap break-words leading-relaxed">{content}</p>
          </div>
        )}
        {!content && attachments.length > 0 && (
          <div className="bg-accent/25 border border-accent/30 rounded-2xl rounded-tr-sm px-4 py-3">
            <p className="text-sm text-gray-400 italic">Attached {attachments.length} file{attachments.length > 1 ? 's' : ''}</p>
          </div>
        )}
      </div>
    </div>
  )
}

// ─── Assistant message ────────────────────────────────────────────────────────
function AssistantMessage({ content, toolEvents, isStreaming, isError }) {
  return (
    <div className="flex gap-3 mb-4 msg-enter">
      <div className="w-7 h-7 rounded-full bg-gradient-to-br from-accent to-red-900 flex items-center justify-center flex-shrink-0 mt-0.5">
        <span className="text-white text-xs font-bold">V</span>
      </div>
      <div className="flex-1 min-w-0 max-w-[85%]">
        <ToolCallBlock toolEvents={toolEvents} />
        {content && !isError && (
          <ContentRenderer content={content} isStreaming={isStreaming} />
        )}
        {content && isError && (
          <p className="text-red-300 text-sm">{content}</p>
        )}
        {isStreaming && !content && (
          (!toolEvents || toolEvents.filter(e => e.type === 'chat:tool-executing').length === 0) && (
            <div className="flex gap-1 items-center py-1">
              <span className="w-1.5 h-1.5 rounded-full bg-accent animate-bounce" style={{ animationDelay: '0ms' }} />
              <span className="w-1.5 h-1.5 rounded-full bg-accent animate-bounce" style={{ animationDelay: '150ms' }} />
              <span className="w-1.5 h-1.5 rounded-full bg-accent animate-bounce" style={{ animationDelay: '300ms' }} />
            </div>
          )
        )}
        {isStreaming && content && (
          <span className="inline-block w-0.5 h-4 bg-accent animate-pulse ml-0.5 align-text-bottom" />
        )}
      </div>
    </div>
  )
}

// ─── Export ───────────────────────────────────────────────────────────────────
export default function MessageBubble({ message, isStreaming, streamingContent, toolEvents }) {
  if (isStreaming) {
    return <AssistantMessage content={streamingContent} toolEvents={toolEvents} isStreaming={true} />
  }
  if (message.role === 'user') return <UserMessage message={message} />
  return (
    <AssistantMessage
      content={message.content}
      toolEvents={message.toolEvents}
      isStreaming={false}
      isError={message.isError}
    />
  )
}
