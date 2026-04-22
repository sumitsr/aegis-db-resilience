package io.aegis.db.resilience.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * AspectJ aspect that activates the resilience pipeline for beans explicitly annotated
 * with {@link io.aegis.db.resilience.annotation.ResilientRepository}.
 *
 * <p>Delegates entirely to {@link DatabaseResilienceInterceptor} to keep the core logic
 * in one place (shared with the global auto-apply advisor).
 *
 * <p><strong>Ordering:</strong> {@code LOWEST_PRECEDENCE - 200} places this aspect
 * <em>outside</em> Spring's {@code TransactionInterceptor} (default {@code LOWEST_PRECEDENCE}),
 * so the aspect sees the committed/rolled-back result of each call, including any
 * {@code UnexpectedRollbackException} produced by the transaction proxy.
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 200)
public class DatabaseResilienceAspect {

    private final DatabaseResilienceInterceptor interceptor;

    /**
     * Constructs the aspect with the shared interceptor.
     *
     * @param interceptor the interceptor containing the resilience logic
     */
    public DatabaseResilienceAspect(DatabaseResilienceInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    /**
     * Around advice that intercepts methods annotated with or within a class 
     * annotated with {@code @ResilientRepository}.
     *
     * @param pjp the AspectJ proceeding join point
     * @return the result of the invocation
     * @throws Throwable if the underlying call or classification fails
     */
    @Around("@within(io.aegis.db.resilience.annotation.ResilientRepository)" +
            " || @annotation(io.aegis.db.resilience.annotation.ResilientRepository)")
    public Object intercept(ProceedingJoinPoint pjp) throws Throwable {
        return interceptor.invoke(new ProceedingJoinPointMethodInvocation(pjp));
    }
}
