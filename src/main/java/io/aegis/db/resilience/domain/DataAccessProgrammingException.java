package io.aegis.db.resilience.domain;

/**
 * Programming error: bad SQL, missing column, lazy-init outside session, schema mismatch.
 * Maps to HTTP 500. Never retried. Triggers an alert via metric tag {@code classification=PROGRAMMING_ERROR}.
 */
public final class DataAccessProgrammingException extends DataOperationException {

    private DataAccessProgrammingException(
            String message, Throwable cause, String sqlState, int errorCode,
            String operation, String repository) {
        super(message, cause, sqlState, errorCode, operation, repository);
    }

    public static DataAccessProgrammingException of(
            Throwable cause, String sqlState, int errorCode,
            String operation, String repository) {
        return new DataAccessProgrammingException(
                "Database programming error; requires immediate investigation",
                cause, sqlState, errorCode, operation, repository);
    }
}
