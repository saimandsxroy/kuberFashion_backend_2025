package com.kuberfashion.backend.service;

import com.kuberfashion.backend.config.PhonePeConfig;
import com.kuberfashion.backend.dto.ReconciliationSummary;
import com.kuberfashion.backend.entity.Order;
import com.kuberfashion.backend.entity.PaymentTransaction;
import com.kuberfashion.backend.repository.OrderRepository;
import com.kuberfashion.backend.repository.PaymentTransactionRepository;
import com.phonepe.sdk.pg.common.models.response.OrderStatusResponse;
import com.phonepe.sdk.pg.payments.v2.StandardCheckoutClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Service responsible for safe, reliable reconciliation and recovery of abandoned
 * or ambiguous online PhonePe payments.
 *
 * <h3>Design & Safety Invariants</h3>
 * <ul>
 *   <li><b>Payment Method Scoping</b>: Strictly targets {@code PaymentMethod.PHONEPE}.
 *       {@code CASH_ON_DELIVERY} orders are NEVER expired/cancelled as unpaid.</li>
 *   <li><b>Non-blocking I/O</b>: Does NOT hold database transactions or row locks
 *       open while waiting for PhonePe's HTTP status API.</li>
 *   <li><b>Active Reconciliation</b>: Checks PhonePe's authoritative status before
 *       cancelling an order. If the customer completed payment, it is marked PAID
 *       and CONFIRMED.</li>
 *   <li><b>Race Condition Safety</b>: All final state transitions occur under
 *       pessimistic locks. If a webhook confirms the payment while reconciliation is
 *       running, reconciliation sees {@code COMPLETED} and aborts cancellation.</li>
 *   <li><b>At-Most-Once Stock Restoration</b>: Terminal state guards ensure inventory
 *       units are returned to the shelf exactly once.</li>
 *   <li><b>Ambiguity Deferral</b>: If PhonePe is down (5xx/timeout), the order is
 *       NOT cancelled; it remains PENDING for the next reconciliation cycle.</li>
 * </ul>
 */
@Service
public class PaymentReconciliationService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentReconciliationService.class);

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private PhonePeConfig phonePeConfig;

    /**
     * Reconcile all pending PhonePe orders created before the specified cutoff minutes.
     *
     * @param timeoutMinutes orders older than this threshold are evaluated (e.g. 15 or 30 minutes)
     * @return summary of actions taken
     */
    public ReconciliationSummary reconcilePendingOrders(int timeoutMinutes) {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(timeoutMinutes);
        logger.info("🔍 Starting PhonePe payment reconciliation for orders older than {} minutes (cutoff: {})",
                timeoutMinutes, cutoff);

        // ── STEP 1: Lightweight query (read-only) ─────────────────────────────
        // Query candidate orders that are PHONEPE, PENDING, and older than cutoff.
        // We do NOT lock rows here to prevent long-lived DB locks during external calls.
        List<Order> candidates = orderRepository.findPendingOrdersForReconciliation(
                Order.PaymentMethod.PHONEPE,
                Order.OrderStatus.PENDING,
                Order.PaymentStatus.PENDING,
                cutoff);

        ReconciliationSummary summary = new ReconciliationSummary();
        summary.setCandidateCount(candidates.size());

        for (Order candidate : candidates) {
            try {
                reconcileSingleOrder(candidate.getId(), summary);
            } catch (Exception e) {
                logger.error("❌ Error reconciling order {}: {}", candidate.getOrderNumber(), e.getMessage());
                summary.recordSkipped(candidate.getOrderNumber(), "Error: " + e.getMessage());
            }
        }

        logger.info("🏁 Reconciliation completed: {} evaluated, {} confirmed, {} expired/cancelled, {} skipped/deferred",
                summary.getCandidateCount(), summary.getConfirmedCount(),
                summary.getExpiredCount(), summary.getSkippedCount());

        return summary;
    }

    private void reconcileSingleOrder(Long orderId, ReconciliationSummary summary) {
        // Find latest payment transaction for this order
        Optional<PaymentTransaction> optTx = transactionRepository.findTopByOrderIdOrderByCreatedAtDesc(orderId);

        if (optTx.isEmpty()) {
            // Customer clicked createOrder, but abandoned before initiating payment on PhonePe
            logger.info("📦 Order id={} was abandoned before payment initiation. Cancelling order and releasing stock.", orderId);
            cancelAbandonedOrderWithoutPayment(orderId);
            summary.recordExpired(orderId.toString(), "Abandoned before payment initiation");
            return;
        }

        PaymentTransaction tx = optTx.get();
        String merchantOrderId = tx.getMerchantOrderId();

        // ── STEP 2: Call PhonePe SDK OUTSIDE Database Transaction ─────────────
        StandardCheckoutClient client = paymentService.getPhonePeClient();
        if (client == null) {
            if (phonePeConfig.isSimulationEnabled()) {
                // In simulation mode (dev/test), expire simulated pending orders
                logger.info("ℹ️ Simulation mode active: expiring pending transaction {}", merchantOrderId);
                paymentService.failSimulatedPayment(merchantOrderId, "SIMULATED_EXPIRED", "Payment expired in simulation");
                summary.recordExpired(merchantOrderId, "Simulated expiration");
                return;
            } else {
                logger.warn("⚠️ PhonePe SDK not configured and simulation disabled. Skipping order id={}", orderId);
                summary.recordSkipped(merchantOrderId, "SDK not configured");
                return;
            }
        }

        String phonePeState = null;
        String phonePeTxnId = null;

        try {
            OrderStatusResponse response = client.getOrderStatus(merchantOrderId);
            phonePeState = response.getState();
            phonePeTxnId = response.getOrderId();
            logger.info("📡 PhonePe reported status for {}: state={}, phonePeTxnId={}",
                    merchantOrderId, phonePeState, phonePeTxnId);
        } catch (Exception e) {
            // ── AMBIGUOUS OUTCOME PROTECTION ──────────────────────────────────
            // If PhonePe API is unreachable or returns an error, DO NOT CANCEL.
            // Cancelling on network error risks cancelling a customer who paid!
            logger.warn("⚠️ PhonePe status API failed for {}: {}. Deferring reconciliation to avoid false cancellation.",
                    merchantOrderId, e.getMessage());
            summary.recordSkipped(merchantOrderId, "Gateway API error: " + e.getMessage());
            return;
        }

        // ── STEP 3: Execute State Transition under Short Atomic Transaction ───
        if ("COMPLETED".equalsIgnoreCase(phonePeState)) {
            paymentService.markPaymentSuccess(merchantOrderId, phonePeTxnId != null ? phonePeTxnId : "RECONCILED_" + System.currentTimeMillis());
            summary.recordConfirmed(merchantOrderId, "Verified COMPLETED at gateway");
        } else if ("FAILED".equalsIgnoreCase(phonePeState)) {
            paymentService.markPaymentFailed(merchantOrderId, "PHONEPE_FAILED", "Verified FAILED at gateway");
            summary.recordExpired(merchantOrderId, "Verified FAILED at gateway");
        } else if ("PENDING".equalsIgnoreCase(phonePeState)) {
            // If gateway still says PENDING after the max window, gateway session is expired
            paymentService.markPaymentFailed(merchantOrderId, "GATEWAY_TIMEOUT", "Payment session expired at gateway");
            summary.recordExpired(merchantOrderId, "Gateway session timeout");
        } else {
            summary.recordSkipped(merchantOrderId, "Unknown gateway state: " + phonePeState);
        }
    }

    @Transactional
    public void cancelAbandonedOrderWithoutPayment(Long orderId) {
        Order order = orderRepository.findByIdWithOrderItemsAndProduct(orderId).orElse(null);
        if (order == null || order.getStatus() != Order.OrderStatus.PENDING) {
            return;
        }
        order.setStatus(Order.OrderStatus.CANCELLED);
        order.setPaymentStatus(Order.PaymentStatus.FAILED);
        order.setCancelledAt(LocalDateTime.now());
        order.setNotes("Expired: abandoned prior to payment initiation");
        // Restore stock
        if (order.getOrderItems() != null) {
            for (com.kuberfashion.backend.entity.OrderItem item : order.getOrderItems()) {
                orderService.restoreStockForOrderItem(item);
            }
        }
        orderRepository.save(order);
    }
}
