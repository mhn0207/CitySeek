package com.cityseek.config;

import com.cityseek.dto.Result;
import com.cityseek.exception.BusinessException;
import com.cityseek.exception.ForbiddenException;
import com.cityseek.exception.ResourceNotFoundException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<Result> handleValidation(Exception exception) {
        String message = "请求参数不合法";
        if (exception instanceof MethodArgumentNotValidException validation
                && validation.getBindingResult().getFieldError() != null) {
            message = validation.getBindingResult().getFieldError().getDefaultMessage();
        } else if (exception instanceof BindException binding
                && binding.getBindingResult().getFieldError() != null) {
            message = binding.getBindingResult().getFieldError().getDefaultMessage();
        }
        return ResponseEntity.badRequest().body(Result.fail(message));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class,
            HttpMessageNotReadableException.class,
            HttpMediaTypeNotSupportedException.class,
            MaxUploadSizeExceededException.class,
            IllegalArgumentException.class})
    public ResponseEntity<Result> handleBadRequest(Exception exception) {
        String message = exception instanceof IllegalArgumentException && exception.getMessage() != null
                ? exception.getMessage() : "请求参数不合法";
        return ResponseEntity.badRequest().body(Result.fail(message));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Result> handleForbidden(ForbiddenException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.fail(exception.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Result> handleNotFound(ResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Result.fail(exception.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result> handleConflict(BusinessException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Result.fail(exception.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result> handleRuntimeException(RuntimeException exception) {
        log.error("Unhandled server exception", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Result.fail("服务器异常"));
    }
}
