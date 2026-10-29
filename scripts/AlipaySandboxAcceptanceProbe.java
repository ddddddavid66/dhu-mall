import com.alipay.api.*;
import com.alipay.api.request.*;
import java.nio.file.*;

/** 仅读取本轮沙箱支付、退款及关单的渠道事实，不记录签名或密钥。 */
class AlipaySandboxAcceptanceProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException("appId privateFile publicFile paidPaymentNo refundNo closePaymentNo");
        com.alipay.api.internal.util.AlipayLogger.setNeedEnableLogger(false);
        var config = new AlipayConfig();
        config.setServerUrl("https://openapi-sandbox.dl.alipaydev.com/gateway.do");
        config.setAppId(args[0]); config.setPrivateKey(key(args[1])); config.setAlipayPublicKey(key(args[2]));
        config.setSignType("RSA2"); config.setFormat("json"); config.setCharset("UTF-8");
        config.setConnectTimeout(5000); config.setReadTimeout(10000);
        try {
            var client = new DefaultAlipayClient(config);
            boolean allConfirmed = true;
            for (int i : new int[]{3, 5}) {
                var query = new AlipayTradeQueryRequest();
                query.setBizContent("{\"out_trade_no\":\"" + identifier(args[i]) + "\"}");
                var response = client.execute(query);
                System.out.printf("{\"operation\":\"query-%s\",\"code\":\"%s\",\"subCode\":\"%s\",\"tradeStatus\":\"%s\",\"amount\":\"%s\"}%n",
                        i == 3 ? "refunded" : "closed", safe(response.getCode()), safe(response.getSubCode()), safe(response.getTradeStatus()), safe(response.getTotalAmount()));
                allConfirmed &= response.isSuccess() && "TRADE_CLOSED".equals(response.getTradeStatus());
            }
            var query = new AlipayTradeFastpayRefundQueryRequest();
            query.setBizContent("{\"out_trade_no\":\"" + identifier(args[3]) + "\",\"out_request_no\":\"" + identifier(args[4]) + "\"}");
            var response = client.execute(query);
            System.out.printf("{\"operation\":\"refund-query\",\"code\":\"%s\",\"refundStatus\":\"%s\",\"amount\":\"%s\"}%n",
                    safe(response.getCode()), safe(response.getRefundStatus()), safe(response.getRefundAmount()));
            allConfirmed &= response.isSuccess() && "REFUND_SUCCESS".equals(response.getRefundStatus()) && "0.01".equals(response.getRefundAmount());
            if (!allConfirmed) System.exit(2);
        } catch (AlipayApiException error) {
            System.out.println("{\"status\":\"CHANNEL_ERROR\",\"type\":\"AlipayApiException\"}");
            System.exit(3);
        }
    }
    private static String identifier(String value) {
        if (!value.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid channel identifier");
        return value;
    }
    private static String safe(String value) { return value == null ? "" : value.replaceAll("[^A-Za-z0-9_.-]", ""); }
    private static String key(String file) throws Exception {
        return Files.readString(Path.of(file)).replaceAll("-----[^-]+-----", "").replaceAll("\\s", "").replace("\uFEFF", "");
    }
}
