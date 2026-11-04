/**
 * 统一的流式生成停止逻辑：中止请求、复位 streaming 状态，
 * 并把当前助手消息标记为「已停止」（保留已生成内容，不清空）。
 *
 * @param {{ abort?: () => void } | null} streamer 由 api 层 streamPost 返回的对象
 * @param {{ value: boolean } | null} streamingRef 页面级 streaming ref
 * @param {object | null} aiMsg 正在流式填充的助手消息
 */
export function stopGeneration(streamer, streamingRef, aiMsg) {
  if (streamer?.abort) streamer.abort()
  if (streamingRef) streamingRef.value = false
  if (aiMsg && aiMsg.streaming) {
    aiMsg.streaming = false
    aiMsg.stopped = true
  }
}
