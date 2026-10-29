<template>
  <div class="page-container">
    <div class="page-header">
      <div style="display:flex;gap:12px;align-items:center">
        <el-button :icon="ArrowLeft" circle @click="$router.back()" />
        <h2>{{ window?.name }} - 调试对话</h2>
        <el-button type="primary" :icon="Plus" @click="newSession">新建会话</el-button>
      </div>
      <div style="display:flex;gap:8px">
        <el-button :icon="Connection" @click="openMultiAgent">多 Agent 协作</el-button>
        <el-button v-if="window?.shareToken" :icon="Link" @click="copyShareLink">复制分享链接</el-button>
      </div>
    </div>

    <div class="chat-layout">
      <!-- 左侧历史会话 -->
      <div class="session-sidebar">
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
                           @confirm="deleteSession(s, $event)">
              <template #reference>
                <el-icon class="session-delete" @click.stop><Delete /></el-icon>
              </template>
            </el-popconfirm>
          </div>
          <div v-if="!sessions.length" class="session-empty">暂无历史会话</div>
        </div>
      </div>

      <!-- 左侧聊天区域 -->
      <div class="chat-main">
        <div class="messages-container" ref="messagesRef" @scroll="handleScroll">
          <!-- 历史消息加载更多提示 -->
          <div v-if="historyLoading" class="load-more-tip">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>加载历史消息中...</span>
          </div>
          <div v-else-if="!noMoreHistory && messages.length > 0" class="load-more-tip" @click="loadMoreHistory">
            <span>↑ 点击加载更多历史消息</span>
          </div>

          <!-- 欢迎 -->
          <div v-if="messages.length === 0" class="welcome">
            <h1>👋 你好，我是 {{ window?.name }}</h1>
            <p>基于知识库的 AI 助手，你可以向我提问关于知识库内容的问题。</p>
          </div>

          <div v-for="(msg, idx) in messages" :key="idx" 
               :class="['message', msg.role === 'USER' ? 'user-msg' : 'assistant-msg']">
            <div class="avatar">{{ msg.role === 'USER' ? '🙂' : '🤖' }}</div>
            <div class="bubble">
              <div class="msg-content" v-html="renderMarkdown(msg.content)"></div>
              <div v-if="msg.streaming" class="typing">
                <span></span><span></span><span></span>
              </div>
              
              <!-- 指标展示（AI消息下方） -->
              <div v-if="msg.role === 'ASSISTANT' && msg.metrics" class="msg-metrics">
                <el-tag size="small" type="info" v-if="msg.metrics.recallRate != null">
                  召回率 {{ (msg.metrics.recallRate * 100).toFixed(1) }}%
                </el-tag>
                <el-tag size="small" type="warning" v-if="msg.metrics.hitRate != null">
                  命中率 {{ (msg.metrics.hitRate * 100).toFixed(1) }}%
                </el-tag>
                <el-tag size="small" type="success" v-if="msg.metrics.accuracyRate != null">
                  正确率 {{ (msg.metrics.accuracyRate * 100).toFixed(1) }}%
                </el-tag>
                <el-tag size="small" type="primary" v-if="msg.metrics.mrr != null">
                  MRR {{ (msg.metrics.mrr * 100).toFixed(1) }}%
                </el-tag>
                <el-tag size="small" type="primary" v-if="msg.metrics.ndcg != null">
                  NDCG {{ (msg.metrics.ndcg * 100).toFixed(1) }}%
                </el-tag>
                <el-tooltip v-if="msg.metrics.faithfulness != null" placement="top" :content="judgeTooltip(msg)">
                  <el-tag size="small" type="danger">
                    忠实度 {{ (msg.metrics.faithfulness * 100).toFixed(1) }}%
                  </el-tag>
                </el-tooltip>
                <el-tag size="small" type="danger" v-if="msg.metrics.answerRelevancy != null">
                  相关性 {{ (msg.metrics.answerRelevancy * 100).toFixed(1) }}%
                </el-tag>
                <el-tag size="small" type="info" v-if="msg.metrics.judgeAgreement != null">
                  Judge一致性 {{ (msg.metrics.judgeAgreement * 100).toFixed(1) }}%
                </el-tag>
              </div>
              
              <!-- 工具调用 -->
              <div v-if="msg.role === 'ASSISTANT' && msg.tools?.length" class="msg-sources">
                <div class="sources-title">🔧 工具调用：</div>
                <div v-for="(t, i) in msg.tools" :key="i" class="source-item">
                  <el-icon><Tools /></el-icon>
                  <span>{{ t.name }}</span>
                  <el-tag size="small" :type="t.success === false ? 'danger' : t.success === true ? 'success' : 'info'">
                    {{ t.success === undefined ? '调用中' : t.success ? '成功' : '失败' }}
                  </el-tag>
                  <span v-if="t.latencyMs != null" style="color:#c0c4cc">· {{ t.latencyMs }}ms</span>
                </div>
              </div>

              <!-- 来源引用 -->
              <div v-if="msg.role === 'ASSISTANT' && msg.sources?.length" class="msg-sources">
                <div class="sources-title">📎 信息来源：</div>
                <div v-for="(s, i) in msg.sources" :key="i" class="source-item">
                  <el-icon><Document /></el-icon>
                  <span>{{ s.kbName }}</span>
                  <span style="color:#c0c4cc">· {{ s.fileName }}</span>
                  <el-tag v-if="s.score" size="small" type="info">{{ (s.score * 100).toFixed(0) }}%</el-tag>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div class="input-area">
          <el-input v-model="input" type="textarea" :rows="2"
                    placeholder="输入消息，Ctrl+Enter 发送"
                    @keydown.ctrl.enter="send"
                    :disabled="streaming" />
          <el-button type="primary" :icon="Promotion" :loading="streaming" @click="send">
            发送
          </el-button>
        </div>
      </div>

      <!-- 右侧信息面板 -->
      <div class="chat-info">
        <!-- Agent 计划树（当窗口启用 Agent 时） -->
        <AgentPlanTree
          v-if="window?.agentEnabled === 1"
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
          @hitl-confirm="onHitlConfirmFromTree"
        />

        <!-- 普通 RAG 会话信息 -->
        <template v-if="window?.agentEnabled !== 1">
          <h3>会话信息</h3>
          <el-descriptions :column="1" border v-if="window">
            <el-descriptions-item label="窗口">{{ window.name }}</el-descriptions-item>
            <el-descriptions-item label="知识库">
              <span v-if="parseKbs(window.knowledgeBaseIds).length">
                已关联 {{ parseKbs(window.knowledgeBaseIds).length }} 个
              </span>
              <span v-else style="color:#c0c4cc">未关联</span>
            </el-descriptions-item>
            <el-descriptions-item label="分享">
              <el-tag :type="window.shareEnabled ? 'success' : 'info'">
                {{ window.shareEnabled ? '已开启' : '未开启' }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </template>

        <!-- Agent 启用状态标识 -->
        <div v-if="window?.agentEnabled === 1 && !agentPlan.planId" class="agent-banner">
          🤖 本窗口已启用 Agent 自动规划执行<br/>
          <span class="banner-tip">发送消息后 Agent 会自动拆解任务并规划执行步骤</span>
        </div>

        <h3 style="margin-top:16px">当前状态</h3>
        <div class="status-card" :class="statusInfo.tone">
          <div class="status-item">
            <span class="label">{{ statusInfo.text }}</span>
            <el-icon v-if="statusInfo.spinning" class="rotating"><Loading /></el-icon>
          </div>
          <div v-if="statusInfo.detail" class="status-detail">{{ statusInfo.detail }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Link, Promotion, Plus, Document, Loading, Tools, Delete, Connection } from '@element-plus/icons-vue'
import { marked } from 'marked'
import { windowGet, streamChatApi, windowMessages, windowSessions, windowCreateSession, windowDeleteSession,
  agentGetPlanState, agentCancelPlan, agentRetryStep, agentSkipStep, agentGetPlanBySession } from '../../api'
import AgentPlanTree from '../../components/AgentPlanTree.vue'

const route = useRoute()
const router = useRouter()
const windowId = route.params.id
const window = ref(null)
const messages = ref([])
const sessions = ref([])
const input = ref('')
const streaming = ref(false)
const currentSessionId = ref(null)
const messagesRef = ref(null)
let streamer = null
let planPollTimer = null
let planAbort = null

// ============ Agent 计划状态 ============
const agentPlan = ref({
  planId: null,
  status: '',
  userGoal: '',
  steps: [],
  activeStepId: null,
  reflectionThreshold: 7.0
})

// 右侧「当前状态」栏的文案与样式（无内容时也要有兜底展示）
const statusInfo = computed(() => {
  const plan = agentPlan.value
  const steps = plan.steps || []
  const activeStep = steps.find(s => s.stepId === plan.activeStepId || s.status === 'RUNNING')

  if (streaming.value) {
    return {
      text: '正在处理',
      detail: activeStep ? `当前步骤：${activeStep.title}` : '',
      spinning: true,
      tone: 'running'
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
        spinning: true,
        tone: 'running'
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

// 历史消息分页状态
const historyPage = ref(1)
const historyPageSize = 20
const historyTotal = ref(0)
const historyLoading = ref(false)
const noMoreHistory = ref(false)

marked.setOptions({ breaks: true })

const parseKbs = (json) => {
  try { return JSON.parse(json || '[]') } catch { return [] }
}

// 按 fileName 去重，保留 score 最高的一条（历史消息与实时消息共用）
const dedupeSources = (sources) => {
  if (!Array.isArray(sources)) return []
  const byFile = new Map()
  for (const s of sources) {
    const key = s.fileName || s.kbName || JSON.stringify(s)
    const prev = byFile.get(key)
    if (!prev || (s.score ?? 0) > (prev.score ?? 0)) byFile.set(key, s)
  }
  return Array.from(byFile.values())
}

// 历史消息 → 视图消息（metrics 抽取）
const toViewMessage = (m) => {
  const msg = {
    role: m.role,
    content: m.content,
    sources: m.sources ? dedupeSources(JSON.parse(m.sources)) : [],
    judgeDetails: m.judgeDetails ? safeParse(m.judgeDetails) : null
  }
  const metricsKeys = ['recallRate', 'hitRate', 'accuracyRate', 'mrr', 'ndcg', 'faithfulness', 'answerRelevancy', 'judgeAgreement']
  const metrics = {}
  let hasAny = false
  for (const k of metricsKeys) {
    if (m[k] != null) { metrics[k] = m[k]; hasAny = true }
  }
  if (hasAny) msg.metrics = metrics
  return msg
}

const safeParse = (json) => {
  try { return JSON.parse(json) } catch { return null }
}

// Judge tooltip 展示每个 judge 的模型、分数与理由
const judgeTooltip = (msg) => {
  if (!msg.judgeDetails || !Array.isArray(msg.judgeDetails)) return 'LLM-as-Judge 评分'
  const lines = msg.judgeDetails.map(d => {
    if (d.error) return `${d.modelName}: 失败 - ${d.error}`
    const faith = d.faithfulness != null ? (d.faithfulness * 100).toFixed(0) + '%' : '-'
    const rel = d.answerRelevancy != null ? (d.answerRelevancy * 100).toFixed(0) + '%' : '-'
    return `${d.modelName} (${d.provider}): 忠实度 ${faith} / 相关性 ${rel}${d.reason ? '\n  ' + d.reason : ''}`
  })
  return lines.join('\n')
}

const load = async () => {
  const res = await windowGet(windowId)
  window.value = res.data
  await loadSessions()
  await loadHistory()
  await restoreAgentPlan()
}

// 加载左侧历史会话列表
const loadSessions = async () => {
  try {
    const res = await windowSessions(windowId)
    sessions.value = res.data || []
    // 首次进入默认选中最近一次会话
    if (!currentSessionId.value && sessions.value.length) {
      currentSessionId.value = sessions.value[0].id
    }
  } catch (e) {
    console.error('加载会话列表失败', e)
  }
}

// 切换会话
const selectSession = async (s) => {
  if (streaming.value || s.id === currentSessionId.value) return
  currentSessionId.value = s.id
  messages.value = []
  await loadHistory()
  await restoreAgentPlan()
}

// 新建会话：调用后端创建，选中后清空消息区
const newSession = async () => {
  if (streaming.value) {
    streamer?.abort()
    streaming.value = false
  }
  try {
    const res = await windowCreateSession(windowId)
    const created = res.data
    currentSessionId.value = created.id
    sessions.value = [created, ...sessions.value]
    messages.value = []
    historyPage.value = 1
    historyTotal.value = 0
    noMoreHistory.value = false
  } catch (e) {
    ElMessage.error('新建会话失败')
  }
}

const formatTime = (t) => {
  if (!t) return ''
  const d = new Date(t)
  if (isNaN(d.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  return sameDay
    ? `${pad(d.getHours())}:${pad(d.getMinutes())}`
    : `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

// 删除会话：清空消息区 + 从左侧列表移除；若删除的是当前会话，自动选中下一条
const deleteSession = async (s) => {
  try {
    await windowDeleteSession(windowId, s.id)
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
        historyPage.value = 1
        historyTotal.value = 0
        noMoreHistory.value = false
      }
    }
  } catch (e) {
    ElMessage.error('删除失败: ' + (e.message || ''))
  }
}

// 加载历史消息（第一页）
const loadHistory = async () => {
  try {
    const res = await windowMessages(windowId, {
      page: 1, size: historyPageSize,
      sessionId: currentSessionId.value || undefined
    })
    const data = res.data
    historyTotal.value = data.total
    historyPage.value = 1
    noMoreHistory.value = data.records.length < historyPageSize

    // 转换为消息格式并反转（后端返回的是倒序）
    messages.value = data.records.map(toViewMessage)
    await nextTick(scrollToBottom)
  } catch (e) {
    console.error('加载历史消息失败', e)
  }
}

// 滚动加载更多历史消息
const loadMoreHistory = async () => {
  if (historyLoading.value || noMoreHistory.value) return

  historyLoading.value = true
  try {
    const nextPage = historyPage.value + 1
    const res = await windowMessages(windowId, {
      page: nextPage, size: historyPageSize,
      sessionId: currentSessionId.value || undefined
    })
    const data = res.data

    if (data.records.length > 0) {
      // 将旧消息插入到列表前面
      const olderMessages = data.records.map(toViewMessage)
      messages.value = [...olderMessages, ...messages.value]
      historyPage.value = nextPage
      noMoreHistory.value = data.records.length < historyPageSize
    } else {
      noMoreHistory.value = true
    }
  } catch (e) {
    console.error('加载更多历史消息失败', e)
  } finally {
    historyLoading.value = false
  }
}

const send = async () => {
  const text = input.value.trim()
  if (!text || streaming.value) return
  input.value = ''

  // 重置 Agent 计划状态（新对话）
  stopPlanPolling()
  agentPlan.value = {
    planId: null, status: '', userGoal: '', steps: [], activeStepId: null,
    reflectionThreshold: window.value?.agentReflectionThreshold ?? 7.0
  }

  // 添加用户消息
  messages.value.push({ role: 'USER', content: text })
  
  // 添加空 AI 消息（等待流式填充）
  const aiMsg = { role: 'ASSISTANT', content: '', streaming: true, metrics: null, sources: [], tools: [] }
  messages.value.push(aiMsg)
  
  await scrollToBottom()
  streaming.value = true

  streamer = streamChatApi(windowId, text, currentSessionId.value)
  
  streamer.doStream(
    (event, data) => handleEvent(event, data, aiMsg),
    () => {
      streaming.value = false
      aiMsg.streaming = false
      nextTick(scrollToBottom)
    },
    (err) => {
      streaming.value = false
      aiMsg.streaming = false
      aiMsg.content = '抱歉，请求失败: ' + err.message
      ElMessage.error('聊天请求失败')
    }
  )
}

const handleEvent = (event, data, aiMsg) => {
  switch (event) {
    case 'start':
      if (data.sessionId) currentSessionId.value = data.sessionId
      break
    case 'content':
      // 后端 JSON 包装 {text}，兼容纯文本
      aiMsg.content += (data && typeof data === 'object' ? (data.text ?? '') : data)
      nextTick(scrollToBottom)
      break
    case 'retrieve':
      // 后端仅在窗口关联知识库时下发该事件；保守起见这里也按 null 过滤
      if (data.recallRate != null || data.hitRate != null) {
        aiMsg.metrics = {
          ...(aiMsg.metrics || {}),
          ...(data.recallRate != null ? { recallRate: data.recallRate } : {}),
          ...(data.hitRate != null ? { hitRate: data.hitRate } : {})
        }
      }
      break

    // ===== Agent 计划创建 =====
    case 'plan_created':
      agentPlan.value = {
        planId: data.planId,
        status: 'RUNNING',
        userGoal: data.planSummary || '',
        steps: [],
        activeStepId: null,
        reflectionThreshold: window.value?.agentReflectionThreshold ?? 7.0
      }
      break

    // ===== Agent 计划完成/失败/取消 =====
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
      break
    }
    case 'plan_updated':
      // 动态重规划：刷新步骤列表（后端下发完整 steps 摘要时更新）
      if (data.steps && Array.isArray(data.steps)) agentPlan.value.steps = data.steps
      break
    case 'plan_create_failed':
      agentPlan.value.status = 'FAILED'
      break

    // ===== Agent 步骤事件 =====
    case 'step_start': {
      const idx = agentPlan.value.steps.findIndex(s => s.stepId === data.stepId)
      const newStep = {
        stepIndex: data.stepIndex, stepId: data.stepId, title: data.stepTitle,
        status: 'RUNNING', reflectionScore: null, reflectionFeedback: '',
        retryCount: 0, resultSummary: '', actionTool: null
      }
      if (idx >= 0) agentPlan.value.steps[idx] = { ...agentPlan.value.steps[idx], ...newStep, status: 'RUNNING' }
      else agentPlan.value.steps.push(newStep)
      agentPlan.value.activeStepId = data.stepId
      break
    }
    case 'step_success': {
      const step = agentPlan.value.steps.find(s => s.stepId === data.stepId)
      if (step) {
        step.status = 'SUCCESS'
        step.actionTool = data.toolCount > 0 ? '已调用' : null
        step.resultSummary = step.resultSummary || '✓ 步骤执行成功'
      }
      agentPlan.value.activeStepId = null
      break
    }
    case 'step_failed': {
      const step = agentPlan.value.steps.find(s => s.stepId === data.stepId)
      if (step) { step.status = 'FAILED'; step.resultSummary = data.reason || '❌ 步骤执行失败' }
      agentPlan.value.activeStepId = null
      break
    }
    case 'step_hitl_pending': {
      // 注意：HITL 步骤不会先发 step_start，前端列表里可能还没有它，需要按 payload 补建
      const idx = agentPlan.value.steps.findIndex(s => s.stepId === data.stepId)
      const hitlStep = {
        id: data.id,
        stepIndex: data.stepIndex,
        stepId: data.stepId,
        title: data.stepTitle,
        description: data.description || '',
        expectedInput: data.expectedInput || '',
        status: 'HITL_PENDING',
        reflectionScore: null,
        reflectionFeedback: '',
        retryCount: 0,
        resultSummary: '',
        actionTool: null
      }
      if (idx >= 0) agentPlan.value.steps[idx] = { ...agentPlan.value.steps[idx], ...hitlStep }
      else agentPlan.value.steps.push(hitlStep)
      agentPlan.value.status = 'PAUSED'
      agentPlan.value.activeStepId = data.stepId
      break
    }
    case 'step_reflection': {
      const step = agentPlan.value.steps.find(s => s.stepId === data.stepId)
      if (step) {
        step.reflectionScore = data.score
        step.reflectionFeedback = data.feedback || ''
        step.retryCount = data.retryCount || 0
      }
      break
    }
    case 'step_retry': {
      const step = agentPlan.value.steps.find(s => s.stepId === data.stepId)
      if (step) step.retryCount = (step.retryCount || 0) + 1
      break
    }

    case 'sources':
      aiMsg.sources = dedupeSources(data)
      break
    case 'tool_call_start':
      aiMsg.tools.push({ name: data.tool, args: data.args })
      nextTick(scrollToBottom)
      break
    case 'tool_call_result': {
      const t = aiMsg.tools.find(x => x.name === data.tool && x.success === undefined)
      if (t) {
        t.success = data.success
        t.result = data.result
        t.latencyMs = data.latencyMs
      }
      break
    }
    case 'accuracy':
      if (!aiMsg.metrics) aiMsg.metrics = {}
      aiMsg.metrics.accuracyRate = data.accuracyRate
      break
    case 'metrics':
      // 方案一：MRR + NDCG
      if (!aiMsg.metrics) aiMsg.metrics = {}
      if (data.mrr != null) aiMsg.metrics.mrr = data.mrr
      if (data.ndcg != null) aiMsg.metrics.ndcg = data.ndcg
      break
    case 'judge':
      // 方案二：LLM-as-Judge
      if (!aiMsg.metrics) aiMsg.metrics = {}
      if (data.faithfulness != null) aiMsg.metrics.faithfulness = data.faithfulness
      if (data.answerRelevancy != null) aiMsg.metrics.answerRelevancy = data.answerRelevancy
      if (data.judgeAgreement != null) aiMsg.metrics.judgeAgreement = data.judgeAgreement
      break
    case 'error':
      aiMsg.content += '\n\n⚠️ ' + (data && typeof data === 'object' ? (data.text ?? JSON.stringify(data)) : data)
      break
    case 'done':
      streaming.value = false
      aiMsg.streaming = false
      if (data.sessionId) currentSessionId.value = data.sessionId
      // Agent 路线后端不逐 token 推送 content，只下发 done.fullResponse，这里兜底填充
      if (!aiMsg.content && data.fullResponse) {
        aiMsg.content = data.fullResponse
        nextTick(scrollToBottom)
      }
      // 刷新左侧会话列表（消息数/时间）
      loadSessions()
      break
  }
}

const scrollToBottom = () => {
  nextTick(() => {
    if (messagesRef.value) {
      messagesRef.value.scrollTop = messagesRef.value.scrollHeight
    }
  })
}

// 滚动事件处理：滚动到顶部时自动加载更多
const handleScroll = () => {
  if (!messagesRef.value) return
  // 滚动到顶部 50px 内时触发加载
  if (messagesRef.value.scrollTop < 50) {
    loadMoreHistory()
  }
}

// marked v14 默认异步返回 Promise，需显式同步解析供 v-html 使用
const renderMarkdown = (text) => marked.parse(text || '', { async: false })

// 复制到剪贴板：优先 navigator.clipboard，降级为 textarea + execCommand（适配非 HTTPS/IP 访问）
const copyText = async (text) => {
  try {
    if (navigator.clipboard && globalThis.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch { /* fallthrough */ }
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.cssText = 'position:fixed;left:-9999px;top:0;opacity:0'
  document.body.appendChild(ta)
  ta.focus(); ta.select()
  let ok = false
  try { ok = document.execCommand('copy') } catch { /* ignore */ }
  document.body.removeChild(ta)
  return ok
}

// 跳转多 Agent 运行调试（携带当前窗口与会话上下文）
const openMultiAgent = () => {
  router.push({
    path: '/multi-agent/playground',
    query: { windowId, sessionId: currentSessionId.value || undefined }
  })
}

const copyShareLink = async () => {
  if (!window.value?.shareToken) return
  // 注意：本组件顶层有 `const window = ref(null)` 遮蔽了全局 window —— 这里必须用 globalThis.location
  const url = `${globalThis.location.origin}/share/${window.value.shareToken}`
  const ok = await copyText(url)
  if (ok) ElMessage.success('已复制分享链接')
  else ElMessage.warning('复制失败，请手动复制: ' + url)
}

// ============ AgentPlanTree 事件处理 ============

// 后端返回的 plan 状态 → 前端面板数据结构
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

// 停止状态轮询
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
        // Agent 在后台执行完成：补拉消息列表，把最终答复显示到聊天区
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

// 从 sessionId 恢复历史 Plan 状态（页面刷新/切换会话时）
const restoreAgentPlan = async () => {
  stopPlanPolling()
  if (window.value?.agentEnabled !== 1 || !currentSessionId.value) return
  try {
    planAbort = new AbortController()
    const res = await agentGetPlanBySession(currentSessionId.value, planAbort.signal)
    const data = res.data
    if (data) {
      applyPlanState(data)
      // 刷新时 Agent 可能仍在后台执行 → 轮询直到终态
      if (isPlanActive(data.status)) startPlanPolling()
    } else {
      // 没有历史 plan，重置
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

const onCancelPlan = async (planId) => {
  try {
    await agentCancelPlan(planId)
    ElMessage.success('计划已取消')
    agentPlan.value.status = 'CANCELLED'
    if (streaming.value) { streamer?.abort(); streaming.value = false }
  } catch (e) {
    ElMessage.error('取消失败: ' + (e.message || ''))
  }
}

const onRetryStep = async (step) => {
  try {
    await agentRetryStep(step.id)
    ElMessage.success('已触发重试')
    await onRefreshPlan(agentPlan.value.planId)
  } catch (e) {
    ElMessage.error('重试失败: ' + (e.message || ''))
  }
}

const onSkipStep = async (step) => {
  try {
    await agentSkipStep(step.id)
    ElMessage.success('已跳过该步骤')
    await onRefreshPlan(agentPlan.value.planId)
  } catch (e) {
    ElMessage.error('跳过失败: ' + (e.message || ''))
  }
}

const onRefreshPlan = async (planId) => {
  try {
    const res = await agentGetPlanState(planId)
    applyPlanState(res.data)
  } catch (e) {
    console.warn('刷新计划失败', e)
  }
}

const onHitlConfirmFromTree = async () => {
  // AgentPlanTree 内部已调 agentHitlConfirm API（同步等待 resume 执行完）
  // 这里刷新面板 + 补拉聊天消息（HITL 后续步骤的最终答复会落库）
  if (agentPlan.value.planId) await onRefreshPlan(agentPlan.value.planId)
  await loadHistory()
  if (isPlanActive(agentPlan.value.status)) startPlanPolling()
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
.chat-layout {
  display: grid;
  grid-template-columns: 200px 1fr 280px;
  gap: 16px;
  height: calc(100vh - 140px);
}
.session-sidebar {
  background: #fff;
  border-radius: 8px;
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
.chat-main {
  background: #fff;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.messages-container {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}
.load-more-tip {
  text-align: center;
  padding: 12px;
  color: #909399;
  font-size: 13px;
  cursor: pointer;
  border-bottom: 1px dashed #e4e7ed;
  margin-bottom: 10px;

  &:hover {
    color: #409eff;
    background: #f5f7fa;
  }

  .el-icon {
    margin-right: 6px;
  }
}
.welcome {
  text-align: center;
  padding: 60px 20px;
  color: #606266;
  h1 { font-size: 24px; margin: 0 0 12px; }
}
.message {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  .avatar {
    width: 40px; height: 40px;
    border-radius: 50%;
    display: flex; align-items: center; justify-content: center;
    font-size: 20px;
    flex-shrink: 0;
  }
  &.user-msg {
    flex-direction: row-reverse;
    .bubble { background: #409eff; color: #fff; border-radius: 12px 2px 12px 12px; }
    .avatar { background: #ecf5ff; }
  }
  &.assistant-msg {
    .bubble { background: #f5f7fa; border-radius: 2px 12px 12px 12px; }
    .avatar { background: #f0f9eb; }
  }
}
.bubble {
  max-width: 70%;
  padding: 12px 16px;
  line-height: 1.7;
  font-size: 14px;
  word-break: break-word;
}
.msg-metrics {
  display: flex;
  gap: 6px;
  margin-top: 8px;
  flex-wrap: wrap;
}
.msg-sources {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed #ebeef5;
  font-size: 12px;
  .sources-title { color: #909399; margin-bottom: 6px; }
  .source-item {
    display: flex; align-items: center; gap: 4px;
    padding: 4px 0; color: #606266;
  }
}
.input-area {
  display: flex;
  gap: 10px;
  padding: 16px;
  border-top: 1px solid #ebeef5;
  align-items: flex-end;
}
.typing {
  display: flex; gap: 4px;
  span { width: 8px; height: 8px; background: #909399; border-radius: 50%;
    animation: blink 1.4s infinite both; }
  span:nth-child(2) { animation-delay: 0.2s; }
  span:nth-child(3) { animation-delay: 0.4s; }
}
@keyframes blink {
  0%, 80%, 100% { opacity: 0.2; }
  40% { opacity: 1; }
}
.chat-info {
  background: #fff;
  border-radius: 8px;
  padding: 14px;
  overflow-y: auto;
  h3 { margin: 0 0 12px; font-size: 14px; color: #606266; }
}
.agent-banner {
  background: linear-gradient(135deg, #fdf6ec 0%, #faecd8 100%);
  border: 1px solid #faecd8;
  border-radius: 6px;
  padding: 12px 14px;
  margin-bottom: 12px;
  font-size: 12px;
  color: #606266;
  line-height: 1.6;
  .banner-tip { color: #909399; font-size: 11px; }
}
.status-card {
  padding: 12px;
  background: #ecf5ff;
  border-radius: 6px;
  .status-item { display: flex; justify-content: space-between; align-items: center; }
  .status-detail { margin-top: 6px; font-size: 12px; color: #606266; line-height: 1.5; }
  .rotating { animation: rotate 1s linear infinite; }
  &.idle { background: #f5f7fa; }
  &.done { background: #f0f9eb; }
  &.failed { background: #fef0f0; }
  &.paused { background: #fdf6ec; }
}
@keyframes rotate { to { transform: rotate(360deg); } }
</style>
