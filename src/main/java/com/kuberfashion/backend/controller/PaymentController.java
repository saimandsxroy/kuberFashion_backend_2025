package com.kuberfashion.backend.controller;

import com.kuberfashion.backend.config.PhonePeConfig;
import com.kuberfashion.backend.dto.ApiResponse;
import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.service.PaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST controller for payment operations.
 *
 * <h3>Security model</h3>
 * <ul>
 *   <li><b>POST /initiate</b> — authenticated ({@code ROLE_USER}); order ownership checked
 *       inside {@link PaymentService}.</li>
 *   <li><b>GET /status/{merchantOrderId}</b> — authenticated ({@code ROLE_USER}); transaction
 *       ownership checked inside {@link PaymentService}.</li>
 *   <li><b>POST /webhook</b> — public (no JWT); PhonePe server-to-server callback.
 *       Cryptographic verification is mandatory before any state change occurs.</li>
 *   <li><b>POST /confirm</b> — simulation only, gated by
 *       {@link PhonePeConfig#isSimulationEnabled()}. Returns HTTP 404 in production.</li>
 *   <li><b>POST /fail</b>    — simulation only, same gate as /confirm.</li>
 * </ul>
 *
 * <p>The URL mappings for /api/payments/** are configured in {@code SecurityConfig} as
 * {@code authenticated()} (customer JWT required), except /webhook which is {@code permitAll()}.
 * The /confirm and /fail routes are additional runtime-gated endpoints that respond with 404
 * when simulation is disabled, making them invisible to attackers in production.
 */
@RestController
@RequestMapping("/api/payments")
@CrossOrigin(origins = {
        "http://localhost:5173", "http://127.0.0.1:5173",
        "https://kuberfashions.in", "https://www.kuberfashions.in",
        "https://laxmi.kuberfashions.in"
})
public class PaymentController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentController.class);

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PhonePeConfig phonePeConfig;

    // ─────────────────────────────────────────────────────────────────────────
    //  1. Initiate payment — authenticated, ownership checked in service layer
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initiate a PhonePe payment for the caller's own order.
     *
     * <p>{@code @AuthenticationPrincipal User user} is resolved by Spring Security from the
     * validated JWT token. It is passed into the service so the service can verify that the
     * order belongs to this user before creating a transaction.
     */
    @PostMapping("/initiate")
    public ResponseEntity<ApiResponse<Map<String, String>>> initiatePayment(
            @AuthenticationPrincipal User user,
            @RequestBody Map<String, Long> request) {

        Long orderId = request.get("orderId");
        if (orderId == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Order ID is required"));
        }

        try {
            Map<String, String> result = paymentService.initiatePayment(orderId, user);
            return ResponseEntity.ok(ApiResponse.success("Payment initiated successfully", result));
        } catch (SecurityException e) {
            // Order ownership mismatch
            logger.warn("Payment initiation denied: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("Access denied"));
        } catch (IllegalStateException e) {
            // SDK not configured and simulation disabled
            logger.error("Payment initiation configuration error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error("Payment service is not configured"));
        } catch (Exception e) {
            logger.error("Payment initiation failed: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Payment initiation failed: " + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  2. Check payment status — authenticated, ownership checked in service
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Poll the payment status for a given merchant order ID.
     *
     * <p>The service checks that the transaction belongs to the calling user.
     * A customer cannot poll another customer's transaction by guessing the merchant order ID.
     */
    @GetMapping("/status/{merchantOrderId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkPaymentStatus(
            @PathVariable String merchantOrderId,
            @AuthenticationPrincipal User user) {
        try {
            Map<String, Object> status = paymentService.checkPaymentStatus(merchantOrderId, user);
            return ResponseEntity.ok(ApiResponse.success("Payment status retrieved", status));
        } catch (SecurityException e) {
            logger.warn("Payment status check denied: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("Access denied"));
        } catch (Exception e) {
            logger.error("Error checking payment status: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Error checking status: " + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  3. PhonePe webhook — public (no JWT), mandatory SDK verification
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * PhonePe server-to-server callback endpoint.
     *
     * <p>This URL must be reachable by PhonePe's servers without a customer JWT. It is
     * declared {@code permitAll()} in {@link com.kuberfashion.backend.config.SecurityConfig}
     * specifically for this route. Authentication instead comes from the
     * {@code Authorization: Basic ...} header that PhonePe sends, which the SDK
     * verifies against the configured webhook credentials.
     *
     * <p>This method never trusts the request body alone. If {@link PaymentService#handleCallback}
     * returns {@code false}, the endpoint responds with HTTP 400 so PhonePe can retry.
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody String requestBody) {
        logger.info("Received PhonePe webhook callback");

        try {
            boolean success = paymentService.handleCallback(authorization, requestBody);
            if (success) {
                return ResponseEntity.ok("OK");
            } else {
                return ResponseEntity.badRequest().body("Callback validation failed");
            }
        } catch (Exception e) {
            logger.error("Webhook processing error: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("Error processing webhook");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  4. Simulation endpoints — disabled (HTTP 404) unless simulation-enabled
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Manually confirm a simulated payment (development / test only).
     *
     * <p>This endpoint returns HTTP 404 when {@code phonepe.simulation-enabled=false}
     * (the production default). It is NEVER a replacement for a real PhonePe webhook.
     * It exists solely so the frontend's simulated payment flow can complete an end-to-end
     * checkout during local development when no PhonePe credentials are available.
     *
     * <p>Even in simulation mode, the caller must be an authenticated user
     * ({@code ROLE_USER}) because {@code /api/payments/**} is {@code authenticated()} in
     * SecurityConfig. The simulation secret ({@code PHONEPE_SIMULATION_SECRET}) is not
     * implemented here intentionally — the endpoint is simply not reachable at all in
     * production due to the 404 gate.
     */
    @PostMapping("/confirm")
    public ResponseEntity<ApiResponse<String>> confirmPayment(
            @RequestBody Map<String, String> request) {

        // Production gate — returns 404 when simulation is disabled
        if (!phonePeConfig.isSimulationEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("Endpoint not available"));
        }

        String merchantOrderId = request.get("merchantOrderId");
        String transactionId = request.getOrDefault("transactionId", "SIM_" + System.currentTimeMillis());

        if (merchantOrderId == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Merchant order ID is required"));
        }

        try {
            paymentService.confirmSimulatedPayment(merchantOrderId, transactionId);
            return ResponseEntity.ok(ApiResponse.success("Payment confirmed successfully"));
        } catch (Exception e) {
            logger.error("Simulated payment confirmation failed: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Payment confirmation failed: " + e.getMessage()));
        }
    }

    /**
     * Manually fail a simulated payment (development / test only).
     *
     * @see #confirmPayment(Map) for the security model
     */
    @PostMapping("/fail")
    public ResponseEntity<ApiResponse<String>> failPayment(
            @RequestBody Map<String, String> request) {

        // Production gate — returns 404 when simulation is disabled
        if (!phonePeConfig.isSimulationEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("Endpoint not available"));
        }

        String merchantOrderId = request.get("merchantOrderId");
        String errorCode = request.getOrDefault("errorCode", "USER_CANCELLED");
        String errorMessage = request.getOrDefault("errorMessage", "Payment was cancelled by user");

        if (merchantOrderId == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Merchant order ID is required"));
        }

        try {
            paymentService.failSimulatedPayment(merchantOrderId, errorCode, errorMessage);
            return ResponseEntity.ok(ApiResponse.success("Payment marked as failed"));
        } catch (Exception e) {
            logger.error("Simulated payment failure marking failed: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error("Operation failed: " + e.getMessage()));
        }
    }
}
