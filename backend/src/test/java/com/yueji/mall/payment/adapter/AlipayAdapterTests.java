package com.yueji.mall.payment.adapter;

import com.alipay.api.*;
import com.alipay.api.request.*;
import com.alipay.api.response.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.payment.model.PaymentModels.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 同步 SDK 应答映射夹具，不是实际沙箱响应。 */
class AlipayAdapterTests {
    AlipayClient client;AlipaySandboxAdapter adapter;Clock clock;Ticket ticket=new Ticket("1","2","P-test","ALIPAY_SANDBOX",new BigDecimal("19.90"),Instant.parse("2026-10-24T12:00:00Z"));
    @BeforeEach void setup() throws Exception {
        var environment=new MockEnvironment();environment.setActiveProfiles("test");
        var settings=new PaymentSettings(environment,"ALIPAY_SANDBOX",false,"2026000000000001","2088000000000001","fixture-key","fixture-key","https://example.invalid/notify","https://example.invalid/return",PaymentSettings.SANDBOX);
        clock=mock(Clock.class);when(clock.instant()).thenReturn(Instant.parse("2026-10-24T11:00:00Z"));
        adapter=spy(new AlipaySandboxAdapter(settings,new ObjectMapper(),clock));client=mock(AlipayClient.class);doReturn(client).when(adapter).client();
    }
    @Test void queryMapsOnlyVerifiedBusinessSuccessAndKnownStates() throws Exception {
        var response=new AlipayTradeQueryResponse();response.setCode("10000");response.setOutTradeNo(ticket.paymentNo());response.setTradeNo("channel-1");response.setTotalAmount("19.90");response.setTransCurrency("CNY");
        when(client.execute(any(AlipayTradeQueryRequest.class))).thenReturn(response);
        for(var entry:Map.of("TRADE_SUCCESS",FactStatus.PAID,"TRADE_FINISHED",FactStatus.PAID,"TRADE_CLOSED",FactStatus.CLOSED,"WAIT_BUYER_PAY",FactStatus.WAITING,"NEW_FUTURE_STATE",FactStatus.UNKNOWN).entrySet()) {response.setTradeStatus(entry.getKey());assertThat(adapter.query(ticket).status()).isEqualTo(entry.getValue());}
        response.setCode("40004");response.setSubCode("ACQ.TRADE_NOT_EXIST");assertThat(adapter.query(ticket).status()).isEqualTo(FactStatus.NOT_FOUND);
        response.setSubCode("ACQ.SYSTEM_ERROR");assertThatThrownBy(()->adapter.query(ticket)).isInstanceOf(BusinessException.class);
        response.setCode("10000");response.setSubCode(null);response.setTransCurrency("USD");assertThatThrownBy(()->adapter.query(ticket)).isInstanceOf(BusinessException.class);
    }
    @Test void closeNotFoundIsUnknownAndSuccessfulCloseChecksOriginalNumber() throws Exception {
        var response=new AlipayTradeCloseResponse();when(client.execute(any(AlipayTradeCloseRequest.class))).thenReturn(response);
        response.setCode("40004");response.setSubCode("ACQ.TRADE_NOT_EXIST");assertThat(adapter.close(ticket).status()).isEqualTo(FactStatus.UNKNOWN);
        response.setCode("10000");response.setSubCode(null);response.setOutTradeNo("wrong");assertThat(adapter.close(ticket).status()).isEqualTo(FactStatus.UNKNOWN);
        response.setOutTradeNo(ticket.paymentNo());assertThat(adapter.close(ticket).status()).isEqualTo(FactStatus.CLOSED);
    }
    @Test void refundRequiresFundChangeAndQueryRequiresRefundSuccess() throws Exception {
        var response=new AlipayTradeRefundResponse();response.setCode("10000");response.setOutTradeNo(ticket.paymentNo());response.setRefundFee("19.90");response.setTradeNo("channel");when(client.execute(any(AlipayTradeRefundRequest.class))).thenReturn(response);
        assertThat(adapter.refund(ticket,"R-same",ticket.amount()).status()).isEqualTo(RefundStatus.UNKNOWN);
        response.setFundChange("N");assertThat(adapter.refund(ticket,"R-same",ticket.amount()).status()).isEqualTo(RefundStatus.UNKNOWN);
        response.setFundChange("Y");assertThat(adapter.refund(ticket,"R-same",ticket.amount()).status()).isEqualTo(RefundStatus.SUCCEEDED);
        var query=new AlipayTradeFastpayRefundQueryResponse();query.setCode("10000");query.setOutTradeNo(ticket.paymentNo());query.setOutRequestNo("R-same");query.setRefundAmount("19.90");when(client.execute(any(AlipayTradeFastpayRefundQueryRequest.class))).thenReturn(query);
        assertThat(adapter.queryRefund(ticket,"R-same",ticket.amount()).status()).isEqualTo(RefundStatus.UNKNOWN);
        query.setRefundStatus("REFUND_SUCCESS");assertThat(adapter.queryRefund(ticket,"R-same",ticket.amount()).status()).isEqualTo(RefundStatus.SUCCEEDED);
        query.setCode("40004");query.setSubCode("ACQ.REFUND_NOT_EXIST");assertThat(adapter.queryRefund(ticket,"R-same",ticket.amount()).status()).isEqualTo(RefundStatus.NOT_FOUND);
        var request=org.mockito.ArgumentCaptor.forClass(AlipayTradeRefundRequest.class);verify(client,times(3)).execute(request.capture());for(var sent:request.getAllValues()) assertThat(sent.getBizContent()).contains("R-same");
    }
    @Test void fiveFailuresOpenCircuitAndOnlyDatabaseWorkMayRetry() throws Exception {
        when(client.execute(any(AlipayTradeQueryRequest.class))).thenThrow(new AlipayApiException("fixture-error"));
        for(int i=0;i<6;i++) assertThatThrownBy(()->adapter.query(ticket)).isInstanceOf(BusinessException.class);verify(client,times(5)).execute(any(AlipayTradeQueryRequest.class));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-24T11:00:31Z"));assertThatThrownBy(()->adapter.query(ticket)).isInstanceOf(BusinessException.class);verify(client,times(6)).execute(any(AlipayTradeQueryRequest.class));
        TransactionSynchronizationManager.setActualTransactionActive(true);try {assertThatThrownBy(()->adapter.query(ticket)).isInstanceOf(IllegalStateException.class);}finally {TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
    @Test void missingInputsAndAnyProductionGatewayFailClosed() {
        var env=new MockEnvironment();env.setActiveProfiles("test");
        var missing=new PaymentSettings(env,"ALIPAY_SANDBOX",false,"","","","","","",PaymentSettings.SANDBOX);
        assertThat(missing.sandboxReady()).isFalse();assertThatThrownBy(()->new AlipaySandboxAdapter(missing,new ObjectMapper(),Clock.systemUTC()).query(ticket)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->new PaymentSettings(env,"ALIPAY_SANDBOX",false,"","","","","","","https://openapi.alipay.com/gateway.do")).isInstanceOf(IllegalStateException.class);
        env.setActiveProfiles("prod");assertThatThrownBy(()->new PaymentSettings(env,"LOCAL_MOCK",false,"","","","","","",PaymentSettings.SANDBOX)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new PaymentSettings(env,"ALIPAY_SANDBOX",true,"","","","","","",PaymentSettings.SANDBOX)).isInstanceOf(IllegalStateException.class);
    }
    @Test void officialSdkVerifiesRawSynchronousResponseAndRejectsTampering() throws Exception {
        var generator=java.security.KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var pair=generator.generateKeyPair();
        String privateKey=Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),publicKey=Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        String body="{\"code\":\"10000\",\"msg\":\"Success\",\"out_trade_no\":\"P-test\",\"trade_no\":\"fixture-channel\",\"trade_status\":\"TRADE_SUCCESS\",\"total_amount\":\"19.90\"}";
        String signed=com.alipay.api.internal.util.AlipaySignature.rsaSign(body,privateKey,"UTF-8","RSA2");
        var raw=new java.util.concurrent.atomic.AtomicReference<>("{\"alipay_trade_query_response\":"+body+",\"sign\":\""+signed+"\"}");
        var merchant=generator.generateKeyPair();
        var config=new AlipayConfig();config.setServerUrl(PaymentSettings.SANDBOX);config.setAppId("2026000000000001");config.setPrivateKey(Base64.getEncoder().encodeToString(merchant.getPrivate().getEncoded()));config.setAlipayPublicKey(publicKey);config.setCharset("UTF-8");config.setSignType("RSA2");config.setFormat("json");
        config.setCustomizedHttpClient(new com.alipay.api.internal.util.AbstractHttpClient() {
            @Override public String doPost(String url,Map<String,String> params,String charset,Map<String,String> headers) {
                assertThat(url).startsWith(PaymentSettings.SANDBOX);return raw.get();
            }
            @Override public String doPost(String url,Map<String,String> params,Map<String,FileItem> files,String charset,Map<String,String> headers) {throw new AssertionError("不应上传文件");}
        });
        var sdk=new DefaultAlipayClient(config);
        var probe=new AlipayTradeQueryRequest();probe.setBizContent("{\"out_trade_no\":\"P-test\"}");
        assertThat(sdk.execute(probe).getTradeStatus()).isEqualTo("TRADE_SUCCESS");
        doReturn(sdk).when(adapter).client();
        assertThat(adapter.query(ticket).status()).isEqualTo(FactStatus.PAID);
        raw.set(raw.get().replace("19.90","0.01"));assertThatThrownBy(()->adapter.query(ticket)).isInstanceOf(BusinessException.class);
    }
}
