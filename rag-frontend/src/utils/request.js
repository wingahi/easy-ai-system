import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { useUserStore } from '../stores/user'

const request = axios.create({
  baseURL: '/api',
  timeout: 120000
})

// 请求拦截器 - 携带外部 IdP 签发的 Bearer token
request.interceptors.request.use(config => {
  const token = localStorage.getItem('wgh_token')
  if (token && !config.url.startsWith('/share')) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  return config
})

// 响应拦截器
request.interceptors.response.use(
  response => {
    const res = response.data
    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      
      // 外部 token 缺失/过期/无效
      if (res.code === 1001 || res.code === 1002) {
        useUserStore().logout()
        router.push('/login')
      }
      return Promise.reject(new Error(res.message))
    }
    return res
  },
  error => {
    if (error.response?.status === 401) {
      useUserStore().logout()
      router.push('/login')
    }
    ElMessage.error(error.message || '网络错误')
    return Promise.reject(error)
  }
)

export default request
