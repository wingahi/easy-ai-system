/**
 * WGH RAG 嵌入式问答 Widget
 *
 * 用法（第三方页面）：
 * <div id="wgh-widget"></div>
 * <script src="https://your-rag-host/widget.js"
 *         data-token="分享 token"
 *         data-base="https://your-rag-host"
 *         data-api-base="https://your-rag-host"   <!-- 可选：API 与页面不同域时指定 -->
 *         data-container="wgh-widget"
 *         data-height="640px"></script>
 *
 * 授权流程：
 * 1. 宿主页面以跨域请求方式调用 /api/public/share/{token}/embed/authorize，
 *    浏览器会自动携带真实 Origin，服务端据此校验分享链接的域名 / IP 白名单；
 * 2. 校验通过后返回短期 grant，iframe 以 /share/{token}?embed=1&grant=... 加载，
 *    分享页随后把 grant 放入 X-Embed-Grant 请求头；
 * 3. 未授权来源会在容器内展示可读的拒绝原因，不会加载 iframe。
 *
 * 注意：iframe 的 frame-ancestors 属于浏览器级兜底，需在网关/静态站点
 * 配置 CSP frame-ancestors 指向分享页白名单域名。
 */
(function () {
  var script = document.currentScript || document.querySelector('script[data-token]')
  if (!script) return

  var token = script.getAttribute('data-token')
  if (!token) {
    console.warn('[WGH Widget] 缺少 data-token')
    return
  }
  var base = (script.getAttribute('data-base') || '').replace(/\/$/, '')
  if (!base) {
    try { base = new URL(script.src).origin } catch (e) { base = location.origin }
  }
  var apiBase = (script.getAttribute('data-api-base') || base).replace(/\/$/, '')
  var height = script.getAttribute('data-height') || '640px'
  var containerId = script.getAttribute('data-container')

  var target = containerId ? document.getElementById(containerId) : null
  if (!target) {
    target = document.createElement('div')
    script.parentNode.insertBefore(target, script.nextSibling)
  }

  function showError(message) {
    target.innerHTML = ''
    var panel = document.createElement('div')
    panel.style.cssText = [
      'box-sizing:border-box', 'width:100%', 'height:' + height, 'padding:24px',
      'border:1px solid #fde2e2', 'border-radius:12px', 'background:#fef0f0',
      'color:#f56c6c', 'font-size:14px', 'line-height:1.7', 'display:flex',
      'align-items:center', 'justify-content:center', 'text-align:center'
    ].join(';')
    panel.textContent = 'WGH RAG Widget 加载失败：' + message
    target.appendChild(panel)
  }

  function showLoading() {
    target.innerHTML = ''
    var panel = document.createElement('div')
    panel.style.cssText = [
      'box-sizing:border-box', 'width:100%', 'height:' + height, 'padding:24px',
      'border:1px solid #ebeef5', 'border-radius:12px', 'background:#fff',
      'color:#909399', 'font-size:14px', 'display:flex', 'align-items:center',
      'justify-content:center'
    ].join(';')
    panel.textContent = '正在校验嵌入授权…'
    target.appendChild(panel)
  }

  showLoading()
  fetch(apiBase + '/api/public/share/' + encodeURIComponent(token) + '/embed/authorize', {
    method: 'GET',
    credentials: 'omit',
    headers: { 'Accept': 'application/json' }
  }).then(function (res) {
    return res.json().catch(function () { return {} }).then(function (body) {
      if (!res.ok || body.code !== 200 || !body.data || !body.data.grant) {
        var message = body.message || ('HTTP ' + res.status)
        throw new Error(message)
      }
      return body.data.grant
    })
  }).then(function (grant) {
    target.innerHTML = ''
    var iframe = document.createElement('iframe')
    iframe.src = base + '/share/' + encodeURIComponent(token)
      + '?embed=1&grant=' + encodeURIComponent(grant)
    iframe.title = 'WGH RAG 智能问答'
    iframe.loading = 'lazy'
    iframe.style.cssText = [
      'width:100%', 'height:' + height, 'border:0', 'border-radius:12px',
      'box-shadow:0 4px 24px rgba(0,0,0,.08)', 'background:#fff', 'display:block'
    ].join(';')
    iframe.setAttribute('allow', 'clipboard-write')
    target.appendChild(iframe)
  }).catch(function (err) {
    showError(err && err.message ? err.message : '网络异常，无法校验嵌入授权')
  })
})()
