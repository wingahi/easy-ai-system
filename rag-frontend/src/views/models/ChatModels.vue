<template>
  <div class="page-container">
    <div class="page-header">
      <h2>大模型配置</h2>
      <el-button type="primary" :icon="Plus" @click="openDialog()">新增模型</el-button>
    </div>

    <div class="card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="provider" label="提供商" width="120" />
        <el-table-column prop="modelName" label="模型名" />
        <el-table-column prop="baseUrl" label="Base URL" show-overflow-tooltip />
        <el-table-column prop="maxContextTokens" label="上下文" width="100" />
        <el-table-column label="压缩模型" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.compressionModel" type="warning">✓ 压缩</el-tag>
            <span v-else>-</span>
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

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑模型' : '新增模型'" width="560px">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="110px">
        <el-form-item prop="name" label="业务名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item prop="provider" label="提供商">
          <el-select v-model="form.provider" style="width:100%">
            <el-option label="OpenAI 兼容" value="OPENAI" />
            <el-option label="通义 DashScope" value="DASHSCOPE" />
            <el-option label="DeepSeek" value="DEEPSEEK" />
            <el-option label="自定义" value="CUSTOM" />
          </el-select>
        </el-form-item>
        <el-form-item prop="modelName" label="厂商模型名"><el-input v-model="form.modelName" placeholder="如 gpt-4o-mini / qwen-plus" /></el-form-item>
        <el-form-item prop="baseUrl" label="Base URL"><el-input v-model="form.baseUrl" placeholder="如 https://api.deepseek.com/v1" /></el-form-item>
        <el-form-item prop="apiKey" label="API Key">
          <el-input v-model="form.apiKey" show-password placeholder="sk-xxx..." />
        </el-form-item>
        <el-form-item prop="maxContextTokens" label="上下文 Token">
          <el-input-number v-model="form.maxContextTokens" :min="512" :max="200000" :step="512" />
        </el-form-item>
        <el-form-item label="温度">
          <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" :show-tooltip="true" />
        </el-form-item>
        <el-form-item label="压缩模型">
          <el-switch v-model="form.compressionModel" />
          <span style="margin-left:10px;color:#909399;font-size:12px">全局只能有一个</span>
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
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { chatModelPage, chatModelCreate, chatModelUpdate, chatModelDelete } from '../../api'

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
  name: '', provider: 'DEEPSEEK', modelName: '', baseUrl: '', apiKey: '',
  maxContextTokens: 8192, temperature: 0.7, compressionModel: false, status: 1, modelType: 'CHAT'
})
const rules = {
  name: [{ required: true, message: '请输入名称' }],
  provider: [{ required: true }],
  modelName: [{ required: true }]
}

const load = async () => {
  loading.value = true
  try {
    const res = await chatModelPage({ page: page.value, size: size.value })
    list.value = res.data.records
    total.value = res.data.total
  } finally { loading.value = false }
}

const openDialog = (row) => {
  editing.value = row || null
  if (row) {
    Object.assign(form, row)
  } else {
    Object.assign(form, { name: '', provider: 'DEEPSEEK', modelName: '', baseUrl: '', apiKey: '',
      maxContextTokens: 8192, temperature: 0.7, compressionModel: false, status: 1, modelType: 'CHAT' })
  }
  dialogVisible.value = true
}

const submit = async () => {
  await formRef.value.validate()
  submitting.value = true
  try {
    if (editing.value) {
      form.id = editing.value.id
      await chatModelUpdate(form)
    } else {
      await chatModelCreate(form)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally { submitting.value = false }
}

const del = async (row) => {
  await chatModelDelete(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>
