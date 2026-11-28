package io.github.gudcks0305.jev.typesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.gudcks0305.jev.*;
import io.github.gudcks0305.jev.schema.JevSchema;
import io.github.gudcks0305.jev.spi.JevTransport;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class TypeSafeJevClientTest {
    enum Team { BILLING, TECHNICAL }
    record MappedTicket(
            @io.github.gudcks0305.jev.schema.JevChoice("Which team?") Team route,
            @io.github.gudcks0305.jev.schema.JevBoolean(value = "Urgent?", threshold = .9) boolean urgent,
            @io.github.gudcks0305.jev.schema.JevScore(value = "Severity?", levels = {"Low", "Medium", "High"}) double score) {}

    @Test void nativeProviderCanEvaluateRecordWithoutManualQuestions() throws Exception {
        Stub transport = new Stub(response());
        try (var client = client(transport)) {
            var result = client.evaluate("state", MappedTicket.class);
            assertEquals(Team.BILLING, result.value().route());
            assertTrue(result.value().urgent());
            assertEquals(1.6, result.value().score());
            assertEquals(3, transport.body.path("questions").size());
        }
    }
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ChoiceQuestion<Team> route = ChoiceQuestion.of("route", "Which team?", Team.class);
    private final NoulQuestion urgent = NoulQuestion.of("urgent", "Is it urgent?");
    private final ScoreQuestion score = ScoreQuestion.of("score", "How severe?", List.of("Low", "Medium", "High"));

    @Test void serializesDirectContractAndMapsTypedAnswers() throws Exception {
        Stub transport = new Stub(response());
        try (var client = client(transport)) {
            Evaluation result = client.evaluate(Map.of("message", "Double charge"), route, urgent, score);
            assertEquals(URI.create("https://api.typesafe.ai/v1/systemone"), transport.uri);
            assertEquals("Bearer test-key", transport.headers.get("Authorization"));
            assertEquals("jev-latest", transport.body.path("model").asText());
            assertEquals("noul", transport.body.at("/questions/urgent/type").asText());
            assertTrue(transport.body.at("/questions/route/criteria/BILLING").isNull());
            ChoiceAnswer<Team> choice = result.answer(route);
            assertEquals(Team.BILLING, choice.choice());
            assertEquals(.85, choice.probabilities().get(Team.BILLING));
            assertEquals(.82, choice.confidence().orElseThrow());
            assertEquals(.92, result.answer(urgent).probability());
            assertTrue(result.answer(urgent).atLeast(.9));
            assertEquals(1.6, result.answer(score).score());
            assertEquals("High", result.answer(score).legend().get(2).asText());
            assertEquals("jev-1.13.0", result.model());
            assertEquals(312, result.usage().inputTokens().orElseThrow());
        }
        assertFalse(transport.closed, "Injected transports remain caller-owned");
    }

    @Test void copiesQuestionCriteriaAndResponseTrees() throws Exception {
        var levels = JSON.createObjectNode().put("description", "Calm");
        var question = ScoreQuestion.of("score", "Severity", List.of(levels, "Angry", "Very angry"));
        levels.put("description", "mutated");
        assertEquals("Calm", question.criteria().get(0).path("description").asText());
        ((ObjectNode) question.criteria().get(0)).put("description", "mutated again");
        assertEquals("Calm", question.criteria().get(0).path("description").asText());
        var criteria = new LinkedHashMap<String, Object>();
        criteria.put("first", null);
        criteria.put("second", Map.of("description", "Second option"));
        var choice = ChoiceQuestion.of("choice", "Pick", criteria);
        criteria.clear();
        assertEquals(2, choice.options().size());
        assertEquals("Second option", choice.criteria().at("/second/description").asText());
        try (var client = client(new Stub(response()))) {
            var result = client.evaluate("state", route, urgent, score);
            ((ObjectNode) result.rawResponse()).removeAll();
            assertTrue(result.rawResponse().has("answers"));
            assertThrows(UnsupportedOperationException.class, () -> result.answer(route).probabilities().clear());
            assertThrows(IllegalArgumentException.class, () -> result.answer(ChoiceQuestion.of("route", "Same id", Team.class)));
        }
    }

    @Test void fullEndpointPreservesPathAndQueryAndKeepsTypeSafeContract() throws Exception {
        Stub stub = new Stub(response());
        URI endpoint = URI.create("https://proxy.example/custom/evaluate/?api-version=1&tenant=a%2Fb");
        try (var client = TypeSafeJevClient.builder().apiKey("test-key").endpoint(endpoint).transport(stub).build()) {
            client.evaluate("state", route, urgent, score);
            assertEquals(endpoint, stub.uri);
            assertEquals("noul", stub.body.at("/questions/urgent/type").asText());
            assertEquals("jev-latest", stub.body.path("model").asText());
        }
    }

    @Test void baseUrlStillAppendsOriginalTypeSafePath() throws Exception {
        Stub stub = new Stub(response());
        try (var client = TypeSafeJevClient.builder().apiKey("test-key")
                .baseUrl(URI.create("https://proxy.example/prefix/")).transport(stub).build()) {
            client.evaluate("state", route, urgent, score);
            assertEquals(URI.create("https://proxy.example/prefix/v1/systemone"), stub.uri);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"/relative", "file:///tmp/request", "https://user:password@example.com/api", "https://example.com/api#fragment", "https:///api"})
    void rejectsInvalidFullEndpoint(String value) {
        assertThrows(IllegalArgumentException.class, () -> TypeSafeJevClient.builder()
                .apiKey("test-key").endpoint(URI.create(value)).build());
    }

    @Test void rejectsConflictingUrlOptionsRegardlessOfOrder() {
        URI base = URI.create("https://proxy.example");
        URI endpoint = URI.create("https://proxy.example/custom");
        assertThrows(IllegalArgumentException.class, () -> TypeSafeJevClient.builder()
                .apiKey("test-key").baseUrl(base).endpoint(endpoint).build());
        assertThrows(IllegalArgumentException.class, () -> TypeSafeJevClient.builder()
                .apiKey("test-key").endpoint(endpoint).baseUrl(base).build());
        assertThrows(IllegalArgumentException.class, () -> TypeSafeJevClient.builder()
                .apiKey("test-key").baseUrl(URI.create("https://proxy.example?query=not-allowed")).build());
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "type", "unknown-choice", "missing-probability", "negative", "numeric-string", "score-range", "extra-answer", "usage"})
    void rejectsMalformedSuccessfulResponses(String defect) throws Exception {
        ObjectNode body = (ObjectNode) response();
        ObjectNode answers = (ObjectNode) body.path("answers");
        ObjectNode routeAnswer = (ObjectNode) answers.path("route");
        switch (defect) {
            case "missing" -> answers.remove("urgent");
            case "type" -> routeAnswer.put("type", "score");
            case "unknown-choice" -> routeAnswer.put("choice", "UNKNOWN");
            case "missing-probability" -> ((ObjectNode) routeAnswer.path("probabilities")).remove("TECHNICAL");
            case "negative" -> ((ObjectNode) routeAnswer.path("probabilities")).put("BILLING", -.2);
            case "numeric-string" -> ((ObjectNode) answers.path("urgent")).put("noul", "0.92");
            case "score-range" -> ((ObjectNode) answers.path("score")).put("score", 3);
            case "extra-answer" -> answers.putObject("extra");
            case "usage" -> ((ObjectNode) body.path("usage")).put("input_tokens", -1);
        }
        try (var client = client(new Stub(body))) {
            var error = assertThrows(JevException.class, () -> client.evaluate("state", route, urgent, score));
            assertEquals(JevException.Kind.PROTOCOL, error.kind());
        }
    }

    @Test void rejectsInvalidInputBeforeTransport() throws Exception {
        Stub stub = new Stub(response());
        try (var client = client(stub)) {
            assertThrows(IllegalArgumentException.class, () -> client.evaluate("state"));
            assertThrows(IllegalArgumentException.class, () -> client.evaluate("state", route, route));
            assertThrows(IllegalArgumentException.class, () -> client.evaluate(42, route));
            assertNull(stub.body);
        }
        assertThrows(IllegalArgumentException.class, () -> TypeSafeJevClient.builder().apiKey(" ").build());
        assertThrows(IllegalArgumentException.class, () -> TypeSafeJevClient.builder().apiKey("test").timeout(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class, () -> TypeSafeJevClient.builder().apiKey("test").baseUrl(URI.create("https://user:pass@host")).build());
        assertThrows(IllegalArgumentException.class, () -> ScoreQuestion.of("s", "Rate", List.of("Only")));
        assertThrows(IllegalArgumentException.class, () -> new NoulAnswer(Double.NaN));
    }

    @Test void cancellationAndCloseReachInjectedTransportCall() {
        Stub stub = new Stub();
        var client = client(stub);
        var result = client.evaluateAsync("state", urgent);
        assertTrue(result.cancel(true));
        assertTrue(stub.pending.isCancelled());
        stub.pending = new CompletableFuture<>();
        var next = client.evaluateAsync("state", urgent);
        client.close();
        assertTrue(stub.pending.isCancelled());
        assertEquals(JevException.Kind.CLOSED, ((JevException) assertThrows(CompletionException.class, next::join).getCause()).kind());
        assertFalse(stub.closed);
        assertEquals(JevException.Kind.CLOSED, assertThrows(JevException.class, () -> client.evaluate("state", urgent)).kind());
    }

    @Test void synchronousInterruptCancelsRequestAndPreservesInterruptFlag() throws Exception {
        Stub stub = new Stub();
        AtomicBoolean interrupted = new AtomicBoolean();
        try (var client = client(stub)) {
            Thread thread = new Thread(() -> {
                assertThrows(JevException.class, () -> client.evaluate("state", urgent));
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            thread.start();
            assertTrue(stub.started.await(2, TimeUnit.SECONDS));
            thread.interrupt();
            thread.join(2000);
            assertFalse(thread.isAlive());
            assertTrue(interrupted.get());
            assertTrue(stub.pending.isCancelled());
        }
    }

    @Test void listsModelsOverHttpAndProtectsRawResponse() throws Exception {
        var method = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var accept = new AtomicReference<String>();
        var userAgent = new AtomicReference<String>();
        var contentType = new AtomicReference<String>();
        var imagesHeader = new AtomicReference<String>();
        var longContextHeader = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            imagesHeader.set(exchange.getRequestHeaders().getFirst("TypeSafe-Danger-Zone-Images"));
            longContextHeader.set(exchange.getRequestHeaders().getFirst("TypeSafe-Danger-Zone-Long-Context"));
            byte[] payload = """
                    {"models":[{"name":"jev-latest","description":"Latest stable model","release_date":"2026-10-14"},
                               {"name":"jev-1.14.0","description":"Jev 1.14","release_date":"2026-10-14"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        try (var client = TypeSafeJevClient.builder()
                .apiKey("test-key")
                .longContext(true)
                .baseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .maxRetries(0)
                .build()) {
            TypeSafeModelList list = client.listModels();
            assertEquals("GET", method.get());
            assertEquals("Bearer test-key", authorization.get());
            assertEquals("application/json", accept.get());
            assertEquals("jev-java/0.7.0", userAgent.get());
            assertNull(contentType.get());
            assertNull(imagesHeader.get());
            assertNull(longContextHeader.get());
            assertEquals(2, list.models().size());
            assertEquals(new TypeSafeModel("jev-latest", "Latest stable model", "2026-10-14"), list.models().get(0));
            ((ObjectNode) list.rawResponse()).removeAll();
            assertEquals(2, list.rawResponse().path("models").size());
            assertThrows(UnsupportedOperationException.class, () -> list.models().clear());
        } finally {
            server.stop(0);
        }
    }

    @Test void resolvesModelsEndpointsValidatesCatalogAndPropagatesCancellation() throws Exception {
        JsonNode catalog = JSON.readTree("""
                {"models":[{"name":"jev-latest","description":"Stable","release_date":"2026-10-14"}]}
                """);
        Stub defaultStub = new Stub(catalog);
        try (var client = client(defaultStub)) {
            client.listModelsAsync().join();
            assertEquals(URI.create("https://api.typesafe.ai/v1/models"), defaultStub.uri);
        }

        Stub customBase = new Stub(catalog);
        try (var client = TypeSafeJevClient.builder().apiKey("test-key")
                .baseUrl(URI.create("https://proxy.example/root/")).transport(customBase).build()) {
            client.listModels();
            assertEquals(URI.create("https://proxy.example/root/v1/models"), customBase.uri);
        }

        Stub endpointOnly = new Stub(catalog);
        try (var client = TypeSafeJevClient.builder().apiKey("test-key")
                .endpoint(URI.create("https://proxy.example/custom/systemone")).transport(endpointOnly).build()) {
            assertThrows(IllegalStateException.class, client::listModelsAsync);
            assertNull(endpointOnly.uri);
            client.close();
            assertEquals(JevException.Kind.CLOSED,
                    ((JevException) assertThrows(CompletionException.class, () -> client.listModelsAsync().join()).getCause()).kind());
        }

        Stub explicitModels = new Stub(catalog);
        URI modelsUri = URI.create("https://proxy.example/custom/models?v=1");
        try (var client = TypeSafeJevClient.builder().apiKey("test-key")
                .endpoint(URI.create("https://proxy.example/custom/systemone"))
                .modelsEndpoint(modelsUri).transport(explicitModels).build()) {
            assertEquals(1, client.listModels().models().size());
            assertEquals(modelsUri, explicitModels.uri);
        }

        for (String badJson : List.of("[]", "{}", "{\"models\":{}}",
                "{\"models\":[{\"name\":\"\",\"description\":\"d\",\"release_date\":\"2026-10-14\"}]}",
                "{\"models\":[{\"name\":\"jev\",\"release_date\":\"2026-10-14\"}]}",
                "{\"models\":[{\"name\":\"jev\",\"description\":\"d\"}]}")) {
            try (var client = client(new Stub(JSON.readTree(badJson)))) {
                var err = assertThrows(JevException.class, client::listModels);
                assertEquals(JevException.Kind.PROTOCOL, err.kind());
            }
        }

        Stub cancelStub = new Stub();
        try (var client = client(cancelStub)) {
            var pending = client.listModelsAsync();
            assertTrue(pending.cancel(true));
            assertTrue(cancelStub.pending.isCancelled());
        }
    }

    @Test void sendsLongContextHeaderAndPerRequestOverridesIncludingWithImagesAndRecords() throws Exception {
        Stub stub = new Stub(response());
        try (var client = TypeSafeJevClient.builder().apiKey("test-key").longContext(true).transport(stub).build()) {
            client.evaluate("state", route, urgent, score);
            assertEquals("true", stub.headers.get("TypeSafe-Danger-Zone-Long-Context"));
            assertFalse(stub.headers.containsKey("TypeSafe-Danger-Zone-Images"));

            var disableOverride = TypeSafeRequestOptions.builder().longContext(false).model("jev-1.14.0").build();
            var typed = client.evaluate("state", disableOverride, MappedTicket.class);
            assertEquals(Team.BILLING, typed.value().route());
            assertFalse(stub.headers.containsKey("TypeSafe-Danger-Zone-Long-Context"));
            assertEquals("jev-1.14.0", stub.body.path("model").asText());
        }

        Stub defaultOff = new Stub(response());
        var image = JevImage.fromBytes(new byte[]{1, 2, 3}, "png");
        try (var client = client(defaultOff)) {
            client.evaluate("state", route, urgent, score);
            assertFalse(defaultOff.headers.containsKey("TypeSafe-Danger-Zone-Long-Context"));

            var schema = JevSchema.of(MappedTicket.class);
            var typed = client.evaluateAsync(JevInput.withImages(Map.of("photo", image.reference()), image),
                    TypeSafeRequestOptions.withLongContext(), schema).join();
            assertEquals(Team.BILLING, typed.value().route());
            assertEquals("true", defaultOff.headers.get("TypeSafe-Danger-Zone-Long-Context"));
            assertEquals("true", defaultOff.headers.get("TypeSafe-Danger-Zone-Images"));
        }

        Stub cancelStub = new Stub();
        try (var client = client(cancelStub)) {
            var schemaFuture = client.evaluateAsync("state", TypeSafeRequestOptions.withLongContext(), MappedTicket.class);
            assertTrue(schemaFuture.cancel(true));
            assertTrue(cancelStub.pending.isCancelled());
        }

        assertThrows(IllegalArgumentException.class, () -> TypeSafeRequestOptions.builder().model(" ").build());
        assertThrows(IllegalArgumentException.class, () -> TypeSafeRequestOptions.builder().model("jev\n1").build());
        assertThrows(NullPointerException.class, () -> TypeSafeJevClient.builder().modelsEndpoint(null));
        assertThrows(IllegalArgumentException.class, () -> TypeSafeJevClient.builder()
                .modelsEndpoint(URI.create("https://user:pass@example.com/models")));
    }

    private TypeSafeJevClient client(Stub transport) { return TypeSafeJevClient.builder().apiKey("test-key").transport(transport).build(); }
    private JsonNode response() throws Exception {
        return JSON.readTree("""
                {"model":"jev-1.13.0","answers":{
                  "route":{"type":"choice","choice":"BILLING","probabilities":{"BILLING":0.85,"TECHNICAL":0.15},"confidence":0.82},
                  "urgent":{"type":"noul","noul":0.92},
                  "score":{"type":"score","score":1.6,"probabilities":{"0":0.05,"1":0.3,"2":0.65},"legend":{"0":"Low","1":"Medium","2":"High"},"confidence":0.78}
                },"usage":{"input_tokens":312,"output_tokens":48}}
                """);
    }

    static class Stub implements JevTransport {
        URI uri;
        Map<String, String> headers;
        JsonNode body;
        boolean closed;
        CompletableFuture<JsonNode> pending = new CompletableFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        Stub() {}
        Stub(JsonNode response) { pending.complete(response); }
        @Override public CompletableFuture<JsonNode> post(URI uri, Map<String, String> headers, JsonNode body) {
            this.uri = uri; this.headers = headers; this.body = body;
            started.countDown();
            return pending;
        }
        @Override public CompletableFuture<JsonNode> get(URI uri, Map<String, String> headers) {
            this.uri = uri; this.headers = headers; this.body = null;
            started.countDown();
            return pending;
        }
        @Override public void close() { closed = true; }
    }
}
