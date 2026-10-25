import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import FoundationPage from '../src/pages/FoundationPage.vue'

afterEach(() => vi.unstubAllGlobals())

it('失败状态能重试，恢复后只显示应用健康，不声称交易已就绪', async () => {
  const fetchMock = vi.fn()
    .mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValueOnce(new Response('{"status":"UP"}', { status: 200 }))
  vi.stubGlobal('fetch', fetchMock)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: FoundationPage }] })
  await router.push('/')
  const wrapper = mount(FoundationPage, { global: { plugins: [createPinia(), router] } })
  await flushPromises()
  expect(wrapper.get('[role="status"]').text()).toBe('暂时无法连接后端')
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(wrapper.get('[role="status"]').text()).toBe('工程健康检查通过')
  expect(wrapper.text()).toContain('不代表商品与交易业务已就绪')
  expect(fetchMock).toHaveBeenCalledTimes(2)
  wrapper.unmount()
})
