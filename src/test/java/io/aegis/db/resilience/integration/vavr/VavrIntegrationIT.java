package io.aegis.db.resilience.integration.vavr;

import io.aegis.db.resilience.domain.DataIntegrityException;
import io.aegis.db.resilience.domain.DataOperationException;
import io.vavr.control.Either;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = VavrIntegrationIT.VavrTestApp.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "aegis.db.resilience.retry.max-attempts=1"
        }
)
class VavrIntegrationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    VavrProductService productService;

    @Autowired
    VavrProductRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void functionalExceptionHandling_returnsLeftInsteadOfThrowing() {
        // Success case
        Either<DataOperationException, VavrProduct> first = productService.createProduct("SKU-001");
        assertThat(first.isRight()).isTrue();
        assertThat(first.get().getSku()).isEqualTo("SKU-001");

        // Failure case: Unique constraint violation on SKU
        Either<DataOperationException, VavrProduct> duplicate = productService.createProduct("SKU-001");

        // The exception was gracefully squashed and returned as Left!
        assertThat(duplicate.isLeft()).isTrue();
        assertThat(duplicate.getLeft()).isInstanceOf(DataIntegrityException.class);
        assertThat(((DataIntegrityException) duplicate.getLeft()).violationType())
                .isEqualTo(DataIntegrityException.ViolationType.UNIQUE);
        
        // Assert it doesn't leak out of the bounds
        assertThatThrownBy(duplicate::get)
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @EnableJpaRepositories(
            basePackageClasses = VavrProductRepository.class,
            considerNestedRepositories = true)
    @EntityScan(basePackageClasses = VavrProduct.class)
    @org.springframework.context.annotation.Import({VavrProductService.class})
    static class VavrTestApp {}

    @Entity(name = "VavrProduct")
    static class VavrProduct {
        @Id
        private UUID id;
        @jakarta.persistence.Column(unique = true)
        private String sku;

        protected VavrProduct() {}
        public VavrProduct(UUID id, String sku) {
            this.id = id;
            this.sku = sku;
        }

        public String getSku() { return sku; }
    }

    @Repository
    interface VavrProductRepository extends JpaRepository<VavrProduct, UUID> {}

    @Service
    static class VavrProductService {
        private final VavrProductRepository repo;

        VavrProductService(VavrProductRepository repo) {
            this.repo = repo;
        }

        @Transactional
        public Either<DataOperationException, VavrProduct> createProduct(String sku) {
            // Note: Returning Either.right() wraps the JPA entity. 
            // If the commit fails, the Interceptor catches and returns Either.left().
            return Either.right(repo.save(new VavrProduct(UUID.randomUUID(), sku)));
        }
    }
}
