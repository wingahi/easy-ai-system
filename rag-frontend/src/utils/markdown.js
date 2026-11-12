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

/**
 * 在安全 HTML 基础上把 [S1]/[S2] 包成可点击引用，用于跳转并高亮来源卡片。
 * 只在文本节点内替换（跳过代码块、链接），替换后不再经过 v-html 之外的 HTML 拼接。
 */
export function renderMarkdownWithCitations(text) {
  const container = document.createElement('div')
  container.innerHTML = renderMarkdown(text)
  const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT)
  const textNodes = []
  while (walker.nextNode()) {
    const node = walker.currentNode
    if (node.parentElement && node.parentElement.closest('pre, code, a')) continue
    if (/\[S\d+\]/.test(node.nodeValue)) textNodes.push(node)
  }
  for (const node of textNodes) {
    const fragment = document.createDocumentFragment()
    for (const part of node.nodeValue.split(/(\[S\d+\])/g)) {
      const cited = /^\[S(\d+)\]$/.exec(part)
      if (cited) {
        const span = document.createElement('span')
        span.className = 'citation-ref'
        span.dataset.cite = cited[1]
        span.textContent = part
        fragment.appendChild(span)
      } else if (part) {
        fragment.appendChild(document.createTextNode(part))
      }
    }
    node.parentNode.replaceChild(fragment, node)
  }
  return container.innerHTML
}
