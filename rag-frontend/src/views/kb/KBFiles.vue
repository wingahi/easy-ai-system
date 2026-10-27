<template>
  <div class="page-container">
    <div class="page-header">
      <div style="display:flex;gap:12px;align-items:center">
        <el-button :icon="ArrowLeft" circle @click="$router.back()" />
        <h2>{{ kb?.name }} - 文件管理</h2>
      </div>
      <el-upload
        :show-file-list="false"
        :before-upload="beforeUpload"
        :http-request="doUpload"
        accept=".pdf,.docx,.doc,.txt,.md,.csv,.xlsx,.xls"
      >
        <el-button type="primary" :icon="Upload">上传文件</el-button>
      </el-upload>
    </div>

    <div class="card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="fileName" label="文件名" />
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column prop="fileType" label="类型" width="80" />
        <el-table-column prop="splitStrategy" label="分割策略" width="100" />
        <el-table-column prop="chunkCount" label="段落数" width="80" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button link type="primary" @click="preview(row)">预览分割</el-button>
              <el-button link type="primary" @click="resplit(row)">重新分割</el-button>
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
            <el-option label="自定义" value="CUSTOM" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resplitVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmResplit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Upload } from '@element-plus/icons-vue'
import { kbGet, kbListFiles, kbUploadFile, kbDeleteFile, kbResplitFile, kbPreviewSplit } from '../../api'

const route = useRoute()
const kbId = route.params.id
const loading = ref(false)
const kb = ref(null)
const list = ref([])
const previewVisible = ref(false)
const previewData = ref(null)
const resplitVisible = ref(false)
const resplitFile = ref(null)
const newStrategy = ref('AUTO')

const load = async () => {
  loading.value = true
  try {
    const [kbRes, filesRes] = await Promise.all([kbGet(kbId), kbListFiles(kbId)])
    kb.value = kbRes.data
    list.value = filesRes.data
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

const formatSize = (bytes) => {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

const statusType = (s) => ({ PENDING: 'info', PROCESSING: 'warning', SUCCESS: 'success', FAILED: 'danger' }[s] || 'info')
const statusText = (s) => ({ PENDING: '待处理', PROCESSING: '处理中', SUCCESS: '成功', FAILED: '失败' }[s] || s)

onMounted(load)
</script>

<style scoped>
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
