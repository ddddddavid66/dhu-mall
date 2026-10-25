# 阶段 E 数据库基线冲突处理记录（已接受）

2026-10-24，影响 FR-07、TC-12、03 第 7 节 refund.reason_type 及迟到支付补偿。

03 的 ENUM(24) 按第 1 节表示 VARCHAR(24)，但同文档规定的 LATE_PAYMENT_COMPENSATION 为 25 字符。隔离 MySQL 首轮测试实际报 Data too long for column 'reason_type'，证据 .artifacts/E-integration-initial.log。

2026-10-25 用户回复：“同意扩为 VARCHAR(32) 并记录”。按同意内容，仅将 reason_type 扩为 ENUM(32)/VARCHAR(32)，不改变枚举、接口、金额、退款唯一性或状态机；通过 V9 追加迁移实现，V1—V8 保留。

决策状态：已接受；验证状态与实际执行结果见 E-验证与交接.md。依据及版本记录见 ADR-17。
