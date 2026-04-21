package io.aegis.db.resilience.classification;

/**
 * ANSI/ISO SQL state class codes. Used for prefix matching — no string parsing of messages.
 */
public enum SqlStateFamily {

    CONNECTION_EXCEPTION("08"),
    INTEGRITY_CONSTRAINT_VIOLATION("23"),
    TRANSACTION_ROLLBACK("40"),
    QUERY_CANCELED("57");

    private final String prefix;

    SqlStateFamily(String prefix) {
        this.prefix = prefix;
    }

    public boolean matches(String sqlState) {
        return sqlState != null && sqlState.startsWith(prefix);
    }

    // Specific SQLState constants (PostgreSQL + ANSI)
    public static final String UNIQUE_VIOLATION           = "23505";
    public static final String FOREIGN_KEY_VIOLATION      = "23503";
    public static final String NOT_NULL_VIOLATION         = "23502";
    public static final String CHECK_VIOLATION            = "23514";
    public static final String EXCLUSION_VIOLATION        = "23P01";
    public static final String DEADLOCK_DETECTED          = "40P01";
    public static final String SERIALIZATION_FAILURE      = "40001";
    public static final String LOCK_NOT_AVAILABLE         = "55P03";
    public static final String QUERY_CANCELLED_SQLSTATE   = "57014";
}
