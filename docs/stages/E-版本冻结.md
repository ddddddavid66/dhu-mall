# 阶段 E 版本冻结

沿用 A—D 工具链和镜像 digest，不增加容器、数据库或微服务。精确清单见 infra/versions.stage-e.lock.json。

新增官方 SDK com.alipay.sdk:alipay-sdk-java:4.40.1044.ALL，先读取 Maven Central 元数据核实（lastUpdated=20261019104322），再解析依赖锁。实际采用的传递依赖见 infra/backend-dependencies.lock.txt；fastjson 为 SDK 的 1.2.83_noneautotype，HTTP 相关依赖保持官方 SDK 的精确解析版本。本阶段未进行全量漏洞扫描，不能宣称无高危风险。

排除 SDK 引入的 commons-logging（复用 Spring JCL）、bcprov-jdk15on 1.62、dom4j 1.6.1；只采用 RSA2 普通公钥、UTF-8 JSON，不启用 SM2、XML 或证书扩展。真实密钥不进入锁文件。Java、Spring、MySQL、前端版本不调整。

迁移追加 V8 支付结构及 V9 兼容字段扩容，V1—V8 历史文件不改写。V9 将 refund.reason_type 扩为 VARCHAR(32)，已按 ADR-17 获用户同意。契约升级 0.5.0，56 项操作有实现；API-34 在运行时还受 profile/provider/enable 三重限制。IMPLEMENTED 指源码能力，不等于阶段验收或真实沙箱通过。

长度冲突已获决策，见 E-待决策变更.md；V9 及补偿用例的实际验证结果记录在 E-验证与交接.md。真实沙箱仍需独立环境输入。
