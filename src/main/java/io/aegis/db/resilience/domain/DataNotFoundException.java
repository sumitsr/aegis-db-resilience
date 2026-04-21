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

    public static DataNotFoundException of(
            Throwable cause, String operation, String repository) {
        return new DataNotFoundException(
                "Expected entity or result not found",
                cause, null, 0, operation, repository);
    }
}
