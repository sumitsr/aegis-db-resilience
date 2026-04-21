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

    public DatabaseOperationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void record(DataOperationException ex, ExceptionCategory category, boolean retried) {
        incrementCounter(ex, category);
        tagCurrentSpan(ex, category);
        structuredLog(ex, category, retried);
    }

    // -------------------------------------------------------------------------

    private void incrementCounter(DataOperationException ex, ExceptionCategory category) {
        String key = category.name() + "|" + ex.repository() + "|" + ex.operation()
                + "|" + nullToEmpty(ex.sqlState());
        counterCache.computeIfAbsent(key, k ->
                Counter.builder(METRIC_NAME)
                        .tag("classification", category.name())
                        .tag("repository", ex.repository())
                        .tag("method", ex.operation())
                        .tag("sqlstate", nullToEmpty(ex.sqlState()))
                        .register(meterRegistry)
        ).increment();
    }

    private void tagCurrentSpan(DataOperationException ex, ExceptionCategory category) {
        Span span = Span.current();
        if (!span.getSpanContext().isValid()) return;
        span.setStatus(StatusCode.ERROR, category.name());
        span.addEvent("db.operation.failure");
        span.setAttribute("db.error.classification", category.name());
        span.setAttribute("db.error.sqlstate", nullToEmpty(ex.sqlState()));
        span.setAttribute("db.error.operation", ex.operation());
        span.setAttribute("db.error.repository", ex.repository());
    }

    private void structuredLog(DataOperationException ex, ExceptionCategory category, boolean retried) {
        try {
            MDC.put("db.classification", category.name());
            MDC.put("db.operation",      ex.operation());
            MDC.put("db.repository",     ex.repository());
            MDC.put("db.sqlstate",       nullToEmpty(ex.sqlState()));
            MDC.put("db.errorCode",      String.valueOf(ex.errorCode()));
            MDC.put("db.retried",        String.valueOf(retried));

            if (category == ExceptionCategory.PROGRAMMING_ERROR) {
                log.error("Database programming error in {}.{}", ex.repository(), ex.operation(), ex.getCause());
            } else if (retried) {
                log.warn("Retryable database failure in {}.{} [sqlstate={}]",
                        ex.repository(), ex.operation(), ex.sqlState(), ex.getCause());
            } else {
                log.info("Non-retryable database failure in {}.{} [classification={}]",
                        ex.repository(), ex.operation(), category);
            }
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
