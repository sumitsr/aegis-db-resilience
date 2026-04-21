package io.aegis.db.resilience.domain;

/**
 * Constraint violation: unique, FK, NOT NULL, CHECK. Maps to HTTP 409. Never retried.
 *
 * {@code violationType} captures the 23xxx SQLState sub-category so the caller can
 * distinguish duplicate-key from FK-violation without re-inspecting the cause chain.
 */
public final class DataIntegrityException extends DataOperationException {

    /** Fine-grained sub-category derived from the SQLState 23xxx family. */
    public enum ViolationType {
        UNIQUE, FOREIGN_KEY, NOT_NULL, CHECK, EXCLUSION, GENERIC
    }

    private final ViolationType violationType;

    private DataIntegrityException(
            String message, Throwable cause, String sqlState, int errorCode,
            String operation, String repository, ViolationType violationType) {
        super(message, cause, sqlState, errorCode, operation, repository);
        this.violationType = violationType;
    }

    public static DataIntegrityException of(
            Throwable cause, String sqlState, int errorCode,
            String operation, String repository, ViolationType violationType) {
        return new DataIntegrityException(
                "Data integrity constraint violated: " + violationType,
                cause, sqlState, errorCode, operation, repository, violationType);
    }

    public ViolationType violationType() { return violationType; }
}
