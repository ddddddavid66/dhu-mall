package com.yueji.mall.identity.service;

import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.common.api.ErrorCode;
import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.identity.model.IdentityInputs.LoginInput;
import com.yueji.mall.identity.model.IdentityViews.AuthView;
import com.yueji.mall.identity.repository.IdentityRepository;
import com.yueji.mall.identity.repository.SessionRepository;
import com.yueji.mall.platform.repository.AuditRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Service
public class SessionService {
    public record Issued(AuthView body,String refreshToken,Instant expiresAt) {}
    private final SessionRepository repository;
    private final IdentityRepository identities;
    private final PasswordEncoder passwords;
    private final AccessTokens tokens;
    private final BrowserSessionGuard browser;
    private final AuditRepository audit;
    private final Clock clock;
    private final String dummyPasswordHash;
    public SessionService(SessionRepository repository,IdentityRepository identities,PasswordEncoder passwords,AccessTokens tokens,
            BrowserSessionGuard browser,AuditRepository audit,Clock clock) {
        this.repository=repository; this.identities=identities; this.passwords=passwords; this.tokens=tokens;
        this.browser=browser; this.audit=audit; this.clock=clock;
        this.dummyPasswordHash=passwords.encode(Crypto.randomToken());
    }
    @Transactional(noRollbackFor=SessionRejectedException.class)
    public Issued login(LoginInput input) {
        var found=identities.byUsername(input.username().toLowerCase(Locale.ROOT));
        if (found.isEmpty()) {
            matches(input.password(),dummyPasswordHash);
            audit.append(null,"auth.login","user",null,null,"FAILED");
            throw new SessionRejectedException(ErrorCode.AUTH_REQUIRED);
        }
        var user=identities.lockUser(text(found.get(),"id"));
        String id=text(user,"id");
        if (!"ACTIVE".equals(text(user,"status"))) throw new SessionRejectedException(ErrorCode.ACCOUNT_DISABLED);
        Instant locked=instant(user.get("locked_until"));
        if (locked!=null && locked.isAfter(clock.instant())) throw new SessionRejectedException(ErrorCode.AUTH_REQUIRED);
        if (!matches(input.password(),text(user,"password_hash"))) {
            int failures=locked!=null ? 1 : ((Number)user.get("failed_login_count")).intValue()+1;
            repository.loginFailure(id,failures,failures>=5 ? clock.instant().plus(Duration.ofMinutes(15)) : null);
            audit.append(id,"auth.login","user",id,null,"FAILED");
            throw new SessionRejectedException(ErrorCode.AUTH_REQUIRED);
        }
        repository.loginSuccess(id);
        Issued issued=create(user,Crypto.randomToken(),clock.instant().plus(Duration.ofDays(7)));
        audit.append(id,"auth.login","user",id,null,"SUCCESS");
        return issued;
    }
    @Transactional(noRollbackFor=SessionRejectedException.class)
    public Issued refresh(String raw,String csrf,String origin) {
        browser.origin(origin,csrf);
        var old=lockedSession(raw);
        var user=identities.user(text(old,"user_id"));
        browser.csrf(csrf,text(old,"csrf_hash"));
        String family=text(old,"family_no");
        if (old.get("revoked_at")!=null || old.get("replaced_by_id")!=null
                || !instant(old.get("expires_at")).isAfter(clock.instant())
                || !"ACTIVE".equals(text(user,"status"))
                || identities.authVersion(user)!=((Number)old.get("auth_version")).longValue()) {
            revokeFamily(family);
            audit.append(text(user,"id"),"auth.refresh_rejected","session",text(old,"id"),null,"DENIED");
            throw new SessionRejectedException(ErrorCode.AUTH_REQUIRED);
        }
        // 整个会话族的绝对期限为首次登录后 7 天，轮换不无限延长有效期。
        Issued issued=create(user,family,instant(old.get("expires_at")));
        String nextId=text(repository.byHash(Crypto.hash(issued.refreshToken())),"id");
        repository.replace(old.get("id"),nextId);
        audit.append(text(user,"id"),"auth.refresh","session",nextId,null,"SUCCESS");
        return issued;
    }
    @Transactional
    public void logout(String raw,String csrf,String origin) {
        browser.origin(origin,csrf);
        if (raw==null) return;
        var session=optionalSession(raw);
        if (session==null) return;
        identities.lockUser(text(session,"user_id"));
        browser.csrf(csrf,text(session,"csrf_hash"));
        revokeFamily(text(session,"family_no"));
        audit.append(text(session,"user_id"),"auth.logout","session",text(session,"id"),null,"SUCCESS");
    }
    private Map<String,Object> optionalSession(String raw) {
        if (raw==null || !raw.matches("[A-Za-z0-9_-]{43}")) return null;
        return repository.byHash(Crypto.hash(raw));
    }
    private Map<String,Object> lockedSession(String raw) {
        var existing=optionalSession(raw);
        if (existing==null) throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        // 始终先锁用户再锁会话，与改密/禁用的撤销路径保持相同顺序。
        identities.lockUser(text(existing,"user_id"));
        return repository.lock(existing.get("id"));
    }
    private Issued create(Map<String,Object> user,String family,Instant expiry) {
        String refresh=Crypto.randomToken();
        String csrf=Crypto.randomToken();
        repository.create(user.get("id"),family,Crypto.hash(refresh),expiry,Crypto.hash(csrf),identities.authVersion(user));
        return new Issued(new AuthView(tokens.issue(user,family),900,csrf,identities.view(text(user,"id"))),refresh,expiry);
    }
    private void revokeFamily(String family) {
        repository.revokeFamily(family);
    }
    private boolean matches(String raw,String encoded) {
        return raw.getBytes(StandardCharsets.UTF_8).length<=72 && passwords.matches(raw,encoded);
    }
    private static Instant instant(Object value) {
        if (value==null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        return ((java.time.LocalDateTime)value).toInstant(java.time.ZoneOffset.UTC);
    }
}
