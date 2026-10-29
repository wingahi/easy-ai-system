<template>
  <div class="page-container">
    <div class="page-header">
      <h2>聊天窗口管理</h2>
      <el-button type="primary" :icon="Plus" @click="openDialog()">创建窗口</el-button>
    </div>

    <div class="card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="窗口名称" />
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="知识库数" width="100">
          <template #default="{ row }">{{ parseKbs(row.knowledgeBaseIds).length }}</template>
        </el-table-column>
        <el-table-column label="工具数" width="90">
          <template #default="{ row }">{{ parseKbs(row.toolIds).length }}</template>
        </el-table-column>
        <el-table-column label="Agent" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.agentEnabled === 1" type="warning" size="small">🤖 已启用</el-tag>
            <el-tag v-else type="info" size="small">未启用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="分享" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.shareEnabled" type="success">已开启</el-tag>
            <el-tag v-else type="info">未开启</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="540" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button link type="primary" @click="playground(row)">调试对话</el-button>
              <el-button link type="primary" @click="multiAgent(row)">多 Agent</el-button>
              <el-button link type="primary" @click="copyShareLink(row)" :disabled="!row.shareEnabled">复制链接</el-button>
              <el-popconfirm title="确认重新生成分享链接？旧链接将立即失效，历史会话仍会保留。" width="240"
                             confirm-button-type="warning" confirm-button-text="重新生成" cancel-button-text="取消"
                             @confirm="regenerateShareLink(row)">
                <template #reference>
                  <el-button link type="warning" :disabled="!row.shareEnabled">重新生成</el-button>
                </template>
              </el-popconfirm>
              <el-button link type="primary" @click="toggleShare(row)">
                {{ row.shareEnabled ? '关闭分享' : '开启分享' }}
              </el-button>
              <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
              <el-button v-if="row.status === 1" link type="warning" @click="disable(row)">停用</el-button>
              <el-button v-else link type="success" @click="enable(row)">启用</el-button>
              <el-popconfirm title="确认删除？" @confirm="del(row)">
                <template #reference><el-button link type="danger">删除</el-button></template>
              </el-popconfirm>
            </div>
          </template>
        </el-table-column>
      </el-table>
      
      <el-pagination style="margin-top:16px;justify-content:flex-end;display:flex"
        v-model:current-page="page" v-model:page-size="size"
        :total="total" :page-sizes="[10,20,50]"
        layout="total, sizes, prev, pager, next" @size-change="load" @current-change="load" />
    </div>

    <!-- 创建/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑聊天窗口' : '创建聊天窗口'" width="600px" top="5vh">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="120px">
        <el-form-item prop="name" label="窗口名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item prop="llmModelId" label="大模型">
          <el-select v-model="form.llmModelId" style="width:100%" placeholder="选择大模型">
            <el-option v-for="m in chatModels" :key="m.id" :label="`${m.name} (${m.modelName})`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联知识库">
          <el-select v-model="selectedKbs" multiple style="width:100%" placeholder="可选">
            <el-option v-for="kb in kbList" :key="kb.id" :label="kb.name" :value="kb.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联工具">
          <el-select v-model="selectedTools" multiple clearable style="width:100%" placeholder="可选，发送消息时供模型调用">
            <el-option-group
              v-for="g in toolGroups"
              :key="g.type"
              :label="`${g.label}（${g.items.length}）`"
            >
              <el-option v-for="t in g.items" :key="t.id" :label="t.name" :value="t.id">
                <span style="float:left">{{ t.name }}</span>
                <el-tag size="small" :type="toolTypeColor(t.toolType)" style="float:right;margin-top:5px">
                  {{ toolTypeLabel(t.toolType) }}
                </el-tag>
              </el-option>
            </el-option-group>
          </el-select>
          <div style="font-size:12px;color:#909399;margin-top:4px;line-height:1.4">非必填。选择后该窗口对话时模型可自动调用这些工具</div>
        </el-form-item>
        <el-form-item label="System 提示词">
          <el-input v-model="form.systemPrompt" type="textarea" :rows="4" placeholder="定义 AI 助手角色，注意防提示词注入" />
        </el-form-item>
        <el-form-item label="RAG 评估方案">
          <el-select v-model="form.evaluationScheme" style="width:100%" placeholder="选择评估方案">
            <el-option label="不启用" value="NONE" />
            <el-option label="检索指标（MRR + NDCG）" value="METRICS" />
            <el-option label="LLM 评估（Faithfulness + Relevancy）" value="LLM_JUDGE" />
            <el-option label="全部" value="BOTH" />
          </el-select>
          <div style="font-size:12px;color:#909399;margin-top:4px;line-height:1.4">METRICS：纯后端计算；LLM_JUDGE：调用 2 个不同厂商模型作为 judge，延迟约 3-10s</div>
        </el-form-item>
        <el-form-item v-if="form.evaluationScheme === 'LLM_JUDGE' || form.evaluationScheme === 'BOTH'" label="Judge 模型">
          <el-select v-model="selectedJudges" multiple style="width:100%" placeholder="留空则自动选择 2 个不同厂商">
            <el-option v-for="m in chatModels" :key="m.id" :label="`${m.name} (${m.provider})`" :value="m.id" />
          </el-select>
          <div style="font-size:12px;color:#909399;margin-top:4px;line-height:1.4">建议选择 2 个不同厂商模型以保证评估一致性</div>
        </el-form-item>
        <el-form-item label="温度"><el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" /></el-form-item>

        <!-- ============ Agent 自动规划执行 ============ -->
        <el-divider content-position="left">🤖 Agent 自动规划执行</el-divider>
        <el-form-item label="启用 Agent">
          <el-switch v-model="form.agentEnabled" :active-value="1" :inactive-value="0" />
          <span style="font-size:12px;color:#909399;margin-left:12px">开启后，发送消息时 Agent 会自动拆解任务、规划步骤、调用工具并反思重试</span>
        </el-form-item>

        <template v-if="form.agentEnabled === 1">
          <el-form-item label="最大步骤数">
            <el-input-number v-model="form.agentMaxSteps" :min="2" :max="20" style="width:140px" />
            <span style="font-size:12px;color:#909399;margin-left:12px">兜底上限，防止 Planner 输出过长计划</span>
          </el-form-item>

          <el-form-item label="Planner 模型">
            <el-select v-model="form.agentPlannerModelId" clearable style="width:100%" placeholder="留空则继承主模型">
              <el-option v-for="m in chatModels" :key="m.id" :label="`${m.name}`" :value="m.id" />
            </el-select>
            <div style="font-size:12px;color:#909399;margin-top:4px">负责拆解用户目标、生成执行计划</div>
          </el-form-item>

          <el-form-item label="Executor 模型">
            <el-select v-model="form.agentExecutorModelId" clearable style="width:100%" placeholder="留空则继承主模型">
              <el-option v-for="m in chatModels" :key="m.id" :label="`${m.name}`" :value="m.id" />
            </el-select>
            <div style="font-size:12px;color:#909399;margin-top:4px">负责每步执行时的 tool-calling 与推理</div>
          </el-form-item>

          <el-form-item label="反思评分阈值">
            <el-slider v-model="form.agentReflectionThreshold" :min="0" :max="10" :step="0.5" show-input />
            <div style="font-size:12px;color:#909399;margin-top:4px">步骤产出评分低于此值时触发反思重试（0-10 分）</div>
          </el-form-item>

          <el-form-item label="允许 HITL 节点">
            <el-switch v-model="form.agentHitlEnabled" :active-value="1" :inactive-value="0" />
            <span style="font-size:12px;color:#909399;margin-left:12px">允许 Planner 规划需要人工确认的步骤（审批、确认等）</span>
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  windowPage, windowCreate, windowUpdate, windowDelete, windowDisable, windowEnable,
  windowEnableShare, windowDisableShare, windowRegenerateShare, chatModelList, kbList as fetchKbList, toolList
} from '../../api'

const router = useRouter()
const loading = ref(false)
const submitting = ref(false)
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const dialogVisible = ref(false)
const editing = ref(null)
const formRef = ref()
const chatModels = ref([])
const kbList = ref([])
const tools = ref([])
const selectedKbs = ref([])
const selectedTools = ref([])
const selectedJudges = ref([])

const form = reactive({
  name: '', description: '', llmModelId: null,
  knowledgeBaseIds: '[]', systemPrompt: '', temperature: 0.7, status: 1,
  evaluationScheme: 'NONE', judgeModelIds: '[]', toolIds: '[]',
  agentEnabled: 0, agentMaxSteps: 15, agentPlannerModelId: null,
  agentExecutorModelId: null, agentCriticModelIds: '[]',
  agentReflectionThreshold: 7.0, agentHitlEnabled: 0
})
const rules = {
  name: [{ required: true }],
  llmModelId: [{ required: true, message: '请选择大模型' }]
}

const load = async () => {
  loading.value = true
  try {
    const res = await windowPage({ page: page.value, size: size.value })
    list.value = res.data.records
    total.value = res.data.total
  } finally { loading.value = false }
}

const loadOptions = async () => {
  const [cmRes, kbRes, toolRes] = await Promise.all([chatModelList(), fetchKbList(), toolList()])
  chatModels.value = cmRes.data
  kbList.value = kbRes.data
  tools.value = toolRes.data || []
}

const parseKbs = (json) => {
  try { return JSON.parse(json || '[]') } catch { return [] }
}

// ============ 工具按类型分组（下拉里用 tag 区分类型） ============
const TOOL_TYPE_ORDER = ['SKILL', 'MCP', 'HTTP', 'FUNCTION_CALLING']
const toolTypeLabel = (t) => ({
  SKILL: 'Skill 技能', MCP: 'MCP 协议', HTTP: 'HTTP 接口', FUNCTION_CALLING: 'Function Calling'
}[t] || t)
const toolTypeColor = (t) => ({ SKILL: 'primary', MCP: 'success', HTTP: 'warning', FUNCTION_CALLING: 'info' }[t] || 'primary')

const toolGroups = computed(() => {
  const groups = new Map()
  for (const t of tools.value) {
    const type = t.toolType || 'OTHER'
    if (!groups.has(type)) groups.set(type, [])
    groups.get(type).push(t)
  }
  return [...groups.entries()]
    .sort(([a], [b]) => {
      const ia = TOOL_TYPE_ORDER.indexOf(a), ib = TOOL_TYPE_ORDER.indexOf(b)
      return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib)
    })
    .map(([type, items]) => ({ type, label: toolTypeLabel(type), items }))
})

const openDialog = (row) => {
  editing.value = row || null
  if (row) {
    Object.assign(form, row)
    selectedKbs.value = parseKbs(row.knowledgeBaseIds)
    selectedTools.value = parseKbs(row.toolIds)
    selectedJudges.value = parseKbs(row.judgeModelIds)
  } else {
    Object.assign(form, {
      name: '', description: '', llmModelId: null, knowledgeBaseIds: '[]',
      systemPrompt: '', temperature: 0.7, status: 1,
      evaluationScheme: 'NONE', judgeModelIds: '[]', toolIds: '[]',
      agentEnabled: 0, agentMaxSteps: 15, agentPlannerModelId: null,
      agentExecutorModelId: null, agentCriticModelIds: '[]',
      agentReflectionThreshold: 7.0, agentHitlEnabled: 0
    })
    selectedKbs.value = []
    selectedTools.value = []
    selectedJudges.value = []
  }
  dialogVisible.value = true
}

const submit = async () => {
  await formRef.value.validate()
  form.knowledgeBaseIds = JSON.stringify(selectedKbs.value)
  form.toolIds = JSON.stringify(selectedTools.value)
  form.judgeModelIds = JSON.stringify(selectedJudges.value)
  submitting.value = true
  try {
    if (editing.value) { form.id = editing.value.id; await windowUpdate(form) }
    else { await windowCreate(form) }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally { submitting.value = false }
}

const del = async (row) => {
  await windowDelete(row.id)
  ElMessage.success('删除成功')
  load()
}
const disable = async (row) => { await windowDisable(row.id); ElMessage.success('已停用'); load() }
const enable = async (row) => { await windowEnable(row.id); ElMessage.success('已启用'); load() }
const toggleShare = async (row) => {
  if (row.shareEnabled) await windowDisableShare(row.id)
  else await windowEnableShare(row.id)
  ElMessage.success(row.shareEnabled ? '已关闭分享' : '已开启分享')
  load()
}
const copyText = async (text) => {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text); return true
    }
  } catch { /* fallthrough */ }
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.cssText = 'position:fixed;left:-9999px;top:0;opacity:0'
  document.body.appendChild(ta); ta.focus(); ta.select()
  let ok = false
  try { ok = document.execCommand('copy') } catch { /* ignore */ }
  document.body.removeChild(ta)
  return ok
}
const copyShareLink = async (row) => {
  const url = `${window.location.origin}/share/${row.shareToken}`
  const ok = await copyText(url)
  if (ok) ElMessage.success('分享链接已复制')
  else ElMessage.warning('复制失败，请手动复制: ' + url)
}
const regenerateShareLink = async (row) => {
  await windowRegenerateShare(row.id)
  ElMessage.success('已重新生成分享链接，旧链接已失效')
  load()
}
const playground = (row) => router.push(`/chat/windows/${row.id}/playground`)
const multiAgent = (row) => router.push({ path: '/multi-agent/playground', query: { windowId: row.id } })

onMounted(async () => { await Promise.all([load(), loadOptions()]) })
</script>
