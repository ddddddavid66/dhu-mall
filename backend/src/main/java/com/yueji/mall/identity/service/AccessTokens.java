package com.yueji.mall.identity.service;

import com.yueji.mall.common.api.ApiId;
import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.common.api.ErrorCode;
import com.yueji.mall.common.persistence.SqlStore;
import com.yueji.mall.identity.model.Actor;
import com.yueji.mall.identity.repository.IdentityRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import static com.yueji.mall.common.persistence.SqlStore.text;

@Service
public class AccessTokens {
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final IdentityRepository identities;
    private final Clock clock;
    private final String issuer;
    public AccessTokens(JwtEncoder encoder, JwtDecoder decoder, IdentityRepository identities,
            Clock clock, @Value("${yueji.security.jwt-issuer}") String issuer) {
        this.encoder=encoder; this.decoder=decoder; this.identities=identities; this.clock=clock; this.issuer=issuer;
    }
    public String issue(Map<String,Object> user, String family) {
        var now=clock.instant();
        var claims=JwtClaimsSet.builder().issuer(issuer).audience(List.of("yueji-web"))
                .subject(text(user,"id")).issuedAt(now).expiresAt(now.plus(Duration.ofMinutes(15)))
                .claim("sid",family).claim("av",identities.authVersion(user)).claim("token_use","access").build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
    public Actor authenticate(String token) {
        final Jwt jwt;
        try { jwt=decoder.decode(token); }
        catch (JwtException | IllegalArgumentException error) { throw new BusinessException(ErrorCode.AUTH_REQUIRED); }
        if (jwt.getExpiresAt()==null || jwt.getIssuedAt()==null || jwt.getIssuedAt().isAfter(clock.instant())
                || jwt.getSubject()==null || !(jwt.getClaim("av") instanceof Number version)
                || !(jwt.getClaim("sid") instanceof String family) || !family.matches("[A-Za-z0-9_-]{43}")) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
        String userId=new ApiId(jwt.getSubject()).value();
        var user=identities.user(userId);
        if (!"ACTIVE".equals(text(user,"status"))) throw new BusinessException(ErrorCode.ACCOUNT_DISABLED);
        if (identities.authVersion(user)!=version.longValue() || !identities.hasLiveFamily(userId,family,version.longValue())) throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        return new Actor(userId,version.longValue(),family,Set.copyOf(identities.permissions(userId)));
    }
}
