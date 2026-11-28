package io.github.gudcks0305.jev.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Asynchronous HTTP JSON transport. Implementations must propagate cancellation to active requests. */
public interface JevTransport extends AutoCloseable {
    CompletableFuture<JsonNode> post(URI uri, Map<String, String> headers, JsonNode body);

    /** Asynchronous HTTP GET request returning parsed JSON. */
    default CompletableFuture<JsonNode> get(URI uri, Map<String, String> headers) {
        return CompletableFuture.failedFuture(new io.github.gudcks0305.jev.JevException(
                io.github.gudcks0305.jev.JevException.Kind.VALIDATION,
                "Transport does not support GET requests"));
    }

    @Override void close();
}
