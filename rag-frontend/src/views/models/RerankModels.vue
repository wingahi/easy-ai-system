<template>
  <div class="page-container">
    <div class="page-header">
      <h2>重排序模型</h2>
      <el-button type="primary" :icon="Plus" @click="openDialog()">新增模型</el-button>
    </div>

    <div class="card">
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="名称" />
        <el-table-column prop="provider" label="提供商" width="140" />
        <el-table-column prop="modelName" label="模型名" />
        <el-table-column prop="baseUrl" label="Base URL" show-overflow-tooltip />
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

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑重排序模型' : '新增重排序模型'" width="560px">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="110px">
        <el-form-item prop="name" label="业务名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item prop="provider" label="提供商">
          <el-select v-model="form.provider" style="width:100%">
            <el-option label="通义 DashScope" value="DASHSCOPE" />
            <el-option label="通用 /rerank（Jina / SiliconFlow）" value="CUSTOM_RERANK" />
          </el-select>
        </el-form-item>
        <el-form-item prop="modelName" label="厂商模型名">
          <el-input v-model="form.modelName" placeholder="如 gte-rerank-v2 / bge-reranker-v2-m3" />
        </el-form-item>
        <el-form-item prop="baseUrl" label="Base URL">
          <el-input v-model="form.baseUrl" placeholder="DashScope 可留空；通用填 https://api.siliconflow.cn/v1" />
        </el-form-item>
        <el-form-item prop="apiKey" label="API Key">
          <el-input v-model="form.apiKey" show-password placeholder="sk-xxx..." />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
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
import { rerankModelPage, rerankModelCreate, rerankModelUpdate, rerankModelDelete } from '../../api'

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
  name: '', provider: 'DASHSCOPE', modelName: '', baseUrl: '', apiKey: '', status: 1, remark: ''
})
const rules = {
  name: [{ required: true, message: '请输入名称' }],
  provider: [{ required: true }],
  modelName: [{ required: true, message: '请输入厂商模型名' }]
}

const load = async () => {
  loading.value = true
  try {
    const res = await rerankModelPage({ page: page.value, size: size.value })
    list.value = res.data.records
    total.value = res.data.total
  } finally { loading.value = false }
}

const openDialog = (row) => {
  editing.value = row || null
  if (row) {
    Object.assign(form, row)
  } else {
    Object.assign(form, { name: '', provider: 'DASHSCOPE', modelName: '', baseUrl: '', apiKey: '', status: 1, remark: '' })
  }
  dialogVisible.value = true
}

const submit = async () => {
  await formRef.value.validate()
  submitting.value = true
  try {
    if (editing.value) {
      form.id = editing.value.id
      await rerankModelUpdate(form)
    } else {
      await rerankModelCreate(form)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally { submitting.value = false }
}

const del = async (row) => {
  await rerankModelDelete(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>
