package io.aegis.db.resilience.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Externalized configuration prefix: {@code aegis.db.resilience}.
 */
@ConfigurationProperties(prefix = "aegis.db.resilience")
public class DatabaseResilienceProperties {

    /** Automatically apply the resilience aspect to all @Repository and @Service beans. */
    private boolean autoApply = true;

    /**
     * Base packages scanned when {@code autoApply=true}. Empty list = entire application context.
     */
    private List<String> basePackages = List.of();

    private final RetryProperties retry = new RetryProperties();
    private final ObservabilityProperties observability = new ObservabilityProperties();

    /**
     * Checks if auto-application to {@code @Repository} and {@code @Service} beans is enabled.
     *
     * @return {@code true} if auto-apply is enabled
     */
    public boolean isAutoApply() { return autoApply; }

    /**
     * Toggles the auto-application of the resilience aspect.
     *
     * @param autoApply whether to enable auto-apply
     */
    public void setAutoApply(boolean autoApply) { this.autoApply = autoApply; }

    /**
     * Returns the list of base packages scanned for auto-application.
     *
     * @return the list of base packages
     */
    public List<String> getBasePackages() { return basePackages; }

    /**
     * Sets the base packages to scan. If empty, the entire context is scanned.
     *
     * @param basePackages the base packages to scan
     */
    public void setBasePackages(List<String> basePackages) { this.basePackages = basePackages; }

    /**
     * Returns the retry configuration properties.
     *
     * @return the retry properties
     */
    public RetryProperties getRetry() { return retry; }

    /**
     * Returns the observability configuration properties.
     *
     * @return the observability properties
     */
    public ObservabilityProperties getObservability() { return observability; }

    /**
     * Configuration for exponential back-off retries.
     */
    public static class RetryProperties {
        /** Total attempts including the first. */
        private int    maxAttempts  = 3;
        /** Initial backoff in ms. */
        private long   backoffMs    = 200;
        /** Backoff cap in ms. */
        private long   maxBackoffMs = 2000;
        /** Exponential multiplier. */
        private double multiplier   = 2.0;

        /** @return the maximum number of attempts */
        public int    getMaxAttempts()  { return maxAttempts; }
        /** @return the initial backoff in ms */
        public long   getBackoffMs()    { return backoffMs; }
        /** @return the maximum backoff cap in ms */
        public long   getMaxBackoffMs() { return maxBackoffMs; }
        /** @return the backoff multiplier */
        public double getMultiplier()   { return multiplier; }

        /** @param maxAttempts the maximum number of attempts */
        public void setMaxAttempts(int maxAttempts)       { this.maxAttempts  = maxAttempts; }
        /** @param backoffMs the initial backoff in ms */
        public void setBackoffMs(long backoffMs)           { this.backoffMs    = backoffMs; }
        /** @param maxBackoffMs the maximum backoff cap in ms */
        public void setMaxBackoffMs(long maxBackoffMs)     { this.maxBackoffMs = maxBackoffMs; }
        /** @param multiplier the backoff multiplier */
        public void setMultiplier(double multiplier)       { this.multiplier   = multiplier; }
    }

    /**
     * Configuration for observability features.
     */
    public static class ObservabilityProperties {
        /** Whether to enable metrics, tracing enrichment, and structured logging. */
        private boolean enabled = true;

        /** @return {@code true} if observability is enabled */
        public boolean isEnabled() { return enabled; }
        /** @param enabled whether to enable observability */
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
