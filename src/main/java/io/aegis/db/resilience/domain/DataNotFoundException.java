package io.aegis.db.resilience.domain;

/**
 * Entity or result set expected but absent. Maps to HTTP 404. Never retried.
 */
public final class DataNotFoundException extends DataOperationException {

    private DataNotFoundException(
            String message, Throwable cause, String sqlState, int errorCode,
            String operation, String repository) {
        super(message, cause, sqlState, errorCode, operation, repository);
    }

    /**
     * Static factory for {@link DataNotFoundException}.
     *
     * @param cause      the original exception
     * @param operation  the name of the repository operation
     * @param repository the name of the repository
     * @return a new DataNotFoundException
     */
    public static DataNotFoundException of(
            Throwable cause, String operation, String repository) {
        return new DataNotFoundException(
                "Expected entity or result not found",
                cause, null, 0, operation, repository);
    }
}
