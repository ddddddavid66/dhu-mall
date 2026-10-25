import { afterEach, describe, expect, it, vi } from 'vitest'
import { clearCredentials, getStoredIdempotencyKey, setCredentials } from '../src/api/auth-state'
import { authorizedFetch, callOperation } from '../src/api/client'

afterEach(() => {
  clearCredentials()
  vi.unstubAllGlobals()
})

describe('认证续期与安全重试', () => {
  it('并发 401 请求合并为一次刷新，并使用新访问令牌重放读取', async () => {
    setCredentials({ accessToken: 'old-access-token', csrfToken: 'old-csrf-token' })
    const calls: Array<{ path: string; authorization: string | null; csrf: string | null }> = []
    let refreshCount = 0
    const authData = {
      accessToken: 'new-access-token', expiresIn: 900, csrfToken: 'new-csrf-token',
      user: { id: '1', username: 'reader', nickname: '读者', status: 'ACTIVE', roles: ['MEMBER'], permissions: [], shops: [] },
    }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      const headers = new Headers(init?.headers)
      calls.push({ path, authorization: headers.get('Authorization'), csrf: headers.get('X-CSRF-Token') })
      if (path === '/api/v1/auth/refresh') {
        refreshCount += 1
        return new Response(JSON.stringify({ code: 'OK', message: 'ok', data: authData, traceId: 'refresh-trace' }), { status: 200 })
      }
      if (headers.get('Authorization') === 'Bearer old-access-token') return new Response('', { status: 401 })
      return new Response('{}', { status: 200 })
    }))

    const responses = await Promise.all([
      authorizedFetch('/api/v1/catalog/categories'),
      authorizedFetch('/api/v1/products'),
    ])

    expect(responses.every(response => response.ok)).toBe(true)
    expect(refreshCount).toBe(1)
    expect(calls.filter(call => call.authorization === 'Bearer new-access-token')).toHaveLength(2)
    expect(calls.find(call => call.path === '/api/v1/auth/refresh')?.csrf).toBe('old-csrf-token')
  })

  it('关键 POST 的网络重试沿用同一幂等键', async () => {
    const scope = 'member:1:batch:preview-1'
    const keys: Array<string | null> = []
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
      keys.push(new Headers(init?.headers).get('Idempotency-Key'))
      if (keys.length === 1) throw new TypeError('temporary network failure')
      return new Response(JSON.stringify({ code: 'OK', message: 'ok', data: { id: '1' }, traceId: 'order-trace' }), { status: 200 })
    })
    vi.stubGlobal('fetch', fetchMock)

    await callOperation('API-27', { body: { previewId: 'preview-1' }, idempotencyScope: scope })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(keys[0]).toBeTruthy()
    expect(keys[1]).toBe(keys[0])
    expect(getStoredIdempotencyKey('API-27', scope)).not.toBe(keys[0])
  })
})

describe('接口未实现边界', () => {
  it('API-89 返回契约 NOT_IMPLEMENTED 时作为错误保留 traceId', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'NOT_IMPLEMENTED', message: '管理概览尚未实现', data: null, traceId: 'dashboard-trace',
    }), { status: 501 })))

    await expect(callOperation('API-89')).rejects.toMatchObject({
      status: 501,
      result: { code: 'NOT_IMPLEMENTED', traceId: 'dashboard-trace' },
    })
  })
})
