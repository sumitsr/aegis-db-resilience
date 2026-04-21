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

    /** JDBC SQLState; may be {@code null} when unavailable. */
    public String sqlState() { return sqlState; }

    /** Vendor-specific error code; 0 when unavailable. */
    public int errorCode() { return errorCode; }

    /** Repository method name where the failure occurred. */
    public String operation() { return operation; }

    /** Simple class name of the originating repository/service bean. */
    public String repository() { return repository; }
}
