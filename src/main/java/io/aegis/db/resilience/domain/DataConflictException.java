package io.aegis.db.resilience.domain;

/**
 * Optimistic-lock conflict or serialization failure that is not retryable at the persistence layer
 * (the caller must re-read and re-apply its changes). Maps to HTTP 409.
 */
public final class DataConflictException extends DataOperationException {

    private DataConflictException(
            String message, Throwable cause, String sqlState, int errorCode,
            String operation, String repository) {
        super(message, cause, sqlState, errorCode, operation, repository);
    }

    /**
     * Static factory for {@link DataConflictException}.
     *
     * @param cause      the original exception
     * @param sqlState   the JDBC SQLState
     * @param errorCode  the vendor-specific error code
     * @param operation  the name of the repository operation
     * @param repository the name of the repository
     * @return a new DataConflictException
     */
    public static DataConflictException of(
            Throwable cause, String sqlState, int errorCode,
            String operation, String repository) {
        return new DataConflictException(
                "Concurrent modification conflict; caller must retry with fresh data",
                cause, sqlState, errorCode, operation, repository);
    }
}
