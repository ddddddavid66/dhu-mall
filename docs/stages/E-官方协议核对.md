# 阶段 E 支付宝协议核对

核对日期 2026-10-24；协议查阅不等于账号开通、凭据正确或实际沙箱交易通过。

先使用 agent-reach 的 Jina 网页读取路径，未取得正文；改用该技能的 Exa 搜索取得支付宝官方通知说明，并通过网页工具直接读取支付宝官方 API 页。未使用第三方教程决定资金状态。

| 项目 | 核对结果与官方来源 |
|---|---|
| 沙箱网关 | 固定 https://openapi-sandbox.dl.alipaydev.com/gateway.do；[支付宝官方沙箱示例](https://aipay.alipay.com/products/agent-pay) |
| 电脑网站支付 | alipay.trade.page.pay，FAST_INSTANT_TRADE_PAY、out_trade_no、total_amount、subject、notify_url、return_url；增加明确 seller_id、time_expire；[官方接口](https://aipay.alipay.com/docs/vibe-pay/ai-web-app-payment-qianyi/api-list/alipay-trade-page-pay.html) |
| 主动查单 | alipay.trade.query，TRADE_SUCCESS/TRADE_FINISHED 为成功，WAIT_BUYER_PAY 为待付；[官方接口](https://aipay.alipay.com/docs/vibe-pay/ai-web-app-payment-qianyi/api-list/alipay-trade-query.html) |
| 关闭 | alipay.trade.close。查单未找到不证明曾给出的支付 URL 永远不可用，因此 PENDING 的 NOT_EXIST 保留预占，不能冒充关闭；[官方接口](https://aipay.alipay.com/docs/vibe-pay/ai-web-app-payment-qianyi/api-list/alipay-trade-close.html) |
| 异步通知 | POST 表单、RSA2，SDK rsaCheckV1；核对 app_id、seller_id、out_trade_no、金额、成功状态及可选币种。CNY 国内接口未携带 currency 时按配置与订单 CNY 校验。事务提交后纯文本 success；失败 fail，重复成功仍确认。[通知说明](https://opendocs.alipay.com/open-v3/099lmj) |
| 退款 | alipay.trade.refund 使用原 paymentNo 与稳定 out_request_no。code=10000 仅代表请求成功，fund_change=Y 才核实此次退款；N/缺失不得标成功。[官方接口](https://aipay.alipay.com/docs/vibe-pay/ai-web-app-payment-qianyi/api-list/alipay-trade-refund.html) |
| 退款查单 | alipay.trade.fastpay.refund.query 返回 REFUND_SUCCESS 才确认；未知保留 PROCESSING；[官方接口](https://aipay.alipay.com/docs/vibe-pay/ai-web-app-payment-qianyi/api-list/alipay-trade-fastpay-refund-query.html) |

Java 官方 SDK 冻结 com.alipay.sdk:alipay-sdk-java:4.40.1044.ALL。Maven Central 元数据 lastUpdated=20261019104322，原始元数据在 .artifacts/E-alipay-sdk-metadata.xml。网关请求签名、同步原文验签和 URL 编码使用 SDK，不自行重排/重新序列化签名 JSON。RSA2 普通公钥模式，不支持未配置的证书模式、真实网关或真实出款。

临时 RSA 密钥、Mockito 受控应答和 LOCAL_MOCK 的测试均标明来源；不能当作支付宝服务器实际应答或沙箱付款截图。真实 TC-10 必须待环境负责人提供独立沙箱应用/卖家、对应私钥/支付宝公钥、已开通能力、可用买家及公网回调后运行。
