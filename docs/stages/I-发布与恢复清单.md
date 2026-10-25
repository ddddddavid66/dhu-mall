# 阶段 I 发布与恢复操作清单

本清单描述可审阅的 test 单机发布和隔离恢复流程。它不代表 Linux 主机、CI、镜像仓库、监控通知、备份或恢复已经实际运行。生产发布没有自动化入口；生产发布必须另行明确授权并完成审核。

## 发布前检查

- [ ] H 阶段固定门槛已通过：后端服务分支覆盖至少 80%，完整测试无失败；当前 H 交接记录为 72.51%，不满足此项，流水线不得产生可晋级清单。
- [ ] 使用 `quality-release` 在受保护的 `main` 上成功运行；对应运行的 `yueji-release-<run_id>` 制品必须包含 `release-manifest.json`，且 manifest 中两个镜像都是 `sha256` digest。
- [ ] `test` Environment 已限制审批人和分支；部署 SSH key 只放在该 Environment secrets。普通 PR 工作流不读取部署 secrets。
- [ ] Linux 目标按 06 准备 Ubuntu 24.04、Docker Engine/Compose v2、磁盘和维护窗口；监控同机时按 06 预留 16 GB 内存。
- [ ] `/etc/yueji/test.env` 是从样例人工填入的受限文件，属主 root、权限 0600；`DEPLOY_ENV=test`、主机名、回调、持久目录和沙箱参数已核对。
- [ ] 私钥和口令由环境负责人提供并放在样例引用的文件路径。JWT、Alipay 应用/公钥文件由 UID/GID 10001 的应用账号读取且仅该账号可读；MySQL root 口令只允许容器入口读取；备份 age identity、rclone 配置仅 root 可读。不得把真实值写回仓库。
- [ ] 文件持久目录可由容器 UID/GID 10001 写入；TLS 证书域名与 `APP_HOSTNAME` 一致。
- [ ] 已确认当前数据库迁移是扩展式兼容迁移，备份目的地可写，异地策略至少保留 7 个日备份和 4 个周备份。脚本不自动清理历史备份。
- [ ] 维护窗口编号、责任人、开始/结束时间和回退负责人已登记。`workflow_dispatch` 必须填维护窗口编号。
- [ ] 服务器 GHCR 只读拉取凭据已由权限负责人设置。deploy SSH 公钥配置 forced command，并只允许运行 `/usr/local/sbin/yueji-deploy-test`；sudoers 只授权该固定命令。

## Linux 主机安装边界

将已审阅的项目版本放入 `/opt/yueji`，配置和数据留在镜像外。示例命令只准备目录，不创建口令或密钥：

```bash
sudo install -d -o root -g root -m 0750 /opt/yueji /opt/yueji/infra /opt/yueji/scripts
sudo install -d -o root -g root -m 0700 /opt/yueji/secrets /opt/yueji/backups /opt/yueji/restore /opt/yueji/releases /opt/yueji/records
sudo install -d -o 10001 -g 10001 -m 0750 /opt/yueji/data/files
sudo install -d -o root -g root -m 0750 /etc/yueji
sudo install -m 0600 infra/runtime.env.example /etc/yueji/test.env
sudoedit /etc/yueji/test.env
sudo chmod 0600 /etc/yueji/test.env
```

在 `/etc/yueji/test.env` 填入的仍是受限运维配置；私钥必须由单独的文件挂载。不要把运行样例直接作为有效配置启动。根据 UID 10001 的容器文件读取边界设置 JWT 与支付宝密钥文件属主，MySQL 文件按入口容器读取权限设置。为 SSH 部署账号安装 forced-command 公钥，并在 `/etc/sudoers.d/yueji-deploy-test` 由管理员用 `visudo -f` 创建唯一命令白名单，例如：

```text
deploy_test ALL=(root) NOPASSWD: /usr/local/sbin/yueji-deploy-test
```

将 `scripts/yueji-deploy-test-wrapper.sh` 以 root:root、0750 安装到 `/usr/local/sbin/yueji-deploy-test`。该入口固定到 `/opt/yueji` 和 `/etc/yueji/test.env`；不要授予任意 shell、通用 sudo、端口转发或 agent 转发能力。

## test 部署

1. 在 GitHub Actions 手工触发 `deploy-test`，填写一个成功的 `quality-release` run ID 和维护窗口编号。
2. 工作流从该 run 下载不可变 digest manifest，核对成功状态、`main`、commit SHA、代码分析/依赖与镜像扫描状态；部署人不能自行输入镜像地址。
3. SSH 使用固定 host key 校验和临时私钥文件，只向强制入口传入 backend/frontend digest、维护窗口编号、发布 run ID 和 Git SHA。
4. 主机以 test runtime env 运行 Compose 配置检查、拉取 digest、确认 MySQL 健康，然后停止 web/backend、记录受控备份，启动后端执行 Flyway 向前迁移，最多等待 120 秒 readiness，再启动 web 并检查健康及公开接口冒烟。
5. 冒烟核对分类与商品接口的 `200/OK`、非空 data 和 traceId；API-89 dashboard 按既有契约核对 `501/NOT_IMPLEMENTED`、空 data 和 traceId，不把未实现接口写成业务成功。
6. 成功时在 `/opt/yueji/records/` 保存发布 run、Git SHA、两条 image digest、备份结果、维护窗口编号和冒烟证据。解除维护后检查应用日志、错误率和库存/支付待处理项。

任何一次真实部署都应把开始和结束时间、实际操作者、备份远端对象名、迁移版本、readiness 和冒烟结果附入 `docs/stages/evidence/I-verification.json` 或环境负责人维护的受限发布记录。不得把此模板记录当运行结果。

## 失败回退

- 后端启动或 readiness 超时：保持 web 停止，保存日志并停止继续晋级。迁移结果未知时不自动回滚应用，也不执行逆向 DDL、清库或删表；由负责人核对 `flyway_schema_history`、数据库和日志后决定恢复路径。
- 后端健康后但 web/冒烟失败：默认关闭 web/backend 并保留向前迁移数据库。只有审核确认旧应用兼容当前 schema，并在受限配置中显式设 `YUEJI_ALLOW_APP_ROLLBACK=true`，才允许脚本恢复前一组应用 digest；数据库保持向前状态。回退必须再次检查 readiness 和同一组冒烟，并留失败与回退记录。
- 人工只回退应用 digest：先检查 `releases/previous.env`、schema 兼容性、维护窗口和日志，再运行：

```bash
DEPLOY_ENV=test YUEJI_PROJECT_ROOT=/opt/yueji YUEJI_RUNTIME_ENV=/etc/yueji/test.env \
  YUEJI_COMPOSE_FILE=/opt/yueji/infra/compose.yml \
  bash /opt/yueji/scripts/rollback-yueji-app-linux.sh --confirm-app-only-rollback
```

- 脚本绝不自动恢复活动数据库，也没有自动删除 volume 的命令。只有发生不可逆数据破坏、人工批准恢复点且核对支付/退款渠道事实后，才按下节恢复到隔离 schema；完成隔离核验前不得切回生产流量。

## 加密备份与隔离恢复

备份要求 test/prod 应用和上传写入者已停止；MySQL 用 `--single-transaction` 导出，文件树在同一维护窗归档；归档使用 age 加密后推送到受限 rclone remote。脚本不清理本地或远端旧副本，7 日/4 周保留由对象存储生命周期配置并单独核验。

备份只在受控维护窗口运行：

```bash
DEPLOY_ENV=test YUEJI_PROJECT_ROOT=/opt/yueji YUEJI_RUNTIME_ENV=/etc/yueji/test.env \
  YUEJI_COMPOSE_FILE=/opt/yueji/infra/compose.yml \
  bash /opt/yueji/scripts/backup-yueji-linux.sh --confirm-maintenance
```

恢复前先由负责人把选定的 test 环境 `.age` 归档取到隔离主机可读路径；配置 `AGE_IDENTITY_FILE`、`RESTORE_DATABASE=yueji_mall_restore_<run>`、空的 `RESTORE_FILE_STORAGE_PATH=/opt/yueji/restore/<run>`。恢复只允许 `DEPLOY_ENV=test`，并拒绝来源标记为 prod 的归档；database 名必须使用 `yueji_mall_restore_` 前缀，文件目录不得是活动目录。脚本拒绝非空目标，校验加密归档内部 SHA-256、拒绝路径穿越及符号链接，不会清空、覆盖或删除既有表：

```bash
DEPLOY_ENV=test YUEJI_PROJECT_ROOT=/opt/yueji YUEJI_RUNTIME_ENV=/etc/yueji/test.env \
  YUEJI_COMPOSE_FILE=/opt/yueji/infra/compose.yml \
  bash /opt/yueji/scripts/restore-yueji-isolated-linux.sh /restricted/backup.age --confirm-isolated-restore
```

记录输出的备份时刻、RPO/RTO 秒数、表数、订单状态抽样、恢复文件数和读取样本 SHA。负责人还需在隔离测试实例上检查 schema、关键订单/支付/退款/库存不变量、文件可读性和应用 readiness；只跑 SQL 导入或报告表计数不算 TC-28 通过。任何活动数据库切换均在独立授权和审批流程中进行，本脚本不提供该能力。

## 监控核验与证据模板

Prometheus 抓取应用 `/actuator/prometheus`，Prometheus/Grafana 只绑定主机回环，Loki 不发布主机端口；告警规则在规则页可见。当前没有 Loki log shipper 和 Alertmanager 通知接收器，因此不得声称日志链路或通知已联调。告警规则被加载不等于告警已发出。

| 记录项 | 实际值 |
|---|---|
| 环境 / 主机 / OS / CPU / 内存 / 磁盘 | 待实际部署填写 |
| 维护窗口编号 / 操作者 / 开始与结束 UTC | 待实际部署填写 |
| GH Actions run ID / Git SHA / manifest 校验 | 待实际部署填写 |
| Backend digest / Frontend digest | 待实际部署填写 |
| 数据迁移版本 / 备份标识 / 远端对象及 hash | 待实际部署填写 |
| API readiness / HTTP 冒烟结果和证据文件 | 待实际部署填写 |
| Prometheus target / rule 状态 / 告警通知 | 待实际部署填写，通知未配置前写“未执行” |
| 恢复源 / 隔离库 / RPO / RTO / 表计数 / 订单样本 / 文件读取 | 待实际演练填写 |
| 失败、回退和未执行事项 | 待实际部署填写 |

手动应用回退同样要求命令环境和受限运行配置都声明 `DEPLOY_ENV=test`，且需显式设置 `YUEJI_ALLOW_APP_ROLLBACK=true`。回退只换回应用镜像 digest；数据库保持向前迁移状态。
