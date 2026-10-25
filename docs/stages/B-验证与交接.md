# 阶段 B 验证与交接

## 1 现状与设计依据

交接日期：2026-10-23。用户明确接受 A 基线并授权实施 B。开始时重新运行 A 的验证；现有设计文件、原始 DOCX、AGENTS.md 与 A 工程保留。当前目录无 Git 仓库，没有可列出的分支、用户 Git diff、commit 或 PR；使用冻结清单与文件哈希追踪本次产物。项目知识图谱服务未发现当前工程有效图谱，按规则退回文件读取。

01 的业务/角色矩阵、02 会话和事务规则、03 持久化约束、04 网络契约为依据。环境沿用 06/08，实施选择记入 ADR-14。没有新增业务需求、万能状态接口、微服务、优惠券、真实支付或出款。

## 2 当前阶段范围与文件

完成 API-01—10、API-70—77、API-84—88，共 23 项，逐项见 B-接口契约清单。包括注册、登录、轮换刷新、退出、当前用户、改密、地址 CRUD、用户及角色管理、权限目录、审计查询、店铺及职责成员维护。静态契约版本 0.2.0；Java/TS 公共 ID、金额、分页、错误定义沿用 A。

数据库 Flyway V1—V4 按拓扑建立 iam_user、iam_role、iam_permission、iam_user_role、iam_role_permission、shop、shop_member、refresh_session、user_address、audit_log，带外键 RESTRICT、唯一键、状态 CHECK、有效默认地址生成列唯一约束，以及审计防改写触发器。V3 仅固定权限目录和角色映射，无用户或密码。后续业务表与业务种子不在本次创建。

主要变更：backend 的 common/identity/cart/platform 层、迁移 SQL 和真实 MySQL 测试；contracts/openapi.json、scripts/stage_b_contract.py/sync-contract.py；数据库准备/环境/冒烟脚本；版本冻结和依赖快照；前端状态文字与对应测试；README、ADR 与本交接。前端登录及业务页面尚未实现。

## 3 实施与安全约束

访问 JWT 固定 900 秒、HS256、发行方/受众/用途/签名/时间校验；当前用户、authVersion、有效会话族及权限来自服务端数据库。随机刷新令牌与 CSRF 令牌只持久化 SHA-256 哈希，轮换保留旧记录，7 天为会话族绝对期限；刷新重放事务提交撤销后才返回 401。Origin 与 CSRF 双检，Cookie 不作为业务身份。

改密/禁用/授权及成员变更提升 authVersion 并撤销用户会话，退出撤销同族。授权修改统一锁已有 ADMIN 角色行，在事务内复核操作者；至少保留一个 ACTIVE ADMIN 且具有用户及角色管理权限的账号。地址在用户锁内校验本人、20 条上限、默认切换，防横向访问和并发重复默认；SQL 参数绑定、分页上限和排序拒绝规则已实施。

开发种子只在 dev 且非 prod、显式启用、空用户库运行，生成 14 个随机口令虚构账号、3 店铺、6 成员，财务账号相互独立。prod 显式一次性管理员初始化仅接收环境输入，开发种子拒绝 prod；密钥无固定生产默认值。

## 4 实际验证

机器结果见 [B-verification.json](evidence/B-verification.json)，接口冒烟见 [B-identity-smoke.json](evidence/B-identity-smoke.json)，工程冒烟见 [B-smoke.json](evidence/B-smoke.json)，运行数据库计数见 [B-database.json](evidence/B-database.json)。最终 38 项后端测试、7 项前端测试全部通过；核心 service 分支覆盖为 145/170（85.29%），80% 门禁通过。原始 JUnit XML、覆盖率及日志保留本地构建/忽略目录。

| 实际命令 | 结果与证据 |
|---|---|
| ./scripts/bootstrap-stage-b-db.ps1；. ./scripts/use-db.ps1 | 官方归档校验，隔离 MySQL 8.4.13 实际监听 127.0.0.1:13306；未修改系统 MySQL80 |
| Maven dependency:list -Dsort=true -DoutputFile=../infra/backend-dependencies.lock.txt | 解析依赖并记录精确快照；.artifacts/B-backend-dependencies.log |
| ./scripts/verify.ps1 | 契约同步、冻结安装、类型、7 项前端测试、前端构建、OpenAPI lint、后端测试/打包；各步 exit 0，详见机器证据 |
| Maven clean verify / JaCoCo | 实际用例数和 service 分支覆盖见机器证据；backend/target/site/jacoco 是完整报告，不用 DTO 覆盖凑核心比例 |
| python scripts/smoke-foundation.py --backend http://127.0.0.1:18080 --frontend http://127.0.0.1:15173 | 8 项通过：JAR 健康/文档、前端入口/代理、未完成业务 501 |
| python scripts/smoke-identity.py | 14 项通过：真实开发账号登录、地址创建/越权/删除、刷新轮换/重放、旧访问拒绝、退出、管理员分页 |

| 验收编号 | 本阶段实际覆盖与边界 |
|---|---|
| TC-01 | 会员注册只获 MEMBER、真实 BCrypt、登录/JWT 900 秒、刷新 Cookie、轮换、退出及旧访问/刷新拒绝；恶意额外字段拒绝 |
| TC-02（B 范围） | 跨会员地址 404；会员调用管理动作 403；服务端店铺职责校验；ID 注入和伪造归属拒绝。商品/订单/库存/结算尚未实现，完整跨业务 TC-02 留给后续阶段，不以 501 计为业务验收 |
| TC-03 | 禁用、改密、角色/权限与店铺成员变更后的旧会话拒绝；并发互相撤权仅一个成功，保留管理员，成功变更写审计 |
| TC-05 地址部分 | 默认唯一、本人 CRUD、删除可重复、软删除后不可修改、客户端 userId 拒绝；购物车/订单历史快照未在 B 验证 |
| TC-25 会话部分 | 缺失/错误 Origin/CSRF 拒绝，重复刷新撤销同族，错误签名/过期/受众拒绝；排序和参数 SQL 片段拒绝。业务文本 XSS 与浏览器页面留待后续 |
| 迁移与兼容 | 空库 V1→V4、再次 migrate 执行 0 条；V1 旧数据/列查询经 V4 保留、约束实际生效、审计 UPDATE/DELETE 被数据库拒绝；这不是生产备份恢复或旧镜像回滚演练 |
| 种子/环境 | 空测试库种子创建与重复运行不改写；prod 拒绝开发种子、缺失密钥拒绝；一次性生产管理员仅空库允许 |

故障与修正保留：首轮发现 page=0 映射 500 和拒绝来源的 CORS 响应非 Result；改为统一 400/403 后复测。新增触发器遇到本机 binlog 创建权限限制，只调整项目自有开发实例，未给应用 SUPER；仅清理隔离测试库失败的迁移记录后重测。另一次 clean 因冒烟 JAR 正在运行被 Windows 锁住，关闭核验 PID 的自有进程后重建。第一次覆盖门禁实测 73%，补齐真实边界用例后达标，未降低阈值。最终复核补强事务内会话族检查，已认证 Actor 在退出后也拒绝使用，相关用例已回归。没有把失败轮次算入通过。

## 5 环境、未执行项目与交接

开发数据库及凭据准备、启动方法详见根 README。随机开发账号明文仅 `.artifacts/B-dev-accounts.json`，数据库口令仅 `.tools/stage-b-db.json`，均不在交接证据中。测试只允许明确本机专用测试库并清理重建，不能指向共享/生产。Flyway 运行 clean 默认禁止；测试显式开启仅用于受校验的隔离库。

最终 JAR 重启后再次通过 8 项工程、14 项身份冒烟，V4 不重复执行且开发凭据文件未改变。冒烟自有后端 18080/前端 15173 进程已关闭；项目自有 MySQL 13306 保留运行，供下一阶段使用，系统 MySQL80 未操作。

Docker daemon 未运行，因此没有 Testcontainers/容器/Linux 兼容实测；本阶段数据库证据来自独立的真实 MySQL 8.4 Windows 进程。未进行远程 CI/CD、漏洞扫描、浏览器业务 E2E、压力/恢复演练、真实支付宝沙箱或财务操作；没有声称这些检查通过。核心分支覆盖率以实际 JaCoCo 结果为准，未伪造截图。

回滚策略：保持 V1—V4 只增结构/目录，失败后由受控迁移者判断修复并前滚；不得自动 drop 生产表或回写既有迁移。旧列读兼容已有自动测试，真实候选镜像回滚及恢复演练属于阶段 I。生产应拆分迁移 DDL 账号和运行 DML 权限、配置持久 JWT 密钥、HTTPS Origin 和触发器所需 DBA 权限；本机库级开发账号不是生产授权模板。

下一阶段可依据 09 的阶段 C 开始图书/商品/文件设计范围：先读 01—04、05/07 和当前契约，复用 IdentityPolicy 的本人/动作/店铺检查；关键写锁后重新鉴权，逐项开放门禁，不得信任客户端 shops/roles。未来阶段补足 TC-02 跨业务对象，身份会话回归保留。本阶段没有需要用户决定的需求或接口变更。
