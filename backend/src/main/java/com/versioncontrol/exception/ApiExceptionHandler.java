package com.versioncontrol.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.versioncontrol.dto.ApiError;
import com.versioncontrol.versioncontrol.storage.ObjectStorageException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ApiError> validation(Exception exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (exception instanceof MethodArgumentNotValidException invalid) {
            for (FieldError field : invalid.getBindingResult().getFieldErrors()) fields.putIfAbsent(field.getField(), field.getDefaultMessage());
        } else if (exception instanceof ConstraintViolationException violations) {
            violations.getConstraintViolations().forEach(v -> fields.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        }
        return response(HttpStatus.BAD_REQUEST, "Validation failed", fields);
    }

    @ExceptionHandler(DuplicateUserException.class)
    ResponseEntity<ApiError> duplicate(DuplicateUserException exception) { return response(HttpStatus.CONFLICT, exception.getMessage(), Map.of()); }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(DataIntegrityViolationException exception) { return response(HttpStatus.CONFLICT, "The request conflicts with existing data", Map.of()); }

    @ExceptionHandler(RepositoryConflictException.class)
    ResponseEntity<ApiError> repositoryConflict(RepositoryConflictException exception) { return response(HttpStatus.CONFLICT, exception.getMessage(), Map.of()); }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ApiError> invalidRequest(InvalidRequestException exception) { return response(HttpStatus.BAD_REQUEST, exception.getMessage(), Map.of()); }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException exception) { return response(HttpStatus.NOT_FOUND, exception.getMessage(), Map.of()); }

    @ExceptionHandler(ResourceForbiddenException.class)
    ResponseEntity<ApiError> forbidden(ResourceForbiddenException exception) { return response(HttpStatus.FORBIDDEN, exception.getMessage(), Map.of()); }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException exception) { return response(HttpStatus.BAD_REQUEST, "Request body is invalid", Map.of()); }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException exception) { return response(HttpStatus.BAD_REQUEST, "Request parameter or path value is invalid", Map.of()); }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ApiError> badCredentials(BadCredentialsException exception) { return response(HttpStatus.UNAUTHORIZED, "Invalid username/email or password", Map.of()); }

    @ExceptionHandler(ObjectStorageException.class)
    ResponseEntity<ApiError> storageFailure(ObjectStorageException exception) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Stored object is unavailable", Map.of());
    }

    private ResponseEntity<ApiError> response(HttpStatus status, String error, Map<String, String> fields) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(), error, fields));
    }
}
