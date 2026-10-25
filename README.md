# 阅集图书商城

业务与页面实施至阶段 G；阶段 H 于 2026-10-26 执行质量验证，阶段 I 已增加容器、CI、监控及 test 发布/恢复产物，但尚无实际 Linux 部署或 CI run。后端测试 119/119、前端 Vitest 10/10、Playwright 4 passed/3 skipped；Maven clean verify 未通过 JaCoCo 服务分支门槛：992/1368（72.51%），低于 80%。因此当前不是全质量通过或可发布状态，I 流水线也会阻止 manifest 晋级。API-89 仍真实返回 501/NOT_IMPLEMENTED，支付宝真实沙箱仍缺环境输入。阶段 H/I 交接见 [H 验证与交接](docs/stages/H-验证与交接.md)、[I 验证与交接](docs/stages/I-验证与交接.md)、[I 发布与恢复清单](docs/stages/I-发布与恢复清单.md)、[I AI 使用记录](docs/stages/I-AI使用记录.md) 和 [I 机器证据](docs/stages/evidence/I-verification.json)。设计入口为 [docs/README.md](docs/README.md)，实施规则为 [AGENTS.md](AGENTS.md)。Java 21 / Spring Boot 3.5 模块化单体，Vue 3 独立前端。

## 当前能力

服务端已有 91 项接口能力；API-89 仍明确标记未实现。完整 [92 项接口状态](docs/stages/F-接口契约清单.md) 与 OpenAPI 0.5.0 同步。模拟支付仅 dev/test + LOCAL_MOCK + ENABLE_MOCK_PAYMENT=true 注册，其余配置返回 404；商家出款始终为模拟。实现标记不代表真实沙箱验收通过。阶段 G 前端页面及本地联调的测试边界见 [G 验证与交接](docs/stages/G-验证与交接.md)；API-89 未被静态数据掩盖。

## 阶段 H 完整质量验证

完整后端 clean verify 实际运行 119 项测试，全部通过；JaCoCo 核心服务分支 72.51% 未达到 80%，故 Maven 退出失败。前端质量检查全部通过，本轮 Playwright 为 4 passed、3 skipped；单次本地 readiness 和 HTTP 冒烟通过。真实沙箱、进程级恢复、消息代理故障、备份恢复、固定 Linux 性能、24 小时探测和 CI 回滚仍未执行。逐项 TC-01—32 状态、实际数据和证据见 [H 阶段交接](docs/stages/H-验证与交接.md)；本阶段不得视为全通过。

## 阶段 I CI/CD、监控与恢复产物

已新增非 root 后端镜像、Vue/Nginx 镜像、digest 锁定 Compose、Prometheus/Grafana/Loki 配置、PR 无部署 secrets 的质量流水线、同一成功 run 的 digest manifest 晋级和 test-only 手工部署入口，以及维护窗备份、隔离恢复和应用 digest 回退脚本。静态检查、Compose 配置解析和 shell 语法检查通过；本机 Windows Docker daemon 未运行，目标目录没有 Git 元数据，Linux/CI 主机未提供。TC-26—31 仍按未执行/阻塞记录，日志 shipper 和告警通知接收器未配置。H 的 80% 分支覆盖门槛仍未通过，因此没有可部署 manifest，未执行 test 或 prod 发布。实际情况见 [I 阶段交接](docs/stages/I-验证与交接.md)、[发布与恢复清单](docs/stages/I-发布与恢复清单.md)、[机器证据](docs/stages/evidence/I-verification.json)。

## 阶段 G 前端页面与联调

商城、会员中心、工作台、商家仓库、客服财务、运营和管理员路由按 UI-01—33 建立。共享 API 层复用 OpenAPI 操作、错误码、Result 和 traceId；401 刷新合并、幂等写请求复用键、店铺切换取消旧请求。前端显示服务器金额和业务状态，API-89 显示真实未实现错误。阶段 G 本地 HTTP 冒烟为分类/商品 200、dashboard 501；商品共 23 项。浏览器测试包含手机跨店预览、会话失败恢复、角色拒绝和桌面长文本检查。

验证记录、截图、未执行项和下一步见 [G 验证与交接](docs/stages/G-验证与交接.md)、[G AI 使用记录](docs/stages/G-AI使用记录.md) 与 [机器证据](docs/stages/evidence/G-verification.json)。本地 UI 用例没有创建订单、请求支付或退款；E2E 为 4 项通过、3 项项目过滤跳过，不是 UI-01—33 每个业务分支均已浏览器验收。远程 CI/CD、真实沙箱及全量角色 E2E 仍未运行。

Flyway V1—V7 原有 30 表；V8 新增支付/差异结构，V9 扩展退款原因字段和唯一索引，V10 新增履约与结算结构。内置 7 类角色和 38 项动作权限。阶段 F 将订单物流状态与退款财务状态分开；退货可再售和未发货退款各自按业务键恰好回补一次。对账逐笔核实并记录差异；结算保存逐订单快照、职责分离审核和不可改写的已出款记录。readiness 检查真实数据库；UP 不代表交易能力可用。

## 阶段 F 履约、退款与结算

阶段 F 依据 FR-08—11、API-36—62/API-69/API-78—83/API-91 实现。实施范围、实际调用及锁顺序见 [F 服务调用与锁边界](docs/stages/F-服务调用与锁边界.md)；验证结论与交接见 [F 验证与交接](docs/stages/F-验证与交接.md)。StageFHttpTests 20 项通过，全量 118 项测试通过；但 clean verify 的 JaCoCo 服务包分支覆盖率为 72.51%，未达到 80% 门槛，不能把 Maven verify 记为通过。

## 阶段 E 支付与当前阻塞

每订单唯一 paymentNo，先提交 PENDING 再在事务外生成渠道动作/查单；原号恢复，用户回跳不能确认支付。CLOSING 仅在渠道核实关闭或确认从未发起支付后释放，未知继续预占、退避查询并记录告警。支付和关单在同一订单锁下收敛，扣减/释放与流水、订单日志和 Outbox 同事务，Outbox 不冒充已发布。

迟到支付补偿原因 `LATE_PAYMENT_COMPENSATION` 长 25 字符。用户已同意字段扩为 VARCHAR(32)，按 ADR-17 由 V9 同步重建函数唯一索引，保留每笔支付只创建一次补偿退款的约束；决策和迁移结果见 [变更记录](docs/stages/E-待决策变更.md)。真实沙箱仍未联调，因此 E 不标记为全通过。

真实沙箱的应用/卖家、私钥/支付宝公钥、买家、能力开通与公网回调尚未提供；[官方协议核对](docs/stages/E-官方协议核对.md) 与一次性 RSA 协议测试不是实际沙箱交易证据。支付配置名称见 [.env.payment.example](.env.payment.example)；该文件不会被 Spring 自动加载，需按进程环境注入，不能把密钥传入前端。

本地调试可在准备好的 PowerShell 会话设置 `$env:PAYMENT_PROVIDER='LOCAL_MOCK'` 与 `$env:ENABLE_MOCK_PAYMENT='true'`，Linux 使用 `export PAYMENT_PROVIDER=LOCAL_MOCK ENABLE_MOCK_PAYMENT=true`。生产 profile 禁止 LOCAL_MOCK；沙箱必须显式 ALIPAY_SANDBOX，网关只允许冻结沙箱地址。先解决阻塞并通过验证，再按后文方式启动 JAR。

执行 `./scripts/verify.ps1` 记录真实结果，不得忽略失败将其写为通过。当前交接见 [G 验证与交接](docs/stages/G-验证与交接.md)、[G AI 使用记录](docs/stages/G-AI使用记录.md)、[F 验证与交接](docs/stages/F-验证与交接.md) 与 [F AI 使用记录](docs/stages/F-AI使用记录.md)；支付沙箱阻塞见 [E 验证与交接](docs/stages/E-验证与交接.md)。G 本地前端验证不替代根级脚本的后端覆盖门槛。

[D 交接及证据](docs/stages/D-验证与交接.md) 保留为当时基线；smoke-stage-d.py 的“支付未实现、CLOSING 不释放”断言只适用于 D，不是当前 E 的验收脚本。原 D 联调订单不得手工改账或清除预占；待 E 核实关闭后自动收敛。

## 阶段 C 联调数据与文件

先准备下文工具链、MySQL 和 B 的随机开发账号，关闭正在使用 JAR 的后端进程后验证：

```powershell
./scripts/verify.ps1
```

然后用 dev 配置启动后端；可沿用下文 Maven 启动方式，或从项目根运行 JAR：

```powershell
. ./scripts/use-tools.ps1
. ./scripts/use-db.ps1
java -jar backend/target/yueji-mall-0.1.0-SNAPSHOT.jar --spring.profiles.active=dev --server.port=18080
```

另开终端生成联调数据：

```powershell
python scripts/seed-stage-c.py --backend http://127.0.0.1:18080 --confirm-dev
```

脚本先核对当前契约、JAR、集成测试和覆盖门禁，仅使用本机 dev 的 B 随机账号，通过正式 API 生成 10 分类、20 虚构书目、30 SKU、3 店铺，以及 reader_a 的 3 条跨店购物车（正常、缺货、下架各一）。有同 ISBN 多店报价、草稿、最后 10 本等场景。它遵守默认限流，可能等待下一分钟；不直接写库、不创建订单/售后/评价，重复运行只验证。已有无清单的目录数据或 IN_PROGRESS 清单时停止，人工核对后再处理，不能清库重跑。结果 ID 在 .artifacts/C-demo-data.json，账号口令仍在 .artifacts/B-dev-accounts.json。

文件由 FILE_STORAGE_ROOT 指定，use-db.ps1 指向项目 .artifacts/storage/dev，test 另用隔离目录；prod 必须显式设置持久卷根。上传接受 JPEG/PNG/WebP，真实解码、大小/尺寸检查后统一存 PNG。只允许公开封面目录单独映射，整个存储根及 private 目录不得交给 Nginx/前端静态服务；私有凭证通过 `/api/v1/files/{id}/content` 带 Bearer 鉴权，响应不提供公开 URL。

## 准备工具与隔离数据库

Windows PowerShell 在项目根目录执行：

```powershell
./scripts/bootstrap-tools.ps1
. ./scripts/use-tools.ps1
./scripts/bootstrap-stage-b-db.ps1
. ./scripts/use-db.ps1
pnpm --dir frontend install --frozen-lockfile
```

工具装入忽略的 `.tools`，不更改系统 Java/Node 默认值。版本与校验和见 [阶段 A](infra/versions.lock.json)、[阶段 B](infra/versions.stage-b.lock.json)、[阶段 C](infra/versions.stage-c.lock.json) 冻结。pnpm-lock.yaml 固定前端依赖；后端固定 BOM/显式版本，解析快照见 infra/backend-dependencies.lock.txt。

数据库脚本仅管理项目自己的 MySQL 8.4.13、127.0.0.1:13306，不访问系统 MySQL80。创建独立开发库 yueji_mall_dev、测试库 yueji_mall_test、迁移验证库 yueji_migration_test 和随机凭据；发现端口被其他进程占用时停止。数据库口令位于忽略的 `.tools/stage-b-db.json`。开发实例显式允许库级权限创建审计触发器；生产应由 DBA 预置受控迁移权限，不能给运行账号 SUPER。

Linux 开发者按冻结版本准备 Java 21、Node 24、pnpm 10、MySQL 8.4，并配置 DB_URL/DB_USERNAME/DB_PASSWORD。测试另需 YUEJI_TEST_DB_URL、YUEJI_TEST_DB_USERNAME、YUEJI_TEST_DB_PASSWORD；仅允许本机指定测试库，测试会重建这些测试库中的表。没有 H2 替代或静默跳过。容器运行与部署流水线验证留待阶段 I。

## 启动与可选开发种子

后端终端：

```powershell
. ./scripts/use-tools.ps1
. ./scripts/use-db.ps1
Set-Location backend
./mvnw.cmd spring-boot:run
```

前端终端：

```powershell
. ./scripts/use-tools.ps1
pnpm --dir frontend dev
```

前端 http://localhost:5173；后端 127.0.0.1:8080。dev 下开放 `/v3/api-docs`、`/swagger-ui.html`、`/actuator/health/liveness`、`/actuator/health/readiness`。开发代理保留 `/api/v1`，地址见 frontend/.env.development.example；禁止将密钥放进 VITE_*。

种子默认关闭。在空开发库首次启动前，从项目根目录显式设置：

```powershell
$env:DEV_SEED_CREDENTIALS_FILE = Join-Path (Get-Location) '.artifacts/B-dev-accounts.json'
$env:YUEJI_DEVSEED_ENABLED = 'true'
```

Spring 宽松绑定读取 YUEJI_DEVSEED_ENABLED。仅 dev 且无 prod 时有效；生成 14 个虚构账号、3 家店铺、6 条成员关系，包含两个独立财务账号。每个账号口令独立随机生成，BCrypt 存库，明文只写指定本地忽略文件；文件已存在时拒绝覆盖。已有用户时不重置、不补造业务数据。保存后关闭种子开关；prod 不允许开发种子。

## 会话与生产配置

API-02 返回访问 JWT、900 秒有效期、csrfToken 与服务端解析的用户范围；刷新令牌只在 HttpOnly、SameSite=Lax、Path=/api/v1/auth Cookie。API-03/04 必须同时发送该 Cookie、精确匹配 ALLOWED_ORIGINS 的 Origin 和 X-CSRF-Token。每次刷新更新 Cookie 与 csrfToken，整个族首次登录后 7 天到期；旧令牌重放撤销整个族。业务接口只接受 Bearer，客户端不能指定当前会员/店铺角色。后续前端须将访问令牌保存在内存，串行处理刷新，不能自动重放旧刷新请求。

退出、改密、禁用和权限/成员变更立即使相关会话失效。默认登录 IP 限流 10 次/分钟、账号失败 5 次锁定 15 分钟，已认证写请求每用户 30 次/分钟；内存限流对应当前单实例部署。测试显式放宽至 1000，默认阈值另有边界测试。默认地址在用户锁内切换，数据库唯一约束兜底；其他用户地址返回 404。

dev 可临时生成进程内 JWT 密钥，重启后旧访问令牌需刷新；prod 必须安全注入至少 32 字节随机值的 Base64 JWT_SIGNING_KEY，以及 DB_* 配置。不要将真实密钥写进文件或命令记录。prod 强制 Secure Cookie、关闭文档、管理端口 9081 回环监听；ALLOWED_ORIGINS 配置真实 HTTPS 前端来源。

空生产库可首次启动时显式启用 `YUEJI_BOOTSTRAPADMIN_ENABLED=true`，通过 INITIAL_ADMIN_USERNAME / INITIAL_ADMIN_PASSWORD 注入管理员凭据；仅 prod 且非 dev、用户表为空时执行。完成后移除这三个输入。已有用户时拒绝重建；无公开初始化接口，无生产迁移口令。

## 验证、构建与交接

```powershell
./scripts/verify.ps1
```

依次验证契约漂移、冻结安装、类型、前端测试/构建、OpenAPI lint、真实 MySQL 后端 clean verify 及覆盖门禁；失败即停止。运行中的 JAR 必须先关闭，避免 Windows 文件锁；clean 确保覆盖率不累加旧轮次。日志在 `.artifacts`，JUnit XML 在 backend/target/surefire-reports。JAR 为 backend/target/yueji-mall-0.1.0-SNAPSHOT.jar，前端为 frontend/dist。

`python scripts/sync-contract.py --check` 检查 92 项清单、静态 OpenAPI 与共享 TS 类型。新增实现必须同步请求/响应 schema、状态、门禁、安全规则和测试；不能全局删除占位门禁。

- [阶段 D 验证与交接](docs/stages/D-验证与交接.md)
- [阶段 D 版本冻结](docs/stages/D-版本冻结.md)
- [阶段 D AI 使用记录](docs/stages/D-AI使用记录.md)
- [阶段 C 验证与交接](docs/stages/C-验证与交接.md)
- [阶段 C 版本冻结](docs/stages/C-版本冻结.md)
- [阶段 C AI 使用记录](docs/stages/C-AI使用记录.md)
- [阶段 B 版本冻结](docs/stages/B-版本冻结.md)
- [阶段 B 验证与交接](docs/stages/B-验证与交接.md)
- [阶段 B AI 使用记录](docs/stages/B-AI使用记录.md)
- [阶段 A 历史交接](docs/stages/A-验证与交接.md)
