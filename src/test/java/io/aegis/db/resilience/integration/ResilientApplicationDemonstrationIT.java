package io.aegis.db.resilience.integration;

import io.aegis.db.resilience.domain.DataIntegrityException;
import jakarta.persistence.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.repository.JpaRepository;
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
 * End-to-end integration test demonstrating the core intended usage:
 * Just handling exceptions strictly through annotations/aspects to translate
 * SQL errors into pure Domain Exceptions (e.g., DataIntegrityException).
 */
@Testcontainers
@SpringBootTest(classes = {
        ResilientApplicationDemonstrationIT.AppConfig.class,
        ResilientApplicationDemonstrationIT.UserService.class,
        ResilientApplicationDemonstrationIT.UserRepository.class
})
@DirtiesContext
class ResilientApplicationDemonstrationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    UserService userService;

    @Test
    void registerUser_success() {
        User saved = userService.registerUser("john.doe@example.com", "John Doe");
        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void registerUser_duplicateEmail_throwsTranslatedException() {
        userService.registerUser("jane.doe@example.com", "Jane Doe");

        // The caller doesn't need to deal with web constraints or low level exceptions.
        // It seamlessly receives a DataIntegrityException.
        assertThatThrownBy(() -> userService.registerUser("jane.doe@example.com", "Jane Doe 2"))
                .isInstanceOf(DataIntegrityException.class)
                .satisfies(ex -> {
                    DataIntegrityException die = (DataIntegrityException) ex;
                    assertThat(die.violationType()).isEqualTo(DataIntegrityException.ViolationType.UNIQUE);
                });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Dummy Application Components (Simulating user's code)
    // ─────────────────────────────────────────────────────────────────────────

    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    static class AppConfig {}

    @Entity
    @Table(name = "users", uniqueConstraints = @UniqueConstraint(columnNames = "email"))
    static class User {
        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        private UUID id;

        @Column(unique = true, nullable = false)
        private String email;

        private String name;

        protected User() {}
        User(String email, String name) { this.email = email; this.name = name; }
        public UUID getId() { return id; }
        public String getEmail() { return email; }
    }

    @Repository
    interface UserRepository extends JpaRepository<User, UUID> {}

    @Service
    static class UserService {
        private final UserRepository repository;

        UserService(UserRepository repository) {
            this.repository = repository;
        }

        @Transactional
        public User registerUser(String email, String name) {
            // Underneath, Unique exception happens here, but Aspect catches and maps it!
            return repository.save(new User(email, name));
        }
    }
}
