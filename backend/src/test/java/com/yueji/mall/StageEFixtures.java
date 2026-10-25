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

abstract class StageEFixtures extends MySqlTestSupport {
    @Autowired SqlStore sql;@Autowired Flyway migrations;@Autowired TestRestTemplate client;
    @Autowired AccessTokens tokens;@Autowired IdentityRepository identities;@Autowired ObjectMapper mapper;@Autowired OrderExpiryService expiry;
    @Autowired com.yueji.mall.common.service.DatabaseTransaction transactions;
    record Buyer(String id,String token,String address) {}
    Buyer a,b;String category,book,shop1,shop2,sku1,sku2;
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
    Buyer buyer(String name,String role) {
        String id=sql.insert("INSERT INTO iam_user(username,password_hash,nickname) VALUES(?,'fixture-not-a-login-password','虚构测试会员')",name);
        sql.update("INSERT INTO iam_user_role(user_id,role_id) SELECT ?,id FROM iam_role WHERE code=?",id,role);
        String family=Crypto.randomToken();
        sql.insert("INSERT INTO refresh_session(user_id,family_no,token_hash,expires_at,csrf_hash,auth_version) VALUES(?,?,?,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 7 DAY),?,1)",id,family,Crypto.hash(Crypto.randomToken()),Crypto.hash(Crypto.randomToken()));
        String address=sql.insert("INSERT INTO user_address(user_id,receiver_name,phone,province,city,district,detail) VALUES(?,'虚构收件人','13800000000','虚构省','虚构市','虚构区','虚构地址一号')",id);
        // 隔离库的会话夹具，签发真实 JWT，100 人库存测试不重复测试 BCrypt 登录性能。
        return new Buyer(id,tokens.issue(identities.user(id),family),address);
    }
    String product(String shop,String code,String price,int stock) {
        String product=sql.insert("INSERT INTO product(shop_id,book_id,description,status) VALUES(?,?,'测试','ON_SALE')",shop,book);
        String sku=sql.insert("INSERT INTO sku(product_id,sku_code,sale_price) VALUES(?,?,?)",product,code,new java.math.BigDecimal(price));
        sql.insert("INSERT INTO inventory(sku_id,on_hand) VALUES(?,?)",sku,stock);return sku;
    }
    void cart(Buyer buyer,String sku,int quantity) {sql.insert("INSERT INTO cart_item(user_id,sku_id,quantity) VALUES(?,?,?)",buyer.id(),sku,quantity);}
    String key() {return UUID.randomUUID().toString();}
    ResponseEntity<JsonNode> call(String method,String path,Object body,Buyer buyer,String key) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.set("X-Request-Id","stage-d-integration-123");
        if(buyer!=null) headers.setBearerAuth(buyer.token());if(key!=null) headers.set("Idempotency-Key",key);
        return client.exchange("/api/v1"+path,HttpMethod.valueOf(method),new HttpEntity<>(body,headers),JsonNode.class);
    }
    JsonNode data(ResponseEntity<JsonNode> response,int code) {
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(code);
        assertThat(response.getBody().path("code").asText()).isEqualTo("OK");return response.getBody().path("data");
    }
    void error(ResponseEntity<JsonNode> response,int status,String code) {
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);assertThat(response.getBody().path("data").isNull()).isTrue();
    }
    List<String> carts(Buyer buyer) {return sql.rows("SELECT id FROM cart_item WHERE user_id=? ORDER BY id",buyer.id()).stream().map(r->r.get("id").toString()).toList();}
    String preview(Buyer buyer) {return data(call("POST","/checkout/previews",Map.of("addressId",buyer.address(),"cartItemIds",carts(buyer)),buyer,null),201).path("previewId").asText();}
    ResponseEntity<JsonNode> create(Buyer buyer,String preview,String key) {return call("POST","/checkout/orders",Map.of("previewId",preview),buyer,key);}
    JsonNode batch() {return data(create(a,preview(a),key()),201);}
    String order(JsonNode batch) {return batch.path("orders").get(0).path("id").asText();}
}
