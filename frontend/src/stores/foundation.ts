import { defineStore } from 'pinia'

type HealthStatus = 'idle' | 'loading' | 'up' | 'unavailable'

export const useFoundationStore = defineStore('foundation', {
  state: () => ({ health: 'idle' as HealthStatus }),
  actions: {
    async checkHealth() {
      this.health = 'loading'
      try {
        const response = await fetch('/actuator/health/readiness', { signal: AbortSignal.timeout(5000) })
        const body: unknown = await response.json()
        this.health = response.ok && typeof body === 'object' && body !== null && 'status' in body && body.status === 'UP'
          ? 'up' : 'unavailable'
      } catch {
        this.health = 'unavailable'
      }
    },
  },
})
