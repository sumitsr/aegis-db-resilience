package io.aegis.db.resilience.classification;

import io.aegis.db.resilience.domain.*;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.NonUniqueResultException;
import jakarta.persistence.PersistenceException;
import jakarta.validation.ConstraintViolationException;
import org.hibernate.StaleObjectStateException;
import org.hibernate.exception.LockAcquisitionException;
import org.springframework.core.annotation.Order;
import org.springframework.dao.*;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;

import java.sql.SQLException;

/**
 * Built-in classifier. Evaluated last (order = Integer.MAX_VALUE) so custom classifiers
 * registered at lower order values take precedence.
 *
 * <p>Classification priority:
 * <ol>
 *   <li>SQLState (most precise — vendor-neutral)</li>
 *   <li>Spring Data exception type</li>
 *   <li>JPA/Hibernate exception type</li>
 *   <li>Raw {@link SQLException} via {@link SQLExceptionTranslator}</li>
 * </ol>
 *
 * @implNote Never string-matches exception messages. SQLState and exception types only.
 */
@Order(Integer.MAX_VALUE)
public class DefaultDatabaseExceptionClassifier implements DatabaseExceptionClassifier {

    private final SQLExceptionTranslator sqlExceptionTranslator;

    public DefaultDatabaseExceptionClassifier(SQLExceptionTranslator sqlExceptionTranslator) {
        this.sqlExceptionTranslator = sqlExceptionTranslator;
    }

    @Override
    public ClassificationResult classify(Throwable throwable, String operation, String repository) {
        Throwable cause = unwrap(throwable);
        String sqlState = extractSqlState(cause);
        int errorCode   = extractErrorCode(cause);

        // --- SQLState-based (most authoritative) ---
        if (sqlState != null) {
            ClassificationResult sqlStateResult = classifyBySqlState(sqlState, errorCode, cause, operation, repository);
            if (sqlStateResult != null) return sqlStateResult;
        }

        // --- Raw SQLException not yet translated ---
        if (cause instanceof SQLException se) {
            DataAccessException translated = sqlExceptionTranslator.translate(operation, null, se);
            if (translated != null) {
                return classify(translated, operation, repository);
            }
        }

        // --- Spring Data exception hierarchy ---
        return classifyByType(cause, sqlState, errorCode, operation, repository);
    }

    // -------------------------------------------------------------------------
    // SQLState classification
    // -------------------------------------------------------------------------

    private ClassificationResult classifyBySqlState(
            String sqlState, int errorCode, Throwable cause,
            String operation, String repository) {

        if (SqlStateFamily.CONNECTION_EXCEPTION.matches(sqlState)) {
            return unavailable(cause, sqlState, errorCode, operation, repository);
        }
        if (SqlStateFamily.INTEGRITY_CONSTRAINT_VIOLATION.matches(sqlState)) {
            return integrityResult(sqlState, errorCode, cause, operation, repository);
        }
        return switch (sqlState) {
            case SqlStateFamily.DEADLOCK_DETECTED,
                 SqlStateFamily.SERIALIZATION_FAILURE  -> transient_(cause, sqlState, errorCode, operation, repository);
            case SqlStateFamily.LOCK_NOT_AVAILABLE      -> transient_(cause, sqlState, errorCode, operation, repository);
            case SqlStateFamily.QUERY_CANCELLED_SQLSTATE -> timeout(cause, sqlState, errorCode, operation, repository);
            default -> null;
        };
    }

    private ClassificationResult integrityResult(
            String sqlState, int errorCode, Throwable cause,
            String operation, String repository) {
        DataIntegrityException.ViolationType type = switch (sqlState) {
            case SqlStateFamily.UNIQUE_VIOLATION      -> DataIntegrityException.ViolationType.UNIQUE;
            case SqlStateFamily.FOREIGN_KEY_VIOLATION -> DataIntegrityException.ViolationType.FOREIGN_KEY;
            case SqlStateFamily.NOT_NULL_VIOLATION    -> DataIntegrityException.ViolationType.NOT_NULL;
            case SqlStateFamily.CHECK_VIOLATION       -> DataIntegrityException.ViolationType.CHECK;
            case SqlStateFamily.EXCLUSION_VIOLATION   -> DataIntegrityException.ViolationType.EXCLUSION;
            default -> DataIntegrityException.ViolationType.GENERIC;
        };
        ExceptionCategory category = switch (type) {
            case UNIQUE      -> ExceptionCategory.INTEGRITY_UNIQUE;
            case FOREIGN_KEY -> ExceptionCategory.INTEGRITY_FK;
            case NOT_NULL    -> ExceptionCategory.INTEGRITY_NOT_NULL;
            case CHECK       -> ExceptionCategory.INTEGRITY_CHECK;
            case EXCLUSION   -> ExceptionCategory.INTEGRITY_EXCLUSION;
            case GENERIC     -> ExceptionCategory.INTEGRITY_GENERIC;
        };
        return new ClassificationResult(
                DataIntegrityException.of(cause, sqlState, errorCode, operation, repository, type),
                category, false);
    }

    // -------------------------------------------------------------------------
    // Type-based classification (fallback)
    // -------------------------------------------------------------------------

    private ClassificationResult classifyByType(
            Throwable t, String sqlState, int errorCode,
            String operation, String repository) {

        // Not-found
        if (t instanceof EmptyResultDataAccessException
                || t instanceof EntityNotFoundException
                || t instanceof NonUniqueResultException
                || t instanceof IncorrectResultSizeDataAccessException) {
            return new ClassificationResult(
                    DataNotFoundException.of(t, operation, repository),
                    ExceptionCategory.NOT_FOUND, false);
        }

        // Timeout
        if (t instanceof QueryTimeoutException) {
            return timeout(t, sqlState, errorCode, operation, repository);
        }

        // Optimistic lock / concurrency conflict (non-retryable at infra layer)
        // explicitly check for optimistic lock failures to prevent them from falling into TransientDataAccessException
        if (t instanceof OptimisticLockingFailureException
                || t instanceof ObjectOptimisticLockingFailureException
                || t instanceof StaleObjectStateException) {
            return new ClassificationResult(
                    DataConflictException.of(t, sqlState, errorCode, operation, repository),
                    ExceptionCategory.CONFLICT, false);
        }

        // Transient / retryable
        // Note: ConcurrencyFailureException (like DeadlockLoser) is a TransientDataAccessException
        if (t instanceof TransientDataAccessException
                || t instanceof RecoverableDataAccessException
                || t instanceof CannotAcquireLockException
                || t instanceof LockAcquisitionException) {
            return transient_(t, sqlState, errorCode, operation, repository);
        }

        // Connectivity / pool exhaustion
        if (t instanceof CannotGetJdbcConnectionException
                || t instanceof DataAccessResourceFailureException
                || t instanceof CannotCreateTransactionException) {
            return unavailable(t, sqlState, errorCode, operation, repository);
        }

        // Integrity constraint (Spring level, no SQLState available)
        if (t instanceof DataIntegrityViolationException) {
            if (t instanceof DuplicateKeyException) {
                return new ClassificationResult(
                        DataIntegrityException.of(t, sqlState, errorCode, operation, repository,
                                DataIntegrityException.ViolationType.UNIQUE),
                        ExceptionCategory.INTEGRITY_UNIQUE, false);
            }
            return new ClassificationResult(
                    DataIntegrityException.of(t, sqlState, errorCode, operation, repository,
                            DataIntegrityException.ViolationType.GENERIC),
                    ExceptionCategory.INTEGRITY_GENERIC, false);
        }

        // Transaction lifecycle — surface the real cause from UnexpectedRollbackException
        if (t instanceof UnexpectedRollbackException ure && ure.getCause() != null) {
            return classify(ure.getCause(), operation, repository);
        }
        if (t instanceof TransactionSystemException tse) {
            return extractConstraintViolation(tse, sqlState, errorCode, operation, repository);
        }
        if (t instanceof IllegalTransactionStateException) {
            return programmingError(t, sqlState, errorCode, operation, repository);
        }

        // Programming / schema errors
        if (t instanceof InvalidDataAccessApiUsageException
                || t instanceof InvalidDataAccessResourceUsageException
                || t instanceof org.hibernate.exception.SQLGrammarException
                || t instanceof org.hibernate.LazyInitializationException) {
            return programmingError(t, sqlState, errorCode, operation, repository);
        }

        // JPA/Hibernate wrapper — unwrap and retry
        if (t instanceof JpaSystemException || t instanceof PersistenceException) {
            Throwable nested = t.getCause();
            if (nested != null && nested != t) {
                return classify(nested, operation, repository);
            }
        }

        // Fallback: treat unknown DataAccessException as programming error
        if (t instanceof DataAccessException) {
            return programmingError(t, sqlState, errorCode, operation, repository);
        }

        return programmingError(t, sqlState, errorCode, operation, repository);
    }

    // -------------------------------------------------------------------------
    // Bean-validation surfaced through transaction flush
    // -------------------------------------------------------------------------

    private ClassificationResult extractConstraintViolation(
            TransactionSystemException tse, String sqlState, int errorCode,
            String operation, String repository) {
        Throwable root = tse.getRootCause();
        if (root == null || root == tse) {
            root = tse.getApplicationException();
        }

        if (root instanceof ConstraintViolationException cve) {
            // Treat as integrity violation — type GENERIC because no SQLState is available
            return new ClassificationResult(
                    DataIntegrityException.of(cve, sqlState, errorCode, operation, repository,
                            DataIntegrityException.ViolationType.GENERIC),
                    ExceptionCategory.INTEGRITY_GENERIC, false);
        }
        if (root != null) {
            return classify(root, operation, repository);
        }
        return programmingError(tse, sqlState, errorCode, operation, repository);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ClassificationResult transient_(
            Throwable t, String sqlState, int errorCode,
            String operation, String repository) {
        return new ClassificationResult(
                TransientDataOperationException.of(t, sqlState, errorCode, operation, repository),
                ExceptionCategory.TRANSIENT, true);
    }

    private ClassificationResult timeout(
            Throwable t, String sqlState, int errorCode,
            String operation, String repository) {
        return new ClassificationResult(
                DataTimeoutException.of(t, sqlState, errorCode, operation, repository),
                ExceptionCategory.TIMEOUT, true);
    }

    private ClassificationResult unavailable(
            Throwable t, String sqlState, int errorCode,
            String operation, String repository) {
        return new ClassificationResult(
                DataUnavailableException.of(t, sqlState, errorCode, operation, repository),
                ExceptionCategory.UNAVAILABLE, true);
    }

    private ClassificationResult programmingError(
            Throwable t, String sqlState, int errorCode,
            String operation, String repository) {
        return new ClassificationResult(
                DataAccessProgrammingException.of(t, sqlState, errorCode, operation, repository),
                ExceptionCategory.PROGRAMMING_ERROR, false);
    }

    /**
     * Walk the cause chain to find a {@link SQLException} carrying a SQLState.
     * Stops at cycle or depth 10 to prevent infinite loops from malformed exceptions.
     */
    private String extractSqlState(Throwable t) {
        int depth = 0;
        Throwable cursor = t;
        while (cursor != null && depth++ < 10) {
            if (cursor instanceof SQLException se && se.getSQLState() != null) {
                return se.getSQLState();
            }
            if (cursor instanceof DataAccessException dae) {
                Throwable nested = dae.getCause();
                if (nested instanceof SQLException se && se.getSQLState() != null) {
                    return se.getSQLState();
                }
            }
            Throwable next = cursor.getCause();
            cursor = (next == cursor) ? null : next;
        }
        return null;
    }

    private int extractErrorCode(Throwable t) {
        int depth = 0;
        Throwable cursor = t;
        while (cursor != null && depth++ < 10) {
            if (cursor instanceof SQLException se) return se.getErrorCode();
            Throwable next = cursor.getCause();
            cursor = (next == cursor) ? null : next;
        }
        return 0;
    }

    /**
     * Unwrap JPA/Spring wrappers one level so type-matching works against the real cause.
     * Only strips if the cause is itself a known database exception type.
     */
    private Throwable unwrap(Throwable t) {
        if (t instanceof JpaSystemException || t instanceof PersistenceException) {
            Throwable cause = t.getCause();
            if (cause != null && cause != t) return cause;
        }
        return t;
    }
}
