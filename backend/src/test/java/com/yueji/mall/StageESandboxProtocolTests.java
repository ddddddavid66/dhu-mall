package com.yueji.mall;

import com.alipay.api.internal.util.AlipaySignature;
import com.yueji.mall.payment.adapter.AlipaySandboxAdapter;
import com.yueji.mall.payment.model.PaymentModels.*;
import com.yueji.mall.payment.repository.PaymentRepository;
import com.yueji.mall.payment.service.*;
import java.math.BigDecimal;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅协议夹具：临时 RSA 密钥及受控渠道应答；绝不标记为真实支付宝沙箱联调。 */
@ActiveProfiles("test") @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"yueji.payment.provider=ALIPAY_SANDBOX","yueji.mock-payment-enabled=true"})
class StageESandboxProtocolTests extends StageEFixtures {
    static final KeyPair KEYS=keys();static final String APP="2026000000000001",SELLER="2088000000000001";
    static KeyPair keys() {try {var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);return generator.generateKeyPair();}catch(Exception error) {throw new IllegalStateException(error);}}
    @DynamicPropertySource static void sandbox(DynamicPropertyRegistry p) {
        p.add("yueji.payment.app-id",()->APP);p.add("yueji.payment.seller-id",()->SELLER);
        p.add("yueji.payment.private-key",()->Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()));
        p.add("yueji.payment.public-key",()->Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
        p.add("yueji.payment.notify-url",()->"https://sandbox.example.invalid/api/v1/payments/alipay/notify");p.add("yueji.payment.return-url",()->"https://sandbox.example.invalid/payment-result");
    }
    @MockitoSpyBean AlipaySandboxAdapter alipay;
    @Autowired PaymentRepository payments;@Autowired PaymentService service;
    @BeforeEach void channelFixture() {doAnswer(i->{Ticket t=i.getArgument(0);return new Fact(FactStatus.WAITING,t.paymentNo(),null,t.amount(),null);}).when(alipay).query(any());}
    String pay(String order) {return data(call("POST","/orders/"+order+"/payments",null,a,key()),201).path("id").asText();}
    Map<String,String> notification(String id) {
        var row=payments.row(id);var p=new HashMap<String,String>();
        p.put("app_id",APP);p.put("seller_id",SELLER);p.put("out_trade_no",row.get("payment_no").toString());p.put("trade_no","sandbox-fixture-"+id);
        p.put("notify_id",UUID.randomUUID().toString());p.put("notify_type","trade_status_sync");p.put("trade_status","TRADE_SUCCESS");p.put("total_amount",row.get("amount").toString());
        p.put("gmt_payment","2026-10-24 08:00:00");p.put("sign_type","RSA2");return p;
    }
    Map<String,String> signed(Map<String,String> p) throws Exception {
        var parameters=new TreeMap<>(p);parameters.remove("sign");parameters.remove("sign_type");
        String canonical=parameters.entrySet().stream().filter(e->!e.getValue().isEmpty()).map(e->e.getKey()+"="+e.getValue()).collect(java.util.stream.Collectors.joining("&"));
        var signature=Signature.getInstance("SHA256withRSA");signature.initSign(KEYS.getPrivate());signature.update(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));p.put("sign",Base64.getEncoder().encodeToString(signature.sign()));return p;
    }
    String notify(Map<String,String> parameters) {
        var form=new LinkedMultiValueMap<String,String>();parameters.forEach(form::add);var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        var response=client.postForEntity("/api/v1/payments/alipay/notify",new HttpEntity<>(form,headers),String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);assertThat(response.getHeaders().getContentType().toString()).startsWith("text/plain");return response.getBody();
    }
    @Test void sdkGeneratesSignedSandboxRedirectAndSignedNotificationReplaysTwentyTimes() throws Exception {
        String order=order(batch()),id=pay(order),url=data(call("POST","/orders/"+order+"/payments",null,a,key()),200).path("paymentAction").path("url").asText();
        assertThat(url).startsWith("https://openapi-sandbox.dl.alipaydev.com/gateway.do?");
        var query=new TreeMap<String,String>();for(String part:java.net.URI.create(url).getRawQuery().split("&")) {var kv=part.split("=",2);query.put(java.net.URLDecoder.decode(kv[0],java.nio.charset.StandardCharsets.UTF_8),java.net.URLDecoder.decode(kv[1],java.nio.charset.StandardCharsets.UTF_8));}
        var biz=mapper.readTree(query.get("biz_content"));assertThat(biz.path("out_trade_no").asText()).isEqualTo(payments.row(id).get("payment_no"));assertThat(biz.path("seller_id").asText()).isEqualTo(SELLER);assertThat(biz.path("time_expire").asText()).isNotBlank();
        assertThat(query.get("method")).isEqualTo("alipay.trade.page.pay");String sign=query.remove("sign");assertThat(AlipaySignature.rsaCheck(AlipaySignature.getSignContent(query),sign,Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()),"UTF-8","RSA2")).isTrue();
        var p=signed(notification(id));for(int i=0;i<20;i++) assertThat(notify(p)).isEqualTo("success");
        assertThat(sql.count("SELECT COUNT(*) FROM order_status_log WHERE action='PAYMENT_VERIFIED'")).isEqualTo(1);assertThat(sql.count("SELECT COUNT(*) FROM inventory_movement WHERE movement_type='COMMIT'")).isEqualTo(1);
        assertThat(sql.count("SELECT COUNT(*) FROM payment_event WHERE event_type='CHANNEL_FACT' AND processing_status='PROCESSED'")).isEqualTo(2); // 受控 WAITING 查单和成功通知
    }
    @Test void invalidSignIdentityAmountAndUnknownNumberCannotChangeBusiness() throws Exception {
        String id=pay(order(batch()));var p=signed(notification(id));p.put("total_amount","0.01");assertThat(notify(p)).isEqualTo("fail");
        for(String field:List.of("app_id","seller_id","currency","total_amount","out_trade_no")) {p=notification(id);p.put(field,field.equals("total_amount")?"0.01":"invalid");assertThat(notify(signed(p))).as(field).isEqualTo("fail");}
        assertThat(sql.count("SELECT COUNT(*) FROM payment WHERE status='SUCCEEDED'")).isZero();assertThat(sql.count("SELECT COUNT(*) FROM inventory_movement WHERE movement_type='COMMIT'")).isZero();
        assertThat(sql.count("SELECT COUNT(*) FROM payment_event WHERE processing_status='REJECTED'")).isGreaterThanOrEqualTo(6);
        String back=client.getForEntity("/api/v1/payments/"+id+"?success=true",String.class).getBody();assertThat(back).contains("AUTH_REQUIRED");
        assertThat(data(call("GET","/payments/"+id+"?success=true",null,a,null),200).path("status").asText()).isEqualTo("PENDING");
        assertThat(sql.count("SELECT COUNT(*) FROM trade_order WHERE status='PAID'")).isZero();
    }
    @Test void sandboxNeverRegistersMockRouteEvenWithSwitchEnabled() {
        assertThat(call("POST","/payments/1/mock-confirm",null,a,key()).getStatusCode().value()).isEqualTo(404);
    }
    @Test void querySuccessAfterRequestCrashUsesSameNoAndUnknownClosePreservesStock() {
        String order=order(batch()),id=pay(order);var t=payments.ticket(payments.row(id));
        data(call("POST","/orders/"+order+"/cancel",Map.of("reason","测试"),a,key()),202);
        doReturn(Fact.unknown(t.paymentNo())).when(alipay).close(any());sql.update("UPDATE payment SET last_checked_at=NULL WHERE id=?",id);service.check(t,"protocol-close",false);
        assertThat(sql.required("SELECT status FROM trade_order WHERE id=?",order).get("status")).isEqualTo("CLOSING");assertThat(sql.count("SELECT SUM(reserved) FROM inventory")).isEqualTo(3);
        doReturn(new Fact(FactStatus.PAID,t.paymentNo(),"queried-trade",t.amount(),java.time.Instant.now())).when(alipay).query(any());sql.update("UPDATE payment SET last_checked_at=NULL WHERE id=?",id);service.check(t,"protocol-recover",false);
        assertThat(payments.row(id).get("status")).isEqualTo("SUCCEEDED");assertThat(sql.required("SELECT status FROM trade_order WHERE id=?",order).get("status")).isEqualTo("PAID");
    }
    @Test void malformedOrUnboundedSignedNotificationsAreRejectedAndDiagnosed() throws Exception {
        String id=pay(order(batch()));
        for(String missing:List.of("notify_id","total_amount","trade_no")) {var p=notification(id);p.remove(missing);assertThat(notify(signed(p))).as(missing).isEqualTo("fail");}
        for(var entry:Map.of("trade_status","FUTURE_UNKNOWN","total_amount","not-a-number","trade_no"," ","gmt_payment","invalid-date").entrySet()) {var p=notification(id);p.put(entry.getKey(),entry.getValue());assertThat(notify(signed(p))).as(entry.getKey()).isEqualTo("fail");}
        var longTrade=notification(id);longTrade.put("trade_no","x".repeat(81));assertThat(notify(signed(longTrade))).isEqualTo("fail");
        var large=notification(id);large.put("body","x".repeat(8193));assertThat(notify(large)).isEqualTo("fail");
        var many=notification(id);for(int i=0;i<101;i++) many.put("field"+i,"x");assertThat(notify(many)).isEqualTo("fail");
        assertThat(sql.count("SELECT COUNT(*) FROM payment WHERE status='SUCCEEDED'")).isZero();
        assertThat(sql.count("SELECT COUNT(*) FROM payment_event WHERE processing_status='REJECTED'")).isGreaterThanOrEqualTo(8);
    }
}
