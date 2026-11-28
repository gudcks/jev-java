package io.github.gudcks0305.jev;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gudcks0305.jev.internal.JsonSupport;

/** A yes/no judgment whose answer is a probability, not an implicit boolean threshold. */
public final class NoulQuestion extends Question<NoulAnswer> {
    private final JsonNode criteria;
    private NoulQuestion(String id, Object instructions, boolean allowNullInstructions, JsonNode criteria) {
        super(id, instructions, allowNullInstructions);
        this.criteria = criteria == null ? null : criteria.deepCopy();
    }
    public static NoulQuestion of(String id, Object instructions) {
        return new NoulQuestion(id, instructions, false, null);
    }
    /** Creates a yes/no question defined by outcome criteria without separate instructions. */
    public static NoulQuestion ofCriteria(String id, Object yes, Object no) {
        return new NoulQuestion(id, null, true, buildCriteria(false, yes, no));
    }
    public NoulQuestion withCriteria(Object yes, Object no) {
        JsonNode currentInstructions = instructions();
        return new NoulQuestion(id(), currentInstructions, true, buildCriteria(currentInstructions != null, yes, no));
    }
    private static JsonNode buildCriteria(boolean hasInstructions, Object yes, Object no) {
        var node = JsonSupport.object();
        JsonNode trueNode = JsonSupport.json(yes);
        JsonNode falseNode = JsonSupport.json(no);
        if (!hasInstructions && trueNode.isNull() && falseNode.isNull()) {
            throw new IllegalArgumentException("Noul question requires instructions or at least one criterion");
        }
        node.set("true", trueNode);
        node.set("false", falseNode);
        return node;
    }
    public JsonNode criteria() { return criteria == null ? null : criteria.deepCopy(); }
}
