<template>
  <div class="page-container">
    <div class="page-header">
      <h2>多 Agent 协作</h2>
      <div style="display:flex;gap:8px">
        <el-button :icon="VideoPlay" @click="goPlayground()">运行调试</el-button>
        <el-button type="primary" :icon="Plus" @click="openDialog()">新建 Crew</el-button>
      </div>
    </div>

    <div class="card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="Crew 名称" min-width="150" />
        <el-table-column prop="description" label="描述" show-overflow-tooltip min-width="160" />
        <el-table-column label="协作模式" width="170">
          <template #default="{ row }">
            <el-tag size="small" :type="modeColor(row.executionMode)">{{ modeLabel(row.executionMode) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Agents" min-width="180">
          <template #default="{ row }">
            <template v-if="parseAgents(row.agentsJson).length">
              <el-tag v-for="a in parseAgents(row.agentsJson)" :key="a.agentId" size="small"
                      effect="plain" style="margin:2px 4px 2px 0">
                {{ a.name || a.agentId }}
              </el-tag>
            </template>
            <span v-else style="color:#c0c4cc">未配置</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="170" />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button link type="primary" @click="goPlayground(row)">运行调试</el-button>
              <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
              <el-button link type="warning" @click="validate(row)">校验</el-button>
              <el-popconfirm title="确认删除该 Crew？" @confirm="del(row)">
                <template #reference><el-button link type="danger">删除</el-button></template>
              </el-popconfirm>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 创建/编辑 -->
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑 Crew' : '新建 Crew'" width="920px" top="4vh">
      <el-form label-width="120px">
        <el-form-item label="Crew 名称" required>
          <el-input v-model="form.name" maxlength="100" placeholder="如：代码-测试-修复小组" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
        <el-form-item label="协作模式" required>
          <el-select v-model="form.executionMode" style="width:100%" @change="onModeChange">
            <el-option v-for="m in MODES" :key="m.value" :label="m.label" :value="m.value" />
          </el-select>
          <div class="hint">{{ currentModeHint }}</div>
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>

        <!-- ============ 模式配置 ============ -->
        <el-divider content-position="left">{{ modeLabel(form.executionMode) }} 配置</el-divider>

        <!-- Crew 串行 / 并行 -->
        <template v-if="isCrewMode">
          <el-form-item v-if="form.executionMode === 'CREW_HIERARCHICAL'" label="Manager Agent">
            <el-select v-model="form.managerAgentId" clearable style="width:100%" placeholder="留空则自动选 role 含 manager/leader 的 Agent">
              <el-option v-for="a in form.agents" :key="a.agentId" :label="`${a.name}（${a.role || a.agentId}）`" :value="a.agentId" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="form.executionMode === 'CREW_PARALLEL'" label="并行 DAG">
            <el-input v-model="form.graphJson" type="textarea" :rows="8"
                      placeholder='{"nodes":[{"id":"start","type":"START"},...],"edges":[...]}' />
            <div class="hint">
              未配置时后端按 Agents 顺序退化为串行链。
              <el-button link type="primary" @click="genParallelGraph">生成并行扇出图</el-button>
            </div>
          </el-form-item>
          <div v-if="form.executionMode === 'CREW_SEQUENTIAL'" class="hint" style="margin:0 0 18px 120px">
            留空编排，后端按 Agents 列表顺序自动串行执行。
          </div>
        </template>

        <!-- 状态图 -->
        <template v-if="form.executionMode === 'GRAPH_ROUTED'">
          <el-form-item label="状态图 JSON" required>
            <el-input v-model="form.graphJson" type="textarea" :rows="12"
                      placeholder='{"nodes":[{"id":"start","type":"START"},{"id":"a1","type":"AGENT","agentId":"researcher"},{"id":"end","type":"END"}],"edges":[{"source":"start","target":"a1"},{"source":"a1","target":"end"}]}' />
            <div class="hint">
              节点类型：START / AGENT / CONDITION / LOOP / HITL / END；CONDITION 用 sourceHandle=true/false，LOOP 用 sourceHandle=loop/done。
              <el-button link type="primary" @click="genSequentialGraph">插入串行示例</el-button>
            </div>
          </el-form-item>
        </template>

        <!-- GroupChat -->
        <template v-if="isGroupChat">
          <el-form-item label="最大对话轮次">
            <el-input-number v-model="form.groupRounds" :min="1" :max="50" />
          </el-form-item>
          <el-form-item label="终止条件">
            <el-input v-model="form.terminationCriteria" placeholder='如 {{lastOutput}} contains "TERMINATE"' />
            <div class="hint">留空则仅按最大轮次 / 连续重复回复终止；消息含 TERMINATE 始终会终止。</div>
          </el-form-item>
          <el-form-item label="汇总 Agent">
            <el-select v-model="form.summarizerAgentId" clearable style="width:100%" placeholder="留空=取最后一个发言 Agent">
              <el-option v-for="a in form.agents" :key="a.agentId" :label="`${a.name}（${a.role || a.agentId}）`" :value="a.agentId" />
            </el-select>
          </el-form-item>
          <template v-if="form.executionMode === 'GROUP_CHAT_DYNAMIC_ROUTER'">
            <el-form-item label="路由模型">
              <el-select v-model="form.routerModelId" clearable style="width:100%" placeholder="留空则用第一个 Agent 的模型">
                <el-option v-for="m in chatModels" :key="m.id" :label="modelLabel(m)" :value="m.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="路由 Prompt">
              <el-input v-model="form.routerPrompt" type="textarea" :rows="4"
                        placeholder="自定义 LLM 路由指令，留空使用内置模板" />
            </el-form-item>
          </template>
        </template>

        <!-- ============ Agents ============ -->
        <el-divider content-position="left">Agent 列表（{{ form.agents.length }}）</el-divider>
        <div class="agent-list">
          <div v-for="(a, idx) in form.agents" :key="idx" class="agent-card">
            <div class="agent-card-header">
              <span class="agent-title">🤖 {{ a.name || a.agentId || ('Agent ' + (idx + 1)) }}</span>
              <div>
                <el-tag size="small" effect="plain">{{ a.role || '未设角色' }}</el-tag>
                <el-button link type="danger" style="margin-left:8px" @click="removeAgent(idx)">删除</el-button>
              </div>
            </div>
            <div class="agent-grid">
              <div class="field">
                <label>Agent ID</label>
                <el-input v-model="a.agentId" placeholder="唯一 ID，如 researcher" />
              </div>
              <div class="field">
                <label>名称</label>
                <el-input v-model="a.name" placeholder="显示名，如 研究员" />
              </div>
              <div class="field">
                <label>角色</label>
                <el-input v-model="a.role" placeholder="role，如 researcher" />
              </div>
              <div class="field">
                <label>对话模型</label>
                <el-select v-model="a.chatModelId" clearable filterable style="width:100%" placeholder="选择大模型">
                  <el-option v-for="m in chatModels" :key="m.id" :label="modelLabel(m)" :value="m.id" />
                </el-select>
              </div>
              <div class="field full">
                <label>目标</label>
                <el-input v-model="a.goal" placeholder="该 Agent 的核心目标" />
              </div>
              <div class="field full">
                <label>System Prompt</label>
                <el-input v-model="a.systemPrompt" type="textarea" :rows="3" placeholder="留空则由角色 + 目标自动构造" />
              </div>
              <div class="field">
                <label>知识库</label>
                <el-select v-model="a.kbIds" multiple clearable style="width:100%" placeholder="可选">
                  <el-option v-for="kb in kbs" :key="kb.id" :label="kb.name" :value="kb.id" />
                </el-select>
              </div>
              <div class="field">
                <label>工具</label>
                <el-select v-model="a.toolIds" multiple clearable style="width:100%" placeholder="可选">
                  <el-option v-for="t in tools" :key="t.id" :label="t.name" :value="String(t.id)" />
                </el-select>
              </div>
              <div class="field">
                <label>独立记忆</label>
                <el-switch v-model="a.hasMemory" />
              </div>
              <div class="field">
                <label>启用反思</label>
                <el-switch v-model="a.enableReflection" />
              </div>
              <template v-if="a.enableReflection">
                <div class="field">
                  <label>反思模型</label>
                  <el-select v-model="a.reflectionModelId" clearable style="width:100%" placeholder="留空=复用对话模型">
                    <el-option v-for="m in chatModels" :key="m.id" :label="modelLabel(m)" :value="m.id" />
                  </el-select>
                </div>
                <div class="field">
                  <label>反思阈值</label>
                  <el-input-number v-model="a.reflectionThreshold" :min="0" :max="10" :step="0.5" style="width:100%" />
                </div>
              </template>
              <div class="field">
                <label>需人工确认</label>
                <el-switch v-model="a.hitlRequired" />
              </div>
              <div v-if="a.hitlRequired" class="field">
                <label>确认提示</label>
                <el-input v-model="a.hitlPrompt" placeholder="向用户展示的确认提示" />
              </div>
            </div>
          </div>
          <el-button type="primary" plain :icon="Plus" @click="addAgent">添加 Agent</el-button>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, VideoPlay } from '@element-plus/icons-vue'
import {
  multiAgentCrewList, multiAgentCrewCreate, multiAgentCrewUpdate, multiAgentCrewDelete, multiAgentCrewValidate,
  chatModelList, kbList as fetchKbList, toolList
} from '../../api'

const router = useRouter()

const MODES = [
  { value: 'CREW_SEQUENTIAL', label: '角色分层 · 串行（CrewAI Sequential）' },
  { value: 'CREW_HIERARCHICAL', label: '角色分层 · 层级（CrewAI Hierarchical）' },
  { value: 'CREW_PARALLEL', label: '角色分层 · 并行（CrewAI Parallel）' },
  { value: 'GRAPH_ROUTED', label: '状态图路由（LangGraph）' },
  { value: 'GROUP_CHAT_ROUND_ROBIN', label: '对话式 · 固定轮询（AutoGen RoundRobin）' },
  { value: 'GROUP_CHAT_DYNAMIC_ROUTER', label: '对话式 · LLM 动态路由（AutoGen Dynamic）' }
]
const MODE_HINTS = {
  CREW_SEQUENTIAL: 'Agent 按列表顺序依次执行，前一个输出作为后续上下文。',
  CREW_HIERARCHICAL: '先由 Manager 规划并汇总，Worker Agents 依次执行。',
  CREW_PARALLEL: '按 DAG 并行调度 Agent（无 DAG 时退化为串行链）。',
  GRAPH_ROUTED: '显式状态图驱动，支持条件路由、循环重试与 HITL 暂停。',
  GROUP_CHAT_ROUND_ROBIN: 'Agent 按固定顺序轮流发言，达到轮次或终止条件后汇总。',
  GROUP_CHAT_DYNAMIC_ROUTER: '由 LLM 根据对话历史动态选择下一位发言 Agent。'
}

const loading = ref(false)
const submitting = ref(false)
const dialogVisible = ref(false)
const editing = ref(null)
const list = ref([])
const chatModels = ref([])
const kbs = ref([])
const tools = ref([])

const form = reactive({
  id: null, name: '', description: '', executionMode: 'CREW_SEQUENTIAL', status: 1,
  agents: [],
  managerAgentId: '', graphJson: '',
  groupRounds: 10, terminationCriteria: '', summarizerAgentId: '', routerModelId: null, routerPrompt: ''
})

const isCrewMode = computed(() => form.executionMode.startsWith('CREW_'))
const isGroupChat = computed(() => form.executionMode.startsWith('GROUP_CHAT_'))
const currentModeHint = computed(() => MODE_HINTS[form.executionMode] || '')

const modeLabel = (v) => (MODES.find(m => m.value === v)?.label || v || '-')
const modeColor = (v) => v?.startsWith('CREW') ? 'success' : v === 'GRAPH_ROUTED' ? 'warning' : 'primary'
const modelLabel = (m) => `${m.name}${m.modelName ? ` (${m.modelName})` : ''}`
const parseAgents = (json) => {
  try { return JSON.parse(json || '[]') } catch { return [] }
}
const safeParse = (json) => {
  try { return json ? JSON.parse(json) : null } catch { return null }
}

const load = async () => {
  loading.value = true
  try {
    const res = await multiAgentCrewList()
    list.value = res.data || []
  } finally { loading.value = false }
}

const loadOptions = async () => {
  const [cmRes, kbRes, toolRes] = await Promise.all([chatModelList(), fetchKbList(), toolList()])
  chatModels.value = cmRes.data || []
  kbs.value = kbRes.data || []
  tools.value = toolRes.data || []
}

const goPlayground = (row) => {
  router.push({ path: '/multi-agent/playground', query: row ? { crewId: row.id } : {} })
}

const blankAgent = (idx) => ({
  agentId: `agent_${idx}`,
  name: `Agent ${idx}`,
  role: '',
  goal: '',
  systemPrompt: '',
  chatModelId: chatModels.value[0]?.id ?? null,
  kbIds: [],
  toolIds: [],
  hasMemory: false,
  enableReflection: false,
  reflectionModelId: null,
  reflectionThreshold: 7.0,
  hitlRequired: false,
  hitlPrompt: ''
})

const addAgent = () => form.agents.push(blankAgent(form.agents.length + 1))
const removeAgent = (idx) => form.agents.splice(idx, 1)

const onModeChange = () => {
  // 切换模式时保留 Agent 列表，仅清理不适用的高级配置编辑器
  form.graphJson = ''
  form.managerAgentId = ''
}

const openDialog = (row) => {
  editing.value = row || null
  const agents = row ? parseAgents(row.agentsJson) : []
  Object.assign(form, {
    id: row?.id ?? null,
    name: row?.name ?? '',
    description: row?.description ?? '',
    executionMode: row?.executionMode ?? 'CREW_SEQUENTIAL',
    status: row?.status ?? 1,
    agents: agents.map(a => ({
      ...a,
      kbIds: a.kbIds || [],
      toolIds: (a.toolIds || []).map(String),
      reflectionThreshold: a.reflectionThreshold ?? 7.0
    })),
    managerAgentId: '',
    graphJson: '',
    groupRounds: 10, terminationCriteria: '', summarizerAgentId: '', routerModelId: null, routerPrompt: ''
  })

  // 解析各模式 JSON 配置
  const crewCfg = safeParse(row?.crewConfigJson)
  if (crewCfg) {
    form.managerAgentId = crewCfg.managerAgentId || ''
    if (crewCfg.graph) form.graphJson = JSON.stringify(crewCfg.graph, null, 2)
  }
  const graphCfg = safeParse(row?.graphJson)
  if (graphCfg && form.executionMode === 'GRAPH_ROUTED') {
    form.graphJson = JSON.stringify(graphCfg, null, 2)
  }
  const groupCfg = safeParse(row?.groupChatConfigJson)
  if (groupCfg) {
    form.groupRounds = groupCfg.rounds ?? 10
    form.terminationCriteria = groupCfg.terminationCriteria || ''
    form.summarizerAgentId = groupCfg.summarizerAgentId || ''
    form.routerModelId = groupCfg.dynamicRouterModelId ?? null
    form.routerPrompt = groupCfg.routerPrompt || ''
  }
  if (!form.agents.length) addAgent()
  dialogVisible.value = true
}

const genParallelGraph = () => {
  if (!form.agents.length) { ElMessage.warning('请先添加 Agent'); return }
  const nodes = [{ id: 'start', type: 'START' }]
  form.agents.forEach(a => nodes.push({ id: a.agentId, type: 'AGENT', agentId: a.agentId }))
  nodes.push({ id: 'end', type: 'END' })
  const edges = []
  form.agents.forEach(a => {
    edges.push({ source: 'start', target: a.agentId })
    edges.push({ source: a.agentId, target: 'end' })
  })
  form.graphJson = JSON.stringify({ nodes, edges }, null, 2)
}

const genSequentialGraph = () => {
  if (!form.agents.length) { ElMessage.warning('请先添加 Agent'); return }
  const nodes = [{ id: 'start', type: 'START' }]
  form.agents.forEach(a => nodes.push({ id: a.agentId, type: 'AGENT', agentId: a.agentId }))
  nodes.push({ id: 'end', type: 'END' })
  const edges = [{ source: 'start', target: form.agents[0].agentId }]
  for (let i = 0; i < form.agents.length - 1; i++) {
    edges.push({ source: form.agents[i].agentId, target: form.agents[i + 1].agentId })
  }
  edges.push({ source: form.agents[form.agents.length - 1].agentId, target: 'end' })
  form.graphJson = JSON.stringify({ nodes, edges }, null, 2)
}

const buildPayload = () => {
  // 基础校验
  if (!form.name.trim()) throw new Error('请填写 Crew 名称')
  if (!form.agents.length) throw new Error('至少需要一个 Agent')
  const usedIds = new Set()
  for (const a of form.agents) {
    if (!a.agentId?.trim()) throw new Error('Agent ID 不能为空')
    if (usedIds.has(a.agentId)) throw new Error('Agent ID 重复：' + a.agentId)
    usedIds.add(a.agentId)
    if (!a.chatModelId) throw new Error(`Agent ${a.agentId} 未选择对话模型`)
  }

  const payload = {
    name: form.name.trim(),
    description: form.description || null,
    executionMode: form.executionMode,
    status: form.status,
    agentsJson: JSON.stringify(form.agents),
    graphJson: null,
    crewConfigJson: null,
    groupChatConfigJson: null
  }

  if (isCrewMode.value) {
    const graph = form.graphJson.trim() ? safeParse(form.graphJson) : null
    if (form.graphJson.trim() && !graph) throw new Error('并行 DAG JSON 解析失败')
    const cfg = { mode: form.executionMode.replace('CREW_', '') }
    if (form.executionMode === 'CREW_HIERARCHICAL' && form.managerAgentId) cfg.managerAgentId = form.managerAgentId
    if (graph) cfg.graph = graph
    payload.crewConfigJson = JSON.stringify(cfg)
  } else if (form.executionMode === 'GRAPH_ROUTED') {
    const graph = safeParse(form.graphJson)
    if (!graph) throw new Error('状态图 JSON 解析失败')
    payload.graphJson = JSON.stringify(graph)
  } else if (isGroupChat.value) {
    payload.groupChatConfigJson = JSON.stringify({
      rounds: form.groupRounds,
      terminationCriteria: form.terminationCriteria || null,
      summarizerAgentId: form.summarizerAgentId || null,
      dynamicRouterModelId: form.routerModelId,
      routerPrompt: form.routerPrompt || null
    })
  }
  return payload
}

const submit = async () => {
  let payload
  try {
    payload = buildPayload()
  } catch (e) {
    ElMessage.warning(e.message)
    return
  }
  submitting.value = true
  try {
    if (editing.value) await multiAgentCrewUpdate(editing.value.id, payload)
    else await multiAgentCrewCreate(payload)
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally { submitting.value = false }
}

const validate = async (row) => {
  try {
    const res = await multiAgentCrewValidate(row.id)
    const data = res.data || {}
    if (data.valid) {
      ElMessage.success('校验通过')
    } else {
      ElMessageBox.alert((data.errors || []).join('\n'), '校验失败', { type: 'error' })
    }
  } catch (e) {
    // 拦截器已提示
  }
}

const del = async (row) => {
  await multiAgentCrewDelete(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(async () => {
  await Promise.all([load(), loadOptions()])
})
</script>

<style scoped lang="scss">
.hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  margin-top: 4px;
}
.agent-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.agent-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 14px;
  background: #fafbfc;
}
.agent-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  .agent-title { font-weight: 600; font-size: 14px; color: #303133; }
}
.agent-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 16px;
  .field {
    display: flex;
    flex-direction: column;
    gap: 4px;
    label { font-size: 12px; color: #606266; }
    &.full { grid-column: 1 / -1; }
  }
}
</style>
