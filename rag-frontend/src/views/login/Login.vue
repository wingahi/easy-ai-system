<template>
  <div class="login-container">
    <div class="login-box">
      <div class="login-header">
        <h1>WGH RAG System</h1>
        <p>企业级知识库系统</p>
      </div>
      <el-form :model="form" :rules="rules" ref="formRef" class="login-form" @submit.prevent>
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" size="large" :prefix-icon="UserIcon" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码" size="large" 
                    :prefix-icon="LockIcon" show-password />
        </el-form-item>
        <el-button type="primary" size="large" :loading="loading" class="login-btn" @click="doLogin">
          登 录
        </el-button>
      </el-form>
      <div class="login-tips">
        <p>默认账号: admin / admin</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User as UserIcon, Lock as LockIcon } from '@element-plus/icons-vue'
import { login } from '../../api'
import { useUserStore } from '../../stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const formRef = ref()
const loading = ref(false)

const form = reactive({ username: 'admin', password: 'admin' })
const rules = {
  username: [{ required: true, message: '请输入用户名' }],
  password: [{ required: true, message: '请输入密码' }]
}

const doLogin = async () => {
  await formRef.value.validate()
  loading.value = true
  try {
    const res = await login(form)
    userStore.login(res.data)
    ElMessage.success('登录成功')
    // 登录后回跳到来源页面（如分享页），无 redirect 时回到首页
    const redirect = route.query.redirect
    router.push(typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/')
  } catch (e) {
    // 错误已拦截器处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped lang="scss">
.login-container {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
.login-box {
  width: 400px;
  background: #fff;
  border-radius: 12px;
  padding: 40px;
  box-shadow: 0 20px 60px rgba(0,0,0,0.15);
}
.login-header {
  text-align: center;
  margin-bottom: 30px;
  h1 { font-size: 24px; margin: 0 0 8px; color: #303133; }
  p { margin: 0; color: #909399; font-size: 14px; }
}
.login-form {
  .login-btn { width: 100%; margin-top: 10px; }
}
.login-tips {
  text-align: center;
  margin-top: 20px;
  p { color: #c0c4cc; font-size: 12px; margin: 0; }
}
</style>
