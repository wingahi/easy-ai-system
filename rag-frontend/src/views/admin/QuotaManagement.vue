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

    <el-card shadow="never" class="refund-card">
      <template #header>
        <div class="card-header">
          <span>退款异常流水</span>
          <div>
            <el-select v-model="refundQuery.status" placeholder="全部异常" size="small" clearable
                       style="width: 150px; margin-right: 8px">
              <el-option label="退款失败" value="REFUND_FAILED" />
              <el-option label="人工处理（DEAD）" value="DEAD" />
            </el-select>
            <el-input v-model="refundQuery.username" placeholder="按用户名筛选" size="small" clearable
                      style="width: 160px; margin-right: 8px" @keyup.enter="loadRefunds(1)" />
            <el-button size="small" type="primary" @click="loadRefunds(1)">查询</el-button>
            <el-button size="small" @click="loadRefunds(refundPage)">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table :data="refundRows" v-loading="refundLoading" size="small" row-key="chargeId">
        <el-table-column prop="chargeId" label="扣费标识" min-width="190" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.chargeId }}</span></template>
        </el-table-column>
        <el-table-column prop="username" label="用户名" width="130" show-overflow-tooltip />
        <el-table-column prop="periodKey" label="周期键" width="110" />
        <el-table-column prop="amount" label="条数" width="70" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="statusMeta(row.status).type">
              {{ statusMeta(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="retryCount" label="已重试" width="80" />
        <el-table-column label="下次重试" width="160">
          <template #default="{ row }">{{ formatTime(row.nextRetryAt) }}</template>
        </el-table-column>
        <el-table-column prop="lastError" label="最近错误" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.lastError || '—' }}</template>
        </el-table-column>
        <el-table-column label="更新时间" width="160">
          <template #default="{ row }">{{ formatTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openRefundDetail(row)">详情</el-button>
            <el-button v-if="canRefund(row.status)" link type="warning" size="small"
                       :loading="rowActionLoading[row.chargeId + ':refund']"
                       @click="manualRefund(row)">人工退款</el-button>
            <el-button v-if="canWriteOff(row.status)" link type="success" size="small"
                       :loading="rowActionLoading[row.chargeId + ':writeOff']"
                       @click="manualWriteOff(row)">核销</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination class="pager" layout="total, prev, pager, next" :total="refundTotal"
                     :page-size="refundSize" :current-page="refundPage"
                     @current-change="loadRefunds" />
    </el-card>

    <el-dialog v-model="refundDetailVisible" title="退款流水详情" width="560px">
      <div v-loading="refundDetailLoading">
        <el-descriptions v-if="refundDetail" :column="2" border size="small">
          <el-descriptions-item label="扣费标识" :span="2">
            <span class="mono">{{ refundDetail.chargeId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="用户名">{{ refundDetail.username || '—' }}</el-descriptions-item>
          <el-descriptions-item label="周期">{{ refundDetail.periodKey || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="statusMeta(refundDetail.status).type">
              {{ statusMeta(refundDetail.status).label }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="计费条数">{{ refundDetail.amount ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="重试次数">{{ refundDetail.retryCount ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="下次重试">{{ formatTime(refundDetail.nextRetryAt) }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(refundDetail.createTime) }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ formatTime(refundDetail.updateTime) }}</el-descriptions-item>
          <el-descriptions-item label="失败原因" :span="2">{{ refundDetail.lastError || '—' }}</el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button @click="refundDetailVisible = false">关闭</el-button>
        <el-button v-if="refundDetail && canRefund(refundDetail.status)" type="warning"
                   :loading="rowActionLoading[refundDetail.chargeId + ':refund']"
                   @click="manualRefund(refundDetail)">人工退款</el-button>
        <el-button v-if="refundDetail && canWriteOff(refundDetail.status)" type="success"
                   :loading="rowActionLoading[refundDetail.chargeId + ':writeOff']"
                   @click="manualWriteOff(refundDetail)">核销</el-button>
      </template>
    </el-dialog>

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
  quotaAdminRestoreDefault,
  quotaRefundIssues,
  quotaRefundDetail,
  quotaRefundManual,
  quotaRefundWriteOff
} from '../../api'

const query = reactive({ username: '' })
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)

const refundQuery = reactive({ status: '', username: '' })
const refundRows = ref([])
const refundTotal = ref(0)
const refundPage = ref(1)
const refundSize = ref(20)
const refundLoading = ref(false)

const refundDetailVisible = ref(false)
const refundDetailLoading = ref(false)
const refundDetail = ref(null)
const rowActionLoading = reactive({})

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

async function loadRefunds(toPage = 1) {
  refundPage.value = toPage
  refundLoading.value = true
  try {
    const res = await quotaRefundIssues({
      page: refundPage.value,
      size: refundSize.value,
      status: refundQuery.status || undefined,
      username: refundQuery.username || undefined
    })
    refundRows.value = res.data.records || []
    refundTotal.value = res.data.total || 0
  } finally {
    refundLoading.value = false
  }
}

const STATUS_META = {
  CHARGED: { label: '已扣未决', type: 'primary' },
  REFUND_FAILED: { label: '退款失败', type: 'warning' },
  DEAD: { label: '人工处理', type: 'danger' },
  SETTLED: { label: '已结算', type: 'success' },
  REFUNDED: { label: '已退款', type: 'info' }
}
const statusMeta = (status) => STATUS_META[status] || { label: status || '—', type: 'info' }
// 可人工退款：死信、退款失败、已扣未决
const canRefund = (status) => ['DEAD', 'REFUND_FAILED', 'CHARGED'].includes(status)
// 可核销为已结算：死信与失败态（已结算/已退款不再提供操作）
const canWriteOff = (status) => ['DEAD', 'REFUND_FAILED'].includes(status)

const GENERIC_SUCCESS_TEXT = new Set(['', '操作成功', 'success'])
const REFUND_RESULT_TEXT = {
  REFUNDED: '退款成功，已回退该笔计费',
  DUPLICATE: '该流水此前已退款，无需重复操作',
  NOTHING_TO_REFUND: '该流水没有可退的计费',
  FAILED: '退款失败，请查看流水的失败原因'
}
const WRITE_OFF_RESULT_TEXT = {
  WRITTEN_OFF: '已核销为已结算，该笔计费不再重试',
  DUPLICATE: '该流水已结算，无需重复核销',
  NOTHING_TO_REFUND: '该流水无需核销'
}

/** 优先透传后端业务 message，通用成功文案则回退到本地结果说明。 */
function resolveBizMessage(res, fallback) {
  const msg = res && res.message ? String(res.message).trim() : ''
  return GENERIC_SUCCESS_TEXT.has(msg) ? fallback : msg
}

async function openRefundDetail(row) {
  refundDetail.value = row
  refundDetailVisible.value = true
  refundDetailLoading.value = true
  try {
    const res = await quotaRefundDetail(row.chargeId)
    refundDetail.value = res.data || row
  } catch {
    // 错误提示由请求拦截器统一透传后端 message，此处保留列表行数据兜底
  } finally {
    refundDetailLoading.value = false
  }
}

async function refreshRefundDetail(chargeId) {
  if (!refundDetailVisible.value || !refundDetail.value || refundDetail.value.chargeId !== chargeId) {
    return
  }
  try {
    const res = await quotaRefundDetail(chargeId)
    refundDetail.value = res.data || refundDetail.value
  } catch {
    // 刷新详情失败不阻断主流程，列表仍会刷新
  }
}

async function manualRefund(row) {
  try {
    await ElMessageBox.confirm(
      `确认对流水「${row.chargeId}」发起人工退款？将按该笔流水回退一次计费，减少用户「${row.username}」`
      + `当期（${row.periodKey || '—'}）已用次数。`,
      '人工退款', { type: 'warning', confirmButtonText: '确认退款', cancelButtonText: '取消' })
  } catch {
    // 用户取消确认：不执行退款
    return
  }
  const key = `${row.chargeId}:refund`
  rowActionLoading[key] = true
  try {
    const res = await quotaRefundManual(row.chargeId)
    const data = res.data || {}
    const text = resolveBizMessage(res, REFUND_RESULT_TEXT[data.result] || '退款操作已完成')
    if (data.result === 'FAILED') {
      ElMessage.error(text)
    } else if (data.result === 'DUPLICATE' || data.result === 'NOTHING_TO_REFUND') {
      ElMessage.warning(text)
    } else {
      ElMessage.success(text)
    }
  } catch {
    // 错误提示由请求拦截器统一透传后端 message
  } finally {
    rowActionLoading[key] = false
    await loadRefunds(refundPage.value)
    await refreshRefundDetail(row.chargeId)
  }
}

async function manualWriteOff(row) {
  try {
    await ElMessageBox.confirm(
      `确认将流水「${row.chargeId}」核销为已结算？该笔计费将被视为有效，不再重试退款，`
      + '不会改变用户当期已用次数。',
      '人工核销', { type: 'warning', confirmButtonText: '确认核销', cancelButtonText: '取消' })
  } catch {
    // 用户取消确认：不执行核销
    return
  }
  const key = `${row.chargeId}:writeOff`
  rowActionLoading[key] = true
  try {
    const res = await quotaRefundWriteOff(row.chargeId)
    const data = res.data || {}
    const text = resolveBizMessage(res, WRITE_OFF_RESULT_TEXT[data.result] || '核销操作已完成')
    if (data.applied) {
      ElMessage.success(text)
    } else {
      ElMessage.warning(text)
    }
  } catch {
    // 错误提示由请求拦截器统一透传后端 message
  } finally {
    rowActionLoading[key] = false
    await loadRefunds(refundPage.value)
    await refreshRefundDetail(row.chargeId)
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
    try {
      await ElMessageBox.confirm(
        `${warnings.join('；')}。保存后该用户当期将立即被限流，是否继续？`,
        '上限低于已用量', { type: 'warning', confirmButtonText: '仍要保存', cancelButtonText: '再想想' })
    } catch {
      // 用户取消确认：保持弹窗打开，不保存
      return
    }
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
  try {
    await ElMessageBox.confirm(`确认清零用户「${row.username}」当期用量？`, '重置用量', { type: 'warning' })
  } catch {
    // 用户取消确认：不执行重置
    return
  }
  await quotaAdminResetUsage(row.username)
  ElMessage.success('当期用量已清零')
  await load(page.value)
}

async function restoreDefault(row) {
  try {
    await ElMessageBox.confirm(
      `确认将用户「${row.username}」恢复为全局默认配额？`, '恢复默认', { type: 'warning' })
  } catch {
    // 用户取消确认：不执行恢复
    return
  }
  await quotaAdminRestoreDefault(row.username)
  ElMessage.success('已恢复全局默认')
  await load(page.value)
}

load(1)
loadRefunds(1)
</script>

<style scoped>
.quota-management .card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.refund-card {
  margin-top: 16px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.hint {
  margin-left: 10px;
  color: #909399;
  font-size: 12px;
}
</style>
