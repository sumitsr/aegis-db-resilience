package io.aegis.db.resilience.autoconfigure;

import io.aegis.db.resilience.aspect.DatabaseResilienceAspect;
import io.aegis.db.resilience.aspect.DatabaseResilienceInterceptor;
import io.aegis.db.resilience.classification.DatabaseExceptionClassifier;
import io.aegis.db.resilience.classification.DefaultDatabaseExceptionClassifier;
import io.aegis.db.resilience.observability.DatabaseOperationMetrics;
import io.aegis.db.resilience.retry.RetryTemplateFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.aop.aspectj.AspectJExpressionPointcutAdvisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;

import javax.sql.DataSource;
import java.util.List;
import java.util.stream.StreamSupport;

@AutoConfiguration
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableConfigurationProperties(DatabaseResilienceProperties.class)
@ConditionalOnClass(name = "org.springframework.dao.DataAccessException")
public class DatabaseResilienceAutoConfiguration {

    /**
     * Provides the default {@link SQLExceptionTranslator}.
     * Prefers using the provided {@link DataSource} for vendor-specific error code mapping.
     *
     * @param dataSource the data source to use for translation hints
     * @return a configured SQL exception translator
     */
    @Bean
    @ConditionalOnMissingBean
    public SQLExceptionTranslator sqlExceptionTranslator(ObjectProvider<DataSource> dataSource) {
        DataSource ds = dataSource.getIfAvailable();
        return ds != null
                ? new SQLErrorCodeSQLExceptionTranslator(ds)
                : new SQLErrorCodeSQLExceptionTranslator();
    }

    /**
     * Provides the default built-in exception classifier.
     *
     * @param translator the translator to use for lower-level SQL error mapping
     * @return the default database exception classifier
     */
    @Bean
    @ConditionalOnMissingBean
    public DefaultDatabaseExceptionClassifier defaultDatabaseExceptionClassifier(
            SQLExceptionTranslator translator) {
        return new DefaultDatabaseExceptionClassifier(translator);
    }

    /**
     * Provides the metrics handler for database operations.
     *
     * @param meterRegistryProvider provider for the Micrometer registry
     * @return the database operation metrics handler
     */
    @Bean
    @ConditionalOnMissingBean
    public DatabaseOperationMetrics databaseOperationMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new DatabaseOperationMetrics(meterRegistryProvider.getIfAvailable());
    }

    /**
     * Provides the factory for creating policy-aware retry templates.
     *
     * @param props the global configuration properties
     * @return the retry template factory
     */
    @Bean
    @ConditionalOnMissingBean
    public RetryTemplateFactory retryTemplateFactory(DatabaseResilienceProperties props) {
        return new RetryTemplateFactory(props);
    }

    /**
     * Provides the shared resilience interceptor.
     * Discovers all {@link DatabaseExceptionClassifier} beans and sorts them by order.
     *
     * @param classifierProvider   provider for all discovered classifiers
     * @param metrics              the metrics handler
     * @param retryTemplateFactory the retry template factory
     * @return the resilience interceptor
     */
    @Bean
    @ConditionalOnMissingBean
    public DatabaseResilienceInterceptor databaseResilienceInterceptor(
            ObjectProvider<DatabaseExceptionClassifier> classifierProvider,
            DatabaseOperationMetrics metrics,
            RetryTemplateFactory retryTemplateFactory) {

        List<DatabaseExceptionClassifier> sorted = StreamSupport
                .stream(classifierProvider.spliterator(), false)
                .sorted(AnnotationAwareOrderComparator.INSTANCE)
                .toList();

        return new DatabaseResilienceInterceptor(sorted, metrics, retryTemplateFactory);
    }

    /**
     * Provides the AspectJ aspect for explicit {@code @ResilientRepository} support.
     *
     * @param interceptor the shared resilience interceptor
     * @return the resilience aspect
     */
    @Bean
    @ConditionalOnMissingBean
    public DatabaseResilienceAspect databaseResilienceAspect(
            DatabaseResilienceInterceptor interceptor) {
        return new DatabaseResilienceAspect(interceptor);
    }

    /**
     * Global advisor: zero-annotation adoption for every {@code @Repository} and
     * {@code @Service} in the configured base packages.
     *
     * <p>Explicitly excludes beans already annotated with {@code @ResilientRepository}
     * to prevent double-advising (the AspectJ aspect handles those).
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "aegis.db.resilience",
            name  = "auto-apply",
            havingValue = "true",
            matchIfMissing = true)
    public AspectJExpressionPointcutAdvisor globalDbResilienceAdvisor(
            DatabaseResilienceInterceptor interceptor,
            DatabaseResilienceProperties props) {

        String basePackageFilter = buildBasePackageExpression(props.getBasePackages());
        String expression =
                "(" + basePackageFilter + ")" +
                " && (@within(org.springframework.stereotype.Repository)" +
                "     || @within(org.springframework.stereotype.Service))" +
                " && !@within(io.aegis.db.resilience.annotation.ResilientRepository)";

        AspectJExpressionPointcutAdvisor advisor = new AspectJExpressionPointcutAdvisor();
        advisor.setExpression(expression);
        advisor.setAdvice(interceptor);
        advisor.setOrder(Ordered.LOWEST_PRECEDENCE - 200);
        return advisor;
    }

    private String buildBasePackageExpression(List<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return "within(*..*) ";
        }
        return packages.stream()
                .map(pkg -> "within(" + pkg + "..*)")
                .reduce((a, b) -> a + " || " + b)
                .orElse("within(*..*) ");
    }
}
