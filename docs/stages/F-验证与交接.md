# 阶段 F 验证与交接

日期：2026-10-25，Asia/Shanghai。本文按 `AGENTS.md` 输出实际依据、范围、文件变更、验证结果、风险和下一步。阶段 F 的业务验收集成用例已通过；项目级 Maven `clean verify` 仍未通过 JaCoCo 服务分支门槛，因此项目检查不能标为全通过。

## 1 现状与设计依据

阶段 F 按 01 FR-08—11、02 第 3—5 节状态机与锁规则、03 持久化字段、04 API-36—62/API-69/API-78—83/API-91、07 TC-15—23 实施。E 本地模拟闭环用于测试；支付宝真实沙箱联调独立记录，未在本阶段伪造或调用真实支付/退款。业务字段、金额表达、状态和接口契约未变。阶段 E 经用户同意的 V9 `reason_type VARCHAR(32)` 保持不变。

开始检查时，代码图谱未提供此项目索引，按 `AGENTS.md` 使用文件搜索；当前目录无 Git 仓库，无法给出提交差异、分支或 SHA。按 02 先核对并记录实际调用与锁边界，见 [F-服务调用与锁边界](F-服务调用与锁边界.md)。该文档描述本轮代码的服务归属、数据库事务和渠道事务外调用，不作为新的业务契约。

## 2 当前阶段范围

阶段 F 增加整体发货和手工轨迹、会员确认收货、售后申请/客服审核/退货寄回和仓库验收、退款执行及事实恢复、评价审核、对账批次与差异处理、结算候选/审批/模拟出款和差异调整。订单物流 `status` 与支付退款 `financial_status` 分开。未发货退款只有渠道成功后才回补；退货仅可再售验收时回补，两路径都用唯一业务键保护；渠道未知不提前完成退款。结算按订单保存快照、排除进行中售后/退款/差异、创建人和审批人分离，已出款记录受数据库约束保护。

OpenAPI 与 04 同步共 92 项操作：91 项标记 `IMPLEMENTED`，API-89 明确保留未实现。商家出款只支持模拟凭证。没有新增前端业务页面；前端仍处于工程占位状态，业务操作页面需后续前端范围完成。

## 3 必要文件变更

- 后端新增履约、售后、评价、退款/对账及结算模块的 API、服务和数据访问；使用现有权限和会员/店铺范围，不接受客户端身份、金额或任意状态。
- V10 追加 shipment、shipment_item、shipment_event、settlement、settlement_item、settlement_adjustment 及历史付款/结算保护约束；V1—V9 文件未回写。迁移按拓扑关系应用到隔离测试库。
- OpenAPI/契约生成与清单同步；新增/扩充 `StageFHttpTests` 场景，覆盖退款超时恢复、退款累计与回补、退货可再售/不可再售、全额退款评价限制、迟到补偿、结算快照及模拟出款重放。
- `F-服务调用与锁边界.md`、本交接、AI 使用记录和机器可读验证摘要。

没有实现支付渠道真实联调、银行出款、真实物流追踪或前端业务页面；没有修改需求/接口/数据库设计基线。

## 4 实施与验证

所有 Maven 命令在 `backend` 运行，使用项目冻结工具链 Java 21/Maven 3.9.16 和本机隔离 MySQL 测试库。数据库口令从忽略目录的 `.tools/stage-b-db.json` 仅注入测试进程，没有写入日志或本文。

| 实际命令 | 实际结果 | 证据 |
|---|---|---|
| `./mvnw.cmd -B -ntp -Dtest=StageFHttpTests test` | 20 项通过，0 失败、0 错误、0 跳过 | `.artifacts/F-stagef-tests.log`、`backend/target/surefire-reports/TEST-com.yueji.mall.StageFHttpTests.xml` |
| `./mvnw.cmd -B -ntp clean verify` | 全量 118 项测试 118/118 通过，0 失败/错误/跳过；V1—V10 迁移和 JAR 打包通过；Maven 最终因服务包 JaCoCo 72.51% < 80% 门槛退出失败 | `.artifacts/F-backend-verify.log`、`backend/target/surefire-reports`、`backend/target/site/jacoco/jacoco.xml` |
| `python scripts/sync-contract.py --check` | 92 项操作与共享契约同步 | 命令实测输出：`Contract and inventory: 92 operations; in sync` |
| `pnpm --dir frontend run typecheck` | Vue/TypeScript 类型检查通过 | `.artifacts/F-frontend-typecheck.log` |
| `pnpm --dir frontend run test` | 7 项通过 | `.artifacts/F-frontend-tests.log` |
| `pnpm --dir frontend run build` | 类型检查和 Vite 构建通过，1627 个模块转换 | `.artifacts/F-frontend-build.log` |
| `pnpm --dir frontend run contract:lint` | OpenAPI lint 通过 | `.artifacts/F-frontend-contract-lint.log` |

开发期间首次阶段 F 断言运行曾因使用错误 JSON 字段名、随后按单条差异断言导致 2 项/1 项测试失败；测试断言修正后，定向复测及最终 20 项套件通过。第一次全量构建为 117 项测试通过但同样停在 JaCoCo 门槛；最终一轮 118 项结果和门槛日志保存在表中路径，首轮中间日志已被最终重跑覆盖，未作为最终证据。

### TC-15—23 实际结果

| 验收 | 实际证据及结论 |
|---|---|
| TC-15 | `tc15WholeShipmentManualEventsAndReceiptKeepOrderStateSeparate`：整体发货仅生成一份 shipment，手工轨迹可见，会员重复确认收货安全；物流状态不因退款改写；越权店铺成员被拒。通过。 |
| TC-16—17 | `tc16Tc17UnshippedRefundIsReservedRetriedAndRestockedOnce`：重复部分申请被拒，库存/退款累计按数量和金额核对；渠道失败后同一退款事实重试成功，未发货库存流水仅一条。`reconciliationRecoversTimedOutRefundUsingSameRefundNumber`：未知结果保持 PROCESSING，通过原 refundNo 对账恢复成功且只回补一次。通过。 |
| TC-18 | `tc18ReturnInspectionRestocksOnlyResalableGoodsAndReviewCanBeModerated` 与 `acceptedReturnWithoutResaleDoesNotRestock`：可再售验收产生一次回补，不可再售仍可退款但不回补。通过。 |
| TC-19 | `tc19ReturnWithoutTrackingExpiresButShippedReturnRemainsPending`、`returnEligibilityDeadlineAndDamagedInspectionAreEnforced`：超期未寄回过期，已寄回不被自动拒绝；售后时限、寄件期限和不合格验收路径均核对。通过。 |
| TC-20 | TC18 覆盖重复评价、超 30 天、运营隐藏及公开列表过滤；TC16—17 全额退款完成后验证整行不得评价。通过。 |
| TC-21—23 | `tc21Tc22Tc23ReconciliationEvidenceSettlementSnapshotsAndNegativeCarryForward` 与配套场景：对账差异必须解决后才能结算，手续费逐单取分保存快照、同键重放、职责分离、模拟出款凭证重放、已出款快照不可改、晚到退款仅生成调整、负余额结转且禁止出款。通过。 |

“通过”指本地真实 MySQL 与 HTTP 集成/LOCAL_MOCK 事实场景，不代表支付宝真实沙箱、银行资金或真实商家出款。全量后端报告最终为 118 项测试通过，0 失败、0 错误、0 跳过；V1—V10 空测试库迁移通过，并已打包 JAR。随后 Maven 在 `jacoco:check (core-branch-gate)` 失败：服务包覆盖 992/1368 个分支，即 72.51%，项目门槛为 80%。因此 `clean verify` 退出失败，不能记为 Maven 全检查通过。报告：`.artifacts/F-backend-verify.log`、`backend/target/site/jacoco/jacoco.xml` 和 `backend/target/site/jacoco/index.html`。门槛未调低。

完整机器结果见 [F-verification.json](evidence/F-verification.json)。全量回归期间还观察到测试库迁移/重建窗口内定时调度任务短暂查询不到 `job_lease` 的日志；Junit 最终 118 项全部通过。建议后续把需要破坏性重建测试 schema 的用例与运行中 Spring 定时任务进一步隔离，避免偶发噪声或时序干扰。

## 5 尚未解决的风险和交接记录

1. **项目级覆盖门禁仍失败**：当前服务包 72.51%，低于现有 80% 门槛。下一执行者应按未覆盖服务分支补充有业务意义的用例，不得降低 POM 门槛或排除阶段 F 服务来伪造通过。
2. **E 真实沙箱依赖未提供**：支付宝沙箱应用/商户、匹配密钥、沙箱买家和公网 HTTPS 通知尚缺。所有本地支付、退款及对账结果与真实沙箱证据分开；商家出款始终模拟。
3. **前端页面尚未做**：此次交付服务端 API 和契约，没有履约/售后/财务 UI。若下一阶段转向前端，请按 `AGENTS.md` 读取 05 并使用已生成的 OpenAPI/TypeScript 契约。
4. **后台调度器测试隔离**：迁移测试清库/重建时出现瞬时缺表错误日志，虽测试通过但需复核调度器在 test profile 的启停策略。
5. **工程状态**：目录没有 Git 仓库，未创建提交/PR。当前本机 MySQL 是项目现有隔离服务；没有真实第三方支付、真实退款或商家出款调用。

已执行前端类型检查、7 项单测、构建、契约 lint、92 项契约同步检查、阶段 F 20 项集成测试和全量后端 118 项测试；Maven 仅因 JaCoCo 门槛失败。未执行真实支付宝联调、公网回调、真实出款、浏览器业务页面 E2E、远程 CI/CD、标准性能/备份恢复演练；其中支付/出款受环境和范围约束，UI 页面不在本阶段实施范围。

下一阶段可继续补充服务分支测试并稳定全量门禁，再按产品前端阶段实现履约、售后、评价和财务页面。不要改写已出款历史、手工改支付/退款或订单状态、清除库存流水，也不要把本地模拟称为沙箱结果。
