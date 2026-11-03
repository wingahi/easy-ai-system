<template>
  <div class="login-container">
    <div class="login-box">
      <div class="login-header">
        <h1>WGH RAG System</h1>
        <p>企业级知识库系统</p>
      </div>

      <el-form :model="form" :rules="rules" ref="formRef" class="login-form" @submit.prevent>
        <el-alert type="info" :closable="false" class="login-alert"
                  title="管理员登录 / 体验账号登录" description="本地管理员与体验账号使用账号密码登录" />
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名（管理员或体验账号）" size="large" clearable />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" show-password
                    placeholder="密码" size="large" @keyup.enter="doAccountLogin" />
        </el-form-item>
        <el-button type="primary" size="large" :loading="loading" class="login-btn" @click="doAccountLogin">
          登 录
        </el-button>
      </el-form>

      <el-divider>或</el-divider>

      <el-collapse class="token-panel">
        <el-collapse-item title="使用外部 OIDC Token 登录（宿主系统用户）" name="token">
          <el-input v-model="form.token" type="textarea" :rows="4"
                    placeholder="粘贴外部 IdP 签发的 Bearer token" />
          <el-button type="primary" plain size="large" :loading="tokenLoading"
                     class="login-btn" @click="doTokenLogin">验 证 并 进 入</el-button>
        </el-collapse-item>
      </el-collapse>

      <div class="login-tips">
        <p>外部 OIDC 用户只能通过 IdP token 进入，不支持账号密码登录。</p>
        <p>宿主系统可调用 localStorage.setItem('wgh_token', token) 或访问 /login?token=xxx 注入。</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login, getMe } from '../../api'
import { useUserStore } from '../../stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const formRef = ref()
const loading = ref(false)
const tokenLoading = ref(false)

const queryToken = typeof route.query.token === 'string' ? route.query.token : ''
const form = reactive({ username: '', password: '', token: queryToken })
const rules = {
  username: [{ required: true, message: '请输入用户名' }],
  password: [{ required: true, message: '请输入密码' }]
}

const redirectTarget = () => {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/'
}

// 管理员 / 体验账号：账号密码登录，换取内部会话凭证
const doAccountLogin = async () => {
  await formRef.value.validate()
  loading.value = true
  try {
    const res = await login({ username: form.username.trim(), password: form.password })
    userStore.setToken(res.data.token)
    userStore.hydrate(res.data.user)
    if (res.data.passwordMustChange) {
      ElMessage.warning('首次登录请先修改密码')
      router.push({ path: '/change-password', query: { redirect: redirectTarget() } })
      return
    }
    ElMessage.success(res.data.user?.username ? `欢迎，${res.data.user.username}` : '登录成功')
    router.push(redirectTarget())
  } catch (e) {
    userStore.logout()
  } finally {
    loading.value = false
  }
}

// 外部 OIDC 用户：注入 IdP token 后校验 /auth/me
const doTokenLogin = async () => {
  if (!form.token) {
    ElMessage.warning('请粘贴外部 IdP 签发的 token')
    return
  }
  tokenLoading.value = true
  try {
    userStore.setToken(form.token)
    const res = await getMe()
    userStore.hydrate(res.data)
    ElMessage.success('验证成功')
    router.push(redirectTarget())
  } catch (e) {
    userStore.logout()
  } finally {
    tokenLoading.value = false
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
  margin-bottom: 24px;
  h1 { font-size: 24px; margin: 0 0 8px; color: #303133; }
  p { margin: 0; color: #909399; font-size: 14px; }
}
.login-form {
  :deep(.el-textarea__inner) { font-family: monospace; font-size: 12px; }
  .login-alert { margin-bottom: 16px; }
  .login-btn { width: 100%; margin-top: 6px; }
}
.token-panel {
  :deep(.el-collapse-item__header) { font-size: 13px; color: #606266; }
  :deep(.el-collapse-item__content) { padding-bottom: 8px; }
}
.login-tips {
  text-align: center;
  margin-top: 16px;
  p { color: #909399; font-size: 12px; margin: 4px 0; line-height: 1.6; }
}
</style>
