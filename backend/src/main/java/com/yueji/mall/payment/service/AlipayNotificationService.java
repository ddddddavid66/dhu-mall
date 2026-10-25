package com.yueji.mall.payment.service;

import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.identity.service.Crypto;
import com.yueji.mall.payment.adapter.AlipaySandboxAdapter;
import com.yueji.mall.payment.model.PaymentModels.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AlipayNotificationService {
    private final AlipaySandboxAdapter alipay;private final PaymentFacts facts;private final SqlStore sql;
    public AlipayNotificationService(AlipaySandboxAdapter alipay,PaymentFacts facts,SqlStore sql) {this.alipay=alipay;this.facts=facts;this.sql=sql;}
    public boolean receive(Map<String,String> p,String trace) {
        // 有界表单先于验签；事件只存哈希，不保存签名、买家身份或整个通知。
        if(p.size()>100 || p.values().stream().anyMatch(v->v.length()>8192)) return false;
        String digest=Crypto.hash(new TreeMap<>(p).toString()),no=p.get("out_trade_no"),key="notify:"+Crypto.hash(Objects.toString(p.get("notify_id"),digest));
        boolean verified=alipay.verify(p);
        if(!verified || !alipay.identityMatches(p) || p.get("notify_id")==null) {facts.rejected("reject:"+digest,digest,verified,no,trace);return false;}
        var row=sql.one("SELECT * FROM payment WHERE payment_no=? AND provider='ALIPAY_SANDBOX'",no);
        if(row.isEmpty()) {facts.rejected(key,digest,true,no,trace);return false;}
        try {
            FactStatus status=switch(Objects.toString(p.get("trade_status"),"")) {case "TRADE_SUCCESS","TRADE_FINISHED"->FactStatus.PAID;case "TRADE_CLOSED"->FactStatus.CLOSED;case "WAIT_BUYER_PAY"->FactStatus.WAITING;default->FactStatus.UNKNOWN;};
            if(status==FactStatus.UNKNOWN || p.get("total_amount")==null || p.get("trade_no")==null || p.get("trade_no").isBlank() || p.get("trade_no").length()>80) {
                facts.rejected("reject:"+digest,digest,true,no,trace);return false;
            }
            Instant paid=p.get("gmt_payment")==null?null:LocalDateTime.parse(p.get("gmt_payment"),DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
            return facts.apply(row.get().get("id").toString(),new Fact(status,no,p.get("trade_no"),new BigDecimal(p.get("total_amount")),paid),key,digest,trace);
        }catch(IllegalArgumentException | java.time.DateTimeException error) {facts.rejected("reject:"+digest,digest,true,no,trace);return false;}
    }
}
