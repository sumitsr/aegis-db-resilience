package io.aegis.db.resilience.aspect;

import io.aegis.db.resilience.annotation.RetryPolicy;
import io.aegis.db.resilience.classification.ClassificationResult;
import io.aegis.db.resilience.classification.DatabaseExceptionClassifier;
import io.aegis.db.resilience.domain.DataOperationException;
import io.aegis.db.resilience.observability.DatabaseOperationMetrics;
import io.aegis.db.resilience.retry.RetryTemplateFactory;
import jakarta.persistence.PersistenceException;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Core resilience interceptor that can be used both from the {@link DatabaseResilienceAspect}
 * (AspectJ pointcut path) and from a Spring AOP {@link org.springframework.aop.Advisor}
 * (global auto-apply path). Keeping the logic here prevents duplication.
 *
 * <p>See {@link DatabaseResilienceAspect} for the ordering and retry-safety rationale.
 */
public class DatabaseResilienceInterceptor implements MethodInterceptor {

    private final List<DatabaseExceptionClassifier> classifiers;
    private final DatabaseOperationMetrics metrics;
    private final RetryTemplateFactory retryTemplateFactory;

    public DatabaseResilienceInterceptor(
            List<DatabaseExceptionClassifier> classifiers,
            DatabaseOperationMetrics metrics,
            RetryTemplateFactory retryTemplateFactory) {
        this.classifiers          = classifiers;
        this.metrics              = metrics;
        this.retryTemplateFactory = retryTemplateFactory;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        RetryTemplate retryTemplate = resolveRetryTemplate(invocation);
        boolean[] retried = {false};

        try {
            return retryTemplate.execute(ctx -> {
                if (ctx.getRetryCount() > 0) retried[0] = true;
                try {
                    return invocation.proceed();
                } catch (DataAccessException | PersistenceException ex) {
                    throw classify(ex, invocation, retried[0]);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during database operation", ex);
                } catch (Throwable ex) {
                    throw classify(ex, invocation, retried[0]);
                }
            });
        } catch (DataOperationException ex) {
            throw ex; // already classified and metered
        }
    }

    private DataOperationException classify(
            Throwable raw, MethodInvocation invocation, boolean wasRetried) {

        String operation  = invocation.getMethod().getName();
        String repository = invocation.getThis() != null
                ? invocation.getThis().getClass().getSimpleName()
                : "unknown";

        ClassificationResult result = classifiers.stream()
                .map(c -> c.classify(raw, operation, repository))
                .filter(r -> r != null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No classifier handled throwable: " + raw.getClass().getName()));

        // Never retry inside an active transaction — a rolled-back EntityManager is poisoned.
        if (result.retryable() && TransactionSynchronizationManager.isActualTransactionActive()) {
            ClassificationResult downgraded = new ClassificationResult(
                    result.domainException(), result.category(), false);
            metrics.record(downgraded.domainException(), downgraded.category(), wasRetried);
            return downgraded.domainException();
        }

        metrics.record(result.domainException(), result.category(), wasRetried);
        return result.domainException();
    }

    private RetryTemplate resolveRetryTemplate(MethodInvocation invocation) {
        RetryPolicy methodPolicy = AnnotationUtils.findAnnotation(
                invocation.getMethod(), RetryPolicy.class);
        if (methodPolicy != null && !methodPolicy.disabled()) {
            return retryTemplateFactory.forPolicy(methodPolicy);
        }

        if (invocation.getThis() != null) {
            RetryPolicy typePolicy = AnnotationUtils.findAnnotation(
                    invocation.getThis().getClass(), RetryPolicy.class);
            if (typePolicy != null && !typePolicy.disabled()) {
                return retryTemplateFactory.forPolicy(typePolicy);
            }
        }

        return retryTemplateFactory.global();
    }
}
