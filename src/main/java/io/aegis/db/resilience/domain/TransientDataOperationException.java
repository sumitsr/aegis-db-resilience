package io.aegis.db.resilience.domain;

/**
 * Retryable: deadlocks, lock timeouts, serialization failures, transient infrastructure blips.
 * The aspect may retry operations that yield this exception.
 */
public final class TransientDataOperationException extends DataOperationException {

    private TransientDataOperationException(
            String message, Throwable cause, String sqlState, int errorCode,
            String operation, String repository) {
        super(message, cause, sqlState, errorCode, operation, repository);
    }

    public static TransientDataOperationException of(
            Throwable cause, String sqlState, int errorCode,
            String operation, String repository) {
        return new TransientDataOperationException(
                "Transient database failure; eligible for retry",
                cause, sqlState, errorCode, operation, repository);
    }
}
