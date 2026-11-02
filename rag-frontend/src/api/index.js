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
export const kbSearch = (kbId, query, rerankModelId, hybrid, queryTransformMode, rewriteModelId) => request.get(`/kb/${kbId}/search`, { params: { query, rerankModelId: rerankModelId || undefined, hybrid: hybrid || undefined, queryTransformMode: queryTransformMode && queryTransformMode !== 'NONE' ? queryTransformMode : undefined, rewriteModelId: rewriteModelId || undefined } })

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

export const rerankModelPage = (params) => request.get('/models/rerank', { params })
export const rerankModelList = () => request.get('/models/rerank/list')
export const rerankModelGet = (id) => request.get(`/models/rerank/${id}`)
export const rerankModelCreate = (data) => request.post('/models/rerank', data)
export const rerankModelUpdate = (data) => request.put('/models/rerank', data)
export const rerankModelDelete = (id) => request.delete(`/models/rerank/${id}`)

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
// 反馈闭环
export const submitMessageFeedback = (id, data) => request.post(`/chat/messages/${id}/feedback`, data)
export const windowFeedbackStats = (windowId) => request.get(`/chat/windows/${windowId}/feedback/stats`)
export const chatUploadImage = (file) => {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/chat/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } })
}

// Prompt 模板中心
export const promptTemplatePage = (params) => request.get('/prompt-templates', { params })
export const promptTemplateList = () => request.get('/prompt-templates/list')
export const promptTemplateGet = (id) => request.get(`/prompt-templates/${id}`)
export const promptTemplateCreate = (data) => request.post('/prompt-templates', data)
export const promptTemplateUpdate = (data) => request.put('/prompt-templates', data)
export const promptTemplateDelete = (id) => request.delete(`/prompt-templates/${id}`)
export const promptTemplateRender = (id, vars) => request.post(`/prompt-templates/${id}/render`, { vars })

// SSE 流式聊天（需要原生 fetch）
export function streamChatApi(windowId, message, sessionId, imageUrls) {
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
        body: JSON.stringify({ message, sessionId, ...(imageUrls?.length ? { imageUrls } : {}) }),
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

// ==================== 工作流编排 ====================
export const workflowPage = (params) => request.get('/workflows', { params })
export const workflowList = () => request.get('/workflows/list')
export const workflowGet = (id) => request.get(`/workflows/${id}`)
export const workflowCreate = (data) => request.post('/workflows', data)
export const workflowUpdate = (id, data) => request.put(`/workflows/${id}`, data)
export const workflowDelete = (id) => request.delete(`/workflows/${id}`)
export const workflowValidate = (id) => request.post(`/workflows/${id}/validate`)
export const workflowEnable = (id) => request.post(`/workflows/${id}/enable`)
export const workflowDisable = (id) => request.post(`/workflows/${id}/disable`)
export const workflowPageRuns = (id, params) => request.get(`/workflows/${id}/runs`, { params })
export const workflowRunState = (runId, signal) =>
  request.get(`/workflows/runs/${runId}/state`, signal ? { signal } : undefined)
export const workflowRunNodes = (runId) => request.get(`/workflows/runs/${runId}/nodes`)
export const workflowCancelRun = (runId) => request.post(`/workflows/runs/${runId}/cancel`)
export const workflowVersions = (id) => request.get(`/workflows/${id}/versions`)
export const workflowRollback = (id, version) => request.post(`/workflows/${id}/rollback/${version}`)
export const workflowTriggers = (id) => request.get(`/workflows/${id}/triggers`)
export const workflowTriggerCreate = (id, data) => request.post(`/workflows/${id}/triggers`, data)
export const workflowTriggerEnable = (tid) => request.post(`/workflows/triggers/${tid}/enable`)
export const workflowTriggerDisable = (tid) => request.post(`/workflows/triggers/${tid}/disable`)
export const workflowTriggerDelete = (tid) => request.delete(`/workflows/triggers/${tid}`)
export const workflowTemplates = () => request.get('/workflows/templates')
export const workflowInstantiate = (templateId, name) =>
  request.post(`/workflows/templates/${templateId}/instantiate`, { name })

/** 通用 SSE 读取（POST JSON） */
function streamPost(url, body, onEvent, onDone, onError) {
  const token = localStorage.getItem('wgh_token')
  const controller = new AbortController()

  const doStream = async () => {
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {})
        },
        body: JSON.stringify(body || {}),
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

          let eventType = 'message'
          let data = ''
          for (const line of chunk.split('\n')) {
            if (line.startsWith('event:')) eventType = line.substring(6).trim()
            else if (line.startsWith('data:')) {
              if (data) data += '\n'
              data += line.substring(5)
            }
          }
          if (data) {
            try {
              onEvent(eventType, JSON.parse(data))
            } catch {
              onEvent(eventType, data)
            }
          }
        }
      }
      onDone?.()
    } catch (err) {
      if (controller.signal.aborted || err.name === 'AbortError') return
      onError?.(err)
    }
  }

  return { doStream, abort: () => controller.abort() }
}

/** 运行工作流（SSE） */
export function streamWorkflowRun(workflowId, input, onEvent, onDone, onError) {
  return streamPost(`/api/workflows/${workflowId}/run`, { input }, onEvent, onDone, onError)
}

/** HITL 确认后继续运行（SSE） */
export function streamWorkflowResume(runId, userInput, onEvent, onDone, onError) {
  return streamPost(`/api/workflows/runs/${runId}/hitl-confirm`, { userInput }, onEvent, onDone, onError)
}

// ==================== 聊天窗口工作流运行（阶段 A） ====================

/** 查询会话最近一次工作流运行状态（调试页刷新恢复） */
export const chatWorkflowState = (windowId, sessionId, signal) =>
  request.get(`/chat/windows/${windowId}/sessions/${sessionId}/workflow-state`, signal ? { signal } : undefined)

/** 调试页工作流 HITL 确认（SSE） */
export function streamChatWorkflowHitl(windowId, runId, sessionId, userInput, onEvent, onDone, onError) {
  return streamPost(`/api/chat/windows/${windowId}/workflow-runs/${runId}/hitl-confirm`,
    { sessionId, userInput }, onEvent, onDone, onError)
}

/** 调试页取消工作流运行 */
export const chatWorkflowCancel = (windowId, runId) =>
  request.post(`/chat/windows/${windowId}/workflow-runs/${runId}/cancel`)

/** 分享页：查询会话最近一次工作流运行状态 */
export const shareWorkflowState = (shareToken, sessionId, signal) =>
  request.get(`/chat/share/${shareToken}/sessions/${sessionId}/workflow-state`, signal ? { signal } : undefined)

/** 分享页工作流 HITL 确认（安全模式，SSE） */
export function streamShareWorkflowHitl(shareToken, runId, sessionId, userInput, onEvent, onDone, onError) {
  return streamPost(`/api/chat/share/${shareToken}/workflow-runs/${runId}/hitl-confirm`,
    { sessionId, userInput }, onEvent, onDone, onError)
}

/** 分享页取消工作流运行 */
export const shareWorkflowCancel = (shareToken, runId, params) =>
  request.post(`/chat/share/${shareToken}/workflow-runs/${runId}/cancel`, null, { params })

// ==================== 工作流分享运行页（阶段 B） ====================

/** 管理端：开启分享运行页 */
export const workflowShareEnable = (id) => request.post(`/workflows/${id}/share/enable`)

/** 管理端：关闭分享运行页 */
export const workflowShareDisable = (id) => request.post(`/workflows/${id}/share/disable`)

/** 管理端：重新生成分享链接（旧链接立即失效） */
export const workflowShareRegenerate = (id) => request.post(`/workflows/${id}/share/regenerate`)

/** 分享运行页：工作流元信息 */
export const workflowShareInfo = (shareToken) => request.get(`/workflows/share/${shareToken}`)

/** 分享运行页：SSE 运行（safeMode 只读工具白名单） */
export function streamWorkflowShareRun(shareToken, input, onEvent, onDone, onError) {
  return streamPost(`/api/workflows/share/${shareToken}/run`, { input }, onEvent, onDone, onError)
}

/** 分享运行页：查询运行状态（刷新恢复） */
export const workflowShareRunState = (shareToken, runId, signal) =>
  request.get(`/workflows/share/${shareToken}/runs/${runId}/state`, signal ? { signal } : undefined)

/** 分享运行页：HITL 人工确认并续跑（SSE） */
export function streamWorkflowShareHitl(shareToken, runId, userInput, onEvent, onDone, onError) {
  return streamPost(`/api/workflows/share/${shareToken}/runs/${runId}/hitl-confirm`,
    { userInput }, onEvent, onDone, onError)
}

/** 分享运行页：取消运行 */
export const workflowShareCancel = (shareToken, runId) =>
  request.post(`/workflows/share/${shareToken}/runs/${runId}/cancel`)

// ==================== 多 Agent 协作 ====================

// Crew（可复用多 Agent 配置模板）
export const multiAgentCrewList = () => request.get('/agent-multi/crews')
export const multiAgentCrewGet = (id) => request.get(`/agent-multi/crews/${id}`)
export const multiAgentCrewCreate = (data) => request.post('/agent-multi/crews', data)
export const multiAgentCrewUpdate = (id, data) => request.put(`/agent-multi/crews/${id}`, data)
export const multiAgentCrewDelete = (id) => request.delete(`/agent-multi/crews/${id}`)
export const multiAgentCrewValidate = (id) => request.post(`/agent-multi/crews/${id}/validate`)

// 运行控制
export const multiAgentCreateRun = (data) => request.post('/agent-multi/runs', data)
export const multiAgentRunState = (runId, signal) =>
  request.get(`/agent-multi/runs/${runId}/state`, signal ? { signal } : undefined)
export const multiAgentRunSteps = (runId) => request.get(`/agent-multi/runs/${runId}/steps`)
export const multiAgentCancelRun = (runId) => request.post(`/agent-multi/runs/${runId}/cancel`)

/** 通用 SSE 读取（GET，带 JWT） */
function streamGetRaw(url, onEvent, onDone, onError) {
  const token = localStorage.getItem('wgh_token')
  const controller = new AbortController()

  const doStream = async () => {
    try {
      const res = await fetch(url, {
        method: 'GET',
        headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}) },
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

          let eventType = 'message'
          let data = ''
          for (const line of chunk.split('\n')) {
            if (line.startsWith('event:')) eventType = line.substring(6).trim()
            else if (line.startsWith('data:')) {
              if (data) data += '\n'
              data += line.substring(5)
            }
          }
          if (data) {
            try {
              onEvent(eventType, JSON.parse(data))
            } catch {
              onEvent(eventType, data)
            }
          }
        }
      }
      onDone?.()
    } catch (err) {
      if (controller.signal.aborted || err.name === 'AbortError') return
      onError?.(err)
    }
  }

  return { doStream, abort: () => controller.abort() }
}

/** 启动多 Agent 运行（SSE，GET） */
export function streamMultiAgentRun(runId, onEvent, onDone, onError) {
  return streamGetRaw(`/api/agent-multi/runs/${runId}/stream`, onEvent, onDone, onError)
}

/** 多 Agent HITL 确认后续跑（SSE，POST） */
export function streamMultiAgentHitl(runId, userInput, onEvent, onDone, onError) {
  return streamPost(`/api/agent-multi/runs/${runId}/hitl-confirm`, { userInput }, onEvent, onDone, onError)
}


// ===== 管理端：审计 / 配额 / 成本 =====
export const adminAuditLogs = (params) => request.get('/admin/audit-logs', { params })
export const adminCostStats = (params) => request.get('/admin/stats/cost', { params })
export const adminQuotaStats = () => request.get('/admin/stats/quota')
export const adminFeedbackDislikes = (params) => request.get('/admin/feedback/dislikes', { params })
export const modelPricePage = (params) => request.get('/admin/model-prices', { params })
export const modelPriceCreate = (data) => request.post('/admin/model-prices', data)
export const modelPriceUpdate = (data) => request.put('/admin/model-prices', data)
export const modelPriceDelete = (id) => request.delete(`/admin/model-prices/${id}`)

// ===== 标签 / 知识库版本 =====
export const tagPage = (params) => request.get('/tags', { params })
export const tagList = () => request.get('/tags/list')
export const tagCreate = (data) => request.post('/tags', data)
export const tagUpdate = (data) => request.put('/tags', data)
export const tagDelete = (id) => request.delete(`/tags/${id}`)
export const kbTags = (kbId) => request.get(`/kb/${kbId}/tags`)
export const kbSetTags = (kbId, tagIds) => request.put(`/kb/${kbId}/tags`, { tagIds })
export const kbFileTags = (fileId) => request.get(`/kb/files/${fileId}/tags`)
export const kbFileSetTags = (fileId, tagIds) => request.put(`/kb/files/${fileId}/tags`, { tagIds })
export const kbFileVersions = (fileId) => request.get(`/kb/files/${fileId}/versions`)
export const kbFileRollback = (fileId, version) => request.post(`/kb/files/${fileId}/rollback/${version}`)
