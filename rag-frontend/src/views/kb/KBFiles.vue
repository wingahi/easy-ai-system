<template>
  <div class="page-container">
    <div class="page-header">
      <div style="display:flex;gap:12px;align-items:center">
        <el-button :icon="ArrowLeft" circle @click="$router.back()" />
        <h2>{{ kb?.name }} - 文件管理</h2>
      </div>
      <div style="display:flex;gap:8px;align-items:center">
        <el-button :icon="DataAnalysis" @click="$router.push(`/kb/${kbId}/evaluation`)">检索评测</el-button>
        <el-upload
          :show-file-list="false"
          :before-upload="beforeUpload"
          :http-request="doUpload"
          accept=".pdf,.docx,.doc,.txt,.md,.csv,.xlsx,.xls,.pptx,.html,.htm,.json,.eml"
        >
          <el-button type="primary" :icon="Upload">上传文件</el-button>
        </el-upload>
      </div>
    </div>

    <div class="card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="fileName" label="文件名" min-width="200" />
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column prop="fileType" label="类型" width="80" />
        <el-table-column prop="splitStrategy" label="分割策略" width="100" />
        <el-table-column prop="chunkCount" label="段落数" width="80" />
        <el-table-column label="标签" min-width="160">
          <template #default="{ row }">
            <el-tag
              v-for="t in (row._tags || [])"
              :key="t.id"
              :color="t.color || '#909399'"
              effect="light"
              size="small"
              style="margin-right:4px;margin-bottom:2px"
            >{{ t.name }}</el-tag>
            <span v-if="!row._tags?.length" style="color:#c0c4cc;font-size:12px">—</span>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.currentVersion" type="warning" size="small">v{{ row.currentVersion }}</el-tag>
            <span v-else style="color:#c0c4cc;font-size:12px">v1</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="320">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button link type="primary" @click="preview(row)">预览</el-button>
              <el-button link type="primary" @click="resplit(row)">重切</el-button>
              <el-button link type="primary" @click="openTagDialog(row)">标签</el-button>
              <el-button link type="primary" @click="openVersionDialog(row)">版本</el-button>
              <el-popconfirm title="确认删除？" @confirm="del(row)">
                <template #reference><el-button link type="danger">删除</el-button></template>
              </el-popconfirm>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 预览对话框 -->
    <el-dialog v-model="previewVisible" title="分割预览" width="700px" top="5vh">
      <div v-if="previewData">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="原始段落数">{{ previewData.paragraphCount }}</el-descriptions-item>
          <el-descriptions-item label="分割段落数">{{ previewData.chunkCount }}</el-descriptions-item>
        </el-descriptions>
        <el-divider content-position="left">前100段预览</el-divider>
        <div style="max-height:500px;overflow-y:auto">
          <div v-for="chunk in previewData.chunks" :key="chunk.index" class="chunk-item">
            <div class="chunk-header">
              <el-tag size="small" type="info">#{{ chunk.index }}</el-tag>
              <el-tag v-if="chunk.attributes?.headingPath" size="small" type="success">{{ chunk.attributes.headingPath }}</el-tag>
              <el-tag v-if="chunk.attributes?.semanticBlockIndex !== undefined" size="small" type="warning">块 {{ chunk.attributes.semanticBlockIndex }}</el-tag>
              <span style="color:#909399;font-size:12px">{{ chunk.length }} 字符</span>
            </div>
            <div class="chunk-text">{{ chunk.text }}</div>
          </div>
        </div>
      </div>
    </el-dialog>

    <!-- 重新分割对话框 -->
    <el-dialog v-model="resplitVisible" title="重新分割" width="400px">
      <el-form label-width="100px">
        <el-form-item label="分割策略">
          <el-select v-model="newStrategy" style="width:100%">
            <el-option label="自动路由（推荐）" value="AUTO" />
            <el-option label="递归分割" value="RECURSIVE" />
            <el-option label="问答对分割" value="FAQ" />
            <el-option label="章节分割" value="CHAPTER" />
            <el-option label="段落分割" value="PARAGRAPH" />
            <el-option label="长度分割" value="LENGTH" />
            <el-option label="结构化切分（Markdown）" value="STRUCTURED" />
            <el-option label="语义切分" value="SEMANTIC" />
            <el-option label="自定义" value="CUSTOM" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resplitVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmResplit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 文件标签对话框 -->
    <el-dialog v-model="tagDialogVisible" :title="'文件标签 - ' + (tagDialogFile?.fileName || '')" width="460px">
      <div v-if="allTags.length === 0" style="color:#909399;font-size:13px">暂无标签，请先在知识库管理页面创建</div>
      <el-checkbox-group v-else v-model="tagDialogSelected" style="display:flex;flex-wrap:wrap;gap:6px">
        <el-checkbox v-for="t in allTags" :key="t.id" :value="t.id" :label="t.id">
          <el-tag :color="t.color || '#909399'" effect="light" size="small">{{ t.name }}</el-tag>
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="tagDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveFileTags">保存</el-button>
      </template>
    </el-dialog>

    <!-- 版本历史对话框 -->
    <el-dialog v-model="versionDialogVisible" :title="'版本历史 - ' + (versionDialogFile?.fileName || '')" width="700px">
      <el-table v-loading="versionLoading" :data="versionList" stripe size="small">
        <el-table-column prop="version" label="版本" width="80">
          <template #default="{ row }">
            <el-tag type="warning" size="small">v{{ row.version }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="splitStrategy" label="分割策略" width="120" />
        <el-table-column prop="chunkCount" label="段落数" width="80" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'SUCCESS' ? 'success' : 'warning'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="200">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :disabled="row.version === (versionDialogFile?.currentVersion || 1) || row.status !== 'SUCCESS'"
              @click="doRollback(row)"
            >回滚到此版本</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!versionLoading && versionList.length === 0" style="text-align:center;color:#909399;padding:30px">暂无版本快照</div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Upload, DataAnalysis } from '@element-plus/icons-vue'
import {
  kbGet, kbListFiles, kbUploadFile, kbDeleteFile, kbResplitFile, kbPreviewSplit,
  tagList, kbFileTags, kbFileSetTags, kbFileVersions, kbFileRollback
} from '../../api'

const route = useRoute()
const kbId = route.params.id
const loading = ref(false)
const kb = ref(null)
const list = ref([])
const allTags = ref([])
const previewVisible = ref(false)
const previewData = ref(null)
const resplitVisible = ref(false)
const resplitFile = ref(null)
const newStrategy = ref('AUTO')

const tagDialogVisible = ref(false)
const tagDialogFile = ref(null)
const tagDialogSelected = ref([])

const versionDialogVisible = ref(false)
const versionDialogFile = ref(null)
const versionList = ref([])
const versionLoading = ref(false)

const loadAllTags = async () => {
  try {
    const res = await tagList()
    allTags.value = res.data || []
  } catch (e) { allTags.value = [] }
}

const loadAllFileTags = async () => {
  if (!list.value.length) return
  try {
    const results = await Promise.allSettled(
      list.value.map(f => kbFileTags(f.id))
    )
    results.forEach((r, i) => {
      if (r.status === 'fulfilled') {
        list.value[i]._tags = r.value.data || []
      } else {
        list.value[i]._tags = []
      }
    })
  } catch (e) {
    list.value.forEach(f => { f._tags = [] })
  }
}

const load = async () => {
  loading.value = true
  try {
    const [kbRes, filesRes] = await Promise.all([kbGet(kbId), kbListFiles(kbId)])
    kb.value = kbRes.data
    list.value = filesRes.data || []
    await loadAllFileTags()
  } finally {
    loading.value = false
  }
}

const beforeUpload = () => true

const doUpload = async ({ file }) => {
  try {
    await kbUploadFile(kbId, file, 'AUTO')
    ElMessage.success('上传成功，正在处理...')
    setTimeout(load, 2000)
  } catch (e) {}
}

const del = async (row) => {
  await kbDeleteFile(row.id)
  ElMessage.success('删除成功')
  load()
}

const preview = async (row) => {
  const res = await kbPreviewSplit(row.id)
  previewData.value = res.data
  previewVisible.value = true
}

const resplit = (row) => {
  resplitFile.value = row
  newStrategy.value = row.splitStrategy
  resplitVisible.value = true
}

const confirmResplit = async () => {
  await kbResplitFile(resplitFile.value.id, newStrategy.value)
  ElMessage.success('已触发重新分割')
  resplitVisible.value = false
  setTimeout(load, 2000)
}

const openTagDialog = async (row) => {
  tagDialogFile.value = row
  const res = await kbFileTags(row.id)
  tagDialogSelected.value = (res.data || []).map(t => t.id)
  tagDialogVisible.value = true
}

const saveFileTags = async () => {
  await kbFileSetTags(tagDialogFile.value.id, tagDialogSelected.value)
  ElMessage.success('标签已保存')
  tagDialogVisible.value = false
  await loadAllFileTags()
}

const openVersionDialog = async (row) => {
  versionDialogFile.value = row
  versionDialogVisible.value = true
  versionLoading.value = true
  try {
    const res = await kbFileVersions(row.id)
    versionList.value = res.data || []
  } finally {
    versionLoading.value = false
  }
}

const doRollback = async (versionRow) => {
  try {
    await ElMessageBox.confirm(
      `确认将《${versionDialogFile.value.fileName}》回滚到 v${versionRow.version}？将按该版本的分割策略重新切分。`,
      '版本回滚', { type: 'warning' }
    )
    await kbFileRollback(versionDialogFile.value.id, versionRow.version)
    ElMessage.success('已触发回滚重切')
    versionDialogVisible.value = false
    setTimeout(load, 2000)
  } catch (_) {}
}

const formatSize = (bytes) => {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

const formatTime = (t) => {
  if (!t) return '-'
  return String(t).replace('T', ' ').substring(0, 19)
}

const statusType = (s) => ({ PENDING: 'info', PROCESSING: 'warning', SUCCESS: 'success', FAILED: 'danger' }[s] || 'info')
const statusText = (s) => ({ PENDING: '待处理', PROCESSING: '处理中', SUCCESS: '成功', FAILED: '失败' }[s] || s)

onMounted(() => { loadAllTags(); load() })
</script>

<style scoped>
.strategy-tip {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: #909399;
}
.chunk-item {
  border: 1px solid #ebeef5;
  border-radius: 4px;
  margin-bottom: 8px;
  padding: 8px;
  .chunk-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 4px;
  }
  .chunk-text {
    font-size: 13px;
    line-height: 1.6;
    color: #606266;
    white-space: pre-wrap;
    word-break: break-all;
  }
}
</style>
