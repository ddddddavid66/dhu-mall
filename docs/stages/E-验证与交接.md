# 阶段 E 验证与交接（本地通过，真实沙箱待联调）

## 1 现状与设计依据

更新日期：2026-10-25。阶段 D 的订单、库存预占和状态机基线通过；本阶段按 AGENTS.md、01 FR-06/07、02 第 3—5/8 节、03 第 7 节、04 API-32—35、06/07/08/12 实施。业务规则、字段、状态及接口均沿用对应基线。

03 原定 `refund.reason_type VARCHAR(24)` 无法容纳 25 字符的既定值 `LATE_PAYMENT_COMPENSATION`。按 AGENTS.md 暂停受影响补偿写入后，用户于 2026-10-25 明确回复“同意扩为 VARCHAR(32) 并记录”。现已在 ADR-17 记录决定、同步 03，并追加 V9 修改字段及唯一函数索引表达式；V1—V8 文件不改写，枚举、约束、接口、金额规则和状态机不变。原冲突记录保留在 [E-待决策变更.md](E-待决策变更.md)。

阶段 E 的代码、迁移、契约与本地回归均通过。**阶段整体仍未全通过**：支付宝沙箱的应用、商户、公私钥、HTTPS 回调和买家账号未提供，真实支付/通知/退款没有执行。不得把签名夹具、SDK 模拟响应或 LOCAL_MOCK 测试称为真实沙箱联调。

项目没有可用的代码图谱索引，按 AGENTS.md 回退到文件搜索。当前目录不是 Git 仓库，无法提供提交或 PR。已核对 V1—V7 与 D 阶段公开摘要哈希一致；开发库没有升级到 V8/V9，也没有新增演示交易数据。

## 2 当前阶段范围

API-32 创建或复用会员本人订单的唯一 paymentNo；API-33 查本地支付状态；API-34 仅在 dev/test、LOCAL_MOCK 且显式开启时注册；API-35 接收渠道表单并返回纯文本。客户端不能决定 provider、金额、支付状态或身份范围，回跳不确认付款。OpenAPI 0.5.0 将 56 项标为已实现、36 项明确未实现，API-34 仍受运行时配置限制。

每笔订单仅一个 paymentNo。支付受理先持久化 PENDING 和幂等资源号，渠道调用在数据库事务外执行；结果不明时用原单号查单。关闭未知保留 CLOSING 和库存预占。已取消订单收到迟到付款时只更新财务事实，创建唯一全额原路补偿退款和差异记录，不复活订单、不重新占库存。模拟渠道事实写入 payment_event，支持业务提交失败后的恢复；商家出款及真实资金结算未实现。

V8 建立 payment、payment_event、refund、reconciliation_run、reconciliation_issue 五张既定结构；V9 把 refund.reason_type 和对应唯一表达式扩到 32 字符。即时差异可落库，但没有实现每日账单对账或 Broker 发布消费。未新增前端付款页面。

## 3 必要的文件变更

- 后端新增 payment 模型、适配器、仓储、服务与 API 控制器；增加支付宝沙箱适配器、本地模拟、回调验签、主动查单、关闭恢复及迟到退款补偿。与既有 trade、inventory 服务协作，按订单→订单行→升序 SKU 库存→预占→财务记录锁定，原子写状态、流水、日志及 Outbox。
- 数据库新增 V8 支付/差异表结构和 V9 字段/函数唯一索引兼容修正；V1—V8 历史迁移保持原样。配置隔离 LOCAL_MOCK 与 ALIPAY_SANDBOX，生产禁止模拟入口；示例文件不含凭据。
- 支付宝 SDK、排除项和解析依赖已冻结在 `infra/versions.stage-e.lock.json`、`infra/backend-dependencies.lock.txt`。使用 RSA2 普通公钥、固定沙箱网关、UTF-8 JSON；不启用证书/SM2 模式。官方参数核对见 [E-官方协议核对.md](E-官方协议核对.md)。
- OpenAPI 同步脚本、契约检查、前后端测试和证据生成脚本已更新；AI 使用记录与 ADR-17 已填写。

## 4 实施与验证

### 实际命令和结果

以下命令均实际执行。Maven 命令在 `backend` 目录运行；所有密钥和数据库口令只通过现有本机环境变量传递，没有打印到输出。

| 命令 | 实际结果 | 证据 |
|---|---|---|
| `./scripts/bootstrap-stage-b-db.ps1` | 恢复项目自有 MySQL 8.4.13 `127.0.0.1:13306`；脚本确认不会访问系统 MySQL80，测试库/迁移库隔离 | `.artifacts/B-mysql.stdout.log`、`.artifacts/B-mysql.stderr.log` |
| `./mvnw.cmd -B -ntp '-Dtest=StageEHttpTests#latePaymentCreatesOneCompensationAndNeverReacquiresReleasedStock' test` | 1 项通过：迟到支付补偿成功、重复处理不重复退款、取消订单不恢复库存 | `.artifacts/E-late-compensation.log`、`evidence/E-local-late-compensation.json` |
| `./mvnw.cmd -B -ntp '-Dtest=MySqlMigrationTests' test` | 2 项通过：空库完整 V1—V9 迁移、V1 旧行前滚保留、重复启动无额外迁移、字段宽度为 32 | `.artifacts/E-migration.log` |
| `./scripts/verify.ps1` | 7/7 步骤退出码 0；后端 `clean verify` 为 98 项通过、0 失败/错误/跳过；前端 7 项通过，类型检查、构建和契约检查通过；JaCoCo 服务分支门禁通过 | `.artifacts/E-verify.log`、`.artifacts/verification-results.json`、`.artifacts/backend-verify.log`、`backend/target/site/jacoco/index.html` |
| `python scripts/record-stage-e-evidence.py` | 按实际命令结果生成机器证据，并校验全回归成功、补偿测试、迁移及库存不变量 | `evidence/E-verification.json` 及 `evidence/E-local-*.json` |
| 查询 `yueji_mall_dev.flyway_schema_history` 最新成功版本 | 只读结果为 V7，证明本次没有将 V8/V9 应用到开发库 | `.artifacts/E-dev-db-version.txt` |

首轮完整回归因 `VARCHAR(24)` 无法写入既定原因值而失败；按用户决策追加 V9 后，实际补偿用例发现 MySQL 函数唯一索引仍保留 24 字符表达式，遂在同一 V9 重建为 `CHAR(32)` 并再次通过完整回归。Maven 包含 Boot 可执行 JAR；没有启动该 JAR 对开发库应用迁移或做独立进程重启测试。

完整回归覆盖真实 MySQL HTTP、支付幂等、并发确认/关闭、关闭未知时库存保留、channel event 恢复、MySQL 事务故障回滚、金额/交易号不匹配、重复通知与迟到补偿；沙箱协议测试使用临时生成的 RSA 密钥和受控 SDK/HTTP 响应。此类测试验证实现路径，不发起真实网络资金请求。`E-verification-preapproval.json` 及对应日志保留第一次未全通过的报告，避免覆盖历史结果。

工具链实测：Temurin Java 21.0.12.1+1、Maven 3.9.16、Node 24.22.1、pnpm 10.34.7，完整记录见 `.artifacts/E-toolchain.txt`。迁移仅在 `yueji_mall_test` 和 `yueji_migration_test` 验证；没有 H2。开发库没有写入 V8/V9。

### TC-10—14 结果边界

| 编号 | 已验证的本地能力 | 真实支付宝沙箱 |
|---|---|---|
| TC-10 | LOCAL_MOCK 同键重放和并发确认只提交一次库存/PAID；有效签名通知夹具重复处理只生效一次 | 未执行；无沙箱凭据/买家/公网回调，不计通过 |
| TC-11 | 错签名、错误 app/seller/币种/金额、未知单号、原文篡改、缺字段、异常状态/时间及伪造回跳均不能确认付款 | 未执行真实渠道攻击与回调 |
| TC-12 | 并发关闭/确认最终一致；未知关闭保持 CLOSING 和预占；迟到支付创建一次补偿退款并把财务记为退款完成，不复活订单、不重占库存 | 未执行真实关单/退款和迟到付款 |
| TC-13 | 本地持久渠道事实、业务事务故障回滚后按原 paymentNo 恢复；库存提交恰一次 | 未强杀独立 JAR 进程，也未做真实渠道断网/崩溃联调 |
| TC-14 | 会员归属、权限、沙箱模式屏蔽模拟路由、生产禁止 LOCAL_MOCK 检查通过 | 只验证配置与隔离；不构成真实支付证据 |

机器汇总 [E-verification.json](evidence/E-verification.json) 保留实际测试数量、分支覆盖门禁、迁移与依赖哈希。库存不变量摘要不包含电话、地址、凭据或原始签名。

## 5 尚未解决的风险和交接记录

**待环境负责人提供**：独立支付宝沙箱应用/商户、匹配的应用私钥和支付宝公钥、已开通电脑网站支付/查单/关单/退款、可用沙箱买家、可公网访问的 HTTPS 通知与回跳 URL。检查时 `ALIPAY_APP_ID`、`ALIPAY_SELLER_ID`、`ALIPAY_APP_PRIVATE_KEY`、`ALIPAY_PUBLIC_KEY`、`ALIPAY_NOTIFY_URL`、`ALIPAY_RETURN_URL` 六项进程输入均缺失；只需由负责人安全注入，勿放入文档、前端或仓库。真实沙箱证据保持空缺，不生成截图或成功记录。

环境具备后，先在独立 `ALIPAY_SANDBOX` 配置下核验 RSA2 通知、同单查单、关单未知恢复和原路退款；逐项补实际时间、脱敏商户标识、paymentNo/refundNo、渠道单号及证据位置，再重新决定阶段 E 是否通过。原 paymentNo、provider 和 refundNo 必须保持稳定。不要手工改订单状态或库存。

未执行项目及原因：真实支付宝付款/退款与公网回调（外部输入缺失）；独立 JAR 强杀重启（本地故障窗口用数据库事实与真实事务失败覆盖，未声称进程级验证）；浏览器支付 E2E、Docker/Linux、全量漏洞扫描、远程 CI/CD、标准负载/灾备恢复、Broker 发布消费（不属当前已完成验证范围）。完整 Maven 构建产生 JAR，但不等于 JAR 启动冒烟。没有留下本次启动的应用进程；项目 MySQL 13306 保留供后续开发。AI 使用记录、依赖版本冻结和审批记录均已更新。
