# 收尾阶段验证与交接

日期：2026-10-26。历史 A— I 记录保留为当时结果；当前状态以本报告和机器证据为准。核心本地闭环已通过，完整课程验收仍有外部及持续运行条件，不能称为全部通过或已上线。

## 现状与设计依据

依据 AGENTS.md、01—08、12—13 与用户批准的 R01—R16 计划。Java 21、Spring Boot 3.5.16、Spring Framework 6.2.19 保持不变；按用户最新决策取消 Boot 4 / Spring 7 和兼容补丁升级。ADR-19 记录仅本地使用的风险接受，扫描失败仍保留，CI 不伪造绿色结果。数据库 V11，OpenAPI 0.7.0，92 项操作已实现。

最初 H 基线为119项后端测试、72.51%服务分支覆盖率，以及4项浏览器通过/3项过滤跳过；这些数字不再代表当前版本。原始记录未重写。

## 当前阶段范围

补齐 API-89、Outbox/RabbitMQ、Redis；建立完整契约观测、财务边界与浏览器闭环；验证容器、监控、进程崩溃和隔离恢复。外部沙箱与 GitHub 运行结果单独归档。所有商家出款仍为模拟。

## 必要的文件变更

后端 platform 模块新增统计、消息发布确认与消费去重、缓存和辅助限流、受控死信重放及指标；V11 只追加 consumed_event。前端接入真实统计并修复售后、结算与支付恢复交互。测试增加财务边界、真实中间件故障、92项响应 JSON Schema 检查和桌面/手机交易。infra/scripts 提供日志与告警接收、容器配置、隔离恢复和持续探测。没有加入优惠券、额外业务数据库或真实出款。

## 实施与验证

| 实际命令或操作 | 实际结果 | 原始证据位置 |
|---|---|---|
| backend 下 ./mvnw.cmd -B -ntp clean verify | 178通过，0失败/错误/跳过；1173/1463=80.1777%，80%门槛通过 | .artifacts/R-backend-clean-verify-final.log；R-final-backend-reports/ |
| pnpm --dir frontend install --frozen-lockfile / run typecheck / test / run build / run contract:lint | 冻结安装、类型、构建和契约检查通过；Vitest 10项通过 | .artifacts/R-vitest-final.log、R-contract-lint-final.log、R-frontend-delivery-typecheck.log |
| python scripts/sync-contract.py --check；python scripts/report-contract-coverage.py --require-complete | 92项操作同步；92项均观测成功与拒绝响应，校验响应字段类型 | docs/stages/evidence/R-contract-coverage.json；R-契约验收矩阵.md |
| pnpm --dir frontend run test:acceptance | 5通过、0失败/跳过；跨店独立支付、履约、评价、两类退款、结算职责分离、越权和XSS/移动布局 | .artifacts/R-browser-acceptance-final.log；R-playwright.json；R-playwright-results/ |
| python scripts/drill-local-crash.py | LOCAL_MOCK渠道事实提交后SIGKILL；原paymentNo恢复、仅一笔支付、库存只扣一次 | docs/stages/evidence/R-process-crash.json；原日志 R-process-crash-final.log |
| docker compose 本机专用栈 up -d --wait | 应用、MySQL、Redis、RabbitMQ及监控健康 | .artifacts/R-final-container-up.log |
| docker build -f frontend/Dockerfile -t yueji-local-frontend:r-delivery . | 最终前端镜像构建成功，digest记录在运行清单 | .artifacts/R-frontend-container-delivery.log |
| powershell -NoProfile -File scripts/run-local-recovery.ps1 | 加密备份、隔离恢复、重复非空恢复拒绝；7订单/6支付/4退款/1结算与库存流水一致；恢复登录、订单、文件成功 | docs/stages/evidence/R-local-recovery.json、R-restored-application.json；.artifacts/R-recovery-delivery.log |
| Prometheus、Alloy、Loki、Alertmanager 实际运行与强杀故障 | 指标抓取与日志可查；后端不可用告警firing和resolved已接收 | docs/stages/evidence/R-alerts-observed.jsonl；.artifacts/R-prometheus-targets.json、R-loki-check.log |
| Trivy固定版本源码秘密扫描 | 待提交源码快照扫描退出0；秘密文件、账号夹具、缓存和卷未纳入源码 | .artifacts/R-final-secret-scan.json |
| 后端镜像漏洞扫描 | 17项HIGH/CRITICAL发现，未修复；用户接受本地风险，不能写通过 | .artifacts/R-trivy-backend-image.json；ADR-19 |
| pnpm --dir frontend audit --audit-level high | 0漏洞 | .artifacts/R-pnpm-audit.json |
| actionlint / python scripts/validate-stage-i.py | 工作流语法通过，运维静态检查26项通过 | .artifacts/R-actionlint.log、R-static-ops-check.log |
| python scripts/drill-local-rollback.py | 本地坏候选退出1未晋级；前端回退旧digest再恢复当前digest；账目和库存计数一致，数据库保持V11 | docs/stages/evidence/R-local-rollback.json |
| GitHub Actions run 40282687327 | 后端178项及80.1777%门槛、5项浏览器、前端质量、CodeQL通过；安全配置扫描失败，镜像晋级跳过 | docs/stages/evidence/R-github-ci.json；实际运行URL见该文件 |
| python scripts/probe-availability.py --base-url http://127.0.0.1:18080 --health-base-url http://127.0.0.1:19081 --output 新运行文件 | 北京时间2026-10-26 14:57:09启动；仍在运行，不是24小时通过 | .artifacts/R-availability-process.json；docs/stages/evidence/R-availability-snapshot.json |
| AlipaySandboxProbe 对固定沙箱网关只读查单 | 真实code=40004、ACQ.TRADE_NOT_EXIST；未付款、未退款 | docs/stages/evidence/R-alipay-connectivity.json |

2026-10-26追加修复：04c562f将前端设为UID101并为临时目录分配对应权限；恢复工具默认UID10001，隔离维护命令显式--user 0:0。未升级依赖、未增加扫描忽略规则。本地实际Trivy配置/秘密扫描退出0，三个Dockerfile无HIGH/CRITICAL配置发现；非root前端健康与真实商品代理通过；新恢复镜像完整加密恢复复验通过。远端复验运行40285947137单独记录最终结果。Linux TLS私钥需允许nginx组101只读，目录可遍历，不能设为全局可读。

24小时探测在北京时间15:27:12记录1次RemoteDisconnected，对应本轮显式恢复维护窗。原始样本保留，当前阶段不重新起算或从分母删掉；最终是否达到99%须等窗口完整结束。

完整后端结论引用保留的最终原基线报告，不引用后来取消的依赖升级诊断测试。运行报告聚合脚本不会重跑测试。92项观测不是所有输入组合的穷举证明。随机开发账号及原始认证头不进入仓库；脱敏JUnit删除环境属性和输出，保留用例名、时间及计数。

## 测试编号状态

| 编号 | 当前状态及边界 |
|---|---|
| TC-01—09 | 通过；真实MySQL身份权限、目录购物车、幂等、整批回滚和库存并发测试 |
| TC-10 | 阻塞；只读真实查单成功，买家暂不能登录。付款、有效通知、真实关闭与退款均未执行 |
| TC-11—12、14—24 | 本地实现范围通过；渠道协议夹具与LOCAL_MOCK明确区分，商家出款模拟 |
| TC-13 | 通过；持久化渠道事实后真实强杀应用并原号恢复 |
| TC-25 | 通过本地会话、越权、浏览器XSS和输入拒绝；依赖漏洞作为独立已接受风险记录 |
| TC-26 | 真实RabbitMQ发布确认、去重、乱序、回滚、重试死信和受控重放测试通过 |
| TC-27 | 中间件/适配器故障与回源、拒绝、恢复本地验证通过；真实支付宝故障场景未验收 |
| TC-28 | 部分完成；本地隔离恢复和文件引用通过，独立备份目标及标准RPO/RTO验收未齐 |
| TC-29 | 阻塞；没有符合01固定口径的独立负载/监控和4vCPU/8GB Linux环境，未产生正式性能数值 |
| TC-30 | 进行中；已启动每分钟探测，需完整24小时全部样本，不将当前成功率当整项通过 |
| TC-31 | 部分完成；真实CI后端/浏览器/前端/CodeQL及源码配置扫描通过；候选构建推送通过，镜像依赖扫描17项失败；坏候选拒绝与前端回退通过，无后端版本回退或远端CD证明 |
| TC-32 | 部分完成；真实AI记录、需求契约和测试可追溯，完整对话导出、成员签名及不可见模型build不能代填 |

## 尚未解决的风险和交接记录

1. 用户明确不部署生产并接受当前依赖安全发现。禁止将该决定解释为扫描通过、取消鉴权或开放管理端口。
2. 用户已提供沙箱appId/sellerId、密钥受限文件路径和cloudflared；买家尚不可用，因此真实交易仍阻塞。密钥未写入仓库或前端。公网临时回调需要运行时建立，并核验可达，不能伪填。
3. GitHub仓库为 ddddddavid66/dhu-mall，源码在main/backend/src和main/frontend/src。已推送实现8341745、报告上传修复59f748b及容器用户修复04c562f。前两次Actions安全配置扫描失败；第二次浏览器报告已实际下载并确认5通过0跳过。配置修复后本地扫描及运行通过，第三次远端结果另记。未发布部署清单或prod。
4. 同机rclone副本不构成独立备份；性能及24小时结果不能用单次健康、设计指标或截图代替。
5. 原始对话、教师模板、成员信息和本人签名由提交者提供。课程材料只能作为待补资料的提交预览。
6. 用户既有未提交内容为此前授权的收尾工作，本轮在同一工作区继续，未清共享开发库。未执行任何prod发布。

下一步继续连续探测和报告上传复验；课程预览材料按交付索引提供，模板、成员及签名待补。具备可用买家后再执行付款、通知、查单、关单和退款。证据汇总入口：evidence/R-validation.json。

## 后续 CI 与容器联调修复记录

远端40285947137的四个质量job全部通过；候选镜像任务因默认docker驱动不支持attestation而失败，不是GHCR登录失败。88f76bd改用docker-container驱动，冻结BuildKit v0.34.1与SBOM生成器digest，保留SBOM/provenance；本地最小上下文已实际导出含SLSA provenance及SPDX SBOM的OCI镜像并解析核对；首次Windows端auth.docker.io超时失败保留，使用已配置的系统本地代理后通过，没有修改系统设置。远端40288130302执行商城镜像复验，最终结果见R-CI实测记录.md与机器证据，不能预写通过。

同一提交只为本地专用环境补上https://localhost:18443精确Origin。真实Chromium经过非root Nginx和TLS验证登录、重载Cookie刷新、会员订单页与退出全部通过，CSRF保持启用；证据evidence/R-container-browser.json。未将所有Origin放行，未改变业务权限或令牌策略。追加修复后没有修改后端业务源码；已通过的完整后端与核心E2E结果仍由原始及远端报告支撑。

最终远端40288130302：四个质量job通过，前后端SHA候选镜像成功构建、生成SBOM/provenance并推送GHCR；后端依赖镜像扫描17项（11高危、6严重）退出1，前端镜像扫描和release-manifest跳过。候选digest见evidence/R-ci-candidate-images.json；未执行部署。最终470文件快照秘密/配置复扫退出0；在线首次Maven429保留，离线依赖解析复扫不代替漏洞验收。

## 用户取消持续验收与停机记录

2026-10-26用户明确取消24小时探测并要求停止全部Docker容器。核对PID命令后实际停止43568的probe-availability.py，保留原始JSONL和既有维护失败，summary及机器快照标记CANCELLED_BY_USER、complete24Hours=false。该任务属于可用性探测，不是标准负载压测；两者均不得记为已通过。Docker按运行清单正常停止，不删除容器、镜像或数据卷；重新使用商城时需重新启动依赖与应用。既有课程包保留取消前历史快照，本段和最新14任务表为后续状态。
