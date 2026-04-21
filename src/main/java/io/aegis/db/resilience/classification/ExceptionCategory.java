package io.aegis.db.resilience.classification;

/** Stable category labels used as Micrometer tag values. */
public enum ExceptionCategory {
    TRANSIENT,
    CONFLICT,
    INTEGRITY_UNIQUE,
    INTEGRITY_FK,
    INTEGRITY_NOT_NULL,
    INTEGRITY_CHECK,
    INTEGRITY_EXCLUSION,
    INTEGRITY_GENERIC,
    NOT_FOUND,
    PROGRAMMING_ERROR,
    TIMEOUT,
    UNAVAILABLE
}
