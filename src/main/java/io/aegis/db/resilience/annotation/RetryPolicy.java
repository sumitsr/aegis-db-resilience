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

    /** Maximum number of attempts (including the first). */
    int maxAttempts() default -1;  // -1 = use global default

    /** Base backoff in milliseconds. */
    long backoffMs() default -1;

    /** Maximum backoff cap in milliseconds. */
    long maxBackoffMs() default -1;

    /** Backoff multiplier (exponential). */
    double multiplier() default -1;

    /** Disable retry entirely for this bean/method regardless of global config. */
    boolean disabled() default false;
}
