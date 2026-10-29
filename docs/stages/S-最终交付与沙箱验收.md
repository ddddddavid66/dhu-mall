# 最终交付与支付宝沙箱验收记录

日期：2026-10-29。范围依据用户“做完支付宝沙箱以后其他的都不做模拟了”，仅一个工程压缩包和三个Word。历史A—I及R报告保留；本轮不再追加压测、恢复、出款或生产部署。

## 现状与设计依据

既有Java21/Boot3.5.16、数据库V11、92操作契约不变，后端业务源码未修改。保留原178项及80.1777%服务分支、10项前端单元、5项浏览器证据；本轮没有重新运行完整业务测试。

## 实施与验证

| 实际命令或操作 | 实际结果 | 证据 |
|---|---|---|
| 独立Compose项目+compose.sandbox.yml | 2026-10-27空库迁移、独立dev种子和沙箱配置启动，mock确认关闭 | .artifacts/alipay-sandbox/start-seed.log；运行env和随机账号不公开 |
| prepare-sandbox-order.py及原幂等响应恢复 | 虚构0.01元订单1与唯一paymentNo；首轮字段名错误未重复付款 | .artifacts/alipay-sandbox/order.json仅本机；不上传签名URL |
| 用户亲自完成支付宝沙箱买家付款 | payment SUCCEEDED，订单PAID，有效异步通知HTTP200/success；无效签名不确认 | S-alipay-sandbox.json |
| 正式售后、客服审核、独立财务执行API | 未发货退款0.01成功，订单CLOSED_REFUND/REFUNDED | 同上；本机refund-result.json |
| Java21运行AlipaySandboxAcceptanceProbe，固定沙箱网关 | 退款交易10000/TRADE_CLOSED，退款查询10000/REFUND_SUCCESS；未付款交易40004/ACQ.TRADE_NOT_EXIST；总退出2 | 同上；本机channel-final-query-all.jsonl |
| scripts/sync-contract.py --check | 92操作同步通过 | 本轮命令输出 |
| scripts/validate-stage-i.py | 26静态检查通过；不是远端部署证据 | .artifacts/S-static-ops-check.json |
| build-final-documents.py + export-final-documents.ps1 | 三个Word实际导出PDF，需求8页、设计53页、报告9页 | S-material-validation.json；本机Word导出与PDF |
| render-final-documents.py | 文档技能render_docx.py生成70页PNG；逐页接触表检查，修正表头孤页；DOCX CRC通过 | 同上；.artifacts/final-doc-qa |
| Python AST、精确本机凭据匹配、git check-ignore | 新Python脚本语法通过；483源码文件无所比对真实凭据；env/账号/签名链接被忽略 | .artifacts/S-exact-secret-check.json；不替代漏洞扫描 |
| package-final-delivery.py | 从干净Git提交生成工程ZIP，包含11份迁移审阅副本及部署入口；运行后由receipt记CRC、条目哈希和源SHA | .artifacts/final-delivery-receipt.json；工程ZIP内manifest与SHA256SUMS |

## 尚未解决的风险及停止追加范围

未付款关单没有取得渠道确认，最后本地观察仍CLOSING/PENDING且reserved=1，不能强制释放或标记通过。2026-10-29Docker Desktop旧engine.sock故障导致无法重新读取已停止库；启动及IPC改名尝试均失败，无恢复出厂、无删卷或清库。使用Microsoft Word完成渲染，不把环境故障掩盖为业务通过。

17项依赖发现保持未修复，原CI镜像门禁失败。24小时探测已取消；独立备份、标准性能、额外恢复/回退及远端CD不再追加，历史未完成不改成通过。教师资料与本人签名待补，不代签；商家出款仍模拟。

## 交接

最终目录deliverables/最终提交仅放四项交付；历史PPT、视频及课程预览包不覆盖。已运行的命令和实际结果在本页及19报告，业务基线测试仍引用历史报告。文件哈希、源SHA和ZIP校验以打包脚本实际生成值为准，不预填。
