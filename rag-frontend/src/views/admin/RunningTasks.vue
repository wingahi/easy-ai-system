<template>
  <div class="page-container">
    <div class="page-header">
      <h2>运行中任务</h2>
      <div class="header-actions">
        <el-select v-model="query.status" placeholder="状态" size="small" clearable
                   style="width: 140px" @change="load(1)">
          <el-option v-for="o in STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-button size="small" type="primary" :icon="Refresh" :loading="loading" @click="load(page)">
          刷新
        </el-button>
      </div>
    </div>

    <el-alert v-if="loadError" class="api-alert" type="warning" show-icon :closable="false"
              :title="'运行中任务接口暂不可用：' + loadError" />

    <div class="card">
      <el-tabs v-model="activeType" class="type-tabs" @tab-change="onTypeChange">
        <el-tab-pane v-for="o in TASK_TYPES" :key="o.value" :label="o.label" :name="o.value" />
      </el-tabs>

      <div class="list-meta">
        <span>共 {{ total }} 个任务</span>
        <span v-if="lastRefreshAt">最近刷新：{{ formatTime(lastRefreshAt) }}</span>
      </div>

      <el-table :data="rows" v-loading="loading" stripe :row-class-name="rowClassName">
        <el-table-column label="任务类型" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="taskTypeTag(row.taskType)">
              {{ taskTypeLabel(row.taskType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.title || '—' }}</template>
        </el-table-column>
        <el-table-column prop="ownerUsername" label="所属用户" width="130">
          <template #default="{ row }">{{ row.ownerUsername || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="currentNode" label="当前节点" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.currentNode || '—' }}</template>
        </el-table-column>
        <el-table-column label="开始时间" width="170">
          <template #default="{ row }">{{ formatTime(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="已停留时长" width="160">
          <template #header>
            <el-tooltip placement="top" :content="`超过 ${STALE_MINUTES} 分钟未推进视为异常，整行高亮`">
              <span>已停留时长 ⓘ</span>
            </el-tooltip>
          </template>
          <template #default="{ row }">
            <span :class="['stay', { stale: isStale(row) }]">{{ formatStay(row.stayMinutes) }}</span>
            <el-tag v-if="isStale(row)" type="danger" size="small" class="stale-tag">异常</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastStepStatus" label="最近步骤状态" width="140">
          <template #default="{ row }">
            <span v-if="row.lastStepStatus">{{ row.lastStepStatus }}</span>
            <span v-else class="hint">—</span>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty :description="emptyText" :image-size="80" />
        </template>
      </el-table>

      <el-pagination class="pager" v-model:current-page="page" v-model:page-size="size"
                     :total="total" :page-sizes="[10, 20, 50]"
                     layout="total, sizes, prev, pager, next"
                     @size-change="load(1)" @current-change="load" />
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { adminRunningTasks } from '../../api'

const STALE_MINUTES = 60

const TASK_TYPES = [
  { value: 'AGENT', label: 'Agent 自动执行', emptyText: '暂无进行中的 Agent 计划' },
  { value: 'MULTI_AGENT', label: '多 Agent 协作', emptyText: '暂无进行中的多 Agent 运行' },
  { value: 'WORKFLOW', label: '工作流', emptyText: '暂无进行中的工作流运行' },
  { value: 'CHAT', label: '普通对话', emptyText: '暂无进行中的普通对话' }
]

const STATUS_OPTIONS = [
  { value: 'RUNNING', label: '执行中' },
  { value: 'PENDING', label: '等待中' },
  { value: 'PAUSED', label: '已暂停' },
  { value: 'HITL_PENDING', label: '等待人工确认' },
  { value: 'INTERRUPTED', label: '已中断' },
  { value: 'FAILED', label: '失败' }
]

const TASK_TYPE_LABELS = Object.fromEntries(TASK_TYPES.map(o => [o.value, o.label]))
const STATUS_LABELS = {
  RUNNING: '执行中',
  PENDING: '等待中',
  PAUSED: '已暂停',
  INTERRUPTED: '已中断',
  HITL_PENDING: '等待人工确认',
  FAILED: '失败',
  COMPLETED: '已完成',
  CANCELLED: '已取消'
}

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const loadError = ref('')
const lastRefreshAt = ref(null)
const activeType = ref(TASK_TYPES[0].value)
let requestSeq = 0

const query = reactive({ status: '' })

const emptyText = computed(() => {
  if (loadError.value) return '任务接口暂不可用'
  const current = TASK_TYPES.find(o => o.value === activeType.value)
  return current ? current.emptyText : '暂无运行中任务'
})

const taskTypeLabel = (type) => TASK_TYPE_LABELS[type] || type || '未知'
const statusLabel = (status) => STATUS_LABELS[status] || status || '未知'

const taskTypeTag = (type) => ({
  AGENT: 'warning',
  MULTI_AGENT: 'primary',
  WORKFLOW: 'success',
  CHAT: 'info'
}[type] || 'info')

const statusTag = (status) => ({
  RUNNING: 'primary',
  PENDING: 'warning',
  PAUSED: 'warning',
  HITL_PENDING: 'warning',
  INTERRUPTED: 'danger',
  FAILED: 'danger',
  COMPLETED: 'success',
  CANCELLED: 'info'
}[status] || 'info')

const isStale = (row) => Number(row.stayMinutes) >= STALE_MINUTES
const rowClassName = ({ row }) => (isStale(row) ? 'row-stale' : '')

const formatTime = (value) => {
  if (!value) return '—'
  if (value instanceof Date) {
    const pad = (n) => String(n).padStart(2, '0')
    return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())} ${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`
  }
  return String(value).replace('T', ' ').slice(0, 19)
}

const formatStay = (minutes) => {
  const m = Number(minutes)
  if (!isFinite(m) || m < 0) return '—'
  if (m < 60) return `${Math.round(m)} 分钟`
  const h = Math.floor(m / 60)
  const rest = Math.round(m % 60)
  if (h < 24) return rest ? `${h} 小时 ${rest} 分钟` : `${h} 小时`
  const d = Math.floor(h / 24)
  return rest ? `${d} 天 ${h % 24} 小时` : `${d} 天`
}

const load = async (p = page.value) => {
  page.value = p
  const seq = ++requestSeq
  loading.value = true
  loadError.value = ''
  try {
    const params = { type: activeType.value, page: p, size: size.value }
    if (query.status) params.status = query.status
    const res = await adminRunningTasks(params)
    if (seq !== requestSeq) return
    const data = res?.data || {}
    rows.value = data.items || []
    total.value = data.total || 0
  } catch (e) {
    if (seq !== requestSeq) return
    // 接口未就绪 / 网络异常时降级为空列表 + 页内提示，不弹全局错误
    rows.value = []
    total.value = 0
    loadError.value = e?.response?.data?.message || e?.message || '请求失败'
  } finally {
    if (seq === requestSeq) {
      loading.value = false
      lastRefreshAt.value = new Date()
    }
  }
}

// 切换类型：清空上一类型的残留行，按新类型重新查询第 1 页
const onTypeChange = () => {
  rows.value = []
  total.value = 0
  loadError.value = ''
  load(1)
}

onMounted(() => load(1))
</script>

<style scoped lang="scss">
.header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.api-alert {
  margin-bottom: 12px;
}
.list-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #909399;
  margin-bottom: 8px;
}
.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
.stay.stale {
  color: #f56c6c;
  font-weight: 600;
}
.stale-tag {
  margin-left: 6px;
}
.hint {
  color: #c0c4cc;
}
:deep(.row-stale) td {
  background: #fef0f0 !important;
}
</style>
