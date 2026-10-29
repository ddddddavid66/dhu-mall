# 阅集图书商城

2026-10-29最终交付：按用户要求仅一个源码/数据库/部署脚本压缩包和三个Word，入口见[最终交付说明](docs/18-最终交付说明.md)及[最终测试报告](docs/19-最终测试报告.md)。沙箱真实付款、有效通知、全额退款和退款查询已核实；未付款关单未确认。24小时探测已取消，其他演练不再追加。以下旧阶段说明保留为历史快照，当前交付状态以上述两份文档为准。

Java21 / Spring Boot3.5.16模块化单体，Vue3独立前端，多商家实体新书商城。每店独立订单和支付；商家出款始终模拟。数据库V11，OpenAPI0.7.0，92项操作已实现。

截至2026-10-26，本地完整后端178项通过，服务分支1173/1463=80.1777%，达到80%门槛；前端单元10项、桌面手机真实浏览器5项通过。已实际运行Redis/RabbitMQ、容器、日志监控告警、进程SIGKILL恢复、加密隔离恢复和前端digest回退。首两次GitHub CI中的后端/浏览器/前端/CodeQL通过；非root配置问题已由04c562f修复并通过本地和第三次远端源码安全扫描，镜像晋级结果见CI记录。不是全课程验收通过或已上线。

用户已取消Boot4/Spring7及兼容补丁升级，接受当前安全发现的本地使用风险；保留扫描失败。支付宝沙箱只读查单取得真实应答，因买家暂不能登录，付款、有效通知、关闭与退款未验收。24小时可用性探测于2026-10-26按用户明确要求取消，原始样本保留，未完成24小时验收；本地容器已停止，无需继续保持电脑和Docker运行。

## 当前验收与交付入口

- [剩余任务表](docs/14-剩余任务与验收计划.md)：已完成项、责任人与剩余输入。
- [本地验收报告](docs/16-本地验收报告.md)、[收尾交接](docs/stages/R-验证与交接.md)、[机器证据](docs/stages/evidence/R-validation.json)。
- [92项接口矩阵](docs/stages/R-契约验收矩阵.md)、[AI记录](docs/stages/R-AI使用记录.md)。
- [环境获取教程](docs/15-外部验收环境获取教程.md)、[设计入口](docs/README.md)。
- 课程Word、PPT、LOCAL_MOCK录像及源码包位于本机deliverables，见[课程交付索引](docs/17-课程交付索引.md)。

历史A—I记录描述各阶段当时状态；例如H的72.51%和API-89未实现不代表当前版本。历史首次失败和修复证据保持原样。无生产发布授权，未执行prod发布。

## 本地启动与验证

先运行scripts/bootstrap-tools.ps1准备冻结工具链；MySQL开发和测试库按scripts/bootstrap-stage-b-db.ps1及B交接准备，Redis/RabbitMQ按scripts/bootstrap-dependencies.ps1准备。测试数据库必须是隔离的yueji_mall_test，禁止把测试环境指向业务库。

```powershell
. ./scripts/use-tools.ps1
. ./scripts/use-db.ps1
. ./scripts/use-dependencies.ps1
./scripts/verify.ps1
```

LOCAL_MOCK仅dev/test显式启用；真实沙箱使用受限密钥文件，不能提交runtime.env、.artifacts随机账号或密钥。现有本机容器使用.artifacts/local-test/runtime.env和infra/compose.local-test.yml，运行命令为：

```powershell
docker compose -p yueji-local-test --env-file .artifacts/local-test/runtime.env -f infra/compose.yml -f infra/compose.local-test.yml -f infra/compose.observability.yml --profile observability up -d --wait
```

会员/管理页面：https://localhost:18443（本机自签名证书）；API：http://127.0.0.1:18080/api/v1；readiness：http://127.0.0.1:19081/actuator/health/readiness。随机测试账号仅在本机.artifacts/R-e2e-accounts.json。新环境应按工具和数据库脚本重新生成，不能复制固定生产口令。数据库持久化卷与文件必须保留，普通停止不要附带-v清卷。

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
