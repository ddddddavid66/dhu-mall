# 阶段 A 版本冻结说明

核对日期：2026-10-23。精确值与官方来源以 infra/versions.lock.json 为唯一机器可读清单；冻结发生在前端锁文件与 Maven Wrapper 生成之前。

## 本阶段直接使用

| 组件 | 冻结值 | 核对来源 |
|---|---|---|
| Java | 21.0.12.1+1-LTS | https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse |
| Node | 24.22.1 | https://nodejs.org/dist/v24.22.1/SHASUMS256.txt |
| Maven | 3.9.16 | https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip |
| springBoot | 3.5.16 | https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml |
| springdoc | 2.8.17 | https://repo.maven.apache.org/maven2/org/springdoc/springdoc-openapi-starter-webmvc-ui/maven-metadata.xml |
| vue | 3.5.44 | https://registry.npmjs.org/vue/3.5.44 |
| vue-router | 4.6.4 | https://registry.npmjs.org/vue-router/4.6.4 |
| pinia | 3.0.4 | https://registry.npmjs.org/pinia/3.0.4 |
| element-plus | 2.14.7 | https://registry.npmjs.org/element-plus/2.14.7 |
| vite | 7.3.7 | https://registry.npmjs.org/vite/7.3.7 |
| @vitejs/plugin-vue | 6.0.10 | https://registry.npmjs.org/@vitejs/plugin-vue/6.0.10 |
| typescript | 5.9.3 | https://registry.npmjs.org/typescript/5.9.3 |
| vue-tsc | 3.3.13 | https://registry.npmjs.org/vue-tsc/3.3.13 |
| vitest | 4.1.12 | https://registry.npmjs.org/vitest/4.1.12 |
| @vue/test-utils | 2.5.1 | https://registry.npmjs.org/@vue/test-utils/2.5.1 |
| jsdom | 27.4.0 | https://registry.npmjs.org/jsdom/27.4.0 |
| pnpm | 10.34.7 | https://registry.npmjs.org/pnpm/10.34.7 |
| @redocly/cli | 2.70.0 | https://registry.npmjs.org/@redocly/cli/2.70.0 |
| @types/node | 24.19.2 | https://registry.npmjs.org/@types/node/24.19.2 |

后端 Spring Security、Jackson、Hibernate Validator、Micrometer、JUnit 等由 Spring Boot 3.5.16 BOM 管理，不独立覆盖版本。实际解析图见 infra/backend-dependencies.lock.txt（生成后为验收证据）；Maven 插件由同一父 POM 固定，Wrapper 为 3.3.4、Maven 为 3.9.16。

## 官方镜像 digest

这些是后续构建/运行的基础镜像候选，尚未编写 Dockerfile 或拉起容器。digest 从 Docker Hub 官方 library 标签 API 查询，未读取用户 Docker 凭据。

| 标签定位线 | 多架构 index digest | Linux amd64 manifest digest |
|---|---|---|
| eclipse-temurin:21-jre-jammy | `sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0` | `sha256:8ecbec1f32912979c15b86aaf98f7082a77d318a7f92af3d9fe2c0fd2723df10` |
| node:24-bookworm-slim | `sha256:55fb5f5a527ea2c5c4a95f963e48ef90aa38c7b346054fdc8ee9957a2b55d205` | `sha256:2dd9a4f758e173b65fee15388f7706af6fb01ef50cf7a2c0538711d67a6981ae` |
| nginx:stable-alpine | `sha256:47928bf67eb62a1863cb08d9ecb52d3e704304ba614eaee17884204677702951` | `sha256:84ed8c0dd296c188bfc24ed506f46589e9285fb0b11cf3a04d91d524d3067e11` |

后续 Dockerfile 必须使用清单中的 name@sha256 引用，不能继续用 mutable tag 代替冻结值。查询元数据不等于容器已运行，也不表示已做镜像漏洞扫描。当前 Docker daemon 不可用，容器启动验证未执行。

## 校验和与兼容性

JDK/Node 安装包使用官方 SHA-256；Maven 安装包使用官方 SHA-512，并在 Wrapper 保存校验后的分发包 SHA-256。前端锁记录 tarball integrity；仅允许 esbuild 执行已知安装脚本，未全局允许依赖脚本。Node 24 满足本批依赖记录的 engines；strict-peer-dependencies 和后续构建检查兼容性。

参考：Spring Boot 3.5 系统要求 https://docs.spring.io/spring-boot/3.5/system-requirements.html；Springdoc 2.x 与 Boot 3.x 的对应关系按 2.8 线核对，3.x 文档现对应 Boot 4，不进行跨大版本升级。本阶段还用真实启动及 /v3/api-docs 测试验证选定组合。

## 未提前冻结的后续组件

MySQL/MyBatis-Plus/Flyway 在阶段 B 接入时核对其兼容补丁及镜像；Redis/RabbitMQ 在对应集成阶段核对；Prometheus/Grafana/Loki、Compose 与 CD 在阶段 I 核对。当前不引入未使用依赖，也不为未查询的镜像编造 digest。

## 更新与复现

普通安装使用 frontend/pnpm-lock.yaml 与 backend Maven Wrapper/BOM，不重新执行 scripts/freeze-environment.py。调整版本需显式运行冻结脚本、检查官方兼容要求、更新锁与解析图、执行完整阶段检查并追加 ADR；不得只改 JSON 清单而保留旧依赖。归档和工具目录位于忽略的 .tools，本地验证日志位于 .artifacts。
