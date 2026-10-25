import { API_OPERATIONS, ERROR_CODES, type ApiOperationId } from './generated'
import { clearCredentials, clearStoredIdempotencyKey, getAccessToken, getCsrfToken, getStoredIdempotencyKey, setCredentials } from './auth-state'
import type { AuthView } from './models'
import type { FailureResult, Result } from './types'

export class ApiError extends Error {
  constructor(readonly status: number, readonly result: FailureResult) {
    super(result.message)
    this.name = 'ApiError'
  }
}

type FetchOptions = {
  idempotencyKey?: string
  skipAuthRefresh?: boolean
  auth?: boolean
  shopScoped?: boolean
  timeoutMs?: number
}

let refreshFlight: Promise<void> | null = null
const shopControllers = new Set<AbortController>()

export function cancelShopRequests() {
  // 切店后中止旧作用域请求，避免旧响应覆盖新店数据。
  for (const controller of shopControllers) controller.abort()
  shopControllers.clear()
}

function isSafeToRetry(method: string, key?: string) {
  return ['GET', 'HEAD', 'OPTIONS'].includes(method.toUpperCase()) || Boolean(key)
}

function combineSignals(signals: Array<AbortSignal | undefined>) {
  const active = signals.filter((signal): signal is AbortSignal => Boolean(signal))
  if (active.length === 1) return active[0]
  return active.length ? AbortSignal.any(active) : undefined
}

function makeTraceId() {
  return typeof crypto !== 'undefined' && crypto.randomUUID
    ? crypto.randomUUID()
    : `trace-${Date.now()}-${Math.random().toString(36).slice(2)}`
}

async function renewSession() {
  const csrfToken = getCsrfToken()
  if (!csrfToken) throw new Error('当前标签页没有刷新会话校验值')
  const headers = new Headers({ Accept: 'application/json', 'X-CSRF-Token': csrfToken, 'X-Request-Id': makeTraceId() })
  const response = await authorizedFetch('/api/v1/auth/refresh', { method: 'POST', headers }, { auth: false, skipAuthRefresh: true, timeoutMs: 8000 })
  const result: Result<AuthView> = await response.json()
  if (!response.ok || result.code !== 'OK') throw new Error(result.message || '会话刷新失败')
  setCredentials(result.data)
}

async function refreshOnce() {
  if (!refreshFlight) {
    refreshFlight = renewSession().catch((error: unknown) => {
      clearCredentials()
      window.dispatchEvent(new CustomEvent('yueji:session-expired'))
      throw error
    }).finally(() => { refreshFlight = null })
  }
  return refreshFlight
}

export async function authorizedFetch(path: string, init: RequestInit = {}, options: FetchOptions = {}): Promise<Response> {
  if (!path.startsWith('/api/v1/')) throw new TypeError('业务 API 路径必须以 /api/v1/ 开头')
  const method = (init.method ?? 'GET').toUpperCase()
  const baseHeaders = new Headers(init.headers)
  baseHeaders.set('Accept', 'application/json')
  if (!baseHeaders.has('X-Request-Id')) baseHeaders.set('X-Request-Id', makeTraceId())
  if (options.idempotencyKey) baseHeaders.set('Idempotency-Key', options.idempotencyKey)
  if (path === '/api/v1/auth/refresh' || path === '/api/v1/auth/logout') {
    const csrfToken = getCsrfToken()
    if (csrfToken) baseHeaders.set('X-CSRF-Token', csrfToken)
  }

  const shopController = options.shopScoped ? new AbortController() : null
  if (shopController) shopControllers.add(shopController)
  const baseSignal = combineSignals([init.signal ?? undefined, shopController?.signal])
  const canRetry = isSafeToRetry(method, options.idempotencyKey)
  const timeoutMs = options.timeoutMs ?? 15000

  const send = async (attempt: number, authRetried = false): Promise<Response> => {
    const headers = new Headers(baseHeaders)
    const token = options.auth === false ? null : getAccessToken()
    if (token && !headers.has('Authorization')) headers.set('Authorization', `Bearer ${token}`)
    const timeoutSignal = AbortSignal.timeout(timeoutMs)
    const signal = combineSignals([baseSignal, timeoutSignal])
    let refreshFailed = false
    try {
      const response = await fetch(path, { ...init, method, headers, signal, credentials: 'same-origin' })
      if (response.status === 401 && !options.skipAuthRefresh && token && !authRetried && getCsrfToken()) {
        await response.body?.cancel().catch(() => undefined)
        try { await refreshOnce() } catch (error) { refreshFailed = true; throw error }
        // 只有安全读取或带幂等键的写请求才会在刷新后重放。
        if (isSafeToRetry(method, options.idempotencyKey)) return send(attempt, true)
      } else if (response.status === 401 && token && (authRetried || !getCsrfToken())) {
        clearCredentials()
        window.dispatchEvent(new CustomEvent('yueji:session-expired'))
      }
      return response
    } catch (error) {
      const callerAborted = Boolean(baseSignal?.aborted)
      const retryableTransport = error instanceof TypeError || (error instanceof DOMException && error.name === 'TimeoutError')
      if (attempt === 0 && canRetry && !callerAborted && !refreshFailed && retryableTransport) return send(1, authRetried)
      throw error
    } finally {
      if (shopController) shopControllers.delete(shopController)
    }
  }

  return send(0)
}

export async function request<T>(path: string, init: RequestInit = {}, options: FetchOptions = {}): Promise<T> {
  const response = await authorizedFetch(path, init, options)
  let result: Result<T>
  try { result = await response.json() as Result<T> } catch { throw new TypeError('响应不是有效的 JSON Result') }
  if (typeof result?.code !== 'string' || typeof result?.traceId !== 'string' || !('data' in result)) {
    throw new TypeError('响应不符合 Result 契约')
  }
  if (!response.ok || result.code !== 'OK') {
    if (result.code === 'OK' || !ERROR_CODES.includes(result.code)) throw new TypeError('HTTP 状态与 Result 不一致')
    throw new ApiError(response.status, result as FailureResult)
  }
  return result.data
}

export type OperationArgs = {
  path?: Record<string, string>
  query?: Record<string, string | number | boolean | null | undefined>
  body?: unknown
  signal?: AbortSignal
  shopScoped?: boolean
  idempotencyScope?: string
  auth?: boolean
}

export async function callOperation<T>(operationId: ApiOperationId, args: OperationArgs = {}): Promise<T> {
  const operation = API_OPERATIONS[operationId]
  if (operation.status !== 'IMPLEMENTED' && operationId !== 'API-89') {
    throw new Error(`${operationId} 当前未实现，前端不会把占位接口当作成功`)
  }
  if (operation.requiresVersion && (!args.body || typeof args.body !== 'object' || !Number.isInteger((args.body as Record<string, unknown>).version))) {
    throw new TypeError(`${operationId} 必须携带契约要求的 version`)
  }

  let path = operation.path.replace(/\{([^}]+)\}/g, (_match, name: string) => {
    const value = args.path?.[name]
    if (!value) throw new TypeError(`${operationId} 缺少路径参数 ${name}`)
    return encodeURIComponent(value)
  })
  const query = new URLSearchParams()
  for (const [name, value] of Object.entries(args.query ?? {})) {
    if (value !== undefined && value !== null && value !== '') query.set(name, String(value))
  }
  const queryString = query.toString()
  if (queryString) path += `?${queryString}`

  let idempotencyKey: string | undefined
  if (operation.requiresIdempotencyKey) {
    if (!args.idempotencyScope) throw new TypeError(`${operationId} 必须提供稳定的业务幂等作用域`)
    idempotencyKey = getStoredIdempotencyKey(operationId, args.idempotencyScope)
  }

  const isForm = typeof FormData !== 'undefined' && args.body instanceof FormData
  const headers = new Headers()
  let body: BodyInit | undefined
  if (args.body !== undefined) {
    if (isForm) body = args.body as FormData
    else { headers.set('Content-Type', 'application/json'); body = JSON.stringify(args.body) }
  }

  try {
    const data = await request<T>(path, { method: operation.method, headers, body, signal: args.signal }, {
      idempotencyKey, shopScoped: args.shopScoped, auth: args.auth,
    })
    if (idempotencyKey && args.idempotencyScope) clearStoredIdempotencyKey(operationId, args.idempotencyScope)
    return data
  } catch (error) {
    if (error instanceof ApiError && idempotencyKey && args.idempotencyScope && error.result.code !== 'REQUEST_IN_PROGRESS' && error.status < 500) {
      clearStoredIdempotencyKey(operationId, args.idempotencyScope)
    }
    throw error
  }
}

export async function downloadFile(id: string, signal?: AbortSignal): Promise<Blob> {
  const path = API_OPERATIONS['API-22'].path.replace('{id}', encodeURIComponent(id))
  const response = await authorizedFetch(path, { signal }, { timeoutMs: 20000 })
  if (!response.ok) {
    let result: Result<null>
    try { result = await response.json() as Result<null> } catch { throw new Error('文件读取失败') }
    if (result.code !== 'OK' && ERROR_CODES.includes(result.code as never)) throw new ApiError(response.status, result as FailureResult)
    throw new Error('文件读取失败')
  }
  return response.blob()
}
