const { contextBridge, ipcRenderer } = require('electron')

const validReceiveChannels = [
  'chat:token',
  'chat:done',
  'chat:error',
  'chat:tool-calls-start',
  'chat:tool-executing',
  'chat:tool-result',
  'chat:tool-confirm-request',
]

contextBridge.exposeInMainWorld('electron', {
  // ── Settings ────────────────────────────────────────────────────────────────
  getSettings: () => ipcRenderer.invoke('settings:get'),
  setSettings: (s) => ipcRenderer.invoke('settings:set', s),
  resetSettings: () => ipcRenderer.invoke('settings:reset'),

  // ── Chat (streaming via IPC events) ─────────────────────────────────────────
  sendChat: (data) => ipcRenderer.send('chat:send', data),
  respondToolApproval: (toolCallId, approved) =>
    ipcRenderer.invoke('tool:respond-approval', { toolCallId, approved }),
  setComputerControlEnabled: (conversationId, enabled) =>
    ipcRenderer.send('computer:set-control-enabled', { conversationId, enabled }),

  // Register a listener for a specific streaming event
  on: (channel, callback) => {
    if (!validReceiveChannels.includes(channel)) return
    const listener = (_, data) => callback(data)
    ipcRenderer.on(channel, listener)
    // Return cleanup function
    return () => ipcRenderer.removeListener(channel, listener)
  },

  // Remove all streaming listeners at once
  offAllChatListeners: () => {
    validReceiveChannels.forEach(ch => ipcRenderer.removeAllListeners(ch))
  },

  // ── Workspace ───────────────────────────────────────────────────────────────
  selectWorkspace: () => ipcRenderer.invoke('workspace:select'),
  openInFinder: () => ipcRenderer.invoke('workspace:open-in-finder'),

  // ── File System ─────────────────────────────────────────────────────────────
  readFile: (filePath) => ipcRenderer.invoke('fs:read', filePath),
  writeFile: (filePath, content) => ipcRenderer.invoke('fs:write', filePath, content),
  listFiles: (dirPath) => ipcRenderer.invoke('fs:list', dirPath),
  openFile: (filePath) => ipcRenderer.invoke('fs:open', filePath),
  deleteFile: (filePath) => ipcRenderer.invoke('fs:delete', filePath),

  // ── MCP Servers ─────────────────────────────────────────────────────────────
  connectMcp: (config) => ipcRenderer.invoke('mcp:connect', config),
  disconnectMcp: (id) => ipcRenderer.invoke('mcp:disconnect', id),
  listMcp: () => ipcRenderer.invoke('mcp:list'),
  getUniversalDbTemplate: () => ipcRenderer.invoke('mcp:universalDbTemplate'),

  // ── Workspaces (context switcher) ───────────────────────────────────────────
  getWorkspaces: () => ipcRenderer.invoke('workspaces:get'),
  setWorkspaces: (ws) => ipcRenderer.invoke('workspaces:set', ws),
  setActiveWorkspace: (id) => ipcRenderer.invoke('workspaces:setActive', id),

  // ── File Attachments ────────────────────────────────────────────────────────
  pickAttachments: () => ipcRenderer.invoke('attachment:pick'),
  readAttachment: (filePath) => ipcRenderer.invoke('attachment:read', filePath),

  // ── Conversation Persistence ─────────────────────────────────────────────────
  loadConversations: () => ipcRenderer.invoke('conversations:load'),
  saveConversations: (convs) => ipcRenderer.invoke('conversations:save', convs),

  // ── Memory / Notes ───────────────────────────────────────────────────────────
  listMemories: () => ipcRenderer.invoke('memory:list'),
  addMemory: (content) => ipcRenderer.invoke('memory:add', content),
  deleteMemory: (id) => ipcRenderer.invoke('memory:delete', id),
})
