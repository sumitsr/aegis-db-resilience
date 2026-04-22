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

    /**
     * Constructs a factory with the provided global properties.
     *
     * @param props the global configuration properties
     */
    public RetryTemplateFactory(DatabaseResilienceProperties props) {
        this.props = props;
    }

    /**
     * Returns the globally configured {@link RetryTemplate}. 
     * This template is thread-safe and safe to share across threads.
     *
     * @return the global retry template
     */
    public RetryTemplate global() {
        return build(
                props.getRetry().getMaxAttempts(),
                props.getRetry().getBackoffMs(),
                props.getRetry().getMaxBackoffMs(),
                props.getRetry().getMultiplier()
        );
    }

    /**
     * Returns a {@link RetryTemplate} that applies annotation-level overrides 
     * on top of the global defaults. An annotation value of {@code -1} signifies 
     * that the global default should be used for that parameter.
     *
     * @param policy the retry policy annotation containing overrides
     * @return a localized retry template
     */
    public RetryTemplate forPolicy(RetryPolicy policy) {
        return io.vavr.control.Option.of(props.getRetry())
                .map(defaults -> build(
                        policy.maxAttempts()  == -1  ? defaults.getMaxAttempts()  : policy.maxAttempts(),
                        policy.backoffMs()    == -1  ? defaults.getBackoffMs()    : policy.backoffMs(),
                        policy.maxBackoffMs() == -1  ? defaults.getMaxBackoffMs() : policy.maxBackoffMs(),
                        policy.multiplier()   == -1  ? defaults.getMultiplier()   : policy.multiplier()
                ))
                .get();
    }

    private RetryTemplate build(
            int maxAttempts, long backoffMs, long maxBackoffMs, double multiplier) {

        return io.vavr.control.Option.of(new ExponentialRandomBackOffPolicy())
                .peek(backOff -> backOff.setInitialInterval(backoffMs))
                .peek(backOff -> backOff.setMaxInterval(maxBackoffMs))
                .peek(backOff -> backOff.setMultiplier(multiplier))
                .map(backOff -> RetryTemplate.builder()
                        .customPolicy(new SimpleRetryPolicy(
                                maxAttempts,
                                Map.of(
                                        TransientDataOperationException.class, true,
                                        DataTimeoutException.class,            true,
                                        DataUnavailableException.class,        true
                                ),
                                true
                        ))
                        .customBackoff(backOff)
                        .build())
                .get();
    }
}
