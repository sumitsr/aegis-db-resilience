package io.aegis.db.resilience.classification;

import io.aegis.db.resilience.domain.*;
import jakarta.persistence.EntityNotFoundException;
import org.hibernate.StaleObjectStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.*;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.support.SQLStateSQLExceptionTranslator;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for every exception category in the taxonomy.
 * Uses {@link SQLStateSQLExceptionTranslator} (no DataSource needed) as the translator.
 */
class DefaultDatabaseExceptionClassifierTest {

    private DefaultDatabaseExceptionClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new DefaultDatabaseExceptionClassifier(new SQLStateSQLExceptionTranslator());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Connectivity / Infrastructure
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Connectivity {

        @Test
        void cannotGetJdbcConnection_isUnavailableAndRetryable() {
            var ex = new CannotGetJdbcConnectionException("pool exhausted");
            ClassificationResult result = classify(ex);
            assertThat(result.domainException()).isInstanceOf(DataUnavailableException.class);
            assertThat(result.retryable()).isTrue();
            assertThat(result.category()).isEqualTo(ExceptionCategory.UNAVAILABLE);
        }

        @ParameterizedTest(name = "SQLState {0} → UNAVAILABLE")
        @ValueSource(strings = {"08000", "08001", "08003", "08006", "08007"})
        void connectionSqlState_isUnavailable(String sqlState) {
            var sqlEx = new SQLException("connection refused", sqlState);
            ClassificationResult result = classify(sqlEx);
            assertThat(result.domainException()).isInstanceOf(DataUnavailableException.class);
            assertThat(result.retryable()).isTrue();
        }

        @Test
        void dataAccessResourceFailure_isUnavailable() {
            ClassificationResult result = classify(new DataAccessResourceFailureException("disk full"));
            assertThat(result.domainException()).isInstanceOf(DataUnavailableException.class);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Transient faults
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Transient {

        @Test
        void deadlockLoser_isTransientAndRetryable() {
            ClassificationResult result = classify(new DeadlockLoserDataAccessException("deadlock", null));
            assertThat(result.domainException()).isInstanceOf(TransientDataOperationException.class);
            assertThat(result.retryable()).isTrue();
        }

        @Test
        void deadlockSqlState_isTransient() {
            var sqlEx = new SQLException("deadlock", SqlStateFamily.PT_DEADLOCK_DETECTED);
            ClassificationResult result = classify(sqlEx);
            assertThat(result.domainException()).isInstanceOf(TransientDataOperationException.class);
        }

        @Test
        void serializationFailureSqlState_isTransient() {
            var sqlEx = new SQLException("serialization failure", SqlStateFamily.SERIALIZATION_FAILURE);
            ClassificationResult result = classify(sqlEx);
            assertThat(result.domainException()).isInstanceOf(TransientDataOperationException.class);
            assertThat(result.retryable()).isTrue();
        }

        @Test
        void cannotAcquireLock_isTransient() {
            ClassificationResult result = classify(new CannotAcquireLockException("lock wait timeout"));
            assertThat(result.domainException()).isInstanceOf(TransientDataOperationException.class);
        }

        @Test
        void pessimisticLockingFailure_isTransient() {
            ClassificationResult result = classify(new PessimisticLockingFailureException("lock"));
            assertThat(result.domainException()).isInstanceOf(TransientDataOperationException.class);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. Concurrency / Optimistic locking
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Concurrency {

        @Test
        void objectOptimisticLockingFailure_isConflictAndNotRetryable() {
            ClassificationResult result = classify(
                    new ObjectOptimisticLockingFailureException("Order", 42L));
            assertThat(result.domainException()).isInstanceOf(DataConflictException.class);
            assertThat(result.retryable()).isFalse();
            assertThat(result.category()).isEqualTo(ExceptionCategory.CONFLICT);
        }

        @Test
        void staleObjectState_isConflict() {
            var stale = new StaleObjectStateException("Order", 1L);
            ClassificationResult result = classify(stale);
            assertThat(result.domainException()).isInstanceOf(DataConflictException.class);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. Integrity constraints (SQLState 23xxx)
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Integrity {

        @Test
        void uniqueViolation_byExceptionType() {
            ClassificationResult result = classify(
                    new DuplicateKeyException("duplicate key value violates unique constraint"));
            assertThat(result.domainException()).isInstanceOf(DataIntegrityException.class);
            assertThat(violationType(result)).isEqualTo(DataIntegrityException.ViolationType.UNIQUE);
            assertThat(result.category()).isEqualTo(ExceptionCategory.INTEGRITY_UNIQUE);
            assertThat(result.retryable()).isFalse();
        }

        @Test
        void uniqueViolation_bySqlState_23505() {
            var sqlEx = new SQLException("duplicate key", SqlStateFamily.PT_UNIQUE_VIOLATION, 0);
            ClassificationResult result = classify(sqlEx);
            assertThat(result.domainException()).isInstanceOf(DataIntegrityException.class);
            assertThat(violationType(result)).isEqualTo(DataIntegrityException.ViolationType.UNIQUE);
        }

        @Test
        void foreignKeyViolation_bySqlState_23503() {
            var sqlEx = new SQLException("fk violation", SqlStateFamily.PT_FOREIGN_KEY_VIOLATION, 0);
            ClassificationResult result = classify(sqlEx);
            assertThat(violationType(result)).isEqualTo(DataIntegrityException.ViolationType.FOREIGN_KEY);
            assertThat(result.category()).isEqualTo(ExceptionCategory.INTEGRITY_FK);
        }

        @Test
        void notNullViolation_bySqlState_23502() {
            var sqlEx = new SQLException("not null", SqlStateFamily.PT_NOT_NULL_VIOLATION, 0);
            ClassificationResult result = classify(sqlEx);
            assertThat(violationType(result)).isEqualTo(DataIntegrityException.ViolationType.NOT_NULL);
        }

        @Test
        void checkViolation_bySqlState_23514() {
            var sqlEx = new SQLException("check violation", SqlStateFamily.PT_CHECK_VIOLATION, 0);
            ClassificationResult result = classify(sqlEx);
            assertThat(violationType(result)).isEqualTo(DataIntegrityException.ViolationType.CHECK);
        }

        @Test
        void exclusionViolation_bySqlState_23P01() {
            var sqlEx = new SQLException("exclusion violation", SqlStateFamily.PT_EXCLUSION_VIOLATION, 0);
            ClassificationResult result = classify(sqlEx);
            assertThat(violationType(result)).isEqualTo(DataIntegrityException.ViolationType.EXCLUSION);
        }

        @Test
        void dataIntegrityViolationWrapper_wrappingUniqueSqlState() {
            var sqlCause = new SQLException("uk", SqlStateFamily.PT_UNIQUE_VIOLATION, 0);
            var wrapped = new DataIntegrityViolationException("constraint", sqlCause);
            ClassificationResult result = classify(wrapped);
            assertThat(result.domainException()).isInstanceOf(DataIntegrityException.class);
            assertThat(violationType(result)).isEqualTo(DataIntegrityException.ViolationType.UNIQUE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5. Result shape
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class ResultShape {

        @Test
        void emptyResult_isNotFound() {
            ClassificationResult result = classify(new EmptyResultDataAccessException(1));
            assertThat(result.domainException()).isInstanceOf(DataNotFoundException.class);
            assertThat(result.category()).isEqualTo(ExceptionCategory.NOT_FOUND);
            assertThat(result.retryable()).isFalse();
        }

        @Test
        void jpaEntityNotFound_isNotFound() {
            ClassificationResult result = classify(new EntityNotFoundException("order not found"));
            assertThat(result.domainException()).isInstanceOf(DataNotFoundException.class);
        }

        @Test
        void incorrectResultSize_isNotFound() {
            ClassificationResult result = classify(new IncorrectResultSizeDataAccessException(1, 2));
            assertThat(result.domainException()).isInstanceOf(DataNotFoundException.class);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6. Transaction lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class TransactionLifecycle {

        @Test
        void unexpectedRollback_surfacesRealCause() {
            // The real cause is a unique violation inside the transaction
            var sqlCause = new SQLException("duplicate", SqlStateFamily.PT_UNIQUE_VIOLATION, 0);
            var realCause = new DataIntegrityViolationException("uk", sqlCause);
            var wrapper = new UnexpectedRollbackException("rollback", realCause);

            ClassificationResult result = classify(wrapper);
            assertThat(result.domainException()).isInstanceOf(DataIntegrityException.class);
        }

        @Test
        void transactionSystemException_withConstraintViolationRoot() {
            var cvEx = new jakarta.validation.ConstraintViolationException(
                    "bean validation failed", java.util.Set.of());
            var tse = new TransactionSystemException("rollback on commit");
            tse.initCause(new jakarta.persistence.RollbackException("rollback", cvEx));

            // TSE.getRootCause() climbs to ConstraintViolationException
            var tseWithRoot = new TransactionSystemException("tx");
            tseWithRoot.initApplicationException(cvEx);

            ClassificationResult result = classify(tseWithRoot);
            assertThat(result.domainException()).isInstanceOf(DataIntegrityException.class);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 7. Programming errors
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class ProgrammingErrors {

        @Test
        void invalidDataAccessApiUsage_isProgrammingError() {
            ClassificationResult result = classify(
                    new InvalidDataAccessApiUsageException("detached entity"));
            assertThat(result.domainException()).isInstanceOf(DataAccessProgrammingException.class);
            assertThat(result.retryable()).isFalse();
            assertThat(result.category()).isEqualTo(ExceptionCategory.PROGRAMMING_ERROR);
        }

        @Test
        void lazyInitializationException_isProgrammingError() {
            ClassificationResult result = classify(
                    new org.hibernate.LazyInitializationException("no session"));
            assertThat(result.domainException()).isInstanceOf(DataAccessProgrammingException.class);
        }

        @Test
        void invalidDataAccessResourceUsage_isProgrammingError() {
            ClassificationResult result = classify(
                    new InvalidDataAccessResourceUsageException("bad SQL"));
            assertThat(result.domainException()).isInstanceOf(DataAccessProgrammingException.class);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 8. Timeout
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Timeout {

        @Test
        void queryTimeout_isTimeoutAndRetryable() {
            ClassificationResult result = classify(new QueryTimeoutException("statement timeout"));
            assertThat(result.domainException()).isInstanceOf(DataTimeoutException.class);
            assertThat(result.retryable()).isTrue();
            assertThat(result.category()).isEqualTo(ExceptionCategory.TIMEOUT);
        }

        @Test
        void queryCancelledSqlState_isTimeout() {
            var sqlEx = new SQLException("query canceled", SqlStateFamily.PT_QUERY_CANCELLED_SQLSTATE);
            ClassificationResult result = classify(sqlEx);
            assertThat(result.domainException()).isInstanceOf(DataTimeoutException.class);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 9. Raw SQLException passthrough
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void rawSqlExceptionWithKnownSqlState_isTranslatedAndClassified() {
        // A raw SQLException bypassing Spring's translator (e.g., from a stored proc)
        var sqlEx = new SQLException("raw", SqlStateFamily.PT_DEADLOCK_DETECTED, 0);
        ClassificationResult result = classify(sqlEx);
        assertThat(result.domainException()).isInstanceOf(TransientDataOperationException.class);
    }

    @Test
    void jpaSystemException_unwrapsToRealCause() {
        var sqlEx = new SQLException("unique", SqlStateFamily.PT_UNIQUE_VIOLATION, 0);
        var hibernate = new org.hibernate.exception.ConstraintViolationException("uk", sqlEx, "orders_pkey");
        var jpaEx = new JpaSystemException(new jakarta.persistence.PersistenceException(hibernate));

        ClassificationResult result = classify(jpaEx);
        assertThat(result.domainException()).isInstanceOf(DataIntegrityException.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Contract: no DataAccessException escapes unclassified
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void anyDataAccessException_alwaysProducesResult() {
        // Unknown subclass — should fall through to PROGRAMMING_ERROR, not null
        DataAccessException unknown = new DataAccessException("unknown") {};
        ClassificationResult result = classify(unknown);
        assertThat(result).isNotNull();
        assertThat(result.domainException()).isNotNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private ClassificationResult classify(Throwable t) {
        return classifier.classify(t, "testMethod", "TestRepository");
    }

    private DataIntegrityException.ViolationType violationType(ClassificationResult r) {
        return ((DataIntegrityException) r.domainException()).violationType();
    }
}
