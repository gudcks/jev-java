package io.github.gudcks0305.jev.vercel;

import io.github.gudcks0305.jev.internal.AbstractJevClient;
import io.github.gudcks0305.jev.internal.ClientBuilder;
import io.github.gudcks0305.jev.internal.EvaluationCodec;
import io.github.gudcks0305.jev.internal.JsonSupport;
import io.github.gudcks0305.jev.internal.WireFormat;
import io.github.gudcks0305.jev.Question;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.Map;
import java.util.Objects;

/** Experimental adapter for Vercel AI Gateway's evaluation and decision v4 protocols. */
public final class VercelJevClient extends AbstractJevClient {
    public enum Protocol { EVALUATION_V4, DECISION_V4 }

    /** Reasoning effort forwarded on Vercel decision-model v4 requests. */
    public enum Reasoning {
        PROVIDER_DEFAULT("provider-default"),
        NONE("none"),
        MINIMAL("minimal"),
        LOW("low"),
        MEDIUM("medium"),
        HIGH("high"),
        XHIGH("xhigh"),
        MAX("max");

        private final String wireValue;

        Reasoning(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    private final Protocol protocol;
    private final Integer maxOutputTokens;
    private final Reasoning reasoning;

    private VercelJevClient(Builder builder) {
        super(builder.configuration(), builder.protocol == Protocol.DECISION_V4 ? WireFormat.VERCEL_DECISION : WireFormat.VERCEL);
        this.protocol = builder.protocol;
        this.maxOutputTokens = builder.maxOutputTokens;
        this.reasoning = builder.reasoning;
    }
    public static Builder builder() { return new Builder(); }

    @Override protected JsonNode encodeRequest(Object state, Map<String, Question<?>> questions) {
        if (state instanceof VercelDecisionInput input) {
            if (protocol != Protocol.DECISION_V4) {
                throw new IllegalArgumentException("VercelDecisionInput requires decision-model v4");
            }
            ObjectNode body = EvaluationCodec.request("state parts", questions, configuredModel(), WireFormat.VERCEL_DECISION);
            body.set("stateParts", input.stateParts());
            return checked(applyDecisionOptions(body));
        }
        JsonNode body = super.encodeRequest(state, questions);
        if (protocol != Protocol.DECISION_V4) {
            return body;
        }
        return checked(applyDecisionOptions((ObjectNode) body));
    }

    private ObjectNode applyDecisionOptions(ObjectNode body) {
        if (maxOutputTokens != null) {
            body.put("maxOutputTokens", maxOutputTokens);
        }
        if (reasoning != null) {
            body.put("reasoning", reasoning.wireValue());
        }
        return body;
    }

    private static JsonNode checked(JsonNode body) {
        if (JsonSupport.utf8Bytes(body).length > 32 * 1024 * 1024) {
            throw new IllegalArgumentException("Vercel decision request body exceeds 32 MiB");
        }
        return body;
    }

    public static final class Builder extends ClientBuilder<Builder> {
        private Protocol protocol = Protocol.EVALUATION_V4;
        private Integer maxOutputTokens;
        private Reasoning reasoning;

        private Builder() {}
        public Builder protocol(Protocol protocol) { this.protocol = Objects.requireNonNull(protocol, "protocol"); return this; }
        /** Optional output token limit for decision-model v4 requests. */
        public Builder maxOutputTokens(int maxOutputTokens) {
            if (maxOutputTokens <= 0) throw new IllegalArgumentException("maxOutputTokens must be positive");
            this.maxOutputTokens = maxOutputTokens;
            return this;
        }
        /** Optional reasoning effort for decision-model v4 requests. */
        public Builder reasoning(Reasoning reasoning) {
            this.reasoning = Objects.requireNonNull(reasoning, "reasoning");
            return this;
        }
        @Override protected Builder self() { return this; }
        private Config configuration() {
            return configure("AI_GATEWAY_API_KEY", "typesafe-ai/jev", URI.create("https://ai-gateway.vercel.sh"),
                    protocol == Protocol.DECISION_V4 ? "v4/ai/decision-model" : "v4/ai/evaluation-model");
        }
        public VercelJevClient build() {
            if (protocol != Protocol.DECISION_V4 && (maxOutputTokens != null || reasoning != null)) {
                throw new IllegalArgumentException("maxOutputTokens and reasoning require decision-model v4");
            }
            return new VercelJevClient(this);
        }
    }
}
