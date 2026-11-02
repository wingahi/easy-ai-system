<template>
  <div class="page-container">
    <div class="page-header">
      <h2>Prompt 模板中心</h2>
      <el-button type="primary" :icon="Plus" @click="openDialog()">新增模板</el-button>
    </div>

    <div class="card">
      <div style="margin-bottom:12px;display:flex;gap:8px">
        <el-input v-model="keyword" placeholder="搜索名称 / 内容" style="width:260px" clearable @change="load" />
        <el-select v-model="category" placeholder="分类" style="width:160px" clearable @change="load">
          <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
        </el-select>
      </div>
      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="模板名" width="180">
          <template #default="{ row }">
            {{ row.name }}
            <el-tag v-if="row.builtin" size="small" type="warning" style="margin-left:6px">内置</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="category" label="分类" width="100" />
        <el-table-column label="变量" width="200">
          <template #default="{ row }">
            <el-tag v-for="v in parseVars(row.variables)" :key="v" size="small" type="info" style="margin-right:4px">
              {{ varTag(v) }}
            </el-tag>
            <span v-if="!parseVars(row.variables).length" style="color:#c0c4cc">无</span>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="内容" show-overflow-tooltip />
        <el-table-column prop="version" label="版本" width="70">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-popconfirm title="确认删除？" @confirm="del(row)">
              <template #reference>
                <el-button link type="danger" :disabled="row.builtin">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top:16px;justify-content:flex-end;display:flex"
        v-model:current-page="page" v-model:page-size="size"
        :total="total" :page-sizes="[10,20,50]"
        layout="total, sizes, prev, pager, next" @size-change="load" @current-change="load" />
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑模板' : '新增模板'" width="760px">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="90px">
        <el-form-item prop="name" label="模板名">
          <el-input v-model="form.name" :disabled="editing?.builtin" placeholder="唯一，窗口中以 template:模板名 引用" />
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="form.category" placeholder="如 RAG / 客服 / 总结" style="width:220px" />
        </el-form-item>
        <el-form-item prop="content" label="内容">
          <el-input v-model="form.content" type="textarea" :rows="8"
                    placeholder="使用 {{变量名}} 作为占位符，保存时自动提取变量" />
        </el-form-item>
        <el-form-item label="检测变量">
          <el-tag v-for="v in liveVars" :key="v" size="small" type="info" style="margin-right:4px">
            {{ varTag(v) }}
          </el-tag>
          <span v-if="!liveVars.length" style="color:#c0c4cc">无</span>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" />
        </el-form-item>

        <!-- 实时渲染预览 -->
        <template v-if="editing">
          <el-form-item label="渲染预览">
            <div style="width:100%">
              <div v-for="v in liveVars" :key="v" style="display:flex;gap:8px;margin-bottom:6px;align-items:center">
                <span style="width:110px;color:#606266;font-size:13px">{{ v }}:</span>
                <el-input v-model="previewVars[v]" size="small" placeholder="变量值" @input="doPreview" />
              </div>
              <el-button size="small" @click="doPreview" :loading="previewing">刷新预览</el-button>
              <pre v-if="preview" class="preview-box">{{ preview }}</pre>
            </div>
          </el-form-item>
        </template>
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
import { promptTemplatePage, promptTemplateCreate, promptTemplateUpdate, promptTemplateDelete,
  promptTemplateRender } from '../../api'

const loading = ref(false)
const submitting = ref(false)
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const category = ref('')
const dialogVisible = ref(false)
const editing = ref(null)
const formRef = ref()
const previewVars = ref({})
const preview = ref('')
const previewing = ref(false)

const form = reactive({ name: '', category: '', content: '', remark: '' })
const rules = {
  name: [{ required: true, message: '请输入模板名' }],
  content: [{ required: true, message: '请输入模板内容' }]
}

// 与后端 VAR_PATTERN 保持一致：{{var}}，允许首尾空白，路径段字母数字下划线点连字符
const VAR_RE = /\{\{\s*([\w.\-]+)\s*\}\}/g
const liveVars = computed(() => {
  const vars = []
  const seen = new Set()
  let m
  while ((m = VAR_RE.exec(form.content || '')) !== null) {
    if (!seen.has(m[1])) { seen.add(m[1]); vars.push(m[1]) }
  }
  return vars
})

const categories = computed(() => [...new Set(list.value.map(t => t.category).filter(Boolean))])

const parseVars = (json) => {
  try { return JSON.parse(json || '[]') } catch { return [] }
}

// 安全渲染 {{var}} 占位符（Vue 模板里直接写会干扰解析）
const varTag = (name) => `{{${name}}}`

const load = async () => {
  loading.value = true
  try {
    const res = await promptTemplatePage({ page: page.value, size: size.value, keyword: keyword.value || undefined, category: category.value || undefined })
    list.value = res.data.records
    total.value = res.data.total
  } finally { loading.value = false }
}

const openDialog = (row) => {
  editing.value = row || null
  preview.value = ''
  if (row) {
    Object.assign(form, { name: row.name, category: row.category, content: row.content, remark: row.remark })
    previewVars.value = {}
  } else {
    Object.assign(form, { name: '', category: '', content: '', remark: '' })
  }
  dialogVisible.value = true
}

const doPreview = async () => {
  if (!editing.value) return
  previewing.value = true
  try {
    // 预览走后端渲染（语义与运行时一致）；内容用表单最新值先保存? 不——直接渲染表单内容需后端支持，
    // 简化：先调 render 接口渲染「库中版本」，表单未保存的修改需先保存再预览
    const res = await promptTemplateRender(editing.value.id, previewVars.value)
    preview.value = res.data.rendered
  } finally { previewing.value = false }
}

const submit = async () => {
  await formRef.value.validate()
  submitting.value = true
  try {
    if (editing.value) {
      await promptTemplateUpdate({ ...form, id: editing.value.id })
    } else {
      await promptTemplateCreate(form)
    }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally { submitting.value = false }
}

const del = async (row) => {
  await promptTemplateDelete(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.preview-box {
  margin-top: 8px;
  padding: 10px;
  background: #f5f7fa;
  border-radius: 6px;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
  max-height: 200px;
  overflow: auto;
  width: 100%;
  box-sizing: border-box;
}
</style>
