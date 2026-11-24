<template>
  <div class="page-container">
    <div class="page-header">
      <h2>调度中心</h2>
      <div class="header-actions">
        <el-switch v-model="autoRefresh" active-text="自动刷新" />
        <el-select v-model="refreshSeconds" size="small" style="width: 92px" :disabled="!autoRefresh">
          <el-option v-for="o in REFRESH_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-button size="small" type="primary" :icon="Refresh" :loading="loading" @click="load(page)">
          刷新
        </el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="scheduler-tabs">
      <el-tab-pane label="作业执行" name="jobs">
    <div class="stat-cards">
      <div class="stat-card">
        <div class="label">待执行</div>
        <div class="value">{{ overview.pendingCount }}</div>
      </div>
      <div class="stat-card">
        <div class="label">运行中（含已领取）</div>
        <div class="value primary">{{ overview.runningCount }}</div>
      </div>
      <div class="stat-card clickable" title="点击查看失败作业" @click="filterStatus('FAILED')">
        <div class="label">失败</div>
        <div class="value danger">{{ overview.failedCount }}</div>
      </div>
      <div class="stat-card clickable" title="点击查看死信作业" @click="filterStatus('DEAD')">
        <div class="label">死信</div>
        <div class="value danger">{{ overview.deadCount }}</div>
      </div>
      <div class="stat-card">
        <div class="label">超时 / 租约过期</div>
        <div class="value warning">{{ overview.timeoutOrLeaseExpiredCount }}</div>
      </div>
      <div class="stat-card">
        <div class="label">作业总数</div>
        <div class="value">{{ overview.total }}</div>
      </div>
    </div>

    <div class="card distribution-card">
      <div class="distribution-row">
        <span class="distribution-label">状态分布</span>
        <div class="distribution-tags">
          <el-tag v-for="(count, key) in overview.statusDistribution" :key="key" size="small"
                  :type="statusTag(key)" effect="plain">
            {{ statusLabel(key) }} {{ count }}
          </el-tag>
        </div>
      </div>
      <div class="distribution-row">
        <span class="distribution-label">车道分布</span>
        <div class="distribution-tags">
          <el-tag v-for="(count, key) in overview.laneDistribution" :key="key" size="small"
                  :type="laneTag(key)" effect="plain">
            {{ laneLabel(key) }} {{ count }}
          </el-tag>
        </div>
      </div>
      <div v-if="lastRefreshAt" class="refresh-hint">统计生成于 {{ formatTime(overview.generatedAt) }}</div>
    </div>

    <div class="card">
      <div class="filter-bar">
        <el-select v-model="query.status" placeholder="状态" size="small" clearable style="width: 130px">
          <el-option v-for="o in STATUS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select v-model="query.lane" placeholder="车道" size="small" clearable style="width: 130px">
          <el-option v-for="o in LANE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-input v-model="query.jobType" placeholder="作业类型" size="small" clearable style="width: 150px" />
        <el-input v-model="query.tenantId" placeholder="租户" size="small" clearable style="width: 130px" />
        <el-input v-model="query.bizKey" placeholder="业务键（包含）" size="small" clearable style="width: 160px" />
        <el-date-picker v-model="timeRange" type="datetimerange" size="small"
                        start-placeholder="计划开始时间" end-placeholder="计划结束时间"
                        value-format="YYYY-MM-DDTHH:mm:ss" style="width: 340px" />
        <el-button size="small" type="primary" @click="load(1)">查询</el-button>
        <el-button size="small" @click="resetFilters">重置</el-button>
      </div>

      <el-alert v-if="loadError" class="api-alert" type="warning" show-icon :closable="false"
                :title="'调度接口暂不可用：' + loadError" />

      <div class="list-meta">
        <span>共 {{ total }} 个作业</span>
        <span v-if="lastRefreshAt">最近刷新：{{ formatTime(lastRefreshAt) }}</span>
      </div>

      <el-table :data="rows" v-loading="loading" stripe>
        <el-table-column label="执行标识" min-width="190">
          <template #default="{ row }">
            <el-tooltip :content="row.executionId" placement="top">
              <span class="mono">{{ shortId(row.executionId) }}</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="作业类型" width="150">
          <template #default="{ row }">
            <el-tag size="small" type="info" effect="plain">{{ jobTypeLabel(row.jobType) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="车道" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="laneTag(row.lane)">{{ laneLabel(row.lane) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="105">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="尝试" width="90">
          <template #default="{ row }">{{ row.attempt ?? 0 }} / {{ row.maxAttempts ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="计划时间" width="165">
          <template #default="{ row }">{{ formatTime(row.scheduledAt) }}</template>
        </el-table-column>
        <el-table-column label="开始时间" width="165">
          <template #default="{ row }">{{ formatTime(row.startTime) }}</template>
        </el-table-column>
        <el-table-column label="结束时间" width="165">
          <template #default="{ row }">{{ formatTime(row.finishTime) }}</template>
        </el-table-column>
        <el-table-column label="错误码" width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ 'error-code': row.errorCode }">{{ row.errorCode || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="租户" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.tenantId || '—' }}</template>
        </el-table-column>
        <el-table-column label="发起人" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.ownerUserId || '系统' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="165" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-tooltip :disabled="cancellable(row)" :content="'当前状态不可取消：' + statusLabel(row.status)"
                        placement="top">
              <span>
                <el-button link type="danger" :disabled="!cancellable(row)"
                           :loading="cancellingId === row.executionId"
                           @click="cancelJob(row)">取消</el-button>
              </span>
            </el-tooltip>
            <el-tooltip :disabled="reclaimable(row)" :content="'仅已领取/运行中可回收，当前：' + statusLabel(row.status)"
                        placement="top">
              <span>
                <el-button link type="warning" :disabled="!reclaimable(row)" @click="reclaimJob(row)">回收</el-button>
              </span>
            </el-tooltip>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty :description="loadError ? '调度接口暂不可用' : '暂无作业'" :image-size="80" />
        </template>
      </el-table>

      <el-pagination class="pager" v-model:current-page="page" v-model:page-size="size"
                     :total="total" :page-sizes="[10, 20, 50]"
                     layout="total, sizes, prev, pager, next"
                     @size-change="load(1)" @current-change="load" />
    </div>

    <el-drawer v-model="detailVisible" title="作业详情" size="620px" @closed="detail = null">
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="detail">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="执行标识" :span="2">
              <span class="mono">{{ detail.executionId }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="作业类型">{{ jobTypeLabel(detail.jobType) }}</el-descriptions-item>
            <el-descriptions-item label="车道">{{ laneLabel(detail.lane) }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag size="small" :type="statusTag(detail.status)">{{ statusLabel(detail.status) }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="状态版本号">{{ detail.version ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="租户">{{ detail.tenantId || '—' }}</el-descriptions-item>
            <el-descriptions-item label="发起人">{{ detail.ownerUserId || '系统' }}</el-descriptions-item>
            <el-descriptions-item label="业务键" :span="2">{{ detail.bizKey || '—' }}</el-descriptions-item>
            <el-descriptions-item label="尝试次数">
              {{ detail.attempt ?? 0 }} / {{ detail.maxAttempts ?? '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="下次重试时间">{{ formatTime(detail.nextRetryTime) }}</el-descriptions-item>
            <el-descriptions-item label="计划时间">{{ formatTime(detail.scheduledAt) }}</el-descriptions-item>
            <el-descriptions-item label="单次超时">{{ detail.timeoutSeconds ? detail.timeoutSeconds + ' 秒' : '车道默认' }}</el-descriptions-item>
            <el-descriptions-item label="开始时间">{{ formatTime(detail.startTime) }}</el-descriptions-item>
            <el-descriptions-item label="结束时间">{{ formatTime(detail.finishTime) }}</el-descriptions-item>
          </el-descriptions>

          <h4 class="section-title">租约与心跳</h4>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="租约持有者">{{ detail.leaseOwner || '—' }}</el-descriptions-item>
            <el-descriptions-item label="租约过期时间">{{ formatTime(detail.leaseExpireTime) }}</el-descriptions-item>
            <el-descriptions-item label="心跳时间">{{ formatTime(detail.heartbeatTime) }}</el-descriptions-item>
            <el-descriptions-item label="取消标记">
              <el-tag size="small" :type="detail.cancelRequested ? 'warning' : 'info'">
                {{ detail.cancelRequested ? '已请求取消' : '未请求' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="取消原因" :span="2">{{ detail.cancelReason || '—' }}</el-descriptions-item>
          </el-descriptions>

          <h4 class="section-title">错误信息</h4>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="错误码">{{ detail.errorCode || '—' }}</el-descriptions-item>
            <el-descriptions-item label="错误详情">
              <span class="pre-wrap">{{ detail.errorMessage || '—' }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <h4 class="section-title">负载 JSON</h4>
          <pre class="json-block">{{ formatJson(detail.payload) }}</pre>

          <h4 class="section-title">结果 JSON</h4>
          <pre class="json-block">{{ formatJson(detail.result) }}</pre>

          <div v-if="cancellable(detail)" class="detail-actions">
            <el-button type="danger" plain :loading="cancellingId === detail.executionId" @click="cancelJob(detail)">
              取消作业
            </el-button>
          </div>
        </template>
        <el-empty v-else-if="!detailLoading" description="未查询到作业详情" :image-size="80" />
      </div>
    </el-drawer>
      </el-tab-pane>

      <el-tab-pane label="租户策略" name="policies">
        <div class="card">
          <div class="filter-bar">
            <el-input v-model="policyTenant" placeholder="租户标识" size="small" clearable style="width: 200px" />
            <el-button size="small" type="primary" :loading="policyLoading" @click="loadPolicies">加载策略</el-button>
            <el-button size="small" :loading="policySaving" @click="savePolicies">保存</el-button>
            <el-button size="small" :loading="policyDryRunning" @click="dryRunPolicies">试算</el-button>
            <span class="policy-hint">未配置的车道按默认策略展示（来源=默认），保存后写入策略表</span>
          </div>
          <el-table :data="policies" v-loading="policyLoading" stripe size="small">
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
              <template #default="{ row }"><el-input-number v-model="row.maxConcurrent" :min="1" :max="1000" size="small" /></template>
            </el-table-column>
            <el-table-column label="排队上限" width="120">
              <template #default="{ row }"><el-input-number v-model="row.maxQueue" :min="0" :max="100000" size="small" /></template>
            </el-table-column>
            <el-table-column label="权重" width="110">
              <template #default="{ row }"><el-input-number v-model="row.weight" :min="1" :max="100" size="small" /></template>
            </el-table-column>
            <el-table-column label="优先级加成" width="120">
              <template #default="{ row }"><el-input-number v-model="row.priorityBoost" :min="0" :max="1000" size="small" /></template>
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
              <template #default="{ row }"><el-input-number v-model="row.queueTimeoutSeconds" :min="0" :max="86400" size="small" /></template>
            </el-table-column>
            <el-table-column label="当前运行/排队" width="130">
              <template #default="{ row }">{{ row.running }} / {{ row.queued }}</template>
            </el-table-column>
            <el-table-column prop="version" label="版本" width="80" />
            <el-table-column label="备注" min-width="160">
              <template #default="{ row }"><el-input v-model="row.remark" size="small" placeholder="可选" /></template>
            </el-table-column>
          </el-table>
          <el-alert v-if="dryRunResults.length" class="api-alert" type="info" show-icon :closable="false"
                    title="策略试算结果（不落库）">
            <div v-for="item in dryRunResults" :key="item.lane" class="dry-run-row">
              {{ item.lane }} → {{ item.decision }}：{{ item.reason }}
            </div>
          </el-alert>
        </div>
      </el-tab-pane>

      <el-tab-pane label="失败与死信" name="failures">
        <div class="card">
          <div class="filter-bar">
            <el-input v-model="failureQuery.jobType" placeholder="作业类型" size="small" clearable style="width: 160px" />
            <el-input v-model="failureQuery.tenantId" placeholder="租户" size="small" clearable style="width: 140px" />
            <el-button size="small" type="primary" :loading="failureLoading" @click="loadFailures(1)">查询</el-button>
          </div>
          <el-table :data="failureRows" v-loading="failureLoading" stripe size="small">
            <el-table-column label="执行标识" min-width="180">
              <template #default="{ row }"><span class="mono">{{ shortId(row.executionId) }}</span></template>
            </el-table-column>
            <el-table-column label="作业类型" width="150">
              <template #default="{ row }">{{ jobTypeLabel(row.jobType) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="statusTag(row.status)">{{ statusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="尝试" width="90">
              <template #default="{ row }">{{ row.attempt ?? 0 }} / {{ row.maxAttempts ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="错误码" width="180" show-overflow-tooltip>
              <template #default="{ row }"><span class="error-code">{{ row.errorCode || '—' }}</span></template>
            </el-table-column>
            <el-table-column label="租户" width="110">
              <template #default="{ row }">{{ row.tenantId || '—' }}</template>
            </el-table-column>
            <el-table-column label="结束时间" width="165">
              <template #default="{ row }">{{ formatTime(row.finishTime) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openDetail(row)">详情</el-button>
                <el-button link type="success" @click="retryJob(row)">重试</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination class="pager" v-model:current-page="failurePage" v-model:page-size="failureSize"
                         :total="failureTotal" :page-sizes="[10, 20, 50]"
                         layout="total, sizes, prev, pager, next"
                         @size-change="loadFailures(1)" @current-change="loadFailures" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="实例与对账" name="runtime">
        <div class="card runtime-card">
          <div class="filter-bar">
            <el-button size="small" type="primary" :loading="runtimeLoading" @click="loadRuntime">刷新运行时</el-button>
            <span class="policy-hint">槽位漂移 = 运行槽与作业表实际在跑数之差的绝对值之和</span>
          </div>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="槽位漂移">{{ runtime.slotDrift ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="策略缓存条数">{{ runtime.cachedPolicyCount ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="本实例车道">
              <span v-for="lane in runtime.lanes || []" :key="lane.lane" class="lane-chip">
                {{ laneLabel(lane.lane) }} {{ lane.inFlight }}/{{ lane.maxConcurrent }}
              </span>
            </el-descriptions-item>
          </el-descriptions>
          <h4 class="section-title">实例</h4>
          <el-table :data="instances" v-loading="runtimeLoading" stripe size="small">
            <el-table-column prop="instanceId" label="实例" min-width="220" show-overflow-tooltip />
            <el-table-column label="存活" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.alive ? 'success' : 'info'">{{ row.alive ? '存活' : '失联' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="最后心跳" width="170">
              <template #default="{ row }">{{ formatTime(row.lastHeartbeat) }}</template>
            </el-table-column>
            <el-table-column label="启动时间" width="170">
              <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
            </el-table-column>
          </el-table>
          <h4 class="section-title">最近一次对账</h4>
          <pre class="json-block">{{ formatJson(reconcileReport) }}</pre>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import {
  schedulerCancelJob, schedulerJobDetail, schedulerJobs, schedulerOverview,
  schedulerFailures, schedulerRetryJob, schedulerReclaimJob,
  schedulerPolicies, schedulerUpdatePolicies, schedulerDryRunPolicies,
  schedulerRuntime, schedulerInstances, schedulerReconcile
} from '../../api'

const STATUS_OPTIONS = [
  { value: 'PENDING', label: '待执行' },
  { value: 'LEASED', label: '已领取' },
  { value: 'RUNNING', label: '运行中' },
  { value: 'SUCCEEDED', label: '成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'DEAD', label: '死信' },
  { value: 'CANCELLED', label: '已取消' }
]

const LANE_OPTIONS = [
  { value: 'BACKGROUND', label: '后台作业' },
  { value: 'MAINTENANCE', label: '维护作业' }
]

const REFRESH_OPTIONS = [
  { value: 5, label: '5 秒' },
  { value: 10, label: '10 秒' },
  { value: 30, label: '30 秒' }
]

const STATUS_LABELS = Object.fromEntries(STATUS_OPTIONS.map(o => [o.value, o.label]))
const LANE_LABELS = Object.fromEntries(LANE_OPTIONS.map(o => [o.value, o.label]))

const JOB_TYPE_LABELS = {
  WF_RUN: '工作流运行',
  QUOTA_REFUND: '配额退款',
  EVAL_RUN: '评测运行',
  AGENT_PLAN: '单 Agent 计划',
  CONNECTOR_SYNC: '连接器同步',
  UPLOAD_CLEANUP: '上传清理',
  SEMCACHE_CLEANUP: '语义缓存清理',
  KB_REBUILD: '知识库重建',
  VECTOR_BACKFILL: '向量回填'
}

const CANCELLABLE_STATUS = ['PENDING', 'LEASED', 'RUNNING']

const RECLAIMABLE_STATUS = ['LEASED', 'RUNNING']

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const loadError = ref('')
const lastRefreshAt = ref(null)
const timeRange = ref(null)
const cancellingId = ref('')

const overview = reactive({
  generatedAt: null,
  total: 0,
  statusDistribution: {},
  laneDistribution: {},
  pendingCount: 0,
  runningCount: 0,
  failedCount: 0,
  deadCount: 0,
  timeoutOrLeaseExpiredCount: 0
})

const query = reactive({ status: '', lane: '', jobType: '', tenantId: '', bizKey: '' })

const autoRefresh = ref(false)
const refreshSeconds = ref(10)
let timer = null

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

const activeTab = ref('jobs')
const policyTenant = ref('default')
const policies = ref([])
const policyLoading = ref(false)
const policySaving = ref(false)
const policyDryRunning = ref(false)
const dryRunResults = ref([])

const failureQuery = reactive({ jobType: '', tenantId: '' })
const failureRows = ref([])
const failureTotal = ref(0)
const failurePage = ref(1)
const failureSize = ref(20)
const failureLoading = ref(false)

const runtimeLoading = ref(false)
const runtime = ref({})
const instances = ref([])
const reconcileReport = ref(null)

const loadPolicies = async () => {
  if (!policyTenant.value) { ElMessage.warning('请输入租户标识'); return }
  policyLoading.value = true
  dryRunResults.value = []
  try {
    const res = await schedulerPolicies(policyTenant.value.trim())
    policies.value = res?.data || []
  } catch (e) {
    policies.value = []
    ElMessage.error(e?.response?.data?.message || e?.message || '策略加载失败')
  } finally {
    policyLoading.value = false
  }
}

const policyPayload = () => policies.value.map(p => ({
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

const savePolicies = async () => {
  if (!policyTenant.value || !policies.value.length) { ElMessage.warning('请先加载策略'); return }
  policySaving.value = true
  try {
    const res = await schedulerUpdatePolicies(policyTenant.value.trim(), policyPayload())
    policies.value = res?.data || []
    ElMessage.success('策略已保存并热更新')
  } catch (e) {
    if (!e?.response?.data?.message && e?.message) ElMessage.error(e.message)
  } finally {
    policySaving.value = false
  }
}

const dryRunPolicies = async () => {
  if (!policyTenant.value || !policies.value.length) { ElMessage.warning('请先加载策略'); return }
  policyDryRunning.value = true
  try {
    const res = await schedulerDryRunPolicies(policyTenant.value.trim(), policyPayload())
    dryRunResults.value = res?.data || []
  } catch (e) {
    dryRunResults.value = []
    ElMessage.error(e?.response?.data?.message || e?.message || '试算失败')
  } finally {
    policyDryRunning.value = false
  }
}

const loadFailures = async (p = failurePage.value) => {
  failurePage.value = p
  failureLoading.value = true
  try {
    const params = { page: p, size: failureSize.value }
    if (failureQuery.jobType) params.jobType = failureQuery.jobType.trim()
    if (failureQuery.tenantId) params.tenantId = failureQuery.tenantId.trim()
    const res = await schedulerFailures(params)
    const data = res?.data || {}
    failureRows.value = data.records || []
    failureTotal.value = data.total || 0
  } catch (e) {
    failureRows.value = []
    failureTotal.value = 0
    ElMessage.error(e?.response?.data?.message || e?.message || '失败列表加载失败')
  } finally {
    failureLoading.value = false
  }
}

const retryJob = async (row) => {
  try {
    await ElMessageBox.confirm(`重试作业「${jobTypeLabel(row.jobType)}」？`, '确认重试', { type: 'warning' })
  } catch { return }
  try {
    await schedulerRetryJob(row.executionId)
    ElMessage.success('已重新入队')
    await loadFailures()
  } catch (e) {
    if (!e?.response?.data?.message && e?.message) ElMessage.error(e.message)
  }
}

const reclaimJob = async (row) => {
  let reason = '管理端强制回收'
  try {
    const result = await ElMessageBox.prompt('输入回收原因（可选）', '强制回收', {
      inputValue: reason, confirmButtonText: '确认回收', cancelButtonText: '返回', type: 'warning'
    })
    reason = (result?.value || '').trim() || reason
  } catch { return }
  try {
    await schedulerReclaimJob(row.executionId, reason)
    ElMessage.success('已回收并重新入队')
    await loadFailures()
  } catch (e) {
    if (!e?.response?.data?.message && e?.message) ElMessage.error(e.message)
  }
}

const loadRuntime = async () => {
  runtimeLoading.value = true
  try {
    const [runtimeRes, instanceRes, reconcileRes] = await Promise.all([
      schedulerRuntime(), schedulerInstances(), schedulerReconcile()
    ])
    runtime.value = runtimeRes?.data || {}
    instances.value = instanceRes?.data || []
    reconcileReport.value = reconcileRes?.data || null
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '运行时加载失败')
  } finally {
    runtimeLoading.value = false
  }
}

watch(activeTab, (tab) => {
  if (tab === 'policies' && !policies.value.length) loadPolicies()
  if (tab === 'failures' && !failureRows.value.length) loadFailures(1)
  if (tab === 'runtime') loadRuntime()
})

const statusLabel = (value) => STATUS_LABELS[value] || value || '未知'
const laneLabel = (value) => LANE_LABELS[value] || value || '—'
const jobTypeLabel = (type) => JOB_TYPE_LABELS[type] || type || '—'

const statusTag = (status) => ({
  PENDING: 'warning',
  LEASED: 'warning',
  RUNNING: 'primary',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  DEAD: 'danger',
  CANCELLED: 'info'
}[status] || 'info')

const laneTag = (lane) => ({ BACKGROUND: 'primary', MAINTENANCE: 'success', INTERACTIVE: 'info' }[lane] || 'info')

const cancellable = (row) => CANCELLABLE_STATUS.includes(row?.status)

const reclaimable = (row) => RECLAIMABLE_STATUS.includes(row?.status)

/** 概览卡片下钻：按状态过滤并回到第一页。 */
const filterStatus = (status) => {
  query.status = status
  load(1)
}
const shortId = (id) => (id && id.length > 14 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id || '—')

const formatTime = (value) => {
  if (!value) return '—'
  if (value instanceof Date) {
    const pad = (n) => String(n).padStart(2, '0')
    return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())} ${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`
  }
  return String(value).replace('T', ' ').slice(0, 19)
}

const formatJson = (value) => {
  if (value == null) return '—'
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

const loadOverview = async (silent = false) => {
  try {
    const res = await schedulerOverview()
    Object.assign(overview, res?.data || {})
  } catch (e) {
    if (!silent) loadError.value = e?.response?.data?.message || e?.message || '概览加载失败'
  }
}

const load = async (p = page.value, silent = false) => {
  if (loading.value) return
  page.value = p
  loading.value = true
  if (!silent) loadError.value = ''
  try {
    const params = { page: p, size: size.value }
    if (query.status) params.status = query.status
    if (query.lane) params.lane = query.lane
    if (query.jobType) params.jobType = query.jobType.trim()
    if (query.tenantId) params.tenantId = query.tenantId.trim()
    if (query.bizKey) params.bizKey = query.bizKey.trim()
    if (timeRange.value?.length === 2) {
      params.from = timeRange.value[0]
      params.to = timeRange.value[1]
    }
    const [res] = await Promise.all([schedulerJobs(params), loadOverview(silent)])
    const data = res?.data || {}
    rows.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    rows.value = []
    total.value = 0
    loadError.value = e?.response?.data?.message || e?.message || '请求失败'
  } finally {
    loading.value = false
    lastRefreshAt.value = new Date()
  }
}

const resetFilters = () => {
  query.status = ''
  query.lane = ''
  query.jobType = ''
  query.tenantId = ''
  query.bizKey = ''
  timeRange.value = null
  load(1)
}

const openDetail = async (row) => {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const res = await schedulerJobDetail(row.executionId)
    detail.value = res?.data || null
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

const cancelJob = async (row) => {
  if (!cancellable(row)) {
    ElMessage.warning(`当前状态不可取消：${statusLabel(row.status)}`)
    return
  }
  let reason = '管理端手动取消'
  try {
    const result = await ElMessageBox.prompt(
      `将取消作业「${jobTypeLabel(row.jobType)}」（${shortId(row.executionId)}），确认继续？`,
      '取消作业确认',
      {
        confirmButtonText: '确认取消',
        cancelButtonText: '返回',
        inputPlaceholder: '取消原因（可选）',
        inputValue: reason,
        type: 'warning'
      }
    )
    reason = (result?.value || '').trim() || reason
  } catch {
    return
  }

  cancellingId.value = row.executionId
  try {
    const res = await schedulerCancelJob(row.executionId, reason)
    ElMessage.success(res?.data?.message || '已提交取消请求')
    if (detailVisible.value && detail.value?.executionId === row.executionId) {
      openDetail(row)
    }
    await load(page.value, true)
  } catch (e) {
    // 终态/未启用等业务错误由统一拦截器展示 message，这里只兜底无 message 的情况
    if (!e?.response?.data?.message && e?.message) {
      ElMessage.error(e.message)
    }
  } finally {
    cancellingId.value = ''
  }
}

const stopTimer = () => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

const startTimer = () => {
  stopTimer()
  if (!autoRefresh.value) return
  timer = setInterval(() => {
    if (document.hidden) return
    load(page.value, true)
  }, refreshSeconds.value * 1000)
}

watch(autoRefresh, (enabled) => {
  if (enabled) {
    startTimer()
  } else {
    stopTimer()
  }
})

watch(refreshSeconds, () => {
  if (autoRefresh.value) startTimer()
})

onMounted(() => load(1))
onBeforeUnmount(stopTimer)
</script>

<style scoped lang="scss">
.header-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}
.stat-card.clickable {
  cursor: pointer;
}
.distribution-card {
  margin-bottom: 20px;
}
.distribution-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  & + .distribution-row { margin-top: 8px; }
}
.distribution-label {
  flex: 0 0 70px;
  color: #909399;
  font-size: 13px;
  line-height: 24px;
}
.distribution-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.refresh-hint {
  margin-top: 10px;
  font-size: 12px;
  color: #c0c4cc;
}
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
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
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}
.error-code {
  color: #f56c6c;
}
.value.primary { color: #409eff; }
.value.danger { color: #f56c6c; }
.value.warning { color: #e6a23c; }
.detail-body {
  min-height: 200px;
}
.section-title {
  margin: 20px 0 8px;
  font-size: 14px;
  font-weight: 600;
}
.pre-wrap {
  white-space: pre-wrap;
  word-break: break-word;
}
.json-block {
  margin: 0;
  max-height: 280px;
  overflow: auto;
  padding: 12px;
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
.detail-actions {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}
.scheduler-tabs {
  margin-top: 4px;
}
.policy-hint {
  font-size: 12px;
  color: #909399;
}
.dry-run-row {
  font-size: 12px;
  line-height: 20px;
}
.lane-chip {
  margin-right: 12px;
  font-size: 12px;
}
</style>
