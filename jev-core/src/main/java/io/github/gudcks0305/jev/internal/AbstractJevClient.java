package io.github.gudcks0305.jev.internal;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gudcks0305.jev.*;
import io.github.gudcks0305.jev.spi.JevTransport;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

public abstract class AbstractJevClient implements JevClient {
    private final JevTransport transport;
    private final boolean ownsTransport;
    private final URI endpoint;
    private final URI baseUrl;
    private final String model;
    private final Map<String, String> headers;
    private final WireFormat format;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<?>> calls = ConcurrentHashMap.newKeySet();

    protected AbstractJevClient(ClientBuilder.Config config, boolean gateway) {
        this(config, gateway ? WireFormat.VERCEL : WireFormat.TYPESAFE);
    }

    protected AbstractJevClient(ClientBuilder.Config config, WireFormat format) {
        this.transport = config.transport;
        this.ownsTransport = config.ownsTransport;
        this.endpoint = config.endpoint;
        this.baseUrl = config.baseUrl;
        this.model = config.model;
        this.format = Objects.requireNonNull(format, "format");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("Authorization", "Bearer " + config.apiKey);
        values.put("Content-Type", "application/json");
        values.put("Accept", "application/json");
        values.put("User-Agent", "jev-java/0.7.0");
        if (format == WireFormat.VERCEL || format == WireFormat.VERCEL_DECISION) {
            values.put("ai-gateway-protocol-version", "0.0.1");
            values.put("ai-gateway-auth-method", "api-key");
            values.put(format == WireFormat.VERCEL_DECISION
                    ? "ai-decision-model-specification-version" : "ai-evaluation-model-specification-version", "4");
            values.put("ai-model-id", model);
        }
        this.headers = Map.copyOf(values);
    }

    @Override public CompletableFuture<Evaluation> evaluateAsync(Object state, Question<?>... questions) {
        if (closed.get()) return CompletableFuture.failedFuture(closedError());
        Map<String, Question<?>> indexed = indexQuestions(questions);
        return executeAsync(() -> encodeRequest(state, indexed),
                response -> EvaluationCodec.response(response, indexed, model, format));
    }

    /** Validates and indexes a non-empty batch of distinct questions in submission order. */
    protected static Map<String, Question<?>> indexQuestions(Question<?>... questions) {
        Objects.requireNonNull(questions, "Questions are required");
        if (questions.length == 0) throw new IllegalArgumentException("At least one question is required");
        Map<String, Question<?>> indexed = new LinkedHashMap<>();
        for (Question<?> question : questions) {
            Objects.requireNonNull(question, "Question cannot be null");
            if (indexed.putIfAbsent(question.id(), question) != null) throw new IllegalArgumentException("Duplicate question id");
        }
        return indexed;
    }

    /** Provider-specific input wrappers can override encoding while retaining shared lifecycle. */
    protected JsonNode encodeRequest(Object state, Map<String, Question<?>> questions) {
        return EvaluationCodec.request(state, questions, model, format);
    }

    /** Configured model for provider-specific request encoders. */
    protected final String configuredModel() { return model; }

    /** Normalized base URL when configured via baseUrl, or null when a full endpoint override is used. */
    protected final URI configuredBaseUrl() { return baseUrl; }

    /** Returns whether this client has already been closed. */
    protected final boolean isClosed() { return closed.get(); }

    /** Returns the canonical closed-client exception. */
    protected static JevException closedClientError() { return closedError(); }

    /** Shared lifecycle for typed provider requests; cancellation reaches the transport. */
    protected final <T> CompletableFuture<T> executeAsync(
            Supplier<? extends JsonNode> encoder, Function<JsonNode, T> decoder) {
        return executeAsync(encoder, decoder, Map.of());
    }

    /** Shared lifecycle for typed provider requests with optional per-call headers. */
    protected final <T> CompletableFuture<T> executeAsync(
            Supplier<? extends JsonNode> encoder, Function<JsonNode, T> decoder, Map<String, String> extraHeaders) {
        if (closed.get()) return CompletableFuture.failedFuture(closedError());
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(decoder, "decoder");
        Objects.requireNonNull(extraHeaders, "extraHeaders");
        JsonNode body = encoder.get();
        boolean addImageHeader = format == WireFormat.TYPESAFE && body.has("assets");
        Map<String, String> requestHeaders = headers;
        if (addImageHeader || !extraHeaders.isEmpty()) {
            var merged = new LinkedHashMap<>(headers);
            merged.putAll(extraHeaders);
            if (addImageHeader) merged.put("TypeSafe-Danger-Zone-Images", "true");
            requestHeaders = Map.copyOf(merged);
        }
        Map<String, String> finalHeaders = requestHeaders;
        return dispatchCall(() -> transport.post(endpoint, finalHeaders, body), decoder);
    }

    /** Shared lifecycle for typed provider GET requests without Content-Type or POST danger-zone headers. */
    protected final <T> CompletableFuture<T> executeGetAsync(URI target, Function<JsonNode, T> decoder) {
        if (closed.get()) return CompletableFuture.failedFuture(closedError());
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(decoder, "decoder");
        Map<String, String> getHeaders = new LinkedHashMap<>(headers);
        getHeaders.remove("Content-Type");
        Map<String, String> finalHeaders = Map.copyOf(getHeaders);
        return dispatchCall(() -> transport.get(target, finalHeaders), decoder);
    }

    private <T> CompletableFuture<T> dispatchCall(
            Supplier<CompletableFuture<JsonNode>> invoker, Function<JsonNode, T> decoder) {
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<CompletableFuture<JsonNode>> source = new AtomicReference<>();
        calls.add(result);
        result.whenComplete((value, failure) -> {
            calls.remove(result);
            CompletableFuture<JsonNode> pending = source.get();
            if (pending != null && !pending.isDone()) pending.cancel(true);
        });
        if (closed.get()) result.completeExceptionally(closedError());
        if (result.isDone()) return result;
        try {
            CompletableFuture<JsonNode> pending = invoker.get();
            source.set(pending);
            if (result.isDone()) pending.cancel(true);
            pending.whenComplete((response, failure) -> {
                if (result.isDone()) return;
                if (failure != null) result.completeExceptionally(failure);
                else {
                    try { result.complete(decoder.apply(response)); }
                    catch (JevException ex) { result.completeExceptionally(ex); }
                    catch (RuntimeException ex) { result.completeExceptionally(new JevException(JevException.Kind.PROTOCOL, "Invalid evaluation response")); }
                }
            });
        } catch (RuntimeException ex) {
            result.completeExceptionally(ex);
        }
        return result;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        calls.forEach(call -> call.completeExceptionally(closedError()));
        if (ownsTransport) transport.close();
    }

    private static JevException closedError() { return new JevException(JevException.Kind.CLOSED, "Jev client is closed"); }
}
