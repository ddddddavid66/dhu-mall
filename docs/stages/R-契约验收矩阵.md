# 收尾阶段接口HTTP验收观察矩阵

HTTP observation evidence, not a claim of full schema or business assertion coverage

| 操作 | 方法与路径 | 实际成功状态 | 实际拒绝状态 | 观察数 | 状态 |
|---|---|---|---|---|---|
| API-01 | POST /api/v1/auth/register | [201] | [400, 409] | 10 | OBSERVED_BOTH |
| API-02 | POST /api/v1/auth/login | [200] | [401, 403] | 328 | OBSERVED_BOTH |
| API-03 | POST /api/v1/auth/refresh | [200] | [401, 403] | 24 | OBSERVED_BOTH |
| API-04 | POST /api/v1/auth/logout | [200] | [403] | 12 | OBSERVED_BOTH |
| API-05 | GET /api/v1/me | [200] | [401, 403] | 28 | OBSERVED_BOTH |
| API-06 | PUT /api/v1/me/password | [200] | [401] | 6 | OBSERVED_BOTH |
| API-07 | GET /api/v1/addresses | [200] | [401, 403] | 10 | OBSERVED_BOTH |
| API-08 | POST /api/v1/addresses | [201] | [400, 401, 409] | 50 | OBSERVED_BOTH |
| API-09 | PUT /api/v1/addresses/{id} | [200] | [401, 404] | 10 | OBSERVED_BOTH |
| API-10 | DELETE /api/v1/addresses/{id} | [200] | [401, 404] | 8 | OBSERVED_BOTH |
| API-11 | GET /api/v1/categories | [200] | [401] | 4 | OBSERVED_BOTH |
| API-12 | GET /api/v1/products | [200] | [400] | 16 | OBSERVED_BOTH |
| API-13 | GET /api/v1/products/{id} | [200] | [404] | 12 | OBSERVED_BOTH |
| API-14 | GET /api/v1/products/{id}/reviews | [200] | [400] | 6 | OBSERVED_BOTH |
| API-15 | GET /api/v1/merchant/shops/{shopId}/products | [200] | [401, 404] | 6 | OBSERVED_BOTH |
| API-16 | POST /api/v1/merchant/shops/{shopId}/products | [201] | [401, 409] | 36 | OBSERVED_BOTH |
| API-17 | PUT /api/v1/merchant/shops/{shopId}/products/{id} | [200] | [400, 401, 404, 409] | 12 | OBSERVED_BOTH |
| API-18 | PUT /api/v1/merchant/shops/{shopId}/products/{id}/status | [200] | [400, 401, 409] | 48 | OBSERVED_BOTH |
| API-19 | GET /api/v1/merchant/shops/{shopId}/inventory | [200] | [400, 401, 403, 404] | 12 | OBSERVED_BOTH |
| API-20 | POST /api/v1/merchant/shops/{shopId}/inventory/{skuId}/adjustments | [201] | [400, 401, 409] | 62 | OBSERVED_BOTH |
| API-21 | POST /api/v1/files | [201] | [400, 401, 403, 413, 415] | 29 | OBSERVED_BOTH |
| API-22 | GET /api/v1/files/{id}/content | [200] | [401, 404] | 24 | OBSERVED_BOTH |
| API-23 | GET /api/v1/cart | [200] | [401, 403] | 12 | OBSERVED_BOTH |
| API-24 | PUT /api/v1/cart/items/{skuId} | [200] | [400, 401, 409] | 32 | OBSERVED_BOTH |
| API-25 | DELETE /api/v1/cart/items/{skuId} | [200] | [401] | 8 | OBSERVED_BOTH |
| API-26 | POST /api/v1/checkout/previews | [201] | [400, 401, 404, 409] | 284 | OBSERVED_BOTH |
| API-27 | POST /api/v1/checkout/orders | [201] | [400, 401, 409, 410] | 364 | OBSERVED_BOTH |
| API-28 | GET /api/v1/checkout/batches/{id} | [200] | [401, 404] | 4 | OBSERVED_BOTH |
| API-29 | GET /api/v1/orders | [200] | [400, 401, 403] | 9 | OBSERVED_BOTH |
| API-30 | GET /api/v1/orders/{id} | [200] | [401, 404] | 7 | OBSERVED_BOTH |
| API-31 | POST /api/v1/orders/{id}/cancel | [200, 202] | [401, 404, 409] | 15 | OBSERVED_BOTH |
| API-32 | POST /api/v1/orders/{id}/payments | [200, 201] | [400, 401, 404, 409] | 155 | OBSERVED_BOTH |
| API-33 | GET /api/v1/payments/{id} | [200] | [400, 401, 404] | 7 | OBSERVED_BOTH |
| API-34 | POST /api/v1/payments/{id}/mock-confirm | [200] | [400, 403, 404, 409] | 158 | OBSERVED_BOTH |
| API-35 | POST /api/v1/payments/alipay/notify | [200] | [200] | 36 | OBSERVED_BOTH |
| API-36 | POST /api/v1/orders/{id}/confirm-receipt | [200] | [401] | 22 | OBSERVED_BOTH |
| API-37 | POST /api/v1/reviews | [201] | [400, 401, 409] | 13 | OBSERVED_BOTH |
| API-38 | GET /api/v1/merchant/shops/{shopId}/orders | [200] | [400, 401, 403, 404] | 17 | OBSERVED_BOTH |
| API-39 | GET /api/v1/merchant/shops/{shopId}/orders/{id} | [200] | [401, 403] | 25 | OBSERVED_BOTH |
| API-40 | POST /api/v1/merchant/shops/{shopId}/orders/{id}/ship | [201] | [401, 409] | 25 | OBSERVED_BOTH |
| API-41 | POST /api/v1/merchant/shops/{shopId}/shipments/{id}/events | [201] | [400, 401] | 4 | OBSERVED_BOTH |
| API-42 | POST /api/v1/after-sales | [201] | [401, 409] | 58 | OBSERVED_BOTH |
| API-43 | GET /api/v1/after-sales | [200] | [400, 401] | 6 | OBSERVED_BOTH |
| API-44 | GET /api/v1/after-sales/{id} | [200] | [401] | 4 | OBSERVED_BOTH |
| API-45 | POST /api/v1/after-sales/{id}/cancel | [200] | [401, 409] | 6 | OBSERVED_BOTH |
| API-46 | PUT /api/v1/after-sales/{id}/return-shipment | [200] | [401, 404, 409] | 16 | OBSERVED_BOTH |
| API-47 | GET /api/v1/support/after-sales | [200] | [400, 401] | 9 | OBSERVED_BOTH |
| API-48 | GET /api/v1/support/after-sales/{id} | [200] | [401, 403] | 6 | OBSERVED_BOTH |
| API-49 | POST /api/v1/support/after-sales/{id}/decision | [200] | [401, 409] | 53 | OBSERVED_BOTH |
| API-50 | GET /api/v1/merchant/shops/{shopId}/after-sales | [200] | [400, 401] | 7 | OBSERVED_BOTH |
| API-51 | POST /api/v1/merchant/shops/{shopId}/after-sales/{id}/inspection | [200] | [401, 409] | 6 | OBSERVED_BOTH |
| API-52 | GET /api/v1/finance/refunds | [200] | [400, 401] | 12 | OBSERVED_BOTH |
| API-53 | POST /api/v1/finance/refunds/{id}/execute | [202] | [401, 403, 409] | 18 | OBSERVED_BOTH |
| API-54 | GET /api/v1/finance/reconciliations | [200] | [400, 401] | 5 | OBSERVED_BOTH |
| API-55 | POST /api/v1/finance/reconciliations | [202] | [400, 401] | 45 | OBSERVED_BOTH |
| API-56 | GET /api/v1/finance/reconciliation-issues | [200] | [400, 401] | 13 | OBSERVED_BOTH |
| API-57 | POST /api/v1/finance/reconciliation-issues/{id}/resolve | [200] | [401] | 14 | OBSERVED_BOTH |
| API-58 | GET /api/v1/finance/settlements | [200] | [400, 401] | 11 | OBSERVED_BOTH |
| API-59 | POST /api/v1/finance/settlements | [201] | [401, 409] | 16 | OBSERVED_BOTH |
| API-60 | POST /api/v1/finance/settlements/{id}/decision | [200] | [401, 403, 409] | 21 | OBSERVED_BOTH |
| API-61 | POST /api/v1/finance/settlements/{id}/mock-payout | [200] | [401, 409] | 7 | OBSERVED_BOTH |
| API-62 | GET /api/v1/merchant/shops/{shopId}/settlements | [200] | [400, 401] | 7 | OBSERVED_BOTH |
| API-63 | GET /api/v1/ops/books | [200] | [401] | 6 | OBSERVED_BOTH |
| API-64 | POST /api/v1/ops/books | [201] | [400, 401, 409] | 38 | OBSERVED_BOTH |
| API-65 | PUT /api/v1/ops/books/{id} | [200] | [400, 401, 409] | 12 | OBSERVED_BOTH |
| API-66 | POST /api/v1/ops/categories | [201] | [401, 403, 409] | 40 | OBSERVED_BOTH |
| API-67 | PUT /api/v1/ops/categories/{id} | [200] | [401, 409] | 8 | OBSERVED_BOTH |
| API-68 | PUT /api/v1/ops/products/{id}/moderation | [200] | [401, 409] | 8 | OBSERVED_BOTH |
| API-69 | PUT /api/v1/ops/reviews/{id}/visibility | [200] | [401, 404] | 11 | OBSERVED_BOTH |
| API-70 | GET /api/v1/admin/users | [200] | [400, 401, 403] | 18 | OBSERVED_BOTH |
| API-71 | POST /api/v1/admin/users | [201] | [400, 401] | 6 | OBSERVED_BOTH |
| API-72 | PUT /api/v1/admin/users/{id} | [200] | [401, 403, 409] | 18 | OBSERVED_BOTH |
| API-73 | GET /api/v1/admin/roles | [200] | [401, 403] | 6 | OBSERVED_BOTH |
| API-74 | POST /api/v1/admin/roles | [201] | [401] | 4 | OBSERVED_BOTH |
| API-75 | PUT /api/v1/admin/roles/{id} | [200] | [401, 409] | 6 | OBSERVED_BOTH |
| API-76 | GET /api/v1/admin/permissions | [200] | [401] | 4 | OBSERVED_BOTH |
| API-77 | GET /api/v1/admin/audit-logs | [200] | [400, 401, 403] | 18 | OBSERVED_BOTH |
| API-78 | GET /api/v1/support/after-sales/{id}/order | [200] | [401] | 3 | OBSERVED_BOTH |
| API-79 | GET /api/v1/finance/settlements/{id} | [200] | [401] | 6 | OBSERVED_BOTH |
| API-80 | GET /api/v1/merchant/shops/{shopId}/settlements/{id} | [200] | [401] | 4 | OBSERVED_BOTH |
| API-81 | POST /api/v1/finance/settlement-adjustments | [201] | [401, 409] | 4 | OBSERVED_BOTH |
| API-82 | GET /api/v1/finance/settlement-adjustments | [200] | [400, 401] | 12 | OBSERVED_BOTH |
| API-83 | POST /api/v1/finance/settlement-adjustments/{id}/approve | [200] | [401, 403] | 5 | OBSERVED_BOTH |
| API-84 | GET /api/v1/admin/shops | [200] | [401, 403] | 6 | OBSERVED_BOTH |
| API-85 | POST /api/v1/admin/shops | [201] | [401] | 8 | OBSERVED_BOTH |
| API-86 | PUT /api/v1/admin/shops/{id} | [200] | [401] | 4 | OBSERVED_BOTH |
| API-87 | GET /api/v1/admin/shops/{shopId}/members | [200] | [401, 403] | 6 | OBSERVED_BOTH |
| API-88 | PUT /api/v1/admin/shops/{shopId}/members/{userId}/{duty} | [200, 201] | [401] | 6 | OBSERVED_BOTH |
| API-89 | GET /api/v1/dashboard | [200] | [400, 401, 403, 404] | 30 | OBSERVED_BOTH |
| API-90 | GET /api/v1/ops/products | [200] | [401, 403] | 6 | OBSERVED_BOTH |
| API-91 | GET /api/v1/ops/reviews | [200] | [400, 401] | 16 | OBSERVED_BOTH |
| API-92 | GET /api/v1/ops/categories | [200] | [401] | 4 | OBSERVED_BOTH |
