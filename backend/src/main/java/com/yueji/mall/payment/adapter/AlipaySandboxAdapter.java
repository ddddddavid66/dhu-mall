package com.yueji.mall.payment.adapter;

import com.alipay.api.*;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yueji.mall.common.api.*;
import com.yueji.mall.payment.model.PaymentModels.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** SDK 负责 RSA2 请求签名、同步原文验签和 URL 编码；网关只能为沙箱。 */
@Component
public class AlipaySandboxAdapter implements PaymentAdapter {
    private final PaymentSettings settings;private final ObjectMapper mapper;private final Clock clock;
    private int failures;private boolean probing;private Instant openUntil=Instant.EPOCH;
    public AlipaySandboxAdapter(PaymentSettings settings,ObjectMapper mapper,Clock clock) {
        this.settings=settings;this.mapper=mapper;this.clock=clock;
        // SDK 默认诊断可能包含完整渠道应答；只保留本应用的脱敏事件摘要和错误码。
        com.alipay.api.internal.util.AlipayLogger.setNeedEnableLogger(false);
    }
    AlipayClient client() throws AlipayApiException {
        if(!settings.sandboxReady()) throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE);
        var config=new AlipayConfig();config.setServerUrl(settings.gateway);config.setAppId(settings.appId);config.setPrivateKey(settings.privateKey);
        config.setAlipayPublicKey(settings.publicKey);config.setFormat("json");config.setCharset("UTF-8");config.setSignType("RSA2");
        config.setConnectTimeout(3000);config.setReadTimeout(7000);return new DefaultAlipayClient(config);
    }
    @FunctionalInterface private interface ChannelCall<T> {T call(AlipayClient client) throws Exception;}
    private <T> T invoke(ChannelCall<T> call) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("渠道调用不得进入数据库事务");
        synchronized(this) {
            if(!Instant.EPOCH.equals(openUntil)) {
                if(clock.instant().isBefore(openUntil) || probing) throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE);
                probing=true;
            }
        }
        try {T result=call.call(client());synchronized(this) {failures=0;probing=false;openUntil=Instant.EPOCH;}return result;}
        catch(Exception error) {
            synchronized(this) {if(probing || ++failures>=5) {openUntil=clock.instant().plusSeconds(30);failures=0;}probing=false;}
            // SDK 原始异常可能含请求参数，不传播或记录原文。
            throw new BusinessException(ErrorCode.CHANNEL_UNAVAILABLE);
        }
    }
    private String json(Object value) throws Exception {return mapper.writeValueAsString(value);}
    @Override public PaymentAction action(Ticket ticket) {
        return invoke(client->{
            var request=new AlipayTradePagePayRequest();request.setNotifyUrl(settings.notifyUrl);request.setReturnUrl(settings.returnUrl);
            request.setBizContent(json(Map.of("out_trade_no",ticket.paymentNo(),"total_amount",ticket.amount().toPlainString(),"subject","阅集订单 "+ticket.orderId(),
                    "product_code","FAST_INSTANT_TRADE_PAY","seller_id",settings.sellerId,"time_expire",DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai")).format(ticket.expiresAt()))));
            String url=client.pageExecute(request,"GET").getBody();
            if(!url.startsWith(settings.gateway+"?")) throw new IllegalStateException("支付跳转地址不合法");
            return new PaymentAction("REDIRECT",url,ticket.expiresAt());
        });
    }
    @Override public Fact query(Ticket ticket) {
        return invoke(client->{
            var request=new AlipayTradeQueryRequest();request.setBizContent(json(Map.of("out_trade_no",ticket.paymentNo())));
            var response=client.execute(request);
            if("ACQ.TRADE_NOT_EXIST".equals(response.getSubCode())) return new Fact(FactStatus.NOT_FOUND,ticket.paymentNo(),null,null,null);
            if(!response.isSuccess()) throw new IllegalStateException("查单未确认");
            if(response.getTransCurrency()!=null && !"CNY".equals(response.getTransCurrency())) throw new IllegalStateException("币种不符");
            var status=switch(response.getTradeStatus()) {case "TRADE_SUCCESS","TRADE_FINISHED"->FactStatus.PAID;case "TRADE_CLOSED"->FactStatus.CLOSED;case "WAIT_BUYER_PAY"->FactStatus.WAITING;default->FactStatus.UNKNOWN;};
            return new Fact(status,response.getOutTradeNo(),response.getTradeNo(),new BigDecimal(response.getTotalAmount()),response.getSendPayDate()==null?clock.instant():response.getSendPayDate().toInstant());
        });
    }
    @Override public Fact close(Ticket ticket) {
        return invoke(client->{
            var request=new AlipayTradeCloseRequest();request.setBizContent(json(Map.of("out_trade_no",ticket.paymentNo())));var response=client.execute(request);
            // NOT_EXIST 不能证明先前给出的支付 URL 永远不会被使用，保守保留预占。
            if(!response.isSuccess() || !ticket.paymentNo().equals(response.getOutTradeNo())) return Fact.unknown(ticket.paymentNo());
            return new Fact(FactStatus.CLOSED,ticket.paymentNo(),response.getTradeNo(),ticket.amount(),null);
        });
    }
    @Override public RefundFact refund(Ticket ticket,String no,BigDecimal amount) {
        return invoke(client->{
            var request=new AlipayTradeRefundRequest();request.setBizContent(json(Map.of("out_trade_no",ticket.paymentNo(),"out_request_no",no,"refund_amount",amount.toPlainString(),"refund_reason","阅集商城订单退款")));
            var response=client.execute(request);
            BigDecimal confirmed=response.getRefundFee()==null?null:new BigDecimal(response.getRefundFee());
            boolean paid=response.isSuccess() && "Y".equals(response.getFundChange()) && ticket.paymentNo().equals(response.getOutTradeNo()) && confirmed!=null && confirmed.compareTo(amount)==0;
            return new RefundFact(paid?RefundStatus.SUCCEEDED:RefundStatus.UNKNOWN,no,response.getOutTradeNo(),paid?confirmed:null,response.getTradeNo());
        });
    }
    @Override public RefundFact queryRefund(Ticket ticket,String no,BigDecimal amount) {
        return invoke(client->{
            var request=new AlipayTradeFastpayRefundQueryRequest();request.setBizContent(json(Map.of("out_trade_no",ticket.paymentNo(),"out_request_no",no)));var response=client.execute(request);
            if("ACQ.REFUND_NOT_EXIST".equals(response.getSubCode())) return new RefundFact(RefundStatus.NOT_FOUND,no,ticket.paymentNo(),null,null);
            boolean paid=response.isSuccess() && "REFUND_SUCCESS".equals(response.getRefundStatus());
            boolean failed=response.isSuccess() && "REFUND_CLOSED".equals(response.getRefundStatus());
            BigDecimal confirmed=paid&&response.getRefundAmount()!=null?new BigDecimal(response.getRefundAmount()):null;
            if(confirmed!=null&&confirmed.compareTo(amount)!=0)paid=false;
            return new RefundFact(paid?RefundStatus.SUCCEEDED:failed?RefundStatus.FAILED:RefundStatus.UNKNOWN,response.getOutRequestNo(),response.getOutTradeNo(),paid?confirmed:null,response.getTradeNo());
        });
    }
    public boolean verify(Map<String,String> parameters) {
        if(!settings.sandboxReady() || !"ALIPAY_SANDBOX".equals(settings.provider) || !"RSA2".equals(parameters.get("sign_type"))) return false;
        try {return AlipaySignature.rsaCheckV1(parameters,settings.publicKey,"UTF-8","RSA2");}catch(Exception error) {return false;}
    }
    public boolean identityMatches(Map<String,String> p) {
        return settings.appId.equals(p.get("app_id")) && settings.sellerId.equals(p.get("seller_id")) && "trade_status_sync".equals(p.get("notify_type")) && "CNY".equals(p.getOrDefault("currency","CNY"));
    }
}
