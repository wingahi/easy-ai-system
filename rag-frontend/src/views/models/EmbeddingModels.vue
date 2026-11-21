<template>
  <div class="page-container">
    <div class="page-header">
      <h2>嵌入模型管理</h2>
      <el-button type="primary" :icon="Plus" @click="openDialog()">新增嵌入模型</el-button>
    </div>

    <div class="card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="provider" label="提供商" width="150">
          <template #default="{ row }">{{ providerLabel(row.provider) }}</template>
        </el-table-column>
        <el-table-column prop="modelName" label="模型名" />
        <el-table-column prop="dimension" label="维度" width="90" />
        <el-table-column prop="baseUrl" label="Base URL" show-overflow-tooltip />
        <el-table-column label="默认" width="80">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault" type="primary">默认</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-popconfirm title="确认删除？" @confirm="del(row)">
              <template #reference><el-button link type="danger">删除</el-button></template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top:16px;justify-content:flex-end;display:flex"
        v-model:current-page="page" v-model:page-size="size"
        :total="total" :page-sizes="[10,20,50]"
        layout="total, sizes, prev, pager, next" @size-change="load" @current-change="load" />
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑' : '新增'" width="500px">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="110px">
        <el-form-item prop="name" label="业务名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item prop="provider" label="提供商">
          <el-select v-model="form.provider" style="width:100%" @change="onProviderChange">
            <el-option v-for="p in embeddingProviders" :key="p.value" :label="p.label" :value="p.value" />
          </el-select>
          <div v-if="currentProvider" style="margin-top:6px;color:#909399;font-size:12px">
            {{ currentProvider.hint }}
          </div>
        </el-form-item>
        <el-form-item prop="modelName" label="模型名">
          <el-input v-model="form.modelName" :placeholder="form.provider === 'OLLAMA' ? '如 nomic-embed-text / bge-m3' : '如 text-embedding-v2'" />
        </el-form-item>
        <el-form-item prop="dimension" label="向量维度"><el-input-number v-model="form.dimension" :min="1" :max="10000" /></el-form-item>
        <el-form-item label="Base URL"><el-input v-model="form.baseUrl" /></el-form-item>
        <el-form-item prop="apiKey" label="API Key">
          <el-input v-model="form.apiKey" show-password
            :disabled="form.provider === 'OLLAMA'"
            :placeholder="form.provider === 'OLLAMA' ? '本地部署无需 Key' : 'sk-xxx...'" />
        </el-form-item>
        <el-form-item label="默认模型"><el-switch v-model="form.isDefault" /></el-form-item>
        <el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { embeddingModelPage, embeddingModelCreate, embeddingModelUpdate, embeddingModelDelete, EMBEDDING_MODEL_PROVIDERS } from '../../api'
import { validateForm } from '../../utils/form'

const embeddingProviders = EMBEDDING_MODEL_PROVIDERS
const providerLabel = (value) => {
  const key = String(value || '').toUpperCase()
  const legacy = { CUSTOM: '自定义 OpenAI 兼容', AZURE: 'Azure OpenAI', GLM: '智谱 GLM' }
  return embeddingProviders.find((p) => p.value === key)?.label || legacy[key] || value || '-'
}
const onProviderChange = (value) => {
  if (editing.value) return
  const provider = embeddingProviders.find((p) => p.value === value)
  if (!provider) return
  form.baseUrl = provider.defaultBaseUrl || ''
  if (value === 'OLLAMA') form.apiKey = ''
}

const loading = ref(false)
const submitting = ref(false)
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const dialogVisible = ref(false)
const editing = ref(null)
const formRef = ref()

const form = reactive({
  name: '', provider: 'DASHSCOPE', modelName: '', dimension: 1536,
  baseUrl: '', apiKey: '', isDefault: false, status: 1
})
const currentProvider = computed(() => embeddingProviders.find((p) => p.value === form.provider))
const rules = {
  name: [{ required: true }], provider: [{ required: true }],
  modelName: [{ required: true }], dimension: [{ required: true }]
}

const load = async () => {
  loading.value = true
  try {
    const res = await embeddingModelPage({ page: page.value, size: size.value })
    list.value = res.data.records
    total.value = res.data.total
  } finally { loading.value = false }
}

const openDialog = (row) => {
  editing.value = row || null
  if (row) Object.assign(form, row)
  else Object.assign(form, { name: '', provider: 'DASHSCOPE', modelName: '', dimension: 1536, baseUrl: '', apiKey: '', isDefault: false, status: 1 })
  dialogVisible.value = true
}

const submit = async () => {
  if (!(await validateForm(formRef))) return
  submitting.value = true
  try {
    if (editing.value) { form.id = editing.value.id; await embeddingModelUpdate(form) }
    else { await embeddingModelCreate(form) }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally { submitting.value = false }
}

const del = async (row) => {
  await embeddingModelDelete(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>
