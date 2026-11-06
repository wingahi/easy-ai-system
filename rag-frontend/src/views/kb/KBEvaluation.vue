<template>
  <div class="page-container">
    <div class="page-header">
      <div style="display:flex;gap:12px;align-items:center">
        <el-button :icon="ArrowLeft" circle @click="$router.push(`/kb/${kbId}/files`)" />
        <h2>{{ kb?.name }} - 检索评测</h2>
      </div>
      <el-button type="primary" :icon="Plus" @click="openAdd">新增标注</el-button>
    </div>

    <div class="card">
      <div class="eval-toolbar">
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
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Plus } from '@element-plus/icons-vue'
import {
  kbGet, kbGroundTruthList, kbGroundTruthCreate, kbGroundTruthDelete, kbListChunks, kbEvaluate
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

const percent = (v) => v == null ? '—' : (Number(v) * 100).toFixed(2) + '%'
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

const openAdd = async () => {
  form.value = { queryText: '', relevantChunks: [] }
  dialogVisible.value = true
  if (!chunks.value.length) {
    try {
      const res = await kbListChunks(kbId, { page: 1, size: 200 })
      chunks.value = (res.data && res.data.chunks) || []
    } catch (e) { chunks.value = [] }
  }
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

onMounted(() => {
  loadKb()
  loadGroundTruths()
})
</script>

<style scoped>
.eval-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}
.eval-label {
  font-size: 13px;
  color: #606266;
}
.eval-empty {
  text-align: center;
  color: #909399;
  font-size: 13px;
  padding: 24px 0;
}
</style>
