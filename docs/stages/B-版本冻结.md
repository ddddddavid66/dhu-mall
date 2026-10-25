# 阶段 B 版本冻结

日期：2026-10-23。A 的工具链和前端依赖继续使用，不随本阶段更新主版本。新增冻结以 infra/versions.stage-b.lock.json 为准；依赖解析快照为 infra/backend-dependencies.lock.txt。

| 组件 | 固定版本 | 来源与验证 |
|---|---|---|
| Java / Maven / Node / pnpm | 21.0.12.1+1 / 3.9.16 / 24.22.1 / 10.34.7 | 沿用 A 官方归档校验及项目内工具 |
| Spring Boot | 3.5.16 | 固定父 POM；Security、Nimbus、JDBC、Connector 等遵守 BOM |
| MyBatis-Plus | 3.5.17 | 官方 Spring Boot 3 starter；权限目录实际通过 Mapper 查询 |
| Flyway Core / MySQL | 11.20.3 | 两个模块同版本，显式覆盖 Boot BOM 的 11.7.2；后者提示 MySQL 8.4 超出其已测试上限，更新后重新验证 |
| MySQL Connector/J | 9.7.0 | Boot BOM；真实 MySQL 8.4 事务和迁移测试 |
| MySQL | 8.4.13 | 官方 Windows ZIP，官方 MD5 与冻结 SHA-256 双校验；真实隔离实例运行 |
| JWT | BOM 管理的 Spring Security OAuth2 JOSE / Nimbus | 只允许 HS256；未增加 JJWT、自建授权服务或额外数据库 |
| JaCoCo | 0.8.14 | [官方发布](https://www.jacoco.org/jacoco/)；报告与核心 service 分支 80% 门禁 |

MySQL 官方归档：https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-8.4.13-winx64.zip 。官方版本页：https://dev.mysql.com/downloads/mysql/8.4.html 。MD5：`7b94489d5524dea9b27097068bea25a8`；本次验证归档 SHA-256：`e9cd98b1d23f122987b1afca26ad942efec5891481103d48ae3cde237fa3ea54`。

Docker Hub 官方 mysql:8.4 标签索引 digest：`sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69`；linux/amd64 digest：`sha256:2068651a18ac6048578647ea57b940ca2daed3f504ebe7fba6a93beeb39aaa1f`。这属于 registry 元数据冻结，当前无 Docker daemon 运行证据；没有把 Windows ZIP 当作容器验证。

冻结顺序：先核对 MyBatis/Flyway/Connector 与 MySQL 元数据及 digest，再解析新增 Maven 依赖。Flyway 更新及 JaCoCo 增加各自固定版本后重新执行构建；最终证据保存冻结文件哈希。Maven 清单是可审计解析快照，不伪称 Maven 原生强制 lockfile。生产迁移不携带账号口令；本机随机凭据和开发种子明文文件均被忽略。
