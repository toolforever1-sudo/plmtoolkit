import { useState, useCallback, useEffect } from 'react'
import {
  Plus, MessageSquare, Folder, Settings, Trash2,
  FolderOpen, ChevronRight, ChevronDown, File,
  RefreshCw, ExternalLink, Brain
} from 'lucide-react'
import { useApp } from '../contexts/AppContext'

function FileTree({ files, depth = 0, onOpen }) {
  const [expanded, setExpanded] = useState({})

  const toggle = (name) => setExpanded(p => ({ ...p, [name]: !p[name] }))

  return (
    <div>
      {files.map(item => (
        <div key={item.path}>
          <div
            className={`flex items-center gap-1 px-2 py-0.5 rounded cursor-pointer hover:bg-surface-600 group text-xs`}
            style={{ paddingLeft: `${8 + depth * 12}px` }}
            onClick={() => {
              if (item.type === 'directory') toggle(item.name)
              else onOpen(item.path)
            }}
          >
            {item.type === 'directory' ? (
              <>
                {expanded[item.name] ? (
                  <ChevronDown size={10} className="text-gray-500 flex-shrink-0" />
                ) : (
                  <ChevronRight size={10} className="text-gray-500 flex-shrink-0" />
                )}
                <FolderOpen size={12} className="text-yellow-400 flex-shrink-0" />
              </>
            ) : (
              <>
                <span className="w-2.5 flex-shrink-0" />
                <File size={12} className="text-gray-400 flex-shrink-0" />
              </>
            )}
            <span className="truncate text-gray-300 ml-1">{item.name}</span>
          </div>
          {item.type === 'directory' && expanded[item.name] && (
            <SubDir path={item.path} depth={depth + 1} onOpen={onOpen} />
          )}
        </div>
      ))}
    </div>
  )
}

function SubDir({ path, depth, onOpen }) {
  const [files, setFiles] = useState(null)

  useState(() => {
    window.electron.listFiles(path).then(setFiles).catch(() => setFiles([]))
  })

  if (files === null) return (
    <div className="text-xs text-gray-600 pl-8">loading...</div>
  )
  return <FileTree files={files} depth={depth} onOpen={onOpen} />
}

function MemoryTab() {
  const [memories, setMemories] = useState(null)
  const [newNote, setNewNote] = useState('')
  const [adding, setAdding] = useState(false)

  const load = useCallback(() => {
    window.electron.listMemories().then(setMemories).catch(() => setMemories([]))
  }, [])

  useEffect(() => { load() }, [load])

  const handleAdd = useCallback(async () => {
    const content = newNote.trim()
    if (!content) return
    setAdding(true)
    try {
      await window.electron.addMemory(content)
      setNewNote('')
      load()
    } finally {
      setAdding(false)
    }
  }, [newNote, load])

  const handleDelete = useCallback(async (id) => {
    await window.electron.deleteMemory(id)
    load()
  }, [load])

  return (
    <div className="px-1">
      <div className="flex items-center gap-1 mb-2">
        <input
          value={newNote}
          onChange={e => setNewNote(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') handleAdd() }}
          placeholder="Add a note..."
          disabled={adding}
          className="flex-1 bg-surface-700 border border-surface-500 rounded-md px-2 py-1.5 text-xs text-gray-200 placeholder-gray-600 outline-none focus:border-accent/60"
        />
        <button
          onClick={handleAdd}
          disabled={adding || !newNote.trim()}
          className="p-1.5 rounded-md hover:bg-surface-600 text-gray-400 hover:text-white disabled:opacity-30 transition-colors"
          title="Add note"
        >
          <Plus size={13} />
        </button>
      </div>

      {memories === null ? (
        <p className="text-xs text-gray-600 text-center py-4">loading...</p>
      ) : memories.length === 0 ? (
        <p className="text-xs text-gray-600 px-2 py-4 text-center">No memories yet</p>
      ) : (
        <div className="space-y-1">
          {[...memories].reverse().map(m => (
            <div key={m.id} className="group flex items-start gap-1.5 px-2 py-1.5 rounded-lg hover:bg-surface-600 transition-colors">
              <Brain size={12} className="text-gray-500 flex-shrink-0 mt-0.5" />
              <span className="flex-1 text-xs text-gray-300 break-words">{m.content}</span>
              <button
                onClick={() => handleDelete(m.id)}
                className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:text-red-400 flex-shrink-0 transition-all"
              >
                <Trash2 size={11} />
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export default function Sidebar() {
  const { state, dispatch, newConversation, deleteConversation, setActiveConversation,
    clearConversation, selectWorkspace, refreshWorkspaceFiles } = useApp()

  const handleOpenFile = useCallback(async (filePath) => {
    const ext = filePath.split('.').pop()?.toLowerCase()
    if (ext === 'md' || ext === 'markdown') {
      try {
        const content = await window.electron.readFile(filePath)
        dispatch({
          type: 'CODE_PANEL_PUSH',
          artifact: { id: `md-${filePath}`, label: filePath.split('/').pop(), language: 'markdown', code: content }
        })
        return
      } catch (e) {
        console.error('Failed to read markdown file, falling back to external app:', e)
      }
    }
    window.electron.openFile(filePath)
  }, [dispatch])

  const handleRefreshFiles = useCallback(() => {
    refreshWorkspaceFiles('.')
  }, [refreshWorkspaceFiles])

  const activeConv = state.conversations.find(c => c.id === state.activeConversationId)

  return (
    <div className="w-64 flex-shrink-0 flex flex-col bg-surface-800 border-r border-surface-600">
      {/* Top spacer for macOS traffic lights */}
      <div className="h-8 flex-shrink-0 titlebar-drag" />

      {/* Logo + New Chat */}
      <div className="px-3 pb-3 flex items-center gap-2 titlebar-no-drag">
        <div className="flex items-center gap-2 flex-1 min-w-0">
          <div className="w-6 h-6 rounded-md bg-accent flex items-center justify-center flex-shrink-0">
            <span className="text-white text-xs font-bold">V</span>
          </div>
          <span className="text-sm font-semibold text-gray-200 truncate">Vortex Cowork</span>
        </div>
        <button
          onClick={newConversation}
          className="p-1.5 rounded-md hover:bg-surface-600 text-gray-400 hover:text-white transition-colors flex-shrink-0"
          title="New Chat"
        >
          <Plus size={16} />
        </button>
      </div>

      {/* Tab switcher */}
      <div className="flex px-2 gap-1 mb-2 titlebar-no-drag">
        <button
          onClick={() => dispatch({ type: 'SET_SIDEBAR_TAB', tab: 'chats' })}
          className={`flex-1 flex items-center justify-center gap-1.5 py-1.5 rounded-md text-xs font-medium transition-colors ${
            state.sidebarTab === 'chats'
              ? 'bg-surface-600 text-white'
              : 'text-gray-500 hover:text-gray-300'
          }`}
        >
          <MessageSquare size={12} />
          Chats
        </button>
        <button
          onClick={() => {
            dispatch({ type: 'SET_SIDEBAR_TAB', tab: 'files' })
            if (state.workspacePath) refreshWorkspaceFiles('.')
          }}
          className={`flex-1 flex items-center justify-center gap-1.5 py-1.5 rounded-md text-xs font-medium transition-colors ${
            state.sidebarTab === 'files'
              ? 'bg-surface-600 text-white'
              : 'text-gray-500 hover:text-gray-300'
          }`}
        >
          <Folder size={12} />
          Files
        </button>
        <button
          onClick={() => dispatch({ type: 'SET_SIDEBAR_TAB', tab: 'memory' })}
          className={`flex-1 flex items-center justify-center gap-1.5 py-1.5 rounded-md text-xs font-medium transition-colors ${
            state.sidebarTab === 'memory'
              ? 'bg-surface-600 text-white'
              : 'text-gray-500 hover:text-gray-300'
          }`}
        >
          <Brain size={12} />
          Memory
        </button>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto px-2 titlebar-no-drag">
        {state.sidebarTab === 'memory' ? (
          <MemoryTab />
        ) : state.sidebarTab === 'chats' ? (
          <div className="space-y-0.5">
            {state.conversations.length === 0 ? (
              <p className="text-xs text-gray-600 px-2 py-4 text-center">No conversations yet</p>
            ) : (
              state.conversations.map(conv => (
                <div
                  key={conv.id}
                  onClick={() => setActiveConversation(conv.id)}
                  className={`group flex items-center gap-2 px-2 py-2 rounded-lg cursor-pointer transition-colors ${
                    conv.id === state.activeConversationId
                      ? 'bg-accent/20 text-white'
                      : 'hover:bg-surface-600 text-gray-400 hover:text-gray-200'
                  } ${conv.ephemeral ? 'border border-dashed border-surface-400' : ''}`}
                >
                  <MessageSquare size={13} className="flex-shrink-0" />
                  <span className="flex-1 text-xs truncate">{conv.title}</span>
                  {conv.ephemeral && (
                    <span className="text-xs text-gray-600 flex-shrink-0" title="Ephemeral — not saved">👻</span>
                  )}
                  {conv.streaming && (
                    <span className="w-1.5 h-1.5 rounded-full bg-accent animate-pulse flex-shrink-0" />
                  )}
                  <button
                    onClick={(e) => { e.stopPropagation(); deleteConversation(conv.id) }}
                    className="opacity-0 group-hover:opacity-100 p-0.5 rounded hover:text-red-400 transition-all"
                  >
                    <Trash2 size={11} />
                  </button>
                </div>
              ))
            )}
          </div>
        ) : (
          <div>
            {!state.workspacePath ? (
              <div className="text-center py-6">
                <Folder size={24} className="text-gray-600 mx-auto mb-2" />
                <p className="text-xs text-gray-500 mb-3">No workspace selected</p>
                <button
                  onClick={selectWorkspace}
                  className="px-3 py-1.5 bg-accent hover:bg-accent-hover text-white text-xs rounded-md transition-colors"
                >
                  Select Folder
                </button>
              </div>
            ) : (
              <div>
                <div className="flex items-center gap-1 mb-2 px-1">
                  <div className="flex-1 min-w-0">
                    <p className="text-xs text-gray-400 truncate" title={state.workspacePath}>
                      {state.workspacePath.split('/').pop() || state.workspacePath}
                    </p>
                  </div>
                  <button onClick={handleRefreshFiles} className="p-1 rounded hover:bg-surface-600 text-gray-500 hover:text-gray-300" title="Refresh">
                    <RefreshCw size={11} />
                  </button>
                  <button onClick={() => window.electron.openInFinder()} className="p-1 rounded hover:bg-surface-600 text-gray-500 hover:text-gray-300" title="Open in Finder">
                    <ExternalLink size={11} />
                  </button>
                  <button onClick={selectWorkspace} className="p-1 rounded hover:bg-surface-600 text-gray-500 hover:text-gray-300" title="Change folder">
                    <FolderOpen size={11} />
                  </button>
                </div>
                {state.workspaceFiles.length === 0 ? (
                  <p className="text-xs text-gray-600 text-center py-4">
                    Click refresh to load files
                  </p>
                ) : (
                  <FileTree files={state.workspaceFiles} onOpen={handleOpenFile} />
                )}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Bottom bar */}
      <div className="p-2 border-t border-surface-600 flex items-center gap-2 titlebar-no-drag">
        {!state.workspacePath && (
          <button
            onClick={selectWorkspace}
            className="flex-1 flex items-center gap-2 px-2 py-1.5 rounded-md text-xs text-gray-500 hover:text-gray-300 hover:bg-surface-600 transition-colors"
          >
            <FolderOpen size={13} />
            <span>Open Workspace</span>
          </button>
        )}
        {state.workspacePath && (
          <div className="flex-1 flex items-center gap-1 px-2 py-1.5 rounded-md bg-surface-700 text-xs text-green-400 min-w-0">
            <div className="w-1.5 h-1.5 rounded-full bg-green-400 flex-shrink-0" />
            <span className="truncate flex-1" title={state.workspacePath}>{state.workspacePath.split('/').pop()}</span>
            <button
              onClick={async () => {
                await window.electron.setSettings({ workspacePath: '' })
                dispatch({ type: 'SET_WORKSPACE_PATH', path: null })
              }}
              className="flex-shrink-0 text-gray-600 hover:text-red-400 transition-colors ml-0.5"
              title="Clear workspace"
            >
              ×
            </button>
          </div>
        )}
        <button
          onClick={() => dispatch({ type: 'TOGGLE_SETTINGS' })}
          className="p-1.5 rounded-md hover:bg-surface-600 text-gray-500 hover:text-gray-300 transition-colors"
          title="Settings"
        >
          <Settings size={15} />
        </button>
      </div>
    </div>
  )
}
