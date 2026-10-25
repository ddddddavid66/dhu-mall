# 阶段 C 版本冻结

日期：2026-10-23。沿用 A/B 冻结的 Java 21、Spring Boot 3.5.16、MySQL 8.4.13、MyBatis-Plus、Flyway、Vue/Node/pnpm 与镜像 digest；没有新增数据库、微服务、Redis 或消息实例。

新增依赖只有 `com.twelvemonkeys.imageio:imageio-webp:3.12.0`，先核对[维护者格式支持说明](https://haraldk.github.io/TwelveMonkeys/)与 [Maven Central](https://central.sonatype.com/artifact/com.twelvemonkeys.imageio/imageio-webp/3.12.0)，再固定版本解析。JPEG/PNG 使用 JDK ImageIO，WebP 使用此插件解码；三种格式全部重新编码为 PNG，不复制原始元数据和附加字节。版本与策略见 infra/versions.stage-c.lock.json；最终传递依赖见 infra/backend-dependencies.lock.txt。

数据库仅新增 V5/V6，不重写 V1—V4。V5 建立文件、分类、书目、Product/SKU、库存、购物车和幂等；V6 拓扑建立库存流水和凭证鉴权需要的订单/售后外键依赖，以及真实只读评价表。依赖结构不等于提前实现交易或评价写入，开发种子不生成这些业务数据。

完整版本、工具运行输出、文件哈希和实际结果见 C 验证机器证据。Docker daemon 仍无运行条件，因此未声称完成镜像或 Linux 实测；沿用 B 的真实隔离 MySQL Windows 实例验证。
