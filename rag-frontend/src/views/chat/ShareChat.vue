<template>
  <div class="share-page">
    <div class="share-header">
      <div class="header-inner">
        <el-button class="new-session-btn" type="primary" :icon="Plus" @click="newSession">新建会话</el-button>
        <div class="header-titles">
          <h1>{{ window?.name || 'AI 助手' }}</h1>
          <p>{{ window?.description || '基于知识库的 AI 助手' }}</p>
        </div>
        <div class="header-right"></div>
      </div>
    </div>

    <div class="share-body">
      <!-- 左侧历史会话 -->
      <aside class="session-sidebar">
        <div class="session-header">历史会话</div>
        <div class="session-list">
          <div v-for="s in sessions" :key="s.id"
               :class="['session-item', { active: s.id === currentSessionId }]"
               @click="selectSession(s)">
            <div class="session-item-body">
              <div class="session-title">{{ s.title || '新对话' }}</div>
              <div class="session-meta">{{ s.messageCount || 0 }} 条 · {{ formatTime(s.updateTime || s.createTime) }}</div>
            </div>
            <el-popconfirm title="确认删除该会话及其全部消息？" width="220"
                           confirm-button-type="danger" confirm-button-text="删除" cancel-button-text="取消"
                           @confirm="deleteSession(s)">
              <template #reference>
                <el-icon class="session-delete" @click.stop><Delete /></el-icon>
              </template>
            </el-popconfirm>
          </div>
          <div v-if="!sessions.length" class="session-empty">暂无历史会话</div>
        </div>
      </aside>

      <div class="share-chat">
        <div class="messages-container" ref="messagesRef">
          <div v-if="messages.length === 0" class="welcome">
            <h2>👋 你好，我是 {{ window?.name }}</h2>
            <p>请输入你的问题，我会基于知识库为你解答</p>
          </div>
          <div v-for="(msg, idx) in messages" :key="idx"
               :class="['msg', msg.role === 'USER' ? 'user' : 'bot']">
            <div class="avatar">{{ msg.role === 'USER' ? '🙂' : '🤖' }}</div>
            <div class="bubble">
              <div v-html="renderMarkdown(msg.content)"></div>
              <div v-if="msg.streaming" class="typing">
                <span></span><span></span><span></span>
              </div>
              <div v-if="dedupSources(msg.sources).length" class="sources">
                <span v-for="(s, i) in dedupSources(msg.sources)" :key="i" class="source-tag">
                  📎 {{ s.kbName }}/{{ s.fileName }}
                </span>
              </div>
            </div>
          </div>
        </div>
        <div class="input-area">
          <el-input v-model="input" type="textarea" :rows="2"
                    placeholder="输入消息..." @keydown.enter.exact.prevent="send"
                    :disabled="streaming" resize="none" />
          <el-button type="primary" :icon="Promotion" :loading="streaming" @click="send">发送</el-button>
        </div>
      </div>

      <!-- Agent 执行计划（窗口启用 Agent 时展示，与调试页一致） -->
      <aside v-if="window?.agentEnabled === 1" class="agent-side">
        <AgentPlanTree
          :plan-id="agentPlan.planId"
          :plan-status="agentPlan.status"
          :user-goal="agentPlan.userGoal"
          :steps="agentPlan.steps"
          :active-step-id="agentPlan.activeStepId"
          :reflection-threshold="agentPlan.reflectionThreshold"
          @cancel-plan="onCancelPlan"
          @retry-step="onRetryStep"
          @skip-step="onSkipStep"
          @refresh-plan="onRefreshPlan"
          @hitl-confirm="onHitlConfirmFromTree" />
        <div class="agent-status">
          <h4>当前状态</h4>
          <div class="status-card" :class="statusInfo.tone">
            <div class="status-item">
              <span>{{ statusInfo.text }}</span>
              <el-icon v-if="statusInfo.spinning" class="rotating"><Loading /></el-icon>
            </div>
            <div v-if="statusInfo.detail" class="status-detail">{{ statusInfo.detail }}</div>
          </div>
        </div>
      </aside>
    </div>

    <div class="share-footer">
      <span>Powered by WGH RAG System</span>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Promotion, Plus, Delete, Loading } from '@element-plus/icons-vue'
import { marked } from 'marked'
import AgentPlanTree from '../../components/AgentPlanTree.vue'
import {
  windowGetByShareToken, streamShareApi,
  shareSessions, shareCreateSession, shareDeleteSession, shareMessages,
  agentGetPlanBySession, agentGetPlanState, agentCancelPlan, agentRetryStep, agentSkipStep
} from '../../api'

const route = useRoute()
const token = route.params.token
const window = ref(null)
const messages = ref([])
const sessions = ref([])
const input = ref('')
const streaming = ref(false)
const messagesRef = ref(null)
let streamer = null
let planPollTimer = null
let planAbort = null

// ===== Agent 执行计划面板状态 =====
const agentPlan = ref({
  planId: null, status: '', userGoal: '', steps: [], activeStepId: null,
  reflectionThreshold: 7.0
})

const statusInfo = computed(() => {
  const plan = agentPlan.value
  const steps = plan.steps || []
  const activeStep = steps.find(s => s.stepId === plan.activeStepId || s.status === 'RUNNING')

  if (streaming.value) {
    return {
      text: '正在处理',
      detail: activeStep ? `当前步骤：${activeStep.title}` : '',
      spinning: true, tone: 'running'
    }
  }
  switch (plan.status) {
    case 'PLANNING':
      return { text: '📝 正在规划执行步骤', spinning: true, tone: 'running' }
    case 'RUNNING': {
      const done = steps.filter(s => s.status === 'SUCCESS' || s.status === 'SKIPPED').length
      return {
        text: '⚡ Agent 执行中',
        detail: `步骤 ${done}/${steps.length}` + (activeStep ? ` · 当前：${activeStep.title}` : ''),
        spinning: true, tone: 'running'
      }
    }
    case 'PAUSED':
      return { text: '✋ 等待人工确认（HITL）', spinning: false, tone: 'paused' }
    case 'COMPLETED': {
      const ok = steps.filter(s => s.status === 'SUCCESS' || s.status === 'SKIPPED').length
      return { text: '✅ 已完成', detail: `成功 ${ok}/${steps.length} 个步骤`, spinning: false, tone: 'done' }
    }
    case 'FAILED':
      return { text: '❌ 执行失败', detail: '可在上方步骤中重试或跳过', spinning: false, tone: 'failed' }
    case 'CANCELLED':
      return { text: '⛔ 已取消', spinning: false, tone: 'paused' }
    default:
      return { text: '空闲', detail: '', spinning: false, tone: 'idle' }
  }
})

// 分享页已要求登录：会话归属取当前登录用户名，不再使用匿名 sessionKey
const currentSessionId = ref(null)

marked.setOptions({ breaks: true })

const load = async () => {
  try {
    const res = await windowGetByShareToken(token)
    window.value = res.data
    await loadSessions()
    await loadHistory()
    await restoreAgentPlan()
  } catch (e) {
    ElMessage.error('分享链接无效或已停用')
  }
}

// 加载历史会话列表
const loadSessions = async () => {
  try {
    const res = await shareSessions(token)
    sessions.value = res.data || []
    if (!currentSessionId.value && sessions.value.length) {
      currentSessionId.value = sessions.value[0].id
    }
  } catch (e) {
    console.error('加载会话列表失败', e)
  }
}

// 加载当前会话的历史消息
const loadHistory = async () => {
  try {
    const res = await shareMessages(token, {
      sessionId: currentSessionId.value || undefined,
      page: 1, size: 50
    })
    const records = res.data?.records || []
    messages.value = records.map(m => ({
      role: m.role,
      content: m.content,
      sources: m.sources ? safeParseSources(m.sources) : []
    }))
    await nextTick(scroll)
  } catch (e) {
    console.error('加载历史消息失败', e)
  }
}

const selectSession = async (s) => {
  if (streaming.value || s.id === currentSessionId.value) return
  currentSessionId.value = s.id
  messages.value = []
  await loadHistory()
  await restoreAgentPlan()
}

// 新建会话：先调后端创建，再清空消息区
const newSession = async () => {
  if (streaming.value) {
    streamer?.abort()
    streaming.value = false
  }
  try {
    const res = await shareCreateSession(token)
    const created = res.data
    currentSessionId.value = created.id
    sessions.value = [created, ...sessions.value]
    messages.value = []
    await restoreAgentPlan()
  } catch (e) {
    ElMessage.error('新建会话失败')
  }
}

const formatTime = (t) => {
  if (!t) return ''
  const d = new Date(t)
  if (isNaN(d.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  const sameDay = d.toDateString() === new Date().toDateString()
  return sameDay
    ? `${pad(d.getHours())}:${pad(d.getMinutes())}`
    : `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// 删除会话：从左侧列表移除；若删除的是当前会话，自动选中下一条
const deleteSession = async (s) => {
  try {
    await shareDeleteSession(token, s.id)
    const idx = sessions.value.findIndex(x => x.id === s.id)
    if (idx >= 0) sessions.value.splice(idx, 1)
    ElMessage.success('会话已删除')

    if (currentSessionId.value === s.id) {
      const next = sessions.value[0]
      if (next) {
        currentSessionId.value = next.id
        await loadHistory()
      } else {
        currentSessionId.value = null
        messages.value = []
      }
    }
  } catch (e) {
    ElMessage.error('删除失败: ' + (e.message || ''))
  }
}

// ===== Agent 计划恢复 / 轮询 / 事件处理 =====
const applyPlanState = (state) => {
  if (!state) return
  const steps = (state.steps || []).map(s => ({
    id: s.id,
    stepIndex: s.stepIndex,
    stepId: s.stepId,
    title: s.title,
    description: s.description,
    status: s.status,
    reflectionScore: s.reflectionScore,
    reflectionFeedback: s.reflectionFeedback,
    retryCount: s.retryCount,
    resultSummary: s.resultSummary,
    actionTool: s.actionTool || null
  }))
  agentPlan.value = {
    planId: state.planId,
    status: state.status,
    userGoal: state.userGoal || '',
    steps,
    activeStepId: steps.find(s => s.status === 'RUNNING')?.stepId || null,
    reflectionThreshold: window.value?.agentReflectionThreshold ?? 7.0
  }
}

const stopPlanPolling = () => {
  if (planPollTimer) {
    clearInterval(planPollTimer)
    planPollTimer = null
  }
  // 中断在途请求，避免刷新/切换会话时产生 net::ERR_ABORTED
  if (planAbort) {
    planAbort.abort()
    planAbort = null
  }
}

const isPlanActive = (status) => status === 'RUNNING' || status === 'PLANNING'

// 页面刷新后 SSE 已断开，用轮询持续刷新 Agent 执行进度
const startPlanPolling = () => {
  stopPlanPolling()
  planPollTimer = setInterval(async () => {
    const planId = agentPlan.value.planId
    if (!planId) { stopPlanPolling(); return }
    try {
      planAbort = new AbortController()
      const res = await agentGetPlanState(planId, planAbort.signal)
      const state = res.data
      if (!state) return
      applyPlanState(state)
      if (!isPlanActive(state.status)) {
        stopPlanPolling()
        if (!streaming.value) await loadHistory()
        loadSessions()
      }
    } catch (e) {
      // 主动取消（切换会话/刷新）不当作错误
      if (e.name !== 'CanceledError' && e.name !== 'AbortError') {
        console.warn('轮询 Agent 状态失败', e)
      }
    }
  }, 2000)
}

// 切换会话/刷新页面时恢复该会话的 Plan
const restoreAgentPlan = async () => {
  stopPlanPolling()
  if (window.value?.agentEnabled !== 1 || !currentSessionId.value) {
    agentPlan.value = {
      planId: null, status: '', userGoal: '', steps: [], activeStepId: null,
      reflectionThreshold: window.value?.agentReflectionThreshold ?? 7.0
    }
    return
  }
  try {
    planAbort = new AbortController()
    const res = await agentGetPlanBySession(currentSessionId.value, planAbort.signal)
    if (res.data) {
      applyPlanState(res.data)
      if (isPlanActive(res.data.status)) startPlanPolling()
    } else {
      agentPlan.value = {
        planId: null, status: '', userGoal: '', steps: [], activeStepId: null,
        reflectionThreshold: window.value?.agentReflectionThreshold ?? 7.0
      }
    }
  } catch (e) {
    if (e.name !== 'CanceledError' && e.name !== 'AbortError') {
      console.warn('恢复 Agent Plan 失败', e)
    }
  }
}

// 处理 Agent 专属 SSE 事件，返回 true 表示已消费
const handleAgentEvent = (event, data) => {
  switch (event) {
    case 'plan_created':
      agentPlan.value = {
        planId: data.planId,
        status: 'RUNNING',
        userGoal: data.planSummary || '',
        steps: [],
        activeStepId: null,
        reflectionThreshold: window.value?.agentReflectionThreshold ?? 7.0
      }
      return true
    case 'plan_complete':
    case 'plan_failed':
    case 'plan_cancelled':
    case 'plan_paused':
    case 'plan_resumed': {
      const statusMap = {
        plan_complete: 'COMPLETED', plan_failed: 'FAILED',
        plan_cancelled: 'CANCELLED', plan_paused: 'PAUSED', plan_resumed: 'RUNNING'
      }
      agentPlan.value.status = statusMap[event] || event
      agentPlan.value.activeStepId = null
      return true
    }
    case 'plan_updated':
      if (data.steps && Array.isArray(data.steps)) agentPlan.value.steps = data.steps
      return true
    case 'plan_create_failed':
      agentPlan.value.status = 'FAILED'
      return true
    case 'step_start': {
      const idx = agentPlan.value.steps.findIndex(s => s.stepId === data.stepId)
      const newStep = {
        stepIndex: data.stepIndex, stepId: data.stepId, title: data.stepTitle,
        status: 'RUNNING', reflectionScore: null, reflectionFeedback: '',
        retryCount: 0, resultSummary: '', actionTool: null
      }
      if (idx >= 0) agentPlan.value.steps[idx] = { ...agentPlan.value.steps[idx], ...newStep }
      else agentPlan.value.steps.push(newStep)
      agentPlan.value.activeStepId = data.stepId
      return true
    }
    case 'step_success': {
      const step = agentPlan.value.steps.find(s => s.stepId === data.stepId)
      if (step) {
        step.status = 'SUCCESS'
        step.actionTool = data.toolCount > 0 ? '已调用' : null
        step.resultSummary = step.resultSummary || '✓ 步骤执行成功'
      }
      agentPlan.value.activeStepId = null
      return true
    }
    case 'step_failed': {
      const step = agentPlan.value.steps.find(s => s.stepId === data.stepId)
      if (step) { step.status = 'FAILED'; step.resultSummary = data.reason || '❌ 步骤执行失败' }
      agentPlan.value.activeStepId = null
      return true
    }
    case 'step_hitl_pending': {
      // HITL 步骤不会先发 step_start，列表里可能还没有它，需按 payload 补建
      const idx = agentPlan.value.steps.findIndex(s => s.stepId === data.stepId)
      const hitlStep = {
        id: data.id, stepIndex: data.stepIndex, stepId: data.stepId,
        title: data.stepTitle, description: data.description || '',
        expectedInput: data.expectedInput || '', status: 'HITL_PENDING',
        reflectionScore: null, reflectionFeedback: '', retryCount: 0,
        resultSummary: '', actionTool: null
      }
      if (idx >= 0) agentPlan.value.steps[idx] = { ...agentPlan.value.steps[idx], ...hitlStep }
      else agentPlan.value.steps.push(hitlStep)
      agentPlan.value.status = 'PAUSED'
      agentPlan.value.activeStepId = data.stepId
      return true
    }
    case 'step_reflection': {
      const step = agentPlan.value.steps.find(s => s.stepId === data.stepId)
      if (step) {
        step.reflectionScore = data.score
        step.reflectionFeedback = data.feedback || ''
        step.retryCount = data.retryCount || 0
      }
      return true
    }
    case 'step_retry': {
      const step = agentPlan.value.steps.find(s => s.stepId === data.stepId)
      if (step) step.retryCount = (step.retryCount || 0) + 1
      return true
    }
    default:
      return false
  }
}

// ===== AgentPlanTree 事件 =====
const onCancelPlan = async (planId) => {
  try {
    await agentCancelPlan(planId)
    ElMessage.success('已取消执行计划')
    await onRefreshPlan(planId)
  } catch (e) {
    ElMessage.error('取消失败: ' + (e.message || ''))
  }
}

const onRetryStep = async (step) => {
  try {
    await agentRetryStep(step.id)
    await onRefreshPlan(agentPlan.value.planId)
  } catch (e) {
    ElMessage.error('重试失败: ' + (e.message || ''))
  }
}

const onSkipStep = async (step) => {
  try {
    await agentSkipStep(step.id)
    await onRefreshPlan(agentPlan.value.planId)
  } catch (e) {
    ElMessage.error('跳过失败: ' + (e.message || ''))
  }
}

const onRefreshPlan = async (planId) => {
  try {
    const res = await agentGetPlanState(planId || agentPlan.value.planId)
    applyPlanState(res.data)
  } catch (e) {
    console.warn('刷新计划失败', e)
  }
}

const onHitlConfirmFromTree = async () => {
  // AgentPlanTree 内部已调 agentHitlConfirm（同步等待恢复执行完成）
  if (agentPlan.value.planId) await onRefreshPlan(agentPlan.value.planId)
  await loadHistory()
  if (isPlanActive(agentPlan.value.status)) startPlanPolling()
}

const send = async () => {
  const text = input.value.trim()
  if (!text || streaming.value || !window.value) return
  input.value = ''

  // 新一轮对话：清空上一轮 Agent 面板状态
  stopPlanPolling()
  agentPlan.value = {
    planId: null, status: '', userGoal: '', steps: [], activeStepId: null,
    reflectionThreshold: window.value?.agentReflectionThreshold ?? 7.0
  }
  
  messages.value.push({ role: 'USER', content: text })
  const aiMsg = { role: 'ASSISTANT', content: '', streaming: true, sources: [] }
  messages.value.push(aiMsg)
  await scroll()
  streaming.value = true

  streamer = streamShareApi(token, text, currentSessionId.value)
  streamer.doStream(
    (event, data) => {
      // 后端 JSON 包装 {text}，兼容纯文本
      if (event === 'start') {
        if (data?.sessionId) currentSessionId.value = data.sessionId
      } else if (handleAgentEvent(event, data)) {
        // Agent 专属事件已消费
      } else if (event === 'content') aiMsg.content += (data && typeof data === 'object' ? (data.text ?? '') : data)
      else if (event === 'sources') aiMsg.sources = data
      else if (event === 'done') {
        streaming.value = false
        aiMsg.streaming = false
        if (data?.sessionId) currentSessionId.value = data.sessionId
        // Agent 路线不逐 token 推送 content，只下发 fullResponse，这里兜底填充
        if (!aiMsg.content && data?.fullResponse) aiMsg.content = data.fullResponse
        loadSessions()
      }
      nextTick(scroll)
    },
    () => { streaming.value = false; aiMsg.streaming = false },
    (err) => { streaming.value = false; aiMsg.streaming = false; aiMsg.content += '\n⚠️ ' + err.message }
  )
}

const scroll = () => nextTick(() => { if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight })
// marked v14 默认异步返回 Promise，需显式同步解析供 v-html 使用
const renderMarkdown = (t) => marked.parse(t || '', { async: false })

const safeParseSources = (json) => {
  try { return JSON.parse(json) || [] } catch { return [] }
}

// 按 fileName 去重，保留 score 最高的一条
const dedupSources = (list) => {
  if (!Array.isArray(list)) return []
  const byFile = new Map()
  for (const s of list) {
    const key = s.fileName || s.kbName || JSON.stringify(s)
    const prev = byFile.get(key)
    if (!prev || (s.score ?? 0) > (prev.score ?? 0)) byFile.set(key, s)
  }
  return Array.from(byFile.values())
}

// 刷新/关闭页面前主动中断 SSE 与轮询，避免浏览器记录 net::ERR_ABORTED
const handleBeforeUnload = () => {
  streamer?.abort()
  stopPlanPolling()
}

onMounted(() => {
  // 注意：本组件内 window 是响应式 ref，需用 globalThis 访问浏览器 window
  globalThis.addEventListener('beforeunload', handleBeforeUnload)
  load()
})
onUnmounted(() => {
  globalThis.removeEventListener('beforeunload', handleBeforeUnload)
  streamer?.abort()
  stopPlanPolling()
})
</script>

<style scoped lang="scss">
.share-page {
  height: 100vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, #f5f7fa 0%, #fff 100%);
}
.share-header {
  padding: 12px 0;
}
/* 头部与下方内容区共用同一套居中容器宽度，避免超宽屏下按钮与内容错位 */
.header-inner {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 12px;
  width: 100%;
  max-width: 1400px;
  margin: 0 auto;
  padding: 8px 24px;
  .header-titles { text-align: center; }
  h1 { margin: 0; font-size: 24px; color: #303133; }
  p { margin: 4px 0 0; color: #909399; font-size: 13px; }
}
.new-session-btn {
  justify-self: start;
}
.share-body {
  flex: 1;
  min-height: 0;
  display: flex;
  gap: 16px;
  width: 100%;
  max-width: 1400px;
  margin: 0 auto;
  padding: 0 24px 16px;
}
.session-sidebar {
  width: 200px;
  flex-shrink: 0;
  min-height: 0;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.04);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.session-header {
  padding: 14px 16px;
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  border-bottom: 1px solid #ebeef5;
}
.session-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}
.session-item {
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
  transition: background .15s;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 6px;

  &:hover {
    background: #f5f7fa;
    .session-delete { opacity: 1; }
  }
  &.active {
    background: #ecf5ff;
    .session-title { color: #409eff; }
  }
}
.session-item-body {
  flex: 1;
  min-width: 0;
}
.session-delete {
  flex-shrink: 0;
  margin-top: 2px;
  color: #c0c4cc;
  font-size: 14px;
  opacity: 0;
  transition: opacity .15s, color .15s;
  &:hover { color: #f56c6c; }
}
.session-title {
  font-size: 13px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-meta {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
.session-empty {
  padding: 16px;
  text-align: center;
  font-size: 13px;
  color: #c0c4cc;
}
.share-chat {
  flex: 1;
  min-width: 0;
  min-height: 0;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0,0,0,0.06);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
/* Agent 执行计划右侧栏（与调试页保持一致） */
.agent-side {
  width: 320px;
  flex-shrink: 0;
  min-height: 0;
  overflow-y: auto;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 4px 20px rgba(0,0,0,0.06);
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;

  .agent-status {
    h4 { margin: 0 0 8px; font-size: 13px; color: #303133; }
    .status-card {
      padding: 12px;
      background: #ecf5ff;
      border-radius: 6px;
      .status-item {
        display: flex; justify-content: space-between; align-items: center;
        font-size: 13px;
      }
      .status-detail { margin-top: 6px; font-size: 12px; color: #606266; line-height: 1.5; }
      .rotating { animation: rotate 1s linear infinite; }
      &.idle { background: #f5f7fa; }
      &.done { background: #f0f9eb; }
      &.failed { background: #fef0f0; }
      &.paused { background: #fdf6ec; }
    }
  }
}
@keyframes rotate { to { transform: rotate(360deg); } }
.messages-container {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px;
}
.welcome { text-align: center; padding: 40px; color: #606266; }
.msg {
  display: flex; gap: 10px; margin-bottom: 16px;
  &.user { flex-direction: row-reverse; .bubble { background: #409eff; color: #fff; border-radius: 12px 2px 12px 12px; } }
  &.bot { .bubble { background: #f5f7fa; border-radius: 2px 12px 12px 12px; } }
}
.avatar {
  width: 36px; height: 36px; border-radius: 50%;
  background: #ecf5ff; display: flex; align-items: center; justify-content: center;
  flex-shrink: 0; font-size: 18px;
}
.bubble {
  max-width: 75%;
  padding: 10px 14px;
  line-height: 1.7;
  font-size: 14px;
  word-break: break-word;
}
.sources {
  margin-top: 8px;
  display: flex; flex-wrap: wrap; gap: 4px;
  .source-tag {
    font-size: 11px; color: #909399;
    background: rgba(0,0,0,0.04); padding: 2px 6px; border-radius: 3px;
  }
}
.input-area {
  display: flex; gap: 10px; padding: 14px;
  border-top: 1px solid #ebeef5;
  align-items: flex-end;
}
.share-footer {
  text-align: center;
  padding: 12px;
  color: #c0c4cc;
  font-size: 12px;
}
@media (max-width: 768px) {
  .header-inner {
    grid-template-columns: auto 1fr;
    padding: 6px 12px;
    .header-titles { text-align: left; }
    h1 { font-size: 18px; }
  }
  .header-right { display: none; }
  .share-body {
    flex-direction: column;
    padding: 0 12px 12px;
    gap: 12px;
  }
  .session-sidebar {
    width: 100%;
    max-height: 132px;
  }
  .agent-side {
    width: 100%;
    max-height: 320px;
  }
  .messages-container { padding: 12px; }
  .bubble { max-width: 85%; }
}
.typing { display: flex; gap: 4px;
  span { width: 8px; height: 8px; background: #909399; border-radius: 50%; animation: blink 1.4s infinite both; }
  span:nth-child(2) { animation-delay: 0.2s; }
  span:nth-child(3) { animation-delay: 0.4s; }
}
@keyframes blink {
  0%, 80%, 100% { opacity: 0.2; }
  40% { opacity: 1; }
}
</style>
