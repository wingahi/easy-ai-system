import { defineStore } from 'pinia'
import { STORAGE_KEYS } from '../utils/constants'

/**
 * 当前用户状态。
 * 双认证并存：
 * - 本地管理员/体验账号通过 /auth/login 换取内部会话凭证（HS256，后端校验）；
 * - 外部 OIDC 用户由宿主系统注入 token（localStorage.wgh_token 或 /login?token=），后端按 JWKS 验签。
 * 两种凭证统一存放在 wgh_token，随后调用 /auth/me 拉取角色与权限。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem(STORAGE_KEYS.TOKEN) || '',
    username: localStorage.getItem(STORAGE_KEYS.USERNAME) || '',
    userId: localStorage.getItem(STORAGE_KEYS.USER_ID) || '',
    roles: JSON.parse(localStorage.getItem(STORAGE_KEYS.ROLES) || '[]'),
    // 权限码（后端 /auth/me 返回）；按钮级控制使用 hasPerm()
    permissions: JSON.parse(localStorage.getItem(STORAGE_KEYS.PERMISSIONS) || '[]'),
    // 本地账号被要求先改密（刷新后沿用上次登录返回的标记）
    passwordMustChange: localStorage.getItem(STORAGE_KEYS.PWD_MUST_CHANGE) === '1',
    // 是否已用当前 token 拉取过 /auth/me（页面刷新后需要重新 hydrate）
    hydrated: false
  }),

  getters: {
    isLoggedIn: (state) => !!state.token,
    // 管理入口按后端返回的角色判断（后端接口另有数据库角色/权限校验）
    isAdmin: (state) => Array.isArray(state.roles) && state.roles.includes('admin'),
    // 权限码判断：admin 视为拥有全部权限（与后端 role=admin 的授权口径一致）
    hasPerm: (state) => (code) => {
      if (!code) return true
      if (Array.isArray(state.roles) && state.roles.includes('admin')) return true
      return Array.isArray(state.permissions) && state.permissions.includes(code)
    }
  },

  actions: {
    /** 接收外部 token（可带 "Bearer " 前缀），仅落地到本地，不向本系统换取 token */
    setToken(token) {
      const normalized = (token || '').trim().replace(/^Bearer\s+/i, '')
      this.token = normalized
      if (normalized) {
        localStorage.setItem(STORAGE_KEYS.TOKEN, normalized)
      } else {
        localStorage.removeItem(STORAGE_KEYS.TOKEN)
      }
    },

    /** 使用 /auth/me 返回的 roles/permissions 填充用户信息 */
    hydrate(data) {
      this.username = data.username || ''
      this.userId = data.userId ?? ''
      this.roles = Array.isArray(data.roles) ? data.roles : []
      this.permissions = Array.isArray(data.permissions) ? data.permissions : []
      this.passwordMustChange = data.passwordMustChange === true
      this.hydrated = true
      localStorage.setItem(STORAGE_KEYS.USERNAME, this.username)
      localStorage.setItem(STORAGE_KEYS.USER_ID, String(this.userId))
      localStorage.setItem(STORAGE_KEYS.ROLES, JSON.stringify(this.roles))
      localStorage.setItem(STORAGE_KEYS.PERMISSIONS, JSON.stringify(this.permissions))
      localStorage.setItem(STORAGE_KEYS.PWD_MUST_CHANGE, this.passwordMustChange ? '1' : '0')
    },

    logout() {
      this.token = ''
      this.username = ''
      this.userId = ''
      this.roles = []
      this.permissions = []
      this.passwordMustChange = false
      this.hydrated = false
      localStorage.removeItem(STORAGE_KEYS.TOKEN)
      localStorage.removeItem(STORAGE_KEYS.USERNAME)
      localStorage.removeItem(STORAGE_KEYS.USER_ID)
      localStorage.removeItem(STORAGE_KEYS.ROLES)
      localStorage.removeItem(STORAGE_KEYS.PERMISSIONS)
      localStorage.removeItem(STORAGE_KEYS.PWD_MUST_CHANGE)
    }
  }
})
