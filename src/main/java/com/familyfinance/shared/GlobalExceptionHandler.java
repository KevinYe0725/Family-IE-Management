package com.familyfinance.shared;

import jakarta.servlet.http.HttpServletRequest;
import com.familyfinance.category.CategoryHierarchyException;
import com.familyfinance.identity.PasswordChangeException;
import com.familyfinance.identity.RegistrationRequestBodyTooLargeException;
import com.familyfinance.identity.RegistrationRateLimitedException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.ValueInstantiationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiEnvelope<Void>> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fields.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(ApiEnvelope.error(new ApiError("VALIDATION_ERROR", "请检查输入内容", fields)));
    }

    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<ApiEnvelope<Void>> handleRequestValidation(RequestValidationException exception) {
        return ResponseEntity.badRequest()
                .body(ApiEnvelope.error(new ApiError("VALIDATION_ERROR", "请检查输入内容", exception.fields())));
    }

    @ExceptionHandler(CategoryHierarchyException.class)
    ResponseEntity<ApiEnvelope<Void>> handleCategoryHierarchy(CategoryHierarchyException exception) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiEnvelope.error(new ApiError("VALIDATION_ERROR", "请检查分类层级", exception.fields())));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiEnvelope<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        String message = Number.class.isAssignableFrom(exception.getRequiredType())
                ? "参数必须是数字"
                : "参数格式不正确";
        Map<String, String> fields = Map.of(exception.getName(), message);
        return ResponseEntity.badRequest()
                .body(ApiEnvelope.error(new ApiError("VALIDATION_ERROR", "请检查输入内容", fields)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiEnvelope<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        if (hasCause(exception, RegistrationRequestBodyTooLargeException.class)) {
            return ResponseEntity.badRequest()
                    .body(ApiEnvelope.error(new ApiError(
                            "VALIDATION_ERROR", "请检查输入内容", Map.of("request", "请求内容过大"))));
        }
        Throwable cause = exception.getCause();
        if (cause instanceof ValueInstantiationException valueException && !valueException.getPath().isEmpty()) {
            JacksonException.Reference reference =
                    valueException.getPath().get(valueException.getPath().size() - 1);
            Throwable root = valueException.getCause();
            String message = root == null ? "字段格式不正确" : root.getMessage();
            Map<String, String> fields = Map.of(reference.getPropertyName(), message);
            return ResponseEntity.badRequest()
                    .body(ApiEnvelope.error(new ApiError("VALIDATION_ERROR", "请检查输入内容", fields)));
        }
        return ResponseEntity.badRequest()
                .body(ApiEnvelope.error(ApiError.of("VALIDATION_ERROR", "请检查输入内容")));
    }

    private static boolean hasCause(Throwable exception, Class<? extends Throwable> causeType) {
        Throwable current = exception;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiEnvelope<Void>> handleNotFound(ResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiEnvelope.error(ApiError.of("NOT_FOUND", exception.getMessage())));
    }

    @ExceptionHandler(ResourceConflictException.class)
    ResponseEntity<ApiEnvelope<Void>> handleConflict(ResourceConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiEnvelope.error(ApiError.of(exception.code(), exception.getMessage())));
    }

    @ExceptionHandler(PasswordChangeException.class)
    ResponseEntity<ApiEnvelope<Void>> handlePasswordChange(PasswordChangeException exception) {
        return ResponseEntity.badRequest()
                .body(ApiEnvelope.error(ApiError.of("PASSWORD_CHANGE_FAILED", "密码修改失败")));
    }

    @ExceptionHandler(RegistrationRateLimitedException.class)
    ResponseEntity<ApiEnvelope<Void>> handleRegistrationRateLimited(RegistrationRateLimitedException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiEnvelope.error(ApiError.of("REGISTRATION_RATE_LIMITED", "注册暂时无法完成")));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiEnvelope<Void>> handleAccessDenied(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiEnvelope.error(ApiError.of("FORBIDDEN", "没有权限执行此操作")));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ApiEnvelope<Void>> handleLockFailure(PessimisticLockingFailureException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiEnvelope.error(ApiError.of("LOCK_RETRY_REQUIRED", "操作繁忙，请重试")));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiEnvelope<Void>> handleMissingRoute(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiEnvelope.error(ApiError.of("NOT_FOUND", "请求的资源不存在")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiEnvelope<Void>> handleUnexpected(Exception exception, HttpServletRequest request) {
        String requestId = RequestCorrelationFilter.requestId(request);
        LOGGER.error("Unexpected API exception requestId={}", requestId, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiEnvelope.error(ApiError.of("INTERNAL_ERROR", "服务器暂时无法处理请求")));
    }
}
