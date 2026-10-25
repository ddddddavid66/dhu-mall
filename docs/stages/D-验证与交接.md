# 阶段 D 验证与交接

日期：2026-10-24，Asia/Shanghai。前置 C 证据已核对；项目位于 C:/Users/123/Desktop/temp/project，无 Git 仓库或远端。开始时读取现有工程和阶段记录，知识图谱项目列表未包含本工程，按规则回退文件检索。保留既有业务、原始实验 DOCX、AGENTS 和 C 联调资料；本轮没有提交或 PR。

## 1 现状、依据和范围

依据 01 的 FR-05/07、业务政策和权限矩阵，02 第 3—5 节状态机/事务/幂等，03 的字段/外键/唯一约束，04 的 API-26—31，以及 07 的 TC-06—09。A/B/C 已实现部分继续回归。本阶段新增六项接口，累计 52 项 IMPLEMENTED、39 项 NOT_IMPLEMENTED/501、1 项 DISABLED/404；运行契约 0.4.0，全部 92 项仍有明确状态。

实现服务端预览、一次跨店原子下单、本人批次/订单恢复查询、取消意图、库存预占、快照、状态日志和持久化 Outbox。每家店各一个订单、独立应付和支付截止，不建立批次支付。没有实现支付渠道、关单确认、库存释放、履约、售后或评价写入，也没有新增购物业务页面。前端七项测试是既有工程测试，不能计作下单页面 E2E。

## 2 必要变更和不变量

- V7 新增 inventory_reservation、order_status_log、outbox_event、job_lease，当前 30 表；V1—V6 哈希与 C 证据一致，未改写。状态日志防更新/删除，库存流水沿用既有只追加触发器；迁移没有口令。
- trade 按 Controller → Service → Repository 分层；购物车消费经 CheckoutCartAccess，库存写入经 ReservationService，事件经 OutboxService，商品查询经 CatalogAccess；不绕过库存模块覆盖 reserved。
- 交易先共享锁既有授权/目录锚点，再锁本人和预览；库存按数值 SKU ID 升序一次锁齐，店铺按数值 shopId 排序。不同会员共享锚点可并发；商品/分类/店铺/授权变更使用原有排他锁，防止核验后提交前变价或停业。
- 数据库条件更新要求 available≥quantity，再增加 reserved 与库存版本；实物量不减少。订单、行、预占、库存流水、日志、购物车删除、预览使用、Outbox 及幂等快照全部同事务。READ_COMMITTED 防止等待后沿用旧读快照；锁冲突最多重试两次，每次新事务，无渠道调用进入重试。
- 预览绑定本人地址摘要、购物车行与更新时间、SKU/商品版本、价格、数量和店铺，五分钟到期。价格或 SKU 版本变化 PRICE_CHANGED；地址/购物车/商品版本变化 STATE_CONFLICT；店铺不可购买及库存不足分别拒绝。金额溢出拒绝，地址拼接超过既定 receiver_address 300 字符拒绝，不静默截断。
- 同键同参在 24 小时内重放原始业务快照，原请求响应丢失后仍可恢复；同键异参冲突，过重放期不重复执行。已用预览换新键按 04 返回 STATE_CONFLICT，预览唯一批次约束兜底；可用原键及 API-28/29/30 恢复。
- OrderCreated 信封只含既定事件字段和 orderId/shopId，不含地址、电话或令牌；同事务保存 PENDING，不把数据库写入当成 Broker 发布确认。

取消只允许待支付推进 CLOSING，重复取消不重复状态日志；返回 202、保留 UNPAID、closedAt=null 和 RESERVED。30 秒扫描使用数据库租约和订单行锁，最多 100 单，将到期订单推进 CLOSING。没有渠道核实能力时不生成 CANCELLED/OrderClosed、不减少 reserved；CLOSING 的查询/关闭、未知状态重试告警与释放由 E 接管。

## 3 实施与实际验证

最终 `scripts/verify.ps1` 七步均退出 0：契约同步、冻结安装、类型检查、前端测试、前端构建、OpenAPI lint、Maven clean verify。74 项后端测试、7 项前端测试通过，失败/错误/跳过均为 0。核心 service 分支 418/496，84.27%，80% 门禁通过；阶段 D HTTP 集成 16 项包含真实 MySQL 事务与 HTTP，不使用 H2。

| 验收 | 实际结果和边界 |
|---|---|
| TC-06，D 范围 | 两店一次预览/批次、两个独立订单，价格/地址/书目/ISBN 快照保持；购物车消费、预占和 OrderCreated 同步提交。取消其中一店保持 CLOSING，另一店不受影响。真正独立付款和确认取消留待 E，不计为渠道验收通过 |
| TC-07 | 第二店缺货、提交变价、商品版本或店铺状态变化、地址/购物车变化与删除全部阻止建单；在第二店 Outbox 写入注入真实 SQL 故障，第一店已写订单及全部预占也回滚，购物车和预览可恢复重试 |
| TC-08 | 相同键额外重放 20 次、再并发重放 20 次，始终一个批次/两个订单；异 previewId 冲突；原键在预览到期后仍恢复原结果，换键拒绝已用预览，24 小时过期键拒绝重执行 |
| TC-09 | 100 个独立有效会员、100 个客户端线程通过屏障同时各买 1 本；最后 10 本恰有 10 个 201、90 个 OUT_OF_STOCK/409，无其他响应；数据库 10 批次、10 单、实物 10/预占 10，不变量全零 |
| 并发与恢复补充 | 同一购物车的两个预览并发只成功一次；真实反向行锁诱发死锁，事务重试后每个写入只提交一次；持续锁错误最多三次尝试且全部回滚；到期重复扫描/其他租约持有者不重复推进 |
| 范围与契约 | 本人地址/购物车/预览/批次/订单范围、匿名及非会员权限、分页/排序/状态参数、拒绝客户端金额覆盖；付款接口仍 501，模拟确认仍禁用；日志和预占数据库约束生效 |
| 迁移与版本 | 空库 V1→V7、V1 旧行前滚保留、重复 migrate=0、V1 读列兼容；最终 JAR 在开发库执行 V7，重启明确 No migration necessary，健康与前端代理再次通过 |

| 实际命令 | 实际结果与证据位置 |
|---|---|
| git status --short；知识图谱 list_projects | 无 Git 仓库；无本工程图谱，未覆盖其他项目索引 |
| ./scripts/bootstrap-stage-b-db.ps1 | 启动既有项目 MySQL 13306，保留开发数据；系统 MySQL80 未操作；.artifacts/D-db-start.log |
| Maven -DskipTests compile | 编译通过；.artifacts/D-compile.log |
| Maven -Dtest=StageDHttpTests test | 初次因测试数据库未运行而启动失败；启动后 13 项中两处测试期望错误，已按实际路由和既有 503 映射修正，原日志 D-integration-initial/second.log 保留 |
| ./scripts/verify.ps1 | 首轮通过；复核已用预览规则并补金额/地址边界后最终全量再通过；D-verify-final.log 和 verification-results.json，backend-verify.log、JUnit XML、Jacoco XML/HTML |
| python scripts/smoke-foundation.py --backend http://127.0.0.1:18080 --frontend http://127.0.0.1:15173 | 打包 JAR、文档、前端入口及代理 8 项通过；重启后再次通过 |
| python scripts/smoke-identity.py / smoke-stage-c.py --backend http://127.0.0.1:18080 | B 身份 14 项、C 目录/库存/文件/购物车 17 项通过；B 脚本允许后续阶段新会员，不再硬编码整个开发库只有 14 人 |
| python scripts/smoke-stage-d.py --backend http://127.0.0.1:18080 --confirm-dev | 独立会员经正式 API 创建真实联调订单，19 项通过；重复执行仅核验，另 8 项通过，未重复建批次 |
| ./scripts/inspect-stage-d.ps1 | 实物/预占流水、金额、快照关联、日志和 Outbox 核对通过；保存三类真实 EXPLAIN。首轮 PowerShell 传递 EXPLAIN 参数错误，修正引号后完成；不把此错误记为 SQL 检查通过 |
| java -version / mvn -version / node --version / pnpm --version | 实际输出 D-toolchain.txt；延续冻结版本，无依赖升级 |
| python scripts/record-stage-d-evidence.py；python scripts/sync-contract.py --check | 从实际记录生成公开证据与源码/JAR 哈希，检查迁移及锁文件未改、契约同步 |

主要机器证据：[D-verification.json](evidence/D-verification.json)；SQL 不变量分别见 [TC-06](evidence/D-tc06-invariants.json)、[TC-07 整批回滚](evidence/D-tc07-rollback.json)、[TC-08 重放](evidence/D-tc08-replay.json)、[TC-09 并发](evidence/D-tc09-concurrency.json)。测试每个用例重建明确校验过的专用测试库，证据在该场景检查当时输出；后续清理不意味着场景证据缺失。

## 4 联调数据、风险与交接

验证通过后才通过正式 API 注册独立虚构会员、地址和两店购物车；开发库实际 1 批次、2 订单、2 订单行、2 RESERVED/预占总量 2、两条 RESERVE 流水、3 条状态日志及 2 条 PENDING Outbox。一个 CLOSING、一个 PENDING_PAYMENT，全部 UNPAID；没有 CANCELLED、已发布事件、售后或评价。总实物 2330，库存/流水和金额等核对差异均为 0。见 [D 开发库证据](evidence/D-database.json) 和 [资源清单](evidence/D-demo-data.json)。联调脚本重复执行不修改业务数据。

账号口令仅在忽略的 .artifacts/D-demo-account.json，本地随机生成，不进入生产迁移或公开文档；其他角色仍在 .artifacts/B-dev-accounts.json。原 C 读者购物车保留。本轮联调订单尚未付款或确认关闭，库存占用是实际业务事实，不能为还原展示而手工清零。地址完整展示仅本人详情，公开证据不包含地址/电话。

三类实际查询计划在 [D-query-plans.json](evidence/D-query-plans.json)。数据量很小，只证明执行计划可核实，不能据此宣称 NFR-01 的延迟/吞吐达标。100 人库存正确性测试也不等于标准负载性能测试。

尚未执行：支付与渠道关单、RabbitMQ 确认发布/消费/死信与重放、浏览器下单页面 E2E、Linux/Docker/Testcontainers、远程 CI/CD、漏洞扫描、标准性能/备份恢复演练；相应阶段或运行环境尚未具备。本阶段没有新增 Redis、数据库、微服务、优惠券或真实外部资金调用。公开限流仍对应当前单实例实现。

下一阶段 E：从既有订单与预占继续，按订单→订单行→升序库存→预占→财务明细锁顺序接入支付。先持久化支付状态再在事务外调用渠道；同一订单唯一支付，关闭未知时保持 CLOSING，不因库存过期直接 RELEASED。核实支付后才能 COMMIT，核实关闭后才能 RELEASE，并同事务写稳定业务键流水、状态日志和 Outbox。处理响应丢失、迟到支付及补偿退款；补齐关闭扫描退避与 15 分钟未收敛告警。后续可靠消息阶段实现 Outbox 租约、Broker 确认和消费去重，不能把现有 PENDING 直接改为已发布。

所有既有迁移只追加；不重建 checkout_preview/batch/trade_order/order_item，不改历史金额或快照。大于 300 字符的完整收货地址当前拒绝，后续前端应提前提示并要求用户缩短；不得自动截断历史履约地址。没有需要批准的需求/契约变更。AI 留痕见 D-AI使用记录.md，冻结见 D-版本冻结.md。

自有冒烟端口 18080/15173 的后端与前端进程经 PID/命令归属确认后关闭；项目 MySQL 13306 保留运行。下一位执行者按 README 重启开发工程即可。
