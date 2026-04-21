package io.aegis.db.resilience.classification;

import io.aegis.db.resilience.domain.DataOperationException;

/**
 * Immutable result of a single classifier pass.
 *
 * @param domainException   the fully constructed domain exception ready to throw
 * @param category          stable tag for metrics and structured logs
 * @param retryable         whether the aspect may attempt a retry
 */
public record ClassificationResult(
        DataOperationException domainException,
        ExceptionCategory category,
        boolean retryable) {
}
