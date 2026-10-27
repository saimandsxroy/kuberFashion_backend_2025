package com.kuberfashion.backend.exception;

import com.kuberfashion.backend.dto.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("SecurityException returns HTTP 403 Forbidden without stack trace leakage")
    void testHandleSecurityException() {
        SecurityException ex = new SecurityException("User 42 does not own order 101");
        ResponseEntity<ApiResponse<Object>> response = exceptionHandler.handleSecurityException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).contains("Access denied");
        assertThat(response.getBody().getMessage()).contains("User 42 does not own order 101");
        // Verify response contains only safe client-facing payload, no stack traces
        assertThat(response.getBody().getData()).isNull();
    }

    @Test
    @DisplayName("Generic Exception returns HTTP 500 with sanitized generic message")
    void testHandleGenericException() {
        Exception ex = new RuntimeException("Unexpected internal failure");
        ResponseEntity<ApiResponse<Object>> response = exceptionHandler.handleGenericException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("An unexpected error occurred. Please try again later.");
        assertThat(response.getBody().getData()).isNull();
    }

    @Test
    @DisplayName("Generic Exception does NOT leak SQL, database credentials, or internal details to clients")
    void testHandleGenericException_DoesNotLeakInternalDetails() {
        String sensitiveInternalMessage = "Fatal DB error: Connection to postgresql://admin:s3cr3t@10.0.0.1:5432/kuberdb failed; SELECT * FROM credentials";
        Exception ex = new RuntimeException(sensitiveInternalMessage);
        ResponseEntity<ApiResponse<Object>> response = exceptionHandler.handleGenericException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("An unexpected error occurred. Please try again later.");
        assertThat(response.getBody().getMessage()).doesNotContain("Fatal DB error");
        assertThat(response.getBody().getMessage()).doesNotContain("postgresql");
        assertThat(response.getBody().getMessage()).doesNotContain("s3cr3t");
        assertThat(response.getBody().getMessage()).doesNotContain("SELECT");
        assertThat(response.getBody().getMessage()).doesNotContain("credentials");
    }

    @Test
    @DisplayName("ResourceNotFoundException returns HTTP 404")
    void testHandleResourceNotFoundException() {
        ResourceNotFoundException ex = new ResourceNotFoundException("Variant not found");
        ResponseEntity<ApiResponse<Object>> response = exceptionHandler.handleResourceNotFoundException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("Variant not found");
    }

    @Test
    @DisplayName("InsufficientStockException returns HTTP 400 Bad Request")
    void testHandleInsufficientStockException() {
        InsufficientStockException ex = new InsufficientStockException("Insufficient stock for size L");
        ResponseEntity<ApiResponse<Object>> response = exceptionHandler.handleInsufficientStockException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).contains("Insufficient stock");
    }

    @Test
    @DisplayName("IdempotencyConflictException returns HTTP 409 Conflict")
    void testHandleIdempotencyConflictException() {
        IdempotencyConflictException ex = new IdempotencyConflictException("Order already processing with this key");
        ResponseEntity<ApiResponse<Object>> response = exceptionHandler.handleIdempotencyConflictException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).contains("already processing");
    }

    @Test
    @DisplayName("MethodArgumentNotValidException returns HTTP 400 Bad Request with field errors map")
    void testHandleValidationExceptions() throws NoSuchMethodException {
        BindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "productName", "Product name is required for AI generation"));
        bindingResult.addError(new FieldError("target", "price", "Price must be positive"));

        MethodParameter parameter = new MethodParameter(
                this.getClass().getDeclaredMethod("testHandleValidationExceptions"), -1);
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<ApiResponse<Map<String, String>>> response = exceptionHandler.handleValidationExceptions(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).isEqualTo("Validation failed");
        assertThat(response.getBody().getData()).containsEntry("productName", "Product name is required for AI generation");
        assertThat(response.getBody().getData()).containsEntry("price", "Price must be positive");
    }

    @Test
    @DisplayName("DataIntegrityViolationException returns HTTP 409 Conflict with sanitized message")
    void testHandleDataIntegrityViolation() {
        DataIntegrityViolationException ex = new DataIntegrityViolationException(
                "ERROR: duplicate key value violates unique constraint 'uq_product_variants_prod_size_color' Detail: Key already exists.");
        ResponseEntity<ApiResponse<Object>> response = exceptionHandler.handleDataIntegrityViolation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getMessage()).contains("Duplicate entry detected");
        assertThat(response.getBody().getMessage()).doesNotContain("uq_product_variants_prod_size_color");
    }
}
