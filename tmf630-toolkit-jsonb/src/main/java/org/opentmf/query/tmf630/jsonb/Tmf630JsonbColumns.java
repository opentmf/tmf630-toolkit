package org.opentmf.query.tmf630.jsonb;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fills the {@link Tmf630JsonbColumn declared columns} of a row entity from its domain object, so a
 * service that writes the row through JPA cannot forget one (since 3.6.0, ledger #4256). Call it
 * before each save, after the payload is set:
 *
 * <pre>{@code
 * row.setPayload(mapper.writeValueAsString(message));
 * Tmf630JsonbColumns.copy(message, row, mapper);
 * repository.save(row);
 * }</pre>
 *
 * <p>A hand-written SQL update of the payload ({@code jsonb_set}) is not a JPA save: it sets the
 * column in the same statement ({@code UPDATE t SET payload = jsonb_set(…), sent_at = ? WHERE …}).
 * {@link Tmf630JsonbColumnCheck#mismatches} finds a row where the two differ.
 */
public final class Tmf630JsonbColumns {

  private static final ObjectMapper DEFAULT_MAPPER = JsonMapper.builder().build();
  private static final Map<Class<?>, JsonbEntityMetadata> METADATA = new ConcurrentHashMap<>();

  private Tmf630JsonbColumns() {}

  /** As {@link #copy(Object, Object, ObjectMapper)} with a default mapper for enums. */
  public static <R> R copy(Object domainObject, R row) {
    return copy(domainObject, row, DEFAULT_MAPPER);
  }

  /**
   * Sets every declared field of {@code row} to the value at its path in {@code domainObject} (a
   * {@code null} on the way gives {@code null}). An enum copied into a {@code String} column is
   * written as {@code mapper} writes it, which is how the payload holds it.
   *
   * @return {@code row}
   */
  public static <R> R copy(Object domainObject, R row, ObjectMapper mapper) {
    JsonbEntityMetadata metadata = METADATA.computeIfAbsent(row.getClass(), JsonbEntityMetadata::of);
    for (JsonbDeclaredColumn declared : metadata.declaredColumns()) {
      Object value = read(domainObject, declared.path());
      if (value instanceof Enum<?> constant && declared.type() == String.class) {
        value = text(constant, mapper);
      }
      write(row, declared.rowField(), value);
    }
    return row;
  }

  private static Object read(Object domainObject, String path) {
    Object current = domainObject;
    for (String segment : path.split("\\.")) {
      if (current == null) {
        return null;
      }
      Optional<Field> field = JsonbEntityMetadata.findField(current.getClass(), segment);
      if (field.isEmpty()) {
        throw new Tmf630JsonbConfigurationException(
            "No field '" + segment + "' of path '" + path + "' on " + current.getClass().getName());
      }
      current = get(field.get(), current);
    }
    return current;
  }

  private static Object get(Field field, Object target) {
    try {
      field.setAccessible(true);
      return field.get(target);
    } catch (IllegalAccessException e) {
      throw new Tmf630JsonbConfigurationException("Cannot read " + field + ": " + e.getMessage());
    }
  }

  private static void write(Object row, String fieldName, Object value) {
    Field field =
        JsonbEntityMetadata.findField(row.getClass(), fieldName)
            .orElseThrow(() -> new Tmf630JsonbConfigurationException("No field " + fieldName));
    try {
      field.setAccessible(true);
      field.set(row, value);
    } catch (IllegalAccessException e) {
      throw new Tmf630JsonbConfigurationException("Cannot write " + field + ": " + e.getMessage());
    }
  }

  private static String text(Enum<?> constant, ObjectMapper mapper) {
    JsonNode written = mapper.valueToTree(constant);
    return written.isString() ? written.asString() : constant.name();
  }
}
