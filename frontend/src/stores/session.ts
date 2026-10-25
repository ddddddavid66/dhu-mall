import { defineStore } from 'pinia'
import { callOperation } from '../api/client'
import { clearCredentials, getCsrfToken, setCredentials } from '../api/auth-state'
import type { AuthView, UserView } from '../api/models'

export const useSessionStore = defineStore('session', {
  state: () => ({
    user: null as UserView | null,
    initialized: false,
    initialization: null as Promise<void> | null,
    busy: false,
    message: '',
  }),
  getters: {
    isSignedIn: state => state.user?.status === 'ACTIVE',
    hasPermission: state => (permission: string) => state.user?.permissions.includes(permission) ?? false,
  },
  actions: {
    applyAuth(auth: AuthView) {
      setCredentials(auth)
      this.user = auth.user
      this.message = ''
    },
    async initialize() {
      if (this.initialized) return
      if (this.initialization) return this.initialization
      this.initialization = (async () => {
        const csrfToken = getCsrfToken()
        if (!csrfToken) { this.initialized = true; return }
        try {
          const auth = await callOperation<AuthView>('API-03', { auth: false })
          this.applyAuth(auth)
        } catch {
          clearCredentials()
          this.user = null
        } finally {
          this.initialized = true
          this.initialization = null
        }
      })()
      return this.initialization
    },
    async login(username: string, password: string) {
      this.busy = true
      this.message = ''
      clearCredentials()
      this.user = null
      try {
        this.applyAuth(await callOperation<AuthView>('API-02', { body: { username, password }, auth: false }))
        this.initialized = true
      } finally { this.busy = false }
    },
    async register(input: { username: string; password: string; nickname: string }) {
      this.busy = true
      this.message = ''
      try { return await callOperation<UserView>('API-01', { body: input, auth: false }) }
      finally { this.busy = false }
    },
    async logout() {
      this.busy = true
      try {
        await callOperation<{ success: true }>('API-04')
        clearCredentials()
        this.user = null
      } finally { this.busy = false }
    },
    async changePassword(oldPassword: string, newPassword: string) {
      this.busy = true
      try {
        await callOperation<{ success: true }>('API-06', { body: { oldPassword, newPassword } })
        clearCredentials()
        this.user = null
      } finally { this.busy = false }
    },
    expire() {
      clearCredentials()
      this.user = null
      this.initialized = true
      this.message = '登录已过期，请重新登录。'
    },
  },
})
