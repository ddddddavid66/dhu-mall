import com.alipay.api.*;
import com.alipay.api.request.AlipayTradeQueryRequest;
import java.nio.file.*;
import java.util.UUID;

/** 只读沙箱连通探测。私钥从受限文件读取，输出仅含渠道错误码。 */
class AlipaySandboxProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("appId privateKeyFile alipayPublicKeyFile");
        com.alipay.api.internal.util.AlipayLogger.setNeedEnableLogger(false);
        var config = new AlipayConfig();
        config.setServerUrl("https://openapi-sandbox.dl.alipaydev.com/gateway.do");
        config.setAppId(args[0]);
        config.setPrivateKey(key(args[1]));
        config.setAlipayPublicKey(key(args[2]));
        config.setFormat("json"); config.setCharset("UTF-8"); config.setSignType("RSA2");
        config.setConnectTimeout(5000); config.setReadTimeout(10000);
        var request = new AlipayTradeQueryRequest();
        request.setBizContent("{\"out_trade_no\":\"RPROBE" + UUID.randomUUID().toString().replace("-", "") + "\"}");
        try {
            var response = new DefaultAlipayClient(config).execute(request);
            System.out.printf("{\"provider\":\"ALIPAY_SANDBOX\",\"method\":\"alipay.trade.query\",\"code\":\"%s\",\"subCode\":\"%s\",\"paymentExecuted\":false}%n",
                    safe(response.getCode()), safe(response.getSubCode()));
            if (!"ACQ.TRADE_NOT_EXIST".equals(response.getSubCode())) System.exit(2);
        } catch (Exception error) {
            // 原始 SDK 异常可能携带签名请求，不输出原文或堆栈。
            System.out.println("{\"status\":\"CHANNEL_ERROR\",\"errorType\":\"" + error.getClass().getSimpleName() + "\",\"paymentExecuted\":false}");
            System.exit(3);
        }
    }
    private static String key(String path) throws Exception {
        return Files.readString(Path.of(path)).replaceAll("-----[^-]+-----", "").replaceAll("\\s", "").replace("\uFEFF", "");
    }
    private static String safe(String value) { return value == null ? "" : value.replaceAll("[^A-Za-z0-9_.-]", ""); }
}
