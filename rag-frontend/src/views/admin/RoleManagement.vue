<template>
  <el-card class="role-page">
    <template #header>
      <div class="card-header">
        <span>角色管理</span>
        <div>
          <el-button @click="load">刷新</el-button>
          <el-button type="primary" @click="openCreate">新建角色</el-button>
        </div>
      </div>
    </template>

    <el-alert
      type="info"
      :closable="false"
      class="tip"
      title="内置 admin 角色拥有全部权限，不可改名、删除或削减权限；角色权限保存后对已登录用户立即生效。"
    />

    <el-table :data="roles" v-loading="loading" border>
      <el-table-column prop="roleCode" label="角色编码" width="200">
        <template #default="{ row }">
          <span class="role-code">{{ row.roleCode }}</span>
          <el-tag v-if="row.builtIn" size="small" type="warning" class="builtin-tag">内置</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="roleName" label="名称" width="150" />
      <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
      <el-table-column prop="userCount" label="用户数" width="90" align="center" />
      <el-table-column prop="permissionCount" label="权限数" width="90" align="center" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openPermissions(row)">权限</el-button>
          <el-button link type="primary" :disabled="row.builtIn" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" :disabled="row.builtIn" @click="removeRole(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <!-- 新建角色 -->
  <el-dialog v-model="createVisible" title="新建角色" width="520px">
    <el-form :model="createForm" :rules="createRules" ref="createFormRef" label-width="90px">
      <el-form-item label="角色编码" prop="roleCode">
        <el-input v-model="createForm.roleCode" placeholder="小写字母开头，可含数字/下划线，如 kb_reader" />
      </el-form-item>
      <el-form-item label="角色名称" prop="roleName">
        <el-input v-model="createForm.roleName" placeholder="如 知识库只读" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="createForm.description" type="textarea" :rows="3" maxlength="500" show-word-limit />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="createVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submitCreate">创建</el-button>
    </template>
  </el-dialog>

  <!-- 编辑角色 -->
  <el-dialog v-model="editVisible" title="编辑角色" width="520px">
    <el-form :model="editForm" :rules="editRules" ref="editFormRef" label-width="90px">
      <el-form-item label="角色编码">
        <el-input :model-value="editForm.roleCode" disabled />
      </el-form-item>
      <el-form-item label="角色名称" prop="roleName">
        <el-input v-model="editForm.roleName" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="editForm.description" type="textarea" :rows="3" maxlength="500" show-word-limit />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="editVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submitEdit">保存</el-button>
    </template>
  </el-dialog>

  <!-- 权限配置 -->
  <el-dialog v-model="permissionVisible" :title="`权限配置 · ${permissionRole?.roleName || ''}`" width="720px">
    <el-alert
      v-if="permissionRole?.builtIn"
      type="warning"
      :closable="false"
      class="tip"
      title="内置角色不可修改：admin 始终拥有全部权限"
    />
    <div v-loading="permissionLoading" class="permission-body">
      <div v-for="group in permissionGroups" :key="group.module" class="perm-group">
        <div class="perm-group-header">
          <span class="module-name">{{ group.moduleName }}</span>
          <el-button
            link
            type="primary"
            :disabled="permissionRole?.builtIn"
            @click="toggleGroup(group)"
          >
            {{ isGroupSelected(group) ? '取消全选' : '全选' }}
          </el-button>
        </div>
        <el-checkbox-group v-model="selectedCodes" :disabled="permissionRole?.builtIn">
          <el-checkbox v-for="perm in group.permissions" :key="perm.permCode" :value="perm.permCode">
            {{ perm.permName }}
            <span class="perm-code">{{ perm.permCode }}</span>
          </el-checkbox>
        </el-checkbox-group>
      </div>
    </div>
    <template #footer>
      <el-button @click="permissionVisible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="saving"
        :disabled="permissionRole?.builtIn"
        @click="savePermissions"
      >
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  adminRolesList,
  adminRoleCreate,
  adminRoleUpdate,
  adminRoleDelete,
  adminPermissionsList,
  adminRolePermissionsUpdate
} from '../../api'

const roles = ref([])
const permissionGroups = ref([])
const loading = ref(false)
const saving = ref(false)
const permissionLoading = ref(false)

const createVisible = ref(false)
const editVisible = ref(false)
const permissionVisible = ref(false)
const createFormRef = ref()
const editFormRef = ref()

const createForm = reactive({ roleCode: '', roleName: '', description: '' })
const editForm = reactive({ roleCode: '', roleName: '', description: '' })
const permissionRole = ref(null)
const selectedCodes = ref([])

const CODE_PATTERN = /^[a-z][a-z0-9_]{1,31}$/
const createRules = {
  roleCode: [
    { required: true, message: '请输入角色编码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (!CODE_PATTERN.test((value || '').trim())) {
          callback(new Error('需 2-32 位，小写字母开头，仅含小写字母、数字、下划线'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ],
  roleName: [{ required: true, message: '请输入角色名称', trigger: 'blur' }]
}
const editRules = {
  roleName: [{ required: true, message: '请输入角色名称', trigger: 'blur' }]
}

const load = async () => {
  loading.value = true
  try {
    const res = await adminRolesList()
    roles.value = res.data || []
  } finally {
    loading.value = false
  }
}

const loadPermissions = async () => {
  if (permissionGroups.value.length) return
  const res = await adminPermissionsList()
  permissionGroups.value = res.data || []
}

const openCreate = () => {
  createForm.roleCode = ''
  createForm.roleName = ''
  createForm.description = ''
  createVisible.value = true
}

const submitCreate = async () => {
  await createFormRef.value.validate()
  saving.value = true
  try {
    await adminRoleCreate({
      roleCode: createForm.roleCode.trim(),
      roleName: createForm.roleName.trim(),
      description: createForm.description
    })
    ElMessage.success('角色创建成功')
    createVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

const openEdit = (row) => {
  editForm.roleCode = row.roleCode
  editForm.roleName = row.roleName
  editForm.description = row.description || ''
  editVisible.value = true
}

const submitEdit = async () => {
  await editFormRef.value.validate()
  saving.value = true
  try {
    await adminRoleUpdate(editForm.roleCode, {
      roleName: editForm.roleName.trim(),
      description: editForm.description
    })
    ElMessage.success('角色已更新')
    editVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

const removeRole = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确认删除角色「${row.roleName}」（${row.roleCode}）？仍有用户绑定时会被拒绝。`,
      '删除角色',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  await adminRoleDelete(row.roleCode)
  ElMessage.success('角色已删除')
  await load()
}

const openPermissions = async (row) => {
  permissionRole.value = row
  selectedCodes.value = [...(row.permCodes || [])]
  permissionVisible.value = true
  permissionLoading.value = true
  try {
    await loadPermissions()
  } finally {
    permissionLoading.value = false
  }
}

const groupCodes = (group) => (group.permissions || []).map((item) => item.permCode)

const isGroupSelected = (group) => {
  const codes = groupCodes(group)
  return codes.length > 0 && codes.every((code) => selectedCodes.value.includes(code))
}

const toggleGroup = (group) => {
  const codes = groupCodes(group)
  if (isGroupSelected(group)) {
    selectedCodes.value = selectedCodes.value.filter((code) => !codes.includes(code))
  } else {
    const merged = new Set([...selectedCodes.value, ...codes])
    selectedCodes.value = [...merged]
  }
}

const savePermissions = async () => {
  if (!permissionRole.value) return
  saving.value = true
  try {
    await adminRolePermissionsUpdate(permissionRole.value.roleCode, selectedCodes.value)
    ElMessage.success('权限已保存，已登录用户下一次请求即生效')
    permissionVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped lang="scss">
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.tip {
  margin-bottom: 16px;
}
.role-code {
  font-family: monospace;
}
.builtin-tag {
  margin-left: 8px;
}
.permission-body {
  max-height: 55vh;
  overflow-y: auto;
}
.perm-group {
  border-bottom: 1px solid #ebeef5;
  padding: 8px 0 14px;
  &:last-child {
    border-bottom: none;
  }
}
.perm-group-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.module-name {
  font-weight: 600;
  color: #303133;
}
.perm-code {
  color: #909399;
  font-size: 12px;
  margin-left: 4px;
}
</style>
