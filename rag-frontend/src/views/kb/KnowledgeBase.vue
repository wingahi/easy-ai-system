<template>
  <div class="page-container">
    <div class="page-header">
      <h2>知识库管理</h2>
      <el-button type="primary" :icon="Plus" @click="openDialog()">新建知识库</el-button>
    </div>

    <!-- 统计 -->
    <div class="stat-cards">
      <div class="stat-card"><div class="label">知识库总数</div><div class="value">{{ total }}</div></div>
      <div class="stat-card"><div class="label">文件总数</div><div class="value">{{ fileTotal }}</div></div>
      <div class="stat-card"><div class="label">段落总数</div><div class="value">{{ chunkTotal }}</div></div>
    </div>

    <!-- 搜索 -->
    <div class="card" style="margin-bottom:16px">
      <el-input v-model="keyword" placeholder="搜索知识库名称/描述" clearable style="width:300px">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
    </div>

    <!-- 列表 -->
    <div class="card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="标签" width="200">
          <template #default="{ row }">
            <div class="kb-tags">
              <el-tag v-for="t in row._tags || []" :key="t.id" :color="t.color || ''"
                      :style="t.color ? `background:${t.color}20;color:${t.color};border-color:${t.color}40` : ''"
                      size="small" effect="light" style="margin-right:4px;margin-bottom:2px">
                {{ t.name }}
              </el-tag>
              <span v-if="!row._tags?.length" style="color:#c0c4cc;font-size:12px">-</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="dimension" label="维度" width="80" />
        <el-table-column prop="fileCount" label="文件" width="80" />
        <el-table-column prop="chunkCount" label="段落" width="80" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" />
        <el-table-column label="操作" width="310" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button link type="primary" @click="goFiles(row)">文件</el-button>
              <el-button link type="primary" @click="goSearch(row)">搜索</el-button>
              <el-button link type="warning" @click="openTagDialog(row)">标签</el-button>
              <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
              <el-popconfirm title="确认删除？" @confirm="del(row)">
                <template #reference>
                  <el-button link type="danger">删除</el-button>
                </template>
              </el-popconfirm>
            </div>
          </template>
        </el-table-column>
      </el-table>
      
      <el-pagination style="margin-top:16px;justify-content:flex-end;display:flex"
        v-model:current-page="page" v-model:page-size="size"
        :total="total" :page-sizes="[10,20,50]"
        layout="total, sizes, prev, pager, next" @size-change="load" @current-change="load" />
    </div>

    <!-- 新建/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑知识库' : '新建知识库'" width="500px">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="100px">
        <el-form-item prop="name" label="名称">
          <el-input v-model="form.name" placeholder="请输入知识库名称" />
        </el-form-item>
        <el-form-item prop="embeddingModelId" label="嵌入模型">
          <el-select v-model="form.embeddingModelId" placeholder="请选择嵌入模型" style="width:100%">
            <el-option v-for="m in embeddingList" :key="m.id" :label="`${m.name} (${m.modelName})`" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item prop="description" label="描述">
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 标签管理弹窗 -->
    <el-dialog v-model="tagDialogVisible" :title="`知识库标签 — ${tagKb?.name || ''}`" width="480px">
      <div style="margin-bottom:8px;color:#909399;font-size:12px">勾选要绑定到该知识库的标签（多选）</div>
      <el-checkbox-group v-model="tagSelectedIds" style="display:flex;flex-wrap:wrap;gap:6px">
        <el-checkbox v-for="t in allTags" :key="t.id" :label="t.id" :value="t.id">
          <el-tag :color="t.color || ''"
                  :style="t.color ? `background:${t.color}20;color:${t.color};border-color:${t.color}40` : ''"
                  effect="light" size="small">
            {{ t.name }}
          </el-tag>
        </el-checkbox>
      </el-checkbox-group>
      <div v-if="!allTags.length" style="color:#909399;font-size:13px;padding:16px 0">
        还没有任何标签。请先在数据库里创建标签，或通过 API 添加。
      </div>
      <template #footer>
        <el-button @click="tagDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="tagSubmitting" @click="saveKbTags">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Search } from '@element-plus/icons-vue'
import { kbPage, kbCreate, kbUpdate, kbDelete, embeddingModelList,
         tagList, kbTags, kbSetTags } from '../../api'

const router = useRouter()
const loading = ref(false)
const submitting = ref(false)
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const dialogVisible = ref(false)
const editing = ref(null)
const formRef = ref()
const embeddingList = ref([])

// ===== 标签管理 =====
const allTags = ref([])
const tagDialogVisible = ref(false)
const tagKb = ref(null)
const tagSelectedIds = ref([])
const tagSubmitting = ref(false)

const form = reactive({
  name: '', description: '', embeddingModelId: null, status: 1
})
const rules = {
  name: [{ required: true, message: '请输入名称' }],
  embeddingModelId: [{ required: true, message: '请选择嵌入模型' }]
}

const fileTotal = computed(() => list.value.reduce((s, i) => s + (i.fileCount || 0), 0))
const chunkTotal = computed(() => list.value.reduce((s, i) => s + (i.chunkCount || 0), 0))

const load = async () => {
  loading.value = true
  try {
    const res = await kbPage({ page: page.value, size: size.value, keyword: keyword.value })
    list.value = res.data.records
    total.value = res.data.total
    // 批量拉每个 KB 的标签
    await Promise.all(list.value.map(async (kb) => {
      try {
        const t = await kbTags(kb.id)
        kb._tags = t.data || []
      } catch { kb._tags = [] }
    }))
  } finally {
    loading.value = false
  }
}

const loadEmbedding = async () => {
  const res = await embeddingModelList()
  embeddingList.value = res.data
}

const openDialog = (row) => {
  editing.value = row || null
  Object.assign(form, {
    name: row?.name || '',
    description: row?.description || '',
    embeddingModelId: row?.embeddingModelId || null,
    status: row?.status ?? 1
  })
  dialogVisible.value = true
}

const submit = async () => {
  await formRef.value.validate()
  submitting.value = true
  try {
    if (editing.value) {
      form.id = editing.value.id
      await kbUpdate(form)
      ElMessage.success('更新成功')
    } else {
      await kbCreate(form)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

const del = async (row) => {
  await kbDelete(row.id)
  ElMessage.success('删除成功')
  load()
}

const goFiles = (row) => router.push(`/kb/${row.id}/files`)
const goSearch = (row) => router.push(`/kb/${row.id}/search`)

// ===== 标签管理方法 =====
const loadAllTags = async () => {
  try {
    const res = await tagList()
    allTags.value = res.data || []
  } catch { allTags.value = [] }
}
const openTagDialog = async (row) => {
  tagKb.value = row
  tagSelectedIds.value = (row._tags || []).map(t => t.id)
  tagDialogVisible.value = true
}
const saveKbTags = async () => {
  tagSubmitting.value = true
  try {
    // 转成 Long 类型 ID 列表（后端 setKbTags 要求 List<Long>）
    const ids = tagSelectedIds.value.map(id => Number(id))
    await kbSetTags(tagKb.value.id, ids)
    ElMessage.success('标签已更新')
    tagDialogVisible.value = false
    // 刷新该行标签
    const t = await kbTags(tagKb.value.id)
    tagKb.value._tags = t.data || []
  } finally { tagSubmitting.value = false }
}

watch(keyword, () => { page.value = 1; load() })
onMounted(() => { load(); loadEmbedding(); loadAllTags() })
</script>
