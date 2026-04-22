package io.aegis.db.resilience.domain;

/**
 * Database host unreachable, pool exhausted, or TLS/DNS failure.
 * Retryable. Maps to HTTP 503.
 */
public final class DataUnavailableException extends DataOperationException {

    private DataUnavailableException(
            String message, Throwable cause, String sqlState, int errorCode,
            String operation, String repository) {
        super(message, cause, sqlState, errorCode, operation, repository);
    }

    /**
     * Static factory for {@link DataUnavailableException}.
     *
     * @param cause     the original exception
     * @param sqlState  the JDBC SQLState
     * @param errorCode the vendor-specific error code
     * @param operation the name of the repository operation
     * @param repository the name of the repository
     * @return a new DataUnavailableException
     */
    public static DataUnavailableException of(
            Throwable cause, String sqlState, int errorCode,
            String operation, String repository) {
        return new DataUnavailableException(
                "Database is unavailable",
                cause, sqlState, errorCode, operation, repository);
    }
}
