const { app, BrowserWindow, ipcMain, dialog, shell, nativeTheme } = require('electron')
const path = require('path')
const fs = require('fs')
const { exec, spawn, execFile } = require('child_process')
const { promisify } = require('util')
const os = require('os')

const execAsync = promisify(exec)
const execFileAsync = promisify(execFile)

// ─── Settings Storage ──────────────────────────────────────────────────────────
const settingsPath = path.join(app.getPath('userData'), 'settings.json')

const DEFAULT_WORKSPACES = [
  {
    id: 'none',
    name: 'No Context',
    systemPrompt: ''
  },
  {
    id: 'sandisk-it',
    name: 'SanDisk IT Supply Chain',
    systemPrompt: `You are a helpful assistant for Pranav, a Senior Industrial Engineer at SanDisk working in IT Supply Chain. You help with Oracle PL/SQL, Python automation, supply chain planning, and data analysis.

When working with files, always use the available tools to read, write, and list files in the workspace. When executing code or commands, use the run_bash tool.`
  },
  {
    id: 'sandisk-legal',
    name: 'SanDisk Legal',
    systemPrompt: `You are a legal research assistant for Pranav at SanDisk. You help analyze contracts, review legal documents, summarize case law, and assist with compliance questions. Be precise and always note when professional legal counsel should be consulted.`
  }
]

const DEFAULT_SETTINGS = {
  apiKey: process.env.PORTKEY_KEY || '',
  baseUrl: process.env.VORTEX_BASE_URL || 'https://ai.vortex.sandisk.com/v1/',
  model: process.env.VORTEX_MODEL || '@anthropic-eastus2/claude-sonnet-4-6',
  systemPrompt: `You are a helpful assistant for Pranav, a Senior Industrial Engineer at SanDisk working in IT Supply Chain. You help with Oracle PL/SQL, Python automation, supply chain planning, and data analysis.

When working with files, always use the available tools to read, write, and list files in the workspace. When executing code or commands, use the run_bash tool.`,
  workspacePath: '',
  mcpServers: [],
  sshHosts: [],
  enableTools: true,
  streamingEnabled: true,
  confirmRiskyTools: true,
  maxTokens: 4096,
  theme: 'dark',
  // Workspace/context switcher — default to no context
  workspaces: DEFAULT_WORKSPACES,
  activeWorkspaceId: 'none',
}

function loadSettings() {
  try {
    if (fs.existsSync(settingsPath)) {
      const saved = JSON.parse(fs.readFileSync(settingsPath, 'utf-8'))
      const merged = { ...DEFAULT_SETTINGS, ...saved }
      // Migration: if saved before workspaces feature existed, reset to no-context default
      if (!saved.workspaces || !Array.isArray(saved.workspaces) || saved.workspaces.length === 0) {
        merged.workspaces = DEFAULT_WORKSPACES
        merged.activeWorkspaceId = 'none'
      }
      return merged
    }
  } catch (e) {
    console.error('Failed to load settings:', e.message)
  }
  return { ...DEFAULT_SETTINGS }
}

function saveSettings(s) {
  try {
    fs.writeFileSync(settingsPath, JSON.stringify(s, null, 2))
  } catch (e) {
    console.error('Failed to save settings:', e.message)
  }
}

let settings = loadSettings()

// ─── Window ────────────────────────────────────────────────────────────────────
let mainWindow

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 900,
    minHeight: 600,
    titleBarStyle: 'hiddenInset',
    trafficLightPosition: { x: 16, y: 16 },
    backgroundColor: '#0f0f0f',
    vibrancy: 'under-window',
    webPreferences: {
      preload: path.join(__dirname, '../preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  })

  // Load env for API key — try multiple locations
  try {
    const dotenv = require('dotenv')
    const envPaths = [
      path.join(process.cwd(), '.env'),
      path.join(__dirname, '../../.env'),
      path.join(__dirname, '../../../.env'),
      path.join(app.getAppPath(), '.env'),
    ]
    for (const p of envPaths) {
      if (fs.existsSync(p)) {
        dotenv.config({ path: p })
        break
      }
    }
    if (process.env.PORTKEY_KEY && process.env.PORTKEY_KEY !== settings.apiKey) {
      settings.apiKey = process.env.PORTKEY_KEY
      saveSettings(settings)
    }
  } catch (e) {}

  if (process.env['ELECTRON_RENDERER_URL']) {
    mainWindow.loadURL(process.env['ELECTRON_RENDERER_URL'])
  } else {
    mainWindow.loadFile(path.join(__dirname, '../renderer/index.html'))
  }
}

app.whenReady().then(() => {
  nativeTheme.themeSource = 'dark'
  createWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  // Terminate all MCP processes
  for (const [, conn] of mcpConnections.entries()) {
    try { conn.process.kill() } catch (e) {}
  }
  if (process.platform !== 'darwin') app.quit()
})

// ─── Built-in Tools ────────────────────────────────────────────────────────────
const BUILTIN_TOOLS = [
  {
    type: 'function',
    function: {
      name: 'read_file',
      description: 'Read a file from the workspace. Text/code files return their content. Images (png, jpg, gif, webp...) are returned as an actual image you can look at. PDF, DOCX, and XLSX return extracted text.',
      parameters: {
        type: 'object',
        properties: {
          path: {
            type: 'string',
            description: 'File path relative to workspace root (e.g. "src/main.py" or "data/report.csv")'
          }
        },
        required: ['path']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'write_file',
      description: 'Write or overwrite a file in the workspace. Creates parent directories if needed.',
      parameters: {
        type: 'object',
        properties: {
          path: {
            type: 'string',
            description: 'File path relative to workspace root'
          },
          content: {
            type: 'string',
            description: 'Full content to write to the file'
          }
        },
        required: ['path', 'content']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'list_files',
      description: 'List files and directories at a given path in the workspace.',
      parameters: {
        type: 'object',
        properties: {
          path: {
            type: 'string',
            description: 'Directory path relative to workspace root. Use "." for the root.'
          }
        },
        required: ['path']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'create_presentation',
      description: `Create or fully replace a PowerPoint presentation (.pptx) file, strictly following the SanDisk Corporate Presentation Template v3.1 brand guidelines — Pilat + PP Fraktion Mono fonts, the official SanDisk red/terracotta/neutral palette, [CONFIDENTIAL] marking, "SPACE TO HOLD MORE" tagline, page numbers, and copyright footer on every slide. The palette and fonts are fixed; there is no theme parameter.
Call this whenever the user asks to make, build, create, or redesign a slide deck. Also call this to apply edits — just pass the full updated slides array.
Slide types: "title" (cover slide — big title + subtitle + date/presenter footer), "section" (segue/divider slide), "bullets" (title + bullet list), "content" (title + body text), "two-column" (title + left/right content), "quote" (large quote + attribution), "closing" (closing slide — appended automatically if you don't include one).`,
      parameters: {
        type: 'object',
        properties: {
          filename: { type: 'string', description: 'Output filename without extension, e.g. "Q3_Review"' },
          title: { type: 'string', description: 'Presentation title (used for file metadata)' },
          slides: {
            type: 'array',
            description: 'Array of slide objects',
            items: {
              type: 'object',
              properties: {
                type: { type: 'string', enum: ['title', 'section', 'bullets', 'content', 'two-column', 'quote', 'closing'] },
                variant: { type: 'string', enum: ['dark', 'red', 'light', 'gray'], description: 'Background variant for title/section slides, default "dark"' },
                title: { type: 'string' },
                subtitle: { type: 'string' },
                date: { type: 'string', description: 'Date shown in the title slide footer' },
                presenter: { type: 'string', description: 'Presenter name/title shown in the title slide footer' },
                content: { type: 'string' },
                bullets: { type: 'array', items: { type: 'string' } },
                left: { type: 'string', description: 'Left column content for two-column slides' },
                right: { type: 'string', description: 'Right column content for two-column slides' },
                quote: { type: 'string' },
                attribution: { type: 'string' },
                notes: { type: 'string', description: 'Speaker notes' },
              },
              required: ['type']
            }
          }
        },
        required: ['filename', 'slides']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'create_spreadsheet',
      description: `Create a colorful, styled Excel (.xlsx) spreadsheet.
Call this whenever the user asks for a spreadsheet, budget, tracker, data export, or tabular report.
Supports a bold colored header row per sheet and optional per-row highlight colors.
The file is saved to the workspace.`,
      parameters: {
        type: 'object',
        properties: {
          filename: { type: 'string', description: 'Output filename without extension, e.g. "Q3_Budget"' },
          sheets: {
            type: 'array',
            description: 'One or more worksheets',
            items: {
              type: 'object',
              properties: {
                name: { type: 'string', description: 'Sheet tab name' },
                columns: {
                  type: 'array',
                  description: 'Column headers, in order',
                  items: {
                    type: 'object',
                    properties: {
                      header: { type: 'string' },
                      width: { type: 'number', description: 'Column width, default 18' }
                    },
                    required: ['header']
                  }
                },
                rows: {
                  type: 'array',
                  description: 'Each row is an array of cell values matching the columns order',
                  items: { type: 'array', items: {} }
                },
                rowColors: {
                  type: 'array',
                  description: 'Optional hex fill color per row, same order as rows (use null for no highlight), e.g. "#FFF3CD"',
                  items: { type: ['string', 'null'] }
                },
                headerFill: { type: 'string', description: 'Header row background hex, default "#E10600"' },
                headerTextColor: { type: 'string', description: 'Header row text hex, default "#FFFFFF"' }
              },
              required: ['name', 'columns', 'rows']
            }
          }
        },
        required: ['filename', 'sheets']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'run_bash',
      description: 'Execute a bash command in the workspace directory. Use for running scripts, git commands, installs, etc.',
      parameters: {
        type: 'object',
        properties: {
          command: {
            type: 'string',
            description: 'Bash command to execute'
          },
          timeout_ms: {
            type: 'number',
            description: 'Optional timeout in milliseconds (default: 30000)'
          }
        },
        required: ['command']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'git_status',
      description: 'Show the working tree status (staged/unstaged/untracked files) for the workspace git repo.',
      parameters: { type: 'object', properties: {} }
    }
  },
  {
    type: 'function',
    function: {
      name: 'git_diff',
      description: 'Show a diff of changes in the workspace git repo.',
      parameters: {
        type: 'object',
        properties: {
          staged: { type: 'boolean', description: 'Show staged (--cached) diff instead of unstaged, default false' },
          filePath: { type: 'string', description: 'Limit the diff to a single file, optional' }
        }
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'git_log',
      description: 'Show recent commit history for the workspace git repo.',
      parameters: {
        type: 'object',
        properties: {
          maxCount: { type: 'number', description: 'Number of commits to show, default 10' }
        }
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'git_branch_list',
      description: 'List local branches in the workspace git repo, showing the current branch.',
      parameters: { type: 'object', properties: {} }
    }
  },
  {
    type: 'function',
    function: {
      name: 'git_add',
      description: 'Stage files for commit in the workspace git repo.',
      parameters: {
        type: 'object',
        properties: {
          files: { type: 'array', items: { type: 'string' }, description: 'File paths to stage, or ["."] for everything' }
        },
        required: ['files']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'git_checkout',
      description: 'Switch to a branch, or create a new one, in the workspace git repo. Can discard uncommitted changes — requires confirmation.',
      parameters: {
        type: 'object',
        properties: {
          branch: { type: 'string', description: 'Branch name to check out' },
          createNew: { type: 'boolean', description: 'Create the branch if it does not already exist, default false' }
        },
        required: ['branch']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'git_commit',
      description: 'Commit staged changes in the workspace git repo. Requires confirmation.',
      parameters: {
        type: 'object',
        properties: {
          message: { type: 'string', description: 'Commit message' }
        },
        required: ['message']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'git_push',
      description: 'Push the current branch to its remote. Requires confirmation.',
      parameters: {
        type: 'object',
        properties: {
          remote: { type: 'string', description: 'Remote name, default "origin"' },
          branch: { type: 'string', description: 'Branch name, defaults to the current branch' }
        }
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'git_pull',
      description: 'Pull changes from the remote into the current branch. Requires confirmation.',
      parameters: {
        type: 'object',
        properties: {
          remote: { type: 'string', description: 'Remote name, default "origin"' },
          branch: { type: 'string', description: 'Branch name, defaults to the current branch' }
        }
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'ssh_exec',
      description: `Run a shell command on a pre-registered SSH host (configured in Settings → SSH Hosts). Requires confirmation.
This cannot connect to hosts that have not been explicitly registered — there is no key auto-discovery or connecting to arbitrary addresses.`,
      parameters: {
        type: 'object',
        properties: {
          hostId: { type: 'string', description: 'The id of a host registered in Settings → SSH Hosts (see the host list from settings)' },
          command: { type: 'string', description: 'Shell command to run on the remote host' }
        },
        required: ['hostId', 'command']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'computer_screenshot',
      description: 'Capture a screenshot of the current screen. Always available (read-only) — does not require screen control mode. Requires the macOS Screen Recording permission to be granted to this app.',
      parameters: { type: 'object', properties: {} }
    }
  },
  {
    type: 'function',
    function: {
      name: 'computer_click',
      description: 'Click the mouse at a screen coordinate. Only usable when the user has turned on "Give Claude screen control" for this conversation.',
      parameters: {
        type: 'object',
        properties: {
          x: { type: 'number' },
          y: { type: 'number' },
          button: { type: 'string', enum: ['left', 'right', 'double'], description: 'Default "left"' }
        },
        required: ['x', 'y']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'computer_move',
      description: 'Move the mouse to a screen coordinate without clicking. Only usable when screen control is on.',
      parameters: {
        type: 'object',
        properties: { x: { type: 'number' }, y: { type: 'number' } },
        required: ['x', 'y']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'computer_type',
      description: 'Type text at the current keyboard focus. Only usable when screen control is on.',
      parameters: {
        type: 'object',
        properties: { text: { type: 'string' } },
        required: ['text']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'computer_keypress',
      description: 'Press a single key, optionally with modifiers (e.g. Return, Tab, Escape, or a letter with "cmd"). Only usable when screen control is on.',
      parameters: {
        type: 'object',
        properties: {
          key: { type: 'string', description: 'Key name, e.g. "return", "tab", "escape", "a"' },
          modifiers: {
            type: 'array',
            items: { type: 'string', enum: ['cmd', 'shift', 'option', 'control'] },
            description: 'Optional modifier keys held during the press'
          }
        },
        required: ['key']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'computer_scroll',
      description: 'Scroll at a screen coordinate. Only usable when screen control is on.',
      parameters: {
        type: 'object',
        properties: {
          x: { type: 'number' },
          y: { type: 'number' },
          direction: { type: 'string', enum: ['up', 'down', 'left', 'right'] },
          amount: { type: 'number', description: 'Scroll distance in pixels, default 200' }
        },
        required: ['x', 'y', 'direction']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'remember',
      description: 'Save a short note to persistent memory so it can be recalled in future conversations. Use for facts, preferences, or context worth not repeating.',
      parameters: {
        type: 'object',
        properties: {
          content: { type: 'string', description: 'The note to remember' }
        },
        required: ['content']
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'recall',
      description: 'Search saved memory notes by keyword. Call this at the start of a task if past context might be relevant.',
      parameters: {
        type: 'object',
        properties: {
          query: { type: 'string', description: 'Keyword or phrase to search for. Leave empty to list everything.' }
        }
      }
    }
  },
  {
    type: 'function',
    function: {
      name: 'list_memories',
      description: 'List all saved memory notes.',
      parameters: { type: 'object', properties: {} }
    }
  },
  {
    type: 'function',
    function: {
      name: 'forget',
      description: 'Delete a saved memory note by id (from list_memories/recall output).',
      parameters: {
        type: 'object',
        properties: {
          id: { type: 'string', description: 'The memory id to delete' }
        },
        required: ['id']
      }
    }
  }
]

// ─── Rich file reading ─────────────────────────────────────────────────────────
const IMAGE_EXTENSIONS = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'tiff', 'ico'])
// Extensions read_file routes through readAttachmentFile instead of a raw UTF-8
// read — images become vision messages, documents get their text extracted.
const RICH_READ_EXTENSIONS = new Set([
  ...IMAGE_EXTENSIONS,
  'pdf', 'docx', 'doc', 'xlsx', 'xls', 'xlsm', 'ods', 'jar', 'zip',
])
const MAX_INLINE_IMAGE_BYTES = 5 * 1024 * 1024

// ─── Path Safety ───────────────────────────────────────────────────────────────
// Resolves a requested path against the workspace root using path.resolve (which
// normalizes ".." segments), and reports whether the result still falls inside the
// workspace. Callers decide what to do when it doesn't (gate behind approval, etc.)
// rather than this silently blocking or silently allowing traversal.
function resolveWorkspacePath(workspacePath, requestedPath) {
  const base = path.resolve(workspacePath || process.cwd())
  const absolutePath = path.resolve(base, requestedPath || '.')
  const insideWorkspace = absolutePath === base || absolutePath.startsWith(base + path.sep)
  return { absolutePath, insideWorkspace }
}

// ─── Tool Risk / Approval Gate ─────────────────────────────────────────────────
// Tools that are always confirmed before running, regardless of their arguments.
// Note: computer_click/type/keypress/scroll/move are deliberately NOT in this set —
// per-call confirmation would be unusably chatty for screen control. They're instead
// gated by the session-level "give Claude screen control" toggle (see
// controlEnabledByConversation below), which excludes them from the tool list
// entirely until the user opts in, plus a live re-check before each execution.
const ALWAYS_CONFIRM_TOOLS = new Set([
  'run_bash',
  'git_commit', 'git_push', 'git_pull', 'git_checkout',
  'ssh_exec',
])

function classifyToolRisk(name, args, workspacePath) {
  if (ALWAYS_CONFIRM_TOOLS.has(name)) return 'confirm'
  if (name.startsWith('mcp__')) return 'confirm'
  if (name === 'read_file' || name === 'write_file' || name === 'list_files') {
    if (args?.path) {
      const { insideWorkspace } = resolveWorkspacePath(workspacePath, args.path)
      if (!insideWorkspace) return 'confirm'
    }
  }
  return 'safe'
}

// Pending approvals keyed by tool_call id — resolved by the renderer's response,
// by a stop request for the conversation, or by the safety timeout (auto-deny).
const pendingApprovals = new Map()
const APPROVAL_TIMEOUT_MS = 5 * 60 * 1000

function resolveApproval(toolCallId, approved) {
  const pending = pendingApprovals.get(toolCallId)
  if (!pending) return
  clearTimeout(pending.timer)
  pendingApprovals.delete(toolCallId)
  pending.resolve(approved)
}

function requestApproval(event, conversationId, toolCallId, toolName, args) {
  event.sender.send('chat:tool-confirm-request', { conversationId, toolCallId, toolName, args })
  return new Promise((resolve) => {
    // Auto-deny if the user never answers (e.g. the renderer reloaded with the
    // modal open) so the agentic loop can't hang forever on a lost approval.
    const timer = setTimeout(() => resolveApproval(toolCallId, false), APPROVAL_TIMEOUT_MS)
    pendingApprovals.set(toolCallId, { resolve, timer, conversationId })
  })
}

ipcMain.handle('tool:respond-approval', (_, { toolCallId, approved }) => {
  resolveApproval(toolCallId, approved)
  return true
})

// ─── Computer Control Session Gate ─────────────────────────────────────────────
// Live per-conversation toggle for "give Claude screen control" — checked before the
// tool list is built each agentic-loop iteration AND immediately before each
// computer_* call executes, so turning it off mid-task actually halts new actions.
const controlEnabledByConversation = new Map()

ipcMain.on('computer:set-control-enabled', (_, { conversationId, enabled }) => {
  controlEnabledByConversation.set(conversationId, !!enabled)
})

const COMPUTER_CONTROL_TOOL_NAMES = new Set([
  'computer_click', 'computer_move', 'computer_type', 'computer_keypress', 'computer_scroll',
])

function filterToolsForControlMode(tools, conversationId) {
  if (controlEnabledByConversation.get(conversationId)) return tools
  return tools.filter(t => !COMPUTER_CONTROL_TOOL_NAMES.has(t.function.name))
}

// Tool-role messages must have string content, so an image can't just be
// stringified into the tool result — it's dropped in as a separate image_url message
// right after, which is what actually lets a vision-capable model see it.
// Applies to computer_screenshot and to read_file on an image file.
function buildToolResultEntries(tc, result) {
  if (result?.dataUrl && (result.type === 'image' || tc.function.name === 'computer_screenshot')) {
    const label = tc.function.name === 'computer_screenshot'
      ? 'Screenshot from computer_screenshot'
      : `Image: ${result.fileName || 'file'}`
    return {
      toolMessage: {
        role: 'tool',
        tool_call_id: tc.id,
        content: JSON.stringify({ success: true, note: 'Image loaded — see the image in the following message.' }),
      },
      imageMessage: {
        role: 'user',
        content: [
          { type: 'image_url', image_url: { url: result.dataUrl } },
          { type: 'text', text: `[${label}]` },
        ],
      },
    }
  }
  return {
    toolMessage: { role: 'tool', tool_call_id: tc.id, content: JSON.stringify(result) },
    imageMessage: null,
  }
}

// ─── Tool Execution ────────────────────────────────────────────────────────────
async function executeTool(name, args, workspacePath) {
  try {
    switch (name) {
      case 'read_file': {
        const { absolutePath } = resolveWorkspacePath(workspacePath, args.path)
        if (!fs.existsSync(absolutePath)) return { error: `File not found: ${args.path}` }
        const ext = path.extname(absolutePath).toLowerCase().replace('.', '')
        // Binary formats go through the attachment reader — the model gets a
        // real image (vision) or extracted document text instead of the
        // mojibake produced by reading binary bytes as UTF-8.
        if (RICH_READ_EXTENSIONS.has(ext)) {
          if (IMAGE_EXTENSIONS.has(ext) && fs.statSync(absolutePath).size > MAX_INLINE_IMAGE_BYTES) {
            return { error: `Image is larger than ${Math.round(MAX_INLINE_IMAGE_BYTES / 1024 / 1024)}MB — too large to inline. Resize it first (e.g. run_bash: sips -Z 1600 <file> --out <smaller.png>) and read the smaller copy.` }
          }
          const att = await readAttachmentFile(absolutePath)
          if (att.type === 'image') {
            return { type: 'image', fileName: att.fileName, sizeKB: att.sizeKB, dataUrl: att.dataUrl }
          }
          return { content: att.content, lines: att.lines, extractedFrom: att.fileName }
        }
        const content = fs.readFileSync(absolutePath, 'utf-8')
        return { content, lines: content.split('\n').length }
      }

      case 'write_file': {
        const { absolutePath } = resolveWorkspacePath(workspacePath, args.path)
        fs.mkdirSync(path.dirname(absolutePath), { recursive: true })
        fs.writeFileSync(absolutePath, args.content, 'utf-8')
        return { success: true, path: args.path, bytes: Buffer.byteLength(args.content) }
      }

      case 'list_files': {
        const { absolutePath: dirPath } = resolveWorkspacePath(workspacePath, args.path)
        if (!fs.existsSync(dirPath)) return { error: `Directory not found: ${args.path}` }
        const items = fs.readdirSync(dirPath, { withFileTypes: true })
        return {
          path: args.path,
          items: items.map(item => ({
            name: item.name,
            type: item.isDirectory() ? 'directory' : 'file',
            size: item.isFile() ? fs.statSync(path.join(dirPath, item.name)).size : null
          }))
        }
      }

      case 'create_presentation': {
        try {
          const PptxGenJS = require('pptxgenjs')
          const pptx = new PptxGenJS()
          pptx.layout = 'LAYOUT_WIDE'
          pptx.title = args.title || args.filename

          // ─── SanDisk Corporate Presentation Template v3.1 — fixed brand constants ───
          const BRAND = {
            red: 'E10600',
            terracotta: '772C22',
            black: '000000',
            gray1: '373737',
            gray2: 'BCBCBC',
            gray3: 'DFDFDF',
            offWhite: 'FFFCF9',
            // Non-web-safe brand fonts — installed locally per the SanDisk template's
            // own instructions. pptxgenjs just references the family name; PowerPoint
            // renders correctly once the fonts are installed on the viewing machine.
            fontHeading: 'Pilat',
            fontBody: 'Pilat',
            fontMono: 'PP Fraktion Mono',
          }
          const BG_VARIANT = { dark: BRAND.black, red: BRAND.red, light: BRAND.offWhite, gray: 'BEBEBE' }
          const isDarkVariant = (v) => v === 'dark' || v === 'red'

          // SanDisk wordmark — styled text placeholder. Swap for addImage() with a
          // real logo asset (e.g. resources/sandisk-logo-white.png) when available.
          function addWordmark(s, color) {
            s.addText('SANDISK', {
              x: 0.4, y: 6.55, w: 3, h: 0.5,
              fontSize: 20, bold: true, color, fontFace: BRAND.fontHeading, charSpacing: 1,
            })
            s.addText('™', { x: 1.62, y: 6.55, w: 0.4, h: 0.3, fontSize: 10, color, fontFace: BRAND.fontHeading })
          }

          // Chrome shared by every non-title slide: [CONFIDENTIAL] + tagline + red
          // square top corners, page number + copyright bottom corners.
          function addStandardChrome(s, pageNum, isDark) {
            const textColor = isDark ? 'FFFFFF' : BRAND.black
            s.addText('[CONFIDENTIAL]', {
              x: 0.35, y: 0.12, w: 2.5, h: 0.28,
              fontSize: 9, color: textColor, fontFace: BRAND.fontMono,
            })
            s.addText('"SPACE TO HOLD MORE"', {
              x: 8.8, y: 0.12, w: 3.6, h: 0.28,
              fontSize: 9, color: textColor, fontFace: BRAND.fontMono, align: 'right',
            })
            s.addShape(pptx.ShapeType.rect, {
              x: 12.68, y: 0.14, w: 0.14, h: 0.14,
              fill: { color: BRAND.red }, line: { color: BRAND.red },
            })
            if (pageNum) {
              s.addText(`00.0${pageNum}`, {
                x: 0.35, y: 7.12, w: 1.6, h: 0.25,
                fontSize: 8, color: textColor, fontFace: BRAND.fontMono,
              })
            }
            s.addText('©2026 SANDISK CORPORATION OR ITS AFFILIATES ALL RIGHTS RESERVED', {
              x: 6.5, y: 7.12, w: 6.3, h: 0.25,
              fontSize: 7, color: textColor, fontFace: BRAND.fontMono, align: 'right',
            })
          }

          // Title/cover slide footer: [CONFIDENTIAL] | date | presenter — no tagline,
          // no page number, matching the template's dedicated title-slide layouts.
          function addTitleFooter(s, isDark, date, presenter) {
            const textColor = isDark ? 'FFFFFF' : BRAND.black
            s.addText('[CONFIDENTIAL]', { x: 0.35, y: 7.12, w: 2.2, h: 0.25, fontSize: 8, color: textColor, fontFace: BRAND.fontMono })
            s.addText(date || 'CLICK TO ADD DATE', { x: 4.8, y: 7.12, w: 3.7, h: 0.25, fontSize: 8, color: textColor, fontFace: BRAND.fontMono, align: 'center' })
            s.addText(presenter || 'CLICK TO ADD PRESENTER NAME, TITLE', { x: 8.5, y: 7.12, w: 4.5, h: 0.25, fontSize: 8, color: textColor, fontFace: BRAND.fontMono, align: 'right' })
          }

          const slidesInput = [...(args.slides || [])]
          // Every deck ends on the closing slide unless the caller already supplied one
          if (slidesInput.length && slidesInput[slidesInput.length - 1].type !== 'closing') {
            slidesInput.push({ type: 'closing' })
          }

          slidesInput.forEach((slide, idx) => {
            const s = pptx.addSlide()
            const pageNum = idx + 1

            switch (slide.type) {
              case 'title': {
                const variant = slide.variant || 'dark'
                const isDark = isDarkVariant(variant)
                const textColor = isDark ? 'FFFFFF' : BRAND.black
                s.background = { color: BG_VARIANT[variant] || BG_VARIANT.dark }
                s.addText(slide.title || '', {
                  x: 0.5, y: 1.6, w: 11.5, h: 1.8,
                  fontSize: 44, bold: true, color: textColor, fontFace: BRAND.fontHeading, align: 'left',
                })
                if (slide.subtitle) {
                  s.addText(slide.subtitle, {
                    x: 0.5, y: 3.3, w: 10, h: 0.6,
                    fontSize: 18, color: textColor, fontFace: BRAND.fontBody, align: 'left',
                  })
                }
                addWordmark(s, textColor)
                addTitleFooter(s, isDark, slide.date, slide.presenter)
                break
              }

              case 'section': {
                const variant = slide.variant || 'dark'
                const isDark = isDarkVariant(variant)
                const textColor = isDark ? 'FFFFFF' : BRAND.black
                s.background = { color: BG_VARIANT[variant] || BG_VARIANT.dark }
                s.addShape(pptx.ShapeType.rect, { x: 0.3, y: 2.6, w: 0.09, h: 1.6, fill: { color: BRAND.red } })
                s.addText(slide.title || '', {
                  x: 0.7, y: 2.6, w: 10.5, h: 1.2,
                  fontSize: 34, bold: true, color: textColor, fontFace: BRAND.fontHeading, align: 'left',
                })
                if (slide.subtitle) {
                  s.addText(slide.subtitle, {
                    x: 0.7, y: 3.9, w: 9, h: 0.6,
                    fontSize: 16, color: textColor, fontFace: BRAND.fontBody, align: 'left',
                  })
                }
                addWordmark(s, textColor)
                addStandardChrome(s, pageNum, isDark)
                break
              }

              case 'closing': {
                s.background = { color: BRAND.red }
                addWordmark(s, 'FFFFFF')
                addStandardChrome(s, null, true)
                break
              }

              case 'bullets':
              case 'content':
              case 'two-column':
              case 'quote':
              default: {
                const type = slide.type || 'content'
                s.background = { color: BRAND.offWhite }
                const textColor = BRAND.black

                if (type !== 'quote') {
                  s.addText(slide.title || '', {
                    x: 0.5, y: 0.55, w: 11.5, h: 0.7,
                    fontSize: 26, bold: true, color: textColor, fontFace: BRAND.fontHeading,
                  })
                  s.addShape(pptx.ShapeType.rect, { x: 0.5, y: 1.3, w: 11.5, h: 0.02, fill: { color: BRAND.gray3 } })
                }

                if (type === 'bullets') {
                  const bullets = (slide.bullets || []).map(b => ({
                    text: b,
                    options: {
                      bullet: { code: '25CF', color: BRAND.red },
                      fontSize: 16, color: textColor, fontFace: BRAND.fontBody, paraSpaceAfter: 8,
                    },
                  }))
                  if (bullets.length) {
                    s.addText(bullets, { x: 0.5, y: 1.55, w: 11.5, h: 4.9, valign: 'top' })
                  }
                } else if (type === 'content') {
                  s.addText(slide.content || '', {
                    x: 0.5, y: 1.55, w: 11.5, h: 4.9,
                    fontSize: 16, color: textColor, fontFace: BRAND.fontBody, valign: 'top', wrap: true,
                  })
                } else if (type === 'two-column') {
                  s.addText(slide.left || '', {
                    x: 0.5, y: 1.55, w: 5.6, h: 4.9,
                    fontSize: 14, color: textColor, fontFace: BRAND.fontBody, valign: 'top', wrap: true,
                  })
                  s.addText(slide.right || '', {
                    x: 6.4, y: 1.55, w: 5.6, h: 4.9,
                    fontSize: 14, color: textColor, fontFace: BRAND.fontBody, valign: 'top', wrap: true,
                  })
                } else if (type === 'quote') {
                  s.addText('“', { x: 0.5, y: 0.8, w: 2, h: 1.4, fontSize: 84, bold: true, color: BRAND.red, fontFace: BRAND.fontHeading })
                  s.addText(slide.quote || '', {
                    x: 0.8, y: 1.9, w: 10.8, h: 3,
                    fontSize: 26, italic: true, color: textColor, fontFace: BRAND.fontBody, align: 'left', wrap: true,
                  })
                  if (slide.attribution) {
                    s.addText(`— ${slide.attribution}`, {
                      x: 0.8, y: 5.0, w: 10.8, h: 0.5,
                      fontSize: 14, color: BRAND.gray1, fontFace: BRAND.fontMono,
                    })
                  }
                }

                addStandardChrome(s, pageNum, false)
                break
              }
            }

            if (slide.notes) s.addNotes(slide.notes)
          })

          const filename = (args.filename || 'presentation').replace(/\.pptx$/i, '') + '.pptx'
          const savePath = workspacePath
            ? path.join(workspacePath, filename)
            : path.join(require('os').homedir(), 'Downloads', filename)

          await pptx.writeFile({ fileName: savePath })

          return {
            success: true,
            filename,
            path: savePath,
            slideCount: slidesInput.length,
            slides: slidesInput,   // renderer uses this for preview
            title: args.title || args.filename
          }
        } catch (e) {
          return { error: `Failed to create presentation: ${e.message}` }
        }
      }

      case 'create_spreadsheet': {
        try {
          const ExcelJS = require('exceljs')
          const workbook = new ExcelJS.Workbook()

          for (const sheet of (args.sheets || [])) {
            const ws = workbook.addWorksheet(sheet.name || 'Sheet1')
            const columns = sheet.columns || []
            ws.columns = columns.map(c => ({ header: c.header, width: c.width || 18 }))

            const headerFill = (sheet.headerFill || '#E10600').replace('#', '')
            const headerTextColor = (sheet.headerTextColor || '#FFFFFF').replace('#', '')
            ws.getRow(1).eachCell(cell => {
              cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF' + headerFill } }
              cell.font = { bold: true, color: { argb: 'FF' + headerTextColor } }
              cell.alignment = { vertical: 'middle' }
            })

            const rows = sheet.rows || []
            const rowColors = sheet.rowColors || []
            rows.forEach((rowValues, i) => {
              const row = ws.addRow(rowValues)
              const color = rowColors[i]
              if (color) {
                const hex = color.replace('#', '')
                row.eachCell(cell => {
                  cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FF' + hex } }
                })
              }
            })

            ws.views = [{ state: 'frozen', ySplit: 1 }]
          }

          const filename = (args.filename || 'spreadsheet').replace(/\.xlsx$/i, '') + '.xlsx'
          const savePath = workspacePath
            ? path.join(workspacePath, filename)
            : path.join(require('os').homedir(), 'Downloads', filename)

          await workbook.xlsx.writeFile(savePath)

          return { success: true, filename, path: savePath, sheetCount: (args.sheets || []).length }
        } catch (e) {
          return { error: `Failed to create spreadsheet: ${e.message}` }
        }
      }

      case 'run_bash': {
        const cwd = workspacePath || process.cwd()
        const timeout = args.timeout_ms || 30000
        try {
          const { stdout, stderr } = await execAsync(args.command, { cwd, timeout, maxBuffer: 10 * 1024 * 1024 })
          return { stdout: stdout || '', stderr: stderr || '', exit_code: 0 }
        } catch (e) {
          // A non-zero exit throws — still surface stdout/stderr and the real
          // exit code so the model can react to the failure instead of a bare error.
          if (e.killed) return { error: `Command timed out after ${timeout}ms`, stdout: e.stdout || '', stderr: e.stderr || '' }
          return { stdout: e.stdout || '', stderr: e.stderr || '', exit_code: typeof e.code === 'number' ? e.code : 1 }
        }
      }

      case 'git_status': {
        const git = require('simple-git')(workspacePath || process.cwd())
        return await git.status()
      }

      case 'git_diff': {
        const git = require('simple-git')(workspacePath || process.cwd())
        const diffArgs = []
        if (args.staged) diffArgs.push('--cached')
        if (args.filePath) diffArgs.push('--', args.filePath)
        const diff = await git.diff(diffArgs)
        return { diff: diff || '(no changes)' }
      }

      case 'git_log': {
        const git = require('simple-git')(workspacePath || process.cwd())
        const log = await git.log({ maxCount: args.maxCount || 10 })
        return { commits: log.all }
      }

      case 'git_branch_list': {
        const git = require('simple-git')(workspacePath || process.cwd())
        const summary = await git.branchLocal()
        return { current: summary.current, branches: Object.keys(summary.branches) }
      }

      case 'git_add': {
        const git = require('simple-git')(workspacePath || process.cwd())
        await git.add(args.files && args.files.length ? args.files : ['.'])
        return { success: true }
      }

      case 'git_checkout': {
        const git = require('simple-git')(workspacePath || process.cwd())
        if (args.createNew) {
          await git.checkoutLocalBranch(args.branch)
        } else {
          await git.checkout(args.branch)
        }
        return { success: true, branch: args.branch }
      }

      case 'git_commit': {
        const git = require('simple-git')(workspacePath || process.cwd())
        const result = await git.commit(args.message)
        return { success: true, commit: result.commit, summary: result.summary }
      }

      case 'git_push': {
        const git = require('simple-git')(workspacePath || process.cwd())
        await git.push(args.remote || 'origin', args.branch)
        return { success: true }
      }

      case 'git_pull': {
        const git = require('simple-git')(workspacePath || process.cwd())
        const result = await git.pull(args.remote || 'origin', args.branch)
        return { success: true, summary: result.summary }
      }

      case 'ssh_exec': {
        const host = (settings.sshHosts || []).find(h => h.id === args.hostId)
        if (!host) return { error: `SSH host not registered: ${args.hostId}. Add it in Settings → SSH Hosts first.` }
        const { NodeSSH } = require('node-ssh')
        const ssh = new NodeSSH()
        try {
          await ssh.connect({
            host: host.host,
            port: host.port || 22,
            username: host.username,
            privateKeyPath: host.privateKeyPath,
          })
          const result = await ssh.execCommand(args.command)
          return { stdout: result.stdout, stderr: result.stderr, code: result.code }
        } catch (e) {
          return { error: `SSH connection/command failed: ${e.message}` }
        } finally {
          ssh.dispose()
        }
      }

      case 'computer_screenshot': {
        const tmpPath = path.join(os.tmpdir(), `vortex-screenshot-${Date.now()}.png`)
        try {
          await execFileAsync('screencapture', ['-x', tmpPath])
          const buf = fs.readFileSync(tmpPath)
          return { success: true, type: 'image', dataUrl: `data:image/png;base64,${buf.toString('base64')}` }
        } catch (e) {
          return { error: `Screenshot failed: ${e.message}. Grant Screen Recording permission to this app in System Settings → Privacy & Security → Screen Recording, then try again.` }
        } finally {
          try { fs.unlinkSync(tmpPath) } catch (_) {}
        }
      }

      case 'computer_click': {
        const flag = args.button === 'right' ? 'rc' : args.button === 'double' ? 'dc' : 'c'
        await execFileAsync('cliclick', [`${flag}:${args.x},${args.y}`])
        return { success: true }
      }

      case 'computer_move': {
        await execFileAsync('cliclick', [`m:${args.x},${args.y}`])
        return { success: true }
      }

      case 'computer_type': {
        const escaped = String(args.text).replace(/\\/g, '\\\\').replace(/"/g, '\\"')
        await execFileAsync('osascript', ['-e', `tell application "System Events" to keystroke "${escaped}"`])
        return { success: true }
      }

      case 'computer_keypress': {
        const KEY_CODES = {
          return: 36, enter: 36, tab: 48, escape: 53, esc: 53, space: 49,
          delete: 51, backspace: 51, left: 123, right: 124, down: 125, up: 126,
        }
        const MODIFIER_MAP = { cmd: 'command down', shift: 'shift down', option: 'option down', control: 'control down' }
        const key = String(args.key || '').toLowerCase()
        const modifiers = (args.modifiers || []).map(m => MODIFIER_MAP[m]).filter(Boolean)
        const usingClause = modifiers.length ? ` using {${modifiers.join(', ')}}` : ''
        const script = KEY_CODES[key] !== undefined
          ? `tell application "System Events" to key code ${KEY_CODES[key]}${usingClause}`
          : `tell application "System Events" to keystroke "${key.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"${usingClause}`
        await execFileAsync('osascript', ['-e', script])
        return { success: true }
      }

      case 'computer_scroll': {
        // Note: cliclick's scroll ("w:") syntax varies slightly by version —
        // verify with `cliclick -h` if this doesn't behave as expected.
        const amount = args.amount || 200
        const dx = args.direction === 'left' ? -amount : args.direction === 'right' ? amount : 0
        const dy = args.direction === 'up' ? -amount : args.direction === 'down' ? amount : 0
        await execFileAsync('cliclick', [`m:${args.x},${args.y}`])
        await execFileAsync('cliclick', [`w:${dx},${dy}`])
        return { success: true }
      }

      case 'remember': {
        const entry = addMemory(args.content)
        return { success: true, memory: entry }
      }

      case 'recall': {
        const memories = loadMemories()
        const query = (args?.query || '').trim().toLowerCase()
        const matches = query
          ? memories.filter(m => m.content.toLowerCase().includes(query))
          : memories
        return { count: matches.length, memories: matches }
      }

      case 'list_memories': {
        return { count: loadMemories().length, memories: loadMemories() }
      }

      case 'forget': {
        const before = loadMemories()
        const after = before.filter(m => m.id !== args.id)
        saveMemories(after)
        return { success: true, deleted: before.length !== after.length }
      }

      default:
        return { error: `Unknown tool: ${name}` }
    }
  } catch (error) {
    if (error.killed) return { error: 'Command timed out', stderr: error.stderr || '' }
    return { error: error.message, stderr: error.stderr || '' }
  }
}

// ─── MCP Management ────────────────────────────────────────────────────────────
const mcpConnections = new Map()

async function callMcpTool(connection, toolName, args) {
  return new Promise((resolve) => {
    const id = `mcp_${Date.now()}_${Math.random().toString(36).slice(2)}`
    const timeout = setTimeout(() => {
      connection.pendingRequests.delete(id)
      resolve({ error: 'MCP tool call timed out after 30s' })
    }, 30000)

    connection.pendingRequests.set(id, (result) => {
      clearTimeout(timeout)
      resolve(result)
    })

    try {
      const request = JSON.stringify({
        jsonrpc: '2.0',
        id,
        method: 'tools/call',
        params: { name: toolName, arguments: args }
      }) + '\n'
      connection.process.stdin.write(request)
    } catch (e) {
      clearTimeout(timeout)
      connection.pendingRequests.delete(id)
      resolve({ error: `Failed to call MCP tool: ${e.message}` })
    }
  })
}

ipcMain.handle('mcp:connect', async (_, serverConfig) => {
  const { id, command, args: cmdArgs, env: envVars } = serverConfig
  if (mcpConnections.has(id)) {
    try { mcpConnections.get(id).process.kill() } catch (e) {}
    mcpConnections.delete(id)
  }

  try {
    const proc = spawn(command, cmdArgs || [], {
      env: { ...process.env, ...(envVars || {}) },
      stdio: ['pipe', 'pipe', 'pipe']
    })

    const connection = { process: proc, tools: [], pendingRequests: new Map(), buffer: '' }

    proc.stdout.on('data', (data) => {
      connection.buffer += data.toString()
      const lines = connection.buffer.split('\n')
      connection.buffer = lines.pop()
      for (const line of lines) {
        if (!line.trim()) continue
        try {
          const msg = JSON.parse(line)
          if (msg.id && connection.pendingRequests.has(msg.id)) {
            const cb = connection.pendingRequests.get(msg.id)
            connection.pendingRequests.delete(msg.id)
            cb(msg.result || msg.error)
          }
        } catch (e) {}
      }
    })

    proc.stderr.on('data', (d) => console.warn(`MCP[${id}]:`, d.toString().trim()))
    proc.on('exit', () => mcpConnections.delete(id))

    // Initialize
    proc.stdin.write(JSON.stringify({
      jsonrpc: '2.0', id: 'init_1',
      method: 'initialize',
      params: {
        protocolVersion: '2024-11-05',
        capabilities: { tools: {} },
        clientInfo: { name: 'vortex-cowork', version: '1.0.0' }
      }
    }) + '\n')

    await new Promise(r => setTimeout(r, 800))

    // Per the MCP spec the client must send notifications/initialized after the
    // initialize handshake — some servers silently ignore tools/list without it.
    proc.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n')

    // List tools
    const tools = await new Promise((resolve) => {
      const tid = 'list_tools_1'
      connection.pendingRequests.set(tid, (result) => resolve(result?.tools || []))
      proc.stdin.write(JSON.stringify({
        jsonrpc: '2.0', id: tid, method: 'tools/list', params: {}
      }) + '\n')
      setTimeout(() => {
        connection.pendingRequests.delete(tid)
        resolve([])
      }, 5000)
    })

    connection.tools = tools
    mcpConnections.set(id, connection)
    return { success: true, tools }
  } catch (error) {
    return { success: false, error: error.message }
  }
})

ipcMain.handle('mcp:disconnect', (_, serverId) => {
  const conn = mcpConnections.get(serverId)
  if (conn) { try { conn.process.kill() } catch (e) {} }
  mcpConnections.delete(serverId)
  return true
})

// Universal DB quick-connect: returns the standard template config
ipcMain.handle('mcp:universalDbTemplate', () => {
  return {
    id: 'universal-db',
    name: 'Universal DB',
    command: 'npx',
    args: ['-y', '@anthropic/mcp-server-databases'],
    env: {
      // Fill in your database connection string
      // Examples:
      //   postgresql://user:pass@localhost:5432/mydb
      //   oracle://user:pass@host:1521/service
      //   sqlite:///path/to/file.db
      DB_CONNECTION_STRING: ''
    }
  }
})

ipcMain.handle('mcp:list', () => {
  const result = {}
  for (const [id, conn] of mcpConnections.entries()) {
    result[id] = { tools: conn.tools, connected: true }
  }
  return result
})

// ─── Settings IPC ──────────────────────────────────────────────────────────────
ipcMain.handle('settings:get', () => settings)

ipcMain.handle('settings:set', (_, newSettings) => {
  settings = { ...settings, ...newSettings }
  saveSettings(settings)
  return settings
})

ipcMain.handle('settings:reset', () => {
  settings = { ...DEFAULT_SETTINGS }
  saveSettings(settings)
  return settings
})

// ─── Workspace IPC ─────────────────────────────────────────────────────────────
ipcMain.handle('workspace:select', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openDirectory'],
    title: 'Select Workspace Folder'
  })
  if (!result.canceled && result.filePaths.length > 0) {
    settings.workspacePath = result.filePaths[0]
    saveSettings(settings)
    return result.filePaths[0]
  }
  return null
})

ipcMain.handle('workspace:open-in-finder', () => {
  if (settings.workspacePath) shell.openPath(settings.workspacePath)
})

// ─── File System IPC ───────────────────────────────────────────────────────────
// These handlers back the sidebar file explorer, which only ever needs the
// selected workspace. Unlike the model's tools (which route escapes through the
// approval gate), there is no legitimate reason for these to leave the
// workspace, so ".."-style escapes are rejected outright.
function requireWorkspacePathInside(requestedPath) {
  const { absolutePath, insideWorkspace } = resolveWorkspacePath(settings.workspacePath, requestedPath)
  if (!insideWorkspace) throw new Error(`Path is outside the workspace: ${requestedPath}`)
  return absolutePath
}

ipcMain.handle('fs:read', (_, filePath) => {
  return fs.readFileSync(requireWorkspacePathInside(filePath), 'utf-8')
})

ipcMain.handle('fs:write', (_, filePath, content) => {
  const absolutePath = requireWorkspacePathInside(filePath)
  fs.mkdirSync(path.dirname(absolutePath), { recursive: true })
  fs.writeFileSync(absolutePath, content, 'utf-8')
  return true
})

ipcMain.handle('fs:list', (_, dirPath) => {
  const full = requireWorkspacePathInside(dirPath || '.')
  if (!fs.existsSync(full)) return []
  return fs.readdirSync(full, { withFileTypes: true }).map(item => ({
    name: item.name,
    path: path.join(dirPath || '.', item.name),
    type: item.isDirectory() ? 'directory' : 'file'
  }))
})

ipcMain.handle('fs:open', (_, filePath) => {
  shell.openPath(requireWorkspacePathInside(filePath))
})

ipcMain.handle('fs:delete', (_, filePath) => {
  fs.rmSync(requireWorkspacePathInside(filePath), { recursive: true, force: true })
  return true
})

// ─── Conversation Persistence IPC ─────────────────────────────────────────────
const conversationsPath = path.join(app.getPath('userData'), 'conversations.json')
const TWO_WEEKS_MS = 14 * 24 * 60 * 60 * 1000

function pruneOldConversations(convs) {
  const cutoff = Date.now() - TWO_WEEKS_MS
  return convs.filter(c => (c.createdAt || 0) >= cutoff)
}

function loadConversations() {
  try {
    if (fs.existsSync(conversationsPath)) {
      const raw = JSON.parse(fs.readFileSync(conversationsPath, 'utf-8'))
      return pruneOldConversations(Array.isArray(raw) ? raw : [])
    }
  } catch (e) {
    console.error('Failed to load conversations:', e.message)
  }
  return []
}

function saveConversations(convs) {
  try {
    const pruned = pruneOldConversations(convs)
    fs.writeFileSync(conversationsPath, JSON.stringify(pruned, null, 2))
  } catch (e) {
    console.error('Failed to save conversations:', e.message)
  }
}

ipcMain.handle('conversations:load', () => loadConversations())

ipcMain.handle('conversations:save', (_, convs) => {
  saveConversations(convs)
  return true
})

// ─── Memory / Notes IPC ────────────────────────────────────────────────────────
const memoryPath = path.join(app.getPath('userData'), 'memory.json')

function loadMemories() {
  try {
    if (fs.existsSync(memoryPath)) {
      const raw = JSON.parse(fs.readFileSync(memoryPath, 'utf-8'))
      return Array.isArray(raw) ? raw : []
    }
  } catch (e) {
    console.error('Failed to load memories:', e.message)
  }
  return []
}

function saveMemories(memories) {
  try {
    fs.writeFileSync(memoryPath, JSON.stringify(memories, null, 2))
  } catch (e) {
    console.error('Failed to save memories:', e.message)
  }
}

function addMemory(content) {
  const memories = loadMemories()
  const entry = { id: `mem_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`, content, createdAt: Date.now() }
  memories.push(entry)
  saveMemories(memories)
  return entry
}

ipcMain.handle('memory:list', () => loadMemories())
ipcMain.handle('memory:add', (_, content) => addMemory(content))
ipcMain.handle('memory:delete', (_, id) => {
  saveMemories(loadMemories().filter(m => m.id !== id))
  return true
})

// ─── Workspaces IPC ───────────────────────────────────────────────────────────
ipcMain.handle('workspaces:get', () => {
  return settings.workspaces || DEFAULT_WORKSPACES
})

ipcMain.handle('workspaces:set', (_, workspaces) => {
  settings.workspaces = workspaces
  saveSettings(settings)
  return settings
})

ipcMain.handle('workspaces:setActive', (_, workspaceId) => {
  settings.activeWorkspaceId = workspaceId
  saveSettings(settings)
  return settings
})

// ─── File Attachment IPC ───────────────────────────────────────────────────────
// Supported text extensions (read as-is)
const TEXT_EXTENSIONS = new Set([
  'txt','md','markdown','js','jsx','ts','tsx','mjs','cjs',
  'py','rb','go','rs','java','c','cpp','h','hpp','cs','swift',
  'sh','bash','zsh','fish','ps1','bat','cmd',
  'sql','plsql','pls','pks','pkb',
  'json','yaml','yml','toml','ini','cfg','conf','env',
  'html','htm','xml','svg','css','scss','sass','less',
  'csv','tsv','log','diff','patch',
  'r','m','scala','kt','dart','lua','pl','pm','php',
  'gradle','properties','dockerfile','vue','svelte','editorconfig',
  'gitignore','makefile','groovy','tf','proto',
])

async function readAttachmentFile(filePath) {
  const ext = path.extname(filePath).toLowerCase().replace('.', '')
  const fileName = path.basename(filePath)
  const stat = fs.statSync(filePath)
  const sizeKB = Math.round(stat.size / 1024)

  // Text-based files
  if (TEXT_EXTENSIONS.has(ext)) {
    const content = fs.readFileSync(filePath, 'utf-8')
    return {
      type: 'text',
      fileName,
      ext,
      sizeKB,
      content,
      lines: content.split('\n').length
    }
  }

  // Images → base64 data URL
  if (IMAGE_EXTENSIONS.has(ext)) {
    const buf = fs.readFileSync(filePath)
    const mime = ext === 'jpg' ? 'image/jpeg'
      : ext === 'svg' ? 'image/svg+xml'
      : `image/${ext}`
    return {
      type: 'image',
      fileName,
      ext,
      sizeKB,
      dataUrl: `data:${mime};base64,${buf.toString('base64')}`,
      mimeType: mime
    }
  }

  // PDF → extract text using pdftotext if available, else raw
  if (ext === 'pdf') {
    try {
      // execFile (not exec) — filenames with quotes/$() must not hit a shell
      const { stdout } = await execFileAsync('pdftotext', [filePath, '-'], { maxBuffer: 50 * 1024 * 1024 })
      return { type: 'text', fileName, ext, sizeKB, content: stdout, lines: stdout.split('\n').length }
    } catch (e) {
      // fallback: try to read as binary and note it
      return {
        type: 'text',
        fileName,
        ext,
        sizeKB,
        content: `[PDF file: ${fileName} — ${sizeKB}KB. pdftotext not available; install poppler-utils to extract text automatically.]`,
        lines: 1
      }
    }
  }

  // DOCX → extract text using mammoth if available
  if (ext === 'docx' || ext === 'doc') {
    try {
      const mammoth = require('mammoth')
      const result = await mammoth.extractRawText({ path: filePath })
      return { type: 'text', fileName, ext, sizeKB, content: result.value, lines: result.value.split('\n').length }
    } catch (e) {
      return {
        type: 'text',
        fileName,
        ext,
        sizeKB,
        content: `[Word document: ${fileName} — ${sizeKB}KB. Install mammoth (npm i mammoth) for automatic text extraction.]`,
        lines: 1
      }
    }
  }

  // XLSX / XLS → extract as CSV-like using xlsx if available
  if (['xlsx','xls','xlsm','ods'].includes(ext)) {
    try {
      const XLSX = require('xlsx')
      const wb = XLSX.readFile(filePath)
      const parts = wb.SheetNames.map(name => {
        const ws = wb.Sheets[name]
        return `=== Sheet: ${name} ===\n${XLSX.utils.sheet_to_csv(ws)}`
      })
      const content = parts.join('\n\n')
      return { type: 'text', fileName, ext, sizeKB, content, lines: content.split('\n').length }
    } catch (e) {
      return {
        type: 'text',
        fileName,
        ext,
        sizeKB,
        content: `[Spreadsheet: ${fileName} — ${sizeKB}KB. Install xlsx (npm i xlsx) for automatic extraction.]`,
        lines: 1
      }
    }
  }

  // JAR / ZIP → list entries + inline small text entries (manifest, config, etc.)
  if (ext === 'jar' || ext === 'zip') {
    try {
      const AdmZip = require('adm-zip')
      const zip = new AdmZip(filePath)
      const entries = zip.getEntries()
      const parts = [`=== ${entries.length} entries in ${fileName} ===`]
      for (const entry of entries) {
        parts.push(`${entry.isDirectory ? 'dir ' : 'file'}  ${entry.entryName}  (${entry.header.size}B)`)
      }
      // Inline a handful of small, useful text entries (manifest, package descriptors)
      const INLINE_NAMES = ['META-INF/MANIFEST.MF', 'package.json', 'pom.xml', 'build.gradle']
      for (const entry of entries) {
        if (!entry.isDirectory && INLINE_NAMES.includes(entry.entryName) && entry.header.size < 20_000) {
          parts.push(`\n--- ${entry.entryName} ---\n${entry.getData().toString('utf-8')}`)
        }
      }
      const content = parts.join('\n')
      return { type: 'text', fileName, ext, sizeKB, content, lines: content.split('\n').length }
    } catch (e) {
      return {
        type: 'text',
        fileName,
        ext,
        sizeKB,
        content: `[${ext.toUpperCase()} file: ${fileName} — ${sizeKB}KB. Failed to inspect: ${e.message}]`,
        lines: 1
      }
    }
  }

  // Unknown binary → inform but don't crash
  return {
    type: 'text',
    fileName,
    ext,
    sizeKB,
    content: `[Binary file: ${fileName} (${ext.toUpperCase()}, ${sizeKB}KB) — cannot display content directly.]`,
    lines: 1
  }
}

ipcMain.handle('attachment:pick', async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ['openFile', 'multiSelections'],
    title: 'Attach Files',
    filters: [
      { name: 'All Files', extensions: ['*'] },
      { name: 'Documents', extensions: ['pdf','docx','doc','txt','md'] },
      { name: 'Spreadsheets', extensions: ['xlsx','xls','xlsm','csv','tsv'] },
      { name: 'Code', extensions: ['js','jsx','ts','tsx','py','java','sql','json','yaml','yml','sh','jar','zip','log'] },
      { name: 'Images', extensions: ['png','jpg','jpeg','gif','webp'] },
    ]
  })
  if (result.canceled || !result.filePaths.length) return []
  const attachments = []
  for (const fp of result.filePaths) {
    try {
      const att = await readAttachmentFile(fp)
      attachments.push(att)
    } catch (e) {
      attachments.push({ type: 'text', fileName: path.basename(fp), ext: '', sizeKB: 0, content: `[Error reading file: ${e.message}]`, lines: 1 })
    }
  }
  return attachments
})

ipcMain.handle('attachment:read', async (_, filePath) => {
  try {
    return await readAttachmentFile(filePath)
  } catch (e) {
    return { type: 'text', fileName: path.basename(filePath), ext: '', sizeKB: 0, content: `[Error: ${e.message}]`, lines: 1 }
  }
})

// ─── Chat Streaming IPC ────────────────────────────────────────────────────────
const MAX_AGENT_ITERATIONS = 15

// One AbortController per in-flight conversation so the renderer's Stop button
// can cancel the API stream and halt the agentic loop between tool calls.
const activeChatControllers = new Map()

ipcMain.on('chat:stop', (_, { conversationId }) => {
  const controller = activeChatControllers.get(conversationId)
  if (controller) controller.abort()
  // Deny any approval modals still waiting on this conversation so the loop
  // isn't left hanging on a question the user no longer cares about.
  for (const [toolCallId, pending] of [...pendingApprovals.entries()]) {
    if (pending.conversationId === conversationId) resolveApproval(toolCallId, false)
  }
})

function isAbortError(error) {
  return error?.name === 'AbortError' || error?.name === 'APIUserAbortError'
    || /abort/i.test(error?.message || '')
}

// Executes one batch of tool calls (shared by the streaming and non-streaming
// paths) and returns the transcript messages to append.
async function processToolCalls(event, conversationId, toolCalls, signal) {
  const resultMessages = []
  for (const tc of toolCalls) {
    let result
    if (signal?.aborted) {
      // Every tool_call still needs a tool result message or the next request
      // is rejected — answer the remaining ones with a stop notice.
      result = { error: 'Generation stopped by user' }
      resultMessages.push({ role: 'tool', tool_call_id: tc.id, content: JSON.stringify(result) })
      continue
    }

    let args = {}
    let parseError = null
    try { args = JSON.parse(tc.function.arguments || '{}') } catch (e) { parseError = e.message }

    event.sender.send('chat:tool-executing', {
      conversationId, toolId: tc.id,
      toolName: tc.function.name, args
    })

    if (parseError) {
      // Tell the model its arguments were malformed instead of silently running
      // the tool with {} — lets it retry with valid JSON.
      result = { error: `Invalid JSON in tool arguments: ${parseError}` }
    }

    if (!result) {
      const risk = classifyToolRisk(tc.function.name, args, settings.workspacePath)
      if (risk === 'confirm' && settings.confirmRiskyTools !== false) {
        const approved = await requestApproval(event, conversationId, tc.id, tc.function.name, args)
        if (!approved) result = { error: 'User denied this action' }
      }
    }

    if (!result) {
      if (COMPUTER_CONTROL_TOOL_NAMES.has(tc.function.name) && !controlEnabledByConversation.get(conversationId)) {
        result = { error: 'Screen control was turned off — action skipped.' }
      } else if (tc.function.name.startsWith('mcp__')) {
        const parts = tc.function.name.split('__')
        const serverId = parts[1]
        const mcpToolName = parts.slice(2).join('__')
        const conn = mcpConnections.get(serverId)
        result = conn
          ? await callMcpTool(conn, mcpToolName, args)
          : { error: `MCP server "${serverId}" not connected` }
      } else {
        result = await executeTool(tc.function.name, args, settings.workspacePath)
      }
    }

    event.sender.send('chat:tool-result', {
      conversationId, toolId: tc.id,
      toolName: tc.function.name, result
    })

    const { toolMessage, imageMessage } = buildToolResultEntries(tc, result)
    resultMessages.push(toolMessage)
    if (imageMessage) resultMessages.push(imageMessage)
  }
  return resultMessages
}

ipcMain.on('chat:send', async (event, { messages, conversationId, computerControlEnabled }) => {
  controlEnabledByConversation.set(conversationId, !!computerControlEnabled)

  let OpenAI
  try {
    OpenAI = require('openai').default || require('openai').OpenAI
  } catch (e) {
    event.sender.send('chat:error', { conversationId, error: 'OpenAI module not found. Run npm install.' })
    return
  }

  if (!settings.apiKey) {
    event.sender.send('chat:error', { conversationId, error: 'No API key configured. Go to Settings to add your PORTKEY_KEY.' })
    return
  }

  const client = new OpenAI({
    apiKey: settings.apiKey,
    baseURL: settings.baseUrl,
  })

  // Assemble tool list. Recomputed per-iteration below via filterToolsForControlMode
  // so toggling screen control off mid-task actually removes computer_* tools from
  // what the model can call on its next turn.
  const baseTools = settings.enableTools ? [...BUILTIN_TOOLS] : []
  for (const [serverId, conn] of mcpConnections.entries()) {
    if (conn.tools) {
      for (const tool of conn.tools) {
        baseTools.push({
          type: 'function',
          function: {
            name: `mcp__${serverId}__${tool.name}`,
            description: tool.description || `MCP tool: ${tool.name}`,
            parameters: tool.inputSchema || { type: 'object', properties: {} }
          }
        })
      }
    }
  }

  // Resolve active workspace system prompt
  let activeSystemPrompt = settings.systemPrompt || ''
  if (settings.workspaces && settings.activeWorkspaceId) {
    const ws = settings.workspaces.find(w => w.id === settings.activeWorkspaceId)
    if (ws) activeSystemPrompt = ws.systemPrompt || ''
  }

  const systemMessages = activeSystemPrompt
    ? [{ role: 'system', content: activeSystemPrompt }]
    : []

  // Normalize messages: convert attachment metadata to proper content blocks
  const normalizedMessages = messages.map(msg => {
    if (msg.role !== 'user' || !msg.attachments?.length) return msg
    const contentBlocks = []
    for (const att of msg.attachments) {
      if (att.type === 'image') {
        // Vision-capable models can handle base64 images
        contentBlocks.push({
          type: 'image_url',
          image_url: { url: att.dataUrl }
        })
        contentBlocks.push({ type: 'text', text: `[Image: ${att.fileName}]` })
      } else {
        contentBlocks.push({
          type: 'text',
          text: `--- Attached file: ${att.fileName} (${att.sizeKB}KB, ${att.lines} lines) ---\n${att.content}\n--- End of ${att.fileName} ---`
        })
      }
    }
    if (msg.content) {
      contentBlocks.push({ type: 'text', text: msg.content })
    }
    return { role: msg.role, content: contentBlocks }
  })

  let currentMessages = [...systemMessages, ...normalizedMessages]

  // Replace any stale controller for this conversation (e.g. from a crashed turn)
  activeChatControllers.get(conversationId)?.abort()
  const controller = new AbortController()
  activeChatControllers.set(conversationId, controller)

  try {
    // Agentic loop: keep going until no more tool calls
    let hitIterationCap = true
    for (let iteration = 0; iteration < MAX_AGENT_ITERATIONS; iteration++) {
      if (controller.signal.aborted) { hitIterationCap = false; break }
      const tools = filterToolsForControlMode(baseTools, conversationId)
      const requestParams = {
        model: settings.model,
        messages: currentMessages,
        max_tokens: settings.maxTokens || 4096,
      }

      if (tools.length > 0) {
        requestParams.tools = tools
        requestParams.tool_choice = 'auto'
      }

      if (settings.streamingEnabled) {
        requestParams.stream = true
        const stream = await client.chat.completions.create(requestParams, { signal: controller.signal })

        let fullContent = ''
        let toolCalls = []
        let finishReason = null

        for await (const chunk of stream) {
          const choice = chunk.choices[0]
          if (!choice) continue

          const delta = choice.delta
          finishReason = choice.finish_reason || finishReason

          if (delta?.content) {
            fullContent += delta.content
            event.sender.send('chat:token', { conversationId, token: delta.content })
          }

          if (delta?.tool_calls) {
            for (const tcDelta of delta.tool_calls) {
              const idx = tcDelta.index ?? 0
              if (!toolCalls[idx]) {
                toolCalls[idx] = { id: '', type: 'function', function: { name: '', arguments: '' } }
              }
              if (tcDelta.id) toolCalls[idx].id += tcDelta.id
              if (tcDelta.function?.name) toolCalls[idx].function.name += tcDelta.function.name
              if (tcDelta.function?.arguments) toolCalls[idx].function.arguments += tcDelta.function.arguments
            }
          }
        }

        toolCalls = toolCalls.filter(Boolean)

        if (toolCalls.length > 0) {
          // Add assistant turn with tool_calls
          currentMessages.push({
            role: 'assistant',
            content: fullContent || null,
            tool_calls: toolCalls
          })

          event.sender.send('chat:tool-calls-start', { conversationId, toolCalls })
          const toolResults = await processToolCalls(event, conversationId, toolCalls, controller.signal)
          currentMessages.push(...toolResults)

          if (controller.signal.aborted) { hitIterationCap = false; break }
          // Continue loop for Claude's follow-up
          continue
        }

        // No tool calls → done
        hitIterationCap = false
        break

      } else {
        // Non-streaming fallback
        const response = await client.chat.completions.create(requestParams, { signal: controller.signal })
        const msg = response.choices[0]?.message
        if (!msg) { hitIterationCap = false; break }

        if (msg.tool_calls?.length > 0) {
          currentMessages.push({ role: 'assistant', content: msg.content || null, tool_calls: msg.tool_calls })
          event.sender.send('chat:tool-calls-start', { conversationId, toolCalls: msg.tool_calls })
          const toolResults = await processToolCalls(event, conversationId, msg.tool_calls, controller.signal)
          currentMessages.push(...toolResults)

          if (controller.signal.aborted) { hitIterationCap = false; break }
          continue
        }

        if (msg.content) {
          event.sender.send('chat:token', { conversationId, token: msg.content })
        }
        hitIterationCap = false
        break
      }
    }

    if (hitIterationCap) {
      event.sender.send('chat:token', {
        conversationId,
        token: `\n\n⚠️ Stopped after ${MAX_AGENT_ITERATIONS} tool iterations — send another message to continue.`,
      })
    }

    event.sender.send('chat:done', { conversationId })
  } catch (error) {
    // A user-initiated stop surfaces as an abort error — end the turn cleanly
    // (keeping the partial text) instead of showing an error bubble.
    if (controller.signal.aborted || isAbortError(error)) {
      event.sender.send('chat:done', { conversationId, stopped: true })
    } else {
      event.sender.send('chat:error', { conversationId, error: error.message })
    }
  } finally {
    if (activeChatControllers.get(conversationId) === controller) {
      activeChatControllers.delete(conversationId)
    }
  }
})
