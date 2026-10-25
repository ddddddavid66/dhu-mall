package com.yueji.mall.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yueji.mall.common.api.ErrorCode;
import com.yueji.mall.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
public class ApiErrorWriter {
    private final ObjectMapper mapper;

    public ApiErrorWriter(ObjectMapper mapper) { this.mapper = mapper; }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException {
        write(response,code,TraceIdFilter.current(request));
    }

    public void write(HttpServletResponse response,ErrorCode code,String traceId) throws IOException {
        response.setStatus(code.httpStatus());
        if (code==ErrorCode.RATE_LIMITED) response.setHeader("Retry-After","60");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), Result.failure(code, traceId));
    }
}
