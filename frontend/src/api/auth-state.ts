import type { AuthView } from './models'

const csrfStorageKey = 'yueji.csrf.v1'
let accessToken: string | null = null
let csrfToken = readCsrfToken()

function readCsrfToken(): string | null {
  try { return sessionStorage.getItem(csrfStorageKey) } catch { return null }
}

export function getAccessToken() { return accessToken }
export function getCsrfToken() { return csrfToken ?? readCsrfToken() }

export function setCredentials(auth: Pick<AuthView, 'accessToken' | 'csrfToken'>) {
  // 访问 JWT 只留在内存；CSRF 值单独存于当前标签页，以便刷新后配合 HttpOnly Cookie 恢复会话。
  accessToken = auth.accessToken
  csrfToken = auth.csrfToken
  try { sessionStorage.setItem(csrfStorageKey, auth.csrfToken) } catch { /* 存储不可用时仍可在当前页面使用 */ }
}

export function clearCredentials() {
  accessToken = null
  csrfToken = null
  try { sessionStorage.removeItem(csrfStorageKey) } catch { /* 无可用存储 */ }
}

export function newSecureKey(): string {
  if (typeof crypto === 'undefined' || !crypto.randomUUID) throw new Error('当前浏览器无法生成安全随机幂等键')
  return crypto.randomUUID()
}

export function getStoredIdempotencyKey(operationId: string, scope: string): string {
  const storageKey = `yueji.idempotency.v1:${operationId}:${scope}`
  try {
    const existing = sessionStorage.getItem(storageKey)
    if (existing) return existing
    const created = newSecureKey()
    sessionStorage.setItem(storageKey, created)
    return created
  } catch {
    return newSecureKey()
  }
}

export function clearStoredIdempotencyKey(operationId: string, scope: string) {
  try { sessionStorage.removeItem(`yueji.idempotency.v1:${operationId}:${scope}`) } catch { /* 忽略不可用存储 */ }
}

export function publishSessionExpired() {
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent('yueji:session-expired'))
}
