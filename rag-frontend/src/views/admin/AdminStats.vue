<template>
  <div class="admin-stats">
    <!-- 当日配额 -->
    <el-card class="quota-card" shadow="never">
      <template #header><span>我的当日配额（{{ quota.dateKey }}）</span></template>
      <div class="quota-body" v-loading="quotaLoading">
        <div class="quota-item">
          <div class="quota-num">{{ quota.chatCount }} / {{ quota.chatDailyLimit }}</div>
          <div class="quota-label">对话条数</div>
          <el-progress :percentage="quotaChatPct" :status="quotaChatPct >= 90 ? 'exception' : ''" />
        </div>
        <div class="quota-item">
          <div class="quota-num">{{ formatNum(quota.tokenUsed) }} / {{ formatNum(quota.tokenDailyLimit) }}</div>
          <div class="quota-label">Token 用量</div>
          <el-progress :percentage="quotaTokenPct" :status="quotaTokenPct >= 90 ? 'exception' : ''" />
        </div>
      </div>
    </el-card>

    <!-- 成本统计 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>成本统计（按天 + 模型）</span>
          <div>
            <el-date-picker v-model="costRange" type="daterange" value-format="YYYY-MM-DD"
                            start-placeholder="开始" end-placeholder="结束" size="small"
                            style="width: 260px; margin-right: 8px" @change="loadCost" />
            <el-button size="small" @click="loadCost">刷新</el-button>
          </div>
        </div>
      </template>
      <el-table :data="costRows" v-loading="costLoading" size="small">
        <el-table-column prop="date" label="日期" width="120" />
        <el-table-column prop="modelName" label="模型" width="180" />
        <el-table-column prop="messages" label="消息数" width="100" align="right" />
        <el-table-column prop="tokens" label="Tokens" width="120" align="right" />
        <el-table-column label="成本（元）" align="right">
          <template #default="{ row }">{{ Number(row.cost).toFixed(6) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 差评反馈（运营闭环） -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>差评反馈</span>
          <el-button size="small" @click="loadDislikes(1)">刷新</el-button>
        </div>
      </template>
      <el-table :data="dislikeRows" v-loading="dislikeLoading" size="small">
        <el-table-column prop="createTime" label="时间" width="170" />
        <el-table-column prop="windowName" label="窗口" width="150" show-overflow-tooltip />
        <el-table-column prop="userQuery" label="用户问题" show-overflow-tooltip />
        <el-table-column prop="answer" label="差评答案" show-overflow-tooltip />
        <el-table-column prop="feedbackComment" label="反馈说明" width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.feedbackComment || '—' }}</template>
        </el-table-column>
      </el-table>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="dislikeTotal"
                     :page-size="20" :current-page="dislikePage"
                     @current-change="loadDislikes" />
    </el-card>

    <!-- 审计日志 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>审计日志</span>
          <div>
            <el-input v-model="auditQuery.username" placeholder="用户名" size="small" clearable
                      style="width: 140px; margin-right: 8px" @keyup.enter="loadAudit(1)" />
            <el-input v-model="auditQuery.action" placeholder="操作（如 KB_CREATE）" size="small" clearable
                      style="width: 180px; margin-right: 8px" @keyup.enter="loadAudit(1)" />
            <el-button size="small" type="primary" @click="loadAudit(1)">查询</el-button>
          </div>
        </div>
      </template>
      <el-table :data="auditRows" v-loading="auditLoading" size="small">
        <el-table-column prop="createTime" label="时间" width="170" />
        <el-table-column prop="username" label="用户" width="100" />
        <el-table-column prop="action" label="操作" width="170" />
        <el-table-column prop="resourceType" label="资源类型" width="140" />
        <el-table-column prop="resourceId" label="资源ID" width="80" />
        <el-table-column prop="ip" label="IP" width="120" />
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="详情" show-overflow-tooltip />
      </el-table>
      <el-pagination class="pager" layout="total, prev, pager, next" :total="auditTotal"
                     :page-size="auditQuery.size" :current-page="auditQuery.page"
                     @current-change="loadAudit" />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, computed } from 'vue'
import { adminAuditLogs, adminCostStats, adminQuotaStats, adminFeedbackDislikes } from '../../api'

const quota = reactive({ chatCount: 0, chatDailyLimit: 0, tokenUsed: 0, tokenDailyLimit: 0, dateKey: '' })
const quotaLoading = ref(false)
const quotaChatPct = computed(() => quota.chatDailyLimit > 0 ? Math.min(100, Math.round(quota.chatCount / quota.chatDailyLimit * 100)) : 0)
const quotaTokenPct = computed(() => quota.tokenDailyLimit > 0 ? Math.min(100, Math.round(quota.tokenUsed / quota.tokenDailyLimit * 100)) : 0)
const formatNum = (n) => (n || 0).toLocaleString()

const costRange = ref(null)
const costRows = ref([])
const costLoading = ref(false)

const auditRows = ref([])
const auditTotal = ref(0)
const auditLoading = ref(false)
const auditQuery = reactive({ page: 1, size: 20, username: '', action: '' })

const dislikeRows = ref([])
const dislikeTotal = ref(0)
const dislikePage = ref(1)
const dislikeLoading = ref(false)

async function loadDislikes(page) {
  if (typeof page === 'number') dislikePage.value = page
  dislikeLoading.value = true
  try {
    const { data } = await adminFeedbackDislikes({ page: dislikePage.value, size: 20 })
    dislikeRows.value = data.data.records || []
    dislikeTotal.value = data.data.total || 0
  } finally { dislikeLoading.value = false }
}

async function loadQuota() {
  quotaLoading.value = true
  try {
    const { data } = await adminQuotaStats()
    Object.assign(quota, data.data)
  } finally { quotaLoading.value = false }
}

async function loadCost() {
  costLoading.value = true
  try {
    const params = {}
    if (costRange.value && costRange.value.length === 2) {
      params.from = costRange.value[0]
      params.to = costRange.value[1]
    }
    const { data } = await adminCostStats(params)
    costRows.value = data.data || []
  } finally { costLoading.value = false }
}

async function loadAudit(page) {
  if (typeof page === 'number') auditQuery.page = page
  auditLoading.value = true
  try {
    const { data } = await adminAuditLogs({
      page: auditQuery.page, size: auditQuery.size,
      username: auditQuery.username || undefined,
      action: auditQuery.action || undefined
    })
    auditRows.value = data.data.records || []
    auditTotal.value = data.data.total || 0
  } finally { auditLoading.value = false }
}

onMounted(() => { loadQuota(); loadCost(); loadAudit(1); loadDislikes(1) })
</script>

<style scoped lang="scss">
.admin-stats {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.card-header { display: flex; justify-content: space-between; align-items: center; }
.quota-body { display: flex; gap: 48px; }
.quota-item { flex: 1; max-width: 360px; }
.quota-num { font-size: 20px; font-weight: 600; margin-bottom: 4px; }
.quota-label { color: #909399; font-size: 12px; margin-bottom: 6px; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
