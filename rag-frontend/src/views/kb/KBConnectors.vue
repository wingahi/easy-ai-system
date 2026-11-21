<template>
  <div class="page-container">
    <div class="page-header">
      <div style="display:flex;gap:12px;align-items:center">
        <el-button :icon="ArrowLeft" circle @click="$router.back()" />
        <h2>{{ kb?.name }} - 数据源连接器</h2>
      </div>
      <div style="display:flex;gap:8px;align-items:center">
        <el-button type="primary" :icon="Plus" @click="openDialog()">新建连接器</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="填写一个 URL 后系统抓取正文入知识库；可定时重抓，内容未变化时复用已有文件、不重复入库。"
      style="margin-bottom:12px" />

    <div class="card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="URL" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            <el-link :href="row.url" target="_blank" type="primary">{{ row.url }}</el-link>
          </template>
        </el-table-column>
        <el-table-column prop="splitStrategy" label="分割策略" width="110" />
        <el-table-column label="自动同步" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small">
              {{ row.enabled === 1 ? '已启用' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="间隔" width="90">
          <template #default="{ row }">
            {{ row.syncIntervalMinutes > 0 ? `${row.syncIntervalMinutes} 分钟` : '仅手动' }}
          </template>
        </el-table-column>
        <el-table-column prop="lastSyncTime" label="最近同步" width="170" />
        <el-table-column label="最近状态" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.lastStatus" :type="statusType(row.lastStatus)" size="small">
              {{ statusText(row.lastStatus) }}
            </el-tag>
            <span v-else style="color:#c0c4cc;font-size:12px">—</span>
          </template>
        </el-table-column>
        <el-table-column label="最近错误" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.lastError" style="color:#f56c6c;font-size:12px">{{ row.lastError }}</span>
            <span v-else style="color:#c0c4cc;font-size:12px">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button link type="primary" :loading="syncingId === row.id" @click="sync(row)">立即同步</el-button>
              <el-button link type="primary" @click="openRuns(row)">运行记录</el-button>
              <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
              <el-popconfirm title="确认删除该连接器？已入库文件不会被删除" @confirm="del(row)">
                <template #reference>
                  <el-button link type="danger">删除</el-button>
                </template>
              </el-popconfirm>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="!loading && list.length === 0" style="text-align:center;color:#909399;padding:30px">
        还没有数据源连接器，点击右上角新建
      </div>
    </div>

    <!-- 新建/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑连接器' : '新建连接器'" width="560px">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="110px">
        <el-form-item prop="name" label="名称">
          <el-input v-model="form.name" placeholder="例如：官方文档首页" />
        </el-form-item>
        <el-form-item prop="url" label="URL">
          <el-input v-model="form.url" placeholder="https://example.com/docs/page" />
          <div style="font-size:12px;color:#909399;margin-top:4px">
            仅支持 http/https；内网、回环与云元数据地址会被 SSRF 防护拒绝
          </div>
        </el-form-item>
        <el-form-item label="分割策略">
          <el-select v-model="form.splitStrategy" style="width:100%">
            <el-option label="自动路由（推荐）" value="AUTO" />
            <el-option label="递归分割" value="RECURSIVE" />
            <el-option label="章节分割" value="CHAPTER" />
            <el-option label="段落分割" value="PARAGRAPH" />
            <el-option label="长度分割" value="LENGTH" />
            <el-option label="结构化切分（Markdown）" value="STRUCTURED" />
            <el-option label="语义切分" value="SEMANTIC" />
          </el-select>
        </el-form-item>
        <el-form-item label="切分配置">
          <el-input v-model="form.splitConfig" type="textarea" :rows="2"
                    placeholder='可选 JSON，例如 {"maxLength":800,"overlap":100}' />
        </el-form-item>
        <el-form-item label="自动同步">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
          <span style="font-size:12px;color:#909399;margin-left:8px">
            需服务端开启连接器调度器后才会自动执行
          </span>
        </el-form-item>
        <el-form-item label="同步间隔">
          <el-input-number v-model="form.syncIntervalMinutes" :min="0" :max="10080" />
          <span style="font-size:12px;color:#909399;margin-left:8px">分钟；0 表示仅手动同步</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 运行记录 -->
    <el-dialog v-model="runsVisible" :title="`运行记录 — ${runConnector?.name || ''}`" width="920px">
      <el-table :data="runs" v-loading="runsLoading" size="small" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="httpStatus" label="HTTP" width="80" />
        <el-table-column prop="fileId" label="文件 ID" width="90" />
        <el-table-column prop="chunkCount" label="段落数" width="80" />
        <el-table-column prop="durationMs" label="耗时(ms)" width="100" />
        <el-table-column prop="startedAt" label="开始时间" width="170" />
        <el-table-column prop="message" label="说明" min-width="220" show-overflow-tooltip />
      </el-table>
      <div v-if="!runsLoading && runs.length === 0" style="text-align:center;color:#909399;padding:24px">
        暂无运行记录
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Plus } from '@element-plus/icons-vue'
import {
  kbGet, kbConnectorList, kbConnectorCreate, kbConnectorUpdate, kbConnectorDelete,
  kbConnectorSync, kbConnectorRuns
} from '../../api'
import { validateForm } from '../../utils/form'

const route = useRoute()
const kbId = route.params.id

const kb = ref(null)
const list = ref([])
const loading = ref(false)
const submitting = ref(false)
const syncingId = ref(null)

const dialogVisible = ref(false)
const editingId = ref(null)
const formRef = ref(null)
const form = ref(defaultForm())

const runsVisible = ref(false)
const runConnector = ref(null)
const runs = ref([])
const runsLoading = ref(false)

const rules = {
  name: [{ required: true, message: '请输入连接器名称', trigger: 'blur' }],
  url: [{ required: true, message: '请输入 URL', trigger: 'blur' }]
}

function defaultForm () {
  return {
    name: '',
    url: '',
    splitStrategy: 'AUTO',
    splitConfig: '',
    syncIntervalMinutes: 60,
    enabled: 1
  }
}

const statusType = (status) => ({
  SUCCESS: 'success',
  UNCHANGED: 'info',
  REMOVED: 'warning',
  FAILED: 'danger'
}[status] || 'info')

const statusText = (status) => ({
  SUCCESS: '已更新',
  UNCHANGED: '内容未变',
  REMOVED: '源已删除',
  FAILED: '失败'
}[status] || status)

const load = async () => {
  loading.value = true
  try {
    const [kbRes, connectorsRes] = await Promise.all([kbGet(kbId), kbConnectorList(kbId)])
    kb.value = kbRes.data
    list.value = connectorsRes.data || []
  } finally {
    loading.value = false
  }
}

const openDialog = (row) => {
  editingId.value = row ? row.id : null
  form.value = row ? {
    name: row.name,
    url: row.url,
    splitStrategy: row.splitStrategy || 'AUTO',
    splitConfig: row.splitConfig || '',
    syncIntervalMinutes: row.syncIntervalMinutes ?? 60,
    enabled: row.enabled ?? 0
  } : defaultForm()
  dialogVisible.value = true
}

const submit = async () => {
  if (!(await validateForm(formRef))) return
  submitting.value = true
  try {
    const payload = { ...form.value }
    if (!payload.splitConfig) payload.splitConfig = null
    if (editingId.value) {
      await kbConnectorUpdate(kbId, editingId.value, payload)
      ElMessage.success('已保存')
    } else {
      await kbConnectorCreate(kbId, payload)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

const sync = async (row) => {
  syncingId.value = row.id
  try {
    const res = await kbConnectorSync(kbId, row.id)
    const run = res.data || {}
    if (run.status === 'FAILED') {
      ElMessage.error(run.message || '同步失败')
    } else {
      ElMessage.success(`${statusText(run.status)}${run.message ? '：' + run.message : ''}`)
    }
    await load()
  } finally {
    syncingId.value = null
  }
}

const del = async (row) => {
  await kbConnectorDelete(kbId, row.id)
  ElMessage.success('已删除')
  await load()
}

const openRuns = async (row) => {
  runConnector.value = row
  runsVisible.value = true
  runsLoading.value = true
  try {
    const res = await kbConnectorRuns(kbId, row.id, { limit: 50 })
    runs.value = res.data || []
  } finally {
    runsLoading.value = false
  }
}

onMounted(load)
</script>
