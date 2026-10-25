import { createPinia, setActivePinia } from 'pinia'
import { apiId, apiMoney, pageQuery } from '../src/api/types'
import { ApiError, request } from '../src/api/client'
import { useFoundationStore } from '../src/stores/foundation'

describe('网络类型边界', () => {
  it('ID 不接受数字、零、前导零或超过无符号 BIGINT 的值', () => {
    expect(apiId('18446744073709551615')).toBe('18446744073709551615')
    for (const invalid of [9007199254740992, '0', '01', '1\n', '18446744073709551616']) {
      expect(() => apiId(invalid)).toThrow(TypeError)
    }
  })
  it('金额保持字符串精度，允许设计中的负调整但拒绝浮点和科学计数法', () => {
    expect(apiMoney('19.90')).toBe('19.90')
    expect(apiMoney('-19.90')).toBe('-19.90')
    for (const invalid of [19.9, '19.9', '1e2', '1.00\n', '10000000000.00']) expect(() => apiMoney(invalid)).toThrow()
  })
  it('分页默认值和最大页大小统一', () => {
    expect(pageQuery()).toEqual({ page: 1, pageSize: 20 })
    expect(() => pageQuery(0)).toThrow()
    expect(() => pageQuery(1, 101)).toThrow()
  })
})

describe('真实状态与错误', () => {
  afterEach(() => vi.unstubAllGlobals())
  it('501 不会被转成业务成功，保留 traceId', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      code: 'NOT_IMPLEMENTED', message: '尚未实现', data: null, traceId: 'test-trace-123',
    }), { status: 501, headers: { 'Content-Type': 'application/json' } })))
    try {
      await request('/api/v1/products')
      throw new Error('不应成功')
    } catch (error) {
      expect(error).toBeInstanceOf(ApiError)
      expect((error as ApiError).result.traceId).toBe('test-trace-123')
    }
  })
  it('后端失败时健康状态不显示通过', async () => {
    setActivePinia(createPinia())
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    const store = useFoundationStore()
    await store.checkHealth()
    expect(store.health).toBe('unavailable')
  })
  it('有效就绪响应才显示健康', async () => {
    setActivePinia(createPinia())
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"status":"UP"}', { status: 200 })))
    const store = useFoundationStore()
    await store.checkHealth()
    expect(store.health).toBe('up')
  })
})
