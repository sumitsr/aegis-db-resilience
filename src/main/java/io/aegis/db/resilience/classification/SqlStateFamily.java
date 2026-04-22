package io.aegis.db.resilience.classification;

/**
 * ANSI/ISO SQL state class codes. Used for prefix matching — no string parsing of messages.
 */
public enum SqlStateFamily {

    // --- ANSI Standard SQLState Class Codes ---
    /** 08: Connection Exception. */
    CONNECTION_EXCEPTION("08"),
    /** 23: Integrity Constraint Violation. */
    INTEGRITY_CONSTRAINT_VIOLATION("23"),
    /** 40: Transaction Rollback (includes deadlocks). */
    TRANSACTION_ROLLBACK("40"),
    /** 57: Operator Intervention (Query Cancel - Postgres specific class). */
    QUERY_CANCELED("57");

    private final String prefix;

    SqlStateFamily(String prefix) {
        this.prefix = prefix;
    }

    /**
     * Checks if the given SQLState belongs to this family.
     *
     * @param sqlState the JDBC SQLState to check
     * @return {@code true} if the SQLState starts with this family's prefix
     */
    public boolean matches(String sqlState) {
        return sqlState != null && sqlState.startsWith(prefix);
    }

    // --- Standard ANSI/ISO Codes ---
    /** ANSI Code: Serialization failure (Generic). */
    public static final String SERIALIZATION_FAILURE      = "40001";

    // --- PostgreSQL Specific Codes ---
    /** Postgres Code: Unique violation. */
    public static final String PT_UNIQUE_VIOLATION           = "23505";
    /** Postgres Code: Foreign key violation. */
    public static final String PT_FOREIGN_KEY_VIOLATION      = "23503";
    /** Postgres Code: Not null violation. */
    public static final String PT_NOT_NULL_VIOLATION         = "23502";
    /** Postgres Code: Check violation. */
    public static final String PT_CHECK_VIOLATION            = "23514";
    /** Postgres Code: Exclusion violation. */
    public static final String PT_EXCLUSION_VIOLATION        = "23P01";
    /** Postgres Code: Deadlock detected. */
    public static final String PT_DEADLOCK_DETECTED          = "40P01";
    /** Postgres Code: Lock not available. */
    public static final String PT_LOCK_NOT_AVAILABLE         = "55P03";
    /** Postgres Code: Query cancelled. */
    public static final String PT_QUERY_CANCELLED_SQLSTATE   = "57014";

    // --- Oracle Specific Codes ---
    /** Oracle Code: Unique violation (SQLState). */
    public static final String OR_UNIQUE_VIOLATION           = "23000";
}
