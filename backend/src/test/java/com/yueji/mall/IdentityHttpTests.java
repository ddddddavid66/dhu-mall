package com.yueji.mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.service.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityHttpTests extends MySqlTestSupport {
    record Login(String access,String csrf,String cookie,String user) {}
    private static final String PASSWORD=Crypto.randomToken();
    private static final String HASH=new BCryptPasswordEncoder(12).encode(PASSWORD);
    @Autowired TestRestTemplate client;
    @Autowired Flyway migrations;
    @Autowired SqlStore sql;
    @Autowired AccessTokens tokens;
    @Autowired IdentityPolicy policy;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;
    private String admin,otherAdmin,reader,otherReader,merchant,adminRole,memberRole,merchantRole;

    @BeforeEach
    void fixtures() {
        Flyway.configure().dataSource(migrations.getConfiguration().getDataSource()).locations("classpath:db/migration").cleanDisabled(false).load().clean();
        migrations.migrate();
        adminRole=role("ADMIN"); memberRole=role("MEMBER"); merchantRole=role("MERCHANT");
        admin=user("admin_test",adminRole); otherAdmin=user("admin_other",adminRole);
        reader=user("reader_test",memberRole); otherReader=user("reader_other",memberRole); merchant=user("merchant_test",merchantRole);
    }
    private String role(String code) { return sql.required("SELECT id FROM iam_role WHERE code=?",code).get("id").toString(); }
    private String user(String username,String role) {
        String id=sql.insert("INSERT INTO iam_user(username,password_hash,nickname) VALUES(?,?,?)",username,HASH,"虚构测试用户");
        sql.insert("INSERT INTO iam_user_role(user_id,role_id) VALUES(?,?)",id,role); return id;
    }
    private ResponseEntity<JsonNode> call(HttpMethod method,String path,Object body,String access) {
        var headers=new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Request-Id","stage-b-test-123");
        if (access!=null) headers.setBearerAuth(access);
        return client.exchange("/api/v1"+path,method,new HttpEntity<>(body,headers),JsonNode.class);
    }
    private Login login(String username) {
        var response=call(HttpMethod.POST,"/auth/login",Map.of("username",username,"password",PASSWORD),null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body=response.getBody().path("data");
        return new Login(body.path("accessToken").asText(),body.path("csrfToken").asText(),response.getHeaders().getFirst("Set-Cookie").split(";",2)[0],body.path("user").path("id").asText());
    }
    private ResponseEntity<JsonNode> session(String operation,Login login,String csrf,String origin) {
        var headers=new HttpHeaders(); headers.set("Cookie",login.cookie());
        if (csrf!=null) headers.set("X-CSRF-Token",csrf);
        if (origin!=null) headers.set("Origin",origin);
        return client.exchange("/api/v1/auth/"+operation,HttpMethod.POST,new HttpEntity<>(null,headers),JsonNode.class);
    }
    private JsonNode data(ResponseEntity<JsonNode> response,int status) {
        assertThat(response.getStatusCode().value()).as(response.getBody().path("code").asText()).isEqualTo(status);
        assertThat(response.getBody().path("code").asText()).isEqualTo("OK"); return response.getBody().path("data");
    }
    private void error(ResponseEntity<JsonNode> response,int status,String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().path("code").asText()).isEqualTo(code);
        assertThat(response.getBody().get("data").isNull()).isTrue();
    }
    private Map<String,Object> address(boolean isDefault) {
        return Map.of("receiverName","虚构收件人","phone","13800000001","province","测试省","city","测试市","district","测试区","detail","虚构测试地址","isDefault",isDefault);
    }

    @Test void forgedExpiredWrongAudienceAndExpiredRefreshSessionsAreRejected() {
        var original=login("reader_test");
        error(call(HttpMethod.GET,"/me",null,original.access()+"tampered"),401,"AUTH_REQUIRED");
        var now=java.time.Instant.now();
        for (boolean expired:List.of(true,false)) {
            var claims=org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("yueji-mall")
                    .audience(List.of(expired ? "yueji-web" : "attacker"))
                    .subject(reader).issuedAt(now.minusSeconds(1000)).expiresAt(expired ? now.minusSeconds(1) : now.plusSeconds(900))
                    .claim("token_use","access").claim("sid",Crypto.randomToken()).claim("av",1).build();
            var header=org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
            String invalid=jwtEncoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header,claims)).getTokenValue();
            error(call(HttpMethod.GET,"/me",null,invalid),401,"AUTH_REQUIRED");
        }
        sql.update("UPDATE refresh_session SET expires_at=UTC_TIMESTAMP(3)-INTERVAL 1 SECOND WHERE user_id=?",reader);
        error(session("refresh",original,original.csrf(),"http://localhost:5173"),401,"AUTH_REQUIRED");
        assertThat(sql.count("SELECT COUNT(*) FROM refresh_session WHERE user_id=? AND revoked_at IS NULL",reader)).isZero();
    }

    @Test void registrationHasOnlyMemberRoleAndStrictFieldsAndStoresOnlyHash() {
        String newPassword=Crypto.randomToken();
        var created=data(call(HttpMethod.POST,"/auth/register",Map.of("username","new_reader","password",newPassword,"nickname","测试读者"),null),201);
        assertThat(created.path("roles").toString()).isEqualTo("[\"MEMBER\"]");
        assertThat(created.has("passwordHash")).isFalse();
        String stored=sql.required("SELECT password_hash FROM iam_user WHERE username='new_reader'").get("password_hash").toString();
        assertThat(new BCryptPasswordEncoder().matches(newPassword,stored)).isTrue();
        error(call(HttpMethod.POST,"/auth/register",Map.of("username","evil_reader","password",PASSWORD,"nickname","test","roleIds",List.of(adminRole)),null),400,"PARAM_INVALID");
        error(call(HttpMethod.POST,"/auth/register",Map.of("username","new_reader","password",PASSWORD,"nickname","test"),null),409,"STATE_CONFLICT");
        error(call(HttpMethod.POST,"/auth/register",Map.of("username","utf8_reader","password","中".repeat(25),"nickname","test"),null),400,"PARAM_INVALID");
    }
    @Test void missingSessionsDisabledAccountsAndOversizedUtf8LoginFailClosed() {
        error(call(HttpMethod.POST,"/auth/login",Map.of("username","unknown_account","password",PASSWORD),null),401,"AUTH_REQUIRED");
        error(call(HttpMethod.POST,"/auth/login",Map.of("username","reader_test","password","中".repeat(25)),null),401,"AUTH_REQUIRED");
        sql.update("UPDATE iam_user SET status='DISABLED' WHERE id=?",reader);
        error(call(HttpMethod.POST,"/auth/login",Map.of("username","reader_test","password",PASSWORD),null),403,"ACCOUNT_DISABLED");
        var absent=new Login("",Crypto.randomToken(),"refreshToken="+Crypto.randomToken(),reader);
        error(session("refresh",absent,absent.csrf(),"http://localhost:5173"),401,"AUTH_REQUIRED");
        data(session("logout",absent,absent.csrf(),"http://localhost:5173"),200);
        var missing=new Login("",Crypto.randomToken(),"",reader);
        data(session("logout",missing,missing.csrf(),"http://localhost:5173"),200);
        error(session("refresh",missing,missing.csrf(),"http://localhost:5173"),401,"AUTH_REQUIRED");
        sql.update("UPDATE iam_role SET status='DISABLED' WHERE id=?",memberRole);
        error(call(HttpMethod.POST,"/auth/register",Map.of("username","closed_signup","password",PASSWORD,"nickname","注册关闭测试"),null),503,"DEPENDENCY_UNAVAILABLE");
    }
    @Test void addressCountAndNonDefaultUpdatesRespectTheServerBoundary() {
        var owner=login("reader_test");
        String first=data(call(HttpMethod.POST,"/addresses",address(false),owner.access()),201).path("id").asText();
        data(call(HttpMethod.PUT,"/addresses/"+first,address(false),owner.access()),200);
        for(int i=1;i<20;i++) data(call(HttpMethod.POST,"/addresses",address(false),owner.access()),201);
        error(call(HttpMethod.POST,"/addresses",address(true),owner.access()),409,"STATE_CONFLICT");
        assertThat(data(call(HttpMethod.GET,"/addresses",null,owner.access()),200).size()).isEqualTo(20);
        assertThat(sql.count("SELECT COUNT(*) FROM user_address WHERE user_id=? AND is_default=1",reader)).isZero();
    }
    @Test void auditQueryAndAdministrativeSortHaveStrictBounds() {
        var root=login("admin_test");
        error(call(HttpMethod.GET,"/admin/users?sort=id",null,root.access()),400,"PARAM_INVALID");
        error(call(HttpMethod.GET,"/admin/audit-logs?action="+"a".repeat(81),null,root.access()),400,"PARAM_INVALID");
        error(call(HttpMethod.GET,"/admin/audit-logs?traceId="+"a".repeat(65),null,root.access()),400,"PARAM_INVALID");
        error(call(HttpMethod.GET,"/admin/audit-logs?sort=id",null,root.access()),400,"PARAM_INVALID");
        error(call(HttpMethod.GET,"/admin/audit-logs?from=2025-01-01T00:00:00Z&to=2026-01-01T00:00:00Z",null,root.access()),400,"PARAM_INVALID");
        data(call(HttpMethod.GET,"/admin/audit-logs?actorId="+admin,null,root.access()),200);
    }
    @Test void accessExpiryCookieAndRefreshRotationAreReal() {
        Login original=login("reader_test");
        var jwt=new String(Base64.getUrlDecoder().decode(original.access().split("\\.")[1]),java.nio.charset.StandardCharsets.UTF_8);
        try {
            var claims=new com.fasterxml.jackson.databind.ObjectMapper().readTree(jwt);
            assertThat(claims.path("exp").asLong()-claims.path("iat").asLong()).isEqualTo(900);
        } catch(Exception e) { throw new AssertionError(e); }
        var changed=session("refresh",original,original.csrf(),"http://localhost:5173");
        var body=data(changed,200);
        assertThat(changed.getHeaders().getFirst("Set-Cookie")).contains("HttpOnly","SameSite=Lax","Path=/api/v1/auth","Secure");
        assertThat(body.has("refreshToken")).isFalse();
        assertThat(body.path("expiresIn").asInt()).isEqualTo(900);
        assertThat(sql.count("SELECT COUNT(*) FROM refresh_session WHERE replaced_by_id IS NOT NULL")).isEqualTo(1);
        data(call(HttpMethod.GET,"/me",null,body.path("accessToken").asText()),200);
        String raw=original.cookie().substring("refreshToken=".length());
        assertThat(sql.count("SELECT COUNT(*) FROM refresh_session WHERE token_hash=?",Crypto.hash(raw))).isEqualTo(1);
        assertThat(sql.count("SELECT COUNT(*) FROM refresh_session WHERE token_hash=?",raw)).isZero();
    }
    @Test void refreshReplayRevokesTheEntireFamilyAndCommitsDespite401() {
        var old=login("reader_test");
        var replacement=session("refresh",old,old.csrf(),"http://localhost:5173");
        var newData=data(replacement,200);
        error(session("refresh",old,old.csrf(),"http://localhost:5173"),401,"AUTH_REQUIRED");
        error(call(HttpMethod.GET,"/me",null,newData.path("accessToken").asText()),401,"AUTH_REQUIRED");
        assertThat(sql.count("SELECT COUNT(*) FROM refresh_session WHERE revoked_at IS NULL")).isZero();
        assertThat(sql.count("SELECT COUNT(*) FROM audit_log WHERE action='auth.refresh_rejected'")).isEqualTo(1);
    }
    @Test void csrfOriginAndLogoutCannotBeBypassedWithCookieAlone() {
        var login=login("reader_test");
        Actor previouslyAuthenticated=tokens.authenticate(login.access());
        error(session("refresh",login,null,"http://localhost:5173"),403,"CSRF_INVALID");
        error(session("logout",login,Crypto.randomToken(),"http://localhost:5173"),403,"CSRF_INVALID");
        error(session("refresh",login,login.csrf(),null),403,"CSRF_INVALID");
        assertThat(session("refresh",login,login.csrf(),"https://untrusted.invalid").getStatusCode().value()).isEqualTo(403);
        data(call(HttpMethod.GET,"/me",null,login.access()),200);
        data(session("logout",login,login.csrf(),"http://localhost:5173"),200);
        data(session("logout",login,login.csrf(),"http://localhost:5173"),200);
        error(call(HttpMethod.GET,"/me",null,login.access()),401,"AUTH_REQUIRED");
        assertThatThrownBy(()->policy.current(previouslyAuthenticated)).isInstanceOf(com.yueji.mall.common.api.BusinessException.class);
    }
    @Test void failedLoginCountersAndAccountLockPersistAndResetAfterExpiry() {
        for(int i=0;i<5;i++) error(call(HttpMethod.POST,"/auth/login",Map.of("username","reader_test","password","not-the-password"),null),401,"AUTH_REQUIRED");
        assertThat(sql.count("SELECT COUNT(*) FROM iam_user WHERE id=? AND failed_login_count=5 AND locked_until>UTC_TIMESTAMP(3)",reader)).isEqualTo(1);
        error(call(HttpMethod.POST,"/auth/login",Map.of("username","reader_test","password",PASSWORD),null),401,"AUTH_REQUIRED");
        sql.update("UPDATE iam_user SET locked_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",reader);
        login("reader_test");
        assertThat(sql.count("SELECT COUNT(*) FROM iam_user WHERE id=? AND failed_login_count=0 AND locked_until IS NULL",reader)).isEqualTo(1);
    }
    @Test void passwordChangeRevokesAllSessionsAndAcceptsOnlyTheOldPassword() {
        var one=login("reader_test"); var two=login("reader_test");
        error(call(HttpMethod.PUT,"/me/password",Map.of("oldPassword","wrong","newPassword",Crypto.randomToken()),one.access()),401,"AUTH_REQUIRED");
        data(call(HttpMethod.PUT,"/me/password",Map.of("oldPassword",PASSWORD,"newPassword",Crypto.randomToken()),one.access()),200);
        error(call(HttpMethod.GET,"/me",null,two.access()),401,"AUTH_REQUIRED");
        error(session("refresh",one,one.csrf(),"http://localhost:5173"),401,"AUTH_REQUIRED");
    }
    @Test void addressesAreOwnedHaveOneDefaultAndDeleteIdempotently() {
        var a=login("reader_test"); var b=login("reader_other");
        String first=data(call(HttpMethod.POST,"/addresses",address(true),a.access()),201).path("id").asText();
        String second=data(call(HttpMethod.POST,"/addresses",address(true),a.access()),201).path("id").asText();
        assertThat(sql.count("SELECT COUNT(*) FROM user_address WHERE user_id=? AND is_default=1 AND status='ACTIVE'",reader)).isEqualTo(1);
        assertThat(data(call(HttpMethod.GET,"/addresses",null,b.access()),200).size()).isZero();
        error(call(HttpMethod.PUT,"/addresses/"+first,address(true),b.access()),404,"RESOURCE_NOT_FOUND");
        error(call(HttpMethod.DELETE,"/addresses/"+first,null,b.access()),404,"RESOURCE_NOT_FOUND");
        data(call(HttpMethod.PUT,"/addresses/"+first,address(true),a.access()),200);
        data(call(HttpMethod.DELETE,"/addresses/"+second,null,a.access()),200);
        data(call(HttpMethod.DELETE,"/addresses/"+second,null,a.access()),200);
        error(call(HttpMethod.PUT,"/addresses/"+second,address(false),a.access()),404,"RESOURCE_NOT_FOUND");
        var spoof=new HashMap<>(address(false)); spoof.put("userId",otherReader);
        error(call(HttpMethod.POST,"/addresses",spoof,a.access()),400,"PARAM_INVALID");
    }
    @Test void administrationRequiresPermissionsAndNeverTrustsClientRoleClaims() {
        var member=login("reader_test");
        error(call(HttpMethod.GET,"/admin/users",null,null),401,"AUTH_REQUIRED");
        error(call(HttpMethod.GET,"/admin/users",null,member.access()),403,"ACCESS_DENIED");
        error(call(HttpMethod.GET,"/admin/roles",null,member.access()),403,"ACCESS_DENIED");
        error(call(HttpMethod.GET,"/admin/shops",null,member.access()),403,"ACCESS_DENIED");
        var root=login("admin_test");
        assertThat(data(call(HttpMethod.GET,"/admin/users?pageSize=2",null,root.access()),200).path("items").size()).isEqualTo(2);
        error(call(HttpMethod.GET,"/admin/users?page=0",null,root.access()),400,"PARAM_INVALID");
        error(call(HttpMethod.GET,"/admin/users?pageSize=101",null,root.access()),400,"PARAM_INVALID");
        assertThat(data(call(HttpMethod.GET,"/admin/users?keyword=%27%20OR%201%3D1%20--",null,root.access()),200).path("total").asLong()).isZero();
    }
    @Test void userDisableAndGrantChangesInvalidateOldAccessAndRefreshImmediately() {
        var root=login("admin_test"); var target=login("reader_test");
        data(call(HttpMethod.PUT,"/admin/users/"+reader,Map.of("nickname","changed","status","DISABLED","roleIds",List.of(memberRole)),root.access()),200);
        error(call(HttpMethod.GET,"/me",null,target.access()),403,"ACCOUNT_DISABLED");
        error(session("refresh",target,target.csrf(),"http://localhost:5173"),401,"AUTH_REQUIRED");
        data(call(HttpMethod.PUT,"/admin/users/"+reader,Map.of("nickname","changed","status","ACTIVE","roleIds",List.of(memberRole)),root.access()),200);
        var newLogin=login("reader_test");
        data(call(HttpMethod.PUT,"/admin/users/"+reader,Map.of("nickname","changed","status","ACTIVE","roleIds",List.of()),root.access()),200);
        error(call(HttpMethod.GET,"/me",null,newLogin.access()),401,"AUTH_REQUIRED");
    }
    @Test void roleCreationEditingAndPermissionDirectoryFollowTheContract() {
        var root=login("admin_test");
        var permissionList=data(call(HttpMethod.GET,"/admin/permissions",null,root.access()),200);
        String addressPermission="";
        for(var p:permissionList) if(p.path("code").asText().equals("member.address")) addressPermission=p.path("id").asText();
        assertThat(addressPermission).isNotBlank();
        String custom=data(call(HttpMethod.POST,"/admin/roles",Map.of("code","TEST_ROLE","name","测试角色","permissionIds",List.of(addressPermission)),root.access()),201).path("id").asText();
        String user=data(call(HttpMethod.POST,"/admin/users",Map.of("username","custom_user","nickname","自定义角色测试","initialPassword",PASSWORD,"roleIds",List.of(custom)),root.access()),201).path("id").asText();
        var access=login("custom_user");
        data(call(HttpMethod.GET,"/addresses",null,access.access()),200);
        data(call(HttpMethod.PUT,"/admin/roles/"+custom,Map.of("name","禁用测试","status","DISABLED","permissionIds",List.of(addressPermission)),root.access()),200);
        error(call(HttpMethod.GET,"/me",null,access.access()),401,"AUTH_REQUIRED");
        error(call(HttpMethod.GET,"/addresses",null,login("custom_user").access()),403,"ACCESS_DENIED");
        assertThat(data(call(HttpMethod.GET,"/admin/roles",null,root.access()),200).path("total").asInt()).isEqualTo(8);
        error(call(HttpMethod.POST,"/admin/users",Map.of("username","bad_roles","nickname","test","initialPassword",PASSWORD,"roleIds",List.of("999999")),root.access()),400,"PARAM_INVALID");
        assertThat(user).isNotBlank();
    }
    @Test void lastAdministratorAndBuiltinCriticalPermissionsAreProtected() {
        var root=login("admin_test");
        data(call(HttpMethod.PUT,"/admin/users/"+otherAdmin,Map.of("nickname","test","status","DISABLED","roleIds",List.of(adminRole)),root.access()),200);
        error(call(HttpMethod.PUT,"/admin/users/"+admin,Map.of("nickname","test","status","DISABLED","roleIds",List.of(adminRole)),root.access()),409,"STATE_CONFLICT");
        error(call(HttpMethod.PUT,"/admin/users/"+admin,Map.of("nickname","test","status","ACTIVE","roleIds",List.of()),root.access()),409,"STATE_CONFLICT");
        error(call(HttpMethod.PUT,"/admin/roles/"+adminRole,Map.of("name","admin","status","DISABLED","permissionIds",List.of()),root.access()),409,"STATE_CONFLICT");
        data(call(HttpMethod.GET,"/admin/users",null,root.access()),200);
        assertThat(sql.count("SELECT COUNT(*) FROM iam_user WHERE status='ACTIVE' AND id=?",admin)).isEqualTo(1);
    }
    @Test void concurrentAdministratorRevocationsCannotRemoveBothOperators() throws Exception {
        var a=login("admin_test"); var b=login("admin_other");
        var gate=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var x=executor.submit(() -> { gate.await(); return call(HttpMethod.PUT,"/admin/users/"+otherAdmin,Map.of("nickname","test","status","DISABLED","roleIds",List.of(adminRole)),a.access()).getStatusCode().value(); });
            var y=executor.submit(() -> { gate.await(); return call(HttpMethod.PUT,"/admin/users/"+admin,Map.of("nickname","test","status","DISABLED","roleIds",List.of(adminRole)),b.access()).getStatusCode().value(); });
            gate.countDown(); var codes=List.of(x.get(15,TimeUnit.SECONDS),y.get(15,TimeUnit.SECONDS));
            assertThat(codes.stream().filter(code->code==200).count()).isEqualTo(1);
            assertThat(sql.count("SELECT COUNT(*) FROM iam_user WHERE id IN (?,?) AND status='ACTIVE'",admin,otherAdmin)).isEqualTo(1);
        }
    }
    @Test void shopMembershipIsServerAssignedScopedAndRevokesExistingTokens() {
        var root=login("admin_test"); var m=login("merchant_test");
        Map<String,Object> input=Map.of("name","虚构店铺","contactName","演示店主","contactPhone","13800000001","status","ACTIVE");
        String shop=data(call(HttpMethod.POST,"/admin/shops",input,root.access()),201).path("id").asText();
        String other=data(call(HttpMethod.POST,"/admin/shops",input,root.access()),201).path("id").asText();
        data(call(HttpMethod.PUT,"/admin/shops/"+shop+"/members/"+merchant+"/MERCHANT",Map.of("status","ACTIVE"),root.access()),201);
        error(call(HttpMethod.GET,"/me",null,m.access()),401,"AUTH_REQUIRED");
        var refreshed=login("merchant_test");
        Actor actor=tokens.authenticate(refreshed.access());
        policy.requireShop(actor,shop,"MERCHANT","shop.product.read");
        assertThatThrownBy(()->policy.requireShop(actor,other,"MERCHANT","shop.product.read")).isInstanceOf(com.yueji.mall.common.api.BusinessException.class);
        error(call(HttpMethod.GET,"/admin/shops/"+shop+"/members",null,refreshed.access()),403,"ACCESS_DENIED");
        assertThat(data(call(HttpMethod.GET,"/admin/shops/"+shop+"/members",null,root.access()),200).path("total").asInt()).isEqualTo(1);
        data(call(HttpMethod.PUT,"/admin/shops/"+shop+"/members/"+merchant+"/MERCHANT",Map.of("status","DISABLED"),root.access()),200);
        error(call(HttpMethod.GET,"/me",null,refreshed.access()),401,"AUTH_REQUIRED");
        var stopped=new HashMap<>(input);stopped.put("status","SUSPENDED");
        data(call(HttpMethod.PUT,"/admin/shops/"+shop,stopped,root.access()),200);
        assertThat(data(call(HttpMethod.GET,"/admin/shops?status=SUSPENDED",null,root.access()),200).path("total").asInt()).isEqualTo(1);
    }
    @Test void auditIsScopedSafeAndSupportsTraceFiltering() {
        var root=login("admin_test");
        var input=Map.of("name","审计测试","contactName","不应泄露的姓名","contactPhone","13800000001","status","ACTIVE");
        data(call(HttpMethod.POST,"/admin/shops",input,root.access()),201);
        var rows=data(call(HttpMethod.GET,"/admin/audit-logs?action=shop.create&traceId=stage-b-test-123",null,root.access()),200);
        assertThat(rows.path("total").asInt()).isEqualTo(1);
        assertThat(rows.toString()).doesNotContain(PASSWORD,"passwordHash","13800000001","不应泄露的姓名");
        error(call(HttpMethod.GET,"/admin/audit-logs",null,login("reader_test").access()),403,"ACCESS_DENIED");
        error(call(HttpMethod.GET,"/admin/audit-logs?from=2026-10-02T00:00:00Z&to=2026-10-01T00:00:00Z",null,root.access()),400,"PARAM_INVALID");
    }
}
