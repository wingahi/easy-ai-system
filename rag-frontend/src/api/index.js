// API 统一入口 - 按需引入
import request from '../utils/request'

// 认证
export const login = (data) => request.post('/auth/login', data)
export const getMe = () => request.get('/auth/me')

// 知识库
export const kbPage = (params) => request.get('/kb', { params })
export const kbGet = (id) => request.get(`/kb/${id}`)
export const kbCreate = (data) => request.post('/kb', data)
export const kbUpdate = (data) => request.put('/kb', data)
export const kbDelete = (id) => request.delete(`/kb/${id}`)
export const kbList = () => request.get('/kb/list')
export const kbUploadFile = (kbId, file, splitStrategy, splitConfig) => {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('splitStrategy', splitStrategy || 'PARAGRAPH')
  if (splitConfig) formData.append('splitConfig', splitConfig)
  return request.post(`/kb/${kbId}/files`, formData, { headers: { 'Content-Type': 'multipart/form-data' } })
}
export const kbListFiles = (kbId) => request.get(`/kb/${kbId}/files`)
export const kbDeleteFile = (fileId) => request.delete(`/kb/files/${fileId}`)
export const kbResplitFile = (fileId, splitStrategy, splitConfig) => {
  const params = new URLSearchParams()
  params.append('splitStrategy', splitStrategy)
  if (splitConfig) params.append('splitConfig', splitConfig)
  return request.post(`/kb/files/${fileId}/resplit?${params.toString()}`)
}
export const kbPreviewSplit = (fileId) => request.get(`/kb/files/${fileId}/preview`)
export const kbSearch = (kbId, query) => request.get(`/kb/${kbId}/search`, { params: { query } })

// 模型
export const chatModelPage = (params) => request.get('/models/chat', { params })
export const chatModelList = () => request.get('/models/chat/list')
export const chatModelGet = (id) => request.get(`/models/chat/${id}`)
export const chatModelCreate = (data) => request.post('/models/chat', data)
export const chatModelUpdate = (data) => request.put('/models/chat', data)
export const chatModelDelete = (id) => request.delete(`/models/chat/${id}`)

export const embeddingModelPage = (params) => request.get('/models/embedding', { params })
export const embeddingModelList = () => request.get('/models/embedding/list')
export const embeddingModelCreate = (data) => request.post('/models/embedding', data)
export const embeddingModelUpdate = (data) => request.put('/models/embedding', data)
export const embeddingModelDelete = (id) => request.delete(`/models/embedding/${id}`)

// 聊天窗口
export const windowPage = (params) => request.get('/chat/windows', { params })
export const windowGet = (id) => request.get(`/chat/windows/${id}`)
export const windowCreate = (data) => request.post('/chat/windows', data)
export const windowUpdate = (data) => request.put('/chat/windows', data)
export const windowDelete = (id) => request.delete(`/chat/windows/${id}`)
export const windowDisable = (id) => request.post(`/chat/windows/${id}/disable`)
export const windowEnable = (id) => request.post(`/chat/windows/${id}/enable`)
export const windowEnableShare = (id) => request.post(`/chat/windows/${id}/share/enable`)
export const windowDisableShare = (id) => request.post(`/chat/windows/${id}/share/disable`)
export const windowRegenerateShare = (id) => request.post(`/chat/windows/${id}/share/regenerate`)
export const windowGetByShareToken = (token) => request.get(`/chat/share/${token}`)
// 分享页面的匿名会话（按 sessionKey 区分）
export const shareSessions = (token, params) => request.get(`/chat/share/${token}/sessions`, { params })
export const shareCreateSession = (token, params) => request.post(`/chat/share/${token}/sessions`, null, { params })
export const shareDeleteSession = (token, sessionId, params) => request.delete(`/chat/share/${token}/sessions/${sessionId}`, { params })
export const shareMessages = (token, params) => request.get(`/chat/share/${token}/messages`, { params })

// 分页查询聊天消息
export const windowMessages = (windowId, params) => request.get(`/chat/windows/${windowId}/messages`, { params })
export const windowSessions = (windowId, params) => request.get(`/chat/windows/${windowId}/sessions`, { params })
export const windowCreateSession = (windowId, params) => request.post(`/chat/windows/${windowId}/sessions`, null, { params })
export const windowDeleteSession = (windowId, sessionId, params) => request.delete(`/chat/windows/${windowId}/sessions/${sessionId}`, { params })

// SSE 流式聊天（需要原生 fetch）
export function streamChatApi(windowId, message, sessionId) {
  const token = localStorage.getItem('wgh_token')
  const controller = new AbortController()
  
  const doStream = async (onEvent, onDone, onError) => {
    try {
      const res = await fetch(`/api/chat/windows/${windowId}/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { 'Authorization': `Bearer ${token}` } : {})
        },
        body: JSON.stringify({ message, sessionId }),
        signal: controller.signal
      })
      
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        
        buffer += decoder.decode(value, { stream: true })
        
        // SSE 格式: event:xxx\ndata:xxx\n\n
        let idx
        while ((idx = buffer.indexOf('\n\n')) !== -1) {
          const chunk = buffer.substring(0, idx)
          buffer = buffer.substring(idx + 2)
          
          const lines = chunk.split('\n')
          let eventType = 'message'
          let data = ''
          
          for (const line of lines) {
            if (line.startsWith('event:')) {
              eventType = line.substring(6).trim()
            } else if (line.startsWith('data:')) {
              // SSE 规范：多个 data 行用 \n 拼接；不能 trim，会丢行内空格
              if (data) data += '\n'
              data += line.substring(5)
            }
          }
          
          if (data) {
            try {
              const parsed = JSON.parse(data)
              onEvent(eventType, parsed)
            } catch {
              onEvent(eventType, data)
            }
          }
        }
      }
      
      onDone?.()
    } catch (err) {
      // 主动 abort / 页面刷新卸载导致的中断都视为正常结束，不回调错误（避免 ERR_ABORTED 报错）
      if (controller.signal.aborted || err.name === 'AbortError') return
      onError?.(err)
    }
  }
  
  return { doStream, abort: () => controller.abort() }
}

// 分享页面 SSE（需登录，userIdentifier 取当前登录用户名）
export function streamShareApi(shareToken, message, sessionId) {
  const token = localStorage.getItem('wgh_token')
  const controller = new AbortController()

  const doStream = async (onEvent, onDone, onError) => {
    try {
      const res = await fetch(`/api/chat/share/${shareToken}/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { 'Authorization': `Bearer ${token}` } : {})
        },
        body: JSON.stringify({ message, sessionId }),
        signal: controller.signal
      })
      
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      
      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        
        buffer += decoder.decode(value, { stream: true })
        
        let idx
        while ((idx = buffer.indexOf('\n\n')) !== -1) {
          const chunk = buffer.substring(0, idx)
          buffer = buffer.substring(idx + 2)
          
          const lines = chunk.split('\n')
          let eventType = 'message'
          let data = ''
          
          for (const line of lines) {
            if (line.startsWith('event:')) eventType = line.substring(6).trim()
            else if (line.startsWith('data:')) data += line.substring(5).trim()
          }
          
          if (data) {
            try { onEvent(eventType, JSON.parse(data)) } 
            catch { onEvent(eventType, data) }
          }
        }
      }
      onDone?.()
    } catch (err) {
      // 主动 abort / 页面刷新卸载导致的中断都视为正常结束，不回调错误
      if (controller.signal.aborted || err.name === 'AbortError') return
      onError?.(err)
    }
  }
  
  return { doStream, abort: () => controller.abort() }
}

// 工具管理
export const toolPage = (params) => request.get('/chat/tools', { params })
export const toolList = (toolType) => request.get('/chat/tools/list', { params: { toolType } })
export const toolCreate = (data) => request.post('/chat/tools', data)
export const toolUpdate = (data) => request.put('/chat/tools', data)
export const toolDelete = (id) => request.delete(`/chat/tools/${id}`)
export const toolDebug = (id, args) => request.post(`/chat/tools/${id}/debug`, { arguments: args })

// Skill 目录管理
export const skillListDirs = () => request.get('/chat/tools/skill/dirs')
export const skillReadContent = (dir) => request.get('/chat/tools/skill/content', { params: { dir } })
export const skillSaveContent = (data) => request.post('/chat/tools/skill/save', data)
export const skillCreateDir = (data) => request.post('/chat/tools/skill/create-dir', data)
export const skillDeleteDir = (dir) => request.delete('/chat/tools/skill/dir', { params: { dir } })
export const skillImport = (data) => request.post('/chat/tools/skill/import', data)

// ==================== Agent 执行控制 ====================
// 查询 plan 当前完整状态（刷新页面恢复面板）
// signal 可选：轮询时用于页面卸载前主动取消，避免 net::ERR_ABORTED
export const agentGetPlanState = (planId, signal) =>
  request.get(`/agent/plans/${planId}/state`, signal ? { signal } : undefined)
// HITL 步骤：用户输入确认后继续执行
export const agentHitlConfirm = (stepId, userInput) =>
  request.post(`/agent/steps/${stepId}/hitl-confirm`, { userInput: userInput || '' })
// 取消整个计划
export const agentCancelPlan = (planId) => request.post(`/agent/plans/${planId}/cancel`)
// 重试单个失败步骤
export const agentRetryStep = (stepId) => request.post(`/agent/steps/${stepId}/retry`)
// 跳过单个步骤
export const agentSkipStep = (stepId) => request.post(`/agent/steps/${stepId}/skip`)
// 刷新某个 plan 的状态（手动触发）
export const agentRefreshPlan = (planId) => request.get(`/agent/plans/${planId}/state`)
// 按 sessionId 查询最新 plan（页面刷新恢复面板用）
export const agentGetPlanBySession = (sessionId, signal) =>
  request.get(`/agent/sessions/${sessionId}/plan`, signal ? { signal } : undefined)
