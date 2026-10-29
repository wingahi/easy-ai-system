<template>
  <div class="page-container">
    <div class="page-header">
      <div style="display:flex;gap:12px;align-items:center">
        <el-button :icon="ArrowLeft" circle @click="$router.push('/multi-agent/crews')" />
        <h2>多 Agent 运行调试</h2>
        <el-select v-model="selectedCrewId" placeholder="选择 Crew" style="width:260px" :disabled="isActive" @change="onCrewChange">
          <el-option v-for="c in crews" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-tag v-if="currentMode" :type="modeColor(currentMode)" size="small">{{ modeLabel(currentMode) }}</el-tag>
        <el-tag v-if="windowId" size="small" type="info" effect="plain">窗口 #{{ windowId }}</el-tag>
        <el-tag v-if="sessionId" size="small" type="info" effect="plain">会话 #{{ sessionId }}</el-tag>
      </div>
      <div style="display:flex;gap:8px;align-items:center">
        <el-tag v-if="runId" effect="plain" size="small">runId: {{ runId }}</el-tag>
        <el-button v-if="isActive" type="danger" plain :loading="cancelling" @click="cancelRun">取消运行</el-button>
      </div>
    </div>

    <div class="playground-layout">
      <!-- 左：运行历史 -->
      <div class="history-panel">
        <div class="panel-title">
          <span>运行历史</span>
          <el-button v-if="history.length" link type="danger" size="small" @click="clearHistory">清空</el-button>
        </div>
        <div class="history-list">
          <div v-for="h in history" :key="h.runId"
               :class="['history-item', { active: h.runId === runId }]"
               @click="loadRun(h.runId)">
            <div class="history-goal">{{ h.goal }}</div>
            <div class="history-meta">
              <el-tag size="small" :type="statusColor(h.status)">{{ statusLabel(h.status) }}</el-tag>
              <span>{{ modeShort(h.executionMode) }} · {{ formatTime(h.ts) }}</span>
            </div>
          </div>
          <div v-if="!history.length" class="history-empty">暂无运行记录</div>
        </div>
      </div>

      <!-- 中：Agent 对话气泡流 -->
      <div class="chat-panel">
        <div class="message-list" ref="messagesRef">
          <div v-if="!bubbles.length" class="welcome">
            <h2>🤝 多 Agent 协作调试</h2>
            <p>选择 Crew，输入任务目标后点击「开始运行」。</p>
            <p class="welcome-tip">支持 CrewAI 角色分层 / LangGraph 状态图 / AutoGen 对话式三种范式，含工具调用、RAG、反思与 HITL。</p>
          </div>

          <div v-for="(b, i) in bubbles" :key="i" :class="['bubble-row', b.type]">
            <div v-if="b.type === 'user'" class="bubble user-bubble">
              <div class="bubble-head"><span class="who">🙂 我</span></div>
              <div class="bubble-content">{{ b.content }}</div>
            </div>

            <div v-else-if="b.type === 'system'" class="system-line">
              <span>{{ b.content }}</span>
            </div>

            <div v-else-if="b.type === 'error'" class="system-line error">
              <span>⚠️ {{ b.content }}</span>
            </div>

            <div v-else class="bubble agent-bubble" :style="{ borderLeftColor: agentColor(b.agentId) }">
              <div class="bubble-head">
                <span class="who" :style="{ color: agentColor(b.agentId) }">
                  🤖 {{ b.name || b.agentId }}
                </span>
                <el-tag v-if="b.role" size="small" effect="plain">{{ b.role }}</el-tag>
                <el-tag v-if="b.round != null" size="small" type="info" effect="plain">第 {{ b.round + 1 }} 轮</el-tag>
                <el-tag v-if="b.reflection && b.reflection.score != null" size="small"
                        :type="b.reflection.pass ? 'success' : 'danger'">
                  反思 {{ b.reflection.score }}
                </el-tag>
                <span v-if="b.durationMs != null" class="duration">{{ b.durationMs }}ms</span>
              </div>
              <div class="bubble-content markdown" v-html="renderMarkdown(b.content)"></div>

              <div v-if="b.reflection && b.reflection.feedback" class="reflection-feedback">
                💭 {{ b.reflection.feedback }}
              </div>

              <div v-if="b.toolCalls?.length" class="tool-list">
                <div v-for="(t, ti) in b.toolCalls" :key="ti" class="tool-item">
                  <el-icon><Tools /></el-icon>
                  <span class="tool-name">{{ t.toolName }}</span>
                  <el-tag size="small" :type="t.success === false ? 'danger' : 'success'">
                    {{ t.success === false ? '失败' : '成功' }}
                  </el-tag>
                  <span v-if="t.latencyMs != null" class="duration">{{ t.latencyMs }}ms</span>
                </div>
              </div>
            </div>
          </div>

          <div v-if="currentSpeaker && status === 'RUNNING'" class="bubble-row agent">
            <div class="bubble agent-bubble typing-bubble" :style="{ borderLeftColor: agentColor(currentSpeaker) }">
              <div class="bubble-head">
                <span class="who" :style="{ color: agentColor(currentSpeaker) }">
                  🤖 {{ agentMeta[currentSpeaker]?.name || currentSpeaker }}
                </span>
                <el-tag v-if="agentMeta[currentSpeaker]?.role" size="small" effect="plain">
                  {{ agentMeta[currentSpeaker].role }}
                </el-tag>
              </div>
              <div class="typing"><span></span><span></span><span></span></div>
            </div>
          </div>
        </div>

        <div class="input-area">
          <el-input v-model="goal" type="textarea" :rows="2"
                    placeholder="输入任务目标，Ctrl+Enter 开始运行"
                    @keydown.ctrl.enter="startRun"
                    :disabled="isActive" />
          <el-button type="primary" :icon="VideoPlay" :loading="starting" @click="startRun" :disabled="isActive">
            开始运行
          </el-button>
        </div>
      </div>

      <!-- 右：执行面板 -->
      <div class="side-panel">
        <h3>运行状态</h3>
        <div class="status-card" :class="statusTone">
          <div class="status-line">
            <span>{{ statusText }}</span>
            <el-icon v-if="isActive" class="rotating"><Loading /></el-icon>
          </div>
          <div v-if="currentMode" class="status-detail">
            模式：{{ modeLabel(currentMode) }}
          </div>
          <div v-if="isGroupChat" class="status-detail">
            轮次：{{ currentRound }} / {{ maxRounds || agentRounds || '-' }}
          </div>
        </div>

        <h3 style="margin-top:16px">Agents（{{ agentList.length }}）</h3>
        <div class="agent-status-list">
          <div v-for="a in agentList" :key="a.agentId" class="agent-status-item">
            <span class="dot" :class="agentStates[a.agentId]?.status || 'idle'"></span>
            <span class="name" :style="{ color: agentColor(a.agentId) }">{{ a.name || a.agentId }}</span>
            <el-tag v-if="agentStates[a.agentId]?.reflection != null" size="small" effect="plain">
              {{ agentStates[a.agentId].reflection }}
            </el-tag>
            <span class="agent-state-text">{{ agentStateLabel(agentStates[a.agentId]?.status) }}</span>
          </div>
          <div v-if="!agentList.length" class="empty-tip">当前 Crew 未配置 Agent</div>
        </div>

        <template v-if="activeTools.length">
          <h3 style="margin-top:16px">工具调用</h3>
          <div class="tool-panel">
            <div v-for="(t, i) in activeTools" :key="i" class="tool-panel-item">
              <span>{{ agentMeta[t.agentId]?.name || t.agentId }}</span>
              <span class="tool-name">{{ t.toolName }}</span>
              <el-tag size="small" :type="t.pending ? 'info' : t.success === false ? 'danger' : 'success'">
                {{ t.pending ? '调用中' : t.success === false ? '失败' : '成功' }}
              </el-tag>
            </div>
          </div>
        </template>

        <template v-if="steps.length">
          <h3 style="margin-top:16px">执行记录</h3>
          <div class="step-list">
            <div v-for="s in steps" :key="s.id" class="step-item">
              <div class="step-head">
                <span>#{{ s.stepIndex }} {{ agentMeta[s.agentId]?.name || s.agentId }}</span>
                <el-tag size="small" :type="stepTagType(s.status)">{{ stepStatusLabel(s.status) }}</el-tag>
              </div>
              <div class="step-meta">
                <span v-if="s.durationMs != null">{{ s.durationMs }}ms</span>
                <span v-if="s.reflectionScore != null">反思 {{ s.reflectionScore }}</span>
                <span v-if="s.round != null">轮次 {{ s.round + 1 }}</span>
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- HITL 确认 -->
    <el-dialog v-model="hitl.visible" title="✋ 等待人工确认" width="540px"
               :close-on-press-escape="false" :close-on-click-modal="false">
      <div class="hitl-prompt">{{ hitl.prompt || '该步骤需要人工确认后才能继续执行。' }}</div>
      <el-input v-model="hitl.input" type="textarea" :rows="3"
                placeholder="输入确认信息 / 补充输入（可留空直接确认）" />
      <template #footer>
        <el-button type="danger" plain @click="cancelRun">取消运行</el-button>
        <el-button type="primary" :loading="hitl.submitting" @click="confirmHitl">确认继续</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Loading, Tools, VideoPlay } from '@element-plus/icons-vue'
import { marked } from 'marked'
import {
  multiAgentCrewList, multiAgentRunState, multiAgentCreateRun, multiAgentCancelRun,
  streamMultiAgentRun, streamMultiAgentHitl
} from '../../api'

const route = useRoute()

const HISTORY_KEY = 'wgh_multi_agent_history'
const PALETTE = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#909399', '#9254de', '#13c2c2', '#eb2f96']

marked.setOptions({ breaks: true })

const crews = ref([])
const selectedCrewId = ref(route.query.crewId ? Number(route.query.crewId) : null)
const windowId = ref(route.query.windowId ? Number(route.query.windowId) : null)
const sessionId = ref(route.query.sessionId ? Number(route.query.sessionId) : null)

const goal = ref('')
const runId = ref('')
const run = ref(null)
const status = ref('')
const bubbles = ref([])
const steps = ref([])
const history = ref(loadHistory())
const agentStates = ref({})
const activeTools = ref([])
const currentSpeaker = ref('')
const currentRound = ref(0)
const maxRounds = ref(0)
const agentRounds = ref(0)

const starting = ref(false)
const cancelling = ref(false)
const messagesRef = ref(null)

const hitl = reactive({ visible: false, prompt: '', agentId: '', input: '', submitting: false })

let streamer = null
let pollTimer = null
let pollAbort = null

// ==================== 计算属性 ====================

const currentMode = computed(() => run.value?.executionMode || crews.value.find(c => c.id === selectedCrewId.value)?.executionMode || '')
const isGroupChat = computed(() => currentMode.value?.startsWith('GROUP_CHAT'))
const isActive = computed(() => status.value === 'RUNNING' || status.value === 'PLANNING' || status.value === 'PAUSED')

const agentMeta = computed(() => {
  const map = {}
  const list = agentList.value
  list.forEach((a, i) => { map[a.agentId] = { ...a, color: PALETTE[i % PALETTE.length] } })
  return map
})

const agentList = computed(() => {
  const json = run.value?.agentsJson || crews.value.find(c => c.id === selectedCrewId.value)?.agentsJson
  try { return JSON.parse(json || '[]') } catch { return [] }
})

const statusText = computed(() => {
  switch (status.value) {
    case 'PLANNING': return '📝 正在初始化运行'
    case 'RUNNING': return currentSpeaker.value
      ? `⚡ 执行中 · ${agentMeta.value[currentSpeaker.value]?.name || currentSpeaker.value}`
      : '⚡ 执行中'
    case 'PAUSED': return '✋ 等待人工确认（HITL）'
    case 'COMPLETED': return '✅ 运行完成'
    case 'FAILED': return '❌ 运行失败'
    case 'CANCELLED': return '⛔ 已取消'
    default: return '空闲'
  }
})

const statusTone = computed(() => {
  if (status.value === 'RUNNING' || status.value === 'PLANNING') return 'running'
  if (status.value === 'PAUSED') return 'paused'
  if (status.value === 'COMPLETED') return 'done'
  if (status.value === 'FAILED') return 'failed'
  return 'idle'
})

// ==================== 工具方法 ====================

const MODES = {
  CREW_SEQUENTIAL: '角色分层 · 串行',
  CREW_HIERARCHICAL: '角色分层 · 层级',
  CREW_PARALLEL: '角色分层 · 并行',
  GRAPH_ROUTED: '状态图路由',
  GROUP_CHAT_ROUND_ROBIN: '对话式 · 固定轮询',
  GROUP_CHAT_DYNAMIC_ROUTER: '对话式 · LLM 动态路由'
}

const modeLabel = (v) => MODES[v] || v || '-'
const modeShort = (v) => v?.startsWith('GROUP_CHAT') ? 'GroupChat' : v === 'GRAPH_ROUTED' ? 'Graph' : 'Crew'
const modeColor = (v) => v?.startsWith('CREW') ? 'success' : v === 'GRAPH_ROUTED' ? 'warning' : 'primary'

const statusLabel = (s) => ({ PLANNING: '初始化', RUNNING: '运行中', PAUSED: '待确认', COMPLETED: '完成', FAILED: '失败', CANCELLED: '已取消' }[s] || s || '-')
const statusColor = (s) => ({ RUNNING: 'primary', PLANNING: 'info', PAUSED: 'warning', COMPLETED: 'success', FAILED: 'danger', CANCELLED: 'info' }[s] || 'info')
const stepStatusLabel = (s) => ({ SUCCESS: '成功', RUNNING: '执行中', HITL_PENDING: '待确认', FAILED: '失败', SKIPPED: '跳过', PENDING: '待执行' }[s] || s)
const stepTagType = (s) => ({ SUCCESS: 'success', RUNNING: 'primary', HITL_PENDING: 'warning', FAILED: 'danger', SKIPPED: 'info' }[s] || 'info')
const agentStateLabel = (s) => ({ RUNNING: '执行中', SUCCESS: '完成', FAILED: '失败' }[s] || '待执行')

const agentColor = (agentId) => agentMeta.value[agentId]?.color || '#909399'

const safeParse = (json) => {
  try { return json ? JSON.parse(json) : null } catch { return null }
}

const renderMarkdown = (text) => marked.parse(text || '', { async: false })

const formatTime = (ts) => {
  if (!ts) return ''
  const d = new Date(ts)
  if (isNaN(d.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const scrollToBottom = () => {
  nextTick(() => {
    if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight
  })
}

// ==================== 历史记录（localStorage） ====================

function loadHistory() {
  try { return JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]') } catch { return [] }
}

const persistHistory = () => {
  try { localStorage.setItem(HISTORY_KEY, JSON.stringify(history.value.slice(0, 30))) } catch { /* ignore */ }
}

const upsertHistory = (entry) => {
  const idx = history.value.findIndex(h => h.runId === entry.runId)
  if (idx >= 0) history.value[idx] = { ...history.value[idx], ...entry }
  else history.value.unshift(entry)
  persistHistory()
}

const updateHistoryStatus = (s) => {
  const item = history.value.find(h => h.runId === runId.value)
  if (item) { item.status = s; persistHistory() }
}

const clearHistory = () => {
  history.value = []
  persistHistory()
}

// ==================== 气泡 ====================

const appendAgentBubble = (agentId, content, extra = {}) => {
  if (!content) return
  const last = bubbles.value[bubbles.value.length - 1]
  if (last && last.type === 'agent' && last.agentId === agentId && last.content === content) return
  const meta = agentMeta.value[agentId] || {}
  bubbles.value.push({
    type: 'agent',
    agentId,
    name: extra.name || meta.name || agentId,
    role: extra.role || meta.role || '',
    content,
    round: extra.round ?? null,
    durationMs: extra.durationMs ?? null,
    toolCalls: extra.toolCalls || toolBuffer[agentId] || [],
    reflection: extra.reflection || reflections[agentId] || null
  })
  delete toolBuffer[agentId]
  delete reflections[agentId]
  scrollToBottom()
}

const appendSystem = (content, type = 'system') => {
  if (!content) return
  bubbles.value.push({ type, content })
  scrollToBottom()
}

// agent 级临时缓存（事件到达顺序：tool/reflection 先于 done）
const toolBuffer = {}
const reflections = {}

// ==================== SSE 事件 ====================

const handleEvent = (event, data) => {
  if (!data || typeof data !== 'object') return
  const agentId = data.agentId
  switch (event) {
    case 'multi_agent_start': {
      status.value = 'RUNNING'
      currentSpeaker.value = ''
      updateHistoryStatus('RUNNING')
      break
    }
    case 'agent_start': {
      currentSpeaker.value = agentId
      agentStates.value = { ...agentStates.value, [agentId]: { status: 'RUNNING' } }
      break
    }
    case 'agent_rag': {
      const st = agentStates.value[agentId] || {}
      agentStates.value = { ...agentStates.value, [agentId]: { ...st, ragCount: data.kbCount } }
      break
    }
    case 'agent_tool_call_start': {
      activeTools.value.push({ agentId, toolName: data.toolName, arguments: data.arguments, pending: true })
      if (!toolBuffer[agentId]) toolBuffer[agentId] = []
      toolBuffer[agentId].push({ toolName: data.toolName, arguments: data.arguments, success: undefined })
      break
    }
    case 'agent_tool_call_result': {
      const pending = activeTools.value.find(t => t.agentId === agentId && t.toolName === data.toolName && t.pending)
      if (pending) {
        pending.pending = false
        pending.success = data.success
        pending.latencyMs = data.latencyMs
      }
      const list = toolBuffer[agentId] || []
      const target = [...list].reverse().find(t => t.toolName === data.toolName && t.success === undefined)
      if (target) {
        target.success = data.success
        target.latencyMs = data.latencyMs
        target.result = data.result
      }
      break
    }
    case 'agent_reflection': {
      if (data.phase === 'start') {
        const st = agentStates.value[agentId] || {}
        agentStates.value = { ...agentStates.value, [agentId]: { ...st, reflecting: true } }
      } else {
        reflections[agentId] = { score: data.score, feedback: data.feedback, pass: data.pass }
        const st = agentStates.value[agentId] || {}
        agentStates.value = { ...agentStates.value, [agentId]: { ...st, reflection: data.score, reflecting: false } }
      }
      break
    }
    case 'agent_done': {
      currentSpeaker.value = ''
      if (data.error) {
        appendSystem(`${agentMeta.value[agentId]?.name || agentId} 执行失败：${data.error}`, 'error')
        agentStates.value = { ...agentStates.value, [agentId]: { status: 'FAILED' } }
      } else {
        appendAgentBubble(agentId, data.output, { durationMs: data.durationMs })
        const prev = agentStates.value[agentId] || {}
        agentStates.value = { ...agentStates.value, [agentId]: { ...prev, status: 'SUCCESS' } }
      }
      break
    }
    case 'agent_turn_start': {
      currentSpeaker.value = agentId
      currentRound.value = data.round ?? currentRound.value
      agentStates.value = { ...agentStates.value, [agentId]: { status: 'RUNNING' } }
      break
    }
    case 'agent_turn_end': {
      appendAgentBubble(agentId, data.output, { round: data.round })
      const prev = agentStates.value[agentId] || {}
      agentStates.value = { ...agentStates.value, [agentId]: { ...prev, status: 'SUCCESS' } }
      currentRound.value = (data.round ?? 0) + 1
      break
    }
    case 'agent_message': {
      // 兜底：MessageBus 广播消息（与 turn_end/done 去重）
      if (data.fromAgentId && data.fromAgentId !== 'user') {
        appendAgentBubble(data.fromAgentId, data.content)
      }
      break
    }
    case 'agent_hitl_pending': {
      status.value = 'PAUSED'
      currentSpeaker.value = ''
      updateHistoryStatus('PAUSED')
      hitl.prompt = data.prompt || ''
      hitl.agentId = data.agentId || data.nodeId || ''
      hitl.input = ''
      hitl.visible = true
      appendSystem(`⏸ 等待人工确认：${data.prompt || ''}`)
      break
    }
    case 'multi_agent_resume': {
      status.value = 'RUNNING'
      updateHistoryStatus('RUNNING')
      break
    }
    case 'multi_agent_complete': {
      status.value = 'COMPLETED'
      currentSpeaker.value = ''
      updateHistoryStatus('COMPLETED')
      if (data.rounds != null) agentRounds.value = data.rounds
      appendAgentBubble('__final__', data.output, { name: '最终答复', role: '汇总' })
      refreshState()
      break
    }
    case 'multi_agent_error': {
      status.value = 'FAILED'
      currentSpeaker.value = ''
      updateHistoryStatus('FAILED')
      appendSystem(data.error || '运行异常', 'error')
      break
    }
  }
}

const onStreamDone = () => {
  // 注意：dev 代理下 SSE 终止帧可能延迟，状态复位必须由事件驱动，这里仅兜底
  if (status.value === 'PLANNING') status.value = 'RUNNING'
}

const onStreamError = (err) => {
  if (status.value === 'RUNNING' || status.value === 'PLANNING') {
    status.value = 'FAILED'
    appendSystem(err.message || '连接异常', 'error')
    updateHistoryStatus('FAILED')
  }
}

// ==================== 运行控制 ====================

const resetView = () => {
  bubbles.value = []
  steps.value = []
  agentStates.value = {}
  activeTools.value = []
  currentSpeaker.value = ''
  currentRound.value = 0
  Object.keys(toolBuffer).forEach(k => delete toolBuffer[k])
  Object.keys(reflections).forEach(k => delete reflections[k])
}

const onCrewChange = () => {
  if (isActive.value) return
  run.value = null
  status.value = ''
  runId.value = ''
  resetView()
}

const startRun = async () => {
  if (!selectedCrewId.value) { ElMessage.warning('请先选择 Crew'); return }
  if (!goal.value.trim()) { ElMessage.warning('请输入任务目标'); return }
  const crew = crews.value.find(c => c.id === selectedCrewId.value)
  if (!crew) return

  starting.value = true
  try {
    const body = {
      crewId: crew.id,
      executionMode: crew.executionMode,
      input: goal.value.trim(),
      userGoal: goal.value.trim(),
      chatWindowId: windowId.value || 0,
      sessionId: sessionId.value || 0
    }
    // GroupChat 配置（轮次/终止条件/路由模型/汇总 Agent）
    const gc = safeParse(crew.groupChatConfigJson)
    if (gc) {
      if (gc.rounds != null) body.maxRounds = gc.rounds
      if (gc.terminationCriteria) body.terminationCriteria = gc.terminationCriteria
      if (gc.dynamicRouterModelId) body.dynamicRouterModelId = gc.dynamicRouterModelId
      if (gc.routerPrompt) body.dynamicRouterPrompt = gc.routerPrompt
      if (gc.summarizerAgentId) body.summarizerAgentId = gc.summarizerAgentId
      agentRounds.value = gc.rounds || 0
    }

    stopStream()
    stopPolling()
    resetView()

    const res = await multiAgentCreateRun(body)
    runId.value = res.data.runId
    status.value = 'PLANNING'
    run.value = {
      runId: runId.value,
      executionMode: crew.executionMode,
      agentsJson: crew.agentsJson,
      userGoal: goal.value.trim(),
      maxRounds: body.maxRounds
    }
    maxRounds.value = body.maxRounds || 0
    bubbles.value.push({ type: 'user', content: goal.value.trim() })

    upsertHistory({
      runId: runId.value,
      crewId: crew.id,
      crewName: crew.name,
      executionMode: crew.executionMode,
      goal: goal.value.trim(),
      status: 'PLANNING',
      ts: Date.now()
    })

    streamer = streamMultiAgentRun(runId.value, handleEvent, onStreamDone, onStreamError)
    streamer.doStream()
  } catch (e) {
    status.value = ''
    ElMessage.error('创建运行失败: ' + (e.message || ''))
  } finally {
    starting.value = false
  }
}

const confirmHitl = () => {
  if (!runId.value) return
  hitl.submitting = true
  status.value = 'RUNNING'
  updateHistoryStatus('RUNNING')
  if (hitl.input.trim()) bubbles.value.push({ type: 'user', content: hitl.input.trim() })
  // HITL 暂停后原运行流已结束，这里统一交给 streamer 管理，保证取消/切页时能被中断
  stopStream()
  streamer = streamMultiAgentHitl(runId.value, hitl.input,
    (evt, data) => {
      if (evt !== 'multi_agent_resume') hitl.visible = false
      handleEvent(evt, data)
    },
    () => { hitl.submitting = false },
    (err) => {
      hitl.submitting = false
      status.value = 'PAUSED'
      ElMessage.error('HITL 续跑失败: ' + (err.message || ''))
    })
  hitl.visible = false
  hitl.submitting = false
  // 与 workflow 一致：状态复位不依赖 doStream resolve，由 multi_agent_start/resume 事件驱动
  streamer.doStream()
}

const cancelRun = async () => {
  if (!runId.value) return
  cancelling.value = true
  try {
    await multiAgentCancelRun(runId.value)
    status.value = 'CANCELLED'
    currentSpeaker.value = ''
    hitl.visible = false
    updateHistoryStatus('CANCELLED')
    stopStream()
    stopPolling()
    ElMessage.success('已取消运行')
  } catch (e) {
    // 拦截器已提示
  } finally { cancelling.value = false }
}

// ==================== 状态恢复 / 轮询 ====================

const buildBubblesFromState = (r, stepList) => {
  bubbles.value = []
  if (r?.userGoal) bubbles.value.push({ type: 'user', content: r.userGoal })
  for (const s of stepList || []) {
    if (s.status === 'HITL_PENDING') {
      appendSystem(`⏸ 等待人工确认：${s.hitlExpectedInput || ''}`)
      continue
    }
    if (!s.outputText) continue
    bubbles.value.push({
      type: 'agent',
      agentId: s.agentId,
      name: agentMeta.value[s.agentId]?.name || s.agentId,
      role: s.role || agentMeta.value[s.agentId]?.role || '',
      content: s.outputText,
      round: s.round ?? null,
      durationMs: s.durationMs ?? null,
      toolCalls: safeParse(s.toolCallsJson) || [],
      reflection: s.reflectionScore != null
        ? { score: s.reflectionScore, feedback: s.reflectionFeedback, pass: null }
        : null
    })
  }
  if (r?.status === 'COMPLETED' && r.outputText) {
    const last = bubbles.value[bubbles.value.length - 1]
    if (!last || last.content !== r.outputText) {
      bubbles.value.push({ type: 'agent', agentId: '__final__', name: '最终答复', role: '汇总', content: r.outputText })
    }
  }
  scrollToBottom()
}

/** PAUSED 状态（刷新/轮询恢复）时重新打开 HITL 弹窗 */
const openHitlFromSteps = () => {
  if (status.value !== 'PAUSED' || hitl.visible) return
  const step = [...steps.value].reverse().find(s => s.status === 'HITL_PENDING')
  hitl.prompt = step?.hitlExpectedInput || ''
  hitl.agentId = step?.agentId || ''
  hitl.input = ''
  hitl.visible = true
}

const refreshState = async () => {
  if (!runId.value) return
  try {
    const res = await multiAgentRunState(runId.value)
    const data = res.data || {}
    run.value = data.run
    steps.value = data.steps || []
    if (data.run) {
      status.value = data.run.status
      currentRound.value = data.run.currentRound || 0
      maxRounds.value = data.run.maxRounds || maxRounds.value
      updateHistoryStatus(data.run.status)
    }
  } catch (e) {
    // 静默：运行中状态轮询失败不打断 UI
  }
}

const loadRun = async (id) => {
  stopStream()
  stopPolling()
  resetView()
  runId.value = id
  try {
    const res = await multiAgentRunState(id)
    const data = res.data || {}
    if (!data.run) { ElMessage.warning('运行记录不存在'); return }
    run.value = data.run
    steps.value = data.steps || []
    status.value = data.run.status
    currentRound.value = data.run.currentRound || 0
    maxRounds.value = data.run.maxRounds || 0
    // agentStates 按 steps 回填
    const st = {}
    for (const s of steps.value) {
      st[s.agentId] = { status: s.status === 'SUCCESS' ? 'SUCCESS' : s.status === 'FAILED' ? 'FAILED' : 'RUNNING', reflection: s.reflectionScore }
    }
    agentStates.value = st
    buildBubblesFromState(data.run, data.steps)
    if (status.value === 'RUNNING' || status.value === 'PLANNING') startPolling()
    else openHitlFromSteps()
  } catch (e) {
    ElMessage.error('加载运行状态失败: ' + (e.message || ''))
  }
}

const startPolling = () => {
  stopPolling()
  pollTimer = setInterval(async () => {
    if (!runId.value) { stopPolling(); return }
    try {
      pollAbort = new AbortController()
      const res = await multiAgentRunState(runId.value, pollAbort.signal)
      const data = res.data || {}
      if (!data.run) return
      run.value = data.run
      steps.value = data.steps || []
      status.value = data.run.status
      currentRound.value = data.run.currentRound || 0
      if (!isActive.value || data.run.status === 'PAUSED') {
        stopPolling()
        buildBubblesFromState(data.run, data.steps)
        updateHistoryStatus(data.run.status)
        openHitlFromSteps()
      }
    } catch (e) {
      if (e.name !== 'CanceledError' && e.name !== 'AbortError') {
        console.warn('轮询运行状态失败', e)
      }
    }
  }, 2000)
}

const stopPolling = () => {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
  if (pollAbort) { pollAbort.abort(); pollAbort = null }
}

const stopStream = () => {
  streamer?.abort()
  streamer = null
}

// ==================== 生命周期 ====================

const loadCrews = async () => {
  const res = await multiAgentCrewList()
  crews.value = res.data || []
  if (!selectedCrewId.value && crews.value.length) selectedCrewId.value = crews.value[0].id
}

onMounted(async () => {
  await loadCrews()
  if (route.query.runId) {
    await loadRun(route.query.runId)
    return
  }
  // 刷新页面后自动恢复最近一次未结束的运行（SSE 已断开，走状态轮询）
  const active = history.value.find(h => ['RUNNING', 'PLANNING', 'PAUSED'].includes(h.status))
  if (active) await loadRun(active.runId)
})

onUnmounted(() => {
  stopStream()
  stopPolling()
})
</script>

<style scoped lang="scss">
.playground-layout {
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr) 340px;
  gap: 16px;
  height: calc(100vh - 140px);
}
/* 窄屏收敛：避免三栏固定宽度超出容器、右侧面板遮挡输入区 */
@media (max-width: 1280px) {
  .playground-layout { grid-template-columns: 210px minmax(0, 1fr) 300px; gap: 12px; }
}
@media (max-width: 1080px) {
  .playground-layout { grid-template-columns: minmax(0, 1fr) 290px; }
  .history-panel { display: none; }
}
@media (max-width: 860px) {
  .playground-layout { grid-template-columns: minmax(0, 1fr); height: auto; grid-template-rows: minmax(420px, 60vh) auto; }
}
.history-panel, .chat-panel, .side-panel {
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
}
.history-panel {
  display: flex;
  flex-direction: column;
}
.panel-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 16px;
  font-size: 14px;
  font-weight: 600;
  border-bottom: 1px solid #ebeef5;
}
.history-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}
.history-item {
  padding: 10px 12px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
  &:hover { background: #f5f7fa; }
  &.active { background: #ecf5ff; }
}
.history-goal {
  font-size: 13px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.history-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}
.history-empty { padding: 20px; text-align: center; font-size: 13px; color: #c0c4cc; }

.chat-panel {
  display: flex;
  flex-direction: column;
}
.message-list {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}
.welcome {
  text-align: center;
  padding: 60px 20px;
  color: #606266;
  h2 { font-size: 22px; margin: 0 0 12px; }
  .welcome-tip { font-size: 13px; color: #909399; }
}
.bubble-row { margin-bottom: 18px; }
.bubble-row.user { display: flex; justify-content: flex-end; }
.bubble {
  max-width: 78%;
  padding: 12px 16px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.7;
  word-break: break-word;
}
.user-bubble {
  background: #409eff;
  color: #fff;
  border-radius: 12px 2px 12px 12px;
}
.agent-bubble {
  background: #f7f9fc;
  border-left: 3px solid #409eff;
  border-radius: 2px 12px 12px 12px;
}
.bubble-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
  flex-wrap: wrap;
  .who { font-size: 13px; font-weight: 600; }
  .duration { font-size: 12px; color: #c0c4cc; }
}
.bubble-content { white-space: pre-wrap; }
.bubble-content.markdown { white-space: normal; }
.system-line {
  text-align: center;
  font-size: 13px;
  color: #909399;
  padding: 6px 0;
  &.error { color: #f56c6c; }
}
.reflection-feedback {
  margin-top: 8px;
  padding: 8px 10px;
  background: #fdf6ec;
  border-radius: 6px;
  font-size: 12px;
  color: #b88230;
  line-height: 1.6;
}
.tool-list {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #ebeef5;
}
.tool-item {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #606266;
  padding: 3px 0;
}
.tool-name { font-weight: 500; }
.typing-bubble { min-width: 160px; }
.typing {
  display: flex; gap: 4px;
  span { width: 8px; height: 8px; background: #909399; border-radius: 50%; animation: blink 1.4s infinite both; }
  span:nth-child(2) { animation-delay: 0.2s; }
  span:nth-child(3) { animation-delay: 0.4s; }
}
@keyframes blink {
  0%, 80%, 100% { opacity: 0.2; }
  40% { opacity: 1; }
}
.input-area {
  display: flex;
  gap: 10px;
  padding: 16px;
  border-top: 1px solid #ebeef5;
  align-items: flex-end;
  :deep(.el-input) { flex: 1; }
  .el-button { flex-shrink: 0; }
}
.side-panel {
  padding: 14px;
  overflow-y: auto;
  h3 { margin: 0 0 10px; font-size: 14px; color: #606266; }
}
.status-card {
  padding: 12px;
  background: #ecf5ff;
  border-radius: 6px;
  .status-line { display: flex; justify-content: space-between; align-items: center; font-size: 14px; }
  .status-detail { margin-top: 6px; font-size: 12px; color: #606266; }
  .rotating { animation: rotate 1s linear infinite; }
  &.idle { background: #f5f7fa; }
  &.done { background: #f0f9eb; }
  &.failed { background: #fef0f0; }
  &.paused { background: #fdf6ec; }
}
@keyframes rotate { to { transform: rotate(360deg); } }
.agent-status-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.agent-status-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  padding: 6px 8px;
  border-radius: 6px;
  background: #fafbfc;
  .dot {
    width: 8px; height: 8px; border-radius: 50%; background: #c0c4cc; flex-shrink: 0;
    &.RUNNING { background: #409eff; box-shadow: 0 0 0 3px #d9ecff; }
    &.SUCCESS { background: #67c23a; }
    &.FAILED { background: #f56c6c; }
  }
  .name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .agent-state-text { font-size: 12px; color: #909399; }
}
.empty-tip { font-size: 12px; color: #c0c4cc; padding: 6px 0; }
.tool-panel { display: flex; flex-direction: column; gap: 6px; }
.tool-panel-item {
  display: flex; align-items: center; gap: 6px; font-size: 12px; color: #606266;
}
.step-list { display: flex; flex-direction: column; gap: 6px; }
.step-item {
  padding: 8px 10px;
  border-radius: 6px;
  background: #fafbfc;
  font-size: 12px;
}
.step-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.step-meta { display: flex; gap: 10px; color: #909399; }
.hitl-prompt {
  padding: 12px;
  background: #fdf6ec;
  border-radius: 6px;
  font-size: 14px;
  color: #b88230;
  line-height: 1.6;
  margin-bottom: 12px;
}
</style>
