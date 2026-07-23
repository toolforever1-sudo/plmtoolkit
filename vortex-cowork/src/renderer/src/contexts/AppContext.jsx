import { createContext, useContext, useReducer, useCallback, useEffect, useRef } from 'react'

const AppContext = createContext(null)

function generateId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

// Extract code blocks from markdown content
function extractCodeBlocks(content, conversationId, messageId) {
  if (!content) return []
  const blocks = []
  // Match ```lang\ncode``` or ```\ncode```
  const re = /```([^\n`]*)\n([\s\S]*?)```/g
  let match
  let idx = 0
  while ((match = re.exec(content)) !== null) {
    const rawLang = (match[1] || '').trim()
    const code = match[2]
    if (!code.trim()) continue
    // Try to derive a filename from the first comment line or use lang
    let label = rawLang || 'code'
    const firstLine = code.split('\n')[0].trim()
    // e.g. // filename: App.jsx  or  # script.py
    const fileMatch = firstLine.match(/(?:filename:|file:)\s*(\S+)/i)
      || firstLine.match(/^(?:\/\/|#)\s*([\w./\\-]+\.\w+)$/)
    if (fileMatch) label = fileMatch[1]
    blocks.push({
      id: `${messageId}-${idx++}`,
      label,
      language: rawLang || 'text',
      code,
      conversationId,
      messageId,
    })
  }
  return blocks
}

const initialState = {
  // Conversations
  conversations: [],
  activeConversationId: null,

  // Settings
  settings: null,
  settingsLoaded: false,

  // Workspace
  workspacePath: null,
  workspaceFiles: [],

  // MCP
  mcpServers: {},

  // Context workspaces
  workspaces: [],
  activeWorkspaceId: 'none',

  // Code panel
  codeArtifacts: [],
  codePanelOpen: false,
  activeArtifactId: null,

  // Presentation panel
  presentations: [],   // [{id, title, filename, path, slides, slideCount}]
  presentationPanelOpen: false,
  activePresentationId: null,

  // UI state
  showSettings: false,
  showFileExplorer: true,
  sidebarTab: 'chats', // 'chats' | 'files' | 'memory'

  // Tool approval gate — requests awaiting a user decision
  pendingApprovals: [],
}

function appReducer(state, action) {
  switch (action.type) {

    case 'SETTINGS_LOADED':
      return {
        ...state,
        settings: action.payload,
        settingsLoaded: true,
        workspacePath: action.payload.workspacePath || null,
        workspaces: action.payload.workspaces || [],
        activeWorkspaceId: action.payload.activeWorkspaceId || 'sandisk-it',
      }

    case 'SETTINGS_UPDATED':
      return {
        ...state,
        settings: action.payload,
        workspacePath: action.payload.workspacePath || state.workspacePath,
        workspaces: action.payload.workspaces || state.workspaces,
        activeWorkspaceId: action.payload.activeWorkspaceId || state.activeWorkspaceId,
      }

    case 'SET_ACTIVE_WORKSPACE':
      return { ...state, activeWorkspaceId: action.id }

    case 'PRESENTATION_ADD': {
      const pres = action.presentation
      return {
        ...state,
        presentations: [
          ...state.presentations.filter(p => p.filename !== pres.filename),
          pres
        ],
        presentationPanelOpen: true,
        activePresentationId: pres.id,
        codePanelOpen: false,  // close code panel to give room
      }
    }

    case 'PRESENTATION_PANEL_TOGGLE':
      return { ...state, presentationPanelOpen: !state.presentationPanelOpen }

    case 'PRESENTATION_SET_ACTIVE':
      return { ...state, activePresentationId: action.id, presentationPanelOpen: true }

    case 'CODE_PANEL_PUSH': {
      const exists = state.codeArtifacts.find(a => a.id === action.artifact.id)
      return {
        ...state,
        codeArtifacts: exists ? state.codeArtifacts : [...state.codeArtifacts, action.artifact],
        codePanelOpen: true,
        activeArtifactId: action.artifact.id,
      }
    }

    case 'CODE_PANEL_TOGGLE':
      return { ...state, codePanelOpen: !state.codePanelOpen }

    case 'CODE_PANEL_SET_ACTIVE':
      return { ...state, activeArtifactId: action.id, codePanelOpen: true }

    case 'CODE_ARTIFACTS_CLEAR':
      return { ...state, codeArtifacts: [], codePanelOpen: false, activeArtifactId: null }

    case 'RESTORE_CONVERSATIONS': {
      const convs = action.conversations.map(c => ({
        ...c,
        streaming: false,
        streamingContent: '',
        toolEvents: [],
      }))
      return {
        ...state,
        conversations: convs,
        activeConversationId: convs[0]?.id || null,
      }
    }

    case 'NEW_CONVERSATION': {
      const conv = {
        id: generateId(),
        title: 'New Chat',
        messages: [],
        createdAt: Date.now(),
        streaming: false,
        streamingContent: '',
        toolEvents: [], // live tool call events during streaming
        ephemeral: false, // persistent by default — opt in to ephemeral per-chat
        computerControlEnabled: false, // session-only, never persisted — resets on restart
      }
      return {
        ...state,
        conversations: [conv, ...state.conversations],
        activeConversationId: conv.id,
      }
    }

    case 'DELETE_CONVERSATION':
      return {
        ...state,
        conversations: state.conversations.filter(c => c.id !== action.id),
        activeConversationId:
          state.activeConversationId === action.id
            ? state.conversations.find(c => c.id !== action.id)?.id || null
            : state.activeConversationId,
      }

    case 'SET_ACTIVE_CONVERSATION':
      return { ...state, activeConversationId: action.id }

    case 'TOGGLE_CONVERSATION_EPHEMERAL':
      return {
        ...state,
        conversations: state.conversations.map(c =>
          c.id !== action.id ? c : { ...c, ephemeral: !c.ephemeral }
        )
      }

    case 'SET_COMPUTER_CONTROL':
      return {
        ...state,
        conversations: state.conversations.map(c =>
          c.id !== action.id ? c : { ...c, computerControlEnabled: action.enabled }
        )
      }

    case 'ADD_USER_MESSAGE': {
      const titleText = action.content || (action.attachments?.length ? `📎 ${action.attachments.map(a => a.fileName).join(', ')}` : 'New message')
      return {
        ...state,
        conversations: state.conversations.map(c =>
          c.id !== action.conversationId ? c : {
            ...c,
            messages: [...c.messages, {
              role: 'user',
              content: action.content,
              attachments: action.attachments || [],
              id: generateId()
            }],
            title: c.messages.length === 0 ? titleText.slice(0, 50) : c.title,
            streaming: true,
            streamingContent: '',
            toolEvents: [],
          }
        )
      }
    }

    case 'APPEND_TOKEN': {
      return {
        ...state,
        conversations: state.conversations.map(c =>
          c.id !== action.conversationId ? c : {
            ...c,
            streamingContent: (c.streamingContent || '') + action.token,
          }
        )
      }
    }

    case 'TOOL_EVENT': {
      return {
        ...state,
        conversations: state.conversations.map(c =>
          c.id !== action.conversationId ? c : {
            ...c,
            toolEvents: [...(c.toolEvents || []), action.event],
          }
        )
      }
    }

    case 'STREAMING_DONE': {
      let newArtifacts = state.codeArtifacts
      let codePanelOpen = state.codePanelOpen
      let activeArtifactId = state.activeArtifactId
      const newConversations = state.conversations.map(c => {
        if (c.id !== action.conversationId) return c
        const msgId = generateId()
        const assistantMsg = {
          role: 'assistant',
          content: c.streamingContent || '',
          id: msgId,
          toolEvents: c.toolEvents || [],
        }
        // Extract code blocks and add to panel
        const blocks = extractCodeBlocks(assistantMsg.content, action.conversationId, msgId)
        if (blocks.length > 0) {
          // Replace any previous artifacts from this same message (re-streams)
          newArtifacts = [
            ...state.codeArtifacts.filter(a => a.messageId !== msgId),
            ...blocks
          ]
          codePanelOpen = true
          activeArtifactId = blocks[blocks.length - 1].id
        }
        return {
          ...c,
          messages: [...c.messages, assistantMsg],
          streaming: false,
          streamingContent: '',
          toolEvents: [],
        }
      })
      return {
        ...state,
        conversations: newConversations,
        codeArtifacts: newArtifacts,
        codePanelOpen,
        activeArtifactId,
      }
    }

    case 'STREAMING_ERROR': {
      return {
        ...state,
        conversations: state.conversations.map(c => {
          if (c.id !== action.conversationId) return c
          return {
            ...c,
            streaming: false,
            streamingContent: '',
            toolEvents: [],
            messages: [
              ...c.messages,
              {
                role: 'assistant',
                content: `⚠️ Error: ${action.error}`,
                id: generateId(),
                isError: true,
              }
            ]
          }
        })
      }
    }

    case 'CLEAR_CONVERSATION': {
      return {
        ...state,
        conversations: state.conversations.map(c =>
          c.id !== action.id ? c : {
            ...c,
            messages: [],
            streaming: false,
            streamingContent: '',
            toolEvents: [],
            title: 'New Chat',
          }
        )
      }
    }

    case 'SET_WORKSPACE_PATH':
      return { ...state, workspacePath: action.path }

    case 'SET_WORKSPACE_FILES':
      return { ...state, workspaceFiles: action.files }

    case 'SET_MCP_SERVERS':
      return { ...state, mcpServers: action.servers }

    case 'TOGGLE_SETTINGS':
      return { ...state, showSettings: !state.showSettings }

    case 'SET_SIDEBAR_TAB':
      return { ...state, sidebarTab: action.tab }

    case 'TOOL_CONFIRM_REQUEST':
      return { ...state, pendingApprovals: [...state.pendingApprovals, action.request] }

    case 'TOOL_CONFIRM_RESOLVED':
      return {
        ...state,
        pendingApprovals: state.pendingApprovals.filter(r => r.toolCallId !== action.toolCallId)
      }

    default:
      return state
  }
}

export function AppProvider({ children }) {
  const [state, dispatch] = useReducer(appReducer, initialState)
  const saveTimerRef = useRef(null)

  // Debounced save — fires 1.5s after last conversation change
  const scheduleSave = useCallback((conversations) => {
    if (saveTimerRef.current) clearTimeout(saveTimerRef.current)
    saveTimerRef.current = setTimeout(() => {
      // Only persist non-streaming, non-empty, non-ephemeral conversations
      const toSave = conversations
        .filter(c => c.messages.length > 0 && !c.streaming && !c.ephemeral)
        .map(c => ({
          id: c.id,
          title: c.title,
          messages: c.messages,
          createdAt: c.createdAt,
        }))
      window.electron.saveConversations(toSave)
    }, 1500)
  }, [])

  // Load settings + persisted conversations on mount
  useEffect(() => {
    Promise.all([
      window.electron.getSettings(),
      window.electron.loadConversations(),
    ]).then(([s, savedConvs]) => {
      dispatch({ type: 'SETTINGS_LOADED', payload: s })
      if (savedConvs && savedConvs.length > 0) {
        dispatch({ type: 'RESTORE_CONVERSATIONS', conversations: savedConvs })
      } else {
        dispatch({ type: 'NEW_CONVERSATION' })
      }
    })
  }, [])

  // Save whenever conversations change. Runs even when the list is empty or
  // has no completed messages — otherwise deleting the last conversation never
  // rewrites the file and the deleted chats reappear on next launch.
  useEffect(() => {
    if (!state.settingsLoaded) return
    if (state.conversations.some(c => c.streaming)) return // wait for stream to finish
    scheduleSave(state.conversations)
  }, [state.conversations, state.settingsLoaded, scheduleSave])

  const newConversation = useCallback(() => {
    dispatch({ type: 'NEW_CONVERSATION' })
  }, [])

  const deleteConversation = useCallback((id) => {
    dispatch({ type: 'DELETE_CONVERSATION', id })
  }, [])

  const setActiveConversation = useCallback((id) => {
    dispatch({ type: 'SET_ACTIVE_CONVERSATION', id })
  }, [])

  const clearConversation = useCallback((id) => {
    dispatch({ type: 'CLEAR_CONVERSATION', id })
  }, [])

  const updateSettings = useCallback(async (newSettings) => {
    const updated = await window.electron.setSettings(newSettings)
    dispatch({ type: 'SETTINGS_UPDATED', payload: updated })
  }, [])

  const selectWorkspace = useCallback(async () => {
    const path = await window.electron.selectWorkspace()
    if (path) {
      dispatch({ type: 'SET_WORKSPACE_PATH', path })
      await updateSettings({ workspacePath: path })
    }
  }, [updateSettings])

  const refreshWorkspaceFiles = useCallback(async (dirPath = '.') => {
    if (!state.workspacePath) return
    try {
      const files = await window.electron.listFiles(dirPath)
      dispatch({ type: 'SET_WORKSPACE_FILES', files })
    } catch (e) {}
  }, [state.workspacePath])

  const respondToApproval = useCallback(async (toolCallId, approved) => {
    dispatch({ type: 'TOOL_CONFIRM_RESOLVED', toolCallId })
    await window.electron.respondToolApproval(toolCallId, approved)
  }, [])

  const stopGeneration = useCallback((conversationId) => {
    // Main aborts the stream and answers with chat:done — partial text is kept.
    window.electron.stopChat(conversationId)
  }, [])

  const setComputerControl = useCallback((conversationId, enabled) => {
    dispatch({ type: 'SET_COMPUTER_CONTROL', id: conversationId, enabled })
    window.electron.setComputerControlEnabled(conversationId, enabled)
  }, [])

  const switchWorkspace = useCallback(async (workspaceId) => {
    const updated = await window.electron.setActiveWorkspace(workspaceId)
    dispatch({ type: 'SETTINGS_UPDATED', payload: updated })
    dispatch({ type: 'SET_ACTIVE_WORKSPACE', id: workspaceId })
  }, [])

  const sendMessage = useCallback(async (conversationId, content, attachments = []) => {
    dispatch({ type: 'ADD_USER_MESSAGE', conversationId, content, attachments })

    const conv = state.conversations.find(c => c.id === conversationId)
    if (!conv) return

    // Build messages for API (existing + new user message)
    const messages = [
      ...conv.messages.map(m => ({
        role: m.role,
        content: m.content,
        attachments: m.attachments || []
      })),
      { role: 'user', content, attachments }
    ]

    // Register streaming listeners
    const cleanups = []

    cleanups.push(window.electron.on('chat:token', (data) => {
      if (data.conversationId === conversationId) {
        dispatch({ type: 'APPEND_TOKEN', conversationId, token: data.token })
      }
    }))

    const toolEventTypes = ['chat:tool-calls-start', 'chat:tool-executing', 'chat:tool-result']
    toolEventTypes.forEach(eventType => {
      cleanups.push(window.electron.on(eventType, (data) => {
        if (data.conversationId === conversationId) {
          dispatch({ type: 'TOOL_EVENT', conversationId, event: { type: eventType, ...data } })
        }
      }))
    })

    cleanups.push(window.electron.on('chat:tool-confirm-request', (data) => {
      if (data.conversationId === conversationId) {
        dispatch({ type: 'TOOL_CONFIRM_REQUEST', request: data })
      }
    }))

    cleanups.push(window.electron.on('chat:tool-result', (data) => {
      if (data.conversationId === conversationId) {
        // If this is a successful create_presentation result, push to panel
        if (data.toolName === 'create_presentation' && data.result?.success) {
          const r = data.result
          dispatch({
            type: 'PRESENTATION_ADD',
            presentation: {
              id: `pres-${Date.now()}`,
              title: r.title || r.filename,
              filename: r.filename,
              path: r.path,
              slides: r.slides || [],
              slideCount: r.slideCount || 0,
            }
          })
        }
      }
    }))

    cleanups.push(window.electron.on('chat:done', (data) => {
      if (data.conversationId === conversationId) {
        dispatch({ type: 'STREAMING_DONE', conversationId })
        cleanups.forEach(fn => fn && fn())
      }
    }))

    cleanups.push(window.electron.on('chat:error', (data) => {
      if (data.conversationId === conversationId) {
        dispatch({ type: 'STREAMING_ERROR', conversationId, error: data.error })
        cleanups.forEach(fn => fn && fn())
      }
    }))

    // Send to main process
    window.electron.sendChat({ messages, conversationId, computerControlEnabled: !!conv.computerControlEnabled })
  }, [state.conversations])

  return (
    <AppContext.Provider value={{
      state,
      dispatch,
      newConversation,
      deleteConversation,
      setActiveConversation,
      clearConversation,
      updateSettings,
      selectWorkspace,
      refreshWorkspaceFiles,
      sendMessage,
      stopGeneration,
      switchWorkspace,
      respondToApproval,
      setComputerControl,
    }}>
      {children}
    </AppContext.Provider>
  )
}

export function useApp() {
  const ctx = useContext(AppContext)
  if (!ctx) throw new Error('useApp must be used within AppProvider')
  return ctx
}
