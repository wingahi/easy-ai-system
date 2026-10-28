<template>
  <div class="wf-editor">
    <div class="editor-header">
      <el-button link :icon="ArrowLeft" @click="back">返回</el-button>
      <span class="wf-name">{{ workflow.name || '工作流' }}</span>
      <el-tag size="small" type="info">v{{ workflow.version || 1 }}</el-tag>
      <el-tag v-if="running" size="small" type="warning">运行中</el-tag>
      <div class="spacer" />
      <el-button :icon="CircleCheck" @click="validateGraph">校验</el-button>
      <el-button :icon="Document" :loading="saving" @click="save">保存</el-button>
      <el-button type="primary" :icon="VideoPlay" :loading="running" @click="startRun">运行</el-button>
    </div>

    <div class="editor-body">
      <!-- 左侧节点面板 -->
      <aside class="palette">
        <div class="palette-title">节点</div>
        <div v-for="item in palette" :key="item.type" class="palette-item" @click="addNode(item.type)">
          <span class="p-icon" :style="{ background: item.color }">{{ item.icon }}</span>
          <span class="p-label">{{ item.label }}</span>
        </div>
        <div class="palette-hint">点击添加节点；从节点右侧圆点拖出连线</div>
      </aside>

      <!-- 画布 -->
      <div ref="canvasRef" class="canvas">
        <VueFlow
          v-model:nodes="nodes"
          v-model:edges="edges"
          :node-types="nodeTypes"
          :min-zoom="0.3"
          :max-zoom="2"
          fit-view-on-init
          @connect="onConnect"
          @node-click="onNodeClick"
          @pane-click="selectedNodeId = ''">
          <Background :gap="16" />
          <Controls />
        </VueFlow>
      </div>

      <!-- 右侧面板 -->
      <aside class="side">
        <el-tabs v-model="activeTab" class="side-tabs">
          <el-tab-pane label="节点配置" name="config">
            <div v-if="!selectedNode" class="empty-hint">在画布上选择一个节点进行配置</div>
            <template v-else>
              <div class="cfg-head">
                <el-tag size="small" :color="typeColor(selectedNode.data.nodeType)" effect="dark" style="border:none">
                  {{ typeLabel(selectedNode.data.nodeType) }}
                </el-tag>
                <span class="cfg-id">{{ selectedNode.id }}</span>
                <el-popconfirm title="删除该节点及其连线？" @confirm="deleteNode">
                  <template #reference><el-button link type="danger" size="small">删除</el-button></template>
                </el-popconfirm>
              </div>

              <div class="field">
                <label>节点名称</label>
                <el-input v-model="selectedNode.data.name" size="small" />
              </div>

              <!-- START -->
              <template v-if="selectedNode.data.nodeType === 'START'">
                <div class="empty-hint">运行输入可通过 <code v-text="INPUT_REF"></code> 引用</div>
              </template>

              <!-- END -->
              <template v-else-if="selectedNode.data.nodeType === 'END'">
                <div class="field">
                  <label>最终输出模板</label>
                  <el-input v-model="cfg.output" type="textarea" :rows="4" placeholder="如：{{input}} 的处理结果：{{n_llm_1.text}}" />
                </div>
              </template>

              <!-- LLM -->
              <template v-else-if="selectedNode.data.nodeType === 'LLM'">
                <div class="field">
                  <label>模型</label>
                  <el-select v-model="cfg.modelId" size="small" style="width:100%" placeholder="选择大模型">
                    <el-option v-for="m in chatModels" :key="m.id" :label="`${m.name} (${m.modelName})`" :value="m.id" />
                  </el-select>
                </div>
                <div class="field">
                  <label>System 提示词</label>
                  <el-input v-model="cfg.systemPrompt" type="textarea" :rows="3" placeholder="可选" />
                </div>
                <div class="field">
                  <label>User Prompt</label>
                  <el-input v-model="cfg.prompt" type="textarea" :rows="6"
                            placeholder="支持变量：{{input}}、{{n_rag_1.text}}" />
                </div>
              </template>

              <!-- RETRIEVAL -->
              <template v-else-if="selectedNode.data.nodeType === 'RETRIEVAL'">
                <div class="field">
                  <label>知识库</label>
                  <el-select v-model="cfg.kbIds" multiple size="small" style="width:100%" placeholder="选择知识库">
                    <el-option v-for="kb in kbOptions" :key="kb.id" :label="kb.name" :value="kb.id" />
                  </el-select>
                </div>
                <div class="field">
                  <label>检索查询</label>
                  <el-input v-model="cfg.query" size="small" placeholder="留空则使用 {{input}}" />
                </div>
                <div class="field">
                  <label>Top K</label>
                  <el-input-number v-model="cfg.topK" :min="1" :max="20" size="small" style="width:120px" />
                </div>
              </template>

              <!-- TOOL -->
              <template v-else-if="selectedNode.data.nodeType === 'TOOL'">
                <div class="field">
                  <label>工具</label>
                  <el-select v-model="cfg.toolId" size="small" style="width:100%" placeholder="选择工具">
                    <el-option v-for="t in tools" :key="t.id" :label="`${t.name} (${t.toolType})`" :value="t.id" />
                  </el-select>
                </div>
                <div class="field">
                  <label>参数（JSON，值支持变量）</label>
                  <el-input v-model="toolArgsText" type="textarea" :rows="5"
                            placeholder='{ "city": "{{input}}" }' />
                </div>
              </template>

              <!-- CONDITION -->
              <template v-else-if="selectedNode.data.nodeType === 'CONDITION'">
                <div class="field">
                  <label>条件表达式</label>
                  <el-input v-model="cfg.expression" size="small"
                            placeholder='{{n_llm_1.text}} contains "通过"' />
                </div>
                <div class="empty-hint">
                  支持 == / != / &gt; / &lt; / &gt;= / &lt;= / contains / is_empty。<br />
                  连线时请分别连到 true / false 两个出口。
                </div>
              </template>

              <!-- LOOP -->
              <template v-else-if="selectedNode.data.nodeType === 'LOOP'">
                <div class="field">
                  <label>最大轮次</label>
                  <el-input-number v-model="cfg.maxIterations" :min="1" :max="20" size="small" style="width:120px" />
                </div>
                <div class="field">
                  <label>中断条件（可选）</label>
                  <el-input v-model="cfg.breakCondition" size="small"
                            placeholder='{{loop.iteration}} >= 3' />
                </div>
                <div class="empty-hint">循环体连线从 loop 出口出发，末尾连回本节点；结束后从 done 出口继续。</div>
              </template>

              <!-- HITL -->
              <template v-else-if="selectedNode.data.nodeType === 'HITL'">
                <div class="field">
                  <label>确认提示语</label>
                  <el-input v-model="cfg.prompt" type="textarea" :rows="4" placeholder="请确认是否继续执行…" />
                </div>
                <div class="empty-hint">运行到该节点会暂停，等待人工输入后从后续节点继续。</div>
              </template>

              <!-- VARIABLE -->
              <template v-else-if="selectedNode.data.nodeType === 'VARIABLE'">
                <div class="field">
                  <label>变量赋值</label>
                  <div v-for="(item, idx) in assignments" :key="idx" class="kv-row">
                    <el-input v-model="item.name" size="small" placeholder="变量名" style="width:110px" />
                    <el-input v-model="item.value" size="small" placeholder="值 / 变量模板" />
                    <el-button link type="danger" size="small" @click="removeAssignment(idx)">×</el-button>
                  </div>
                  <el-button link type="primary" size="small" @click="addAssignment">+ 添加变量</el-button>
                </div>
                <div class="empty-hint">赋值后可通过 <code v-text="VARS_REF"></code> 引用。</div>
              </template>

              <el-divider content-position="left">失败策略</el-divider>
              <div class="field">
                <el-radio-group v-model="selectedNode.data.onError" size="small">
                  <el-radio value="STOP">失败即终止</el-radio>
                  <el-radio value="CONTINUE">忽略错误继续</el-radio>
                </el-radio-group>
              </div>
            </template>
          </el-tab-pane>

          <el-tab-pane label="运行调试" name="run">
            <div class="field">
              <label>运行输入</label>
              <el-input v-model="runInput" type="textarea" :rows="3" placeholder="输入测试内容" />
            </div>
            <div class="run-actions">
              <el-button type="primary" size="small" :icon="VideoPlay" :loading="running" @click="startRun">执行</el-button>
              <el-button size="small" :disabled="!running || !currentRunId" @click="cancelRun">取消</el-button>
            </div>

            <el-alert v-if="runError" :title="runError" type="error" :closable="false" show-icon style="margin-top:10px" />

            <div class="trace-title">节点轨迹</div>
            <div class="trace-list">
              <div v-if="!trace.length" class="empty-hint">点击「执行」查看实时节点轨迹</div>
              <div v-for="t in trace" :key="t.key" class="trace-item" @click="selectTraceNode(t.nodeId)">
                <el-tag size="small" :type="traceTagType(t.status)" effect="dark">{{ t.status }}</el-tag>
                <span class="t-name">{{ t.nodeName }}</span>
                <span class="t-dur">{{ t.durationMs != null ? t.durationMs + ' ms' : '' }}</span>
                <div v-if="t.preview" class="t-preview">{{ t.preview }}</div>
                <div v-if="t.error" class="t-error">{{ t.error }}</div>
              </div>
            </div>

            <template v-if="finalOutput">
              <div class="trace-title">最终输出</div>
              <div class="final-output">{{ finalOutput }}</div>
            </template>
          </el-tab-pane>
        </el-tabs>
      </aside>
    </div>

    <!-- HITL 确认 -->
    <el-dialog v-model="hitlVisible" title="人工确认" width="520px"
               :close-on-click-modal="false" :close-on-press-escape="false">
      <div class="hitl-prompt">{{ hitlPrompt || '流程需要人工确认后继续执行' }}</div>
      <el-input v-model="hitlInput" type="textarea" :rows="4" placeholder="输入确认内容（可为空）" style="margin-top:12px" />
      <template #footer>
        <el-button @click="deferHitl">稍后处理</el-button>
        <el-button type="primary" :loading="confirming" @click="confirmHitl">确认并继续</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'

import { computed, markRaw, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, CircleCheck, Document, VideoPlay } from '@element-plus/icons-vue'
import { VueFlow, MarkerType, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import WorkflowNodeCard from '../../components/WorkflowNodeCard.vue'
import {
  chatModelList, kbList, toolList,
  workflowGet, workflowUpdate, workflowValidate, workflowPageRuns,
  workflowRunState, workflowCancelRun, streamWorkflowRun, streamWorkflowResume
} from '../../api'

const route = useRoute()
const router = useRouter()
const workflowId = Number(route.params.id)

const nodeTypes = { wf: markRaw(WorkflowNodeCard) }
const { screenToFlowCoordinate, getNodes, getEdges, fitView } = useVueFlow()

const workflow = reactive({ id: workflowId, name: '', description: '', version: 1 })
const nodes = ref([])
const edges = ref([])
const saving = ref(false)

const canvasRef = ref(null)
const chatModels = ref([])
const kbOptions = ref([])
const tools = ref([])

const activeTab = ref('config')
const selectedNodeId = ref('')
const selectedNode = computed(() => nodes.value.find(n => n.id === selectedNodeId.value) || null)
const cfg = computed(() => (selectedNode.value ? selectedNode.value.data.config : {}))

const palette = [
  { type: 'START', label: '开始', icon: '▶', color: '#67c23a' },
  { type: 'LLM', label: '大模型', icon: '✨', color: '#409eff' },
  { type: 'RETRIEVAL', label: '知识库检索', icon: '🔍', color: '#e6a23c' },
  { type: 'TOOL', label: '工具调用', icon: '🔧', color: '#8e44ad' },
  { type: 'CONDITION', label: '条件分支', icon: '◆', color: '#f56c6c' },
  { type: 'LOOP', label: '循环', icon: '↻', color: '#16a085' },
  { type: 'HITL', label: '人工确认', icon: '👤', color: '#d46b08' },
  { type: 'VARIABLE', label: '变量赋值', icon: '𝑥', color: '#606266' },
  { type: 'END', label: '结束', icon: '■', color: '#909399' }
]

const PALETTE_META = Object.fromEntries(palette.map(p => [p.type, p]))
const typeLabel = (t) => (PALETTE_META[t]?.label || t)
const typeColor = (t) => (PALETTE_META[t]?.color || '#909399')

// ============ 运行状态 ============
const runInput = ref('')
const running = ref(false)
const currentRunId = ref(null)
const trace = ref([])
const finalOutput = ref('')
const runError = ref('')
const hitlVisible = ref(false)
const hitlPrompt = ref('')
const hitlInput = ref('')
const confirming = ref(false)

let streamHandle = null
let pollTimer = null
let nodeSeq = 1

// ============ 配置表单辅助 ============
const INPUT_REF = '{{input}}'
const VARS_REF = '{{vars.变量名}}'

const toolArgsText = computed({
  get() {
    const args = cfg.value?.arguments
    try {
      return args ? JSON.stringify(args, null, 2) : '{}'
    } catch {
      return '{}'
    }
  },
  set(val) {
    if (!selectedNode.value) return
    try {
      selectedNode.value.data.config.arguments = val.trim() ? JSON.parse(val) : {}
    } catch {
      // 输入过程中的非法 JSON 不写回，避免配置丢失
    }
  }
})

const assignments = computed(() => {
  if (!selectedNode.value) return []
  if (!Array.isArray(selectedNode.value.data.config.assignments)) {
    selectedNode.value.data.config.assignments = []
  }
  return selectedNode.value.data.config.assignments
})
const addAssignment = () => assignments.value.push({ name: '', value: '' })
const removeAssignment = (idx) => assignments.value.splice(idx, 1)

// ============ 加载 ============
const loadWorkflow = async () => {
  const res = await workflowGet(workflowId)
  const wf = res.data
  Object.assign(workflow, {
    id: wf.id, name: wf.name, description: wf.description, version: wf.version
  })
  const graph = parseGraph(wf.graphJson)
  nodes.value = graph.nodes.map(toFlowNode)
  edges.value = graph.edges.map(toFlowEdge)
  nodeSeq = nodes.value.length + 1
  setTimeout(() => { try { fitAll() } catch { /* 画布尚未初始化 */ } }, 100)
}

const loadOptions = async () => {
  const [m, k, t] = await Promise.all([chatModelList(), kbList(), toolList()])
  chatModels.value = m.data || []
  kbOptions.value = k.data || []
  tools.value = t.data || []
}

const parseGraph = (graphJson) => {
  try {
    const g = JSON.parse(graphJson || '{}')
    return { nodes: g.nodes || [], edges: g.edges || [] }
  } catch {
    return { nodes: [], edges: [] }
  }
}

const toFlowNode = (n) => ({
  id: n.id,
  type: 'wf',
  position: n.position || { x: 100, y: 100 },
  data: {
    name: n.name || n.type,
    nodeType: n.type,
    config: n.config || {},
    onError: n.onError || 'STOP',
    status: 'IDLE'
  }
})

const toFlowEdge = (e) => ({
  id: e.id || `e_${e.source}_${e.target}`,
  source: e.source,
  target: e.target,
  sourceHandle: e.sourceHandle || 'default',
  type: 'smoothstep',
  markerEnd: MarkerType.ArrowClosed
})

const fitAll = () => {
  try {
    fitView?.({ padding: 0.2 })
  } catch { /* 画布尚未初始化 */ }
}

// ============ 画布操作 ============
const onConnect = (conn) => {
  // 同一对节点 + 同一出口不重复连线
  const exists = edges.value.some(e =>
    e.source === conn.source && e.target === conn.target &&
    (e.sourceHandle || 'default') === (conn.sourceHandle || 'default'))
  if (exists) return
  edges.value.push({
    id: `e_${conn.source}_${conn.target}_${conn.sourceHandle || 'default'}_${Date.now()}`,
    source: conn.source,
    target: conn.target,
    sourceHandle: conn.sourceHandle || 'default',
    type: 'smoothstep',
    markerEnd: MarkerType.ArrowClosed
  })
}

const onNodeClick = ({ node }) => {
  selectedNodeId.value = node.id
  activeTab.value = 'config'
}

const addNode = (type) => {
  const id = genId(type)
  const meta = PALETTE_META[type]
  let position = { x: 160, y: 120 + (nodes.value.length % 6) * 90 }
  try {
    const rect = canvasRef.value?.getBoundingClientRect()
    if (rect) {
      position = screenToFlowCoordinate({
        x: rect.left + rect.width / 2,
        y: rect.top + rect.height / 3
      })
    }
  } catch { /* 使用默认位置 */ }
  nodes.value.push({
    id,
    type: 'wf',
    position,
    data: {
      name: meta.label,
      nodeType: type,
      config: defaultConfig(type),
      onError: 'STOP',
      status: 'IDLE'
    }
  })
  selectedNodeId.value = id
  activeTab.value = 'config'
}

const defaultConfig = (type) => {
  if (type === 'RETRIEVAL') return { kbIds: [], query: '', topK: 5 }
  if (type === 'CONDITION') return { expression: '' }
  if (type === 'LOOP') return { maxIterations: 3, breakCondition: '' }
  if (type === 'VARIABLE') return { assignments: [] }
  if (type === 'END') return { output: '{{input}}' }
  if (type === 'LLM') return { systemPrompt: '', prompt: '{{input}}' }
  if (type === 'TOOL') return { toolId: null, arguments: {} }
  if (type === 'HITL') return { prompt: '请确认是否继续执行' }
  return {}
}

const genId = (type) => {
  let id
  do {
    id = `n_${type.toLowerCase()}_${nodeSeq++}`
  } while (nodes.value.some(n => n.id === id))
  return id
}

const deleteNode = () => {
  const id = selectedNodeId.value
  if (!id) return
  nodes.value = nodes.value.filter(n => n.id !== id)
  edges.value = edges.value.filter(e => e.source !== id && e.target !== id)
  selectedNodeId.value = ''
}

// ============ 保存 / 校验 ============
const buildGraph = () => {
  const storeNodes = getNodes?.value?.length ? getNodes.value : nodes.value
  const storeEdges = getEdges?.value?.length ? getEdges.value : edges.value
  return {
    nodes: storeNodes.map(n => ({
      id: n.id,
      type: n.data.nodeType,
      name: n.data.name,
      position: { x: Math.round(n.position.x), y: Math.round(n.position.y) },
      config: n.data.config || {},
      onError: n.data.onError || 'STOP'
    })),
    edges: storeEdges.map(e => ({
      id: e.id,
      source: e.source,
      target: e.target,
      sourceHandle: e.sourceHandle || 'default'
    }))
  }
}

const save = async (silent = false) => {
  saving.value = true
  try {
    const res = await workflowUpdate(workflowId, {
      name: workflow.name,
      description: workflow.description,
      graphJson: JSON.stringify(buildGraph())
    })
    workflow.version = res.data.version
    if (!silent) ElMessage.success('已保存 v' + res.data.version)
  } finally {
    saving.value = false
  }
}

const validateGraph = async () => {
  try {
    await save(true)
    const res = await workflowValidate(workflowId)
    const errors = res.data || []
    if (!errors.length) {
      ElMessage.success('校验通过')
      return
    }
    ElMessageBox.alert(errors.map((e, i) => `${i + 1}. ${e}`).join('<br/>'), '校验未通过', {
      dangerouslyUseHTMLString: true
    })
  } catch (e) {
    // 拦截器已提示
  }
}

// ============ 运行 ============
const startRun = async () => {
  if (running.value) return
  runError.value = ''
  finalOutput.value = ''
  trace.value = []
  running.value = true
  activeTab.value = 'run'
  resetAllStatus()

  try {
    await save(true)
  } catch {
    running.value = false
    return
  }

  streamHandle = streamWorkflowRun(workflowId, runInput.value, handleEvent,
    () => { running.value = false },
    (err) => {
      running.value = false
      runError.value = err?.message || '运行失败'
    })
  // 注意：不 await doStream —— 开发环境 vite 代理对 SSE 终止帧的转发不可靠，
  // 所有运行状态一律以 SSE 事件驱动复位，避免流结束信号延迟导致 UI 卡死
  streamHandle.doStream()
}

const confirmHitl = () => {
  confirming.value = true
  hitlVisible.value = false
  running.value = true
  streamHandle = streamWorkflowResume(currentRunId.value, hitlInput.value,
    (event, payload) => {
      // 收到首个事件即视为后端已受理，解除按钮 loading（不依赖流结束信号）
      confirming.value = false
      handleEvent(event, payload)
    },
    () => {
      confirming.value = false
      running.value = false
    },
    (err) => {
      confirming.value = false
      running.value = false
      runError.value = err?.message || '继续执行失败'
    })
  streamHandle.doStream()
}

/** 稍后处理：关闭确认弹窗，运行保持 PAUSED，重新进入页面时可从断点恢复确认 */
const deferHitl = () => {
  hitlVisible.value = false
  stopPolling()
  ElMessage.info('流程保持暂停，重新进入本页可继续确认')
}

const cancelRun = async () => {
  if (!currentRunId.value) return
  streamHandle?.abort()
  await workflowCancelRun(currentRunId.value)
  running.value = false
  stopPolling()
  markRemainingSkipped()
  ElMessage.info('已取消运行')
}

const runningEntries = new Map()

const handleEvent = (event, payload) => {
  switch (event) {
    case 'workflow_start':
      currentRunId.value = payload.runId
      running.value = true
      break
    case 'node_start': {
      const key = `${payload.nodeId}_${trace.value.length}_${Date.now()}`
      trace.value.push({
        key,
        nodeId: payload.nodeId,
        nodeName: payload.nodeName,
        status: 'RUNNING',
        iteration: payload.iteration
      })
      runningEntries.set(payload.nodeId, key)
      setNodeStatus(payload.nodeId, 'RUNNING')
      break
    }
    case 'node_output': {
      const entry = findEntry(payload.nodeId)
      if (entry) {
        entry.status = 'SUCCESS'
        entry.durationMs = payload.durationMs
        entry.preview = previewOf(payload.output)
      }
      setNodeStatus(payload.nodeId, 'SUCCESS')
      break
    }
    case 'node_error': {
      const entry = findEntry(payload.nodeId)
      if (entry) {
        entry.status = 'FAILED'
        entry.error = payload.error
      }
      setNodeStatus(payload.nodeId, 'FAILED')
      break
    }
    case 'node_hitl_pending':
      setNodeStatus(payload.nodeId, 'HITL_PENDING')
      currentRunId.value = payload.runId
      hitlPrompt.value = payload.prompt
      hitlInput.value = ''
      hitlVisible.value = true
      running.value = false
      break
    case 'workflow_complete':
      running.value = false
      finalOutput.value = payload.output || ''
      stopPolling()
      break
    case 'workflow_failed':
      running.value = false
      runError.value = payload.error || '运行失败'
      stopPolling()
      break
    case 'workflow_cancelled':
      running.value = false
      stopPolling()
      break
    default:
      break
  }
}

const findEntry = (nodeId) => {
  const key = runningEntries.get(nodeId)
  if (key) {
    const entry = trace.value.find(t => t.key === key)
    if (entry) return entry
  }
  return [...trace.value].reverse().find(t => t.nodeId === nodeId)
}

const previewOf = (output) => {
  if (!output) return ''
  const text = output.text
  if (text != null) {
    const s = String(text).replace(/\s+/g, ' ').trim()
    return s.length > 120 ? s.slice(0, 120) + '…' : s
  }
  try {
    const s = JSON.stringify(output)
    return s.length > 120 ? s.slice(0, 120) + '…' : s
  } catch {
    return ''
  }
}

const setNodeStatus = (nodeId, status) => {
  const node = nodes.value.find(n => n.id === nodeId)
  if (node) node.data.status = status
}

const resetAllStatus = () => nodes.value.forEach(n => { n.data.status = 'IDLE' })

const markRemainingSkipped = () => {
  trace.value.forEach(t => {
    if (t.status === 'RUNNING') t.status = 'SKIPPED'
  })
  nodes.value.forEach(n => {
    if (n.data.status === 'RUNNING' || n.data.status === 'HITL_PENDING') n.data.status = 'SKIPPED'
  })
}

const selectTraceNode = (nodeId) => {
  selectedNodeId.value = nodeId
  activeTab.value = 'config'
}

const traceTagType = (s) => ({
  SUCCESS: 'success', FAILED: 'danger', RUNNING: 'warning',
  HITL_PENDING: 'warning', SKIPPED: 'info'
}[s] || 'info')

// ============ 刷新恢复 ============
const restoreRun = async () => {
  try {
    const res = await workflowPageRuns(workflowId, { page: 1, size: 1 })
    const latest = (res.data.records || [])[0]
    if (!latest) return
    if (!['RUNNING', 'PAUSED'].includes(latest.status)) return
    currentRunId.value = latest.id
    runInput.value = latest.inputText || ''
    await syncRunState(latest.id)
    if (latest.status === 'RUNNING') {
      running.value = true
      startPolling(latest.id)
    }
  } catch {
    // 忽略恢复失败
  }
}

const syncRunState = async (runId) => {
  const res = await workflowRunState(runId)
  const state = res.data
  ;(state.nodes || []).forEach(n => {
    setNodeStatus(n.nodeId, n.status)
    if (!trace.value.some(t => t.nodeId === n.nodeId && t.key === `restore_${n.id}`)) {
      trace.value.push({
        key: `restore_${n.id}`,
        nodeId: n.nodeId,
        nodeName: n.nodeName,
        status: n.status,
        durationMs: n.durationMs,
        preview: previewOf(safeParse(n.output)),
        error: n.error
      })
    }
  })
  if (state.output) finalOutput.value = state.output
  if (state.status === 'PAUSED') {
    const pending = (state.nodes || []).find(n => n.status === 'HITL_PENDING')
    if (pending) {
      let prompt = '流程需要人工确认后继续执行'
      try {
        const input = safeParse(pending.input)
        prompt = input?.prompt || prompt
      } catch { /* 忽略 */ }
      hitlPrompt.value = prompt
      hitlInput.value = ''
      // 延迟到组件渲染稳定后再打开弹窗，避免挂载周期内 overlay 渲染错位（display:none）
      nextTick(() => { hitlVisible.value = true })
    }
  }
  return state
}

const safeParse = (s) => {
  try {
    return typeof s === 'string' ? JSON.parse(s) : s
  } catch {
    return null
  }
}

const startPolling = (runId) => {
  stopPolling()
  pollTimer = setInterval(async () => {
    try {
      const state = await syncRunState(runId)
      if (!['RUNNING', 'PAUSED'].includes(state.status)) {
        running.value = false
        stopPolling()
      }
    } catch {
      stopPolling()
    }
  }, 2000)
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// ============ 生命周期 ============
const back = () => router.push('/workflows')

onMounted(async () => {
  await Promise.all([loadWorkflow(), loadOptions()])
  await restoreRun()
})

onBeforeUnmount(() => {
  streamHandle?.abort()
  stopPolling()
})
</script>

<style scoped lang="scss">
.wf-editor {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 56px);
  background: #f5f7fa;
}
.editor-header {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 52px;
  padding: 0 16px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  .wf-name { font-size: 15px; font-weight: 600; }
  .spacer { flex: 1; }
}
.editor-body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.palette {
  width: 150px;
  background: #fff;
  border-right: 1px solid #e4e7ed;
  padding: 12px 10px;
  overflow-y: auto;
  .palette-title {
    font-size: 12px;
    color: #909399;
    margin-bottom: 8px;
  }
  .palette-item {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 7px 8px;
    border-radius: 6px;
    cursor: pointer;
    font-size: 13px;
    &:hover { background: #f0f6ff; }
  }
  .p-icon {
    width: 22px;
    height: 22px;
    border-radius: 5px;
    color: #fff;
    font-size: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  .palette-hint {
    margin-top: 14px;
    font-size: 11px;
    color: #b1b3b8;
    line-height: 1.5;
  }
}
.canvas {
  flex: 1;
  min-width: 0;
  height: 100%;
}
.side {
  width: 360px;
  background: #fff;
  border-left: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  .side-tabs {
    height: 100%;
    display: flex;
    flex-direction: column;
    :deep(.el-tabs__content) {
      flex: 1;
      overflow-y: auto;
      padding: 0 14px 14px;
    }
    :deep(.el-tabs__header) { margin: 0 0 10px; padding: 0 10px; }
  }
}
.cfg-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  .cfg-id { font-size: 11px; color: #909399; font-family: monospace; }
  .el-button { margin-left: auto; }
}
.field {
  margin-bottom: 12px;
  label {
    display: block;
    font-size: 12px;
    color: #606266;
    margin-bottom: 4px;
  }
}
.kv-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.empty-hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  code { background: #f4f4f5; padding: 1px 4px; border-radius: 3px; }
}
.run-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.trace-title {
  font-size: 12px;
  color: #909399;
  margin: 14px 0 6px;
}
.trace-list { max-height: 42vh; overflow-y: auto; }
.trace-item {
  padding: 7px 8px;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  margin-bottom: 6px;
  cursor: pointer;
  font-size: 12px;
  &:hover { border-color: #c6e2ff; background: #fafcff; }
  .t-name { margin-left: 8px; font-weight: 500; }
  .t-dur { float: right; color: #909399; }
  .t-preview {
    margin-top: 4px;
    color: #606266;
    word-break: break-all;
  }
  .t-error { margin-top: 4px; color: #f56c6c; word-break: break-all; }
}
.final-output {
  background: #f7f8fa;
  border-radius: 6px;
  padding: 10px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 30vh;
  overflow-y: auto;
}
.hitl-prompt {
  font-size: 13px;
  color: #303133;
  background: #fff7e6;
  border: 1px solid #ffe0a3;
  border-radius: 6px;
  padding: 10px;
}
</style>
