package io.aegis.db.resilience.annotation;

import java.lang.annotation.*;

/**
 * Override retry parameters at the class or method level.
 * Defaults in {@code application.yml} apply when this annotation is absent.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RetryPolicy {

    /**
     * Maximum number of attempts (including the first).
     * Set to -1 to use the global default.
     *
     * @return the maximum number of attempts
     */
    int maxAttempts() default -1;

    /**
     * Base backoff in milliseconds.
     * Set to -1 to use the global default.
     *
     * @return the base backoff in ms
     */
    long backoffMs() default -1;

    /**
     * Maximum backoff cap in milliseconds.
     * Set to -1 to use the global default.
     *
     * @return the maximum backoff cap in ms
     */
    long maxBackoffMs() default -1;

    /**
     * Backoff multiplier (exponential).
     * Set to -1 to use the global default.
     *
     * @return the backoff multiplier
     */
    double multiplier() default -1;

    /**
     * Disable retry entirely for this bean/method regardless of global config.
     *
     * @return {@code true} if retry is disabled, {@code false} otherwise
     */
    boolean disabled() default false;
}
