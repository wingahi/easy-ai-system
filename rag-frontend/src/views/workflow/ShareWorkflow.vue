<template>
  <div class="share-wf-page">
    <header class="swf-header">
      <div class="swf-title">
        <span class="swf-name">{{ meta?.name || '分享工作流' }}</span>
        <el-tag size="small" type="warning">分享运行</el-tag>
        <el-tag v-if="meta?.version" size="small" type="info">v{{ meta.version }}</el-tag>
      </div>
      <div v-if="meta?.description" class="swf-desc">{{ meta.description }}</div>
    </header>

    <div class="swf-body">
      <!-- 消息区 -->
      <main class="swf-main">
        <div v-if="!messages.length" class="swf-empty">
          <el-empty description="输入内容后运行工作流，结果将在这里展示" :image-size="90" />
        </div>
        <div v-for="(m, i) in messages" :key="i" class="swf-msg" :class="m.role === 'USER' ? 'is-user' : 'is-ai'">
          <div class="swf-bubble">
            <div v-if="m.role === 'USER'" class="swf-text">{{ m.content }}</div>
            <div v-else class="swf-md" v-html="renderMd(m.content)"></div>
          </div>
        </div>
        <div v-if="streaming" class="swf-msg is-ai">
          <div class="swf-bubble swf-streaming">
            <el-icon class="is-loading"><Loading /></el-icon>
            <span>工作流执行中…</span>
          </div>
        </div>
      </main>

      <!-- 运行轨迹 -->
      <aside class="swf-side">
        <WorkflowRunPanel
          :run="run"
          :prompt="hitlPrompt"
          :confirming="confirming"
          @confirm="onHitlConfirm"
          @cancel="onCancel"
        />
      </aside>
    </div>

    <footer class="swf-inputbar">
      <el-input
        v-model="input"
        type="textarea"
        :rows="2"
        resize="none"
        placeholder="输入要提交给工作流的内容，Enter 发送 / Shift+Enter 换行"
        :disabled="streaming"
        @keydown.enter.exact.prevent="send"
      />
      <el-button type="primary" :icon="Promotion" :loading="streaming" :disabled="!input.trim()" @click="send">
        运行
      </el-button>
    </footer>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Promotion, Loading } from '@element-plus/icons-vue'
import { marked } from 'marked'
import WorkflowRunPanel from '../../components/WorkflowRunPanel.vue'
import {
  workflowShareInfo, streamWorkflowShareRun, workflowShareRunState,
  streamWorkflowShareHitl, workflowShareCancel
} from '../../api'

const route = useRoute()
const token = route.params.token
const RUN_CACHE_KEY = `wf_share_run_${token}`

const meta = ref(null)
const input = ref('')
const messages = ref([])
const streaming = ref(false)

// ===== 工作流运行状态 =====
const run = ref(null)
const hitlPrompt = ref('')
const confirming = ref(false)
let streamer = null
let pollTimer = null
let pollAbort = null

const renderMd = (text) => marked.parse(text || '')

// ===== 生命周期：加载元信息 + 恢复上次运行 =====
onMounted(async () => {
  try {
    const res = await workflowShareInfo(token)
    meta.value = res.data
  } catch (e) {
    ElMessage.error('分享链接无效或已关闭分享')
    return
  }
  await restoreRun()
})

onBeforeUnmount(() => {
  stopPolling()
  streamer?.abort()
})

// ===== 刷新恢复：本浏览器最近一次运行（runId 缓存在 localStorage） =====
const restoreRun = async () => {
  const runId = Number(localStorage.getItem(RUN_CACHE_KEY))
  if (!runId) return
  try {
    pollAbort = new AbortController()
    const res = await workflowShareRunState(token, runId, pollAbort.signal)
    const state = res.data
    if (!state) return
    applyRunState(state)
    if (state.status === 'RUNNING') {
      startPolling()
    } else if (state.status === 'COMPLETED' && state.output) {
      messages.value = [
        { role: 'USER', content: state.input || '' },
        { role: 'ASSISTANT', content: state.output }
      ].filter(m => m.content)
    }
  } catch (e) {
    if (e.name !== 'CanceledError' && e.name !== 'AbortError') {
      console.warn('恢复工作流运行状态失败', e)
    }
  }
}

const applyRunState = (state) => {
  run.value = {
    runId: state.runId,
    status: state.status,
    totalNodes: state.totalNodes,
    executedNodes: state.executedNodes,
    error: state.error,
    nodes: (state.nodes || []).map(n => ({
      nodeId: n.nodeId, nodeType: n.nodeType, nodeName: n.nodeName,
      status: n.status, iteration: n.iteration, durationMs: n.durationMs,
      error: n.error, output: n.output
    }))
  }
}

// ===== 轮询（刷新后运行仍在进行时） =====
const stopPolling = () => {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
  if (pollAbort) { pollAbort.abort(); pollAbort = null }
}

const startPolling = () => {
  stopPolling()
  pollTimer = setInterval(async () => {
    if (!run.value?.runId) { stopPolling(); return }
    try {
      pollAbort = new AbortController()
      const res = await workflowShareRunState(token, run.value.runId, pollAbort.signal)
      const state = res.data
      if (!state) { stopPolling(); return }
      applyRunState(state)
      if (state.status === 'COMPLETED' || state.status === 'FAILED' || state.status === 'CANCELLED') {
        stopPolling()
        if (state.status === 'COMPLETED' && state.output) {
          messages.value.push({ role: 'ASSISTANT', content: state.output })
        }
      }
    } catch (e) {
      if (e.name !== 'CanceledError' && e.name !== 'AbortError') {
        console.warn('轮询工作流状态失败', e)
      }
    }
  }, 2000)
}

// ===== SSE 事件处理（状态复位由事件驱动，不依赖 doStream 结束） =====
const upsertNode = (nodeId, patch) => {
  if (!run.value) return
  const nodes = run.value.nodes || []
  const idx = nodes.findIndex(n => n.nodeId === nodeId)
  if (idx >= 0) {
    nodes[idx] = { ...nodes[idx], ...patch }
  } else {
    nodes.push({
      nodeId, nodeType: '', nodeName: nodeId, status: 'PENDING',
      iteration: null, durationMs: null, error: null, output: null, ...patch
    })
  }
  run.value.nodes = nodes
}

const handleEvent = (event, data) => {
  confirming.value = false
  switch (event) {
    case 'workflow_start':
      run.value = {
        runId: data.runId, status: 'RUNNING',
        totalNodes: data.totalNodes || 0, executedNodes: 0, nodes: [], error: null
      }
      hitlPrompt.value = ''
      localStorage.setItem(RUN_CACHE_KEY, String(data.runId))
      break
    case 'node_start':
      upsertNode(data.nodeId, {
        nodeType: data.nodeType, nodeName: data.nodeName,
        iteration: data.iteration, status: 'RUNNING'
      })
      break
    case 'node_output':
      upsertNode(data.nodeId, {
        nodeType: data.nodeType, nodeName: data.nodeName, status: 'SUCCESS',
        durationMs: data.durationMs, iteration: data.iteration, output: data.output
      })
      if (run.value) run.value.executedNodes = (run.value.executedNodes || 0) + 1
      break
    case 'node_error':
      upsertNode(data.nodeId, { nodeName: data.nodeName, status: 'FAILED', error: data.error })
      break
    case 'node_hitl_pending':
      upsertNode(data.nodeId, { nodeName: data.nodeName, status: 'HITL_PENDING', output: { prompt: data.prompt } })
      if (run.value) run.value.status = 'PAUSED'
      hitlPrompt.value = data.prompt || ''
      streaming.value = false
      break
    case 'workflow_complete':
      if (run.value) {
        run.value.status = 'COMPLETED'
        run.value.executedNodes = data.executedNodes ?? run.value.executedNodes
      }
      if (data.output) messages.value.push({ role: 'ASSISTANT', content: data.output })
      streaming.value = false
      break
    case 'workflow_failed':
      if (run.value) {
        run.value.status = 'FAILED'
        run.value.error = data.error
      }
      messages.value.push({ role: 'ASSISTANT', content: `❌ 工作流执行失败：${data.error || '未知错误'}` })
      streaming.value = false
      break
    case 'workflow_cancelled':
      if (run.value) run.value.status = 'CANCELLED'
      streaming.value = false
      break
    default:
      break
  }
}

// ===== 发送运行 =====
const send = async () => {
  const text = input.value.trim()
  if (!text || streaming.value || !meta.value) return

  stopPolling()
  streamer?.abort()
  run.value = null
  hitlPrompt.value = ''
  messages.value.push({ role: 'USER', content: text })
  input.value = ''
  streaming.value = true

  streamer = streamWorkflowShareRun(
    token, text,
    (event, data) => handleEvent(event, data),
    () => { streaming.value = false },
    (err) => {
      streaming.value = false
      ElMessage.error('运行失败: ' + (err.message || ''))
    }
  )
  streamer.doStream()
}

// ===== HITL 确认：另开 SSE 续跑 =====
const onHitlConfirm = async (userInput) => {
  const runId = run.value?.runId
  if (!runId || confirming.value || streaming.value) return
  confirming.value = true
  hitlPrompt.value = ''
  streaming.value = true

  streamer = streamWorkflowShareHitl(
    token, runId, userInput,
    (event, data) => handleEvent(event, data),
    () => { streaming.value = false; confirming.value = false },
    (err) => {
      streaming.value = false
      confirming.value = false
      ElMessage.error('确认失败: ' + (err.message || ''))
    }
  )
  streamer.doStream()
}

const onCancel = async (runId) => {
  try {
    await workflowShareCancel(token, runId)
    ElMessage.success('工作流运行已取消')
    if (run.value) run.value.status = 'CANCELLED'
    streamer?.abort()
    streaming.value = false
  } catch (e) {
    ElMessage.error('取消失败: ' + (e.message || ''))
  }
}
</script>

<style scoped lang="scss">
.share-wf-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f5f7fa;
}
.swf-header {
  padding: 14px 20px;
  background: #fff;
  border-bottom: 1px solid #ebeef5;
}
.swf-title { display: flex; align-items: center; gap: 8px; }
.swf-name { font-size: 16px; font-weight: 600; color: #303133; }
.swf-desc { margin-top: 4px; font-size: 12px; color: #909399; }
.swf-body {
  flex: 1;
  display: flex;
  gap: 16px;
  padding: 16px 20px;
  overflow: hidden;
}
.swf-main {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.swf-empty { margin: auto; }
.swf-side {
  width: 340px;
  flex-shrink: 0;
  overflow-y: auto;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 14px;
}
.swf-msg {
  display: flex;
  &.is-user { justify-content: flex-end; }
  &.is-ai { justify-content: flex-start; }
}
.swf-bubble {
  max-width: 78%;
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 14px;
  line-height: 1.7;
  .is-user & { background: #409eff; color: #fff; }
  .is-ai & { background: #fff; border: 1px solid #ebeef5; color: #303133; }
}
.swf-text { white-space: pre-wrap; word-break: break-word; }
.swf-md {
  word-break: break-word;
  :deep(p) { margin: 0 0 8px; }
  :deep(p:last-child) { margin-bottom: 0; }
  :deep(pre) { background: #f6f8fa; padding: 10px; border-radius: 6px; overflow-x: auto; }
  :deep(code) { font-size: 13px; }
}
.swf-streaming {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #909399;
}
.swf-inputbar {
  display: flex;
  gap: 10px;
  padding: 14px 20px;
  background: #fff;
  border-top: 1px solid #ebeef5;
  align-items: flex-end;
}
</style>
