package io.aegis.db.resilience.observability;

import io.aegis.db.resilience.classification.ExceptionCategory;
import io.aegis.db.resilience.domain.DataOperationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.vavr.API.*;
import static io.vavr.Predicates.*;

/**
 * Centralises all observability side-effects for a database failure:
 * Micrometer counter, OpenTelemetry span status + event, and structured MDC log.
 */
public class DatabaseOperationMetrics {

    private static final Logger log = LoggerFactory.getLogger(DatabaseOperationMetrics.class);

    static final String METRIC_NAME = "db.operation.failures";

    private final MeterRegistry meterRegistry;
    // Cache counters to avoid repeated tag lookups on every failure
    private final Map<String, Counter> counterCache = new ConcurrentHashMap<>();

    /**
     * Constructs the metrics handler with a {@link MeterRegistry}.
     *
     * @param meterRegistry the registry to record metrics to; may be {@code null} to disable metrics
     */
    public DatabaseOperationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Records a database failure across all observability channels.
     * Increments Micrometer counters, tags the current OpenTelemetry span,
     * and writes a structured MDC log.
     *
     * @param ex       the classified domain exception
     * @param category the stable exception category
     * @param retried  whether the operation was attempted more than once
     */
    public void record(DataOperationException ex, ExceptionCategory category, boolean retried) {
        incrementCounter(ex, category);
        tagCurrentSpan(ex, category);
        structuredLog(ex, category, retried);
    }

    // -------------------------------------------------------------------------

    private void incrementCounter(DataOperationException ex, ExceptionCategory category) {
        if (meterRegistry == null) return;

        io.vavr.control.Option.of(category.name() + "|" + ex.repository() + "|" + ex.operation() + "|" + nullToEmpty(ex.sqlState()))
                .map(key -> counterCache.computeIfAbsent(key, k ->
                        Counter.builder(METRIC_NAME)
                                .tag("classification", category.name())
                                .tag("repository", ex.repository())
                                .tag("method", ex.operation())
                                .tag("sqlstate", nullToEmpty(ex.sqlState()))
                                .register(meterRegistry)))
                .peek(Counter::increment);
    }

    private void tagCurrentSpan(DataOperationException ex, ExceptionCategory category) {
        io.vavr.control.Option.of(Span.current())
                .filter(span -> span.getSpanContext().isValid())
                .peek(span -> {
                    span.setStatus(StatusCode.ERROR, category.name());
                    span.addEvent("db.operation.failure");
                    span.setAttribute("db.error.classification", category.name());
                    span.setAttribute("db.error.sqlstate", nullToEmpty(ex.sqlState()));
                    span.setAttribute("db.error.operation", ex.operation());
                    span.setAttribute("db.error.repository", ex.repository());
                });
    }

    private void structuredLog(DataOperationException ex, ExceptionCategory category, boolean retried) {
        try {
            MDC.put("db.classification", category.name());
            MDC.put("db.operation",      ex.operation());
            MDC.put("db.repository",     ex.repository());
            MDC.put("db.sqlstate",       nullToEmpty(ex.sqlState()));
            MDC.put("db.errorCode",      String.valueOf(ex.errorCode()));
            MDC.put("db.retried",        String.valueOf(retried));

            Match(category).of(
                    Case($(ExceptionCategory.PROGRAMMING_ERROR), c -> run(() ->
                            log.error("Database programming error in {}.{}", ex.repository(), ex.operation(), ex.getCause()))),
                    Case($(c -> retried), c -> run(() ->
                            log.warn("Retryable database failure in {}.{} [sqlstate={}]", ex.repository(), ex.operation(), ex.sqlState(), ex.getCause()))),
                    Case($(), c -> run(() ->
                            log.info("Non-retryable database failure in {}.{} [classification={}]", ex.repository(), ex.operation(), category)))
            );
        } finally {
            MDC.remove("db.classification");
            MDC.remove("db.operation");
            MDC.remove("db.repository");
            MDC.remove("db.sqlstate");
            MDC.remove("db.errorCode");
            MDC.remove("db.retried");
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
