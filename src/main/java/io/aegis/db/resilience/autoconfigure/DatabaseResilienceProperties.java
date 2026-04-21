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

    public boolean isAutoApply() { return autoApply; }
    public void setAutoApply(boolean autoApply) { this.autoApply = autoApply; }

    public List<String> getBasePackages() { return basePackages; }
    public void setBasePackages(List<String> basePackages) { this.basePackages = basePackages; }

    public RetryProperties getRetry() { return retry; }

    public static class RetryProperties {
        /** Total attempts including the first. */
        private int    maxAttempts  = 3;
        /** Initial backoff in ms. */
        private long   backoffMs    = 200;
        /** Backoff cap in ms. */
        private long   maxBackoffMs = 2000;
        /** Exponential multiplier. */
        private double multiplier   = 2.0;

        public int    getMaxAttempts()  { return maxAttempts; }
        public long   getBackoffMs()    { return backoffMs; }
        public long   getMaxBackoffMs() { return maxBackoffMs; }
        public double getMultiplier()   { return multiplier; }

        public void setMaxAttempts(int maxAttempts)       { this.maxAttempts  = maxAttempts; }
        public void setBackoffMs(long backoffMs)           { this.backoffMs    = backoffMs; }
        public void setMaxBackoffMs(long maxBackoffMs)     { this.maxBackoffMs = maxBackoffMs; }
        public void setMultiplier(double multiplier)       { this.multiplier   = multiplier; }
    }
}
