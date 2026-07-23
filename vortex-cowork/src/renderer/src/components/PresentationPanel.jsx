import { useState, useCallback } from 'react'
import { X, Download, ChevronLeft, ChevronRight, Presentation, Trash2 } from 'lucide-react'
import { useApp } from '../contexts/AppContext'

// ── Slide renderer ────────────────────────────────────────────────────────────
function SlidePreview({ slide, index, isActive, onClick }) {
  const bgDark = slide.type === 'title' || slide.type === 'section'
  const bgColor = slide.type === 'closing' ? '#E10600' : (bgDark ? '#000000' : '#FFFCF9')

  return (
    <button
      onClick={onClick}
      className={`w-full text-left rounded-lg overflow-hidden border transition-all flex-shrink-0 ${
        isActive
          ? 'border-accent shadow-lg shadow-accent/20'
          : 'border-surface-500 hover:border-surface-400'
      }`}
      style={{ aspectRatio: '16/9' }}
    >
      <div
        className="w-full h-full relative p-3 flex flex-col"
        style={{ background: bgColor }}
      >
        {/* Slide number */}
        <span className="absolute top-1.5 right-2 text-xs opacity-40"
          style={{ color: bgDark || slide.type === 'closing' ? '#fff' : '#000' }}>
          {index + 1}
        </span>

        {slide.type === 'closing' && (
          <div className="flex-1 flex items-center justify-center">
            <span className="text-white font-bold text-xs tracking-wide">SANDISK</span>
          </div>
        )}

        {slide.type === 'title' && (
          <>
            <div className="mt-4 mb-1 font-bold text-white text-xs leading-tight line-clamp-2">
              {slide.title}
            </div>
            {slide.subtitle && (
              <div className="text-gray-400 text-xs line-clamp-1">{slide.subtitle}</div>
            )}
            <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-accent" />
          </>
        )}

        {slide.type === 'section' && (
          <>
            <div className="absolute left-0 top-1/4 bottom-1/4 w-1 bg-accent rounded-r" />
            <div className="ml-3 mt-4 font-bold text-white text-xs leading-tight line-clamp-2">
              {slide.title}
            </div>
            <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-accent" />
          </>
        )}

        {(slide.type === 'bullets' || slide.type === 'content') && (
          <>
            <div className="font-bold text-black text-xs mb-1 line-clamp-1">{slide.title}</div>
            <div className="w-full h-px bg-gray-200 mb-1" />
            {slide.type === 'bullets' && (slide.bullets || []).slice(0, 4).map((b, i) => (
              <div key={i} className="flex items-start gap-1 mb-0.5">
                <span className="text-accent text-xs flex-shrink-0">●</span>
                <span className="text-gray-700 text-xs line-clamp-1">{b}</span>
              </div>
            ))}
            {slide.type === 'content' && (
              <div className="text-gray-700 text-xs line-clamp-3">{slide.content}</div>
            )}
            <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-accent" />
          </>
        )}

        {slide.type === 'two-column' && (
          <>
            <div className="font-bold text-black text-xs mb-1 line-clamp-1">{slide.title}</div>
            <div className="w-full h-px bg-gray-200 mb-1" />
            <div className="flex gap-1 flex-1">
              <div className="flex-1 bg-gray-100 rounded p-1">
                <div className="text-gray-600 text-xs line-clamp-3">{slide.left}</div>
              </div>
              <div className="flex-1 bg-gray-100 rounded p-1">
                <div className="text-gray-600 text-xs line-clamp-3">{slide.right}</div>
              </div>
            </div>
            <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-accent" />
          </>
        )}

        {slide.type === 'quote' && (
          <>
            <div className="text-accent font-bold text-2xl leading-none">"</div>
            <div className="text-black text-xs italic line-clamp-3 mt-1">{slide.quote}</div>
            {slide.attribution && (
              <div className="text-gray-500 text-xs mt-1">— {slide.attribution}</div>
            )}
            <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-accent" />
          </>
        )}
      </div>
    </button>
  )
}

// ── Full slide detail view ────────────────────────────────────────────────────
function SlideDetail({ slide, index, total, onPrev, onNext }) {
  const bgDark = slide.type === 'title' || slide.type === 'section'
  const bgColor = slide.type === 'closing' ? '#E10600' : (bgDark ? '#000000' : '#FFFCF9')

  return (
    <div className="flex-1 flex flex-col min-h-0">
      {/* Large preview */}
      <div
        className="mx-4 mt-3 rounded-xl overflow-hidden border border-surface-500 flex-shrink-0"
        style={{ aspectRatio: '16/9', background: bgColor }}
      >
        <div className="w-full h-full p-8 flex flex-col relative">
          {slide.type === 'closing' && (
            <div className="flex-1 flex items-center justify-center">
              <span className="text-white font-bold text-2xl tracking-wide">SANDISK</span>
            </div>
          )}

          {slide.type === 'title' && (
            <>
              <div className="mt-8 text-white font-bold text-2xl leading-tight">{slide.title}</div>
              {slide.subtitle && <div className="text-gray-400 text-base mt-3">{slide.subtitle}</div>}
              <div className="absolute bottom-0 left-0 right-0 h-1.5" style={{ background: '#E10600' }} />
            </>
          )}
          {slide.type === 'section' && (
            <>
              <div className="absolute left-0 top-1/4 bottom-1/4 w-2" style={{ background: '#E10600' }} />
              <div className="ml-6 mt-10 text-white font-bold text-xl">{slide.title}</div>
              {slide.subtitle && <div className="ml-6 text-gray-400 text-sm mt-2">{slide.subtitle}</div>}
              <div className="absolute bottom-0 left-0 right-0 h-1.5" style={{ background: '#E10600' }} />
            </>
          )}
          {(slide.type === 'bullets' || slide.type === 'content') && (
            <>
              <div className="text-black font-bold text-lg">{slide.title}</div>
              <div className="w-full h-px bg-gray-200 my-2" />
              {slide.type === 'bullets' && (
                <ul className="space-y-1.5">
                  {(slide.bullets || []).map((b, i) => (
                    <li key={i} className="flex items-start gap-2">
                      <span style={{ color: '#E10600' }} className="text-sm font-bold flex-shrink-0">●</span>
                      <span className="text-gray-800 text-sm">{b}</span>
                    </li>
                  ))}
                </ul>
              )}
              {slide.type === 'content' && (
                <p className="text-gray-800 text-sm leading-relaxed">{slide.content}</p>
              )}
              <div className="absolute bottom-0 left-0 right-0 h-1.5" style={{ background: '#E10600' }} />
            </>
          )}
          {slide.type === 'two-column' && (
            <>
              <div className="text-black font-bold text-lg mb-2">{slide.title}</div>
              <div className="w-full h-px bg-gray-200 mb-2" />
              <div className="flex gap-3 flex-1">
                <div className="flex-1 bg-gray-50 rounded p-3">
                  <p className="text-gray-700 text-xs">{slide.left}</p>
                </div>
                <div className="flex-1 bg-gray-50 rounded p-3">
                  <p className="text-gray-700 text-xs">{slide.right}</p>
                </div>
              </div>
              <div className="absolute bottom-0 left-0 right-0 h-1.5" style={{ background: '#E10600' }} />
            </>
          )}
          {slide.type === 'quote' && (
            <>
              <div className="font-bold text-5xl leading-none" style={{ color: '#E10600' }}>"</div>
              <div className="text-gray-800 text-base italic mt-2 leading-relaxed">{slide.quote}</div>
              {slide.attribution && <div className="text-gray-500 text-sm mt-3">— {slide.attribution}</div>}
              <div className="absolute bottom-0 left-0 right-0 h-1.5" style={{ background: '#E10600' }} />
            </>
          )}
        </div>
      </div>

      {/* Nav */}
      <div className="flex items-center justify-between px-4 py-2 flex-shrink-0">
        <button onClick={onPrev} disabled={index === 0}
          className="p-1.5 rounded-lg hover:bg-surface-600 text-gray-500 hover:text-gray-300 disabled:opacity-30 transition-colors">
          <ChevronLeft size={16} />
        </button>
        <span className="text-xs text-gray-500">{index + 1} / {total}</span>
        <button onClick={onNext} disabled={index === total - 1}
          className="p-1.5 rounded-lg hover:bg-surface-600 text-gray-500 hover:text-gray-300 disabled:opacity-30 transition-colors">
          <ChevronRight size={16} />
        </button>
      </div>

      {/* Notes */}
      {slide.notes && (
        <div className="mx-4 mb-2 p-2 bg-surface-700 rounded-lg border border-surface-500 flex-shrink-0">
          <p className="text-xs text-gray-500 font-medium mb-0.5">Speaker notes</p>
          <p className="text-xs text-gray-400">{slide.notes}</p>
        </div>
      )}
    </div>
  )
}

// ── Main panel ────────────────────────────────────────────────────────────────
export default function PresentationPanel() {
  const { state, dispatch } = useApp()
  const [activeSlideIdx, setActiveSlideIdx] = useState(0)

  const { presentations, presentationPanelOpen, activePresentationId } = state
  const pres = presentations.find(p => p.id === activePresentationId) || presentations[presentations.length - 1]

  const handleDownload = useCallback(() => {
    if (pres?.path) window.electron.openFile(pres.path)
  }, [pres])

  if (!presentationPanelOpen || !pres) return null

  const slides = pres.slides || []
  const activeSlide = slides[activeSlideIdx] || slides[0]

  return (
    <div className="flex flex-col border-l border-surface-600 bg-surface-900" style={{ width: '460px', minWidth: '360px' }}>
      {/* Header */}
      <div className="flex items-center justify-between px-3 py-2 border-b border-surface-600 flex-shrink-0">
        <div className="flex items-center gap-2 min-w-0">
          <Presentation size={14} className="text-accent flex-shrink-0" />
          <span className="text-xs font-semibold text-gray-300 truncate">{pres.title}</span>
          <span className="text-xs text-gray-600 flex-shrink-0">{slides.length} slides</span>
        </div>
        <div className="flex items-center gap-1">
          <button onClick={handleDownload}
            className="flex items-center gap-1 px-2 py-1 rounded-lg text-xs bg-accent hover:bg-accent-hover text-white transition-colors"
            title="Open / Download PPTX">
            <Download size={12} />
            <span>Open PPTX</span>
          </button>
          <button onClick={() => dispatch({ type: 'PRESENTATION_PANEL_TOGGLE' })}
            className="p-1 rounded hover:bg-surface-600 text-gray-500 hover:text-gray-300 transition-colors">
            <X size={14} />
          </button>
        </div>
      </div>

      {/* Deck tabs if multiple presentations */}
      {presentations.length > 1 && (
        <div className="flex overflow-x-auto border-b border-surface-600 flex-shrink-0" style={{ scrollbarWidth: 'none' }}>
          {presentations.map(p => (
            <button key={p.id}
              onClick={() => { dispatch({ type: 'PRESENTATION_SET_ACTIVE', id: p.id }); setActiveSlideIdx(0) }}
              className={`px-3 py-1.5 text-xs whitespace-nowrap border-r border-surface-600 flex-shrink-0 transition-colors ${
                p.id === activePresentationId ? 'bg-surface-700 text-gray-100' : 'text-gray-500 hover:text-gray-300'
              }`}>
              {p.title}
            </button>
          ))}
        </div>
      )}

      {/* Large slide detail */}
      {activeSlide && (
        <SlideDetail
          slide={activeSlide}
          index={activeSlideIdx}
          total={slides.length}
          onPrev={() => setActiveSlideIdx(i => Math.max(0, i - 1))}
          onNext={() => setActiveSlideIdx(i => Math.min(slides.length - 1, i + 1))}
        />
      )}

      {/* Thumbnail strip */}
      <div className="border-t border-surface-600 p-2 flex-shrink-0">
        <div className="flex gap-2 overflow-x-auto pb-1" style={{ scrollbarWidth: 'thin' }}>
          {slides.map((slide, i) => (
            <div key={i} className="flex-shrink-0" style={{ width: '120px' }}>
              <SlidePreview
                slide={slide}
                index={i}
                isActive={i === activeSlideIdx}
                onClick={() => setActiveSlideIdx(i)}
              />
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
