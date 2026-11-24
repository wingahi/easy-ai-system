/**
 * 统一的流式生成停止逻辑：通知后端取消任务、中止请求、复位 streaming 状态，
 * 并把当前助手消息标记为「已停止」（保留已生成内容，不清空）。
 *
 * @param {{ abort?: () => void } | null} streamer 由 api 层 streamPost 返回的对象
 * @param {{ value: boolean } | null} streamingRef 页面级 streaming ref
 * @param {object | null} aiMsg 正在流式填充的助手消息
 * @param {{ key?: string, request?: (() => any) | null }} [options]
 *        key 用于 300ms 内去重；request 为停止请求（调用方按自身上下文选择 windows / share 路径）
 */
const STOP_REQUEST_MIN_INTERVAL = 300
let lastStopRequestKey = ''
let lastStopRequestAt = 0

/**
 * 先发停止请求（不等待、失败静默），再做本地断连。
 * 请求失败或后端接口未就绪都不能阻止本地停止。
 */
function fireStopRequest(options) {
  const request = options?.request
  if (typeof request !== 'function') return
  const key = options?.key || ''
  const now = Date.now()
  // 同一会话在 300ms 内只发一次，避免双击重复请求
  if (key && key === lastStopRequestKey && now - lastStopRequestAt < STOP_REQUEST_MIN_INTERVAL) return
  lastStopRequestKey = key
  lastStopRequestAt = now
  try {
    const pending = request()
    // 不等待完成；失败（404/500/网络异常）静默吞掉
    if (pending && typeof pending.catch === 'function') pending.catch(() => {})
  } catch {
    /* 忽略同步异常，继续本地停止 */
  }
}

export function stopGeneration(streamer, streamingRef, aiMsg, options) {
  // 先通知后端取消，再断开本地 SSE
  fireStopRequest(options)
  if (streamer?.abort) streamer.abort()
  if (streamingRef) streamingRef.value = false
  if (aiMsg && aiMsg.streaming) {
    aiMsg.streaming = false
    aiMsg.stopped = true
  }
}

// 重放信令的面向用户文案：不暴露 stream_replay_* 等技术术语
const REPLAY_INCOMPLETE_TEXT = {
  'generation-stopped': '回答生成已中止，内容可能不完整。',
  'idle-timeout': '长时间没有新的生成内容，连接已结束，回答可能不完整。',
  'buffer-degraded': '服务端记录不完整，部分内容可能缺失。',
  'follow-timeout': '等待生成结果超时，连接已结束，回答可能不完整。',
  'stream-expired': '会话连接已过期，请刷新会话查看最新内容。',
  'replay-error': '恢复连接时出错，请刷新会话查看最新内容。'
}

/**
 * 把重连补发信令转换为页面提示。
 *
 * @param {string} event SSE 事件名
 * @param {any} data 事件负载
 * @returns {{ tone: 'warning' | 'info', text: string } | null} 非重放信令返回 null
 */
export function describeReplayNotice(event, data) {
  if (event === 'stream_replay_gap') {
    return {
      tone: 'warning',
      text: '网络恢复后部分内容已过期，当前回答可能不完整，建议刷新会话查看完整结果。'
    }
  }
  if (event === 'stream_replay_incomplete') {
    const reason = data && typeof data === 'object' ? data.reason : ''
    return {
      tone: 'info',
      text: REPLAY_INCOMPLETE_TEXT[reason] || '连接未能完整恢复，回答可能缺失部分内容。'
    }
  }
  return null
}
