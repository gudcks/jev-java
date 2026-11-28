package io.github.gudcks0305.jev.typesafe;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * Immutable model catalog returned by {@link TypeSafeJevClient#listModels()}.
 */
public record TypeSafeModelList(List<TypeSafeModel> models, JsonNode rawResponse) {
    public TypeSafeModelList {
        models = List.copyOf(Objects.requireNonNull(models, "models"));
        rawResponse = Objects.requireNonNull(rawResponse, "rawResponse").deepCopy();
    }

    @Override
    public JsonNode rawResponse() {
        return rawResponse.deepCopy();
    }

    @Override
    public String toString() {
        return "TypeSafeModelList[models=" + models.size() + "]";
    }
}
