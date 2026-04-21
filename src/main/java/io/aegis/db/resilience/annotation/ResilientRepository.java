package io.aegis.db.resilience.annotation;

import java.lang.annotation.*;

/**
 * Meta-annotation that activates the full database resilience pipeline:
 * exception classification, structured logging, Micrometer metrics, OpenTelemetry span
 * events, and configurable retry with exponential back-off.
 *
 * <p>When {@code DatabaseResilienceAutoConfiguration} is active (default), this annotation
 * is <em>not</em> required — all {@code @Repository} and {@code @Service} beans in the
 * configured base packages are covered automatically. Use this annotation only when you
 * need per-class overrides of the retry policy.
 *
 * <pre>{@code
 * @ResilientRepository
 * @Repository
 * public class OrderRepository { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ResilientRepository {

    /**
     * Optional method-level retry override. When specified on the type, applies to all methods.
     * Individual method-level {@link RetryPolicy} annotations take precedence.
     */
    RetryPolicy retryPolicy() default @RetryPolicy;
}
