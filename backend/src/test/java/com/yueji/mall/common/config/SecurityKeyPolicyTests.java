package com.yueji.mall.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class SecurityKeyPolicyTests {
    @Test void productionCannotUseDevelopmentFallbackEvenWhenBothProfilesAreActive() {
        var env=new MockEnvironment(); env.setActiveProfiles("prod","dev");
        assertThatThrownBy(()->new IdentitySecurityBeans().jwtKey("",env)).isInstanceOf(IllegalStateException.class);
    }
    @Test void developmentKeyIsRandomButShortConfiguredSecretsAreRefused() {
        var env=new MockEnvironment(); env.setActiveProfiles("dev");
        var beans=new IdentitySecurityBeans();
        assertThat(java.util.Arrays.equals(beans.jwtKey("",env).getEncoded(),beans.jwtKey("",env).getEncoded())).isFalse();
        assertThatThrownBy(()->beans.jwtKey(java.util.Base64.getEncoder().encodeToString(new byte[8]),env)).isInstanceOf(IllegalStateException.class);
    }
}
