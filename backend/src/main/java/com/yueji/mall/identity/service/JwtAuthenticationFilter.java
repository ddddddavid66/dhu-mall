package com.yueji.mall.identity.service;

import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.common.api.ErrorCode;
import com.yueji.mall.common.web.ApiErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final AccessTokens tokens;
    private final ApiErrorWriter errors;
    private final UserWriteLimiter writes;
    public JwtAuthenticationFilter(AccessTokens tokens,ApiErrorWriter errors,UserWriteLimiter writes) { this.tokens=tokens; this.errors=errors; this.writes=writes; }
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/") || Set.of("/api/v1/auth/register","/api/v1/auth/login",
                "/api/v1/auth/refresh","/api/v1/auth/logout").contains(request.getRequestURI());
    }
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String header=request.getHeader("Authorization");
        if (header==null) { chain.doFilter(request,response); return; }
        try {
            if (!header.startsWith("Bearer ") || header.length()>4096) throw new BusinessException(ErrorCode.AUTH_REQUIRED);
            var actor=tokens.authenticate(header.substring(7));
            if (Set.of("POST","PUT","PATCH","DELETE").contains(request.getMethod())) writes.check(actor.userId());
            var authorities=actor.permissions().stream().map(SimpleGrantedAuthority::new).toList();
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,null,authorities));
        } catch (BusinessException error) { errors.write(request,response,error.code()); return; }
        catch (IllegalArgumentException error) { errors.write(request,response,ErrorCode.AUTH_REQUIRED); return; }
        catch (DataAccessException error) { errors.write(request,response,ErrorCode.DEPENDENCY_UNAVAILABLE); return; }
        chain.doFilter(request,response);
    }
}
