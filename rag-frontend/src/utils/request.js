import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { useUserStore } from '../stores/user'

const request = axios.create({
  baseURL: '/api',
  timeout: 120000
})

// 请求拦截器 - 携带 Bearer 凭证（本地登录的会话凭证或外部 IdP token，统一存 wgh_token）
request.interceptors.request.use(config => {
  const token = localStorage.getItem('wgh_token')
  if (token && !config.url.startsWith('/share')) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  return config
})

/**
 * 统一处理鉴权类错误码：
 * - 1006：需要先改密 → 跳改密页（不清凭证）
 * - 1001/1002/1007：凭证缺失/过期/已失效 → 清理并提示重新登录
 */
const handleAuthCode = (code, message) => {
  const userStore = useUserStore()
  if (code === 1006) {
    ElMessage.warning(message || '请先修改密码')
    if (router.currentRoute.value.path !== '/change-password') {
      router.push({ path: '/change-password', query: { redirect: router.currentRoute.value.fullPath } })
    }
    return true
  }
  if (code === 1001 || code === 1002 || code === 1007) {
    userStore.logout()
    ElMessage.warning(code === 1002 ? (message || '登录已过期，请重新登录') : (message || '请先登录'))
    if (router.currentRoute.value.path !== '/login') {
      router.push('/login')
    }
    return true
  }
  return false
}

// 响应拦截器
request.interceptors.response.use(
  response => {
    const res = response.data
    if (res.code !== 200) {
      if (!handleAuthCode(res.code, res.message)) {
        ElMessage.error(res.message || '请求失败')
      }
      return Promise.reject(new Error(res.message))
    }
    return res
  },
  error => {
    const code = error.response?.data?.code
    const message = error.response?.data?.message
    if (!handleAuthCode(code, message)) {
      if (error.response?.status === 401 || error.response?.status === 403) {
        ElMessage.error(message || '无权访问')
      } else {
        ElMessage.error(message || error.message || '网络错误')
      }
    }
    return Promise.reject(error)
  }
)

export default request
