package io.aegis.db.resilience.classification;

/** Stable category labels used as Micrometer tag values. */
public enum ExceptionCategory {
    /** Transient failures (e.g., deadlocks, lock timeouts) that may succeed on retry. */
    TRANSIENT,
    /** Optimistic locking conflicts. */
    CONFLICT,
    /** Unique constraint violation. */
    INTEGRITY_UNIQUE,
    /** Foreign key constraint violation. */
    INTEGRITY_FK,
    /** Not-null constraint violation. */
    INTEGRITY_NOT_NULL,
    /** Check constraint violation. */
    INTEGRITY_CHECK,
    /** Exclusion constraint violation. */
    INTEGRITY_EXCLUSION,
    /** Generic or unknown integrity violation. */
    INTEGRITY_GENERIC,
    /** Entity not found or incorrect result size. */
    NOT_FOUND,
    /** Programming error (bad SQL, schema mismatch, etc.). Never retried. */
    PROGRAMMING_ERROR,
    /** Query or transaction timeout. */
    TIMEOUT,
    /** Database or connection pool unavailable. */
    UNAVAILABLE
}
