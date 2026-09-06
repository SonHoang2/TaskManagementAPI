package com.sonhoang2.task_service.common.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonhoang2.task_service.common.dto.JSendResponse;
import com.sonhoang2.task_service.common.exception.ResourceConflictException;
import com.sonhoang2.common.exception.RateLimitExceededException;
import feign.FeignException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<JSendResponse<Map<String, Object>>> handleResourceNotFound(
            ResourceNotFoundException ex,
            HttpServletRequest request) {

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp", Instant.now());
        data.put("path", request.getRequestURI());

        return buildFailResponse(
                HttpStatus.NOT_FOUND,
                ex.getMessage(),
                data
        );
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<JSendResponse<Map<String, Object>>> handleBadCredentials(
            BadCredentialsException ex,
            HttpServletRequest request) {

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp", Instant.now());
        data.put("path", request.getRequestURI());

        return buildFailResponse(
                HttpStatus.UNAUTHORIZED,
                ex.getMessage(),
                data
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<JSendResponse<Map<String, Object>>> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp", Instant.now());
        data.put("path", request.getRequestURI());

        return buildFailResponse(
                HttpStatus.FORBIDDEN,
                ex.getMessage(),
                data
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<JSendResponse<Map<String, Object>>> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        Map<String, String> validationErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            validationErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp", Instant.now());
        data.put("path", request.getRequestURI());
        data.put("validationErrors", validationErrors);

        return buildFailResponse(
                HttpStatus.BAD_REQUEST,
                "Validation failed",
                data
        );
    }

    @ExceptionHandler(ResourceConflictException.class)
    public ResponseEntity<JSendResponse<Map<String, Object>>> handleResourceConflict(
            ResourceConflictException ex,
            HttpServletRequest request) {

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp", Instant.now());
        data.put("path", request.getRequestURI());

        return buildFailResponse(
                HttpStatus.CONFLICT,
                ex.getMessage(),
                data
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<JSendResponse<Map<String, Object>>> handleDataIntegrityViolation(
            DataIntegrityViolationException ignored,
            HttpServletRequest request) {

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp", Instant.now());
        data.put("path", request.getRequestURI());

        return buildFailResponse(
                HttpStatus.CONFLICT,
                "Data integrity violation",
                data
        );
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<JSendResponse<Map<String, Object>>> handleInvalidInput(
            Exception ignored,
            HttpServletRequest request) {

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp", Instant.now());
        data.put("path", request.getRequestURI());

        return buildFailResponse(
                HttpStatus.BAD_REQUEST,
                "Invalid status value",
                data
        );
    }

    @ExceptionHandler(FeignException.class)
    public ResponseEntity<JSendResponse<Map<String, Object>>> handleFeignException(
            FeignException ex,
            HttpServletRequest request) {

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp", Instant.now());
        data.put("path", request.getRequestURI());

        HttpStatus status = HttpStatus.valueOf(ex.status());
        String message = ex.getMessage();

        String content = ex.contentUTF8();
        if (content != null && !content.isEmpty()) {
            try {
                JsonNode jsonNode = objectMapper.readTree(content);
                JsonNode messageNode = jsonNode.path("message");
                if (!messageNode.isMissingNode() && !messageNode.isNull()) {
                    message = messageNode.asText();
                }
            } catch (Exception e) {
                // If parsing fails, try to extract message using regex
                try {
                    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"message\"\\s*:\\s*\"([^\"]+)\"");
                    java.util.regex.Matcher matcher = pattern.matcher(content);
                    if (matcher.find()) {
                        message = matcher.group(1);
                    }
                } catch (Exception ignored) {
                    // If regex fails, use the original content
                }
            }
        }

        return buildFailResponse(
                status,
                message,
                data
        );
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<JSendResponse<Map<String, Object>>> handleRateLimit(
            RateLimitExceededException ex,
            HttpServletRequest request) {

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp", Instant.now());
        data.put("path", request.getRequestURI());

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(JSendResponse.error(ex.getMessage(), HttpStatus.TOO_MANY_REQUESTS.value(), data));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<JSendResponse<Map<String, Object>>> handleGenericException(
            Exception ignored,
            HttpServletRequest request) {

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp", Instant.now());
        data.put("path", request.getRequestURI());

        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                data
        );
    }

    private ResponseEntity<JSendResponse<Map<String, Object>>> buildFailResponse(
            HttpStatus status,
            String message,
            Map<String, Object> data) {

        return ResponseEntity.status(status).body(JSendResponse.fail(data, message));
    }

    private ResponseEntity<JSendResponse<Map<String, Object>>> buildErrorResponse(
            HttpStatus status,
            String message,
            Map<String, Object> data) {

        return ResponseEntity.status(status).body(JSendResponse.error(message, status.value(), data));
    }
}

