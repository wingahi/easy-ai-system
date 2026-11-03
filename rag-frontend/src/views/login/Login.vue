<template>
  <div class="login-container">
    <div class="login-box">
      <div class="login-header">
        <h1>WGH RAG System</h1>
        <p>企业级知识库系统</p>
      </div>
      <el-form :model="form" :rules="rules" ref="formRef" class="login-form" @submit.prevent>
        <el-form-item prop="token">
          <el-input
            v-model="form.token"
            type="textarea"
            :rows="4"
            placeholder="粘贴外部 IdP 签发的 Bearer token"
            size="large"
          />
        </el-form-item>
        <el-button type="primary" size="large" :loading="loading" class="login-btn" @click="doLogin">
          验 证 并 进 入
        </el-button>
      </el-form>
      <div class="login-tips">
        <p>本系统不签发 token，仅校验外部 OIDC IdP 签发的 token。</p>
        <p>宿主系统可调用 localStorage.setItem('wgh_token', token) 或访问 /login?token=xxx 注入。</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getMe } from '../../api'
import { useUserStore } from '../../stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const formRef = ref()
const loading = ref(false)

const queryToken = typeof route.query.token === 'string' ? route.query.token : ''
const form = reactive({ token: queryToken })
const rules = {
  token: [{ required: true, message: '请输入外部 IdP 签发的 token' }]
}

const doLogin = async () => {
  await formRef.value.validate()
  loading.value = true
  try {
    // 只接受外部 token，不向本系统登录换取 token
    userStore.setToken(form.token)
    const res = await getMe()
    userStore.hydrate(res.data)
    ElMessage.success('验证成功')
    // 回跳到来源页面（如分享页），无 redirect 时回到首页
    const redirect = route.query.redirect
    router.push(typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/')
  } catch (e) {
    userStore.logout()
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
  width: 460px;
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
  :deep(.el-textarea__inner) { font-family: monospace; font-size: 12px; }
  .login-btn { width: 100%; margin-top: 10px; }
}
.login-tips {
  text-align: center;
  margin-top: 20px;
  p { color: #909399; font-size: 12px; margin: 4px 0; line-height: 1.6; }
}
</style>
