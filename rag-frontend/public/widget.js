/**
 * WGH RAG 嵌入式问答 Widget
 *
 * 用法（第三方页面）：
 * <div id="wgh-widget"></div>
 * <script src="https://your-rag-host/widget.js"
 *         data-token="分享 token"
 *         data-base="https://your-rag-host"
 *         data-container="wgh-widget"
 *         data-height="640px"></script>
 *
 * 说明：
 * - iframe 内加载 /share/{token}?embed=1，样式天然隔离（独立文档、独立 CSS）
 * - 需在服务端配置 CSP frame-ancestors 允许嵌入域名；跨域时前端需允许 CORS（本项目默认 *）
 */
(function () {
  var script = document.currentScript || document.querySelector('script[data-token]')
  if (!script) return

  var token = script.getAttribute('data-token')
  if (!token) {
    console.warn('[WGH Widget] 缺少 data-token')
    return
  }
  var base = script.getAttribute('data-base') || ''
  if (!base) {
    try { base = new URL(script.src).origin } catch (e) { base = location.origin }
  }
  var height = script.getAttribute('data-height') || '640px'
  var containerId = script.getAttribute('data-container')

  var iframe = document.createElement('iframe')
  iframe.src = base.replace(/\/$/, '') + '/share/' + encodeURIComponent(token) + '?embed=1'
  iframe.title = 'WGH RAG 智能问答'
  iframe.loading = 'lazy'
  iframe.style.cssText = [
    'width:100%', 'height:' + height, 'border:0', 'border-radius:12px',
    'box-shadow:0 4px 24px rgba(0,0,0,.08)', 'background:#fff', 'display:block'
  ].join(';')
  // 允许同源脚本访问，跨域时无权限也不影响展示
  iframe.setAttribute('allow', 'clipboard-write')

  var target = containerId ? document.getElementById(containerId) : null
  if (!target) {
    target = document.createElement('div')
    script.parentNode.insertBefore(target, script.nextSibling)
  }
  target.appendChild(iframe)
})()
