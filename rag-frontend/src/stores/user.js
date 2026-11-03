import { defineStore } from 'pinia'

/**
 * 当前用户状态。
 * 本系统不签发 token：token 由外部 OIDC IdP 签发，通过宿主系统注入
 * localStorage.wgh_token（或登录页粘贴 / ?token= 参数），随后调用 /auth/me 拉取角色与权限。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('wgh_token') || '',
    username: localStorage.getItem('wgh_username') || '',
    userId: localStorage.getItem('wgh_userId') || '',
    roles: JSON.parse(localStorage.getItem('wgh_roles') || '[]'),
    // 是否已用当前 token 拉取过 /auth/me（页面刷新后需要重新 hydrate）
    hydrated: false
  }),

  getters: {
    isLoggedIn: (state) => !!state.token,
    // 管理入口按后端返回的角色判断（后端接口另有数据库角色/权限校验）
    isAdmin: (state) => Array.isArray(state.roles) && state.roles.includes('admin')
  },

  actions: {
    /** 接收外部 token（可带 "Bearer " 前缀），仅落地到本地，不向本系统换取 token */
    setToken(token) {
      const normalized = (token || '').trim().replace(/^Bearer\s+/i, '')
      this.token = normalized
      if (normalized) {
        localStorage.setItem('wgh_token', normalized)
      } else {
        localStorage.removeItem('wgh_token')
      }
    },

    /** 使用 /auth/me 返回的 roles/permissions 填充用户信息 */
    hydrate(data) {
      this.username = data.username || ''
      this.userId = data.userId ?? ''
      this.roles = Array.isArray(data.roles) ? data.roles : []
      this.hydrated = true
      localStorage.setItem('wgh_username', this.username)
      localStorage.setItem('wgh_userId', String(this.userId))
      localStorage.setItem('wgh_roles', JSON.stringify(this.roles))
    },

    logout() {
      this.token = ''
      this.username = ''
      this.userId = ''
      this.roles = []
      this.hydrated = false
      localStorage.removeItem('wgh_token')
      localStorage.removeItem('wgh_username')
      localStorage.removeItem('wgh_userId')
      localStorage.removeItem('wgh_roles')
    }
  }
})
