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
