<template>
  <div class="page-container">
    <div class="page-header">
      <div style="display:flex;gap:12px;align-items:center">
        <el-button :icon="ArrowLeft" circle @click="$router.push(`/kb/${kbId}/files`)" />
        <h2>{{ kb?.name }} - 检索评测</h2>
      </div>
      <el-button type="primary" :icon="Plus" @click="openAdd">新增标注</el-button>
    </div>

    <!-- 版本化评测数据集 -->
    <div class="card">
      <h3 style="margin:0 0 12px">评测数据集（版本化）</h3>
      <div class="eval-toolbar">
        <span class="eval-label">数据集</span>
        <el-select v-model="selectedDatasetId" size="small" style="width:200px"
                   placeholder="选择数据集" @change="onDatasetChange">
          <el-option v-for="d in datasets" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <el-button size="small" @click="openDatasetDialog">新建数据集</el-button>
        <el-popconfirm v-if="selectedDatasetId" title="确认删除该数据集？用例与历史结果保留归档" @confirm="removeDataset">
          <template #reference><el-button size="small" link type="danger">删除数据集</el-button></template>
        </el-popconfirm>

        <span class="eval-label">版本</span>
        <el-select v-model="selectedVersion" size="small" style="width:100px" :disabled="!selectedDatasetId"
                   @change="loadCases">
          <el-option v-for="v in versions" :key="v" :label="'v' + v" :value="v" />
        </el-select>
        <span class="eval-label">TopK</span>
        <el-input-number v-model="dsTopK" :min="1" :max="20" size="small" />
        <span class="eval-label">混合检索</span>
        <el-switch v-model="dsHybrid" />
        <el-button size="small" type="primary" :disabled="!selectedDatasetId" @click="openCaseDialog()">新增用例</el-button>
        <el-button size="small" type="primary" :loading="dsEvalLoading" :disabled="!selectedDatasetId"
                   @click="runDatasetEvaluate">运行该版本评测</el-button>
      </div>

      <el-table :data="cases" v-loading="casesLoading" stripe size="small">
        <el-table-column prop="queryText" label="评测查询" min-width="220" />
        <el-table-column label="相关 Chunk" min-width="220">
          <template #default="{ row }">
            <el-tag v-for="k in parseKeys(row.relevantChunks)" :key="k" size="small" style="margin:2px 4px 2px 0">
              {{ k }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button link type="primary" @click="openCaseDialog(row)">编辑</el-button>
            <el-popconfirm title="确认删除该用例？将生成新版本" @confirm="removeCase(row)">
              <template #reference><el-button link type="danger">删除</el-button></template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!selectedDatasetId" class="eval-empty">
        暂无评测数据集，点击「新建数据集」开始；用例每次变更会自动生成新版本
      </div>
      <div v-else-if="!casesLoading && cases.length === 0" class="eval-empty">
        当前版本暂无用例，点击「新增用例」添加
      </div>
    </div>

    <div v-if="datasetResult" class="card">
      <h3 style="margin:0 0 12px">版本 v{{ datasetResult.datasetVersion }} 评测指标</h3>
      <el-table :data="dsMetricRows" border size="small" style="max-width:640px">
        <el-table-column prop="name" label="指标" width="180" />
        <el-table-column prop="value" label="数值" />
      </el-table>
      <h3 style="margin:20px 0 12px">逐查询明细</h3>
      <el-table :data="datasetResult.details || []" border size="small">
        <el-table-column prop="query" label="查询" min-width="220" />
        <el-table-column prop="relevantCount" label="相关数" width="90" />
        <el-table-column prop="retrievedCount" label="召回数" width="90" />
        <el-table-column prop="hitCount" label="命中数" width="90" />
        <el-table-column label="Recall" width="100">
          <template #default="{ row }">{{ percent(row.recall) }}</template>
        </el-table-column>
        <el-table-column label="MRR" width="100">
          <template #default="{ row }">{{ percent(row.mrr) }}</template>
        </el-table-column>
        <el-table-column label="NDCG" width="100">
          <template #default="{ row }">{{ percent(row.ndcg) }}</template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="selectedDatasetId" class="card">
      <div class="eval-toolbar">
        <h3 style="margin:0">历史结果（按版本归档）</h3>
        <el-button size="small" :disabled="historySelection.length !== 2" @click="compareHistory">对比选中两项</el-button>
        <span class="eval-hint">勾选任意两条记录，按版本从旧到新对比</span>
      </div>
      <el-table :data="resultHistory" size="small" border @selection-change="onHistorySelect">
        <el-table-column type="selection" width="46" />
        <el-table-column prop="datasetVersion" label="版本" width="80">
          <template #default="{ row }">v{{ row.datasetVersion }}</template>
        </el-table-column>
        <el-table-column prop="queryCount" label="查询数" width="90" />
        <el-table-column prop="topK" label="TopK" width="80" />
        <el-table-column label="Recall@K" width="100">
          <template #default="{ row }">{{ percent(row.recallAtK) }}</template>
        </el-table-column>
        <el-table-column label="Precision@K" width="110">
          <template #default="{ row }">{{ percent(row.precisionAtK) }}</template>
        </el-table-column>
        <el-table-column label="MRR" width="90">
          <template #default="{ row }">{{ percent(row.mrr) }}</template>
        </el-table-column>
        <el-table-column label="NDCG" width="90">
          <template #default="{ row }">{{ percent(row.ndcg) }}</template>
        </el-table-column>
        <el-table-column label="命中率" width="90">
          <template #default="{ row }">{{ percent(row.queryHitRate) }}</template>
        </el-table-column>
        <el-table-column label="评测时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
      </el-table>
    </div>

    <div class="card">
      <div class="eval-toolbar">
        <h3 style="margin:0">临时标注（未版本化）</h3>
        <span class="eval-label">TopK</span>
        <el-input-number v-model="topK" :min="1" :max="20" size="small" />
        <span class="eval-label">混合检索</span>
        <el-switch v-model="hybrid" />
        <el-button type="primary" :loading="evalLoading" @click="runEvaluate">运行评测</el-button>
      </div>

      <el-table :data="groundTruths" v-loading="gtLoading" stripe size="small">
        <el-table-column prop="queryText" label="评测查询" min-width="220" />
        <el-table-column label="相关 Chunk" min-width="220">
          <template #default="{ row }">
            <el-tag v-for="k in parseKeys(row.relevantChunks)" :key="k" size="small" style="margin:2px 4px 2px 0">
              {{ k }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-popconfirm title="确认删除该标注？" @confirm="removeGroundTruth(row)">
              <template #reference><el-button link type="danger">删除</el-button></template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!gtLoading && groundTruths.length === 0" class="eval-empty">
        暂无标注数据，请先点击「新增标注」为查询指定相关 Chunk
      </div>
    </div>

    <div v-if="result" class="card">
      <h3 style="margin:0 0 12px">评测指标</h3>
      <el-table :data="metricsRows" border size="small" style="max-width:640px">
        <el-table-column prop="name" label="指标" width="180" />
        <el-table-column prop="value" label="数值" />
      </el-table>

      <h3 style="margin:20px 0 12px">逐查询明细</h3>
      <el-table :data="result.details || []" border size="small">
        <el-table-column prop="query" label="查询" min-width="220" />
        <el-table-column prop="relevantCount" label="相关数" width="90" />
        <el-table-column prop="retrievedCount" label="召回数" width="90" />
        <el-table-column prop="hitCount" label="命中数" width="90" />
        <el-table-column label="Recall" width="100">
          <template #default="{ row }">{{ percent(row.recall) }}</template>
        </el-table-column>
        <el-table-column label="Precision" width="100">
          <template #default="{ row }">{{ percent(row.precision) }}</template>
        </el-table-column>
        <el-table-column prop="latencyMs" label="耗时(ms)" width="100" />
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" title="新增评测标注" width="520px">
      <el-form label-width="100px">
        <el-form-item label="评测查询">
          <el-input v-model="form.queryText" placeholder="如：云岚科技专业版年费是多少" />
        </el-form-item>
        <el-form-item label="相关 Chunk">
          <el-select v-model="form.relevantChunks" multiple filterable style="width:100%"
                     placeholder="选择该查询应命中的 Chunk">
            <el-option v-for="c in chunks" :key="chunkKey(c)"
                       :label="`${c.fileName || '文件' + c.fileId} #${c.chunkIndex}`"
                       :value="chunkKey(c)" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveGroundTruth">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="datasetDialogVisible" title="新建评测数据集" width="480px">
      <el-form label-width="90px">
        <el-form-item label="名称">
          <el-input v-model="datasetForm.name" placeholder="如：核心业务回归集" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="datasetForm.description" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="datasetDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="datasetSaving" @click="saveDataset">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="caseDialogVisible" :title="caseForm.id ? '编辑评测用例' : '新增评测用例'" width="520px">
      <el-form label-width="100px">
        <el-form-item label="评测查询">
          <el-input v-model="caseForm.queryText" placeholder="如：云岚科技专业版年费是多少" />
        </el-form-item>
        <el-form-item label="相关 Chunk">
          <el-select v-model="caseForm.relevantChunks" multiple filterable style="width:100%"
                     placeholder="选择该查询应命中的 Chunk">
            <el-option v-for="c in chunks" :key="chunkKey(c)"
                       :label="`${c.fileName || '文件' + c.fileId} #${c.chunkIndex}`"
                       :value="chunkKey(c)" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="caseDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="caseSaving" @click="saveCase">保存（生成新版本）</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="compareVisible" title="版本对比" width="820px">
      <template v-if="compareData">
        <el-table :data="compareRows" border size="small">
          <el-table-column prop="name" label="指标" width="160" />
          <el-table-column label="基线版本" width="150">
            <template #default="{ row }">{{ percent(row.base) }}</template>
          </el-table-column>
          <el-table-column label="对比版本" width="150">
            <template #default="{ row }">{{ percent(row.target) }}</template>
          </el-table-column>
          <el-table-column label="差值">
            <template #default="{ row }">{{ signedPercent(row.delta) }}</template>
          </el-table-column>
        </el-table>

        <h3 style="margin:20px 0 12px">逐查询 Recall 对比</h3>
        <el-table :data="compareData.queryDiffs || []" border size="small" max-height="280">
          <el-table-column prop="query" label="查询" min-width="240" />
          <el-table-column label="基线" width="110">
            <template #default="{ row }">{{ percent(row.baseRecall) }}</template>
          </el-table-column>
          <el-table-column label="对比" width="110">
            <template #default="{ row }">{{ percent(row.targetRecall) }}</template>
          </el-table-column>
          <el-table-column label="差值" width="110">
            <template #default="{ row }">{{ signedPercent(row.delta) }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Plus } from '@element-plus/icons-vue'
import {
  kbGet, kbGroundTruthList, kbGroundTruthCreate, kbGroundTruthDelete, kbListChunks, kbEvaluate,
  kbEvalDatasetList, kbEvalDatasetCreate, kbEvalDatasetDelete, kbEvalDatasetVersions,
  kbEvalCaseList, kbEvalCaseCreate, kbEvalCaseUpdate, kbEvalCaseDelete,
  kbEvalDatasetEvaluate, kbEvalRunStatus, kbEvalResultList, kbEvalResultCompare
} from '../../api'

const route = useRoute()
const kbId = route.params.id

const kb = ref(null)
const groundTruths = ref([])
const gtLoading = ref(false)
const chunks = ref([])
const topK = ref(5)
const hybrid = ref(true)
const evalLoading = ref(false)
const result = ref(null)

const dialogVisible = ref(false)
const saving = ref(false)
const form = ref({ queryText: '', relevantChunks: [] })

// 版本化数据集
const datasets = ref([])
const selectedDatasetId = ref(null)
const versions = ref([])
const selectedVersion = ref(null)
const cases = ref([])
const casesLoading = ref(false)
const dsTopK = ref(5)
const dsHybrid = ref(true)
const dsEvalLoading = ref(false)
const datasetResult = ref(null)
const resultHistory = ref([])
const historySelection = ref([])
const datasetDialogVisible = ref(false)
const datasetSaving = ref(false)
const datasetForm = ref({ name: '', description: '' })
const caseDialogVisible = ref(false)
const caseSaving = ref(false)
const caseForm = ref({ id: null, queryText: '', relevantChunks: [] })
const compareVisible = ref(false)
const compareData = ref(null)

const metricsRows = computed(() => {
  if (!result.value) return []
  const r = result.value
  return [
    { name: '查询数', value: r.queryCount },
    { name: 'TopK', value: r.topK },
    { name: 'Recall@K', value: percent(r.recallAtK) },
    { name: 'Precision@K', value: percent(r.precisionAtK) },
    { name: '查询命中率', value: percent(r.queryHitRate) },
    { name: '覆盖率', value: percent(r.coverageRate) },
    { name: '平均耗时', value: (r.avgLatencyMs || 0) + ' ms' }
  ]
})

const dsMetricRows = computed(() => {
  if (!datasetResult.value) return []
  const r = datasetResult.value
  return [
    { name: '查询数', value: r.queryCount },
    { name: 'TopK', value: r.topK },
    { name: 'Recall@K', value: percent(r.recallAtK) },
    { name: 'Precision@K', value: percent(r.precisionAtK) },
    { name: 'MRR', value: percent(r.mrr) },
    { name: 'NDCG', value: percent(r.ndcg) },
    { name: '查询命中率', value: percent(r.queryHitRate) }
  ]
})

const compareRows = computed(() => {
  if (!compareData.value) return []
  const { base, target, delta } = compareData.value
  return [
    { name: 'Recall@K', base: base.recallAtK, target: target.recallAtK, delta: delta.recallAtK },
    { name: 'Precision@K', base: base.precisionAtK, target: target.precisionAtK, delta: delta.precisionAtK },
    { name: 'MRR', base: base.mrr, target: target.mrr, delta: delta.mrr },
    { name: 'NDCG', base: base.ndcg, target: target.ndcg, delta: delta.ndcg },
    { name: '查询命中率', base: base.queryHitRate, target: target.queryHitRate, delta: delta.queryHitRate }
  ]
})

const percent = (v) => v == null ? '—' : (Number(v) * 100).toFixed(2) + '%'
const signedPercent = (v) => {
  if (v == null) return '—'
  const n = Number(v) * 100
  return (n > 0 ? '+' : '') + n.toFixed(2) + ' pp'
}
const chunkKey = (c) => `${c.fileId}:${c.chunkIndex}`
const parseKeys = (raw) => {
  if (!raw) return []
  try { const v = JSON.parse(raw); return Array.isArray(v) ? v : [] } catch { return [] }
}
const formatTime = (v) => v ? new Date(v).toLocaleString('zh-CN') : '—'

const loadGroundTruths = async () => {
  gtLoading.value = true
  try {
    const res = await kbGroundTruthList(kbId)
    groundTruths.value = res.data || []
  } catch (e) { groundTruths.value = [] } finally { gtLoading.value = false }
}

const loadKb = async () => {
  try { const res = await kbGet(kbId); kb.value = res.data } catch (e) { /* ignore */ }
}

const ensureChunks = async () => {
  if (chunks.value.length) return
  try {
    const res = await kbListChunks(kbId, { page: 1, size: 200 })
    chunks.value = (res.data && res.data.chunks) || []
  } catch (e) { chunks.value = [] }
}

const openAdd = async () => {
  form.value = { queryText: '', relevantChunks: [] }
  dialogVisible.value = true
  await ensureChunks()
}

const saveGroundTruth = async () => {
  if (!form.value.queryText.trim()) { ElMessage.warning('请输入评测查询'); return }
  if (!form.value.relevantChunks.length) { ElMessage.warning('请选择至少一个相关 Chunk'); return }
  saving.value = true
  try {
    await kbGroundTruthCreate(kbId, {
      queryText: form.value.queryText.trim(),
      relevantChunks: form.value.relevantChunks
    })
    ElMessage.success('标注已保存')
    dialogVisible.value = false
    await loadGroundTruths()
  } catch (e) { /* 统一错误提示已由拦截器处理 */ } finally { saving.value = false }
}

const removeGroundTruth = async (row) => {
  try {
    await kbGroundTruthDelete(kbId, row.id)
    ElMessage.success('标注已删除')
    await loadGroundTruths()
  } catch (e) { /* ignore */ }
}

const runEvaluate = async () => {
  evalLoading.value = true
  try {
    const res = await kbEvaluate(kbId, { topK: topK.value, hybrid: hybrid.value })
    result.value = res.data
    ElMessage.success('评测完成')
  } catch (e) { result.value = null } finally { evalLoading.value = false }
}

// ===== 版本化数据集 =====

const loadDatasets = async (keepSelection = true) => {
  try {
    const res = await kbEvalDatasetList(kbId)
    datasets.value = res.data || []
  } catch (e) { datasets.value = [] }
  if (keepSelection && selectedDatasetId.value
      && !datasets.value.some((d) => d.id === selectedDatasetId.value)) {
    selectedDatasetId.value = null
  }
  if (!selectedDatasetId.value && datasets.value.length) {
    selectedDatasetId.value = datasets.value[0].id
    await onDatasetChange()
  }
}

const loadVersions = async () => {
  if (!selectedDatasetId.value) { versions.value = []; return }
  try {
    const res = await kbEvalDatasetVersions(kbId, selectedDatasetId.value)
    versions.value = res.data || []
  } catch (e) { versions.value = [] }
  if (!versions.value.length || !versions.value.includes(selectedVersion.value)) {
    selectedVersion.value = versions.value.length ? versions.value[versions.value.length - 1] : null
  }
}

const loadCases = async () => {
  if (!selectedDatasetId.value || selectedVersion.value == null) { cases.value = []; return }
  casesLoading.value = true
  try {
    const res = await kbEvalCaseList(kbId, selectedDatasetId.value, { version: selectedVersion.value })
    cases.value = res.data || []
  } catch (e) { cases.value = [] } finally { casesLoading.value = false }
}

const loadHistory = async () => {
  if (!selectedDatasetId.value) { resultHistory.value = []; return }
  try {
    const res = await kbEvalResultList(kbId, selectedDatasetId.value)
    resultHistory.value = res.data || []
  } catch (e) { resultHistory.value = [] }
}

const onDatasetChange = async () => {
  datasetResult.value = null
  selectedVersion.value = null
  await loadVersions()
  await Promise.all([loadCases(), loadHistory()])
}

const refreshAfterMutation = async () => {
  await loadDatasets()
  await loadVersions()
  selectedVersion.value = versions.value.length ? versions.value[versions.value.length - 1] : null
  await Promise.all([loadCases(), loadHistory()])
}

const openDatasetDialog = () => {
  datasetForm.value = { name: '', description: '' }
  datasetDialogVisible.value = true
}

const saveDataset = async () => {
  if (!datasetForm.value.name.trim()) { ElMessage.warning('请输入数据集名称'); return }
  datasetSaving.value = true
  try {
    const res = await kbEvalDatasetCreate(kbId, {
      name: datasetForm.value.name.trim(),
      description: datasetForm.value.description || null
    })
    datasetDialogVisible.value = false
    selectedDatasetId.value = res.data && res.data.id
    await loadDatasets()
    await onDatasetChange()
    ElMessage.success('数据集已创建')
  } catch (e) { /* ignore */ } finally { datasetSaving.value = false }
}

const removeDataset = async () => {
  try {
    await kbEvalDatasetDelete(kbId, selectedDatasetId.value)
    selectedDatasetId.value = null
    datasetResult.value = null
    cases.value = []
    resultHistory.value = []
    versions.value = []
    await loadDatasets(false)
    ElMessage.success('数据集已删除')
  } catch (e) { /* ignore */ }
}

const openCaseDialog = async (row = null) => {
  await ensureChunks()
  caseForm.value = row
    ? { id: row.id, queryText: row.queryText, relevantChunks: parseKeys(row.relevantChunks) }
    : { id: null, queryText: '', relevantChunks: [] }
  caseDialogVisible.value = true
}

const saveCase = async () => {
  if (!caseForm.value.queryText.trim()) { ElMessage.warning('请输入评测查询'); return }
  if (!caseForm.value.relevantChunks.length) { ElMessage.warning('请选择至少一个相关 Chunk'); return }
  caseSaving.value = true
  try {
    const payload = {
      queryText: caseForm.value.queryText.trim(),
      relevantChunks: caseForm.value.relevantChunks
    }
    if (caseForm.value.id) {
      await kbEvalCaseUpdate(kbId, selectedDatasetId.value, caseForm.value.id, payload)
    } else {
      await kbEvalCaseCreate(kbId, selectedDatasetId.value, payload)
    }
    caseDialogVisible.value = false
    await refreshAfterMutation()
    ElMessage.success('用例已保存，已生成新版本')
  } catch (e) { /* ignore */ } finally { caseSaving.value = false }
}

const removeCase = async (row) => {
  try {
    await kbEvalCaseDelete(kbId, selectedDatasetId.value, row.id)
    await refreshAfterMutation()
    ElMessage.success('用例已删除，已生成新版本')
  } catch (e) { /* ignore */ }
}

const pollEvalRun = async (executionId) => {
  const deadline = Date.now() + 15 * 60 * 1000
  while (Date.now() < deadline) {
    const res = await kbEvalRunStatus(kbId, selectedDatasetId.value, executionId)
    const data = res.data || {}
    if (data.status === 'SUCCEEDED') return data.result || {}
    if (['FAILED', 'DEAD', 'CANCELLED'].includes(data.status)) {
      throw new Error(data.errorMessage || `评测任务${data.status}`)
    }
    await new Promise(resolve => setTimeout(resolve, 2000))
  }
  throw new Error('评测任务超时，请稍后在历史结果中查看')
}

const runDatasetEvaluate = async () => {
  if (selectedVersion.value == null) { ElMessage.warning('请选择版本'); return }
  dsEvalLoading.value = true
  try {
    const res = await kbEvalDatasetEvaluate(kbId, selectedDatasetId.value, {
      version: selectedVersion.value, topK: dsTopK.value, hybrid: dsHybrid.value
    })
    const body = res.data || {}
    // 调度中心启用时返回 202 + executionId，轮询到终态再展示；未启用时保持同步返回
    datasetResult.value = body.executionId ? await pollEvalRun(body.executionId) : body
    await loadHistory()
    ElMessage.success('评测完成，结果已归档')
  } catch (e) {
    datasetResult.value = null
    if (e?.message) ElMessage.error(e.message)
  } finally { dsEvalLoading.value = false }
}

const onHistorySelect = (rows) => {
  historySelection.value = rows
}

const compareHistory = async () => {
  const ordered = [...historySelection.value].sort((a, b) => a.datasetVersion - b.datasetVersion)
  const base = ordered[0]
  const target = ordered[ordered.length - 1]
  try {
    const res = await kbEvalResultCompare(kbId, selectedDatasetId.value, {
      baseId: base.id, targetId: target.id
    })
    compareData.value = res.data
    compareVisible.value = true
  } catch (e) { compareData.value = null }
}

onMounted(() => {
  loadKb()
  loadGroundTruths()
  loadDatasets()
})
</script>

<style scoped>
.eval-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.eval-label {
  font-size: 13px;
  color: #606266;
}
.eval-hint {
  font-size: 12px;
  color: #909399;
}
.eval-empty {
  text-align: center;
  color: #909399;
  font-size: 13px;
  padding: 24px 0;
}
</style>
