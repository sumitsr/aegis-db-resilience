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

    /**
     * Static factory for {@link TransientDataOperationException}.
     *
     * @param cause     the original exception
     * @param sqlState  the JDBC SQLState
     * @param errorCode the vendor-specific error code
     * @param operation the name of the repository operation
     * @param repository the name of the repository
     * @return a new TransientDataOperationException
     */
    public static TransientDataOperationException of(
            Throwable cause, String sqlState, int errorCode,
            String operation, String repository) {
        return new TransientDataOperationException(
                "Transient database failure; eligible for retry",
                cause, sqlState, errorCode, operation, repository);
    }
}
