package io.aegis.db.resilience.retry;

import io.aegis.db.resilience.annotation.RetryPolicy;
import io.aegis.db.resilience.autoconfigure.DatabaseResilienceProperties;
import io.aegis.db.resilience.domain.DataTimeoutException;
import io.aegis.db.resilience.domain.DataUnavailableException;
import io.aegis.db.resilience.domain.TransientDataOperationException;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.Map;

/**
 * Builds {@link RetryTemplate} instances. Shared globally (one per default config) or
 * per-invocation when a {@link RetryPolicy} annotation overrides defaults.
 */
public class RetryTemplateFactory {

    private final DatabaseResilienceProperties props;

    public RetryTemplateFactory(DatabaseResilienceProperties props) {
        this.props = props;
    }

    /** Returns the globally configured template. Thread-safe; safe to share. */
    public RetryTemplate global() {
        return build(
                props.getRetry().getMaxAttempts(),
                props.getRetry().getBackoffMs(),
                props.getRetry().getMaxBackoffMs(),
                props.getRetry().getMultiplier()
        );
    }

    /**
     * Returns a template applying annotation overrides on top of the global defaults.
     * Annotation value of {@code -1} means "use global default".
     */
    public RetryTemplate forPolicy(RetryPolicy policy) {
        DatabaseResilienceProperties.RetryProperties defaults = props.getRetry();
        return build(
                policy.maxAttempts()  == -1  ? defaults.getMaxAttempts()  : policy.maxAttempts(),
                policy.backoffMs()    == -1  ? defaults.getBackoffMs()    : policy.backoffMs(),
                policy.maxBackoffMs() == -1  ? defaults.getMaxBackoffMs() : policy.maxBackoffMs(),
                policy.multiplier()   == -1  ? defaults.getMultiplier()   : policy.multiplier()
        );
    }

    private RetryTemplate build(
            int maxAttempts, long backoffMs, long maxBackoffMs, double multiplier) {

        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                maxAttempts,
                Map.of(
                        TransientDataOperationException.class, true,
                        DataTimeoutException.class,            true,
                        DataUnavailableException.class,        true
                ),
                true  // traverse cause chain
        );

        ExponentialRandomBackOffPolicy backOff = new ExponentialRandomBackOffPolicy();
        backOff.setInitialInterval(backoffMs);
        backOff.setMaxInterval(maxBackoffMs);
        backOff.setMultiplier(multiplier);

        return RetryTemplate.builder()
                .customPolicy(retryPolicy)
                .customBackoff(backOff)
                .build();
    }
}
