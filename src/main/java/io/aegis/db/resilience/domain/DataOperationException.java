package io.aegis.db.resilience.domain;

/**
 * Sealed root of the database failure hierarchy.
 *
 * <p>Every subclass is immutable; construction is via the static factory methods on each leaf type.
 * SQL-specific details are retained for server-side logging but never exposed to clients.
 */
public abstract sealed class DataOperationException extends RuntimeException
        permits TransientDataOperationException,
                DataConflictException,
                DataIntegrityException,
                DataNotFoundException,
                DataAccessProgrammingException,
                DataTimeoutException,
                DataUnavailableException {

    private final String sqlState;
    private final int    errorCode;
    private final String operation;
    private final String repository;

    protected DataOperationException(
            String message,
            Throwable cause,
            String sqlState,
            int errorCode,
            String operation,
            String repository) {
        super(message, cause);
        this.sqlState   = sqlState;
        this.errorCode  = errorCode;
        this.operation  = operation;
        this.repository = repository;
    }

    /**
     * Returns the JDBC SQLState associated with this exception.
     *
     * @return the SQLState, or {@code null} if unavailable
     */
    public String sqlState() { return sqlState; }

    /**
     * Returns the vendor-specific error code associated with this exception.
     *
     * @return the error code, or {@code 0} if unavailable
     */
    public int errorCode() { return errorCode; }

    /**
     * Returns the name of the repository operation that failed.
     *
     * @return the name of the failed operation
     */
    public String operation() { return operation; }

    /**
     * Returns the simple name of the repository where the failure occurred.
     *
     * @return the name of the repository
     */
    public String repository() { return repository; }
}
