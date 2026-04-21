package io.aegis.db.resilience.domain;

/**
 * Query or statement timeout. Retryable with backoff. Maps to HTTP 408 / 504.
 */
public final class DataTimeoutException extends DataOperationException {

    private DataTimeoutException(
            String message, Throwable cause, String sqlState, int errorCode,
            String operation, String repository) {
        super(message, cause, sqlState, errorCode, operation, repository);
    }

    public static DataTimeoutException of(
            Throwable cause, String sqlState, int errorCode,
            String operation, String repository) {
        return new DataTimeoutException(
                "Database operation timed out",
                cause, sqlState, errorCode, operation, repository);
    }
}
