package io.github.gudcks0305.jev.typesafe;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gudcks0305.jev.Evaluation;
import io.github.gudcks0305.jev.JevException;
import io.github.gudcks0305.jev.Question;
import io.github.gudcks0305.jev.internal.AbstractJevClient;
import io.github.gudcks0305.jev.internal.CancellableFutures;
import io.github.gudcks0305.jev.internal.ClientBuilder;
import io.github.gudcks0305.jev.internal.EvaluationCodec;
import io.github.gudcks0305.jev.internal.WireFormat;
import io.github.gudcks0305.jev.schema.JevSchema;
import io.github.gudcks0305.jev.schema.TypedEvaluation;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** TypeSafe's public /v1/systemone and /v1/models APIs. Thread safe; reuse a client across requests. */
public final class TypeSafeJevClient extends AbstractJevClient {
    private static final String LONG_CONTEXT_HEADER = "TypeSafe-Danger-Zone-Long-Context";

    private final boolean longContext;
    private final URI modelsEndpoint;

    private TypeSafeJevClient(Builder builder) {
        super(builder.configuration(), WireFormat.TYPESAFE);
        this.longContext = builder.longContext;
        URI baseUrl = configuredBaseUrl();
        this.modelsEndpoint = builder.modelsEndpoint != null
                ? builder.modelsEndpoint
                : (baseUrl != null ? URI.create(baseUrl + "/v1/models") : null);
    }

    public static Builder builder() { return new Builder(); }

    @Override
    public CompletableFuture<Evaluation> evaluateAsync(Object state, Question<?>... questions) {
        return evaluateAsync(state, TypeSafeRequestOptions.defaults(), questions);
    }

    /** Evaluates questions with per-request model or long-context overrides. */
    public CompletableFuture<Evaluation> evaluateAsync(
            Object state, TypeSafeRequestOptions options, Question<?>... questions) {
        Objects.requireNonNull(options, "options");
        if (isClosed()) return CompletableFuture.failedFuture(closedClientError());
        Map<String, Question<?>> indexed = indexQuestions(questions);
        String targetModel = options.model().orElse(configuredModel());
        boolean useLongContext = options.longContext().orElse(longContext);
        Map<String, String> extraHeaders = useLongContext
                ? Map.of(LONG_CONTEXT_HEADER, "true") : Map.of();
        return executeAsync(
                () -> EvaluationCodec.request(state, indexed, targetModel, WireFormat.TYPESAFE),
                response -> EvaluationCodec.response(response, indexed, targetModel, WireFormat.TYPESAFE),
                extraHeaders);
    }

    /** Blocking evaluation with per-request options. Use evaluateAsync on reactive event loops. */
    public Evaluation evaluate(Object state, TypeSafeRequestOptions options, Question<?>... questions) {
        return awaitFuture(evaluateAsync(state, options, questions), "Evaluation");
    }

    /** Evaluates an annotated record schema with per-request TypeSafe options. */
    public <T> CompletableFuture<TypedEvaluation<T>> evaluateAsync(
            Object state, TypeSafeRequestOptions options, JevSchema<T> schema) {
        Objects.requireNonNull(schema, "schema");
        return CancellableFutures.map(
                evaluateAsync(state, options, schema.questions().toArray(Question<?>[]::new)),
                schema::decode);
    }

    /** Blocking record evaluation with per-request TypeSafe options. */
    public <T> TypedEvaluation<T> evaluate(
            Object state, TypeSafeRequestOptions options, JevSchema<T> schema) {
        Objects.requireNonNull(schema, "schema");
        return schema.decode(evaluate(state, options, schema.questions().toArray(Question<?>[]::new)));
    }

    /** Evaluates an annotated record class asynchronously with per-request TypeSafe options. */
    public <T> CompletableFuture<TypedEvaluation<T>> evaluateAsync(
            Object state, TypeSafeRequestOptions options, Class<T> recordType) {
        return evaluateAsync(state, options, JevSchema.of(recordType));
    }

    /** Blocking record evaluation with per-request TypeSafe options. */
    public <T> TypedEvaluation<T> evaluate(
            Object state, TypeSafeRequestOptions options, Class<T> recordType) {
        return evaluate(state, options, JevSchema.of(recordType));
    }

    /** Lists models and aliases available to the authenticated TypeSafe account via {@code GET /v1/models}. */
    public CompletableFuture<TypeSafeModelList> listModelsAsync() {
        if (isClosed()) return CompletableFuture.failedFuture(closedClientError());
        if (modelsEndpoint == null) {
            throw new IllegalStateException(
                    "listModels requires baseUrl or modelsEndpoint when endpoint override is configured");
        }
        return executeGetAsync(modelsEndpoint, TypeSafeJevClient::decodeModels);
    }

    /** Blocking wrapper for {@link #listModelsAsync()}. */
    public TypeSafeModelList listModels() {
        return awaitFuture(listModelsAsync(), "Model listing");
    }

    private static TypeSafeModelList decodeModels(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new JevException(JevException.Kind.PROTOCOL, "Expected an object response");
        }
        JsonNode array = body.get("models");
        if (array == null || !array.isArray()) {
            throw new JevException(JevException.Kind.PROTOCOL, "Missing or invalid models array");
        }
        List<TypeSafeModel> models = new ArrayList<>(array.size());
        for (JsonNode entry : array) {
            if (!entry.isObject()) {
                throw new JevException(JevException.Kind.PROTOCOL, "Invalid model metadata entry");
            }
            JsonNode name = entry.get("name");
            JsonNode description = entry.get("description");
            JsonNode releaseDate = entry.get("release_date");
            if (name == null || !name.isTextual() || name.textValue().isBlank()
                    || description == null || !description.isTextual()
                    || releaseDate == null || !releaseDate.isTextual() || releaseDate.textValue().isBlank()) {
                throw new JevException(JevException.Kind.PROTOCOL, "Missing or invalid model metadata fields");
            }
            models.add(new TypeSafeModel(name.textValue(), description.textValue(), releaseDate.textValue()));
        }
        return new TypeSafeModelList(models, body);
    }

    private static <T> T awaitFuture(CompletableFuture<T> future, String operation) {
        try {
            return future.get();
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new JevException(JevException.Kind.CONNECTION, operation + " interrupted");
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            throw new JevException(JevException.Kind.CONNECTION, operation + " failed");
        }
    }

    public static final class Builder extends ClientBuilder<Builder> {
        private boolean longContext;
        private URI modelsEndpoint;

        private Builder() {}

        /** Enables {@code TypeSafe-Danger-Zone-Long-Context: true} by default on all requests. */
        public Builder longContext(boolean longContext) {
            this.longContext = longContext;
            return this;
        }

        /** Explicit full URL for {@code GET /v1/models}, useful when {@link #endpoint(URI)} is customized. */
        public Builder modelsEndpoint(URI modelsEndpoint) {
            ClientBuilder.validateEndpoint(modelsEndpoint, "modelsEndpoint");
            this.modelsEndpoint = modelsEndpoint;
            return this;
        }

        @Override protected Builder self() { return this; }

        private Config configuration() {
            return configure("TYPESAFE_API_KEY", "jev-latest", URI.create("https://api.typesafe.ai"), "v1/systemone");
        }

        public TypeSafeJevClient build() { return new TypeSafeJevClient(this); }
    }
}
