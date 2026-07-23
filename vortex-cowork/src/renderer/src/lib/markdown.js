import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'

const md = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: false,
  breaks: true,
  highlight: (code, lang) => {
    if (lang && hljs.getLanguage(lang)) {
      try { return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value } catch (_) {}
    }
    try { return hljs.highlightAuto(code).value } catch (_) {}
    return ''
  },
})

export function renderMarkdown(content) {
  if (!content) return ''
  try {
    return md.render(content)
  } catch (e) {
    return `<p>${content.replace(/</g, '&lt;').replace(/>/g, '&gt;')}</p>`
  }
}
