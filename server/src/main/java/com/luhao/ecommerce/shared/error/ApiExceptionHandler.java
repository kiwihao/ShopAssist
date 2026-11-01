package com.luhao.ecommerce.shared.error;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> business(ApiException e) {
        return error(e.status().value(), e.code(), e.getMessage());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestPartException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception e) {
        return error(400, "INVALID_REQUEST", "请检查必填字段和参数类型");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> large(Exception e) {
        return error(413, "FILE_TOO_LARGE", "Markdown 文件不能超过 256KB");
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> database(DataAccessException e) {
        return error(503, "DATABASE_ERROR", "数据库操作失败，请稍后重试");
    }

    private ResponseEntity<?> error(int status, String code, String message) {
        String requestId = UUID.randomUUID().toString();
        log.warn("api requestId={} errorCode={}", requestId, code);
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message, "requestId", requestId));
    }
}
