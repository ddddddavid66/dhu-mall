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
| AlipaySandboxProbe 对固定沙箱网关只读查单 | 真实code=40004、ACQ.TRADE_NOT_EXIST；未付款、未退款 | docs/stages/evidence/R-alipay-connectivity.json |

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
| TC-30 | 未完成；需连续24小时的全部样本，启动后的进度单独记录 |
| TC-31 | 部分完成；静态门禁与本地容器通过，远端CI及回退演练另记实际结果 |
| TC-32 | 部分完成；真实AI记录、需求契约和测试可追溯，完整对话导出、成员签名及不可见模型build不能代填 |

## 尚未解决的风险和交接记录

1. 用户明确不部署生产并接受当前依赖安全发现。禁止将该决定解释为扫描通过、取消鉴权或开放管理端口。
2. 用户已提供沙箱appId/sellerId、密钥受限文件路径和cloudflared；买家尚不可用，因此真实交易仍阻塞。密钥未写入仓库或前端。公网临时回调需要运行时建立，并核验可达，不能伪填。
3. GitHub仓库为 ddddddavid66/dhu-mall，当前授权已核验；实际推送及Actions结果补充到CI证据，未完成前不写成功。
4. 同机rclone副本不构成独立备份；性能及24小时结果不能用单次健康、设计指标或截图代替。
5. 原始对话、教师模板、成员信息和本人签名由提交者提供。课程材料只能作为待补资料的提交预览。
6. 用户既有未提交内容为此前授权的收尾工作，本轮在同一工作区继续，未清共享开发库。未执行任何prod发布。

下一步优先完成CI真实运行、回退/连续探测、课程材料和源码归档；具备可用买家后再执行付款、通知、查单、关单和退款。证据汇总入口：evidence/R-validation.json。
