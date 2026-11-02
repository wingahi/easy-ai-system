<template>
  <div class="my-quota">
    <el-card shadow="never" v-loading="loading">
      <template #header>
        <div class="card-header">
          <span>我的配额（{{ quota.username || '—' }}）</span>
          <div>
            <el-tag :type="quota.source === 'CUSTOM' ? 'success' : 'info'" size="small">
              {{ quota.source === 'CUSTOM' ? '自定义配置' : '全局默认' }}
            </el-tag>
            <el-button size="small" style="margin-left: 8px" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="quota.nearLimit" type="warning" show-icon :closable="false"
                title="配额接近上限"
                description="当期用量已达上限的 90%，超出后将拒绝新的对话请求（429）。"
                style="margin-bottom: 16px" />

      <div class="quota-body">
        <div class="quota-item">
          <div class="quota-label">
            对话条数（{{ quota.period === 'MONTH' ? '本月' : '今日' }} · {{ quota.periodKey }}）
          </div>
          <div class="quota-num">
            {{ quota.chatUsed }} / {{ quota.chatLimit > 0 ? quota.chatLimit : '不限' }}
          </div>
          <el-progress :percentage="chatPct" :status="chatPct >= 90 ? 'exception' : ''" />
          <div class="quota-remain" v-if="quota.chatRemaining !== null">
            剩余 {{ quota.chatRemaining }} 条
          </div>
        </div>

        <div class="quota-item">
          <div class="quota-label">
            Token 用量（{{ quota.period === 'MONTH' ? '本月' : '今日' }} · {{ quota.periodKey }}）
          </div>
          <div class="quota-num">
            {{ formatNum(quota.tokenUsed) }} / {{ quota.tokenLimit > 0 ? formatNum(quota.tokenLimit) : '不限' }}
          </div>
          <el-progress :percentage="tokenPct" :status="tokenPct >= 90 ? 'exception' : ''" />
          <div class="quota-remain" v-if="quota.tokenRemaining !== null">
            剩余 {{ formatNum(quota.tokenRemaining) }} tokens
          </div>
        </div>
      </div>

      <div class="tip">
        配额按自然{{ quota.period === 'MONTH' ? '月' : '日' }}重置；如需调整上限，请联系管理员。
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { myQuota } from '../../api'

const loading = ref(false)
const quota = reactive({
  username: '',
  source: 'DEFAULT',
  period: 'DAY',
  periodKey: '',
  chatLimit: 0,
  chatUsed: 0,
  chatRemaining: null,
  tokenLimit: 0,
  tokenUsed: 0,
  tokenRemaining: null,
  nearLimit: false
})

const pct = (used, limit) => limit > 0 ? Math.min(100, Math.round(used / limit * 100)) : 0
const chatPct = computed(() => pct(quota.chatUsed, quota.chatLimit))
const tokenPct = computed(() => pct(quota.tokenUsed, quota.tokenLimit))
const formatNum = (n) => Number(n || 0).toLocaleString('zh-CN')

async function load() {
  loading.value = true
  try {
    const res = await myQuota()
    Object.assign(quota, res.data)
  } finally {
    loading.value = false
  }
}

load()
</script>

<style scoped>
.my-quota .card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.quota-body {
  display: flex;
  gap: 48px;
  flex-wrap: wrap;
}
.quota-item {
  flex: 1;
  min-width: 280px;
  max-width: 420px;
}
.quota-label {
  color: #909399;
  font-size: 12px;
  margin-bottom: 6px;
}
.quota-num {
  font-size: 20px;
  font-weight: 600;
  margin-bottom: 8px;
}
.quota-remain {
  color: #909399;
  font-size: 12px;
  margin-top: 6px;
}
.tip {
  margin-top: 20px;
  color: #909399;
  font-size: 12px;
}
</style>
