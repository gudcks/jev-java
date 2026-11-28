package io.github.gudcks0305.jev.typesafe;

import io.github.gudcks0305.jev.internal.JsonSupport;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-request overrides for {@link TypeSafeJevClient}. Unset options inherit the client defaults.
 */
public final class TypeSafeRequestOptions {
    private static final TypeSafeRequestOptions DEFAULTS = new TypeSafeRequestOptions(null, null);
    private static final TypeSafeRequestOptions LONG_CONTEXT = new TypeSafeRequestOptions(Boolean.TRUE, null);

    private final Boolean longContext;
    private final String model;

    private TypeSafeRequestOptions(Boolean longContext, String model) {
        this.longContext = longContext;
        this.model = model == null ? null : validateModel(model);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static TypeSafeRequestOptions defaults() {
        return DEFAULTS;
    }

    public static TypeSafeRequestOptions withLongContext() {
        return LONG_CONTEXT;
    }

    /** Optional override for {@code TypeSafe-Danger-Zone-Long-Context: true}. */
    public Optional<Boolean> longContext() {
        return Optional.ofNullable(longContext);
    }

    /** Optional per-request model identifier overriding the client default. */
    public Optional<String> model() {
        return Optional.ofNullable(model);
    }

    private static String validateModel(String model) {
        String value = JsonSupport.nonBlank(model, "Model");
        if (value.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("Invalid model format");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || (other instanceof TypeSafeRequestOptions that
                && Objects.equals(longContext, that.longContext)
                && Objects.equals(model, that.model));
    }

    @Override
    public int hashCode() {
        return Objects.hash(longContext, model);
    }

    @Override
    public String toString() {
        return "TypeSafeRequestOptions[longContext=" + longContext + ", model=" + model + "]";
    }

    public static final class Builder {
        private Boolean longContext;
        private String model;

        private Builder() {}

        public Builder longContext(boolean longContext) {
            this.longContext = longContext;
            return this;
        }

        public Builder model(String model) {
            this.model = Objects.requireNonNull(model, "model");
            return this;
        }

        public TypeSafeRequestOptions build() {
            return new TypeSafeRequestOptions(longContext, model);
        }
    }
}
