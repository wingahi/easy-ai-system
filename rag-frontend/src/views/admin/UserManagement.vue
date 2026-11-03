<template>
  <div class="user-management">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>用户管理（体验账号）</span>
          <div>
            <el-input v-model="query.username" placeholder="搜索用户名" size="small" clearable
                      style="width: 160px; margin-right: 8px" @keyup.enter="load(1)" />
            <el-select v-model="query.source" placeholder="来源" size="small" clearable
                       style="width: 110px; margin-right: 8px" @change="load(1)">
              <el-option label="本地" value="local" />
              <el-option label="外部 OIDC" value="oidc" />
            </el-select>
            <el-select v-model="query.role" placeholder="角色" size="small" clearable
                       style="width: 110px; margin-right: 8px" @change="load(1)">
              <el-option label="admin" value="admin" />
              <el-option label="editor" value="editor" />
              <el-option label="viewer" value="viewer" />
            </el-select>
            <el-select v-model="query.enabled" placeholder="状态" size="small" clearable
                       style="width: 100px; margin-right: 8px" @change="load(1)">
              <el-option label="启用" :value="true" />
              <el-option label="禁用" :value="false" />
            </el-select>
            <el-button size="small" type="primary" @click="load(1)">查询</el-button>
            <el-button size="small" type="success" @click="openCreate">新建体验账号</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" v-loading="loading" size="small" row-key="username">
        <el-table-column prop="username" label="用户名" width="140" show-overflow-tooltip />
        <el-table-column label="来源" width="100">
          <template #default="{ row }">
            <el-tag :type="row.source === 'local' ? 'success' : 'info'" size="small">
              {{ row.source === 'local' ? '本地' : '外部 OIDC' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.roles && row.roles.includes('admin')" type="danger" size="small">管理员</el-tag>
            <el-tag v-else-if="row.trial" type="warning" size="small">体验账号</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="120">
          <template #default="{ row }">{{ row.roles && row.roles.length ? row.roles.join(', ') : '—' }}</template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="有效期至" width="170">
          <template #default="{ row }">
            <span v-if="row.expired" class="expired">{{ formatTime(row.expireAt) }}（已过期）</span>
            <span v-else>{{ row.expireAt ? formatTime(row.expireAt) : '长期' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="配额（请求/Tokens）" width="200">
          <template #default="{ row }">
            <span v-if="row.customQuota">
              {{ row.chatLimit > 0 ? row.chatLimit : '不限' }} /
              {{ row.tokenLimit > 0 ? formatNum(row.tokenLimit) : '不限' }}
            </span>
            <span v-else class="hint">全局默认</span>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" :disabled="!row.trial"
                       @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" :disabled="!row.trial || !row.enabled"
                       @click="disable(row)">禁用</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination class="pager" layout="total, prev, pager, next" :total="total"
                     :page-size="size" :current-page="page" @current-change="load" />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑体验账号' : '新建体验账号'" width="520px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="用户名">
          <el-input v-model="form.username" :disabled="editing" placeholder="3-32 位字母/数字/._-" />
        </el-form-item>
        <el-form-item v-if="!editing" label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="BCrypt 加密入库" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.role" style="width: 200px">
            <el-option label="viewer（只读）" value="viewer" />
            <el-option label="editor（读写）" value="editor" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="有效期">
          <el-date-picker v-model="form.expireAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                          placeholder="留空表示长期有效" clearable style="width: 220px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
        <el-divider content-position="left">配额（复用用户配额配置，留空回退全局默认）</el-divider>
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
        <el-form-item label="启用自定义配额">
          <el-switch v-model="form.quotaEnabled" />
          <span class="hint">关闭后回退全局默认</span>
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
import { adminUsersPage, adminUserCreate, adminUserUpdate, adminUserDisable } from '../../api'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editing = ref(false)

const query = reactive({ username: '', source: '', role: '', enabled: null })

const emptyForm = () => ({
  username: '', password: '', role: 'viewer', enabled: true,
  expireAt: null, remark: '',
  period: 'DAY', chatLimit: null, tokenLimit: null, quotaEnabled: true
})
const form = reactive(emptyForm())

const load = async (p = page.value) => {
  page.value = p
  loading.value = true
  try {
    const params = { page: p, size: size.value }
    if (query.username) params.username = query.username
    if (query.source) params.source = query.source
    if (query.role) params.role = query.role
    if (query.enabled !== null && query.enabled !== '') params.enabled = query.enabled
    const res = await adminUsersPage(params)
    rows.value = res.data.records || []
    total.value = res.data.total || 0
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  Object.assign(form, emptyForm())
  editing.value = false
  dialogVisible.value = true
}

const openEdit = (row) => {
  Object.assign(form, {
    username: row.username,
    password: '',
    role: row.roles?.includes('editor') ? 'editor' : 'viewer',
    enabled: row.enabled,
    expireAt: row.expireAt || null,
    remark: row.remark || '',
    period: row.period || 'DAY',
    chatLimit: row.chatLimit ?? null,
    tokenLimit: row.tokenLimit ?? null,
    quotaEnabled: row.customQuota ? row.quotaEnabled : false
  })
  editing.value = true
  dialogVisible.value = true
}

const save = async () => {
  if (!editing.value) {
    if (!form.username || !form.password) {
      ElMessage.warning('用户名和密码不能为空')
      return
    }
  }
  saving.value = true
  try {
    const payload = {
      username: form.username,
      password: form.password,
      role: form.role,
      enabled: form.enabled,
      expireAt: form.expireAt || null,
      remark: form.remark,
      period: form.period,
      chatLimit: form.chatLimit,
      tokenLimit: form.tokenLimit,
      quotaEnabled: form.quotaEnabled
    }
    if (editing.value) {
      delete payload.password
      await adminUserUpdate(form.username, payload)
      ElMessage.success('已保存')
    } else {
      await adminUserCreate(payload)
      ElMessage.success('体验账号已创建')
    }
    dialogVisible.value = false
    load(editing.value ? page.value : 1)
  } finally {
    saving.value = false
  }
}

const disable = async (row) => {
  await ElMessageBox.confirm(`确认禁用体验账号「${row.username}」？禁用后无法登录。`, '禁用确认', {
    type: 'warning'
  })
  await adminUserDisable(row.username)
  ElMessage.success('已禁用')
  load(page.value)
}

const formatTime = (value) => {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 19)
}

const formatNum = (value) => {
  const n = Number(value || 0)
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`
  return String(n)
}

load(1)
</script>

<style scoped lang="scss">
.user-management {
  .card-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }
  .pager { margin-top: 12px; text-align: right; }
  .hint { margin-left: 8px; color: #909399; font-size: 12px; }
  .expired { color: #f56c6c; }
}
</style>
