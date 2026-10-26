package com.yueji.mall;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** 仅显式 -Dtest=E2EFixturePreparation 执行；只重建 MySqlTestSupport 许可的隔离测试库。 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"yueji.mock-payment-enabled=true","yueji.payment.provider=LOCAL_MOCK"})
class E2EFixturePreparation extends StageFFixtures {
    @Autowired PasswordEncoder passwords;
    @Test void prepareIsolatedBrowserFixturesThroughRealDomainFlows() throws Exception {
        String completed=firstOrder(batch());pay(completed);ship(completed,shop1);receive(completed);
        // 独立验收夹具明确准备已过窗口订单；没有生产改时间接口。
        sql.update("UPDATE trade_order SET completed_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 10 DAY) WHERE id=?",completed);
        cart(a,sku1,1);cart(a,sku2,1);
        var admin=buyer("e2e_admin","ADMIN");
        var actors=Map.of("buyer",a,"otherBuyer",b,"merchant",merchant,"warehouse",warehouse,
                "support",support,"finance",finance,"approver",financeApprover,"operator",operator,"admin",admin);
        var output=new LinkedHashMap<String,Object>();
        for(var entry:actors.entrySet()) {
            String password=com.yueji.mall.identity.service.Crypto.randomToken();
            sql.update("UPDATE iam_user SET password_hash=? WHERE id=?",passwords.encode(password),entry.getValue().id());
            String username=sql.required("SELECT username FROM iam_user WHERE id=?",entry.getValue().id()).get("username").toString();
            output.put(entry.getKey(),Map.of("username",username,"password",password,"id",entry.getValue().id()));
        }
        String xss="<img src=x onerror=\"window.__yuejiXss=true\">实体新书长标题移动布局测试";
        sql.update("UPDATE book SET title=?,description=? WHERE id=?",xss,xss,book);
        var bytes=new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(32,48,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",bytes);
        var resource=new org.springframework.core.io.ByteArrayResource(bytes.toByteArray()) {@Override public String getFilename(){return "fixture-cover.png";}};
        var upload=new org.springframework.util.LinkedMultiValueMap<String,Object>();upload.add("purpose","BOOK_COVER");upload.add("file",resource);
        var headers=new org.springframework.http.HttpHeaders();headers.setBearerAuth(operator.token());headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA);
        var uploaded=data(client.postForEntity("/api/v1/files",new org.springframework.http.HttpEntity<>(upload,headers),com.fasterxml.jackson.databind.JsonNode.class),201);
        String coveredBook=sql.insert("INSERT INTO book(isbn,title,author,publisher,category_id,description,cover_file_id) VALUES('9780132350884','带封面的虚构新书','虚构作者','虚构出版社',?,'恢复文件引用验收',?)",category,uploaded.path("id").asText());
        String originalBook=book;book=coveredBook;product(shop1,"E2E-COVER","9.90",100);book=originalBook;
        var directory=Path.of("../.artifacts");Files.createDirectories(directory);
        Files.writeString(directory.resolve("R-e2e-accounts.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output));
        Files.writeString(directory.resolve("R-e2e-fixtures.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "scope","isolated yueji_mall_test only","shop1",shop1,"shop2",shop2,"sku1",sku1,"sku2",sku2,"completedOrder",completed,"book",book,"xssTitle",xss,
                "coverFileId",uploaded.path("id").asText(),"coverStorageKey",sql.required("SELECT storage_key FROM file_object WHERE id=?",uploaded.path("id").asText()).get("storage_key").toString())));
    }
}
