package com.yueji.mall.common.config;

import com.yueji.mall.common.api.ErrorCode;
import com.yueji.mall.common.web.ApiErrorWriter;
import com.yueji.mall.common.web.ContractStubFilter;
import com.yueji.mall.identity.service.JwtAuthenticationFilter;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class SecurityConfiguration {
    @Bean
    CorsFilter corsFilter(@org.springframework.beans.factory.annotation.Qualifier("corsConfigurationSource") CorsConfigurationSource source,ApiErrorWriter errors) {
        var filter=new CorsFilter(source);
        filter.setCorsProcessor(new DefaultCorsProcessor() {
            @Override
            protected void rejectRequest(org.springframework.http.server.ServerHttpResponse response) throws java.io.IOException {
                var servlet=((org.springframework.http.server.ServletServerHttpResponse)response).getServletResponse();
                errors.write(servlet,ErrorCode.CSRF_INVALID,servlet.getHeader("X-Request-Id"));
            }
        });
        return filter;
    }
    @Bean
    FilterRegistrationBean<CorsFilter> corsRegistration(CorsFilter corsFilter) {
        var registration=new FilterRegistrationBean<>(corsFilter); registration.setEnabled(false); return registration;
    }
    @Bean
    SecurityFilterChain security(HttpSecurity http, ApiErrorWriter errors, ContractStubFilter stubs,JwtAuthenticationFilter jwt,com.yueji.mall.common.web.PublicReadLimiter publicReads) throws Exception {
        // Cookie 刷新/退出的 CSRF 由 SessionService 核对 Origin 与持久化令牌哈希；其他写入只接受 Bearer。
        return http.cors(Customizer.withDefaults()).csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwt,UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(stubs, AuthorizationFilter.class)
                .addFilterBefore(publicReads,AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/api/v1/auth/register","/api/v1/auth/login","/api/v1/auth/refresh","/api/v1/auth/logout").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/categories","/api/v1/products","/api/v1/products/**","/api/v1/files/*/content").permitAll()
                        .requestMatchers("/api/v1/merchant/**","/api/v1/ops/**","/api/v1/cart","/api/v1/cart/**","/api/v1/files").authenticated()
                        .requestMatchers("/api/v1/after-sales/**","/api/v1/support/**","/api/v1/finance/**","/api/v1/reviews").authenticated()
                        .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/v1/payments/alipay/notify").permitAll()
                        .requestMatchers("/api/v1/checkout/**","/api/v1/orders","/api/v1/orders/**","/api/v1/payments/**").authenticated()
                        .requestMatchers("/api/v1/me","/api/v1/me/**","/api/v1/addresses","/api/v1/addresses/**","/api/v1/admin/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> errors.write(request, response, ErrorCode.AUTH_REQUIRED))
                        .accessDeniedHandler((request, response, exception) -> errors.write(request, response, ErrorCode.ACCESS_DENIED)))
                .build();
    }

    @Bean
    FilterRegistrationBean<com.yueji.mall.common.web.PublicReadLimiter> publicReadRegistration(com.yueji.mall.common.web.PublicReadLimiter filter) {
        var registration=new FilterRegistrationBean<>(filter);registration.setEnabled(false);return registration;
    }
    @Bean
    FilterRegistrationBean<JwtAuthenticationFilter> jwtRegistration(JwtAuthenticationFilter jwt) {
        var registration=new FilterRegistrationBean<>(jwt);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ContractStubFilter> stubRegistration(ContractStubFilter stubs) {
        var registration = new FilterRegistrationBean<>(stubs);
        // 只在安全链中运行一次，让 CORS 预检先得到正常处理。
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${yueji.allowed-origins}") String origins) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key", "X-CSRF-Token"));
        configuration.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
        configuration.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
