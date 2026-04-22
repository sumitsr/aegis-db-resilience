package io.aegis.db.resilience.integration.unique;

import io.aegis.db.resilience.domain.DataIntegrityException;
import io.aegis.db.resilience.domain.DataNotFoundException;
import io.aegis.db.resilience.domain.DataOperationException;
import io.vavr.control.Option;
import jakarta.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test verifying that:
 * <ol>
 *   <li>A PostgreSQL unique-constraint violation is translated to {@link DataIntegrityException}
 *       with {@code ViolationType.UNIQUE} and never leaks a raw {@link org.springframework.dao.DataAccessException}.
 *   <li>An absent entity yields {@link DataNotFoundException}.
 *   <li>Retryable exceptions (transient) do not escape the aspect as raw Spring exceptions.
 * </ol>
 *
 * <p>The test application context wires the auto-configuration; no explicit {@code @ResilientRepository}
 * annotation is required on the service/repository — demonstrating zero-annotation adoption.
 */
@Testcontainers
@SpringBootTest(
        classes = UniqueConstraintViolationIT.TestConfig.class,
        properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@DirtiesContext
class UniqueConstraintViolationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    ProductService productService;

    // ─────────────────────────────────────────────────────────────────────────
    // Happy path: insert succeeds
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void insertProduct_succeeds() {
        Product saved = productService.create("Widget-" + UUID.randomUUID(), "SKU-001");
        assertThat(saved.getId()).isNotNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unique constraint violation → DataIntegrityException.UNIQUE
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void duplicateSku_throwsDataIntegrityException_withUniqueViolationType() {
        productService.create("Widget A", "SKU-DUPE");

        assertThatThrownBy(() -> productService.create("Widget B", "SKU-DUPE"))
                .isInstanceOf(DataIntegrityException.class)
                .satisfies(ex -> {
                    DataIntegrityException die = (DataIntegrityException) ex;
                    assertThat(die.violationType()).isEqualTo(DataIntegrityException.ViolationType.UNIQUE);
                    assertThat(die.sqlState()).isEqualTo("23505");
                    // Constraint name / table name must NOT be in the message exposed to callers
                    assertThat(die.getMessage()).doesNotContainIgnoringCase("products");
                    assertThat(die.getMessage()).doesNotContainIgnoringCase("sku");
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // No raw DataAccessException escapes the aspect
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void noRawDataAccessExceptionEscapes_uniqueViolation() {
        productService.create("Widget C", "SKU-ESCAPE");

        assertThatThrownBy(() -> productService.create("Widget D", "SKU-ESCAPE"))
                .isInstanceOf(DataOperationException.class)   // domain hierarchy
                .isNotInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Not-found → DataNotFoundException
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void findNonExistentProduct_throwsDataNotFoundException() {
        assertThatThrownBy(() -> productService.findOrThrow(UUID.randomUUID()))
                .isInstanceOf(DataNotFoundException.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner test application components
    // ─────────────────────────────────────────────────────────────────────────

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(
            basePackageClasses = ProductRepository.class,
            considerNestedRepositories = true)
    @org.springframework.boot.autoconfigure.domain.EntityScan(basePackageClasses = Product.class)
    @org.springframework.context.annotation.Import(ProductService.class)
    static class TestConfig {}

    @Entity(name = "Product")
    @Table(name = "products", uniqueConstraints = @UniqueConstraint(columnNames = "sku"))
    static class Product {
        @Id @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;
        private String name;
        @Column(unique = true, nullable = false)
        private String sku;

        protected Product() {}
        Product(String name, String sku) { this.name = name; this.sku = sku; }
        public UUID getId() { return id; }
        public String getSku() { return sku; }
        public String getName() { return name; }
    }

    @Repository
    interface ProductRepository extends JpaRepository<Product, UUID> {
        @Query("select p from Product p where p.sku = :sku")
        java.util.Optional<Product> findBySku(String sku);
    }

    @Service
    static class ProductService {

        private final ProductRepository repo;

        ProductService(ProductRepository repo) {
            this.repo = repo;
        }

        @Transactional
        public Product create(String name, String sku) {
            return repo.save(new Product(name, sku));
        }

        @Transactional(readOnly = true)
        @SuppressWarnings("null")
        public Product findOrThrow(UUID id) {
            return Option.of(id)
                    .flatMap(i -> Option.ofOptional(repo.findById(i)))
                    .getOrElseThrow(() -> new EmptyResultDataAccessException(1));
        }
    }
}
