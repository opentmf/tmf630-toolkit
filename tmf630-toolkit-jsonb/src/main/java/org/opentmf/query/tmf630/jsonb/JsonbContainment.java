package org.opentmf.query.tmf630.jsonb;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * The JSON fragment of an equality emitted as containment, {@code payload @> ?::jsonb}: the value,
 * in the JSON type the field declares, nested under the dotted path ({@code a.b = 'x'} → {@code
 * {"a":{"b":"x"}}}). Containment is type-exact, so the leaf must be what the payload holds:
 *
 * <ul>
 *   <li>String and CharSequence → a JSON string;
 *   <li>the integer types → a JSON integer, the decimal types → a JSON number (compared by value,
 *       so {@code 1.5} finds a stored {@code 1.50});
 *   <li>Boolean → a JSON boolean;
 *   <li>an Enum → what the mapper writes for the constant (see {@link #writtenEnum}).
 * </ul>
 *
 * <p>Every other type, a {@code null} value, a non-finite floating-point value and a path with a
 * positional segment have no fragment: their equality keeps the extraction.
 */
final class JsonbContainment {

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final ObjectMapper mapper;

  JsonbContainment(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** The fragment for {@code segments = value}, or empty when the equality keeps the extraction. */
  Optional<String> fragment(String[] segments, Class<?> type, Object value) {
    for (String segment : segments) {
      if (isPositional(segment)) {
        return Optional.empty();
      }
    }
    return leaf(type, value).map(leaf -> mapper.writeValueAsString(nest(segments, leaf)));
  }

  /**
   * The JSON value the mapper writes for {@code constant} when it is a scalar ({@code @JsonValue},
   * {@code @JsonProperty}, {@code toString()} or the name, as the mapper is configured), else empty.
   */
  Optional<JsonNode> writtenEnum(Enum<?> constant) {
    JsonNode written = mapper.valueToTree(constant);
    return written.isValueNode() && !written.isNull() ? Optional.of(written) : Optional.empty();
  }

  private Optional<JsonNode> leaf(Class<?> type, Object value) {
    if (type == null || value == null) {
      return Optional.empty();
    }
    if (type.isEnum()) {
      return value instanceof Enum<?> constant ? writtenEnum(constant) : Optional.empty();
    }
    if (CharSequence.class.isAssignableFrom(type)) {
      return value instanceof CharSequence text
          ? Optional.of(NODES.stringNode(text.toString()))
          : Optional.empty();
    }
    return switch (JsonbCast.forJavaType(type)) {
      case BOOLEAN ->
          value instanceof Boolean flag ? Optional.of(NODES.booleanNode(flag)) : Optional.empty();
      case BIGINT -> integral(value);
      case NUMERIC -> decimal(value);
      default -> Optional.empty();
    };
  }

  private static Optional<JsonNode> integral(Object value) {
    boolean integral =
        value instanceof Byte
            || value instanceof Short
            || value instanceof Integer
            || value instanceof Long
            || value instanceof BigInteger;
    return integral
        ? Optional.of(NODES.numberNode(new BigInteger(value.toString())))
        : Optional.empty();
  }

  private static Optional<JsonNode> decimal(Object value) {
    if (value instanceof BigDecimal number) {
      return Optional.of(NODES.numberNode(number));
    }
    boolean finite =
        (value instanceof Double d && Double.isFinite(d))
            || (value instanceof Float f && Float.isFinite(f));
    // toString() is the shortest decimal that reads back as the same value, as Jackson writes it
    return finite
        ? Optional.of(NODES.numberNode(new BigDecimal(value.toString())))
        : Optional.empty();
  }

  private static JsonNode nest(String[] segments, JsonNode leaf) {
    JsonNode node = leaf;
    for (int i = segments.length - 1; i >= 0; i--) {
      node = NODES.objectNode().set(segments[i], node);
    }
    return node;
  }

  private static boolean isPositional(String segment) {
    return !segment.isEmpty() && segment.chars().allMatch(Character::isDigit);
  }
}
