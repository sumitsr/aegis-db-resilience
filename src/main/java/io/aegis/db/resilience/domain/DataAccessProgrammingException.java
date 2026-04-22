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

    /**
     * Static factory for {@link DataAccessProgrammingException}.
     *
     * @param cause      the original exception
     * @param sqlState   the JDBC SQLState
     * @param errorCode  the vendor-specific error code
     * @param operation  the name of the repository operation
     * @param repository the name of the repository
     * @return a new DataAccessProgrammingException
     */
    public static DataAccessProgrammingException of(
            Throwable cause, String sqlState, int errorCode,
            String operation, String repository) {
        return new DataAccessProgrammingException(
                "Database programming error; requires immediate investigation",
                cause, sqlState, errorCode, operation, repository);
    }
}
