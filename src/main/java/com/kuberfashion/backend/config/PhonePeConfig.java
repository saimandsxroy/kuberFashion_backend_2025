package com.kuberfashion.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * PhonePe Payment Gateway Configuration.
 *
 * <p>Configure these values via environment variables or profile-specific properties files:
 * <pre>
 * PHONEPE_CLIENT_ID=your_client_id
 * PHONEPE_CLIENT_SECRET=your_client_secret
 * PHONEPE_CLIENT_VERSION=1
 * PHONEPE_ENVIRONMENT=SANDBOX or PRODUCTION
 * PHONEPE_REDIRECT_URL=https://yourdomain.com/payment/status
 * PHONEPE_WEBHOOK_USERNAME=webhook_username
 * PHONEPE_WEBHOOK_PASSWORD=webhook_password
 * </pre>
 *
 * <h3>Simulation mode</h3>
 * {@code phonepe.simulation-enabled} (default: {@code false}) controls whether the
 * {@code /api/payments/confirm} and {@code /api/payments/fail} simulation endpoints are active.
 * This flag MUST be {@code false} in production. Enable it only in the
 * {@code application-dev.properties} file when PhonePe credentials are unavailable during
 * local development.
 */
@Configuration
public class PhonePeConfig {

    @Value("${phonepe.client-id:}")
    private String clientId;

    @Value("${phonepe.client-secret:}")
    private String clientSecret;

    @Value("${phonepe.client-version:1}")
    private String clientVersion;

    @Value("${phonepe.environment:SANDBOX}")
    private String environment;

    @Value("${phonepe.redirect-url:http://localhost:5173/payment/status}")
    private String redirectUrl;

    @Value("${phonepe.webhook-username:}")
    private String webhookUsername;

    @Value("${phonepe.webhook-password:}")
    private String webhookPassword;

    /**
     * Whether the simulation endpoints (/confirm, /fail) are accessible.
     * MUST be false in production. Only set to true in application-dev.properties.
     */
    @Value("${phonepe.simulation-enabled:false}")
    private boolean simulationEnabled;

    // ── Getters ──────────────────────────────────────────────────────────────

    public String getClientId() {
        return clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public String getEnvironment() {
        return environment;
    }

    public String getRedirectUrl() {
        return redirectUrl;
    }

    public String getWebhookUsername() {
        return webhookUsername;
    }

    public String getWebhookPassword() {
        return webhookPassword;
    }

    public boolean isSimulationEnabled() {
        return simulationEnabled;
    }

    // ── Helper predicates ─────────────────────────────────────────────────────

    /**
     * Returns {@code true} when both {@code clientId} and {@code clientSecret} are non-blank,
     * indicating that the PhonePe SDK can be initialised.
     */
    public boolean isConfigured() {
        return clientId != null && !clientId.isEmpty()
                && clientSecret != null && !clientSecret.isEmpty();
    }

    public boolean isSandbox() {
        return "SANDBOX".equalsIgnoreCase(environment);
    }
}
