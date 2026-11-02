import { defineStore } from 'pinia'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('wgh_token') || '',
    username: localStorage.getItem('wgh_username') || '',
    userId: localStorage.getItem('wgh_userId') || '',
    roles: JSON.parse(localStorage.getItem('wgh_roles') || '[]')
  }),

  getters: {
    isLoggedIn: (state) => !!state.token,
    // 前端权限体系尚未完善：暂按登录角色（含 admin）控制管理入口，后端接口仍有 admin 兜底校验
    isAdmin: (state) => Array.isArray(state.roles) && state.roles.includes('admin')
  },

  actions: {
    login(data) {
      this.token = data.token
      this.username = data.username
      this.userId = data.userId
      this.roles = data.roles || []
      localStorage.setItem('wgh_token', data.token)
      localStorage.setItem('wgh_username', data.username)
      localStorage.setItem('wgh_userId', data.userId)
      localStorage.setItem('wgh_roles', JSON.stringify(this.roles))
    },

    logout() {
      this.token = ''
      this.username = ''
      this.userId = ''
      this.roles = []
      localStorage.removeItem('wgh_token')
      localStorage.removeItem('wgh_username')
      localStorage.removeItem('wgh_userId')
      localStorage.removeItem('wgh_roles')
    }
  }
})
