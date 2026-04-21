package io.aegis.db.resilience.kafka;

import io.aegis.db.resilience.domain.*;
import org.apache.kafka.clients.consumer.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.KafkaListenerErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.messaging.Message;

/**
 * Kafka {@link ListenerErrorHandler} that reuses domain exception semantics to decide
 * whether a consumer failure is a transient blip (seek-and-retry) or a poison pill
 * (send to DLT / log-and-skip).
 *
 * <p>Register this bean as the {@code errorHandler} on {@code @KafkaListener}:
 * <pre>{@code
 * @KafkaListener(topics = "orders", errorHandler = "resilientKafkaListenerErrorHandler")
 * public void consume(OrderEvent event) { ... }
 * }</pre>
 *
 * <p>Transient / timeout / unavailable failures re-throw so the container's retry/DLT
 * infrastructure can handle them. Integrity, conflict, and not-found failures are
 * logged and swallowed (the message is a confirmed poison-pill for this handler).
 */
public class ResilientKafkaListenerErrorHandler implements KafkaListenerErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ResilientKafkaListenerErrorHandler.class);

    @Override
    public Object handleError(Message<?> message, ListenerExecutionFailedException exception) {
        return handleError(message, exception, null, null);
    }

    @Override
    public Object handleError(
            Message<?> message,
            ListenerExecutionFailedException exception,
            Consumer<?, ?> consumer) {
        return handleError(message, exception, consumer, null);
    }

    @Override
    public Object handleError(
            Message<?> message,
            ListenerExecutionFailedException exception,
            Consumer<?, ?> consumer,
            Acknowledgment acknowledgment) {

        Throwable cause = exception.getCause();

        if (isTransient(cause)) {
            log.warn("Transient DB failure consuming Kafka message — will retry. offset={}",
                    extractOffset(message), cause);
            // Re-throw: container's retry/DLT configuration takes over
            throw exception;
        }

        if (cause instanceof DataNotFoundException || cause instanceof DataIntegrityException) {
            // Poison-pill: the record references non-existent or constraint-violating data.
            // Log with full cause (server-side only) and skip.
            log.error("Poison-pill Kafka message skipped [classification={}] offset={}",
                    cause.getClass().getSimpleName(), extractOffset(message), cause);
            return null;
        }

        if (cause instanceof DataAccessProgrammingException) {
            log.error("Programming error consuming Kafka message — requires investigation. offset={}",
                    extractOffset(message), cause);
            throw exception;
        }

        // Unknown domain exception or non-domain — propagate
        log.error("Unexpected error consuming Kafka message. offset={}", extractOffset(message), cause);
        throw exception;
    }

    private boolean isTransient(Throwable t) {
        return t instanceof TransientDataOperationException
                || t instanceof DataTimeoutException
                || t instanceof DataUnavailableException;
    }

    private Object extractOffset(Message<?> message) {
        Object offset = message.getHeaders().get("kafka_offset");
        return offset != null ? offset : "unknown";
    }
}
