<template>
  <div class="page-container">
    <div class="page-header">
      <h2>工具管理</h2>
      <el-button v-if="activeTab !== 'SKILL'" type="primary" :icon="Plus" @click="openDialog()">新增工具</el-button>
      <el-button v-if="activeTab === 'SKILL'" type="primary" :icon="Plus" @click="openCreateSkillDialog()">新建 Skill 目录</el-button>
    </div>

    <div class="card">
      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <el-tab-pane label="全部" name="" />
        <el-tab-pane label="Skill" name="SKILL" />
        <el-tab-pane label="MCP" name="MCP" />
        <el-tab-pane label="HTTP接口" name="HTTP" />
        <el-tab-pane label="Function Calling" name="FUNCTION_CALLING" />
      </el-tabs>

      <!-- ===== 通用工具列表（非 SKILL 模式） ===== -->
      <template v-if="activeTab !== 'SKILL'">
        <el-table :data="list" v-loading="loading" stripe>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="name" label="名称" />
          <el-table-column label="类型" width="130">
            <template #default="{ row }">
              <el-tag :type="toolTypeColor(row.toolType)">{{ toolTypeLabel(row.toolType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="描述" show-overflow-tooltip />
          <el-table-column label="参考示例" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.isExample" type="warning">示例</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'danger'">{{ row.status === 1 ? '启用' : '禁用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button link type="warning" @click="openDebugDialog(row)">调试</el-button>
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
      </template>

      <!-- ===== Skill 专属：目录管理 + 在线编辑 ===== -->
      <template v-else>
        <div class="skill-layout">
          <!-- 左：skill 列表（数据库记录 + 磁盘目录统一展示） -->
          <div class="skill-left">
            <div class="skill-section-header">
              <strong>Skill 列表</strong>
              <el-button link type="primary" size="small" :icon="Refresh" @click="loadSkillDirs" />
            </div>
            <div class="skill-base-path" v-if="skillBasePath">
              <el-icon><FolderOpened /></el-icon>
              <span class="path-text" :title="skillBasePath">{{ skillBasePath }}</span>
            </div>
            <el-table
              :data="skillItems"
              v-loading="skillLoading"
              stripe
              highlight-current-row
              height="420"
              @current-change="onSkillSelect"
              empty-text="暂无 Skill（数据库无 SKILL 记录，目录也为空）"
            >
              <el-table-column prop="displayName" label="名称" min-width="120" show-overflow-tooltip>
                <template #default="{ row }">
                  <el-icon style="margin-right:4px">
                    <Folder v-if="row.source === 'dir'" />
                    <Coin v-else />
                  </el-icon>
                  {{ row.displayName }}
                </template>
              </el-table-column>
              <el-table-column label="来源" width="110" align="center">
                <template #default="{ row }">
                  <template v-if="row.source === 'dir'">
                    <el-tag type="success" size="small">目录</el-tag>
                    <el-icon v-if="row.imported" color="#67C23A" style="margin-left:4px" title="已导入数据库">
                      <CircleCheckFilled />
                    </el-icon>
                  </template>
                  <el-tag v-else type="primary" size="small">数据库</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="描述" min-width="130" show-overflow-tooltip>
                <template #default="{ row }">{{ row.description || '—' }}</template>
              </el-table-column>
              <el-table-column label="文件" width="60" align="center">
                <template #default="{ row }">{{ row.fileCount ?? '—' }}</template>
              </el-table-column>
              <el-table-column label="操作" width="80" align="center">
                <template #default="{ row }">
                  <el-tooltip v-if="!row.dbId" content="未导入数据库，需先「导入数据库」后才能调试" placement="top">
                    <el-button link type="warning" disabled>调试</el-button>
                  </el-tooltip>
                  <el-button v-else link type="warning" @click.stop="openDebugDialog(row.record)">调试</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <!-- 右：编辑器 + 导入/保存 -->
          <div class="skill-right">
            <template v-if="!selectedSkill">
              <el-empty description="请选择左侧 Skill 进行编辑（目录 / 数据库记录均可）" />
            </template>
            <template v-else>
              <div class="skill-editor-header">
                <div>
                  <strong>{{ selectedSkill.displayName }}</strong>
                  <el-tag style="margin-left:6px" size="small" :type="selectedSkill.source === 'dir' ? 'success' : 'primary'">
                    {{ selectedSkill.source === 'dir' ? '目录' : '数据库' }}
                  </el-tag>
                  <el-tag v-if="selectedSkill.source === 'dir'" style="margin-left:6px" size="small">
                    主文件: {{ selectedSkill.mainFile }}
                  </el-tag>
                  <el-tag v-else style="margin-left:6px" size="small" type="info">
                    模式: {{ selectedSkill.executorMode }}
                  </el-tag>
                </div>
                <div class="skill-actions">
                  <!-- 目录：删除磁盘目录；数据库：删除 ToolConfig 记录 -->
                  <el-popconfirm
                    v-if="selectedSkill.source === 'dir'"
                    title="确认删除整个 Skill 目录？磁盘文件将全部清除！"
                    @confirm="handleDeleteSkillDir">
                    <template #reference>
                      <el-button link type="danger">删目录</el-button>
                    </template>
                  </el-popconfirm>
                  <el-popconfirm
                    v-else
                    title="确认删除该数据库 Skill 记录？"
                    @confirm="handleDeleteDbSkill">
                    <template #reference>
                      <el-button link type="danger">删除记录</el-button>
                    </template>
                  </el-popconfirm>

                  <el-button v-if="selectedSkill.source === 'dir'" type="primary" :icon="Upload"
                             @click="handleSaveSkill" :loading="skillSaving">
                    保存到磁盘
                  </el-button>
                  <el-button v-else type="primary" :icon="DocumentCopy"
                             @click="handleSaveDbSkill" :loading="skillSaving">
                    保存到数据库
                  </el-button>

                  <el-button v-if="selectedSkill.source === 'dir' && !selectedSkill.imported"
                             type="success" :icon="Download" @click="handleImportSkill">
                    导入数据库
                  </el-button>
                  <el-button v-else-if="selectedSkill.source === 'dir'"
                             :icon="DocumentCopy" @click="handleImportSkill" title="覆盖更新 ToolConfig 记录">
                    重新导入
                  </el-button>
                </div>
              </div>

              <!-- front matter / executorConfig 快速编辑 -->
              <div class="skill-meta">
                <el-select
                  v-model="skillMeta.subModelId"
                  placeholder="子模型（默认：继承窗口模型）"
                  size="small"
                  clearable
                  style="width:260px"
                >
                  <el-option
                    v-for="m in chatModels"
                    :key="m.id"
                    :value="String(m.id)"
                    :label="`${m.name} (${m.provider}/${m.modelName})`"
                  />
                </el-select>
                <el-input v-model="skillMeta.description" placeholder="描述" size="small" style="flex:1" />
              </div>

              <!-- 提示词内容编辑器 -->
              <el-input
                v-model="skillContent"
                type="textarea"
                :rows="18"
                :placeholder="skillPlaceholder"
              />

              <!-- 文件树折叠面板（仅目录） -->
              <el-collapse v-if="selectedSkill.source === 'dir' && selectedSkill.fileTree?.length" style="margin-top:8px">
                <el-collapse-item v-for="f in selectedSkill.fileTree" :key="f.path" :title="f.name" :name="f.path">
                  <div style="color:#909399;font-size:12px">
                    路径: {{ f.path }} · {{ (f.size / 1024).toFixed(1) }} KB
                    <el-tag v-if="f.isMain" type="warning" size="small" style="margin-left:6px">主 prompt</el-tag>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </template>
          </div>
        </div>
      </template>
    </div>

    <!-- ===== 通用新增/编辑工具弹窗 ===== -->
    <el-dialog v-if="activeTab !== 'SKILL'" v-model="dialogVisible" :title="editing ? '编辑工具' : '新增工具'" width="600px" top="5vh">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="120px">
        <el-form-item prop="name" label="工具名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item prop="toolType" label="工具类型">
          <el-select v-model="form.toolType" style="width:100%">
            <el-option label="Skill 技能" value="SKILL" />
            <el-option label="MCP 协议" value="MCP" />
            <el-option label="HTTP 接口" value="HTTP" />
            <el-option label="Function Calling" value="FUNCTION_CALLING" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="工具定义 (JSON)">
          <el-input v-model="form.definition" type="textarea" :rows="6" placeholder='{"type":"function",...}' />
        </el-form-item>
        <el-form-item label="执行器配置 (JSON)">
          <el-input v-model="form.executorConfig" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="状态"><el-switch v-model="form.status" :active-value="1" :inactive-value="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">确定</el-button>
      </template>
    </el-dialog>

    <!-- ===== 新建 Skill 目录弹窗 ===== -->
    <el-dialog v-model="createSkillVisible" title="新建 Skill 目录" width="480px">
      <el-form :model="newSkillForm" :rules="newSkillRules" ref="newSkillFormRef" label-width="120px">
        <el-form-item prop="dir" label="目录名">
          <el-input v-model="newSkillForm.dir" placeholder="如 weather-query" />
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="newSkillForm.displayName" placeholder="如 天气查询助手" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="newSkillForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="子模型">
          <el-select v-model="newSkillForm.subModelId" placeholder="默认：继承窗口模型" clearable style="width:100%">
            <el-option
              v-for="m in chatModels"
              :key="m.id"
              :value="String(m.id)"
              :label="`${m.name} (${m.provider}/${m.modelName})`"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createSkillVisible = false">取消</el-button>
        <el-button type="primary" :loading="creatingSkill" @click="submitCreateSkill">创建</el-button>
      </template>
    </el-dialog>

    <!-- ===== 工具调试弹窗 ===== -->
    <el-dialog v-model="debugVisible" :title="`调试工具：${debugTool?.name || ''}`" width="640px" top="5vh">
      <div v-if="debugTool" class="debug-body">
        <div class="debug-meta">
          <el-tag :type="toolTypeColor(debugTool.toolType)">{{ toolTypeLabel(debugTool.toolType) }}</el-tag>
          <span class="fn-name">{{ debugParsed.name || '—' }}</span>
          <span class="fn-desc">{{ debugParsed.description || '' }}</span>
        </div>

        <el-form label-width="120px" class="debug-form">
          <el-form-item
            v-for="p in debugParsed.params"
            :key="p.name"
            :label="p.name"
            :required="p.required"
          >
            <el-select v-if="p.enum?.length" v-model="debugArgs[p.name]" clearable :placeholder="p.description || p.name">
              <el-option v-for="v in p.enum" :key="v" :label="v" :value="v" />
            </el-select>
            <el-input-number v-else-if="p.type === 'integer' || p.type === 'number'"
                             v-model="debugArgs[p.name]" style="width:100%" :placeholder="p.description || p.name" />
            <el-switch v-else-if="p.type === 'boolean'" v-model="debugArgs[p.name]" />
            <el-input v-else v-model="debugArgs[p.name]" :placeholder="p.description || p.name" />
            <div v-if="p.description" class="param-desc">{{ p.description }}</div>
          </el-form-item>
          <el-form-item v-if="!debugParsed.params.length">
            <el-text type="info">该工具无参数，点击「执行」即可</el-text>
          </el-form-item>
        </el-form>

        <div v-if="debugExampleText" class="debug-example">
          <div class="debug-example-header">
            <strong>示例参数</strong>
            <el-button link type="primary" size="small" @click="fillExample">一键填入</el-button>
          </div>
          <pre>{{ debugExampleText }}</pre>
        </div>

        <div v-if="debugResult" class="debug-result" :class="{ error: !debugResult.success }">
          <div class="debug-result-header">
            <strong>执行结果</strong>
            <el-tag :type="debugResult.success ? 'success' : 'danger'" size="small">
              {{ debugResult.success ? '成功' : '失败' }}
            </el-tag>
            <span class="duration">{{ debugResult.durationMs }} ms</span>
          </div>
          <pre>{{ debugResult.resultText }}</pre>
        </div>
      </div>
      <template #footer>
        <el-button @click="debugVisible = false">关闭</el-button>
        <el-button type="primary" :loading="debugRunning" @click="runDebug">执行</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Plus, Refresh, Upload, Download, DocumentCopy,
  Folder, FolderOpened, CircleCheckFilled, Coin
} from '@element-plus/icons-vue'
import {
  toolPage, toolCreate, toolUpdate, toolDelete, toolDebug,
  skillListDirs, skillReadContent, skillSaveContent, skillCreateDir, skillDeleteDir, skillImport,
  chatModelList
} from '../../api'

// ============ 通用状态 ============
const loading = ref(false)
const submitting = ref(false)
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const activeTab = ref('')
const dialogVisible = ref(false)
const editing = ref(null)
const formRef = ref()

const form = reactive({
  name: '', toolType: 'SKILL', description: '', definition: '', executorConfig: '',
  status: 1
})
const rules = {
  name: [{ required: true }], toolType: [{ required: true }]
}

const toolTypeLabel = (t) => ({ SKILL: 'Skill技能', MCP: 'MCP协议', HTTP: 'HTTP接口', FUNCTION_CALLING: 'Function Calling' }[t] || t)
const toolTypeColor = (t) => ({ SKILL: 'primary', MCP: 'success', HTTP: 'warning', FUNCTION_CALLING: 'info' }[t] || 'primary')

const load = async () => {
  loading.value = true
  try {
    const res = await toolPage({ page: page.value, size: size.value, toolType: activeTab.value || undefined })
    list.value = res.data.records
    total.value = res.data.total
  } finally { loading.value = false }
}

const onTabChange = () => {
  page.value = 1
  load()
  if (activeTab.value === 'SKILL') { loadSkillDirs(); loadChatModels() }
}

const openDialog = (row) => {
  editing.value = row || null
  if (row) Object.assign(form, row)
  else Object.assign(form, { name: '', toolType: 'SKILL', description: '', definition: '', executorConfig: '', status: 1 })
  dialogVisible.value = true
}

const submit = async () => {
  await formRef.value.validate()
  submitting.value = true
  try {
    if (editing.value) { form.id = editing.value.id; await toolUpdate(form) }
    else { await toolCreate(form) }
    ElMessage.success('保存成功')
    dialogVisible.value = false
    load()
  } finally { submitting.value = false }
}

const del = async (row) => {
  await toolDelete(row.id)
  ElMessage.success('删除成功')
  load()
}

// ============ Skill 目录管理 ============
const skillLoading = ref(false)
const skillDirs = ref([])
const skillBasePath = ref('')
const selectedSkill = ref(null)   // { relativePath, dirName, mainFile, content, frontMatter, fileTree, ... }
const skillContent = ref('')
const skillMeta = reactive({ subModelId: '', description: '' })
const skillSaving = ref(false)
const creatingSkill = ref(false)
const createSkillVisible = ref(false)
const newSkillFormRef = ref()
const newSkillForm = reactive({ dir: '', displayName: '', description: '', subModelId: '' })
const newSkillRules = { dir: [{ required: true, message: '请输入目录名', trigger: 'blur' }] }

/** 模型管理的启用大模型列表（子模型下拉选项） */
const chatModels = ref([])
const loadChatModels = async () => {
  try {
    const res = await chatModelList()
    chatModels.value = res.data || []
  } catch { chatModels.value = [] }
}

/** 已导入 DB 的 skill 目录集合（以绝对路径为 key） */
const parseSkillConfig = (record) => {
  try { return JSON.parse(record.executorConfig || '{}') } catch { return {} }
}

const dbSkills = computed(() => (list.value || []).filter(t => t.toolType === 'SKILL'))

/** 内容编辑器的占位提示（按来源区分，避免在模板表达式里写换行/大括号） */
const skillPlaceholder = computed(() => {
  if (selectedSkill.value?.source !== 'dir') {
    return '直接填写 promptTemplate，可用 ---SYSTEM--- / ---USER--- 分隔，支持 {{参数名}} 模板'
  }
  return ['---SYSTEM---', '你是...', '', '---USER---', '请帮我 {{task}}', ''].join('\n')
})

/**
 * 左侧统一列表：磁盘目录 skill + 数据库 skill
 * source = 'dir'（磁盘） | 'db'（数据库，且未关联到某个磁盘目录）
 */
const skillItems = computed(() => {
  const dirAbsPaths = new Set(skillDirs.value.map(d => d.absolutePath).filter(Boolean))
  // 目录绝对路径 → DB 记录（已导入）
  const dirToDb = new Map()
  for (const t of dbSkills.value) {
    const cfg = parseSkillConfig(t)
    if (cfg.skillPath) dirToDb.set(cfg.skillPath, t)
  }
  const dirItems = skillDirs.value.map(d => {
    const dbRecord = dirToDb.get(d.absolutePath)
    return {
      key: `dir:${d.relativePath}`,
      source: 'dir',
      displayName: d.dirName,
      relativePath: d.relativePath,
      absolutePath: d.absolutePath,
      description: d.description,
      fileCount: d.fileCount,
      imported: !!dbRecord,
      dbId: dbRecord?.id || null,
      record: dbRecord || null
    }
  })

  const dbOnlyItems = dbSkills.value
    .filter(t => {
      const cfg = parseSkillConfig(t)
      return !(cfg.skillPath && dirAbsPaths.has(cfg.skillPath))
    })
    .map(t => {
      const cfg = parseSkillConfig(t)
      return {
        key: `db:${t.id}`,
        source: 'db',
        dbId: t.id,
        displayName: t.name,
        description: t.description,
        fileCount: null,
        imported: true,
        executorMode: cfg.mode || (cfg.skillPath ? 'directory' : 'inline'),
        record: t
      }
    })

  return [...dirItems, ...dbOnlyItems]
})

const loadSkillDirs = async () => {
  skillLoading.value = true
  try {
    const res = await skillListDirs()
    skillBasePath.value = res.data.basePath
    skillDirs.value = res.data.skills
  } finally { skillLoading.value = false }
}

/** 左表选中：目录读磁盘，数据库记录读 executorConfig.promptTemplate */
const onSkillSelect = async (row) => {
  if (!row) { selectedSkill.value = null; return }
  if (row.source === 'dir') {
    try {
      const res = await skillReadContent(row.relativePath)
      selectedSkill.value = { ...row, ...res.data, source: 'dir', imported: row.imported }
      skillContent.value = res.data.content
      skillMeta.subModelId = res.data.frontMatter?.subModelId || ''
      skillMeta.description = res.data.frontMatter?.description || ''
    } catch (e) {
      ElMessage.error(`读取失败: ${e.message}`)
    }
    return
  }

  const cfg = parseSkillConfig(row.record)
  selectedSkill.value = {
    ...row,
    mainFile: row.executorMode === 'directory' ? '磁盘目录' : '数据库 promptTemplate',
    content: cfg.promptTemplate || '',
    fileTree: []
  }
  skillContent.value = cfg.promptTemplate || ''
  skillMeta.subModelId = cfg.subModelId != null ? String(cfg.subModelId) : ''
  skillMeta.description = row.description || ''
}

/** 保存数据库 Skill：写回 executorConfig.promptTemplate + subModelId */
const handleSaveDbSkill = async () => {
  const item = selectedSkill.value
  if (!item || item.source !== 'db') return
  skillSaving.value = true
  try {
    const record = { ...item.record }
    const cfg = { ...parseSkillConfig(record) }
    cfg.promptTemplate = skillContent.value
    if (skillMeta.subModelId) {
      cfg.subModelId = Number(skillMeta.subModelId)
      delete cfg.subModelDefault
    } else {
      delete cfg.subModelId
      cfg.subModelDefault = true
    }
    record.executorConfig = JSON.stringify(cfg)
    record.description = skillMeta.description || record.description
    await toolUpdate(record)

    ElMessage.success('已保存到数据库')
    await load()
    const again = skillItems.value.find(i => i.key === item.key)
    if (again) await onSkillSelect(again)
  } catch (e) {
    ElMessage.error(`保存失败: ${e.message}`)
  } finally { skillSaving.value = false }
}

/** 删除数据库 Skill 记录 */
const handleDeleteDbSkill = async () => {
  const item = selectedSkill.value
  if (!item || item.source !== 'db') return
  try {
    await toolDelete(item.dbId)
    ElMessage.success('Skill 记录已删除')
    selectedSkill.value = null
    skillContent.value = ''
    await load()
  } catch (e) {
    ElMessage.error(`删除失败: ${e.message}`)
  }
}

const handleSaveSkill = async () => {
  if (!selectedSkill.value) return
  skillSaving.value = true
  try {
    await skillSaveContent({
      dir: selectedSkill.value.relativePath,
      content: skillContent.value,
      createIfAbsent: true,
      subModelId: skillMeta.subModelId || '',
      description: skillMeta.description || ''
    })
    ElMessage.success('已保存到磁盘')
    // 后端会把 subModelId/description 回写进 front matter，重新读回刷新编辑器内容
    const res = await skillReadContent(selectedSkill.value.relativePath)
    skillContent.value = res.data.content
    loadSkillDirs()   // 刷新目录信息（文件数、修改时间等）
  } catch (e) {
    ElMessage.error(`保存失败: ${e.message}`)
  } finally { skillSaving.value = false }
}

const handleImportSkill = async () => {
  if (!selectedSkill.value) return
  try {
    const res = await skillImport({
      dir: selectedSkill.value.relativePath,
      subModelId: skillMeta.subModelId || undefined,
      name: selectedSkill.value.dirName
    })
    const { duplicated, tool, hint } = res.data || {}
    if (duplicated) {
      // 同名 skill 已存在 —— 用 warning 提示用户，不创建/不更新
      ElMessage.warning(hint || `数据库已存在同名 skill「${tool?.name}」(id=${tool?.id})，本次未做导入或更新`)
    } else {
      ElMessage.success(`已导入到数据库（id=${tool?.id}）`)
    }
    load()              // 刷新 DB 列表
    loadSkillDirs()     // 刷新导入标记
  } catch (e) {
    ElMessage.error(`导入失败: ${e.message}`)
  }
}

const handleDeleteSkillDir = async () => {
  if (!selectedSkill.value) return
  try {
    await skillDeleteDir(selectedSkill.value.relativePath)
    ElMessage.success('Skill 目录已删除')
    selectedSkill.value = null
    skillContent.value = ''
    loadSkillDirs()
    load()
  } catch (e) {
    ElMessage.error(`删除失败: ${e.message}`)
  }
}

const openCreateSkillDialog = () => {
  Object.assign(newSkillForm, { dir: '', displayName: '', description: '', subModelId: '' })
  createSkillVisible.value = true
}

const submitCreateSkill = async () => {
  await newSkillFormRef.value.validate()
  creatingSkill.value = true
  try {
    await skillCreateDir(newSkillForm)
    ElMessage.success('已创建新 Skill 目录')
    createSkillVisible.value = false
    loadSkillDirs()
  } catch (e) {
    ElMessage.error(`创建失败: ${e.message}`)
  } finally { creatingSkill.value = false }
}

// ============ 工具调试 ============
const debugVisible = ref(false)
const debugTool = ref(null)
const debugArgs = ref({})
const debugResult = ref(null)
const debugRunning = ref(false)

// 解析 definition，提取 function.name/description/parameters
const debugParsed = computed(() => {
  if (!debugTool.value?.definition) return { name: '', description: '', params: [] }
  try {
    const def = JSON.parse(debugTool.value.definition)
    const fn = def.function || def
    const params = []
    const props = fn.parameters?.properties || {}
    const required = new Set(fn.parameters?.required || [])
    for (const [name, schema] of Object.entries(props)) {
      params.push({
        name,
        type: schema.type || 'string',
        description: schema.description || '',
        required: required.has(name),
        enum: Array.isArray(schema.enum) ? schema.enum : null
      })
    }
    return { name: fn.name || '', description: fn.description || '', params }
  } catch {
    return { name: '', description: '', params: [] }
  }
})

// 生成示例参数（根据 definition 的 properties 类型生成示例值）
const debugExampleText = computed(() => {
  if (!debugParsed.value.params.length) return ''
  const sample = {}
  for (const p of debugParsed.value.params) {
    if (p.enum?.length) sample[p.name] = p.enum[0]
    else if (p.type === 'integer' || p.type === 'number') sample[p.name] = 1
    else if (p.type === 'boolean') sample[p.name] = true
    else sample[p.name] = p.name === 'userId' ? 'U1001'
      : p.name === 'message' ? 'hello'
      : p.name === 'city' ? '北京'
      : 'example'
  }
  return JSON.stringify(sample, null, 2)
})

const openDebugDialog = (row) => {
  debugTool.value = row
  debugArgs.value = {}
  debugResult.value = null
  debugVisible.value = true
}

const fillExample = () => {
  try {
    debugArgs.value = JSON.parse(debugExampleText.value)
  } catch { /* ignore */ }
}

const runDebug = async () => {
  // 校验必填
  for (const p of debugParsed.value.params) {
    if (p.required && (debugArgs.value[p.name] === undefined || debugArgs.value[p.name] === null || debugArgs.value[p.name] === '')) {
      ElMessage.warning(`请填写必填参数: ${p.name}`)
      return
    }
  }
  // 过滤空值（非必填项留空即不传）
  const args = {}
  for (const p of debugParsed.value.params) {
    const v = debugArgs.value[p.name]
    if (v !== undefined && v !== null && v !== '') args[p.name] = v
  }
  debugRunning.value = true
  debugResult.value = null
  try {
    const res = await toolDebug(debugTool.value.id, args)
    debugResult.value = res.data
  } catch (e) {
    debugResult.value = { success: false, resultText: e.message || '调用失败', durationMs: 0 }
  } finally { debugRunning.value = false }
}

onMounted(load)
</script>

<style scoped>
.skill-layout {
  display: flex;
  gap: 16px;
  margin-top: 12px;
}
.skill-left {
  flex: 0 0 380px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 12px;
  background: #fafbfc;
}
.skill-right {
  flex: 1;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 12px;
  min-width: 0;
}
.skill-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.skill-base-path {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 10px;
  padding: 6px 10px;
  background: #f0f9ff;
  border: 1px dashed #9ec8ff;
  border-radius: 4px;
  font-size: 12px;
  color: #409eff;
  overflow: hidden;
}
.path-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.skill-editor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.skill-actions {
  display: flex;
  gap: 8px;
}
.skill-meta {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}
/* ===== 工具调试弹窗 ===== */
.debug-body { padding: 4px 0; }
.debug-meta {
  display: flex; align-items: center; gap: 10px; margin-bottom: 16px;
  padding: 8px 12px; background: #f5f7fa; border-radius: 4px;
}
.debug-meta .fn-name { font-family: 'SF Mono', Monaco, monospace; font-weight: 600; color: #303133; }
.debug-meta .fn-desc { color: #909399; font-size: 12px; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.debug-form { max-height: 320px; overflow-y: auto; }
.param-desc { font-size: 12px; color: #909399; margin-top: 2px; line-height: 1.4; }
.debug-example, .debug-result {
  margin-top: 16px; padding: 10px 12px; border-radius: 4px;
  background: #f5f7fa; border: 1px solid #e4e7ed;
}
.debug-result.error { background: #fef0f0; border-color: #fbc4c4; }
.debug-example-header, .debug-result-header {
  display: flex; align-items: center; gap: 8px; margin-bottom: 6px;
}
.debug-example-header strong, .debug-result-header strong { font-size: 13px; color: #303133; }
.debug-example-header { justify-content: space-between; }
.debug-result-header .duration { margin-left: auto; font-size: 12px; color: #909399; font-family: 'SF Mono', Monaco, monospace; }
.debug-example pre, .debug-result pre {
  margin: 0; padding: 8px; background: #fff; border-radius: 3px;
  font-family: 'SF Mono', Monaco, monospace; font-size: 12px; line-height: 1.5;
  white-space: pre-wrap; word-break: break-all; max-height: 200px; overflow-y: auto;
  color: #303133;
}
.debug-result.error pre { background: #fff; color: #f56c6c; }
</style>
