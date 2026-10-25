# 阶段 B 接口契约清单

从 04 设计及阶段 B schema 生成；23 项已实现，其余保持占位或禁用。

| 编号 | 方法 | 完整路径 | 当前状态 | 计划权限/范围 | 计划契约 |
|---|---|---|---|---|---|
| API-01 | POST | `/api/v1/auth/register` | IMPLEMENTED | 匿名 | RegisterInput → UserView；201，不自动授予后台角色 |
| API-02 | POST | `/api/v1/auth/login` | IMPLEMENTED | 匿名 | LoginInput → AuthView |
| API-03 | POST | `/api/v1/auth/refresh` | IMPLEMENTED | 刷新会话＋CSRF | 空 → AuthView，轮换 Cookie |
| API-04 | POST | `/api/v1/auth/logout` | IMPLEMENTED | 当前会话＋CSRF | 空 → Ack，重复退出仍成功 |
| API-05 | GET | `/api/v1/me` | IMPLEMENTED | 登录本人 | 空 → UserView |
| API-06 | PUT | `/api/v1/me/password` | IMPLEMENTED | 登录本人 | oldPassword、newPassword → Ack；撤销所有会话，需重新登录 |
| API-07 | GET | `/api/v1/addresses` | IMPLEMENTED | 会员本人 | 空 → AddressView 数组，最多 20 个 |
| API-08 | POST | `/api/v1/addresses` | IMPLEMENTED | 会员本人 | AddressInput → AddressView；201 |
| API-09 | PUT | `/api/v1/addresses/{id}` | IMPLEMENTED | 会员本人 | AddressInput → AddressView |
| API-10 | DELETE | `/api/v1/addresses/{id}` | IMPLEMENTED | 会员本人 | 空 → Ack；重复删除成功 |
| API-11 | GET | `/api/v1/categories` | NOT_IMPLEMENTED | 公开 | 空 → CategoryView 树，仅有效分类 |
| API-12 | GET | `/api/v1/products` | NOT_IMPLEMENTED | 公开 | keyword?、categoryId?、shopId?、featured?、sort?、分页 → ProductView 列表；sort NEWEST/PRICE_ASC/PRICE_DESC |
| API-13 | GET | `/api/v1/products/{id}` | NOT_IMPLEMENTED | 公开已上架 | 空 → ProductView |
| API-14 | GET | `/api/v1/products/{id}/reviews` | NOT_IMPLEMENTED | 公开 | 分页 → 可见 ReviewView 列表 |
| API-15 | GET | `/api/v1/merchant/shops/{shopId}/products` | NOT_IMPLEMENTED | 商家本店 | status?、keyword?、分页 → ProductView 列表 |
| API-16 | POST | `/api/v1/merchant/shops/{shopId}/products` | NOT_IMPLEMENTED | 商家本店 | ProductInput → ProductView；201 |
| API-17 | PUT | `/api/v1/merchant/shops/{shopId}/products/{id}` | NOT_IMPLEMENTED | 商家本店 V | ProductEdit → ProductView |
| API-18 | PUT | `/api/v1/merchant/shops/{shopId}/products/{id}/status` | NOT_IMPLEMENTED | 商家本店 V | status ON_SALE/OFF_SHELF、version → ProductView |
| API-19 | GET | `/api/v1/merchant/shops/{shopId}/inventory` | NOT_IMPLEMENTED | 商家本店 | skuId?、分页 → InventoryView 列表 |
| API-20 | POST | `/api/v1/merchant/shops/{shopId}/inventory/{skuId}/adjustments` | NOT_IMPLEMENTED | 商家本店 K V | InventoryAdjust → InventoryView；201 |
| API-21 | POST | `/api/v1/files` | NOT_IMPLEMENTED | 运营封面／会员售后凭证 | multipart 文件、purpose → FileView；201；平台根据 purpose 决定可见性 |
| API-22 | GET | `/api/v1/files/{id}/content` | NOT_IMPLEMENTED | 公开封面／凭证本人、关联客服仓库 | 空 → 文件字节；不套 Result；鉴权失败使用 Result |
| API-23 | GET | `/api/v1/cart` | NOT_IMPLEMENTED | 会员本人 | 空 → CartView |
| API-24 | PUT | `/api/v1/cart/items/{skuId}` | NOT_IMPLEMENTED | 会员本人 | quantity、selected（等价 CartSet，skuId 由路径给定）→ CartView |
| API-25 | DELETE | `/api/v1/cart/items/{skuId}` | NOT_IMPLEMENTED | 会员本人 | 空 → Ack |
| API-26 | POST | `/api/v1/checkout/previews` | NOT_IMPLEMENTED | 会员本人 | PreviewInput → PreviewView；201 |
| API-27 | POST | `/api/v1/checkout/orders` | NOT_IMPLEMENTED | 会员本人 K | CreateOrderInput → BatchView；201 |
| API-28 | GET | `/api/v1/checkout/batches/{id}` | NOT_IMPLEMENTED | 会员本人 | 空 → BatchView，用于网络断开后恢复 |
| API-29 | GET | `/api/v1/orders` | NOT_IMPLEMENTED | 会员本人 | status?、orderNo?、分页 → OrderSummary 列表 |
| API-30 | GET | `/api/v1/orders/{id}` | NOT_IMPLEMENTED | 会员本人 | 空 → OrderView |
| API-31 | POST | `/api/v1/orders/{id}/cancel` | NOT_IMPLEMENTED | 会员本人 K | CancelInput → OrderView；关闭未完成时 202，否则 200 |
| API-32 | POST | `/api/v1/orders/{id}/payments` | NOT_IMPLEMENTED | 会员本人 K | 空 → PaymentView；201 或既有结果 200；渠道由环境配置决定 |
| API-33 | GET | `/api/v1/payments/{id}` | NOT_IMPLEMENTED | 会员本人 | 空 → PaymentView；查询本地状态，后台受控触发查单 |
| API-34 | POST | `/api/v1/payments/{id}/mock-confirm` | DISABLED | 会员本人且启用模拟模式 K | 空 → PaymentView；只适用于 LOCAL_MOCK |
| API-35 | POST | `/api/v1/payments/alipay/notify` | NOT_IMPLEMENTED | 支付宝签名校验 | 渠道表单 → 渠道规定的纯文本确认；不是会员 Bearer 接口 |
| API-36 | POST | `/api/v1/orders/{id}/confirm-receipt` | NOT_IMPLEMENTED | 会员本人 K | 空 → OrderView |
| API-37 | POST | `/api/v1/reviews` | NOT_IMPLEMENTED | 会员本人 | ReviewInput → ReviewView；201，订单行唯一 |
| API-38 | GET | `/api/v1/merchant/shops/{shopId}/orders` | NOT_IMPLEMENTED | 商家或仓库本店 | status?、orderNo?、分页 → OrderSummary 列表 |
| API-39 | GET | `/api/v1/merchant/shops/{shopId}/orders/{id}` | NOT_IMPLEMENTED | 商家或仓库本店 | 空 → OrderView（履约所需字段） |
| API-40 | POST | `/api/v1/merchant/shops/{shopId}/orders/{id}/ship` | NOT_IMPLEMENTED | 商家或仓库本店 K V | ShipInput → ShipmentView；201 |
| API-41 | POST | `/api/v1/merchant/shops/{shopId}/shipments/{id}/events` | NOT_IMPLEMENTED | 商家或仓库本店 | ShipmentEventInput → ShipmentView；201 |
| API-42 | POST | `/api/v1/after-sales` | NOT_IMPLEMENTED | 会员本人 K | AfterSaleInput → AfterSaleView；201 |
| API-43 | GET | `/api/v1/after-sales` | NOT_IMPLEMENTED | 会员本人 | status?、分页 → AfterSaleView 列表 |
| API-44 | GET | `/api/v1/after-sales/{id}` | NOT_IMPLEMENTED | 会员本人 | 空 → AfterSaleView |
| API-45 | POST | `/api/v1/after-sales/{id}/cancel` | NOT_IMPLEMENTED | 会员本人 | 空 → AfterSaleView，仅 REQUESTED |
| API-46 | PUT | `/api/v1/after-sales/{id}/return-shipment` | NOT_IMPLEMENTED | 会员本人 | ReturnInput → AfterSaleView，重复相同运单成功，不接受第二包裹 |
| API-47 | GET | `/api/v1/support/after-sales` | NOT_IMPLEMENTED | 客服平台 | status?、shopId?、分页 → AfterSaleView 列表 |
| API-48 | GET | `/api/v1/support/after-sales/{id}` | NOT_IMPLEMENTED | 客服平台 | 空 → AfterSaleView，必要订单快照通过 API-78 |
| API-49 | POST | `/api/v1/support/after-sales/{id}/decision` | NOT_IMPLEMENTED | 客服平台 | ReviewDecision → AfterSaleView，非 REQUESTED 拒绝重复不同决定 |
| API-50 | GET | `/api/v1/merchant/shops/{shopId}/after-sales` | NOT_IMPLEMENTED | 商家或仓库本店 | status?、分页 → AfterSaleView 列表 |
| API-51 | POST | `/api/v1/merchant/shops/{shopId}/after-sales/{id}/inspection` | NOT_IMPLEMENTED | 仓库本店 K | InspectionInput → AfterSaleView |
| API-52 | GET | `/api/v1/finance/refunds` | NOT_IMPLEMENTED | 财务平台 | status?、shopId?、分页 → RefundView 列表 |
| API-53 | POST | `/api/v1/finance/refunds/{id}/execute` | NOT_IMPLEMENTED | 财务平台，非售后审核人 K | 空 → RefundView；202；网络未知重放原 K，查询明确 FAILED 后新执行用新 K，但 refundId/refundNo 不变 |
| API-54 | GET | `/api/v1/finance/reconciliations` | NOT_IMPLEMENTED | 财务平台 | billDate?、分页 → ReconciliationView 列表 |
| API-55 | POST | `/api/v1/finance/reconciliations` | NOT_IMPLEMENTED | 财务平台 K | billDate、provider → ReconciliationView；202，同日复用并可重跑 FAILED |
| API-56 | GET | `/api/v1/finance/reconciliation-issues` | NOT_IMPLEMENTED | 财务平台 | status?、分页 → IssueView 列表 |
| API-57 | POST | `/api/v1/finance/reconciliation-issues/{id}/resolve` | NOT_IMPLEMENTED | 财务平台 | IssueResolution → IssueView |
| API-58 | GET | `/api/v1/finance/settlements` | NOT_IMPLEMENTED | 财务平台 | shopId?、status?、periodDate?、分页 → SettlementView 列表（省略明细） |
| API-59 | POST | `/api/v1/finance/settlements` | NOT_IMPLEMENTED | 财务平台 K | SettlementInput → SettlementView；201，已有拒绝单按重新生成处理 |
| API-60 | POST | `/api/v1/finance/settlements/{id}/decision` | NOT_IMPLEMENTED | 财务平台，非创建人 K V | SettlementDecision → SettlementView |
| API-61 | POST | `/api/v1/finance/settlements/{id}/mock-payout` | NOT_IMPLEMENTED | 财务平台 K V | version → SettlementView；仅模拟，不发起真实转账 |
| API-62 | GET | `/api/v1/merchant/shops/{shopId}/settlements` | NOT_IMPLEMENTED | 商家本店 | status?、分页 → SettlementView 列表（省略明细） |
| API-63 | GET | `/api/v1/ops/books` | NOT_IMPLEMENTED | 运营全量／商家只读有效 | keyword?、分页 → BookView 列表 |
| API-64 | POST | `/api/v1/ops/books` | NOT_IMPLEMENTED | 运营 | BookInput → BookView；201 |
| API-65 | PUT | `/api/v1/ops/books/{id}` | NOT_IMPLEMENTED | 运营 | BookInput → BookView；已使用 ISBN 不允许改号 |
| API-66 | POST | `/api/v1/ops/categories` | NOT_IMPLEMENTED | 运营 | CategoryInput → CategoryView；201 |
| API-67 | PUT | `/api/v1/ops/categories/{id}` | NOT_IMPLEMENTED | 运营 | CategoryInput → CategoryView |
| API-68 | PUT | `/api/v1/ops/products/{id}/moderation` | NOT_IMPLEMENTED | 运营 V | offShelf、featured、reason、version → ProductView；不能将商家下架商品强制上架 |
| API-69 | PUT | `/api/v1/ops/reviews/{id}/visibility` | NOT_IMPLEMENTED | 运营 | status、reason → ReviewView |
| API-70 | GET | `/api/v1/admin/users` | IMPLEMENTED | 管理员 | keyword?、status?、分页 → UserView 列表 |
| API-71 | POST | `/api/v1/admin/users` | IMPLEMENTED | 管理员 | username、nickname、initialPassword、roleIds → UserView；201；演示初始密码不入日志 |
| API-72 | PUT | `/api/v1/admin/users/{id}` | IMPLEMENTED | 管理员 | UserEdit → UserView；变更 authVersion |
| API-73 | GET | `/api/v1/admin/roles` | IMPLEMENTED | 管理员 | 分页 → RoleView 列表 |
| API-74 | POST | `/api/v1/admin/roles` | IMPLEMENTED | 管理员 | RoleInput → RoleView；201 |
| API-75 | PUT | `/api/v1/admin/roles/{id}` | IMPLEMENTED | 管理员 | name、permissionIds、status → RoleView |
| API-76 | GET | `/api/v1/admin/permissions` | IMPLEMENTED | 管理员 | 空 → PermissionView 数组 |
| API-77 | GET | `/api/v1/admin/audit-logs` | IMPLEMENTED | 管理员 | actorId?、action?、traceId?、from?、to?、分页 → AuditView 列表 |
| API-78 | GET | `/api/v1/support/after-sales/{id}/order` | NOT_IMPLEMENTED | 客服关联售后 | 空 → OrderView（仅客服必要字段） |
| API-79 | GET | `/api/v1/finance/settlements/{id}` | NOT_IMPLEMENTED | 财务平台 | 空 → SettlementView |
| API-80 | GET | `/api/v1/merchant/shops/{shopId}/settlements/{id}` | NOT_IMPLEMENTED | 商家本店 | 空 → SettlementView；屏蔽平台操作者个人信息 |
| API-81 | POST | `/api/v1/finance/settlement-adjustments` | NOT_IMPLEMENTED | 财务平台 | AdjustmentInput → AdjustmentView；201 |
| API-82 | GET | `/api/v1/finance/settlement-adjustments` | NOT_IMPLEMENTED | 财务平台 | status?、shopId?、分页 → AdjustmentView 列表 |
| API-83 | POST | `/api/v1/finance/settlement-adjustments/{id}/approve` | NOT_IMPLEMENTED | 财务平台，非创建人 | note → AdjustmentView |
| API-84 | GET | `/api/v1/admin/shops` | IMPLEMENTED | 管理员 | status?、分页 → ShopView 列表 |
| API-85 | POST | `/api/v1/admin/shops` | IMPLEMENTED | 管理员 | ShopInput → ShopView；201 |
| API-86 | PUT | `/api/v1/admin/shops/{id}` | IMPLEMENTED | 管理员 | ShopInput → ShopView |
| API-87 | GET | `/api/v1/admin/shops/{shopId}/members` | IMPLEMENTED | 管理员 | 分页 → MemberView 列表 |
| API-88 | PUT | `/api/v1/admin/shops/{shopId}/members/{userId}/{duty}` | IMPLEMENTED | 管理员 | status → MemberView；首次创建 201，修改 200 |
| API-89 | GET | `/api/v1/dashboard` | NOT_IMPLEMENTED | 后台角色 | shopId?（商家仓库须本店）→ DashboardView，依角色裁剪 |
| API-90 | GET | `/api/v1/ops/products` | NOT_IMPLEMENTED | 运营 | keyword?、status?、shopId?、分页 → ProductView 列表 |
| API-91 | GET | `/api/v1/ops/reviews` | NOT_IMPLEMENTED | 运营 | productId?、status?、分页 → ReviewView 列表 |
| API-92 | GET | `/api/v1/ops/categories` | NOT_IMPLEMENTED | 运营 | status? → CategoryView 树，包含停用项用于维护 |
