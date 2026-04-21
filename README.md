# Aegis DB Resilience Starter

A zero-annotation, auto-configuring Spring Boot 3.x starter that transforms raw database exceptions into a clean domain exception hierarchy, adds intelligent retry with exponential back-off, and emits structured observability signals — all without changing a single line of your repository or service code.

---

## Table of Contents

- [Why Aegis?](#why-aegis)
- [Architecture Overview](#architecture-overview)
- [Getting Started](#getting-started)
- [Exception Hierarchy](#exception-hierarchy)
- [Exception Classification](#exception-classification)
- [Retry Behaviour](#retry-behaviour)
- [Annotations](#annotations)
- [Configuration Reference](#configuration-reference)
- [Observability](#observability)
- [HTTP / REST Integration](#http--rest-integration)
- [gRPC Integration](#grpc-integration)
- [Kafka Integration](#kafka-integration)
- [Custom Classifiers](#custom-classifiers)
- [Testing](#testing)
- [Security Model](#security-model)

---

## Why Aegis?

Without this starter, a single unique-constraint violation can expose:

- Raw `DataIntegrityViolationException` with the constraint name and table schema in the message
- Stack traces leaking through HTTP responses
- Inconsistent retry logic copy-pasted across every repository
- No Micrometer metrics or OTel span events

Aegis intercepts every `@Repository` and `@Service` bean automatically, classifies the failure precisely, retries transient errors with exponential back-off, emits Micrometer counters and OpenTelemetry span events, and finally re-throws a clean domain exception that your REST/gRPC/Kafka layer maps to the correct wire format.

---

## Architecture Overview

```
@Service / @Repository bean
         │
         ▼
DatabaseResilienceInterceptor  ◄── AOP advice (auto-applied or via @ResilientRepository)
         │
         ├─► DatabaseExceptionClassifier chain
         │        └─► DefaultDatabaseExceptionClassifier
         │              ├── SQLState (23xxx, 40xxx, 08xxx, …)
         │              ├── Spring DataAccessException hierarchy
         │              └── JPA / Hibernate exception types
         │
         ├─► DatabaseOperationMetrics
         │        ├── Micrometer counter  (db.operation.failures)
         │        ├── OTel span attributes + event
         │        └── MDC-enriched structured log
         │
         └─► RetryTemplateFactory  (transient/timeout/unavailable only)
                  └── ExponentialBackoffRetryPolicy

Domain exception thrown to caller:
  DataIntegrityException  │ DataNotFoundException  │ DataConflictException
  DataTimeoutException    │ DataUnavailableException│ DataAccessProgrammingException
  TransientDataOperationException

REST layer:        DatabaseExceptionAdvice  →  RFC 7807 ProblemDetail
gRPC layer:        DatabaseExceptionServerInterceptor  →  gRPC Status codes
Kafka layer:       ResilientKafkaListenerErrorHandler  →  retry / DLT routing
```

---

## Getting Started

### 1. Add the dependency

**Gradle**

```groovy
implementation 'io.aegis:aegis-db-resilience:1.0.0-SNAPSHOT'
```

**Maven**

```xml
<dependency>
    <groupId>io.aegis</groupId>
    <artifactId>aegis-db-resilience</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. That's it

The starter auto-configures itself via Spring Boot's `AutoConfiguration` mechanism. Every `@Repository` and `@Service` bean in your application context is automatically covered. No annotations, no XML, no extra `@EnableXxx`.

### Building from source

Requirements: JDK 21+, no local Gradle installation needed (wrapper included).

```bash
git clone https://github.com/sumitsr/aegis-db-resilience.git
cd aegis-db-resilience
./gradlew build               # compile + unit tests
./gradlew test                # all tests including Testcontainers IT (requires Docker)
./gradlew jar                 # produces build/libs/aegis-db-resilience-*.jar
```

> **JDK note:** the Gradle daemon is pinned to JDK 21 via `gradle.properties`
> (`org.gradle.java.home`). Update that path if your JDK 21 is installed elsewhere.

---

## Exception Hierarchy

All domain exceptions extend `DataOperationException`, which carries three diagnostic fields that are safe to log server-side but are **never leaked to clients**:

| Field        | Description                                  |
|--------------|----------------------------------------------|
| `sqlState`   | SQL standard state code (e.g. `23505`)       |
| `errorCode`  | Vendor-specific error code (e.g. PostgreSQL) |
| `operation`  | Method name that triggered the failure       |
| `repository` | Simple class name of the bean                |

### Exception types and default HTTP/gRPC mapping

| Exception                         | Category             | Retried | HTTP  | gRPC                   |
|-----------------------------------|----------------------|---------|-------|------------------------|
| `DataIntegrityException`          | `INTEGRITY_*`        | No      | 409   | `ALREADY_EXISTS` / `FAILED_PRECONDITION` |
| `DataNotFoundException`           | `NOT_FOUND`          | No      | 404   | `NOT_FOUND`            |
| `DataConflictException`           | `CONFLICT`           | No      | 409   | `ABORTED`              |
| `DataTimeoutException`            | `TIMEOUT`            | Yes     | 504   | `DEADLINE_EXCEEDED`    |
| `DataUnavailableException`        | `UNAVAILABLE`        | Yes     | 503   | `UNAVAILABLE`          |
| `TransientDataOperationException` | `TRANSIENT`          | Yes     | 503   | `UNAVAILABLE`          |
| `DataAccessProgrammingException`  | `PROGRAMMING_ERROR`  | No      | 500   | `INTERNAL`             |

`DataIntegrityException` additionally exposes a `ViolationType` enum:

```
UNIQUE  |  FOREIGN_KEY  |  NOT_NULL  |  CHECK  |  EXCLUSION  |  GENERIC
```

---

## Exception Classification

The built-in `DefaultDatabaseExceptionClassifier` uses a strict priority order — it never message-matches strings:

1. **SQLState** (most authoritative — vendor-neutral)
2. **Spring `DataAccessException` hierarchy**
3. **JPA / Hibernate exception types**
4. **Raw `SQLException`** translated via `SQLExceptionTranslator`

### SQLState families covered

| SQLState prefix / code | Classification         |
|------------------------|------------------------|
| `08xxx`                | `UNAVAILABLE`          |
| `23505`                | `INTEGRITY_UNIQUE`     |
| `23503`                | `INTEGRITY_FK`         |
| `23502`                | `INTEGRITY_NOT_NULL`   |
| `23514`                | `INTEGRITY_CHECK`      |
| `23P01`                | `INTEGRITY_EXCLUSION`  |
| `23xxx` (other)        | `INTEGRITY_GENERIC`    |
| `40001`                | `TRANSIENT` (serialization failure) |
| `40P01`                | `TRANSIENT` (deadlock) |
| `55P03`                | `TRANSIENT` (lock not available) |
| `57014`                | `TIMEOUT` (query cancelled) |

---

## Retry Behaviour

Only **transient** failures are retried. Non-retryable exceptions (integrity violations, not-found, conflicts, programming errors) are never retried and propagate immediately.

Retried categories: `TRANSIENT`, `TIMEOUT`, `UNAVAILABLE`

The retry template uses **exponential back-off with a cap**:

```
attempt 1: immediate
attempt 2: backoff-ms
attempt 3: backoff-ms × multiplier  (capped at max-backoff-ms)
```

If all attempts are exhausted the final domain exception propagates to the caller.

---

## Annotations

### `@ResilientRepository`

Applied at the **class level**. Activates the full resilience pipeline (classification, logging, metrics, OTel, retry) for every method.

> You only need this annotation when you want **per-class retry overrides**. All `@Repository` and `@Service` beans are already covered automatically by the global advisor.

```java
@ResilientRepository(retryPolicy = @RetryPolicy(maxAttempts = 5, backoffMs = 100))
@Repository
public class OrderRepository { ... }
```

### `@RetryPolicy`

Can be placed on a **class** (via `@ResilientRepository`) or directly on a **method** to override global defaults for that specific call.

```java
@RetryPolicy(maxAttempts = 1, disabled = false)
public Order findById(UUID id) { ... }
```

| Attribute      | Default | Description                                     |
|----------------|---------|-------------------------------------------------|
| `maxAttempts`  | `-1`    | Total attempts. `-1` uses the global default.   |
| `backoffMs`    | `-1`    | Initial backoff in ms. `-1` = global default.   |
| `maxBackoffMs` | `-1`    | Backoff cap in ms. `-1` = global default.       |
| `multiplier`   | `-1`    | Exponential multiplier. `-1` = global default.  |
| `disabled`     | `false` | Set `true` to disable retry for this bean/method.|

---

## Configuration Reference

Include `application-resilience-defaults.yml` in your project for IDE auto-complete, or override any key in your own `application.yml`:

```yaml
aegis:
  db:
    resilience:

      # Disable the global auto-apply advisor (default: true).
      # @ResilientRepository beans are still covered.
      auto-apply: true

      # Restrict auto-apply to specific packages.
      # Empty = entire application context.
      base-packages:
        - com.example.orders
        - com.example.inventory

      retry:
        max-attempts: 3       # total attempts including the first
        backoff-ms: 200       # initial backoff (ms)
        max-backoff-ms: 2000  # backoff ceiling (ms)
        multiplier: 2.0       # exponential growth factor
```

### Built-in profile overrides

The starter ships with sensible production and test profiles. Activate them or copy the values:

```yaml
# production — more aggressive retry
spring.config.activate.on-profile: production
aegis.db.resilience.retry:
  max-attempts: 5
  backoff-ms: 500
  max-backoff-ms: 10000
  multiplier: 2.5

# test — no retry delay so tests don't hang
spring.config.activate.on-profile: test
aegis.db.resilience.retry:
  max-attempts: 1
  backoff-ms: 0
  max-backoff-ms: 0
  multiplier: 1.0
```

---

## Observability

### Micrometer — `db.operation.failures` counter

Every classified failure increments a counter with the following tags:

| Tag              | Example value          |
|------------------|------------------------|
| `classification` | `INTEGRITY_UNIQUE`     |
| `repository`     | `OrderRepository`      |
| `method`         | `save`                 |
| `sqlstate`       | `23505`                |

Prometheus scrape example:

```
db_operation_failures_total{classification="INTEGRITY_UNIQUE",repository="OrderRepository",method="save",sqlstate="23505"} 3.0
```

### OpenTelemetry span enrichment

On every failure the current OTel span receives:

- `span.status` → `ERROR`
- `span.event` → `db.operation.failure`
- Attributes: `db.error.classification`, `db.error.sqlstate`, `db.error.operation`, `db.error.repository`

### MDC-enriched structured logs

The following MDC keys are set for the duration of the log call and then removed:

```
db.classification  db.operation  db.repository
db.sqlstate        db.errorCode  db.retried
```

Log levels by category:

- `PROGRAMMING_ERROR` → `ERROR`
- Retried failures → `WARN`
- All others → `INFO`

---

## HTTP / REST Integration

`DatabaseExceptionAdvice` (`@RestControllerAdvice`) maps every domain exception to an **RFC 7807 `ProblemDetail`** response. It is auto-registered.

### Response shape

```json
{
  "type": "https://aegis.io/problems/db/integrity-violation",
  "title": "Integrity violation",
  "status": 409,
  "detail": "A resource with these values already exists.",
  "errorCode": "INTEGRITY_VIOLATION",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "repository": "ProductRepository",
  "operation": "save"
}
```

**Security invariants:** no SQL text, constraint names, schema names, or stack traces reach the client.

### Status mapping

| Exception                        | HTTP Status |
|----------------------------------|-------------|
| `DataNotFoundException`          | 404         |
| `DataConflictException`          | 409         |
| `DataIntegrityException`         | 409         |
| `DataTimeoutException`           | 504         |
| `DataUnavailableException`       | 503         |
| `TransientDataOperationException`| 503         |
| `DataAccessProgrammingException` | 500         |

---

## gRPC Integration

`DatabaseExceptionServerInterceptor` converts domain exceptions to gRPC `Status` codes before they reach the wire. Register it as a global server interceptor (e.g. via `@GrpcGlobalServerInterceptor` in `grpc-spring-boot-starter`):

```java
@Bean
@GrpcGlobalServerInterceptor
public DatabaseExceptionServerInterceptor dbExceptionInterceptor() {
    return new DatabaseExceptionServerInterceptor();
}
```

### Status mapping

| Domain Exception                 | gRPC Status               | Notes                                    |
|----------------------------------|---------------------------|------------------------------------------|
| `DataNotFoundException`          | `NOT_FOUND`               |                                          |
| `DataIntegrityException` (UNIQUE)| `ALREADY_EXISTS`          |                                          |
| `DataIntegrityException` (other) | `FAILED_PRECONDITION`     |                                          |
| `DataConflictException`          | `ABORTED`                 |                                          |
| `TransientDataOperationException`| `UNAVAILABLE`             | `grpc-retry-pushback-ms: 1000` trailer   |
| `DataUnavailableException`       | `UNAVAILABLE`             | `grpc-retry-pushback-ms: 1000` trailer   |
| `DataTimeoutException`           | `DEADLINE_EXCEEDED`       |                                          |
| `DataAccessProgrammingException` | `INTERNAL`                | No detail exposed                        |

---

## Kafka Integration

`ResilientKafkaListenerErrorHandler` routes Kafka consumer failures based on domain exception semantics:

| Failure type                         | Action                                              |
|--------------------------------------|-----------------------------------------------------|
| Transient / timeout / unavailable    | Re-throw — container's retry / DLT config takes over|
| `DataNotFoundException`              | Log + skip (poison pill)                            |
| `DataIntegrityException`             | Log + skip (poison pill)                            |
| `DataAccessProgrammingException`     | Log + re-throw for investigation                    |
| Unknown                              | Log + re-throw                                      |

Register on a `@KafkaListener`:

```java
@Bean
public ResilientKafkaListenerErrorHandler resilientKafkaListenerErrorHandler() {
    return new ResilientKafkaListenerErrorHandler();
}

@KafkaListener(topics = "orders", errorHandler = "resilientKafkaListenerErrorHandler")
public void consume(OrderEvent event) { ... }
```

---

## Custom Classifiers

Implement `DatabaseExceptionClassifier` and register it as a Spring bean. Use `@Order` to control priority — lower values run first. The built-in `DefaultDatabaseExceptionClassifier` is `@Order(Integer.MAX_VALUE)`, so custom classifiers always take precedence.

```java
@Component
@Order(100)
public class MyVendorClassifier implements DatabaseExceptionClassifier {

    @Override
    public ClassificationResult classify(Throwable t, String operation, String repository) {
        if (isMyVendorSpecificError(t)) {
            return new ClassificationResult(
                DataUnavailableException.of(t, null, 0, operation, repository),
                ExceptionCategory.UNAVAILABLE,
                true   // retryable
            );
        }
        return null; // delegate to next classifier
    }
}
```

Return `null` to pass classification to the next classifier in the chain.

---

## Testing

### Disable retry in tests

Activate the built-in `test` profile to make retry instant (max 1 attempt, 0 ms backoff):

```yaml
# src/test/resources/application-test.yml
spring.profiles.active: test
```

Or set it in your test class:

```java
@SpringBootTest
@ActiveProfiles("test")
class MyRepositoryTest { ... }
```

### Integration test pattern (Testcontainers)

The starter's own integration tests demonstrate zero-annotation adoption against a real PostgreSQL container:

```java
@Testcontainers
@SpringBootTest
class UniqueConstraintViolationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    ProductService productService;

    @Test
    void duplicateSku_throwsDataIntegrityException() {
        productService.create("Widget A", "SKU-001");

        assertThatThrownBy(() -> productService.create("Widget B", "SKU-001"))
                .isInstanceOf(DataIntegrityException.class)
                .satisfies(ex -> {
                    DataIntegrityException die = (DataIntegrityException) ex;
                    assertThat(die.violationType()).isEqualTo(ViolationType.UNIQUE);
                    assertThat(die.sqlState()).isEqualTo("23505");
                });
    }
}
```

No `@ResilientRepository` annotation is placed on `ProductService` or `ProductRepository` — coverage is automatic.

### Verifying no raw Spring exceptions escape

```java
assertThatThrownBy(() -> service.doSomething())
    .isInstanceOf(DataOperationException.class)                        // domain hierarchy
    .isNotInstanceOf(org.springframework.dao.DataAccessException.class); // never leaks
```

---

## Security Model

- **No SQL text or schema names** reach the client via HTTP or gRPC responses. Only stable `errorCode` tokens are returned.
- `DataAccessProgrammingException` responses always use the generic message `"An internal error occurred. Support has been notified."` — no details exposed.
- `traceId` is the only server-internal value included in responses; it is populated from MDC (`traceId` key set by OTel/Brave) and falls back to `"none"`.
- SQLState codes are logged server-side only and are never included in HTTP response bodies.
- The AOP interceptor applies `Ordered.LOWEST_PRECEDENCE - 200` to run after transaction management but before your application's exception handlers, ensuring every database exception passes through classification.
