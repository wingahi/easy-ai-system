import { defineStore } from 'pinia'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('wgh_token') || '',
    username: localStorage.getItem('wgh_username') || '',
    userId: localStorage.getItem('wgh_userId') || ''
  }),
  
  getters: {
    isLoggedIn: (state) => !!state.token
  },
  
  actions: {
    login(data) {
      this.token = data.token
      this.username = data.username
      this.userId = data.userId
      localStorage.setItem('wgh_token', data.token)
      localStorage.setItem('wgh_username', data.username)
      localStorage.setItem('wgh_userId', data.userId)
    },
    
    logout() {
      this.token = ''
      this.username = ''
      this.userId = ''
      localStorage.removeItem('wgh_token')
      localStorage.removeItem('wgh_username')
      localStorage.removeItem('wgh_userId')
    }
  }
})
