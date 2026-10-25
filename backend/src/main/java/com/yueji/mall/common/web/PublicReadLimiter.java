package com.yueji.mall.common.web;

import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.identity.service.LoginLimiter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class PublicReadLimiter extends OncePerRequestFilter {
    private final LoginLimiter windows;private final ApiErrorWriter errors;
    public PublicReadLimiter(@Value("${yueji.security.public-read-limit:120}") int limit,Clock clock,ApiErrorWriter errors) {windows=new LoginLimiter(limit,clock);this.errors=errors;}
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI();
        return !"GET".equals(request.getMethod()) || !(path.equals("/api/v1/categories") || path.equals("/api/v1/products") || path.startsWith("/api/v1/products/") || path.startsWith("/api/v1/files/"));
    }
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        try {windows.check(request.getRemoteAddr());} catch(BusinessException error) {errors.write(request,response,error.code());return;}
        chain.doFilter(request,response);
    }
}
