# 阶段 I CI/CD、运维与恢复验证交接

日期：2026-10-26。阶段 I 已生成可审阅的容器、CI、监控、test 发布、备份和隔离恢复产物，并完成本机可做的静态/解析检查；远端部署、实际容器、CI、告警、备份和恢复均未执行。阶段 I 不能标为通过：其前置阶段 H 的固定质量门槛仍失败，JaCoCo 核心服务分支为 992/1368 = 72.51%，低于 80%。

## 1 现状与设计依据

依据 `AGENTS.md`、`docs/README.md`、01、02、03、04、06、07、08、12、13 和阶段 H 交接。实施边界为：多商家商城维持 Java/Spring Boot 模块化单体和 Vue 独立前端；不新增微服务或无当前使用者的 Redis/RabbitMQ 服务；不改业务、API、字段、订单/支付/库存状态契约。容器和部署只接已构建镜像 digest，单机切换需要维护窗口；Flyway 只向前兼容迁移；故障不会自动清库、删表或逆向 DDL。生产发布需要明确授权，本阶段只提供 test-only 手工触发工作流。

开始时核对了当前目录与环境。`C:\Users\123\Desktop\temp\project` 不含 `.git`，因此不能检查用户已有 Git diff、分支、提交 SHA 或 PR；没有覆盖业务源码。用户提供的既有 H 报告记录 119 项后端测试通过，但覆盖率门禁失败。该事实作为本阶段发布阻断条件保留，没有降低门槛。

## 2 当前阶段范围

- 后端与前端 Dockerfile 固定基础镜像 digest；后端容器以 UID/GID 10001 运行，Compose 用受限文件读取 DB/JWT/支付宝密钥，数据库和管理端口不发布到宿主机。
- Compose 配置 MySQL、后端、Nginx 和可选 Prometheus/Grafana/Loki；公开入口仅 web 80/443，管理面只绑定回环。Prometheus 告警规则与 Grafana dashboard 作为配置产物。
- `quality-release` 将 PR 质量检查、依赖/配置/secret 扫描、CodeQL、JaCoCo 80% 集成门槛与 SHA 镜像构建串联。候选 manifest 只有在 required checks、镜像扫描、SBOM 和 provenance 步骤成功后才生成。
- `deploy-test` 不接收人工镜像地址，而是从指定成功的 `quality-release` run 下载其 digest manifest，核对 main、run ID、SHA 和扫描标志；再通过 known_hosts 固定校验的 SSH 和维护窗口编号触发 test-only 部署。
- Linux 操作脚本支持单机维护窗前置备份、加密异地复制、前一版应用 digest 保存、readiness/API 冒烟、隔离 schema 恢复和只回滚应用 digest。不会自动切换或清理活动数据库。
- 无服务器、Linux Docker、GitHub repo/runner 和必要秘密，因此只完成静态配置/脚本/文档与本机配置解析，不执行实际部署。

## 3 必要的文件变更

- 容器和代理：`.dockerignore`、`backend/Dockerfile`、`backend/docker-entrypoint.sh`、`frontend/Dockerfile`、`frontend/nginx.conf.template`、`infra/compose.yml`。
- 监控：`infra/compose.observability.yml`、`infra/prometheus.yml`、`infra/alert-rules.yml`、`infra/loki.yml`、`infra/grafana/`。
- 工具锁和流水线：`infra/versions.stage-i.lock.json`、`.github/workflows/quality-release.yml`、`.github/workflows/deploy-test.yml`、`infra/runtime.env.example`。
- 运维脚本：`scripts/yueji-ops-common.sh`、`scripts/backup-yueji-linux.sh`、`scripts/restore-yueji-isolated-linux.sh`、`scripts/deploy-yueji-test-linux.sh`、`scripts/yueji-deploy-test-wrapper.sh`、`scripts/rollback-yueji-app-linux.sh`、`scripts/smoke-stage-i-linux.sh`、`scripts/validate-stage-i.py`。
- 记录：本文件、[I 发布与恢复清单](I-发布与恢复清单.md)、[I AI 使用记录](I-AI使用记录.md)、[机器证据](evidence/I-verification.json)，以及根 README / docs README 的阶段状态入口。

Action 版本和 base/监控/MySQL 镜像摘要记录在 `infra/versions.stage-i.lock.json`；第三方 Action 使用完整 commit SHA。镜像 build、pull 和运行尚未在本机发生，lock 中 metadata 来源不等于本机拉取证据。

## 4 实施与验证

### 实际执行的命令与结果

| 实际命令 | 结果 | 证据 |
|---|---|---|
| `python scripts/validate-stage-i.py` | 退出 0；26 项静态策略检查通过，包括完整 Action SHA、Compose digest 引用、仅 web 公网端口、secrets 引用、PR 无部署 secrets、test-only CD/应用回退/隔离恢复和同一 run 的 digest manifest。 | `.artifacts/I-static-validation.json` |
| `docker compose --env-file .artifacts/I-compose-validation/compose.env -f infra/compose.yml -f infra/compose.observability.yml --profile observability config --quiet` | 退出 0。只解析 Compose；用 disposable validation-only 值解析配置，没有启动服务。 | `.artifacts/I-compose-validation.log`、`.artifacts/I-compose-validation/compose.env` |
| PowerShell 循环对 `scripts/*.sh` 和 `backend/docker-entrypoint.sh` 执行 `& 'D:\Git\Git\bin\bash.exe' -n $f.FullName` | 8 个 shell 文件全部通过语法解析；没有在 Windows 上实际执行 Linux 运维逻辑。 | `.artifacts/I-shell-syntax.log` |
| `python -m json.tool` 检查版本锁、Grafana dashboard、静态检查输出和阶段证据 JSON | 四个 JSON 文件均解析成功。 | 对应文件；各命令退出码均为 0 |
| `docker compose version` | Docker Compose v5.3.0。 | `.artifacts/I-environment-check.txt` |
| `docker info --format '{{.ServerVersion}}'` | 退出 1：无法连接 `npipe:////./pipe/docker_engine`，Docker Desktop Linux engine 不可用；因此没有尝试 build/pull/up。 | `.artifacts/I-environment-check.txt` |
| `git -C C:\Users\123\Desktop\temp\project status --short --branch` | 退出 128：不是 Git repository；无法确认 branch、commit、PR 或 diff。 | `.artifacts/I-environment-check.txt` |

`actionlint` 和 PowerShell `ConvertFrom-Yaml` 均未安装，Python 无 PyYAML；因此 GitHub Actions workflow YAML 没有由专用 Action lint/parser 验证，仅做了静态 Action ref 和门禁检查。`docker compose config` 解析的是 Compose YAML，不代表 GitHub Actions YAML 验证。

阶段 H 的既有最终命令 `./mvnw.cmd -B -ntp clean verify` 已在 H 记录中实际运行：119/119 后端测试通过，但 JaCoCo 992/1368 = 72.51% 小于 80%，Maven 以门禁失败退出。阶段 I 没有重跑 Maven，因为本阶段改动不涉及 Java 业务代码；此 H 结果仍阻止 `publish-candidate` 和 digest manifest 生成。H 证据见 `.artifacts/H-backend-verify.log`。

### TC-26—31 状态

| 编号 | 当前状态 | 实际结果与阻塞条件 |
|---|---|---|
| TC-26 | 未执行 | 无 RabbitMQ、消费者或 Linux 运行时。D 的数据库事务/Outbox 写入测试不证明代理确认、消费者崩溃恢复、乱序或死信重放。 |
| TC-27 | 未执行 | 没有 Redis/RabbitMQ 故障注入或依赖恢复环境。Compose 不加入当前代码没有实际使用的服务。 |
| TC-28 | 阻塞 | 备份与隔离恢复脚本已生成，但无 Linux Docker/MySQL、age identity、rclone 配置和远端备份目的地，未运行；无表数、订单样本、文件可读性、RPO/RTO 实测。 |
| TC-29 | 未执行 | 没有 01 固定的 4 vCPU/8 GB Linux SSD 环境、独立压测机或 k6 负载。本阶段没有生成吞吐/延迟数字。 |
| TC-30 | 未执行 | 无 test 主机和连续 24 小时探测/告警接收记录。Compose 中规则文件不等于告警已触发或送达。 |
| TC-31 | 阻塞 | 无 Git repository/remote、GitHub Actions run、test 主机或故障镜像回滚记录；静态工作流文件不构成 CI/CD 运行证据。 |

### 远端未执行项

Docker 镜像 build、pull、Compose up/down、backend readiness、HTTPS API smoke、容器数据卷检查、Prometheus scrape、Grafana 页面、实际通知、test 发布、加密备份、隔离恢复、应用 digest rollback、GitHub Actions、bad-image rollback 和 prod 发布均未执行。原因分别是 Docker daemon、Linux 部署主机、真实受限凭据/证书/备份目标、GitHub repository/runner 权限不可用；没有以静态配置推断运行成功。

## 5 尚未解决的风险和交接记录

1. **发布阻断 I-QG-01：** H 的服务分支覆盖率 72.51% 低于 80%；后续先补业务状态分支并通过 `clean verify`，再运行 GitHub workflow。当前不会有通过流程生成的 release manifest，因此 test CD 无法继续。
2. **监控完整性：** Prometheus/Grafana/Loki 组件与阈值配置已经建立，但没有 Loki log shipper，也没有 Alertmanager/测试通知 receiver。日志面板不能当作已有日志；规则状态也不等同通知送达。启用前按 08 决定安全的日志采集与通知目标并做实际故障演练。
3. **维护窗前置：** 当前没有 Linux 主机、`/etc/yueji/test.env`、证书、沙箱输入、UID 10001 文件权限、GHCR pull 权限、SSH known_hosts 和只允许固定 sudo 命令的部署账号。缺任何一项均不开始发布。
4. **恢复策略：** 备份脚本不删除历史对象；对象存储需独立配置 7 个日备份/4 个周备份保留。恢复只落到空的 `yueji_mall_restore_*` schema 与 `/opt/yueji/restore/` 目录，且没有自动切回应用。实际运行需验证 MySQL root secret mount 与 UID 10001 文件读取权限。
5. **Actions 校验与分支规则：** 目前不能在项目本机解析 Actions workflow。下一执行者需在已配置 GitHub 仓库后以 `actionlint`/GitHub Actions 真实运行验证两条 workflow，并将 frontend、backend、security 和 CodeQL 状态设为 required checks；部署 Environment 仅供 test 使用。
6. **追溯限制：** 无 Git 元数据，当前改动没有 commit SHA 或 PR；先在项目受控仓库确认目录内容、建立版本控制并由人工 review 后再接入真实 CI。

下一步顺序：解决 H-QG-01 → 在真实 GitHub 仓库校验 workflow 与 required checks → 环境负责人准备 Linux test、证书/沙箱、restricted runtime secrets 与 offsite backup → test maintenance window 部署和 API smoke → 触发测试告警并配置日志采集 → 独立隔离恢复和回退演练 → 按 07 继续 TC-26—31。没有完成真实 test 演练前，生产发布不在可操作范围。
