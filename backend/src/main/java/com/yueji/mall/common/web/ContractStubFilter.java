package com.yueji.mall.common.web;

import com.yueji.mall.common.api.ErrorCode;
import io.swagger.v3.oas.models.OpenAPI;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ContractStubFilter extends OncePerRequestFilter {
    private record StubRoute(String method, PathPattern pattern, String operationId,boolean implemented) {}
    private final List<StubRoute> routes;
    private final ApiErrorWriter errors;
    private final com.yueji.mall.payment.adapter.PaymentSettings paymentSettings;

    public ContractStubFilter(OpenAPI contract, ApiErrorWriter errors,com.yueji.mall.payment.adapter.PaymentSettings paymentSettings) {
        this.paymentSettings=paymentSettings;
        this.errors = errors;
        var result = new ArrayList<StubRoute>();
        contract.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) ->
                result.add(new StubRoute(method.name(), PathPatternParser.defaultInstance.parse(path),
                        operation.getOperationId(),"IMPLEMENTED".equals(operation.getExtensions().get("x-implementation-status"))))));
        this.routes = List.copyOf(result);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        PathContainer path = PathContainer.parsePath(request.getRequestURI());
        var matched = routes.stream().filter(route -> route.pattern().matches(path)).toList();
        // Only operations marked IMPLEMENTED in the generated contract reach a controller.
        var operation = matched.stream().filter(route -> route.method().equals(request.getMethod())).findFirst();
        if (operation.isPresent()) {
            if("API-34".equals(operation.get().operationId()) && !paymentSettings.mockEnabled) {errors.write(request,response,ErrorCode.RESOURCE_NOT_FOUND);return;}
            if (operation.get().implemented()) { chain.doFilter(request,response); return; }
            if ("API-34".equals(operation.get().operationId())) {
                errors.write(request, response, ErrorCode.RESOURCE_NOT_FOUND);
                return;
            }
            errors.write(request, response, ErrorCode.NOT_IMPLEMENTED);
            return;
        }
        errors.write(request, response, matched.isEmpty() ? ErrorCode.RESOURCE_NOT_FOUND : ErrorCode.METHOD_NOT_ALLOWED);
    }
}
