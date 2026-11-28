package io.github.gudcks0305.jev.typesafe;

import io.github.gudcks0305.jev.internal.JsonSupport;
import java.util.Objects;

/**
 * Model or alias entry returned by TypeSafe's {@code GET /v1/models} endpoint.
 */
public record TypeSafeModel(String name, String description, String releaseDate) {
    public TypeSafeModel {
        name = JsonSupport.nonBlank(name, "Model name");
        description = Objects.requireNonNull(description, "Model description");
        releaseDate = Objects.requireNonNull(releaseDate, "Model release date");
    }
}
