package io.aegis.db.resilience.aspect;

import io.aegis.db.resilience.annotation.RetryPolicy;
import io.aegis.db.resilience.classification.ClassificationResult;
import io.aegis.db.resilience.classification.DatabaseExceptionClassifier;
import io.aegis.db.resilience.domain.*;
import io.aegis.db.resilience.observability.DatabaseOperationMetrics;
import io.aegis.db.resilience.retry.RetryTemplateFactory;
import io.vavr.control.Either;
import io.vavr.control.Option;
import io.vavr.control.Try;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;

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

    /**
     * Constructs a new interceptor with the required resilience components.
     *
     * @param classifiers          ordered list of exception translation strategies
     * @param metrics              meter registry wrapper for observability
     * @param retryTemplateFactory factory for generating policy-aware retry templates
     */
    public DatabaseResilienceInterceptor(
            List<DatabaseExceptionClassifier> classifiers,
            DatabaseOperationMetrics metrics,
            RetryTemplateFactory retryTemplateFactory) {
        this.classifiers          = classifiers;
        this.metrics              = metrics;
        this.retryTemplateFactory = retryTemplateFactory;
    }

    /**
     * Intercepts a method invocation to apply the resilience pipeline.
     * Uses Vavr's {@link Try} to manage the retry logic and map exceptions.
     * Supports methods returning {@link Either} by wrapping the failure in {@link Either#left(Object)}.
     *
     * @param invocation the method invocation being intercepted
     * @return the result of the method call, or an {@link Either#left(Object)} if it failed and returns Either
     * @throws Throwable the classified domain exception or the raw failure if classification fails
     */
    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        RetryTemplate retryTemplate = resolveRetryTemplate(invocation);
        boolean[] retried = {false};
        boolean isEither = Either.class.isAssignableFrom(invocation.getMethod().getReturnType());

        return Try.of(() -> retryTemplate.execute(ctx -> {
                    if (ctx.getRetryCount() > 0) retried[0] = true;
                    return Try.of(invocation::proceed)
                            .getOrElseThrow(ex -> classify(ex, invocation, retried[0]));
                }))
                .recoverWith(DataOperationException.class, ex -> isEither 
                        ? Try.success(Either.left(ex)) 
                        : Try.failure(ex))
                .get();
    }

    private DataOperationException classify(
            Throwable raw, MethodInvocation invocation, boolean wasRetried) {

        if (raw instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return DataAccessProgrammingException.of(
                    raw, null, 0, 
                    invocation.getMethod().getName(), "unknown");
        }

        String operation  = invocation.getMethod().getName();
        String repository = Option.of(invocation.getThis())
                .map(Object::getClass)
                .map(Class::getSimpleName)
                .getOrElse("unknown");

        return io.vavr.collection.Stream.ofAll(Option.of(classifiers).getOrElse(List.of()))
                .map(c -> c.classify(raw, operation, repository))
                .filter(Objects::nonNull)
                .headOption()
                .map(result -> Option.of(result)
                        .filter(ClassificationResult::retryable)
                        .filter(r -> TransactionSynchronizationManager.isActualTransactionActive())
                        .map(r -> new ClassificationResult(r.domainException(), r.category(), false))
                        .getOrElse(result))
                .peek(r -> Option.of(metrics).forEach(m -> m.record(r.domainException(), r.category(), wasRetried)))
                .map(ClassificationResult::domainException)
                .getOrElseThrow(() -> new IllegalStateException(
                        "No classifier handled throwable: " + raw.getClass().getName(), raw));
    }

    private RetryTemplate resolveRetryTemplate(MethodInvocation invocation) {
        return Option.of(invocation.getMethod())
                .flatMap(m -> Option.of(AnnotationUtils.findAnnotation(m, RetryPolicy.class)))
                .filter(p -> !p.disabled())
                .orElse(() -> Option.of(invocation.getThis())
                        .map(Object::getClass)
                        .flatMap(c -> Option.of(AnnotationUtils.findAnnotation(c, RetryPolicy.class)))
                        .filter(p -> !p.disabled()))
                .map(retryTemplateFactory::forPolicy)
                .getOrElse(retryTemplateFactory::global);
    }
}
