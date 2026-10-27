import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({
  baseURL: '/api',
  timeout: 120000
})

// 请求拦截器 - 添加 JWT Token
request.interceptors.request.use(config => {
  const token = localStorage.getItem('wgh_token')
  if (token && !config.url.startsWith('/auth/login') && !config.url.startsWith('/share')) {
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
      
      // Token 过期
      if (res.code === 1001 || res.code === 1002) {
        localStorage.removeItem('wgh_token')
        router.push('/login')
      }
      return Promise.reject(new Error(res.message))
    }
    return res
  },
  error => {
    if (error.response?.status === 401) {
      localStorage.removeItem('wgh_token')
      router.push('/login')
    }
    ElMessage.error(error.message || '网络错误')
    return Promise.reject(error)
  }
)

export default request
