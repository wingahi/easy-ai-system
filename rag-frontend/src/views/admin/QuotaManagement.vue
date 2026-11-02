<template>
  <div class="quota-management">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>用户配额管理</span>
          <div>
            <el-input v-model="query.username" placeholder="搜索用户名" size="small" clearable
                      style="width: 180px; margin-right: 8px" @keyup.enter="load(1)" />
            <el-button size="small" type="primary" @click="load(1)">查询</el-button>
            <el-button size="small" @click="load(page)">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" size="small" row-key="username">
        <el-table-column prop="username" label="用户名" width="140" show-overflow-tooltip />
        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            {{ row.roles && row.roles.length ? row.roles.join(', ') : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="来源" width="90">
          <template #default="{ row }">
            <el-tag :type="row.custom ? 'success' : 'info'" size="small">
              {{ row.custom ? '自定义' : '全局默认' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="周期" width="80">
          <template #default="{ row }">{{ row.period === 'MONTH' ? '月' : '日' }}</template>
        </el-table-column>
        <el-table-column label="请求数" width="130">
          <template #default="{ row }">
            {{ row.chatUsed }} / {{ row.chatLimit > 0 ? row.chatLimit : '不限' }}
          </template>
        </el-table-column>
        <el-table-column label="Token" width="180">
          <template #default="{ row }">
            {{ formatNum(row.tokenUsed) }} / {{ row.tokenLimit > 0 ? formatNum(row.tokenLimit) : '不限' }}
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="periodKey" label="当期键" width="110" />
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">{{ formatTime(row.configUpdateTime) }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="warning" size="small" @click="resetUsage(row)">重置用量</el-button>
            <el-button link type="danger" size="small" :disabled="!row.custom"
                       @click="restoreDefault(row)">恢复默认</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
                     :page-size="size" :current-page="page" @current-change="load" />
    </el-card>

    <el-dialog v-model="dialogVisible" title="编辑用户配额" width="440px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="用户名">
          <el-input v-model="form.username" disabled />
        </el-form-item>
        <el-form-item label="统计周期">
          <el-radio-group v-model="form.period">
            <el-radio label="DAY">按日</el-radio>
            <el-radio label="MONTH">按月</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="请求数上限">
          <el-input-number v-model="form.chatLimit" :min="0" :precision="0" :step="10" controls-position="right" />
          <span class="hint">0 表示不限制</span>
        </el-form-item>
        <el-form-item label="Token 上限">
          <el-input-number v-model="form.tokenLimit" :min="0" :precision="0" :step="100000" controls-position="right" />
          <span class="hint">0 表示不限制</span>
        </el-form-item>
        <el-form-item label="启用自定义">
          <el-switch v-model="form.enabled" />
          <span class="hint">关闭后回退全局默认</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  quotaAdminPage,
  quotaAdminSave,
  quotaAdminResetUsage,
  quotaAdminRestoreDefault
} from '../../api'

const query = reactive({ username: '' })
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const editingRow = reactive({ chatUsed: 0, tokenUsed: 0 })
const form = reactive({
  username: '',
  period: 'DAY',
  chatLimit: 0,
  tokenLimit: 0,
  enabled: true,
  remark: ''
})

const formatNum = (n) => Number(n || 0).toLocaleString('zh-CN')
const formatTime = (t) => (t ? String(t).replace('T', ' ').substring(0, 19) : '—')

async function load(toPage = 1) {
  page.value = toPage
  loading.value = true
  try {
    const res = await quotaAdminPage({ page: page.value, size: size.value, username: query.username || undefined })
    rows.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  Object.assign(editingRow, { chatUsed: row.chatUsed || 0, tokenUsed: row.tokenUsed || 0 })
  Object.assign(form, {
    username: row.username,
    period: row.period || 'DAY',
    chatLimit: row.chatLimit || 0,
    tokenLimit: row.tokenLimit || 0,
    enabled: row.enabled !== false,
    remark: row.remark || ''
  })
  dialogVisible.value = true
}

async function save() {
  // 基础校验：非负整数（el-input-number :min="0" :precision="0" 已兜底，这里防御一次）
  for (const [label, v] of [['请求数上限', form.chatLimit], ['Token 上限', form.tokenLimit]]) {
    if (!Number.isInteger(v) || v < 0) {
      ElMessage.error(`${label}必须为非负整数`)
      return
    }
  }
  // 上限不高于当期已用量：提示但不阻断（保存后该用户将立即被限流）
  const warnings = []
  if (form.chatLimit > 0 && form.chatLimit <= editingRow.chatUsed) {
    warnings.push(`请求数上限 ${form.chatLimit} 不高于当期已用量 ${editingRow.chatUsed}`)
  }
  if (form.tokenLimit > 0 && form.tokenLimit <= editingRow.tokenUsed) {
    warnings.push(`Token 上限 ${formatNum(form.tokenLimit)} 不高于当期已用量 ${formatNum(editingRow.tokenUsed)}`)
  }
  if (warnings.length) {
    await ElMessageBox.confirm(
      `${warnings.join('；')}。保存后该用户当期将立即被限流，是否继续？`,
      '上限低于已用量', { type: 'warning', confirmButtonText: '仍要保存', cancelButtonText: '再想想' })
  }

  saving.value = true
  try {
    await quotaAdminSave(form.username, {
      period: form.period,
      chatLimit: form.chatLimit,
      tokenLimit: form.tokenLimit,
      enabled: form.enabled,
      remark: form.remark
    })
    ElMessage.success('配额已保存')
    dialogVisible.value = false
    await load(page.value)
  } finally {
    saving.value = false
  }
}

async function resetUsage(row) {
  await ElMessageBox.confirm(`确认清零用户「${row.username}」当期用量？`, '重置用量', { type: 'warning' })
  await quotaAdminResetUsage(row.username)
  ElMessage.success('当期用量已清零')
  await load(page.value)
}

async function restoreDefault(row) {
  await ElMessageBox.confirm(
    `确认将用户「${row.username}」恢复为全局默认配额？`, '恢复默认', { type: 'warning' })
  await quotaAdminRestoreDefault(row.username)
  ElMessage.success('已恢复全局默认')
  await load(page.value)
}

load(1)
</script>

<style scoped>
.quota-management .card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.hint {
  margin-left: 10px;
  color: #909399;
  font-size: 12px;
}
</style>
