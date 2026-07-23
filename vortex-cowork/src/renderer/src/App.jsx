import { AppProvider, useApp } from './contexts/AppContext'
import Sidebar from './components/Sidebar'
import ChatPanel from './components/ChatPanel'
import CodePanel from './components/CodePanel'
import PresentationPanel from './components/PresentationPanel'
import SettingsModal from './components/SettingsModal'
import ToolConfirmModal from './components/ToolConfirmModal'

function AppInner() {
  const { state } = useApp()
  const activeConv = state.conversations.find(c => c.id === state.activeConversationId)

  return (
    <div className="flex h-screen bg-surface-900 text-gray-100 overflow-hidden">
      {/* macOS title bar drag area */}
      <div
        className="titlebar-drag fixed top-0 left-0 right-0 h-8 z-50 pointer-events-none"
        style={{ WebkitAppRegion: 'drag' }}
      />

      {/* Sidebar */}
      <Sidebar />

      {/* Main chat area */}
      <div className="flex-1 flex flex-col overflow-hidden min-w-0">
        {activeConv ? (
          <ChatPanel conversation={activeConv} />
        ) : (
          <div className="flex-1 flex flex-col items-center justify-center text-gray-500">
            <div className="text-5xl mb-4 opacity-30">⚡</div>
            <p className="text-lg font-medium text-gray-400">Vortex Cowork</p>
            <p className="text-sm mt-1">Start a new chat to begin</p>
          </div>
        )}
      </div>

      {/* Code panel */}
      <CodePanel />

      {/* Presentation panel */}
      <PresentationPanel />

      {/* Settings modal */}
      {state.showSettings && <SettingsModal />}

      {/* Tool approval gate */}
      <ToolConfirmModal />
    </div>
  )
}

export default function App() {
  return (
    <AppProvider>
      <AppInner />
    </AppProvider>
  )
}
