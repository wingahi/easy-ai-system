<template>
  <div class="change-password">
    <el-card class="change-card">
      <template #header>
        <div class="header">
          <h2>修改密码</h2>
          <p v-if="mustChange" class="tip">当前账号需要先修改密码才能继续使用其他功能</p>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px" @submit.prevent>
        <el-form-item label="当前密码" prop="oldPassword">
          <el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入当前密码" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" show-password placeholder="至少 8 位，包含字母与数字" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" show-password placeholder="再次输入新密码" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="submit">提交</el-button>
          <el-button @click="cancel">返回</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { changePassword, getMe } from '../../api'
import { useUserStore } from '../../stores/user'
import { STORAGE_KEYS } from '../../utils/constants'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const formRef = ref()
const loading = ref(false)
const mustChange = computed(() => userStore.passwordMustChange)

const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const rules = {
  oldPassword: [{ required: true, message: '请输入当前密码' }],
  newPassword: [
    { required: true, message: '请输入新密码' },
    {
      validator: (_rule, value, callback) => {
        if (!value) return callback()
        if (value.length < 8) return callback(new Error('密码长度至少 8 位'))
        if (!/[A-Za-z]/.test(value) || !/\d/.test(value)) {
          return callback(new Error('密码必须包含字母与数字'))
        }
        callback()
      }
    }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码' },
    {
      validator: (_rule, value, callback) => {
        if (value !== form.newPassword) return callback(new Error('两次输入的密码不一致'))
        callback()
      }
    }
  ]
}

const submit = async () => {
  await formRef.value.validate()
  loading.value = true
  try {
    await changePassword({ oldPassword: form.oldPassword, newPassword: form.newPassword })
    ElMessage.success('密码修改成功')
    userStore.passwordMustChange = false
    localStorage.setItem(STORAGE_KEYS.PWD_MUST_CHANGE, '0')
    // 重新拉取用户信息，确保权限/标记与后端一致
    const res = await getMe()
    userStore.hydrate(res.data)
    const redirect = route.query.redirect
    router.push(typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/')
  } catch (e) {
    // 错误提示由 request.js 统一处理
  } finally {
    loading.value = false
  }
}

const cancel = () => {
  if (mustChange.value) {
    ElMessage.warning('请先完成密码修改')
    return
  }
  router.push('/')
}
</script>

<style scoped>
.change-password {
  display: flex;
  justify-content: center;
  padding-top: 8vh;
  min-height: 100vh;
  background: #f5f7fa;
}
.change-card { width: 460px; }
.header {
  text-align: center;
  h2 { margin: 0 0 6px; font-size: 20px; color: #303133; }
  .tip { margin: 0; font-size: 12px; color: #e6a23c; }
}
</style>
