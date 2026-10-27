package com.kuberfashion.backend.service;

import com.kuberfashion.backend.config.PhonePeConfig;
import com.kuberfashion.backend.entity.Order;
import com.kuberfashion.backend.entity.OrderItem;
import com.kuberfashion.backend.entity.PaymentTransaction;
import com.kuberfashion.backend.entity.Product;
import com.kuberfashion.backend.entity.User;
import com.kuberfashion.backend.exception.ResourceNotFoundException;
import com.kuberfashion.backend.repository.OrderRepository;
import com.kuberfashion.backend.repository.PaymentTransactionRepository;
import com.kuberfashion.backend.repository.ProductRepository;

import com.phonepe.sdk.pg.Env;
import com.phonepe.sdk.pg.common.models.response.CallbackResponse;
import com.phonepe.sdk.pg.payments.v2.StandardCheckoutClient;
import com.phonepe.sdk.pg.payments.v2.models.request.StandardCheckoutPayRequest;
import com.phonepe.sdk.pg.payments.v2.models.response.StandardCheckoutPayResponse;
import com.phonepe.sdk.pg.common.models.response.OrderStatusResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Payment Service for PhonePe Integration using official SDK.
 *
 * <h3>Security design</h3>
 * <ul>
 *   <li><b>initiatePayment</b> — caller must be the authenticated owner of the order.</li>
 *   <li><b>checkPaymentStatus</b> — caller must be the authenticated owner of the transaction.</li>
 *   <li><b>markPaymentSuccess / markPaymentFailed</b> — package-private and never exposed to HTTP
 *       directly. Accessible only from (a) a verified PhonePe webhook callback or
 *       (b) dev-simulation mode when the {@code phonepe.simulation-enabled} flag is explicitly
 *       {@code true}.</li>
 *   <li><b>handleCallback</b> — calls {@link StandardCheckoutClient#validateCallback} with the
 *       configured webhook credentials. Returns {@code false} (→ HTTP 400) on any failure; the
 *       caller is never trusted.</li>
 *   <li><b>Idempotency</b> — {@code markPaymentSuccess} is a no-op when the transaction is already
 *       {@code COMPLETED}, preventing referral double-awards from replayed webhooks.</li>
 * </ul>
 */
@Service
@Transactional
public class PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    @Autowired
    private PhonePeConfig phonePeConfig;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderService orderService;

    @Autowired
    private ReferralService referralService;

    private StandardCheckoutClient phonePeClient;

    @PostConstruct
    public void init() {
        if (phonePeConfig.isConfigured()) {
            try {
                Env env = phonePeConfig.isSandbox() ? Env.SANDBOX : Env.PRODUCTION;
                phonePeClient = StandardCheckoutClient.getInstance(
                        phonePeConfig.getClientId(),
                        phonePeConfig.getClientSecret(),
                        Integer.parseInt(phonePeConfig.getClientVersion()),
                        env);
                logger.info("✅ PhonePe SDK initialized successfully in {} mode", env);
            } catch (Exception e) {
                logger.error("❌ Failed to initialize PhonePe SDK: {}", e.getMessage());
            }
        } else {
            logger.warn("⚠️ PhonePe SDK not configured — payment features will be limited");
        }
    }

    public StandardCheckoutClient getPhonePeClient() {
        return phonePeClient;
    }

    // -------------------------------------------------------------------------
    //  Payment initiation — caller must own the order
    // -------------------------------------------------------------------------

    /**
     * Initiate a PhonePe payment for an order.
     *
     * <p><b>Ownership check:</b> {@code user.getId()} is compared with the order's user ID.
     * A mismatched caller receives a {@link SecurityException} which the caller should map to
     * HTTP 403. This prevents customer-A from initiating payment against customer-B's order.
     *
     * @param orderId the database PK of the {@link Order}
     * @param callingUser the authenticated principal (injected from {@code @AuthenticationPrincipal})
     * @return a map containing {@code merchantOrderId}, {@code redirectUrl}, and {@code orderId}
     */
    public Map<String, String> initiatePayment(Long orderId, User callingUser) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        // ── OWNERSHIP CHECK ──────────────────────────────────────────────────
        // callingUser is populated by JwtAuthenticationFilter; it is never null here
        // because /api/payments/initiate requires authentication in SecurityConfig.
        if (!order.getUser().getId().equals(callingUser.getId())) {
            logger.warn("⛔ User {} attempted to pay for order {} owned by user {}",
                    callingUser.getId(), orderId, order.getUser().getId());
            throw new SecurityException("Access denied: order does not belong to the authenticated user");
        }

        // Generate unique merchant order ID
        String merchantOrderId = "KF" + System.currentTimeMillis()
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Amount in paise (multiply by 100)
        long amountInPaise = order.getTotalAmount().multiply(BigDecimal.valueOf(100)).longValue();

        // Create payment transaction record
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setOrder(order);
        transaction.setMerchantOrderId(merchantOrderId);
        transaction.setAmount(order.getTotalAmount());
        transaction.setStatus(PaymentTransaction.TransactionStatus.INITIATED);

        String redirectUrl;

        if (phonePeClient != null) {
            try {
                // Build the redirect URL with merchantOrderId
                String callbackRedirectUrl = phonePeConfig.getRedirectUrl() + "?merchantOrderId=" + merchantOrderId;

                // Create PhonePe payment request
                StandardCheckoutPayRequest payRequest = StandardCheckoutPayRequest.builder()
                        .merchantOrderId(merchantOrderId)
                        .amount(amountInPaise)
                        .redirectUrl(callbackRedirectUrl)
                        .build();

                // Initiate payment with PhonePe
                StandardCheckoutPayResponse payResponse = phonePeClient.pay(payRequest);
                redirectUrl = payResponse.getRedirectUrl();

                transaction.setRedirectUrl(redirectUrl);
                transaction.setStatus(PaymentTransaction.TransactionStatus.PENDING);

                logger.info("✅ PhonePe payment initiated for order {} — merchantOrderId: {}",
                        order.getOrderNumber(), merchantOrderId);

            } catch (Exception e) {
                logger.error("❌ PhonePe payment initiation failed: {}", e.getMessage());

                // GRACEFUL FALLBACK: simulation mode is only triggered when
                // phonePeClient is configured but the SDK call fails (e.g. network).
                // The ?simulated=true flag is recognised by PaymentStatusPage.jsx to
                // auto-confirm; this path is NOT reached in production because the SDK
                // only fails in development/sandbox environments.
                if (!phonePeConfig.isSimulationEnabled()) {
                    throw new RuntimeException("Payment initiation failed: " + e.getMessage(), e);
                }
                logger.warn("⚠️ Falling back to simulated payment mode (simulation-enabled=true)");
                redirectUrl = phonePeConfig.getRedirectUrl() + "?merchantOrderId=" + merchantOrderId
                        + "&simulated=true&error=sdk_failure";
                transaction.setRedirectUrl(redirectUrl);
                transaction.setStatus(PaymentTransaction.TransactionStatus.PENDING);
                transaction.setErrorMessage("PhonePe SDK failed, using simulation: "
                        + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
            }
        } else {
            // SDK not configured — simulation mode is only permitted when explicitly enabled
            if (!phonePeConfig.isSimulationEnabled()) {
                throw new IllegalStateException(
                        "PhonePe SDK is not configured. Set PHONEPE_CLIENT_ID and PHONEPE_CLIENT_SECRET.");
            }
            redirectUrl = phonePeConfig.getRedirectUrl() + "?merchantOrderId=" + merchantOrderId + "&simulated=true";
            transaction.setRedirectUrl(redirectUrl);
            transaction.setStatus(PaymentTransaction.TransactionStatus.PENDING);
            logger.warn("⚠️ PhonePe SDK not available — using simulated payment URL (simulation-enabled=true)");
        }

        transactionRepository.save(transaction);

        // Update order payment status
        order.setPaymentStatus(Order.PaymentStatus.PENDING);
        order.setPaymentMethod(Order.PaymentMethod.PHONEPE);
        orderRepository.save(order);

        Map<String, String> result = new HashMap<>();
        result.put("merchantOrderId", merchantOrderId);
        result.put("redirectUrl", redirectUrl);
        result.put("orderId", order.getId().toString());

        return result;
    }

    // -------------------------------------------------------------------------
    //  Payment status polling — caller must own the transaction
    // -------------------------------------------------------------------------

    /**
     * Check payment status from PhonePe (or from DB when in simulation mode).
     *
     * <p><b>Ownership check:</b> The {@link PaymentTransaction} is resolved, then its order's
     * user ID is compared with {@code callingUser.getId()}. This prevents customer-A from
     * polling customer-B's transaction ID.
     *
     * @param merchantOrderId the unique merchant order ID issued at initiation time
     * @param callingUser the authenticated principal
     */
    public Map<String, Object> checkPaymentStatus(String merchantOrderId, User callingUser) {
        PaymentTransaction transaction = transactionRepository.findByMerchantOrderId(merchantOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + merchantOrderId));

        // ── OWNERSHIP CHECK ──────────────────────────────────────────────────
        if (!transaction.getOrder().getUser().getId().equals(callingUser.getId())) {
            logger.warn("⛔ User {} attempted to check status of transaction belonging to user {}",
                    callingUser.getId(), transaction.getOrder().getUser().getId());
            throw new SecurityException("Access denied: transaction does not belong to the authenticated user");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("merchantOrderId", merchantOrderId);
        result.put("orderId", transaction.getOrderId());
        result.put("amount", transaction.getAmount());
        result.put("status", transaction.getStatus().name());

        if (phonePeClient != null && transaction.getStatus() == PaymentTransaction.TransactionStatus.PENDING) {
            try {
                // Check status with PhonePe SDK
                OrderStatusResponse statusResponse = phonePeClient.getOrderStatus(merchantOrderId);
                String state = statusResponse.getState();

                logger.info("PhonePe order status for {}: {}", merchantOrderId, state);

                if ("COMPLETED".equalsIgnoreCase(state)) {
                    markPaymentSuccess(merchantOrderId, statusResponse.getOrderId());
                    result.put("status", "COMPLETED");
                } else if ("FAILED".equalsIgnoreCase(state)) {
                    markPaymentFailed(merchantOrderId, "PAYMENT_FAILED", "Payment failed at PhonePe");
                    result.put("status", "FAILED");
                } else {
                    result.put("status", state);
                }
            } catch (Exception e) {
                logger.error("Error checking PhonePe payment status: {}", e.getMessage());
                result.put("error", e.getMessage());
            }
        }

        return result;
    }

    // -------------------------------------------------------------------------
    //  Webhook / callback handler — verification is mandatory
    // -------------------------------------------------------------------------

    /**
     * Handle a PhonePe server-to-server webhook callback.
     *
     * <h3>How verification works (PhonePe SDK v2)</h3>
     * PhonePe sends an HTTP POST to the merchant's webhook URL with an
     * {@code Authorization} header of the form {@code Basic base64(username:password)}.
     * The SDK's {@link StandardCheckoutClient#validateCallback(String, String, String, String)}
     * method:
     * <ol>
     *   <li>Re-computes the expected Basic-auth value from the configured webhook credentials.</li>
     *   <li>Parses and returns the decoded {@link CallbackResponse} on success.</li>
     *   <li>Throws {@link com.phonepe.sdk.pg.common.exception.PhonePeException} on mismatch.</li>
     * </ol>
     *
     * <h3>Idempotency</h3>
     * {@link #markPaymentSuccess(String, String)} is a no-op when the transaction is already
     * {@code COMPLETED}. PhonePe may replay webhooks; the guard prevents double-processing.
     *
     * <h3>Simulation mode</h3>
     * When {@code phonepe.simulation-enabled=true} and the SDK client is {@code null},
     * the endpoint is not used (the frontend calls {@code /confirm} instead which is guarded
     * by {@code ROLE_SIMULATION} or disabled in production).  This path is never triggered
     * for real webhooks.
     *
     * @param authorization the raw {@code Authorization} header value from PhonePe
     * @param requestBody   the raw JSON body from PhonePe
     * @return {@code true} only when the callback is cryptographically verified and processed
     */
    public boolean handleCallback(String authorization, String requestBody) {
        logger.info("Received PhonePe webhook callback");

        // Simulation mode: if SDK is not configured, there are no real callbacks to handle.
        if (phonePeClient == null) {
            logger.warn("⚠️ PhonePe webhook received but SDK is not configured. Rejecting.");
            return false;
        }

        // Webhook credentials are required for verification
        String webhookUsername = phonePeConfig.getWebhookUsername();
        String webhookPassword = phonePeConfig.getWebhookPassword();

        if (webhookUsername == null || webhookUsername.isEmpty()
                || webhookPassword == null || webhookPassword.isEmpty()) {
            logger.error("❌ PhonePe webhook credentials are not configured. " +
                    "Set PHONEPE_WEBHOOK_USERNAME and PHONEPE_WEBHOOK_PASSWORD.");
            return false;
        }

        try {
            // ── SDK VERIFICATION ──────────────────────────────────────────────
            // validateCallback(username, password, authorizationHeader, responseBody)
            // Throws PhonePeException if the Authorization header doesn't match
            // the HMAC/Basic-auth computed from the configured credentials.
            CallbackResponse callbackResponse = phonePeClient.validateCallback(
                    webhookUsername,
                    webhookPassword,
                    authorization,
                    requestBody);

            if (callbackResponse == null || callbackResponse.getPayload() == null) {
                logger.error("❌ PhonePe callback validation returned null payload");
                return false;
            }

            String state = callbackResponse.getPayload().getState();
            // originalMerchantOrderId is the field PhonePe uses to identify the merchant's order
            String originalMerchantOrderId = callbackResponse.getPayload().getOriginalMerchantOrderId();
            Long callbackAmount = callbackResponse.getPayload().getAmount(); // paise

            logger.info("✅ Verified PhonePe callback: state={}, originalMerchantOrderId={}",
                    state, originalMerchantOrderId);

            if (originalMerchantOrderId == null || originalMerchantOrderId.isEmpty()) {
                logger.error("❌ Callback payload missing originalMerchantOrderId");
                return false;
            }

            // ── AMOUNT VALIDATION ─────────────────────────────────────────────
            // Validate that the amount PhonePe reports matches what we stored
            PaymentTransaction transaction = transactionRepository
                    .findByMerchantOrderId(originalMerchantOrderId)
                    .orElse(null);

            if (transaction == null) {
                logger.error("❌ No transaction found for merchantOrderId={}", originalMerchantOrderId);
                return false;
            }

            if (callbackAmount != null) {
                // Amount from PhonePe is in paise; transaction.amount is in rupees
                long expectedPaise = transaction.getAmount()
                        .multiply(BigDecimal.valueOf(100)).longValue();
                if (!callbackAmount.equals(expectedPaise)) {
                    logger.error("❌ Amount mismatch for {}: expected {} paise, callback reported {} paise",
                            originalMerchantOrderId, expectedPaise, callbackAmount);
                    return false;
                }
            }

            // ── PROCESS VERIFIED STATE ────────────────────────────────────────
            String phonePeOrderId = callbackResponse.getPayload().getOrderId();
            if ("COMPLETED".equalsIgnoreCase(state)) {
                markPaymentSuccess(originalMerchantOrderId,
                        phonePeOrderId != null ? phonePeOrderId : "WEBHOOK_" + System.currentTimeMillis());
            } else if ("FAILED".equalsIgnoreCase(state)) {
                String errorCode = callbackResponse.getPayload().getErrorCode();
                markPaymentFailed(originalMerchantOrderId,
                        errorCode != null ? errorCode : "PAYMENT_FAILED",
                        "Payment failed (PhonePe webhook)");
            } else {
                logger.info("ℹ️ Received non-terminal callback state '{}' for {} — no action taken",
                        state, originalMerchantOrderId);
            }

            return true;

        } catch (Exception e) {
            logger.error("❌ PhonePe callback verification failed: {}", e.getMessage());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    //  Dev / simulation helper — only callable when simulation is enabled
    // -------------------------------------------------------------------------

    /**
     * Confirm a simulated payment during development/testing.
     *
     * <p>This method is package-private and is only callable from
     * {@link com.kuberfashion.backend.controller.PaymentController} when
     * {@link PhonePeConfig#isSimulationEnabled()} is {@code true}.
     * Production deployments must have {@code phonepe.simulation-enabled=false}
     * (the default) so the HTTP endpoint returns 404/403.
     *
     * @param merchantOrderId the simulated merchant order ID
     * @param transactionId   an arbitrary transaction ID for the simulation record
     */
    public void confirmSimulatedPayment(String merchantOrderId, String transactionId) {
        if (!phonePeConfig.isSimulationEnabled()) {
            throw new IllegalStateException(
                    "Simulation mode is disabled. Set phonepe.simulation-enabled=true in dev configuration only.");
        }
        markPaymentSuccess(merchantOrderId, transactionId);
    }

    /**
     * Mark a simulated payment failed during development/testing.
     *
     * @see #confirmSimulatedPayment(String, String)
     */
    public void failSimulatedPayment(String merchantOrderId, String errorCode, String errorMessage) {
        if (!phonePeConfig.isSimulationEnabled()) {
            throw new IllegalStateException(
                    "Simulation mode is disabled. Set phonepe.simulation-enabled=true in dev configuration only.");
        }
        markPaymentFailed(merchantOrderId, errorCode, errorMessage);
    }

    // -------------------------------------------------------------------------
    //  Internal state-transition methods — not exposed directly to HTTP
    // -------------------------------------------------------------------------

    /**
     * Mark payment as successful.
     *
     * <h3>Idempotency guard</h3>
     * If the transaction is already {@code COMPLETED}, this method logs a warning and
     * returns immediately without modifying any state. This is the primary defence
     * against replayed webhooks causing double referral awards or double cart-clears.
     *
     * @param merchantOrderId the merchant-generated order ID
     * @param transactionId   the PhonePe-assigned transaction ID (from callback or status poll)
     */
    public void markPaymentSuccess(String merchantOrderId, String transactionId) {
        PaymentTransaction transaction = transactionRepository.findByMerchantOrderId(merchantOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + merchantOrderId));

        // ── IDEMPOTENCY & TERMINAL STATE GUARD ──────────────────────────────
        // If this transaction was already completed (e.g. a replayed webhook), do nothing.
        if (transaction.getStatus() == PaymentTransaction.TransactionStatus.COMPLETED) {
            logger.warn("⚠️ Ignoring duplicate success callback for already-completed transaction: {}",
                    merchantOrderId);
            return;
        }

        // A transaction that already reached terminal state FAILED cannot be marked COMPLETED.
        // Once failed, its stock was already returned to the available inventory pool.
        if (transaction.getStatus() == PaymentTransaction.TransactionStatus.FAILED) {
            logger.error("❌ Refusing to mark transaction {} COMPLETED: already in terminal state FAILED",
                    merchantOrderId);
            return;
        }

        transaction.setStatus(PaymentTransaction.TransactionStatus.COMPLETED);
        transaction.setPhonePeTransactionId(transactionId);
        transactionRepository.save(transaction);

        // Update order
        Order order = transaction.getOrder();
        order.setPaymentStatus(Order.PaymentStatus.PAID);
        order.setPaymentTransactionId(transactionId);
        order.setPaidAt(LocalDateTime.now());
        order.setStatus(Order.OrderStatus.CONFIRMED);
        orderRepository.save(order);

        // Process referral ONLY after successful payment
        String referralCode = order.getReferralCode();
        if (referralCode != null && !referralCode.trim().isEmpty()) {
            try {
                referralService.handlePostRegistration(order.getUser(), referralCode);
                logger.info("✅ Referral processed for user {} with code {}",
                        order.getUser().getPhone(), referralCode);
            } catch (Exception e) {
                logger.error("❌ Failed to process referral for order {}: {}",
                        order.getOrderNumber(), e.getMessage());
                // Don't fail the payment confirmation — referral is secondary
            }
        }

        logger.info("✅ Payment marked successful for order: {}", order.getOrderNumber());
    }

    /**
     * Mark payment as failed.
     *
     * <p>Idempotency: if the transaction is already {@code COMPLETED}, the failure is ignored
     * (a completed payment cannot be retroactively failed by a late-arriving error callback).
     *
     * @param merchantOrderId the merchant-generated order ID
     * @param errorCode       the PhonePe error code
     * @param errorMessage    a human-readable description
     */
    public void markPaymentFailed(String merchantOrderId, String errorCode, String errorMessage) {
        PaymentTransaction transaction = transactionRepository.findByMerchantOrderId(merchantOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + merchantOrderId));

        // Don't override a completed payment with a late-arriving failure
        if (transaction.getStatus() == PaymentTransaction.TransactionStatus.COMPLETED) {
            logger.warn("⚠️ Ignoring FAILED callback for already-completed transaction: {}", merchantOrderId);
            return;
        }

        // Idempotency: if already FAILED, ignore duplicate failure callback
        if (transaction.getStatus() == PaymentTransaction.TransactionStatus.FAILED) {
            logger.warn("⚠️ Ignoring duplicate FAILED callback for already-failed transaction: {}", merchantOrderId);
            return;
        }

        transaction.setStatus(PaymentTransaction.TransactionStatus.FAILED);
        transaction.setErrorCode(errorCode);
        transaction.setErrorMessage(errorMessage);
        transactionRepository.save(transaction);

        // ── RESTORE STOCK ON PAYMENT FAILURE ──────────────────────────────────
        // Stock was reserved at order creation. If payment fails, return the reserved
        // stock to the products so other customers can buy them, and transition order to CANCELLED.
        Order order = transaction.getOrder();
        order.setPaymentStatus(Order.PaymentStatus.FAILED);

        if (order.getStatus() == Order.OrderStatus.PENDING) {
            if (order.getOrderItems() != null) {
                for (OrderItem item : order.getOrderItems()) {
                    orderService.restoreStockForOrderItem(item);
                }
            }
            order.setStatus(Order.OrderStatus.CANCELLED);
            order.setCancelledAt(LocalDateTime.now());
        }

        orderRepository.save(order);

        logger.info("❌ Payment marked failed for order: {}", order.getOrderNumber());
    }
}
