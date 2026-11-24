import { STORAGE_KEYS } from './constants'

/**
 * SSE 客户端：解析 event/data/id，断线后按 Last-Event-ID 自动重连续传。
 *
 * 约定（与后端「SSE 事件重放」设计一致）：
 * - 每条事件带 `id: <streamId>:<seq>`，客户端记住最后一个 id；
 * - 重连沿用同一 POST 地址，请求头带 `Last-Event-ID`，请求体只带 `{ sessionId, lastEventId }`，
 *   绝不重发 message / userInput，因此不会重复提交消息或重复确认 HITL；
 * - 只有「已经收到过事件 id」才允许重连：否则无法保证服务端走接管分支，可能触发新的生成；
 * - 业务错误（HTTP 200 + JSON，如 2001/3001/3003）为终止态，不再重试；
 * - abort（用户停止生成 / 页面离开）后不再重连。
 */

export const SSE_RECONNECT = {
  maxAttempts: 5,
  baseDelay: 1000,
  maxDelay: 15000
}

/** 指数退避 + 抖动，避免多端同时重连 */
function backoffDelay(attempt, baseDelay, maxDelay) {
  const grow = baseDelay * Math.pow(2, Math.max(attempt - 1, 0))
  const jitter = grow * 0.2 * Math.random()
  return Math.min(maxDelay, Math.round(grow + jitter))
}

/** 可被 abort 打断的等待 */
function sleep(ms, signal) {
  return new Promise((resolve) => {
    let timer = null
    const done = () => {
      if (timer) clearTimeout(timer)
      signal.removeEventListener('abort', done)
      resolve()
    }
    timer = setTimeout(done, ms)
    signal.addEventListener('abort', done, { once: true })
  })
}

/** 解析单个 SSE 事件块（不含结尾空行） */
function parseChunk(chunk) {
  let event = 'message'
  let data = ''
  let id = ''
  for (const line of chunk.split('\n')) {
    if (!line || line.startsWith(':')) continue
    if (line.startsWith('event:')) {
      event = line.substring(6).trim()
    } else if (line.startsWith('data:')) {
      // 多个 data 行按 SSE 规范用换行拼接；不能 trim，会丢失行内空格
      if (data) data += '\n'
      data += line.substring(5)
    } else if (line.startsWith('id:')) {
      id = line.substring(3).trim()
    }
  }
  return { event, data, id }
}

/**
 * @param {object} options
 * @param {string} options.url 流式接口地址（重连复用同一地址）
 * @param {() => object} [options.headers] 额外请求头（每次请求重新计算，便于携带最新凭证）
 * @param {object} [options.body] 首次请求体（含 message 等业务参数）
 * @param {() => (string|number|null)} [options.getSessionId] 重连时读取当前会话 id
 * @param {boolean} [options.requiresSessionId=true] 重连是否必须带 sessionId（对话流必须）
 * @param {number} [options.maxAttempts] 最大重连次数
 * @param {(status: object) => void} [options.onStatus] 连接状态回调：reconnecting / reconnected / failed / exhausted
 * @param {(event: string, data: any) => void} [options.onEvent]
 * @param {() => void} [options.onDone]
 * @param {(err: Error) => void} [options.onError]
 * @returns {{ doStream: Function, abort: Function }}
 */
export function createSseStream(options) {
  const {
    url,
    headers,
    body,
    getSessionId,
    requiresSessionId = true,
    maxAttempts = SSE_RECONNECT.maxAttempts,
    baseDelay = SSE_RECONNECT.baseDelay,
    maxDelay = SSE_RECONNECT.maxDelay,
    onStatus,
    onEvent,
    onDone,
    onError
  } = options || {}

  const controller = new AbortController()
  let stopped = false
  let started = false
  let lastEventId = ''
  let attempt = 0

  const notify = (status) => {
    if (typeof onStatus !== 'function') return
    try {
      onStatus(status)
    } catch {
      /* 状态回调异常不影响流本身 */
    }
  }

  const resolveHeaders = (reconnect) => {
    const token = localStorage.getItem(STORAGE_KEYS.TOKEN)
    return {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(typeof headers === 'function' ? headers() : {}),
      // 重连才带断点游标；首次请求不带，避免被误判为接管
      ...(reconnect && lastEventId ? { 'Last-Event-ID': lastEventId } : {})
    }
  }

  // 重连请求体只带会话与游标：不重发 message/userInput，服务端走“接管”分支
  const resolveBody = (reconnect) => {
    if (!reconnect) return body || {}
    const sessionId = typeof getSessionId === 'function' ? getSessionId() : null
    return { sessionId: sessionId == null ? null : sessionId, lastEventId }
  }

  const readFailure = async (res) => {
    const contentType = res.headers.get('content-type') || ''
    if (contentType.includes('application/json')) {
      // 业务错误（含重放未启用/流已过期/已被取代），后端已给出可读中文
      let message = ''
      try {
        const payload = await res.json()
        message = payload?.message || ''
      } catch {
        /* 忽略解析失败 */
      }
      const err = new Error(message || `请求失败（HTTP ${res.status}）`)
      err.fatal = true
      return err
    }
    const err = new Error(`HTTP ${res.status}`)
    err.fatal = res.status < 500
    return err
  }

  const runOnce = async (reconnect, eventHandler) => {
    const res = await fetch(url, {
      method: 'POST',
      headers: resolveHeaders(reconnect),
      body: JSON.stringify(resolveBody(reconnect)),
      signal: controller.signal
    })
    if (stopped) return
    if (!res.ok || (res.headers.get('content-type') || '').includes('application/json')) {
      throw await readFailure(res)
    }
    if (reconnect) notify({ phase: 'reconnected' })
    if (!res.body) throw new Error('响应没有可读取的内容')

    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (!stopped) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      let idx
      while ((idx = buffer.indexOf('\n\n')) !== -1) {
        const chunk = buffer.substring(0, idx)
        buffer = buffer.substring(idx + 2)
        const parsed = parseChunk(chunk)
        if (parsed.id) lastEventId = parsed.id
        if (!parsed.data) continue
        let data = parsed.data
        try {
          data = JSON.parse(parsed.data)
        } catch {
          /* 纯文本负载原样透传 */
        }
        eventHandler(parsed.event, data)
      }
    }
  }

  const doStream = async (eventHandler, doneHandler, errorHandler) => {
    if (started) return
    started = true
    const handleEvent = eventHandler || onEvent || (() => {})
    const handleDone = doneHandler || onDone || (() => {})
    const handleError = errorHandler || onError || (() => {})

    // 终端信令：收到后连接即使中断也不再重连
    let terminal = false
    // 重连成功后收到过事件即视为连接已恢复，重置退避次数，避免长会话被累计次数耗尽
    let resetAttemptOnEvent = false
    const dispatch = (event, data) => {
      if (resetAttemptOnEvent) {
        resetAttemptOnEvent = false
        attempt = 0
      }
      if (event === 'done' || event === 'stream_replay_incomplete') terminal = true
      handleEvent(event, data)
    }

    let reconnect = false
    while (true) {
      try {
        resetAttemptOnEvent = reconnect
        await runOnce(reconnect, dispatch)
        if (stopped) return
        stopped = true
        handleDone()
        return
      } catch (err) {
        if (stopped || controller.signal.aborted || err?.name === 'AbortError') return
        const failure = err instanceof Error ? err : new Error(String(err))

        if (terminal) {
          stopped = true
          handleDone()
          return
        }
        // 业务错误为终止态：交给页面展示并回退到会话历史
        if (failure.fatal) {
          stopped = true
          notify({ phase: 'failed', message: failure.message })
          handleError(failure)
          return
        }
        // 没有事件游标时无法保证服务端走“接管”分支，重试会重复提交，直接放弃
        if (!lastEventId) {
          stopped = true
          notify({ phase: 'failed', message: '连接中断，请刷新会话后重试' })
          handleError(failure)
          return
        }
        if (requiresSessionId) {
          const sessionId = typeof getSessionId === 'function' ? getSessionId() : null
          if (sessionId == null || sessionId === '') {
            stopped = true
            notify({ phase: 'failed', message: '无法恢复连接，请刷新会话后重试' })
            handleError(new Error('无法恢复连接（会话信息缺失），请刷新会话后重试'))
            return
          }
        }
        if (attempt >= maxAttempts) {
          stopped = true
          notify({ phase: 'exhausted', message: '自动重连失败' })
          handleError(new Error('网络连接中断，自动重连失败，请刷新会话查看最新内容'))
          return
        }

        attempt += 1
        const delay = backoffDelay(attempt, baseDelay, maxDelay)
        notify({ phase: 'reconnecting', attempt, maxAttempts, delay })
        await sleep(delay, controller.signal)
        if (stopped || controller.signal.aborted) return
        reconnect = true
      }
    }
  }

  const abort = () => {
    if (stopped) return
    stopped = true
    notify({ phase: 'closed' })
    controller.abort()
  }

  return { doStream, abort }
}
