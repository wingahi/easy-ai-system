<template>
  <div class="page-container">
    <div class="page-header">
      <h2>工作流编排</h2>
      <div>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建工作流</el-button>
      </div>
    </div>

    <div class="card">
      <el-table :data="list" v-loading="loading" stripe @row-dblclick="openEditor">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column label="节点数" width="90">
          <template #default="{ row }">{{ nodeCount(row) }}</template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="80">
          <template #default="{ row }">v{{ row.version || 1 }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="180">
          <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="460" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button link type="primary" @click="openEditor(row)">编排</el-button>
              <el-button link type="primary" @click="openVersions(row)">版本</el-button>
              <el-button link type="primary" @click="openTriggers(row)">触发器</el-button>
              <el-button link type="primary" @click="openHistory(row)">运行历史</el-button>
              <el-button v-if="row.status === 1" link type="warning" @click="toggle(row, 0)">停用</el-button>
              <el-button v-else link type="success" @click="toggle(row, 1)">启用</el-button>
              <el-popconfirm title="确认删除该工作流？" @confirm="remove(row)">
                <template #reference><el-button link type="danger">删除</el-button></template>
              </el-popconfirm>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        style="margin-top:16px;justify-content:flex-end;display:flex"
        v-model:current-page="page" v-model:page-size="size"
        :total="total" :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @size-change="load" @current-change="load" />
    </div>

    <!-- 新建 -->
    <el-dialog v-model="createVisible" title="新建工作流" width="520px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如：合同审查流程" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">创建并编排</el-button>
      </template>
    </el-dialog>

    <!-- 触发器管理 -->
    <el-dialog v-model="triggerVisible" :title="`触发器 - ${currentWorkflow?.name || ''}`" width="820px">
      <div style="margin-bottom:12px;display:flex;justify-content:space-between;align-items:center">
        <span class="empty-hint">CRON 为 6 段表达式（秒 分 时 日 月 周）；Webhook 创建后生成免密调用地址</span>
        <el-button type="primary" size="small" :icon="Plus" @click="openTriggerCreate">新增触发器</el-button>
      </div>
      <el-table :data="triggers" v-loading="triggerLoading" stripe>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="row.triggerType === 'CRON' ? 'warning' : 'success'" size="small">{{ row.triggerType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="配置" min-width="260">
          <template #default="{ row }">
            <code v-if="row.triggerType === 'CRON'">{{ row.cronExpression }}</code>
            <template v-else>
              <code style="word-break:break-all">/api/workflows/webhook/{{ row.webhookToken }}</code>
              <el-button link type="primary" size="small" @click="copyWebhook(row)">复制</el-button>
            </template>
          </template>
        </el-table-column>
        <el-table-column prop="inputText" label="默认输入" min-width="110" show-overflow-tooltip />
        <el-table-column label="状态" width="70">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="触发统计" width="140">
          <template #default="{ row }">
            {{ row.fireCount || 0 }} 次
            <div class="empty-hint" style="font-size:12px">{{ formatTime(row.lastFireTime) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button v-if="row.status === 1" link type="warning" @click="toggleTrigger(row, 0)">停用</el-button>
            <el-button v-else link type="success" @click="toggleTrigger(row, 1)">启用</el-button>
            <el-popconfirm title="确认删除该触发器？" @confirm="removeTrigger(row)">
              <template #reference><el-button link type="danger">删除</el-button></template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 新增触发器 -->
    <el-dialog v-model="triggerCreateVisible" title="新增触发器" width="480px" append-to-body>
      <el-form :model="triggerForm" label-width="90px">
        <el-form-item label="类型" required>
          <el-radio-group v-model="triggerForm.triggerType">
            <el-radio value="CRON">定时（CRON）</el-radio>
            <el-radio value="WEBHOOK">Webhook</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="triggerForm.triggerType === 'CRON'" label="CRON" required>
          <el-input v-model="triggerForm.cronExpression" placeholder="如 0 */5 * * * *（每 5 分钟）" />
        </el-form-item>
        <el-form-item label="默认输入">
          <el-input v-model="triggerForm.inputText" placeholder="触发时作为 input 变量传入" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="triggerCreateVisible = false">取消</el-button>
        <el-button type="primary" :loading="triggerCreating" @click="createTrigger">创建</el-button>
      </template>
    </el-dialog>

    <!-- 版本历史 -->
    <el-dialog v-model="versionVisible" :title="`版本历史 - ${currentWorkflow?.name || ''}`" width="680px">
      <el-table :data="versions" v-loading="versionLoading" stripe>
        <el-table-column label="版本" width="110">
          <template #default="{ row }">
            v{{ row.version }}
            <el-tag v-if="row.version === currentWorkflow?.version" type="success" size="small" style="margin-left:4px">当前</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="节点数" width="80">
          <template #default="{ row }">{{ nodeCount(row) }}</template>
        </el-table-column>
        <el-table-column label="保存时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-popconfirm :title="`确认回滚到 v${row.version}？将基于该版本生成新版本`" @confirm="rollback(row)">
              <template #reference>
                <el-button link type="warning" :disabled="row.version === currentWorkflow?.version">回滚</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 运行历史 -->
    <el-drawer v-model="historyVisible" :title="`运行历史 - ${currentWorkflow?.name || ''}`" size="70%">
      <el-table :data="runs" v-loading="runLoading" stripe @expand-change="(row) => loadNodeRuns(row.id)">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="node-run-list" v-loading="nodeLoadingId === row.id">
              <div v-if="!nodeRuns[row.id]?.length" class="empty-hint">暂无节点记录</div>
              <div v-for="n in nodeRuns[row.id] || []" :key="n.id" class="node-run-item">
                <el-tag :type="nodeStatusType(n.status)" size="small" effect="dark">{{ n.status }}</el-tag>
                <span class="node-name">{{ n.nodeName }}<span class="node-type">({{ n.nodeType }})</span></span>
                <span v-if="n.iteration" class="iter">第 {{ n.iteration }} 轮</span>
                <span class="duration">{{ n.durationMs || 0 }} ms</span>
                <div v-if="n.error" class="node-error">{{ n.error }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="id" label="Run" width="80">
          <template #default="{ row }">#{{ row.id }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="runStatusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="输入" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.inputText }}</template>
        </el-table-column>
        <el-table-column label="输出" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.outputText || row.errorMessage }}</template>
        </el-table-column>
        <el-table-column label="节点进度" width="110">
          <template #default="{ row }">{{ row.executedNodes || 0 }} / {{ row.totalNodes || 0 }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="100">
          <template #default="{ row }">{{ row.durationMs || 0 }} ms</template>
        </el-table-column>
        <el-table-column label="开始时间" width="180">
          <template #default="{ row }">{{ formatTime(row.startTime) }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  workflowPage, workflowCreate, workflowDelete, workflowEnable, workflowDisable,
  workflowPageRuns, workflowRunNodes, workflowVersions, workflowRollback,
  workflowTriggers, workflowTriggerCreate, workflowTriggerEnable, workflowTriggerDisable,
  workflowTriggerDelete
} from '../../api'

const router = useRouter()

const list = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)

const createVisible = ref(false)
const creating = ref(false)
const form = reactive({ name: '', description: '' })

const historyVisible = ref(false)
const currentWorkflow = ref(null)
const runs = ref([])
const runLoading = ref(false)
const nodeRuns = reactive({})
const nodeLoadingId = ref(null)

const versionVisible = ref(false)
const versions = ref([])
const versionLoading = ref(false)

const openVersions = async (row) => {
  currentWorkflow.value = row
  versionVisible.value = true
  versionLoading.value = true
  try {
    const res = await workflowVersions(row.id)
    versions.value = res.data || []
  } finally {
    versionLoading.value = false
  }
}

const rollback = async (row) => {
  const res = await workflowRollback(currentWorkflow.value.id, row.version)
  ElMessage.success(`已回滚到 v${row.version}，生成新版本 v${res.data.version}`)
  await openVersions(res.data)
  load()
}

const triggerVisible = ref(false)
const triggers = ref([])
const triggerLoading = ref(false)
const triggerCreateVisible = ref(false)
const triggerCreating = ref(false)
const triggerForm = reactive({ triggerType: 'CRON', cronExpression: '', inputText: '' })

const openTriggers = async (row) => {
  currentWorkflow.value = row
  triggerVisible.value = true
  triggerLoading.value = true
  try {
    const res = await workflowTriggers(row.id)
    triggers.value = res.data || []
  } finally {
    triggerLoading.value = false
  }
}

const openTriggerCreate = () => {
  triggerForm.triggerType = 'CRON'
  triggerForm.cronExpression = ''
  triggerForm.inputText = ''
  triggerCreateVisible.value = true
}

const createTrigger = async () => {
  if (triggerForm.triggerType === 'CRON' && !triggerForm.cronExpression.trim()) {
    ElMessage.warning('请填写 CRON 表达式')
    return
  }
  triggerCreating.value = true
  try {
    await workflowTriggerCreate(currentWorkflow.value.id, { ...triggerForm })
    ElMessage.success('创建成功')
    triggerCreateVisible.value = false
    await openTriggers(currentWorkflow.value)
  } finally {
    triggerCreating.value = false
  }
}

const toggleTrigger = async (row, status) => {
  if (status === 1) await workflowTriggerEnable(row.id)
  else await workflowTriggerDisable(row.id)
  ElMessage.success('已更新状态')
  openTriggers(currentWorkflow.value)
}

const removeTrigger = async (row) => {
  await workflowTriggerDelete(row.id)
  ElMessage.success('已删除')
  openTriggers(currentWorkflow.value)
}

const copyWebhook = async (row) => {
  const url = `${location.origin}/api/workflows/webhook/${row.webhookToken}`
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success('Webhook 地址已复制')
  } catch {
    ElMessage.info(url)
  }
}

const load = async () => {
  loading.value = true
  try {
    const res = await workflowPage({ page: page.value, size: size.value })
    list.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

const nodeCount = (row) => {
  try {
    return (JSON.parse(row.graphJson || '{}').nodes || []).length
  } catch {
    return 0
  }
}

const openCreate = () => {
  form.name = ''
  form.description = ''
  createVisible.value = true
}

const create = async () => {
  if (!form.name.trim()) {
    ElMessage.warning('请填写工作流名称')
    return
  }
  creating.value = true
  try {
    const graph = {
      nodes: [
        { id: 'n_start', type: 'START', name: '开始', position: { x: 80, y: 180 }, config: {} },
        { id: 'n_end', type: 'END', name: '结束', position: { x: 480, y: 180 }, config: { output: '{{input}}' } }
      ],
      edges: [{ id: 'e_start_end', source: 'n_start', target: 'n_end', sourceHandle: 'default' }]
    }
    const res = await workflowCreate({
      name: form.name.trim(),
      description: form.description,
      graphJson: JSON.stringify(graph)
    })
    ElMessage.success('创建成功')
    createVisible.value = false
    router.push(`/workflows/${res.data.id}/edit`)
  } finally {
    creating.value = false
  }
}

const openEditor = (row) => router.push(`/workflows/${row.id}/edit`)

const toggle = async (row, status) => {
  if (status === 1) await workflowEnable(row.id)
  else await workflowDisable(row.id)
  ElMessage.success('已更新状态')
  load()
}

const remove = async (row) => {
  await workflowDelete(row.id)
  ElMessage.success('已删除')
  load()
}

const openHistory = async (row) => {
  currentWorkflow.value = row
  historyVisible.value = true
  runLoading.value = true
  try {
    const res = await workflowPageRuns(row.id, { page: 1, size: 30 })
    runs.value = res.data.records || []
  } finally {
    runLoading.value = false
  }
}

const loadNodeRuns = async (runId) => {
  if (nodeRuns[runId]) return
  nodeLoadingId.value = runId
  try {
    const res = await workflowRunNodes(runId)
    nodeRuns[runId] = res.data || []
  } finally {
    nodeLoadingId.value = null
  }
}

const runStatusType = (s) => ({
  COMPLETED: 'success', FAILED: 'danger', RUNNING: 'warning',
  PAUSED: 'warning', CANCELLED: 'info'
}[s] || 'info')

const nodeStatusType = (s) => ({
  SUCCESS: 'success', FAILED: 'danger', RUNNING: 'warning',
  HITL_PENDING: 'warning', SKIPPED: 'info'
}[s] || 'info')

const formatTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 19) : '-')

onMounted(load)
</script>

<style scoped lang="scss">
.table-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  .el-button { margin-left: 0; margin-right: 8px; }
}
.node-run-list {
  padding: 8px 24px;
  background: #fafafa;
}
.node-run-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px dashed #eee;
  font-size: 13px;
  .node-name { font-weight: 500; }
  .node-type { color: #909399; font-size: 12px; margin-left: 2px; }
  .iter { color: #e6a23c; }
  .duration { color: #909399; margin-left: auto; }
  .node-error { color: #f56c6c; width: 100%; }
}
.empty-hint { color: #909399; font-size: 13px; }
</style>
