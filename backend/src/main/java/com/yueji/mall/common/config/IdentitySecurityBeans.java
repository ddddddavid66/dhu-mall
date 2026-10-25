package com.yueji.mall.common.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration
@EnableMethodSecurity
public class IdentitySecurityBeans {
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean SecretKey jwtKey(@Value("${yueji.security.jwt-key}") String configured, Environment environment) {
        if (configured.isBlank()) {
            if (environment.acceptsProfiles(Profiles.of("prod")) || !environment.acceptsProfiles(Profiles.of("dev"))) throw new IllegalStateException("必须配置 JWT_SIGNING_KEY，生产和测试不自动生成签名密钥");
            return new SecretKeySpec(Base64.getUrlDecoder().decode(com.yueji.mall.identity.service.Crypto.randomToken()), "HmacSHA256");
        }
        byte[] bytes = Base64.getDecoder().decode(configured);
        if (bytes.length < 32) throw new IllegalStateException("JWT_SIGNING_KEY 必须至少为 256 位随机值的 Base64");
        return new SecretKeySpec(bytes,"HmacSHA256");
    }
    @Bean JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }
    @Bean JwtDecoder jwtDecoder(SecretKey key, @Value("${yueji.security.jwt-issuer}") String issuer, Clock clock) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                timestamps,new JwtIssuerValidator(issuer),
                new JwtClaimValidator<String>("token_use","access"::equals),
                new JwtClaimValidator<java.util.List<String>>("aud",aud -> aud != null && aud.contains("yueji-web"))));
        return decoder;
    }
}
