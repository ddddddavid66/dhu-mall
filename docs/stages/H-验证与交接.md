# 阶段 H 完整质量验证与交接（质量门禁未通过）

日期：2026-10-26。报告按 docs/07 的“通过、失败、阻塞、未执行”记录。119 项后端自动测试全部通过；本机前后端检查及 4 项浏览器 E2E 通过。Maven clean verify 最终仍因服务分支覆盖率 72.51% 低于固定的 80% 门槛而失败，因此本阶段不得标为全通过或可发布。

## 1 现状与设计依据

依据 AGENTS.md、docs/README.md、01—08、12—13、B—G 交接和当前 OpenAPI 0.5.0。业务政策以 01 为准，状态和锁边界以 02 为准，字段以 03 为准，接口和错误响应以 04 为准，测试分层及 TC-01—32 以 07 为准。没有修改接口、业务金额、库存或财务规则，也没有降低覆盖率门槛。

开始时先核对当前文件和环境。工作目录不是 Git 仓库，无法确认 Git 分支、提交 SHA、PR 或列出用户工作树差异；测试和报告没有覆盖业务源码。按项目规则先使用 codebase-memory-mcp 图谱定位调度器、测试数据库基类和相关服务，再读具体源片段；项目图谱在本会话已索引。

测试使用项目自有 MySQL 8.4.13，监听 127.0.0.1:13306，测试目标仅为 yueji_mall_test。最终 Maven clean verify 从空测试 schema 应用 Flyway V1—V10。系统 MySQL80 和 yueji_mall_dev 未清库；测试账号口令、开发账号口令及支付凭据未写入报告。阶段 G 浏览器 E2E 使用本机 dev 后端和现有随机开发数据，临时地址与购物车行按用例清理；本轮 E2E 没有建订单、付款、退款或出款。

## 2 当前阶段范围

执行前端依赖冻结安装、类型检查、Vitest、生产构建、OpenAPI lint 和 92 项契约同步；对 92 个 OpenAPI 操作检查未实现能力不返回虚假成功，并对 83 个标记为受保护且已实现的操作发匿名请求；重跑 B—F 后端真实 MySQL 集成套件；通过本地 JAR 做 readiness 和公开接口冒烟；执行 Playwright 核心 E2E；按 01 核对固定压测口径并判定本机是否可执行。

未向支付宝、银行、外部物流或公网回调发送请求。商家出款只在测试夹具中验证模拟路径。

## 3 必要的文件变更

- backend/src/test/java/com/yueji/mall/FoundationHttpTests.java：新增 OpenAPI 驱动的匿名访问检查。83 个受保护已实现操作均须返回 401/AUTH_REQUIRED 或 403/CSRF_INVALID；沙箱能力关闭的 API-34 明确允许 404/RESOURCE_NOT_FOUND。每项另核对空 data，并核对响应 traceId 与请求 ID 匹配。原有 92 操作未实现门禁测试继续验证不返回 200/201 假成功。
- backend/src/test/java/com/yueji/mall/MySqlTestSupport.java：测试上下文将 yueji.trade.expiry-enabled 设为 false。完整测试会重建隔离 schema；禁用由该属性控制的自动调度，消除后台任务与测试库清表之间的时序错误。生产配置默认仍启用；测试直接调用的恢复、关单和售后过期服务没有关闭。
- docs/stages/H-验证与交接.md、H-AI使用记录.md、evidence/H-verification.json：保存本轮报告、AI 留痕和机器结果。
- README.md、docs/README.md：更新到阶段 H 实测状态，明确当前门禁失败。

## 4 实施与验证

### 实际命令与结果

| 实际命令 | 结果 | 证据 |
|---|---|---|
| . ./scripts/use-tools.ps1; node --version; pnpm --version; pnpm --dir frontend install --frozen-lockfile | 成功；Node v24.22.1、pnpm 10.34.7，冻结锁文件无变更 | .artifacts/H-frontend-quality.log、H-toolchain.txt |
| pnpm --dir frontend run typecheck | 成功，无 TypeScript 诊断 | .artifacts/H-frontend-quality.log |
| pnpm --dir frontend test | 成功，3 个测试文件、10 项通过 | .artifacts/H-frontend-quality.log |
| pnpm --dir frontend run build | 成功，vue-tsc 和 Vite 构建通过，1648 个模块 | .artifacts/H-frontend-quality.log |
| pnpm --dir frontend run contract:lint | 成功，OpenAPI 描述有效 | .artifacts/H-frontend-quality.log |
| python scripts/sync-contract.py --check | 成功，92 项接口清单与共享模型同步 | .artifacts/H-contract-sync.log |
| ./scripts/use-tools.ps1; pnpm --dir frontend run test:e2e | 成功：4 passed、3 skipped、0 failed；跳过项为 desktop/mobile 项目过滤，不计入通过。含真实登录、立即购买预览、跨店预览、失败重试、角色拒绝、长文本/无封面布局与 API-89 501 边界 | .artifacts/H-playwright.log；.artifacts/H-e2e/playwright-report、playwright-results、screenshots |
| python scripts/smoke-stage-g-http.py --base-url http://127.0.0.1:18080 | 3 项通过：分类 200/OK，商品 200/OK（23 项），dashboard 501/NOT_IMPLEMENTED；都有 traceId | .artifacts/H-http-smoke.log |
| 本机 JAR 启动后读取 /actuator/health/readiness | HTTP 200，status=UP | .artifacts/H-readiness.json、H-final-jar-smoke.log |
| ./mvnw.cmd -B -ntp -Dtest=FoundationHttpTests test | 6/6 通过；新增的 83 操作匿名拒绝矩阵通过 | .artifacts/H-contract-http-retest.log |
| ./mvnw.cmd -B -ntp -Dtest=StageFHttpTests test | 20/20 通过；测试调度隔离后 job_lease 缺表异常数为 0 | .artifacts/H-scheduler-isolation-test.log |
| ./mvnw.cmd -B -ntp clean verify | 119/119 后端测试通过，0 失败、0 错误、0 跳过；V1—V10 迁移及 JAR 打包成功。最终 Maven 在 JaCoCo 门禁退出 1：992/1368 分支已覆盖，72.51%，低于 80% | .artifacts/H-backend-verify.log、backend/target/surefire-reports、backend/target/site/jacoco/jacoco.xml |

本轮最终后端测试构成为：基础/安全/会话及迁移 43 项、支付适配器 6 项、C 16 项、D 16 项、E 13 项、E 协议夹具 5 项、F 20 项，合计 119 项。所有 MySQL HTTP 集成测试均使用本机隔离测试库，无 H2。JaCoCo 门槛需要在当前分母下至少再覆盖 103 个服务分支；主要未覆盖集中在 ReconciliationService、SettlementService、AfterSaleService 和 RefundService。未为门槛失败调低阈值。

完整回归期间曾观察到清理测试 schema 时自动调度任务查询不到 job_lease 的错误。修正仅作用于测试上下文后，StageFHttpTests 20/20 通过，最终 clean verify 中该缺表错误为 0。保留的前一轮日志为 .artifacts/H-backend-verify-before-scheduler-isolation.log；最终结论只引用隔离后的 H-backend-verify.log。

FoundationHttpTests 新断言开发过程曾暴露路径正则、API-03 的 CSRF 403、API-34 的关闭态 404 与 JsonNode 类型比较问题；按既有接口安全契约逐项修正后最终定向测试 6/6 通过。前几次定向输出被最终同名日志覆盖，没有作为通过证据。首次 clean verify 还因本轮启动的 JAR 占用文件而未进入测试；确认 PID 属于本轮进程后停止并重跑。最终报告的 119 项来自其后的完整运行。

### TC-01—32 实际执行记录

状态统计：通过 22，失败 0，阻塞 2，未执行 8。TC-10 和 TC-32 为外部/版本控制前置阻塞。TC-13、TC-25—31 的完整验收条件未跑齐；部分子场景已有本地测试，但不把子场景计成整项通过。JaCoCo 质量门禁失败作为独立发布阻断记录，不混入 TC 计数。

| 编号 | 本轮真实前置、操作、断言及清理 | 状态与证据 |
|---|---|---|
| TC-01 | 隔离 MySQL 中使用测试会员和管理员；执行注册/登录、刷新轮换、重放、退出和旧令牌访问；核验角色、令牌失效及会话错误响应。测试库由完整套件重建。 | 通过。IdentityHttpTests、SessionBoundaryTests；最终 Surefire XML |
| TC-02 | 使用不同会员、不同店铺成员和管理角色调用地址、商品/库存、订单、售后、结算等接口；核验本人/本店范围、404/403 和无数据泄露。 | 通过。B/C/D/F HTTP 测试；匿名矩阵 83 操作；FoundationHttpTests |
| TC-03 | 修改/撤销测试用户会话、角色/权限或店铺成员后重用旧令牌，并检查最后管理员保护及审计事实。 | 通过。IdentityHttpTests、InitialAdministratorTests；最终 Surefire XML |
| TC-04 | 以 ISBN、书名、作者和分类查目录，检查分页/排序，以及草稿、下架、停业店铺不对外销售。 | 通过。StageCHttpTests 16 项；最终 Surefire XML |
| TC-05 | 测试地址默认切换、本人隔离和删除；对同 SKU 重复设置购物车数量，核对只保留一行及不可购状态。 | 通过。IdentityHttpTests、StageCHttpTests；最终 Surefire XML |
| TC-06 | 两店商品通过一个结算批次预览及提交，核对拆成两个独立店铺订单、金额/地址快照和库存预占。 | 通过。StageDHttpTests；evidence/D-tc06-invariants.json |
| TC-07 | 触发第二店缺货、价格/地址/购物车版本变化及第二店事务写入故障；核对全批回滚且没有孤立订单、预占或成功幂等行。 | 通过。StageDHttpTests；evidence/D-tc07-rollback.json |
| TC-08 | 同一幂等键重复提交及并发重放，再用不同参数复用；核对同参只返回原批次、异参冲突、订单和库存只写一次。 | 通过。StageDHttpTests；evidence/D-tc08-replay.json |
| TC-09 | 100 个有效会员、100 个客户端线程同时争用最后 10 本；核对最多 10 个成功、其余库存冲突，并查询数据库 on_hand/reserved 不变量。 | 通过。StageDHttpTests；evidence/D-tc09-concurrency.json |
| TC-10 | 真实支付宝买家、卖家、沙箱凭据和公网通知未提供；没有发送真实付款/通知请求。本地 LOCAL_MOCK 和 RSA 协议夹具单独验证，不能替代本项。 | 阻塞。条件见 E-验证与交接.md、E-官方协议核对.md |
| TC-11 | 用本地支付适配器及协议夹具测试伪造回跳、坏签名、交易金额/单号不匹配、重复通知；核对订单不被错误确认及差异被保留。 | 通过（本地实现范围）。StageEHttpTests、StageESandboxProtocolTests；最终 Surefire XML |
| TC-12 | 本地模拟支付与取消并发，制造关闭未知/响应丢失并查询恢复；核对状态收敛规则和未知时预占不释放。 | 通过（本地模拟范围）。StageEHttpTests；evidence/E-local-race-invariants.json、E-local-closed-invariants.json |
| TC-13 | 已重跑支付恢复服务级用例并检查稳定 paymentNo；本轮没有强杀独立 JAR 后重启并以持久数据库核对恢复。 | 未执行（仅服务级恢复子场景通过）。原因及依据见 E-验证与交接.md；StageEHttpTests |
| TC-14 | 本地模式验证 mock-confirm 权限及配置门禁；生产/沙箱配置不暴露模拟确认路由。未调用真实资金服务。 | 通过。StageEHttpTests、ProductionIsolationTests；最终 Surefire XML |
| TC-15 | 测试订单整单发货、人工轨迹、重复收货和店铺越权；核对发货数量快照及物流状态不因退款改变。 | 通过。StageFHttpTests.tc15WholeShipmentManualEventsAndReceiptKeepOrderStateSeparate |
| TC-16 | 已付款未发货订单申请部分退款，重复/超额申请并核对可退数量、状态及发货阻止规则。 | 通过。StageFHttpTests.tc16Tc17UnshippedRefundIsReservedRetriedAndRestockedOnce |
| TC-17 | 以本地适配器制造退款失败/未知/超时后重试或对账；核对复用 refundNo、成功只回补一次、金额和数量累计一致。 | 通过（本地模拟范围）。StageFHttpTests 的 TC16/17、unknownRefundResultKeepsProcessingAndInventoryHeld、reconciliationRecoversTimedOutRefundUsingSameRefundNumber |
| TC-18 | 测试退货验收可再售与不可再售两条路径；核对前者仅回补一次，后者退款但不回补。 | 通过。StageFHttpTests.tc18ReturnInspectionRestocksOnlyResalableGoodsAndReviewCanBeModerated、acceptedReturnWithoutResaleDoesNotRestock |
| TC-19 | 修改测试订单售后时间窗口，验证超时未寄回、已寄回待验收和损坏品验收路径。 | 通过。StageFHttpTests.tc19ReturnWithoutTrackingExpiresButShippedReturnRemainsPending、returnEligibilityDeadlineAndDamagedInspectionAreEnforced |
| TC-20 | 测试同订单行重复评价、售后/全退限制、超期限制与运营隐藏后的公开列表。 | 通过。StageFHttpTests.tc18…；最终 Surefire XML |
| TC-21 | 使用已完成且过窗口的订单生成结算，核对每单快照、3% 手续费四舍五入及重复创建幂等。 | 通过（模拟出款）。StageFHttpTests.tc21Tc22Tc23ReconciliationEvidenceSettlementSnapshotsAndNegativeCarryForward |
| TC-22 | 生成进行中售后、开放对账差异和创建人审核等场景；核对候选排除和职责分离。 | 通过。StageFHttpTests 结算/对账场景；最终 Surefire XML |
| TC-23 | 重放模拟出款及差异调整，核对历史 PAID 记录不可改、负余额结转且不产生负出款。 | 通过（模拟出款）。StageFHttpTests.tc21Tc22Tc23ReconciliationEvidenceSettlementSnapshotsAndNegativeCarryForward |
| TC-24 | 使用真实 JPEG/PNG/WebP 与伪扩展、路径式名称和危险尺寸夹具上传；测试私有下载的匿名、他人和跨店拒绝。 | 通过。ImageSafetyTests、StageCHttpTests；最终 Surefire XML |
| TC-25 | 已验证刷新 CSRF/旧令牌和排序参数拒绝；本轮没有执行富文本/标题 XSS 注入并在浏览器验证无脚本执行的完整场景。 | 未执行（部分子场景通过）。B-验证与交接.md；Stage G E2E 只覆盖长文本布局，不是 XSS 证据 |
| TC-26 | 核对订单事务写入 Outbox；本机没有 RabbitMQ/独立消费者，未执行发布确认丢失、消费者提交前崩溃、乱序及死信重放。 | 未执行。StageDHttpTests 的事务/Outbox 写入测试不等于消息传递验收 |
| TC-27 | 本地适配器超时/失败路径有覆盖；本轮未注入 Redis、RabbitMQ 和外部支付全链路不可用，也没有验证真实熔断恢复。 | 未执行（部分子场景通过）。无 Redis/RabbitMQ 运行环境；见 E/F 交接 |
| TC-28 | 没有在隔离部署环境执行备份恢复、RPO/RTO、容器重启或镜像回滚；测试 schema 重建不等于恢复演练。 | 未执行。Docker Linux 引擎及恢复环境不可用 |
| TC-29 | 没有执行 k6，也没有独立压测机；当前 Windows 11 / Ryzen 9 8945HX / 16 核 32 线程 / 约 15.8 GiB 环境不符合 01 的 4 vCPU、8 GB Linux SSD 固定口径，Docker daemon 不可用且找不到 k6。 | 未执行。不得引用历史或设计值为性能实测 |
| TC-30 | 没有连续 24 小时探测历史；本轮只做一次 JAR readiness 和 HTTP 冒烟。 | 未执行。H-readiness.json 是单次检查证据 |
| TC-31 | 无 Git 仓库或 CI 发布流水线，且 Docker daemon 不可用；没有提交故障测试、凭据扫描样本、坏镜像发布和回滚。 | 未执行。不是 CI 通过证据 |
| TC-32 | 本轮生成 H-AI使用记录.md，记录输入、执行摘要、模型可见信息、采纳与验证；仓库无 Git/PR，模型精确构建号及完整可导出的对话附件不可得。 | 阻塞（记录已写，端到端追溯未齐）。见 H-AI使用记录.md |

### 性能结论

01 NFR-01 要求 4 vCPU/8 GB Linux SSD、应用与中间件同机、独立压测机、10,000 本/20,000 SKU/100,000 订单、预热 5 分钟后 10 分钟混合 80 查询 RPS + 20 下单 RPS，并按 P95/错误率实测。本轮主机为 Windows 11、AMD Ryzen 9 8945HX（16 核/32 逻辑处理器）、约 15.8 GiB 可见内存；k6 未安装。Docker 客户端版本 29.6.1，但 Docker Linux daemon 不可连接。硬件、操作系统、负载器和基础设施均不满足口径，未启动压测，未产生 P50/P95/P99、RPS 或错误率结果。

## 5 尚未解决的风险和交接记录

1. H-QG-01（发布阻断）：服务分支覆盖 992/1368=72.51%，比固定 80% 门槛少至少 103 个已覆盖分支。主要缺口位于对账、结算、售后与退款服务；应按未覆盖业务分支补真实状态场景，并保留资金/库存不变量断言。禁止调低门槛、删除复杂服务或仅写镜像实现的测试来过门禁。
2. 真支付宝沙箱和公网回调输入仍缺，TC-10 阻塞。协议夹具和本地模拟结果不等于渠道交易。
3. TC-13 进程级 kill/restart、TC-25 XSS、TC-26 消息代理确认/重放、TC-27 Redis/MQ 故障、TC-28 备份恢复、TC-29 性能、TC-30 24 小时可用性、TC-31 CI/坏镜像回滚未执行。
4. TC-32 缺 Git SHA/PR、精确模型 build 信息及完整对话导出；本报告如实标注，不代填。
5. 发现 API-89 仍为 501/NOT_IMPLEMENTED；这是既有未实现契约，不是虚假通过，也没有在本阶段扩大功能范围。

**结论：阶段 H 已产出实际测试报告和证据，不能判定质量门禁通过或整体可发布。** 119 项已执行后端测试和本机前后端检查均无断言失败，但 80% 服务分支门槛失败且 8 项未执行、2 项阻塞。下一步先补足真实服务分支场景并运行 clean verify；需要真人/环境负责人提供真实沙箱、Linux 压测与恢复/CI 环境，之后才能完成剩余验收。

下一阶段直接从 H-QG-01 的 JaCoCo 报告和本交接第 4 节 TC 状态继续。保留 API-89 501、支付沙箱和真实出款边界。不要覆盖当前 H 测试和报告文件；项目没有 Git，下一位执行者应先让用户确认并建立版本控制。
