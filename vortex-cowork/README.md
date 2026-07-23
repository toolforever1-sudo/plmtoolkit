# ⚡ Vortex Cowork

A Claude-powered desktop AI assistant built on the Vortex API gateway.  
Dark theme · File attachments · MCP tool support · Code panel · Workspace switcher.

> **macOS only**

---

## Quick Start

### 1. Install Node.js
Download and install the **LTS** version from [nodejs.org](https://nodejs.org).

### 2. Clone the repo
```bash
git clone https://github.com/YOUR_USERNAME/vortex-cowork.git
cd vortex-cowork
```

### 3. Run setup (one time only)
```bash
chmod +x setup.sh && ./setup.sh
```
This installs everything, downloads Electron, and walks you through adding your API key.

### 4. Add your API key
Open `.env` and fill in:
```
PORTKEY_KEY=your_portkey_api_key_here
VORTEX_BASE_URL=https://ai.vortex.sandisk.com/v1/
VORTEX_MODEL=@anthropic-eastus2/claude-sonnet-4-6
```

### 5. Launch
```bash
npm run dev
```

---

## `vortex` shortcut

The setup script offers to add a `vortex` alias so you can launch the app from anywhere:
```bash
vortex
```

To add it manually:
```bash
echo 'alias vortex="cd ~/path/to/vortex-cowork && npm run dev"' >> ~/.zshrc && source ~/.zshrc
```

---

## Troubleshooting

**`Error: Electron uninstalled`**
```bash
node node_modules/electron/install.js && npm run dev
```

**`electron-vite: command not found`**
```bash
npm install && npm run dev
```

---

## Features
- 💬 Streaming chat with Claude via Vortex API
- 📎 File attachments — PDF, DOCX, XLSX, images, code, CSV
- 🗄️ MCP server support (Universal DB, Slack, and more)
- 💻 Right-side code panel with tabbed file view + copy/download
- 🌐 Workspace context switcher (swap system prompts instantly)
- 💾 Conversations persist across sessions (2-week rolling window)
- ⚙️ All settings editable in-app — no code edits needed
