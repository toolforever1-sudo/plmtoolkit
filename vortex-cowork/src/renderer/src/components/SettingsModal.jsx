import { useState, useEffect } from 'react'
import { X, Plus, Trash2, Save, AlertCircle, CheckCircle, ChevronDown, ChevronRight, Database, Globe } from 'lucide-react'
import { useApp } from '../contexts/AppContext'

// Static template — the official Playwright MCP server, no main-process lookup needed
const PLAYWRIGHT_MCP_TEMPLATE = {
  id: 'playwright-mcp',
  name: 'Playwright (Browser Automation)',
  command: 'npx',
  args: ['-y', '@playwright/mcp@latest'],
  env: {},
}

function Section({ title, children, defaultOpen = true }) {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <div className="border border-surface-500 rounded-xl overflow-hidden mb-4">
      <button
        onClick={() => setOpen(p => !p)}
        className="w-full flex items-center justify-between px-4 py-3 hover:bg-surface-600 transition-colors"
      >
        <span className="text-sm font-semibold text-gray-200">{title}</span>
        {open ? <ChevronDown size={14} className="text-gray-500" /> : <ChevronRight size={14} className="text-gray-500" />}
      </button>
      {open && (
        <div className="px-4 pb-4 pt-1 border-t border-surface-500">
          {children}
        </div>
      )}
    </div>
  )
}

function FieldLabel({ label, hint }) {
  return (
    <div className="mb-1">
      <label className="text-xs font-medium text-gray-300">{label}</label>
      {hint && <p className="text-xs text-gray-600">{hint}</p>}
    </div>
  )
}

function Input({ value, onChange, placeholder, type = 'text', monospace = false }) {
  return (
    <input
      type={type}
      value={value}
      onChange={e => onChange(e.target.value)}
      placeholder={placeholder}
      className={`w-full bg-surface-800 border border-surface-500 rounded-lg px-3 py-2 text-sm text-gray-200 placeholder-gray-600 focus:outline-none focus:border-accent/60 transition-colors ${monospace ? 'font-mono text-xs' : ''}`}
    />
  )
}

function Textarea({ value, onChange, placeholder, rows = 4 }) {
  return (
    <textarea
      value={value}
      onChange={e => onChange(e.target.value)}
      placeholder={placeholder}
      rows={rows}
      className="w-full bg-surface-800 border border-surface-500 rounded-lg px-3 py-2 text-sm text-gray-200 placeholder-gray-600 focus:outline-none focus:border-accent/60 transition-colors resize-none font-mono"
    />
  )
}

function Toggle({ checked, onChange, label }) {
  return (
    <label className="flex items-center gap-3 cursor-pointer">
      <div
        onClick={() => onChange(!checked)}
        className={`w-9 h-5 rounded-full transition-colors flex items-center px-0.5 ${checked ? 'bg-accent' : 'bg-surface-500'}`}
      >
        <div className={`w-4 h-4 rounded-full bg-white shadow transition-transform ${checked ? 'translate-x-4' : 'translate-x-0'}`} />
      </div>
      <span className="text-sm text-gray-300">{label}</span>
    </label>
  )
}

function McpServerRow({ server, onUpdate, onDelete, onConnect, onDisconnect, connected }) {
  const [expanded, setExpanded] = useState(false)

  return (
    <div className="border border-surface-500 rounded-lg mb-2 overflow-hidden">
      <div className="flex items-center gap-2 px-3 py-2">
        <div className={`w-2 h-2 rounded-full flex-shrink-0 ${connected ? 'bg-green-400' : 'bg-gray-600'}`} />
        <input
          value={server.name || server.id}
          onChange={e => onUpdate({ ...server, name: e.target.value })}
          placeholder="Server name"
          className="flex-1 bg-transparent text-sm text-gray-200 outline-none"
        />
        <button onClick={() => setExpanded(p => !p)} className="p-1 text-gray-500 hover:text-gray-300">
          {expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
        </button>
        <button
          onClick={connected ? () => onDisconnect(server.id) : () => onConnect(server)}
          className={`px-2 py-0.5 rounded text-xs ${connected ? 'bg-red-500/20 text-red-400 hover:bg-red-500/30' : 'bg-green-500/20 text-green-400 hover:bg-green-500/30'}`}
        >
          {connected ? 'Disconnect' : 'Connect'}
        </button>
        <button onClick={onDelete} className="p-1 text-gray-600 hover:text-red-400">
          <Trash2 size={12} />
        </button>
      </div>
      {expanded && (
        <div className="px-3 pb-3 pt-1 border-t border-surface-500 space-y-2">
          <div>
            <FieldLabel label="Command" hint="e.g. npx or uvx" />
            <Input value={server.command || ''} onChange={v => onUpdate({ ...server, command: v })} placeholder="npx" monospace />
          </div>
          <div>
            <FieldLabel label="Arguments (space-separated)" />
            <Input
              value={(server.args || []).join(' ')}
              onChange={v => onUpdate({ ...server, args: v.split(' ').filter(Boolean) })}
              placeholder="-y @modelcontextprotocol/server-filesystem /path/to/dir"
              monospace
            />
          </div>
          <div>
            <FieldLabel label="Environment variables (KEY=VALUE, one per line)" />
            <Textarea
              value={Object.entries(server.env || {}).map(([k, v]) => `${k}=${v}`).join('\n')}
              onChange={v => {
                const env = {}
                v.split('\n').forEach(line => {
                  const idx = line.indexOf('=')
                  if (idx > 0) env[line.slice(0, idx)] = line.slice(idx + 1)
                })
                onUpdate({ ...server, env })
              }}
              placeholder="API_KEY=your_key"
              rows={3}
            />
          </div>
        </div>
      )}
    </div>
  )
}

function SshHostRow({ host, onUpdate, onDelete }) {
  const [expanded, setExpanded] = useState(false)

  return (
    <div className="border border-surface-500 rounded-lg mb-2 overflow-hidden">
      <div className="flex items-center gap-2 px-3 py-2">
        <input
          value={host.name || host.id}
          onChange={e => onUpdate({ ...host, name: e.target.value })}
          placeholder="Host label"
          className="flex-1 bg-transparent text-sm text-gray-200 outline-none"
        />
        <button onClick={() => setExpanded(p => !p)} className="p-1 text-gray-500 hover:text-gray-300">
          {expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
        </button>
        <button onClick={onDelete} className="p-1 text-gray-600 hover:text-red-400">
          <Trash2 size={12} />
        </button>
      </div>
      {expanded && (
        <div className="px-3 pb-3 pt-1 border-t border-surface-500 space-y-2">
          <div>
            <FieldLabel label="Host" />
            <Input value={host.host || ''} onChange={v => onUpdate({ ...host, host: v })} placeholder="192.168.1.10 or my-server.internal" monospace />
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div>
              <FieldLabel label="Port" />
              <Input type="number" value={host.port || 22} onChange={v => onUpdate({ ...host, port: parseInt(v) || 22 })} />
            </div>
            <div>
              <FieldLabel label="Username" />
              <Input value={host.username || ''} onChange={v => onUpdate({ ...host, username: v })} monospace />
            </div>
          </div>
          <div>
            <FieldLabel
              label="Private key path"
              hint="e.g. ~/.ssh/id_ed25519. Passphrase-protected keys need an already-unlocked ssh-agent — passphrases aren't stored here."
            />
            <Input value={host.privateKeyPath || ''} onChange={v => onUpdate({ ...host, privateKeyPath: v })} placeholder="~/.ssh/id_ed25519" monospace />
          </div>
        </div>
      )}
    </div>
  )
}

export default function SettingsModal() {
  const { state, dispatch, updateSettings } = useApp()
  const [form, setForm] = useState(null)
  const [saving, setSaving] = useState(false)
  const [saved, setSaved] = useState(false)
  const [mcpStatus, setMcpStatus] = useState({})

  useEffect(() => {
    if (state.settings) {
      setForm({
        ...state.settings,
        workspaces: state.workspaces || state.settings.workspaces || [],
        activeWorkspaceId: state.activeWorkspaceId || state.settings.activeWorkspaceId || 'none',
      })
    }
    // Load current MCP connection status
    window.electron.listMcp().then(servers => {
      const status = {}
      Object.keys(servers).forEach(id => { status[id] = servers[id].connected })
      setMcpStatus(status)
    })
  }, [state.settings])

  if (!form) return null

  const handleSave = async () => {
    setSaving(true)
    try {
      await updateSettings(form)
      setSaved(true)
      setTimeout(() => setSaved(false), 2000)
    } finally {
      setSaving(false)
    }
  }

  const handleMcpConnect = async (server) => {
    const result = await window.electron.connectMcp(server)
    if (result.success) {
      setMcpStatus(p => ({ ...p, [server.id]: true }))
    } else {
      alert(`Failed to connect MCP server: ${result.error}`)
    }
  }

  const handleMcpDisconnect = async (id) => {
    await window.electron.disconnectMcp(id)
    setMcpStatus(p => ({ ...p, [id]: false }))
  }

  const addMcpServer = () => {
    const id = `mcp_${Date.now()}`
    setForm(f => ({
      ...f,
      mcpServers: [...(f.mcpServers || []), { id, name: 'New Server', command: '', args: [], env: {} }]
    }))
  }

  const addSshHost = () => {
    const id = `ssh_${Date.now()}`
    setForm(f => ({
      ...f,
      sshHosts: [...(f.sshHosts || []), { id, name: 'New Host', host: '', port: 22, username: '', privateKeyPath: '' }]
    }))
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm">
      <div className="bg-surface-800 border border-surface-500 rounded-2xl w-full max-w-xl max-h-[85vh] flex flex-col shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-surface-600">
          <h2 className="text-base font-semibold text-gray-100">Settings</h2>
          <button
            onClick={() => dispatch({ type: 'TOGGLE_SETTINGS' })}
            className="p-1.5 rounded-lg hover:bg-surface-600 text-gray-500 hover:text-gray-300 transition-colors"
          >
            <X size={16} />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-6 py-4">

          {/* API Config */}
          <Section title="API Configuration">
            <div className="space-y-3">
              <div>
                <FieldLabel label="API Key (PORTKEY_KEY)" hint="Your Vortex/Portkey API key" />
                <Input
                  type="password"
                  value={form.apiKey || ''}
                  onChange={v => setForm(f => ({ ...f, apiKey: v }))}
                  placeholder="pk-..."
                  monospace
                />
              </div>
              <div>
                <FieldLabel label="Base URL" />
                <Input
                  value={form.baseUrl || ''}
                  onChange={v => setForm(f => ({ ...f, baseUrl: v }))}
                  placeholder="https://ai.vortex.sandisk.com/v1/"
                  monospace
                />
              </div>
              <div>
                <FieldLabel label="Model" />
                <Input
                  value={form.model || ''}
                  onChange={v => setForm(f => ({ ...f, model: v }))}
                  placeholder="@anthropic-eastus2/claude-sonnet-4-6"
                  monospace
                />
              </div>
              <div>
                <FieldLabel label="Max tokens" />
                <Input
                  type="number"
                  value={form.maxTokens || 4096}
                  onChange={v => setForm(f => ({ ...f, maxTokens: parseInt(v) || 4096 }))}
                />
              </div>
            </div>
          </Section>

          {/* System Prompt */}
          <Section title="System Prompt">
            <Textarea
              value={form.systemPrompt || ''}
              onChange={v => setForm(f => ({ ...f, systemPrompt: v }))}
              placeholder="You are a helpful assistant..."
              rows={6}
            />
          </Section>

          {/* Features */}
          <Section title="Features">
            <div className="space-y-3">
              <Toggle
                checked={form.enableTools !== false}
                onChange={v => setForm(f => ({ ...f, enableTools: v }))}
                label="Enable tool use (file access, bash, MCP)"
              />
              <Toggle
                checked={form.streamingEnabled !== false}
                onChange={v => setForm(f => ({ ...f, streamingEnabled: v }))}
                label="Enable streaming responses"
              />
              <Toggle
                checked={form.confirmRiskyTools !== false}
                onChange={v => setForm(f => ({ ...f, confirmRiskyTools: v }))}
                label="Confirm risky tool calls (shell, git writes, SSH, screen control, outside-workspace access)"
              />
            </div>
          </Section>

          {/* Context Workspaces */}
          <Section title="Context Workspaces" defaultOpen={false}>
            <p className="text-xs text-gray-500 mb-3">
              Named system-prompt presets. Switch between them from the chat input bar without editing settings.
            </p>
            {(form.workspaces || []).map((ws, i) => (
              <div key={ws.id} className="border border-surface-500 rounded-lg mb-2 overflow-hidden">
                <div className="flex items-center gap-2 px-3 py-2">
                  <Globe size={12} className="text-gray-500 flex-shrink-0" />
                  <input
                    value={ws.name}
                    onChange={e => {
                      const updated = [...(form.workspaces || [])]
                      updated[i] = { ...ws, name: e.target.value }
                      setForm(f => ({ ...f, workspaces: updated }))
                    }}
                    placeholder="Workspace name"
                    className="flex-1 bg-transparent text-sm text-gray-200 outline-none"
                    disabled={ws.id === 'none'}
                  />
                  {ws.id === form.activeWorkspaceId && (
                    <span className="text-xs text-accent px-1.5 py-0.5 rounded bg-accent/10">active</span>
                  )}
                  <button
                    onClick={() => setForm(f => ({ ...f, activeWorkspaceId: ws.id }))}
                    disabled={ws.id === form.activeWorkspaceId}
                    className="px-2 py-0.5 rounded text-xs bg-surface-600 text-gray-400 hover:bg-accent/20 hover:text-accent disabled:opacity-30 disabled:cursor-default"
                  >
                    Use
                  </button>
                  {ws.id !== 'none' && (
                    <button
                      onClick={() => {
                        const updated = (form.workspaces || []).filter((_, j) => j !== i)
                        setForm(f => ({ ...f, workspaces: updated }))
                      }}
                      className="p-1 text-gray-600 hover:text-red-400"
                    >
                      <Trash2 size={12} />
                    </button>
                  )}
                </div>
                {ws.id !== 'none' && (
                  <div className="px-3 pb-3 pt-1 border-t border-surface-500">
                    <FieldLabel label="System Prompt" />
                    <Textarea
                      value={ws.systemPrompt || ''}
                      onChange={v => {
                        const updated = [...(form.workspaces || [])]
                        updated[i] = { ...ws, systemPrompt: v }
                        setForm(f => ({ ...f, workspaces: updated }))
                      }}
                      rows={4}
                      placeholder="You are a helpful assistant..."
                    />
                  </div>
                )}
              </div>
            ))}
            <button
              onClick={() => {
                const id = `ws_${Date.now()}`
                setForm(f => ({
                  ...f,
                  workspaces: [...(f.workspaces || []), { id, name: 'New Workspace', systemPrompt: '' }]
                }))
              }}
              className="flex items-center gap-2 px-3 py-2 rounded-lg border border-dashed border-surface-400 text-xs text-gray-500 hover:text-gray-300 hover:border-surface-300 transition-colors w-full justify-center"
            >
              <Plus size={13} />
              Add Workspace
            </button>
          </Section>

          {/* MCP Servers */}
          <Section title="MCP Servers" defaultOpen={false}>
            <p className="text-xs text-gray-500 mb-3">
              Connect MCP servers to give Claude access to Slack, Google Drive, databases, and more.
              Each server is a process that Claude can call tools from.
            </p>
            {/* Universal DB quick-connect */}
            <button
              onClick={async () => {
                const tmpl = await window.electron.getUniversalDbTemplate()
                const already = (form.mcpServers || []).some(s => s.id === tmpl.id)
                if (already) { alert('Universal DB is already in your MCP server list.'); return }
                setForm(f => ({ ...f, mcpServers: [...(f.mcpServers || []), tmpl] }))
              }}
              className="flex items-center gap-2 px-3 py-2 rounded-lg bg-blue-500/10 border border-blue-500/30 text-xs text-blue-300 hover:bg-blue-500/20 transition-colors w-full mb-3"
            >
              <Database size={13} />
              Quick-add Universal DB (Oracle / PostgreSQL / SQLite)
            </button>
            <button
              onClick={() => {
                const already = (form.mcpServers || []).some(s => s.id === PLAYWRIGHT_MCP_TEMPLATE.id)
                if (already) { alert('Playwright is already in your MCP server list.'); return }
                setForm(f => ({ ...f, mcpServers: [...(f.mcpServers || []), { ...PLAYWRIGHT_MCP_TEMPLATE } ] }))
              }}
              className="flex items-center gap-2 px-3 py-2 rounded-lg bg-blue-500/10 border border-blue-500/30 text-xs text-blue-300 hover:bg-blue-500/20 transition-colors w-full mb-3"
            >
              <Globe size={13} />
              Quick-add Playwright (browser automation, accessibility-tree based)
            </button>
            {(form.mcpServers || []).map((server, i) => (
              <McpServerRow
                key={server.id}
                server={server}
                connected={!!mcpStatus[server.id]}
                onUpdate={updated => {
                  const servers = [...(form.mcpServers || [])]
                  servers[i] = updated
                  setForm(f => ({ ...f, mcpServers: servers }))
                }}
                onDelete={() => {
                  const servers = (form.mcpServers || []).filter((_, j) => j !== i)
                  setForm(f => ({ ...f, mcpServers: servers }))
                  if (mcpStatus[server.id]) handleMcpDisconnect(server.id)
                }}
                onConnect={handleMcpConnect}
                onDisconnect={handleMcpDisconnect}
              />
            ))}
            <button
              onClick={addMcpServer}
              className="flex items-center gap-2 px-3 py-2 rounded-lg border border-dashed border-surface-400 text-xs text-gray-500 hover:text-gray-300 hover:border-surface-300 transition-colors w-full justify-center"
            >
              <Plus size={13} />
              Add MCP Server
            </button>
          </Section>

          {/* SSH Hosts */}
          <Section title="SSH Hosts" defaultOpen={false}>
            <p className="text-xs text-gray-500 mb-3">
              Pre-register hosts here so Claude can run commands on them via the ssh_exec
              tool. Only registered hosts are reachable — no key auto-discovery, no
              connecting to arbitrary addresses.
            </p>
            {(form.sshHosts || []).map((host, i) => (
              <SshHostRow
                key={host.id}
                host={host}
                onUpdate={updated => {
                  const hosts = [...(form.sshHosts || [])]
                  hosts[i] = updated
                  setForm(f => ({ ...f, sshHosts: hosts }))
                }}
                onDelete={() => {
                  const hosts = (form.sshHosts || []).filter((_, j) => j !== i)
                  setForm(f => ({ ...f, sshHosts: hosts }))
                }}
              />
            ))}
            <button
              onClick={addSshHost}
              className="flex items-center gap-2 px-3 py-2 rounded-lg border border-dashed border-surface-400 text-xs text-gray-500 hover:text-gray-300 hover:border-surface-300 transition-colors w-full justify-center"
            >
              <Plus size={13} />
              Add SSH Host
            </button>
          </Section>

        </div>

        {/* Footer */}
        <div className="px-6 py-4 border-t border-surface-600 flex items-center justify-between">
          <button
            onClick={() => {
              if (confirm('Reset all settings to defaults?')) {
                window.electron.resetSettings().then(s => {
                  setForm({ ...s })
                  dispatch({ type: 'SETTINGS_UPDATED', payload: s })
                })
              }
            }}
            className="text-xs text-gray-600 hover:text-red-400 transition-colors"
          >
            Reset to defaults
          </button>
          <button
            onClick={handleSave}
            disabled={saving}
            className="flex items-center gap-2 px-5 py-2 bg-accent hover:bg-accent-hover disabled:opacity-50 text-white text-sm rounded-xl transition-colors"
          >
            {saved ? (
              <><CheckCircle size={14} /> Saved</>
            ) : saving ? (
              'Saving...'
            ) : (
              <><Save size={14} /> Save Settings</>
            )}
          </button>
        </div>
      </div>
    </div>
  )
}
