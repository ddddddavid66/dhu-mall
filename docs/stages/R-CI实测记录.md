# 收尾阶段 CI 实测记录

仓库：ddddddavid66/dhu-mall，默认main。源码实际位于backend/src/main/java/com/yueji/mall与frontend/src；GitHub目录API和远程refs已核验。流水线红色不表示源码没有推送。

| 运行 | 输入提交 | 实际结果和定位 | 后续处理 |
|---|---|---|---|
| 40282687327 | 8341745 | 后端178项和80.1777%门槛、5浏览器、前端质量、CodeQL通过；Trivy配置检查DS-0002失败，镜像晋级跳过 | 非root默认用户修复；历史失败保留 |
| 40284373617 | 59f748b | 上述业务质量再次通过；配置问题仍失败；新增include-hidden-files后浏览器报告已实际上传并下载核验5通过0跳过 | 不忽略配置检查，继续修复 |
| 40285947137 | 04c562f | 四个质量job全部通过，包括源码依赖/秘密/配置扫描；GHCR登录成功；镜像构建在31秒失败：Attestation is not supported for the docker driver | 改用固定BuildKit的docker-container驱动，继续保留SBOM和provenance |
| 40288130302 | 88f76bd | 四个质量job通过；前后端构建、SBOM/provenance生成及GHCR推送通过；后端镜像扫描17项（11高危/6严重）退出1，前端扫描与部署清单步骤跳过 | 同时冻结BuildKit与SBOM生成器digest；不涉及业务依赖升级 |

BuildKit v0.34.1官方发布于2026-10-20；本机实际运行buildkitd核验同版本和提交f5b59a1f2fb25a0cb7072c398cab9aed7835da4e。镜像digest与生成器digest在infra/versions.stage-r.lock.json。默认docker驱动不支持该次请求的证明产物，不能通过删掉--sbom/--provenance来虚假完成原有要求。

本机独立docker-container驱动已实际导出OCI镜像、SPDX SBOM及https://slsa.dev/provenance/v1证明，已解析OCI归档核对两种predicateType，见evidence/R-build-attestation.json。首次完整SBOM构建被Windows端auth.docker.io连接超时阻塞，随后仅为该进程使用机器已配置的本地系统代理后成功，未修改系统代理设置。验证只用FROM scratch最小上下文，不代替商城镜像构建或漏洞门禁；首轮失败日志保留。远端结果另行登记。

本地容器浏览器补测发现HTTPS地址缺少Origin白名单；只增加https://localhost:18443，保留原有精确开发地址与CSRF规则。真实Chromium经非root Nginx完成登录200、重载刷新200、会员订单页和退出200，见evidence/R-container-browser.json；没有放开任意Origin或关闭CSRF。首轮补测因账号菜单未展开而超时，按实际UI展开后通过；不是修改业务断言。

应用依赖未兼容升级，已接受的镜像漏洞仍如实记录；源码扫描成功不等于镜像漏洞扫描成功。即使候选镜像已推送，也只有全部原定门禁通过才生成部署manifest。没有任何prod发布。

最终候选digest见evidence/R-ci-candidate-images.json；运行40288130302于2026-10-26完成，构建驱动错误已消除。镜像风险按ADR-19保留，不产生通过门禁的release-manifest，也不部署。最终470文件快照秘密/配置复扫退出0；首次在线解析受Maven429阻塞，重试仅将不需要的依赖解析设离线，秘密/配置范围与HIGH/CRITICAL门槛保持不变，不将此结论用于依赖漏洞验收。
