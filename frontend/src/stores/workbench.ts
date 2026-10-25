import { defineStore } from 'pinia'
import { cancelShopRequests } from '../api/client'
import type { UserView } from '../api/models'
import type { ApiId } from '../api/generated'

export const useWorkbenchStore = defineStore('workbench', {
  state: () => ({ selectedShopId: null as ApiId | null }),
  actions: {
    syncShops(user: UserView | null) {
      const allowed = user?.shops.map(shop => shop.id) ?? []
      if (this.selectedShopId && allowed.includes(this.selectedShopId)) return
      this.selectedShopId = allowed[0] ?? null
      cancelShopRequests()
    },
    switchShop(shopId: ApiId, user: UserView | null) {
      if (!user?.shops.some(shop => shop.id === shopId)) throw new Error('只能选择服务端会话返回的店铺')
      if (this.selectedShopId === shopId) return
      this.selectedShopId = shopId
      cancelShopRequests()
    },
  },
})
