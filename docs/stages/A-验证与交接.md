# 阶段 A 验证与交接

验收日期：2026-10-23。结论：阶段 A 工程初始化完成，前后端可构建，公共协议与配置隔离已验证，92 项操作都有未实现状态。此结论不代表业务功能、数据库、身份权限或课程完整验收通过。

## 1 初始状态与依据

项目初始仅有 AGENTS.md、设计文档和原始 DOCX，无 Git 仓库、无业务实现。已调用项目知识图谱的 list_projects：没有当前项目索引，因此使用本地文件读取，不使用其他项目的图谱推断本项目结构。未覆盖其他业务实现或用户未提交代码；没有 Git 提交 SHA。

遵循 01/02/04/06/08/12 的范围和协议；03 用于核对金额和 BIGINT 范围，05/07 用于页面状态和基础测试边界。具体阶段技术决定已追加 ADR-13。

## 2 本轮交付

| 子系统 | 交付内容 | 明确边界 |
|---|---|---|
| 后端 | Java 21、Boot 3.5.16、Spring Security 骨架、Actuator、Springdoc 2.8.17、Maven Wrapper 3.3.4 / Maven 3.9.16 | 未接数据源、JWT、刷新会话、RBAC、迁移或业务服务 |
| 模块分层 | identity/catalog/cart/trade/inventory/payment/fulfillment/aftersales/review/settlement/platform 各保留 controller/service/repository 包 | 只有包级边界，没有假 Controller/Service/Repository 实现 |
| 公共协议 | Result/ErrorCode、字段错误、PageQuery/PageData、ApiId/ApiMoney、统一异常和 traceId | 金额始终字符串；负金额只供后续调整 DTO 使用，普通业务另加非负约束 |
| 前端 | Vue 3、Router、Pinia、Element Plus、工程状态页、404 页、请求层、类型与测试 | 没有商品、登录和交易界面；不会显示虚假成交数据 |
| 契约 | contracts/openapi.json、92 操作清单、同步/漂移检查与生成的 TS 基础类型 | 计划业务 DTO 仍由 x-planned-contract 引用原设计，不是完整业务 OpenAPI |
| 环境 | dev/test/prod、回环绑定、prod 独立管理端口、prod 关闭 OpenAPI/UI、模拟确认不启用 | 没有真实凭据；不接支付渠道或数据库，不是完整安全实现 |
| 版本 | 官方元数据、精确工具与 npm 版本、3 个基础镜像 index/amd64 digest、前端锁和后端解析图 | 镜像未运行，后续组件按接入阶段冻结 |

## 3 实际执行与结果

以下命令均实际执行。PowerShell 中已先点源 scripts/use-tools.ps1；Maven 命令在 backend 内执行。

| 命令 | 实际结果 | 本地原始证据 |
|---|---|---|
| java -version / node --version / pnpm --version | Temurin 21.0.12.1+1、Node 24.22.1、pnpm 10.34.7；Maven Wrapper 固定 3.9.16 | 工具冻结 JSON 与会话输出 |
| python scripts/freeze-environment.py | 官方版本与三个 digest 查询完成，先于前端锁生成 | infra/versions.lock.json |
| Maven Wrapper 3.3.4 wrapper 目标 | 成功生成 only-script Wrapper，分发包 SHA-256 已加入属性 | .artifacts/maven-wrapper.log |
| pnpm --dir frontend install --frozen-lockfile | 成功，无 peer 冲突；仅允许 esbuild 安装脚本 | .artifacts/pnpm-frozen-install.log |
| python scripts/sync-contract.py --check | 92 项清单、OpenAPI 和生成类型一致 | .artifacts/contract-sync.log |
| pnpm --dir frontend typecheck | 通过，vue-tsc 无类型错误 | .artifacts/frontend-typecheck.log |
| pnpm --dir frontend test | 2 个测试文件、7 项测试通过 | .artifacts/frontend-tests.log |
| pnpm --dir frontend build | 通过，输出 frontend/dist | .artifacts/frontend-build.log |
| pnpm --dir frontend contract:lint | OpenAPI 3.1 校验通过，无剩余警告；阶段 A 对无 2xx 作明确规则例外 | .artifacts/contract-lint.log |
| ./mvnw.cmd -B -ntp verify | 编译、11 项测试、打包均通过 | .artifacts/backend-verify.log；backend/target/surefire-reports |
| Maven dependency:list，sort=true，outputFile 指向 infra/backend-dependencies.lock.txt | 成功记录直接及传递依赖版本（包括测试依赖） | .artifacts/backend-dependencies.log |
| ./scripts/verify.ps1 | 七步检查均 exit 0；统一入口本身已验证 | .artifacts/verification-results.json |
| python scripts/smoke-foundation.py --backend http://127.0.0.1:18080 --frontend http://127.0.0.1:15173 | 8 项通过：JAR 存活/就绪、92 操作文档、Swagger、两个前端入口、健康代理与 501/traceId 代理 | .artifacts/smoke-results.json |

后端测试包含：ID 精度边界和拒绝数字强转、金额小数和范围、分页边界、字段校验、未知字段拒绝、业务与未知异常、MDC 清理、92 项失败关闭、文档状态、未知路径/方法、CORS、prod 文档/模拟/管理入口隔离。92 项在一个循环测试中逐项验证，不伪报成 92 个 JUnit 用例。

初次后端验证有 1 项 CORS 测试失败，修复后全量复测通过，初始日志保留 .artifacts/backend-verify-initial-failure.log。Mockito/Byte Buddy 有 Java 动态代理加载警告，当前 Java 21 测试可运行；尚无 Mockito mock 使用需求，后续可在调整测试工具时配置显式 agent，不能把警告当成业务失败。

## 4 证据、版本与产物

精确冻结：infra/versions.lock.json、frontend/pnpm-lock.yaml、backend/pom.xml、backend/.mvn/wrapper/maven-wrapper.properties、infra/backend-dependencies.lock.txt。后端解析清单是审计快照，Maven 实际依赖由固定父 POM/BOM 和显式版本决定；它不是 Maven 原生的强制 lock 机制。

可交接机器证据见 evidence/A-verification.json 和 evidence/A-smoke.json，含结果与关键文件哈希；完整测试 XML 和日志未混入源码提交，当前仍保留在工作区。后端可运行 JAR 与前端 dist 均位于忽略的构建目录，可按 README 重建。临时 18080/15173 冒烟进程验证后关闭，避免后台残留。

## 5 未执行与限制

- Docker 客户端可用但 daemon 未启动，且用户 Docker 配置读取受限；没有修改该配置。已从 Docker Hub 官方标签 API 核实 digest，没有容器启动、镜像漏洞扫描或 Linux 运行证据。
- 未执行数据库/Redis/RabbitMQ/Testcontainers 集成、业务 E2E、RBAC、幂等库存、真实沙箱、压测、CI/CD 远端发布或恢复演练；这些是后续阶段任务。
- Actuator 的 UP 只覆盖当前无外部依赖工程；阶段 B 接入数据库时应把真实数据库健康纳入 readiness，不能保留当前含义后宣称 DB 就绪。
- 本轮没有 Git 仓库、远端或 PR；版本追踪使用文件哈希和冻结清单，不能伪造 commit。是否创建 Git 仓库及远端由后续工程任务确定。
- 所有业务入口目前由 ContractStubFilter 拦截；尚未实施正常请求 DTO 和认证。阶段 A 的 HTTP 501 不是业务权限测试通过。

## 6 阶段 B 接手顺序

1. 阅读 AGENTS.md、01/02/03/04 和本记录；按 README 激活锁定工具链，执行 verify.ps1 得到本地基线。
2. 准备 MySQL 8.4 和 Docker，冻结 MyBatis-Plus/Flyway/MySQL 兼容补丁与 digest；不另加数据库。按 03 外键拓扑生成迁移和隔离种子，不产生固定生产口令。
3. 完成身份/权限/地址和管理操作所需 DTO、JWT、刷新 Cookie/CSRF、归属与职责检查。参考 09 阶段 B 的操作编号；禁用、退出与令牌轮换不能简化。
4. 对确实实现的操作才变更契约状态、请求响应 schema 和成功/错误状态；修改门禁使其只阻断仍未实现路由，并同步 Spring Security 的实际授权规则。不能全局 permitAll 或删除所有占位保护。
5. 调整契约同步工具和测试，从“全 92 项均未实现”演进为“已实现项验证业务，其余仍失败关闭”；保留清单数量与设计一致检查，不能为通过测试删除设计操作。
6. schema 中的 Id/Money、Java ApiId/ApiMoney、生成 TS 类型继续作为唯一网络类型映射。安全与参数检查通过后，才能把相应 x-implementation-status 改为 IMPLEMENTED。
7. 执行阶段 B 专属迁移、刷新重放、权限和地址测试，填写新交接与 AI 台账；未完成真实环境条件明确记录，不让 mock 替代数据库证据。

本次没有未解决的阶段 A 编译、类型或测试失败。需要后续实际准备的外部条件是 Docker/MySQL 与后续沙箱账号；没有需要当前用户批准的业务范围变更。
