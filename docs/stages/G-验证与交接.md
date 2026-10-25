# 阶段 G 前端页面与联调验证及交接

日期：2026-10-25，Asia/Shanghai。阶段 G 页面路由和主要操作已按 UI-01—33 交付，并以本地真实 API 联调。浏览器 E2E 本轮 4 项通过、3 项因项目过滤规则跳过；未覆盖 UI 的每一个分支，因此不把抽样 E2E 表述为全量页面验收。

## 1 现状与设计依据

实施依据为 AGENTS.md、docs/README.md、01—08、12—13、F 阶段交接、OpenAPI 0.5.0 和其 92 项接口清单。页面遵循 01 的业务政策、02 的状态与权限规则、03 的字段、04 的网络契约、05 的 UI-01—33 和 07 的浏览器验收要求。Node/pnpm 依赖与本地运行按 06；测试目标和未来流水线约束按 07/08。未修改数据库字段、接口 schema、业务状态或支付规则。

开始检查时没有可用的项目代码图谱索引，按 AGENTS.md 的后备规则使用文件搜索；当前目录没有 Git 仓库，无法列出分支、SHA 或区分先前未提交改动。变更集中于前端、契约生成脚本、阶段 G 文档与证据，没有触碰后端业务实现。

阶段 F 服务端 92 项接口中 91 项标记已实现；API-89 保持未实现。前端对其展示后端真实 HTTP 501 / NOT_IMPLEMENTED 及 traceId，不显示占位成功统计。其余页面调用共享 OpenAPI 生成的操作元数据和模型。

## 2 当前阶段范围

完成公共商城与搜索商品浏览、详情/评价、登录与注册入口、购物车、跨店预览/提交、逐店订单与支付查询、会员地址/订单/售后/评价/安全页面，以及商家商品库存和订单、客服/仓库售后、平台与商家财务、运营、用户角色、店铺成员、审计和运维页面路由。商城使用独立布局；工作台共用导航、当前身份/店铺上下文、权限裁剪、表格和契约驱动表单。

请求层统一检查 Result/error code/traceId，访问令牌仅保存在内存；401 并发刷新合并为一次，安全读取或带稳定幂等键的写请求才自动重放。切店中止旧店请求；版本写入必须携带 version。服务端金额使用十进制字符串展示。文件凭证用授权下载而非公开静态 URL。页面采用加载、无数据、错误/重试、禁用和处理中状态；未知接口明确展示未实现。移动会员和桌面工作台使用响应式布局，表单控件可由键盘访问。

本轮没有真实支付宝沙箱、下单支付/退款交易、平台结算或商家出款操作；E2E 的会员场景完成真实登录、地址恢复、购物车和跨店预览，但未创建订单。极长书名、无封面和管理员长昵称只在同契约布局夹具中呈现，以检验排版；核心 API 请求仍走本机真实后端。测试临时地址和购物车项在用例末清理。后台统计 API-89 的实际 501 是已知范围边界。

## 3 必要文件变更

- 前端结构与 API：frontend/src/api/auth-state.ts、client.ts、generated.ts、models.ts、types.ts；新增共享商城/工作台布局、页面组件、路由、Pinia 身份/店铺状态与基础样式。
- 页面：frontend/src/pages/StorefrontPage.vue、storefront/CatalogPage.vue、storefront/MemberPage.vue、WorkbenchPage.vue、workbench/WorkbenchModulePage.vue、AccessDeniedPage.vue、NotFoundPage.vue；基础组件 AsyncState.vue、MoneyText.vue。
- 测试与工具：frontend/tests/api-client.test.ts；frontend/e2e/storefront-workbench.spec.ts；frontend/playwright.config.ts；frontend/package.json 与锁文件；scripts/sync-contract.py 生成 92 项操作元数据；scripts/smoke-stage-g-http.py 本地 HTTP 冒烟。
- 记录：docs/stages/G-验证与交接.md、G-AI使用记录.md、evidence/G-verification.json；更新项目 README 和 docs/README 阶段状态。

接口操作状态与 OpenAPI schema 没有改动。API-89 没有伪造客户端数据或本地成功响应。未创建远程 CI/CD 工作流；本阶段只完成本地自动化前端检查与浏览器联调。

## 4 实施与验证

测试使用项目 dev 配置、本机隔离 MySQL、后端 127.0.0.1:18080 与 LOCAL_MOCK 设置；本轮没有发起任何支付/退款/出款请求。Playwright 使用已安装的 Edge 通道。冻结工具为 Node 24.22.1、pnpm 10.34.7。

| 实际命令 | 结果 | 证据 |
|---|---|---|
| . ./scripts/use-tools.ps1; node --version; pnpm --version; pnpm --dir frontend install --frozen-lockfile | 成功；Node v24.22.1、pnpm 10.34.7；lockfile up to date、already up to date | 命令实际输出；.artifacts/pnpm-frozen-install.log 为此前同版本记录 |
| pnpm --dir frontend run typecheck | 成功，vue-tsc --noEmit 无诊断 | .artifacts/G-frontend-typecheck.log |
| pnpm --dir frontend test | 成功，3 个测试文件、10 项通过 | .artifacts/G-frontend-tests.log |
| pnpm --dir frontend run build | 成功，1648 个模块转换，Vite 生成 frontend/dist | .artifacts/G-frontend-build.log |
| pnpm --dir frontend run contract:lint | 成功，OpenAPI 描述校验有效 | .artifacts/G-contract-lint.log |
| python scripts/sync-contract.py --check | 成功，92 项操作同步 | .artifacts/G-contract-sync.log |
| pnpm --dir frontend exec tsc --noEmit --strict --target ES2022 --module ESNext --moduleResolution bundler --types node --skipLibCheck playwright.config.ts e2e/storefront-workbench.spec.ts | 成功，Playwright 配置与 E2E TypeScript 严格类型检查 | .artifacts/G-playwright-typecheck.log；Playwright 源码为 frontend/e2e/storefront-workbench.spec.ts |
| pnpm --dir frontend run test:e2e | 成功：7 个项目用例中 4 passed、3 skipped、0 failed。跳过项是非匹配桌面/移动项目，不计通过。含立即购买单 SKU 真实预览、手机跨店预览与错误重试、桌面 API-89 501、公共手机目录布局 | .artifacts/G-playwright-final.log；截图见下方 |
| python scripts/smoke-stage-g-http.py | 成功：分类 200/OK、商品 200/OK（23 项）、dashboard 501/NOT_IMPLEMENTED；三项都有 traceId | .artifacts/G-http-smoke.json |

截图证据：.artifacts/G-mobile-catalog-long-title-no-cover.png、G-mobile-product-buy-now.png、G-mobile-member-long-address.png、G-mobile-member-cart-long-title-no-cover.png、G-mobile-address-error-retry.png、G-mobile-cross-store-server-preview.png、G-desktop-workbench-long-text.png、G-desktop-api89-not-implemented.png。图像来自真实浏览器运行；长文本/无封面展示在测试中用于布局校验，不代表后端持久数据。

两次未采用的安装尝试如实保留：pnpm --dir frontend install --frozen-lockfile --offline 因本机 pnpm 元数据镜像缺少 @pnpm/exe@10.34.7 而退出 1；未加载项目工具的 pnpm install --frozen-lockfile 因主机 Node v25.8.1 与 package engines 指定 v24.22.1 不符退出 1。按 docs/06 使用 scripts/use-tools.ps1 加载冻结版本后，冻结安装通过。另有一次 Playwright TS 检查从 frontend 子目录运行，根相对脚本路径解析失败并退出 1；改从仓库根目录执行同一检查后退出 0。以上路径/环境错误均不作为通过结果。

本轮未运行完整后端 verify、支付宝真实沙箱、第三方支付、退款、真实出款、k6 性能、备份恢复、远程 CI/CD；这些不属于本次前端范围，其中项目后端 Maven 覆盖门槛和沙箱阻塞仍以 F/E 交接记录为准。

## 5 尚未解决的风险和交接记录

1. API-89 仪表盘数据保持未实现。相关路由展示真实 501 和 traceId；后续必须先完成该 API 的服务端契约实现，再补待处理数量卡，不能显示零值或样例统计。
2. 浏览器测试覆盖核心商城、会话错误恢复、跨店预览和工作台错误边界，不是 UI-01—33 的逐页面逐角色全矩阵；发布前按真实演示角色补齐剩余购买/履约/退款/评价/结算路径 E2E。
3. 测试未创建订单、没有付款和退款。本机后台页面虽有契约驱动读写入口，涉及资金的全链路真实沙箱验收仍待 E 阶段输入；商家出款仍为模拟。
4. 未运行远程 CI/CD。08 中要求的 PR 安全扫描、后端 JaCoCo 80% 门槛、test 部署、全套发布 E2E 与恢复演练不能由本地前端构建替代。F 阶段 72.51% 服务分支覆盖门槛未通过，仍是独立阻塞。
5. 无 Git 仓库，无法提供 SHA、PR 或 diff；后续接手者应先由用户确认文件集并建立版本控制，再提交。

下一步：先补 API-89 服务端实现及后台数量接口，然后按 UI-07—08 和 UI-21—28 建立一组仅用 LOCAL_MOCK 的完整浏览器流程；最后完善逐角色 UI/API 矩阵、CI 和部署验证。不要把 501 页面、布局夹具、LOCAL_MOCK 或本地 E2E 解释为沙箱和全量验收。
