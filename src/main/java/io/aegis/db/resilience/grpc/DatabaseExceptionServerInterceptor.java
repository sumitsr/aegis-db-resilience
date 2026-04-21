package io.aegis.db.resilience.grpc;

import io.aegis.db.resilience.domain.*;
import io.grpc.*;

/**
 * gRPC server interceptor that maps domain database exceptions to appropriate
 * {@link Status} codes before they reach the wire.
 *
 * <p>Register as a gRPC global server interceptor (e.g., via {@code @GrpcGlobalServerInterceptor}
 * when using the {@code grpc-spring-boot-starter}).
 *
 * <p>Status mapping rationale:
 * <ul>
 *   <li>{@code NOT_FOUND} → {@code Status.NOT_FOUND}
 *   <li>{@code CONFLICT / INTEGRITY} → {@code Status.ALREADY_EXISTS} for duplicate keys,
 *       {@code Status.FAILED_PRECONDITION} for other integrity violations
 *   <li>{@code TRANSIENT / TIMEOUT / UNAVAILABLE} → {@code Status.UNAVAILABLE} with
 *       {@code retry-able} trailer set to true per gRPC retry convention
 *   <li>{@code PROGRAMMING_ERROR} → {@code Status.INTERNAL} (no detail leaked)
 * </ul>
 */
public class DatabaseExceptionServerInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
                next.startCall(call, headers)) {

            @Override
            public void onHalfClose() {
                try {
                    super.onHalfClose();
                } catch (DataOperationException ex) {
                    StatusRuntimeException sre = toStatusException(ex);
                    call.close(sre.getStatus(), sre.getTrailers() != null ? sre.getTrailers() : new Metadata());
                }
            }
        };
    }

    private StatusRuntimeException toStatusException(DataOperationException ex) {
        return switch (ex) {
            case DataNotFoundException ignored ->
                    Status.NOT_FOUND
                            .withDescription("Resource not found")
                            .asRuntimeException();

            case DataIntegrityException die when
                    die.violationType() == DataIntegrityException.ViolationType.UNIQUE ->
                    Status.ALREADY_EXISTS
                            .withDescription("A resource with these values already exists")
                            .asRuntimeException();

            case DataIntegrityException ignored ->
                    Status.FAILED_PRECONDITION
                            .withDescription("Data integrity constraint violated")
                            .asRuntimeException();

            case DataConflictException ignored ->
                    Status.ABORTED
                            .withDescription("Concurrent modification conflict; re-read and retry")
                            .asRuntimeException();

            case TransientDataOperationException ignored ->
                    retryableUnavailable();

            case DataUnavailableException ignored ->
                    retryableUnavailable();

            case DataTimeoutException ignored ->
                    Status.DEADLINE_EXCEEDED
                            .withDescription("Database operation timed out")
                            .asRuntimeException();

            case DataAccessProgrammingException ignored ->
                    Status.INTERNAL
                            .withDescription("Internal server error")
                            .asRuntimeException();

            default ->
                    Status.INTERNAL
                            .withDescription("Internal server error")
                            .asRuntimeException();
        };
    }

    private StatusRuntimeException retryableUnavailable() {
        Metadata trailers = new Metadata();
        // gRPC retry-able key per https://grpc.github.io/grpc/core/md_doc_statuscodes.html
        trailers.put(
                Metadata.Key.of("grpc-retry-pushback-ms", Metadata.ASCII_STRING_MARSHALLER),
                "1000");
        return Status.UNAVAILABLE
                .withDescription("Service temporarily unavailable; retry after backoff")
                .asRuntimeException(trailers);
    }
}
