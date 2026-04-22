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

    /**
     * Static factory for {@link DataTimeoutException}.
     *
     * @param cause     the original exception
     * @param sqlState  the JDBC SQLState
     * @param errorCode the vendor-specific error code
     * @param operation the name of the repository operation
     * @param repository the name of the repository
     * @return a new DataTimeoutException
     */
    public static DataTimeoutException of(
            Throwable cause, String sqlState, int errorCode,
            String operation, String repository) {
        return new DataTimeoutException(
                "Database operation timed out",
                cause, sqlState, errorCode, operation, repository);
    }
}
