package com.yueji.mall.common.web;

import com.yueji.mall.common.api.BusinessException;
import com.yueji.mall.common.api.ErrorCode;
import com.yueji.mall.common.api.FieldErrorDetail;
import com.yueji.mall.common.api.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<Result<Void>> uploadSize(Exception error,HttpServletRequest request) {
        return failure(ErrorCode.FILE_TOO_LARGE,request,List.of());
    }
    @ExceptionHandler({org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.MultipartException.class})
    ResponseEntity<Result<Void>> uploadPart(Exception error,HttpServletRequest request) {
        return failure(ErrorCode.PARAM_INVALID,request,List.of());
    }
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Result<Void>> business(BusinessException error, HttpServletRequest request) {
        return failure(error.code(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Result<Void>> validation(MethodArgumentNotValidException error, HttpServletRequest request) {
        var details = error.getBindingResult().getFieldErrors().stream()
                .map(field -> new FieldErrorDetail(field.getField(), field.getDefaultMessage())).toList();
        return failure(ErrorCode.PARAM_INVALID, request, details);
    }

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Result<Void>> invalid(Exception error, HttpServletRequest request) {
        return failure(ErrorCode.PARAM_INVALID, request, List.of());
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<Result<Void>> conflict(Exception error,HttpServletRequest request) {
        return failure(ErrorCode.STATE_CONFLICT,request,List.of());
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<Result<Void>> unavailable(Exception error,HttpServletRequest request) {
        LOG.error("Database failure type={}",error.getClass().getName());
        return failure(ErrorCode.DEPENDENCY_UNAVAILABLE,request,List.of());
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<Result<Void>> denied(Exception error,HttpServletRequest request) {
        return failure(ErrorCode.ACCESS_DENIED,request,List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Result<Void>> missing(Exception error, HttpServletRequest request) {
        return failure(ErrorCode.RESOURCE_NOT_FOUND, request, List.of());
    }

    @ExceptionHandler(org.springframework.beans.BeanInstantiationException.class)
    ResponseEntity<Result<Void>> queryConstructor(org.springframework.beans.BeanInstantiationException error,HttpServletRequest request) {
        boolean invalidPage=error.getBeanClass()==com.yueji.mall.common.api.PageQuery.class && error.getCause() instanceof IllegalArgumentException;
        return failure(invalidPage ? ErrorCode.PARAM_INVALID : ErrorCode.INTERNAL_ERROR,request,List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Result<Void>> method(Exception error, HttpServletRequest request) {
        return failure(ErrorCode.METHOD_NOT_ALLOWED, request, List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Result<Void>> media(Exception error, HttpServletRequest request) {
        return failure(ErrorCode.FILE_TYPE_UNSUPPORTED, request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Result<Void>> unexpected(Exception error, HttpServletRequest request) {
        // 不把异常 message 或请求内容写入日志，traceId 用于人工定位。
        LOG.error("Unhandled error type={}", error.getClass().getName());
        return failure(ErrorCode.INTERNAL_ERROR, request, List.of());
    }

    private ResponseEntity<Result<Void>> failure(ErrorCode code, HttpServletRequest request,
            List<FieldErrorDetail> details) {
        return ResponseEntity.status(code.httpStatus())
                .headers(headers -> { if(code==ErrorCode.RATE_LIMITED) headers.set("Retry-After","60"); })
                .body(Result.failure(code, TraceIdFilter.current(request), details));
    }
}
