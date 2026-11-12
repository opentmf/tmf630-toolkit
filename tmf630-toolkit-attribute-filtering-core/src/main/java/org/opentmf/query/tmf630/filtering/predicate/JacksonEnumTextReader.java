package org.opentmf.query.tmf630.filtering.predicate;

import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads an enum filter value the way the service's {@link ObjectMapper} reads that enum:
 * {@code @JsonValue}, {@code @JsonProperty} on the constants, and Jackson 3's read-by-toString
 * default all apply.
 *
 * <p>The mapper's answer counts only when it is a constant that the same mapper WRITES back as
 * exactly the text. That keeps a typo a 400 under a mapper that turns an unknown text into the
 * {@code @JsonEnumDefaultValue} constant ({@code READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE}) or
 * into {@code null} ({@code READ_UNKNOWN_ENUM_VALUES_AS_NULL}), and under a case-insensitive mapper
 * it accepts only the spelling the payloads carry.
 *
 * <p>Needs {@code tools.jackson.core:jackson-databind} on the classpath (an optional dependency of
 * this module). Without the service's mapper, a default {@link JsonMapper} reads: it honours the
 * annotations, not the service's own configuration.
 */
public final class JacksonEnumTextReader implements EnumTextReader {

  private final ObjectMapper mapper;

  /** A reader using {@code mapper}, the mapper the service writes its payloads with. */
  public JacksonEnumTextReader(ObjectMapper mapper) {
    this.mapper = mapper == null ? JsonMapper.builder().build() : mapper;
  }

  @Override
  public Optional<Enum<?>> read(Class<?> enumType, String text) {
    if (enumType == null || !enumType.isEnum() || text == null) {
      return Optional.empty();
    }
    Object read;
    try {
      read = mapper.convertValue(text, enumType);
    } catch (JacksonException | IllegalArgumentException notAWireValue) {
      return Optional.empty();
    }
    return read instanceof Enum<?> constant && writesBackAs(constant, text)
        ? Optional.of(constant)
        : Optional.empty();
  }

  private boolean writesBackAs(Enum<?> constant, String text) {
    JsonNode written = mapper.valueToTree(constant);
    return written.isValueNode() && !written.isNull() && text.equals(written.asString());
  }
}
