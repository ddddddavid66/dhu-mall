package com.yueji.mall.payment.adapter;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class PaymentSettings {
    public static final String SANDBOX="https://openapi-sandbox.dl.alipaydev.com/gateway.do";
    public final String provider,appId,sellerId,privateKey,publicKey,notifyUrl,returnUrl,gateway;
    public final boolean mockEnabled;
    public PaymentSettings(Environment environment,@Value("${yueji.payment.provider:LOCAL_MOCK}") String provider,
            @Value("${yueji.mock-payment-enabled:false}") boolean enabled,@Value("${yueji.payment.app-id:}") String appId,
            @Value("${yueji.payment.seller-id:}") String seller,@Value("${yueji.payment.private-key:}") String privateKey,
            @Value("${yueji.payment.public-key:}") String publicKey,@Value("${yueji.payment.notify-url:}") String notify,
            @Value("${yueji.payment.return-url:}") String returns,@Value("${yueji.payment.gateway:"+SANDBOX+"}") String gateway) {
        boolean prod=environment.matchesProfiles("prod");
        if(!provider.equals("LOCAL_MOCK") && !provider.equals("ALIPAY_SANDBOX")) throw new IllegalStateException("不支持的支付环境");
        if(!SANDBOX.equals(gateway)) throw new IllegalStateException("仅允许固定支付宝沙箱网关");
        if(prod && (enabled || provider.equals("LOCAL_MOCK"))) throw new IllegalStateException("prod 禁止本地模拟支付");
        this.provider=provider;this.mockEnabled=enabled && provider.equals("LOCAL_MOCK") && environment.matchesProfiles("dev | test") && !prod;
        this.appId=appId;this.sellerId=seller;this.privateKey=privateKey;this.publicKey=publicKey;this.notifyUrl=notify;this.returnUrl=returns;this.gateway=gateway;
    }
    public boolean sandboxReady() {
        return appId.matches("[0-9]{8,32}") && sellerId.matches("[0-9]{8,30}") && !privateKey.isBlank() && !publicKey.isBlank() && secureUrl(notifyUrl) && secureUrl(returnUrl);
    }
    private boolean secureUrl(String url) {
        try {var uri=URI.create(url);return "https".equals(uri.getScheme()) && uri.getHost()!=null && uri.getUserInfo()==null && uri.getFragment()==null;}
        catch(IllegalArgumentException error) {return false;}
    }
}
