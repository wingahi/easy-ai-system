<template>
  <div class="tenant-management">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>租户管理</span>
          <div>
            <el-button size="small" @click="load" :loading="loading">刷新</el-button>
            <el-button v-if="canManage" size="small" type="primary" @click="openCreate">新建租户</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="loadError" class="api-alert" type="warning" show-icon :closable="false"
                :title="'租户接口暂不可用：' + loadError" />

      <el-table :data="rows" v-loading="loading" size="small" row-key="id" stripe>
        <el-table-column prop="code" label="租户编码" width="150" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="plan" label="套餐" width="130" />
        <el-table-column prop="isolationMode" label="隔离模式" width="110" />
        <el-table-column label="更新时间" width="165">
          <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="330" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canUpdate" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button v-if="canManage" link type="warning" size="small"
                       :disabled="row.code === 'default'" @click="toggleStatus(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button v-if="canQuota" link type="primary" size="small" @click="openQuota(row)">配额</el-button>
            <el-button v-if="canSchedule" link type="primary" size="small"
                       @click="openSchedule(row)">调度策略</el-button>
            <el-button v-if="canDisable" link type="danger" size="small"
                       :disabled="row.code === 'default' || row.status === 0" @click="offline(row)">下线</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty :description="loadError ? '租户接口暂不可用' : '暂无租户'" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- 新建 -->
    <el-dialog v-model="createVisible" title="新建租户" width="480px">
      <el-form :model="createForm" label-width="90px">
        <el-form-item label="租户编码" required>
          <el-input v-model="createForm.code" placeholder="小写字母/数字/下划线/中划线，最多 64 位" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="createForm.name" placeholder="默认与编码相同" />
        </el-form-item>
        <el-form-item label="套餐">
          <el-select v-model="createForm.plan" style="width: 100%">
            <el-option v-for="p in PLAN_OPTIONS" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 编辑 -->
    <el-dialog v-model="editVisible" title="编辑租户" width="480px">
      <el-form :model="editForm" label-width="90px">
        <el-form-item label="租户编码">
          <el-input :model-value="editForm.code" disabled />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="套餐">
          <el-select v-model="editForm.plan" style="width: 100%">
            <el-option v-for="p in PLAN_OPTIONS" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="隔离模式">
          <el-select v-model="editForm.isolationMode" style="width: 100%">
            <el-option v-for="m in ISOLATION_OPTIONS" :key="m.value" :label="m.label" :value="m.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 配额 -->
    <el-dialog v-model="quotaVisible" title="租户配额配置" width="520px">
      <div v-loading="quotaLoading">
        <el-alert v-if="quota" type="info" show-icon :closable="false" class="api-alert"
                  :title="quota.custom ? '当前为租户自定义配置' : '当前未配置，展示全局默认值；保存后生效为自定义配置'" />
        <el-form :model="quotaForm" label-width="110px">
          <el-form-item label="配额周期">
            <el-select v-model="quotaForm.period" style="width: 100%">
              <el-option label="按日" value="DAY" />
              <el-option label="按月" value="MONTH" />
            </el-select>
          </el-form-item>
          <el-form-item label="请求数上限">
            <el-input-number v-model="quotaForm.chatLimit" :min="0" :max="10000000" controls-position="right"
                             style="width: 100%" />
          </el-form-item>
          <el-form-item label="Token 上限">
            <el-input-number v-model="quotaForm.tokenLimit" :min="0" :max="1000000000" controls-position="right"
                             style="width: 100%" />
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="quotaForm.enabled" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="quotaForm.remark" maxlength="255" show-word-limit type="textarea" :rows="2" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="quotaVisible = false">取消</el-button>
        <el-button type="primary" :loading="quotaSaving" :disabled="quotaLoading" @click="submitQuota">保存</el-button>
      </template>
    </el-dialog>

    <!-- 调度策略 -->
    <el-dialog v-model="scheduleVisible" title="租户调度策略" width="1100px" top="6vh">
      <div v-loading="scheduleLoading">
        <el-alert type="info" show-icon :closable="false" class="api-alert"
                  title="未配置的车道按默认策略展示（来源=默认），保存后写入策略表并热更新" />
        <el-table :data="policies" size="small" stripe>
          <el-table-column prop="lane" label="车道" width="120" />
          <el-table-column label="来源" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.custom ? 'success' : 'info'" effect="plain">
                {{ row.custom ? '策略表' : '默认' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="启用" width="80">
            <template #default="{ row }"><el-switch v-model="row.enabled" /></template>
          </el-table-column>
          <el-table-column label="并发上限" width="120">
            <template #default="{ row }">
              <el-input-number v-model="row.maxConcurrent" :min="1" :max="1000" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="排队上限" width="120">
            <template #default="{ row }">
              <el-input-number v-model="row.maxQueue" :min="0" :max="100000" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="权重" width="110">
            <template #default="{ row }">
              <el-input-number v-model="row.weight" :min="1" :max="100" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="优先级加成" width="120">
            <template #default="{ row }">
              <el-input-number v-model="row.priorityBoost" :min="0" :max="1000" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="拒绝方式" width="120">
            <template #default="{ row }">
              <el-select v-model="row.rejectPolicy" size="small">
                <el-option label="REJECT" value="REJECT" />
                <el-option label="QUEUE" value="QUEUE" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="排队超时(秒)" width="130">
            <template #default="{ row }">
              <el-input-number v-model="row.queueTimeoutSeconds" :min="0" :max="86400" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="当前运行/排队" width="130">
            <template #default="{ row }">{{ row.running }} / {{ row.queued }}</template>
          </el-table-column>
          <el-table-column prop="version" label="版本" width="70" />
          <el-table-column label="备注" min-width="140">
            <template #default="{ row }"><el-input v-model="row.remark" size="small" placeholder="可选" /></template>
          </el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button @click="scheduleVisible = false">取消</el-button>
        <el-button type="primary" :loading="scheduleSaving" :disabled="scheduleLoading || !policies.length"
                   @click="submitSchedule">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  tenantList, tenantCreate, tenantUpdate, tenantUpdateStatus, tenantOffline,
  tenantQuota, tenantSaveQuota, tenantSchedulePolicies, tenantUpdateSchedulePolicies
} from '../../api'
import { useUserStore } from '../../stores/user'

const PLAN_OPTIONS = [
  { value: 'standard', label: 'standard（标准）' },
  { value: 'professional', label: 'professional（专业）' },
  { value: 'enterprise', label: 'enterprise（企业）' }
]

const ISOLATION_OPTIONS = [
  { value: 'shared', label: 'shared（共享库）' },
  { value: 'schema', label: 'schema（独立 schema）' },
  { value: 'database', label: 'database（独立库）' }
]

const userStore = useUserStore()
const canManage = computed(() => userStore.hasPerm('tenant:manage'))
const canUpdate = computed(() => userStore.hasPerm('tenant:update'))
const canDisable = computed(() => userStore.hasPerm('tenant:disable'))
const canQuota = computed(() => userStore.hasPerm('tenant:quota'))
const canSchedule = computed(() => userStore.hasPerm('tenant:schedule'))

const rows = ref([])
const loading = ref(false)
const loadError = ref('')
const submitting = ref(false)

const createVisible = ref(false)
const createForm = reactive({ code: '', name: '', plan: 'standard' })

const editVisible = ref(false)
const editForm = reactive({ id: null, code: '', name: '', plan: 'standard', isolationMode: 'shared' })

const quotaVisible = ref(false)
const quotaLoading = ref(false)
const quotaSaving = ref(false)
const quota = ref(null)
const quotaTenantId = ref(null)
const quotaForm = reactive({ period: 'DAY', chatLimit: 0, tokenLimit: 0, enabled: true, remark: '' })

const scheduleVisible = ref(false)
const scheduleLoading = ref(false)
const scheduleSaving = ref(false)
const scheduleTenantId = ref(null)
const scheduleTenantCode = ref('')
const policies = ref([])

const apiMessage = (e, fallback) => e?.response?.data?.message || e?.message || fallback

const load = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const res = await tenantList()
    rows.value = res?.data || []
  } catch (e) {
    rows.value = []
    loadError.value = apiMessage(e, '请求失败')
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  createForm.code = ''
  createForm.name = ''
  createForm.plan = 'standard'
  createVisible.value = true
}

const submitCreate = async () => {
  if (!createForm.code.trim()) { ElMessage.warning('请填写租户编码'); return }
  submitting.value = true
  try {
    await tenantCreate({ code: createForm.code.trim(), name: createForm.name.trim(), plan: createForm.plan })
    ElMessage.success('租户已创建')
    createVisible.value = false
    await load()
  } catch (e) {
    if (!e?.response?.data?.message) ElMessage.error(apiMessage(e, '创建失败'))
  } finally {
    submitting.value = false
  }
}

const openEdit = (row) => {
  editForm.id = row.id
  editForm.code = row.code
  editForm.name = row.name
  editForm.plan = row.plan || 'standard'
  editForm.isolationMode = row.isolationMode || 'shared'
  editVisible.value = true
}

const submitEdit = async () => {
  if (!editForm.name.trim()) { ElMessage.warning('租户名称不能为空'); return }
  submitting.value = true
  try {
    await tenantUpdate(editForm.id, {
      name: editForm.name.trim(), plan: editForm.plan, isolationMode: editForm.isolationMode
    })
    ElMessage.success('租户已更新')
    editVisible.value = false
    await load()
  } catch (e) {
    if (!e?.response?.data?.message) ElMessage.error(apiMessage(e, '更新失败'))
  } finally {
    submitting.value = false
  }
}

const toggleStatus = async (row) => {
  const next = row.status === 1 ? 0 : 1
  const action = next === 1 ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(`确认${action}租户「${row.name || row.code}」？`, `${action}租户`, { type: 'warning' })
  } catch { return }
  try {
    await tenantUpdateStatus(row.id, next)
    ElMessage.success(`已${action}`)
    await load()
  } catch (e) {
    if (!e?.response?.data?.message) ElMessage.error(apiMessage(e, `${action}失败`))
  }
}

const offline = async (row) => {
  try {
    await ElMessageBox.confirm(
      `下线租户「${row.name || row.code}」后将逻辑删除该租户，其令牌与 API Key 一律拒绝，业务数据保留。确认继续？`,
      '租户下线', { type: 'warning', confirmButtonText: '确认下线' }
    )
  } catch { return }
  try {
    await tenantOffline(row.id)
    ElMessage.success('租户已下线')
    await load()
  } catch (e) {
    if (!e?.response?.data?.message) ElMessage.error(apiMessage(e, '下线失败'))
  }
}

const openQuota = async (row) => {
  quotaVisible.value = true
  quotaLoading.value = true
  quota.value = null
  quotaTenantId.value = row.id
  try {
    const res = await tenantQuota(row.id)
    const data = res?.data
    quota.value = data
    quotaForm.period = data?.period || 'DAY'
    quotaForm.chatLimit = data?.chatLimit ?? 0
    quotaForm.tokenLimit = data?.tokenLimit ?? 0
    quotaForm.enabled = data?.enabled !== false
    quotaForm.remark = data?.remark || ''
  } catch (e) {
    quotaVisible.value = false
    if (!e?.response?.data?.message) ElMessage.error(apiMessage(e, '配额加载失败'))
  } finally {
    quotaLoading.value = false
  }
}

const submitQuota = async () => {
  if (!quotaTenantId.value) return
  quotaSaving.value = true
  try {
    const res = await tenantSaveQuota(quotaTenantId.value, {
      period: quotaForm.period,
      chatLimit: quotaForm.chatLimit,
      tokenLimit: quotaForm.tokenLimit,
      enabled: quotaForm.enabled,
      remark: quotaForm.remark
    })
    quota.value = res?.data || null
    ElMessage.success('租户配额已保存')
    quotaVisible.value = false
  } catch (e) {
    if (!e?.response?.data?.message) ElMessage.error(apiMessage(e, '配额保存失败'))
  } finally {
    quotaSaving.value = false
  }
}

const openSchedule = async (row) => {
  scheduleVisible.value = true
  scheduleLoading.value = true
  policies.value = []
  scheduleTenantId.value = row.id
  scheduleTenantCode.value = row.code
  try {
    const res = await tenantSchedulePolicies(row.id)
    policies.value = res?.data || []
  } catch (e) {
    scheduleVisible.value = false
    if (!e?.response?.data?.message) ElMessage.error(apiMessage(e, '调度策略加载失败'))
  } finally {
    scheduleLoading.value = false
  }
}

const submitSchedule = async () => {
  if (!scheduleTenantId.value || !policies.value.length) return
  scheduleSaving.value = true
  try {
    const payload = policies.value.map(p => ({
      lane: p.lane,
      enabled: p.enabled,
      maxConcurrent: p.maxConcurrent,
      maxQueue: p.maxQueue,
      weight: p.weight,
      priorityBoost: p.priorityBoost,
      rejectPolicy: p.rejectPolicy,
      queueTimeoutSeconds: p.queueTimeoutSeconds,
      dailyJobLimit: p.dailyJobLimit,
      ratePerMinute: p.ratePerMinute,
      timeWindowJson: p.timeWindowJson,
      remark: p.remark,
      version: p.custom ? p.version : null
    }))
    const res = await tenantUpdateSchedulePolicies(scheduleTenantId.value, payload)
    policies.value = res?.data || []
    ElMessage.success(`租户「${scheduleTenantCode.value}」调度策略已保存`)
    scheduleVisible.value = false
  } catch (e) {
    if (!e?.response?.data?.message) ElMessage.error(apiMessage(e, '调度策略保存失败'))
  } finally {
    scheduleSaving.value = false
  }
}

const formatTime = (value) => {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 19)
}

onMounted(load)
</script>

<style scoped lang="scss">
.tenant-management {
  padding: 20px;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.api-alert {
  margin-bottom: 12px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}
</style>
