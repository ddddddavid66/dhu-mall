# 阶段 D 版本冻结

日期：2026-10-24。本轮没有新增或升级依赖，继续使用 A/B/C 已冻结的 JDK 21、Spring Boot 3.5.16、MySQL 8.4.13、Flyway 11.20.3、Maven、Node/pnpm 和 Vue 依赖。镜像 digest 沿用 infra/versions.lock.json；不把 Windows 原生 MySQL 验证写成容器验证。实际 CLI 输出及哈希记录在 D-verification.json。

新增 infra/versions.stage-d.lock.json 记录继承关系、V7 与 OpenAPI 0.4.0。pnpm-lock.yaml 和后端依赖快照无需重写；冻结安装及完整构建仍须通过。V1—V6 不改写，V7 只增加四张既定支撑表和只追加状态日志触发器；旧列与历史数据保留。

阶段 D 数据库事务采用 READ_COMMITTED，避免等待行锁之后继续使用旧一致性快照；数据库锁与条件更新仍是并发依据。事务最多重试两次，不在重试中调用渠道。默认连接池 20、获取连接等待 3 秒；集成测试沿用独立测试库限流 1000，开发仍为每用户 30 次写/分钟。

Outbox 目前只保存真实 PENDING 事实，没有新增 RabbitMQ 客户端或实例，不标记 PUBLISHED。支付、关单确认和关闭重试告警留待 E；取消和超时只进入 CLOSING，保持预占。
