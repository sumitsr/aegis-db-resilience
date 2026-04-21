package io.aegis.db.resilience.classification;

/**
 * Strategy for translating a raw database throwable into a {@link ClassificationResult}.
 *
 * <p>Implementations are discovered as Spring beans and consulted in {@link org.springframework.core.annotation.Order}
 * sequence. The first non-empty result wins (chain-of-responsibility). Register custom classifiers
 * as {@code @Bean @Order(n)} where {@code n < 0} to override built-in behaviour.
 */
public interface DatabaseExceptionClassifier {

    /**
     * @param throwable  the raw exception from the repository/service call
     * @param operation  method name for context
     * @param repository simple class name for context
     * @return a result, or {@code null} to pass to the next classifier in the chain
     */
    ClassificationResult classify(Throwable throwable, String operation, String repository);
}
