package com.yueji.mall;

import com.fasterxml.jackson.databind.*;
import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.identity.service.*;
import com.yueji.mall.trade.service.OrderExpiryService;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test") @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class StageDHttpTests extends MySqlTestSupport {
    @Autowired SqlStore sql;@Autowired Flyway migrations;@Autowired TestRestTemplate client;
    @Autowired AccessTokens tokens;@Autowired IdentityRepository identities;@Autowired ObjectMapper mapper;@Autowired OrderExpiryService expiry;
    @Autowired com.yueji.mall.common.service.DatabaseTransaction transactions;
    private record Buyer(String id,String token,String address) {}
    private Buyer a,b;private String category,book,shop1,shop2,sku1,sku2;
    @BeforeEach void fixtures() {
        Flyway.configure().dataSource(migrations.getConfiguration().getDataSource()).locations("classpath:db/migration").cleanDisabled(false).load().clean();migrations.migrate();
        a=buyer("buyer_d_a","MEMBER");b=buyer("buyer_d_b","MEMBER");
        category=sql.insert("INSERT INTO category(name) VALUES('虚构分类')");
        book=sql.insert("INSERT INTO book(isbn,title,author,publisher,category_id,description) VALUES('9780306406157','虚构新书','虚构作者','虚构出版社',?,'测试')",category);
        shop1=sql.insert("INSERT INTO shop(name,contact_name,contact_phone) VALUES('虚构一店','测试','13800000001')");
        shop2=sql.insert("INSERT INTO shop(name,contact_name,contact_phone) VALUES('虚构二店','测试','13800000002')");
        sku1=product(shop1,"D-SKU-A","19.90",100);sku2=product(shop2,"D-SKU-B","25.00",100);
        cart(a,sku1,2);cart(a,sku2,1);
    }
    private Buyer buyer(String name,String role) {
        String id=sql.insert("INSERT INTO iam_user(username,password_hash,nickname) VALUES(?,'fixture-not-a-login-password','虚构测试会员')",name);
        sql.update("INSERT INTO iam_user_role(user_id,role_id) SELECT ?,id FROM iam_role WHERE code=?",id,role);
        String family=Crypto.randomToken();
        sql.insert("INSERT INTO refresh_session(user_id,family_no,token_hash,expires_at,csrf_hash,auth_version) VALUES(?,?,?,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 7 DAY),?,1)",id,family,Crypto.hash(Crypto.randomToken()),Crypto.hash(Crypto.randomToken()));
        String address=sql.insert("INSERT INTO user_address(user_id,receiver_name,phone,province,city,district,detail) VALUES(?,'虚构收件人','13800000000','虚构省','虚构市','虚构区','虚构地址一号')",id);
        // 隔离库的会话夹具，签发真实 JWT，100 人库存测试不重复测试 BCrypt 登录性能。
        return new Buyer(id,tokens.issue(identities.user(id),family),address);
    }
    private String product(String shop,String code,String price,int stock) {
        String product=sql.insert("INSERT INTO product(shop_id,book_id,description,status) VALUES(?,?,'测试','ON_SALE')",shop,book);
        String sku=sql.insert("INSERT INTO sku(product_id,sku_code,sale_price) VALUES(?,?,?)",product,code,new java.math.BigDecimal(price));
        sql.insert("INSERT INTO inventory(sku_id,on_hand) VALUES(?,?)",sku,stock);return sku;
    }
    private void cart(Buyer buyer,String sku,int quantity) {sql.insert("INSERT INTO cart_item(user_id,sku_id,quantity) VALUES(?,?,?)",buyer.id(),sku,quantity);}
    private String key() {return UUID.randomUUID().toString();}
    private ResponseEntity<JsonNode> call(String method,String path,Object body,Buyer buyer,String key) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.set("X-Request-Id","stage-d-integration-123");
        if(buyer!=null) headers.setBearerAuth(buyer.token());if(key!=null) headers.set("Idempotency-Key",key);
        return client.exchange("/api/v1"+path,HttpMethod.valueOf(method),new HttpEntity<>(body,headers),JsonNode.class);
    }
    private JsonNode data(ResponseEntity<JsonNode> response,int code) {
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(code);
        assertThat(response.getBody().path("code").asText()).isEqualTo("OK");return response.getBody().path("data");
    }
    private void error(ResponseEntity<JsonNode> response,int status,String code) {
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);assertThat(response.getBody().path("data").isNull()).isTrue();
    }
    private List<String> carts(Buyer buyer) {return sql.rows("SELECT id FROM cart_item WHERE user_id=? ORDER BY id",buyer.id()).stream().map(r->r.get("id").toString()).toList();}
    private String preview(Buyer buyer) {return data(call("POST","/checkout/previews",Map.of("addressId",buyer.address(),"cartItemIds",carts(buyer)),buyer,null),201).path("previewId").asText();}
    private ResponseEntity<JsonNode> create(Buyer buyer,String preview,String key) {return call("POST","/checkout/orders",Map.of("previewId",preview),buyer,key);}
    private JsonNode batch() {return data(create(a,preview(a),key()),201);}
    private String order(JsonNode batch) {return batch.path("orders").get(0).path("id").asText();}
    private void emptyTrade() {
        for(String table:List.of("checkout_batch","trade_order","order_item","inventory_reservation","inventory_movement","order_status_log","outbox_event","idempotency_record")) assertThat(sql.count("SELECT COUNT(*) FROM "+table)).as(table).isZero();
        assertThat(sql.count("SELECT COALESCE(SUM(reserved),0) FROM inventory")).isZero();
    }
    private void invariants(String filename) throws Exception {
        var counts=new LinkedHashMap<String,Object>();
        counts.put("stockConstraintViolations",sql.count("SELECT COUNT(*) FROM inventory WHERE reserved>on_hand"));
        counts.put("reservationMismatches",sql.count("SELECT COUNT(*) FROM inventory i WHERE i.reserved<>(SELECT COALESCE(SUM(r.quantity),0) FROM inventory_reservation r WHERE r.sku_id=i.sku_id AND r.status='RESERVED')"));
        counts.put("batchAmountMismatches",sql.count("SELECT COUNT(*) FROM checkout_batch b WHERE b.total_amount<>(SELECT COALESCE(SUM(o.payable_amount),0) FROM trade_order o WHERE o.batch_id=b.id)"));
        counts.put("orderAmountMismatches",sql.count("SELECT COUNT(*) FROM trade_order o WHERE o.goods_amount<>(SELECT COALESCE(SUM(i.line_amount),0) FROM order_item i WHERE i.order_id=o.id) OR o.payable_amount<>o.goods_amount+o.freight_amount OR o.freight_amount<>0"));
        counts.put("missingReservations",sql.count("SELECT COUNT(*) FROM order_item i LEFT JOIN inventory_reservation r ON r.order_item_id=i.id WHERE r.id IS NULL OR r.quantity<>i.quantity OR r.order_id<>i.order_id OR r.sku_id<>i.sku_id"));
        counts.put("movementMismatches",sql.count("SELECT COUNT(*) FROM inventory_reservation r LEFT JOIN inventory_movement m ON m.business_key=CONCAT('RESERVE:',r.order_item_id) WHERE m.id IS NULL OR m.reserved_delta<>r.quantity OR m.on_hand_delta<>0"));
        counts.put("outboxMismatches",sql.count("SELECT COUNT(*) FROM trade_order o WHERE (SELECT COUNT(*) FROM outbox_event e WHERE e.aggregate_id=CAST(o.id AS CHAR) AND e.event_type='OrderCreated')<>1"));
        counts.put("missingCreationLog",sql.count("SELECT COUNT(*) FROM trade_order o WHERE (SELECT COUNT(*) FROM order_status_log l WHERE l.order_id=o.id AND l.action='CREATE' AND l.to_status='PENDING_PAYMENT')<>1"));
        counts.put("previewMismatches",sql.count("SELECT COUNT(*) FROM checkout_batch b JOIN checkout_preview p ON p.id=b.preview_id WHERE p.used_at IS NULL OR p.user_id<>b.user_id OR p.amount<>b.total_amount"));
        for(var entry:counts.entrySet()) assertThat(((Number)entry.getValue()).longValue()).as(entry.getKey()).isZero();
        counts.put("batches",sql.count("SELECT COUNT(*) FROM checkout_batch"));counts.put("orders",sql.count("SELECT COUNT(*) FROM trade_order"));
        counts.put("reserved",sql.count("SELECT COALESCE(SUM(reserved),0) FROM inventory"));counts.put("databaseVersion",sql.required("SELECT VERSION() v").get("v"));counts.put("capturedAt",java.time.Instant.now().toString());
        var path=Path.of("../.artifacts/"+filename);Files.createDirectories(path.getParent());Files.writeString(path,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(counts));
    }
    @Test void tc06SplitTransactionSnapshotsAndDurableOutbox() throws Exception {
        var p=data(call("POST","/checkout/previews",Map.of("addressId",a.address(),"cartItemIds",carts(a)),a,null),201);
        assertThat(p.path("shops").size()).isEqualTo(2);assertThat(p.path("totalAmount").asText()).isEqualTo("64.80");
        var batch=data(create(a,p.path("previewId").asText(),key()),201);assertThat(batch.path("orders").size()).isEqualTo(2);
        assertThat(batch.path("totalAmount").isTextual()).isTrue();String id=order(batch);
        var view=data(call("GET","/orders/"+id,null,a,null),200);
        assertThat(view.path("receiver").path("name").asText()).isEqualTo("虚构收件人");assertThat(view.path("items").get(0).path("unitPrice").asText()).isEqualTo("19.90");
        assertThat(view.path("timeline").get(0).path("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(sql.count("SELECT COUNT(*) FROM cart_item WHERE user_id=?",a.id())).isZero();
        assertThat(sql.count("SELECT COUNT(*) FROM outbox_event WHERE status='PENDING' AND attempts=0 AND published_at IS NULL")).isEqualTo(2);
        var payload=mapper.readTree(sql.required("SELECT payload FROM outbox_event LIMIT 1").get("payload").toString());
        assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrder("eventId","eventType","schemaVersion","occurredAt","aggregateId","traceId","data");
        assertThat(payload.path("data").fieldNames()).toIterable().containsExactlyInAnyOrder("orderId","shopId");
        sql.update("UPDATE user_address SET detail='后来新地址' WHERE id=?",a.address());sql.update("UPDATE book SET title='后来的书名' WHERE id=?",book);sql.update("UPDATE sku SET sale_price=99 WHERE id=?",sku1);
        var immutable=data(call("GET","/orders/"+id,null,a,null),200);assertThat(immutable.path("receiver")).isEqualTo(view.path("receiver"));assertThat(immutable.path("items")).isEqualTo(view.path("items"));
        invariants("D-tc06-invariants.json");
    }
    @Test void tc07PriceProductAndShopChangesRejectWholeBatch() {
        String preview=preview(a);sql.update("UPDATE sku SET sale_price=26,version=version+1 WHERE id=?",sku2);
        error(create(a,preview,key()),409,"PRICE_CHANGED");emptyTrade();
        preview=preview(a);sql.update("UPDATE product SET description='新说明',version=version+1 WHERE shop_id=?",shop1);
        error(create(a,preview,key()),409,"STATE_CONFLICT");emptyTrade();
        preview=preview(a);sql.update("UPDATE shop SET status='SUSPENDED' WHERE id=?",shop2);
        error(create(a,preview,key()),409,"PRODUCT_UNAVAILABLE");emptyTrade();
    }
    @Test void tc07AddressCartDeletionQuantityAndSelectionChangesRequireNewPreview() {
        String p=preview(a);sql.update("UPDATE user_address SET detail='新地址',updated_at=DATE_ADD(updated_at,INTERVAL 1 SECOND) WHERE id=?",a.address());error(create(a,p,key()),409,"STATE_CONFLICT");
        p=preview(a);sql.update("UPDATE cart_item SET quantity=3 WHERE user_id=? AND sku_id=?",a.id(),sku1);error(create(a,p,key()),409,"STATE_CONFLICT");
        p=preview(a);sql.update("UPDATE cart_item SET selected=0 WHERE user_id=? AND sku_id=?",a.id(),sku2);error(create(a,p,key()),409,"STATE_CONFLICT");
        sql.update("UPDATE cart_item SET selected=1 WHERE user_id=?",a.id());p=preview(a);sql.update("DELETE FROM cart_item WHERE user_id=? AND sku_id=?",a.id(),sku2);error(create(a,p,key()),409,"STATE_CONFLICT");
        p=preview(a);sql.update("UPDATE user_address SET status='DELETED' WHERE id=?",a.address());error(create(a,p,key()),409,"STATE_CONFLICT");emptyTrade();
    }
    @Test void tc07StockAndLateOutboxFailureLeaveNoPartialBatchOrReservation() throws Exception {
        String p=preview(a);sql.update("UPDATE inventory SET on_hand=0 WHERE sku_id=?",sku2);error(create(a,p,key()),409,"OUT_OF_STOCK");emptyTrade();
        sql.update("UPDATE inventory SET on_hand=100 WHERE sku_id=?",sku2);
        // 在第二个店铺的 Outbox 写入处注入真实 MySQL 故障，证明已经写过的第一店订单和预占也全部回滚。
        sql.update("CREATE TRIGGER test_fail_second_outbox BEFORE INSERT ON outbox_event FOR EACH ROW BEGIN IF JSON_UNQUOTE(JSON_EXTRACT(NEW.payload,'$.data.shopId'))='"+shop2+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test outbox failure'; END IF; END");
        try {error(create(a,p,key()),503,"DEPENDENCY_UNAVAILABLE");emptyTrade();assertThat(carts(a)).hasSize(2);assertThat(sql.count("SELECT COUNT(*) FROM checkout_preview WHERE used_at IS NOT NULL")).isZero();}
        finally {sql.update("DROP TRIGGER test_fail_second_outbox");}
        invariants("D-tc07-rollback.json");data(create(a,p,key()),201);
    }
    @Test void expiredPreviewAndLostResponseHaveRecoveryPaths() {
        String p=preview(a);sql.update("UPDATE checkout_preview SET expires_at=UTC_TIMESTAMP(3) WHERE id=?",p);error(create(a,p,key()),410,"PREVIEW_EXPIRED");emptyTrade();
        p=preview(a);String key=key();var result=data(create(a,p,key),201);
        sql.update("UPDATE checkout_preview SET expires_at=UTC_TIMESTAMP(3) WHERE id=?",p);
        assertThat(data(create(a,p,key),201)).isEqualTo(result);
        error(create(a,p,key()),409,"STATE_CONFLICT");
        assertThat(data(call("GET","/checkout/batches/"+result.path("batchId").asText(),null,a,null),200)).isEqualTo(result);
    }
    @Test void tc08TwentyReplaysConflictAndConcurrentSameKeyCreateOnlyOnce() throws Exception {
        String p=preview(a),key=key();var first=data(create(a,p,key),201);
        for(int i=0;i<20;i++) assertThat(data(create(a,p,key),201)).isEqualTo(first);
        error(create(a,"999999",key),409,"IDEMPOTENCY_CONFLICT");
        try(var pool=Executors.newFixedThreadPool(10)) {
            var jobs=new ArrayList<Future<JsonNode>>();for(int i=0;i<20;i++) jobs.add(pool.submit(()->data(create(a,p,key),201)));
            for(var job:jobs) assertThat(job.get(30,TimeUnit.SECONDS)).isEqualTo(first);
        }
        assertThat(sql.count("SELECT COUNT(*) FROM checkout_batch")).isEqualTo(1);invariants("D-tc08-replay.json");
        sql.update("UPDATE idempotency_record SET replay_until=UTC_TIMESTAMP(3) WHERE operation_id='API-27'");error(create(a,p,key),409,"IDEMPOTENCY_KEY_EXPIRED");
    }
    @Test void tc09OneHundredIndependentBuyersCompeteForLastTen() throws Exception {
        sql.update("UPDATE inventory SET on_hand=10 WHERE sku_id=?",sku1);
        var buyers=new ArrayList<Buyer>();var previews=new ArrayList<String>();
        for(int i=0;i<100;i++) {var buyer=buyer("concurrent_d_"+i,"MEMBER");cart(buyer,sku1,1);buyers.add(buyer);previews.add(preview(buyer));}
        var start=new CountDownLatch(1);var ready=new CountDownLatch(100);var responses=new ArrayList<ResponseEntity<JsonNode>>();
        try(var pool=Executors.newFixedThreadPool(100)) {
            var jobs=new ArrayList<Future<ResponseEntity<JsonNode>>>();
            for(int i=0;i<100;i++) {int index=i;jobs.add(pool.submit(()-> {ready.countDown();start.await();return create(buyers.get(index),previews.get(index),key());}));}
            assertThat(ready.await(20,TimeUnit.SECONDS)).isTrue();start.countDown();
            for(var job:jobs) responses.add(job.get(90,TimeUnit.SECONDS));
        }
        int success=0;for(var response:responses) {if(response.getStatusCode().value()==201) {data(response,201);success++;}else error(response,409,"OUT_OF_STOCK");}
        assertThat(success).isEqualTo(10);assertThat(sql.count("SELECT COUNT(*) FROM checkout_batch")).isEqualTo(10);
        assertThat(sql.count("SELECT reserved FROM inventory WHERE sku_id=?",sku1)).isEqualTo(10);assertThat(sql.count("SELECT on_hand FROM inventory WHERE sku_id=?",sku1)).isEqualTo(10);
        assertThat(sql.count("SELECT COUNT(*) FROM idempotency_record WHERE status='SUCCEEDED'")).isEqualTo(10);
        invariants("D-tc09-concurrency.json");
    }
    @Test void differentPreviewSameCartCannotDoublePurchaseAndOppositeOrderIsSafe() throws Exception {
        String p1=preview(a),p2=preview(a);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->create(a,p1,key()));var second=pool.submit(()->create(a,p2,key()));
            var codes=List.of(first.get().getStatusCode().value(),second.get().getStatusCode().value());assertThat(codes).containsExactlyInAnyOrder(201,409);
        }
        cart(b,sku2,1);cart(b,sku1,1);String p=preview(b);data(create(b,p,key()),201);invariants("D-ordering-invariants.json");
    }
    @Test void cancelAndTimeoutPersistClosingWithoutPretendingChannelSuccess() throws Exception {
        var batch=batch();String id=order(batch);String key=key();var input=Map.of("reason","用户取消");
        var close=data(call("POST","/orders/"+id+"/cancel",input,a,key),202);assertThat(close.path("status").asText()).isEqualTo("CLOSING");assertThat(close.path("closedAt").isNull()).isTrue();
        assertThat(data(call("POST","/orders/"+id+"/cancel",input,a,key),202)).isEqualTo(close);
        data(call("POST","/orders/"+id+"/cancel",input,a,key()),202);error(call("POST","/orders/"+id+"/cancel",Map.of("reason","异参"),a,key),409,"IDEMPOTENCY_CONFLICT");
        assertThat(sql.count("SELECT COUNT(*) FROM order_status_log WHERE action='CANCEL_REQUEST'")).isEqualTo(1);
        sql.update("UPDATE trade_order SET expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE status='PENDING_PAYMENT'");
        assertThat(expiry.scan("test-owner")).isEqualTo(1);assertThat(expiry.scan("test-owner")).isZero();assertThat(expiry.scan("other-owner")).isZero();
        assertThat(sql.count("SELECT COUNT(*) FROM trade_order WHERE status='CLOSING'")).isEqualTo(2);
        assertThat(sql.count("SELECT COUNT(*) FROM inventory_reservation WHERE status='RESERVED'")).isEqualTo(2);invariants("D-closing-invariants.json");
    }
    @Test void readsAreOwnedScopedFilteredAndStableAndFulfillmentRequiresMerchantScope() {
        var batch=batch();String id=order(batch),batchId=batch.path("batchId").asText();
        error(call("GET","/orders/"+id,null,b,null),404,"RESOURCE_NOT_FOUND");error(call("GET","/checkout/batches/"+batchId,null,b,null),404,"RESOURCE_NOT_FOUND");
        error(call("POST","/orders/"+id+"/cancel",Map.of("reason","越权"),b,key()),404,"RESOURCE_NOT_FOUND");
        var list=data(call("GET","/orders?status=PENDING_PAYMENT&pageSize=1",null,a,null),200);assertThat(list.path("total").asInt()).isEqualTo(2);assertThat(list.path("items").size()).isEqualTo(1);
        String no=batch.path("orders").get(0).path("orderNo").asText();assertThat(data(call("GET","/orders?orderNo="+no,null,a,null),200).path("total").asInt()).isEqualTo(1);
        assertThat(data(call("GET","/orders",null,b,null),200).path("total").asInt()).isZero();
        error(call("GET","/orders?status=UNKNOWN",null,a,null),400,"PARAM_INVALID");error(call("GET","/orders?sort=id",null,a,null),400,"PARAM_INVALID");error(call("GET","/orders?pageSize=101",null,a,null),400,"PARAM_INVALID");
        error(call("GET","/orders/"+id,null,null,null),401,"AUTH_REQUIRED");
        error(call("GET","/merchant/shops/"+shop1+"/orders",null,a,null),403,"ACCESS_DENIED");
        var operator=buyer("operator_d","OPERATOR");error(call("GET","/orders",null,operator,null),403,"ACCESS_DENIED");
    }
    @Test void previewValidationOwnershipAndIdempotencyInputRejectClientOverrides() {
        var ids=carts(a);
        error(call("POST","/checkout/previews",Map.of("addressId",a.address(),"cartItemIds",List.of(ids.getFirst(),ids.getFirst())),a,null),400,"PARAM_INVALID");
        error(call("POST","/checkout/previews",Map.of("addressId",a.address(),"cartItemIds",List.of()),a,null),400,"PARAM_INVALID");
        error(call("POST","/checkout/previews",Map.of("addressId",b.address(),"cartItemIds",ids),a,null),404,"RESOURCE_NOT_FOUND");
        error(call("POST","/checkout/previews",Map.of("addressId",b.address(),"cartItemIds",ids),b,null),404,"RESOURCE_NOT_FOUND");
        String p=preview(a);error(create(a,p,null),400,"PARAM_INVALID");error(create(a,p,"short"),400,"PARAM_INVALID");
        error(call("POST","/checkout/orders",Map.of("previewId",p,"price","0.01"),a,key()),400,"PARAM_INVALID");
        sql.update("UPDATE cart_item SET selected=0 WHERE user_id=?",a.id());error(call("POST","/checkout/previews",Map.of("addressId",a.address(),"cartItemIds",ids),a,null),409,"STATE_CONFLICT");emptyTrade();
    }
    @Test void sessionRevocationAndCancelledOrPaidStatesDoNotBypassTransitionChecks() {
        var batch=batch();String id=order(batch);
        sql.update("UPDATE trade_order SET status='PAID',financial_status='PAID' WHERE id=?",id);
        error(call("POST","/orders/"+id+"/cancel",Map.of("reason","已付拒绝"),a,key()),409,"STATE_CONFLICT");
        sql.update("UPDATE trade_order SET status='CANCELLED',financial_status='UNPAID' WHERE id=?",id);
        data(call("POST","/orders/"+id+"/cancel",Map.of("reason","已取消"),a,key()),200);
        sql.update("UPDATE refresh_session SET revoked_at=UTC_TIMESTAMP(3) WHERE user_id=?",a.id());error(call("GET","/orders/"+id,null,a,null),401,"AUTH_REQUIRED");
    }
    @Test void databaseAppendOnlyLogsAndReservationConstraintsAreEnforced() {
        batch();assertThatThrownBy(()->sql.update("UPDATE order_status_log SET reason='篡改'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->sql.update("DELETE FROM order_status_log")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->sql.update("UPDATE inventory_reservation SET status='UNKNOWN'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->sql.update("UPDATE inventory_reservation SET quantity=0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->sql.update("UPDATE inventory SET on_hand=0")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void realDatabaseDeadlockRetriesInFreshTransactionAndCommitsOnlyOnce() throws Exception {
        var rendezvous=new CyclicBarrier(2);var attempts=new java.util.concurrent.atomic.AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var jobs=new ArrayList<Future<Integer>>();
            for(int i=0;i<2;i++) {String first=i==0?sku1:sku2,second=i==0?sku2:sku1;var ownAttempts=new java.util.concurrent.atomic.AtomicInteger();
                jobs.add(pool.submit(()->transactions.run(()-> {
                    attempts.incrementAndGet();int attempt=ownAttempts.incrementAndGet();
                    sql.required("SELECT * FROM inventory WHERE sku_id=? FOR UPDATE",first);
                    if(attempt==1) {try {rendezvous.await(10,TimeUnit.SECONDS);}catch(Exception e) {throw new IllegalStateException(e);}}
                    sql.required("SELECT * FROM inventory WHERE sku_id=? FOR UPDATE",second);
                    sql.update("UPDATE inventory SET on_hand=on_hand+1 WHERE sku_id IN (?,?)",first,second);return 1;
                })));
            }
            for(var job:jobs) assertThat(job.get(30,TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(attempts.get()).isEqualTo(3);
        assertThat(sql.count("SELECT COUNT(*) FROM inventory WHERE on_hand=102")).isEqualTo(2);
    }
    @Test void lockRetryBudgetStopsAfterTwoRetriesAndEveryAttemptRollsBack() {
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(()->transactions.run(()-> {
            attempts.incrementAndGet();sql.update("UPDATE inventory SET on_hand=on_hand+1 WHERE sku_id=?",sku1);
            throw new org.springframework.dao.CannotAcquireLockException("test retry exhaustion");
        })).isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
        assertThat(attempts.get()).isEqualTo(3);assertThat(sql.count("SELECT on_hand FROM inventory WHERE sku_id=?",sku1)).isEqualTo(100);
    }
    @Test void amountAddressAndCartVersionLimitsFailBeforeAnyOrderIsWritten() {
        String p=preview(a);sql.update("UPDATE cart_item SET updated_at=DATE_ADD(updated_at,INTERVAL 1 SECOND) WHERE user_id=?",a.id());
        error(create(a,p,key()),409,"STATE_CONFLICT");emptyTrade();
        sql.update("UPDATE sku SET sale_price=9999999999.99 WHERE id=?",sku1);
        error(call("POST","/checkout/previews",Map.of("addressId",a.address(),"cartItemIds",carts(a)),a,null),400,"PARAM_INVALID");
        sql.update("UPDATE sku SET sale_price=19.90 WHERE id=?",sku1);
        sql.update("UPDATE user_address SET province=?,city=?,district=?,detail=? WHERE id=?","省".repeat(40),"市".repeat(40),"区".repeat(40),"址".repeat(200),a.address());
        error(call("POST","/checkout/previews",Map.of("addressId",a.address(),"cartItemIds",carts(a)),a,null),400,"PARAM_INVALID");emptyTrade();
    }
}
