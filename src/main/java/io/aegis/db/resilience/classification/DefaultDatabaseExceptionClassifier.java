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

import static io.vavr.API.*;
import static io.vavr.Predicates.*;

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

    /**
     * Constructs a new classifier using the provided {@link SQLExceptionTranslator}
     * for raw JDBC exception mapping.
     *
     * @param sqlExceptionTranslator the translator to use for low-level JDBC errors
     */
    public DefaultDatabaseExceptionClassifier(SQLExceptionTranslator sqlExceptionTranslator) {
        this.sqlExceptionTranslator = sqlExceptionTranslator;
    }

    @Override
    @SuppressWarnings("null")
    public ClassificationResult classify(Throwable throwable, String operation, String repository) {
        Throwable cause = unwrap(throwable);
        String sqlState = extractSqlState(cause);
        int errorCode   = extractErrorCode(cause);

        // --- 1. Raw SQLException translation (Database Agnostic via Spring) ---
        if (cause instanceof SQLException se) {
            DataAccessException translated = sqlExceptionTranslator.translate(operation, null, se);
            if (translated != null) {
                return classify(translated, operation, repository);
            }
        }

        // --- 2. Type-based classification (Highly Database Agnostic) ---
        ClassificationResult typeResult = classifyByType(cause, sqlState, errorCode, operation, repository);
        
        // --- 3. SQLState refinement for Generic Categories ---
        // If we got a generic classification, try to sharpen it using the SQLState
        if (isGeneric(typeResult) && sqlState != null) {
            ClassificationResult refined = classifyBySqlState(sqlState, errorCode, cause, operation, repository);
            if (refined != null) return refined;
        }

        return typeResult;
    }

    private boolean isGeneric(ClassificationResult result) {
        return result.category() == ExceptionCategory.INTEGRITY_GENERIC ||
               result.category() == ExceptionCategory.UNAVAILABLE ||
               result.category() == ExceptionCategory.PROGRAMMING_ERROR;
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
            case SqlStateFamily.PT_DEADLOCK_DETECTED,
                 SqlStateFamily.SERIALIZATION_FAILURE  -> transient_(cause, sqlState, errorCode, operation, repository);
            case SqlStateFamily.PT_LOCK_NOT_AVAILABLE      -> transient_(cause, sqlState, errorCode, operation, repository);
            case SqlStateFamily.PT_QUERY_CANCELLED_SQLSTATE -> timeout(cause, sqlState, errorCode, operation, repository);
            default -> null;
        };
    }

    private ClassificationResult integrityResult(
            String sqlState, int errorCode, Throwable cause,
            String operation, String repository) {
        
        DataIntegrityException.ViolationType type = switch (sqlState) {
            case SqlStateFamily.PT_UNIQUE_VIOLATION, 
                 SqlStateFamily.OR_UNIQUE_VIOLATION      -> DataIntegrityException.ViolationType.UNIQUE;
            case SqlStateFamily.PT_FOREIGN_KEY_VIOLATION -> DataIntegrityException.ViolationType.FOREIGN_KEY;
            case SqlStateFamily.PT_NOT_NULL_VIOLATION    -> DataIntegrityException.ViolationType.NOT_NULL;
            case SqlStateFamily.PT_CHECK_VIOLATION       -> DataIntegrityException.ViolationType.CHECK;
            case SqlStateFamily.PT_EXCLUSION_VIOLATION   -> DataIntegrityException.ViolationType.EXCLUSION;
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

        return Match(t).of(
                Case($(anyOf(
                        instanceOf(EmptyResultDataAccessException.class),
                        instanceOf(EntityNotFoundException.class),
                        instanceOf(NonUniqueResultException.class),
                        instanceOf(IncorrectResultSizeDataAccessException.class))),
                        ex -> new ClassificationResult(DataNotFoundException.of(ex, operation, repository), ExceptionCategory.NOT_FOUND, false)),

                Case($(instanceOf(QueryTimeoutException.class)),
                        ex -> timeout(ex, sqlState, errorCode, operation, repository)),

                Case($(anyOf(
                        instanceOf(OptimisticLockingFailureException.class),
                        instanceOf(ObjectOptimisticLockingFailureException.class),
                        instanceOf(StaleObjectStateException.class))),
                        ex -> new ClassificationResult(DataConflictException.of(ex, sqlState, errorCode, operation, repository), ExceptionCategory.CONFLICT, false)),

                Case($(anyOf(
                        instanceOf(TransientDataAccessException.class),
                        instanceOf(RecoverableDataAccessException.class),
                        instanceOf(CannotAcquireLockException.class),
                        instanceOf(LockAcquisitionException.class))),
                        ex -> transient_(ex, sqlState, errorCode, operation, repository)),

                Case($(anyOf(
                        instanceOf(CannotGetJdbcConnectionException.class),
                        instanceOf(DataAccessResourceFailureException.class),
                        instanceOf(CannotCreateTransactionException.class))),
                        ex -> unavailable(ex, sqlState, errorCode, operation, repository)),

                Case($(instanceOf(DuplicateKeyException.class)),
                        ex -> new ClassificationResult(DataIntegrityException.of(ex, sqlState, errorCode, operation, repository, DataIntegrityException.ViolationType.UNIQUE), ExceptionCategory.INTEGRITY_UNIQUE, false)),

                Case($(instanceOf(DataIntegrityViolationException.class)),
                        ex -> new ClassificationResult(DataIntegrityException.of(ex, sqlState, errorCode, operation, repository, DataIntegrityException.ViolationType.GENERIC), ExceptionCategory.INTEGRITY_GENERIC, false)),

                Case($(instanceOf(UnexpectedRollbackException.class)),
                        ex -> ex.getCause() != null ? classify(ex.getCause(), operation, repository) : programmingError(ex, sqlState, errorCode, operation, repository)),

                Case($(instanceOf(TransactionSystemException.class)),
                        ex -> extractConstraintViolation(ex, sqlState, errorCode, operation, repository)),

                Case($(anyOf(
                        instanceOf(IllegalTransactionStateException.class),
                        instanceOf(InvalidDataAccessApiUsageException.class),
                        instanceOf(InvalidDataAccessResourceUsageException.class),
                        instanceOf(org.hibernate.exception.SQLGrammarException.class),
                        instanceOf(org.hibernate.LazyInitializationException.class))),
                        ex -> programmingError(ex, sqlState, errorCode, operation, repository)),

                Case($(anyOf(
                        instanceOf(JpaSystemException.class),
                        instanceOf(PersistenceException.class))),
                        ex -> ex.getCause() != null && ex.getCause() != ex ? classify(ex.getCause(), operation, repository) : programmingError(ex, sqlState, errorCode, operation, repository)),

                Case($(instanceOf(DataAccessException.class)),
                        ex -> programmingError(ex, sqlState, errorCode, operation, repository)),

                Case($(), ex -> programmingError(ex, sqlState, errorCode, operation, repository))
        );
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

    private String extractSqlState(Throwable t) {
        return io.vavr.collection.Stream.iterate(t, cause -> cause == cause.getCause() ? null : cause.getCause())
                .take(10)
                .takeWhile(java.util.Objects::nonNull)
                .flatMap(cursor -> {
                    if (cursor instanceof SQLException se && se.getSQLState() != null) {
                        return io.vavr.collection.Stream.of(se.getSQLState());
                    }
                    if (cursor instanceof DataAccessException dae && dae.getCause() instanceof SQLException se && se.getSQLState() != null) {
                        return io.vavr.collection.Stream.of(se.getSQLState());
                    }
                    return io.vavr.collection.Stream.empty();
                })
                .headOption()
                .getOrNull();
    }

    private int extractErrorCode(Throwable t) {
        return io.vavr.collection.Stream.iterate(t, cause -> cause == cause.getCause() ? null : cause.getCause())
                .take(10)
                .takeWhile(java.util.Objects::nonNull)
                .flatMap(cursor -> {
                    if (cursor instanceof SQLException se) return io.vavr.collection.Stream.of(se.getErrorCode());
                    if (cursor instanceof DataAccessException dae && dae.getCause() instanceof SQLException se) return io.vavr.collection.Stream.of(se.getErrorCode());
                    return io.vavr.collection.Stream.empty();
                })
                .headOption()
                .getOrElse(0);
    }

    /**
     * Unwrap JPA/Spring wrappers one level using Match API.
     */
    private Throwable unwrap(Throwable t) {
        return Match(t).of(
                Case($(anyOf(instanceOf(JpaSystemException.class), instanceOf(PersistenceException.class))),
                        ex -> ex.getCause() != null && ex.getCause() != ex ? ex.getCause() : ex),
                Case($(), ex -> ex)
        );
    }
}
