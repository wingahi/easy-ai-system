import { marked } from 'marked'
import DOMPurify from 'dompurify'

// 统一 Markdown 渲染入口：marked 解析后必须经 DOMPurify 清洗，禁止直接 v-html 渲染原始 HTML
marked.setOptions({ breaks: true })

// 仅放行 Markdown 常见输出标签：表格、代码块、引用、链接、列表等
const ALLOWED_TAGS = [
  'p', 'br', 'hr',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
  'strong', 'em', 'del', 'ins', 'sup', 'sub',
  'blockquote', 'ul', 'ol', 'li',
  'a', 'img',
  'code', 'pre',
  'table', 'thead', 'tbody', 'tr', 'th', 'td',
  'span'
]

const ALLOWED_ATTR = [
  'href', 'title', 'target', 'rel',
  'src', 'alt',
  'class', 'align', 'colspan', 'rowspan', 'start', 'lang'
]

// 仅允许 http/https/mailto 及相对路径、页内锚点，拦截 javascript:、data:、vbscript: 等危险协议
const ALLOWED_URI_REGEXP = /^(?:(?:https?|mailto):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i

const SANITIZE_CONFIG = {
  ALLOWED_TAGS,
  ALLOWED_ATTR,
  ALLOWED_URI_REGEXP,
  // 显式禁止可执行/可外联的危险标签与内联事件
  FORBID_TAGS: ['script', 'iframe', 'frame', 'frameset', 'object', 'embed', 'form', 'input', 'button',
    'textarea', 'select', 'option', 'style', 'link', 'meta', 'base'],
  FORBID_ATTR: ['style', 'srcset', 'formaction'],
  KEEP_CONTENT: true
}

// 外部链接统一新窗口打开并附加 rel，避免 opener 反向控制
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    const href = node.getAttribute('href')
    if (href) {
      node.setAttribute('target', '_blank')
      node.setAttribute('rel', 'noopener noreferrer')
    }
  }
})

/**
 * Markdown 文本转安全 HTML。
 * 每次均对完整累积文本整体解析并清洗，不做分段清洗，避免流式输出时标签被截断。
 */
export function renderMarkdown(text) {
  const html = marked.parse(text == null ? '' : String(text), { async: false })
  return DOMPurify.sanitize(html, SANITIZE_CONFIG)
}
