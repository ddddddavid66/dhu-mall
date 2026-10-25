package com.yueji.mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.identity.service.Crypto;
import java.util.*;
import java.util.concurrent.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test") @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class StageCHttpTests extends MySqlTestSupport {
    private static final String PASSWORD=Crypto.randomToken();
    private static final String HASH=new BCryptPasswordEncoder(12).encode(PASSWORD);
    @Autowired TestRestTemplate client;@Autowired Flyway migrations;@Autowired SqlStore sql;
    private String ops,merchant,otherMerchant,reader,otherReader,support,warehouse,otherWarehouse,shop,otherShop,category,book,product,sku;
    @BeforeEach void fixtures() {
        Flyway.configure().dataSource(migrations.getConfiguration().getDataSource()).locations("classpath:db/migration").cleanDisabled(false).load().clean();migrations.migrate();
        user("ops_c","OPERATOR");String owner=user("merchant_c","MERCHANT"),other=user("merchant_other_c","MERCHANT");
        user("reader_c","MEMBER");user("reader_other_c","MEMBER");user("support_c","SUPPORT");
        String warehouseId=user("warehouse_c","WAREHOUSE"),otherWarehouseId=user("warehouse_other_c","WAREHOUSE");
        shop=sql.insert("INSERT INTO shop(name,contact_name,contact_phone) VALUES('虚构 A','虚构','13800000001')");
        otherShop=sql.insert("INSERT INTO shop(name,contact_name,contact_phone) VALUES('虚构 B','虚构','13800000002')");
        sql.insert("INSERT INTO shop_member(shop_id,user_id,duty) VALUES(?,?,'MERCHANT')",shop,owner);
        sql.insert("INSERT INTO shop_member(shop_id,user_id,duty) VALUES(?,?,'MERCHANT')",otherShop,other);
        sql.insert("INSERT INTO shop_member(shop_id,user_id,duty) VALUES(?,?,'WAREHOUSE')",shop,warehouseId);
        sql.insert("INSERT INTO shop_member(shop_id,user_id,duty) VALUES(?,?,'WAREHOUSE')",otherShop,otherWarehouseId);
        ops=login("ops_c");merchant=login("merchant_c");otherMerchant=login("merchant_other_c");reader=login("reader_c");otherReader=login("reader_other_c");
        support=login("support_c");warehouse=login("warehouse_c");otherWarehouse=login("warehouse_other_c");
        category=data(call(HttpMethod.POST,"/ops/categories",Map.of("name","文学","sortOrder",1,"status","ACTIVE"),ops),201).path("id").asText();
        book=data(call(HttpMethod.POST,"/ops/books",bookInput("9780306406157",category,"ACTIVE"),ops),201).path("id").asText();
        var created=data(call(HttpMethod.POST,"/merchant/shops/"+shop+"/products",productInput(book,"TEST-C-1","19.90"),merchant),201);
        product=created.path("id").asText();sku=created.path("skuId").asText();
        data(adjust(100,1,UUID.randomUUID().toString()),201);
        data(status(product,"ON_SALE",1),200);
    }
    private String user(String name,String role) {
        String id=sql.insert("INSERT INTO iam_user(username,password_hash,nickname) VALUES(?,?,?)",name,HASH,"虚构测试人");
        sql.update("INSERT INTO iam_user_role(user_id,role_id) SELECT ?,id FROM iam_role WHERE code=?",id,role);return id;
    }
    private String userId(String name) {return sql.required("SELECT id FROM iam_user WHERE username=?",name).get("id").toString();}
    private String login(String user) {return data(call(HttpMethod.POST,"/auth/login",Map.of("username",user,"password",PASSWORD),null),200).path("accessToken").asText();}
    private ResponseEntity<JsonNode> call(HttpMethod method,String path,Object body,String token) {return call(method,path,body,token,null);}
    private ResponseEntity<JsonNode> call(HttpMethod method,String path,Object body,String token,String key) {
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.set("X-Request-Id","stage-c-integration-123");
        if(token!=null) headers.setBearerAuth(token);if(key!=null) headers.set("Idempotency-Key",key);
        return client.exchange("/api/v1"+path,method,new HttpEntity<>(body,headers),JsonNode.class);
    }
    private JsonNode data(ResponseEntity<JsonNode> response,int expected) {
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(expected);
        assertThat(response.getBody().path("code").asText()).isEqualTo("OK");return response.getBody().path("data");
    }
    private void error(ResponseEntity<JsonNode> response,int expected,String code) {
        assertThat(response.getStatusCode().value()).as(String.valueOf(response.getBody())).isEqualTo(expected);assertThat(response.getBody().path("code").asText()).isEqualTo(code);
        assertThat(response.getBody().path("data").isNull()).isTrue();
    }
    private Map<String,Object> bookInput(String isbn,String cat,String status) {return Map.of("isbn",isbn,"title","虚构算法图书","author","虚构作者","publisher","虚构出版社","categoryId",cat,"description","仅测试纯文本","status",status);}
    private Map<String,Object> productInput(String book,String code,String price) {return Map.of("bookId",book,"description","实体新书测试","skuCode",code,"salePrice",price);}
    private ResponseEntity<JsonNode> status(String id,String value,long version) {return call(HttpMethod.PUT,"/merchant/shops/"+shop+"/products/"+id+"/status",Map.of("status",value,"version",version),merchant);}
    private ResponseEntity<JsonNode> adjust(int delta,long version,String key) {return call(HttpMethod.POST,"/merchant/shops/"+shop+"/inventory/"+sku+"/adjustments",Map.of("delta",delta,"version",version,"reason","测试盘点"),merchant,key);}
    private byte[] image(String format) throws Exception {var image=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);var output=new ByteArrayOutputStream();assertThat(ImageIO.write(image,format,output)).isTrue();return output.toByteArray();}
    private ResponseEntity<JsonNode> upload(byte[] bytes,String filename,String mime,String purpose,String token) {
        var partHeaders=new HttpHeaders();partHeaders.setContentType(MediaType.parseMediaType(mime));
        var body=new LinkedMultiValueMap<String,Object>();
        if(bytes!=null) {var resource=new ByteArrayResource(bytes) {public String getFilename() {return filename;}};body.add("file",new HttpEntity<>(resource,partHeaders));}
        if(purpose!=null) body.add("purpose",purpose);
        var headers=new HttpHeaders();headers.setContentType(MediaType.MULTIPART_FORM_DATA);if(token!=null) headers.setBearerAuth(token);
        return client.exchange("/api/v1/files",HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);
    }
    private ResponseEntity<byte[]> content(String id,String token) {var headers=new HttpHeaders();if(token!=null) headers.setBearerAuth(token);return client.exchange("/api/v1/files/"+id+"/content",HttpMethod.GET,new HttpEntity<>(headers),byte[].class);}

    @Test void publicSearchFiltersIsbnSortingAndSchemasAreReal() {
        var list=data(call(HttpMethod.GET,"/products?keyword=978-0-306-40615-7&categoryId="+category+"&shopId="+shop+"&sort=PRICE_ASC",null,null),200);
        assertThat(list.path("total").asInt()).isEqualTo(1);var view=list.path("items").get(0);
        assertThat(view.path("id").isTextual()).isTrue();assertThat(view.path("salePrice").asText()).isEqualTo("19.90");assertThat(view.path("availableQuantity").asInt()).isEqualTo(100);
        data(call(HttpMethod.GET,"/products?keyword=虚构&sort=PRICE_DESC",null,null),200);
        data(call(HttpMethod.GET,"/products?sort=NEWEST&featured=false",null,null),200);
        assertThat(data(call(HttpMethod.GET,"/products?keyword=%27%20OR%201%3D1",null,null),200).path("total").asInt()).isZero();
        error(call(HttpMethod.GET,"/products?sort=price%20desc;drop",null,null),400,"PARAM_INVALID");
        error(call(HttpMethod.GET,"/products?keyword="+"x".repeat(101),null,null),400,"PARAM_INVALID");
        error(call(HttpMethod.GET,"/products?pageSize=101",null,null),400,"PARAM_INVALID");
        assertThat(data(call(HttpMethod.GET,"/categories",null,null),200).size()).isEqualTo(1);
    }
    @Test void publicReadsExcludeDraftOffShelfDisabledBooksCategoriesAndSuspendedShops() {
        data(status(product,"OFF_SHELF",2),200);error(call(HttpMethod.GET,"/products/"+product,null,null),404,"RESOURCE_NOT_FOUND");
        assertThat(data(call(HttpMethod.GET,"/products",null,null),200).path("total").asInt()).isZero();
        data(status(product,"ON_SALE",3),200);
        data(call(HttpMethod.PUT,"/ops/books/"+book,bookInput("9780306406157",category,"DISABLED"),ops),200);
        error(call(HttpMethod.GET,"/products/"+product,null,null),404,"RESOURCE_NOT_FOUND");
        assertThat(data(call(HttpMethod.GET,"/ops/books",null,merchant),200).path("total").asInt()).isZero();
        assertThat(data(call(HttpMethod.GET,"/ops/books",null,ops),200).path("total").asInt()).isEqualTo(1);
        data(call(HttpMethod.PUT,"/ops/books/"+book,bookInput("9780306406157",category,"ACTIVE"),ops),200);
        sql.update("UPDATE shop SET status='SUSPENDED' WHERE id=?",shop);
        error(call(HttpMethod.GET,"/products/"+product,null,null),404,"RESOURCE_NOT_FOUND");
        error(status(product,"ON_SALE",4),409,"PRODUCT_UNAVAILABLE");
        sql.update("UPDATE shop SET status='ACTIVE' WHERE id=?",shop);sql.update("UPDATE category SET status='DISABLED' WHERE id=?",category);
        error(call(HttpMethod.GET,"/products/"+product,null,null),404,"RESOURCE_NOT_FOUND");
    }
    @Test void oneProductOneSkuAndVersionUpdatesCannotBeBypassed() {
        error(call(HttpMethod.POST,"/merchant/shops/"+shop+"/products",productInput(book,"duplicate","19.90"),merchant),409,"STATE_CONFLICT");
        assertThat(sql.count("SELECT COUNT(*) FROM sku")).isEqualTo(1);
        var changed=data(call(HttpMethod.PUT,"/merchant/shops/"+shop+"/products/"+product,Map.of("description","新介绍","salePrice","21.20","version",2),merchant),200);
        assertThat(changed.path("version").asInt()).isEqualTo(3);assertThat(changed.path("skuVersion").asInt()).isEqualTo(2);
        error(call(HttpMethod.PUT,"/merchant/shops/"+shop+"/products/"+product,Map.of("description","过期","salePrice","22.20","version",2),merchant),409,"VERSION_CONFLICT");
        var again=data(call(HttpMethod.PUT,"/merchant/shops/"+shop+"/products/"+product,Map.of("description","仅介绍","salePrice","21.20","version",3),merchant),200);
        assertThat(again.path("version").asInt()).isEqualTo(4);assertThat(again.path("skuVersion").asInt()).isEqualTo(2);
        error(call(HttpMethod.PUT,"/merchant/shops/"+shop+"/products/"+product,Map.of("description","价格错误","salePrice","0.00","version",4),merchant),400,"PARAM_INVALID");
        error(status(product,"DRAFT",4),400,"PARAM_INVALID");
    }
    @Test void categoryDepthCycleDisableAndIsbnRulesAreEnforced() {
        String child=data(call(HttpMethod.POST,"/ops/categories",Map.of("name","子类","sortOrder",2,"parentId",category,"status","ACTIVE"),ops),201).path("id").asText();
        error(call(HttpMethod.POST,"/ops/categories",Map.of("name","第三级","sortOrder",3,"parentId",child,"status","ACTIVE"),ops),409,"STATE_CONFLICT");
        error(call(HttpMethod.PUT,"/ops/categories/"+category,Map.of("name","循环","sortOrder",1,"parentId",child,"status","ACTIVE"),ops),409,"STATE_CONFLICT");
        error(call(HttpMethod.PUT,"/ops/categories/"+category,Map.of("name","停用","sortOrder",1,"status","DISABLED"),ops),409,"STATE_CONFLICT");
        error(call(HttpMethod.POST,"/ops/books",bookInput("9780306406158",category,"ACTIVE"),ops),400,"PARAM_INVALID");
        error(call(HttpMethod.POST,"/ops/books",bookInput("9780306406157",category,"ACTIVE"),ops),409,"STATE_CONFLICT");
        error(call(HttpMethod.PUT,"/ops/books/"+book,bookInput("9780132350884",category,"ACTIVE"),ops),409,"STATE_CONFLICT");
        data(call(HttpMethod.PUT,"/ops/categories/"+child,Map.of("name","子类停用","sortOrder",2,"parentId",category,"status","DISABLED"),ops),200);
        assertThat(data(call(HttpMethod.GET,"/ops/categories?status=DISABLED",null,ops),200).get(0).path("children").size()).isEqualTo(1);
    }
    @Test void operatorModerationCannotForceMerchantOffShelfProductsOnSale() {
        data(status(product,"OFF_SHELF",2),200);
        var moderated=data(call(HttpMethod.PUT,"/ops/products/"+product+"/moderation",Map.of("offShelf",false,"featured",true,"reason","人工精选","version",3),ops),200);
        assertThat(moderated.path("status").asText()).isEqualTo("OFF_SHELF");
        error(call(HttpMethod.GET,"/products/"+product,null,null),404,"RESOURCE_NOT_FOUND");
        error(call(HttpMethod.PUT,"/ops/products/"+product+"/moderation",Map.of("offShelf",true,"featured",false,"reason","违规下架","version",3),ops),409,"VERSION_CONFLICT");
        data(status(product,"ON_SALE",4),200);
        data(call(HttpMethod.PUT,"/ops/products/"+product+"/moderation",Map.of("offShelf",true,"featured",false,"reason","违规下架","version",5),ops),200);
        assertThat(sql.count("SELECT COUNT(*) FROM audit_log WHERE action='product.moderate' AND JSON_UNQUOTE(JSON_EXTRACT(summary,'$.reason'))='违规下架'")).isEqualTo(1);
        assertThat(data(call(HttpMethod.GET,"/ops/products?status=OFF_SHELF&shopId="+shop,null,ops),200).path("total").asInt()).isEqualTo(1);
    }
    @Test void merchantAndMemberScopesUseServerRelationsAndActionPermissions() {
        error(call(HttpMethod.GET,"/merchant/shops/"+shop+"/products",null,otherMerchant),404,"RESOURCE_NOT_FOUND");
        error(call(HttpMethod.GET,"/merchant/shops/"+otherShop+"/inventory?skuId="+sku,null,otherMerchant),404,"RESOURCE_NOT_FOUND");
        error(call(HttpMethod.PUT,"/merchant/shops/"+otherShop+"/products/"+product,Map.of("description","越权","salePrice","1.00","version",2),otherMerchant),404,"RESOURCE_NOT_FOUND");
        error(call(HttpMethod.GET,"/ops/products",null,merchant),403,"ACCESS_DENIED");
        error(call(HttpMethod.GET,"/cart",null,merchant),403,"ACCESS_DENIED");
        error(call(HttpMethod.POST,"/ops/categories",Map.of("name","越权","sortOrder",1,"status","ACTIVE"),reader),403,"ACCESS_DENIED");
        assertThat(data(call(HttpMethod.GET,"/merchant/shops/"+shop+"/products?status=ON_SALE&keyword=虚构",null,merchant),200).path("total").asInt()).isEqualTo(1);
        error(call(HttpMethod.GET,"/merchant/shops/"+shop+"/inventory",null,warehouse),403,"ACCESS_DENIED");
    }
    @Test void inventoryReplayReturnsOriginalSnapshotAndNeverWritesTwice() {
        assertThat(data(call(HttpMethod.GET,"/merchant/shops/"+shop+"/inventory?skuId="+sku,null,merchant),200).path("items").size()).isEqualTo(1);
        assertThat(data(call(HttpMethod.GET,"/merchant/shops/"+shop+"/inventory",null,merchant),200).path("total").asInt()).isEqualTo(1);
        error(call(HttpMethod.GET,"/merchant/shops/"+shop+"/inventory?sort=id",null,merchant),400,"PARAM_INVALID");
        String key=UUID.randomUUID().toString();var first=data(adjust(5,2,key),201);
        data(adjust(2,3,UUID.randomUUID().toString()),201);
        assertThat(data(adjust(5,2,key),201)).isEqualTo(first);
        assertThat(sql.count("SELECT COUNT(*) FROM inventory_movement")).isEqualTo(3);
        error(adjust(6,2,key),409,"IDEMPOTENCY_CONFLICT");
        sql.update("UPDATE idempotency_record SET status='PROCESSING' WHERE idempotency_key=?",key);
        error(adjust(5,2,key),409,"REQUEST_IN_PROGRESS");
        sql.update("UPDATE idempotency_record SET status='SUCCEEDED' WHERE idempotency_key=?",key);
        sql.update("UPDATE idempotency_record SET replay_until=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE idempotency_key=?",key);
        error(adjust(5,2,key),409,"IDEMPOTENCY_KEY_EXPIRED");
        error(adjust(1,4,null),400,"PARAM_INVALID");error(adjust(0,4,UUID.randomUUID().toString()),400,"PARAM_INVALID");
    }
    @Test void inventoryCannotOverwriteReservationsAndFailedAdjustmentRollsBackAllRows() {
        sql.update("UPDATE inventory SET reserved=80 WHERE sku_id=?",sku);
        long records=sql.count("SELECT COUNT(*) FROM idempotency_record");
        error(adjust(-21,2,UUID.randomUUID().toString()),409,"STATE_CONFLICT");
        assertThat(sql.count("SELECT COUNT(*) FROM idempotency_record")).isEqualTo(records);
        var result=data(adjust(-20,2,UUID.randomUUID().toString()),201);
        assertThat(result.path("reserved").asInt()).isEqualTo(80);assertThat(result.path("available").asInt()).isZero();
        error(adjust(1,2,UUID.randomUUID().toString()),409,"VERSION_CONFLICT");
        assertThatThrownBy(()->sql.update("UPDATE inventory SET on_hand=79 WHERE sku_id=?",sku)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->sql.update("UPDATE inventory_movement SET reason='tamper'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->sql.update("DELETE FROM inventory_movement")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        sql.update("UPDATE inventory SET on_hand=4294967295 WHERE sku_id=?",sku);
        error(adjust(1,3,UUID.randomUUID().toString()),409,"STATE_CONFLICT");
    }
    @Test void concurrentSameKeyInventoryRequestsCommitExactlyOneMovement() throws Exception {
        String key=UUID.randomUUID().toString();var gate=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->{gate.await();return adjust(1,2,key);});var b=executor.submit(()->{gate.await();return adjust(1,2,key);});gate.countDown();
            assertThat(data(a.get(20,TimeUnit.SECONDS),201)).isEqualTo(data(b.get(20,TimeUnit.SECONDS),201));
        }
        assertThat(sql.count("SELECT COUNT(*) FROM inventory_movement")).isEqualTo(2);
        assertThat(sql.required("SELECT on_hand FROM inventory WHERE sku_id=?",sku).get("on_hand").toString()).isEqualTo("101");
    }
    @Test void cartSetsAbsoluteQuantityGroupsByShopAndHasNoCrossUserRows() {
        for(int i=0;i<3;i++) data(call(HttpMethod.PUT,"/cart/items/"+sku,Map.of("quantity",3,"selected",true),reader),200);
        assertThat(sql.count("SELECT COUNT(*) FROM cart_item")).isEqualTo(1);
        assertThat(data(call(HttpMethod.GET,"/cart",null,otherReader),200).path("groups").size()).isZero();
        data(call(HttpMethod.PUT,"/cart/items/"+sku,Map.of("quantity",2,"selected",false),reader),200);
        var item=data(call(HttpMethod.GET,"/cart",null,reader),200).path("groups").get(0).path("items").get(0);
        assertThat(item.path("quantity").asInt()).isEqualTo(2);assertThat(item.path("selected").asBoolean()).isFalse();
        error(call(HttpMethod.PUT,"/cart/items/"+sku,Map.of("quantity",0,"selected",true),reader),400,"PARAM_INVALID");
        error(call(HttpMethod.PUT,"/cart/items/"+sku,Map.of("quantity",100,"selected",true),reader),400,"PARAM_INVALID");
        error(call(HttpMethod.PUT,"/cart/items/"+sku,Map.of("quantity",1.5,"selected",true),reader),400,"PARAM_INVALID");
        error(call(HttpMethod.PUT,"/cart/items/"+sku,Map.of("quantity","2","selected",true),reader),400,"PARAM_INVALID");
        error(call(HttpMethod.PUT,"/cart/items/"+sku,Map.of("quantity",1,"selected",true,"userId",userId("reader_other_c")),reader),400,"PARAM_INVALID");
        data(call(HttpMethod.DELETE,"/cart/items/"+sku,null,otherReader),200);assertThat(sql.count("SELECT COUNT(*) FROM cart_item")).isEqualTo(1);
        data(call(HttpMethod.DELETE,"/cart/items/"+sku,null,reader),200);data(call(HttpMethod.DELETE,"/cart/items/"+sku,null,reader),200);
    }
    @Test void cartReportsStaleItemsAndRejectsNewUnavailableSelections() {
        data(call(HttpMethod.PUT,"/cart/items/"+sku,Map.of("quantity",3,"selected",true),reader),200);
        sql.update("UPDATE inventory SET on_hand=1 WHERE sku_id=?",sku);
        var unavailable=data(call(HttpMethod.GET,"/cart",null,reader),200).path("groups").get(0).path("items").get(0);
        assertThat(unavailable.path("purchasable").asBoolean()).isFalse();assertThat(unavailable.path("unavailableReason").asText()).isEqualTo("OUT_OF_STOCK");
        data(call(HttpMethod.PUT,"/cart/items/"+sku,Map.of("quantity",3,"selected",false),reader),200);
        error(call(HttpMethod.PUT,"/cart/items/"+sku,Map.of("quantity",2,"selected",true),reader),409,"OUT_OF_STOCK");
        data(status(product,"OFF_SHELF",2),200);
        assertThat(data(call(HttpMethod.GET,"/cart",null,reader),200).path("groups").get(0).path("items").get(0).path("unavailableReason").asText()).isEqualTo("PRODUCT_UNAVAILABLE");
        error(call(HttpMethod.PUT,"/cart/items/"+sku,Map.of("quantity",1,"selected",true),reader),409,"PRODUCT_UNAVAILABLE");
        error(call(HttpMethod.PUT,"/cart/items/"+sku,Map.of("quantity",1,"selected",false),otherReader),409,"PRODUCT_UNAVAILABLE");
    }
    @Test void filesAreDecodedReencodedAndAttachedAsPublicCovers() throws Exception {
        byte[] jpeg=image("jpeg");var uploaded=data(upload(jpeg,"cover.jpg","image/jpeg","BOOK_COVER",ops),201);String id=uploaded.path("id").asText();
        assertThat(uploaded.path("mimeType").asText()).isEqualTo("image/png");
        var bytes=content(id,null);assertThat(bytes.getStatusCode().value()).isEqualTo(200);assertThat(bytes.getBody()).startsWith(new byte[]{(byte)137,80,78,71});assertThat(ImageIO.read(new java.io.ByteArrayInputStream(bytes.getBody()))).isNotNull();
        var input=new HashMap<>(bookInput("9780306406157",category,"ACTIVE"));input.put("coverFileId",id);data(call(HttpMethod.PUT,"/ops/books/"+book,input,ops),200);
        assertThat(data(call(HttpMethod.GET,"/products/"+product,null,null),200).path("book").path("coverUrl").asText()).endsWith("/files/"+id+"/content");
        error(upload(image("png"),"not-allowed.png","image/png","BOOK_COVER",merchant),403,"ACCESS_DENIED");
    }
    @Test void dangerousMultipartFilesAndMetadataAreRejected() throws Exception {
        error(upload(image("png"),"missing-purpose.png","image/png",null,ops),400,"PARAM_INVALID");
        error(upload(null,"missing-file.png","image/png","BOOK_COVER",ops),400,"PARAM_INVALID");
        error(upload("<script>alert(1)</script>".getBytes(),"fake.png","image/png","BOOK_COVER",ops),415,"FILE_TYPE_UNSUPPORTED");
        error(upload(image("png"),"../../cover.png","image/png","BOOK_COVER",ops),400,"PARAM_INVALID");
        error(upload(image("png"),"C:\\outside.png","image/png","BOOK_COVER",ops),400,"PARAM_INVALID");
        error(upload(image("png"),"fake.jpg","image/jpeg","BOOK_COVER",ops),415,"FILE_TYPE_UNSUPPORTED");
        error(upload(image("png"),"fake.png","application/octet-stream","BOOK_COVER",ops),415,"FILE_TYPE_UNSUPPORTED");
        error(upload(new byte[0],"empty.png","image/png","BOOK_COVER",ops),415,"FILE_TYPE_UNSUPPORTED");
        error(upload(new byte[5242881],"huge.png","image/png","BOOK_COVER",ops),413,"FILE_TOO_LARGE");
        assertThat(sql.count("SELECT COUNT(*) FROM file_object")).isZero();
    }
    @Test void privateEvidenceHasNoStaticUrlAndOnlyItsOwnerCanReadWithoutAssociation() throws Exception {
        var uploaded=data(upload(image("png"),"evidence.png","image/png","AFTER_SALE_EVIDENCE",reader),201);String id=uploaded.path("id").asText();
        assertThat(uploaded.path("url").isNull()).isTrue();assertThat(uploaded.toString()).doesNotContain("storageKey");
        assertThat(content(id,reader).getStatusCode().value()).isEqualTo(200);assertThat(content(id,reader).getHeaders().getCacheControl()).isEqualTo("private, no-store");
        assertThat(content(id,null).getStatusCode().value()).isEqualTo(401);assertThat(content(id,otherReader).getStatusCode().value()).isEqualTo(404);
        assertThat(content(id,support).getStatusCode().value()).isEqualTo(404);assertThat(content(id,warehouse).getStatusCode().value()).isEqualTo(404);
        String key=sql.required("SELECT storage_key FROM file_object WHERE id=?",id).get("storage_key").toString();
        assertThat(client.getForEntity("/"+key,byte[].class).getStatusCode().value()).isNotEqualTo(200);
        assertThat(client.getForEntity("/files/"+key,byte[].class).getStatusCode().value()).isNotEqualTo(200);
        var input=new HashMap<>(bookInput("9780306406157",category,"ACTIVE"));input.put("coverFileId",id);error(call(HttpMethod.PUT,"/ops/books/"+book,input,ops),400,"PARAM_INVALID");
    }
    @Test void evidenceAssociationAllowsOnlyRelatedSupportAndReturnWarehouse() throws Exception {
        String file=data(upload(image("png"),"evidence.png","image/png","AFTER_SALE_EVIDENCE",reader),201).path("id").asText();
        String owner=userId("reader_c");
        // 仅专用 test 库构造满足外键的关联事实；不生成开发订单、售后或评价联调样本。
        String address=sql.insert("INSERT INTO user_address(user_id,receiver_name,phone,province,city,district,detail) VALUES(?,'虚构','13800000001','测试','测试','测试','测试')",owner);
        String preview=sql.insert("INSERT INTO checkout_preview(user_id,address_id,items_snapshot,address_version_hash,amount,expires_at) VALUES(?,?,JSON_ARRAY(),'test',19.90,UTC_TIMESTAMP(3)+INTERVAL 5 MINUTE)",owner,address);
        String batch=sql.insert("INSERT INTO checkout_batch(batch_no,user_id,preview_id,total_amount) VALUES('TEST-C-BATCH',?,?,19.90)",owner,preview);
        String order=sql.insert("INSERT INTO trade_order(order_no,batch_id,user_id,shop_id,goods_amount,payable_amount,receiver_name,receiver_phone,receiver_address,expires_at) VALUES('TEST-C-ORDER',?,?,?,19.90,19.90,'虚构','13800000001','测试',UTC_TIMESTAMP(3)+INTERVAL 30 MINUTE)",batch,owner,shop);
        String item=sql.insert("INSERT INTO order_item(order_id,sku_id,book_id,product_id,book_title,isbn,sku_code,unit_price,quantity,line_amount) VALUES(?,?,?,?,'测试','9780306406157','TEST-C-1',19.90,1,19.90)",order,sku,book,product);
        String after=sql.insert("INSERT INTO after_sale(after_sale_no,order_id,order_item_id,user_id,type,quantity,requested_amount,reason) VALUES('TEST-C-AS',?,?,?,'RETURN_REFUND',1,19.90,'虚构测试')",order,item,owner);
        sql.insert("INSERT INTO after_sale_evidence(after_sale_id,file_id) VALUES(?,?)",after,file);
        assertThat(content(file,support).getStatusCode().value()).isEqualTo(200);assertThat(content(file,warehouse).getStatusCode().value()).isEqualTo(200);
        assertThat(content(file,otherWarehouse).getStatusCode().value()).isEqualTo(404);assertThat(content(file,otherReader).getStatusCode().value()).isEqualTo(404);
        sql.update("UPDATE after_sale SET type='REFUND_ONLY' WHERE id=?",after);assertThat(content(file,warehouse).getStatusCode().value()).isEqualTo(404);
    }
    @Test void reviewReadingUsesRealTableAndModerationRequiresAnExistingReview() {
        assertThat(data(call(HttpMethod.GET,"/products/"+product+"/reviews",null,null),200).path("total").asInt()).isZero();
        assertThat(sql.count("SELECT COUNT(*) FROM review")).isZero();
        error(call(HttpMethod.PUT,"/ops/reviews/1/visibility",Map.of("status","HIDDEN","reason","test"),ops),404,"RESOURCE_NOT_FOUND");
        assertThat(data(call(HttpMethod.GET,"/ops/reviews",null,ops),200).path("total").asInt()).isZero();
    }
}
